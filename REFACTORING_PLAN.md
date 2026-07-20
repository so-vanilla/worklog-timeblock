# Refactoring Plan

Initial complete version tag: `v0.1.0`

This plan is for behavior-preserving cleanup after the first usable version.
Feature work is out of scope unless explicitly approved.

## External Guidance Applied

- Refactoring should keep observable behavior unchanged and proceed through small
  behavior-preserving steps with frequent testing.
  Source: https://refactoring.com/
- Existing code should get tests before risky changes. When tests are hard to add,
  first identify seams, break dependencies minimally, add tests, then refactor.
  Source: https://understandlegacycode.com/blog/key-points-of-working-effectively-with-legacy-code/
- Refactoring prompts should put instructions first, separate context from tasks,
  specify the expected output format, and avoid vague goals.
  Source: https://help.openai.com/en/articles/6654000-best-practices-for-prompt-engineering-with-the-openai-api
- Prompt iteration is expected: review the result, tighten the prompt, and rerun
  only when the output violates the requested format or scope.
  Source: https://help.openai.com/en/articles/10032626-prompt-engineering-best-practices-for-chatgpt

## Current Shape

Line-count hotspots as of `v0.1.0`:

| File | Lines | Main Issue |
|---|---:|---|
| `src/worklog_timeblock/web/pages.clj` | 1688 | CSS, HTML rendering, timeline JavaScript, calendar JavaScript, and page composition are in one namespace. |
| `src/worklog_timeblock/api/routes.clj` | 1409 | HTTP parsing, route definitions, validation, workflow orchestration, calendar state assembly, and form warning behavior are mixed. |
| `src/worklog_timeblock/db/core.clj` | 1235 | Row mapping, repository functions, settings, imports, categories, breaks, attendance, and work logs are in one namespace. |
| `scripts/browser-e2e.js` | 822 | Browser E2E covers many independent workflows in one script, so failures are harder to localize. |

Existing strengths to preserve:

- Web/API/TUI share the same backend and SQLite database.
- Current verification stack is broad: unit/domain tests, Web/API E2E, browser
  E2E, zellij TUI E2E, lint, `nix flake check`, and `nix run` smoke.
- User-facing behavior is already well covered for the day workspace, settings,
  export, import source handling, title suggestions, and Days calendars.

## Progress Log

### 2026-07-20

- Tagged the initial complete app version as `v0.1.0`.
- Added `docs/testing.md` with the full refactoring verification gate.
- Added `worklog-timeblock.refactor-characterization-test` with 58 assertions
  covering calendar date logic, day status classification, warning rendering,
  safe redirects, and key public page/API output.
- Started Phase 2 by extracting shared HTML escaping and flash-warning rendering
  from `web/pages.clj` into `web/layout.clj`.

## Should We Add Tests?

Yes. Add tests before moving production code.

The goal is not raw coverage percentage. The useful safety net is a small set of
characterization tests around outputs and behavior that users can observe:

- HTML characterization tests for the key pages:
  - `/days/:date`
  - `/`
  - `/settings`
  - Days Month/Week category breakdowns
- Pure unit tests for logic that should be extracted:
  - category tree/path labeling
  - assignable category rules
  - calendar date ranges, week start day, fiscal month start day
  - day status classification
  - warning-code to visible warning rendering
- Route/form tests for expected warning redirects instead of raw JSON errors.
- DB repository tests that lock current behavior before splitting `db/core.clj`.
- Browser E2E should remain broad, but the script should be split by workflow
  after characterization tests exist.

Minimum test gate before production refactoring:

- Add at least 25 new assertions that lock current behavior in the areas being
  moved first.
- Existing `devenv shell test`, `devenv shell e2e-all`, `devenv shell lint`,
  `nix flake check`, and `nix run` smoke must pass before the first extraction
  commit.

## Refactoring Rules

1. No user-visible behavior changes inside refactoring commits.
2. No schema changes unless the phase explicitly targets DB structure.
3. Keep existing route paths, form actions, query parameters, and response
   semantics stable.
4. Add or strengthen tests before moving a behavior.
5. One phase should have one primary ownership boundary.
6. Prefer extracting existing functions over rewriting algorithms.
7. Introduce abstraction only when it removes duplicated behavior or isolates a
   real dependency.
