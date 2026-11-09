#!/usr/bin/env bash
# A map of the code: each file's sections and functions, with the one-line
# comment that says what each is for. Made from the code, so it's never stale.
#
#   dev/map.sh              every file in src/
#   dev/map.sh src/tui.sh   one file
#   dev/map.sh --check      list the functions with no comment (dev/check.sh runs it)
#
# Bash: "name() {  # what it's for" and "# ---- section" lines.
# Python: "def"/"class" lines with the first line of their docstring.
set -euo pipefail
cd "$(dirname "$0")/.."
check=0; [ "${1:-}" = --check ] && { check=1; shift; }
files=("$@"); [ "${#files[@]}" -gt 0 ] || files=(src/kokoro src/player.sh src/tui.sh src/theme.sh src/engine.py src/words.py)
if [ "$check" = 1 ]; then
  # a function with no comment: no "# ..." on its line, nor the line above
  missing="$(awk '/^[a-z_][a-z_0-9]*\(\) *\{/ && !/#/ && prev !~ /^#/ { print FILENAME ":" FNR ": " $1 } { prev = $0 }' \
    "${files[@]/%*.py/}" 2>/dev/null)" || true
  [ -z "$missing" ] && exit 0
  printf 'Say what these are for, in a comment on the same line:\n%s\n' "$missing" >&2
  exit 1
fi
for f in "${files[@]}"; do
  printf '\n%s\n' "$f"
  case "$f" in
    *.py)
      awk '
        /^# -+ / { sub(/^# -+ /, ""); sub(/ -+$/, ""); printf "\n  -- %s\n", $0; next }
        /^(def|class) / { name = $2; sub(/[(:].*/, "", name); line = NR; want = 1; next }
        want && /"""/ { d = $0; sub(/^[ \t]*"""/, "", d); sub(/""".*/, "", d); printf "  %4d  %-26s %s\n", line, name, d; want = 0; next }
        want && !/^[ \t]*$/ && !/^[ \t]*"""/ { printf "  %4d  %s\n", line, name; want = 0 }
      ' "$f" ;;
    *)
      awk '
        /^# --------+ / { sub(/^# -+ /, ""); printf "\n  -- %s\n", $0; next }
        /^[a-z_][a-z_0-9]*\(\) *\{/ {
          name = $1; sub(/\(\).*/, "", name)
          d = $0; if (sub(/^[^#]*#[ ]*/, "", d) == 0) d = (prev ~ /^# / ? substr(prev, 3) : "")
          printf "  %4d  %-18s %s\n", NR, name, d
        }
        { prev = $0 }
      ' "$f" ;;
  esac
done
