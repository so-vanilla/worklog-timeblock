# Compaction Plan

このファイルは、会話コンテキストが圧縮された後、または別セッションへ引き継ぐための背景付き計画書です。上から順に読めば、なぜこのアプリを作っているか、何が実装済みか、次に何をすべきか、どの検証を通すべきかが分かる粒度にしています。

## Repository

- Path: `/Users/shuto-vanilla/Documents/Codex/2026-07-06-flake-my-emacs-git-log-org/worklog-timeblock`
- Status: GitHub `so-vanilla/worklog-timeblock` の `main` に初回版はpush済み。P0入力対応まで実装済み。
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
- `POST /api/categories`
- `POST /api/days/:date/worklogs`
- JSON validation for work-log update state, category, and time range
- JSON validation for category creation and manual work-log creation
- 400 response for invalid edits, duplicate categories, unknown categories, and invalid manual ranges
- 404 response for missing work logs
- Form routes for the Web UI:
  - `POST /days`
  - `POST /categories`
  - `POST /days/:date/worklogs`
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
- Empty-DB date picker
- Category creation form
- Manual work-log creation form
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

- 5 tests
- 109 assertions
- 0 failures

Covered:

- API import and summary
- API category creation and duplicate rejection
- API manual work-log creation from an empty DB
- API category/range/exclude updates
- API invalid range, unknown category, and unknown worklog behavior without mutation
- Web empty-DB date/category/work-log form flow
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

- Clojure E2E: 5 tests / 109 assertions / 0 failures
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

Recent successful artifact example:

```text
e2e-artifacts/zellij-tui-layout/20260719-162239/index.md
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

- Initial repo was committed and pushed to `origin/main`.
- P0 input capability changes are expected to be part of `main` after the final gate/commit step.
- If the worktree has diffs, inspect them before assuming they belong to the P0 input work.
- `e2e-artifacts/` is ignored and not staged.

Start any resumed session with:

```sh
cd /Users/shuto-vanilla/Documents/Codex/2026-07-06-flake-my-emacs-git-log-org/worklog-timeblock
git status --short --branch
zellij --session wz-10 action list-tabs
find e2e-artifacts/zellij-tui-layout -maxdepth 2 -type f -name index.md -print | sort
```

## Roadmap

Do not jump to calendar sync first. The highest-value next step remains app-side category/title-mapping management.

### Approved Continuous Goal Sequence

The user approved continuing through all listed goals without waiting for
another instruction if each goal's gate passes. Commit and push after each goal.

- Goal 1: category IDs become internal auto-increment integers; Web no longer
  asks for category ID; parent/child categories, ordering, and assignability are
  enforced.
- Goal 2: import source storage and iCal file/url import are added without
  allowing re-fetch to silently mutate confirmed snapshots.
- Goal 3: day workspace becomes a compact one-day timeline plus input, totals,
  and attention queue. The timeline should be about one third of full-width UI,
  not the dominant surface.

Goal 2 implementation status:

- Added `import_sources`, `import_runs`, `source_events`.
- Added iCal file/url adapter and Web/API import source creation/manual fetch.
- Added periodic due-fetch loop in the backend.
- Added snapshot non-overwrite behavior on re-fetch.
- Current local validation before Goal 2 commit:
  - `devenv shell e2e-all`: Clojure E2E 10 tests / 182 assertions, zellij
    8 cases / 220 assertions / 0 failures.
  - `devenv shell test`: 22 tests / 320 assertions / 0 failures.
  - `devenv shell lint`: errors 0 / warnings 0.
  - `nix flake check`: success.

Goal 3 implementation status:

- Added three-pane day workspace. Timeline is about one third of desktop width.
- Added snapped drag selection, manual time edit highlight sync, and draft
  duration/category preview.
- Added confirmed timeline blocks and imported candidate blocks.
- Added source candidate right-click menu plus attention queue confirm/exclude
  forms.
- Added overlap fallback stripe and blocked overlapping manual draft submit.
- Added `devenv shell e2e-browser`; `e2e-all` now runs Clojure E2E, browser
  E2E, then zellij E2E.
- Current local validation before Goal 3 commit:
  - `devenv shell e2e-browser`: 6 cases / 32 assertions / 0 failures.
  - `devenv shell e2e-all`: Clojure E2E 10 tests / 197 assertions, browser
    6 cases / 32 assertions, zellij 8 cases / 220 assertions / 0 failures.
  - `devenv shell test`: 22 tests / 335 assertions / 0 failures.
  - `devenv shell lint`: errors 0 / warnings 0.
  - `nix flake check`: success.

Detailed done criteria live in `PLAN.md`.

### Approved Continuous Goal Sequence: 2026-07-19 Additions

The user approved the following sequence with `go`. Continue through all listed
goals without waiting for another instruction if each goal's gate passes. Commit
and push after each goal.

Implementation coordination:

- Product/UX: workflow friction, visual grouping, day workspace shape.
- Backend/Data: persistent concepts and API contracts.
- QA/E2E: test-first coverage, negative cases, quantitative gates.
- Use sequential implementation in the main session unless file ownership can
  be split cleanly; these goals mostly share `pages.clj`, `routes.clj`, and
  `scripts/browser-e2e.js`.

Target layout:

```text
--------------------------------------------------------------------------------
| < Prev | [ YYYY-MM-DD ] | GOTO | TODAY | Next >          Days | Import sources |
--------------------------------------------------------------------------------
| Timeline              | Entry / Edit                         | Summary         |
| about 1/3 width       | selected work log editor              | attendance      |
| confirmed blocks      | add draft form                        | breaks          |
| imported candidates   | imported candidate queue              | category totals |
| break bands           | selected row highlighted              | categories      |
| warning bubble        | compact editable log list             | warnings        |
--------------------------------------------------------------------------------
```

Goal 1:

- Add day navigation: Prev, Next, TODAY, and date input plus GOTO.
- Make category groups visually obvious: root rows, indented children, stable
  root-derived colors.
- Remove parent-name repetition from child category labels in the category list.
- Keep category order persisted through existing `position` values.
- Render category totals in category-list order, with parent subtotal rows.
- Keep parents with children non-assignable.
- Remove Manual Entry.
- Fix central edit/delete row overlap.
- Add at least 20 Clojure/Web E2E assertions and at least 8 browser E2E
  assertions.

Goal 1 implementation status:

- Added day navigation with Prev, Next, TODAY, and GOTO.
- Changed category selects to root optgroups with child-only option labels.
- Changed category management to root rows and indented child rows with stable
  root-derived colors.
- Added ordered summary rows with parent subtotals for active children.
- Removed Manual Entry from the day page.
- Reworked central work-log edit rows into stacked action cards and added
  browser horizontal-overflow checks.
- Current local validation before Goal 1 commit:
  - `devenv shell e2e-all`: Clojure E2E 10 tests / 220 assertions, browser
    E2E 6 cases / 51 assertions, zellij 8 cases / 220 assertions / 0 failures.
  - `devenv shell test`: 22 tests / 360 assertions / 0 failures.
  - `devenv shell lint`: errors 0 / warnings 0.
  - `nix flake check`: success.
  - `git diff --check --cached`: success.
  - `git diff --check`: success.
  - `zellij --session wz-10 action list-tabs`: only `Tab #1`.

Goal 2:

- Confirmed timeline block click selects the matching work log.
- The matching central editor scrolls near the vertical middle of the entry
  pane and is highlighted.
- Timeline click selection does not create a new draft; empty-space drag still
  creates a draft.
- Add at least 10 browser E2E assertions.

Goal 2 implementation status:

- Confirmed timeline blocks are clickable and no longer start draft selection.
- Clicking a confirmed block selects both the timeline block and matching
  central work-log row.
- The entry pane stores `data-selected-worklog-id`.
- The selected row is scrolled near the vertical middle of the entry pane when
  there is enough content to make that possible.
- Browser E2E now seeds a longer editable list and validates selection,
  highlighting, scroll focus, and draft-field stability.
