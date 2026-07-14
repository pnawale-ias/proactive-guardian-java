package com.proactiveguardian.ingestion;

import com.proactiveguardian.config.GuardianProperties;
import com.proactiveguardian.knowledge.GraphStore;
import com.proactiveguardian.knowledge.VectorStore;
import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.ArtifactType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Introspects a live MySQL database via INFORMATION_SCHEMA and dual-writes
 * every table/column artifact to Qdrant (vector search) and Neo4j (dependency
 * graph). Triggered on demand via {@code POST /ingest/mysql}.
 *
 * <p>Activated only when {@code guardian.mysql-enabled=true} is set alongside
 * the four {@code MYSQL_*} connection vars.</p>
 */
@Component
@ConditionalOnProperty(name = "guardian.mysql-enabled", havingValue = "true")
public class MysqlSchemaIngester {

    private static final Logger log = LoggerFactory.getLogger(MysqlSchemaIngester.class);

    private final VectorStore vs;
    private final GraphStore gs;
    private final GuardianProperties props;

    public MysqlSchemaIngester(VectorStore vs, GraphStore gs, GuardianProperties props) {
        this.vs = vs;
        this.gs = gs;
        this.props = props;
    }

    /**
     * Connects to MySQL, reads all tables/columns/FK constraints from
     * INFORMATION_SCHEMA, and persists the full schema knowledge to Qdrant
     * and Neo4j.
     *
     * @return total number of artifacts ingested (SQL_TABLE + SQL_COLUMN)
     */
    public int ingestSchema() {
        String db = props.mysqlDatabase();
        log.info("MySQL schema ingest starting: db={} url={}", db, props.mysqlUrl());

        // table name → {columns, fks}
        Map<String, TableMeta> blueprint = introspect(db);

        List<Artifact> artifacts = buildArtifacts(blueprint, db);

        // Embed + upsert to Qdrant in batches (Databricks BGE caps at 150, Ollama at 2048)
        int batchSize = 100;
        for (int i = 0; i < artifacts.size(); i += batchSize) {
            vs.upsert(artifacts.subList(i, Math.min(i + batchSize, artifacts.size())));
        }

        // Upsert each artifact to Neo4j (HAS_COLUMN edges auto-created via meta.table_id)
        for (Artifact a : artifacts) {
            gs.upsertArtifact(a);
        }

        // FK REFERENCES edges — written after all table nodes exist
        for (Map.Entry<String, TableMeta> entry : blueprint.entrySet()) {
            String tableId = hash("mysql:" + db + "." + entry.getKey());
            for (ForeignKey fk : entry.getValue().foreignKeys()) {
                String refTableId = hash("mysql:" + db + "." + fk.refTable());
                try {
                    gs.link(tableId, refTableId, "REFERENCES");
                } catch (Exception e) {
                    log.debug("FK edge skipped ({} → {}): {}", entry.getKey(), fk.refTable(), e.getMessage());
                }
            }
        }

        log.info("MySQL schema ingest done: db={} tables={} artifacts={}",
                db, blueprint.size(), artifacts.size());
        return artifacts.size();
    }

    // ------------------------------------------------------------------
    // INFORMATION_SCHEMA introspection
    // ------------------------------------------------------------------

