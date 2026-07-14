package com.proactiveguardian.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Minimal deserialisation of a GitHub {@code pull_request} webhook payload —
 * only the fields Guardian actually uses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubPullRequestEvent(
        String action,
        Repository repository,
        @JsonProperty("pull_request") PullRequest pullRequest
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(
            @JsonProperty("full_name") String fullName,
            @JsonProperty("clone_url") String cloneUrl
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequest(
            int number,
            String title,
            Ref base,
            Ref head
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ref(String sha) {}
}

