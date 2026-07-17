# Compaction Plan

このファイルは、会話コンテキストが圧縮された後、または別セッションへ引き継ぐための背景付き計画書です。上から順に読めば、なぜこのアプリを作っているか、何が実装済みか、次に何をすべきか、どの検証を通すべきかが分かる粒度にしています。

## Repository

- Path: `/Users/shuto-vanilla/Documents/Codex/2026-07-06-flake-my-emacs-git-log-org/worklog-timeblock`
- Status: 新規Clojureリポジトリ。まだ初回コミット前。
- Runtime: `nix run` and `devenv`
- Scope: ローカル専用の工数集計補助アプリ。
- Non-scope: 社内工数サービスへの自動入力。

## Background

### Origin

この作業は `flake-my-emacs` のgit logにあった `org-timeblock` から始まった。元のEmacsパッケージは、org-agenda/GCal由来の予定を工数候補として扱い、確定、カテゴリ割当、SQLite保存、カテゴリ別集計を行うようなものだった。

参考になった `flake-my-emacs` commits:

- `780c09f org-timeblock: サイドバー工数管理パッケージを追加`
- `3d4d096 fix: vterm C-c プレフィックス競合と org-timeblock face 形式を修正`
- `b2d7f83 feat(org-timeblock): add date-specifiable clock-in/out functions`

今回はEmacs Lispとして復活させるのではなく、独立したローカルアプリケーションとして作る。

### User Pain

社内工数サービスへの入力は手動前提。面倒なのは、その前段の計算と判断。

- 何時間その作業をしたかを求める
- その作業がどの工数カテゴリに該当するかを決める
- 工数カテゴリごとの合計時間を出す
- 欠席、スキップ、未分類、除外、端数、短い空白、大きい空白を扱う

目的は、手入力前の集計と判断をできるだけ楽にすること。

### Product Decisions

- ローカルのみで使う。
- Clojureで実装する。
- 共通バックエンドを持ち、WebとTUIを用意する。
- SQLiteに保存する。
- org-mode依存にはしない。
- 内部フォーマットは人間が直接編集しない前提。
- Calendar syncはMVP外。
- Calendar等の外部ソースは、後でplugin-likeなinterfaceとして追加する。
- Google Calendar用のテストカレンダーがないので、現時点では実GCal連携を実装しない。
- 予定タイトルによる自動確定は残す。
- 自動確定後の誤りは変更操作で直す。
- 確定ログはsnapshot。後からsource calendarが変わっても自動で上書きしない。
- 丸めは現状15分単位。
- 端数や短い空白は `other` カテゴリへ寄せる。
- 未分類や大きい空白は警告として残し、`other` に silently hide しない。
- Docker Composeは残してよいが、Docker daemonがなかったため主経路は `nix run`。

### Non-Goals For Current MVP

- 社内工数サービスへの自動入力
- 実Google Calendar認証
- cron/ical2org互換
- org-mode直接編集
- SQLiteや内部ファイルの手編集運用
- 「起動しただけ」のE2E

## Current Implementation

### Domain

Implemented:

- Candidate event to work log conversion
- Title mapping based auto-confirm/exclude
- Unknown title to `uncategorized`
- Assign category
- Exclude work log
- Change range
- Source snapshot/stale detection helper
- Day summary
- 15-minute rounding
- Rounding residual to `other`
- Short gap to `other`
- Large gap warning
- Uncategorized warning

Key files:

- `src/worklog_timeblock/domain/worklog.clj`
- `src/worklog_timeblock/domain/summary.clj`
- `test/worklog_timeblock/domain/worklog_test.clj`
- `test/worklog_timeblock/domain/summary_test.clj`

### Persistence

Implemented:

- SQLite datasource
- Initial migration SQL
- Category CRUD-ish upsert/list helpers
- Title mapping upsert/list helpers
- Work log insert/upsert/update/read helpers
- Source event uniqueness by `(source_id, external_id)`

Key files:

- `resources/migrations/001_initial.up.sql`
- `src/worklog_timeblock/db/core.clj`
- `src/worklog_timeblock/db/migration.clj`
- `test/worklog_timeblock/db/core_test.clj`

### Plugin Boundary

Implemented:

- `EventSource` protocol
- Local EDN/resource/file event source for tests

Key files:

- `src/worklog_timeblock/plugin/protocol.clj`
- `src/worklog_timeblock/plugin/local.clj`
- `resources/fixtures/local-events.edn`
- `test/worklog_timeblock/plugin/local_test.clj`

### API

Implemented:

- `GET /health`
- `POST /api/candidates/import`
- `GET /api/days/:date`
- `GET /api/days/:date/summary`
- `PATCH /api/worklogs/:id`
- JSON validation for work-log update state, category, and time range
- 400 response for invalid edits
- 404 response for missing work logs
- Form routes for the Web UI:
  - `POST /worklogs/:id/assign-category`
  - `POST /worklogs/:id/range`
  - `POST /worklogs/:id/exclude`
- HTML routes via same Ring app

Key files:

- `src/worklog_timeblock/api/routes.clj`
- `src/worklog_timeblock/api/server.clj`
- `test/worklog_timeblock/api_e2e/routes_test.clj`

### Web

Implemented:

- Home page
- Day page
- Full-viewport day workspace
- Timeline pane
- Summary pane
- Work log correction controls:
  - category assignment
  - time range edit
  - exclusion
- Summary table
- Manual-entry output block
- Warnings

Key files:

- `src/worklog_timeblock/web/pages.clj`
- `test/worklog_timeblock/web_e2e/pages_test.clj`

### TUI

Implemented:

- Display-focused dashboard
- Date, timeline, category totals, warnings
- Width-aware rendering
- Narrow output compaction
- No interactive editing yet

Key files:

- `src/worklog_timeblock/tui/main.clj`
- `test/worklog_timeblock/tui_e2e/main_test.clj`

Important fixed bug:

- zellij viewport E2E found this narrow-width visual bug:

```text
09:00-09:15  confirmedBuild
```

Expected/fixed narrow viewport:

```text
worklog-timeblock  2026-07-06

Timeline
09:00-09:15 confirmed Build

Category totals
dev 0.25h
```

`confirmedBuild` is now an explicit NG token in zellij E2E.

### Nix / Devenv

Implemented:

- `nix run .` runs backend.
- `nix run .#backend` runs backend.
- `nix run .#tui` runs TUI.
- `flake.nix` uses `flake-utils`.
- TUI runner exports `COLUMNS` from `tput cols` when stdout is a TTY.
- `devenv.nix` defines:
  - `test`
  - `e2e`
  - `e2e-zellij`
  - `e2e-all`
  - `lint`
  - `run-backend`
  - `run-tui`

Key files:

- `flake.nix`
- `devenv.nix`
- `deps.edn`

## E2E And Verification Policy

The current testing goal was:

```text
全E2Eテストにおいて、標準が想定通りになるまで修正と合わせてループ
```

Meaning:

- Do not only run tests.
- Inspect failures.
- If the standard is wrong, make it explicit.
- If implementation is wrong, fix implementation.
- Do not drop hard cases to get green.
- Do not accept "it launched" as E2E.

### Normal E2E

Command:

```sh
devenv shell e2e
```

Last verified:

- 3 tests
- 61 assertions
- 0 failures

Covered:

- API import and summary
- API category/range/exclude updates
- API invalid range, unknown category, and unknown worklog behavior
- Web full-viewport day workspace rendering
- Web category/range/exclude forms
- Web summary updates after edits
- TUI render function

### Full E2E Gate

Command:

```sh
devenv shell e2e-all
```

This runs:

1. `clojure -M:e2e`
2. `scripts/zellij-tui-e2e.sh`

Last verified:

- Clojure E2E: 3 tests / 61 assertions / 0 failures
- zellij E2E: 8 cases / 198 assertions / 0 failures

