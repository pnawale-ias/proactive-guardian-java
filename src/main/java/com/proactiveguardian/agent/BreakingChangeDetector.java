package com.proactiveguardian.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proactiveguardian.config.BreakingChangeLlmProperties;
import com.proactiveguardian.knowledge.GraphStore;
import com.proactiveguardian.knowledge.VectorStore;
import com.proactiveguardian.knowledge.VectorStore.Hit;
import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.model.Severity;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    // Optional LLM impact-prediction collaborators. When any is null the
    // detector falls back to the pure lexical/graph baseline (used by unit
    // tests and by deployments that opt out via guardian.breaking.llm.enabled).
    private final ChatModel chatModel;
    private final ObjectMapper mapper;
    private final String promptTemplate;
    private final BreakingChangeLlmProperties llmProps;
    private final ExecutorService llmExecutor;

    /**
     * Test / no-LLM constructor. Findings are computed purely from lexical +
     * graph consumer discovery, matching pre-LLM behaviour.
     */
    public BreakingChangeDetector(VectorStore vs, GraphStore gs) {
        this(vs, gs, null, null, null, BreakingChangeLlmProperties.disabled());
    }

    /**
     * Production constructor. Spring wires {@link ChatModel} (OpenAI or Ollama
     * depending on {@code AiProfileSelector}), and after lexical consumer
     * discovery the detector asks the LLM which of those consumers will
     * actually break in downstream repos.
     */
    @Autowired
    public BreakingChangeDetector(VectorStore vs,
                                  GraphStore gs,
                                  ChatModel chatModel,
                                  ObjectMapper mapper,
                                  @Value("classpath:prompts/breaking_change_predictor.st") Resource promptResource,
                                  BreakingChangeLlmProperties llmProps) {
        this.vs = vs;
        this.gs = gs;
        this.chatModel = chatModel;
        this.mapper = mapper;
        this.llmProps = llmProps == null ? BreakingChangeLlmProperties.defaults() : llmProps;
        this.promptTemplate = loadPrompt(promptResource);
        this.llmExecutor = (chatModel == null || this.promptTemplate == null || mapper == null)
                ? null
                : Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "breaking-change-llm");
                    t.setDaemon(true);
                    return t;
                });
        if (chatModel != null) {
            log.info("BreakingChangeDetector wired with ChatModel={} model={} (llm.enabled={})",
                    chatModel.getClass().getName(), resolveModelName(chatModel), this.llmProps.enabled());
        } else {
            log.info("BreakingChangeDetector wired without ChatModel — lexical baseline only");
        }
    }

    private static String loadPrompt(Resource r) {
        if (r == null) return null;
        try {
            return new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not load breaking_change_predictor prompt: {}", e.getMessage());
            return null;
        }
    }

    /** Reflectively extract the underlying model name from any Spring AI ChatModel. */
    private static String resolveModelName(Object chatModel) {
        try {
            Object opts = chatModel.getClass().getMethod("getDefaultOptions").invoke(chatModel);
            if (opts == null) return "<unknown>";
            Object name = opts.getClass().getMethod("getModel").invoke(opts);
            return name == null ? "<unknown>" : name.toString();
        } catch (ReflectiveOperationException e) {
            return "<unknown>";
        }
    }

    @PreDestroy
    void shutdown() {
        if (llmExecutor != null) llmExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------
    public List<Finding> check(Artifact before, Artifact after) {
        if (before == null && after == null) return List.of();

        // Non-code / non-API files (CI workflows, k8s manifests, docker-compose,
        // application.yml, README, ...) never produce meaningful "breaking API
        // change" findings. Skip them entirely — both the modify path AND the
        // add/delete paths — so we don't ship noise like
        // "Symbol `on` was deleted" for a .github/workflows/*.yml edit.
        Artifact probe = after != null ? after : before;
        if (!isBreakingChangeRelevant(probe)) {
            log.debug("breaking: skipping non-API artifact {} (path={})",
                    probe.name(), probe.path());
            return List.of();
        }

        if (after == null) return deletedFinding(before);
        if (before == null) return addedFinding(after);

        ChangeAnalysis analysis = analyze(before, after);
        String change = analysis == null ? null : analysis.diff();
        RequiredFieldSignal reqSignal = analysis == null ? null : analysis.requiredSignal();
        RemovedFieldSignal  remSignal = analysis == null ? null : analysis.removedSignal();
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

        // For field-level changes, broaden consumer discovery to include the
        // enclosing DTO class name and any REST path we can extract from the
        // artifact — the field name alone (e.g. "phone") is too generic to find
        // downstream callers that POST JSON to /users without importing the DTO.
        List<Map<String, Object>> consumers = analysis.hasFieldSignal()
                ? findConsumersBroad(after, analysis)
                : findConsumers(after.name(), after.repo());

        // Classify each consumer against the impacted fields.
        List<String> impactedFields = analysis.impactedFields();
        Map<String, ConsumerVerdict> verdictByConsumerId = new LinkedHashMap<>();
        Map<String, Set<String>> setsByRepo    = new TreeMap<>();
        Map<String, Set<String>> missingByRepo = new TreeMap<>();
        Map<String, Set<String>> unclearByRepo = new TreeMap<>();
        for (Map<String, Object> c : consumers) {
            ConsumerVerdict v = classifyConsumer(c, impactedFields);
            Object id = c.get("id");
            if (id != null) verdictByConsumerId.put(id.toString(), v);
            Object repoObj = c.get("repo");
            String repo = repoObj == null ? "?" : repoObj.toString();
            if (v.sets   && reqSignal != null) setsByRepo   .computeIfAbsent(repo, k -> new TreeSet<>()).add(shortLoc(c));
            if (v.missing)                     missingByRepo.computeIfAbsent(repo, k -> new TreeSet<>()).add(shortLoc(c));
            if (v.unclear && !v.sets && !v.missing)
                                               unclearByRepo.computeIfAbsent(repo, k -> new TreeSet<>()).add(shortLoc(c));
        }

        Set<String> crossRepo = new TreeSet<>();
        for (Map<String, Object> c : consumers) {
            Object repo = c.get("repo");
            if (repo != null && !repo.equals(after.repo())) crossRepo.add(repo.toString());
        }

        // Severity: BLOCK when a cross-repo consumer is likely to break,
        // WARN when we know consumers exist but can't confirm breakage,
        // INFO otherwise. Required-field additions and field removals are
        // both floored at WARN even without evidence.
        boolean hasConfirmedBreak = !missingByRepo.isEmpty()
                || (remSignal != null && !consumers.isEmpty());
        Severity severity = hasConfirmedBreak ? Severity.BLOCK
                : (!crossRepo.isEmpty() ? Severity.BLOCK
                : (!consumers.isEmpty() ? Severity.WARN : Severity.INFO));
        double confidence = hasConfirmedBreak ? 0.95
                : (!crossRepo.isEmpty() ? 0.9
                : (!consumers.isEmpty() ? 0.75 : 0.6));
        if ((reqSignal != null || remSignal != null) && severity == Severity.INFO) {
            severity = Severity.WARN;
            confidence = 0.75;
        }

        StringBuilder detail = new StringBuilder(change);
        if (reqSignal != null) {
            detail.append("\n\n**⚠️ Newly required field(s):** `")
                  .append(String.join("`, `", reqSignal.fieldNames())).append("`");
            if (reqSignal.enclosingType() != null && !reqSignal.enclosingType().isBlank()) {
                detail.append(" in `").append(reqSignal.enclosingType()).append("`");
            }
            if (reqSignal.restPath() != null && !reqSignal.restPath().isBlank()) {
                detail.append(" (endpoint `").append(reqSignal.restPath()).append("`)");
            }
            detail.append(". Consumer requests that omit these fields will now be rejected (HTTP 400 / validation error).");
        }
        if (remSignal != null) {
            detail.append("\n\n**⚠️ Removed field(s):** `")
                  .append(String.join("`, `", remSignal.fieldNames())).append("`");
            if (remSignal.enclosingType() != null && !remSignal.enclosingType().isBlank()) {
                detail.append(" from `").append(remSignal.enclosingType()).append("`");
            }
            detail.append(". Consumers still reading these fields will get `null` / missing values.");
        }

        // Per-repo verdict table — this is the actionable core for reviewers.
        if (!missingByRepo.isEmpty() || !setsByRepo.isEmpty() || !unclearByRepo.isEmpty()) {
            detail.append("\n\n### 📡 Downstream consumer impact\n");
            if (!missingByRepo.isEmpty()) {
                detail.append("\n**🚫 Will break** — do NOT set `")
                      .append(String.join("`, `", impactedFields)).append("`:\n");
                missingByRepo.forEach((r, locs) -> detail.append("- `").append(r).append("` — ")
                        .append(String.join(", ", locs)).append('\n'));
            }
            if (reqSignal != null && !setsByRepo.isEmpty()) {
                detail.append("\n**✅ Safe** — already set the field(s):\n");
                setsByRepo.forEach((r, locs) -> detail.append("- `").append(r).append("` — ")
                        .append(String.join(", ", locs)).append('\n'));
            }
            if (!unclearByRepo.isEmpty()) {
                detail.append("\n**❓ Uncertain** — reference the DTO/endpoint but usage is unclear from static analysis:\n");
                unclearByRepo.forEach((r, locs) -> detail.append("- `").append(r).append("` — ")
                        .append(String.join(", ", locs)).append('\n'));
            }
        } else if (analysis.hasFieldSignal()) {
            detail.append("\n\n_No downstream consumer files were found in the knowledge base for this DTO/endpoint. "
                    + "Ensure every repo under your org (e.g. `softwarepravin2007/*`) has been ingested — "
                    + "see `scripts/ingest-consumers.sh`. Any consumer that omits the field will still break at runtime._");
        }

        if (!consumers.isEmpty()) {
            Set<Object> repos = new HashSet<>();
            for (Map<String, Object> c : consumers) if (c.get("repo") != null) repos.add(c.get("repo"));
            detail.append("\n\n_Scanned **").append(consumers.size())
                    .append(" consumer file(s)** across **").append(repos.size()).append(" repo(s)**._");
            if (!crossRepo.isEmpty()) {
                detail.append(" Cross-repo: `").append(String.join("`, `", crossRepo)).append("`.");
            }
        }

        // Ask the LLM which consumers will actually break in downstream repos.
        LlmVerdict verdict = predictBreakage(after, change, consumers);

        if (verdict.hasSignal()) {
            severity = mergeSeverity(severity, verdict);
            confidence = mergeConfidence(confidence, verdict);
            appendLlmDetail(detail, verdict);
        }

        List<String> evidence = buildEvidence(consumers, verdict);

        String title;
        String subject = analysis.enclosingType() != null && !analysis.enclosingType().isBlank()
                ? analysis.enclosingType() : after.name();
        if (reqSignal != null && remSignal != null) {
            title = "Breaking API change in `" + subject + "` (required + removed field)";
        } else if (reqSignal != null) {
            title = "Newly required field in `" + subject + "` — downstream consumers may break";
        } else if (remSignal != null) {
            title = "Field removed from `" + subject + "` — downstream consumers may break";
        } else {
            title = "Breaking API change in `" + after.name() + "`";
        }

        return List.of(new Finding(
                severity,
                "breaking_change",
                title,
                detail.toString(),
                evidence,
                confidence
        ));
    }

    /** Verdict for a single consumer wrt one or more impacted fields. */
    private record ConsumerVerdict(boolean sets, boolean missing, boolean unclear) {}

    /**
     * Inspects a consumer's raw content to decide whether it sets the impacted
     * field(s). Recognises common patterns across Java / Kotlin / Python / JS:
     *   • Setter or builder call: {@code .setPhone(} or {@code .phone(}.
     *   • Map / JSON literal key: {@code "phone"} or {@code 'phone'}.
     *   • Struct/object literal: {@code phone:} or {@code phone =}.
     * A hit means the consumer already sends the field — safe. Otherwise, if
     * the consumer references the DTO / REST path but never mentions the
     * field, it will break at runtime.
     */
    private static ConsumerVerdict classifyConsumer(Map<String, Object> consumer, List<String> fields) {
        String content = consumer.get("content") == null ? "" : consumer.get("content").toString();
        if (content.isBlank() || fields.isEmpty()) {
            return new ConsumerVerdict(false, false, true);
        }
        boolean anySet = false;
        boolean anyMissing = false;
        for (String f : fields) {
            if (f == null || f.isBlank()) continue;
            if (consumerSetsField(content, f)) anySet = true;
            else anyMissing = true;
        }
        // If we saw at least one field being set and none missing, safe.
        // If any field is missing, flag as breaking.
        return new ConsumerVerdict(anySet && !anyMissing, anyMissing, !anySet && !anyMissing);
    }

    /** Heuristic: does {@code content} appear to set the field named {@code f}? */
    private static boolean consumerSetsField(String content, String f) {
        // Builder / setter: .setPhone(  or  .phone(
        String cap = Character.toUpperCase(f.charAt(0)) + f.substring(1);
        if (Pattern.compile("\\.(?:set)?" + Pattern.quote(cap) + "\\s*\\(").matcher(content).find()) return true;
        if (Pattern.compile("\\." + Pattern.quote(f) + "\\s*\\(").matcher(content).find()) return true;
        // JSON / Map literal key: "phone"  or  'phone'
        if (Pattern.compile("[\"']" + Pattern.quote(f) + "[\"']\\s*[:,]").matcher(content).find()) return true;
        if (Pattern.compile("[\"']" + Pattern.quote(f) + "[\"']\\s*,").matcher(content).find()) return true;
        // Struct-lit: phone: value  or  phone = value
        if (Pattern.compile("(?m)^\\s*" + Pattern.quote(f) + "\\s*[:=]").matcher(content).find()) return true;
        return false;
    }

    private static String shortLoc(Map<String, Object> c) {
        Object p = c.get("path");
        Object n = c.get("name");
        String path = p == null ? "?" : p.toString();
        // Strip leading absolute-path noise if present.
        int idx = path.lastIndexOf("/src/");
        if (idx > 0) path = path.substring(idx + 1);
        return "`" + path + (n == null ? "" : "::" + n) + "`";
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
        String where = after.path() != null ? PathUtils.repoRelative(after.path())
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
    // Change analysis (signature + required-field aware)
    // ------------------------------------------------------------------

    /** Wraps the diff text with any structured signal we extracted from it. */
    private record ChangeAnalysis(String diff,
                                  RequiredFieldSignal requiredSignal,
                                  RemovedFieldSignal removedSignal) {
        boolean hasFieldSignal() { return requiredSignal != null || removedSignal != null; }
        List<String> impactedFields() {
            List<String> out = new ArrayList<>();
            if (requiredSignal != null) out.addAll(requiredSignal.fieldNames());
            if (removedSignal != null)  out.addAll(removedSignal.fieldNames());
            return out;
        }
        String enclosingType() {
            if (requiredSignal != null) return requiredSignal.enclosingType();
            if (removedSignal != null)  return removedSignal.enclosingType();
            return null;
        }
        String restPath() {
            if (requiredSignal != null) return requiredSignal.restPath();
            if (removedSignal != null)  return removedSignal.restPath();
            return null;
        }
    }

    /**
     * Structured signal for "field(s) became required" changes — surfaced by
     * Java validation annotations (Bean Validation / Spring / Jackson / JPA /
     * Swagger) and by OpenAPI / JSON-schema {@code required:} additions.
     */
    private record RequiredFieldSignal(List<String> fieldNames,
                                       String enclosingType,
                                       String restPath) {}

    /** Fields present in the base DTO but removed in the PR. */
    private record RemovedFieldSignal(List<String> fieldNames,
                                      String enclosingType,
                                      String restPath) {}

    /** Java validation / schema annotations that make a field non-optional. */
    private static final Pattern JAVA_REQUIRED_ANNOTATION = Pattern.compile(
            "@(?:NotNull|NotBlank|NotEmpty|NonNull|Nonnull|" +
            "Column\\s*\\([^)]*nullable\\s*=\\s*false[^)]*\\)|" +
            "Schema\\s*\\([^)]*required\\s*=\\s*true[^)]*\\)|" +
            "JsonProperty\\s*\\([^)]*required\\s*=\\s*true[^)]*\\))");

    private static final Pattern JAVA_FIELD_DECL = Pattern.compile(
            "^\\s*(?:private|protected|public)\\s+(?:static\\s+|final\\s+)*" +
            "[\\w<>\\[\\],\\s\\?]+?\\s+(\\w+)\\s*[;=]");

    private static final Pattern SPRING_MAPPING = Pattern.compile(
            "@(?:RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping|FeignClient)" +
            "\\s*\\(\\s*(?:path|value)?\\s*=?\\s*\\{?\\s*\"([^\"]+)\"");

    private ChangeAnalysis analyze(Artifact before, Artifact after) {
        RequiredFieldSignal reqSig = detectNewlyRequiredFields(before, after);
        RemovedFieldSignal  remSig = detectRemovedFields(before, after);
        if (reqSig != null || remSig != null) {
            String bC = before.content() == null ? "" : before.content();
            String aC = after.content()  == null ? "" : after.content();
            StringBuilder d = new StringBuilder();
            if (reqSig != null) {
                d.append("**Newly required field(s)** — `")
                 .append(String.join("`, `", reqSig.fieldNames())).append("` in `")
                 .append(reqSig.enclosingType() == null ? "" : reqSig.enclosingType()).append('`');
                if (reqSig.restPath() != null) d.append(" (endpoint `").append(reqSig.restPath()).append("`)");
                d.append('\n');
            }
            if (remSig != null) {
                if (d.length() > 0) d.append('\n');
                d.append("**Removed field(s)** — `")
                 .append(String.join("`, `", remSig.fieldNames())).append("` from `")
                 .append(remSig.enclosingType() == null ? "" : remSig.enclosingType()).append('`');
                if (remSig.restPath() != null) d.append(" (endpoint `").append(remSig.restPath()).append("`)");
                d.append('\n');
            }
            d.append("\n```diff\n- ").append(firstLine(bC))
              .append("\n+ ").append(firstLine(aC)).append("\n```");
            return new ChangeAnalysis(d.toString(), reqSig, remSig);
        }
        String diff = compare(before, after);
        return diff == null ? null : new ChangeAnalysis(diff, null, null);
    }

    /**
     * Detects Java DTO fields present in {@code before} that no longer exist
     * in {@code after}. Consumers still reading those fields will get
     * {@code null} / missing values.
     */
    private RemovedFieldSignal detectRemovedFields(Artifact before, Artifact after) {
        if (before == null || after == null) return null;
        String lang = after.language() == null ? "" : after.language().toLowerCase();
        String path = after.path() == null ? "" : after.path().toLowerCase();
        if (!"java".equals(lang) && !path.endsWith(".java")) return null;
        // Only class-level artifacts — see detectNewlyRequiredFields for why.
        if (!isClassArtifact(after)) return null;

        String bFile = readIfFile(before.path());
        String aFile = readIfFile(after.path());
        boolean usedFile = bFile != null && aFile != null;
        String bC = usedFile ? bFile : (before.content() == null ? "" : before.content());
        String aC = usedFile ? aFile : (after.content()  == null ? "" : after.content());
        if (bC.equals(aC)) return null;

        Set<String> beforeFields = javaRequiredFields(bC).keySet();
        Set<String> afterFields  = javaRequiredFields(aC).keySet();
        List<String> removed = new ArrayList<>();
        for (String f : beforeFields) if (!afterFields.contains(f)) removed.add(f);
        if (removed.isEmpty()) return null;

        String dedupKey = "REMOVED::" + (after.repo() == null ? "" : after.repo()) + "::" + after.path();
        if (!seenRequiredFieldFiles.add(dedupKey)) return null;

        String enclosing = extractPrimaryClassName(aC);
        if (enclosing == null || enclosing.isBlank()) enclosing = after.name();
        String restPath = extractRestPath(after);
        if (restPath == null && usedFile) restPath = extractRestPathFromText(aC);
        log.info("removed-field: detected removed {} from {} (usedFile={}, restPath={}, repo={}, path={})",
                removed, enclosing, usedFile, restPath, after.repo(), after.path());
        return new RemovedFieldSignal(removed, enclosing, restPath);
    }

    /**
     * Returns a signal if {@code after} adds a required-field constraint that
     * was absent in {@code before}. Handles:
     *   • Java DTO fields gaining a validation annotation.
     *   • OpenAPI / JSON-schema documents gaining a name in {@code required:}.
     */
    private RequiredFieldSignal detectNewlyRequiredFields(Artifact before, Artifact after) {
        if (before == null || after == null) return null;
        String lang = after.language() == null ? "" : after.language().toLowerCase();
        String path = after.path() == null ? "" : after.path().toLowerCase();
        log.info("required-field: invoked for {} lang={} beforePath={} afterPath={}",
                after.name(), lang, before.path(), after.path());

        // Only run on the class-level Java artifact — method / field artifacts
        // for the same file would otherwise emit misleading titles like
        // "Newly required field in setName" (setName is not the enclosing type).
        // For non-Java (YAML / OpenAPI) there's a single artifact per file, so
        // no filtering is needed.
        if (("java".equals(lang) || path.endsWith(".java")) && !isClassArtifact(after)) {
            return null;
        }

        // Prefer raw file bytes when both paths exist on disk — JavaParser's
        // `cls.toString()` normalises whitespace/annotations and can hide the
        // exact change we want to detect (e.g. a newly added `@NotBlank` on a
        // field that lives outside the artifact's own snippet).
        String bFile = readIfFile(before.path());
        String aFile = readIfFile(after.path());
        boolean usedFile = bFile != null && aFile != null;
        String bC = usedFile ? bFile : (before.content() == null ? "" : before.content());
        String aC = usedFile ? aFile : (after.content()  == null ? "" : after.content());
        if (bC.equals(aC)) return null;

        List<String> newlyRequired;
        if ("java".equals(lang) || path.endsWith(".java")) {
            newlyRequired = diffJavaRequiredFields(bC, aC);
        } else if (isApiSchemaFile(path, aC)) {
            newlyRequired = diffSchemaRequiredFields(bC, aC);
        } else {
            // Not code we can analyse for API contract changes (workflow YAML,
            // README, docker-compose, ...). Silently ignore — the change is
            // not a downstream API break by itself.
            log.debug("required-field: skipping non-API file {} (lang={}, path={})",
                    after.name(), lang, after.path());
            return null;
        }
        if (newlyRequired.isEmpty()) {
            log.info("required-field: no newly-required fields for {} (lang={}, usedFile={}, bLen={}, aLen={}, path={})",
                    after.name(), lang, usedFile, bC.length(), aC.length(), after.path());
            if (usedFile) {
                logRequiredAnnotationLines("before", bC);
                logRequiredAnnotationLines("after", aC);
            }
            return null;
        }

        // Dedupe: multiple artifacts (class + each method) all live in the same
        // file, so file-level detection would otherwise emit N copies.
        String dedupKey = (after.repo() == null ? "" : after.repo()) + "::" + after.path();
        if (!seenRequiredFieldFiles.add(dedupKey)) {
            log.debug("required-field: already reported for {} — suppressing duplicate", dedupKey);
            return null;
        }

        // Derive the enclosing class name from the raw file — `after.name()`
        // for a method-level artifact would be e.g. "setName", which is not
        // what the reader wants to see. For the class-level artifact this
        // usually resolves to the same string.
        String enclosing = extractPrimaryClassName(aC);
        if (enclosing == null || enclosing.isBlank()) enclosing = after.name();

        String restPath = extractRestPath(after);
        if (restPath == null && usedFile) restPath = extractRestPathFromText(aC);
        log.info("required-field: detected newly-required {} in {} (usedFile={}, restPath={}, repo={}, path={})",
                newlyRequired, enclosing, usedFile, restPath, after.repo(), after.path());
        return new RequiredFieldSignal(newlyRequired, enclosing, restPath);
    }

    /** True when the artifact's ingestion metadata marks it as a Java class / interface / enum declaration. */
    private static boolean isClassArtifact(Artifact a) {
        Object kind = a.metadata() == null ? null : a.metadata().get("kind");
        if (kind == null) return true; // unknown → fail open so we don't miss real changes
        String k = kind.toString();
        return k.equals("class_declaration") || k.equals("interface_declaration") || k.equals("enum_declaration");
    }

    /**
     * True when the artifact is something the breaking-change detector should
     * even look at. Code files (Java / Python / Go / …) and genuine API
     * schema documents pass; CI workflows, Kubernetes manifests,
     * docker-compose, Spring {@code application.yml}, generic markdown / text,
     * and other non-contract artifacts are rejected outright so we don't
     * produce nonsense findings for them (e.g. "Symbol `on` was deleted"
     * for a GitHub Actions {@code on:} → {@code trigger:} rename).
     */
    private static boolean isBreakingChangeRelevant(Artifact a) {
        if (a == null) return false;
        String path = a.path() == null ? "" : a.path().toLowerCase();
        String lang = a.language() == null ? "" : a.language().toLowerCase();

        // Hard-exclude well-known non-code / non-API locations.
        if (path.contains("/.github/") || path.contains("/.gitlab/")) return false;
        if (path.contains("/workflows/") || path.contains("/pipelines/")) return false;
        if (path.contains("/k8s/") || path.contains("/kubernetes/")
                || path.contains("/manifests/") || path.contains("/helm/")
                || path.contains("/charts/")) return false;
        if (path.endsWith("docker-compose.yml") || path.endsWith("docker-compose.yaml")
                || path.endsWith("dockerfile")) return false;
        if (path.endsWith("application.yml") || path.endsWith("application.yaml")
                || path.contains("application-") && (path.endsWith(".yml") || path.endsWith(".yaml"))) return false;
        if (path.endsWith(".md") || path.endsWith(".txt") || path.endsWith(".rst")
                || path.endsWith(".adoc")) return false;
        if (path.endsWith(".properties") || path.endsWith(".toml") || path.endsWith(".ini")
                || path.endsWith(".env") || path.endsWith(".lock")) return false;

        // Known code languages — always relevant.
        switch (lang) {
            case "java": case "kotlin": case "scala": case "groovy":
            case "python": case "go": case "rust":
            case "javascript": case "typescript":
            case "c": case "cpp": case "csharp":
            case "ruby": case "php":
            case "sql":
            case "proto":
                return true;
            default:
                // fall through
        }
        if (path.endsWith(".java") || path.endsWith(".kt") || path.endsWith(".scala")
                || path.endsWith(".py") || path.endsWith(".go") || path.endsWith(".rs")
                || path.endsWith(".js") || path.endsWith(".ts") || path.endsWith(".tsx")
                || path.endsWith(".c") || path.endsWith(".cpp") || path.endsWith(".cs")
                || path.endsWith(".rb") || path.endsWith(".php") || path.endsWith(".sql")
                || path.endsWith(".proto")) return true;

        // YAML / JSON: only genuine API schema docs qualify.
        if ("yaml".equals(lang) || "yml".equals(lang) || "json".equals(lang)
                || path.endsWith(".yaml") || path.endsWith(".yml") || path.endsWith(".json")) {
            String content = a.content() == null ? "" : a.content();
            return isApiSchemaFile(path, content);
        }
        return false;
    }

    /**
     * True only when the file is a genuine API contract document:
     * OpenAPI / Swagger / JSON-schema. Generic YAML (GitHub Actions
     * workflows, docker-compose, Helm charts, application.yml, ...) is
     * excluded — a change there is not a downstream API break.
     */
    private static boolean isApiSchemaFile(String path, String content) {
        if (path == null) path = "";
        // Hard-exclude well-known non-API YAML/JSON locations.
        if (path.contains("/.github/") || path.contains("/.gitlab/")) return false;
        if (path.contains("/workflows/") || path.contains("/pipelines/")) return false;
        if (path.endsWith("docker-compose.yml") || path.endsWith("docker-compose.yaml")) return false;
        if (path.endsWith("application.yml")   || path.endsWith("application.yaml"))   return false;
        if (path.endsWith("application-local.yml") || path.endsWith("application-local.yaml")) return false;
        // Positive signal from the path.
        if (path.contains("openapi") || path.contains("swagger") || path.contains("api-spec")) return true;
        // Positive signal from the content: openapi:/swagger: top-level keys
        // or a JSON-schema $schema URL.
        if (content == null) return false;
        String head = content.length() > 4096 ? content.substring(0, 4096) : content;
        if (head.matches("(?s).*(?m)^\\s*openapi\\s*:.*"))  return true;
        if (head.matches("(?s).*(?m)^\\s*swagger\\s*:.*"))  return true;
        if (head.contains("\"$schema\""))                    return true;
        return false;
    }

    /** Extract the first {@code (public\s+)?(class|interface|enum) Xxx} declaration name. */
    private static String extractPrimaryClassName(String javaContent) {
        if (javaContent == null) return null;
        Matcher m = JAVA_CLASS_DECL.matcher(javaContent);
        return m.find() ? m.group(1) : null;
    }

    private static final Pattern JAVA_CLASS_DECL = Pattern.compile(
            "(?m)^\\s*(?:public\\s+|abstract\\s+|final\\s+)*(?:class|interface|enum|record)\\s+(\\w+)");

    /** Bounded cache to keep the dedupe set from growing unbounded over long uptimes. */
    private final java.util.Set<String> seenRequiredFieldFiles =
            java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<>() {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > 4096;
                }
            });

    private static String readIfFile(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) return null;
        try {
            java.nio.file.Path p = java.nio.file.Path.of(pathStr);
            if (!java.nio.file.Files.isRegularFile(p)) return null;
            if (java.nio.file.Files.size(p) > 5 * 1024 * 1024) return null; // 5 MB guard
            return java.nio.file.Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractRestPathFromText(String content) {
        if (content == null) return null;
        Matcher m = SPRING_MAPPING.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private static void logRequiredAnnotationLines(String label, String src) {
        if (src == null) return;
        int hits = 0;
        for (String line : src.split("\r?\n", -1)) {
            if (JAVA_REQUIRED_ANNOTATION.matcher(line).find()) {
                if (hits++ < 20) log.info("required-field[{}]: {}", label, line.strip());
            }
        }
        log.info("required-field[{}]: {} annotation-line(s) total", label, hits);
    }

    /** Fields that carry a validation annotation in {@code after} but not in {@code before}. */
    private static List<String> diffJavaRequiredFields(String before, String after) {
        Map<String, Boolean> beforeReq = javaRequiredFields(before);
        Map<String, Boolean> afterReq  = javaRequiredFields(after);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : afterReq.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue()) && !Boolean.TRUE.equals(beforeReq.get(e.getKey()))) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Map of {@code fieldName -> hasValidationAnnotation} for a Java source blob. */
    private static Map<String, Boolean> javaRequiredFields(String src) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (src == null || src.isBlank()) return out;
        String[] lines = src.split("\r?\n", -1);
        boolean pendingRequired = false;
        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;
            // Standalone annotation line: remember for the next field decl.
            if (line.startsWith("@") && !JAVA_FIELD_DECL.matcher(rawLine).find()) {
                if (JAVA_REQUIRED_ANNOTATION.matcher(line).find()) pendingRequired = true;
                continue;
            }
            Matcher fm = JAVA_FIELD_DECL.matcher(rawLine);
            if (fm.find()) {
                // JavaParser's toString() sometimes inlines annotations on the
                // same line as the field decl — check the raw line too.
                boolean inlineRequired = JAVA_REQUIRED_ANNOTATION.matcher(rawLine).find();
                boolean required = pendingRequired || inlineRequired;
                out.merge(fm.group(1), required, (a, b) -> a || b);
                pendingRequired = false;
            } else if (!line.startsWith("//") && !line.startsWith("*")) {
                pendingRequired = false;
            }
        }
        return out;
    }

    /** OpenAPI / JSON-schema: names added to a {@code required:} block in {@code after}. */
    private static List<String> diffSchemaRequiredFields(String before, String after) {
        Set<String> b = schemaRequiredNames(before);
        Set<String> a = schemaRequiredNames(after);
        List<String> out = new ArrayList<>();
        for (String n : a) if (!b.contains(n)) out.add(n);
        return out;
    }

    private static final Pattern YAML_REQUIRED_ITEM = Pattern.compile("^\\s*-\\s*([\\w-]+)\\s*$");
    private static final Pattern JSON_REQUIRED_ARRAY = Pattern.compile(
            "\"required\"\\s*:\\s*\\[([^\\]]*)]", Pattern.DOTALL);
    private static final Pattern JSON_STRING_ITEM = Pattern.compile("\"([\\w-]+)\"");

    private static Set<String> schemaRequiredNames(String src) {
        Set<String> out = new LinkedHashSet<>();
        if (src == null || src.isBlank()) return out;
        // YAML: locate `required:` blocks and collect `- name` items until dedent / new key.
        String[] lines = src.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.stripTrailing();
            int colon = stripped.indexOf("required:");
            if (colon >= 0 && stripped.endsWith("required:")) {
                int baseIndent = colon;
                for (int j = i + 1; j < lines.length; j++) {
                    String l = lines[j];
                    if (l.isBlank()) continue;
                    int indent = 0;
                    while (indent < l.length() && l.charAt(indent) == ' ') indent++;
                    if (indent <= baseIndent) break;
                    Matcher m = YAML_REQUIRED_ITEM.matcher(l);
                    if (m.find()) out.add(m.group(1));
                }
            } else if (colon >= 0 && stripped.endsWith("required: true")) {
                // `<fieldName>:\n  required: true` style — grab nearest key above.
                for (int j = i - 1; j >= 0; j--) {
                    String prev = lines[j].stripTrailing();
                    if (prev.isBlank()) continue;
                    Matcher km = Pattern.compile("^\\s*([\\w-]+)\\s*:").matcher(prev);
                    if (km.find()) { out.add(km.group(1)); break; }
                }
            }
        }
        // JSON: "required": ["a","b"]
        Matcher jm = JSON_REQUIRED_ARRAY.matcher(src);
        while (jm.find()) {
            Matcher sm = JSON_STRING_ITEM.matcher(jm.group(1));
            while (sm.find()) out.add(sm.group(1));
        }
        return out;
    }

    /** Extract the first Spring / Feign REST path declared in the artifact, if any. */
    private static String extractRestPath(Artifact a) {
        if (a == null || a.content() == null) return null;
        Matcher m = SPRING_MAPPING.matcher(a.content());
        return m.find() ? m.group(1) : null;
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

    /**
     * For field-level changes we search the vector/graph store with
     * multiple queries — enclosing DTO class + REST-endpoint path segments —
     * and unions the results. Field names alone (e.g. {@code phone}) are
     * intentionally NOT used as discovery queries: they're generic English
     * words that match huge numbers of unrelated repos. Every candidate then
     * has to pass {@link #isLikelyConsumer} which requires hard evidence of an
     * actual API call before it makes it into the PR comment.
     */
    private List<Map<String, Object>> findConsumersBroad(Artifact after, ChangeAnalysis a) {
        LinkedHashMap<String, Map<String, Object>> merged = new LinkedHashMap<>();
        List<String> queries = new ArrayList<>();
        if (a.enclosingType() != null) queries.add(a.enclosingType());
        if (a.restPath() != null) {
            // Strip leading '/' and split on remaining '/'. Require segment
            // length >= 4 so meaningless tokens like `v1`, `id`, `me` don't
            // pull in every repo that happens to contain them.
            for (String seg : a.restPath().split("/")) {
                if (!seg.isBlank() && !seg.startsWith("{") && seg.length() >= 4) queries.add(seg);
            }
        }
        for (String q : queries) {
            if (q == null || q.isBlank() || q.length() < 4) continue;
            for (Map<String, Object> c : findConsumers(q, after.repo())) {
                if (!isLikelyConsumer(c, a)) {
                    log.debug("consumer-discovery: dropped candidate {}::{} — no strong API-call evidence (query='{}')",
                            c.get("repo"), c.get("path"), q);
                    continue;
                }
                Object id = c.get("id");
                if (id != null && !merged.containsKey(id.toString())) {
                    merged.put(id.toString(), c);
                    log.info("consumer-discovery: matched candidate {}::{} via query='{}'",
                            c.get("repo"), c.get("path"), q);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * True when the consumer content shows hard evidence of actually calling
     * the producer's API. We require ONE of:
     *   1. Java-side reference to the DTO class in a code-like context
     *      (import statement, `new UserRequest(`, typed variable declaration,
     *      generic type parameter, or `UserRequest.class`).
     *   2. The REST path appearing inside a quoted string AND the same file
     *      using a recognised HTTP-client library (RestTemplate / WebClient /
     *      Feign / OkHttp / Java 11 HttpClient / axios / fetch / requests / …).
     * Anything less is discarded to prevent noisy cross-repo matches.
     */
    private static boolean isLikelyConsumer(Map<String, Object> c, ChangeAnalysis a) {
        Object contentObj = c.get("content");
        String content = contentObj == null ? "" : contentObj.toString();
        if (content.isBlank()) return false;

        String enclosing = a.enclosingType();
        if (enclosing != null && !enclosing.isBlank() && hasStrongTypeReference(content, enclosing)) {
            return true;
        }

        String rest = a.restPath();
        if (rest != null && !rest.isBlank() && hasHttpCallToPath(content, rest)) {
            return true;
        }
        return false;
    }

    /** Detects import / instantiation / typed reference / .class of the given Java type. */
    private static boolean hasStrongTypeReference(String content, String typeName) {
        String q = Pattern.quote(typeName);
        Pattern p = Pattern.compile(
                "(?m)^\\s*import\\s+[\\w.]+\\." + q + "\\s*;"
                + "|new\\s+" + q + "\\s*\\("
                + "|\\b" + q + "\\s*<[^>]*>\\s+\\w+"
                + "|\\b" + q + "\\s+\\w+\\s*[=;,)]"
                + "|\\b" + q + "\\.class\\b"
                + "|@RequestBody\\s+" + q + "\\b"
                + "|extends\\s+" + q + "\\b"
                + "|implements\\s+(?:[\\w.,\\s]*,\\s*)?" + q + "\\b");
        return p.matcher(content).find();
    }

    /** Detects a quoted occurrence of the REST path near a known HTTP-client call. */
    private static boolean hasHttpCallToPath(String content, String restPath) {
        Pattern quoted = Pattern.compile("[\"']" + Pattern.quote(restPath) + "(?:[/?\"']|$)");
        if (!quoted.matcher(content).find()) return false;
        Pattern httpHint = Pattern.compile(
                "\\b(?:RestTemplate|WebClient|HttpClient|HttpURLConnection|OkHttpClient|"
                + "FeignClient|RestClient|HttpEntity|ResponseEntity|HttpRequest|"
                + "axios|fetch|XMLHttpRequest|requests|urllib|http\\.request)\\b"
                + "|@FeignClient|@RestClient|@RetrofitClient|@HttpExchange",
                Pattern.CASE_INSENSITIVE);
        return httpHint.matcher(content).find();
    }

    // ------------------------------------------------------------------
    // LLM cross-repo impact prediction
    // ------------------------------------------------------------------

    /** Aggregated LLM verdict about which consumers will break. */
    record LlmVerdict(double overallConfidence,
                      String summary,
                      List<PredictedConsumer> breakers,
                      List<PredictedConsumer> safe,
                      boolean modelInvoked) {
        boolean hasSignal() { return modelInvoked && (summary != null && !summary.isBlank()
                || !breakers.isEmpty() || !safe.isEmpty()); }
        boolean saysWillBreak() { return !breakers.isEmpty(); }
        boolean confidentlySafe() {
            return breakers.isEmpty() && !safe.isEmpty() && overallConfidence >= 0.8;
        }
    }

    /** One predicted consumer entry from the LLM. */
    record PredictedConsumer(String repo, String path, String name,
                             boolean willBreak, double confidence, String reason) {}

    private LlmVerdict predictBreakage(Artifact after,
                                       String change,
                                       List<Map<String, Object>> consumers) {
        if (llmExecutor == null || !llmProps.enabled()) return emptyVerdict();
        if (consumers == null || consumers.isEmpty()) return emptyVerdict();
        if (after == null || after.name() == null || after.name().isBlank()) return emptyVerdict();
        if (GENERIC_NAMES.contains(after.name())) return emptyVerdict();

        String rendered = renderConsumersForPrompt(consumers);
        String userPrompt = promptTemplate
                .replace("{symbol}", nullSafe(after.name()))
                .replace("{repo}", nullSafe(after.repo()))
                .replace("{language}", nullSafe(after.language()))
                .replace("{change}", truncate(change, llmProps.maxChangeChars()))
                .replace("{consumers}", rendered);

        Prompt prompt = new Prompt(List.of(
                new SystemMessage("You output only valid JSON."),
                new UserMessage(userPrompt)
        ));

        String raw;
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(
                    () -> chatModel.call(prompt).getResult().getOutput().getText(),
                    llmExecutor);
            raw = future.get(llmProps.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("BreakingChangeDetector LLM call timed out after {}ms for {}",
                    llmProps.timeoutMs(), after.name());
            return emptyVerdict();
        } catch (Exception e) {
            log.warn("BreakingChangeDetector LLM call failed for {}: {}", after.name(), e.toString());
            return emptyVerdict();
        }

        return parseVerdict(raw);
    }

    private String renderConsumersForPrompt(List<Map<String, Object>> consumers) {
        int limit = llmProps.maxConsumers();
        int snippetMax = llmProps.maxSnippetChars();
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (Map<String, Object> c : consumers) {
            if (idx >= limit) break;
            idx++;
            String repo = String.valueOf(c.getOrDefault("repo", "?"));
            String path = String.valueOf(c.getOrDefault("path", "?"));
            String name = String.valueOf(c.getOrDefault("name", "?"));
            String content = c.get("content") == null ? "" : c.get("content").toString();
            String snippet = truncate(content, snippetMax);
            sb.append("\n").append(idx).append(". repo=`").append(repo)
              .append("`, path=`").append(path).append("`, name=`").append(name).append("`\n")
              .append("```\n").append(snippet).append("\n```\n");
        }
        if (sb.length() == 0) sb.append("(no consumer snippets available)\n");
        return sb.toString();
    }

    private LlmVerdict parseVerdict(String raw) {
        if (raw == null || raw.isBlank()) return emptyVerdict();
        JsonNode root;
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}') + 1;
            if (start < 0 || end <= start) return emptyVerdict();
            root = mapper.readTree(raw.substring(start, end));
        } catch (Exception e) {
            log.debug("BreakingChangeDetector could not parse LLM JSON: {}", e.getMessage());
            return emptyVerdict();
        }

        double overall = root.path("overall_confidence").asDouble(0.0);
        String summary = root.path("summary").asText("");

        List<PredictedConsumer> breakers = new ArrayList<>();
        List<PredictedConsumer> safe = new ArrayList<>();
        JsonNode arr = root.path("consumers");
        if (arr.isArray()) {
            double minConf = llmProps.minConsumerConfidence();
            for (JsonNode n : arr) {
                PredictedConsumer pc = new PredictedConsumer(
                        n.path("repo").asText(""),
                        n.path("path").asText(""),
                        n.path("name").asText(""),
                        n.path("will_break").asBoolean(false),
                        n.path("confidence").asDouble(0.0),
                        n.path("reason").asText("")
                );
                if (pc.confidence() < minConf) continue;
                if (pc.willBreak()) breakers.add(pc); else safe.add(pc);
            }
        }
        return new LlmVerdict(overall, summary, breakers, safe, true);
    }

    private static Severity mergeSeverity(Severity baseline, LlmVerdict v) {
        if (v.saysWillBreak()) {
            // Any high-confidence predicted breaker escalates to BLOCK.
            boolean highConfBreaker = v.breakers().stream().anyMatch(pc -> pc.confidence() >= 0.8);
            if (highConfBreaker) return Severity.BLOCK;
            // Otherwise ensure at least WARN.
            return baseline == Severity.INFO ? Severity.WARN : baseline;
        }
        // LLM is advisory when it disagrees with lexical evidence — never
        // downgrade a BLOCK/WARN just because the model says "probably fine".
        return baseline;
    }

    private static double mergeConfidence(double baseline, LlmVerdict v) {
        if (v.saysWillBreak()) {
            double topBreaker = v.breakers().stream()
                    .mapToDouble(PredictedConsumer::confidence).max().orElse(v.overallConfidence());
            double blended = 0.5 * baseline + 0.5 * Math.max(v.overallConfidence(), topBreaker);
            return Math.min(0.99, blended);
        }
        if (v.confidentlySafe()) {
            // Reviewer should still see the lexical hit, but with lower certainty.
            return Math.max(0.3, baseline - 0.1);
        }
        return baseline;
    }

    private static void appendLlmDetail(StringBuilder detail, LlmVerdict v) {
        detail.append("\n\n**🤖 LLM cross-repo impact analysis** (confidence ")
              .append(String.format("%.2f", v.overallConfidence())).append("):");
        if (v.summary() != null && !v.summary().isBlank()) {
            detail.append("\n> ").append(v.summary().replace("\n", "\n> "));
        }
        if (!v.breakers().isEmpty()) {
            detail.append("\n\n**Consumers predicted to break:**");
            int shown = 0;
            for (PredictedConsumer pc : v.breakers()) {
                if (shown++ >= 5) { detail.append("\n- … (").append(v.breakers().size() - 5).append(" more)"); break; }
                detail.append("\n- `").append(nullSafe(pc.repo()))
                      .append("::").append(PathUtils.repoRelative(pc.path())).append("` ")
                      .append("(conf ").append(String.format("%.2f", pc.confidence())).append(") — ")
                      .append(nullSafe(pc.reason()));
            }
        } else if (!v.safe().isEmpty()) {
            detail.append("\n\n**No downstream break predicted** — reviewed ")
                  .append(v.safe().size()).append(" consumer(s).");
        }
    }

    /** Evidence list = LLM-predicted breakers first (repo::path::name), then remaining lexical consumers. */
    private static List<String> buildEvidence(List<Map<String, Object>> consumers, LlmVerdict v) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (v != null) {
            for (PredictedConsumer pc : v.breakers()) {
                out.add(String.format("%s::%s::%s",
                        nullSafe(pc.repo()), PathUtils.repoRelative(pc.path()), nullSafe(pc.name())));
                if (out.size() == 10) return List.copyOf(out);
            }
        }
        for (Map<String, Object> c : consumers) {
            out.add(String.format("%s::%s::%s",
                    c.getOrDefault("repo", "?"),
                    PathUtils.repoRelative((String) c.getOrDefault("path", "?")),
                    c.getOrDefault("name", "?")));
            if (out.size() == 10) break;
        }
        return List.copyOf(out);
    }

    private static LlmVerdict emptyVerdict() {
        return new LlmVerdict(0.0, "", List.of(), List.of(), false);
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n… (truncated)";
    }
}

