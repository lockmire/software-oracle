package ml.oracle;

import io.javalin.Javalin;
import ml.oracle.controller.AskController;
import ml.oracle.infrastructure.OllamaClient;
import ml.oracle.infrastructure.QueryExecutor;
import ml.oracle.model.AppConfig;
import ml.oracle.service.AnswerFormatter;
import ml.oracle.service.CypherGenerator;
import ml.oracle.service.OracleService;
import ml.oracle.service.SchemaIntrospector;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OracleServer {

    private static final Logger logger = LoggerFactory.getLogger(OracleServer.class);

    public static void main(String[] args) throws Exception {

        AppConfig config = AppConfig.load();

        Driver driver = GraphDatabase.driver(config.neo4jUri(), AuthTokens.basic(config.neo4jUser(), config.neo4jPassword()));

        OllamaClient ollama = new OllamaClient(config.ollamaUri(), config.ollamaModel(), config.ollamaTimeout());
        OracleService oracle = new OracleService(
                new SchemaIntrospector(driver),
                new CypherGenerator(ollama),
                new QueryExecutor(driver),
                new AnswerFormatter(ollama));

        AskController askController = new AskController(oracle);

        Javalin app = Javalin.create(javalinConfig -> {
            javalinConfig.staticFiles.add("/static");
            javalinConfig.http.asyncTimeout = config.ollamaTimeout().toMillis() + 10000; // slightly longer than LLM timeout
        });

        app.post("/api/ask", askController::ask);

        app.events(event -> {
            event.serverStopping(() -> {
                logger.info("Shutting down");
                driver.close();
            });
        });

        app.start(config.port());
        logger.info("Oracle server listening on http://localhost:{}  (model: {})", config.port(), config.ollamaModel());
    }
}
