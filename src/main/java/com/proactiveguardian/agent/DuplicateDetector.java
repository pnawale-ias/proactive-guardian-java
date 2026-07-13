package com.proactiveguardian.agent;

import com.proactiveguardian.config.GuardianProperties;
import com.proactiveguardian.knowledge.VectorStore;
import com.proactiveguardian.knowledge.VectorStore.Hit;
import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.model.Severity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Port of {@code src/agents/duplicate_detector.py::DuplicateDetector}. */
@Component
public class DuplicateDetector {

    private final VectorStore vs;
    private final GuardianProperties props;

    public DuplicateDetector(VectorStore vs, GuardianProperties props) {
        this.vs = vs;
        this.props = props;
    }

    public List<Finding> check(Artifact artifact) {
        List<Hit> hits = vs.searchSimilar(artifact.name() + "\n" + artifact.content(), 5, artifact.id());
        List<Finding> findings = new ArrayList<>();
        for (Hit h : hits) {
            if (h.score() < props.riskThreshold()) continue;
            String name = h.getStr("name");
            String repo = h.getStr("repo");
            String path = h.getStr("path");
            String url  = h.getStr("url");

            // Skip self-matches: a prior ingestion of the exact same symbol
            // (same repo + path + name) shows up as ~100% similar to the PR
            // version. That's not a duplicate — it's just the previously-
            // ingested copy of the very thing being changed.
            if (sameSymbol(repo, path, name, artifact)) continue;

            // Skip near-identical hits from the SAME repo — a real duplicate
            // signal should be a cross-repo copy (e.g. consumer mirrored the
            // producer's DTO). Same-repo overlaps are usually intra-repo
            // helpers or re-ingestion artifacts.
            if (sameRepo(repo, artifact.repo()) && !samePath(path, artifact.path())) {
                // Same repo, different file — could still be a genuine dup,
                // but require a higher bar to avoid noise.
                if (h.score() < Math.max(props.riskThreshold(), 0.985)) continue;
            }

            String prettyPath = repoRelative(path);
            String prettyRepo = shortRepo(repo);
            String prettyUrl  = url != null ? url : prettyPath;
            findings.add(new Finding(
                    Severity.WARN,
                    "duplicate",
                    "Possible duplicate of `" + name + "`",
                    "New artifact `" + artifact.name() + "` is "
                            + Math.round(h.score() * 100) + "% similar to `" + name
                            + "` in `" + prettyRepo + ": " + prettyPath + "`.",
                    List.of(prettyUrl),
                    h.score()
            ));
        }
        return findings;
    }

    /**
     * True when the hit refers to the same logical symbol as the artifact
     * under review. Comparison is tolerant of:
     *   - tmp-dir path prefixes (ingest and PR pipeline write into different
     *     temp folders); we compare only the repo-relative path suffix.
     *   - repo names in short form ("generateautocode") vs full form
     *     ("softwarepravin2007/generateautocode"); trailing-segment match.
     */
    private static boolean sameSymbol(String repo, String path, String name, Artifact a) {
        return sameName(name, a.name())
                && sameRepo(repo, a.repo())
                && samePath(path, a.path());
    }

    private static boolean sameName(String a, String b) {
        return a != null && a.equals(b);
    }

    private static boolean sameRepo(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        // Trailing segment (owner/repo → repo, or nested paths → last part).
        return lastSegment(a).equals(lastSegment(b));
    }

    private static boolean samePath(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        return repoRelative(a).equals(repoRelative(b));
    }

    private static String lastSegment(String s) {
        int i = s.lastIndexOf('/');
        return i < 0 ? s : s.substring(i + 1);
    }

    /** Short display form of a repo string: {@code owner/repo} -> {@code repo}. */
    private static String shortRepo(String repo) {
        return repo == null ? "?" : lastSegment(repo);
    }

    /**
     * Strip everything up to and including the first well-known source root
     * marker so paths from different tmp dirs compare equal. Falls back to
     * the last 4 segments — enough to distinguish DTOs across DTOs while
     * still ignoring the tmp-dir noise.
     */
    private static String repoRelative(String path) {
        if (path == null) return "";
        // Common Java / Python / Go source roots.
        for (String marker : new String[]{"/src/", "/lib/", "/app/", "/pkg/", "/internal/"}) {
            int idx = path.lastIndexOf(marker);
            if (idx >= 0) return path.substring(idx + 1);
        }
        // Fallback: last 4 slash-separated segments.
        String[] parts = path.split("/");
        if (parts.length <= 4) return path;
        StringBuilder sb = new StringBuilder();
        for (int i = parts.length - 4; i < parts.length; i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

}
