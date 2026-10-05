package ml.oracle.importer;

import ml.oracle.infrastructure.Neo4jService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * Builds a Maven-aware graph on top of the Git/Java layers:
 *
 * <pre>
 *   Repository -[:CONTAINS]-> Module -[:DEPENDS_ON {scope,version}]-> ExternalLib
 *                                    -[:DEPENDS_ON {scope}]->  Module   (internal)
 *                                    -[:HAS_PARENT]->  Module          (POM inheritance)
 *                                    -[:HAS_MODULE]->  Module          (aggregation)
 *                                    -[:USES_PLUGIN {version,goals}]-> Plugin
 *                                    -[:PRODUCES]->    Artifact
 *                                    -[:IMPORTS_BOM {version}]-> BillOfMaterials
 *                                    -[:MANAGES {version}]->     ExternalLib
 *                                    -[:DECLARES_PROPERTY]->    Property
 *              Module     -[:CONTAINS]->  SourceFile
 * </pre>
 *
 * The import is three-pass:
 * <ol>
 *   <li>Parse every pom.xml, collect data in memory, create Module nodes.</li>
 *   <li>Create all relationships (all Module nodes exist so internal deps resolve cleanly).</li>
 *   <li>Link SourceFile nodes (created by GitImporter) to their owning Module via path-prefix matching.</li>
 * </ol>
 */
public class MavenImporter implements Importer {

    private static final Logger logger = LoggerFactory.getLogger(MavenImporter.class);

    // ── Data records collected during pass 1 ──────────────────────────────────

    private record ModuleData(
            String id,           // groupId:artifactId
            String groupId,
            String artifactId,
            String version,
            String packaging,
            String relPath,
            String url,
            String parentGroupId,
            String parentArtifactId,
            String parentVersion,
            Map<String, String> properties,
            List<String> declaredChildPaths,
            List<DepData> deps,
            List<DepData> managedDeps,
            List<BomData> boms,
            List<PluginData> plugins
    ) {}

    private record DepData(String groupId, String artifactId, String version, String scope) {}
    private record BomData(String groupId, String artifactId, String version) {}
    private record PluginData(String groupId, String artifactId, String version, List<String> goals) {}

    // ── Importer state ─────────────────────────────────────────────────────────

    private final String repositoryPath;
    private final List<ModuleData> allModules  = new ArrayList<>();
    private final Map<String, String> pathToId = new HashMap<>();   // relPath -> moduleId
    private final Set<String> workspaceIds     = new HashSet<>();   // all module IDs in this repo

    public MavenImporter(String repositoryPath) {
        this.repositoryPath = repositoryPath;
    }

    // ── Importer entry point ───────────────────────────────────────────────────

