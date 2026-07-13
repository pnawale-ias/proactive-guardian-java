# 🛡️ Proactive Guardian — Spring Boot Port

Java 21 · Spring Boot 3.3 · Maven

Parallel Java port of the FastAPI/LangGraph reference implementation in
`../src`. Preserves the exact 5-stage analysis pipeline
(`schema_change → breaking → duplicates → dependencies → constraints`) plus
every ingester and notifier. The Python code stays in place as a behavioural
reference until the JUnit suite reaches parity.

## Quick start

```bash
# 1) build
./mvnw -B -DskipTests package

# 2) bring up Qdrant + Neo4j + Guardian
make up          # or: docker compose up -d --build

# 3) health check
curl http://localhost:8080/healthz    # {"status":"ok"}

# 4) run tests (needs Docker for Testcontainers-backed ITs)
make test
```

## Configuration

All settings live under `guardian.*` in
[`src/main/resources/application.yml`](src/main/resources/application.yml) and
map 1:1 to the Python `Settings` class. Every field is also exposed as an env
var (`GUARDIAN_OPENAI_API_KEY`, `GUARDIAN_QDRANT_URL`, …) so the same
[`docker-compose.yml`](docker-compose.yml) works.

| Feature toggle                          | Purpose                                    |
|-----------------------------------------|--------------------------------------------|
| `guardian.qdrant.enabled=false`         | Skip Qdrant startup wiring (unit tests).   |
| `guardian.neo4j.enabled=false`          | Skip Neo4j startup wiring (unit tests).    |
| `guardian.confluence.enabled=false`     | Disable Confluence client + ingester.      |
| `guardian.github.enabled=false`         | Disable GitHub notifier.                   |
| `guardian.dbt.enabled=false`            | Skip dbt project detection.                |
| `guardian.databricks.enabled=true`      | Enable the Unity Catalog snapshot stub.    |

## Layout

```
src/main/java/com/proactiveguardian/
├── GuardianApplication.java
├── config/            @ConfigurationProperties + @Async executor
├── model/             Records: Artifact, ArtifactType, Finding, SchemaDelta ...
├── web/               HMAC filter · webhook · ingest · health controllers
├── knowledge/         VectorStore (Qdrant) + GraphStore (Neo4j) + Embeddings
├── ingestion/         Git · code · SQL schema · data-refs · dbt · Databricks
├── agent/             The 5 detectors + GuardianOrchestrator (linear pipeline)
└── notifier/          GitHub · Confluence
```

## Python ↔ Java module map

| Python (`src/`)                                | Java (`com.proactiveguardian.*`)                       |
|------------------------------------------------|---------------------------------------------------------|
| `main.py`                                      | `web.WebhookController` + `web.IngestController` + `web.HealthController` + `web.GithubHmacFilter` + `web.PullRequestPipelineService` |
| `config.py`                                    | `config.GuardianProperties`                             |
| `models.py`                                    | `model.*` records                                       |
| `knowledge/embeddings.py`                      | `knowledge.EmbeddingService` (Spring AI `EmbeddingModel`) |
| `knowledge/vector_store.py`                    | `knowledge.QdrantVectorStore` (interface `VectorStore`) |
| `knowledge/graph_store.py`                     | `knowledge.Neo4jGraphStore` (interface `GraphStore`)    |
| `ingestion/code_parser.py`                     | `ingestion.CodeParser` + `ingestion.language.*`         |
| `ingestion/sql_schema_parser.py`               | `ingestion.sql.SqlSchemaParser` (JSqlParser)            |
| `ingestion/data_reference_extractor.py`        | `ingestion.DataReferenceExtractor`                      |
| `ingestion/git_ingester.py`                    | `ingestion.GitIngester` (JGit)                          |
| `ingestion/confluence_ingester.py`             | `ingestion.ConfluenceIngester` + `notifier.ConfluenceClient` |
| `ingestion/dbt_parser.py`                      | `ingestion.DbtParser` (stub)                            |
| `ingestion/databricks_ingester.py`             | `ingestion.DatabricksIngester` (stub)                   |
| `ingestion/sql_query_analyzer.py`              | `ingestion.SqlQueryAnalyzer` (stub)                     |
| `agents/sql_diff.py`                           | `ingestion.sql.SqlDiffService`                          |
| `agents/orchestrator.py` (LangGraph)           | `agent.GuardianOrchestrator` (sequential pipeline)      |
| `agents/duplicate_detector.py`                 | `agent.DuplicateDetector`                               |
| `agents/dependency_analyzer.py`                | `agent.DependencyAnalyzer`                              |
| `agents/constraint_validator.py`               | `agent.ConstraintValidator` (Spring AI `ChatModel`)     |
| `agents/breaking_change_detector.py`           | `agent.BreakingChangeDetector`                          |
| `agents/schema_change_detector.py`             | `agent.SchemaChangeDetector`                            |
| `notifiers/github_notifier.py` (PyGithub)      | `notifier.GitHubNotifier` (hub4j `github-api`)          |
| `notifiers/confluence_notifier.py`             | `notifier.ConfluenceNotifier`                           |

