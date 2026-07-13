#!/usr/bin/env bash
# ============================================================================
#  ingest-org.sh — enumerate every repo under a GitHub user/org and ingest
#  each into the Guardian knowledge base (Qdrant + Neo4j) so that
#  BreakingChangeDetector can detect CROSS-REPO consumers when a PR lands.
#
#  Usage:
#     bash scripts/ingest-org.sh                       # defaults to softwarepravin2007
#     bash scripts/ingest-org.sh <owner>               # any GitHub user or org
#     bash scripts/ingest-org.sh <owner> <host:port>   # non-default app host
#
#  Env:
#     GITHUB_TOKEN   optional but recommended (higher rate limit, private repos)
# ============================================================================
set -euo pipefail

OWNER="${1:-softwarepravin2007}"
HOST="${2:-localhost:8080}"

# .env is auto-sourced by guardian.sh — source here too so GITHUB_TOKEN is set
# when this script is run standalone.
if [[ -f "$(dirname "$0")/../.env" ]]; then
  set -a; . "$(dirname "$0")/../.env"; set +a
fi

AUTH=()
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  AUTH=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi

echo ">> Listing repos for '${OWNER}'..."

# Try /users/{owner}/repos first (works for users); if that returns 404 fall
# back to /orgs/{owner}/repos (works for organisations).
list_url() {
  local kind="$1"    # users | orgs
  echo "https://api.github.com/${kind}/${OWNER}/repos?per_page=100&type=all&sort=updated"
}

fetch_page() {
  curl -fsS "${AUTH[@]}" -H "Accept: application/vnd.github+json" "$1"
}

KIND="users"
if ! fetch_page "$(list_url users)&page=1" >/dev/null 2>&1; then
  KIND="orgs"
fi

REPOS=()
PAGE=1
while : ; do
  RESP="$(fetch_page "$(list_url "$KIND")&page=${PAGE}")"
  # extract "clone_url" fields without requiring jq
  MAPFILE=$(printf '%s' "$RESP" \
    | grep -Eo '"clone_url"\s*:\s*"[^"]+"' \
    | sed -E 's/.*"clone_url"[[:space:]]*:[[:space:]]*"([^"]+)"/\1/')
  [[ -z "$MAPFILE" ]] && break
  while IFS= read -r url; do REPOS+=("$url"); done <<< "$MAPFILE"
  # stop if this page had < 100 entries
  COUNT=$(printf '%s\n' "$MAPFILE" | wc -l | tr -d ' ')
  (( COUNT < 100 )) && break
  PAGE=$((PAGE+1))
done

if (( ${#REPOS[@]} == 0 )); then
  echo "!! No repos found for ${OWNER}. Check the owner name and GITHUB_TOKEN." >&2
  exit 1
fi

echo ">> Found ${#REPOS[@]} repo(s). Ingesting via http://${HOST}/ingest/repo ..."
echo

OK=0; FAIL=0
for url in "${REPOS[@]}"; do
  # derive repo_name from URL: .../owner/name.git -> name
  name="$(basename "${url%.git}")"

  # For private repos embed the token so JGit can clone.
  clone_url="$url"
  if [[ -n "${GITHUB_TOKEN:-}" && "$url" == https://github.com/* ]]; then
    clone_url="https://${GITHUB_TOKEN}@${url#https://}"
  fi

  printf ">> [%-40s] " "$name"
  # Do NOT use `curl -f` — we want the response body even on 4xx/5xx so the
  # user sees the real reason (auth failure, clone timeout, parse error, …).
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
    # Surface stage + message from the structured error body if present.
    STAGE=$(grep -Eo '"stage"[[:space:]]*:[[:space:]]*"[^"]*"' /tmp/guardian-ingest.json \
            | sed -E 's/.*"stage"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/' | head -n1)
    MSG=$(grep -Eo '"message"[[:space:]]*:[[:space:]]*"[^"]*"' /tmp/guardian-ingest.json \
          | sed -E 's/.*"message"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/' | head -n1)
    echo "FAIL  (http=${HTTP} stage=${STAGE:-?} msg=${MSG:-see /tmp/guardian-ingest.json})"
    FAIL=$((FAIL+1))
  fi
done

echo
echo ">> Done. ok=${OK}  fail=${FAIL}  total=${#REPOS[@]}"
echo ">> Verify with:"
echo "     curl -s http://localhost:6333/collections/code_and_docs | jq .result.points_count"

