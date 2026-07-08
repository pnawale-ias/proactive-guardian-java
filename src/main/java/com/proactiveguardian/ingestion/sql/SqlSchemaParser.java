package com.proactiveguardian.ingestion.sql;

import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.ArtifactType;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.view.CreateView;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Structured DDL parser for {@code .sql} files.
 * Emits {@link ArtifactType#SQL_TABLE}, {@link ArtifactType#SQL_COLUMN},
 * {@link ArtifactType#SQL_VIEW} artifacts.
 *
 * <p>Direct port of {@code src/ingestion/sql_schema_parser.py::parse_sql_file}.</p>
 */
@Component
public class SqlSchemaParser {

    public List<Artifact> parse(Path path, String repo,
                                String defaultCatalog, String defaultSchema,
                                String dialect) {
        String source;
        try {
            source = Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return List.of();
        }
        if (source.isBlank()) return List.of();

        Statements parsed;
        try {
            parsed = CCJSqlParserUtil.parseStatements(source);
        } catch (JSQLParserException e) {
            return List.of();
        }

        List<Artifact> artifacts = new ArrayList<>();
        for (Statement s : parsed.getStatements()) {
            if (s instanceof CreateTable ct) {
                artifacts.addAll(fromCreateTable(ct, path, repo, defaultCatalog, defaultSchema, dialect));
            } else if (s instanceof CreateView cv) {
                artifacts.add(fromCreateView(cv, path, repo, defaultCatalog, defaultSchema, dialect));
            }
        }
        return artifacts;
    }

    private List<Artifact> fromCreateTable(CreateTable ct, Path path, String repo,
                                           String defaultCatalog, String defaultSchema, String dialect) {
        String rawFqn = ct.getTable().getFullyQualifiedName();
        String fqn = normaliseFqn(rawFqn, defaultCatalog, defaultSchema);
        String tableId = hash(repo + ":" + path + ":" + fqn + ":sql_table");

        Map<String, Object> tableMeta = new HashMap<>();
        tableMeta.put("catalog", partOrNull(fqn, 0));
        tableMeta.put("schema",  partOrNull(fqn, 1));
        tableMeta.put("table",   partOrNull(fqn, 2));
        tableMeta.put("table_fqn", fqn);
        tableMeta.put("dialect", dialect);
        tableMeta.put("kind", "TABLE");

        List<Artifact> out = new ArrayList<>();
        out.add(new Artifact(
                tableId, ArtifactType.SQL_TABLE, fqn, ct.toString(),
                "sql", repo, path.toString(), null, tableMeta, null
        ));

        List<ColumnDefinition> defs = ct.getColumnDefinitions();
        if (defs != null) {
            for (int i = 0; i < defs.size(); i++) {
                ColumnDefinition cd = defs.get(i);
                String colName = cd.getColumnName();
                String colType = typeStr(cd.getColDataType());
                boolean nullable = isNullable(cd.getColumnSpecs());
                Map<String, Object> colMeta = new HashMap<>();
                colMeta.put("table_id", tableId);
                colMeta.put("table_fqn", fqn);
                colMeta.put("column", colName.toLowerCase(Locale.ROOT));
                colMeta.put("data_type", colType);
                colMeta.put("nullable", nullable);
                colMeta.put("ordinal", i);
                colMeta.put("dialect", dialect);
                out.add(new Artifact(
                        hash(repo + ":" + path + ":" + fqn + "." + colName),
                        ArtifactType.SQL_COLUMN,
                        fqn + "." + colName.toLowerCase(Locale.ROOT),
                        cd.toString(),
                        "sql", repo, path.toString(), null, colMeta, null
                ));
            }
        }
        return out;
    }

    private Artifact fromCreateView(CreateView cv, Path path, String repo,
                                    String defaultCatalog, String defaultSchema, String dialect) {
        String rawFqn = cv.getView().getFullyQualifiedName();
        String fqn = normaliseFqn(rawFqn, defaultCatalog, defaultSchema);

        Map<String, Object> meta = new HashMap<>();
        meta.put("catalog", partOrNull(fqn, 0));
        meta.put("schema",  partOrNull(fqn, 1));
        meta.put("table",   partOrNull(fqn, 2));
        meta.put("table_fqn", fqn);
        meta.put("dialect", dialect);
        meta.put("kind", "VIEW");

        return new Artifact(
                hash(repo + ":" + path + ":" + fqn + ":sql_view"),
                ArtifactType.SQL_VIEW, fqn, cv.toString(),
                "sql", repo, path.toString(), null, meta, null
        );
    }

    // ------------------------------------------------------------------
    private static String typeStr(ColDataType dt) {
        if (dt == null) return "";
        String name = dt.getDataType() == null ? "" : dt.getDataType().toUpperCase(Locale.ROOT);
        List<String> args = dt.getArgumentsStringList();
        return (args == null || args.isEmpty()) ? name : name + "(" + String.join(",", args) + ")";
    }

    private static boolean isNullable(List<String> specs) {
        if (specs == null) return true;
        for (int i = 0; i < specs.size() - 1; i++) {
            if ("NOT".equalsIgnoreCase(specs.get(i)) && "NULL".equalsIgnoreCase(specs.get(i + 1))) return false;
        }
        return true;
    }

    private static String normaliseFqn(String rawFqn, String defaultCatalog, String defaultSchema) {
        String cleaned = rawFqn.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
        String[] parts = cleaned.split("\\.");
        return switch (parts.length) {
            case 3 -> cleaned;
            case 2 -> (defaultCatalog == null || defaultCatalog.isBlank())
                    ? cleaned
                    : defaultCatalog.toLowerCase(Locale.ROOT) + "." + cleaned;
            case 1 -> {
                String catalog = defaultCatalog == null ? "" : defaultCatalog.toLowerCase(Locale.ROOT);
                String schema  = defaultSchema  == null ? "" : defaultSchema.toLowerCase(Locale.ROOT);
                yield (!catalog.isBlank() && !schema.isBlank())
                        ? catalog + "." + schema + "." + cleaned
                        : cleaned;
            }
            default -> cleaned;
        };
    }

    private static String partOrNull(String fqn, int idx) {
        String[] parts = fqn.split("\\.");
        return idx < parts.length ? parts[idx] : null;
    }

    private static String hash(String input) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
