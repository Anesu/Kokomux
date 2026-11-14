#!/usr/bin/env bash
# Everything to run before a commit: build install.sh, then lint and test.
#   dev/check.sh
# Tests run with .dev/app/venv's Python when dev/rig.sh has made it (so the
# audio and web tests run too), otherwise with python3.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
fail=0
step() { printf '\n== %s\n' "$1"; }

step build
./build.sh || exit 1

step "bash -n"
for f in install.sh src/kokoro src/*.sh dev/*.sh dev/kokoro; do
  [ "$f" = src/install.sh ] || bash -n "$f" || fail=1
done

step shellcheck
# (src/install.sh is a template: its #@EMBED lines only make sense once built into install.sh)
shellcheck -x -S warning install.sh src/kokoro src/tui.sh src/player.sh src/theme.sh src/commands.sh \
  src/setup-container.sh build.sh dev/*.sh dev/kokoro || fail=1

step "bash traps"
# x="$( [ cond ] && echo y )" exits the script under set -e when cond is false
# (it took the home screen down once): use if, or a variable set beforehand.
if grep -nE '\$\( *\[\[? ' src/kokoro src/*.sh; then echo "  ^ a test inside \$( ): use if instead"; fail=1; fi
dev/map.sh --check || fail=1                 # every function says what it's for

PY=python3; [ -x .dev/app/venv/bin/python ] && PY=.dev/app/venv/bin/python
if "$PY" -m pyflakes --version >/dev/null 2>&1; then
  step pyflakes
  "$PY" -m pyflakes src/*.py tests/*.py dev/*.py || fail=1
fi

for t in tests/test_*.py; do
  step "$t"
  "$PY" "$t" || fail=1
done

echo
if [ "$fail" = 0 ]; then echo "All checks passed."; else echo "Some checks failed."; exit 1; fi