## Deviations from the Python reference

1. **LangGraph → sequential pipeline.** The Python `GuardianOrchestrator`
   builds a `StateGraph`; the port folds a `List<GuardianStage>` left-to-right
   (`schema → breaking → dup → dep → constraint`). Node semantics — including
   the "data-plane pair short-circuit" for `schema`/`breaking` — are preserved.
2. **tree-sitter → JavaParser + regex SPI.** No maintained tree-sitter binding
   for the JVM. `.java` is handled by JavaParser (loss-less); every other
   language falls back to the per-language regex table already present in
   `BreakingChangeDetector.SIGNATURE_PATTERNS`. Symbol names + line numbers —
   the only fields downstream detectors use — are preserved.
3. **sqlglot → JSqlParser.** Same DDL coverage for `CREATE TABLE` / `ALTER
   TABLE`; the fallback regex parser is ported verbatim and still triggers the
   `parseConfidence=LOW` downgrade.
4. **atlassian-python-api → hand-rolled `ConfluenceClient`.** ~70 LOC over
   Spring's `RestClient` covering only the two REST calls Guardian needs.
5. **GitPython → JGit.** All commit/tree/blob access moves to `TreeWalk` /
   `RevWalk`; the temp-file blob-extraction trick used in `diffChangedPairs`
   is preserved.

## Tests

```
src/test/java/com/proactiveguardian/
├── testsupport/           Fakes: FakeVectorStore, FakeGraphStore, MiniRepoBuilder
├── web/                   HMAC filter · webhook integration tests
├── agent/                 Detector unit tests (one file per Python test)
└── ingestion/             Parser + SQL diff tests
```

`FakeVectorStore` and `FakeGraphStore` implement the same interfaces used in
production and are wired as `@Primary` `@TestConfiguration` beans — matching
the pytest `conftest.py` pattern.

# 🛡️ Handy build commands
 Standard build (was failing, now works)
./mvnw -DskipTests package

 Fully offline build (works now that the repo is warm)
./mvnw -o -DskipTests package

 Infra lifecycle
make infra-up      // or: docker compose -f docker-compose.infra.yml up -d
make infra-ps
make smoke
make infra-down    // keep data
make infra-nuke    // wipe volumes

 Run the app against local Qdrant+Neo4j
./mvnw spring-boot:run

 One-command lifecycle (Ollama + Spring Boot app):
make start        // start Ollama + app one by one (waits for /healthz)
make stop         // stop app + Ollama
make restart      // stop then start
make status       // show ports + processes for Qdrant / Neo4j / Ollama / app
make app-smoke    // /healthz + Ollama embed/chat + /ingest/repo
make app-logs     // tail the app log
// underlying script: scripts/guardian.sh {start|stop|restart|status|smoke|logs}



brew install ollama
brew services start ollama          # or: nohup ollama serve &
ollama pull nomic-embed-text        # embeddings (~275 MB, 768 dims)
ollama pull llama3.1                # chat model (~4.7 GB)

# App picks up Ollama at http://localhost:11434 automatically — no restart needed
# only if models were missing when a request was made. Restart to be safe:
kill $(cat /tmp/guardian-app.pid)
java -jar target/proactive-guardian-0.1.0-SNAPSHOT.jar

## Ollama registry blocked by corporate proxy? Pull from Hugging Face instead

On networks that inspect TLS (Cisco Secure Access, Zscaler, Netskope, Palo
Alto, etc.) the direct pull from `registry.ollama.ai` typically fails with
`tls: handshake failure`. Ollama has a built-in Hugging Face adapter that
bypasses `registry.ollama.ai` entirely and pulls GGUF weights straight from
`huggingface.co` — which almost all corporate policies allow.

