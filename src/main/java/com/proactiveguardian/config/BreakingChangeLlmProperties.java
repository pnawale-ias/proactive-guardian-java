package com.proactiveguardian.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knobs for {@link com.proactiveguardian.agent.BreakingChangeDetector}'s
 * LLM-assisted cross-repo impact prediction. Bound to the
 * {@code guardian.breaking.llm} prefix. All fields have sensible defaults so
 * the properties block can be omitted entirely in {@code application.yml}.
 */
@ConfigurationProperties(prefix = "guardian.breaking.llm")
public record BreakingChangeLlmProperties(
        Boolean enabled,
        Integer maxConsumers,
        Integer maxSnippetChars,
        Integer maxChangeChars,
        Long timeoutMs,
        Double minConsumerConfidence
) {
    public BreakingChangeLlmProperties {
        if (enabled == null) enabled = Boolean.TRUE;
        if (maxConsumers == null || maxConsumers <= 0) maxConsumers = 8;
        if (maxSnippetChars == null || maxSnippetChars <= 0) maxSnippetChars = 500;
        if (maxChangeChars == null || maxChangeChars <= 0) maxChangeChars = 1500;
        if (timeoutMs == null || timeoutMs <= 0) timeoutMs = 15_000L;
        if (minConsumerConfidence == null || minConsumerConfidence <= 0) minConsumerConfidence = 0.6d;
    }

    /** Convenience factory for tests. */
    public static BreakingChangeLlmProperties defaults() {
        return new BreakingChangeLlmProperties(null, null, null, null, null, null);
    }

    /** Convenience factory for tests that need to disable the LLM path. */
    public static BreakingChangeLlmProperties disabled() {
        return new BreakingChangeLlmProperties(Boolean.FALSE, null, null, null, null, null);
    }
}

