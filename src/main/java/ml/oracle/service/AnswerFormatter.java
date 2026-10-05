package ml.oracle.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ml.oracle.infrastructure.OllamaClient;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class AnswerFormatter {

    private static final Pattern THINK_TAG =
            Pattern.compile("<think>.*?</think>", Pattern.DOTALL);

    // ObjectMapper is thread-safe and relatively expensive to build, so share one.
    private static final ObjectMapper json = new ObjectMapper();

    private final OllamaClient ollama;

    public AnswerFormatter(OllamaClient ollama) {
        this.ollama = ollama;
    }

    public String format(
            String question,
            String cypher,
            List<String> columns,
            List<Map<String, Object>> rows) throws Exception {

        String system = """
                You explain Neo4j query results to a developer in plain prose.

                Rules:
                - Answer in the same language as the user's question (English or Dutch).
                - Be concise: 1-3 short sentences.
                - Quote specific values from the results (names, counts, paths) where helpful.
                - If the result is empty, say so plainly — don't invent data.
                - Do NOT mention Cypher or the query mechanics.
                - Plain text only, no markdown headers, no code fences.
                """;

        String user = """
                Question: %s

                Cypher executed: %s

                Result columns: %s

                Result rows (JSON, may be truncated):
                %s
                """.formatted(
                question,
                cypher,
                columns,
                json.writeValueAsString(rows.size() > 50 ? rows.subList(0, 50) : rows));

        String raw = ollama.chat(system, user);
        return THINK_TAG.matcher(raw).replaceAll("").trim();
    }
}
