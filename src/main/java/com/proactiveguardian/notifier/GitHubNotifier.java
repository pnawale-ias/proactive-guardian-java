package com.proactiveguardian.notifier;

import com.proactiveguardian.config.GuardianProperties;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.model.Severity;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Port of {@code src/notifiers/github_notifier.py::GitHubNotifier}. */
@Component
@ConditionalOnProperty(name = "guardian.github.enabled", havingValue = "true", matchIfMissing = true)
public class GitHubNotifier {

    private static final Logger log = LoggerFactory.getLogger(GitHubNotifier.class);
    private static final Map<Severity, String> SEV_EMOJI = Map.of(
            Severity.INFO,  "ℹ️",
            Severity.WARN,  "⚠️",
            Severity.BLOCK, "🚫"
    );

    /** Hidden HTML marker embedded in every comment so we can detect duplicates. */
    private static final String MARKER_PREFIX = "<!-- proactive-guardian:";
    private static final String MARKER_SUFFIX = " -->";

    private final GitHub gh;

    public GitHubNotifier(GuardianProperties props) {
        GitHub instance;
        try {
            instance = new GitHubBuilder().withOAuthToken(resolveToken(props)).build();
        } catch (IOException e) {
            log.warn("GitHub client init failed, falling back to anonymous: {}", e.getMessage());
            try {
                instance = GitHub.connectAnonymously();
            } catch (IOException ee) {
                throw new IllegalStateException("Cannot init GitHub client", ee);
            }
        }
        this.gh = instance;
    }

    private static String resolveToken(GuardianProperties props) {
        try {
            ProcessBuilder pb = new ProcessBuilder("gh", "auth", "token")
                    .redirectErrorStream(true);
            pb.environment().remove("GITHUB_TOKEN");
            Process p = pb.start();
            String token = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (!token.isBlank()) return token;
        } catch (Exception e) {
            log.debug("gh auth token unavailable, falling back to configured token: {}", e.getMessage());
        }
        return props.githubToken() == null ? "" : props.githubToken();
    }

    public void postPrComment(String repoFullName, int prNumber, List<Finding> findings) {
        if (findings == null || findings.isEmpty()) return;
        try {
            GHRepository repo = gh.getRepository(repoFullName);
            GHPullRequest pr = repo.getPullRequest(prNumber);
            String body = render(findings);
            String marker = MARKER_PREFIX + fingerprint(findings) + MARKER_SUFFIX;

            for (GHIssueComment existing : pr.getComments()) {
                String eb = existing.getBody();
                if (eb == null) continue;
                // Exact-fingerprint match: same set of findings already reported.
                if (eb.contains(marker)) {
                    log.info("GitHub PR comment already present on {}#{} (fingerprint match) — skipping",
                            repoFullName, prNumber);
                    return;
                }
                // Any prior Guardian comment on this PR — skip too, so we don't
                // spam the PR every polling cycle while findings are stable.
                // Set guardian.github.always-comment=true to disable this guard.
                if (eb.contains(MARKER_PREFIX)) {
                    log.info("GitHub PR {}#{} already has a Proactive Guardian comment — skipping",
                            repoFullName, prNumber);
                    return;
                }
            }
            pr.comment(marker + "\n" + body);
        } catch (IOException e) {
            log.warn("GitHub PR comment failed on {}#{}: {}", repoFullName, prNumber, e.getMessage());
        }
    }

    /**
     * Stable fingerprint over the finding identity tuple
     * {@code (severity | category | title)} — deliberately IGNORES the rendered
     * detail body and confidence, both of which fluctuate between runs when
     * the LLM is enabled and would otherwise defeat deduplication.
     */
    static String fingerprint(List<Finding> findings) {
        List<String> keys = new java.util.ArrayList<>();
        for (Finding f : findings) {
            keys.add((f.severity() == null ? "" : f.severity().name())
                    + "|" + (f.category() == null ? "" : f.category())
                    + "|" + (f.title()    == null ? "" : f.title()));
        }
        java.util.Collections.sort(keys);
        String joined = String.join("\n", keys);
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", d[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(joined.hashCode());
        }
    }

    String render(List<Finding> findings) {
        long blocks = findings.stream().filter(f -> f.severity() == Severity.BLOCK).count();
        long warns  = findings.stream().filter(f -> f.severity() == Severity.WARN).count();
        long infos  = findings.stream().filter(f -> f.severity() == Severity.INFO).count();

        StringBuilder sb = new StringBuilder("## 🛡️ Proactive Guardian Report\n");
        sb.append("> ");
        if (blocks > 0) sb.append("🚫 ").append(blocks).append(" blocking  ");
        if (warns  > 0) sb.append("⚠️ ").append(warns).append(" warning  ");
        if (infos  > 0) sb.append("ℹ️ ").append(infos).append(" info");
        sb.append("\n\n");

        for (Severity sev : List.of(Severity.BLOCK, Severity.WARN, Severity.INFO)) {
            List<Finding> group = findings.stream()
                    .filter(f -> f.severity() == sev)
                    .sorted(Comparator.comparingDouble(Finding::confidence).reversed())
                    .toList();
            if (group.isEmpty()) continue;

            String emoji = SEV_EMOJI.getOrDefault(sev, "•");
            sb.append("### ").append(emoji).append(' ').append(sev.name()).append('\n');

            for (Finding f : group) {
                String titlePart = f.sourceUrl() != null && !f.sourceUrl().isBlank()
                        ? "[" + f.title() + "](" + f.sourceUrl() + ")"
                        : f.title();
                sb.append("<details><summary><strong>").append(titlePart).append("</strong>")
                  .append(" &nbsp;`").append(f.category()).append("`")
                  .append(" &nbsp;").append(Math.round(f.confidence() * 100)).append("%")
                  .append("</summary>\n\n");
                if (f.detail() != null && !f.detail().isBlank()) {
                    sb.append(f.detail()).append("\n\n");
                }
                sb.append("</details>\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
