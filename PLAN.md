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
- Status: GitHub `so-vanilla/worklog-timeblock` の `main` に初回版はpush済み。P0入力対応まで実装済み。
- Git state: 作業再開時は `git status --short --branch` で最新差分を確認する。
- Runtime: `nix run` and `devenv`
- Main verification: `devenv shell e2e-all`, `devenv shell test`, `devenv shell lint`, `nix flake check`

直近の確認済みベースライン:

- `devenv shell e2e`: 5 tests / 109 assertions / 0 failures
- `devenv shell e2e-all`: Clojure E2E 5 tests / 109 assertions / 0 failures, zellij E2E 8 cases / 198 assertions / 0 failures
- `devenv shell test`: 11 tests / 149 assertions / 0 failures
- `devenv shell lint`: errors 0 / warnings 0
- `nix flake check`: success
- `git diff --check --cached && git diff --check`: success
- `zellij --session wz-10 action list-tabs`: `Tab #1` only
- Browser/DOM verification: empty `data/app.db`, `nix run . -- --host 127.0.0.1 --port 3002 --db ./data/app.db`, home/date/category/worklog/summary checks 9/9 passed.
- Recent local zellij artifact example: `e2e-artifacts/zellij-tui-layout/20260719-162239/index.md`

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
- `POST /api/categories`.
- `POST /api/days/:date/worklogs`.
- 400 for invalid work-log edits.
- 400 for duplicate categories, unknown categories, and invalid manual work-log ranges.
- 404 for missing work logs.
- Form routes:
  - `POST /days`
  - `POST /categories`
  - `POST /days/:date/worklogs`
  - `POST /worklogs/:id/assign-category`
  - `POST /worklogs/:id/range`
  - `POST /worklogs/:id/exclude`
- Full-viewport day page.
- Empty-DB home date picker.
- Category creation form.
- Manual work-log creation form.
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
- API E2E verifies empty-DB category creation and manual work-log creation.
- API E2E verifies invalid range, unknown category, and unknown worklog behavior.
- API E2E verifies invalid manual work-log requests do not mutate the day.
- Web E2E verifies full-viewport workspace and edit controls.
- Web E2E verifies empty-DB date/category/work-log form flow.
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
3. Preserve unrelated local/user changes. Do not reset or discard work in progress.
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

## Continuous Goals: Workspace, Import, Categories

Status: Approved for continuous execution. Do not stop after Goal 1 or Goal 2 if
the gate passes; commit/push each goal and continue until all listed goals are
done.

## Approved Fix Goals: 2026-07-19 UI Corrections

Status: Done.

Coordination model:

- Product/UX owns the interaction shape: timeline cursor feedback, attendance
  visibility, central editor layout, Settings split, and right-pane ordering.
- Backend/Data owns category rename/delete semantics and route contracts.
- QA/E2E owns coverage for DOM structure, browser interaction, persistence, and
  negative cases.
- Implementation is sequential in the main session because `pages.clj`,
  `routes.clj`, and browser E2E are shared by nearly every item.

Goal A: timeline and central editor corrections.

- Confirmed work-log top/bottom edge hover shows an `ns-resize` cursor; the
  middle drag area remains visually distinct from edge resize.
- Day attendance is visible on the timeline itself as a non-work-log span or
  marker.
- Central confirmed work-log rows remove the category `Set` button and bottom
  category label.
- Category select changes auto-submit and always represent the persisted value.
- `Exclude` is placed on the right side of the `Range` row.
- Browser E2E verifies cursor state, attendance timeline rendering, auto-submit,
  and non-overlap layout.

Goal B: Settings split and right pane order.

- `/settings` is added as the page for settings-like concerns.
- Daily break rule creation/listing moves from the day right pane to Settings.
- The day right pane top-level order is Attendance, Category totals, Categories.
- Today's concrete breaks remain editable as part of Attendance because they are
  day operations, not global settings.
- Web E2E verifies Settings navigation, daily-break form location, and right
  pane order.

Goal C: category rename/delete.

