package ml.oracle.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ml.oracle.infrastructure.OllamaClient;
import ml.oracle.infrastructure.QueryExecutor;
import ml.oracle.model.AppConfig;
import ml.oracle.model.QueryResult;
import ml.oracle.service.CypherGenerator;
import ml.oracle.service.QueryValidator;
import ml.oracle.service.SchemaIntrospector;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Execution-accuracy harness for the natural-language → Cypher pipeline.
 *
 * <p>For every {@link EvalCase} it runs the real generation pipeline
 * (generate → validate read-only → EXPLAIN → execute, with the same retry loop
 * as the server) and compares the rows it returns against the rows of a trusted
 * <em>reference</em> query. A case passes when the two result sets are equal, so
 * any correctly-phrased query counts as correct regardless of how it is written.
 *
 * <p>The comparison is tolerant of column naming/order and (unless the case is
 * {@code orderSensitive}) row order, but not of a different set of returned
 * values — that is the point: it measures whether the model fetched the right
 * data.
 *
 * <p><b>Prerequisites:</b> a Neo4j instance already populated by importing the
 * <em>software-oracle repository itself</em> with all three importers
 * ({@code oracle.importers=git,java,maven}), plus a running Ollama. A few cases
 * reference names specific to this codebase (e.g. {@code Neo4jService},
 * {@code Importer}); the rest are repo-agnostic.
 *
 * <p><b>Run:</b>
 * {@code mvn -o exec:java -Dexec.mainClass=ml.oracle.eval.EvalRunner}
 */
public class EvalRunner {

    private static final Logger logger = LoggerFactory.getLogger(EvalRunner.class);
    private static final int MAX_ATTEMPTS = 3;

    public static void main(String[] args) throws Exception {

        AppConfig config = AppConfig.load();

        List<EvalCase> cases;
        try (InputStream in = EvalRunner.class.getClassLoader().getResourceAsStream("eval/cases.json")) {
            if (in == null) throw new IllegalStateException("eval/cases.json not found on classpath");
            cases = new ObjectMapper().readValue(in, new TypeReference<List<EvalCase>>() {});
        }

        try (Driver driver = GraphDatabase.driver(
                config.neo4jUri(), AuthTokens.basic(config.neo4jUser(), config.neo4jPassword()))) {

            OllamaClient ollama = new OllamaClient(config.ollamaUri(), config.ollamaModel(), config.ollamaTimeout());
            SchemaIntrospector schema = new SchemaIntrospector(driver);
            CypherGenerator generator = new CypherGenerator(ollama);
            QueryExecutor executor = new QueryExecutor(driver);

            String schemaDescription = schema.describe();

            // Labels present in the graph, used to skip cases whose importer
            // (or language) is not represented in this database.
            Set<String> presentLabels = new HashSet<>();
            executor.run("CALL db.labels() YIELD label RETURN label").rows()
                    .forEach(row -> presentLabels.add(String.valueOf(row.get("label"))));

            List<Result> results = new ArrayList<>();
            System.out.printf("Running %d cases against model '%s'...%n%n", cases.size(), config.ollamaModel());

            for (EvalCase c : cases) {
                Result r;
                if (c.requires() != null && !presentLabels.containsAll(c.requires())) {
                    List<String> missing = new ArrayList<>(c.requires());
                    missing.removeAll(presentLabels);
                    r = new Result(c, "SKIPPED", null, "labels not in graph: " + String.join(", ", missing));
                } else {
                    r = runCase(c, generator, executor, schemaDescription);
                }
                results.add(r);
                System.out.printf("  [%-8s] %-26s %s%n", r.status(), c.id(), r.note() == null ? "" : r.note());
            }

            report(results, config.ollamaModel());
        }
    }

    // ── One case ────────────────────────────────────────────────────────────

