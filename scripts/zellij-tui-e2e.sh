#!/usr/bin/env bash
set -uo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
session="${WORKLOG_ZELLIJ_SESSION:-wz-10}"
stamp="$(date +%Y%m%d-%H%M%S)"
artifact="${WORKLOG_ZELLIJ_ARTIFACT_DIR:-$repo/e2e-artifacts/zellij-tui-layout/$stamp}"
manifest="$artifact/manifest.tsv"
results="$artifact/results.tsv"
active_tabs=()

mkdir -p "$artifact/cases"

cleanup_tabs() {
  local tab_id
  for tab_id in "${active_tabs[@]}"; do
    zellij --session "$session" action close-tab-by-id "$tab_id" >/dev/null 2>&1 || true
  done
}

trap cleanup_tabs EXIT

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "missing command: $1" >&2
    exit 2
  fi
}

require_command jq
require_command nix
require_command sqlite3
require_command zellij

if ! zellij list-sessions --short | grep -Fxq "$session"; then
  echo "zellij session not found: $session" >&2
  exit 2
fi

printf 'case_id\twidth\theight\tdate\tintent\texpected_present\texpected_absent\n' > "$manifest"
printf 'case_id\tcase_status\tassertions\tfailures\tpane_id\ttab_id\tgeometry\tviewport_dump\tfull_dump\n' > "$results"

add_case() {
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" "$5" "$6" "$7" >> "$manifest"
}

add_case normal-080x20 80 20 2026-07-06 normal-day \
  'worklog-timeblock|2026-07-06|Build|Standup|Development|0.75h|Meetings|0.25h|Other|0.25h' \
  'Warnings|Exception|Stacktrace|Error|dev|meeting'
add_case uncategorized-060x20 60 20 2026-07-06 uncategorized-warning \
  'worklog-timeblock|2026-07-06|Unknown|Uncategorized: Unknown|Development|0.75h|Other|0.67h|Warnings' \
  'Exception|Stacktrace|Error|dev|other'
add_case excluded-060x20 60 20 2026-07-06 excluded-log-not-totaled \
  'worklog-timeblock|2026-07-06|Lunch|excluded|Build|Development|0.75h|Other|0.08h' \
  'Warnings|Meetings|meeting|Exception|Stacktrace|Error|dev'
add_case rounding-080x20 80 20 2026-07-06 rounding-boundaries \
  'worklog-timeblock|2026-07-06|Short14|Exact15|Meetings|0.25h|Other|0.25h' \
  'Development                 |dev|meeting|Exception|Stacktrace|Error'
add_case long-japanese-040x20 40 20 2026-07-06 narrow-long-japanese \
  'worklog-timeblock|2026-07-06|非常に長い|Very Long Category Name|0.75h|Warnings|未分類候補' \
  'very-long-category-id|Exception|Stacktrace|Error'
add_case narrow-032x10 32 10 2026-07-06 very-narrow-small-data \
  'worklog-timeblock|2026-07-06|09:00-09:15 confirmed Build|Development 0.25h' \
  'Warnings|Exception|Stacktrace|Error|confirmedBuild|dev'
add_case empty-080x10 80 10 2026-07-06 empty-day \
  'worklog-timeblock|2026-07-06|Timeline|Category totals' \
  'Warnings|Exception|Stacktrace|Error'
add_case specials-120x20 120 20 2026-07-06 special-characters \
  'worklog-timeblock|2026-07-06|Escape <tag> & "quote"|Development|0.25h' \
  'Exception|Stacktrace|Error|dev'

create_db() {
  local case_id=$1
  local db="$artifact/cases/$case_id.db"
  sqlite3 "$db" < "$repo/resources/migrations/001_initial.up.sql"
  sqlite3 "$db" <<SQL
INSERT INTO categories (legacy_key, name, kind) VALUES ('dev', 'Development', 'normal');
INSERT INTO categories (legacy_key, name, kind) VALUES ('meeting', 'Meetings', 'normal');
INSERT INTO categories (legacy_key, name, kind) VALUES ('support', 'Support', 'normal');
INSERT INTO categories (legacy_key, name, kind) VALUES ('research', 'Research', 'normal');
INSERT INTO categories (legacy_key, name, kind) VALUES ('very-long-category-id', 'Very Long Category Name', 'normal');
INSERT INTO categories (legacy_key, name, kind) VALUES ('other', 'Other', 'other');
SQL

  case "$case_id" in
    normal-080x20)
      sqlite3 "$db" <<SQL
INSERT INTO work_logs (date,title,start_minute,end_minute,state,category_id)
VALUES ('2026-07-06','Build',540,590,'confirmed',(SELECT id FROM categories WHERE legacy_key = 'dev'));
INSERT INTO work_logs (date,title,start_minute,end_minute,state,category_id)
VALUES ('2026-07-06','Standup',600,615,'confirmed',(SELECT id FROM categories WHERE legacy_key = 'meeting'));
SQL
      ;;
    uncategorized-060x20)
      sqlite3 "$db" <<SQL
