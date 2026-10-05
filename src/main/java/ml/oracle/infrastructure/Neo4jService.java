package ml.oracle.infrastructure;

import org.neo4j.driver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

public class Neo4jService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(Neo4jService.class);

    private final Driver driver;

    public Neo4jService(
            String uri,
            String username,
            String password) {

           AuthToken authToken = AuthTokens.basic(username, password);

        driver =
                GraphDatabase.driver(
                        uri,
                        authToken);

        setupSchema();
    }

    /**
     * Creates uniqueness constraints (idempotent) so that Developer, Commit and
     * SourceFile nodes can never be duplicated. The constraints also back the
     * lookups done by MERGE, making the import fast.
     */
    private void setupSchema() {

        try (Session session = driver.session()) {

            session.executeWrite(tx -> {

                tx.run("CREATE CONSTRAINT developer_email IF NOT EXISTS " +
                        "FOR (d:Developer) REQUIRE d.email IS UNIQUE");

                tx.run("CREATE CONSTRAINT commit_hash IF NOT EXISTS " +
                        "FOR (c:Commit) REQUIRE c.hash IS UNIQUE");

                tx.run("CREATE CONSTRAINT sourcefile_path IF NOT EXISTS " +
                        "FOR (f:SourceFile) REQUIRE f.path IS UNIQUE");

                tx.run("CREATE CONSTRAINT type_fqn IF NOT EXISTS " +
                        "FOR (t:Type) REQUIRE t.fqn IS UNIQUE");

                tx.run("CREATE CONSTRAINT method_id IF NOT EXISTS " +
                        "FOR (m:Method) REQUIRE m.id IS UNIQUE");

                tx.run("CREATE CONSTRAINT package_name IF NOT EXISTS " +
                        "FOR (p:Package) REQUIRE p.name IS UNIQUE");

                tx.run("CREATE CONSTRAINT annotation_fqn IF NOT EXISTS " +
                        "FOR (a:Annotation) REQUIRE a.fqn IS UNIQUE");

                tx.run("CREATE CONSTRAINT field_id IF NOT EXISTS " +
                        "FOR (f:Field) REQUIRE f.id IS UNIQUE");

                tx.run("CREATE CONSTRAINT tag_name IF NOT EXISTS " +
                        "FOR (t:Tag) REQUIRE t.name IS UNIQUE");

                // Maven schema
                tx.run("CREATE CONSTRAINT repository_name IF NOT EXISTS " +
                        "FOR (r:Repository) REQUIRE r.name IS UNIQUE");
                tx.run("CREATE CONSTRAINT module_id IF NOT EXISTS " +
                        "FOR (m:Module) REQUIRE m.id IS UNIQUE");
                tx.run("CREATE CONSTRAINT external_lib_name IF NOT EXISTS " +
                        "FOR (l:ExternalLib) REQUIRE l.name IS UNIQUE");
                tx.run("CREATE CONSTRAINT plugin_id IF NOT EXISTS " +
                        "FOR (p:Plugin) REQUIRE p.id IS UNIQUE");
                tx.run("CREATE CONSTRAINT bom_name IF NOT EXISTS " +
                        "FOR (b:BillOfMaterials) REQUIRE b.name IS UNIQUE");
                tx.run("CREATE CONSTRAINT artifact_id IF NOT EXISTS " +
                        "FOR (a:Artifact) REQUIRE a.id IS UNIQUE");
                tx.run("CREATE CONSTRAINT property_id IF NOT EXISTS " +
                        "FOR (p:Property) REQUIRE p.id IS UNIQUE");

                // Jira schema
                tx.run("CREATE CONSTRAINT issue_key IF NOT EXISTS " +
                        "FOR (i:Issue) REQUIRE i.key IS UNIQUE");
                tx.run("CREATE CONSTRAINT merge_request_id IF NOT EXISTS " +
                        "FOR (mr:MergeRequest) REQUIRE mr.id IS UNIQUE");

                return null;
            });
        }
    }

    /**
     * @return true if a Commit node with the given hash already exists in the graph.
     */
    public boolean commitExists(String hash) {

        try (Session session = driver.session()) {

            return session.executeRead(tx -> {

                Result result = tx.run("""
                    MATCH (c:Commit {hash:$hash})
                    RETURN count(c) > 0 AS present
                """, Values.parameters("hash", hash));

                return result.single().get("present").asBoolean();
            });
        }
    }

    /** Records the line count (lines of code) of a SourceFile. Idempotent. */
    public void setSourceFileLoc(String path, int loc) {

        try (Session session = driver.session()) {

            session.executeWrite(tx -> {

                tx.run("""
                    MERGE (f:SourceFile {path:$path})
                    SET f.loc=$loc
                """, Values.parameters("path", path, "loc", loc));

                return null;
            });
        }
    }

    /**
     * Creates every SourceFile touched by a commit and links it to the commit in
     * a single round-trip via {@code UNWIND}, instead of two statements per file.
     * Idempotent.
     */
    public void linkCommitToSourceFiles(String commitHash, java.util.List<String> filePaths) {

        if (filePaths == null || filePaths.isEmpty()) {
            return;
        }

        try (Session session = driver.session()) {

            session.executeWrite(tx -> {

                tx.run("""
                    MATCH (c:Commit {hash:$hash})
                    UNWIND $paths AS path
                    MERGE (f:SourceFile {path:path})
                    MERGE (c)-[:MODIFIED]->(f)
                """, Values.parameters(
                        "hash", commitHash,
                        "paths", filePaths
                ));

                return null;
            });
        }
    }

    /**
     * Creates (or updates) a Type node and links it to the SourceFile it is
     * declared in. Idempotent: re-running never creates duplicates.
     *
     * @param filePath   repo-relative path of the source file (matches SourceFile.path)
     * @param fqn        fully qualified name of the type (unique key)
     * @param name       simple name of the type
     * @param kind       CLASS, INTERFACE, ENUM, ANNOTATION or RECORD
     * @param isAbstract whether the type is declared abstract
     * @param lineStart  first source line (or 0 if unknown)
     * @param lineEnd    last source line (or 0 if unknown)
     */
    public void createType(String filePath, String fqn, String name, String kind, boolean isAbstract,
                           int lineStart, int lineEnd) {

        try (Session session = driver.session()) {

            session.executeWrite(tx -> {

                tx.run("""
                    MERGE (f:SourceFile {path:$path})
                    MERGE (t:Type {fqn:$fqn})
                    SET t.name=$name, t.kind=$kind, t.isAbstract=$isAbstract,
                        t.lineStart=$lineStart, t.lineEnd=$lineEnd
                    MERGE (f)-[:DEFINES]->(t)
                """, Values.parameters(
                        "path", filePath,
                        "fqn", fqn,
                        "name", name,
                        "kind", kind,
                        "isAbstract", isAbstract,
                        "lineStart", lineStart,
                        "lineEnd", lineEnd
                ));

                return null;
            });
        }
    }

    /**
     * Creates (or updates) a Method node and links it to its declaring Type.
     * Idempotent: the method is keyed by "&lt;fqn&gt;#&lt;signature&gt;".
     *
     * @param visibility PUBLIC, PROTECTED, PRIVATE or PACKAGE_PRIVATE
     * @param isStatic   whether the method/constructor is static
     * @param isAbstract whether the method is abstract
     * @param lineStart  first source line (or 0 if unknown)
     * @param lineEnd    last source line (or 0 if unknown)
     * @param paramCount number of declared parameters (used to disambiguate CALLS)
     */
    public void createMethod(
            String typeFqn,
            String signature,
            String name,
            String visibility,
            boolean isStatic,
            boolean isAbstract,
            int lineStart,
            int lineEnd,
            int paramCount) {

        String id = typeFqn + "#" + signature;

        try (Session session = driver.session()) {

            session.executeWrite(tx -> {

                tx.run("""
                    MATCH (t:Type {fqn:$fqn})
                    MERGE (m:Method {id:$id})
                    SET m.name=$name,
                        m.signature=$signature,
                        m.visibility=$visibility,
                        m.isStatic=$isStatic,
                        m.isAbstract=$isAbstract,
                        m.lineStart=$lineStart,
                        m.lineEnd=$lineEnd,
                        m.paramCount=$paramCount
                    MERGE (t)-[:HAS_METHOD]->(m)
                """, Values.parameters(
                        "fqn", typeFqn,
                        "id", id,
                        "name", name,
                        "signature", signature,
                        "visibility", visibility,
                        "isStatic", isStatic,
                        "isAbstract", isAbstract,
                        "lineStart", lineStart,
                        "lineEnd", lineEnd,
                        "paramCount", paramCount
                ));

                return null;
            });
        }
    }

    /**
     * Links a Type to a super-type (class or interface) via the given
     * relationship ({@code EXTENDS} or {@code IMPLEMENTS}). The super-type node
     * is MERGEd by its fully qualified name so the edge is always created, even
     * when the super-type itself is not part of the imported sources (e.g. a
     * JDK or third-party type) — it is then enriched later if it is imported.
     */
    public void linkSupertype(String typeFqn, String superFqn, String superName, String relationship) {

        // Whitelist the relationship name to keep it out of string interpolation risk.
        String rel = "IMPLEMENTS".equals(relationship) ? "IMPLEMENTS" : "EXTENDS";

        try (Session session = driver.session()) {

            session.executeWrite(tx -> {

                tx.run("""
                    MATCH (t:Type {fqn:$fqn})
                    MERGE (s:Type {fqn:$superFqn})
                    ON CREATE SET s.name=$superName
                    MERGE (t)-[:%s]->(s)
                """.formatted(rel), Values.parameters(
                        "fqn", typeFqn,
                        "superFqn", superFqn,
                        "superName", superName
                ));

                return null;
            });
        }
    }

    /**
     * Links a Type to other Types it imports via IMPORTS. The target nodes are
     * MERGEd by fully qualified name. Batched via UNWIND.
     */
    public void linkTypeUses(String typeFqn, java.util.List<java.util.Map<String, Object>> usedTypes) {

        if (usedTypes == null || usedTypes.isEmpty()) {
            return;
        }

        try (Session session = driver.session()) {

            session.executeWrite(tx -> {

                tx.run("""
                    MATCH (t:Type {fqn:$fqn})
                    UNWIND $rows AS row
                    MERGE (u:Type {fqn:row.fqn})
                    ON CREATE SET u.name=row.name
                    MERGE (t)-[:IMPORTS]->(u)
                """, Values.parameters(
                        "fqn", typeFqn,
                        "rows", usedTypes
                ));

                return null;
            });
        }
    }

    /**
     * Creates a Field node (keyed by "&lt;typeFqn&gt;#&lt;fieldName&gt;"),
     * links it to its declaring Type via HAS_FIELD, and to the field's type via
     * OF_TYPE (the field-type node is MERGEd by fqn). Idempotent.
     */
    public void createField(
            String typeFqn,
            String fieldName,
            String fieldTypeFqn,
            String fieldTypeName) {

        String id = typeFqn + "#" + fieldName;

        try (Session session = driver.session()) {

            session.executeWrite(tx -> {

                tx.run("""
                    MATCH (t:Type {fqn:$fqn})
                    MERGE (f:Field {id:$id})
                    SET f.name=$name
                    MERGE (t)-[:HAS_FIELD]->(f)
                    MERGE (ft:Type {fqn:$ftFqn})
                    ON CREATE SET ft.name=$ftName
                    MERGE (f)-[:OF_TYPE]->(ft)
                """, Values.parameters(
                        "fqn", typeFqn,
                        "id", id,
                        "name", fieldName,
                        "ftFqn", fieldTypeFqn,
                        "ftName", fieldTypeName
                ));

                return null;
            });
        }
    }

    /**
     * Links a Method to its return type (RETURNS) and parameter types
     * (HAS_PARAM). All target type nodes are MERGEd by fqn. Batched via UNWIND.
     *
     * @param returnTypeFqn  fully qualified return type, or {@code null}/"void"
     * @param paramTypes     list of {fqn,name} maps for the parameter types
     */
    public void linkMethodTypes(
            String methodId,
            String returnTypeFqn,
            String returnTypeName,
            java.util.List<java.util.Map<String, Object>> paramTypes) {

        try (Session session = driver.session()) {

            session.executeWrite(tx -> {

                if (returnTypeFqn != null && !"void".equals(returnTypeFqn)) {
                    tx.run("""
                        MATCH (m:Method {id:$id})
                        MERGE (rt:Type {fqn:$rtFqn})
                        ON CREATE SET rt.name=$rtName
                        MERGE (m)-[:RETURNS]->(rt)
                    """, Values.parameters(
                            "id", methodId,
                            "rtFqn", returnTypeFqn,
                            "rtName", returnTypeName
                    ));
                }

                if (paramTypes != null && !paramTypes.isEmpty()) {
                    tx.run("""
                        MATCH (m:Method {id:$id})
                        UNWIND $rows AS row
                        MERGE (pt:Type {fqn:row.fqn})
                        ON CREATE SET pt.name=row.name
                        MERGE (m)-[:HAS_PARAM]->(pt)
                    """, Values.parameters(
                            "id", methodId,
                            "rows", paramTypes
                    ));
                }

                return null;
            });
        }
    }

    /**
     * Creates CALLS edges from one Method to the methods it invokes. Each row is
     * a {type,name,params} map identifying the callee's declaring Type FQN,
     * method name and parameter count; the edge is created to the declared
     * method(s) of that type matching both name and arity, so overloaded methods
     * with a different number of parameters are not falsely linked. Calls whose
     * target type is not part of the imported graph produce no edge.
     */
    public void linkMethodCalls(String callerId, java.util.List<java.util.Map<String, Object>> calls) {

        if (calls == null || calls.isEmpty()) {
            return;
        }

        try (Session session = driver.session()) {

            session.executeWrite(tx -> {

                tx.run("""
                    MATCH (caller:Method {id:$id})
                    UNWIND $rows AS row
                    MATCH (:Type {fqn:row.type})-[:HAS_METHOD]->(callee:Method {name:row.name})
                    WHERE callee.paramCount = row.params
                    MERGE (caller)-[:CALLS]->(callee)
                """, Values.parameters(
                        "id", callerId,
                        "rows", calls
                ));

                return null;
            });
        }
    }

    /**
     * Creates (or updates) a Package node and links it to a Type via CONTAINS.
     * Idempotent: the package is keyed by its fully qualified name.
     *
     * @param packageName fully qualified package name (e.g. {@code ml.git})
     * @param typeFqn     fully qualified name of the contained type
     */
    public void linkPackageToType(String packageName, String typeFqn) {

        try (Session session = driver.session()) {

            session.executeWrite(tx -> {

                tx.run("""
                    MERGE (p:Package {name:$name})
                    WITH p
                    MATCH (t:Type {fqn:$fqn})
                    MERGE (p)-[:CONTAINS]->(t)
                """, Values.parameters(
                        "name", packageName,
                        "fqn", typeFqn
                ));

                return null;
            });
        }
    }

    /**
     * Links a Type to an Annotation via ANNOTATED_WITH. The Annotation node is
     * shared across the whole graph (keyed by its fully qualified name), so a
     * single {@code @Service} node is reused by every annotated element.
     */
    public void annotateType(String typeFqn, String annotationFqn, String annotationName) {

        try (Session session = driver.session()) {

            session.executeWrite(tx -> {

                tx.run("""
                    MERGE (a:Annotation {fqn:$annFqn})
                    SET a.name=$annName
                    WITH a
                    MATCH (t:Type {fqn:$fqn})
                    MERGE (t)-[:ANNOTATED_WITH]->(a)
                """, Values.parameters(
                        "fqn", typeFqn,
                        "annFqn", annotationFqn,
                        "annName", annotationName
                ));

                return null;
            });
        }
    }

    /**
     * Links a Method to an Annotation via ANNOTATED_WITH. The Annotation node is
     * shared across the graph (keyed by its fully qualified name).
     */
    public void annotateMethod(String methodId, String annotationFqn, String annotationName) {

        try (Session session = driver.session()) {

            session.executeWrite(tx -> {

                tx.run("""
                    MERGE (a:Annotation {fqn:$annFqn})
                    SET a.name=$annName
                    WITH a
                    MATCH (m:Method {id:$id})
                    MERGE (m)-[:ANNOTATED_WITH]->(a)
                """, Values.parameters(
                        "id", methodId,
                        "annFqn", annotationFqn,
                        "annName", annotationName
                ));

                return null;
            });
        }
    }

    public void createCommit(
            String hash,
            String author,
            String email,
            String message,
            Instant timestamp,
            int insertions,
            int deletions,
            java.util.List<String> parents) {

        try (Session session = driver.session()) {

            session.executeWrite(tx -> {

                tx.run(

                        """
                        MERGE (d:Developer {email:$email})
                        SET d.name=$author

                        MERGE (c:Commit {hash:$hash})
                        SET c.message=$message,
                            c.timestamp=datetime($timestamp),
                            c.insertions=$insertions,
                            c.deletions=$deletions

                        MERGE (d)-[:AUTHORED]->(c)

                        WITH c
                        UNWIND $parents AS parentHash
                        MERGE (p:Commit {hash:parentHash})
                        MERGE (c)-[:HAS_PARENT]->(p)
                        """,

                        Values.parameters(
                                "email", email,
                                "author", author,
                                "hash", hash,
                                "message", message,
                                "timestamp", timestamp.toString(),
                                "insertions", insertions,
                                "deletions", deletions,
                                "parents", parents
                        ));

                return null;
            });
        }
    }

    public void createTag(String name, String commitHash) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MERGE (t:Tag {name:$name})
                    MERGE (c:Commit {hash:$hash})
                    MERGE (t)-[:POINTS_TO]->(c)
                """, Values.parameters("name", name, "hash", commitHash));
                return null;
            });
        }
    }

    public void updateSourceFileChurn(String path, Instant lastModified) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                // History is walked newest-first, so only advance the timestamp:
                // an unconditional SET would leave the oldest commit's date behind.
                tx.run("""
                    MERGE (f:SourceFile {path:$path})
                    SET f.changeCount = coalesce(f.changeCount, 0) + 1,
                        f.lastModified = CASE
                            WHEN f.lastModified IS NULL OR f.lastModified < datetime($lastModified)
                            THEN datetime($lastModified)
                            ELSE f.lastModified END
                """, Values.parameters("path", path, "lastModified", lastModified.toString()));
                return null;
            });
        }
    }

    /**
     * Sets {@code existsAtHead} on every SourceFile: true for paths present in
     * the HEAD tree, false for files that have since been deleted (or renamed
     * away), so churn/hotspot queries can exclude dead files. Run after the
     * commit walk; idempotent.
     */
    public void markSourceFilesAtHead(java.util.List<String> headPaths) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (f:SourceFile) SET f.existsAtHead = false");
                tx.run("""
                    UNWIND $paths AS path
                    MERGE (f:SourceFile {path:path})
                    SET f.existsAtHead = true
                """, Values.parameters("paths", headPaths));
                return null;
            });
        }
    }

    public void addCoChangeCoupling(java.util.List<java.util.Map<String, Object>> couplings) {
        if (couplings == null || couplings.isEmpty()) return;
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    UNWIND $couplings AS row
                    MATCH (f1:SourceFile {path:row.file1})
                    MATCH (f2:SourceFile {path:row.file2})
                    MERGE (f1)-[r:CO_CHANGED_WITH]-(f2)
                    SET r.count = coalesce(r.count, 0) + 1
                """, Values.parameters("couplings", couplings));
                return null;
            });
        }
    }

    public void linkTypeToLibrary(String typeFqn, String libraryName) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                // MATCH the ExternalLib (must already exist from dependency import)
                tx.run("""
                    MATCH (t:Type {fqn: $typeFqn})
                    MATCH (l:ExternalLib {name: $libName})
                    MERGE (t)-[:USES_LIBRARY]->(l)
                """, Values.parameters("typeFqn", typeFqn, "libName", libraryName));
                return null;
            });
        }
    }

    // ── Maven schema ──────────────────────────────────────────────────────────

    public void mergeRepository(String name, String url, String branch) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MERGE (r:Repository {name: $name})
                    SET r.url = $url, r.branch = $branch
                """, Values.parameters("name", name, "url", url, "branch", branch));
                return null;
            });
        }
    }

    public void linkRepositoryModule(String repoName, String moduleId) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (r:Repository {name: $repoName})
                    MATCH (m:Module {id: $moduleId})
                    MERGE (r)-[:CONTAINS]->(m)
                """, Values.parameters("repoName", repoName, "moduleId", moduleId));
                return null;
            });
        }
    }

    public void createModule(String groupId, String artifactId, String version, String packaging, String path) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MERGE (m:Module {id: $id})
                    SET m.groupId = $groupId, m.artifactId = $artifactId,
                        m.version = $version, m.packaging = $packaging, m.path = $path
                """, Values.parameters(
                        "id", groupId + ":" + artifactId,
                        "groupId", groupId,
                        "artifactId", artifactId,
                        "version", version,
                        "packaging", packaging,
                        "path", path));
                return null;
            });
        }
    }

    public void linkParentModule(String childId, String parentId) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (child:Module {id: $childId})
                    MATCH (parent:Module {id: $parentId})
                    MERGE (child)-[:HAS_PARENT]->(parent)
                """, Values.parameters("childId", childId, "parentId", parentId));
                return null;
            });
        }
    }

    public void linkHasModule(String parentId, String childId) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (parent:Module {id: $parentId})
                    MATCH (child:Module {id: $childId})
                    MERGE (parent)-[:HAS_MODULE]->(child)
                """, Values.parameters("parentId", parentId, "childId", childId));
                return null;
            });
        }
    }

    // ExternalLib is keyed by groupId:artifactId only: the requested version is a
    // property of the dependency edge, not of the shared library node, so two
    // modules pulling different versions of the same lib no longer clobber it.
    public void mergeExternalLib(String groupId, String artifactId) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MERGE (l:ExternalLib {name: $name})
                    SET l.groupId = $groupId, l.artifactId = $artifactId
                """, Values.parameters(
                        "name", groupId + ":" + artifactId,
                        "groupId", groupId,
                        "artifactId", artifactId));
                return null;
            });
        }
    }

    public void linkModuleDependsOnExternal(String moduleId, String libName, String scope, String version) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (m:Module {id: $moduleId})
                    MATCH (l:ExternalLib {name: $libName})
                    MERGE (m)-[r:DEPENDS_ON]->(l)
                    SET r.scope = $scope, r.version = $version
                """, Values.parameters("moduleId", moduleId, "libName", libName, "scope", scope, "version", version));
                return null;
            });
        }
    }

    public void linkModuleDependsOnModule(String sourceId, String targetId, String scope) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (src:Module {id: $sourceId})
                    MATCH (tgt:Module {id: $targetId})
                    MERGE (src)-[r:DEPENDS_ON]->(tgt)
                    SET r.scope = $scope
                """, Values.parameters("sourceId", sourceId, "targetId", targetId, "scope", scope));
                return null;
            });
        }
    }

    public void linkModuleManages(String moduleId, String libName, String version) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (m:Module {id: $moduleId})
                    MATCH (l:ExternalLib {name: $libName})
                    MERGE (m)-[r:MANAGES]->(l)
                    SET r.version = $version
                """, Values.parameters("moduleId", moduleId, "libName", libName, "version", version));
                return null;
            });
        }
    }

    // Like ExternalLib, Plugin is keyed by groupId:artifactId; the version and the
    // executed goals live on the USES_PLUGIN edge, since both are declared per
    // module (and goals belong to a module's plugin executions, not the plugin).
    public void mergePlugin(String groupId, String artifactId) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MERGE (p:Plugin {id: $id})
                    SET p.groupId = $groupId, p.artifactId = $artifactId
                """, Values.parameters(
                        "id", groupId + ":" + artifactId,
                        "groupId", groupId,
                        "artifactId", artifactId));
                return null;
            });
        }
    }

    public void linkModulePlugin(String moduleId, String pluginId, String version, java.util.List<String> goals) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (m:Module {id: $moduleId})
                    MATCH (p:Plugin {id: $pluginId})
                    MERGE (m)-[r:USES_PLUGIN]->(p)
                    SET r.version = $version, r.goals = $goals
                """, Values.parameters("moduleId", moduleId, "pluginId", pluginId,
                        "version", version, "goals", goals));
                return null;
            });
        }
    }

    public void mergeArtifact(String groupId, String artifactId, String version, String packaging) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                // Version is part of the Artifact identity so distinct releases of
                // the same artifact (across a multi-repo graph) are distinct nodes.
                tx.run("""
                    MERGE (a:Artifact {id: $id})
                    SET a.groupId = $groupId, a.artifactId = $artifactId,
                        a.version = $version, a.packaging = $packaging
                """, Values.parameters(
                        "id", groupId + ":" + artifactId + ":" + version + ":" + packaging,
                        "groupId", groupId,
                        "artifactId", artifactId,
                        "version", version,
                        "packaging", packaging));
                return null;
            });
        }
    }

    public void linkModuleProduces(String moduleId, String artifactId) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (m:Module {id: $moduleId})
                    MATCH (a:Artifact {id: $artifactId})
                    MERGE (m)-[:PRODUCES]->(a)
                """, Values.parameters("moduleId", moduleId, "artifactId", artifactId));
                return null;
            });
        }
    }

    // BOM is keyed by groupId:artifactId; the imported version lives on the
    // IMPORTS_BOM edge, so different modules can import different BOM versions.
    public void mergeBOM(String groupId, String artifactId) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MERGE (b:BillOfMaterials {name: $name})
                    SET b.groupId = $groupId, b.artifactId = $artifactId
                """, Values.parameters(
                        "name", groupId + ":" + artifactId,
                        "groupId", groupId,
                        "artifactId", artifactId));
                return null;
            });
        }
    }

    public void linkModuleImportsBOM(String moduleId, String bomName, String version) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (m:Module {id: $moduleId})
                    MATCH (b:BillOfMaterials {name: $bomName})
                    MERGE (m)-[r:IMPORTS_BOM]->(b)
                    SET r.version = $version
                """, Values.parameters("moduleId", moduleId, "bomName", bomName, "version", version));
                return null;
            });
        }
    }

    public void createModuleProperty(String moduleId, String name, String value) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (m:Module {id: $moduleId})
                    MERGE (p:Property {id: $propId})
                    SET p.name = $name, p.value = $value
                    MERGE (m)-[:DECLARES_PROPERTY]->(p)
                """, Values.parameters(
                        "moduleId", moduleId,
                        "propId", moduleId + "#" + name,
                        "name", name,
                        "value", value));
                return null;
            });
        }
    }

    /**
     * Links every SourceFile to the most specific Module that contains it,
     * determined by longest path-prefix match, via a single CONTAINS edge
     * (Neo4j traverses it in both directions, so no inverse edge is stored).
     * The root module (path '') matches every file, so it acts as the fallback
     * owner — in particular, in a single-module repo it owns all files.
     * Run after all Modules and SourceFiles have been imported.
     */
    public void linkSourceFilesToModules() {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (sf:SourceFile), (m:Module)
                    WHERE m.path = '' OR sf.path STARTS WITH (m.path + '/')
                    WITH sf, m ORDER BY size(m.path) DESC
                    WITH sf, collect(m)[0] AS module
                    WHERE module IS NOT NULL
                    MERGE (module)-[:CONTAINS]->(sf)
                """);
                return null;
            });
        }
    }

    public void linkCommitToCodeElements(String commitHash, String filePath, java.util.List<java.util.Map<String, Object>> elements) {
        if (elements == null || elements.isEmpty()) return;
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (c:Commit {hash:$hash})
                    UNWIND $elements AS el
                    MATCH (f:SourceFile {path:$path})
                    OPTIONAL MATCH (f)-[:DEFINES]->(t:Type) WHERE t.fqn = el.fqn
                    OPTIONAL MATCH (f)-[:DEFINES]->(:Type)-[:HAS_METHOD]->(m:Method) WHERE m.id = el.id
                    WITH c, t, m
                    FOREACH (x IN CASE WHEN t IS NOT NULL THEN [t] ELSE [] END | MERGE (c)-[:MODIFIED_CODE]->(x))
                    FOREACH (x IN CASE WHEN m IS NOT NULL THEN [m] ELSE [] END | MERGE (c)-[:MODIFIED_CODE]->(x))
                """, Values.parameters("hash", commitHash, "path", filePath, "elements", elements));
                return null;
            });
        }
    }

    // ── Jira schema ───────────────────────────────────────────────────────────

    /**
     * Links a Commit to the Issues its message references. Issue nodes are
     * MERGEd as stubs (key only) and enriched later; the Commit is MATCHed, so
     * nothing is created when the git importer has not run.
     */
    public void linkCommitToIssues(String commitHash, java.util.List<String> issueKeys) {
        if (issueKeys == null || issueKeys.isEmpty()) return;
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (c:Commit {hash:$hash})
                    UNWIND $keys AS key
                    MERGE (i:Issue {key:key})
                    MERGE (c)-[:REFERENCES]->(i)
                """, Values.parameters("hash", commitHash, "keys", issueKeys));
                return null;
            });
        }
    }

    /**
     * Creates a MergeRequest derived from a merge commit (id = the merge
     * commit's hash, so re-imports are idempotent) and links it to that commit
     * via MERGED_AS. {@code number} and {@code sourceBranch} are nullable —
     * they are only known when the merge-commit message follows a recognised
     * convention.
     */
    public void createMergeRequest(String mergeCommitHash, Integer number, String title,
                                   String sourceBranch, Instant mergedAt) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (c:Commit {hash:$hash})
                    MERGE (mr:MergeRequest {id:$hash})
                    SET mr.number=$number, mr.title=$title,
                        mr.sourceBranch=$sourceBranch, mr.mergedAt=datetime($mergedAt)
                    MERGE (mr)-[:MERGED_AS]->(c)
                """, Values.parameters(
                        "hash", mergeCommitHash,
                        "number", number,
                        "title", title,
                        "sourceBranch", sourceBranch,
                        "mergedAt", mergedAt.toString()));
                return null;
            });
        }
    }

    /** Links a MergeRequest to the commits it merged in. Batched via UNWIND. */
    public void linkMergeRequestCommits(String mergeRequestId, java.util.List<String> commitHashes) {
        if (commitHashes == null || commitHashes.isEmpty()) return;
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (mr:MergeRequest {id:$id})
                    UNWIND $hashes AS hash
                    MATCH (c:Commit {hash:hash})
                    MERGE (mr)-[:INCLUDES]->(c)
                """, Values.parameters("id", mergeRequestId, "hashes", commitHashes));
                return null;
            });
        }
    }

    /** Links a MergeRequest to the Issues it implements (stubs MERGEd by key). */
    public void linkMergeRequestIssues(String mergeRequestId, java.util.List<String> issueKeys) {
        if (issueKeys == null || issueKeys.isEmpty()) return;
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (mr:MergeRequest {id:$id})
                    UNWIND $keys AS key
                    MERGE (i:Issue {key:key})
                    MERGE (mr)-[:IMPLEMENTS]->(i)
                """, Values.parameters("id", mergeRequestId, "keys", issueKeys));
                return null;
            });
        }
    }

    /**
     * Enriches an Issue stub with data from the Jira API. When the assignee's
     * email is known the Issue is also linked to the matching Developer (the
     * same key as the git graph), connecting tickets to committers.
     */
    public void enrichIssue(String key, String summary, String status, String type,
                            String priority, String assigneeName, String assigneeEmail, String url) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                    MATCH (i:Issue {key:$key})
                    SET i.summary=$summary, i.status=$status, i.type=$type,
                        i.priority=$priority, i.assignee=$assignee, i.url=$url
                """, Values.parameters(
                        "key", key,
                        "summary", summary,
                        "status", status,
                        "type", type,
                        "priority", priority,
                        "assignee", assigneeName,
                        "url", url));

                if (assigneeEmail != null && !assigneeEmail.isBlank()) {
                    tx.run("""
                        MATCH (i:Issue {key:$key})
                        MERGE (d:Developer {email:$email})
                        ON CREATE SET d.name=$name
                        MERGE (i)-[:ASSIGNED_TO]->(d)
                    """, Values.parameters("key", key, "email", assigneeEmail, "name", assigneeName));
                }
                return null;
            });
        }
    }

    public void close() {
        driver.close();
    }
}