    private Map<String, TableMeta> introspect(String db) {
        try (Connection conn = DriverManager.getConnection(
                props.mysqlUrl(), props.mysqlUser(), props.mysqlPassword())) {

            Map<String, TableMeta> tables = new LinkedHashMap<>();

            // 1. Enumerate all base tables
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                    "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' " +
                    "ORDER BY TABLE_NAME")) {
                ps.setString(1, db);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tables.put(rs.getString("TABLE_NAME"),
                                new TableMeta(new ArrayList<>(), new ArrayList<>()));
                    }
                }
            }

            // 2. Full column metadata per table
            for (String table : tables.keySet()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COLUMN_NAME, DATA_TYPE, ORDINAL_POSITION, " +
                        "IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT, EXTRA " +
                        "FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
                        "ORDER BY ORDINAL_POSITION")) {
                    ps.setString(1, db);
                    ps.setString(2, table);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            tables.get(table).columns().add(new ColumnMeta(
                                    rs.getString("COLUMN_NAME"),
                                    rs.getString("DATA_TYPE"),
                                    rs.getInt("ORDINAL_POSITION"),
                                    rs.getString("IS_NULLABLE"),
                                    rs.getString("COLUMN_KEY"),
                                    rs.getString("COLUMN_DEFAULT"),
                                    rs.getString("EXTRA")
                            ));
                        }
                    }
                }
            }

            // 3. Foreign key constraints
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT TABLE_NAME, COLUMN_NAME, " +
                    "REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME " +
                    "FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE " +
                    "WHERE TABLE_SCHEMA = ? AND REFERENCED_TABLE_NAME IS NOT NULL")) {
                ps.setString(1, db);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String t = rs.getString("TABLE_NAME");
                        if (tables.containsKey(t)) {
                            tables.get(t).foreignKeys().add(new ForeignKey(
                                    rs.getString("COLUMN_NAME"),
                                    rs.getString("REFERENCED_TABLE_NAME"),
                                    rs.getString("REFERENCED_COLUMN_NAME")
                            ));
                        }
                    }
                }
            }

            log.info("Introspected {} tables from db={}", tables.size(), db);
            return tables;

        } catch (Exception e) {
            throw new RuntimeException("MySQL introspection failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Artifact construction
    // ------------------------------------------------------------------

    private List<Artifact> buildArtifacts(Map<String, TableMeta> blueprint, String db) {
        List<Artifact> artifacts = new ArrayList<>();

        for (Map.Entry<String, TableMeta> entry : blueprint.entrySet()) {
            String table = entry.getKey();
            TableMeta meta = entry.getValue();
            String tableFqn = db + "." + table;
            String tableId = hash("mysql:" + tableFqn);

            // Build human-readable column summary for the table content field
            StringBuilder colSummary = new StringBuilder();
            for (ColumnMeta col : meta.columns()) {
                if (!colSummary.isEmpty()) colSummary.append(", ");
                colSummary.append(col.name()).append(" ").append(col.dataType());
            }

            Map<String, Object> tableMeta = new HashMap<>();
            tableMeta.put("table_fqn", tableFqn);
            tableMeta.put("schema", db);
            tableMeta.put("table", table);
            tableMeta.put("kind", "TABLE");
            tableMeta.put("dialect", "mysql");
            tableMeta.put("column_count", meta.columns().size());

            artifacts.add(new Artifact(
                    tableId, ArtifactType.SQL_TABLE, table, colSummary.toString(),
                    null, db, db + "/" + table, null, tableMeta, null
            ));

            // One SQL_COLUMN artifact per column
            for (ColumnMeta col : meta.columns()) {
                String colId = hash("mysql:" + tableFqn + "." + col.name());

                Map<String, Object> colMeta = new HashMap<>();
                colMeta.put("table_id", tableId);
                colMeta.put("table_fqn", tableFqn);
                colMeta.put("column", col.name());
                colMeta.put("data_type", col.dataType());
                colMeta.put("ordinal", col.ordinal());
                colMeta.put("nullable", col.nullable());
                colMeta.put("column_key", col.columnKey());
                colMeta.put("column_default", col.columnDefault());
                colMeta.put("extra", col.extra());
                colMeta.put("dialect", "mysql");

                artifacts.add(new Artifact(
                        colId, ArtifactType.SQL_COLUMN, table + "." + col.name(), col.dataType(),
                        null, db, db + "/" + table + "/" + col.name(), null, colMeta, null
                ));
            }
        }

        return artifacts;
    }

    // ------------------------------------------------------------------
    // Internal data model
    // ------------------------------------------------------------------

    private record ColumnMeta(
            String name, String dataType, int ordinal,
            String nullable, String columnKey, String columnDefault, String extra) {}

    private record ForeignKey(String column, String refTable, String refColumn) {}

    private record TableMeta(List<ColumnMeta> columns, List<ForeignKey> foreignKeys) {}

    // ------------------------------------------------------------------
    // Hashing — SHA-256 first 8 bytes as hex (matches ConfluenceIngester)
    // ------------------------------------------------------------------

    private static String hash(String input) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