INSERT INTO work_logs (date,title,start_minute,end_minute,state,category_id)
VALUES ('2026-07-06','Build',540,585,'confirmed',(SELECT id FROM categories WHERE legacy_key = 'dev'));
INSERT INTO work_logs (date,title,start_minute,end_minute,state,category_id) VALUES ('2026-07-06','Unknown',595,625,'uncategorized',NULL);
SQL
      ;;
    excluded-060x20)
      sqlite3 "$db" <<SQL
INSERT INTO work_logs (date,title,start_minute,end_minute,state,category_id)
VALUES ('2026-07-06','Build',540,590,'confirmed',(SELECT id FROM categories WHERE legacy_key = 'dev'));
INSERT INTO work_logs (date,title,start_minute,end_minute,state,category_id) VALUES ('2026-07-06','Lunch',590,650,'excluded',NULL);
SQL
      ;;
    rounding-080x20)
      sqlite3 "$db" <<SQL
INSERT INTO work_logs (date,title,start_minute,end_minute,state,category_id)
VALUES ('2026-07-06','Short14',540,554,'confirmed',(SELECT id FROM categories WHERE legacy_key = 'dev'));
INSERT INTO work_logs (date,title,start_minute,end_minute,state,category_id)
VALUES ('2026-07-06','Exact15',555,570,'confirmed',(SELECT id FROM categories WHERE legacy_key = 'meeting'));
SQL
      ;;
    long-japanese-040x20)
      sqlite3 "$db" <<SQL
INSERT INTO work_logs (date,title,start_minute,end_minute,state,category_id)
VALUES ('2026-07-06','非常に長いタイトルの開発作業とレビュー確認',540,585,'confirmed',(SELECT id FROM categories WHERE legacy_key = 'very-long-category-id'));
INSERT INTO work_logs (date,title,start_minute,end_minute,state,category_id) VALUES ('2026-07-06','未分類候補の長い予定名',590,620,'uncategorized',NULL);
SQL
      ;;
    narrow-032x10)
      sqlite3 "$db" <<SQL
INSERT INTO work_logs (date,title,start_minute,end_minute,state,category_id)
VALUES ('2026-07-06','Build',540,555,'confirmed',(SELECT id FROM categories WHERE legacy_key = 'dev'));
SQL
      ;;
    empty-080x10)
      ;;
    specials-120x20)
      sqlite3 "$db" <<SQL
INSERT INTO work_logs (date,title,start_minute,end_minute,state,category_id)
VALUES ('2026-07-06','Escape <tag> & "quote"',540,555,'confirmed',(SELECT id FROM categories WHERE legacy_key = 'dev'));
SQL
      ;;
  esac

  printf '%s' "$db"
}

check_tokens() {
  local file=$1
  local tokens=$2
  local mode=$3
  local label=$4
  local failures=0
  local assertions=0
  local token
  local token_array=()

  IFS='|' read -r -a token_array <<< "$tokens"
  for token in "${token_array[@]}"; do
    [ -z "$token" ] && continue
    assertions=$((assertions + 1))
    if [ "$mode" = present ]; then
      if ! grep -Fq "$token" "$file"; then
        echo "missing:$label:$token" >> "$case_dir/assertions.log"
        failures=$((failures + 1))
      fi
    else
      if grep -Fq "$token" "$file"; then
        echo "unexpected:$label:$token" >> "$case_dir/assertions.log"
        failures=$((failures + 1))
      fi
    fi
  done
  printf '%s %s' "$assertions" "$failures"
}

close_tab() {
  local tab_id=$1
  zellij --session "$session" action close-tab-by-id "$tab_id" >/dev/null 2>&1 || true
  local remaining=()
  local active
  for active in "${active_tabs[@]}"; do
    [ "$active" = "$tab_id" ] || remaining+=("$active")
  done
  active_tabs=("${remaining[@]}")
}

case_count=0
total_assertions=0
total_failures=0

