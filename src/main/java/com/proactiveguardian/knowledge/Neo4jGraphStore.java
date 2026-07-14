package com.proactiveguardian.knowledge;

import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.ArtifactType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Neo4j-backed {@link GraphStore}. Every method preserves the Cypher used in
 * {@code src/knowledge/graph_store.py::GraphStore} — only the driver layer
 * changes.
 */
@Component
@ConditionalOnProperty(name = "guardian.neo4j.enabled", havingValue = "true", matchIfMissing = true)
public class Neo4jGraphStore implements GraphStore {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphStore.class);

    private final Neo4jClient neo4j;

    public Neo4jGraphStore(Neo4jClient neo4j) {
        this.neo4j = neo4j;
    }

    /**
     * Idempotently ensure the schema Neo4j needs before any pipeline query runs:
     *  - UNIQUE constraint on {@code Artifact.id} (also serves to pre-register
     *    the property key so cold-start queries don't emit
     *    {@code Neo.ClientNotification.Statement.UnknownPropertyKeyWarning}).
     *  - Regular indexes on the other properties the impact-radius queries
     *    filter on, again to pre-register the keys and speed up lookups.
     */
    @PostConstruct
    void ensureSchema() {
        // These properties are read by MATCH clauses in impactRadiusByName,
        // consumersOfTable, consumersOfColumn, etc. Pre-register them so the
        // Cypher planner never emits UnknownPropertyKeyWarning on an empty DB.
        String[] indexedProps = {"id", "name", "repo", "path", "type",
                                 "table_fqn", "column"};
        try {
            neo4j.query("""
                    CREATE CONSTRAINT artifact_id_unique IF NOT EXISTS
                    FOR (a:Artifact) REQUIRE a.id IS UNIQUE
                    """).run();
            for (String prop : indexedProps) {
                if ("id".equals(prop)) continue;   // already covered by constraint
                neo4j.query(("""
                        CREATE INDEX artifact_%s_idx IF NOT EXISTS
                        FOR (a:Artifact) ON (a.%s)
                        """).formatted(prop, prop)).run();
            }
            log.info("Neo4j schema ready: Artifact.id UNIQUE + {} indexes",
                    indexedProps.length - 1);
        } catch (Exception e) {
            log.warn("Neo4j schema init skipped: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // upsertArtifact  (with SQL_COLUMN → parent SQL_TABLE auto-linking)
    // ------------------------------------------------------------------
    @Override
    public void upsertArtifact(Artifact a) {
        Map<String, Object> meta = a.metadata() == null ? Map.of() : a.metadata();
        Map<String, Object> params = new HashMap<>();
        params.put("id", a.id());
        params.put("type", a.type().wire());
        params.put("name", a.name());
        params.put("repo", a.repo());
        params.put("path", a.path());
        params.put("language", a.language());
        params.put("url", a.url());
        params.put("table_fqn", meta.get("table_fqn"));
        params.put("column", meta.get("column"));
        params.put("data_type", meta.get("data_type"));
        params.put("kind", meta.get("kind"));
        params.put("read_or_write", meta.get("read_or_write"));

        neo4j.query("""
                MERGE (n:Artifact {id: $id})
                SET n.type=$type, n.name=$name, n.repo=$repo,
                    n.path=$path, n.language=$language, n.url=$url,
                    n.table_fqn=$table_fqn, n.column=$column,
                    n.data_type=$data_type, n.kind=$kind,
                    n.read_or_write=$read_or_write
                """).bindAll(params).run();

        String label = secondaryLabel(a.type());
        if (!label.isEmpty()) {
            // Label name interpolated (validated by static enum), value is $id.
            neo4j.query("MATCH (n:Artifact {id: $id}) SET n:" + label)
                    .bindAll(Map.of("id", a.id())).run();
        }

        Object tableId = meta.get("table_id");
        if (a.type() == ArtifactType.SQL_COLUMN && tableId != null) {
            Map<String, Object> linkParams = new HashMap<>();
            linkParams.put("tid", tableId);
            linkParams.put("cid", a.id());
            linkParams.put("ord", meta.get("ordinal"));
            linkParams.put("dtype", meta.get("data_type"));
            neo4j.query("""
                    MATCH (t:Artifact {id: $tid})
                    MATCH (c:Artifact {id: $cid})
                    MERGE (t)-[r:HAS_COLUMN]->(c)
                    SET r.ordinal=$ord, r.data_type=$dtype
                    """).bindAll(linkParams).run();
        }
    }

    @Override
    public void link(String srcId, String dstId, String rel) {
        String safeRel = sanitizeRelType(rel);
        neo4j.query("MATCH (a:Artifact {id: $src}) MATCH (b:Artifact {id: $dst}) MERGE (a)-[:" + safeRel + "]->(b)")
                .bindAll(Map.of("src", srcId, "dst", dstId))
                .run();
    }

    @Override
    public void linkDataReference(String consumerId, String tableFqn, List<String> columns, String kind) {
        String relTable = "read".equalsIgnoreCase(kind) ? "READS_TABLE" : "WRITES_TABLE";
        String relCol   = "read".equalsIgnoreCase(kind) ? "READS_COLUMN" : "WRITES_COLUMN";

        neo4j.query("""
                MATCH (c:Artifact {id: $cid})
                MATCH (t:Artifact {table_fqn: $fqn})
                WHERE t.type IN ['sql_table', 'sql_view', 'dbt_model']
                MERGE (c)-[:%s]->(t)
                """.formatted(relTable))
                .bindAll(Map.of("cid", consumerId, "fqn", tableFqn))
                .run();

        if (columns == null) return;
        for (String col : columns) {
            neo4j.query("""
                    MATCH (c:Artifact {id: $cid})
                    MATCH (col:Artifact {table_fqn: $fqn, column: $col, type: 'sql_column'})
                    MERGE (c)-[:%s]->(col)
                    """.formatted(relCol))
                    .bindAll(Map.of("cid", consumerId, "fqn", tableFqn, "col", col == null ? "" : col.toLowerCase(Locale.ROOT)))
                    .run();
        }
    }

    @Override
    public void linkOrmToTable(String ormId, String tableFqn, List<String> columns) {
        neo4j.query("""
                MATCH (o:Artifact {id: $oid})
                OPTIONAL MATCH (t:Artifact {table_fqn: $fqn, type: 'sql_table'})
                FOREACH (_ IN CASE WHEN t IS NULL THEN [] ELSE [1] END |
                    MERGE (o)-[:MAPPED_TO_TABLE]->(t)
                )
                """)
                .bindAll(Map.of("oid", ormId, "fqn", tableFqn))
                .run();

        if (columns == null) return;
        for (String col : columns) {
            neo4j.query("""
                    MATCH (o:Artifact {id: $oid})
                    OPTIONAL MATCH (c:Artifact {table_fqn: $fqn, column: $col, type: 'sql_column'})
                    FOREACH (_ IN CASE WHEN c IS NULL THEN [] ELSE [1] END |
                        MERGE (o)-[:MAPPED_TO_COLUMN]->(c)
                    )
                    """)
                    .bindAll(Map.of("oid", ormId, "fqn", tableFqn, "col", col == null ? "" : col.toLowerCase(Locale.ROOT)))
                    .run();
        }
    }

    @Override
    public List<Map<String, Object>> impactRadius(String artifactId, int hops) {
        String cypher = ("""
                MATCH path = (start:Artifact {id: $id})-[*1..%d]-(neighbor)
                RETURN DISTINCT neighbor.id AS id, neighbor.name AS name,
                       neighbor.type AS type, length(path) AS distance
                ORDER BY distance
                LIMIT 50
                """).formatted(clampHops(hops));
        return runList(cypher, Map.of("id", artifactId));
    }

    @Override
    public List<Map<String, Object>> findConstraintsFor(String artifactId) {
        return runList("""
                MATCH (a:Artifact {id: $id})-[:CONSTRAINED_BY|DOCUMENTED_BY*1..2]-(c)
                WHERE c.type IN ['constraint','confluence_page']
                RETURN DISTINCT c.id AS id, c.name AS name, c.url AS url
                """, Map.of("id", artifactId));
    }

    @Override
    public List<Map<String, Object>> impactRadiusByName(String symbolName, int hops) {
        String cypher = ("""
                MATCH (target:Artifact {name: $name})
                MATCH (consumer:Artifact)-[*1..%d]->(target)
                WHERE consumer.name <> $name
                RETURN DISTINCT consumer.id AS id, consumer.name AS name,
                       consumer.repo AS repo, consumer.path AS path,
                       consumer.type AS type
                LIMIT 100
                """).formatted(clampHops(hops));
        return runList(cypher, Map.of("name", symbolName));
    }

    @Override
    public List<Map<String, Object>> consumersOfTable(String tableFqn, int hops) {
        String cypher = ("""
                MATCH (t:Artifact {table_fqn: $fqn})
                WHERE t.type IN ['sql_table', 'sql_view', 'dbt_model']
                MATCH (consumer)-[:READS_TABLE|WRITES_TABLE|MAPPED_TO_TABLE|DBT_REFS|VIEW_OF|REFERENCES*1..%d]->(t)
                WHERE consumer.id <> t.id
                RETURN DISTINCT consumer.id AS id, consumer.name AS name,
                       consumer.repo AS repo, consumer.path AS path,
                       consumer.type AS type
                LIMIT 200
                """).formatted(clampHops(hops));
        return runList(cypher, Map.of("fqn", tableFqn.toLowerCase(Locale.ROOT)));
    }

    @Override
    public List<Map<String, Object>> consumersOfColumn(String tableFqn, String column, int hops) {
        String cypher = ("""
                MATCH (col:Artifact {table_fqn: $fqn, column: $col, type: 'sql_column'})
                MATCH (consumer)-[:READS_COLUMN|WRITES_COLUMN|MAPPED_TO_COLUMN*1..%d]->(col)
                WHERE consumer.id <> col.id
                RETURN DISTINCT consumer.id AS id, consumer.name AS name,
                       consumer.repo AS repo, consumer.path AS path,
                       consumer.type AS type
                LIMIT 200
                """).formatted(clampHops(hops));
        return runList(cypher, Map.of("fqn", tableFqn.toLowerCase(Locale.ROOT), "col", column.toLowerCase(Locale.ROOT)));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Circuit-breaker: once Neo4j is confirmed unreachable, short-circuit reads for a while. */
    private volatile long unavailableUntilMs = 0L;
    private static final long UNAVAILABLE_BACKOFF_MS = 30_000L;

    private List<Map<String, Object>> runList(String cypher, Map<String, Object> params) {
        if (System.currentTimeMillis() < unavailableUntilMs) {
            return List.of();
        }
        try {
            return new ArrayList<>(neo4j.query(cypher).bindAll(params).fetch().all());
        } catch (Exception e) {
            if (isConnectionError(e)) {
                if (System.currentTimeMillis() >= unavailableUntilMs) {
                    log.warn("Neo4j unavailable ({}); short-circuiting graph queries for {}s",
                            rootMessage(e), UNAVAILABLE_BACKOFF_MS / 1000);
                }
                unavailableUntilMs = System.currentTimeMillis() + UNAVAILABLE_BACKOFF_MS;
            } else {
                log.warn("Cypher query failed ({}): {}", cypher.strip().split("\n", 2)[0], e.getMessage());
            }
            return List.of();
        }
    }

    private static boolean isConnectionError(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String n = c.getClass().getName();
            String msg = String.valueOf(c.getMessage());
            if (n.contains("ServiceUnavailable")
                    || n.contains("ConnectException")
                    || n.contains("UnresolvedAddressException")
                    || n.contains("DiscoveryException")
                    || n.contains("SessionExpired")
                    || msg.contains("Unable to connect")
                    || msg.contains("Connection refused")) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        String m = c.getMessage();
        return c.getClass().getSimpleName() + (m != null ? ": " + m : "");
    }

    private static int clampHops(int hops) {
        if (hops < 1) return 1;
        return Math.min(hops, 5);
    }

    /** Cypher relationship types are identifiers — only allow [A-Z0-9_]. */
    private static String sanitizeRelType(String rel) {
        if (rel == null || !rel.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("invalid relationship type: " + rel);
        }
        return rel;
    }

    private static String secondaryLabel(ArtifactType t) {
        return switch (t) {
            case SQL_TABLE            -> "SqlTable";
            case SQL_COLUMN           -> "SqlColumn";
            case SQL_VIEW             -> "SqlView";
            case DBT_MODEL            -> "DbtModel";
            case DATABRICKS_NOTEBOOK  -> "DatabricksNotebook";
            case DATABRICKS_PIPELINE  -> "DatabricksPipeline";
            case ORM_MODEL            -> "OrmModel";
            case DATA_REFERENCE       -> "DataReference";
            default                   -> "";
        };
    }
}

