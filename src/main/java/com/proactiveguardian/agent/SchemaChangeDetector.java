package com.proactiveguardian.agent;

import com.proactiveguardian.ingestion.sql.SqlDiffService;
import com.proactiveguardian.knowledge.GraphStore;
import com.proactiveguardian.knowledge.VectorStore;
import com.proactiveguardian.knowledge.VectorStore.Hit;
import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.ArtifactType;
import com.proactiveguardian.model.ColumnChange;
import com.proactiveguardian.model.ColumnRename;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.model.SchemaDelta;
import com.proactiveguardian.model.Severity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Port of {@code src/agents/schema_change_detector.py::SchemaChangeDetector}. */
@Component
public class SchemaChangeDetector {

    private static final Set<String> GENERIC_COLUMN_NAMES = Set.of(
            "id", "name", "value", "type", "status", "created_at", "updated_at",
            "date", "timestamp", "user", "email", "flag", "code"
    );

    private static final Set<ArtifactType> TABLE_LIKE = Set.of(
            ArtifactType.SQL_TABLE, ArtifactType.SQL_VIEW, ArtifactType.DBT_MODEL
    );

    private final VectorStore vs;
    private final GraphStore gs;
    private final SqlDiffService sqlDiff;

    public SchemaChangeDetector(VectorStore vs, GraphStore gs, SqlDiffService sqlDiff) {
        this.vs = vs;
        this.gs = gs;
        this.sqlDiff = sqlDiff;
    }

    public boolean isDataArtifact(Artifact a) {
        return a != null && a.type().isDataArtifact();
    }

    public List<Finding> check(Artifact before, Artifact after) {
        if (!isDataArtifact(before) && !isDataArtifact(after)) return List.of();

        if (before == null && after != null) {
            if (TABLE_LIKE.contains(after.type())) {
                return List.of(new Finding(
                        Severity.INFO, "schema_change",
                        "New " + after.type().wire() + ": `" + after.name() + "`",
                        "Newly created; no consumers to break.",
                        List.of(), 0.9
                ));
            }
            return List.of();
        }

        if (after == null && before != null) {
            if (TABLE_LIKE.contains(before.type())) return deletedTableFindings(before);
            return List.of();
        }

        String dialect = "databricks";
        if (after.metadata() != null && after.metadata().get("sql_dialect") instanceof String s) {
            dialect = s;
        }

        SchemaDelta delta = sqlDiff.diff(
                before == null ? "" : Objects.requireNonNullElse(before.content(), ""),
                after == null ? "" : Objects.requireNonNullElse(after.content(), ""),
                dialect
        );
        if (delta.isEmpty()) return List.of();
        return findingsFromDelta(delta, after);
    }

    private List<Finding> findingsFromDelta(SchemaDelta delta, Artifact after) {
        List<Finding> findings = new ArrayList<>();
        double pen = delta.parseConfidence() == SchemaDelta.Confidence.LOW ? 0.15 : 0.0;

        for (ColumnChange c : delta.addedColumns()) {
            findings.add(new Finding(
                    Severity.INFO, "column_added",
                    "Column added: `" + c.tableFqn() + "." + c.column() + "`",
                    "Added `" + c.column() + " " + nullSafe(c.newType()) + "`. No consumer breakage.",
                    List.of(), 0.9 - pen
            ));
        }
        for (ColumnChange c : delta.droppedColumns()) findings.add(droppedColumnFinding(c, after, pen));
        for (ColumnRename r : delta.renamedColumns()) findings.add(renamedColumnFinding(r, after, pen));

        for (ColumnChange c : delta.typeChanges()) {
            if (c.isNarrowing()) findings.add(narrowedTypeFinding(c, after, pen));
            else findings.add(new Finding(
                    Severity.INFO, "column_type_changed",
                    "Type widened: `" + c.tableFqn() + "." + c.column() + "`",
                    "`" + c.oldType() + "` → `" + c.newType() + "` (widening).",
                    List.of(), 0.85 - pen
            ));
        }
        for (ColumnChange c : delta.nullabilityChanges()) findings.add(nullabilityFinding(c, after, pen));

        for (String fqn : delta.droppedTables()) findings.add(droppedTableFinding(fqn, after, pen));
        for (SchemaDelta.TableRename r : delta.renamedTables()) findings.add(renamedTableFinding(r.oldName(), r.newName(), after, pen));
        for (String fqn : delta.addedTables()) findings.add(new Finding(
                Severity.INFO, "table_added",
                "Table added: `" + fqn + "`",
                "Newly created; no consumers to break.",
                List.of(), 0.9 - pen
        ));

        return findings;
    }