- Current local validation before Goal 2 commit:
  - `devenv shell e2e-all`: Clojure E2E 10 tests / 220 assertions, browser
    E2E 7 cases / 66 assertions, zellij 8 cases / 220 assertions / 0 failures.
  - `devenv shell test`: 22 tests / 360 assertions / 0 failures.
  - `devenv shell lint`: errors 0 / warnings 0.
  - `nix flake check`: success.
  - `git diff --check --cached`: success.
  - `git diff --check`: success.
  - `zellij --session wz-10 action list-tabs`: only `Tab #1`.

Goal 3:

- Add day-level attendance, separate from work logs.
- Add current-time and manual-time clock-in/clock-out controls.
- Add separate break persistence and daily break rules.
- Breaks do not count toward work effort.
- Breaks can later be converted/changed into work-log effort.
- Summary shows attendance span, confirmed work, break total, and unallocated
  time.
- Breaks do not produce false large-gap warnings.
- Add at least 30 DB/API/domain assertions and at least 10 Web/browser
  assertions.

Goal 3 implementation status:

- Added `day_attendance`, `break_rules`, and `breaks`.
- Added JSON APIs and form routes for attendance, daily break rules, one-off
  breaks, break range edit, and break-to-work conversion.
- Day reads materialize enabled daily break rules into editable day breaks.
- Summary reports attendance span, confirmed work, break total, and
  unallocated minutes.
- Break-covered gaps are excluded from large-gap warnings.
- Web timeline renders break bands.
- Right pane exposes current-time and manual attendance controls, daily break
  setup, one-off breaks, range edits, and conversion to categorized work.
- Current local validation before Goal 3 commit:
  - `devenv shell e2e-all`: Clojure E2E 12 tests / 278 assertions, browser
    E2E 8 cases / 79 assertions, zellij 8 cases / 220 assertions / 0 failures.
  - `devenv shell test`: 26 tests / 436 assertions / 0 failures.
  - `devenv shell lint`: errors 0 / warnings 0.
  - `nix flake check`: success.
  - `git diff --check --cached`: success.
  - `git diff --check`: success.
  - `zellij --session wz-10 action list-tabs`: only `Tab #1`.

Goal 4:

- Add confirmed-block move by center drag.
- Add top/bottom edge resize.
- Add adjacent boundary adjustment in one transaction.
- Add Shift edge shrink without moving adjacent logs.
- Prevent overlap expansion and show a small warning bubble.
- Add API overlap rejection with DB-unchanged tests.
- Add at least 4 browser cases and at least 20 browser assertions.

Common gate for every goal:

```sh
devenv shell e2e-all
devenv shell test
devenv shell lint
nix flake check
git diff --check --cached
git diff --check
zellij --session wz-10 action list-tabs
```

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

### Phase 2: Empty-DB Web/API Manual Input

Goal:

- Make the app usable from an empty SQLite DB without direct DB edits or fixture imports.

Status:

- Implemented on 2026-07-19.

Implement:

- Home date picker. Done.
- Web category creation form. Done.
- Web manual work-log creation form. Done.
- API category creation. Done.
- API manual work-log creation. Done.
- Validation for duplicate category id, unknown category, invalid date, invalid range, and missing title. Done.
- Confirmed log when category is provided; uncategorized log when category is omitted. Done.

Test requirements:

- API E2E starts from empty DB and creates category and work logs.
- API E2E proves invalid manual input does not mutate the target day.
- Web E2E starts from empty DB and completes date/category/work-log input.
- Browser/DOM verification starts `nix run` against empty `data/app.db` and confirms the rendered summary.

Done when:

- A user can open the Web UI on an empty DB, create a category, add a categorized work log, and see category totals update.

### Phase 3: Category And Mapping Management

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

### Phase 4: Manual Entry Output

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

### Phase 5: TUI Interaction

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

### Phase 6: Calendar Source Plugins

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

### Phase 7: Hardening

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
- Comprehensive API error response design beyond current 400/404 edit and manual-input errors
- Production-grade migrations
- Docker Compose runtime verification
- CI for zellij E2E

## Handoff Instructions

If another session takes over:

1. Read this file first.
2. Inspect current git state.
3. Preserve unrelated local/user changes.
4. Do not `git reset` or discard work in progress.
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
