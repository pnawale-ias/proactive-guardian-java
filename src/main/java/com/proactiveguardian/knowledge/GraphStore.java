package com.proactiveguardian.knowledge;

import com.proactiveguardian.model.Artifact;

import java.util.List;
import java.util.Map;

/**
 * Contract for the Neo4j knowledge graph. Direct port of
 * {@code src/knowledge/graph_store.py::GraphStore}. In tests this is
 * replaced by an in-memory {@code FakeGraphStore} with identical semantics.
 */
public interface GraphStore {

    void upsertArtifact(Artifact a);

    void link(String srcId, String dstId, String rel);

    void linkDataReference(String consumerId, String tableFqn, List<String> columns, String kind);

    void linkOrmToTable(String ormId, String tableFqn, List<String> columns);

    List<Map<String, Object>> impactRadius(String artifactId, int hops);

    default List<Map<String, Object>> impactRadius(String artifactId) {
        return impactRadius(artifactId, 3);
    }

    List<Map<String, Object>> findConstraintsFor(String artifactId);

    List<Map<String, Object>> impactRadiusByName(String symbolName, int hops);

    default List<Map<String, Object>> impactRadiusByName(String symbolName) {
        return impactRadiusByName(symbolName, 2);
    }

    List<Map<String, Object>> consumersOfTable(String tableFqn, int hops);

    default List<Map<String, Object>> consumersOfTable(String tableFqn) {
        return consumersOfTable(tableFqn, 2);
    }

    List<Map<String, Object>> consumersOfColumn(String tableFqn, String column, int hops);

    default List<Map<String, Object>> consumersOfColumn(String tableFqn, String column) {
        return consumersOfColumn(tableFqn, column, 2);
    }
}