8. Do not introduce a UI rendering library until the current string renderer is
   split and its pain points are measurable.

## Target Module Boundaries

Proposed source layout after refactoring:

```text
src/worklog_timeblock/
  api/
    routes.clj              ; thin router assembly only
    http.clj                ; parse body, responses, redirects, warnings
    day_routes.clj          ; day workspace routes
    calendar_routes.clj     ; Days Month/Week routes
    settings_routes.clj     ; settings forms
    category_routes.clj     ; category CRUD/order routes
    import_routes.clj       ; import source routes
    export_routes.clj       ; export route
  db/
    core.clj                ; datasource and compatibility facade
    category.clj
    work_log.clj
    import_source.clj
    settings.clj
    attendance.clj
    break.clj
    source_event.clj
  domain/
    calendar.clj            ; date ranges and day status classification
    category.clj            ; category tree/path/assignability
    validation.clj          ; shared request/domain validation helpers
    export.clj
    summary.clj
    worklog.clj
  web/
    layout.clj              ; page shell, escaping, shared controls
    days.clj                ; Days Month/Week rendering
    day.clj                 ; day workspace rendering
    settings.clj            ; settings rendering
    timeline.clj            ; timeline HTML helpers
    assets.clj              ; CSS/JS strings, later optional static files
```

Non-goals:

- Do not split by "one function per file".
- Do not make a framework rewrite.
- Do not replace Reitit, next.jdbc, SQLite, or the local-only model.
- Do not change TUI behavior while splitting Web/API/DB boundaries.

## Quantitative Completion Goals

The refactoring run is complete when all of these are true:

- No single production namespace exceeds 700 lines.
- `web/pages.clj` is removed or reduced to a compatibility namespace under 150
  lines.
- `api/routes.clj` is router assembly under 300 lines.
- `db/core.clj` is datasource plus compatibility facade under 250 lines.
- Browser E2E is split into at least 4 named workflow sections or files:
  day workspace, settings/import/export, Days calendar, title suggestions.
- At least 40 new assertions are added across characterization/unit tests before
  or during the refactor.
- Full gate passes at the end:
  - `devenv shell test`
  - `devenv shell e2e-all`
  - `devenv shell lint`
  - `nix flake check`
  - `git diff --check --cached`
  - `git diff --check`
  - `nix run --no-warn-dirty . -- --db <temp-db> --host 127.0.0.1 --port <temp-port>`

## Phase Plan

### Phase 0: Baseline Lock

Goal: create a clean baseline commit for refactoring.

Tasks:

- Confirm `v0.1.0` points at the first complete app commit.
- Run and record the current full verification gate.
- Add a short `docs/testing.md` or README section describing the gate commands.

Exit criteria:

- Worktree clean.
- All existing gates pass.
- No production code changes.

### Phase 1: Characterization Tests

Goal: make the risky boundaries movable.

Tasks:

- Add page-render tests for day workspace, settings, and Days calendars.
- Add unit tests for category path labels and category assignability.
- Add unit tests for calendar range/status calculations before extracting them.
- Add route tests for form warning redirects for common validation errors.

Exit criteria:

- At least 25 new assertions.
- No production behavior changes except test-only seams if unavoidable.
- Full gate passes.

### Phase 2: Web Rendering Split

Goal: reduce `web/pages.clj` without changing HTML behavior.

Tasks:

- Move page shell, escaping, and common controls to `web/layout.clj`.
- Move Days Month/Week rendering to `web/days.clj`.
- Move day workspace/timeline rendering to `web/day.clj` and
  `web/timeline.clj`.
- Move settings rendering to `web/settings.clj`.
- Move CSS/JS strings to `web/assets.clj`.

Exit criteria:

- `web/pages.clj` is gone or under 150 lines as a facade.
- Existing HTML assertions and browser E2E pass unchanged.
- No route or form action changes.

### Phase 3: Domain Extraction

Goal: remove business rules from route and rendering namespaces.

Tasks:

- Extract calendar date range and day status classification to
  `domain/calendar.clj`.
