#!/usr/bin/env bash
# Bundle src/ into the single-file install.sh (what the phone downloads).
#   #@EMBED file   in src/install.sh: the file's lines, as they are (theme.sh, for the installer's own colours)
#   #@FILE file    the file, written into $WORK/file when install.sh runs (a quoted heredoc)
set -euo pipefail
cd "$(dirname "$0")"
version="$(cat VERSION)"
for f in src/*; do
  [ -f "$f" ] || continue
  if grep -q '^__KOKOMUX_FILE__$' "$f"; then
    echo "$f contains the heredoc marker" >&2; exit 1
  fi
done
awk '
  /^#@EMBED / { f = "src/" $2; while ((getline line < f) > 0) print line; close(f); next }
  /^#@FILE / {
    f = "src/" $2
    if ((getline line < f) <= 0) { print "build.sh: no " f > "/dev/stderr"; exit 1 }
    printf "cat > \"$WORK/%s\" <<'\''__KOKOMUX_FILE__'\''\n%s\n", $2, line
    while ((getline line < f) > 0) print line
    close(f); print "__KOKOMUX_FILE__"; next
  }
  { print }' src/install.sh | sed "s/@@VERSION@@/$version/g" > install.sh
chmod +x install.sh
echo "Built install.sh (v$version)"
