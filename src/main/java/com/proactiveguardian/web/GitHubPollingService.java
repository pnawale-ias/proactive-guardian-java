package com.proactiveguardian.web;

import com.proactiveguardian.config.GuardianProperties;
import com.proactiveguardian.web.dto.GithubPullRequestEvent;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Polls the GitHub REST API for open pull requests on the configured repo and
 * hands each new/updated PR to {@link PullRequestPipelineService} — exactly the
 * same code path as the {@code /webhook/github} handler.
 *
 * <p>Purpose: on developer laptops / CI runners with no public inbound URL,
 * GitHub cannot deliver webhooks directly. Polling uses only OUTBOUND HTTPS
 * to {@code api.github.com}, which almost every corporate proxy already
 * permits.</p>
 *
 * <p>Enable with {@code guardian.github-poll-enabled=true} (or env
 * {@code GITHUB_POLL_ENABLED=true}). Default interval is 60 s, override with
 * {@code guardian.github-poll-interval-ms} / {@code GITHUB_POLL_INTERVAL_MS}.</p>
 */
@Component
@ConditionalOnProperty(name = "guardian.github-poll-enabled", havingValue = "true")
public class GitHubPollingService {

    private static final Logger log = LoggerFactory.getLogger(GitHubPollingService.class);

    private final GuardianProperties props;
    private final PullRequestPipelineService pipeline;

    /** PR number → last processed updated_at (millis since epoch). */
    private final Map<Integer, Long> lastSeen = new HashMap<>();

    private final GitHub gh;

    public GitHubPollingService(GuardianProperties props, PullRequestPipelineService pipeline) {
        this.props = props;
        this.pipeline = pipeline;
        this.gh = buildClient(props);
    }

    private static GitHub buildClient(GuardianProperties props) {
        try {
            String token = props.githubToken();
            if (token != null && !token.isBlank()) {
                return new GitHubBuilder().withOAuthToken(token).build();
            }
            log.warn("GITHUB_TOKEN not set — using anonymous GitHub client "
                    + "(60 req/hour rate limit, no access to private repos)");
            return GitHub.connectAnonymously();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build GitHub client", e);
        }
    }

    @Scheduled(
            fixedDelayString = "${guardian.github-poll-interval-ms:60000}",
            initialDelayString = "${guardian.github-poll-interval-ms:60000}"
    )
    public void poll() {
        String ownerRepo = props.githubOwnerRepo();
        if (ownerRepo == null) {
            log.debug("Poller skipped: guardian.github-repo-url not configured or not a github.com URL");
            return;
        }

        try {
            GHRepository repo = gh.getRepository(ownerRepo);
            int processed = 0;
            for (GHPullRequest pr : repo.getPullRequests(GHIssueState.OPEN)) {
                Date updated = pr.getUpdatedAt();
                long updatedMs = updated == null ? 0L : updated.getTime();
                Long seen = lastSeen.get(pr.getNumber());
                if (seen != null && updatedMs <= seen) continue;   // no change since last poll

                log.info("Polled PR #{} '{}' (updated {}) — dispatching to pipeline",
                        pr.getNumber(), pr.getTitle(), updated);
                pipeline.process(toEvent(repo, pr));
                lastSeen.put(pr.getNumber(), updatedMs);
                processed++;
            }
            log.debug("Poll cycle: {} open PR(s), {} dispatched", lastSeen.size(), processed);
        } catch (IOException e) {
            log.warn("GitHub poll failed for {}: {}", ownerRepo, e.getMessage());
        }
    }

    /** Fabricate the same DTO the webhook builds, so we reuse the pipeline verbatim. */
    private static GithubPullRequestEvent toEvent(GHRepository repo, GHPullRequest pr) {
        return new GithubPullRequestEvent(
                "synchronize",
                new GithubPullRequestEvent.Repository(
                        repo.getFullName(),
                        repo.getHttpTransportUrl()   // "https://github.com/owner/repo.git"
                ),
                new GithubPullRequestEvent.PullRequest(
                        pr.getNumber(),
                        new GithubPullRequestEvent.Ref(pr.getBase().getSha()),
                        new GithubPullRequestEvent.Ref(pr.getHead().getSha())
                )
        );
    }
}

