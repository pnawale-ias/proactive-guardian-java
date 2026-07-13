package com.proactiveguardian.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.proactiveguardian.config.BreakingChangeLlmProperties;
import com.proactiveguardian.knowledge.GraphStore;
import com.proactiveguardian.knowledge.VectorStore;
import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.ArtifactType;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.model.Severity;
import com.proactiveguardian.testsupport.FakeGraphStore;
import com.proactiveguardian.testsupport.FakeVectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the LLM-assisted cross-repo impact prediction added to
 * {@link BreakingChangeDetector}. All tests use in-memory fakes and an
 * inline {@link ChatModel} lambda so no network calls are made.
 */
class BreakingChangeDetectorTest {

    private static final String PROMPT_TEMPLATE = """
            symbol={symbol}
            repo={repo}
            language={language}
            change={change}
            consumers={consumers}
            """;

    // ------------------------------------------------------------------
    // 2-arg constructor (no LLM) still produces the lexical baseline.
    // ------------------------------------------------------------------
    @Test
    void noLlmConstructor_matchesLexicalBaseline() {
        Fixture fx = seededFixture();

        BreakingChangeDetector det = new BreakingChangeDetector(fx.vs, fx.gs);
        List<Finding> findings = det.check(fx.before, fx.after);

        assertThat(findings).hasSize(1);
        Finding f = findings.get(0);
        assertThat(f.category()).isEqualTo("breaking_change");
        assertThat(f.severity()).isEqualTo(Severity.BLOCK);           // cross-repo consumer exists
        assertThat(f.detail()).doesNotContain("LLM cross-repo impact analysis");
        assertThat(f.evidence()).anyMatch(e -> e.startsWith("repo-b::"));
    }

    // ------------------------------------------------------------------
    // LLM disabled via properties → same lexical baseline, model never invoked.
    // ------------------------------------------------------------------
    @Test
    void llmDisabled_neverCallsModel() {
        Fixture fx = seededFixture();
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = p -> {
            calls.incrementAndGet();
            return canned("{}");
        };

        BreakingChangeDetector det = new BreakingChangeDetector(
                fx.vs, fx.gs, model, jsonMapper(), promptResource(),
                BreakingChangeLlmProperties.disabled());

        Finding f = det.check(fx.before, fx.after).get(0);

        assertThat(calls).hasValue(0);
        assertThat(f.detail()).doesNotContain("LLM cross-repo impact analysis");
    }

    // ------------------------------------------------------------------
    // LLM confirms a high-confidence breaker → severity stays BLOCK,
    // confidence is blended, LLM section appears in the detail body.
    // ------------------------------------------------------------------
    @Test
    void llmConfirmsBreakage_appendsAnalysisAndPrioritisesEvidence() {
        Fixture fx = seededFixture();
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();

        String verdictJson = """
                {
                  "overall_confidence": 0.92,
                  "summary": "callSharedUtil() call site will fail to compile",
                  "consumers": [
                    {"repo":"repo-b","path":"src/consumer.py","name":"callSharedUtil",
                     "will_break":true,"confidence":0.9,
                     "reason":"positional arg order changed"}
                  ]
                }
                """;

        ChatModel model = p -> {
            capturedPrompt.set(p);
            return canned(verdictJson);
        };

        BreakingChangeDetector det = new BreakingChangeDetector(
                fx.vs, fx.gs, model, jsonMapper(), promptResource(),
                BreakingChangeLlmProperties.defaults());

        Finding f = det.check(fx.before, fx.after).get(0);

        assertThat(f.severity()).isEqualTo(Severity.BLOCK);
        assertThat(f.confidence()).isBetween(0.9, 0.99);
        assertThat(f.detail())
                .contains("🤖 LLM cross-repo impact analysis")
                .contains("callSharedUtil() call site will fail to compile")
                .contains("positional arg order changed");
        // Predicted breaker should float to the top of the evidence list.
        assertThat(f.evidence()).first().asString()
                .startsWith("repo-b::src/consumer.py::callSharedUtil");

        // Prompt payload includes symbol + rendered consumer snippet.
        assertThat(capturedPrompt.get()).isNotNull();
        String rendered = capturedPrompt.get().getUserMessage().getText();
        assertThat(rendered)
                .contains("symbol=sharedUtil")
                .contains("repo=repo-a")
                .contains("repo=`repo-b`");
    }

