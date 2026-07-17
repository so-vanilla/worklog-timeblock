# worklog-timeblock Plan

このファイルは、更新後のAGENTS.md運用に合わせた実行用プランです。`COMPACTION_PLAN.md` は背景と引き継ぎ資料、この `PLAN.md` は今後 `/team <planファイルパス>` または単一セッションで作業するための計画ファイルとして扱います。

## Objective

org-timeblock由来の工数管理補助を、Emacs/org-mode依存ではなく、ローカル専用のClojureアプリとして育てる。目的は、社内工数サービスへ手入力する前段の判断と集計を軽くすること。

対象:

- カレンダー由来またはfixture由来の予定候補をwork log化する。
- タイトルマッピングで自動確定または除外する。
- 未分類、除外、欠席、端数、短い空白、大きい空白を明示的に扱う。
- WebとTUIで同じバックエンドを使う。
- Webは画面全体を使い、確認、修正、手入力用サマリ確認を同時に行えるようにする。

対象外:

- 社内工数サービスへの自動入力。
- 初期段階での実Google Calendar認証。
- org-modeファイルを直接データストアにする運用。
- SQLiteや内部フォーマットを人間が直接編集する運用。

## Current State

リポジトリ:

- Path: `/Users/shuto-vanilla/Documents/Codex/2026-07-06-flake-my-emacs-git-log-org/worklog-timeblock`
- Status: 新規リポジトリ。初回コミット前。
- Git state: 全実装ファイルはstaged。未コミット。
- Runtime: `nix run` and `devenv`
- Main verification: `devenv shell e2e-all`, `devenv shell test`, `devenv shell lint`, `nix flake check`

直近の確認済みベースライン:

- `devenv shell e2e`: 3 tests / 61 assertions / 0 failures
- `devenv shell e2e-all`: Clojure E2E 3 tests / 61 assertions / 0 failures, zellij E2E 8 cases / 198 assertions / 0 failures
- `devenv shell test`: 9 tests / 101 assertions / 0 failures
- `devenv shell lint`: errors 0 / warnings 0
- `nix flake check`: success
- `git diff --check --cached && git diff --check`: success
- `zellij --session wz-10 action list-tabs`: `Tab #1` only
- Latest local zellij artifact: `e2e-artifacts/zellij-tui-layout/20260717-144801/index.md`

## Completed Work

### Phase 0: Repository And Runtime Baseline

Status: Done.

Implemented:

- Clojure project scaffold.
- `deps.edn`.
- `devenv.nix` scripts:
  - `test`
  - `e2e`
  - `e2e-zellij`
  - `e2e-all`
  - `lint`
  - `run-backend`
  - `run-tui`
- `flake.nix` with `flake-utils`.
- `nix run .`, `nix run .#backend`, `nix run .#tui`.
- SQLite migration.
- Docker Compose file retained as optional path.

Validation:

- `nix flake check` passed.
- `nix run . -- --host 127.0.0.1 --port 3000 --db ./data/app.db` was verified with `/health` and `/days/2026-07-06` during the previous session.

### Phase 1: Domain, Persistence, And Source Boundary

Status: Done.

Implemented:

- Candidate event to work-log conversion.
- Title mapping based auto-confirm/exclude.
- Unknown title to `uncategorized`.
- Work-log category assignment.
- Work-log exclusion.
- Work-log range change.
- Source snapshot/stale helper.
- Day summary.
- 15-minute rounding.
- Rounding residual to `other`.
- Short gap to `other`.
- Large gap warning.
- Uncategorized warning.
- SQLite category/title mapping/work-log helpers.
- `EventSource` protocol.
- Local EDN/resource/file source for tests.

Owned files:

- `src/worklog_timeblock/domain/worklog.clj`
- `src/worklog_timeblock/domain/summary.clj`
- `src/worklog_timeblock/db/core.clj`
- `src/worklog_timeblock/db/migration.clj`
- `src/worklog_timeblock/plugin/protocol.clj`
- `src/worklog_timeblock/plugin/local.clj`
- `resources/migrations/001_initial.up.sql`
- `resources/fixtures/local-events.edn`
- `test/worklog_timeblock/domain/worklog_test.clj`
- `test/worklog_timeblock/domain/summary_test.clj`
- `test/worklog_timeblock/db/core_test.clj`
- `test/worklog_timeblock/plugin/local_test.clj`

### Phase 2: Web/API Editing Workspace

Status: Done.

Implemented:

- `GET /health`.
- `POST /api/candidates/import`.
- `GET /api/days/:date`.
- `GET /api/days/:date/summary`.
- `PATCH /api/worklogs/:id`.
- 400 for invalid work-log edits.
- 404 for missing work logs.
- Form routes:
  - `POST /worklogs/:id/assign-category`
  - `POST /worklogs/:id/range`
  - `POST /worklogs/:id/exclude`
- Full-viewport day page.
- Timeline pane.
- Summary pane.
- Category selector.
- Range edit form.
- Exclude action.
- Manual-entry output block.
- Warnings.

