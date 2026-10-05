package ml.oracle.service;

import ml.oracle.model.Answer;
import ml.oracle.model.QueryResult;
import ml.oracle.infrastructure.QueryExecutor;
import ml.oracle.service.QueryValidator;
import org.neo4j.driver.exceptions.TransientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end pipeline for a single question:
 *
 * <pre>
 *   question -> CypherGenerator -> QueryValidator -> QueryExecutor -> AnswerFormatter -> Answer
 * </pre>
 *
 * <p>The schema is introspected once per request — cheap enough, and ensures
 * newly imported data is reflected without a server restart.
 */
public class OracleService {

    private static final Logger logger = LoggerFactory.getLogger(OracleService.class);

    private final SchemaIntrospector schema;
    private final CypherGenerator generator;
    private final QueryExecutor executor;
    private final AnswerFormatter formatter;

    public OracleService(
            SchemaIntrospector schema,
            CypherGenerator generator,
            QueryExecutor executor,
            AnswerFormatter formatter) {

        this.schema = schema;
        this.generator = generator;
        this.executor = executor;
        this.formatter = formatter;
    }

    private static final int MAX_ATTEMPTS = 3;

    public Answer ask(String question, boolean refreshSchema) throws Exception {

        logger.info("Question: {}", question);

        if (refreshSchema) {
            schema.refresh();
        }
        String schemaDescription = schema.describe();

        String cypher = null;
        Exception lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                cypher = (attempt == 1)
                        ? generator.generate(question, schemaDescription)
                        : generator.regenerate(question, schemaDescription, cypher, lastError.getMessage());

                QueryValidator.requireReadOnly(cypher);

                // Plan-check with EXPLAIN before executing: catches syntax errors
                // and unknown labels/relationships/properties cheaply, feeding any
                // problem back into the next regeneration attempt.
                executor.validate(cypher);

                QueryResult result = executor.run(cypher);
                logger.info("Query returned {} row(s) on attempt {}", result.rows().size(), attempt);

                String answer = formatter.format(question, cypher, result.columns(), result.rows());
                String type = (result.columns().size() > 1 || result.rows().size() > 5) ? "table" : "text";
                return new Answer(answer, cypher, type, result.columns(), result.rows());

            } catch (SecurityException e) {
                throw e; // write-query violation — never retry
            } catch (TransientException e) {
                // Resource limit (memory, locks) — the driver already retried internally.
                // Re-generating the same query won't help, so fail fast.
                throw new RuntimeException(
                        "Query exceeded Neo4j resource limits. Try a simpler or more specific question.", e);
            } catch (Exception e) {
                lastError = e;
                logger.warn("Attempt {}/{} failed — {}: {}", attempt, MAX_ATTEMPTS,
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }

        throw lastError;
    }
}