    // ------------------------------------------------------------------
    // LLM says all consumers are safe with high confidence →
    // baseline severity is preserved (LLM is advisory), confidence drops.
    // ------------------------------------------------------------------
    @Test
    void llmSaysSafe_keepsBaselineSeverityAndReducesConfidence() {
        Fixture fx = seededFixture();
        String safeJson = """
                {
                  "overall_confidence": 0.9,
                  "summary": "consumers only read an unchanged field",
                  "consumers": [
                    {"repo":"repo-b","path":"src/consumer.py","name":"callSharedUtil",
                     "will_break":false,"confidence":0.9,"reason":"unaffected usage"}
                  ]
                }
                """;
        ChatModel model = p -> canned(safeJson);

        double baselineConf = new BreakingChangeDetector(fx.vs, fx.gs)
                .check(fx.before, fx.after).get(0).confidence();

        Finding f = new BreakingChangeDetector(
                fx.vs, fx.gs, model, jsonMapper(), promptResource(),
                BreakingChangeLlmProperties.defaults())
                .check(fx.before, fx.after).get(0);

        assertThat(f.severity()).isEqualTo(Severity.BLOCK);   // never downgraded
        assertThat(f.confidence()).isLessThan(baselineConf);
        assertThat(f.detail()).contains("No downstream break predicted");
    }

    // ------------------------------------------------------------------
    // Malformed model output → silently falls back to baseline.
    // ------------------------------------------------------------------
    @Test
    void llmReturnsGarbage_fallsBackToBaseline() {
        Fixture fx = seededFixture();
        ChatModel model = p -> canned("sorry I cannot answer");

        Finding f = new BreakingChangeDetector(
                fx.vs, fx.gs, model, jsonMapper(), promptResource(),
                BreakingChangeLlmProperties.defaults())
                .check(fx.before, fx.after).get(0);

        assertThat(f.severity()).isEqualTo(Severity.BLOCK);   // baseline
        assertThat(f.detail()).doesNotContain("🤖 LLM cross-repo impact analysis");
    }

