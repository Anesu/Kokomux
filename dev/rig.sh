#!/usr/bin/env bash
# Set up a stand-in for the phone, in .dev/ (ignored by git), so kokoro can run
# here with the real Kokoro model: no Termux, no container.
#
#   dev/rig.sh            make .dev/ (a venv with the container's Python packages,
#                         the models, a fake $PREFIX and $HOME, and a proot-distro
#                         stand-in); safe to run again
#   dev/kokoro ARGS...    then run kokoro from src/, e.g. dev/kokoro -p -f story.txt
#
# KOKOMUX_MODELS=DIR dev/rig.sh  links a folder that already holds the models
# instead of downloading them (~330 MB).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
DEV="$ROOT/.dev"
mkdir -p "$DEV"/{app,bin,home,prefix/bin,prefix/share/kokomux}

# .dev/app is the container's /opt/kokoro: engine.py and words.py linked from
# src/ (so edits show at once), venv/ and models/.
ln -sfn "$ROOT/src/engine.py" "$DEV/app/engine.py"
ln -sfn "$ROOT/src/words.py" "$DEV/app/words.py"
for f in theme.sh tui.sh player.sh; do ln -sfn "$ROOT/src/$f" "$DEV/prefix/share/kokomux/$f"; done
ln -sfn /tmp "$DEV/prefix/tmp"        # proot-distro --shared-tmp: both sides see the same /tmp

# The same packages as setup-container.sh's pip phase.
if [ ! -x "$DEV/app/venv/bin/python" ]; then
  python3 -m venv "$DEV/app/venv"
  "$DEV/app/venv/bin/pip" install --upgrade pip
  "$DEV/app/venv/bin/pip" install kokoro-onnx soundfile pypdf "trafilatura>=2.0,<3" \
    "misaki>=0.7.4,<0.8" num2words "spacy>=3.8,<3.9" \
    "en_core_web_sm @ https://github.com/explosion/spacy-models/releases/download/en_core_web_sm-3.8.0/en_core_web_sm-3.8.0-py3-none-any.whl"
fi

if [ -n "${KOKOMUX_MODELS:-}" ]; then
  ln -sfn "$(realpath "$KOKOMUX_MODELS")" "$DEV/app/models"
elif [ ! -s "$DEV/app/models/voices-v1.0.bin" ]; then
  "$DEV/app/venv/bin/python" -u "$DEV/app/engine.py" download
fi

# "proot-distro login kokoro [options] -- CMD..." runs CMD here, with /opt/kokoro mapped to .dev/.
cat > "$DEV/bin/proot-distro" <<STAND_IN
#!/usr/bin/env bash
[ "\$1" = login ] || exit 0          # (install/remove: nothing to do here)
while [ "\$1" != "--" ]; do shift; done; shift
args=()
for a in "\$@"; do
  case "\$a" in /opt/kokoro/*) a="$DEV/app/\${a#/opt/kokoro/}" ;; esac
  args+=("\$a")
done
exec "\${args[@]}"
STAND_IN
chmod +x "$DEV/bin/proot-distro"
echo "Ready: dev/kokoro ARGS (e.g. dev/kokoro -p -f story.txt)"
