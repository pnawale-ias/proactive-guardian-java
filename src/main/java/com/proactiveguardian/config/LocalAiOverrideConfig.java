package com.proactiveguardian.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Force Spring AI to use the Ollama beans (chat + embedding) whenever the
 * {@code local} profile is active — bypassing the auto-configuration race
 * where {@code spring.ai.model.embedding=ollama} in {@code application-local.yml}
 * is not honoured because the OpenAI starter is also on the classpath.
 *
 * <p>Symptom this fixes:
 * {@code RestClientException: content type [text/plain] ... OpenAiApi$EmbeddingList}
 * — the OpenAI adapter was being invoked with a dummy key against api.openai.com
 * because both {@code OpenAiEmbeddingModel} and {@code OllamaEmbeddingModel} beans
 * existed and OpenAI won the primary tie-breaker.</p>
 *
 * <p>Activated only under the {@code local} profile (chosen automatically by
 * {@link AiProfileSelector} when {@code OPENAI_API_KEY} is missing). When you
 * later set a real key, the profile is dropped and this override goes away.</p>
 */
@Configuration
@Profile("local")
public class LocalAiOverrideConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalAiOverrideConfig.class);

    @Bean
    @Primary
    public EmbeddingModel guardianPrimaryEmbeddingModel(ObjectProvider<OllamaEmbeddingModel> ollama) {
        OllamaEmbeddingModel model = ollama.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException(
                "The 'local' profile is active but no OllamaEmbeddingModel bean is on the "
              + "classpath. Verify spring-ai-starter-model-ollama is a compile dependency "
              + "and that OLLAMA_BASE_URL (default http://localhost:11434) is reachable.");
        }
        log.info("Local profile: @Primary EmbeddingModel → {}", model.getClass().getName());
        return model;
    }

    @Bean
    @Primary
    public ChatModel guardianPrimaryChatModel(ObjectProvider<OllamaChatModel> ollama) {
        OllamaChatModel model = ollama.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException(
                "The 'local' profile is active but no OllamaChatModel bean is on the "
              + "classpath. Verify spring-ai-starter-model-ollama is a compile dependency "
              + "and that OLLAMA_BASE_URL (default http://localhost:11434) is reachable.");
        }
        log.info("Local profile: @Primary ChatModel → {}", model.getClass().getName());
        return model;
    }
}

