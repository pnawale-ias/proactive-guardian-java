package com.proactiveguardian.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proactiveguardian.web.dto.GithubPullRequestEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** Port of {@code src/main.py::github_webhook}. */
@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final Set<String> PROCESSED_ACTIONS = Set.of("opened", "synchronize");

    private final ObjectMapper mapper;
    private final PullRequestPipelineService pipeline;

    public WebhookController(ObjectMapper mapper, PullRequestPipelineService pipeline) {
        this.mapper = mapper;
        this.pipeline = pipeline;
    }

    @PostMapping("/github")
    public Map<String, Object> github(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        String event = request.getHeader("X-GitHub-Event");
        if ("pull_request".equals(event)) {
            GithubPullRequestEvent payload = mapper.readValue(body, GithubPullRequestEvent.class);
            if (PROCESSED_ACTIONS.contains(payload.action())) {
                pipeline.process(payload);
            } else {
                log.debug("Ignoring pull_request action={}", payload.action());
            }
        } else {
            log.debug("Ignoring event={}", event);
        }
        return Map.of("ok", true);
    }
}

