#!/usr/bin/env bash
# ============================================================================
#  guardian.sh — one-stop lifecycle script for Proactive Guardian
# ----------------------------------------------------------------------------
#  Usage:  ./scripts/guardian.sh <command>
#
#  Commands:
#    start      Start each service one by one, waiting for health after each:
#                 1) Qdrant  (probe only — brought up via SSH tunnel)
#                 2) Neo4j   (probe only — brought up via SSH tunnel)
#                 3) Ollama  (local daemon,   brew services start ollama)
#                 4) App     (java -jar target/*.jar, background)
#    stop       Stop services in reverse order (app, then Ollama). Tunnels
#               and remote datastores are left alone.
#    restart    stop && start
#    status     Print the state of every port and process we care about.
#    logs       Tail /tmp/guardian-app.log (Ctrl-C to quit).
#    env        Show the effective env vars that will be inherited by the JVM
#               (loaded from ./.env + current shell).  Use this to debug
#               "logs not printing" / "poller not running" problems.
#    smoke      Run embed / chat / /healthz smoke tests against the running app.
#
#  Environment file (recommended):
#    Create $PROJECT_DIR/.env  — auto-sourced every run so you never have to
#    re-export in each new terminal.  Example:
#       GITHUB_TOKEN=ghp_xxx
#       GITHUB_POLL_ENABLED=true
#       GITHUB_POLL_INTERVAL_MS=6000
#       GITHUB_REPO_URL=https://github.com/softwarepravin2007/generateautocode
#       # OPENAI_API_KEY=sk-...          # leave unset to use local Ollama
#
#  Environment overrides:
#    OPENAI_API_KEY            If set, app uses OpenAI. Otherwise AiProfileSelector
#                              activates the 'local' profile (Ollama).
#    SPRING_AI_OPENAI_BASE_URL Point Spring AI's OpenAI adapter at any
#                              OpenAI-compatible local endpoint (LM Studio,
#                              llama.cpp, corp gateway, …).
#    OLLAMA_CHAT_MODEL         Default: llama3.1
#    OLLAMA_EMBEDDING_MODEL    Default: nomic-embed-text
# ============================================================================
set +e

# ----- config ---------------------------------------------------------------
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$PROJECT_DIR/target/proactive-guardian-0.1.0-SNAPSHOT.jar"
APP_LOG=/tmp/guardian-app.log
APP_PID_FILE=/tmp/guardian-app.pid
ENV_FILE="$PROJECT_DIR/.env"

# ----- .env auto-loader -----------------------------------------------------
# Sources $PROJECT_DIR/.env if present so that env vars (GITHUB_TOKEN,
# GITHUB_POLL_ENABLED, OPENAI_API_KEY, …) are picked up automatically every
# time this script runs — fixes "I exported it in another terminal" bugs.
# `set -a` auto-exports every assignment so the child JVM inherits them.
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

CHAT_MODEL=${OLLAMA_CHAT_MODEL:-llama3.1}
EMBED_MODEL=${OLLAMA_EMBEDDING_MODEL:-nomic-embed-text}

# ----- pretty printing ------------------------------------------------------
if [ -t 1 ]; then
  BOLD=$'\033[1m'; DIM=$'\033[2m'; GREEN=$'\033[32m'; RED=$'\033[31m'
  YELLOW=$'\033[33m'; BLUE=$'\033[34m'; RESET=$'\033[0m'
else
  BOLD=""; DIM=""; GREEN=""; RED=""; YELLOW=""; BLUE=""; RESET=""
fi
ok()   { printf '  %s✓%s %s\n'  "$GREEN"  "$RESET" "$*"; }
fail() { printf '  %s✗%s %s\n'  "$RED"    "$RESET" "$*"; }
warn() { printf '  %s!%s %s\n'  "$YELLOW" "$RESET" "$*"; }
info() { printf '  %s·%s %s\n'  "$DIM"    "$RESET" "$*"; }
step() { printf '\n%s%s%s\n' "$BOLD$BLUE" "── $* ─────────────────────────" "$RESET"; }

# ----- helpers --------------------------------------------------------------
sdkman_java() {
  # Ensure we use SDKMAN's Java 21 even in non-interactive shells.
  if [ -s "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
    # shellcheck disable=SC1091
    source "$HOME/.sdkman/bin/sdkman-init.sh" >/dev/null 2>&1
  fi
  export PATH="/opt/homebrew/bin:$PATH"
}

listening_pid() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | awk 'NR==2{print $2}'
}