while IFS=$'\t' read -r case_id width height date intent present absent; do
  [ "$case_id" = case_id ] && continue
  case_count=$((case_count + 1))
  case_dir="$artifact/cases/$case_id"
  mkdir -p "$case_dir"

  db=$(create_db "$case_id")
  done_file="$case_dir/done.txt"
  tab="wtb-e2e-$case_id-$stamp"
  tab_id=$(zellij --session "$session" action new-tab --cwd "$repo" -n "$tab")
  active_tabs+=("$tab_id")
  sleep 0.5

  command="nix run --no-warn-dirty .#tui -- --db '$db' --date '$date'; code=\$?; printf '%s' \$code > '$done_file'; sleep 600"
  zellij --session "$session" action new-pane \
    --tab-id "$tab_id" \
    --floating \
    --width "$width" \
    --height "$height" \
    --cwd "$repo" \
    -- bash -lc "$command" > "$case_dir/new-pane-result.txt"

  pane_id=""
  for _ in $(seq 1 25); do
    zellij --session "$session" action list-panes --json --all --command --tab --state --geometry > "$case_dir/panes.json"
    pane_id=$(jq -r --arg tab "$tab" '.[] | select(.is_plugin == false and .tab_name == $tab and .is_floating == true) | .id' "$case_dir/panes.json" | tail -n 1)
    [ -s "$done_file" ] && break
    sleep 1
  done

  if [ -z "$pane_id" ]; then
    echo "pane not found" > "$case_dir/assertions.log"
    assertions=1
    failures=1
    geometry=missing
  else
    zellij --session "$session" action dump-screen --pane-id "$pane_id" --path "$case_dir/viewport.dump.txt"
    zellij --session "$session" action dump-screen --pane-id "$pane_id" --full --path "$case_dir/full.dump.txt"
    jq --argjson id "$pane_id" '.[] | select(.is_plugin == false and .id == $id)' "$case_dir/panes.json" > "$case_dir/geometry.json"

    read -r vpa vpf <<< "$(check_tokens "$case_dir/viewport.dump.txt" "$present" present viewport)"
    read -r vaa vaf <<< "$(check_tokens "$case_dir/viewport.dump.txt" "$absent" absent viewport)"
    read -r fpa fpf <<< "$(check_tokens "$case_dir/full.dump.txt" "$present" present full)"
    read -r faa faf <<< "$(check_tokens "$case_dir/full.dump.txt" "$absent" absent full)"

    assertions=$((vpa + vaa + fpa + faa + 3))
    failures=$((vpf + vaf + fpf + faf))

    if [ "$(cat "$done_file" 2>/dev/null || printf missing)" != 0 ]; then
      echo "bad-exit-code:$(cat "$done_file" 2>/dev/null || printf missing)" >> "$case_dir/assertions.log"
      failures=$((failures + 1))
    fi
    if [ ! -s "$case_dir/viewport.dump.txt" ]; then
      echo "empty-viewport-dump" >> "$case_dir/assertions.log"
      failures=$((failures + 1))
    fi
    if [ ! -s "$case_dir/full.dump.txt" ]; then
      echo "empty-full-dump" >> "$case_dir/assertions.log"
      failures=$((failures + 1))
    fi
    geometry=$(jq -r '"cols=\(.pane_content_columns) rows=\(.pane_content_rows) floating=\(.is_floating)"' "$case_dir/geometry.json")
  fi

  total_assertions=$((total_assertions + assertions))
  total_failures=$((total_failures + failures))
  case_status=pass
  [ "$failures" -eq 0 ] || case_status=fail

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$case_id" "$case_status" "$assertions" "$failures" "$pane_id" "$tab_id" "$geometry" \
    "$case_dir/viewport.dump.txt" "$case_dir/full.dump.txt" >> "$results"

  close_tab "$tab_id"
  sleep 0.5
done < "$manifest"

zellij --session "$session" action list-tabs > "$artifact/final-tabs.txt"
if grep -q 'wtb-e2e-' "$artifact/final-tabs.txt"; then
  echo 'residual-tabs-found' >> "$artifact/residue.log"
  total_failures=$((total_failures + 1))
fi

{
  printf '# zellij TUI layout E2E %s\n\n' "$stamp"
  printf '%s\n' "- session: $session"
  printf '%s\n' '- screenshots: omitted'
  printf '%s\n' '- viewport assertions: enabled'
  printf '%s\n' '- full dump assertions: enabled'
  printf '%s\n' '- completion marker: file only, not printed into pane'
  printf '%s\n' "- cases: $case_count"
  printf '%s\n' "- assertions: $total_assertions"
  printf '%s\n\n' "- failures: $total_failures"
  printf '## Results\n\n'
  printf '| case | status | assertions | failures | geometry | viewport dump | full dump |\n'
  printf '|---|---:|---:|---:|---|---|---|\n'
  tail -n +2 "$results" | while IFS=$'\t' read -r cid st as fs pane tab geom viewport full; do
    relviewport=${viewport#"$artifact/"}
    relfull=${full#"$artifact/"}
    printf '| %s | %s | %s | %s | %s | %s | %s |\n' "$cid" "$st" "$as" "$fs" "$geom" "$relviewport" "$relfull"
  done
} > "$artifact/index.md"

printf 'artifact=%s\ncases=%s\nassertions=%s\nfailures=%s\n' "$artifact" "$case_count" "$total_assertions" "$total_failures"

if [ "$total_failures" -ne 0 ]; then
  exit 1
fi