    private Finding droppedColumnFinding(ColumnChange c, Artifact after, double pen) {
        List<Map<String, Object>> consumers = findColumnConsumers(c.tableFqn(), c.column(), after.repo());
        Set<String> crossRepo = crossRepoOnly(consumers, after.repo());
        Severity sev = !crossRepo.isEmpty() ? Severity.BLOCK : Severity.WARN;
        StringBuilder detail = new StringBuilder("**Column dropped**: `")
                .append(c.tableFqn()).append('.').append(c.column())
                .append("` (was `").append(nullSafe(c.oldType(), "unknown")).append("`).\n");
        appendConsumers(detail, consumers, crossRepo, "reference it");
        return new Finding(
                sev, "column_dropped",
                "Column dropped: `" + c.tableFqn() + "." + c.column() + "`",
                detail.toString(), evidence(consumers),
                (!crossRepo.isEmpty() ? 0.98 : 0.8) - pen
        );
    }

    private Finding renamedColumnFinding(ColumnRename r, Artifact after, double pen) {
        List<Map<String, Object>> consumers = findColumnConsumers(r.tableFqn(), r.oldName(), after.repo());
        Set<String> crossRepo = crossRepoOnly(consumers, after.repo());
        Severity sev = !crossRepo.isEmpty() ? Severity.BLOCK : Severity.WARN;
        StringBuilder detail = new StringBuilder("**Column renamed**: `")
                .append(r.oldName()).append("` → `").append(r.newName())
                .append("` on `").append(r.tableFqn())
                .append("` (confidence ").append(Math.round(r.confidence() * 100)).append("%).\n");
        appendConsumers(detail, consumers, crossRepo, "reference the old name");
        return new Finding(
                sev, "column_renamed",
                "Column renamed: `" + r.tableFqn() + "." + r.oldName() + "` → `" + r.newName() + "`",
                detail.toString(), evidence(consumers),
                (!crossRepo.isEmpty() ? 0.95 : 0.75) - pen
        );
    }

    private Finding narrowedTypeFinding(ColumnChange c, Artifact after, double pen) {
        List<Map<String, Object>> consumers = findColumnConsumers(c.tableFqn(), c.column(), after.repo());
        Set<String> crossRepo = crossRepoOnly(consumers, after.repo());
        Severity sev = !crossRepo.isEmpty() ? Severity.BLOCK : Severity.WARN;
        StringBuilder detail = new StringBuilder("**Type narrowed** on `")
                .append(c.tableFqn()).append('.').append(c.column())
                .append("`: `").append(c.oldType()).append("` → `").append(c.newType())
                .append("`.\nPotential data loss / cast failures downstream.\n");
        appendConsumers(detail, consumers, crossRepo, null);
        return new Finding(
                sev, "column_type_changed",
                "Type narrowed: `" + c.tableFqn() + "." + c.column() + "`",
                detail.toString(), evidence(consumers),
                (!crossRepo.isEmpty() ? 0.95 : 0.75) - pen
        );
    }