Owned files:

- `src/worklog_timeblock/api/routes.clj`
- `src/worklog_timeblock/api/server.clj`
- `src/worklog_timeblock/web/pages.clj`
- `test/worklog_timeblock/api_e2e/routes_test.clj`
- `test/worklog_timeblock/web_e2e/pages_test.clj`

Validation:

- API E2E verifies category/range/exclude updates.
- API E2E verifies invalid range, unknown category, and unknown worklog behavior.
- Web E2E verifies full-viewport workspace and edit controls.
- Web E2E verifies rendered summary changes after edits.

### Phase 3: Display-Only TUI And Zellij E2E

Status: Done.

Implemented:

- TUI dashboard rendering.
- Date, timeline, category totals, warnings.
- Width-aware rendering.
- Narrow output compaction.
- zellij-driven viewport E2E.
- NG token check for the prior `confirmedBuild` narrow-width bug.

Owned files:

- `src/worklog_timeblock/tui/main.clj`
- `test/worklog_timeblock/tui_e2e/main_test.clj`
- `scripts/zellij-tui-e2e.sh`

Validation:

- `devenv shell e2e-zellij`: 8 cases / 198 assertions / 0 failures.
- Temporary `wtb-e2e-*` zellij tabs are closed after success.

### Phase 4: Handoff And Documentation

Status: Done.

Implemented:

- `README.md` with scope, current UI, development commands, and Nix running instructions.
- `COMPACTION_PLAN.md` with background, implementation status, verification policy, roadmap, and handoff instructions.
- This `PLAN.md` as AGENTS.md-compliant execution plan.

Owned files:

- `README.md`
- `COMPACTION_PLAN.md`
- `PLAN.md`

## Execution Rules

Before new implementation:

1. Read `PLAN.md` and `COMPACTION_PLAN.md`.
2. Run `git status --short --branch`.
3. Preserve staged files. Do not reset or discard the initial staged repository.
4. If task scope is non-trivial, present the next concrete plan before editing.
5. Use `apply_patch` for manual edits.
6. Do not use `rm`; use safe-rm if deletion becomes necessary.
7. Keep `nix run` and `devenv` paths working.

Verification before claiming completion:

```sh
devenv shell e2e-all
devenv shell test
devenv shell lint
nix flake check
git diff --check --cached
git diff --check
zellij --session wz-10 action list-tabs
```

Expected zellij tab state:

```text
TAB_ID  POSITION  NAME
0  0  Tab #1
```

## Agent Team Plan

Agent Teams should be used only when work can be split without shared-file conflicts. Do not use Delegate mode.

Recommended worker count: 3.

### Worker A: Domain And Persistence

Owns:

- `src/worklog_timeblock/domain/**`
- `src/worklog_timeblock/db/**`
- `src/worklog_timeblock/plugin/**`
- `resources/migrations/**`
- `resources/fixtures/**`
- matching unit tests under `test/worklog_timeblock/domain/**`, `db/**`, `plugin/**`

Typical tasks:

- Category model changes.
- Title mapping persistence.
- Summary semantics.
- Time edge cases.
- Source plugin interfaces.

Must not edit:

- `src/worklog_timeblock/web/pages.clj`
- `src/worklog_timeblock/api/routes.clj` unless explicitly assigned in a sequential handoff.
- `scripts/zellij-tui-e2e.sh`.

### Worker B: API And Web

Owns:

- `src/worklog_timeblock/api/routes.clj`
- `src/worklog_timeblock/api/server.clj`
- `src/worklog_timeblock/web/pages.clj`
- `test/worklog_timeblock/api_e2e/routes_test.clj`
- `test/worklog_timeblock/web_e2e/pages_test.clj`

Typical tasks:

- HTTP endpoints.
- HTML form flows.
- Web day workspace.
- Manual-entry output.
- API and Web E2E.

Must not edit:

- Domain/persistence files unless Worker A has completed a dependency and handed off the API contract.
- TUI/zellij files unless the task explicitly includes visible TUI changes.

### Worker C: TUI, Runtime, And Verification

Owns:

- `src/worklog_timeblock/tui/main.clj`
- `test/worklog_timeblock/tui_e2e/main_test.clj`
- `scripts/zellij-tui-e2e.sh`
- `devenv.nix`
- `flake.nix`
- `README.md`
- `COMPACTION_PLAN.md`
- `PLAN.md`

Typical tasks:

- TUI rendering or interaction.
- zellij E2E.
- Nix/devenv execution paths.
- Verification scripts.
- Documentation updates.

Must not edit:

- API/Web or domain files unless the task is a sequential integration step.

### Shared File Rule

No file should be assigned to multiple workers in the same parallel phase. If a feature needs a schema change plus API plus UI, split it into sequential phases:

1. Worker A defines and tests domain/persistence contract.
2. Worker B consumes the contract in API/Web.
3. Worker C updates TUI/runtime/docs and runs verification.

