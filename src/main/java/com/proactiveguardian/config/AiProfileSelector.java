package com.proactiveguardian.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLog;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Automatically selects the AI provider before the {@link org.springframework.context.ApplicationContext}
 * refreshes. Selection order (first match wins):
 *
 * <ol>
 *   <li><b>Explicit override</b> — {@code GUARDIAN_AI_PROVIDER} /
 *       {@code guardian.ai-provider} set to {@code openai}, {@code databricks},
 *       {@code custom}, or {@code local}. Skips auto-detection.</li>
 *   <li><b>OpenAI (default)</b> — {@code OPENAI_API_KEY} (env) or
 *       {@code guardian.openai-api-key} is set to a real value.</li>
 *   <li><b>Databricks Model Serving</b> — {@code DATABRICKS_HOST} and
 *       {@code DATABRICKS_TOKEN} are both set. Databricks exposes an
 *       OpenAI-compatible endpoint at
 *       {@code <host>/serving-endpoints}, so we point the Spring AI OpenAI
 *       adapter at it. Chat model defaults to {@code databricks-meta-llama-3-1-70b-instruct}
 *       (override with {@code DATABRICKS_MODEL}); embedding model defaults
 *       to {@code databricks-bge-large-en} (override with
 *       {@code DATABRICKS_EMBEDDING_MODEL}).</li>
 *   <li><b>OpenAI-compatible custom endpoint</b> — {@code SPRING_AI_OPENAI_BASE_URL}
 *       set but no key. LM Studio, llama.cpp, vLLM, corp gateway.</li>
 *   <li><b>Ollama (final fallback)</b> — activates the {@code local}
 *       Spring profile.</li>
 * </ol>
 *
 * <p>Registered in {@code META-INF/spring.factories}.</p>
 */
public class AiProfileSelector implements EnvironmentPostProcessor, ApplicationListener<org.springframework.context.ApplicationEvent>, Ordered {

    // EnvironmentPostProcessor runs BEFORE the logging system is initialised,
    // so a normal SLF4J logger silently drops messages. DeferredLog buffers
    // them and replays once logging is ready (ApplicationPreparedEvent).
    private static final DeferredLog log = new DeferredLog();

    /** Same placeholder used in application.yml's {@code ${OPENAI_API_KEY:...}} default. */
    private static final String PLACEHOLDER = "disabled-set-OPENAI_API_KEY";
    private static final String LOCAL_PROFILE = "local";
    private static final String DUMMY_KEY = "sk-local-no-openai-key-needed";

    /** Databricks Model Serving defaults — override via DATABRICKS_MODEL / DATABRICKS_EMBEDDING_MODEL. */
    private static final String DBX_DEFAULT_CHAT_MODEL      = "databricks-meta-llama-3-1-70b-instruct";
    private static final String DBX_DEFAULT_EMBEDDING_MODEL = "databricks-bge-large-en";

    @Override
    public int getOrder() {
        // Run AFTER Spring Boot's ConfigDataEnvironmentPostProcessor so
        // application.yml / -local.yml properties are already bound and
        // visible via env.getProperty(...).
        return Ordered.LOWEST_PRECEDENCE;
    }

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
        String dbxHost  = firstNonBlank(env.getProperty("DATABRICKS_HOST"),
                                        env.getProperty("guardian.databricks.host"));
        String dbxToken = firstNonBlank(env.getProperty("DATABRICKS_TOKEN"),
                                        env.getProperty("guardian.databricks.token"));

        System.out.println("[AiProfileSelector] postProcessEnvironment invoked: "
                + "OPENAI_API_KEY=" + (isMissing(key) ? "<missing>" : "<set>")
                + ", DATABRICKS_HOST=" + (isBlank(dbxHost) ? "<missing>" : dbxHost)
                + ", DATABRICKS_TOKEN=" + (isBlank(dbxToken) ? "<missing>" : "<set>")
                + ", SPRING_AI_OPENAI_BASE_URL=" + (isBlank(baseUrl) ? "<missing>" : baseUrl)
                + ", GUARDIAN_AI_PROVIDER=" + env.getProperty("GUARDIAN_AI_PROVIDER")
                + ", GUARDIAN_EMBEDDING_PROVIDER=" + env.getProperty("GUARDIAN_EMBEDDING_PROVIDER"));