### Zellij TUI E2E

Script:

```sh
scripts/zellij-tui-e2e.sh
```

Default session:

```sh
wz-10
```

Override:

```sh
WORKLOG_ZELLIJ_SESSION=wz-09 devenv shell e2e-zellij
```

Behavior:

- Creates temporary `wtb-e2e-*` tabs in the selected zellij session.
- Creates floating panes of specified width/height.
- Runs `nix run --no-warn-dirty .#tui`.
- Captures `viewport.dump.txt` and `full.dump.txt`.
- Asserts expected visible tokens and unexpected NG tokens.
- Writes artifact under `e2e-artifacts/zellij-tui-layout/<timestamp>/`.
- Closes temporary tabs.

Latest successful artifact:

```text
e2e-artifacts/zellij-tui-layout/20260717-144801/index.md
```

`e2e-artifacts/` is ignored by git and intentionally left as local evidence.

### Required Verification Before Claiming Completion

Run:

```sh
devenv shell e2e-all
devenv shell test
devenv shell lint
nix flake check
git diff --check --cached
git diff --check
zellij --session wz-10 action list-tabs
```

Expected zellij cleanup state:

```text
TAB_ID  POSITION  NAME
0  0  Tab #1
```

## Current Git State Expectations

At the time this file was updated:

- Initial repo files are staged.
- `COMPACTION_PLAN.md` is staged.
- `scripts/zellij-tui-e2e.sh` is staged and executable.
- API/Web edit-workspace changes are staged.
- `e2e-artifacts/` is ignored and not staged.
- No commit has been made.

Start any resumed session with:

```sh
cd /Users/shuto-vanilla/Documents/Codex/2026-07-06-flake-my-emacs-git-log-org/worklog-timeblock
git status --short --branch
zellij --session wz-10 action list-tabs
find e2e-artifacts/zellij-tui-layout -maxdepth 2 -type f -name index.md -print | sort
```

## Roadmap

Do not jump to calendar sync first. The highest-value next step is app-side editing.

### Phase 0: Preserve Current Green Baseline

Goal:

- Keep all current tests green.
- Keep `nix run` working.
- Keep zellij viewport expectations strict.

Done when:

- `devenv shell e2e-all` passes.
- `devenv shell test` passes.
- `devenv shell lint` passes.
- `nix flake check` passes.
- No `wtb-e2e-*` zellij tabs remain.

### Phase 1: Web/API Editing For Existing Work Logs

Goal:

- Make current imported/seeded work logs correctable from the app.

Status:

- Implemented on 2026-07-07.

Implement:

- API endpoint or PATCH semantics for assigning category. Done.
- API endpoint or PATCH semantics for excluding a log. Done.
- API endpoint or PATCH semantics for editing time range. Done.
- Validation for invalid ranges. Done.
- Clear 404/400-ish behavior for missing or invalid edits. Done.
- Web controls on day page. Done:
  - category selector,
  - exclude action,
  - range edit form,
  - visible status or warning feedback.
- Summary refresh after edits. Done.

Test requirements:

- API E2E verifies state changes.
- API E2E verifies summary changes after each edit.
- Web E2E verifies controls and updated rendered state.
- Negative tests for invalid range and unknown worklog id.

Done when:

- A user can open a day, fix category/exclude/range, and see totals update without DB edits.

### Phase 2: Category And Mapping Management

Goal:

- Make name-based auto-confirmation maintainable through the app.

Implement:

- Category create/update/delete or deactivate.
- Title mapping create/update/delete.
- Show mapping source for auto-confirmed logs.
- Action to create a title mapping from a corrected work log.
- Explicit reapply behavior if existing snapshots should be updated.

Test requirements:

- Unknown title remains uncategorized before mapping.
- New mapping auto-confirms future imports.
- Existing confirmed snapshots do not silently change.

Done when:

- The user can configure auto-confirmation without direct DB edits.

### Phase 3: Manual Entry Output

Goal:

