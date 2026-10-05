package ml.oracle.importer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import ml.oracle.infrastructure.Neo4jService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Walks a repository, parses every {@code .java} file and writes the extracted
 * structure into the knowledge graph, connecting it to the existing git graph:
 *
 * <pre>
 *   Package -[:CONTAINS]-> Type -[:ANNOTATED_WITH]-> Annotation
 *                            |
 *                       [:HAS_METHOD]
 *                            v
 *                          Method -[:ANNOTATED_WITH]-> Annotation
 * </pre>
 *
 * In addition to declarations, the importer records the relationships between
 * code elements that power impact analysis:
 *
 * <pre>
 *   Type   -[:EXTENDS]->    Type          (super-class)
 *   Type   -[:IMPLEMENTS]-> Type          (implemented interface)
 *   Type   -[:IMPORTS]->    Type          (imported / referenced types)
 *   Type   -[:HAS_FIELD]->  Field -[:OF_TYPE]-> Type
 *   Method -[:RETURNS]->    Type
 *   Method -[:HAS_PARAM]->  Type
 *   Method -[:CALLS]->      Method         (best-effort, resolved call graph)
 * </pre>
 *
 * Richer node properties are stored too: {@code Type.isAbstract},
 * {@code Method.visibility / isStatic / isAbstract / lineStart / lineEnd} and
 * {@code SourceFile.loc}.
 *
 * Types are also matched to the {@code SourceFile} nodes created by the git
 * import via the repo-relative file path
 * ({@code SourceFile -[:DEFINES]-> Type}), so the Java structure is "connected
 * via files" to commits and developers.
 *
 * <p>A JavaParser {@link JavaSymbolSolver} (reflection for the JDK plus the
 * repository's own sources) is used to resolve annotations to their fully
 * qualified names, so a single {@code Annotation} node is shared across the
 * whole graph regardless of which repository it appears in. When an annotation
 * cannot be resolved (e.g. a third-party library that is not on the classpath)
 * the importer falls back to the file's imports and finally the simple name.
 *
 * <p>The import is idempotent (everything uses MERGE backed by uniqueness
 * constraints), so only new nodes/relationships are created.
 */
public class JavaImporter implements Importer {

    private static final Logger logger = LoggerFactory.getLogger(JavaImporter.class);

    private final Path repositoryRoot;
    private final JavaParser parser;

    // Method CALLS edges are deferred to a second pass: a callee may live in a
    // file that has not been parsed yet, so its Method node may not exist while
    // the caller is being processed. Keyed by caller method id -> list of
    // {type,name} callee descriptors.
    private final Map<String, List<Map<String, Object>>> pendingCalls = new HashMap<>();

    // Map of file path -> list of {id, fqn, lineStart, lineEnd} for methods and types
    private final Map<String, List<Map<String, Object>>> codeElementsMap = new HashMap<>();

    public JavaImporter(String repositoryPath) {

        this.repositoryRoot = Paths.get(repositoryPath).toAbsolutePath().normalize();

        // Resolve types via the JDK (reflection) and the repository's own
        // sources so annotation FQNs can be determined. Each Maven-style source
        // root gets its own solver: a solver rooted at the repo root would never
        // resolve anything, because package paths start below src/main/java.
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        for (Path sourceRoot : findSourceRoots(repositoryRoot)) {
            typeSolver.add(new JavaParserTypeSolver(sourceRoot));
        }

        ParserConfiguration configuration = new ParserConfiguration()
                // Parse modern Java (records, text blocks, sealed types, etc.).
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));

        this.parser = new JavaParser(configuration);
    }

    /**
     * Finds the repository's source roots (directories ending in
     * {@code src/main/java} or {@code src/test/java}, in any module). Falls
     * back to the repository root for repos with a non-standard layout.
     */
    private static List<Path> findSourceRoots(Path root) {
        List<Path> roots = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isDirectory)
                    .filter(p -> p.endsWith(Path.of("src", "main", "java"))
                            || p.endsWith(Path.of("src", "test", "java")))
                    .forEach(roots::add);
        } catch (IOException e) {
            logger.warn("Could not scan for source roots ({}), falling back to repo root", e.getMessage());
        }
        if (roots.isEmpty()) {
            roots.add(root);
        }
        return roots;
    }

    @Override
    public void importRepository(Neo4jService neo4j) throws IOException {

        // First pass: types, methods, fields, inheritance and usage edges.
        try (Stream<Path> paths = Files.walk(repositoryRoot)) {

            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> processFile(p, neo4j));
        }

        // Second pass: CALLS edges, now that every Method node exists.
        pendingCalls.forEach(neo4j::linkMethodCalls);
        pendingCalls.clear();

        // Third pass: Link commits to code elements by correlating Git diffs with AST ranges
        linkCommitsToCode(neo4j);
    }

    private void linkCommitsToCode(Neo4jService neo4j) {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repositoryRoot.toFile())) {
            org.eclipse.jgit.lib.Repository repo = git.getRepository();
            try (org.eclipse.jgit.revwalk.RevWalk revWalk = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
                org.eclipse.jgit.lib.ObjectId head = repo.resolve("HEAD");
                if (head == null) return;
                revWalk.markStart(revWalk.parseCommit(head));

                // Rename chain (old path -> current path), same trick as
                // GitImporter: history is walked newest-first, so canonicalising
                // lets commits from before a rename still find the file's code
                // elements (which are keyed by present-day path).
                Map<String, String> renames = new HashMap<>();

                for (org.eclipse.jgit.revwalk.RevCommit commit : revWalk) {
                    String hash = commit.getName();
                    try (org.eclipse.jgit.diff.DiffFormatter df = new org.eclipse.jgit.diff.DiffFormatter(org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE)) {
                        df.setRepository(repo);
                        df.setDetectRenames(true);

                        org.eclipse.jgit.treewalk.AbstractTreeIterator oldTree = commit.getParentCount() > 0
                                ? prepareTreeParser(repo, commit.getParent(0))
                                : new org.eclipse.jgit.treewalk.EmptyTreeIterator();
                        org.eclipse.jgit.treewalk.AbstractTreeIterator newTree = prepareTreeParser(repo, commit);

                        List<org.eclipse.jgit.diff.DiffEntry> diffs = df.scan(oldTree, newTree);
                        for (org.eclipse.jgit.diff.DiffEntry diff : diffs) {
                            String newPath = diff.getNewPath();
                            if (newPath == null || newPath.equals(org.eclipse.jgit.diff.DiffEntry.DEV_NULL)) continue;
                            if (diff.getChangeType() == org.eclipse.jgit.diff.DiffEntry.ChangeType.RENAME) {
                                renames.put(diff.getOldPath(), canonicalPath(newPath, renames));
                            }
                            String filePath = canonicalPath(newPath, renames);
                            if (!filePath.endsWith(".java") || !codeElementsMap.containsKey(filePath)) continue;

                            List<Map<String, Object>> touchedElements = new ArrayList<>();
                            List<Map<String, Object>> elements = codeElementsMap.get(filePath);

                            org.eclipse.jgit.patch.FileHeader fileHeader = df.toFileHeader(diff);
                            for (org.eclipse.jgit.diff.Edit edit : fileHeader.toEditList()) {
                                int begin = edit.getBeginB() + 1; // JGit is 0-based, JavaParser 1-based
                                int end = edit.getEndB();

                                for (Map<String, Object> el : elements) {
                                    int start = (int) el.get("lineStart");
                                    int stop = (int) el.get("lineEnd");
                                    // Overlap check
                                    if (Math.max(begin, start) <= Math.min(end, stop)) {
                                        touchedElements.add(el);
                                    }
                                }
                            }
                            if (!touchedElements.isEmpty()) {
                                neo4j.linkCommitToCodeElements(hash, filePath, touchedElements);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error linking commits to code", e);
        }
    }

    /**
     * Follows the rename chain to a file's current path (see
     * {@code GitImporter#canonicalPath}); the walk is newest-first, so a path
     * renamed later already has its mapping recorded.
     */
    private static String canonicalPath(String path, Map<String, String> renames) {
        String current = path;
        Set<String> seen = new HashSet<>();
        while (renames.containsKey(current) && seen.add(current)) {
            current = renames.get(current);
        }
        return current;
    }

    private org.eclipse.jgit.treewalk.AbstractTreeIterator prepareTreeParser(org.eclipse.jgit.lib.Repository repo, org.eclipse.jgit.revwalk.RevCommit commit) throws Exception {
        try (org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
            org.eclipse.jgit.revwalk.RevTree tree = walk.parseTree(commit.getTree().getId());
            org.eclipse.jgit.treewalk.CanonicalTreeParser parser = new org.eclipse.jgit.treewalk.CanonicalTreeParser();
            try (org.eclipse.jgit.lib.ObjectReader reader = repo.newObjectReader()) {
                parser.reset(reader, tree.getId());
            }
            walk.dispose();
            return parser;
        }
    }

    @Override
    public String name() {
        return "JavaImporter";
    }

    @Override
    public void close() {
        // Shared Neo4jService is managed by caller
    }

    private void processFile(Path file, Neo4jService neo4j) {

        String relativePath = repositoryRoot.relativize(file).toString();

        CompilationUnit cu;
        try {
            cu = parser.parse(file).getResult().orElseThrow();
        } catch (Exception e) {
            // Skip files that fail to parse (e.g. invalid/partial sources) but
            // keep importing the rest of the repository.
            logger.warn("Skipping {} ({})", relativePath, e.getMessage());
            return;
        }

        // Record lines of code on the SourceFile node.
        int loc = cu.getRange().map(r -> r.end.line).orElse(0);
        neo4j.setSourceFileLoc(relativePath, loc);

        cu.findAll(TypeDeclaration.class).forEach(type -> processType(relativePath, type, neo4j));
    }

    /** Imported (non-static, non-asterisk) types become IMPORTS edges. */
    private void recordImports(String typeFqn, CompilationUnit cu, Neo4jService neo4j) {

        List<Map<String, Object>> used = new ArrayList<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk() || imp.isStatic()) {
                continue;
            }
            String fqn = imp.getNameAsString();
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            used.add(Map.of("fqn", fqn, "name", simple));
        }
        neo4j.linkTypeUses(typeFqn, used);
    }

    private void processType(String relativePath, TypeDeclaration<?> type, Neo4jService neo4j) {

        String fqn = type.getFullyQualifiedName().orElse(type.getNameAsString());
        String name = type.getNameAsString();
        String kind = kindOf(type);
        boolean isAbstract = type instanceof ClassOrInterfaceDeclaration coid && coid.isAbstract();

        int typeStart = type.getBegin().map(p -> p.line).orElse(0);
        int typeEnd = type.getEnd().map(p -> p.line).orElse(0);

        neo4j.createType(relativePath, fqn, name, kind, isAbstract, typeStart, typeEnd);

        codeElementsMap.computeIfAbsent(relativePath, k -> new ArrayList<>())
                .add(Map.of("fqn", fqn, "lineStart", typeStart, "lineEnd", typeEnd));

        // Package -[:CONTAINS]-> Type
        type.findCompilationUnit()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .ifPresent(pd -> neo4j.linkPackageToType(pd.getNameAsString(), fqn));

        // Type -[:ANNOTATED_WITH]-> Annotation
        for (AnnotationExpr annotation : type.getAnnotations()) {
            neo4j.annotateType(fqn, resolveFqn(annotation), annotation.getName().getIdentifier());
        }

        // Type -[:EXTENDS|IMPLEMENTS]-> Type and Type -[:IMPORTS]-> Type (imports).
        if (type instanceof ClassOrInterfaceDeclaration coid) {
            for (ClassOrInterfaceType ext : coid.getExtendedTypes()) {
                linkSupertype(fqn, ext, "EXTENDS", neo4j);
            }
            for (ClassOrInterfaceType impl : coid.getImplementedTypes()) {
                linkSupertype(fqn, impl, "IMPLEMENTS", neo4j);
            }
        }
        type.findCompilationUnit().ifPresent(cu -> recordImports(fqn, cu, neo4j));

        // Type -[:HAS_FIELD]-> Field -[:OF_TYPE]-> Type
        for (FieldDeclaration field : type.getFields()) {
            String fieldTypeFqn = resolveTypeFqn(field.getElementType());
            String fieldTypeName = field.getElementType().asString();
            for (VariableDeclarator var : field.getVariables()) {
                neo4j.createField(fqn, var.getNameAsString(), fieldTypeFqn, fieldTypeName);
            }
        }

        for (Object member : type.getMethods()) {
            processCallable(relativePath, fqn, (MethodDeclaration) member, neo4j);
        }

        for (Object member : type.getConstructors()) {
            processCallable(relativePath, fqn, (ConstructorDeclaration) member, neo4j);
        }
    }

    private void linkSupertype(String typeFqn, ClassOrInterfaceType superType, String relationship, Neo4jService neo4j) {
        String superFqn = resolveTypeFqn(superType);
        neo4j.linkSupertype(typeFqn, superFqn, superType.getNameAsString(), relationship);
    }

    private void processCallable(String relativePath, String typeFqn, CallableDeclaration<?> callable, Neo4jService neo4j) {

        String signature = callable.getSignature().asString();
        String methodId = typeFqn + "#" + signature;

        String visibility = callable.getAccessSpecifier().name(); // PUBLIC/PROTECTED/PRIVATE/NONE
        boolean isStatic = callable.isStatic();
        boolean isAbstract = callable instanceof MethodDeclaration md && md.isAbstract();
        int lineStart = callable.getBegin().map(p -> p.line).orElse(0);
        int lineEnd = callable.getEnd().map(p -> p.line).orElse(0);

        neo4j.createMethod(typeFqn, signature, callable.getNameAsString(),
                visibility, isStatic, isAbstract, lineStart, lineEnd,
                callable.getParameters().size());

        codeElementsMap.computeIfAbsent(relativePath, k -> new ArrayList<>())
                .add(Map.of("id", methodId, "lineStart", lineStart, "lineEnd", lineEnd));

        // Method -[:ANNOTATED_WITH]-> Annotation
        for (AnnotationExpr annotation : callable.getAnnotations()) {
            neo4j.annotateMethod(methodId, resolveFqn(annotation), annotation.getName().getIdentifier());
        }

        // Method -[:RETURNS]-> Type and Method -[:HAS_PARAM]-> Type
        String returnFqn = null;
        String returnName = null;
        if (callable instanceof MethodDeclaration md) {
            returnName = md.getType().asString();
            returnFqn = md.getType().isVoidType() ? "void" : resolveTypeFqn(md.getType());
        }
        List<Map<String, Object>> params = new ArrayList<>();
        for (Parameter param : callable.getParameters()) {
            params.add(Map.of(
                    "fqn", resolveTypeFqn(param.getType()),
                    "name", param.getType().asString()));
        }
        neo4j.linkMethodTypes(methodId, returnFqn, returnName, params);

        // Collect Method -[:CALLS]-> Method targets for the deferred second pass.
        List<Map<String, Object>> calls = new ArrayList<>();
        callable.findAll(MethodCallExpr.class).forEach(call -> {
            try {
                var resolved = call.resolve();
                calls.add(Map.of(
                        "type", resolved.declaringType().getQualifiedName(),
                        "name", resolved.getName(),
                        "params", resolved.getNumberOfParams()));
            } catch (Exception ignored) {
                // Unresolvable call (e.g. external/unknown target): skip it.
            }
        });
        if (!calls.isEmpty()) {
            pendingCalls.computeIfAbsent(methodId, k -> new ArrayList<>()).addAll(calls);
        }
    }

    /**
     * Resolves an AST type to its fully qualified name via the symbol solver,
     * falling back to the name as written in source when it cannot be resolved.
     */
    private String resolveTypeFqn(com.github.javaparser.ast.type.Type astType) {
        try {
            var resolved = astType.resolve();
            if (resolved.isReferenceType()) {
                return resolved.asReferenceType().getQualifiedName();
            }
            return resolved.describe();
        } catch (Exception ignored) {
            return astType.asString();
        }
    }

    /**
     * Resolves an annotation to its fully qualified name. Tries the symbol
     * solver first, then the file's imports, then falls back to the name as
     * written in the source.
     */
    private String resolveFqn(AnnotationExpr annotation) {

        try {
            return annotation.resolve().getQualifiedName();
        } catch (Exception ignored) {
            // Symbol could not be resolved (e.g. library not on the classpath).
        }

        String name = annotation.getNameAsString();

        // Already fully qualified in the source.
        if (name.contains(".")) {
            return name;
        }

        // Expand via an explicit import (e.g. "@Service" -> the imported FQN).
        String simpleName = annotation.getName().getIdentifier();
        for (ImportDeclaration imp : annotation.findCompilationUnit()
                .map(CompilationUnit::getImports)
                .orElseGet(com.github.javaparser.ast.NodeList::new)) {

            if (!imp.isAsterisk() && imp.getNameAsString().endsWith("." + simpleName)) {
                return imp.getNameAsString();
            }
        }

        // Unknown package: fall back to the simple name.
        return name;
    }

    private String kindOf(TypeDeclaration<?> type) {

        if (type instanceof ClassOrInterfaceDeclaration coid) {
            return coid.isInterface() ? "INTERFACE" : "CLASS";
        }
        if (type.isEnumDeclaration()) {
            return "ENUM";
        }
        if (type.isRecordDeclaration()) {
            return "RECORD";
        }
        if (type.isAnnotationDeclaration()) {
            return "ANNOTATION";
        }
        return "TYPE";
    }
}
