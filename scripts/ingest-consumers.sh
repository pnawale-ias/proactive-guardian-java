#!/usr/bin/env bash
# Ingest every repo listed in scripts/consumer-repos.txt so that
# BreakingChangeDetector can discover cross-repo consumers.
#
# File format (one repo per line, comments with #):
#   <repo_url>    <repo_name>
# Example:
#   https://github.com/acme/billing-api.git    billing-api
#
# Usage:  bash scripts/ingest-consumers.sh [host:port]

set -euo pipefail

HOST="${1:-localhost:8080}"
LIST="$(dirname "$0")/consumer-repos.txt"

if [[ ! -f "$LIST" ]]; then
  echo "Missing $LIST" >&2
  exit 1
fi

# .env is auto-sourced by guardian.sh — source here too so GITHUB_TOKEN is set
# when this script is run standalone (needed for private repos).
if [[ -f "$(dirname "$0")/../.env" ]]; then
  set -a; . "$(dirname "$0")/../.env"; set +a
fi

OK=0; FAIL=0; TOTAL=0
while IFS= read -r line || [[ -n "$line" ]]; do
  # strip comments / blank lines
  line="${line%%#*}"
  [[ -z "${line// }" ]] && continue

  # shellcheck disable=SC2206
  parts=($line)
  url="${parts[0]}"
  name="${parts[1]:-$(basename "${url%.git}")}"

  # For private repos embed the token so JGit can clone.
  clone_url="$url"
  if [[ -n "${GITHUB_TOKEN:-}" && "$url" == https://github.com/* ]]; then
    clone_url="https://${GITHUB_TOKEN}@${url#https://}"
  fi

  TOTAL=$((TOTAL+1))
  printf ">> [%-40s] " "$name"

  # Do NOT use `curl -f` — we want the response body even on 4xx/5xx so we
  # can surface the real reason (auth failure, Neo4j down, parse error, …).
  HTTP=$(curl -sS -o /tmp/guardian-ingest.json -w '%{http_code}' -X POST \
        --data-urlencode "repo_url=${clone_url}" \
        --data-urlencode "repo_name=${name}" \
        "http://${HOST}/ingest/repo" || echo "000")

  OK_FLAG=$(grep -Eo '"ok"[[:space:]]*:[[:space:]]*(true|false)' /tmp/guardian-ingest.json \
            | head -n1 | grep -Eo '(true|false)')

  if [[ "$HTTP" == "200" && "$OK_FLAG" != "false" ]]; then
    n=$(grep -Eo '"ingested"[[:space:]]*:[[:space:]]*[0-9]+' /tmp/guardian-ingest.json \
        | grep -Eo '[0-9]+' | head -n1)
    echo "ok  (artifacts=${n:-?})"
    OK=$((OK+1))
  else
    STAGE=$(grep -Eo '"stage"[[:space:]]*:[[:space:]]*"[^"]*"' /tmp/guardian-ingest.json \
            | sed -E 's/.*"stage"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/' | head -n1)
    MSG=$(grep -Eo '"message"[[:space:]]*:[[:space:]]*"[^"]*"' /tmp/guardian-ingest.json \
          | sed -E 's/.*"message"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/' | head -n1)
    echo "FAIL  (http=${HTTP} stage=${STAGE:-?} msg=${MSG:-see /tmp/guardian-ingest.json})"
    FAIL=$((FAIL+1))
  fi
done < "$LIST"

echo
echo ">> Done. ok=${OK}  fail=${FAIL}  total=${TOTAL}"

