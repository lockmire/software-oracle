package ml.oracle.model;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record AppConfig(
    int port,
    String ollamaUri,
    String ollamaModel,
    Duration ollamaTimeout,
    String neo4jUri,
    String neo4jUser,
    String neo4jPassword,
    String repoPath,
    List<String> importers,
    String jiraUri,
    String jiraEmail,
    String jiraToken,
    List<String> jiraProjectKeys
) {
    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);

    public static AppConfig load() {
        Properties props = new Properties();
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in == null) {
                throw new IllegalStateException("application.properties not found on classpath");
            }
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load application.properties", e);
        }

        return new AppConfig(
            Integer.parseInt(props.getProperty("oracle.port", "8080")),
            props.getProperty("ollama.uri", "http://localhost:11434"),
            props.getProperty("ollama.model", "llama3:latest"),
            Duration.ofSeconds(Long.parseLong(props.getProperty("ollama.timeoutSeconds", "180"))),
            props.getProperty("neo4j.uri", "").trim(),
            props.getProperty("neo4j.username", "").trim(),
            props.getProperty("neo4j.password", "").trim(),
            props.getProperty("repo.path", "").trim(),
            Arrays.asList(props.getProperty("oracle.importers", "git,java").split(","))
                .stream().map(String::trim).filter(s -> !s.isEmpty()).toList(),
            props.getProperty("jira.uri", "").trim(),
            props.getProperty("jira.email", "").trim(),
            props.getProperty("jira.token", "").trim(),
            Arrays.asList(props.getProperty("jira.projectKeys", "").split(","))
                .stream().map(String::trim).filter(s -> !s.isEmpty()).toList()
        );
    }
}
