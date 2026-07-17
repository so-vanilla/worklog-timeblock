{
  description = "Local worklog timeblock app";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        mkRunner = { name, alias }: pkgs.writeShellApplication {
          inherit name;
          runtimeInputs = [ pkgs.clojure pkgs.jdk21 ];
          text = ''
            absolute_path() {
              case "$1" in
                /*)
                  printf '%s\n' "$1"
                  ;;
                *)
                  printf '%s/%s\n' "$PWD" "$1"
                  ;;
              esac
            }

            has_db_arg() {
              for arg in "$@"; do
                case "$arg" in
                  --db|-d|--db=*)
                    return 0
                    ;;
                esac
              done
              return 1
            }

            data_dir="''${WORKLOG_TIMEBLOCK_DATA_DIR:-''${XDG_DATA_HOME:-$HOME/.local/share}/worklog-timeblock}"
            db_path="$(absolute_path "''${WORKLOG_TIMEBLOCK_DB:-$data_dir/app.db}")"
            mkdir -p "$(dirname "$db_path")"

            args=()
            while [ "$#" -gt 0 ]; do
              case "$1" in
                --db|-d)
                  if [ "$#" -lt 2 ]; then
                    args+=("$1")
                    shift
                  else
                    args+=("$1" "$(absolute_path "$2")")
                    shift 2
                  fi
                  ;;
                --db=*)
                  args+=("--db=$(absolute_path "''${1#--db=}")")
                  shift
                  ;;
                *)
                  args+=("$1")
                  shift
                  ;;
              esac
            done

            if ! has_db_arg "''${args[@]}"; then
              args=(--db "$db_path" "''${args[@]}")
            fi

            if [ -t 1 ]; then
              if columns="$(${pkgs.ncurses}/bin/tput cols 2>/dev/null)"; then
                export COLUMNS="$columns"
              fi
            fi

            cd ${self}
            exec clojure -M:${alias} "''${args[@]}"
          '';
        };
      in
      {
        packages.backend = mkRunner {
          name = "worklog-timeblock";
          alias = "run";
        };

        packages.tui = mkRunner {
          name = "worklog-timeblock-tui";
          alias = "tui";
        };

        packages.default = self.packages.${system}.backend;

        apps.default = flake-utils.lib.mkApp {
          drv = self.packages.${system}.backend;
        };

        apps.backend = flake-utils.lib.mkApp {
          drv = self.packages.${system}.backend;
        };

        apps.tui = flake-utils.lib.mkApp {
          drv = self.packages.${system}.tui;
        };

        checks.static-layout = pkgs.runCommand "worklog-timeblock-static-layout" { } ''
          test -f ${self}/deps.edn
          test -f ${self}/docker-compose.yml
          test -d ${self}/src
          test -d ${self}/test
          touch "$out"
        '';
      });
}