    // ------------------------------------------------------------------
    // ChatModel throws → the finding still emits, LLM section is absent.
    // ------------------------------------------------------------------
    @Test
    void llmThrows_fallsBackToBaseline() {
        Fixture fx = seededFixture();
        ChatModel model = p -> { throw new RuntimeException("boom"); };

        List<Finding> findings = new BreakingChangeDetector(
                fx.vs, fx.gs, model, jsonMapper(), promptResource(),
                BreakingChangeLlmProperties.defaults())
                .check(fx.before, fx.after);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).detail())
                .doesNotContain("🤖 LLM cross-repo impact analysis");
    }

    // ------------------------------------------------------------------
    // ChatModel exceeds timeout → the finding still emits, LLM section absent.
    // ------------------------------------------------------------------
    @Test
    void llmTimeout_fallsBackToBaseline() {
        Fixture fx = seededFixture();
        ChatModel model = p -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return canned("{}");
        };

        BreakingChangeLlmProperties fast = new BreakingChangeLlmProperties(
                Boolean.TRUE, null, null, null, 50L, null);

        List<Finding> findings = new BreakingChangeDetector(
                fx.vs, fx.gs, model, jsonMapper(), promptResource(), fast)
                .check(fx.before, fx.after);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).detail())
                .doesNotContain("🤖 LLM cross-repo impact analysis");
    }

    // ------------------------------------------------------------------
    // Prompt caps the number of consumer snippets even if lexical
    // discovery returns many candidates.
    // ------------------------------------------------------------------
    @Test
    void consumersInPromptAreCappedByMaxConsumers() {
        ObjectMapper mapper = jsonMapper();
        VectorStore vs = new FakeVectorStore(mapper);
        GraphStore gs = new FakeGraphStore();

        Artifact source = artifact("sym-src", "repo-a", "src/util.py",
                "def sharedUtil(a):\n    return a", "python");
        vs.upsert(List.of(source));

        // 20 lexical consumers across many repos.
        for (int i = 0; i < 20; i++) {
            Artifact c = artifact("c" + i, "repo-b" + i, "src/c" + i + ".py",
                    "sharedUtil(" + i + ")", "python");
            vs.upsert(List.of(c));
        }

        Artifact after = artifact("sym-src", "repo-a", "src/util.py",
                "def sharedUtil(a, b):\n    return a + b", "python");

        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel model = p -> { capturedPrompt.set(p); return canned("{}"); };

        BreakingChangeLlmProperties props = new BreakingChangeLlmProperties(
                Boolean.TRUE, 4, null, null, null, null);

        new BreakingChangeDetector(vs, gs, model, mapper,
                promptResource(), props)
                .check(source, after);

        assertThat(capturedPrompt.get()).isNotNull();
        String text = capturedPrompt.get().getUserMessage().getText();

        // Numbered list contains "1." .. "4." but not "5.".
        assertThat(text).contains("\n1. repo=`", "\n4. repo=`");
        assertThat(text).doesNotContain("\n5. repo=`");
    }

    // ------------------------------------------------------------------
    // Newly-required Java DTO field (Bean Validation @NotNull added)
    // should be flagged as a breaking change, cross-repo consumer found
    // via the enclosing class name AND the REST path (not just the field name).
    // Mirrors the generateautocode → consumerservice scenario.
    // ------------------------------------------------------------------
    @Test
    void newlyRequiredJavaField_flagsBreakingChangeAndFindsCrossRepoConsumer() {
        ObjectMapper mapper = jsonMapper();
        VectorStore vs = new FakeVectorStore(mapper);
        GraphStore gs = new FakeGraphStore();

        String beforeDto = """
                package com.example;
                public class UserRequest {
                    private String firstName;
                    private String lastName;
                    private String email;
                    private String phone;
                }
                """;
        String afterDto = """
                package com.example;
                import jakarta.validation.constraints.NotBlank;
                public class UserRequest {
                    private String firstName;
                    private String lastName;
                    private String email;
                    @NotBlank(message = "email is mandatory")
                    private String phone;
                }
                """;
        String controller = """
                @RestController
                @RequestMapping("/users")
                public class UserController {
                    @PostMapping public void create(UserRequest r) {}
                }
                """;

        Artifact before = new Artifact(
                "dto-1", ArtifactType.CODE_SYMBOL, "UserRequest",
                beforeDto + controller, "java",
                "softwarepravin2007/generateautocode",
                "src/main/java/com/example/UserRequest.java",
                null, Map.of(), Instant.now()
        );
        Artifact after = new Artifact(
                "dto-1", ArtifactType.CODE_SYMBOL, "UserRequest",
                afterDto + controller, "java",
                "softwarepravin2007/generateautocode",
                "src/main/java/com/example/UserRequest.java",
                null, Map.of(), Instant.now()
        );

        // Consumer never imports UserRequest — it just POSTs JSON to /users.
        Artifact consumer = new Artifact(
                "cons-1", ArtifactType.CODE_SYMBOL, "UserClient",
                """
                @Service
                public class UserClient {
                    public void create() {
                        restTemplate.postForObject("/users",
                            Map.of("firstName","p","lastName","n","email","x@y"), Void.class);
                    }
                }
                """,
                "java", "softwarepravin2007/consumerservice",
                "src/main/java/com/example/UserClient.java",
                null, Map.of(), Instant.now()
        );
        vs.upsert(List.of(before, consumer));

        List<Finding> findings = new BreakingChangeDetector(vs, gs).check(before, after);

        assertThat(findings).hasSize(1);
        Finding f = findings.get(0);
        assertThat(f.category()).isEqualTo("breaking_change");
        assertThat(f.detail())
                .contains("Newly required field")
                .contains("phone")
                // Per-repo verdict must call out that consumerservice does NOT set the field.
                .contains("Will break")
                .contains("softwarepravin2007/consumerservice");
        // Cross-repo consumer (softwarepravin2007/consumerservice) must be surfaced
        // via the REST path `/users`, since consumer never references `UserRequest`.
        assertThat(f.severity()).isEqualTo(Severity.BLOCK);
        assertThat(f.evidence()).anyMatch(e -> e.contains("consumerservice"));
    }

    // ------------------------------------------------------------------
    // A consumer that DOES set the field should be marked safe (✅),
    // while a sibling consumer that omits it should be marked breaking (🚫).
    // ------------------------------------------------------------------
    @Test
    void newlyRequiredJavaField_perConsumerVerdictSeparatesSafeAndBreaking() {
        ObjectMapper mapper = jsonMapper();
        VectorStore vs = new FakeVectorStore(mapper);
        GraphStore gs = new FakeGraphStore();

        String beforeDto = """
                package com.example;
                public class UserRequest {
                    private String email;
                    private String phone;
                }
                """;
        String afterDto = """
                package com.example;
                import jakarta.validation.constraints.NotBlank;
                public class UserRequest {
                    private String email;
                    @NotBlank(message = "phone required")
                    private String phone;
                }
                @RestController
                @RequestMapping("/users")
                class UserController {}
                """;

        Artifact before = new Artifact("dto-1", ArtifactType.CODE_SYMBOL, "UserRequest",
                beforeDto, "java", "softwarepravin2007/generateautocode",
                "src/main/java/com/example/UserRequest.java", null, Map.of(), Instant.now());
        Artifact after  = new Artifact("dto-1", ArtifactType.CODE_SYMBOL, "UserRequest",
                afterDto, "java", "softwarepravin2007/generateautocode",
                "src/main/java/com/example/UserRequest.java", null, Map.of(), Instant.now());

        Artifact safeConsumer = new Artifact(
                "cons-safe", ArtifactType.CODE_SYMBOL, "SafeClient",
                """
                public class SafeClient {
                    void call() {
                        restTemplate.postForObject("/users",
                            Map.of("email","x@y","phone","+1-555"), Void.class);
                    }
                }
                """, "java", "softwarepravin2007/safeconsumer",
                "src/main/java/com/example/SafeClient.java", null, Map.of(), Instant.now());

        Artifact breakingConsumer = new Artifact(
                "cons-break", ArtifactType.CODE_SYMBOL, "BrokenClient",
                """
                public class BrokenClient {
                    void call() {
                        restTemplate.postForObject("/users",
                            Map.of("email","x@y"), Void.class);
                    }
                }
                """, "java", "softwarepravin2007/brokenconsumer",
                "src/main/java/com/example/BrokenClient.java", null, Map.of(), Instant.now());

        vs.upsert(List.of(before, safeConsumer, breakingConsumer));

        Finding f = new BreakingChangeDetector(vs, gs).check(before, after).get(0);

        assertThat(f.severity()).isEqualTo(Severity.BLOCK);
        assertThat(f.detail())
                .contains("🚫 Will break")
                .contains("softwarepravin2007/brokenconsumer")
                .contains("✅ Safe")
                .contains("softwarepravin2007/safeconsumer");
    }

    // ------------------------------------------------------------------
    // Removing a field the consumer still reads should be flagged.
    // ------------------------------------------------------------------
    @Test
    void removedJavaField_flagsCrossRepoConsumerReadingField() {
        ObjectMapper mapper = jsonMapper();
        VectorStore vs = new FakeVectorStore(mapper);
        GraphStore gs = new FakeGraphStore();

        String beforeDto = """
                package com.example;
                public class UserResponse {
                    private String email;
                    private String phone;
                }
                """;
        String afterDto = """
                package com.example;
                public class UserResponse {
                    private String email;
                }
                """;

        Artifact before = new Artifact("dto-2", ArtifactType.CODE_SYMBOL, "UserResponse",
                beforeDto, "java", "softwarepravin2007/generateautocode",
                "src/main/java/com/example/UserResponse.java", null, Map.of(), Instant.now());
        Artifact after  = new Artifact("dto-2", ArtifactType.CODE_SYMBOL, "UserResponse",
                afterDto, "java", "softwarepravin2007/generateautocode",
                "src/main/java/com/example/UserResponse.java", null, Map.of(), Instant.now());

        Artifact consumer = new Artifact(
                "cons-read", ArtifactType.CODE_SYMBOL, "PhoneReader",
                """
                public class PhoneReader {
                    void show(UserResponse r) {
                        System.out.println(r.getPhone());
                    }
                }
                """, "java", "softwarepravin2007/consumerservice",
                "src/main/java/com/example/PhoneReader.java", null, Map.of(), Instant.now());
        vs.upsert(List.of(before, consumer));

        Finding f = new BreakingChangeDetector(vs, gs).check(before, after).get(0);

        assertThat(f.severity()).isEqualTo(Severity.BLOCK);
        assertThat(f.title()).contains("removed");
        assertThat(f.detail())
                .contains("Removed field")
                .contains("phone")
                .contains("softwarepravin2007/consumerservice");
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    /** Two-repo setup: sharedUtil defined in repo-a, one consumer in repo-b. */
    private static Fixture seededFixture() {
        ObjectMapper mapper = jsonMapper();
        VectorStore vs = new FakeVectorStore(mapper);
        GraphStore gs = new FakeGraphStore();

        Artifact source = artifact("sym-src", "repo-a", "src/util.py",
                "def sharedUtil(a):\n    return a", "python");
        // Consumer's `name` deliberately set to `callSharedUtil` so evidence renders that.
        Artifact consumer = new Artifact(
                "sym-cons", ArtifactType.CODE_SYMBOL, "callSharedUtil",
                "from util import sharedUtil\n\ndef callSharedUtil():\n    return sharedUtil(1)",
                "python", "repo-b", "src/consumer.py", null, Map.of(), Instant.now()
        );
        vs.upsert(List.of(source, consumer));

        Artifact after = artifact("sym-src", "repo-a", "src/util.py",
                "def sharedUtil(a, b):\n    return a + b", "python");

        return new Fixture(vs, gs, source, after);
    }

    private static Artifact artifact(String id, String repo, String path,
                                     String content, String lang) {
        String name = deriveName(content, lang);
        return new Artifact(id, ArtifactType.CODE_SYMBOL, name, content, lang,
                repo, path, null, Map.of(), Instant.now());
    }

    private static String deriveName(String content, String lang) {
        if ("python".equals(lang)) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("def\\s+(\\w+)").matcher(content);
            if (m.find()) return m.group(1);
        }
        return "sharedUtil";
    }

    private static Resource promptResource() {
        return new ByteArrayResource(PROMPT_TEMPLATE.getBytes());
    }

    private static ChatResponse canned(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** Jackson mapper wired for {@code java.time.Instant} so {@link FakeVectorStore} can serialize {@link Artifact}. */
    private static ObjectMapper jsonMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private record Fixture(VectorStore vs, GraphStore gs, Artifact before, Artifact after) {}
}

