package com.proactiveguardian.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proactiveguardian.knowledge.VectorStore;
import com.proactiveguardian.knowledge.VectorStore.Hit;
import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.model.PrContext;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final ChatModel chatModel;
    private final ObjectMapper mapper;
    private final String promptTemplate;

    public SchemaContractValidator(VectorStore vs,
                                   ChatModel chatModel,
                                   ObjectMapper mapper,
                                   @Value("classpath:prompts/schema_contract_validator.st") Resource promptResource)
            throws IOException {
        this.vs = vs;
        this.chatModel = chatModel;
        this.mapper = mapper;
        this.promptTemplate = new String(promptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * @param before artifact before PR change (may be null for new files)
     * @param after  artifact after PR change (null for deletions — skip)
     */
    public List<Finding> check(Artifact before, Artifact after) {
        return check(before, after, PrContext.empty());
    }

    public List<Finding> check(Artifact before, Artifact after, PrContext prCtx) {
        // Only analyse code artifacts — data artifacts are handled by SchemaChangeDetector
        if (after == null) return List.of();
        if (after.type().isDataArtifact()) return List.of();

        // The diff content to validate — prefer the new version; use before content
        // only if after is somehow empty (shouldn't happen but guard against it)
        String diffContent = after.content();
        if (diffContent == null || diffContent.isBlank()) return List.of();

        // Step 1: find SQL_TABLE and SQL_COLUMN hits relevant to this diff
        List<Hit> schemaHits = vs.searchSimilar(diffContent, 20);
        Map<String, StringBuilder> tableSchemas = buildSchemaContext(schemaHits);
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
                .replace("{code}", code)
                .replace("{pr_title}", prCtx.prTitle())
                .replace("{commit_msg}", prCtx.commitMessage());

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
     * From raw Qdrant hits, build a table→"  - col_name (type)\n" map.
     * SQL_TABLE hits provide table identity; SQL_COLUMN hits provide column rows.
     * The metadata map is serialised as toString() in Qdrant so we reconstruct
     * table_fqn from the `name` field ("schema.table" or "table.column" form).
     */
    private Map<String, StringBuilder> buildSchemaContext(List<Hit> hits) {
        Map<String, StringBuilder> tables = new LinkedHashMap<>();

        for (Hit hit : hits) {
            Object typeObj = hit.payload().get("type");
            if (typeObj == null) continue;
            String type = typeObj.toString();

            String name = hit.getStr("name");
            String content = hit.getStr("content");

            if ("sql_table".equals(type)) {
                // name = table name (e.g. "orders"), content = "col1 type1, col2 type2, ..."
                if (name == null) continue;
                String fqn = resolveTableFqn(hit, name);
                tables.computeIfAbsent(fqn, k -> new StringBuilder());
                // Content already has a column summary; use it as fallback if no column hits
                if (content != null && !content.isBlank()
                        && tables.get(fqn).isEmpty()) {
                    for (String col : content.split(",")) {
                        tables.get(fqn).append("  - ").append(col.trim()).append("\n");
                    }
                }
            } else if ("sql_column".equals(type)) {
                // name = "table.column", content = data_type
                if (name == null) continue;
                // Reconstruct table name from the dot-separated name
                String tablePart = resolveColumnTable(hit, name);
                String colPart = resolveColumnName(hit, name);
                String dataType = (content != null && !content.isBlank()) ? content : "unknown";
                tables.computeIfAbsent(tablePart, k -> new StringBuilder());
                tables.get(tablePart).append("  - ").append(colPart)
                      .append(" (").append(dataType).append(")\n");
            }
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
        for (JsonNode v : violations) {
            double conf = v.path("confidence").asDouble(0);
            if (conf < 0.6) continue;

            String table   = v.path("table").asText("");
            String field   = v.path("field").asText("");
            String problem = v.path("problem").asText("");
            if (problem.isBlank()) continue;

            String title = table.isBlank()
                    ? "Schema contract violation in `" + artifact.name() + "`"
                    : "Schema contract: `" + table + "." + field + "` — " +
                      problem.substring(0, Math.min(60, problem.length()));

            String fix = v.path("fix_suggestion").asText("");
            String detail = problem + " (in `" + artifact.path() + "`)";
            if (!fix.isBlank()) detail += "\n\n> 💡 **Suggested fix:** " + fix;

            findings.add(new Finding(
                    conf >= 0.85 ? Severity.BLOCK : Severity.WARN,
                    "schema_contract",
                    title,
                    detail,
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
