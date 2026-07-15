package com.proactiveguardian.ingestion.sql;

import com.proactiveguardian.model.ColumnChange;
import com.proactiveguardian.model.ColumnRename;
import com.proactiveguardian.model.SchemaDelta;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured SQL DDL diff — direct port of {@code src/agents/sql_diff.py::diff_ddl}.
 *
 * <p>Uses JSqlParser for CREATE/ALTER TABLE parsing; falls back to a coarse
 * regex parser when JSqlParser cannot handle the input (e.g. Databricks
 * dialect quirks). The regex fallback flips {@code parseConfidence=LOW} so
 * downstream detectors downgrade severity.</p>
 */
@Service
public class SqlDiffService {

    /** Type transitions considered widening (backwards-compatible). */
    private static final Set<TypePair> WIDENING_PAIRS = Set.of(
            new TypePair("TINYINT", "SMALLINT"),
            new TypePair("TINYINT", "INT"),
            new TypePair("TINYINT", "BIGINT"),
            new TypePair("SMALLINT", "INT"),
            new TypePair("SMALLINT", "BIGINT"),
            new TypePair("INT", "BIGINT"),
            new TypePair("FLOAT", "DOUBLE"),
            new TypePair("DATE", "TIMESTAMP")
    );

    private record TypePair(String from, String to) {}

    /** Column metadata extracted from a CREATE/ALTER statement. */
    public record ColumnSpec(String type, boolean nullable, int ordinal, String renamedFrom) {
        public ColumnSpec(String type, boolean nullable, int ordinal) {
            this(type, nullable, ordinal, null);
        }
    }

    private static final class TableMap extends LinkedHashMap<String, LinkedHashMap<String, ColumnSpec>> {}

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------
    public SchemaDelta diff(String beforeSql, String afterSql) {
        return diff(beforeSql, afterSql, "ansi");
    }

