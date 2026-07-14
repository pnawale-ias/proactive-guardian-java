#!/usr/bin/env bash
# Creates the "User Request API" Confluence page under
#   https://softwarepravin2007.atlassian.net/wiki/spaces/~5e9d4815a77bf50c1ea301d5/pages/524289/Generate+Auto+Code
#
# Prereqs: Guardian app running on ${HOST:-localhost:8080}
#          with CONFLUENCE_USER / CONFLUENCE_TOKEN configured.

set -euo pipefail
HOST="${1:-localhost:8080}"
SPACE="${SPACE_KEY:-~5e9d4815a77bf50c1ea301d5}"
# Use `-` (not `:-`) so an explicit empty PARENT_PAGE_ID means "no parent"
# (create at space root). Default parent is left blank because the original
# "Generate Auto Code" page (id 524289) has been trashed.
PARENT="${PARENT_PAGE_ID-}"
TITLE="${TITLE:-User Request API}"

BODY='<h1>User Request API</h1>

<h2>Service</h2>
<table>
  <tr><th>Name</th><td><code>generateautocode</code></td></tr>
  <tr><th>Repository</th><td><a href="https://github.com/softwarepravin2007/generateautocode">softwarepravin2007/generateautocode</a></td></tr>
  <tr><th>Owner</th><td>Platform Automation</td></tr>
  <tr><th>Runtime</th><td>GitHub Actions workflow (<code>.github/workflows/autogencode.yml</code>)</td></tr>
</table>

<h2>API</h2>
<table>
  <tr><th>Method</th><td>POST</td></tr>
  <tr><th>Endpoint</th><td><code>https://api.github.com/repos/softwarepravin2007/generateautocode/dispatches</code></td></tr>
  <tr><th>Trigger</th><td><code>repository_dispatch</code></td></tr>
  <tr><th>Event type</th><td><code>auto_gen_code</code></td></tr>
  <tr><th>Auth</th><td><code>Authorization: Bearer &lt;GITHUB_TOKEN&gt;</code></td></tr>
  <tr><th>Content-Type</th><td><code>application/json</code></td></tr>
</table>

<h2>Request fields</h2>
<table>
  <tr><th>Field</th><th>Type</th><th>Required</th><th>Description</th></tr>
  <tr><td><code>event_type</code></td><td>string</td><td>yes</td><td>Must be <code>auto_gen_code</code>.</td></tr>
  <tr><td><code>client_payload.issue_key</code></td><td>string</td><td>yes</td><td>JIRA issue key, e.g. <code>PROJ-123</code>. Used as the new branch name.</td></tr>
  <tr><td><code>client_payload.issue_title</code></td><td>string</td><td>yes</td><td>Short JIRA summary — used in commit / PR title.</td></tr>
  <tr><td><code>client_payload.issue_description</code></td><td>string</td><td>yes</td><td>Full JIRA description — forwarded to the LLM as prompt context.</td></tr>
  <tr><td><code>client_payload.summary</code></td><td>string</td><td>no</td><td>Optional short summary echoed to workflow logs.</td></tr>
  <tr><td><code>client_payload.reporter</code></td><td>string</td><td>no</td><td>Optional reporter email echoed to workflow logs.</td></tr>
</table>

<h2>Response fields</h2>
<p>GitHub returns <code>204 No Content</code> on success and does <em>not</em> emit a JSON body. Failures surface as standard GitHub error envelopes:</p>
<table>
  <tr><th>Field</th><th>Type</th><th>Required</th><th>Description</th></tr>
  <tr><td>HTTP status</td><td>integer</td><td>yes</td><td><code>204</code> on success, <code>401</code>/<code>403</code>/<code>404</code>/<code>422</code> on error.</td></tr>
  <tr><td><code>message</code></td><td>string</td><td>only on error</td><td>Human-readable error message from GitHub.</td></tr>
  <tr><td><code>documentation_url</code></td><td>string</td><td>only on error</td><td>Link to GitHub API docs for the failing check.</td></tr>
  <tr><td><code>errors</code></td><td>array</td><td>only on error</td><td>Per-field validation problems, when applicable.</td></tr>
</table>

<h2>Sample request</h2>
<pre><code>curl -X POST \
  -H "Accept: application/vnd.github+json" \
  -H "Authorization: Bearer &lt;GITHUB_TOKEN&gt;" \
  -H "Content-Type: application/json" \
  https://api.github.com/repos/softwarepravin2007/generateautocode/dispatches \
  -d &apos;{
    "event_type": "auto_gen_code",
    "client_payload": {
      "issue_key":         "PROJ-123",
      "issue_title":       "Add GET /users endpoint",
      "issue_description": "Expose GET /users returning a paginated list of active users.",
      "summary":           "Add /users",
      "reporter":          "alice@example.com"
    }
  }&apos;</code></pre>

<h3>Sample response — success (204 No Content)</h3>
<pre><code>HTTP/1.1 204 No Content
X-GitHub-Request-Id: ABCD:1234:56789
Date: Fri, 10 Jul 2026 10:20:30 GMT
&lt;empty body&gt;</code></pre>

<h3>Sample response — error (422)</h3>
<pre><code>HTTP/1.1 422 Unprocessable Entity
Content-Type: application/json

{
  "message": "Validation Failed",
  "errors": [
    { "resource": "Dispatch", "code": "invalid", "field": "event_type" }
  ],
  "documentation_url": "https://docs.github.com/rest/repos/repos#create-a-repository-dispatch-event"
}</code></pre>

<h2>Required secrets</h2>
<ul>
  <li><code>OPENAI_API_KEY</code> — OpenAI key used by the workflow.</li>
  <li><code>GITHUB_TOKEN</code> — auto-provided by Actions (permits branch push + PR create).</li>
</ul>

<p><em>Auto-created by Proactive Guardian.</em></p>'

echo ">> Creating Confluence page: $TITLE  (space=$SPACE, parent=$PARENT)"
curl -fsS -X POST "http://${HOST}/ingest/confluence/page" \
  --data-urlencode "space_key=${SPACE}" \
  --data-urlencode "parent_page_id=${PARENT}" \
  --data-urlencode "title=${TITLE}" \
  --data-urlencode "body=${BODY}"
echo

