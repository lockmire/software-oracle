package ml.oracle.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ml.oracle.infrastructure.Neo4jService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds an issue-tracking layer on top of the git graph:
 *
 * <pre>
 *   Commit       -[:REFERENCES]->  Issue        (issue keys in commit messages)
 *   MergeRequest -[:MERGED_AS]->   Commit       (the merge commit)
 *   MergeRequest -[:INCLUDES]->    Commit       (the commits that MR brought in)
 *   MergeRequest -[:IMPLEMENTS]->  Issue        (keys in source branch / merge message)
 *   Issue        -[:ASSIGNED_TO]-> Developer    (when Jira exposes the assignee's email)
 * </pre>
 *
 * <p>MergeRequest nodes are derived from merge commits (2+ parents) in the
 * local history, so they exist without any API access. The source branch and
 * MR/PR number are parsed from conventional merge-commit messages ("Merge pull
 * request #12 from owner/branch", "Merge branch 'feature/ABC-12-x'"), and the
 * included commits are those reachable from the merge's second parent but not
 * from its first.
 *
 * <p>Issue nodes start as stubs (just the key, matched with
 * {@code PROJECT-123} patterns). When {@code jira.uri/email/token} are
 * configured, each stub is enriched via the Jira REST API with summary,
 * status, type, priority and assignee; keys Jira does not know (404) simply
 * stay stubs. When {@code jira.projectKeys} is set, only those project
 * prefixes are recognised (recommended — it eliminates false positives like
 * UTF-8 and lets branch names match case-insensitively).
 *
 * <p>Run after the git importer: commits are MATCHed, not MERGEd, so links are
 * only created to commits that already exist. Idempotent like the other
 * importers.
 */
public class JiraImporter implements Importer {

    private static final Logger logger = LoggerFactory.getLogger(JiraImporter.class);

    private static final Pattern ISSUE_KEY = Pattern.compile("\\b([A-Z][A-Z0-9]{1,9})-(\\d+)\\b");
    private static final Pattern PR_MERGE = Pattern.compile("^Merge pull request #(\\d+) from (\\S+)");
    private static final Pattern BRANCH_MERGE = Pattern.compile("^Merge (?:remote-tracking )?branch '([^']+)'");

    // Common ALLCAPS-number patterns that are not Jira keys; only consulted
    // when no explicit jira.projectKeys are configured.
    private static final Set<String> NOT_PROJECTS = Set.of(
            "UTF", "SHA", "MD", "ISO", "RFC", "CVE", "AES", "RSA",
            "HTTP", "HTML", "JSON", "API", "JDK", "OAUTH");

    // Safety cap so a pathological merge (e.g. of a whole vendored tree)
    // does not create tens of thousands of INCLUDES edges.
    private static final int MAX_MR_COMMITS = 1000;

    private final String repositoryPath;
    private final String jiraUri;
    private final String jiraEmail;
    private final String jiraToken;
    private final List<String> projectKeys;

    // Every distinct issue key seen anywhere, for the enrichment pass.
    private final Set<String> foundKeys = new LinkedHashSet<>();

    public JiraImporter(String repositoryPath, String jiraUri, String jiraEmail,
                        String jiraToken, List<String> projectKeys) {
        this.repositoryPath = repositoryPath;
        this.jiraUri = jiraUri == null ? "" : jiraUri.replaceAll("/$", "");
        this.jiraEmail = jiraEmail == null ? "" : jiraEmail;
        this.jiraToken = jiraToken == null ? "" : jiraToken;
        this.projectKeys = projectKeys == null ? List.of() : projectKeys;
    }

    @Override
    public void importRepository(Neo4jService neo4j) throws Exception {

        try (Git git = Git.open(new File(repositoryPath))) {
            Repository repo = git.getRepository();

            ObjectId head = repo.resolve("HEAD");
            if (head == null) {
                logger.warn("Repository has no HEAD, skipping jira import");
                return;
            }

            try (RevWalk revWalk = new RevWalk(repo)) {
                revWalk.markStart(revWalk.parseCommit(head));

                for (RevCommit commit : revWalk) {
                    Set<String> keys = extractKeys(commit.getFullMessage());
                    if (!keys.isEmpty()) {
                        neo4j.linkCommitToIssues(commit.getName(), new ArrayList<>(keys));
                        foundKeys.addAll(keys);
                    }
                    if (commit.getParentCount() > 1) {
                        processMergeCommit(repo, commit, neo4j);
                    }
                }
            }
        }

        if (!jiraUri.isBlank()) {
            enrichFromJira(neo4j);
        } else {
            logger.info("jira.uri not configured — {} Issue stubs created from git history only",
                    foundKeys.size());
        }
    }

    private void processMergeCommit(Repository repo, RevCommit commit, Neo4jService neo4j) throws Exception {

        String title = commit.getShortMessage();
        Integer number = null;
        String sourceBranch = null;

        Matcher pr = PR_MERGE.matcher(title);
        Matcher branch = BRANCH_MERGE.matcher(title);
        if (pr.find()) {
            number = Integer.parseInt(pr.group(1));
            sourceBranch = pr.group(2);
        } else if (branch.find()) {
            sourceBranch = branch.group(1);
        }

        // Committer time: for merges that is the moment of merging, which may
        // differ from the author time of the merged work.
        Instant mergedAt = commit.getCommitterIdent().getWhen().toInstant();
        neo4j.createMergeRequest(commit.getName(), number, title, sourceBranch, mergedAt);

        // Issue keys from the branch name (lenient) and the merge message.
        Set<String> keys = extractKeysFromBranch(sourceBranch);
        keys.addAll(extractKeys(commit.getFullMessage()));
        if (!keys.isEmpty()) {
            neo4j.linkMergeRequestIssues(commit.getName(), new ArrayList<>(keys));
            foundKeys.addAll(keys);
        }

        // Commits the MR brought in: reachable from the second parent but not
        // from the first (the branch the merge landed on).
        List<String> included = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(commit.getParent(1).getId()));
            walk.markUninteresting(walk.parseCommit(commit.getParent(0).getId()));
            for (RevCommit merged : walk) {
                included.add(merged.getName());
                if (included.size() >= MAX_MR_COMMITS) {
                    logger.warn("Merge {} includes more than {} commits, truncating INCLUDES",
                            commit.getName(), MAX_MR_COMMITS);
                    break;
                }
            }
        }
        if (!included.isEmpty()) {
            neo4j.linkMergeRequestCommits(commit.getName(), included);
        }
    }

    /** Extracts issue keys from free text (uppercase form only). */
    private Set<String> extractKeys(String text) {
        Set<String> keys = new LinkedHashSet<>();
        if (text == null) return keys;
        Matcher m = ISSUE_KEY.matcher(text);
        while (m.find()) {
            String prefix = m.group(1);
            if (!projectKeys.isEmpty()) {
                if (projectKeys.contains(prefix)) keys.add(m.group());
            } else if (!NOT_PROJECTS.contains(prefix)) {
                keys.add(m.group());
            }
        }
        return keys;
    }

    /**
     * Branch names usually carry the key in lowercase ("feature/abc-12-fix"),
     * so when explicit project keys are configured they are also matched
     * case-insensitively and normalised to the canonical uppercase key.
     */
    private Set<String> extractKeysFromBranch(String branchName) {
        Set<String> keys = extractKeys(branchName);
        if (branchName == null) return keys;
        for (String projectKey : projectKeys) {
            Matcher m = Pattern.compile("(?i)\\b" + Pattern.quote(projectKey) + "-(\\d+)\\b")
                    .matcher(branchName);
            while (m.find()) {
                keys.add(projectKey.toUpperCase() + "-" + m.group(1));
            }
        }
        return keys;
    }

    /** Enriches every Issue stub found in git via the Jira REST API. */
    private void enrichFromJira(Neo4jService neo4j) {

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        String auth = Base64.getEncoder().encodeToString(
                (jiraEmail + ":" + jiraToken).getBytes(StandardCharsets.UTF_8));
        ObjectMapper mapper = new ObjectMapper();

        int enriched = 0;
        for (String key : foundKeys) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(jiraUri + "/rest/api/2/issue/" + key
                                + "?fields=summary,status,issuetype,priority,assignee"))
                        .header("Authorization", "Basic " + auth)
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404) {
                    logger.debug("Issue {} not found in Jira, keeping stub", key);
                    continue;
                }
                if (response.statusCode() != 200) {
                    logger.warn("Jira returned {} for {}", response.statusCode(), key);
                    continue;
                }

                JsonNode fields = mapper.readTree(response.body()).path("fields");
                neo4j.enrichIssue(key,
                        fields.path("summary").asText(null),
                        fields.path("status").path("name").asText(null),
                        fields.path("issuetype").path("name").asText(null),
                        fields.path("priority").path("name").asText(null),
                        fields.path("assignee").path("displayName").asText(null),
                        fields.path("assignee").path("emailAddress").asText(null),
                        jiraUri + "/browse/" + key);
                enriched++;

            } catch (Exception e) {
                logger.warn("Failed to enrich {} from Jira: {}", key, e.getMessage());
            }
        }
        logger.info("Enriched {}/{} issues from Jira", enriched, foundKeys.size());
    }

    @Override
    public void close() {
        // Shared Neo4jService is managed by caller
    }
}
