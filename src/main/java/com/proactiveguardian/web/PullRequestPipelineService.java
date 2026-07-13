package com.proactiveguardian.web;

import com.proactiveguardian.agent.ConfluenceDocAdvisor;
import com.proactiveguardian.agent.ConsumerAwarenessAdvisor;
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
    private final ConsumerAwarenessAdvisor consumerAwareness;
    private final ConfluenceDocAdvisor confluenceDocAdvisor;
    private final ObjectProvider<GitHubNotifier> notifier;

    public PullRequestPipelineService(GuardianProperties props,
                                      GitIngester gitIngester,
                                      GuardianOrchestrator orchestrator,
                                      ConsumerAwarenessAdvisor consumerAwareness,
                                      ConfluenceDocAdvisor confluenceDocAdvisor,
                                      ObjectProvider<GitHubNotifier> notifier) {
        this.props = props;
        this.gitIngester = gitIngester;
        this.orchestrator = orchestrator;
        this.consumerAwareness = consumerAwareness;
        this.confluenceDocAdvisor = confluenceDocAdvisor;
        this.notifier = notifier;
    }

    @Async("guardianWorker")
    public void process(GithubPullRequestEvent payload) {
        String repoName = payload.repository().fullName();
        String cloneUrl = payload.repository().cloneUrl().replace(
                "https://",
                "https://x-access-token:" + resolveGitToken() + "@"
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

                // CRITICAL: `git clone` leaves the working tree at the default
                // branch (= the base). Without an explicit checkout, every
                // file under `tmp/` is the *base* version and the pipeline
                // would compare base-vs-base and produce zero findings.
                // Checkout the PR head SHA so the working tree = after.
                try {
                    git.checkout()
                       .setName(headSha)
                       .setForced(true)
                       .call();
                    log.debug("checked out head {} into working tree for {}", shortSha(headSha), repoName);
                } catch (Exception ce) {
                    log.warn("checkout of head {} failed for {}: {} — after-content will fall back to base branch",
                            shortSha(headSha), repoName, ce.getMessage());
                }
            }
            log.debug("cloned {} into {} ({} ms)", repoName, tmp, System.currentTimeMillis() - t0);

            List<GitIngester.Pair> pairs = gitIngester.diffChangedPairs(tmp, repoName, baseSha, headSha);
            log.info("diff produced {} changed pair(s) for {}#{}", pairs.size(), repoName, prNumber);

            List<Finding> allFindings = new ArrayList<>();
            for (GitIngester.Pair p : pairs) {
                allFindings.addAll(orchestrator.analyzeChange(p.before(), p.after()));
            }

            // Always surface the known-consumer blast radius exactly once per PR,
            // even when no pair produced a specific breaking-change signal — a
            // seemingly unrelated change can still break downstream repos.
            try {
                allFindings.addAll(consumerAwareness.advise(repoName, pairs));
            } catch (Exception ce) {
                log.debug("consumer-awareness advisory skipped: {}", ce.toString());
            }

            // Cross-reference the diff against ingested Confluence pages —
            // if a page mentions a class / file that this PR touches, ask
            // the reviewer to update the doc (or confirm it's still valid).
            try {
                allFindings.addAll(confluenceDocAdvisor.advise(pairs));
            } catch (Exception ce) {
                log.debug("confluence-doc advisory skipped: {}", ce.toString());
            }
            // Same file can appear in multiple diff pairs (e.g. rename +
            // modify) and several agents can emit near-identical advisories.
            // Collapse duplicates so the PR comment isn't spammed.
            List<Finding> deduped = dedupe(allFindings);
            log.info("pipeline done  {}#{} — {} finding(s) ({} after dedupe) across {} pair(s) in {} ms",
                    repoName, prNumber, allFindings.size(), deduped.size(), pairs.size(),
                    System.currentTimeMillis() - t0);

            notifier.ifAvailable(n -> {
                log.info("posting PR comment to {}#{} ({} finding(s))", repoName, prNumber, deduped.size());
                n.postPrComment(repoName, prNumber, deduped);
            });
        } catch (Exception e) {
            log.error("PR processing failed for {}#{}: {}", repoName, prNumber, e.getMessage(), e);
        } finally {
            if (tmp != null) deleteRecursively(tmp);
        }
    }

    /**
     * Deduplicate findings by (category, title, sorted-evidence). Keeps the
     * first occurrence — preserves ordering the agents produced. Different
     * wording of the same finding (e.g. two variants of the workflow-schema
     * message for the same file) is collapsed via a secondary key that
     * ignores the free-text title tail after the file path.
     */
    private static List<Finding> dedupe(List<Finding> findings) {
        java.util.LinkedHashMap<String, Finding> byKey = new java.util.LinkedHashMap<>();
        for (Finding f : findings) {
            String ev = f.evidence() == null ? "" :
                    f.evidence().stream().sorted().toList().toString();
            // Primary: exact (cat + title + evidence).
            String primary = f.category() + "|" + f.title() + "|" + ev;
            // Secondary: (cat + evidence) — collapses re-worded duplicates
            // pointing at the same file/location.
            String secondary = f.category() + "||" + ev;
            if (byKey.containsKey(primary) || byKey.containsKey(secondary)) continue;
            byKey.put(primary, f);
            byKey.put(secondary, f);
        }
        // Deduplicate the values (we inserted each finding under two keys).
        java.util.LinkedHashSet<Finding> unique = new java.util.LinkedHashSet<>(byKey.values());
        return new ArrayList<>(unique);
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(7, sha.length()));
    }

    /** Returns the gh OAuth token, falling back to the configured PAT. */
    private String resolveGitToken() {
        try {
            ProcessBuilder pb = new ProcessBuilder("gh", "auth", "token")
                    .redirectErrorStream(true);
            pb.environment().remove("GITHUB_TOKEN");
            Process p = pb.start();
            String token = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (!token.isBlank()) return token;
        } catch (Exception e) {
            log.debug("gh auth token failed, falling back to configured token: {}", e.getMessage());
        }
        return props.githubToken() == null ? "" : props.githubToken();
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) { /* best-effort cleanup */ }
    }
}