- Category IDs remain internal and hidden from the frontend.
- Category settings rows add rename and delete controls.
- Rename rejects blank names, unknown IDs, and duplicate active sibling names.
- Delete hard-deletes categories with no active children and no assignments.
- Delete deactivates assigned categories so historical work logs keep their
  category name, while selectors and category settings hide the inactive row.
- Delete rejects parents with active children.
- DB/API/Web E2E verify rename, hard delete, soft delete, delete rejection, and
  hidden inactive rows.

Gate before final report:

```sh
devenv shell e2e-all
devenv shell test
devenv shell lint
nix flake check
git diff --check --cached
git diff --check
zellij --session wz-10 action list-tabs
```

Implementation status:

- Goal A done: confirmed block edge hover uses resize cursor, attendance is
  visible on the timeline, work-log category changes auto-submit, and
  Range/Exclude share one row.
- Goal B done: `/settings` owns daily break rule creation/listing, day right
  pane top-level order is Attendance, Category totals, Categories, and today's
  materialized breaks remain editable inside Attendance.
- Goal C done: category settings have rename/delete; delete hard-removes
  unreferenced leaves, soft-deactivates assigned categories, blocks parents with
  active children, and keeps historical summary names.

Verification:

- `devenv shell e2e-all`: Clojure E2E 15 tests / 358 assertions, browser E2E
  16 cases / 124 assertions, zellij E2E 8 cases / 220 assertions / 0 failures.
- `devenv shell test`: 29 tests / 529 assertions / 0 failures.
- `devenv shell lint`: errors 0 / warnings 0.
- `nix flake check`: success.
- `git diff --check --cached && git diff --check`: success.
- `zellij --session wz-10 action list-tabs`: only `Tab #1`.

Current layout decision:

- The calendar/timeline is not the main surface. On a full-width screen it should
  take about one third of the workspace.
- The other two thirds are for input/editing, live category totals, and the
  attention queue for uncategorized, overlapping, or source-updated items.
- If the screen is narrow, prefer input/editing and summary before the compact
  timeline.

Workspace sketch:

```text
--------------------------------------------------------------------------------
| 2026-07-06  < Prev | Today | Next >                         Settings | Import |
--------------------------------------------------------------------------------
| 1-day timeline       | Input / edit                         | Totals          |
| 00:00                | title                                | Development 2h  |
| 08:00  imported      | start 09:00  end 10:00               | Meeting     1h  |
| 09:00  confirmed     | category [leaf category dropdown]    | Other    0.25h  |
| 10:00  selected      | Save / Exclude / Confirm candidate   |-----------------|
| 24:00                |                                      | Attention queue |
|                      | overlapping/uncategorized details    | pending items   |
--------------------------------------------------------------------------------
     about 1/3                     about 1/3                         about 1/3
```

### Goal 1: Category Model And Category Workspace

Done when:

- Category IDs are internal auto-increment integers.
- Category IDs are not visible as user-facing fields in the Web UI.
- Existing string keys such as `dev` and `other` are preserved only as
  `legacy_key` migration/fixture compatibility.
- A category can have zero or one parent.
- Category depth is limited to two levels.
- Root categories can be reordered globally.
- Child categories can be reordered inside the parent group.
- A category with one or more active children is not assignable to work logs or
  title mappings.
- Parent categories with active children are not included in manual-entry
  category totals.
- Web category creation exposes name and optional parent selector, not a manual
  category ID field.
- Existing baseline is not reduced:
  - Clojure E2E at least 5 tests / 109 assertions.
  - Full test suite at least 11 tests / 149 assertions.
  - zellij E2E at least 8 cases / 198 assertions.
- Add at least 40 assertions across DB/domain/API/Web tests.
- Full gate passes and the result is committed and pushed.

### Goal 2: Source Events And iCal Import

Status: Implemented locally; gate passed before commit.

Done when:

- `candidate` from adapters, persisted `source_events`, and user-owned
  `work_logs` snapshots are separate concepts.
- Re-fetching an import source updates `source_events` but does not silently
  overwrite confirmed or excluded work-log snapshots.
- `import_sources` can hold zero or more iCal file/url configurations.
- Manual fetch exists for configured sources.
- Backend-owned periodic fetch exists for enabled sources while the backend is
  running.
