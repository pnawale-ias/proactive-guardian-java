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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Emits a single "known consumers of this service" advisory finding per PR so
 * reviewers see the downstream blast radius EVEN when no specific breaking-
 * change signal fired. Complements {@link BreakingChangeDetector}, which only
 * lists per-consumer verdicts when a required-field / removed-field signal is
 * actually detected.
 *
 * <p><b>Consumer detection:</b> a repo is treated as a potential consumer of
 * {@code producerRepo} only if the knowledge base contains at least one
 * artifact belonging to that repo whose content references the producer
 * (e.g. imports the producer's package, calls its HTTP client, declares it
 * as a Maven/Gradle dependency, uses its GitHub Action, etc.). We locate
 * such artifacts by running a similarity search for the producer's short
 * name against the vector store and keeping hits above a confidence
 * threshold. Repos with zero matching artifacts are skipped, so the
 * advisory no longer lists every ingested repo indiscriminately.</p>
 */
@Component
public class ConsumerAwarenessAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ConsumerAwarenessAdvisor.class);

    /** Soft cap on scan results per producer-name variant. */
    private static final int SEARCH_K = 500;
    /** Cap on hits per PR-derived token — a token matching thousands of artifacts is not useful. */
    private static final int PER_TOKEN_CAP = 200;
    /** Skip tokens shorter than this to avoid matching everything (`id`, `dto`, …). */
    private static final int MIN_TOKEN_LEN = 4;

    /** Common English/tech words that would match every repo. */
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

    public ConsumerAwarenessAdvisor(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /** Back-compat: no PR context — only the repo name is used as a token. */
    public List<Finding> advise(String producerRepo) {
        return advise(producerRepo, List.of());
    }

    /**
     * Return a single INFO advisory listing repos in the KB that literally
     * reference any identifier this PR touches (class/controller/workflow
     * name, …) OR the producer repo name itself. The repo name alone is
     * rarely mentioned by consumers — they import the producer's classes
     * or hit its endpoints — so we scan for both.
     */
    public List<Finding> advise(String producerRepo, List<GitIngester.Pair> pairs) {
        if (producerRepo == null || producerRepo.isBlank()) return List.of();

        Map<String, Long> counts;
        try {
            counts = vectorStore.repoCounts();
        } catch (Exception e) {
            log.debug("consumer-awareness: repoCounts() failed: {}", e.getMessage());
            return List.of();
        }
        if (counts == null || counts.isEmpty()) return List.of();

        String producerShort = shortRepo(producerRepo);
        String producerFull  = producerRepo;

        // -------- 1) Build the set of tokens we will scan the KB for --------
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        tokens.add(producerShort);
        if (!producerFull.equalsIgnoreCase(producerShort)) tokens.add(producerFull);
        if (pairs != null) {
            for (GitIngester.Pair p : pairs) {
                collectTokens(p.after(),  tokens);
                collectTokens(p.before(), tokens);
            }
        }
        // Filter out stopwords / too-short tokens (but always keep the repo names).
        tokens.removeIf(t -> !t.equalsIgnoreCase(producerShort)
                          && !t.equalsIgnoreCase(producerFull)
                          && (t.length() < MIN_TOKEN_LEN
                              || STOPWORDS.contains(t.toLowerCase(Locale.ROOT))));

        // repo -> (token -> list of evidence labels)
        java.util.Map<String, java.util.Map<String, java.util.LinkedHashSet<String>>>
                evidenceByRepo = new java.util.TreeMap<>();

        try {
            for (String token : tokens) {
                List<VectorStore.Hit> hits;
                try {
                    hits = vectorStore.scanReferences(token, PER_TOKEN_CAP);
                } catch (Exception e) {
                    log.debug("consumer-awareness: scanReferences({}) failed: {}", token, e.getMessage());
                    continue;
                }
                if (hits == null) continue;
                for (VectorStore.Hit h : hits) {
                    String repo = h.getStr("repo");
                    if (repo == null || repo.isBlank()) continue;
                    if (shortRepo(repo).equalsIgnoreCase(producerShort)) continue;
                    // Confluence pages aren't "consumers" — the ConfluenceDocAdvisor handles them.
                    String type = h.getStr("type");
                    if (type != null && "confluence_page".equalsIgnoreCase(type)) continue;

                    String name = h.getStr("name");
                    String path = h.getStr("path");
                    String label = path != null ? repoRelative(path)
                                                 : (name != null ? name : "?");
                    evidenceByRepo
                        .computeIfAbsent(repo, r -> new java.util.LinkedHashMap<>())
                        .computeIfAbsent(token, t -> new java.util.LinkedHashSet<>())
                        .add(label);
                }
            }
        } catch (Exception e) {
            log.debug("consumer-awareness: scan loop failed: {}", e.getMessage());
            return List.of();
        }

        if (evidenceByRepo.isEmpty()) {
            log.info("consumer-awareness: no repo in the KB references any of {} token(s) — skipping advisory",
                    tokens.size());
            return List.of();
        }

        // -------- 2) Render the advisory --------
        StringBuilder detail = new StringBuilder();
        detail.append("The following repos have artifacts in the knowledge base that reference `")
              .append(producerShort).append("` (by repo name and/or by identifiers this PR touches). ")
              .append("Even if this PR did not trigger a specific breaking-change signal, please ")
              .append("double-check that your changes don't break them:\n\n");
        for (var entry : evidenceByRepo.entrySet()) {
            String repo = entry.getKey();
            long indexed = counts.getOrDefault(repo, 0L);
            var tokenMap = entry.getValue();
            // Total distinct labels across all matched tokens for this repo.
            java.util.LinkedHashSet<String> allLabels = new java.util.LinkedHashSet<>();
            for (var s : tokenMap.values()) allLabels.addAll(s);

            detail.append("- `").append(shortRepo(repo)).append("`")
                  .append("   _(").append(indexed).append(" artifact(s) indexed, ")
                  .append(allLabels.size()).append(" reference(s) via ")
                  .append(tokenMap.size()).append(" token(s): ")
                  .append(String.join(", ", limitTokens(tokenMap.keySet(), 5)))
                  .append(")_\n");
            java.util.List<String> shownList = new java.util.ArrayList<>(allLabels);
            int shown = Math.min(shownList.size(), 3);
            for (int i = 0; i < shown; i++) {
                detail.append("    - ").append(shownList.get(i)).append('\n');
            }
            if (shownList.size() > shown) {
                detail.append("    - _…and ").append(shownList.size() - shown).append(" more_\n");
            }
        }
        detail.append("\n_Missing a repo? Run `bash scripts/ingest-org.sh <owner>` to ingest it. ")
              .append("A repo that isn't in the knowledge base cannot be checked automatically._");

        List<Finding> out = new ArrayList<>(1);
        out.add(new Finding(
                Severity.INFO,
                "consumer_awareness",
                "🔔 Known consumers of `" + producerShort + "` — please verify",
                detail.toString(),
                List.copyOf(evidenceByRepo.keySet()),
                0.75
        ));
        return out;
    }

    private static java.util.List<String> limitTokens(java.util.Collection<String> in, int max) {
        java.util.List<String> l = new java.util.ArrayList<>(in);
        if (l.size() <= max) return l;
        java.util.List<String> head = new java.util.ArrayList<>(l.subList(0, max));
        head.add("+" + (l.size() - max) + " more");
        return head;
    }

    // ------------------------------------------------------------------
    // Token extraction (mirrors ConfluenceDocAdvisor — kept local to avoid
    // creating a shared utility class for two callers).
    // ------------------------------------------------------------------

    private static void collectTokens(Artifact a, Set<String> out) {
        if (a == null) return;
        if (a.name() != null && !a.name().isBlank()) addIdentifier(a.name(), out);
        if (a.path() != null && !a.path().isBlank()) {
            String p = a.path().replace('\\', '/');
            int slash = p.lastIndexOf('/');
            String base = slash < 0 ? p : p.substring(slash + 1);
            int dot = base.indexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            addIdentifier(base, out);
        }
    }

    private static void addIdentifier(String id, Set<String> out) {
        if (id == null) return;
        String s = id.trim();
        if (s.isEmpty()) return;
        out.add(s);
        for (String part : s.split("(?<=[a-z0-9])(?=[A-Z])|[_\\-\\s\\.]+")) {
            if (!part.isBlank()) out.add(part);
        }
    }

    private static String repoRelative(String path) {
        return PathUtils.repoRelative(path);
    }

    private static String shortRepo(String repo) {
        if (repo == null) return "";
        int i = repo.lastIndexOf('/');
        return i < 0 ? repo : repo.substring(i + 1);
    }
}

