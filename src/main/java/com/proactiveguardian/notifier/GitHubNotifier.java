package com.proactiveguardian.notifier;

import com.proactiveguardian.config.GuardianProperties;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.model.Severity;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
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

    private final GitHub gh;

    public GitHubNotifier(GuardianProperties props) {
        GitHub instance;
        try {
            instance = new GitHubBuilder().withOAuthToken(
                    props.githubToken() == null ? "" : props.githubToken()
            ).build();
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

    public void postPrComment(String repoFullName, int prNumber, List<Finding> findings) {
        if (findings == null || findings.isEmpty()) return;
        try {
            GHRepository repo = gh.getRepository(repoFullName);
            repo.getPullRequest(prNumber).comment(render(findings));
        } catch (IOException e) {
            log.warn("GitHub PR comment failed on {}#{}: {}", repoFullName, prNumber, e.getMessage());
        }
    }

    String render(List<Finding> findings) {
        StringBuilder sb = new StringBuilder("## 🛡️ Proactive Guardian Report\n\n");
        findings.stream()
                .sorted(Comparator.comparingDouble(Finding::confidence).reversed())
                .forEach(f -> {
                    String emoji = SEV_EMOJI.getOrDefault(f.severity(), "•");
                    sb.append("### ").append(emoji).append(' ').append(f.title()).append('\n')
                      .append("**Category:** `").append(f.category()).append("` · ")
                      .append("**Confidence:** ").append(Math.round(f.confidence() * 100)).append("%\n\n")
                      .append(f.detail() == null ? "" : f.detail()).append('\n');
                    if (f.evidence() != null && !f.evidence().isEmpty()) {
                        sb.append("\n**Evidence:**\n");
                        for (String e : f.evidence()) sb.append("- ").append(e).append('\n');
                    }
                    sb.append('\n');
                });
        return sb.toString();
    }
}