- iCal parsing covers file and URL sources through a common source interface.
- Fixture tests include at least 8 ICS cases, including duplicate UID, updated
  UID, cancelled event, malformed input, timezone, recurrence, exception date,
  and day crossing or clipping.
- Fetch behavior is verified across at least 2 cycles.
- Add at least 60 assertions beyond Goal 1.
- Full gate passes and the result is committed and pushed.

Implemented:

- Added `import_sources`, `import_runs`, and `source_events`.
- Added iCal file/url adapter through the common `EventSource` protocol.
- Added manual fetch API and Web settings page for iCal import sources.
- Added backend-owned periodic fetch loop for enabled sources.
- Kept `/api/candidates/import` as a compatibility entrypoint backed by
  `source_events`.
- Re-fetch updates `source_events`; existing `work_logs` snapshots are not
  overwritten.
- Day APIs include `source-events`; summaries include `source-updated`
  warnings.

Validation:

- `devenv shell e2e-all`: Clojure E2E 10 tests / 182 assertions, zellij E2E
  8 cases / 220 assertions / 0 failures.
- `devenv shell test`: 22 tests / 320 assertions / 0 failures.
- `devenv shell lint`: errors 0 / warnings 0.
- `nix flake check`: success.
- iCal fixtures cover basic timed event, UTC, TZID, duplicate UID/update,
  cancelled event, outside date range, folded line, date-only skip,
  recurrence with EXDATE, day crossing, and malformed input.

### Goal 3: Compact Day Timeline Workspace

Status: Implemented locally; gate passed before commit.

Done when:

- Day view has a compact one-day vertical timeline using about one third of a
  full-width workspace.
- Drag selection on the timeline snaps to the configured quantum and fills the
  right-side input form start/end fields.
- Keyboard/manual start/end edits also highlight the selected interval on the
  timeline.
- Confirmed logs render as occupied blocks.
- Imported source candidates render as lighter candidate blocks and do not
  contribute to manual-entry totals until confirmed.
- Right-clicking an imported candidate opens a confirm/exclude action surface.
- Dragging over an imported candidate starts a new manual draft instead of
  opening the candidate action surface.
- Confirmed/imported overlap has a visible fallback: confirmed is primary, and
  covered candidates remain reachable from a badge, stripe, or attention queue.
- Creating a draft that overlaps confirmed work is visibly blocked or requires
  an explicit future override; silent double-counting is not allowed.
- Browser/DOM E2E has at least 4 cases covering drag, form sync, candidate
  context action, overlap fallback, and live summary update.
- Full gate passes and the result is committed and pushed.

Implemented:

- Replaced the day page with a three-pane workspace:
  - left one-day timeline at about one third of desktop width,
  - center input, imported-candidate queue, and editable work-log list,
  - right category totals, category management, manual-entry output, warnings.
- Timeline supports snapped mouse drag selection.
- Manual start/end edits update the same visible selection range.
- Confirmed logs render as occupied blocks.
- Imported source events render as lighter candidate blocks.
- Right-click on imported blocks opens a confirm/exclude surface.
- Dragging over imported blocks creates a manual draft and does not open the
  candidate menu.
- Confirmed/imported overlaps render with a fallback stripe and covered
  candidate entry in the attention queue.
- Drafts overlapping confirmed work disable Add and show an overlap warning.
- Candidate confirm/exclude forms update the source-backed snapshot.
- Added Playwright browser E2E through `devenv shell e2e-browser`; `e2e-all`
  now runs Clojure E2E, browser E2E, and zellij E2E.

Validation:

- `devenv shell e2e-browser`: 6 cases / 32 assertions / 0 failures.
- `devenv shell e2e-all`: Clojure E2E 10 tests / 197 assertions, browser E2E
  6 cases / 32 assertions, zellij E2E 8 cases / 220 assertions / 0 failures.
- `devenv shell test`: 22 tests / 335 assertions / 0 failures.
- `devenv shell lint`: errors 0 / warnings 0.
- `nix flake check`: success.

