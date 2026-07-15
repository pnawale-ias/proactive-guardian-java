package com.proactiveguardian.model;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable record describing a piece of knowledge (code symbol, doc page,
 * SQL table, ...). Direct port of {@code src/models.py::Artifact}.
 */
public record Artifact(
        String id,
        ArtifactType type,
        String name,
        String content,
        String language,
        String repo,
        String path,
        String url,
        Map<String, Object> metadata,
        Instant updatedAt
) {
    public Artifact {
        if (metadata == null) metadata = Map.of();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    /** Convenience builder-less constructor for the common code-plane case. */
    public static Artifact of(String id, ArtifactType type, String name, String content) {
        return new Artifact(id, type, name, content, null, null, null, null, Map.of(), Instant.now());
    }

    /** Return a copy with a different path. */
    public Artifact withPath(String newPath) {
        return new Artifact(id, type, name, content, language, repo, newPath, url, metadata, updatedAt);
    }
}

