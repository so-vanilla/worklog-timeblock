# worklog-timeblock

Local-only worklog preparation tool for turning calendar-like candidate events
into confirmed work logs and category totals for manual entry into an internal
time tracking service.

## Scope

- Calendar sync is intentionally outside the first implementation.
- Source plugins only return candidate events.
- The core owns confirmation, category assignment, exclusion, rounding, gaps,
  warnings, and summaries.
- Confirmed logs are snapshots and do not automatically follow later source
  changes.
- Storage is SQLite, manipulated through the app rather than direct file edits.

## Current UI

The Web UI can be used from an empty database: open any date from the home page,
create categories, add manual work logs, and immediately see per-category
totals plus a manual-entry output block. Existing work logs can also be
corrected from the day page by assigning a category, editing the time range, or
excluding the log.

The import source page can store zero or more iCal file/URL sources. Manual
fetch stores imported events as `source_events` and creates initial work-log
snapshots only when a matching snapshot does not already exist. Re-fetching a
changed calendar event updates the source event and raises a source-updated
warning without silently mutating confirmed or excluded work logs.

The TUI is currently display-focused. It renders the selected day, totals, and
warnings, but interactive correction still belongs to the Web UI.

## Development

```sh
devenv shell
test
e2e
e2e-zellij
e2e-all
run-backend
run-tui
```

`e2e-zellij` is a local terminal E2E for the TUI. It expects an existing
Zellij session named `wz-10` by default and writes ignored artifacts under
`e2e-artifacts/zellij-tui-layout/`. Override the session with
`WORKLOG_ZELLIJ_SESSION`. `e2e-all` runs both the Clojure E2E suite and the
local Zellij TUI E2E.

Expected local verification before handing off:

```sh
devenv shell e2e-all
devenv shell test
devenv shell lint
nix flake check
git diff --check --cached
git diff --check
zellij --session wz-10 action list-tabs
```

## Running with Nix

```sh
nix run . -- --host 127.0.0.1 --port 3000
nix run .#tui
```

By default, `nix run` stores SQLite data under
`${XDG_DATA_HOME:-$HOME/.local/share}/worklog-timeblock/app.db`. Override this
with `WORKLOG_TIMEBLOCK_DB` or an explicit `--db` option.

The app is designed for local single-user use. The backend serves both API and
HTML pages. Docker Compose remains available for environments with a Docker
daemon, but it is not required for local execution.
