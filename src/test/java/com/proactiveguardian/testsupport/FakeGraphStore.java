package com.proactiveguardian.testsupport;

import com.proactiveguardian.knowledge.GraphStore;
import com.proactiveguardian.model.Artifact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory {@link GraphStore} with the same query semantics as
 * {@link com.proactiveguardian.knowledge.Neo4jGraphStore}. Ports the
 * {@code FakeGraphStore} fixture from {@code tests/conftest.py}.
 */
public class FakeGraphStore implements GraphStore {

    private final Map<String, Artifact> nodes = new HashMap<>();
    private final Set<Edge> edges = new LinkedHashSet<>();

    private record Edge(String src, String dst, String rel) {}

    @Override
    public void upsertArtifact(Artifact a) {
        nodes.put(a.id(), a);
    }

    @Override
    public void link(String srcId, String dstId, String rel) {
        edges.add(new Edge(srcId, dstId, rel));
    }

    @Override
    public void linkDataReference(String consumerId, String tableFqn, List<String> columns, String kind) {
        String relTable = "read".equalsIgnoreCase(kind) ? "READS_TABLE" : "WRITES_TABLE";
        String relCol   = "read".equalsIgnoreCase(kind) ? "READS_COLUMN" : "WRITES_COLUMN";
        for (Artifact a : nodes.values()) {
            if (tableFqn.equalsIgnoreCase(fqnOf(a))) edges.add(new Edge(consumerId, a.id(), relTable));
        }
        if (columns == null) return;
        for (String c : columns) {
            String col = c == null ? "" : c.toLowerCase(Locale.ROOT);
            for (Artifact a : nodes.values()) {
                if (tableFqn.equalsIgnoreCase(fqnOf(a)) && col.equals(columnOf(a))) {
                    edges.add(new Edge(consumerId, a.id(), relCol));
                }
            }
        }
    }

    @Override
    public void linkOrmToTable(String ormId, String tableFqn, List<String> columns) {
        for (Artifact a : nodes.values()) {
            if (tableFqn.equalsIgnoreCase(fqnOf(a))) edges.add(new Edge(ormId, a.id(), "MAPPED_TO_TABLE"));
        }
        if (columns == null) return;
        for (String c : columns) {
            String col = c == null ? "" : c.toLowerCase(Locale.ROOT);
            for (Artifact a : nodes.values()) {
                if (tableFqn.equalsIgnoreCase(fqnOf(a)) && col.equals(columnOf(a))) {
                    edges.add(new Edge(ormId, a.id(), "MAPPED_TO_COLUMN"));
                }
            }
        }
    }

    @Override
    public List<Map<String, Object>> impactRadius(String artifactId, int hops) {
        // 1-hop only — enough for tests.
        List<Map<String, Object>> out = new ArrayList<>();
        for (Edge e : edges) {
            if (e.src.equals(artifactId) || e.dst.equals(artifactId)) {
                String neighbour = e.src.equals(artifactId) ? e.dst : e.src;
                Artifact a = nodes.get(neighbour);
                if (a != null) out.add(toRow(a, 1));
            }
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> findConstraintsFor(String artifactId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Edge e : edges) {
            if ((e.rel.equals("CONSTRAINED_BY") || e.rel.equals("DOCUMENTED_BY"))
                    && (e.src.equals(artifactId) || e.dst.equals(artifactId))) {
                String otherId = e.src.equals(artifactId) ? e.dst : e.src;
                Artifact a = nodes.get(otherId);
                if (a != null) out.add(toRow(a, 1));
            }
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> impactRadiusByName(String symbolName, int hops) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> targetIds = new HashSet<>();
        for (Artifact a : nodes.values()) if (symbolName.equals(a.name())) targetIds.add(a.id());
        for (Edge e : edges) {
            if (targetIds.contains(e.dst)) {
                Artifact consumer = nodes.get(e.src);
                if (consumer != null && !symbolName.equals(consumer.name())) out.add(toRow(consumer, 1));
            }
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> consumersOfTable(String tableFqn, int hops) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> targetIds = new HashSet<>();
        for (Artifact a : nodes.values()) if (tableFqn.equalsIgnoreCase(fqnOf(a))) targetIds.add(a.id());
        for (Edge e : edges) {
            if (targetIds.contains(e.dst) &&
                    Set.of("READS_TABLE", "WRITES_TABLE", "MAPPED_TO_TABLE", "DBT_REFS", "VIEW_OF")
                            .contains(e.rel)) {
                Artifact consumer = nodes.get(e.src);
                if (consumer != null) out.add(toRow(consumer, 1));
            }
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> consumersOfColumn(String tableFqn, String column, int hops) {
        String col = column == null ? "" : column.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> targetIds = new HashSet<>();
        for (Artifact a : nodes.values()) {
            if (tableFqn.equalsIgnoreCase(fqnOf(a)) && col.equals(columnOf(a))) targetIds.add(a.id());
        }
        for (Edge e : edges) {
            if (targetIds.contains(e.dst) &&
                    Set.of("READS_COLUMN", "WRITES_COLUMN", "MAPPED_TO_COLUMN").contains(e.rel)) {
                Artifact consumer = nodes.get(e.src);
                if (consumer != null) out.add(toRow(consumer, 1));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    private static String fqnOf(Artifact a) {
        Object v = a.metadata() == null ? null : a.metadata().get("table_fqn");
        return v == null ? "" : v.toString();
    }

    private static String columnOf(Artifact a) {
        Object v = a.metadata() == null ? null : a.metadata().get("column");
        return v == null ? "" : v.toString();
    }

    private static Map<String, Object> toRow(Artifact a, int distance) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", a.id());
        row.put("name", a.name());
        row.put("type", a.type().wire());
        row.put("repo", a.repo());
        row.put("path", a.path());
        row.put("distance", distance);
        return row;
    }
}