## Approved Continuous Goals: Navigation, Editing, Attendance, And Mouse Control

Status: Approved for continuous execution. Do not stop after Goal 1, Goal 2, or
Goal 3 if the gate passes; commit/push each goal and continue until all listed
goals are done.

Team model:

- Product/UX owns friction, daily workflow order, and screen layout.
- Backend/Data owns persistent concepts and API contracts.
- QA/E2E owns test-first coverage, negative cases, and quantitative gates.
- Implementation is sequential in this session because `pages.clj`,
  `routes.clj`, and browser E2E are shared across most goals.

Current target layout:

```text
--------------------------------------------------------------------------------
| < Prev | [ YYYY-MM-DD ] | GOTO | TODAY | Next >          Days | Import sources |
--------------------------------------------------------------------------------
| Timeline              | Entry / Edit                         | Summary         |
| about 1/3 width       | selected work log editor              | attendance      |
| confirmed blocks      | add draft form                        | breaks          |
| imported candidates   | imported candidate queue              | category totals |
| break bands           | compact editable log list             | categories      |
| warning bubble        | selected row highlighted              | warnings        |
--------------------------------------------------------------------------------
```

### Goal 1: Navigation, Category Visibility, And Summary Cleanup

Status: Implemented and pushed when commit step completes.

Done when:

- The day header has Prev, Next, TODAY, and a date input plus GOTO form.
- Child category labels no longer repeat their parent name in category
  management rows.
- Child categories are indented under their root category.
- Root category groups are visually grouped by a stable color derived from the
  root category.
- Category order persists across restart using the existing `position` column.
- Category totals are rendered in the same root/child order as the category
  list.
- Parent category rows are visible in totals and display the subtotal of their
  active children.
- Parent categories with children remain non-assignable.
- Manual Entry output is removed from the day page.
- The central edit/delete list no longer overlaps at desktop or narrow widths.
- Add at least 20 Clojure/Web E2E assertions and at least 8 browser E2E
  assertions.
- Full gate passes and the result is committed and pushed.

Implemented:

- Added day navigation with Prev, Next, TODAY, and date input plus GOTO.
- Changed category selects to root optgroups with child option names.
- Changed category management to root rows and indented child rows with stable
  root-derived group colors.
- Added ordered category summary rows with parent rows showing child subtotals.
- Removed the Manual Entry output block from the day page.
- Changed work-log edit rows from dense multi-column rows to compact stacked
  action cards to avoid overlapping controls.
- Added browser layout checks for edit row horizontal overflow.

Validation:

- `devenv shell e2e-all`: Clojure E2E 10 tests / 220 assertions, browser E2E
  6 cases / 51 assertions, zellij E2E 8 cases / 220 assertions / 0 failures.
- `devenv shell test`: 22 tests / 360 assertions / 0 failures.
- `devenv shell lint`: errors 0 / warnings 0.
- `nix flake check`: success.
- `git diff --check --cached`: success.
- `git diff --check`: success.
- `zellij --session wz-10 action list-tabs`: only `Tab #1`.

### Goal 2: Confirmed Block Selection And Edit Focus

Status: Implemented and pushed when commit step completes.

Done when:

- Clicking a confirmed block in the left timeline selects the matching work log.
- The matching central work-log editor is scrolled close to the vertical middle
  of the entry pane.
- The selected central row is highlighted.
- Timeline block click selection does not create a new draft.
- Dragging on empty timeline space still creates a new draft.
- Browser E2E verifies selection, row highlight, scroll target, and no layout
  overlap.
- Add at least 10 browser E2E assertions.
- Full gate passes and the result is committed and pushed.

Implemented:

- Confirmed timeline blocks are clickable without starting a new draft.
- Clicking a confirmed block selects the matching timeline block and central
  work-log row.
- The entry pane stores the selected work-log id.
- The selected central row is scrolled near the vertical center of the entry
  pane when enough surrounding content exists.
- Browser E2E seeds enough work logs to make scroll/focus behavior meaningful.

Validation:

- `devenv shell e2e-all`: Clojure E2E 10 tests / 220 assertions, browser E2E
  7 cases / 66 assertions, zellij E2E 8 cases / 220 assertions / 0 failures.
