# Testing Gate

Run these from the repository root through `devenv`.

## Full Refactoring Gate

```sh
devenv shell test
devenv shell e2e-all
devenv shell lint
nix flake check
git diff --check --cached
git diff --check
```

Also smoke-test the runnable app with a temporary SQLite database:

```sh
nix run --no-warn-dirty . -- --db <temp-db> --host 127.0.0.1 --port <temp-port>
```

The smoke test is complete only after the process starts, `/health` responds,
and the process is stopped.

## Targeted Refactoring Checks

Use targeted checks while moving one boundary:

```sh
devenv shell clojure -M:test worklog-timeblock.refactor-characterization-test
devenv shell clojure -M:test worklog-timeblock.web-e2e.pages-test
devenv shell clojure -M:test worklog-timeblock.api-e2e.routes-test
```

The full gate still has to pass before a refactoring branch is considered done.
