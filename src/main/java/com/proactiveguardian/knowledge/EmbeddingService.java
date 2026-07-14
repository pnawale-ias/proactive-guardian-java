package com.proactiveguardian.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper over Spring AI's {@link EmbeddingModel}, mirroring
 * {@code src/knowledge/embeddings.py::embed}.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        log.info("EmbeddingService wired with {} (dims={})",
                embeddingModel.getClass().getName(),
                safeDims(embeddingModel));
    }

    private static Object safeDims(EmbeddingModel m) {
        try { return m.dimensions(); } catch (Exception e) { return "?"; }
    }

    /**
     * Returns one dense vector per input text. The dimension matches whatever
     * model Spring AI is configured with (3072 for {@code text-embedding-3-large}).
     */
    private static final int BATCH_SIZE = 100;

    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        if (texts.size() <= BATCH_SIZE) return embeddingModel.embed(texts);
        List<float[]> result = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            result.addAll(embeddingModel.embed(texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()))));
        }
        return result;
    }

    public float[] embedOne(String text) {
        return embeddingModel.embed(text);
    }

    /** Native vector dimension of the wired model (768 for nomic-embed-text, 3072 for text-embedding-3-large). */
    public int dimensions() {
        return embeddingModel.dimensions();
    }
}

