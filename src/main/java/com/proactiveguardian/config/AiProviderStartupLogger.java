package com.proactiveguardian.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs the AI provider that {@link AiProfileSelector} resolved during
 * {@code postProcessEnvironment}. Runs at {@link ApplicationReadyEvent} so the
 * message is emitted through the fully-initialised logging system — a reliable
 * alternative to {@code DeferredLog}, which can silently swallow messages if
 * the corresponding {@code ApplicationListener} isn't registered.
 */
@Component
public class AiProviderStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(AiProfileSelector.class);

    private final String resolved;
    private final String summary;
    private final String baseUrl;
    private final String chatModel;
    private final String embeddingModel;

    public AiProviderStartupLogger(
            @Value("${guardian.ai-provider.resolved:openai}") String resolved,
            @Value("${guardian.ai-provider.summary:Using OpenAI provider}") String summary,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model:}") String chatModel,
            @Value("${spring.ai.openai.embedding.options.model:}") String embeddingModel) {
        this.resolved = resolved;
        this.summary = summary;
        this.baseUrl = baseUrl;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }

    @PostConstruct
    void logOnInit() {
        String line1 = "[AiProfileSelector] provider=" + resolved + " — " + summary;
        String line2 = "[AiProfileSelector] effective base-url=" + baseUrl
                + ", chat-model=" + chatModel
                + ", embedding-model=" + embeddingModel;
        log.info(line1);
        log.info(line2);
        // Belt-and-braces: if the logging config filters us out, at least stdout will show it.
        System.out.println(line1);
        System.out.println(line2);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("[AiProfileSelector] ready — provider={} baseUrl={}", resolved, baseUrl);
    }
}

