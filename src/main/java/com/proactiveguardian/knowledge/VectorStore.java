package com.proactiveguardian.knowledge;

import com.proactiveguardian.model.Artifact;

import java.util.List;
import java.util.Map;

/**
 * Contract for the semantic-similarity backing store (Qdrant in production,
 * an in-memory Jaccard-token fake in tests). Direct port of
 * {@code src/knowledge/vector_store.py::VectorStore}.
 */
public interface VectorStore {

    /** Upsert artifacts along with their embeddings. No-op for an empty list. */
    void upsert(List<Artifact> artifacts);

    /**
     * Return up to {@code k} most-similar artifacts to {@code text}.
     * Each hit has a {@code score} (0.0–1.0, cosine similarity) and a
     * {@code payload} map (typically the artifact serialised as JSON).
     */
    List<Hit> searchSimilar(String text, int k, String excludeId);

    default List<Hit> searchSimilar(String text, int k) {
        return searchSimilar(text, k, null);
    }

    /** Individual search result. */
    record Hit(double score, Map<String, Object> payload) {
        public String getStr(String field) {
            Object v = payload.get(field);
            return v == null ? null : v.toString();
        }
    }
}
