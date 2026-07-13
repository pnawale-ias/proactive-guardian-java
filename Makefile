COMPOSE       ?= docker compose
INFRA_COMPOSE ?= $(COMPOSE) -f docker-compose.infra.yml

.PHONY: help deps build test package run clean \
        up down logs \
        infra-up infra-down infra-restart infra-logs infra-ps infra-nuke \
        infra-neo4j-shell infra-qdrant-ui smoke \
        start stop restart status app-smoke app-logs deploy

help:
	@echo "Proactive Guardian (Spring Boot) — common targets"
	@echo ""
	@echo "  Local infra (Qdrant + Neo4j only, no app image):"
	@echo "    make infra-up          — start Qdrant + Neo4j in background"
	@echo "    make infra-ps          — show container status"
	@echo "    make infra-logs        — tail infra logs"
	@echo "    make infra-down        — stop containers (keep volumes)"
	@echo "    make infra-nuke        — stop + delete volumes (WIPES DATA)"
	@echo "    make infra-neo4j-shell — open cypher-shell in the Neo4j container"
	@echo "    make infra-qdrant-ui   — open Qdrant dashboard in browser"
	@echo "    make smoke             — curl Qdrant + Neo4j readiness endpoints"
	@echo ""
	@echo "  App build:"
	@echo "    make deps      — download all Maven deps (enables offline builds)"
	@echo "    make build     — mvn compile"
	@echo "    make test      — mvn test"
	@echo "    make package   — mvn package (produces target/*.jar)"
	@echo "    make run       — spring-boot:run on :8080"
	@echo "    make clean     — mvn clean"
	@echo ""
	@echo "  Full stack (build image + infra):"
	@echo "    make up        — docker compose up (guardian + qdrant + neo4j)"
	@echo "    make down      — docker compose down"
	@echo "    make logs      — tail guardian container logs"
	@echo ""
	@echo "  Lifecycle (Ollama + app, uses scripts/guardian.sh):"
	@echo "    make start     — start Ollama + Spring Boot app (one by one)"
	@echo "    make stop      — stop app + Ollama"
	@echo "    make restart   — stop then start"
	@echo "    make status    — show port + process status"
	@echo "    make app-smoke — /healthz + Ollama embed/chat + /ingest/repo"
	@echo "    make app-logs  — tail the Spring Boot app log"
	@echo "    make deploy    — stop → build → package → restart → app-logs"

# ---- Maven ---------------------------------------------------------------

deps:
	./mvnw -B dependency:go-offline

build:
	./mvnw -B -q compile

test:
	./mvnw -B test

package:
	./mvnw -B -q -DskipTests package

run:
	./mvnw spring-boot:run

# ---- Lifecycle (Ollama + Spring Boot app) --------------------------------
# Thin wrappers around scripts/guardian.sh so `make start` / `make stop`
# work from the project root.

start:
	@./scripts/guardian.sh start

stop:
	@./scripts/guardian.sh stop

restart:
	@./scripts/guardian.sh restart

status:
	@./scripts/guardian.sh status

app-smoke:
	@./scripts/guardian.sh smoke

app-logs:
	@./scripts/guardian.sh logs

# One-shot redeploy: stop the running app, rebuild the jar, restart, then tail.
deploy: stop build package restart app-logs

clean:
	./mvnw -B -q clean

# ---- Local infra (Qdrant + Neo4j) ---------------------------------------

infra-up:
	$(INFRA_COMPOSE) up -d
	@echo ""
	@echo "Qdrant  → http://localhost:6333/dashboard"
	@echo "Neo4j   → http://localhost:7474  (neo4j / guardianpass)"
	@echo "Run 'make infra-ps' to watch health status."

infra-down:
	$(INFRA_COMPOSE) down

infra-restart:
	$(INFRA_COMPOSE) restart

infra-logs:
	$(INFRA_COMPOSE) logs -f

infra-ps:
	$(INFRA_COMPOSE) ps

infra-nuke:
	$(INFRA_COMPOSE) down -v

infra-neo4j-shell:
	docker exec -it guardian-neo4j cypher-shell -u neo4j -p guardianpass

infra-qdrant-ui:
	open http://localhost:6333/dashboard

smoke:
	@echo "→ Qdrant /readyz"
	@curl -fsS http://localhost:6333/readyz && echo " OK" || echo " FAIL"
	@echo "→ Qdrant /collections"
	@curl -fsS http://localhost:6333/collections | head -c 200 && echo ""
	@echo "→ Neo4j HTTP"
	@curl -fsS -u neo4j:guardianpass http://localhost:7474/ | head -c 200 && echo ""

# ---- Full stack ----------------------------------------------------------

up:
	$(COMPOSE) up -d --build

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f guardian

