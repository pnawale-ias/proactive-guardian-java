package com.proactiveguardian.agent;

import com.proactiveguardian.ingestion.GitIngester;
import com.proactiveguardian.knowledge.VectorStore;
import com.proactiveguardian.model.Artifact;
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
import java.util.TreeSet;

/**
 * Emits a per-PR advisory listing Confluence pages that likely document the
 * files/classes changed in the diff — so reviewers can update the docs (or
 * confirm they're still accurate) before merge.
 *
 * <p><b>How it works.</b> For every changed pair we derive a small set of
 * "salient tokens" from the changed artifact (class name from a Java file,
 * workflow file base-name, SQL table name, …). We then ask the vector store
 * for every payload that literally mentions the token as a whole word, and
 * keep only those with {@code type == confluence_page}. Deterministic
 * (payload text-match), so we never surface pages that only happen to be
 * semantically close to the diff.</p>
 */
@Component
public class ConfluenceDocAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceDocAdvisor.class);

    /** Cap on hits per token — a token that matches thousands of pages is not useful. */
    private static final int PER_TOKEN_CAP = 50;
    /** Skip tokens shorter than this to avoid matching everything (`id`, `dto`, …). */
    private static final int MIN_TOKEN_LEN = 4;
    /** Max pages to surface in the finding — sort by token-match count desc before truncating. */
    private static final int MAX_PAGES = 5;

    /** Very common English/tech words we never want to trigger on. */
    private static final Set<String> STOPWORDS = Set.of(
            "main", "test", "tests", "src", "java", "python", "kotlin", "scala",
            "app", "api", "apis", "core", "util", "utils", "common", "commons",
            "config", "configs", "model", "models", "service", "services",
            "controller", "controllers", "repository", "repositories",
            "impl", "impls", "helper", "helpers", "base", "abstract",
            "dto", "dtos", "entity", "entities", "readme", "index",
            "application", "workflow", "workflows", "deployment", "deployments",
            "values", "chart", "charts", "pom", "gradle", "build",
            "yaml", "yml", "json", "xml", "properties",
            "github", "gitlab", "actions", "action",
            "public", "private", "class", "interface", "record", "enum"
    );

    private final VectorStore vectorStore;

    public ConfluenceDocAdvisor(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * @param pairs the changed artifact pairs for the PR
     * @return zero or one INFO finding pointing at Confluence pages that
     *         mention any of the changed identifiers
     */
    public List<Finding> advise(List<GitIngester.Pair> pairs) {
        if (pairs == null || pairs.isEmpty()) return List.of();

        // Collect salient tokens across every changed artifact, and note
        // whether the diff touches anything that *should* be documented on
        // Confluence (an API endpoint, controller, OpenAPI spec, …).
        Set<String> tokens = new LinkedHashSet<>();
        List<String> apiChanges = new ArrayList<>();
        for (GitIngester.Pair p : pairs) {
            collectTokens(p.after(),  tokens);
            collectTokens(p.before(), tokens);
            String label = apiChangeLabel(p.after() != null ? p.after() : p.before());
            if (label != null) apiChanges.add(label);
        }
        tokens.removeIf(t -> t.length() < MIN_TOKEN_LEN
                || STOPWORDS.contains(t.toLowerCase(Locale.ROOT)));
        if (tokens.isEmpty() && apiChanges.isEmpty()) return List.of();

        // page-id -> aggregated match info
        Map<String, PageMatch> byPage = new LinkedHashMap<>();
        for (String token : tokens) {
            List<VectorStore.Hit> hits;
            try {
                hits = vectorStore.scanReferences(token, PER_TOKEN_CAP);
            } catch (Exception e) {
                log.debug("confluence-doc: scanReferences({}) failed: {}", token, e.getMessage());
                continue;
            }
            if (hits == null) continue;
            for (VectorStore.Hit h : hits) {
                String type = h.getStr("type");
                if (!"confluence_page".equalsIgnoreCase(type)) continue;
                String id    = h.getStr("id");
                String title = h.getStr("name");
                String url   = h.getStr("url");
                if (id == null && url == null && title == null) continue;
                String key = id != null ? id : (url != null ? url : title);
                byPage.computeIfAbsent(key, k -> new PageMatch(title, url))
                      .tokens.add(token);
            }
        }

        // Case A: at least one Confluence page mentions a changed identifier
        //         → ask the reviewer to UPDATE the page.
        if (!byPage.isEmpty()) {
            return List.of(buildUpdateFinding(byPage));
        }

        // Case B: no Confluence page in the KB references anything this PR
        //         touched. Prefer showing the API-relevant surface when the
        //         PR clearly changes public API (controllers, OpenAPI specs,
        //         workflows, …); otherwise fall back to the plain file list
        //         so the reviewer is *always* nudged to document the change.
        List<String> surface = !apiChanges.isEmpty() ? apiChanges : fileSurface(pairs);
        if (surface.isEmpty()) {
            log.info("confluence-doc: no documentable surface in this PR");
            return List.of();
        }
        log.info("confluence-doc: no Confluence page references any of {} token(s) — asking reviewer to add a page for {} change(s)",
                tokens.size(), surface.size());
        return List.of(buildMissingFinding(surface, !apiChanges.isEmpty()));
    }

    /** Fallback surface: every changed file, best-effort short path. */
    private static List<String> fileSurface(List<GitIngester.Pair> pairs) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (GitIngester.Pair p : pairs) {
            Artifact a = p.after() != null ? p.after() : p.before();
            if (a == null) continue;
            String label = a.path() != null ? shortPath(a.path())
                         : a.name() != null ? a.name()
                         : a.id();
            if (label != null && !label.isBlank()) out.add("`" + label + "`");
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------
    // Finding builders
    // ------------------------------------------------------------------

    private static Finding buildUpdateFinding(Map<String, PageMatch> byPage) {
        // Sort by descending token-match count so the most relevant pages come first.
        List<PageMatch> sorted = byPage.values().stream()
                .sorted((a, b) -> Integer.compare(b.tokens.size(), a.tokens.size()))
                .toList();
        List<PageMatch> shown = sorted.subList(0, Math.min(sorted.size(), MAX_PAGES));
        int hidden = sorted.size() - shown.size();

        StringBuilder detail = new StringBuilder();
        detail.append("The following Confluence page(s) in the knowledge base mention identifiers ")
              .append("changed in this PR. They may need to be **updated** (or confirmed still accurate):\n\n");
        List<String> evidence = new ArrayList<>();
        for (PageMatch pm : shown) {
            String linkText = pm.title != null && !pm.title.isBlank() ? pm.title : "(untitled page)";
            if (pm.url != null && !pm.url.isBlank()) {
                detail.append("- [").append(linkText).append("](").append(pm.url).append(")");
                evidence.add(pm.url);
            } else {
                detail.append("- ").append(linkText);
                evidence.add(linkText);
            }
            detail.append("  — mentions: ");
            int i = 0;
            for (String t : pm.tokens) {
                if (i++ > 0) detail.append(", ");
                detail.append("`").append(t).append("`");
            }
            detail.append('\n');
        }
        if (hidden > 0) {
            detail.append("- _…and ").append(hidden).append(" more_\n");
        }
        detail.append("\n_Tip: if the page is stale, update it in the same PR (or open a follow-up) ")
              .append("to keep documentation and code in sync._");

        return new Finding(
                Severity.INFO,
                "confluence_doc_drift",
                "📄 Confluence pages that may need updating",
                detail.toString(),
                List.copyOf(evidence),
                0.7
        );
    }

    private static Finding buildMissingFinding(List<String> surface, boolean isApiChange) {
        StringBuilder detail = new StringBuilder();
        if (isApiChange) {
            detail.append("This PR changes **API-facing code**, but no Confluence page in the ")
                  .append("knowledge base mentions the affected identifiers. Please **add a ")
                  .append("Confluence page** documenting these changes (endpoints, request / ")
                  .append("response shapes, error codes) so downstream consumers can discover them.\n\n")
                  .append("**Changed API surface:**\n\n");
        } else {
            detail.append("No Confluence page in the knowledge base mentions the files or ")
                  .append("identifiers changed in this PR. If this change is user- or ")
                  .append("consumer-visible, please **add a Confluence page** so downstream ")
                  .append("teams can find it — otherwise resolve this comment.\n\n")
                  .append("**Changed surface:**\n\n");
        }
        List<String> evidence = new ArrayList<>();
        int shown = Math.min(surface.size(), 10);
        for (int i = 0; i < shown; i++) {
            detail.append("- ").append(surface.get(i)).append('\n');
            evidence.add(surface.get(i));
        }
        if (surface.size() > shown) {
            detail.append("- _…and ").append(surface.size() - shown).append(" more_\n");
        }
        detail.append("\n_Tip: after publishing the page, re-run_ `bash scripts/ingest-confluence.sh` _")
              .append("so the knowledge base picks it up and future PRs get update prompts instead of this one._");

        return new Finding(
                isApiChange ? Severity.WARN : Severity.INFO,
                "confluence_doc_missing",
                isApiChange
                    ? "📝 Missing Confluence documentation for API change"
                    : "📝 No Confluence page found — consider adding one",
                detail.toString(),
                List.copyOf(evidence),
                0.7
        );
    }

    // ------------------------------------------------------------------
    // Detection: does this artifact represent something that should be
    // documented on Confluence? Returns a human-readable label for the
    // finding, or {@code null} when the artifact is not API-relevant.
    // ------------------------------------------------------------------

    private static String apiChangeLabel(Artifact a) {
        if (a == null) return null;
        // 1. Explicit API_ENDPOINT artifacts from the code parser.
        if (a.type() != null && "api_endpoint".equalsIgnoreCase(a.type().name())
                || (a.type() != null && "API_ENDPOINT".equals(a.type().name()))) {
            String name = a.name() != null ? a.name() : (a.path() != null ? a.path() : a.id());
            return "API endpoint `" + name + "`";
        }
        // 2. Path/name heuristics for controllers, OpenAPI specs, route files.
        String path = a.path() == null ? "" : a.path().replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = a.name() == null ? "" : a.name().toLowerCase(Locale.ROOT);
        String hay  = path + " " + name;
        if (hay.contains("controller")   ||
            hay.contains("restcontroller")||
            hay.contains("/resource")    || hay.contains("resource.java") ||
            hay.contains("/routes")      || hay.contains("routes.")       ||
            hay.contains("openapi")      || hay.contains("swagger")       ||
            hay.contains("/api/")        || hay.endsWith(".proto")) {
            String label = a.path() != null ? a.path() : (a.name() != null ? a.name() : a.id());
            return "`" + shortPath(label) + "`";
        }
        return null;
    }

    private static String shortPath(String p) {
        if (p == null) return "?";
        String s = p.replace('\\', '/');
        int i = s.indexOf("/guardian-ingest-");
        if (i >= 0) {
            int slash = s.indexOf('/', i + "/guardian-ingest-".length());
            if (slash > 0 && slash + 1 < s.length()) return s.substring(slash + 1);
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Token extraction
    // ------------------------------------------------------------------

    private static void collectTokens(Artifact a, Set<String> out) {
        if (a == null) return;
        // Class / symbol name (e.g. "ConsumerController")
        if (a.name() != null && !a.name().isBlank()) {
            addIdentifier(a.name(), out);
        }
        // File base-name from path (e.g. "autogencode.yml" -> "autogencode")
        if (a.path() != null && !a.path().isBlank()) {
            String p = a.path().replace('\\', '/');
            int slash = p.lastIndexOf('/');
            String base = slash < 0 ? p : p.substring(slash + 1);
            int dot = base.indexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            addIdentifier(base, out);
        }
    }

    /**
     * Add an identifier as a whole, plus its space-joined camelCase phrase form.
     * e.g. "UserRequest" → {"UserRequest", "User Request"}
     * The phrase form lets scanReferences match natural-language Confluence content
     * without re-introducing individual word tokens that cause noise.
     */
    private static void addIdentifier(String id, Set<String> out) {
        if (id == null) return;
        String s = id.trim();
        if (s.isEmpty()) return;
        out.add(s);
        // Space-joined phrase (only when every part clears MIN_TOKEN_LEN, to avoid
        // noisy short-word phrases like "get Email" from "getEmail").
        String[] parts = s.split("(?<=[a-z0-9])(?=[A-Z])|[_\\-\\s\\.]+");
        if (parts.length > 1) {
            boolean allLongEnough = java.util.Arrays.stream(parts)
                    .allMatch(p -> p.length() >= MIN_TOKEN_LEN);
            if (allLongEnough) {
                String phrase = String.join(" ", parts);
                if (!phrase.equals(s)) out.add(phrase);
            }
        }
    }

    private static class PageMatch {
        final String title;
        final String url;
        final Set<String> tokens = new TreeSet<>();
        PageMatch(String title, String url) { this.title = title; this.url = url; }
    }
}

