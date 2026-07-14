package com.proactiveguardian.ingestion;

import com.proactiveguardian.knowledge.GraphStore;
import com.proactiveguardian.knowledge.VectorStore;
import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.ArtifactType;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * JGit-backed replacement for {@code src/ingestion/git_ingester.py::GitIngester}.
 *
 * <p>Provides three operations:
 * <ul>
 *   <li>{@link #ingestRepo(Path, String)} — full-tree walk + upsert.</li>
 *   <li>{@link #diffChangedArtifacts(Path, String, String, String)} — parse the
 *       HEAD version of every changed file (backwards-compat helper).</li>
 *   <li>{@link #diffChangedPairs(Path, String, String, String)} — the primary
 *       PR API: returns {@code (before, after)} tuples, extracting the pre-PR
 *       blob into a temp file so {@link CodeParser} can re-use its file-based
 *       code path.</li>
 * </ul>
 */
@Component
public class GitIngester {

    private static final Logger log = LoggerFactory.getLogger(GitIngester.class);
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "build", "dist", "target", ".gradle", ".idea"
    );

    private final VectorStore vs;
    private final GraphStore gs;
    private final CodeParser codeParser;

    public GitIngester(VectorStore vs, GraphStore gs, CodeParser codeParser) {
        this.vs = vs;
        this.gs = gs;
        this.codeParser = codeParser;
    }

    /** Recursively parse every non-skipped file in {@code localPath}. */
    public List<Artifact> ingestRepo(Path localPath, String repoName) {
        List<Artifact> artifacts = new ArrayList<>();
        try (Stream<Path> walker = Files.walk(localPath)) {
            walker.filter(Files::isRegularFile)
                  .filter(p -> !isSkipped(p))
                  .forEach(p -> artifacts.addAll(codeParser.parseFile(p, repoName)));
        } catch (IOException e) {
            log.warn("Failed to walk {}: {}", localPath, e.getMessage());
        }
        vs.upsert(artifacts);
        for (Artifact a : artifacts) gs.upsertArtifact(a);
        inferEdges(artifacts);
        return artifacts;
    }

    private void inferEdges(List<Artifact> artifacts) {
        Map<String, Artifact> byName = new HashMap<>();
        for (Artifact a : artifacts) if (a.name() != null) byName.put(a.name(), a);

        // 1) name-based REFERENCES edges for CODE_SYMBOL cross-links
        for (Artifact a : artifacts) {
            if (a.type() != ArtifactType.CODE_SYMBOL) continue;
            for (Map.Entry<String, Artifact> e : byName.entrySet()) {
                if (!e.getValue().id().equals(a.id())
                        && e.getKey() != null && !e.getKey().isBlank()
                        && a.content() != null && a.content().contains(e.getKey())) {
                    gs.link(a.id(), e.getValue().id(), "REFERENCES");
                }
            }
        }

        // 2) Data-plane edges
        for (Artifact a : artifacts) {
            Map<String, Object> meta = a.metadata() == null ? Map.of() : a.metadata();
            if (a.type() == ArtifactType.DATA_REFERENCE) {
                Object fqn = meta.get("table_fqn");
                if (fqn == null) continue;
                Object cols = meta.get("columns");
                Object kind = meta.getOrDefault("read_or_write", "read");
                gs.linkDataReference(a.id(), fqn.toString(), asStringList(cols), kind.toString());
            } else if (a.type() == ArtifactType.ORM_MODEL) {
                Object fqn = meta.get("table_fqn");
                if (fqn == null) continue;
                gs.linkOrmToTable(a.id(), fqn.toString(), asStringList(meta.get("columns")));
            } else if (a.type() == ArtifactType.SQL_VIEW) {
                Object viewOf = meta.get("view_of");
                for (String tbl : asStringList(viewOf)) {
                    gs.linkDataReference(a.id(), tbl, List.of(), "read");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Diff-based ingestion
    // ------------------------------------------------------------------
    public List<Artifact> diffChangedArtifacts(Path localPath, String repoName,
                                               String baseSha, String headSha) {
        List<Artifact> changed = new ArrayList<>();
        try (Git git = Git.open(localPath.toFile());
             Repository repo = git.getRepository()) {
            for (DiffEntry d : diffEntries(repo, baseSha, headSha)) {
                String bPath = d.getNewPath();
                if (bPath == null || DiffEntry.DEV_NULL.equals(bPath)) continue;
                Path p = localPath.resolve(bPath);
                if (Files.exists(p)) changed.addAll(codeParser.parseFile(p, repoName));
            }
        } catch (Exception e) {
            log.warn("diffChangedArtifacts failed: {}", e.getMessage());
        }
        return changed;
    }

    public DiffResult diffChangedPairs(Path localPath, String repoName,
                                       String baseSha, String headSha) {
        List<Pair> pairs = new ArrayList<>();
        String fileSummary = "";
        String commitMessage = "";
        try (Git git = Git.open(localPath.toFile());
             Repository repo = git.getRepository()) {

            List<DiffEntry> entries = diffEntries(repo, baseSha, headSha);
            log.info("diffEntries {} base={} head={} → {} entry/entries",
                    repoName, shortSha(baseSha), shortSha(headSha), entries.size());
            if (entries.isEmpty()) {
                log.warn("no diff entries — is the head SHA reachable in the clone? "
                        + "(fork PR? shallow clone? wrong SHA?)");
            }

            // Build compact file summary from diff entries (capped to avoid bloat)
            StringBuilder summaryBuf = new StringBuilder();
            for (DiffEntry d : entries) {
                if (summaryBuf.length() > 280) { summaryBuf.append(", …"); break; }
                if (summaryBuf.length() > 0) summaryBuf.append(", ");
                String p = d.getNewPath() != null && !DiffEntry.DEV_NULL.equals(d.getNewPath())
                        ? d.getNewPath() : d.getOldPath();
                summaryBuf.append(d.getChangeType().name(), 0, 1).append(' ').append(p);
            }
            fileSummary = summaryBuf.toString();

            // Extract head commit short message
            try (org.eclipse.jgit.revwalk.RevWalk rw = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
                commitMessage = rw.parseCommit(ObjectId.fromString(headSha)).getShortMessage();
            } catch (Exception ignored) {}

            for (DiffEntry d : entries) {
                String path = d.getNewPath() != null && !DiffEntry.DEV_NULL.equals(d.getNewPath())
                        ? d.getNewPath() : d.getOldPath();
                List<Artifact> beforeArts = List.of();
                List<Artifact> afterArts  = List.of();

                if (d.getNewPath() != null && !DiffEntry.DEV_NULL.equals(d.getNewPath())) {
                    Path p = localPath.resolve(d.getNewPath());
                    if (Files.exists(p)) {
                        List<Artifact> parsed = codeParser.parseFile(p, repoName);
                        // Stamp blob URL on each after-artifact for source linking
                        afterArts = parsed.stream()
                                .map(a -> stampUrl(a, repoName, headSha, d.getNewPath()))
                                .toList();
                    }
                }
                if (d.getOldPath() != null && !DiffEntry.DEV_NULL.equals(d.getOldPath())) {
                    // Keep the base-blob for the lifetime of the PR temp dir so
                    // downstream detectors (BreakingChangeDetector) can re-read
                    // the raw file, not just the JavaParser-normalised snippet.
                    Path tmp = extractBlobInto(repo, baseSha, d.getOldPath(), localPath);
                    if (tmp != null) {
                        beforeArts = codeParser.parseFile(tmp, repoName);
                    }
                }

                if (beforeArts.isEmpty() && afterArts.isEmpty()) {
                    log.info("  skip {} ({}) — CodeParser produced 0 artifacts "
                            + "(unsupported extension or file yielded no symbols)",
                            path, d.getChangeType());
                    continue;
                }

                Map<String, Artifact> beforeByName = new HashMap<>();
                for (Artifact a : beforeArts) if (a.name() != null) beforeByName.put(a.name(), a);
                Map<String, Artifact> afterByName = new HashMap<>();
                for (Artifact a : afterArts)  if (a.name() != null) afterByName.put(a.name(), a);

                int added = 0;
                for (Map.Entry<String, Artifact> e : afterByName.entrySet()) {
                    pairs.add(new Pair(beforeByName.get(e.getKey()), e.getValue()));
                    added++;
                }
                for (Map.Entry<String, Artifact> e : beforeByName.entrySet()) {
                    if (!afterByName.containsKey(e.getKey())) {
                        pairs.add(new Pair(e.getValue(), null));
                        added++;
                    }
                }
                log.info("  {} ({}) → {} pair(s) [before={}, after={}]",
                        path, d.getChangeType(), added, beforeArts.size(), afterArts.size());
            }
        } catch (Exception e) {
            log.warn("diffChangedPairs failed {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
        }
        return new DiffResult(pairs, fileSummary, commitMessage);
    }

    private static Artifact stampUrl(Artifact a, String repoName, String sha, String filePath) {
        if (a.url() != null && !a.url().isBlank()) return a;
        Object startLine = a.metadata().get("start_line");
        int line = startLine instanceof Number n ? n.intValue() + 1 : 1;
        String url = "https://github.com/" + repoName + "/blob/" + sha + "/" + filePath + "#L" + line;
        return a.withUrl(url);
    }

    /** Result of {@link #diffChangedPairs}: pairs plus PR-level metadata. */
    public record DiffResult(List<Pair> pairs, String fileSummary, String commitMessage) {}

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(7, sha.length()));
    }

    // ------------------------------------------------------------------
    // Low-level JGit helpers
    // ------------------------------------------------------------------
    private static List<DiffEntry> diffEntries(Repository repo, String baseSha, String headSha) throws IOException {
        try (RevWalk rw = new RevWalk(repo)) {
            RevCommit baseCommit = rw.parseCommit(ObjectId.fromString(baseSha));
            RevCommit headCommit = rw.parseCommit(ObjectId.fromString(headSha));
            try (org.eclipse.jgit.lib.ObjectReader reader = repo.newObjectReader()) {
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, baseCommit.getTree());
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, headCommit.getTree());
                try (Git g = new Git(repo)) {
                    return g.diff().setOldTree(oldTree).setNewTree(newTree).call();
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }
        }
    }

    /** Extract a file version at {@code sha} into a temp file and return its Path. */
    private static Path extractBlob(Repository repo, String sha, String repoRelativePath) {
        try (RevWalk rw = new RevWalk(repo)) {
            RevCommit commit = rw.parseCommit(ObjectId.fromString(sha));
            try (TreeWalk tw = TreeWalk.forPath(repo, repoRelativePath, commit.getTree())) {
                if (tw == null) return null;
                ObjectId blobId = tw.getObjectId(0);
                ObjectLoader loader = repo.open(blobId);
                String suffix = repoRelativePath.contains(".")
                        ? repoRelativePath.substring(repoRelativePath.lastIndexOf('.'))
                        : "";
                Path tmp = Files.createTempFile("guardian-blob-", suffix);
                try (OutputStream os = Files.newOutputStream(tmp)) {
                    loader.copyTo(os);
                }
                return tmp;
            }
        } catch (Exception e) {
            log.debug("blob extract failed for {}@{}: {}", repoRelativePath, sha, e.getMessage());
            return null;
        }
    }

     /**
     * Extract the file at {@code sha} into {@code <clonePath>/.guardian-base/<repoRelativePath>}
     * so it survives for the whole pipeline run and is cleaned up when the
     * caller deletes the PR temp dir. Returns {@code null} on failure.
     */
    private static Path extractBlobInto(Repository repo, String sha,
                                        String repoRelativePath, Path clonePath) {
        try (RevWalk rw = new RevWalk(repo)) {
            RevCommit commit = rw.parseCommit(ObjectId.fromString(sha));
            try (TreeWalk tw = TreeWalk.forPath(repo, repoRelativePath, commit.getTree())) {
                if (tw == null) return null;
                ObjectLoader loader = repo.open(tw.getObjectId(0));
                Path dst = clonePath.resolve(".guardian-base").resolve(repoRelativePath);
                Files.createDirectories(dst.getParent());
                try (OutputStream os = Files.newOutputStream(dst)) {
                    loader.copyTo(os);
                }
                return dst;
            }
        } catch (Exception e) {
            log.debug("blob extract-into failed for {}@{}: {}", repoRelativePath, sha, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    private static boolean isSkipped(Path p) {
        String s = p.toString();
        for (String skip : SKIP_DIRS) if (s.contains(skip)) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object x : l) out.add(String.valueOf(x));
            return out;
        }
        return List.of();
    }

    /** {@code (before, after)} tuple emitted by {@link #diffChangedPairs}. */
    public record Pair(Artifact before, Artifact after) {}
}

