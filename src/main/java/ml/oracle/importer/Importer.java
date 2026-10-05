package ml.oracle.importer;

public interface Importer extends AutoCloseable {
    void importRepository(ml.oracle.infrastructure.Neo4jService neo4j) throws Exception;

    /** Human-readable name for logging. Defaults to the simple class name. */
    default String name() {
        return getClass().getSimpleName();
    }
}
