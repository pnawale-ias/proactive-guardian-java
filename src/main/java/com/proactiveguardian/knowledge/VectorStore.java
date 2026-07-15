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

    /**
     * Like {@link #searchSimilar} but restricted to artifacts whose {@code type}
     * payload matches one of {@code types}. Default implementation fetches a
     * larger result set and post-filters; implementations may override with a
     * native store filter for efficiency.
     */
    default List<Hit> searchSimilarByTypes(String text, int k, List<String> types) {
        List<Hit> all = searchSimilar(text, k * 10);
        java.util.Set<String> typeSet = new java.util.HashSet<>(types);
        return all.stream()
                .filter(h -> {
                    Object t = h.payload().get("type");
                    return t != null && typeSet.contains(t.toString());
                })
                .limit(k)
                .toList();
    }

    /**
     * Distinct {@code repo} payload values present in the store, mapped to the
     * number of artifacts each repo contributes. Used at startup to print an
     * "ingested services" summary. Default is empty (fakes / non-Qdrant stores).
     */
    default Map<String, Long> repoCounts() {
        return Map.of();
    }

    /**
     * Scan the collection and return every artifact whose {@code content},
     * {@code name}, or {@code path} payload literally contains {@code token}
     * (case-insensitive, whole-word). Used by the consumer-awareness advisor
     * to build a deterministic list of downstream repos without relying on
     * embedding-similarity rankings (which are unreliable on small KBs).
     *
     * <p>Default is empty (fakes / non-Qdrant stores can opt in).</p>
     *
     * @param token   token to match (e.g. producer repo short name)
     * @param maxHits soft cap on returned hits — implementations may still
     *                need to scroll further to find them.
     */
    default List<Hit> scanReferences(String token, int maxHits) {
        return List.of();
    }

    /**
     * Return every ingested Confluence page as a lightweight summary
     * ({@code id}, {@code title}, {@code url}, {@code page_id}, {@code version}).
     * Used by the startup banner to advertise which docs are in the KB.
     * Default is empty (fakes / non-Qdrant stores can opt in).
     */
    default List<Map<String, Object>> listConfluencePages(int maxPages) {
        return List.of();
    }

    /** Individual search result. */
    record Hit(double score, Map<String, Object> payload) {
        public String getStr(String field) {
            Object v = payload.get(field);
            return v == null ? null : v.toString();
        }
    }
}