- `devenv shell test`: 22 tests / 360 assertions / 0 failures.
- `devenv shell lint`: errors 0 / warnings 0.
- `nix flake check`: success.
- `git diff --check --cached`: success.
- `git diff --check`: success.
- `zellij --session wz-10 action list-tabs`: only `Tab #1`.

### Goal 3: Attendance And Breaks

Status: Implemented and pushed when commit step completes.

Done when:

- Attendance is persisted as a day-level concept, not as a work log.
- The Web UI can set clock-in and clock-out from the current time.
- The Web UI can set clock-in and clock-out from manually entered time values.
- Breaks are persisted separately from work logs.
- A default daily break can be set and displayed on each day.
- Breaks are not counted as work effort.
- A break can be changed later into a work-log range/category when needed.
- Summary displays attendance span, confirmed work total, break total, and
  unallocated time.
- Breaks do not cause false large-gap warnings.
- Add at least 30 DB/API/domain assertions and at least 10 Web/browser
  assertions.
- Full gate passes and the result is committed and pushed.

Implemented:

- Added `day_attendance`, `break_rules`, and `breaks` storage.
- Added API and form routes for attendance, daily break rules, one-off breaks,
  break range edits, and break-to-work conversion.
- Day reads materialize enabled daily break rules into editable day breaks.
- Summary now reports attendance span, confirmed work, break total, and
  unallocated minutes.
- Break-covered gaps no longer create false large-gap warnings.
- Day timeline renders break bands.
- Right pane exposes current-time clock-in/out buttons, manual attendance
  inputs, break rule creation, break range edits, and conversion to work.

Validation:

- `devenv shell e2e-all`: Clojure E2E 12 tests / 278 assertions, browser E2E
  8 cases / 79 assertions, zellij E2E 8 cases / 220 assertions / 0 failures.
- `devenv shell test`: 26 tests / 436 assertions / 0 failures.
- `devenv shell lint`: errors 0 / warnings 0.
- `nix flake check`: success.
- `git diff --check --cached`: success.
- `git diff --check`: success.
- `zellij --session wz-10 action list-tabs`: only `Tab #1`.

### Goal 4: Calendar Mouse Editing

Status: Implemented and pushed when commit step completes.

Done when:

- Dragging the middle of a confirmed block moves the work log vertically.
- Dragging the top or bottom edge changes the start or end time.
- Dragging a shared boundary between adjacent logs updates both ranges in one
  transaction.
- Holding Shift while dragging an edge shrinks the selected log without moving
  the adjacent log.
- Shift edge drag cannot expand into an overlap.
- Any drag that would create overlap shows a small warning bubble and does not
  save.
- API-side validation rejects confirmed work-log overlaps and leaves the DB
  unchanged.
- Browser E2E adds at least 4 cases and at least 20 assertions.
- Full gate passes and the result is committed and pushed.

Implemented:

- Confirmed work-log creation and update now reject overlaps with other
  confirmed logs.
- Added `POST /api/days/:date/boundary-adjustments` for adjacent work-log
  boundary changes.
- Timeline confirmed blocks support middle drag move.
- Timeline block top and bottom edges support resize.
- Adjacent edge drag moves the shared boundary and updates both logs together.
- Shift edge drag shrinks the selected block without moving its neighbor.
- Shift edge expansion and overlap moves show a small timeline warning bubble
  and do not save.
- Browser E2E verifies move, resize, boundary adjustment, Shift shrink,
  overlap warning, and Shift expansion warning.

Validation:

- `devenv shell e2e-all`: Clojure E2E 13 tests / 297 assertions, browser E2E
  12 cases / 104 assertions, zellij E2E 8 cases / 220 assertions / 0 failures.
- `devenv shell test`: 27 tests / 455 assertions / 0 failures.
- `devenv shell lint`: errors 0 / warnings 0.
- `nix flake check`: success.
- `git diff --check --cached`: success.
- `git diff --check`: success.
- `zellij --session wz-10 action list-tabs`: only `Tab #1`.

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
