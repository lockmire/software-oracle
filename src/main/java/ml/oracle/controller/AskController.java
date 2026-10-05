package ml.oracle.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import ml.oracle.service.OracleService;
import ml.oracle.model.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AskController {
    private static final Logger logger = LoggerFactory.getLogger(AskController.class);
    private final OracleService oracle;

    // Very simple in-memory rate limiting (max 5 requests per 10 seconds per IP)
    private final Map<String, RequestCounter> rateLimits = new ConcurrentHashMap<>();
    private static final int MAX_REQUESTS = 5;
    private static final long WINDOW_MS = 10000;

    private record RequestCounter(AtomicInteger count, long windowStart) {}

    /** Request body for {@code POST /api/ask}. Unknown JSON fields are ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AskRequest(String question, boolean refresh) {}

    public AskController(OracleService oracle) {
        this.oracle = oracle;
    }

    public void ask(Context ctx) {
        String ip = ctx.ip();
        if (isRateLimited(ip)) {
            ctx.status(HttpStatus.TOO_MANY_REQUESTS).json(Map.of("error", "Too many requests. Please wait."));
            return;
        }

        try {
            AskRequest body = ctx.bodyAsClass(AskRequest.class);
            String question = body.question() == null ? "" : body.question().trim();
            boolean refresh = body.refresh();
            if (question.isEmpty()) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Missing 'question'"));
                return;
            }

            Answer answer = oracle.ask(question, refresh);
            ctx.json(answer);

        } catch (SecurityException e) {
            logger.warn("Refused unsafe Cypher: {}", e.getMessage());
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Generated query was not read-only"));
        } catch (Exception e) {
            logger.error("Ask failed after all retry attempts", e);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of(
                    "error", "The oracle couldn't answer that question. Try rephrasing it or ask something more specific."));
        }
    }

    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();

        // Bound memory: drop windows that have already expired so the map does
        // not grow unboundedly with every distinct client IP.
        rateLimits.entrySet().removeIf(e -> (now - e.getValue().windowStart()) > WINDOW_MS);

        // Capture the post-increment count inside the atomic compute so the
        // decision is not based on a value read outside the lock.
        int[] currentCount = new int[1];
        rateLimits.compute(ip, (key, old) -> {
            if (old == null || (now - old.windowStart()) > WINDOW_MS) {
                currentCount[0] = 1;
                return new RequestCounter(new AtomicInteger(1), now);
            }
            currentCount[0] = old.count().incrementAndGet();
            return old;
        });
        return currentCount[0] > MAX_REQUESTS;
    }
}
