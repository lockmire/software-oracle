package ml.oracle.infrastructure;

import ml.oracle.model.QueryResult;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.summary.Notification;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a Cypher query and converts each row into an order-preserving
 * {@code Map<String,Object>} that is safe to serialise as JSON. Node and
 * relationship values are unwrapped to their properties so the LLM sees plain
 * data instead of driver-specific wrappers.
 */
public class QueryExecutor {

    private final Driver driver;

    public QueryExecutor(Driver driver) {
        this.driver = driver;
    }

    /**
     * Plans the query with {@code EXPLAIN} — which parses and plans it without
     * touching data — to catch mistakes cheaply before the real run. A syntax
     * error surfaces as a driver exception; an unknown label, relationship type
     * or property key surfaces as a planner notification. Both are strong signs
     * the query is mis-grounded, so we throw with the details for the caller to
     * feed back into a regeneration attempt.
     *
     * <p>Only "unknown token" notifications fail validation; performance hints
     * (e.g. cartesian products) are left alone so valid queries still run.
     */
    public void validate(String cypher) {

        try (Session session = driver.session()) {

            List<String> problems = new ArrayList<>();
            for (Notification n : session.run("EXPLAIN " + cypher).consume().notifications()) {
                if (n.code() != null && n.code().contains("Unknown")) {
                    problems.add(n.description());
                }
            }

            if (!problems.isEmpty()) {
                throw new IllegalStateException(
                        "Query references unknown schema elements: " + String.join("; ", problems));
            }
        }
    }

    public QueryResult run(String cypher) {

        try (Session session = driver.session()) {

            return session.executeRead(tx -> {

                var rs = tx.run(cypher);

                List<String> columns = rs.keys();
                List<Map<String, Object>> rows = new ArrayList<>();

                while (rs.hasNext()) {
                    Record record = rs.next();
                    var row = new LinkedHashMap<String, Object>();
                    for (String key : columns) {
                        row.put(key, unwrap(record.get(key)));
                    }
                    rows.add(row);

                    // Safety cap to prevent OOM on accidental "MATCH (n), (m) RETURN *"
                    if (rows.size() >= 1000) {
                        break;
                    }
                }

                return new QueryResult(columns, rows);
            });
        }
    }

    /** Convert driver Value into plain Java so Jackson can serialise it. */
    private static Object unwrap(Value v) {

        if (v == null || v.isNull()) {
            return null;
        }
        Object o = v.asObject();
        if (o instanceof Node n) {
            var m = new LinkedHashMap<String, Object>();
            m.put("_labels", n.labels());
            n.asMap().forEach(m::put);
            return m;
        }
        if (o instanceof Relationship r) {
            var m = new LinkedHashMap<String, Object>();
            m.put("_type", r.type());
            r.asMap().forEach(m::put);
            return m;
        }
        return o;
    }
}