        // ---- 1. explicit override ----------------------------------------
        String forced = firstNonBlank(env.getProperty("GUARDIAN_AI_PROVIDER"),
                                      env.getProperty("guardian.ai-provider"));
        if (!isBlank(forced)) {
            switch (forced.trim().toLowerCase()) {
                case "openai" -> {
                    log.info("[AiProfileSelector] provider=openai (forced).");
                    return;
                }
                case "databricks" -> {
                    if (isBlank(dbxHost) || isBlank(dbxToken)) {
                        log.warn("[AiProfileSelector] provider=databricks forced but DATABRICKS_HOST / DATABRICKS_TOKEN missing — falling back to local.");
                        applyLocal(env);
                        return;
                    }
                    applyDatabricks(env, dbxHost, dbxToken);
                    return;
                }
                case "custom" -> {
                    if (isBlank(baseUrl)) {
                        log.warn("[AiProfileSelector] provider=custom forced but SPRING_AI_OPENAI_BASE_URL missing — falling back to local.");
                        applyLocal(env);
                        return;
                    }
                    applyCustomOpenAiCompatible(env, baseUrl);
                    return;
                }
                case "local", "ollama" -> {
                    log.info("[AiProfileSelector] provider=local (forced).");
                    applyLocal(env);
                    return;
                }
                default ->
                    log.warn("[AiProfileSelector] Unknown GUARDIAN_AI_PROVIDER='" + forced + "' — falling back to auto-detect.");
            }
        }

        // ---- 2. OpenAI ---------------------------------------------------
        if (!isMissing(key)) {
            log.info("[AiProfileSelector] OPENAI_API_KEY detected — using OpenAI provider.");
            return;
        }

        // ---- 3. Databricks ----------------------------------------------
        if (!isBlank(dbxHost) && !isBlank(dbxToken)) {
            applyDatabricks(env, dbxHost, dbxToken);
            return;
        }

        // ---- 4. Custom OpenAI-compatible endpoint ------------------------
        if (!isBlank(baseUrl)) {
            applyCustomOpenAiCompatible(env, baseUrl);
            return;
        }

