package com.proactiveguardian.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proactiveguardian.config.GuardianProperties;
import com.proactiveguardian.web.dto.GithubPullRequestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Polls GitHub for open pull requests using the {@code gh} CLI so no PAT
 * configuration is needed — {@code gh}'s own auth (keyring / GITHUB_TOKEN env
 * var) is used instead.
 *
 * <p>Enable with {@code guardian.github-poll-enabled=true} (or env
 * {@code GITHUB_POLL_ENABLED=true}). Default interval is 60 s.</p>
 */
@Component
@ConditionalOnProperty(name = "guardian.github-poll-enabled", havingValue = "true")
public class GitHubPollingService {

    private static final Logger log = LoggerFactory.getLogger(GitHubPollingService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GuardianProperties props;
    private final PullRequestPipelineService pipeline;

    /** PR number → last processed updated_at string (ISO-8601). */
    private final Map<Integer, String> lastSeen = new HashMap<>();

    public GitHubPollingService(GuardianProperties props, PullRequestPipelineService pipeline) {
        this.props = props;
        this.pipeline = pipeline;
    }

    @Scheduled(
            fixedDelayString  = "${guardian.github-poll-interval-ms:60000}",
            initialDelayString = "${guardian.github-poll-interval-ms:60000}"
    )
    public void poll() {
        String ownerRepo = props.githubOwnerRepo();
        if (ownerRepo == null) {
            log.debug("Poller skipped: guardian.github-repo-url not configured or not a github.com URL");
            return;
        }

        try {
            String json = runGhApi(ownerRepo);
            JsonNode prs = MAPPER.readTree(json);

            int processed = 0, skippedDraft = 0, skippedUnchanged = 0;

            for (JsonNode pr : prs) {
                if (pr.path("draft").asBoolean(false)) {
                    skippedDraft++;
                    log.debug("PR #{} '{}' — ignored (draft)", pr.path("number").asInt(), pr.path("title").asText());
                    continue;
                }

                int number = pr.path("number").asInt();
                String updatedAt = pr.path("updated_at").asText("");
                String seen = lastSeen.get(number);

                if (updatedAt.equals(seen)) {
                    skippedUnchanged++;
                    continue;
                }

                log.info("Polled PR #{} '{}' (updated {}) — dispatching to pipeline",
                        number, pr.path("title").asText(), updatedAt);

                pipeline.process(toEvent(pr));
                lastSeen.put(number, updatedAt);
                processed++;
            }

            log.info("Poll cycle {}: open={}, dispatched={}, unchanged={}, drafts={}",
                    ownerRepo, prs.size(), processed, skippedUnchanged, skippedDraft);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("GitHub poll failed for {}: {}", ownerRepo, e.getMessage());
        }
    }

    private static String runGhApi(String ownerRepo) throws IOException, InterruptedException {
        String endpoint = "repos/" + ownerRepo + "/pulls?state=open&per_page=100";
        ProcessBuilder pb = new ProcessBuilder("gh", "api", endpoint)
                .redirectErrorStream(true);
        // Use the token gh itself is configured with (keyring/oauth) rather than
        // GITHUB_TOKEN from the environment, which may be a fine-grained PAT without
        // sufficient repo access.
        String ghToken = resolveGhToken();
        if (ghToken != null) {
            pb.environment().put("GH_TOKEN", ghToken);
            pb.environment().remove("GITHUB_TOKEN");
        }
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("gh api exited " + exit + ": " + output.trim());
        }
        return output;
    }

    private static String resolveGhToken() {
        try {
            ProcessBuilder pb = new ProcessBuilder("gh", "auth", "token")
                    .redirectErrorStream(true);
            // gh auth token echoes GITHUB_TOKEN if set — remove it so we get
            // the real keyring/oauth token instead of the env var.
            pb.environment().remove("GITHUB_TOKEN");
            Process p = pb.start();
            String token = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return token.isBlank() ? null : token;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private static GithubPullRequestEvent toEvent(JsonNode pr) {
        String repoFullName = pr.path("repo_full_name").asText(
                pr.at("/base/repo/full_name").asText());
        String cloneUrl = pr.path("clone_url").asText(
                pr.at("/base/repo/clone_url").asText());
        return new GithubPullRequestEvent(
                "synchronize",
                new GithubPullRequestEvent.Repository(repoFullName, cloneUrl),
                new GithubPullRequestEvent.PullRequest(
                        pr.path("number").asInt(),
                        new GithubPullRequestEvent.Ref(pr.path("base_sha").asText(
                                pr.at("/base/sha").asText())),
                        new GithubPullRequestEvent.Ref(pr.path("head_sha").asText(
                                pr.at("/head/sha").asText()))
                )
        );
    }
}