    public SchemaDelta diff(String beforeSql, String afterSql, String dialect) {
        ParseResult before = parseToTableMap(beforeSql);
        ParseResult after  = parseToTableMap(afterSql);
        SchemaDelta delta = diffTableMaps(before.tables, after.tables);
        if (before.confidence == SchemaDelta.Confidence.LOW
                || after.confidence == SchemaDelta.Confidence.LOW) {
            // Records are immutable; rebuild with LOW confidence.
            return new SchemaDelta(
                    delta.addedTables(), delta.droppedTables(), delta.renamedTables(),
                    delta.addedColumns(), delta.droppedColumns(), delta.renamedColumns(),
                    delta.typeChanges(), delta.nullabilityChanges(),
                    SchemaDelta.Confidence.LOW
            );
        }
        return delta;
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------
    private record ParseResult(TableMap tables, SchemaDelta.Confidence confidence) {}

    private ParseResult parseToTableMap(String sql) {
        TableMap tables = new TableMap();
        if (sql == null || sql.isBlank()) return new ParseResult(tables, SchemaDelta.Confidence.HIGH);
        try {
            Statements parsed = CCJSqlParserUtil.parseStatements(sql);
            for (Statement s : parsed.getStatements()) applyStatement(s, tables);
            return new ParseResult(tables, SchemaDelta.Confidence.HIGH);
        } catch (JSQLParserException e) {
            return new ParseResult(regexFallback(sql), SchemaDelta.Confidence.LOW);
        }
    }

    private void applyStatement(Statement s, TableMap tables) {
        if (s instanceof CreateTable ct) {
            String fqn = fqnOf(ct.getTable().getFullyQualifiedName());
            LinkedHashMap<String, ColumnSpec> cols = new LinkedHashMap<>();
            List<ColumnDefinition> defs = ct.getColumnDefinitions();
            if (defs != null) {
                for (int i = 0; i < defs.size(); i++) {
                    ColumnDefinition cd = defs.get(i);
                    cols.put(normalizeIdent(cd.getColumnName()), toSpec(cd, i));
                }
            }
            tables.put(fqn, cols);
            return;
        }
        if (s instanceof Alter alter) {
            String fqn = fqnOf(alter.getTable().getFullyQualifiedName());
            LinkedHashMap<String, ColumnSpec> cols = tables.computeIfAbsent(fqn, k -> new LinkedHashMap<>());
            if (alter.getAlterExpressions() != null) {
                for (AlterExpression a : alter.getAlterExpressions()) applyAlter(a, cols);
            }
        }
    }

    private void applyAlter(AlterExpression a, LinkedHashMap<String, ColumnSpec> cols) {
        AlterOperation op = a.getOperation();
        if (op == null) return;
        switch (op) {
            case ADD -> {
                if (a.getColDataTypeList() != null) {
                    for (AlterExpression.ColumnDataType c : a.getColDataTypeList()) {
                        String name = normalizeIdent(c.getColumnName());
                        cols.put(name, new ColumnSpec(
                                typeStr(c.getColDataType()),
                                isNullable(c.getColumnSpecs()),
                                cols.size()
                        ));
                    }
                }
            }
            case DROP -> {
                if (a.getColumnName() != null) {
                    cols.remove(normalizeIdent(a.getColumnName()));
                }
            }
            case RENAME -> {
                String oldName = normalizeIdent(a.getColumnOldName());
                String newName = normalizeIdent(a.getColumnName());
                if (oldName != null && newName != null && cols.containsKey(oldName)) {
                    ColumnSpec spec = cols.remove(oldName);
                    cols.put(newName, new ColumnSpec(spec.type(), spec.nullable(), spec.ordinal(), oldName));
                }
            }
            case ALTER, MODIFY, CHANGE -> {
                if (a.getColDataTypeList() != null) {
                    for (AlterExpression.ColumnDataType c : a.getColDataTypeList()) {
                        String name = normalizeIdent(c.getColumnName());
                        ColumnSpec prev = cols.get(name);
                        if (prev == null) continue;
                        cols.put(name, new ColumnSpec(
                                typeStr(c.getColDataType()),
                                isNullable(c.getColumnSpecs()),
                                prev.ordinal(),
                                prev.renamedFrom()
                        ));
                    }
                }
            }
            default -> { /* ignore other DDL */ }
        }
    }

    private static ColumnSpec toSpec(ColumnDefinition cd, int ordinal) {
        return new ColumnSpec(
                typeStr(cd.getColDataType()),
                isNullable(cd.getColumnSpecs()),
                ordinal
        );
    }

    private static String typeStr(ColDataType dt) {
        if (dt == null) return "";
        String name = dt.getDataType() == null ? "" : dt.getDataType().toUpperCase(Locale.ROOT);
        List<String> args = dt.getArgumentsStringList();
        if (args != null && !args.isEmpty()) {
            return name + "(" + String.join(",", args) + ")";
        }
        return name;
    }

    private static boolean isNullable(List<String> specs) {
        if (specs == null) return true;
        for (int i = 0; i < specs.size() - 1; i++) {
            if ("NOT".equalsIgnoreCase(specs.get(i)) && "NULL".equalsIgnoreCase(specs.get(i + 1))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Regex fallback
    // ------------------------------------------------------------------
    private static final Pattern CREATE_TABLE_RE = Pattern.compile(
            "CREATE\\s+(?:OR\\s+REPLACE\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?" +
                    "([\\w`\".]+)\\s*\\((.*?)\\)\\s*(?:;|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private TableMap regexFallback(String sql) {
        TableMap tables = new TableMap();
        Matcher m = CREATE_TABLE_RE.matcher(sql);
        while (m.find()) {
            String fqn = normalizeIdent(m.group(1)).replace("`", "").replace("\"", "");
            LinkedHashMap<String, ColumnSpec> cols = new LinkedHashMap<>();
            int i = 0;
            for (String raw : splitTopLevelCommas(m.group(2))) {
                String trimmed = raw.strip();
                if (trimmed.isEmpty()) continue;
                String upper = trimmed.toUpperCase(Locale.ROOT);
                if (upper.startsWith("PRIMARY ") || upper.startsWith("FOREIGN ")
                        || upper.startsWith("CONSTRAINT ") || upper.startsWith("UNIQUE ")
                        || upper.startsWith("CHECK ")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                if (parts.length < 2) continue;
                String name = normalizeIdent(parts[0]);
                String dtype = parts[1].toUpperCase(Locale.ROOT);
                boolean nullable = !upper.contains("NOT NULL");
                cols.put(name, new ColumnSpec(dtype, nullable, i++));
            }
            tables.put(fqn, cols);
        }
        return tables;
    }

    private static List<String> splitTopLevelCommas(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int depth = 0;
        for (char ch : s.toCharArray()) {
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            if (ch == ',' && depth == 0) {
                out.add(buf.toString());
                buf.setLength(0);
            } else {
                buf.append(ch);
            }
        }
        if (buf.length() > 0) out.add(buf.toString());
        return out;
    }

    // ------------------------------------------------------------------
    // Diff
    // ------------------------------------------------------------------
    private SchemaDelta diffTableMaps(TableMap before, TableMap after) {
        SchemaDelta delta = SchemaDelta.empty();
        Set<String> beforeKeys = before.keySet();
        Set<String> afterKeys  = after.keySet();

        for (String fqn : new TreeSet<>(setMinus(afterKeys, beforeKeys))) {
            delta.addedTables().add(fqn);
        }
        for (String fqn : new TreeSet<>(setMinus(beforeKeys, afterKeys))) {
            delta.droppedTables().add(fqn);
        }
        for (String fqn : new TreeSet<>(setAnd(beforeKeys, afterKeys))) {
            diffColumns(fqn, before.get(fqn), after.get(fqn), delta);
        }
        return delta;
    }

    private void diffColumns(String fqn,
                             LinkedHashMap<String, ColumnSpec> before,
                             LinkedHashMap<String, ColumnSpec> after,
                             SchemaDelta delta) {
        Set<String> bNames = new java.util.HashSet<>(before.keySet());
        Set<String> aNames = new java.util.HashSet<>(after.keySet());

        // 0. Explicit ALTER … RENAME COLUMN hints (renamedFrom is populated).
        for (Map.Entry<String, ColumnSpec> e : after.entrySet()) {
            String renamedFrom = e.getValue().renamedFrom();
            String name = e.getKey();
            if (renamedFrom != null && bNames.contains(renamedFrom)
                    && aNames.contains(name) && !aNames.contains(renamedFrom)) {
                delta.renamedColumns().add(new ColumnRename(fqn, renamedFrom, name, 0.95));
                bNames.remove(renamedFrom);
                aNames.remove(name);
            }
        }

        Set<String> rawAdded   = setMinus(aNames, bNames);
        Set<String> rawDropped = setMinus(bNames, aNames);
        Set<String> retained   = setAnd(aNames, bNames);

        // 1. Type / nullability changes on retained columns.
        for (String name : new TreeSet<>(retained)) {
            ColumnSpec b = before.get(name);
            ColumnSpec a = after.get(name);
            // Emit when base type differs OR when base types match but the full
            // type string differs (e.g. VARCHAR(200) vs VARCHAR(50) — same base,
            // different size).
            if (!normalizeType(b.type()).equals(normalizeType(a.type()))
                    || !b.type().equalsIgnoreCase(a.type())) {
                delta.typeChanges().add(new ColumnChange(
                        fqn, name, b.type(), a.type(), null, null,
                        isNarrowing(b.type(), a.type())
                ));
            }
            if (b.nullable() != a.nullable()) {
                delta.nullabilityChanges().add(new ColumnChange(
                        fqn, name, null, null, b.nullable(), a.nullable(),
                        // NOT-NULL added is a narrowing/breaking change.
                        b.nullable() && !a.nullable()
                ));
            }
        }

        // 2. Heuristic rename: same ordinal + same normalized type.
        List<String> droppedLeft = new ArrayList<>(rawDropped);
        List<String> addedLeft   = new ArrayList<>(rawAdded);
        for (String d : new ArrayList<>(droppedLeft)) {
            ColumnSpec dSpec = before.get(d);
            List<String> matches = new ArrayList<>();
            for (String candidate : addedLeft) {
                ColumnSpec aSpec = after.get(candidate);
                if (aSpec.ordinal() == dSpec.ordinal()
                        && normalizeType(aSpec.type()).equals(normalizeType(dSpec.type()))) {
                    matches.add(candidate);
                }
            }
            if (matches.size() == 1) {
                String a = matches.get(0);
                delta.renamedColumns().add(new ColumnRename(fqn, d, a, 0.7));
                droppedLeft.remove(d);
                addedLeft.remove(a);
            }
        }

        // 3. True add / drop.
        for (String name : new TreeSet<>(addedLeft)) {
            ColumnSpec spec = after.get(name);
            delta.addedColumns().add(ColumnChange.added(fqn, name, spec.type(), spec.nullable()));
        }
        for (String name : new TreeSet<>(droppedLeft)) {
            ColumnSpec spec = before.get(name);
            delta.droppedColumns().add(ColumnChange.dropped(fqn, name, spec.type(), spec.nullable()));
        }
    }

    // ------------------------------------------------------------------
    // Type helpers
    // ------------------------------------------------------------------
    private static String normalizeType(String t) {
        if (t == null) return "";
        return t.toUpperCase(Locale.ROOT).strip().replaceAll("\\s*\\(.*\\)", "").strip();
    }

    private static boolean isNarrowing(String oldType, String newType) {
        String oldBase = normalizeType(oldType);
        String newBase = normalizeType(newType);
        if (oldBase.equals(newBase)) {
            Integer oldSize = parseSize(oldType);
            Integer newSize = parseSize(newType);
            if (oldSize != null && newSize != null) return newSize < oldSize;
            return false;
        }
        if (WIDENING_PAIRS.contains(new TypePair(oldBase, newBase))) return false;
        if (WIDENING_PAIRS.contains(new TypePair(newBase, oldBase))) return true;
        return true; // unknown transition → assume risky
    }

    private static Integer parseSize(String t) {
        if (t == null) return null;
        Matcher m = Pattern.compile("\\(\\s*(\\d+)").matcher(t);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    // ------------------------------------------------------------------
    // Ident helpers
    // ------------------------------------------------------------------
    private static String fqnOf(String rawFqn) {
        if (rawFqn == null) return "";
        return rawFqn.replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private static String normalizeIdent(String s) {
        return s == null ? "" : s.replaceAll("[\"`\\[\\]]", "").toLowerCase(Locale.ROOT);
    }

    private static Set<String> setMinus(Set<String> a, Set<String> b) {
        Set<String> out = new java.util.HashSet<>(a);
        out.removeAll(b);
        return out;
    }

    private static Set<String> setAnd(Set<String> a, Set<String> b) {
        Set<String> out = new java.util.HashSet<>(a);
        out.retainAll(b);
        return out;
    }
}

