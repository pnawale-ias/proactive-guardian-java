package com.proactiveguardian.agent;

import com.proactiveguardian.knowledge.GraphStore;
import com.proactiveguardian.knowledge.VectorStore;
import com.proactiveguardian.knowledge.VectorStore.Hit;
import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects breaking API changes across ingested repos — port of
 * {@code src/agents/breaking_change_detector.py::BreakingChangeDetector}.
 */
@Component
public class BreakingChangeDetector {

    private static final Logger log = LoggerFactory.getLogger(BreakingChangeDetector.class);

    /** Per-language signature regex: (returnOrName, params[, return]) groups. */
    private static final Map<String, Pattern> SIGNATURE_PATTERNS = Map.ofEntries(
            Map.entry("python",     Pattern.compile("def\\s+(\\w+)\\s*\\(([^)]*)\\)")),
            Map.entry("java",       Pattern.compile("(?:public|private|protected|static|final|\\s)+([\\w<>\\[\\],\\s]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)")),
            Map.entry("kotlin",     Pattern.compile("fun\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?::\\s*([\\w<>?]+))?")),
            Map.entry("go",         Pattern.compile("func\\s+(?:\\([^)]+\\)\\s*)?(\\w+)\\s*\\(([^)]*)\\)\\s*([\\w\\[\\]\\*\\.]*)")),
            Map.entry("typescript", Pattern.compile("(?:function|async\\s+function)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*:?\\s*([\\w<>\\[\\]|]*)")),
            Map.entry("tsx",        Pattern.compile("(?:function|async\\s+function)\\s+(\\w+)\\s*\\(([^)]*)\\)")),
            Map.entry("javascript", Pattern.compile("function\\s+(\\w+)\\s*\\(([^)]*)\\)")),
            Map.entry("rust",       Pattern.compile("fn\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:->\\s*([\\w<>&\\[\\]]+))?")),
            Map.entry("cpp",        Pattern.compile("([\\w:<>\\*&]+)\\s+(\\w+)\\s*\\(([^)]*)\\)")),
            Map.entry("c",          Pattern.compile("([\\w\\*]+)\\s+(\\w+)\\s*\\(([^)]*)\\)")),
            Map.entry("c_sharp",    Pattern.compile("(?:public|private|protected|internal|static|\\s)+([\\w<>\\[\\],\\s]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)")),
            Map.entry("ruby",       Pattern.compile("def\\s+(\\w+)\\s*\\(?([^)\\n]*)\\)?")),
            Map.entry("proto",      Pattern.compile("rpc\\s+(\\w+)\\s*\\(\\s*([\\w\\.]+)\\s*\\)\\s*returns\\s*\\(\\s*([\\w\\.]+)\\s*\\)"))
    );

    /** Symbol names too generic to trust for literal consumer search. */
    private static final Set<String> GENERIC_NAMES = Set.of(
            "get", "set", "run", "do", "handle", "process", "init", "main",
            "start", "stop", "close", "open", "read", "write", "load", "save",
            "User", "Item", "Data", "Config", "Client", "Service", "Manager"
    );

    private final VectorStore vs;
    private final GraphStore gs;

