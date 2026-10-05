package ml.oracle.service;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hard guard against destructive Cypher reaching the database.
 *
 * <p>The LLM is instructed in its system prompt to emit only read-only queries,
 * but we never trust that. Before any query is executed, it is validated to
 * ensure no forbidden write keywords or administrative commands are present.
 */
public final class QueryValidator {

    // Keywords that indicate a write operation.
    private static final Set<String> FORBIDDEN_WRITE = Set.of(
            "CREATE", "MERGE", "SET", "DELETE", "DETACH", "REMOVE",
            "LOAD", "FOREACH"
    );

    // Keywords for schema or administrative changes.
    private static final Set<String> FORBIDDEN_ADMIN = Set.of(
            "DROP", "ALTER", "GRANT", "REVOKE", "DENY", "RENAME", "STOP", "START", "TERMINATE"
    );

    private static final Pattern STRING_LITERAL =
            Pattern.compile("'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\"");

    private static final Pattern COMMENT =
            Pattern.compile("//.*|/\\*.*?\\*/", Pattern.DOTALL);

    // A procedure CALL is "CALL" followed by anything other than a "{" (a subquery opener).
    // Read-only "CALL { ... }" subqueries are allowed; "CALL db.labels()" etc. are not.
    private static final Pattern PROCEDURE_CALL =
            Pattern.compile("\\bCALL\\b(?!\\s*\\{)", Pattern.CASE_INSENSITIVE);

    private QueryValidator() {}

    /**
     * Ensures the given Cypher query is read-only and doesn't contain administrative commands.
     *
     * @param cypher the Cypher query to validate
     * @throws SecurityException if the query is deemed unsafe
     */
    public static void requireReadOnly(String cypher) {

        if (cypher == null || cypher.isBlank()) {
            throw new IllegalArgumentException("Empty Cypher query");
        }

        // 1. Remove comments and string literals so a forbidden keyword inside them never trips us.
        // We remove comments FIRST so that strings inside comments don't confuse us,
        // although in Cypher strings can't span lines unless they are block strings (not supported here).
        String sanitised = COMMENT.matcher(cypher).replaceAll(" ");
        sanitised = STRING_LITERAL.matcher(sanitised).replaceAll(" ");

        // 2. Check for CALL to potentially writing procedures.
        // Read-only "CALL { ... }" subqueries are permitted (the system prompt relies on them),
        // but procedure calls such as "CALL db.labels()" or "CALL apoc.*" are refused.
        if (PROCEDURE_CALL.matcher(sanitised).find()) {
            throw new SecurityException("Refused CALL procedure for safety: " + cypher);
        }

        // 3. Tokenise and check against forbidden sets.
        for (String token : sanitised.split("[^A-Za-z_]+")) {
            String upperToken = token.toUpperCase();
            if (FORBIDDEN_WRITE.contains(upperToken)) {
                throw new SecurityException(
                        "Refused to execute write Cypher (keyword '" + token + "'): " + cypher);
            }
            if (FORBIDDEN_ADMIN.contains(upperToken)) {
                throw new SecurityException(
                        "Refused to execute administrative Cypher (keyword '" + token + "'): " + cypher);
            }
        }
    }
}
