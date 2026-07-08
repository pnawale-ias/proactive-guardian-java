package com.proactiveguardian.testsupport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proactiveguardian.knowledge.VectorStore;
import com.proactiveguardian.model.Artifact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link VectorStore} that ranks candidates by Jaccard-token overlap.
 * Direct port of the {@code FakeVectorStore} used in the Python
 * {@code tests/conftest.py}.
 */
public class FakeVectorStore implements VectorStore {

    private final ObjectMapper mapper;
    private final Map<String, Artifact> store = new HashMap<>();

    public FakeVectorStore() {
        this(new ObjectMapper());
    }

    public FakeVectorStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void upsert(List<Artifact> artifacts) {
        if (artifacts == null) return;
        for (Artifact a : artifacts) store.put(a.id(), a);
    }

    @Override
    public List<Hit> searchSimilar(String text, int k, String excludeId) {
        Set<String> queryTokens = tokens(text);
        List<Hit> ranked = new ArrayList<>();
        for (Artifact a : store.values()) {
            if (excludeId != null && excludeId.equals(a.id())) continue;
            Set<String> artTokens = tokens(a.name() + " " + a.content());
            double score = jaccard(queryTokens, artTokens);
            ranked.add(new Hit(score, toMap(a)));
        }
        ranked.sort(Comparator.comparingDouble(Hit::score).reversed());
        return ranked.subList(0, Math.min(k, ranked.size()));
    }

    private Map<String, Object> toMap(Artifact a) {
        return mapper.convertValue(a, new TypeReference<>() {});
    }

    private static Set<String> tokens(String s) {
        if (s == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
            if (t.length() >= 3) out.add(t);
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / (double) union.size();
    }
}

