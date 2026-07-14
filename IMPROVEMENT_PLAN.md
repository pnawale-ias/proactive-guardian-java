# LLM Prompt & PR Comment Improvement Plan

## Current Token Baseline (per PR analysis)

**Per changed symbol (one `Pair`):**

| Node | Input tokens | Output tokens |
|---|---|---|
| `BreakingChangeDetector` | ~800–1,200 | ~200–400 |
| `ConstraintValidator` | ~600–900 | ~100–200 |
| `SchemaContractValidator` | ~400–700 | ~100–200 |

**~2,000–3,100 tokens per pair. A 10-symbol PR ≈ 20k–30k tokens.**

---

## Phase 1 — Prompt & Comment Format (zero infra cost)

Edit `.st` template files and `GitHubNotifier.render()` only.

### 1. Few-shot examples in `breaking_change_predictor.st`
Add 1–2 concrete examples of a breaking change + expected JSON output.
Example: method parameter type narrowing (`String → UUID`) with `will_break: true` + reason.
**Token delta: +300–500 input per call**

### 2. Add `fix_suggestion` + `reasoning` fields to all three prompt schemas
- `reasoning`: CoT scratchpad — ask the model to reason before outputting the verdict. Improves confidence calibration passively.
- `fix_suggestion`: backward-compatible alternative the model proposes (e.g., "add a column alias before dropping the old one").
- Render `fix_suggestion` as a `> 💡 Suggested fix:` blockquote in the PR comment.
**Token delta: +50 input, +100–200 output per finding**

### 3. Add negative instructions to prompts
Add to each template: `"Do not flag style changes, formatting, or comment-only changes."`
Reduces false positives with negligible token cost.
**Token delta: +30–50 input**

### 4. Grouped + collapsible PR comment format in `GitHubNotifier.render()`
No LLM cost — pure rendering change.

Current flat format:
```
## 🛡️ Proactive Guardian Report
### ⚠️ Title
**Category:** `breaking_change` · **Confidence:** 72%
<detail>
```

Target format:
```markdown
## 🛡️ Guardian Report — 1 blocking issue, 2 warnings

### 🚫 Breaking Changes
| Symbol | Impact | Confidence |
|--------|--------|------------|
| `UserService.getById` | 3 consumers in 2 repos | 91% |

<details><summary>Details</summary>

> 💡 Suggested fix: ...

</details>

### ⚠️ Schema Warnings
...
```

Changes:
- Summary header line (counts by severity)
- Findings grouped by severity (`BLOCK` → `WARN` → `INFO`)
- Per-finding `<details><summary>` collapsible (GitHub renders natively)
- `fix_suggestion` rendered as blockquote inside details
- Direct source link in finding title (see Phase 2, item 3)

**Phase 1 total token delta: +400–750 per LLM call**

---

## Phase 2 — Context Enrichment (code changes in ingestion + pipeline)

### 1. Unified diff as `{change}` in `breaking_change_predictor.st`
- In `GitIngester`, use JGit `DiffFormatter` to capture line-level `+`/`-` hunks per changed file.
- Populate `ChangeEvent.diff` (field exists, never set).
- Pass to `BreakingChangeDetector` instead of the current coarse content delta.
- Keep existing `maxChangeChars` cap (1,500–2,000 chars) — cost stays bounded.

Files: `GitIngester.java`, `BreakingChangeDetector.java`
**Token delta: 0 to +500 (bounded by existing cap)**

### 2. Cross-file PR context `{pr_summary}`
- In `PullRequestPipelineService`, collect all changed file paths + `DiffEntry.getChangeType()` (ADD/MODIFY/DELETE/RENAME).
- Pass as a new `{pr_summary}` template variable to `breaking_change_predictor.st`.
- Lets the LLM reason: "this PR also modifies the migration file, so the schema change is intentional."

Files: `PullRequestPipelineService.java`, `BreakingChangeDetector.java`, `breaking_change_predictor.st`
**Token delta: +100–200 input per call**

### 3. `{pr_title}` and `{commit_message}` in all prompts
- Extract from `GithubPullRequestEvent` (already available in `PullRequestPipelineService`).
- Inject into all three `.st` templates.
- A commit message like "feat: drop legacy_id column (migration 0042)" disambiguates intent.

Files: `PullRequestPipelineService.java`, all three `.st` templates
**Token delta: +50–100 input per call**

### 4. Populate `Artifact.url` with GitHub blob URL
- In `GitIngester`, construct `https://github.com/{repo}/blob/{sha}/{path}#L{start_line}` for each artifact.
- Render as a hyperlink in `GitHubNotifier` finding titles.
- Zero LLM cost — rendering only.

Files: `GitIngester.java`, `GitHubNotifier.java`

---

## Combined Phase 1 + 2 Cost Estimate

| Metric | Current | After Phase 1+2 |
|---|---|---|
| Input tokens per LLM call | ~800–1,200 | ~1,350–2,500 |
| Output tokens per LLM call | ~200–400 | ~300–600 |
| Tokens per 10-symbol PR | ~20k–30k | ~27k–47k |
| Cost per 10-symbol PR (Sonnet) | ~$0.07 | ~$0.10–$0.13 |

Biggest variable: unified diff size. The `maxChangeChars` cap in `BreakingChangeLlmProperties` keeps this bounded.

---

## Files to Change

| File | Phase | Change |
|---|---|---|
| `src/main/resources/prompts/breaking_change_predictor.st` | 1+2 | Few-shot examples, `reasoning`/`fix_suggestion` fields, negative instructions, `{pr_summary}`, `{pr_title}`, `{commit_message}` |
| `src/main/resources/prompts/constraint_validator.st` | 1+2 | `reasoning`/`fix_suggestion` fields, negative instructions, `{pr_title}` |
| `src/main/resources/prompts/schema_contract_validator.st` | 1+2 | `reasoning`/`fix_suggestion` fields, negative instructions, `{pr_title}` |
| `GitHubNotifier.java` | 1+2 | Grouped/collapsible render, `fix_suggestion` blockquote, source URL link |
| `GitIngester.java` | 2 | Capture unified diff via `DiffFormatter`; populate `Artifact.url` |
| `BreakingChangeDetector.java` | 2 | Accept + pass `{pr_summary}`, `{pr_title}`, `{commit_message}`, unified diff |
| `PullRequestPipelineService.java` | 2 | Extract + thread PR title, commit message, file change list to orchestrator |
