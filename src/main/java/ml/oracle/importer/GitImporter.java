package ml.oracle.importer;

import ml.oracle.infrastructure.Neo4jService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class GitImporter implements Importer {

    private static final Logger logger = LoggerFactory.getLogger(GitImporter.class);

    private final String repositoryPath;

    // Rename chain (old path -> current path) collected while walking history
    // newest-first, so a file's churn and MODIFIED edges always accrue to its
    // present-day path instead of splitting across every historical name.
    private final Map<String, String> renameMap = new HashMap<>();

    public GitImporter(String repositoryPath) {
        this.repositoryPath = repositoryPath;
    }

    @Override
    public void importRepository(Neo4jService neo4j) throws Exception {

        try (Git git = Git.open(new File(repositoryPath))) {

            Repository repo = git.getRepository();

            importTags(git, neo4j);

            ObjectId head = repo.resolve("HEAD");
            if (head == null) {
                logger.warn("Repository has no HEAD, skipping git import");
                return;
            }

            try (RevWalk revWalk = new RevWalk(repo)) {

                revWalk.markStart(revWalk.parseCommit(head));

                for (RevCommit commit : revWalk) {

                    processCommit(repo, commit, neo4j);
                }
            }

            markFilesAtHead(repo, head, neo4j);
        }
    }

    /**
     * Flags every SourceFile with whether it still exists in the HEAD tree.
     * Files only present in history (deleted or renamed away) keep their churn
     * data but get {@code existsAtHead=false} so queries can exclude them.
     */
    private void markFilesAtHead(Repository repo, ObjectId head, Neo4jService neo4j) throws Exception {
        List<String> paths = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit headCommit = walk.parseCommit(head);
            try (TreeWalk treeWalk = new TreeWalk(repo)) {
                treeWalk.addTree(headCommit.getTree());
                treeWalk.setRecursive(true);
                while (treeWalk.next()) {
                    paths.add(treeWalk.getPathString());
                }
            }
        }
        neo4j.markSourceFilesAtHead(paths);
    }

    private void importTags(Git git, Neo4jService neo4j) throws Exception {
        List<Ref> tags = git.tagList().call();
        for (Ref tag : tags) {
            String tagName = tag.getName().replace("refs/tags/", "");
            ObjectId commitId = tag.getPeeledObjectId();
            if (commitId == null) commitId = tag.getObjectId();
            neo4j.createTag(tagName, commitId.getName());
        }
    }

    @Override
    public String name() {
        return "GitImporter";
    }

    @Override
    public void close() {
        // Shared Neo4jService is managed by caller
    }

    private void processCommit(Repository repo, RevCommit commit, Neo4jService neo4j) throws Exception {

        String hash = commit.getName();

        // Skip commits that have already been imported so re-runs are cheap
        // and never create duplicate nodes or relationships.
        if (neo4j.commitExists(hash)) {
            logger.debug("Commit {} already imported, skipping", hash);
            return;
        }

        String authorName = commit.getAuthorIdent().getName();
        String authorEmail = commit.getAuthorIdent().getEmailAddress();
        String message = commit.getFullMessage();
        // Use the author date so the timestamp matches the author identity we
        // record (getCommitTime() is the committer's date, e.g. of a rebase).
        Instant timestamp = commit.getAuthorIdent().getWhen().toInstant();

        List<String> parents = Stream.of(commit.getParents()).map(RevCommit::getName).toList();

        int totalInsertions = 0;
        int totalDeletions = 0;

        try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

            df.setRepository(repo);
            df.setDetectRenames(true);

            // For the root commit (no parents) diff against an empty tree so its
            // files are imported too; otherwise diff against the first parent.
            AbstractTreeIterator oldTree =
                    commit.getParentCount() > 0
                            ? prepareTreeParser(repo, commit.getParent(0))
                            : new EmptyTreeIterator();

            AbstractTreeIterator newTree = prepareTreeParser(repo, commit);

            List<DiffEntry> diffs = df.scan(oldTree, newTree);

            // Collect the touched paths and write them all in a single batched
            // round-trip instead of two statements per file.
            List<String> filePaths = new ArrayList<>();
            for (DiffEntry diff : diffs) {
                String newPath = diff.getNewPath();
                if (newPath == null || newPath.equals(DiffEntry.DEV_NULL)) {
                    continue;
                }

                // Record renames so this and older commits attribute their churn
                // to the file's current path rather than a since-renamed one.
                if (diff.getChangeType() == DiffEntry.ChangeType.RENAME) {
                    renameMap.put(diff.getOldPath(), canonicalPath(newPath));
                }
                String filePath = canonicalPath(newPath);
                filePaths.add(filePath);

                // Churn for Commit
                FileHeader fileHeader = df.toFileHeader(diff);
                for (Edit edit : fileHeader.toEditList()) {
                    totalInsertions += edit.getEndB() - edit.getBeginB();
                    totalDeletions += edit.getEndA() - edit.getBeginA();
                }

                // Churn for SourceFile
                neo4j.updateSourceFileChurn(filePath, timestamp);
            }

            neo4j.createCommit(hash, authorName, authorEmail, message, timestamp, totalInsertions, totalDeletions, parents);
            neo4j.linkCommitToSourceFiles(hash, filePaths);

            // Co-change coupling
            if (filePaths.size() > 1 && filePaths.size() <= 20) { // skip huge commits for coupling
                List<Map<String, Object>> couplings = new ArrayList<>();
                for (int i = 0; i < filePaths.size(); i++) {
                    for (int j = i + 1; j < filePaths.size(); j++) {
                        Map<String, Object> coupling = new HashMap<>();
                        coupling.put("file1", filePaths.get(i));
                        coupling.put("file2", filePaths.get(j));
                        couplings.add(coupling);
                    }
                }
                neo4j.addCoChangeCoupling(couplings);
            }
        }
    }

    /**
     * Follows the rename chain to a file's current path. Because history is
     * walked newest-first, a path renamed later already has its mapping recorded,
     * so resolving transitively (A→B→C) yields the present-day name.
     */
    private String canonicalPath(String path) {
        String current = path;
        Set<String> seen = new HashSet<>();
        while (renameMap.containsKey(current) && seen.add(current)) {
            current = renameMap.get(current);
        }
        return current;
    }

    private AbstractTreeIterator prepareTreeParser(Repository repo, RevCommit commit) throws Exception {

        try (RevWalk walk = new RevWalk(repo)) {

            RevTree tree = walk.parseTree(commit.getTree().getId());

            CanonicalTreeParser parser = new CanonicalTreeParser();

            try (ObjectReader reader = repo.newObjectReader()) {
                parser.reset(reader, tree.getId());
            }

            walk.dispose();

            return parser;
        }
    }
}
