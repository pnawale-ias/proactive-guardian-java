package com.proactiveguardian.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proactiveguardian.config.GuardianProperties;
import com.proactiveguardian.model.Artifact;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.ScoredPoint;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * Qdrant-backed {@link VectorStore}. Direct port of
 * {@code src/knowledge/vector_store.py::VectorStore}.
 *
 * <p>Disabled with {@code guardian.qdrant.enabled=false} in tests where a fake
 * store replaces it as {@code @Primary}.</p>
 */
@Component
@ConditionalOnProperty(name = "guardian.qdrant.enabled", havingValue = "true", matchIfMissing = true)
public class QdrantVectorStore implements VectorStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    private final GuardianProperties props;
    private final EmbeddingService embeddings;
    private final ObjectMapper objectMapper;
    private final QdrantClient client;
    /** Native vector dim of the active embedding model — used to (re)create the collection. */
    private final int vectorSize;

    public QdrantVectorStore(GuardianProperties props,
                             EmbeddingService embeddings,
                             ObjectMapper objectMapper) {
        this.props = props;
        this.embeddings = embeddings;
        this.objectMapper = objectMapper;
        this.client = buildClient(props);
        this.vectorSize = resolveVectorSize(embeddings);
        log.info("QdrantVectorStore configured for vector dim={} (from embedding model)", vectorSize);
    }

    private static int resolveVectorSize(EmbeddingService svc) {
        try {
            int d = svc.dimensions();
            if (d > 0) return d;
        } catch (Exception e) {
            log.warn("Could not read embedding dimensions from model: {} — defaulting to 768 (nomic-embed-text)", e.getMessage());
        }
        return 768;   // ollama nomic-embed-text default
    }

    private static QdrantClient buildClient(GuardianProperties props) {
        URI uri = URI.create(props.qdrantUrl());
        String host = uri.getHost() != null ? uri.getHost() : "localhost";
        // guardian.qdrant-url is the REST endpoint (default 6333). Qdrant's gRPC
        // listener sits on REST+1 by convention (6334 by default), which is what
        // the Java client needs.
        int restPort = uri.getPort() > 0 ? uri.getPort() : 6333;
        int grpcPort = restPort + 1;
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        QdrantGrpcClient.Builder b = QdrantGrpcClient.newBuilder(host, grpcPort, tls);
        if (props.qdrantApiKey() != null && !props.qdrantApiKey().isBlank()) {
            b.withApiKey(props.qdrantApiKey());
        }
        return new QdrantClient(b.build());
    }

    @PostConstruct
    void ensureCollection() {
        try {
            boolean exists = client.collectionExistsAsync(props.qdrantCollection()).get();

            if (exists) {
                int existingDim = readCollectionDim(props.qdrantCollection());
                if (existingDim > 0 && existingDim != vectorSize) {
                    log.warn("Qdrant collection '{}' has dim={} but current embedding model produces dim={} — "
                                    + "DROPPING and recreating (all existing vectors will be lost).",
                            props.qdrantCollection(), existingDim, vectorSize);
                    client.deleteCollectionAsync(props.qdrantCollection()).get();
                    exists = false;
                }
            }

            if (!exists) {
                log.info("Creating Qdrant collection '{}' (dim={}, cosine)", props.qdrantCollection(), vectorSize);
                client.createCollectionAsync(
                        props.qdrantCollection(),
                        VectorParams.newBuilder()
                                .setSize(vectorSize)
                                .setDistance(Distance.Cosine)
                                .build()
                ).get();
            }
        } catch (Exception e) {
            log.warn("Qdrant collection ensure failed: {}", e.getMessage(), e);
        }
    }

    /** Read the currently-stored vector dimension, or -1 if it can't be determined. */
    private int readCollectionDim(String name) {
        try {
            var info = client.getCollectionInfoAsync(name).get();
            long size = info.getConfig().getParams().getVectorsConfig().getParams().getSize();
            return (int) size;
        } catch (Exception e) {
            log.debug("Could not read collection dim for '{}': {}", name, e.getMessage());
            return -1;
        }
    }

    @Override
    public void upsert(List<Artifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) return;
        List<String> texts = artifacts.stream()
                .map(a -> a.name() + "\n" + a.content())
                .toList();
        List<float[]> vectors = embeddings.embed(texts);

        List<PointStruct> points = new ArrayList<>(artifacts.size());
        for (int i = 0; i < artifacts.size(); i++) {
            Artifact a = artifacts.get(i);
            float[] vec = vectors.get(i);
            List<Float> vecList = new ArrayList<>(vec.length);
            for (float f : vec) vecList.add(f);

            PointStruct point = PointStruct.newBuilder()
                    .setId(id(idFor(a.id())))
                    .setVectors(vectors(vecList))
                    .putAllPayload(toPayload(a))
                    .build();
            points.add(point);
        }
        try {
            client.upsertAsync(props.qdrantCollection(), points).get();
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant upsert failed", e);
        }
    }

    @Override
    public List<Hit> searchSimilar(String text, int k, String excludeId) {
        float[] vec = embeddings.embedOne(text);
        List<Float> vecList = new ArrayList<>(vec.length);
        for (float f : vec) vecList.add(f);

        try {
            List<ScoredPoint> results = client.searchAsync(
                    io.qdrant.client.grpc.Points.SearchPoints.newBuilder()
                            .setCollectionName(props.qdrantCollection())
                            .addAllVector(vecList)
                            .setLimit(k + 1)
                            .setWithPayload(io.qdrant.client.grpc.Points.WithPayloadSelector
                                    .newBuilder().setEnable(true).build())
                            .build()
            ).get();

            List<Hit> hits = new ArrayList<>();
            for (ScoredPoint r : results) {
                Map<String, Object> payload = fromPayload(r.getPayloadMap());
                Object rid = payload.get("id");
                if (excludeId != null && excludeId.equals(rid)) continue;
                hits.add(new Hit(r.getScore(), payload));
                if (hits.size() >= k) break;
            }
            return hits;
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant search failed", e);
        }
    }

    /** Deterministically map an app-level artifact id to a Qdrant UUID. */
    private UUID idFor(String appId) {
        return UUID.nameUUIDFromBytes(appId.getBytes());
    }

    private Map<String, Value> toPayload(Artifact a) {
        Map<String, Object> json = objectMapper.convertValue(a, new TypeReference<>() {});
        Map<String, Value> out = new HashMap<>(json.size());
        json.forEach((k, v) -> out.put(k, toQdrantValue(v)));
        return out;
    }

    private Map<String, Object> fromPayload(Map<String, Value> payload) {
        Map<String, Object> out = new HashMap<>();
        payload.forEach((k, v) -> out.put(k, fromQdrantValue(v)));
        return out;
    }

    private static Value toQdrantValue(Object v) {
        if (v == null) return value("");
        if (v instanceof String s) return value(s);
        if (v instanceof Boolean b) return value(b);
        if (v instanceof Integer i) return value(i);
        if (v instanceof Long l) return value(l);
        if (v instanceof Double d) return value(d);
        if (v instanceof Float f) return value(f);
        // fall back to string serialisation for complex types (metadata map, list, ...)
        return value(v.toString());
    }

    private static Object fromQdrantValue(Value v) {
        return switch (v.getKindCase()) {
            case STRING_VALUE  -> v.getStringValue();
            case BOOL_VALUE    -> v.getBoolValue();
            case INTEGER_VALUE -> v.getIntegerValue();
            case DOUBLE_VALUE  -> v.getDoubleValue();
            default            -> null;
        };
    }

    @Override
    @PreDestroy
    public void close() {
        client.close();
    }
}