    public BreakingChangeDetector(VectorStore vs, GraphStore gs) {
        this.vs = vs;
        this.gs = gs;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------
    public List<Finding> check(Artifact before, Artifact after) {
        if (before == null && after == null) return List.of();
        if (after == null) return deletedFinding(before);
        if (before == null) return addedFinding(after);

        String change = compare(before, after);
        if (change == null) {
            String bContent = before.content() == null ? "" : before.content();
            String aContent = after.content()  == null ? "" : after.content();
            log.debug("breaking: no change detected for {} (lang={}, beforeLen={}, afterLen={}, equal={})",
                    after.name(), after.language(),
                    bContent.length(), aContent.length(),
                    bContent.equals(aContent));
            return List.of();
        }
        log.debug("breaking: change detected for {} — {}", after.name(),
                change.length() > 120 ? change.substring(0, 120) + "..." : change);

        List<Map<String, Object>> consumers = findConsumers(after.name(), after.repo());
        Set<String> crossRepo = new TreeSet<>();
        for (Map<String, Object> c : consumers) {
            Object repo = c.get("repo");
            if (repo != null && !repo.equals(after.repo())) crossRepo.add(repo.toString());
        }

        Severity severity = !crossRepo.isEmpty() ? Severity.BLOCK
                : (!consumers.isEmpty() ? Severity.WARN : Severity.INFO);
        double confidence = !crossRepo.isEmpty() ? 0.95 : (!consumers.isEmpty() ? 0.8 : 0.6);

        StringBuilder detail = new StringBuilder(change);
        if (!consumers.isEmpty()) {
            Set<Object> repos = new HashSet<>();
            for (Map<String, Object> c : consumers) if (c.get("repo") != null) repos.add(c.get("repo"));
            detail.append("\n\n**").append(consumers.size())
                    .append(" consumer(s)** found across ").append(repos.size()).append(" repo(s).");
            if (!crossRepo.isEmpty()) {
                detail.append("\n⚠️ Cross-repo impact: `").append(String.join("`, `", crossRepo)).append("`");
            }
        }

        List<String> evidence = new ArrayList<>();
        for (Map<String, Object> c : consumers.stream().limit(10).toList()) {
            evidence.add(String.format("%s::%s::%s",
                    c.getOrDefault("repo", "?"),
                    c.getOrDefault("path", "?"),
                    c.getOrDefault("name", "?")));
        }

        return List.of(new Finding(
                severity,
                "breaking_change",
                "Breaking API change in `" + after.name() + "`",
                detail.toString(),
                evidence,
                confidence
        ));
    }

    /** Externally invoked when the after-version is {@code null}. */
    public List<Finding> deletedFinding(Artifact before) {
        List<Map<String, Object>> consumers = findConsumers(before.name(), before.repo());
        return List.of(new Finding(
                consumers.isEmpty() ? Severity.WARN : Severity.BLOCK,
                "breaking_change",
                "Symbol `" + before.name() + "` was deleted",
                "`" + before.name() + "` from `" + before.repo() + "` removed; "
                        + consumers.size() + " consumer(s) still reference it.",
                consumers.stream().limit(10)
                        .map(c -> c.getOrDefault("repo", "?") + "::" + c.getOrDefault("path", "?"))
                        .map(Object::toString)
                        .toList(),
                consumers.isEmpty() ? 0.7 : 0.98
        ));
    }

    /**
     * Emitted when a symbol/file appears in the PR but has no prior version.
     * A pure addition isn't a break by itself, but reporting it as
     * {@link Severity#INFO} means the pipeline surfaces newly-added workflows,
     * schemas, and config files instead of silently returning zero findings.
     */
    public List<Finding> addedFinding(Artifact after) {
        String where = after.path() != null ? after.path()
                : (after.repo() != null ? after.repo() : "?");
        String lang = after.language() == null ? "unknown" : after.language();
        String preview = after.content() == null ? "" : after.content();
        if (preview.length() > 400) preview = preview.substring(0, 400) + "\n… (truncated)";
        return List.of(new Finding(
                Severity.INFO,
                "breaking_change",
                "New symbol `" + after.name() + "` added",
                "`" + after.name() + "` introduced in `" + where + "` (lang=" + lang + ").\n\n"
                        + (preview.isBlank() ? "" : "```" + lang + "\n" + preview + "\n```"),
                List.of(),
                0.4
        ));
    }

    // ------------------------------------------------------------------
    // Signature comparison
    // ------------------------------------------------------------------
    private String compare(Artifact before, Artifact after) {
        String lang = after.language() == null ? "" : after.language();
        Pattern pattern = SIGNATURE_PATTERNS.get(lang);

        if (pattern != null) {
            Matcher b = pattern.matcher(before.content());
            Matcher a = pattern.matcher(after.content());
            if (b.find() && a.find()) {
                if (groupsEqual(b, a)) return null;
                return "**Signature changed** (" + lang + "):\n"
                        + "```diff\n"
                        + "- " + b.group(0).strip() + "\n"
                        + "+ " + a.group(0).strip() + "\n"
                        + "```";
            }
        }

        // Fallback #1: first-line change — catches renames of the declaring line
        // for languages without a signature regex.
        String bLine = firstLine(before.content());
        String aLine = firstLine(after.content());
        if (!bLine.equals(aLine)) {
            return "**First-line change** (fallback):\n"
                    + "```diff\n- " + bLine + "\n+ " + aLine + "\n```";
        }

        // Fallback #2: full content-level diff. This catches edits to YAML
        // workflows / K8s manifests / config files (where the top-level key is
        // stable but the body changes), as well as internal code edits that
        // preserve the method signature. Without this, YAML PRs always
        // produced 0 breaking-change findings.
        String beforeContent = before.content() == null ? "" : before.content();
        String afterContent  = after.content()  == null ? "" : after.content();
        if (beforeContent.equals(afterContent)) return null;

        String diff = renderDiff(beforeContent, afterContent, 40);
        if (diff.isBlank()) return null;   // pure whitespace-only edit
        String kind = lang.isBlank() ? "text" : lang;
        return "**Content changed** (" + kind + "):\n"
                + "```diff\n" + diff + "```";
    }

    private static boolean groupsEqual(Matcher a, Matcher b) {
        if (a.groupCount() != b.groupCount()) return false;
        for (int i = 1; i <= a.groupCount(); i++) {
            String ga = a.group(i);
            String gb = b.group(i);
            if (ga == null ? gb != null : !ga.equals(gb)) return false;
        }
        return true;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).strip();
    }

