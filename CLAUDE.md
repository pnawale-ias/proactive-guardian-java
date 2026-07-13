# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build & test
./mvnw compile                        # compile
./mvnw test                           # run all tests
./mvnw -pl . -Dtest=SomeTest test     # run a single test class
./mvnw -DskipTests package            # build jar (target/*.jar)
./mvnw spring-boot:run                # run app directly via Maven

# Infra (Qdrant + Neo4j via Docker)
make infra-up                         # start Qdrant :6333 + Neo4j :7687
make infra-down                       # stop (keep volumes)
make infra-nuke                       # stop + delete volumes
make smoke                            # probe Qdrant + Neo4j health endpoints

# Lifecycle (Ollama + app together)
make start                            # start Ollama + app, wait for /healthz
make stop                             # stop app + Ollama
make status                           # show port/process state
make app-logs                         # tail /tmp/guardian-app.log

# Full stack via Docker Compose
make up                               # build image + start guardian + qdrant + neo4j
make down
```

Configure runtime env vars in `.env` at the project root — it's auto-sourced by `scripts/guardian.sh`.

## Architecture

Spring Boot 3.3 / Java 21 application that analyzes GitHub pull requests for breaking changes by combining a vector store (Qdrant), a knowledge graph (Neo4j), and an LLM (OpenAI or Ollama).

### AI Provider Selection (`config/AiProfileSelector`)

`AiProfileSelector` is an `EnvironmentPostProcessor` that runs before the Spring context starts. It picks the LLM backend in order:

1. **OpenAI** — `OPENAI_API_KEY` is set to a real value.
2. **OpenAI-compatible local endpoint** — `SPRING_AI_OPENAI_BASE_URL` is set (LM Studio, llama.cpp, vLLM). A dummy key is injected.
3. **Ollama fallback** — activates the `local` Spring profile (`application-local.yml`), which switches `spring.ai.model.chat/embedding` to `ollama`.

### PR Analysis Pipeline

```
GitHub webhook/poll → WebhookController / GitHubPollingService
  → PullRequestPipelineService (@Async "guardianWorker")
      → GitIngester.diffChangedPairs()   # JGit: base..head diff → (before, after) Artifact pairs
      → GuardianOrchestrator.analyzeChange()
          schema_change → breaking → duplicates → dependencies → constraints
      → GitHubNotifier.postPrComment()   # post findings as PR review comment
```

`GuardianOrchestrator` runs five detector nodes sequentially. Each node failure is swallowed so the pipeline continues. Alternatively, `POST /ingest/repo` ingests a full git repo without a PR context.

### Package Map

| Package | Responsibility |
|---|---|
| `agent` | Five analysis nodes (`SchemaChangeDetector`, `BreakingChangeDetector`, `DuplicateDetector`, `DependencyAnalyzer`, `ConstraintValidator`) + `GuardianOrchestrator` |
| `ingestion` | `GitIngester` (JGit), `CodeParser`, `DbtParser`, `ConfluenceIngester`, `DatabricksIngester`, language parsers (`JavaParserAdapter`, `RegexSymbolParser`), SQL parsers |
| `knowledge` | `VectorStore` / `QdrantVectorStore`, `GraphStore` / `Neo4jGraphStore`, `EmbeddingService` |
| `model` | Immutable records: `Artifact`, `ArtifactType`, `Finding`, `Severity`, `SchemaDelta`, `ColumnChange`, `ChangeEvent` |
| `notifier` | `GitHubNotifier` (PR comments), `ConfluenceNotifier`, `ConfluenceClient` |
| `web` | REST controllers (`WebhookController`, `IngestController`, `PullRequestController`, `HealthController`), `PullRequestPipelineService`, `GitHubPollingService`, `GithubHmacFilter` |
| `config` | `AiProfileSelector`, `GuardianProperties`, `AsyncConfig`, `LocalAiOverrideConfig` |

### Knowledge Stores

- **Qdrant** (gRPC `:6334`, REST `:6333`): vector embeddings for semantic similarity search.
- **Neo4j** (Bolt `:7687`): dependency graph. `GraphStore` interface; `Neo4jGraphStore` uses `Neo4jClient` directly (Spring Data repositories are disabled). Key relationships: `REFERENCES`, `DATA_REFERENCES`, `ORM_TO_TABLE`, `SQL_VIEW_OF`.
- The app skips `.git`, `node_modules`, `build`, `dist`, `target`, `.gradle`, `.idea` during repo walks.

### Prompt Templates

LLM prompts are StringTemplate (`.st`) files in `src/main/resources/prompts/`. Add new ones there.

### Testing

Unit tests use `FakeGraphStore` and `FakeVectorStore` (in-memory) declared via `TestFakesConfig` and activated by `application-test.yml` (which also disables all external integrations). Testcontainers (`neo4j`, `qdrant`) are available for integration tests if needed.
