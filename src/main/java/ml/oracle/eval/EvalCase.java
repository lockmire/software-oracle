package ml.oracle.eval;

import java.util.List;

/**
 * A single evaluation case for the natural-language → Cypher pipeline.
 *
 * @param id             short stable identifier used in the report
 * @param question       the natural-language question fed to the model
 * @param cypher         a trusted reference query whose result set defines the
 *                       correct answer (any generated query returning the same
 *                       data passes, regardless of exact phrasing)
 * @param orderSensitive when true the row order must match too (for "top N" /
 *                       ranking questions); otherwise results are compared as an
 *                       unordered multiset of rows
 * @param requires       node labels that must exist in the graph for the case to
 *                       be meaningful (e.g. {@code ["Type"]} for Java cases,
 *                       {@code ["Module"]} for Maven ones). When any label is
 *                       absent — different importer set, or a repo without that
 *                       language — the case is skipped instead of failed, and it
 *                       does not count towards accuracy. Null/empty = always run.
 */
public record EvalCase(String id, String question, String cypher, boolean orderSensitive,
                       List<String> requires) {}