    private static Result runCase(EvalCase c, CypherGenerator generator,
                                  QueryExecutor executor, String schema) {

        // 1. Reference result — authoritative "ground truth" on the live graph.
        List<Map<String, Object>> reference;
        try {
            reference = executor.run(c.cypher()).rows();
        } catch (Exception e) {
            return new Result(c, "REF_ERR", null, "reference query failed: " + e.getMessage());
        }

        // 2. Generated result — the real pipeline with its retry loop.
        String cypher = null;
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                cypher = (attempt == 1)
                        ? generator.generate(c.question(), schema)
                        : generator.regenerate(c.question(), schema, cypher, lastError.getMessage());
                QueryValidator.requireReadOnly(cypher);
                executor.validate(cypher);
                QueryResult generated = executor.run(cypher);

                if (reference.isEmpty()) {
                    // Non-discriminating on this graph — excluded from accuracy.
                    return new Result(c, "EMPTY_REF", cypher, "reference returned no rows");
                }
                boolean pass = normalize(generated.rows(), c.orderSensitive())
                        .equals(normalize(reference, c.orderSensitive()));
                String note = "gen=" + generated.rows().size() + " ref=" + reference.size() + " rows";
                return new Result(c, pass ? "PASS" : "FAIL", cypher, note);

            } catch (SecurityException e) {
                return new Result(c, "FAIL", cypher, "rejected as unsafe: " + e.getMessage());
            } catch (Exception e) {
                lastError = e;
                logger.debug("Case {} attempt {}/{} failed: {}", c.id(), attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        return new Result(c, "ERROR", cypher,
                "no valid query after " + MAX_ATTEMPTS + " attempts: "
                        + (lastError == null ? "?" : lastError.getMessage()));
    }

    // ── Result-set comparison ───────────────────────────────────────────────

    /**
     * Normalises a result to a comparable form: each row becomes a sorted list
     * of its stringified cell values (so column name/order is irrelevant), and
     * unless {@code ordered} the rows themselves are sorted (so row order is
     * irrelevant — an unordered multiset comparison).
     */
    private static List<List<String>> normalize(List<Map<String, Object>> rows, boolean ordered) {
        List<List<String>> signatures = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>();
            for (Object value : row.values()) cells.add(cell(value));
            cells.sort(Comparator.naturalOrder());
            signatures.add(cells);
        }
        if (!ordered) {
            signatures.sort(Comparator.comparing(Object::toString));
        }
        return signatures;
    }

    /** Stringifies a single cell, flattening nodes/relationships and lists, and
     *  canonicalising integral numbers so {@code 5L} and {@code 5} compare equal. */
    private static String cell(Object v) {
        if (v == null) return "∅";
        if (v instanceof Map<?, ?> map) {
            List<String> vals = new ArrayList<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey().toString().startsWith("_")) continue; // skip _labels/_type
                vals.add(cell(e.getValue()));
            }
            vals.sort(Comparator.naturalOrder());
            return "{" + String.join(",", vals) + "}";
        }
        if (v instanceof List<?> list) {
            List<String> vals = new ArrayList<>();
            for (Object o : list) vals.add(cell(o));
            return "[" + String.join(",", vals) + "]";
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isInfinite(d) && d == Math.floor(d)) return Long.toString(n.longValue());
            return Double.toString(d);
        }
        return v.toString();
    }

    // ── Reporting ───────────────────────────────────────────────────────────

    private static void report(List<Result> results, String model) throws Exception {
        long passed = results.stream().filter(r -> r.status().equals("PASS")).count();
        long failed = results.stream().filter(r -> r.status().equals("FAIL")).count();
        long errored = results.stream().filter(r -> r.status().equals("ERROR")).count();
        long emptyRef = results.stream().filter(r -> r.status().equals("EMPTY_REF")).count();
        long refErr = results.stream().filter(r -> r.status().equals("REF_ERR")).count();
        long skipped = results.stream().filter(r -> r.status().equals("SKIPPED")).count();

        long discriminating = passed + failed + errored; // cases with a real reference answer
        double accuracy = discriminating == 0 ? 0.0 : 100.0 * passed / discriminating;

        StringBuilder md = new StringBuilder();
        md.append("# NL→Cypher evaluation\n\n");
        md.append("Model: `").append(model).append("`\n\n");
        md.append(String.format("**Execution accuracy: %d/%d (%.1f%%)**%n%n", passed, discriminating, accuracy));
        md.append(String.format("- %d passed, %d failed, %d errored%n", passed, failed, errored));
        if (emptyRef > 0) md.append(String.format("- %d skipped (reference returned no rows — non-discriminating)%n", emptyRef));
        if (skipped > 0) md.append(String.format("- %d skipped (required node labels not in graph)%n", skipped));
        if (refErr > 0) md.append(String.format("- %d harness errors (reference query failed)%n", refErr));
        md.append("\n| Case | Status | Notes | Generated Cypher |\n");
        md.append("|------|--------|-------|------------------|\n");
        for (Result r : results) {
            md.append("| ").append(r.eval().id())
              .append(" | ").append(r.status())
              .append(" | ").append(r.note() == null ? "" : r.note().replace("|", "\\|"))
              .append(" | ").append(r.cypher() == null ? "" : "`" + r.cypher().replace("\n", " ").replace("|", "\\|") + "`")
              .append(" |\n");
        }

        Path out = Path.of("target", "eval-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());

        System.out.println();
        System.out.printf("=== Execution accuracy: %d/%d (%.1f%%) ===%n", passed, discriminating, accuracy);
        System.out.printf("    %d passed · %d failed · %d errored", passed, failed, errored);
        if (emptyRef > 0) System.out.printf(" · %d skipped(empty ref)", emptyRef);
        if (skipped > 0) System.out.printf(" · %d skipped(missing labels)", skipped);
        if (refErr > 0) System.out.printf(" · %d harness errors", refErr);
        System.out.println();
        System.out.println("    Full report: " + out.toAbsolutePath());
    }

    private record Result(EvalCase eval, String status, String cypher, String note) {}
}