    /**
     * Compact set-based line diff (order-preserving). Lines only in {@code before}
     * are prefixed with {@code -}, lines only in {@code after} with {@code +}.
     * Not an LCS diff, but good enough for a PR-comment summary and cheap.
     * Truncates after {@code maxLines} changed lines to keep comments readable.
     */
    private static String renderDiff(String before, String after, int maxLines) {
        String[] bLines = before.split("\r?\n", -1);
        String[] aLines = after.split("\r?\n", -1);
        Set<String> bSet = new LinkedHashSet<>();
        for (String l : bLines) bSet.add(l);
        Set<String> aSet = new LinkedHashSet<>();
        for (String l : aLines) aSet.add(l);

        StringBuilder sb = new StringBuilder();
        int emitted = 0;
        boolean truncated = false;
        for (String line : bLines) {
            if (line.isBlank() || aSet.contains(line)) continue;
            if (emitted >= maxLines) { truncated = true; break; }
            sb.append("- ").append(line).append('\n');
            emitted++;
        }
        if (!truncated) {
            for (String line : aLines) {
                if (line.isBlank() || bSet.contains(line)) continue;
                if (emitted >= maxLines) { truncated = true; break; }
                sb.append("+ ").append(line).append('\n');
                emitted++;
            }
        }
        if (truncated) sb.append("... (diff truncated)\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Consumer discovery (cross-repo)
    // ------------------------------------------------------------------
    private List<Map<String, Object>> findConsumers(String symbolName, String ownRepo) {
        if (symbolName == null || symbolName.isBlank()
                || GENERIC_NAMES.contains(symbolName) || symbolName.length() < 3) {
            return List.of();
        }

        List<Hit> candidates = vs.searchSimilar(symbolName, 40);
        Pattern tokenRe = Pattern.compile("\\b" + Pattern.quote(symbolName) + "\\b");

        List<Map<String, Object>> consumers = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Hit h : candidates) {
            Map<String, Object> p = h.payload();
            Object id = p.get("id");
            if (id == null || !seen.add(id.toString())) continue;
            if (ownRepo != null && ownRepo.equals(p.get("repo"))
                    && symbolName.equals(p.get("name"))) continue;
            String content = p.get("content") == null ? "" : p.get("content").toString();
            if (tokenRe.matcher(content).find()) consumers.add(p);
        }

        try {
            for (Map<String, Object> hop : gs.impactRadiusByName(symbolName, 2)) {
                Object id = hop.get("id");
                if (id != null && seen.add(id.toString())) consumers.add(hop);
            }
        } catch (Exception ignored) { /* graph optional in tests */ }

        return consumers;
    }
}

