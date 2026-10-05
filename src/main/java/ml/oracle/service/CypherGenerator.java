package ml.oracle.service;

import ml.oracle.infrastructure.OllamaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CypherGenerator {

    private static final Logger logger = LoggerFactory.getLogger(CypherGenerator.class);

    private static final Pattern THINK_TAG =
            Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    private static final Pattern CODE_FENCE =
            Pattern.compile("```(?:cypher|Cypher)?\\s*(.*?)\\s*```", Pattern.DOTALL);
    // First uppercase Cypher keyword a read-only query can start with; used to
    // strip leading prose like "The corrected Cypher query: MATCH ...".
    private static final Pattern QUERY_START =
            Pattern.compile("\\b(MATCH|OPTIONAL MATCH|UNWIND|CALL|WITH|RETURN)\\b");

    private final OllamaClient ollama;

    public CypherGenerator(OllamaClient ollama) {
        this.ollama = ollama;
    }

    public String generate(String question, String schema) throws Exception {
        String raw = ollama.chat(buildSystem(schema), question);
        String cleaned = clean(raw);
        logger.info("Generated Cypher:\n{}", cleaned);
        return cleaned;
    }

    public String regenerate(String question, String schema, String failedCypher, String errorMessage) throws Exception {
        String user = """
                Question: %s

                Your previous Cypher query failed. Fix it and return ONLY the corrected query.

                Failed query:
                %s

                Error:
                %s
                """.formatted(question, failedCypher, errorMessage);

        String raw = ollama.chat(buildSystem(schema), user);
        String cleaned = clean(raw);
        logger.info("Regenerated Cypher (after error):\n{}", cleaned);
        return cleaned;
    }

    private static String buildSystem(String schema) {
        return """
                You are an expert at translating natural-language questions into
                Neo4j Cypher queries. The user's question may be in English or Dutch.

                Rules:
                - Return ONLY the Cypher query. No explanation, no comments, no markdown.
                - The query MUST be read-only: only MATCH, OPTIONAL MATCH, WITH, WHERE,
                  RETURN, ORDER BY, SKIP, LIMIT, UNWIND, CALL { ... } subqueries.
                - Never use CREATE, MERGE, SET, DELETE, REMOVE, DROP, LOAD, CALL apoc.*write*.
                - Use only the labels, properties and relationships listed in the schema.
                - IMPORTANT: Every query MUST end with a RETURN clause. Never end with WITH.
                - ALWAYS add LIMIT to every query that returns rows (default: LIMIT 100). Never omit it.
                - Exception: pure COUNT(*) or aggregation-only queries do not need LIMIT.
                - For "most"/"top"/"meeste" questions use ORDER BY ... DESC LIMIT N.
                - "most recent"/"latest"/"laatste" is about time: ORDER BY c.timestamp DESC
                  LIMIT 1. Never count commits unless the question asks "how many".
                - When the question asks for a single number ("how many", "what is the
                  highest ..."), RETURN exactly one aggregate value such as count(x) or
                  max(x) — no other columns.
                - For "which"/"list" questions return DISTINCT values.
                - "name" or "simple name" means the name property; only use the fqn
                  property when asked for the fully qualified name.
                - Relationship direction matters — follow the directions shown in the schema,
                  e.g. (SourceFile)-[:DEFINES]->(Type), (Type)-[:HAS_METHOD]->(Method),
                  (Commit)-[:MODIFIED]->(SourceFile).
                - When searching for code elements by name, use case-insensitive matching if appropriate (e.g., WHERE t.name =~ '(?i)User.*').
                - For "hotspots" or "volatile files", look for SourceFile nodes with high changeCount.
                - ONLY SourceFile nodes have the existsAtHead property (false = the file was
                  deleted from the repository). Add WHERE f.existsAtHead when returning files,
                  but NEVER use existsAtHead on any other label (Commit, Type, Method, ...).
                - Annotations attach to both Types and Methods via ANNOTATED_WITH. For
                  "which annotations are used" just MATCH (a:Annotation).
                - For "coupling" or "files changed together", use the CO_CHANGED_WITH relationship.
                - For "who changed this method", use the MODIFIED_CODE relationship from Commit to Method.
                - Maven structure uses these node labels (note: requested versions live on the
                  relationship, not on the shared ExternalLib/Plugin/BillOfMaterials node):
                    Repository {name, url, branch} -[:CONTAINS]-> Module {id, groupId, artifactId, version, packaging, path}
                    Module -[:HAS_PARENT]->   Module          (POM inheritance, workspace only)
                    Module -[:HAS_MODULE]->   Module          (aggregate to child)
                    Module -[:DEPENDS_ON {scope, version}]-> ExternalLib {name, groupId, artifactId}
                    Module -[:DEPENDS_ON {scope}]-> Module     (internal dependency)
                    Module -[:MANAGES {version}]->      ExternalLib   (dependency management)
                    Module -[:IMPORTS_BOM {version}]->  BillOfMaterials {name, groupId, artifactId}
                    Module -[:USES_PLUGIN {version, goals}]-> Plugin {id, groupId, artifactId}
                    Module -[:PRODUCES]->     Artifact {id, groupId, artifactId, version, packaging}
                    Module -[:DECLARES_PROPERTY]-> Property {id, name, value}
                    Module -[:CONTAINS]->     SourceFile       (files in that module)
                    Type   -[:USES_LIBRARY]-> ExternalLib      (which library a Java type imports from)
                - For "where is spring-ai used", look for Type-[:USES_LIBRARY]->ExternalLib WHERE l.groupId CONTAINS 'spring-ai'.
                - For "which modules depend on X", look for Module-[:DEPENDS_ON]->ExternalLib {artifactId:'X'}.
                - Issue tracking (present when the jira importer ran):
                    Commit -[:REFERENCES]-> Issue {key, summary, status, type, priority, assignee, url}
                    MergeRequest {id, number, title, sourceBranch, mergedAt} -[:MERGED_AS]-> Commit
                    MergeRequest -[:INCLUDES]->   Commit    (the commits that merge request merged in)
                    MergeRequest -[:IMPLEMENTS]-> Issue
                    Issue -[:ASSIGNED_TO]-> Developer

                Vocabulary — map the user's words (English or Dutch) to the schema:
                - "developer", "author", "contributor", "who", "wie" -> Developer (keyed by email)
                - "commit", "change", "wijziging"; "when"/"wanneer" -> Commit.timestamp
                - "file", "bestand"; "hotspot", "volatile", "churn", "most changed" -> high SourceFile.changeCount
                - "class", "type", "interface", "enum", "record" -> Type (narrow with Type.kind)
                - "method", "function", "functie"; "caller"/"callee" -> Method and Method-[:CALLS]->Method
                - "package", "annotation" -> Package, Annotation
                - "library", "dependency", "3rd party", "afhankelijkheid" -> ExternalLib
                - "module", "project" -> Module; "release", "tag" -> Tag
                - "issue", "ticket", "story", "bug", or a key like "ABC-123" -> Issue (match on key)
                - "merge request", "pull request", "MR", "PR" -> MergeRequest

                Examples (question -> Cypher):
                Q: Which files are the biggest hotspots?
                A: MATCH (f:SourceFile) WHERE f.changeCount IS NOT NULL AND f.existsAtHead
                   RETURN f.path, f.changeCount ORDER BY f.changeCount DESC LIMIT 25
                Q: Which files change together most often?
                A: MATCH (a:SourceFile)-[r:CO_CHANGED_WITH]-(b:SourceFile)
                   RETURN a.path, b.path, r.count ORDER BY r.count DESC LIMIT 25
                Q: Who last modified the UserService class?
                A: MATCH (d:Developer)-[:AUTHORED]->(c:Commit)-[:MODIFIED_CODE]->(t:Type)
                   WHERE t.name = 'UserService'
                   RETURN d.name, c.hash, c.timestamp ORDER BY c.timestamp DESC LIMIT 1
                Q: When was the most recent commit and by whom?
                A: MATCH (d:Developer)-[:AUTHORED]->(c:Commit)
                   RETURN d.name, c.timestamp ORDER BY c.timestamp DESC LIMIT 1
                Q: How many classes are defined in files under src/main?
                A: MATCH (f:SourceFile)-[:DEFINES]->(t:Type)
                   WHERE f.path STARTS WITH 'src/main' AND t.kind = 'CLASS'
                   RETURN count(DISTINCT t) AS classes
                Q: Who are the top contributors?
                A: MATCH (d:Developer)-[:AUTHORED]->(c:Commit)
                   RETURN d.name, count(c) AS commits ORDER BY commits DESC LIMIT 10
                Q: What does the OrderController class call?
                A: MATCH (:Type {name:'OrderController'})-[:HAS_METHOD]->(:Method)-[:CALLS]->(callee:Method)<-[:HAS_METHOD]-(t:Type)
                   RETURN DISTINCT t.name, callee.name LIMIT 100
                Q: Which modules depend on jackson-databind?
                A: MATCH (m:Module)-[:DEPENDS_ON]->(l:ExternalLib {artifactId:'jackson-databind'})
                   RETURN DISTINCT m.artifactId LIMIT 100
                Q: Where is the spring-web library used in code?
                A: MATCH (t:Type)-[:USES_LIBRARY]->(l:ExternalLib) WHERE l.artifactId CONTAINS 'spring-web'
                   RETURN DISTINCT t.fqn LIMIT 100
                Q: Which classes are annotated with @Service?
                A: MATCH (t:Type)-[:ANNOTATED_WITH]->(a:Annotation) WHERE a.name = 'Service'
                   RETURN DISTINCT t.fqn LIMIT 100

                Schema of the database:
                %s
                """.formatted(schema);
    }

    static String clean(String raw) {

        String s = THINK_TAG.matcher(raw).replaceAll("").trim();

        Matcher m = CODE_FENCE.matcher(s);
        if (m.find()) {
            s = m.group(1).trim();
        }

        // Strip a leading "cypher" label sometimes emitted before the query.
        if (s.toLowerCase().startsWith("cypher\n")) {
            s = s.substring(7).trim();
        }

        // Strip leading prose ("The corrected Cypher query: MATCH ...") that
        // small models emit despite the rules: cut at the first uppercase
        // Cypher keyword. Case-sensitive so prose containing "match" survives.
        Matcher q = QUERY_START.matcher(s);
        if (q.find() && q.start() > 0) {
            s = s.substring(q.start()).trim();
        }

        return s;
    }
}