    @Override
    public void importRepository(Neo4jService neo4j) throws Exception {
        Path root = Paths.get(repositoryPath).toAbsolutePath().normalize();

        // Pass 1: parse poms, collect data, create Module nodes
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path pomPath : paths.filter(p -> "pom.xml".equals(p.getFileName().toString())).toList()) {
                ModuleData data = parsePom(pomPath.toFile(), root);
                if (data == null) continue;
                allModules.add(data);
                pathToId.put(data.relPath(), data.id());
                workspaceIds.add(data.id());
                neo4j.createModule(data.groupId(), data.artifactId(), data.version(),
                        data.packaging(), data.relPath());
            }
        }

        // Create the Repository node and link all workspace modules to it
        String repoName = allModules.stream()
                .filter(m -> m.relPath().isEmpty())
                .findFirst()
                .map(ModuleData::artifactId)
                .orElseGet(() -> root.getFileName().toString());

        String repoUrl = allModules.stream()
                .filter(m -> m.relPath().isEmpty())
                .findFirst()
                .map(ModuleData::url)
                .orElse(null);

        neo4j.mergeRepository(repoName, repoUrl, readBranch(root));
        for (ModuleData m : allModules) {
            neo4j.linkRepositoryModule(repoName, m.id());
        }

        // Pass 2: create all relationships
        for (ModuleData m : allModules) {
            createRelationships(m, neo4j);
        }

        // Pass 3: link SourceFile nodes to their owning Module by path prefix
        neo4j.linkSourceFilesToModules();
    }

    // ── Pass 1: parse a single pom.xml ────────────────────────────────────────

    private ModuleData parsePom(File pomFile, Path root) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        Document doc = factory.newDocumentBuilder().parse(pomFile);
        doc.getDocumentElement().normalize();

        Element project = doc.getDocumentElement();
        Element parentEl = child(project, "parent");

        String groupId   = text(project, "groupId");
        String artifactId = text(project, "artifactId");
        String version   = text(project, "version");
        String packaging = Objects.requireNonNullElse(text(project, "packaging"), "jar");
        String url       = text(project, "url");

        // Inherit groupId / version from <parent> if omitted locally
        String parentGroupId    = parentEl != null ? text(parentEl, "groupId")    : null;
        String parentArtifactId = parentEl != null ? text(parentEl, "artifactId") : null;
        String parentVersion    = parentEl != null ? text(parentEl, "version")    : null;
        if (groupId == null)  groupId  = parentGroupId;
        if (version == null)  version  = parentVersion;

        if (groupId == null || artifactId == null) {
            logger.warn("Skipping {} — incomplete coordinates ({}:{})", pomFile, groupId, artifactId);
            return null;
        }

        String relPath  = root.relativize(pomFile.toPath().getParent()).toString();
        String moduleId = groupId + ":" + artifactId;

        // ── Properties ──────────────────────────────────────────────────────────
        Map<String, String> properties = new LinkedHashMap<>();
        Element propsEl = child(project, "properties");
        if (propsEl != null) {
            NodeList kids = propsEl.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                if (kids.item(i) instanceof Element e) {
                    properties.put(e.getTagName(), e.getTextContent().trim());
                }
            }
        }

        // ── Declared child modules ───────────────────────────────────────────────
        List<String> declaredChildPaths = new ArrayList<>();
        Element modulesEl = child(project, "modules");
        if (modulesEl != null) {
            NodeList mods = modulesEl.getChildNodes();
            for (int i = 0; i < mods.getLength(); i++) {
                if (mods.item(i) instanceof Element modEl && "module".equals(modEl.getTagName())) {
                    String declared = modEl.getTextContent().trim();
                    String resolved = relPath.isEmpty() ? declared : relPath + "/" + declared;
                    declaredChildPaths.add(Paths.get(resolved).normalize().toString());
                }
            }
        }

        // ── Direct dependencies ──────────────────────────────────────────────────
        List<DepData> deps = new ArrayList<>();
        Element depsEl = child(project, "dependencies");
        if (depsEl != null) {
            deps = parseDeps(depsEl, properties);
        }

        // ── Dependency management ────────────────────────────────────────────────
        List<DepData> managedDeps = new ArrayList<>();
        List<BomData> boms        = new ArrayList<>();
        Element dmEl = child(project, "dependencyManagement");
        if (dmEl != null) {
            Element dmDepsEl = child(dmEl, "dependencies");
            if (dmDepsEl != null) {
                for (DepData dep : parseDeps(dmDepsEl, properties)) {
                    boolean isBom = "import".equals(dep.scope()) || "pom".equals(dep.version());
                    // BOM imports: scope=import AND a version (possibly a property ref)
                    String resolvedVersion = resolve(dep.version(), properties);
                    if ("import".equals(dep.scope())) {
                        boms.add(new BomData(dep.groupId(), dep.artifactId(), resolvedVersion));
                    } else {
                        managedDeps.add(new DepData(dep.groupId(), dep.artifactId(), resolvedVersion, dep.scope()));
                    }
                }
            }
        }

        // ── Build plugins ────────────────────────────────────────────────────────
        List<PluginData> plugins = new ArrayList<>();
        Element buildEl = child(project, "build");
        if (buildEl != null) {
            plugins.addAll(parsePlugins(child(buildEl, "plugins"), properties));
            Element pmEl = child(buildEl, "pluginManagement");
            if (pmEl != null) {
                plugins.addAll(parsePlugins(child(pmEl, "plugins"), properties));
            }
        }

        logger.info("Parsed {}:{} at '{}'", groupId, artifactId, relPath);
        return new ModuleData(moduleId, groupId, artifactId, version, packaging, relPath, url,
                parentGroupId, parentArtifactId, parentVersion,
                properties, declaredChildPaths, deps, managedDeps, boms, plugins);
    }

    private List<DepData> parseDeps(Element depsEl, Map<String, String> properties) {
        List<DepData> result = new ArrayList<>();
        NodeList nodes = depsEl.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element dep) || !"dependency".equals(dep.getTagName())) continue;
            String g = text(dep, "groupId");
            String a = text(dep, "artifactId");
            String v = resolve(text(dep, "version"), properties);
            String s = Objects.requireNonNullElse(text(dep, "scope"), "compile");
            if (g != null && a != null) result.add(new DepData(g, a, v, s));
        }
        return result;
    }

    private List<PluginData> parsePlugins(Element pluginsEl, Map<String, String> properties) {
        if (pluginsEl == null) return List.of();
        List<PluginData> result = new ArrayList<>();
        NodeList nodes = pluginsEl.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element plugin) || !"plugin".equals(plugin.getTagName())) continue;
            String g = Objects.requireNonNullElse(text(plugin, "groupId"), "org.apache.maven.plugins");
            String a = text(plugin, "artifactId");
            String v = resolve(text(plugin, "version"), properties);
            if (a == null) continue;

            List<String> goals = new ArrayList<>();
            Element execsEl = child(plugin, "executions");
            if (execsEl != null) {
                NodeList execs = execsEl.getChildNodes();
                for (int j = 0; j < execs.getLength(); j++) {
                    if (!(execs.item(j) instanceof Element exec) || !"execution".equals(exec.getTagName())) continue;
                    Element goalsEl = child(exec, "goals");
                    if (goalsEl == null) continue;
                    NodeList goalNodes = goalsEl.getChildNodes();
                    for (int k = 0; k < goalNodes.getLength(); k++) {
                        if (goalNodes.item(k) instanceof Element goalEl && "goal".equals(goalEl.getTagName())) {
                            goals.add(goalEl.getTextContent().trim());
                        }
                    }
                }
            }
            result.add(new PluginData(g, a, v, goals));
        }
        return result;
    }

    // ── Pass 2: write all relationships for one module ─────────────────────────

    private void createRelationships(ModuleData m, Neo4jService neo4j) {
        // HAS_PARENT — workspace parents only; external parents stored as Module property
        if (m.parentGroupId() != null) {
            String parentId = m.parentGroupId() + ":" + m.parentArtifactId();
            if (workspaceIds.contains(parentId)) {
                neo4j.linkParentModule(m.id(), parentId);
            }
        }

        // HAS_MODULE — aggregate to child
        for (String childPath : m.declaredChildPaths()) {
            String childId = pathToId.get(childPath);
            if (childId != null) {
                neo4j.linkHasModule(m.id(), childId);
            } else {
                logger.warn("Module at '{}' declared by '{}' has no pom.xml", childPath, m.relPath());
            }
        }

        // DEPENDS_ON — internal or external (requested version carried on the edge)
        for (DepData dep : m.deps()) {
            String depId = dep.groupId() + ":" + dep.artifactId();
            if (workspaceIds.contains(depId)) {
                neo4j.linkModuleDependsOnModule(m.id(), depId, dep.scope());
            } else {
                neo4j.mergeExternalLib(dep.groupId(), dep.artifactId());
                neo4j.linkModuleDependsOnExternal(m.id(), depId, dep.scope(), dep.version());
            }
        }

        // MANAGES — dependency management entries (non-BOM)
        for (DepData dep : m.managedDeps()) {
            String libName = dep.groupId() + ":" + dep.artifactId();
            neo4j.mergeExternalLib(dep.groupId(), dep.artifactId());
            neo4j.linkModuleManages(m.id(), libName, dep.version());
        }

        // IMPORTS_BOM — BOM entries in dependency management
        for (BomData bom : m.boms()) {
            String bomName = bom.groupId() + ":" + bom.artifactId();
            neo4j.mergeBOM(bom.groupId(), bom.artifactId());
            neo4j.linkModuleImportsBOM(m.id(), bomName, bom.version());
        }

        // USES_PLUGIN — version and executed goals live on the edge
        for (PluginData plugin : m.plugins()) {
            String pluginId = plugin.groupId() + ":" + plugin.artifactId();
            neo4j.mergePlugin(plugin.groupId(), plugin.artifactId());
            neo4j.linkModulePlugin(m.id(), pluginId, plugin.version(), plugin.goals());
        }

        // PRODUCES — skip aggregate poms (Artifact id includes version)
        if (!"pom".equals(m.packaging())) {
            neo4j.mergeArtifact(m.groupId(), m.artifactId(), m.version(), m.packaging());
            neo4j.linkModuleProduces(m.id(), m.id() + ":" + m.version() + ":" + m.packaging());
        }

        // DECLARES properties
        m.properties().forEach((name, value) ->
                neo4j.createModuleProperty(m.id(), name, value));

        // Type -[:USES_LIBRARY]-> ExternalLib (link Java types to the libs they import)
        linkTypesToLibraries(m, neo4j);
    }

    private void linkTypesToLibraries(ModuleData m, Neo4jService neo4j) {
        // Build a groupId -> set of ExternalLib names map for this module's deps
        Map<String, Set<String>> libsByGroup = new HashMap<>();
        for (DepData dep : m.deps()) {
            if (!workspaceIds.contains(dep.groupId() + ":" + dep.artifactId())) {
                libsByGroup.computeIfAbsent(dep.groupId(), k -> new HashSet<>())
                        .add(dep.groupId() + ":" + dep.artifactId());
            }
        }
        if (libsByGroup.isEmpty()) return;

        // Walk .java files in this module's source directory and match imports to libs
        Path moduleRoot = Paths.get(repositoryPath).resolve(m.relPath());
        Path srcRoot    = moduleRoot.resolve("src");
        if (!Files.exists(srcRoot)) return;

        try (Stream<Path> files = Files.walk(srcRoot)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String typeFqn = null;
                    Set<String> linked = new HashSet<>();
                    for (String line : Files.readAllLines(p)) {
                        line = line.trim();
                        if (line.startsWith("package ")) {
                            String pkg = line.replace("package ", "").replace(";", "").trim();
                            typeFqn = pkg + "." + p.getFileName().toString().replace(".java", "");
                        } else if (line.startsWith("import ") && !line.startsWith("import static ")) {
                            String imp = line.replace("import ", "").replace(";", "").trim();
                            if (imp.startsWith("java.") || imp.startsWith("javax.")) continue;
                            for (Map.Entry<String, Set<String>> entry : libsByGroup.entrySet()) {
                                if (imp.startsWith(entry.getKey())) {
                                    for (String libName : entry.getValue()) {
                                        if (typeFqn != null && linked.add(libName)) {
                                            neo4j.linkTypeToLibrary(typeFqn, libName);
                                        }
                                    }
                                }
                            }
                        } else if (line.contains("class ") || line.contains("interface ") || line.contains("enum ")) {
                            break;
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Error linking types to libs for {}: {}", p, e.getMessage());
                }
            });
        } catch (Exception e) {
            logger.error("Error walking src for module {}", m.id(), e);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Resolves a ${property} reference using the module's declared properties. */
    private static String resolve(String value, Map<String, String> properties) {
        if (value == null) return null;
        if (value.startsWith("${") && value.endsWith("}")) {
            return properties.getOrDefault(value.substring(2, value.length() - 1), value);
        }
        return value;
    }

    /** Reads the current branch name from .git/HEAD; returns null if unavailable. */
    private static String readBranch(Path root) {
        try {
            Path head = root.resolve(".git/HEAD");
            if (Files.exists(head)) {
                String content = Files.readString(head).trim();
                return content.startsWith("ref: refs/heads/")
                        ? content.substring("ref: refs/heads/".length())
                        : content.substring(0, Math.min(8, content.length())); // detached HEAD
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Returns the first direct child Element with the given tag name, or null. */
    private static Element child(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element e && tag.equals(e.getTagName())) return e;
        }
        return null;
    }

    /** Returns the text of the first direct child element with the given tag, or null. */
    private static String text(Element parent, String tag) {
        Element el = child(parent, tag);
        return el != null ? el.getTextContent().trim() : null;
    }

    @Override
    public void close() {
        // Neo4jService is managed by the caller
    }
}
