#!/usr/bin/env bash
# Bundle src/ into the single-file install.sh (what users download).
set -euo pipefail
cd "$(dirname "$0")"
for f in src/*; do
  [ -f "$f" ] || continue
  if grep -q '^__KOKOMUX_' "$f" && [ "$f" != src/install.sh ]; then
    echo "$f contains a heredoc marker" >&2; exit 1
  fi
done
awk '/^#@EMBED / { f = "src/" $2; while ((getline line < f) > 0) print line; close(f); next } { print }' \
  src/install.sh > install.sh
chmod +x install.sh
echo "Built install.sh"
