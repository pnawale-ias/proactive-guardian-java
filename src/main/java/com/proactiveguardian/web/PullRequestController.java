package com.proactiveguardian.web;

import com.proactiveguardian.config.GuardianProperties;
import com.proactiveguardian.web.dto.GithubPullRequestEvent;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manual on-demand PR analyzer. Useful when polling is disabled or when you
 * want to re-analyze a specific PR immediately without waiting for the next
 * poll tick.
 *
 * <p>Example:</p>
 * <pre>curl -X POST 'http://localhost:8080/pr/analyze?number=42'</pre>
 *
 * <p>When {@code owner_repo} is omitted, {@code guardian.github-repo-url} is
 * used.</p>
 */
@RestController
@RequestMapping("/pr")
public class PullRequestController {

    private static final Logger log = LoggerFactory.getLogger(PullRequestController.class);

    private final GuardianProperties props;
    private final PullRequestPipelineService pipeline;

    public PullRequestController(GuardianProperties props, PullRequestPipelineService pipeline) {
        this.props = props;
        this.pipeline = pipeline;
    }

    @PostMapping("/analyze")
    public Map<String, Object> analyze(
            @RequestParam("number") int number,
            @RequestParam(value = "owner_repo", required = false) String ownerRepo) throws IOException {

        if (ownerRepo == null || ownerRepo.isBlank()) ownerRepo = props.githubOwnerRepo();
        if (ownerRepo == null) {
            return Map.of("ok", false, "reason",
                    "owner_repo not supplied and guardian.github-repo-url is not a github.com URL");
        }

        String token = resolveGhToken();
        GitHub gh = (token == null || token.isBlank())
                ? GitHub.connectAnonymously()
                : new GitHubBuilder().withOAuthToken(token).build();

        GHRepository repo = gh.getRepository(ownerRepo);
        GHPullRequest pr = repo.getPullRequest(number);

        GithubPullRequestEvent evt = new GithubPullRequestEvent(
                "synchronize",
                new GithubPullRequestEvent.Repository(repo.getFullName(), repo.getHttpTransportUrl()),
                new GithubPullRequestEvent.PullRequest(
                        pr.getNumber(),
                        new GithubPullRequestEvent.Ref(pr.getBase().getSha()),
                        new GithubPullRequestEvent.Ref(pr.getHead().getSha())
                )
        );

        log.info("Manual analyze dispatch for {}#{}", ownerRepo, number);
        pipeline.process(evt);  // @Async — returns immediately

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("owner_repo", ownerRepo);
        out.put("number", number);
        out.put("base_sha", pr.getBase().getSha());
        out.put("head_sha", pr.getHead().getSha());
        out.put("dispatched", true);
        return out;
    }

    private static String resolveGhToken() {
        try {
            ProcessBuilder pb = new ProcessBuilder("gh", "auth", "token")
                    .redirectErrorStream(true);
            pb.environment().remove("GITHUB_TOKEN");
            Process p = pb.start();
            String token = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (!token.isBlank()) return token;
        } catch (Exception e) {
            log.debug("gh auth token unavailable: {}", e.getMessage());
        }
        return "";
    }
}

