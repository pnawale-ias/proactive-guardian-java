package com.proactiveguardian.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.proactiveguardian.config.GuardianProperties;
import com.proactiveguardian.ingestion.sql.SqlDiffService;
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
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Smoke test for the linear pipeline using in-memory fakes. */
class GuardianOrchestratorTest {

    @Test
    void schemaDetectorFiresColumnDroppedFinding() throws Exception {
        VectorStore vs = new FakeVectorStore();
        GraphStore gs = new FakeGraphStore();
        SqlDiffService sql = new SqlDiffService();

        GuardianOrchestrator orch = new GuardianOrchestrator(
                new SchemaChangeDetector(vs, gs, sql),
                new BreakingChangeDetector(vs, gs),
                new DuplicateDetector(vs, props(0.99)),
                new DependencyAnalyzer(gs),
                constraintValidator(vs),
                new YamlSyntaxValidator(),
                schemaContractValidator(vs)
        );

        Artifact before = new Artifact(
                "t1", ArtifactType.SQL_TABLE, "sales.orders",
                "CREATE TABLE sales.orders (id INT NOT NULL, amount DECIMAL(10,2), status VARCHAR(20));",
                "sql", "repo-a", "ddl/orders.sql", null,
                Map.of("table_fqn", "sales.orders"), null
        );
        Artifact after = new Artifact(
                "t1", ArtifactType.SQL_TABLE, "sales.orders",
                "CREATE TABLE sales.orders (id INT NOT NULL, amount DECIMAL(10,2));",
                "sql", "repo-a", "ddl/orders.sql", null,
                Map.of("table_fqn", "sales.orders"), null
        );

        List<Finding> findings = orch.analyzeChange(before, after);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.category()).isEqualTo("column_dropped");
            assertThat(f.severity()).isIn(Severity.WARN, Severity.BLOCK);
            assertThat(f.title()).contains("sales.orders.status");
        });
    }

    @Test
    void newArtifactYieldsNoBreakingChangeFinding() throws Exception {
        VectorStore vs = new FakeVectorStore();
        GraphStore gs = new FakeGraphStore();

        GuardianOrchestrator orch = new GuardianOrchestrator(
                new SchemaChangeDetector(vs, gs, new SqlDiffService()),
                new BreakingChangeDetector(vs, gs),
                new DuplicateDetector(vs, props(0.99)),
                new DependencyAnalyzer(gs),
                constraintValidator(vs),
                new YamlSyntaxValidator(),
                schemaContractValidator(vs)
        );

        Artifact fresh = new Artifact(
                "sym1", ArtifactType.CODE_SYMBOL, "brandNewFunction",
                "def brandNewFunction():\n    return 42",
                "python", "repo-b", "src/x.py", null, Map.of(), null
        );

        List<Finding> findings = orch.analyzeChange(null, fresh);
        // A new symbol emits an INFO-level breaking_change advisory — that is expected.
        // Assert no WARN or BLOCK severity breaking_change (i.e. no destructive impact).
        assertThat(findings).noneMatch(f -> "breaking_change".equals(f.category())
                && f.severity() != Severity.INFO);
    }

    private static GuardianProperties props(double riskThreshold) {
        // openaiApiKey, embeddingModel, llmModel,
        // qdrantUrl, qdrantCollection, qdrantApiKey,
        // neo4jUri, neo4jUser, neo4jPass,
        // githubToken, githubWebhookSecret, githubRepoUrl, githubRepoName, githubPollEnabled, githubPollIntervalMs,
        // confluenceBaseUrl, confluenceUser, confluenceToken,
        // databricksHost, databricksToken, databricksDefaultCatalog, defaultSqlDialect,
        // mysqlEnabled, mysqlUrl, mysqlUser, mysqlPassword, mysqlDatabase,
        // riskThreshold
        return new GuardianProperties(
                "k", null, null,
                null, null, null,
                null, null, null,
                null, null, null, null, null, null,
                null, null, null,
                null, null, null, null,
                null, null, null, null, null,
                riskThreshold
        );
    }

    private static ConstraintValidator constraintValidator(VectorStore vs) throws Exception {
        return new ConstraintValidator(
                vs,
                p -> new ChatResponse(List.of(new Generation(new AssistantMessage("{\"violations\":[]}")))),
                new ObjectMapper(),
                new ByteArrayResource("{code}\n{constraints}".getBytes())
        );
    }

    private static SchemaContractValidator schemaContractValidator(VectorStore vs) throws Exception {
        return new SchemaContractValidator(
                vs,
                p -> new ChatResponse(List.of(new Generation(new AssistantMessage("{\"violations\":[]}")))),
                new ObjectMapper(),
                new ByteArrayResource("{schemas}\n{code}".getBytes())
        );
    }
}

