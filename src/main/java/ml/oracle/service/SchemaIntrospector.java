package ml.oracle.service;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds a compact, human-readable description of the live Neo4j schema
 * (node labels with their properties + relationship types with their
 * properties and endpoints).
 *
 * <p>The output is injected verbatim into the LLM prompt so the model knows
 * exactly which labels, properties and edges exist.
 *
 * <p>The schema is read <em>completely</em> from Neo4j's own metadata
 * procedures ({@code db.schema.nodeTypeProperties}, {@code relTypeProperties}
 * and {@code visualization}) rather than by sampling a handful of nodes, so no
 * property or endpoint combination is ever missed (a missed endpoint would
 * otherwise hide a valid traversal from the model).
 *
 * <p>Results are cached with a TTL to avoid redundant metadata queries during a
 * typical user session.
 */
public class SchemaIntrospector {

    private final Driver driver;
    private final Duration ttl;

    private String cachedDescription;
    private Instant lastUpdate = Instant.MIN;

    public SchemaIntrospector(Driver driver) {
        this(driver, Duration.ofMinutes(5));
    }

    public SchemaIntrospector(Driver driver, Duration ttl) {
        this.driver = driver;
        this.ttl = ttl;
    }

    public synchronized String describe() {
        if (cachedDescription != null && Instant.now().isBefore(lastUpdate.plus(ttl))) {
            return cachedDescription;
        }

        cachedDescription = fetchSchema();
        lastUpdate = Instant.now();
        return cachedDescription;
    }

    public synchronized void refresh() {
        lastUpdate = Instant.MIN;
        cachedDescription = null;
    }

    private String fetchSchema() {

        try (Session session = driver.session()) {

            // ── Node labels -> property keys ─────────────────────────────────
            var labelProps = new TreeMap<String, TreeSet<String>>();

            // Seed with every label so labels that currently have no properties
            // still appear.
            session.run("CALL db.labels() YIELD label RETURN label")
                    .stream()
                    .forEach(r -> labelProps.computeIfAbsent(r.get("label").asString(), k -> new TreeSet<>()));

            session.run("CALL db.schema.nodeTypeProperties() " +
                            "YIELD nodeLabels, propertyName RETURN nodeLabels, propertyName")
                    .stream()
                    .forEach(r -> {
                        List<String> nodeLabels = r.get("nodeLabels").asList(Value::asString);
                        if (nodeLabels.isEmpty()) return;
                        var keys = labelProps.computeIfAbsent(nodeLabels.get(0), k -> new TreeSet<>());
                        if (!r.get("propertyName").isNull()) keys.add(r.get("propertyName").asString());
                    });

            // ── Relationship type -> property keys ───────────────────────────
            var relProps = new TreeMap<String, TreeSet<String>>();
            session.run("CALL db.schema.relTypeProperties() " +
                            "YIELD relType, propertyName RETURN relType, propertyName")
                    .stream()
                    .forEach(r -> {
                        String type = stripRelType(r.get("relType").asString());
                        var keys = relProps.computeIfAbsent(type, k -> new TreeSet<>());
                        if (!r.get("propertyName").isNull()) keys.add(r.get("propertyName").asString());
                    });

            // ── Relationship endpoints ───────────────────────────────────────
            // db.schema.visualization() returns the complete connectivity graph:
            // one virtual node per label and one virtual relationship per
            // (startLabel, type, endLabel) triple that exists in the data.
            var relEndpoints = new TreeMap<String, TreeSet<String>>();
            Record viz = session.run("CALL db.schema.visualization()").single();

            Map<String, String> idToLabel = new HashMap<>();
            for (Node n : viz.get("nodes").asList(Value::asNode)) {
                String label = n.labels().iterator().hasNext() ? n.labels().iterator().next() : "?";
                idToLabel.put(n.elementId(), label);
            }
            for (Relationship rel : viz.get("relationships").asList(Value::asRelationship)) {
                String src = idToLabel.getOrDefault(rel.startNodeElementId(), "?");
                String dst = idToLabel.getOrDefault(rel.endNodeElementId(), "?");
                relEndpoints.computeIfAbsent(rel.type(), k -> new TreeSet<>())
                        .add(endpoint(src, rel.type(), dst, relProps.get(rel.type())));
            }

            // ── Enumerable (low-cardinality) property values ─────────────────
            // The model cannot guess that Type.kind is one of CLASS/INTERFACE/…
            // from the label alone, so we list the actual distinct values of a
            // few curated enum-like properties to ground filters and WHERE terms.
            var valueHints = new LinkedHashMap<String, List<String>>();
            addNodeValues(session, valueHints, "Type", "kind");
            addNodeValues(session, valueHints, "Method", "visibility");
            addRelValues(session, valueHints, "DEPENDS_ON", "scope");

            return format(labelProps, relEndpoints, valueHints);
        }
    }

    private static void addNodeValues(Session s, Map<String, List<String>> out, String label, String prop) {
        List<String> values = s.run("MATCH (n:`" + label + "`) WHERE n.`" + prop + "` IS NOT NULL " +
                        "RETURN DISTINCT n.`" + prop + "` AS v ORDER BY v LIMIT 25")
                .stream().map(r -> r.get("v").asString()).toList();
        if (!values.isEmpty()) out.put(label + "." + prop, values);
    }

    private static void addRelValues(Session s, Map<String, List<String>> out, String type, String prop) {
        List<String> values = s.run("MATCH ()-[r:`" + type + "`]->() WHERE r.`" + prop + "` IS NOT NULL " +
                        "RETURN DISTINCT r.`" + prop + "` AS v ORDER BY v LIMIT 25")
                .stream().map(r -> r.get("v").asString()).toList();
        if (!values.isEmpty()) out.put(type + "." + prop, values);
    }

    private static String format(
            TreeMap<String, TreeSet<String>> labelProps,
            TreeMap<String, TreeSet<String>> relEndpoints,
            Map<String, List<String>> valueHints) {

        StringBuilder sb = new StringBuilder();

        sb.append("NODE LABELS:\n");
        labelProps.forEach((label, props) ->
                sb.append("- ").append(label).append(" { ").append(String.join(", ", props)).append(" }\n"));

        sb.append("\nRELATIONSHIPS:\n");
        relEndpoints.values().stream()
                .flatMap(TreeSet::stream)
                .forEach(line -> sb.append("- ").append(line).append("\n"));

        if (!valueHints.isEmpty()) {
            sb.append("\nVALUE HINTS (enumerable property values):\n");
            valueHints.forEach((key, values) ->
                    sb.append("- ").append(key).append(": ").append(String.join(", ", values)).append("\n"));
        }

        return sb.toString();
    }

    /** Renders an endpoint as {@code (Src)-[:TYPE {props}]->(Dst)}. */
    private static String endpoint(String src, String type, String dst, TreeSet<String> props) {
        String p = (props == null || props.isEmpty()) ? "" : " {" + String.join(", ", props) + "}";
        return "(" + src + ")-[:" + type + p + "]->(" + dst + ")";
    }

    /** {@code db.schema.relTypeProperties} yields the type as {@code :`TYPE`}. */
    private static String stripRelType(String raw) {
        String s = raw.startsWith(":") ? raw.substring(1) : raw;
        return s.replace("`", "");
    }
}
