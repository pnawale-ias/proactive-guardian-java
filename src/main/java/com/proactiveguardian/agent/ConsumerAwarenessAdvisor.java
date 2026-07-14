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

        // -------- 0) REST-API gate --------
        // This advisory is scoped to REST-API producers: only emit it when the
        // PR actually changes REST-API surface (controllers, endpoints,
        // OpenAPI/Swagger specs, proto/route files, …). Non-API changes
        // (internal refactors, build files, docs, workflows) should not
        // produce a "Known consumers of <service>" section, since a
        // "consumer" here means an HTTP/REST caller of the producer.
        if (!touchesRestApi(pairs)) {
            log.info("consumer-awareness: PR does not touch REST-API surface — skipping advisory");
            return List.of();
        }

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
        // We split tokens into two buckets:
        //   * producerTokens   → the producer repo name in short/full form.
        //                        A hit on one of these is *strong* evidence
        //                        that the candidate repo actually consumes
        //                        this specific REST service (import path,
        //                        HTTP URL, Feign client, dependency GAV,
        //                        k8s service name, …).
        //   * identifierTokens → class / file / workflow names touched by
        //                        the PR. On their own these are unreliable:
        //                        many services independently define a DTO
        //                        called `UserRequest`, so a match does NOT
        //                        prove consumption. We only use these to
        //                        *enrich* evidence for repos that are
        //                        already linked via a producerToken.
        LinkedHashSet<String> producerTokens = new LinkedHashSet<>();
        producerTokens.add(producerShort);
        if (!producerFull.equalsIgnoreCase(producerShort)) producerTokens.add(producerFull);

        LinkedHashSet<String> identifierTokens = new LinkedHashSet<>();
        if (pairs != null) {
            for (GitIngester.Pair p : pairs) {
                collectTokens(p.after(),  identifierTokens);
                collectTokens(p.before(), identifierTokens);
            }
        }
        // Never let a PR-derived identifier collide with a producer-name token.
        identifierTokens.removeIf(t -> t.equalsIgnoreCase(producerShort)
                                     || t.equalsIgnoreCase(producerFull));
        // Filter out stopwords / too-short PR-derived tokens.
        identifierTokens.removeIf(t -> t.length() < MIN_TOKEN_LEN
                              || STOPWORDS.contains(t.toLowerCase(Locale.ROOT)));

        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        tokens.addAll(producerTokens);
        tokens.addAll(identifierTokens);

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

        // -------- 1b) Require producer-name evidence per repo --------
        // A repo is only considered a consumer of *this* REST service if at
        // least one of its indexed artifacts literally references the
        // producer's repo/service name. This filters out false positives
        // where two unrelated services happen to define a class with the
        // same name (e.g. both `samplerestservice` and `generateautocode`
        // define their own `UserRequest` DTO — the identifier match alone
        // does NOT mean `generateautocode` calls `samplerestservice`).
        evidenceByRepo.entrySet().removeIf(e -> {
            var tokenMap = e.getValue();
            boolean hasProducerLink = tokenMap.keySet().stream()
                    .anyMatch(t -> t.equalsIgnoreCase(producerShort)
                                || t.equalsIgnoreCase(producerFull));
            if (!hasProducerLink) {
                log.debug("consumer-awareness: dropping `{}` — matches only via shared identifiers {}, "
                        + "no reference to producer `{}`",
                        e.getKey(), tokenMap.keySet(), producerShort);
            }
            return !hasProducerLink;
        });

        if (evidenceByRepo.isEmpty()) {
            log.info("consumer-awareness: no repo in the KB references producer `{}` directly — skipping advisory",
                    producerShort);
            return List.of();
        }

        // -------- 2) Render the advisory --------
        StringBuilder detail = new StringBuilder();
        detail.append("The following repos have artifacts in the knowledge base that reference `")
              .append(producerShort).append("` directly (by repo/service name, HTTP client, ")
              .append("dependency, etc.). Even if this PR did not trigger a specific ")
              .append("breaking-change signal, please double-check that your changes don't ")
              .append("break them:\n\n");
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

    // ------------------------------------------------------------------
    // REST-API surface detection.
    // Mirrors ConfluenceDocAdvisor#apiChangeLabel — kept local to avoid
    // introducing a shared util class for two callers.
    // ------------------------------------------------------------------

    private static boolean touchesRestApi(List<GitIngester.Pair> pairs) {
        if (pairs == null || pairs.isEmpty()) return false;
        for (GitIngester.Pair p : pairs) {
            if (isRestApiArtifact(p.after()) || isRestApiArtifact(p.before())) return true;
        }
        return false;
    }

    private static boolean isRestApiArtifact(Artifact a) {
        if (a == null) return false;
        // 1. Explicit API_ENDPOINT artifacts emitted by the code parser.
        if (a.type() != null && "API_ENDPOINT".equalsIgnoreCase(a.type().name())) return true;

        // 2. Path/name heuristics for controllers, OpenAPI specs, route files.
        String path = a.path() == null ? "" : a.path().replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = a.name() == null ? "" : a.name().toLowerCase(Locale.ROOT);
        String hay  = path + " " + name;
        if (hay.contains("controller")    ||
            hay.contains("restcontroller") ||
            hay.contains("/resource")     || hay.contains("resource.java") ||
            hay.contains("/routes")       || hay.contains("routes.")       ||
            hay.contains("openapi")       || hay.contains("swagger")       ||
            hay.contains("/api/")         || hay.endsWith(".proto")) {
            return true;
        }

        // 3. Content-level check: look for Spring/JAX-RS/Feign mapping
        //    annotations in the artifact body when available.
        String content = a.content();
        if (content != null && !content.isBlank()) {
            return REST_ANNOTATION.matcher(content).find();
        }
        return false;
    }

    private static final java.util.regex.Pattern REST_ANNOTATION = java.util.regex.Pattern.compile(
            "@(?:RestController|Controller|RequestMapping|GetMapping|PostMapping|" +
            "PutMapping|PatchMapping|DeleteMapping|FeignClient|Path|POST|GET|PUT|DELETE|PATCH)\\b");
}

