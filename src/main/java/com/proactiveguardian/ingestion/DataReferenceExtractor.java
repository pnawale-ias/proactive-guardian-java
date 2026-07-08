package com.proactiveguardian.ingestion;

import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.ArtifactType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts {@link ArtifactType#DATA_REFERENCE} artifacts from Python / Scala /
 * Java / Kotlin source files. Port of
 * {@code src/ingestion/data_reference_extractor.py::extract_references}.
 */
@Component
public class DataReferenceExtractor {

    /** {@code spark.sql("...")} — single or triple quotes, single-line or DOTALL. */
    private static final Pattern SPARK_SQL_RE = Pattern.compile(
            "spark\\.sql\\s*\\(\\s*(['\"]{1,3})(.*?)\\1",
            Pattern.DOTALL
    );

    /** {@code spark.table("db.table")} / {@code spark.read.table("db.table")}. */
    private static final Pattern SPARK_TABLE_RE = Pattern.compile(
            "spark(?:\\.read)?\\.table\\s*\\(\\s*['\"]([A-Za-z_][\\w.]*)['\"]\\s*\\)"
    );

    /** JDBC URLs. */
    private static final Pattern JDBC_URL_RE = Pattern.compile(
            "(jdbc:[a-z0-9]+://[^\\s'\"]+)", Pattern.CASE_INSENSITIVE
    );

    /** FROM/JOIN/INTO/UPDATE table refs inside a SQL literal. */
    private static final Pattern SQL_TABLE_REF_RE = Pattern.compile(
            "\\b(?:FROM|JOIN|INTO|UPDATE)\\s+([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*){0,2})",
            Pattern.CASE_INSENSITIVE
    );

    public List<Artifact> extract(Path path, String repo, String lang,
                                  String defaultCatalog, String defaultSchema, String dialect) {
        String source;
        try {
            source = Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return List.of();
        }
        if (source.isEmpty()) return List.of();

        Set<String> seen = new HashSet<>();
        List<Artifact> artifacts = new ArrayList<>();

        // spark.sql("...") — one DATA_REFERENCE per referenced table
        Matcher sqlM = SPARK_SQL_RE.matcher(source);
        while (sqlM.find()) {
            String sql = sqlM.group(2);
            int line = lineOf(source, sqlM.start());
            for (String tbl : tablesFromSql(sql)) {
                emit(artifacts, seen, "spark_sql", tbl, sql, line,
                        path, repo, lang, dialect, defaultCatalog, defaultSchema);
            }
        }
        // spark.table("db.table")
        Matcher tblM = SPARK_TABLE_RE.matcher(source);
        while (tblM.find()) {
            int line = lineOf(source, tblM.start());
            emit(artifacts, seen, "spark_table", tblM.group(1), tblM.group(0), line,
                    path, repo, lang, dialect, defaultCatalog, defaultSchema);
        }
        // jdbc:
        Matcher jdbcM = JDBC_URL_RE.matcher(source);
        while (jdbcM.find()) {
            int line = lineOf(source, jdbcM.start());
            emit(artifacts, seen, "jdbc_url", jdbcM.group(1), jdbcM.group(0), line,
                    path, repo, lang, dialect, defaultCatalog, defaultSchema);
        }
        return artifacts;
    }

    private static Set<String> tablesFromSql(String sql) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = SQL_TABLE_REF_RE.matcher(sql);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private void emit(List<Artifact> out, Set<String> seen,
                      String kind, String target, String snippet, int line,
                      Path path, String repo, String lang, String dialect,
                      String defaultCatalog, String defaultSchema) {
        String fqn = fqn(defaultCatalog, defaultSchema, target);
        String key = kind + ":" + fqn;
        if (!seen.add(key)) return;

        Map<String, Object> meta = new HashMap<>();
        meta.put("reference_kind", kind);
        meta.put("target", fqn);
        meta.put("line", line);
        meta.put("dialect", dialect);
        meta.put("table_fqn", fqn);
        meta.put("read_or_write", "read");

        String trimmed = snippet.strip();
        out.add(new Artifact(
                hash(repo + ":" + path + ":" + key + ":" + line),
                ArtifactType.DATA_REFERENCE,
                fqn,
                trimmed.substring(0, Math.min(500, trimmed.length())),
                lang, repo, path.toString(), null, meta, null
        ));
    }

    private static int lineOf(String src, int offset) {
        int count = 1;
        for (int i = 0; i < offset; i++) if (src.charAt(i) == '\n') count++;
        return count;
    }

    private static String fqn(String catalog, String schema, String name) {
        String[] parts = name.split("\\.");
        if (parts.length >= 3 || catalog == null || catalog.isBlank()) return name;
        if (parts.length == 2) return catalog + "." + name;
        // bare table name
        return schema != null && !schema.isBlank() ? catalog + "." + schema + "." + name : name;
    }

    private static String hash(String input) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