```bash
# 1) Pull GGUFs via HF (works behind Cisco / Zscaler / Netskope proxies)
ollama pull hf.co/nomic-ai/nomic-embed-text-v1.5-GGUF:Q4_K_M
ollama pull hf.co/bartowski/Llama-3.2-3B-Instruct-GGUF:Q4_K_M     # small/fast, 2 GB
# heavier options if you have the bandwidth/disk:
# ollama pull hf.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF:Q4_K_M   # 4.9 GB
# ollama pull hf.co/bartowski/Qwen2.5-Coder-7B-Instruct-GGUF:Q4_K_M    # 4.7 GB

# 2) Alias them to the short names Spring AI's application-local.yml expects
printf 'FROM hf.co/nomic-ai/nomic-embed-text-v1.5-GGUF:Q4_K_M\n'  | ollama create nomic-embed-text -f -
printf 'FROM hf.co/bartowski/Llama-3.2-3B-Instruct-GGUF:Q4_K_M\n' | ollama create llama3.1         -f -

# 3) Sanity-check
curl -s http://localhost:11434/api/embeddings \
  -d '{"model":"nomic-embed-text","prompt":"hello"}' | head -c 120 ; echo
curl -s http://localhost:11434/api/chat \
  -d '{"model":"llama3.1","stream":false,"messages":[{"role":"user","content":"say hi"}]}' | head -c 200 ; echo
```

If **Hugging Face is also blocked**, alternatives that were reachable behind
Cisco Secure Access at time of writing:

| Source                       | How to use                                                                                              |
|------------------------------|----------------------------------------------------------------------------------------------------------|
| `hf-mirror.com` (HF mirror)  | `curl -LO https://hf-mirror.com/<user>/<repo>/resolve/main/<file>.gguf` → `ollama create <name> -f -`   |
| GitHub releases              | `curl -LO https://github.com/<user>/<repo>/releases/download/<tag>/<file>.gguf` → `ollama create ...`   |
| LM Studio / llama.cpp server | Point Spring AI at any OpenAI-compatible endpoint: `SPRING_AI_OPENAI_BASE_URL=http://localhost:1234/v1` |
| Any private LLM gateway      | Same as LM Studio: set `OPENAI_API_KEY` and `SPRING_AI_OPENAI_BASE_URL` to your internal endpoint       |


Proactive Guardian (Spring Boot) — common targets

Local infra (Qdrant + Neo4j only, no app image):
make infra-up          — start Qdrant + Neo4j in background
make infra-ps          — show container status
make infra-logs        — tail infra logs
make infra-down        — stop containers (keep volumes)
make infra-nuke        — stop + delete volumes (WIPES DATA)
make infra-neo4j-shell — open cypher-shell in the Neo4j container
make infra-qdrant-ui   — open Qdrant dashboard in browser
make smoke             — curl Qdrant + Neo4j readiness endpoints

App build:
make deps      — download all Maven deps (enables offline builds)
make build     — mvn compile
make test      — mvn test
make package   — mvn package (produces target/*.jar)
make run       — spring-boot:run on :8080
make clean     — mvn clean

Full stack (build image + infra):
make up        — docker compose up (guardian + qdrant + neo4j)
make down      — docker compose down
make logs      — tail guardian container logs
pnawale@JTGQTCGYXP proactive-guardian-java % cd /Users/pnawale/proactive-guardian/proactive-guardian-java && make help > /tmp/mkhelp.txt 2>&1 ; tail -15 /tmp/mkhelp.txt
make infra-qdrant-ui   — open Qdrant dashboard in browser
make smoke             — curl Qdrant + Neo4j readiness endpoints

App build:
make deps      — download all Maven deps (enables offline builds)
make build     — mvn compile
make test      — mvn test
make package   — mvn package (produces target/*.jar)
make run       — spring-boot:run on :8080
make clean     — mvn clean

Full stack (build image + infra):
make up        — docker compose up (guardian + qdrant + neo4j)
make down      — docker compose down
make logs      — tail guardian container logs
pnawale@JTGQTCGYXP proactive-guardian-java % cd /Users/pnawale/proactive-guardian/proactive-guardian-java && make help 2>&1 | tail -12 > /tmp/mkhelp.txt ; cat /tmp/mkhelp.txt
Full stack (build image + infra):
make up        — docker compose up (guardian + qdrant + neo4j)
make down      — docker compose down
make logs      — tail guardian container logs

Lifecycle (Ollama + app, uses scripts/guardian.sh):
make start     — start Ollama + Spring Boot app (one by one)
make stop      — stop app + Ollama
make restart   — stop then start
make status    — show port + process status
make app-smoke — /healthz + Ollama embed/chat + /ingest/repo
make app-logs  — tail the Spring Boot app log


export GITHUB_TOKEN=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
export GITHUB_POLL_ENABLED=true
export GITHUB_POLL_INTERVAL_MS=6000 
