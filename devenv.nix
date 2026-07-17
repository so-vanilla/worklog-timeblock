{ pkgs, ... }:

{
  packages = with pkgs; [
    clojure
    clj-kondo
    docker-client
    git
    jdk21
    jq
    sqlite
    zellij
  ];

  scripts.test.exec = "clojure -M:test";
  scripts.e2e.exec = "clojure -M:e2e";
  scripts.e2e-zellij.exec = "scripts/zellij-tui-e2e.sh";
  scripts.e2e-all.exec = "clojure -M:e2e && scripts/zellij-tui-e2e.sh";
  scripts.lint.exec = "clj-kondo --lint src test";
  scripts.run-backend.exec = "clojure -M:run --db ./data/app.db";
  scripts.run-tui.exec = "clojure -M:tui --db ./data/app.db";
}