- Extract category tree/path/assignability logic to `domain/category.clj`.
- Extract reusable validation to `domain/validation.clj`.
- Keep DB access out of domain namespaces; pass plain maps/vectors in.

Exit criteria:

- Domain tests cover extracted functions directly.
- `api/routes.clj` no longer owns calendar classification or category tree logic.
- Full gate passes.

### Phase 4: Route Split

Goal: make route ownership visible.

Tasks:

- Add `api/http.clj` for body parsing, response helpers, warning redirects, and
  safe redirect handling.
- Split day, calendar, settings, category, import, and export route handlers into
  dedicated namespaces.
- Leave `api/routes.clj` as thin Reitit assembly.

Exit criteria:

- `api/routes.clj` under 300 lines.
- Route tests still call the same public app entrypoint.
- No user-facing route changes.

### Phase 5: DB Repository Split

Goal: keep persistence APIs understandable without changing SQL behavior.

Tasks:

- Split row mappers and repository functions by table/workflow.
- Keep `db/core.clj` as datasource plus compatibility facade until callers are
  migrated.
- Move settings normalization and accessors to `db/settings.clj`.
- Move category ordering/rename/delete to `db/category.clj`.
- Move work-log and source-event functions into their own repositories.

Exit criteria:

- `db/core.clj` under 250 lines.
- Existing DB tests pass.
- New repository tests prove moved SQL behavior did not change.

### Phase 6: Browser E2E Split

Goal: make browser failures local and cheap to diagnose.

Tasks:

- Split `scripts/browser-e2e.js` by workflow or add named case grouping with
  independent setup.
- Keep one shared fixture factory for categories, work logs, settings, and import
  sources.
- Preserve all existing assertions.

Exit criteria:

- At least 4 named workflow groups.
- Assertion count is not reduced.
- Browser E2E failure output identifies the workflow and assertion.

### Phase 7: Final Cleanup

Goal: remove compatibility shims that are no longer needed.

Tasks:

- Remove unused facade functions after callers are migrated.
- Review namespaces for accidental cycles.
- Update README/PLAN/COMPACTION_PLAN with the new module structure.

Exit criteria:

- Quantitative completion goals are satisfied.
- Full verification gate passes.
- Final commit is tagged only if requested.

## Agent Team Plan

Use 2 to 3 workers. Do not assign the same file to multiple workers.

Recommended split:

| Role | Ownership | Notes |
|---|---|---|
| Behavior Guardian | Tests and fixtures | Adds characterization tests first and reviews assertion count. |
| Web Refactorer | `src/worklog_timeblock/web/*` | Splits rendering and assets after tests exist. |
| API/DB Refactorer | `src/worklog_timeblock/api/*`, `src/worklog_timeblock/db/*` | Starts only after domain extraction tests exist. |

Avoid parallel edits to `api/routes.clj`, `db/core.clj`, and `web/pages.clj`
unless the plan assigns one file to exactly one worker.

## Refactoring Prompt Template

Use this for each phase instead of a vague `/refactoring` command.

```text
You are refactoring worklog-timeblock.

## Goal
<one behavior-preserving goal, e.g. extract Days calendar rendering from web/pages.clj>

## Scope
Allowed files:
- <explicit file list>

Forbidden changes:
- No user-visible behavior changes.
- No route, form action, query parameter, or DB schema changes.
- No unrelated formatting churn.

## Context
- Current baseline tag: v0.1.0
- Existing verification gate:
  - devenv shell test
  - devenv shell e2e-all
  - devenv shell lint
  - nix flake check
- Relevant tests:
  - <test files>

## Required Steps
1. Read the target files and identify current responsibilities.
2. Add or strengthen characterization tests before moving behavior.
3. Move behavior in small commits or small patch chunks.
4. Run targeted tests after each move.
5. Run the full gate before declaring completion.

## Output Format
- Changed files
- Behavior preserved
- New tests and assertion count
- Commands run and results
- Remaining risks
```

## Recommendation

Start with Phase 1 and Phase 2. Do not begin with DB splitting. The largest
change pressure is currently in Web rendering, and HTML/browser behavior is the
most likely place to regress from extraction.
