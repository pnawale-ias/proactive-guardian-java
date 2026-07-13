#!/usr/bin/env bash
#
# Ingest one or more Confluence spaces into the Guardian knowledge base.
#
# Usage:
#   bash scripts/ingest-confluence.sh [host:port] [space_key ...]
#
# Defaults:
#   host:port  -> localhost:8080
#   space_key  -> ~5e9d4815a77bf50c1ea301d5   (the personal space from the
#                 https://softwarepravin2007.atlassian.net Confluence)
#
# Prereqs (in application.yml / environment):
#   guardian.confluence.enabled = true
#   CONFLUENCE_BASE_URL   = https://softwarepravin2007.atlassian.net
#   CONFLUENCE_USER       = <atlassian account email>
#   CONFLUENCE_TOKEN      = <API token from id.atlassian.com/manage-profile/security/api-tokens>
#
set -euo pipefail

HOST="${1:-localhost:8080}"
shift || true

if [[ $# -eq 0 ]]; then
  # Personal space parsed out of
  # https://softwarepravin2007.atlassian.net/wiki/spaces/~5e9d4815a77bf50c1ea301d5/overview
  set -- "~5e9d4815a77bf50c1ea301d5"
fi

for space in "$@"; do
  # URL-encode the tilde so `~` in personal-space keys survives.
  encoded="${space//~/%7E}"
  echo ">> Ingesting Confluence space: ${space}"
  curl -fsS -X POST \
    "http://${HOST}/ingest/confluence?space_key=${encoded}" \
    | sed 's/^/   /'
  echo
done