## Next Recommended Work

### Phase 5: Category And Title Mapping Management

Status: Not started.

Reason:

Name-based auto-confirmation is already part of the workflow, but there is no UI to maintain categories or mappings. This is higher value than real calendar sync because calendar sync would still produce uncategorized or wrongly categorized logs without maintainable mapping controls.

Scope:

- List categories.
- Create/update category.
- Deactivate category instead of destructive delete if logs reference it.
- List title mappings.
- Create/update/delete title mapping.
- Show when a work log was auto-confirmed from a mapping.
- Add action to create or update mapping from a corrected work log.
- Preserve snapshot behavior: existing confirmed logs do not silently change unless an explicit reapply action is implemented.

Suggested ownership:

- Worker A: persistence and domain contract for category/mapping management.
- Worker B: API/Web management screens and E2E.
- Worker C: docs, verification, optional TUI display of mapping source.

Quantitative done criteria:

- Add at least 25 new assertions across unit/API/Web tests.
- API E2E covers create/update/delete-or-deactivate mappings.
- Web E2E covers creating a mapping from a corrected work log.
- Test proves existing snapshots do not silently mutate after mapping changes.
- Full gate passes:
  - `devenv shell e2e-all`
  - `devenv shell test`
  - `devenv shell lint`
  - `nix flake check`
  - `git diff --check --cached`
  - `git diff --check`

### Phase 6: Manual Entry Export And Other Breakdown

Status: Partially started through the Web manual-entry block.

Scope:

- Stable category ordering.
- Copy-friendly plain text output.
- Optional CSV/TSV export.
- Separate visibility for:
  - confirmed category totals
  - `other` from rounding residual
  - `other` from short gaps
  - warnings requiring manual attention

Suggested ownership:

- Worker A: summary model details.
- Worker B: Web output and export routes.
- Worker C: README/plan updates and optional TUI output.

Quantitative done criteria:

- Add at least 20 new assertions.
- Export output has exact string tests.
- `other` breakdown is visible and tested.
- Uncategorized and large-gap warnings remain visible and tested.

### Phase 7: TUI Interaction

Status: Not started.

Scope:

- Select work log.
- Assign category.
- Exclude.
- Edit range.
- Create mapping from corrected log if Phase 5 exists.
- Re-render totals after edits.

Suggested ownership:

- Worker C owns TUI files.
- Worker A/B only provide stable APIs before TUI work begins.

Quantitative done criteria:

- Add at least 5 zellij interaction cases if interaction is implemented through real terminal input.
- Keep at least the current 8 zellij viewport cases.
- Total zellij E2E assertions must remain at least 198 and increase when new cases are added.
- Narrow viewport output must remain readable, with explicit NG-token checks for concatenated fields.

### Phase 8: Calendar Source Plugins

Status: Not started.

Scope:

- Provider configuration model.
- Local test server or fixture-backed CalDAV/GCal-like source.
- Auth/token storage design for local-only app.
- Sync/import command.
- Source update/diff warnings.

Constraints:

- Do not require a real personal Google Calendar for tests.
- Confirmed snapshots must not silently mutate after source changes.
- Source-updated warnings must be visible.

Quantitative done criteria:

- Add fixture/server E2E for import and changed source event.
- Test proves confirmed snapshots are preserved.
- Test proves source update warning is visible.

### Phase 9: Hardening

Status: Not started.

Scope:

- Migration version tracking.
- Backup/export of SQLite data.
- Better API error schema.
- Overlaps.
- Day-crossing events.
- DST.
- Timezone mismatch.
- Duplicate source events.
- Optional Docker Compose runtime verification if Docker daemon is available.
- CI subset that excludes local zellij E2E.

Quantitative done criteria:

- Add edge-case tests before implementation.
- Each edge case has at least one failing test before fix.
- All failure modes are visible through API or UI, not hidden in logs only.

## Immediate Next Session Checklist

Run:

```sh
cd /Users/shuto-vanilla/Documents/Codex/2026-07-06-flake-my-emacs-git-log-org/worklog-timeblock
git status --short --branch
zellij --session wz-10 action list-tabs
devenv shell test
```

Then choose one:

- If only preserving baseline or preparing first commit: run full gate and commit.
- If implementing next functionality: start with Phase 5 and write failing tests first.
- If using Agent Teams: use this file as `/team /Users/shuto-vanilla/Documents/Codex/2026-07-06-flake-my-emacs-git-log-org/worklog-timeblock/PLAN.md`.

## Workflow Feedback Template

After `/team`, Ralph Loop, or other multi-step workflow, report:

- Worker/file ownership conflicts: none or list concrete files.
- API/domain contract mismatches: none or list mismatches.
- Test coverage gaps: none or list missing tests.
- Verification commands run: list exact commands and results.
- Plan quality: too coarse, too fine, or adequate.
- Next improvement: one concrete adjustment for the next plan.