    private Finding nullabilityFinding(ColumnChange c, Artifact after, double pen) {
        boolean tightened = Boolean.TRUE.equals(c.oldNullable()) && Boolean.FALSE.equals(c.newNullable());
        if (!tightened) {
            return new Finding(
                    Severity.INFO, "column_nullability_changed",
                    "Nullability relaxed: `" + c.tableFqn() + "." + c.column() + "`",
                    "NULLs are now allowed. Existing consumers unaffected.",
                    List.of(), 0.85 - pen
            );
        }
        List<Map<String, Object>> consumers = findColumnConsumers(c.tableFqn(), c.column(), after.repo());
        Set<String> crossRepo = crossRepoOnly(consumers, after.repo());
        Severity sev = !crossRepo.isEmpty() ? Severity.BLOCK : Severity.WARN;
        StringBuilder detail = new StringBuilder("**NOT NULL added** to `")
                .append(c.tableFqn()).append('.').append(c.column())
                .append("`. Any existing NULL row / write will fail.\n");
        appendConsumers(detail, consumers, crossRepo, "write this column");
        return new Finding(
                sev, "column_nullability_changed",
                "NOT NULL added: `" + c.tableFqn() + "." + c.column() + "`",
                detail.toString(), evidence(consumers),
                (!crossRepo.isEmpty() ? 0.95 : 0.75) - pen
        );
    }

    private Finding droppedTableFinding(String fqn, Artifact after, double pen) {
        List<Map<String, Object>> consumers = findTableConsumers(fqn, after.repo());
        Set<String> crossRepo = crossRepoOnly(consumers, after.repo());
        Severity sev = !crossRepo.isEmpty() ? Severity.BLOCK : Severity.WARN;
        StringBuilder detail = new StringBuilder("**Table dropped**: `").append(fqn).append("`.\n");
        appendConsumers(detail, consumers, crossRepo, "reference it");
        return new Finding(
                sev, "table_dropped",
                "Table dropped: `" + fqn + "`",
                detail.toString(), evidence(consumers),
                (!crossRepo.isEmpty() ? 0.98 : 0.8) - pen
        );
    }

    private Finding renamedTableFinding(String oldName, String newName, Artifact after, double pen) {
        List<Map<String, Object>> consumers = findTableConsumers(oldName, after.repo());
        Set<String> crossRepo = crossRepoOnly(consumers, after.repo());
        Severity sev = !crossRepo.isEmpty() ? Severity.BLOCK : Severity.WARN;
        StringBuilder detail = new StringBuilder("**Table renamed**: `").append(oldName)
                .append("` → `").append(newName).append("`.\n");
        appendConsumers(detail, consumers, crossRepo, "reference the old name");
        return new Finding(
                sev, "table_renamed",
                "Table renamed: `" + oldName + "` → `" + newName + "`",
                detail.toString(), evidence(consumers),
                (!crossRepo.isEmpty() ? 0.95 : 0.8) - pen
        );
    }

    private List<Finding> deletedTableFindings(Artifact before) {
        Object fqnMeta = before.metadata() == null ? null : before.metadata().get("table_fqn");
        String fqn = fqnMeta != null ? fqnMeta.toString() : before.name();
        List<Map<String, Object>> consumers = findTableConsumers(fqn, before.repo());
        Set<String> crossRepo = crossRepoOnly(consumers, before.repo());
        Severity sev = !crossRepo.isEmpty() ? Severity.BLOCK : Severity.WARN;
        String detail = "`" + fqn + "` no longer defined. "
                + consumers.size() + " consumer(s) still reference it."
                + (!crossRepo.isEmpty()
                    ? "\n⚠️ Cross-repo impact: `" + String.join("`, `", crossRepo) + "`"
                    : "");
        return List.of(new Finding(
                sev, "table_dropped",
                before.type().wire() + " removed: `" + fqn + "`",
                detail, evidence(consumers),
                !crossRepo.isEmpty() ? 0.98 : 0.7
        ));
    }

