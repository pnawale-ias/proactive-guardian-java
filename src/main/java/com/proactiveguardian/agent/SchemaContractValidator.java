package com.proactiveguardian.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proactiveguardian.knowledge.GraphStore;
import com.proactiveguardian.knowledge.VectorStore;
import com.proactiveguardian.knowledge.VectorStore.Hit;
import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates code changes against the ingested MySQL schema knowledge base.
 *
 * For every code artifact (any language — JPA, SQLAlchemy, ActiveRecord, JOOQ,
 * raw SQL, TypeORM, etc.) it:
 *   1. Searches Qdrant for SQL_TABLE / SQL_COLUMN artifacts whose names appear
 *      in the diff.
 *   2. Builds a compact table→columns map from those hits.
 *   3. Sends the diff + schema snapshot to the LLM and asks it to identify
 *      schema contract violations (missing columns, type mismatches, NOT NULL
 *      misuse, wrong FK columns, etc.).
 *   4. Emits one Finding per confirmed violation.
 *
 * No-ops gracefully when no SQL schema has been ingested.
 */
@Component
public class SchemaContractValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaContractValidator.class);

    private final VectorStore vs;
    private final GraphStore gs;
    private final ChatModel chatModel;
    private final ObjectMapper mapper;
    private final String promptTemplate;

    public SchemaContractValidator(VectorStore vs,
                                   GraphStore gs,
                                   ChatModel chatModel,
                                   ObjectMapper mapper,
                                   @Value("classpath:prompts/schema_contract_validator.st") Resource promptResource)
            throws IOException {
        this.vs = vs;
        this.gs = gs;
        this.chatModel = chatModel;
        this.mapper = mapper;
        this.promptTemplate = new String(promptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * @param before artifact before PR change (may be null for new files)
     * @param after  artifact after PR change (null for deletions — skip)
     */
    public List<Finding> check(Artifact before, Artifact after) {
        // Only analyse code artifacts — data artifacts are handled by SchemaChangeDetector
        if (after == null) return List.of();
        if (after.type().isDataArtifact()) return List.of();

        // The diff content to validate — prefer the new version; use before content
        // only if after is somehow empty (shouldn't happen but guard against it)
        String diffContent = after.content();
        if (diffContent == null || diffContent.isBlank()) return List.of();

        // Step 1: extract candidate table names from the code, then look them up
        // deterministically in Neo4j (HAS_COLUMN edges). Fall back to Qdrant
        // similarity only for tables not found in Neo4j.
        Set<String> candidateTables = extractTableCandidates(diffContent);
        Map<String, StringBuilder> tableSchemas = buildSchemaContext(diffContent, candidateTables);
        if (tableSchemas.isEmpty()) return List.of();

        // Step 2: render table→columns map as compact text for the prompt
        StringBuilder schemas = new StringBuilder();
        for (Map.Entry<String, StringBuilder> e : tableSchemas.entrySet()) {
            schemas.append("Table `").append(e.getKey()).append("`:\n")
                   .append(e.getValue()).append("\n");
        }

        String code = truncateAtLine(diffContent, 2500);
        String userPrompt = promptTemplate
                .replace("{schemas}", schemas.toString())
                .replace("{code}", code);

        Prompt prompt = new Prompt(List.of(
                new SystemMessage("You output only valid JSON."),
                new UserMessage(userPrompt)
        ));

        String raw;
        try {
            raw = chatModel.call(prompt).getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("SchemaContractValidator LLM call failed: {}", e.getMessage());
            return List.of();
        }

        return parseFindings(raw, after);
    }

    // ------------------------------------------------------------------

    /**
     * Extract candidate table/class names referenced in the code that could
     * be DB table names: @Table(name="..."), @Column(name="...") annotations,
     * SQL FROM/JOIN/INTO/UPDATE clauses, and uppercase identifiers.
     */
    private static final java.util.regex.Pattern TABLE_ANNOTATION_RE =
            java.util.regex.Pattern.compile(
                    "@(?:Table|Column)\\s*\\([^)]*name\\s*=\\s*[\"']([^\"']+)[\"']",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern SQL_TABLE_RE =
            java.util.regex.Pattern.compile(
                    "\\b(?:FROM|JOIN|INTO|UPDATE|TABLE)\\s+[`\"']?([A-Za-z_][\\w.]*)[`\"']?",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static Set<String> extractTableCandidates(String code) {
        Set<String> candidates = new LinkedHashSet<>();
        var m1 = TABLE_ANNOTATION_RE.matcher(code);
        while (m1.find()) candidates.add(m1.group(1));
        var m2 = SQL_TABLE_RE.matcher(code);
        while (m2.find()) candidates.add(m2.group(1));
        return candidates;
    }

    /**
     * Build a table→columns map by:
     *  1. For each candidate table name, query Neo4j's HAS_COLUMN edges (complete + deterministic).
     *  2. For any table not resolved via Neo4j, fall back to Qdrant vector similarity.
     *
     * Tables resolved from Neo4j are marked [complete]; Qdrant-only tables are marked
     * [partial — do not flag missing columns] so the LLM doesn't generate false positives.
     */
    private Map<String, StringBuilder> buildSchemaContext(String diffContent, Set<String> candidateTables) {
        Map<String, StringBuilder> tables = new LinkedHashMap<>();
        Set<String> completeTables = new HashSet<>();

        // Stage 1: deterministic Neo4j lookup per extracted candidate
        for (String candidate : candidateTables) {
            List<Map<String, Object>> cols = gs.columnsForTable(candidate);
            if (cols.isEmpty()) continue;
            String fqn = cols.get(0).getOrDefault("table_fqn", candidate).toString();
            StringBuilder sb = tables.computeIfAbsent(fqn, k -> new StringBuilder());
            for (Map<String, Object> col : cols) {
                Object colName  = col.get("column");
                Object dataType = col.get("data_type");
                if (colName == null) continue;
                sb.append("  - ").append(colName)
                  .append(" (").append(dataType != null ? dataType : "unknown").append(")\n");
            }
            completeTables.add(fqn);
        }

        // Stage 2: Qdrant fallback for tables not resolved via Neo4j (or when no candidates extracted)
        if (tables.isEmpty()) {
            List<Hit> hits = vs.searchSimilarByTypes(diffContent, 20, List.of("sql_table", "sql_column"));
            for (Hit hit : hits) {
                Object typeObj = hit.payload().get("type");
                if (typeObj == null) continue;
                String type = typeObj.toString();
                String name = hit.getStr("name");
                String content = hit.getStr("content");
                if ("sql_table".equals(type)) {
                    if (name == null) continue;
                    String fqn = resolveTableFqn(hit, name);
                    if (completeTables.contains(fqn)) continue;
                    StringBuilder sb = tables.computeIfAbsent(fqn, k -> new StringBuilder());
                    if (content != null && !content.isBlank() && sb.isEmpty()) {
                        for (String col : content.split(",")) {
                            sb.append("  - ").append(col.trim()).append("\n");
                        }
                        completeTables.add(fqn);
                    }
                } else if ("sql_column".equals(type)) {
                    if (name == null) continue;
                    String tablePart = resolveColumnTable(hit, name);
                    if (completeTables.contains(tablePart)) continue;
                    String colPart = resolveColumnName(hit, name);
                    String dataType = (content != null && !content.isBlank()) ? content : "unknown";
                    tables.computeIfAbsent(tablePart, k -> new StringBuilder())
                          .append("  - ").append(colPart).append(" (").append(dataType).append(")\n");
                }
            }
        }

        // Annotate completeness for the LLM
        for (Map.Entry<String, StringBuilder> e : tables.entrySet()) {
            String label = completeTables.contains(e.getKey())
                    ? " [complete]"
                    : " [partial — do not flag missing columns]";
            e.setValue(new StringBuilder(label + "\n").append(e.getValue()));
        }
        return tables;
    }

    private static String resolveTableFqn(Hit hit, String name) {
        // Try metadata map string for table_fqn, fall back to name
        Object meta = hit.payload().get("metadata");
        if (meta != null) {
            String ms = meta.toString();
            int idx = ms.indexOf("table_fqn=");
            if (idx >= 0) {
                int end = ms.indexOf(',', idx);
                if (end < 0) end = ms.indexOf('}', idx);
                if (end > idx) return ms.substring(idx + 10, end).trim();
            }
        }
        return name;
    }

    private static String resolveColumnTable(Hit hit, String name) {
        // Try metadata string first
        Object meta = hit.payload().get("metadata");
        if (meta != null) {
            String ms = meta.toString();
            int idx = ms.indexOf("table_fqn=");
            if (idx >= 0) {
                int end = ms.indexOf(',', idx);
                if (end < 0) end = ms.indexOf('}', idx);
                if (end > idx) return ms.substring(idx + 10, end).trim();
            }
        }
        // Fall back: split on last '.'
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String resolveColumnName(Hit hit, String name) {
        Object meta = hit.payload().get("metadata");
        if (meta != null) {
            String ms = meta.toString();
            int idx = ms.indexOf("column=");
            if (idx >= 0) {
                int end = ms.indexOf(',', idx);
                if (end < 0) end = ms.indexOf('}', idx);
                if (end > idx) return ms.substring(idx + 7, end).trim();
            }
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private List<Finding> parseFindings(String raw, Artifact artifact) {
        JsonNode data;
        try {
            int start = raw.indexOf('{');
            int end   = raw.lastIndexOf('}') + 1;
            if (start < 0 || end <= start) return List.of();
            data = mapper.readTree(raw.substring(start, end));
        } catch (Exception e) {
            return List.of();
        }

        JsonNode violations = data.path("violations");
        if (!violations.isArray()) return List.of();

        List<Finding> findings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode v : violations) {
            double conf = v.path("confidence").asDouble(0);
            if (conf < 0.6) continue;

            String table   = v.path("table").asText("");
            String field   = v.path("field").asText("");
            String problem = v.path("problem").asText("");
            if (problem.isBlank()) continue;
            if (!seen.add(table + "::" + field + "::" + problem)) continue;

            String title = table.isBlank()
                    ? "Schema contract violation in `" + artifact.name() + "`"
                    : "Schema contract: `" + table + "." + field + "` — " + problem;

            findings.add(new Finding(
                    conf >= 0.85 ? Severity.BLOCK : Severity.WARN,
                    "schema_contract",
                    title,
                    problem + " (in `" + artifact.path() + "`)",
                    List.of(artifact.repo() + "::" + artifact.path() + "::" + artifact.name()),
                    conf
            ));
            log.debug("schema_contract violation: table={} field={} conf={}", table, field, conf);
        }
        return findings;
    }

    private static String truncateAtLine(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        int cut = s.lastIndexOf('\n', maxChars);
        if (cut <= 0) cut = maxChars;
        return s.substring(0, cut) + "\n// … (truncated for brevity)";
    }
}
