package com.proactiveguardian.web;

import com.proactiveguardian.agent.GuardianOrchestrator;
import com.proactiveguardian.config.GuardianProperties;
import com.proactiveguardian.ingestion.GitIngester;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.notifier.GitHubNotifier;
import com.proactiveguardian.web.dto.GithubPullRequestEvent;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Async PR-processing service — direct port of {@code src/main.py::process_pr}.
 * Runs on the {@code guardianWorker} executor so the webhook returns immediately.
 */
@Service
public class PullRequestPipelineService {

    private static final Logger log = LoggerFactory.getLogger(PullRequestPipelineService.class);

    private final GuardianProperties props;
    private final GitIngester gitIngester;
    private final GuardianOrchestrator orchestrator;
    private final ObjectProvider<GitHubNotifier> notifier;

    public PullRequestPipelineService(GuardianProperties props,
                                      GitIngester gitIngester,
                                      GuardianOrchestrator orchestrator,
                                      ObjectProvider<GitHubNotifier> notifier) {
        this.props = props;
        this.gitIngester = gitIngester;
        this.orchestrator = orchestrator;
        this.notifier = notifier;
    }

    @Async("guardianWorker")
    public void process(GithubPullRequestEvent payload) {
        String repoName = payload.repository().fullName();
        String cloneUrl = payload.repository().cloneUrl().replace(
                "https://",
                "https://x-access-token:" + (props.githubToken() == null ? "" : props.githubToken()) + "@"
        );
        int prNumber = payload.pullRequest().number();
        String baseSha = payload.pullRequest().base().sha();
        String headSha = payload.pullRequest().head().sha();

        Path tmp = null;
        try {
            long t0 = System.currentTimeMillis();
            log.info("pipeline start {}#{} base={} head={}",
                    repoName, prNumber, shortSha(baseSha), shortSha(headSha));

            tmp = Files.createTempDirectory("guardian-pr-");
            try (Git git = Git.cloneRepository().setURI(cloneUrl).setDirectory(tmp.toFile()).call()) {
                // A plain `git clone` fetches refs/heads/* only. PRs from
                // forks (and sometimes cross-branch merges) live under
                // refs/pull/N/head, so the head SHA would be unreachable.
                // Fetch it explicitly — this is the same trick `hub` / `gh` use.
                try {
                    git.fetch()
                       .setRefSpecs(new RefSpec(
                           "+refs/pull/" + prNumber + "/head:refs/remotes/origin/pr-" + prNumber))
                       .call();
                    log.debug("fetched refs/pull/{}/head for {}", prNumber, repoName);
                } catch (Exception fe) {
                    // Non-fatal: same-repo PRs are already reachable via the branch ref.
                    log.debug("PR ref fetch skipped/failed ({}): {}",
                            fe.getClass().getSimpleName(), fe.getMessage());
                }
            }
            log.debug("cloned {} into {} ({} ms)", repoName, tmp, System.currentTimeMillis() - t0);

            List<GitIngester.Pair> pairs = gitIngester.diffChangedPairs(tmp, repoName, baseSha, headSha);
            log.info("diff produced {} changed pair(s) for {}#{}", pairs.size(), repoName, prNumber);

            List<Finding> allFindings = new ArrayList<>();
            for (GitIngester.Pair p : pairs) {
                allFindings.addAll(orchestrator.analyzeChange(p.before(), p.after()));
            }
            log.info("pipeline done  {}#{} — {} finding(s) across {} pair(s) in {} ms",
                    repoName, prNumber, allFindings.size(), pairs.size(),
                    System.currentTimeMillis() - t0);

            notifier.ifAvailable(n -> {
                log.info("posting PR comment to {}#{} ({} finding(s))", repoName, prNumber, allFindings.size());
                n.postPrComment(repoName, prNumber, allFindings);
            });
        } catch (Exception e) {
            log.error("PR processing failed for {}#{}: {}", repoName, prNumber, e.getMessage(), e);
        } finally {
            if (tmp != null) deleteRecursively(tmp);
        }
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(7, sha.length()));
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) { /* best-effort cleanup */ }
    }
}

