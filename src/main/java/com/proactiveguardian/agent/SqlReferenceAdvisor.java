package com.proactiveguardian.agent;

import com.proactiveguardian.ingestion.GitIngester;
import com.proactiveguardian.knowledge.GraphStore;
import com.proactiveguardian.knowledge.VectorStore;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Advisor that surfaces SQL schema impact for code-level PRs.
 *
 * When a PR modifies Java/Python/Go/etc. files that reference MySQL tables,
 * this advisor looks up the FK dependency graph and vector store to find
 * downstream tables and other consumers, then emits a Finding so reviewers
 * know the blast radius.
 *
 * No-ops gracefully when the SQL knowledge base is empty.
 */
@Component
public class SqlReferenceAdvisor {

    private static final Logger log = LoggerFactory.getLogger(SqlReferenceAdvisor.class);

    // SQL keyword tokens that appear as table-name-position identifiers but aren't tables
    private static final Set<String> SKIP_TOKENS = Set.of(
            "select", "from", "where", "join", "inner", "outer", "left", "right",
            "on", "and", "or", "not", "in", "is", "null", "true", "false",
            "set", "into", "update", "delete", "create", "drop", "alter", "table",
            "index", "view", "database", "schema", "if", "exists", "as", "by",
            "group", "order", "having", "limit", "offset", "distinct", "all",
            "values", "insert", "replace", "truncate", "case", "when", "then", "else", "end",
            "int", "varchar", "text", "bigint", "datetime", "timestamp", "boolean",
            "with", "union", "except", "intersect", "cross", "natural", "full"
    );

    // Matches table-name position in common SQL keywords
    private static final Pattern SQL_TABLE_PATTERN = Pattern.compile(
            "(?i)(?:FROM|JOIN|INTO|UPDATE|TABLE)\\s+([`\"']?)(\\w+)\\1",
            Pattern.CASE_INSENSITIVE
    );

    private final VectorStore vs;
    private final GraphStore gs;

    public SqlReferenceAdvisor(VectorStore vs, GraphStore gs) {
        this.vs = vs;
        this.gs = gs;
    }

    /**
     * For each code-file pair in the PR, extract candidate table names from the
     * diff content, look them up in the SQL knowledge base, and emit a Finding
     * when FK-dependent tables or known consumers exist.
     */
    public List<Finding> advise(String repoName, List<GitIngester.Pair> pairs) {
        List<Finding> findings = new ArrayList<>();
        // Deduplicate findings by table name — one finding per table per PR
        Set<String> emittedTables = new LinkedHashSet<>();

        for (GitIngester.Pair pair : pairs) {
            var after = pair.after();
            if (after == null) continue;
            // Only analyse code artifacts — SQL/DBT/etc. are handled by SchemaChangeDetector
            if (after.type().isDataArtifact()) continue;

            Set<String> candidates = extractTableCandidates(after.content(), after.name());
            if (candidates.isEmpty()) continue;

            for (String candidate : candidates) {
                if (emittedTables.contains(candidate)) continue;

                // Vector search to confirm this token matches a known SQL_TABLE artifact
                String tableFqn = resolveTableFqn(candidate);
                if (tableFqn == null) continue;

                List<Map<String, Object>> consumers = gs.consumersOfTable(tableFqn);
                if (consumers.isEmpty()) continue;

                emittedTables.add(candidate);

                // Partition: cross-repo consumers are the high-signal cases
                Set<String> crossRepo = crossRepoOnly(consumers, repoName);
                Severity severity = crossRepo.isEmpty() ? Severity.INFO : Severity.WARN;
                int n = consumers.size();
                double confidence = Math.min(1.0, n / 15.0);

                String title = "Code references table `" + tableFqn + "` with " + n + " downstream dependent(s)";
                StringBuilder detail = new StringBuilder();
                detail.append("File `").append(after.path()).append("` references `").append(tableFqn)
                      .append("`. The knowledge graph found **").append(n)
                      .append(" dependent(s)**");
                if (!crossRepo.isEmpty()) {
                    detail.append(" including cross-repo impact: ");
                    crossRepo.forEach(r -> detail.append("`").append(r).append("` "));
                }
                detail.append(".");

                findings.add(new Finding(severity, "sql_impact", title,
                        detail.toString(), evidence(consumers), confidence));
                log.debug("sql_impact: {} → {} consumers ({})", tableFqn, n, repoName);
            }
        }
        return findings;
    }

    // ------------------------------------------------------------------

    private Set<String> extractTableCandidates(String content, String artifactName) {
        Set<String> out = new LinkedHashSet<>();
        if (content != null) {
            Matcher m = SQL_TABLE_PATTERN.matcher(content);
            while (m.find()) {
                String tok = m.group(2).toLowerCase(Locale.ROOT);
                if (!SKIP_TOKENS.contains(tok) && tok.length() >= 3) out.add(tok);
            }
        }
        // Artifact name itself (e.g. "OrderDao" → "order", "orders")
        if (artifactName != null) {
            String base = artifactName.replaceAll("(?i)(dao|service|repository|mapper|manager|handler)$", "")
                                       .toLowerCase(Locale.ROOT);
            if (base.length() >= 3 && !SKIP_TOKENS.contains(base)) out.add(base);
        }
        // Cap to 10 to limit vector search calls
        if (out.size() > 10) {
            Set<String> capped = new LinkedHashSet<>();
            out.stream().limit(10).forEach(capped::add);
            return capped;
        }
        return out;
    }

    /** Vector-search for a candidate table name; returns the table_fqn if a SQL_TABLE hit is found. */
    private String resolveTableFqn(String candidate) {
        try {
            List<VectorStore.Hit> hits = vs.searchSimilar(candidate, 5);
            for (VectorStore.Hit hit : hits) {
                Object type = hit.payload().get("type");
                if ("sql_table".equals(type)) {
                    Object fqn = hit.payload().get("table_fqn");
                    if (fqn != null) return fqn.toString();
                    // Fallback: use the name field
                    Object name = hit.payload().get("name");
                    if (name != null) return name.toString();
                }
            }
        } catch (Exception e) {
            log.debug("vector lookup failed for candidate '{}': {}", candidate, e.getMessage());
        }
        return null;
    }

    private static Set<String> crossRepoOnly(List<Map<String, Object>> consumers, String ownRepo) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> c : consumers) {
            Object r = c.get("repo");
            if (r != null && !r.toString().equals(ownRepo)) out.add(r.toString());
        }
        return out;
    }

    private static List<String> evidence(List<Map<String, Object>> consumers) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> c : consumers) {
            if (out.size() >= 10) break;
            String repo = c.getOrDefault("repo", "?").toString();
            String path = c.getOrDefault("path", "?").toString();
            String name = c.getOrDefault("name", "?").toString();
            out.add(repo + "::" + path + "::" + name);
        }
        return out;
    }
}
