package com.proactiveguardian.testsupport;

import com.proactiveguardian.knowledge.GraphStore;
import com.proactiveguardian.knowledge.VectorStore;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

/**
 * Test-only wiring that swaps every external dependency for an in-memory fake.
 * Import with {@code @Import(TestFakesConfig.class)} on a Spring-Boot test.
 */
@TestConfiguration
public class TestFakesConfig {

    @Bean
    @Primary
    public VectorStore fakeVectorStore() {
        return new FakeVectorStore();
    }

    @Bean
    @Primary
    public GraphStore fakeGraphStore() {
        return new FakeGraphStore();
    }

    /** Deterministic 8-dim embedding for tests (Jaccard fake ignores the vector anyway). */
    @Bean
    @Primary
    public EmbeddingModel testEmbeddingModel() {
        return new EmbeddingModel() {
            @Override public float[] embed(String text) { return new float[8]; }
            @Override public float[] embed(org.springframework.ai.document.Document d) { return new float[8]; }
            @Override public List<float[]> embed(List<String> texts) {
                List<float[]> out = new ArrayList<>(texts.size());
                for (int i = 0; i < texts.size(); i++) out.add(new float[8]);
                return out;
            }
            @Override public EmbeddingResponse embedForResponse(List<String> texts) {
                return new EmbeddingResponse(List.of());
            }
            @Override public EmbeddingResponse call(EmbeddingRequest request) {
                return new EmbeddingResponse(List.of());
            }
            @Override public int dimensions() { return 8; }
        };
    }

    /** Chat model that always returns {@code {"violations":[]}}. */
    @Bean
    @Primary
    public ChatModel testChatModel() {
        return prompt -> {
            AssistantMessage msg = new AssistantMessage("{\"violations\":[]}");
            return new ChatResponse(List.of(new Generation(msg)));
        };
    }
}

