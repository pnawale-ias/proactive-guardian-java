package com.proactiveguardian.agent;

import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses every YAML artifact in the PR head with SnakeYAML. When parsing
 * fails, emits a single {@code yaml_syntax} finding pointing at the offending
 * line + column and suggesting a probable fix based on the exception text.
 *
 * <p>Runs BEFORE the breaking-change gate so that syntax problems in
 * workflow YAML, k8s manifests, and Spring config still surface (whereas
 * {@code BreakingChangeDetector} intentionally ignores those files).</p>
 */
@Component
public class YamlSyntaxValidator {

    private static final Logger log = LoggerFactory.getLogger(YamlSyntaxValidator.class);

    public List<Finding> check(Artifact after) {
        if (after == null) return List.of();
        if (!looksLikeYaml(after)) return List.of();

        String content = after.content();
        if (content == null || content.isBlank()) return List.of();

        // SnakeYAML: load ALL documents so multi-doc `---` files are validated
        // end-to-end (k8s manifests are typically multi-document).
        List<Object> docs = new ArrayList<>();
        try {
            Yaml yaml = new Yaml();
            for (Object doc : yaml.loadAll(content)) {
                docs.add(doc);
            }
        } catch (MarkedYAMLException e) {
            return List.of(buildFinding(after, content, e));
        } catch (YAMLException e) {
            return List.of(new Finding(
                    Severity.BLOCK,
                    "yaml_syntax",
                    "Invalid YAML in `" + shortPath(after.path()) + "`",
                    "YAML parser failed: " + safe(e.getMessage())
                            + "\n\n_Fix the syntax before merging — CI / runtime tools will reject this file._",
                    List.of(shortPath(after.path())),
                    0.95));
        } catch (Exception e) {
            log.debug("yaml-syntax: unexpected error validating {}: {}", after.path(), e.toString());
            return List.of();
        }

        // Syntax is fine — run lightweight schema checks for well-known file kinds.
        if (isGithubWorkflow(after.path())) {
            return validateGithubWorkflow(after, content, docs);
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static boolean looksLikeYaml(Artifact a) {
        String lang = a.language() == null ? "" : a.language().toLowerCase();
        String path = a.path() == null ? "" : a.path().toLowerCase();
        return "yaml".equals(lang) || "yml".equals(lang)
                || path.endsWith(".yaml") || path.endsWith(".yml");
    }

    private static Finding buildFinding(Artifact after, String content, MarkedYAMLException e) {
        Mark mark = e.getProblemMark() != null ? e.getProblemMark() : e.getContextMark();
        int lineNo = mark == null ? -1 : mark.getLine() + 1;      // SnakeYAML is 0-based
        int colNo  = mark == null ? -1 : mark.getColumn() + 1;
        String excerpt = renderExcerpt(content, lineNo, colNo);
        String probableFix = suggestFix(e, content, lineNo);

        StringBuilder detail = new StringBuilder();
        detail.append("YAML parse error at **line ").append(lineNo)
              .append(", column ").append(colNo).append("**:\n\n")
              .append("> ").append(safe(shortMessage(e))).append("\n\n")
              .append("```yaml\n").append(excerpt).append("```\n");
        if (probableFix != null && !probableFix.isBlank()) {
            detail.append("\n**Probable fix:** ").append(probableFix).append('\n');
        }
        detail.append("\n_This file will fail to load at runtime / in CI until the syntax is corrected._");

        return new Finding(
                Severity.BLOCK,
                "yaml_syntax",
                "Invalid YAML in `" + shortPath(after.path()) + "` (line " + lineNo + ")",
                detail.toString(),
                List.of(shortPath(after.path()) + ":" + lineNo),
                0.98);
    }

    /** Render 3 lines of context around the failing line with a caret under the column. */
    private static String renderExcerpt(String content, int lineNo, int colNo) {
        if (lineNo <= 0) return "(no line information)\n";
        String[] lines = content.split("\r?\n", -1);
        int from = Math.max(1, lineNo - 2);
        int to   = Math.min(lines.length, lineNo + 2);
        StringBuilder sb = new StringBuilder();
        int gutter = String.valueOf(to).length();
        for (int i = from; i <= to; i++) {
            sb.append(String.format("%" + gutter + "d | %s%n", i, lines[i - 1]));
            if (i == lineNo && colNo > 0) {
                sb.append(" ".repeat(gutter))
                  .append(" | ")
                  .append(" ".repeat(Math.max(0, colNo - 1)))
                  .append("^\n");
            }
        }
        return sb.toString();
    }

    /**
     * Turn common SnakeYAML error phrasings into an actionable suggestion.
     * Best-effort — falls back to {@code null} for anything we can't classify.
     */
    private static String suggestFix(MarkedYAMLException e, String content, int lineNo) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        String line = safeLine(content, lineNo);
        String trimmed = line.strip();

        // Tabs are illegal for indentation in YAML.
        if (line.contains("\t")) {
            return "This line uses **tab characters** for indentation. Replace tabs with spaces (typically 2).";
        }
        if (msg.contains("mapping values are not allowed here")) {
            return "A `key: value` appears where the parser expected a scalar. "
                    + "Check that the previous line ended cleanly and that this key is indented under its parent.";
        }
        if (msg.contains("could not find expected ':'")) {
            return "The parser expected `:` after a mapping key. Add the missing colon, or wrap the value in quotes if it contains `:` inside a string.";
        }
        if (msg.contains("expected <block end>")
                || msg.contains("expected the node content")
                || msg.contains("found character that cannot start any token")) {
            return "Indentation looks inconsistent. Make sure every child key sits under its parent by exactly the same number of spaces.";
        }
        if (msg.contains("while scanning a quoted scalar") || msg.contains("unexpected end of stream")) {
            return "A quoted string is not closed. Add the missing `\"` or `'` on line " + lineNo + ".";
        }
        if (msg.contains("while parsing a flow")) {
            return "Unbalanced `{ }` or `[ ]` in a flow-style mapping/sequence. Match up the brackets.";
        }
        if (msg.contains("found duplicate key")) {
            return "Two entries in the same mapping share the same key. Remove or rename the duplicate.";
        }
        if (msg.contains("found undefined alias")) {
            return "You referenced an anchor with `*name`, but no `&name` is defined earlier in the file.";
        }
        if (!trimmed.isEmpty() && trimmed.startsWith("-") && !trimmed.contains(" ")) {
            return "A list item `- value` needs a space after the dash: `- value` (not `-value`).";
        }
        return null;
    }

    private static String safeLine(String content, int lineNo) {
        if (content == null || lineNo <= 0) return "";
        String[] lines = content.split("\r?\n", -1);
        if (lineNo > lines.length) return "";
        return lines[lineNo - 1];
    }

    private static String shortMessage(MarkedYAMLException e) {
        String m = e.getMessage();
        if (m == null) return "(no message)";
        int nl = m.indexOf('\n');
        return nl < 0 ? m : m.substring(0, nl);
    }

    private static String shortPath(String path) {
        if (path == null) return "?";
        // Strip tmp-dir noise: keep only from the last /src/ or /.github/ etc.
        for (String marker : new String[]{"/src/", "/.github/", "/k8s/", "/kubernetes/",
                "/helm/", "/charts/", "/manifests/", "/config/", "/deploy/"}) {
            int i = path.lastIndexOf(marker);
            if (i >= 0) return path.substring(i + 1);
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\r", "").trim();
    }

    // ------------------------------------------------------------------
    // GitHub Actions workflow schema — lightweight checks
    // ------------------------------------------------------------------

    private static boolean isGithubWorkflow(String path) {
        if (path == null) return false;
        String p = path.replace('\\', '/').toLowerCase();
        return p.contains("/.github/workflows/")
                && (p.endsWith(".yml") || p.endsWith(".yaml"));
    }

    /**
     * GitHub Actions files must declare {@code on:} at the top level. A very
     * common mistake is writing {@code trigger:} (Azure Pipelines) or
     * {@code triggers:} instead — the YAML is syntactically valid, but the
     * workflow will silently never run. We also sanity-check {@code jobs}.
     */
    @SuppressWarnings("unchecked")
    private static List<Finding> validateGithubWorkflow(Artifact after, String content, List<Object> docs) {
        if (docs.isEmpty() || !(docs.get(0) instanceof java.util.Map<?, ?> root)) {
            return List.of();
        }
        java.util.Map<String, Object> m = (java.util.Map<String, Object>) root;
        List<Finding> out = new ArrayList<>();
        String path = shortPath(after.path());

        boolean hasOn = m.containsKey("on") || m.containsKey(Boolean.TRUE); // `on:` is parsed as boolean true by SnakeYAML 1.x
        if (!hasOn) {
            String wrongKey = null;
            for (String bad : new String[]{"trigger", "triggers", "onn", "when", "events"}) {
                if (m.containsKey(bad)) { wrongKey = bad; break; }
            }
            int lineNo = wrongKey != null ? findKeyLine(content, wrongKey) : 1;
            StringBuilder detail = new StringBuilder();
            detail.append("This workflow file has no top-level **`on:`** key")
                  .append(wrongKey != null
                          ? " — found `" + wrongKey + ":` instead.\n\n"
                          : ".\n\n")
                  .append("GitHub Actions requires an `on:` block to declare triggers. ")
                  .append("Without it, the workflow will be accepted by the YAML parser ")
                  .append("but **will never execute**.\n\n")
                  .append("**Fix:** rename the key to `on:` — e.g.\n\n")
                  .append("```yaml\non:\n  repository_dispatch:\n    types: [auto_gen_code]\n```\n");
            out.add(new Finding(
                    Severity.BLOCK,
                    "workflow_schema",
                    "GitHub Actions workflow missing `on:` trigger in `" + path + "`",
                    detail.toString(),
                    List.of(path + (lineNo > 0 ? ":" + lineNo : "")),
                    0.97));
        }

        Object jobs = m.get("jobs");
        if (!(jobs instanceof java.util.Map<?, ?> jm) || jm.isEmpty()) {
            out.add(new Finding(
                    Severity.BLOCK,
                    "workflow_schema",
                    "GitHub Actions workflow missing `jobs:` in `" + path + "`",
                    "Every workflow must declare at least one job under a top-level `jobs:` mapping.",
                    List.of(path),
                    0.95));
        } else {
            for (var e : ((java.util.Map<String, Object>) jm).entrySet()) {
                if (!(e.getValue() instanceof java.util.Map<?, ?> job)) continue;
                java.util.Map<String, Object> j = (java.util.Map<String, Object>) job;
                if (!j.containsKey("runs-on") && !j.containsKey("uses")) {
                    out.add(new Finding(
                            Severity.WARN,
                            "workflow_schema",
                            "Job `" + e.getKey() + "` missing `runs-on` in `" + path + "`",
                            "Job `" + e.getKey() + "` declares no `runs-on:` runner and is not a reusable-workflow call (`uses:`). It will fail to start.",
                            List.of(path),
                            0.9));
                }
                if (!j.containsKey("steps") && !j.containsKey("uses")) {
                    out.add(new Finding(
                            Severity.WARN,
                            "workflow_schema",
                            "Job `" + e.getKey() + "` has no `steps` in `" + path + "`",
                            "Job `" + e.getKey() + "` has no `steps:` list.",
                            List.of(path),
                            0.85));
                }
            }
        }
        return out;
    }

    /** Locate the 1-based line where a top-level key appears. Best-effort. */
    private static int findKeyLine(String content, String key) {
        if (content == null || key == null) return -1;
        String[] lines = content.split("\r?\n", -1);
        String needle = key + ":";
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i];
            // top-level = no leading whitespace
            if (!s.isEmpty() && !Character.isWhitespace(s.charAt(0)) && s.startsWith(needle)) {
                return i + 1;
            }
        }
        return -1;
    }
}