        // ---- 5. Local Ollama fallback ------------------------------------
        applyLocal(env);
    }

    /**
     * Replay buffered messages once the logging system is initialised.
     */
    @Override
    public void onApplicationEvent(org.springframework.context.ApplicationEvent event) {
        if (event instanceof ApplicationPreparedEvent || event instanceof ApplicationFailedEvent) {
            DeferredLog.replay(log, org.apache.commons.logging.LogFactory.getLog(AiProfileSelector.class));
        }
    }

    private static void applyDatabricks(ConfigurableEnvironment env, String host, String token) {
        String base = host.replaceAll("/+$", "") + "/serving-endpoints";
        String chatModel      = firstNonBlank(env.getProperty("DATABRICKS_MODEL"),
                                              env.getProperty("guardian.databricks.model"),
                                              DBX_DEFAULT_CHAT_MODEL);
        String embeddingModel = firstNonBlank(env.getProperty("DATABRICKS_EMBEDDING_MODEL"),
                                              env.getProperty("guardian.databricks.embedding-model"),
                                              DBX_DEFAULT_EMBEDDING_MODEL);
        boolean splitEmbedding = isSplitEmbedding(env);
        String summary = "Using Databricks Model Serving at " + base
                + " (chat=" + chatModel + ", embedding=" + (splitEmbedding ? "ollama/local" : embeddingModel) + ")";
        announce(summary);
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("guardian.openai-api-key",           token);
        overrides.put("spring.ai.openai.api-key",          token);
        overrides.put("spring.ai.openai.base-url",         base);
        overrides.put("spring.ai.openai.chat.options.model",       chatModel);
        overrides.put("spring.ai.openai.embedding.options.model",  embeddingModel);
        overrides.put("spring.ai.model.chat",              "openai");
        overrides.put("spring.ai.model.embedding",         splitEmbedding ? "ollama" : "openai");
        overrides.put("guardian.ai-provider.resolved",     "databricks");
        overrides.put("guardian.ai-provider.summary",      summary);
        env.getPropertySources().addFirst(
                new MapPropertySource("aiProfileSelectorOverrides", overrides));
        if (!splitEmbedding) removeLocalProfile(env, "databricks");
    }

    private static void applyCustomOpenAiCompatible(ConfigurableEnvironment env, String baseUrl) {
        boolean splitEmbedding = isSplitEmbedding(env);
        String summary = "No OPENAI_API_KEY but SPRING_AI_OPENAI_BASE_URL='" + baseUrl
                + "' is set — using OpenAI adapter against that endpoint with a dummy key."
                + (splitEmbedding ? " Embeddings → Ollama." : "");
        announce(summary);
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("guardian.openai-api-key",  DUMMY_KEY);
        overrides.put("spring.ai.openai.api-key", DUMMY_KEY);
        overrides.put("spring.ai.model.chat",      "openai");
        overrides.put("spring.ai.model.embedding", splitEmbedding ? "ollama" : "openai");
        overrides.put("guardian.ai-provider.resolved", "custom");
        overrides.put("guardian.ai-provider.summary",  summary);
        env.getPropertySources().addFirst(
                new MapPropertySource("aiProfileSelectorOverrides", overrides));
        if (!splitEmbedding) removeLocalProfile(env, "custom");
    }

    /**
     * Ensure the {@code local} Spring profile is NOT active when a non-local
     * provider (OpenAI / Databricks / custom) has been resolved. Otherwise
     * {@link LocalAiOverrideConfig}'s {@code @Primary} Ollama beans would win
     * over the OpenAI-adapter beans and BreakingChangeDetector (and every
     * other {@code ChatModel} consumer) would silently talk to Ollama.
     */
    private static void removeLocalProfile(ConfigurableEnvironment env, String resolvedProvider) {
        String[] active = env.getActiveProfiles();
        boolean hasLocal = false;
        for (String p : active) {
            if (LOCAL_PROFILE.equalsIgnoreCase(p)) { hasLocal = true; break; }
        }
        if (!hasLocal) return;
        log.warn("[AiProfileSelector] provider=" + resolvedProvider
                + " resolved but 'local' profile was active — removing it so "
                + "LocalAiOverrideConfig does not force Ollama beans.");
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (String p : active) {
            if (!LOCAL_PROFILE.equalsIgnoreCase(p)) kept.add(p);
        }
        env.setActiveProfiles(kept.toArray(new String[0]));
    }

    private static void applyLocal(ConfigurableEnvironment env) {
        String summary = "Activating '" + LOCAL_PROFILE
                + "' profile (Spring AI → Ollama at http://localhost:11434).";
        announce(summary);
        env.addActiveProfile(LOCAL_PROFILE);
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("guardian.openai-api-key",  DUMMY_KEY);
        overrides.put("spring.ai.openai.api-key", DUMMY_KEY);
        overrides.put("guardian.ai-provider.resolved", "local");
        overrides.put("guardian.ai-provider.summary",  summary);
        env.getPropertySources().addFirst(
                new MapPropertySource("aiProfileSelectorOverrides", overrides));
    }

    /**
     * Emit the message via {@link DeferredLog} (for the normal INFO log line
     * once logging is up) AND directly to {@code System.out} so it shows up
     * even if the deferred replay is skipped (some environments strip the
     * listener registration when the app is repackaged / when tests bootstrap
     * their own {@link SpringApplication}).
     */
    private static void announce(String msg) {
        log.info("[AiProfileSelector] " + msg);
        System.out.println("[AiProfileSelector] " + msg);
    }


    private static boolean isSplitEmbedding(ConfigurableEnvironment env) {
        String v = firstNonBlank(env.getProperty("GUARDIAN_EMBEDDING_PROVIDER"),
                                 env.getProperty("guardian.embedding-provider"));
        // Default to local Ollama for embeddings when using a remote chat provider.
        // Override with GUARDIAN_EMBEDDING_PROVIDER=remote to use the same remote backend.
        return !"remote".equalsIgnoreCase(v);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) if (!isBlank(v)) return v;
        return null;
    }

    private static boolean isMissing(String v) {
        return v == null || v.isBlank() || PLACEHOLDER.equals(v);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
