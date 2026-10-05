package ml.oracle.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin wrapper around Ollama's {@code /api/chat} endpoint.
 *
 * <p>One blocking {@link #chat(String, String)} call per prompt: we send a
 * system + user message pair and return the assistant's full reply as a string.
 * Streaming is disabled — the caller wants the whole answer in one go.
 */
public class OllamaClient {

    private static final Logger logger = LoggerFactory.getLogger(OllamaClient.class);

    // ObjectMapper is thread-safe and relatively expensive to build, so share one.
    private static final ObjectMapper json = new ObjectMapper();

    private final String baseUri;
    private final String model;
    private final Duration requestTimeout;
    private final HttpClient http;

    public OllamaClient(
            String baseUri,
            String model,
            Duration timeout) {

        this.baseUri = baseUri.endsWith("/") ? baseUri.substring(0, baseUri.length() - 1) : baseUri;
        this.model = model;
        this.requestTimeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String chat(String system, String user) throws Exception {

        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("stream", false);

        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);

        // Lower temperature → more deterministic Cypher / formatting.
        body.putObject("options").put("temperature", 0.1);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUri + "/api/chat"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

        long start = System.currentTimeMillis();
        logger.debug("Sending request to Ollama (model: {})", model);
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - start;

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "Ollama returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = json.readTree(response.body());
        String content = root.path("message").path("content").asText();

        logger.info("Ollama {} replied in {} ms ({} chars)", model, elapsed, content.length());

        return content;
    }
}
