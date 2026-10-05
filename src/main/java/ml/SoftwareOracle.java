package ml;

import ml.oracle.importer.GitImporter;
import ml.oracle.importer.Importer;
import ml.oracle.importer.JavaImporter;
import ml.oracle.importer.JiraImporter;
import ml.oracle.importer.MavenImporter;
import ml.oracle.infrastructure.Neo4jService;
import ml.oracle.model.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class SoftwareOracle {

    private static final Logger logger = LoggerFactory.getLogger(SoftwareOracle.class);

    public static void main(String[] args) throws Exception {

        AppConfig config = AppConfig.load();

        if (config.neo4jPassword().isEmpty()) {
            throw new IllegalStateException(
                    "Neo4j password is empty. Set 'neo4j.password' in application.properties.");
        }

        try (Neo4jService neo4j = new Neo4jService(config.neo4jUri(), config.neo4jUser(), config.neo4jPassword())) {

            List<Importer> importers = new ArrayList<>();
            try {
                for (String importerType : config.importers()) {
                    switch (importerType.toLowerCase()) {
                        case "git" -> importers.add(new GitImporter(config.repoPath()));
                        case "java" -> importers.add(new JavaImporter(config.repoPath()));
                        case "maven" -> importers.add(new MavenImporter(config.repoPath()));
                        case "jira" -> importers.add(new JiraImporter(config.repoPath(),
                                config.jiraUri(), config.jiraEmail(), config.jiraToken(),
                                config.jiraProjectKeys()));
                        default -> logger.warn("Unknown importer type: {}", importerType);
                    }
                }

                for (Importer importer : importers) {
                    logger.info("Running importer: {}", importer.name());
                    importer.importRepository(neo4j);
                }
            } finally {
                for (Importer importer : importers) {
                    try {
                        importer.close();
                    } catch (Exception e) {
                        logger.error("Error closing importer", e);
                    }
                }
            }
        }
    }
}
