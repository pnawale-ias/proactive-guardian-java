package com.proactiveguardian.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for Proactive Guardian, replacing {@code src/config.py}
 * ({@code pydantic-settings}). Bound to the {@code guardian.*} prefix in
 * {@code application.yml} or environment variables (e.g. {@code GUARDIAN_OPENAI_API_KEY}).
 */
@ConfigurationProperties(prefix = "guardian")
public record GuardianProperties(
        String openaiApiKey,
        String embeddingModel,
        String llmModel,

        String qdrantUrl,
        String qdrantCollection,
        String qdrantApiKey,

        String neo4jUri,
        String neo4jUser,
        String neo4jPass,

        String githubToken,
        String githubWebhookSecret,
        String githubRepoUrl,
        String githubRepoName,
        Boolean githubPollEnabled,
        Long githubPollIntervalMs,

        String confluenceBaseUrl,
        String confluenceUser,
        String confluenceToken,

        String databricksHost,
        String databricksToken,
        String databricksDefaultCatalog,
        String defaultSqlDialect,

        Boolean mysqlEnabled,
        String mysqlUrl,
        String mysqlUser,
        String mysqlPassword,
        String mysqlDatabase,

        double riskThreshold
) {
    public GuardianProperties {
        if (embeddingModel == null || embeddingModel.isBlank()) embeddingModel = "text-embedding-3-large";
        if (llmModel == null || llmModel.isBlank()) llmModel = "gpt-4o";
        if (qdrantUrl == null || qdrantUrl.isBlank()) qdrantUrl = "http://localhost:6333";
        if (qdrantCollection == null || qdrantCollection.isBlank()) qdrantCollection = "code_and_docs";
        if (neo4jUri == null || neo4jUri.isBlank()) neo4jUri = "bolt://localhost:7687";
        if (neo4jUser == null || neo4jUser.isBlank()) neo4jUser = "neo4j";
        if (neo4jPass == null || neo4jPass.isBlank()) neo4jPass = "guardianpass";
        if (defaultSqlDialect == null || defaultSqlDialect.isBlank()) defaultSqlDialect = "databricks";
        if (mysqlEnabled == null) mysqlEnabled = Boolean.FALSE;
        if (riskThreshold <= 0) riskThreshold = 0.75d;

        // If a repo URL is set but no name, derive the name from the last path segment.
        if ((githubRepoName == null || githubRepoName.isBlank())
                && githubRepoUrl != null && !githubRepoUrl.isBlank()) {
            String u = githubRepoUrl.trim();
            if (u.endsWith(".git")) u = u.substring(0, u.length() - 4);
            int slash = u.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < u.length()) {
                githubRepoName = u.substring(slash + 1);
            }
        }

        if (githubPollEnabled == null)   githubPollEnabled = Boolean.FALSE;
        if (githubPollIntervalMs == null || githubPollIntervalMs <= 0) githubPollIntervalMs = 60_000L;
    }

    /**
     * Parse {@code owner/repo} from {@link #githubRepoUrl()}. Returns {@code null}
     * if the URL is missing or does not look like {@code https://github.com/owner/repo(.git)?}.
     */
    public String githubOwnerRepo() {
        if (githubRepoUrl == null || githubRepoUrl.isBlank()) return null;
        String u = githubRepoUrl.trim();
        if (u.endsWith(".git")) u = u.substring(0, u.length() - 4);
        int host = u.indexOf("github.com/");
        if (host < 0) return null;
        String tail = u.substring(host + "github.com/".length());
        int slash = tail.indexOf('/');
        if (slash <= 0 || slash + 1 >= tail.length()) return null;
        // Trim any trailing path segments
        int end = tail.indexOf('/', slash + 1);
        return (end < 0) ? tail : tail.substring(0, end);
    }
}