    private List<Map<String, Object>> findColumnConsumers(String tableFqn, String column, String ownRepo) {
        String col = column == null ? "" : column.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> consumers = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try {
            for (Map<String, Object> hit : gs.consumersOfColumn(tableFqn, col)) addIfNew(hit, consumers, seen);
        } catch (Exception ignored) { /* graph optional */ }
        try {
            for (Map<String, Object> hit : gs.consumersOfTable(tableFqn)) addIfNew(hit, consumers, seen);
        } catch (Exception ignored) { /* graph optional */ }

        if (!col.isBlank() && !GENERIC_COLUMN_NAMES.contains(col) && col.length() >= 4) {
            List<Hit> candidates = vs.searchSimilar(tableFqn + " " + col, 30);
            Pattern colRe = Pattern.compile("\\b" + Pattern.quote(col) + "\\b", Pattern.CASE_INSENSITIVE);
            String shortTable = tableFqn == null ? "" : tableFqn.substring(tableFqn.lastIndexOf('.') + 1);
            Pattern tableRe = Pattern.compile("\\b" + Pattern.quote(shortTable) + "\\b", Pattern.CASE_INSENSITIVE);
            for (Hit h : candidates) {
                Map<String, Object> p = h.payload();
                Object id = p.get("id");
                if (id == null || seen.contains(id.toString())) continue;
                String content = p.get("content") == null ? "" : p.get("content").toString();
                if (colRe.matcher(content).find() && tableRe.matcher(content).find()) {
                    consumers.add(p);
                    seen.add(id.toString());
                }
            }
        }
        return consumers;
    }

    private List<Map<String, Object>> findTableConsumers(String tableFqn, String ownRepo) {
        List<Map<String, Object>> consumers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            for (Map<String, Object> hit : gs.consumersOfTable(tableFqn)) addIfNew(hit, consumers, seen);
        } catch (Exception ignored) { /* graph optional */ }

        String shortTable = tableFqn == null ? "" : tableFqn.substring(tableFqn.lastIndexOf('.') + 1);
        if (shortTable.length() >= 4) {
            List<Hit> candidates = vs.searchSimilar(tableFqn, 30);
            Pattern tableRe = Pattern.compile("\\b" + Pattern.quote(shortTable) + "\\b", Pattern.CASE_INSENSITIVE);
            for (Hit h : candidates) {
                Map<String, Object> p = h.payload();
                Object id = p.get("id");
                if (id == null || seen.contains(id.toString())) continue;
                String content = p.get("content") == null ? "" : p.get("content").toString();
                if (tableRe.matcher(content).find()) {
                    consumers.add(p);
                    seen.add(id.toString());
                }
            }
        }
        return consumers;
    }

    private static void addIfNew(Map<String, Object> hit, List<Map<String, Object>> consumers, Set<String> seen) {
        Object id = hit.get("id");
        if (id != null && seen.add(id.toString())) consumers.add(hit);
    }

    private static Set<String> crossRepoOnly(List<Map<String, Object>> consumers, String ownRepo) {
        return consumers.stream()
                .map(c -> c.get("repo"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .filter(r -> !r.equals(ownRepo))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static List<String> evidence(List<Map<String, Object>> consumers) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> c : consumers.stream().limit(10).toList()) {
            out.add(String.format("%s::%s::%s",
                    c.getOrDefault("repo", "?"),
                    c.getOrDefault("path", "?"),
                    c.getOrDefault("name", "?")));
        }
        return out;
    }

    private static void appendConsumers(StringBuilder detail, List<Map<String, Object>> consumers,
                                        Set<String> crossRepo, String verb) {
        if (consumers.isEmpty()) return;
        long repos = consumers.stream().map(c -> c.get("repo")).filter(Objects::nonNull).distinct().count();
        detail.append("\n**").append(consumers.size()).append(" consumer(s)** ")
              .append(verb == null ? "across " : ("still " + verb + " across "))
              .append(repos).append(" repo(s).");
        if (!crossRepo.isEmpty()) {
            detail.append("\n⚠️ Cross-repo impact: `").append(String.join("`, `", crossRepo)).append("`");
        }
    }

    private static String nullSafe(String s)           { return s == null ? "" : s; }
    private static String nullSafe(String s, String d) { return s == null ? d : s; }
}