port_free() { [ -z "$(listening_pid "$1")" ]; }

wait_http_200() {
  local url=$1 tries=${2:-40} i code
  for i in $(seq 1 "$tries"); do
    code=$(curl -sS -o /dev/null -w '%{http_code}' -m 2 "$url" 2>/dev/null)
    [ "$code" = "200" ] && { echo "$i"; return 0; }
    sleep 1
  done
  return 1
}

# ============================================================================
#  START — bring services up one at a time
# ============================================================================
cmd_start() {
  sdkman_java
  cd "$PROJECT_DIR" || exit 1

  # ---- 1) Qdrant (probe only) --------------------------------------------
  step "1/4  Qdrant  ·  http://localhost:6333"
  if curl -sS -m 4 http://localhost:6333/collections | grep -q '"status":"ok"'; then
    ok "reachable  ($(curl -sS http://localhost:6333/collections | python3 -c 'import sys,json;d=json.load(sys.stdin);print("collections=[" + ", ".join(c["name"] for c in d["result"]["collections"]) + "]")' 2>/dev/null))"
  else
    fail "NOT reachable — bring up the SSH tunnel first:"
    info "  ssh -L 6333:localhost:6333 <remote-host>"
    warn "continuing (Qdrant calls will fail at runtime)"
  fi

  # ---- 2) Neo4j (probe only) ---------------------------------------------
  step "2/4  Neo4j   ·  bolt://localhost:7687"
  if (echo >/dev/tcp/localhost/7687) 2>/dev/null; then
    ok "Bolt port open"
  else
    fail "NOT reachable — bring up the SSH tunnel first:"
    info "  ssh -L 7687:localhost:7687 <remote-host>"
    warn "continuing (Neo4j calls will fail at runtime)"
  fi

  # ---- 3) Ollama ---------------------------------------------------------
  step "3/4  Ollama  ·  http://localhost:11434"
  if curl -sS -m 2 http://localhost:11434/api/version >/dev/null 2>&1; then
    ok "already running  ($(curl -sS http://localhost:11434/api/version))"
  else
    info "starting via brew services…"
    brew services start ollama >/dev/null 2>&1
    n=$(wait_http_200 http://localhost:11434/api/version 20 || echo "")
    if [ -n "$n" ]; then
      ok "started in ${n}s  ($(curl -sS http://localhost:11434/api/version))"
    else
      fail "did not come up"
      return 1
    fi
  fi

  # verify expected models exist locally
  installed=$(curl -sS http://localhost:11434/api/tags 2>/dev/null)
  for m in "$EMBED_MODEL" "$CHAT_MODEL"; do
    if echo "$installed" | grep -q "\"$m"; then
      ok "model present: $m"
    else
      warn "model MISSING: $m"
      info "  ollama pull hf.co/nomic-ai/nomic-embed-text-v1.5-GGUF:Q4_K_M"
      info "  ollama pull hf.co/bartowski/Llama-3.2-3B-Instruct-GGUF:Q4_K_M"
      info "  printf 'FROM hf.co/...\\n' | ollama create $m -f -"
    fi
  done

  # ---- 4) Spring Boot app ------------------------------------------------
  step "4/4  Spring Boot app  ·  http://localhost:8080"
  if [ ! -f "$JAR" ]; then
    fail "jar not found: $JAR"
    info "  build it first:  ./mvnw -DskipTests package"
    return 1
  fi

  if ! port_free 8080; then
    warn "port 8080 already in use by PID $(listening_pid 8080) — leaving it"
    ok  "assuming app already running"
    return 0
  fi

  # ---- surface which env vars the JVM will inherit ----------------------
  info "Environment being passed to the JVM:"
  info "  OPENAI_API_KEY        = ${OPENAI_API_KEY:+set (${#OPENAI_API_KEY} chars)}${OPENAI_API_KEY:-<unset — will use local Ollama>}"
  info "  GITHUB_POLL_ENABLED   = ${GITHUB_POLL_ENABLED:-<unset — poller disabled>}"
  info "  GITHUB_POLL_INTERVAL_MS = ${GITHUB_POLL_INTERVAL_MS:-60000 (default)}"
  info "  GITHUB_REPO_URL       = ${GITHUB_REPO_URL:-<yml default>}"
  info "  GITHUB_TOKEN          = ${GITHUB_TOKEN:+set (${#GITHUB_TOKEN} chars)}${GITHUB_TOKEN:-<unset — anonymous, 60 req/hr>}"

  if [ "${GITHUB_POLL_ENABLED:-false}" = "true" ]; then
    [ -z "${GITHUB_TOKEN:-}" ] && \
      warn "GITHUB_POLL_ENABLED=true but GITHUB_TOKEN is empty (anonymous → 60 req/hr, no private repos)"
    # Force DEBUG so 'Poll cycle: N open PR(s), M dispatched' is always visible.
    export LOGGING_LEVEL_COM_PROACTIVEGUARDIAN_WEB_GITHUBPOLLINGSERVICE=DEBUG
    info "  LOGGING_LEVEL_COM_PROACTIVEGUARDIAN_WEB_GITHUBPOLLINGSERVICE=DEBUG (forced)"
  else
    warn "GITHUB_POLL_ENABLED is not 'true' — GitHubPollingService bean will NOT be created."
    info "  Fix: create $ENV_FILE with:  GITHUB_POLL_ENABLED=true"
    info "                                GITHUB_TOKEN=ghp_...    (personal access token)"
    info "                                GITHUB_POLL_INTERVAL_MS=6000"
  fi

  info "launching: java -jar $(basename "$JAR")"
  nohup java -jar "$JAR" >"$APP_LOG" 2>&1 &
  echo "$!" >"$APP_PID_FILE"
  info "PID $(cat "$APP_PID_FILE"), log: $APP_LOG"

  n=$(wait_http_200 http://localhost:8080/healthz 45 || echo "")
  if [ -n "$n" ]; then
    ok "/healthz READY in ${n}s"
    profile=$(grep -Eo "profile is active: .*" "$APP_LOG" | head -1)
    [ -n "$profile" ] && info "$profile"
  else
    fail "app failed to become healthy — showing last 25 log lines:"
    tail -25 "$APP_LOG"
    return 1
  fi

  printf '\n%s%s%s\n\n' "$BOLD$GREEN" "✓ ALL SERVICES UP" "$RESET"
  cmd_status
}

# ============================================================================
#  STOP — bring services down in reverse order
# ============================================================================
cmd_stop() {
  sdkman_java

  # ---- App --------------------------------------------------------------
  step "1/2  Spring Boot app"
  if [ -f "$APP_PID_FILE" ]; then
    OLD=$(cat "$APP_PID_FILE")
    if kill -0 "$OLD" 2>/dev/null; then
      kill "$OLD" 2>/dev/null
      for i in 1 2 3 4 5; do kill -0 "$OLD" 2>/dev/null || break; sleep 1; done
      kill -9 "$OLD" 2>/dev/null
      ok "killed PID $OLD"
    else
      info "PID $OLD already dead"
    fi
    rm -f "$APP_PID_FILE"
  fi
  pkill -f 'proactive-guardian-0.1.0-SNAPSHOT.jar' 2>/dev/null
  sleep 1
  stray=$(listening_pid 8080)
  if [ -n "$stray" ]; then
    warn "still on 8080 (PID $stray) — force-killing"
    kill -9 "$stray" 2>/dev/null
    sleep 1
  fi
  port_free 8080 && ok "port 8080 free" || fail "port 8080 still busy"

  # ---- Ollama -----------------------------------------------------------
  step "2/2  Ollama daemon"
  brew services stop ollama 2>&1 | tail -2 | sed 's/^/  /'
  sleep 2
  pkill -f 'ollama serve' 2>/dev/null
  sleep 1
  stray=$(listening_pid 11434)
  if [ -n "$stray" ]; then
    warn "still on 11434 (PID $stray) — force-killing"
    kill -9 "$stray" 2>/dev/null
    sleep 1
  fi
  port_free 11434 && ok "port 11434 free" || fail "port 11434 still busy"

  info "Qdrant :6333 and Neo4j :7687 SSH tunnels are left as-is."
  printf '\n%s%s%s\n' "$BOLD$GREEN" "✓ ALL SERVICES DOWN" "$RESET"
}

# ============================================================================
#  STATUS
# ============================================================================
cmd_status() {
  step "Ports"
  for entry in "6333 Qdrant" "7687 Neo4j" "11434 Ollama" "8080 App"; do
    port=${entry%% *}; name=${entry#* }
    pid=$(listening_pid "$port")
    if [ -n "$pid" ]; then
      cmd=$(ps -o command= -p "$pid" 2>/dev/null | head -c 60)
      ok "$port  $name  pid=$pid  $DIM$cmd$RESET"
    else
      warn "$port  $name  (not listening)"
    fi
  done

  step "Ollama models"
  if curl -sS -m 2 http://localhost:11434/api/tags >/dev/null 2>&1; then
    ollama list 2>&1 | head -10 | sed 's/^/  /'
  else
    warn "Ollama not running"
  fi

  step "App"
  if [ -f "$APP_PID_FILE" ] && kill -0 "$(cat "$APP_PID_FILE")" 2>/dev/null; then
    ps -o pid,etime,command -p "$(cat "$APP_PID_FILE")" 2>/dev/null | tail -1 | sed 's/^/  /'
  else
    warn "no app PID file / process"
  fi
}

# ============================================================================
#  SMOKE TESTS
# ============================================================================
cmd_smoke() {
  step "1  ·  GET /healthz"
  curl -sS -w "  HTTP %{http_code}  time=%{time_total}s\n" http://localhost:8080/healthz

  step "2  ·  Ollama embed  ($EMBED_MODEL)"
  curl -sS -m 30 http://localhost:11434/api/embeddings \
    -d "{\"model\":\"$EMBED_MODEL\",\"prompt\":\"hello world\"}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print('  dim =', len(d['embedding']));" 2>&1

  step "3  ·  Ollama chat  ($CHAT_MODEL)"
  curl -sS -m 60 http://localhost:11434/api/chat \
    -d "{\"model\":\"$CHAT_MODEL\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"reply with exactly one word: ok\"}]}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print('  reply =', repr(d['message']['content'][:80])); print('  ms    =', d.get('total_duration',0)//1_000_000)" 2>&1

  step "4  ·  POST /ingest/repo  (Hello-World, tiny repo)"
  curl -sS -m 60 -X POST \
    'http://localhost:8080/ingest/repo?repo_url=https://github.com/octocat/Hello-World.git&repo_name=hello-world' \
    -w '\n  HTTP %{http_code}  time=%{time_total}s\n' | head -c 300
  echo
}

# ============================================================================
#  LOGS
# ============================================================================
cmd_logs() {
  if [ -f "$APP_LOG" ]; then
    tail -f "$APP_LOG"
  else
    fail "no log file: $APP_LOG"
    exit 1
  fi
}

# ============================================================================
#  ENV — show what will be inherited by the JVM
# ============================================================================
cmd_env() {
  step "Effective environment (from $ENV_FILE + current shell)"
  if [ -f "$ENV_FILE" ]; then
    ok ".env loaded: $ENV_FILE"
  else
    warn ".env NOT found at $ENV_FILE  (create it — see 'help')"
  fi
  for v in OPENAI_API_KEY GITHUB_TOKEN GITHUB_POLL_ENABLED GITHUB_POLL_INTERVAL_MS \
           GITHUB_REPO_URL GITHUB_REPO_NAME GITHUB_WEBHOOK_SECRET \
           OLLAMA_BASE_URL OLLAMA_CHAT_MODEL OLLAMA_EMBEDDING_MODEL \
           QDRANT_URL NEO4J_URI NEO4J_USER; do
    val=$(eval "printf %s \"\${$v:-}\"")
    if [ -z "$val" ]; then
      warn "$v = <unset>"
    elif echo "$v" | grep -qE 'TOKEN|SECRET|KEY|PASS'; then
      ok   "$v = <set, ${#val} chars>"
    else
      ok   "$v = $val"
    fi
  done
}

# ============================================================================
#  main
# ============================================================================
case "${1:-}" in
  start)   cmd_start   ;;
  stop)    cmd_stop    ;;
  restart) cmd_stop && cmd_start ;;
  status)  cmd_status  ;;
  smoke)   cmd_smoke   ;;
  logs)    cmd_logs    ;;
  env)     cmd_env     ;;
  ""|-h|--help|help)
    sed -n '2,32p' "$0"
    ;;
  *) fail "unknown command: $1"; sed -n '2,32p' "$0"; exit 2 ;;
esac