- Produce a reliable source of truth for manual company worklog entry.

Status:

- Partially implemented on 2026-07-07. The Web day page has a manual-entry output block with category name and hour totals. CSV/TSV export and richer `other` detail separation remain future work.

Implement:

- Copy-friendly summary table. Partially done on the Web day page.
- Stable category ordering.
- Optional CSV/TSV export.
- Separate visibility for:
  - confirmed category totals,
  - `other` from rounding residual,
  - `other` from short gaps,
  - warnings requiring manual attention.

Test requirements:

- Export output exactly matches expected totals.
- `other` details remain visible.
- Uncategorized and large gap warnings remain visible.

Done when:

- The user can use one screen/output to manually enter company worklog totals.

### Phase 4: TUI Interaction

Goal:

- Low-friction keyboard correction.

Implement after Phase 1/2 semantics are stable:

- Select work log.
- Assign category.
- Exclude.
- Edit range.
- Create mapping from corrected log.
- Re-render updated totals.

Test requirements:

- Render/unit tests.
- zellij viewport tests if visible output changes.
- zellij input-driving tests only when interaction semantics are stable enough.

Done when:

- Common corrections can be done from TUI without switching to Web.

### Phase 5: Calendar Source Plugins

Goal:

- Replace local fixture imports with real calendar source imports.

Implement:

- Provider configuration model.
- GCal or CalDAV source plugin.
- Auth/token storage suitable for local-only app.
- Sync/import command.
- Source update/diff warnings.

Test requirements:

- Use local fixture/server tests, not a real personal calendar.
- Confirmed snapshots do not silently mutate after source changes.
- Source-updated warnings are visible.

Done when:

- Calendar events can be imported without cron/ical2org while preserving local confirmation semantics.

### Phase 6: Hardening

Goal:

- Make it reliable enough for daily use.

Implement:

- Better API errors.
- Migration version tracking.
- Backup/export of SQLite data.
- More time edge cases:
  - overlaps,
  - day crossing,
  - DST,
  - timezone mismatches,
  - duplicated source events.
- Optional Docker Compose runtime verification if Docker becomes available.
- CI subset that excludes local zellij E2E.

Done when:

- Failure modes are visible and recoverable.
- Local data is not easy to corrupt through normal operations.

## Known Remaining Work

Not implemented yet:

- Google Calendar / iCal / CalDAV real sync
- Plugin configuration, auth, token handling, incremental sync
- Category and title mapping management UI
- Interactive TUI operations
- CSV/TSV/export
- Weekly/monthly/period summaries
- Overlap handling
- Day-crossing events
- DST and timezone edge cases
- Comprehensive API error response design beyond current 400/404 work-log edit errors
- Production-grade migrations
- Docker Compose runtime verification
- CI for zellij E2E

## Handoff Instructions

If another session takes over:

1. Read this file first.
2. Inspect current git state.
3. Preserve staged files.
4. Do not `git reset` or discard the initial staged repo.
5. Do not remove zellij E2E because it is local-only.
6. If TUI output changes, update both:
   - `test/worklog_timeblock/tui_e2e/main_test.clj`
   - `scripts/zellij-tui-e2e.sh`
7. If a zellij E2E case fails:
   - inspect latest `index.md`,
   - inspect `assertions.log`,
   - inspect `viewport.dump.txt`,
   - inspect `full.dump.txt`,
   - fix implementation or update the explicit standard,
   - do not hide the failed case.

Useful reads:

```sh
sed -n '1,260p' COMPACTION_PLAN.md
sed -n '1,220p' README.md
sed -n '1,320p' scripts/zellij-tui-e2e.sh
sed -n '1,180p' src/worklog_timeblock/tui/main.clj
sed -n '1,180p' src/worklog_timeblock/api/routes.clj
```

Useful verification:

```sh
devenv shell e2e-all
devenv shell test
devenv shell lint
nix flake check
git diff --check --cached
git diff --check
zellij --session wz-10 action list-tabs
```
