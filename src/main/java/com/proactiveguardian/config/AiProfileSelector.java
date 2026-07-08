package com.proactiveguardian.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Automatically selects the AI provider before the {@link org.springframework.context.ApplicationContext}
 * refreshes. Runs in three modes, tried in order:
 *
 * <ol>
 *   <li><b>OpenAI (default)</b> — {@code OPENAI_API_KEY} (env) or
 *       {@code guardian.openai-api-key} is set to a real value. Nothing extra
 *       is done; Spring AI uses the OpenAI starter as configured.</li>
 *   <li><b>OpenAI-compatible local endpoint</b> — {@code SPRING_AI_OPENAI_BASE_URL}
 *       (or {@code spring.ai.openai.base-url}) is set, but no real API key
 *       is provided. Useful for LM Studio ({@code http://localhost:1234/v1}),
 *       llama.cpp's {@code llama-server}, vLLM, or an internal corporate LLM
 *       gateway. A dummy key is injected so the OpenAI starter boots, and
 *       {@code spring.ai.model.*} stays on {@code openai} so requests hit the
 *       overridden base URL.</li>
 *   <li><b>Ollama (final fallback)</b> — nothing else is configured. Activates
 *       the {@code local} Spring profile which flips
 *       {@code spring.ai.model.chat=ollama} + {@code spring.ai.model.embedding=ollama}
 *       (see {@code application-local.yml}). A dummy OpenAI key is still
 *       injected so the co-loaded OpenAI auto-config's {@code Assert.hasText}
 *       check passes without ever being used.</li>
 * </ol>
 *
 * <p>Registered in {@code META-INF/spring.factories}.</p>
 */
public class AiProfileSelector implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(AiProfileSelector.class);

    /** Same placeholder used in application.yml's {@code ${OPENAI_API_KEY:...}} default. */
    private static final String PLACEHOLDER = "disabled-set-OPENAI_API_KEY";
    private static final String LOCAL_PROFILE = "local";
    private static final String DUMMY_KEY = "sk-local-no-openai-key-needed";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        // Read the raw env var (or bound guardian.* property) BEFORE Spring
        // resolves the ${OPENAI_API_KEY:disabled-...} placeholder in application.yml.
        String key = env.getProperty("OPENAI_API_KEY");
        if (isMissing(key)) {
            key = env.getProperty("guardian.openai-api-key");
        }
        String baseUrl = env.getProperty("SPRING_AI_OPENAI_BASE_URL");
        if (isBlank(baseUrl)) {
            baseUrl = env.getProperty("spring.ai.openai.base-url");
        }

        if (!isMissing(key)) {
            log.info("[AiProfileSelector] OPENAI_API_KEY detected — using OpenAI provider.");
            return;
        }

        // No real key. Do we have a custom base URL pointing at an OpenAI-compatible
        // local server (LM Studio / llama.cpp / vLLM / internal gateway)?
        if (!isBlank(baseUrl)) {
            log.info("[AiProfileSelector] No OPENAI_API_KEY but SPRING_AI_OPENAI_BASE_URL='{}' is set — "
                    + "using OpenAI adapter against that endpoint with a dummy key.", baseUrl);
            Map<String, Object> overrides = new LinkedHashMap<>();
            overrides.put("guardian.openai-api-key", DUMMY_KEY);
            overrides.put("spring.ai.openai.api-key", DUMMY_KEY);
            // Provider stays 'openai'; base-url override is what redirects traffic.
            env.getPropertySources().addFirst(
                    new MapPropertySource("aiProfileSelectorOverrides", overrides));
            return;
        }

        // Nothing configured — fall back to local Ollama.
        log.info("[AiProfileSelector] No usable OPENAI_API_KEY or SPRING_AI_OPENAI_BASE_URL — "
                + "activating '{}' profile (Spring AI → Ollama at http://localhost:11434).",
                LOCAL_PROFILE);
        env.addActiveProfile(LOCAL_PROFILE);
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("guardian.openai-api-key", DUMMY_KEY);
        overrides.put("spring.ai.openai.api-key", DUMMY_KEY);
        env.getPropertySources().addFirst(
                new MapPropertySource("aiProfileSelectorOverrides", overrides));
    }

    private static boolean isMissing(String v) {
        return v == null || v.isBlank() || PLACEHOLDER.equals(v);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}

