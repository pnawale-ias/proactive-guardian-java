package com.proactiveguardian.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Envelope pushed to SQS by the upstream GitHub webhook relay service. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SqsGithubEventEnvelope(
        String source,
        String eventType,
        @JsonProperty("rawPayload") GithubPullRequestEvent rawPayload
) {}
