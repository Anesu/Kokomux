#!/data/data/com.termux/files/usr/bin/bash
# kokomux: Kokoro text-to-speech for Termux. https://github.com/Anesu/kokomux
#
#   bash install.sh
#
# 1. Installs proot-distro and play-audio from the Termux repos.
# 2. Creates a small Debian container called "kokoro". Kokoro's Python
#    packages only ship glibc builds, which don't run on Termux directly.
# 3. Installs kokoro-onnx there and downloads the int8 model and voices.
# 4. Adds the `kokoro` command, a settings file, Share-to-Termux handlers
#    and Termux:Widget shortcuts.
#
# Safe to run again: it skips what's done and updates the rest.
#
# GENERATED FILE: edit the files in src/ and run ./build.sh.
set -euo pipefail

DISTRO=kokoro
say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31mError: %s\033[0m\n' "$*" >&2; exit 1; }

[ -n "${PREFIX:-}" ] && [ -d "$PREFIX/bin" ] || die "Run this inside Termux."
case "$(uname -m)" in
  aarch64|arm64|x86_64) ;;
  *) die "Your CPU is $(uname -m). Kokoro needs a 64-bit device (aarch64)." ;;
esac

WORK="$PREFIX/tmp/kokoro-setup"   # the container sees this as /tmp/kokoro-setup
rm -rf "$WORK"; mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/engine.py" <<'__KOKOMUX_ENGINE__'
#@EMBED engine.py
__KOKOMUX_ENGINE__

cat > "$WORK/setup.sh" <<'__KOKOMUX_SETUP__'
#@EMBED setup-container.sh
__KOKOMUX_SETUP__

cat > "$WORK/kokoro" <<'__KOKOMUX_WRAPPER__'
#@EMBED kokoro
__KOKOMUX_WRAPPER__

# ------------------------------------------------------------ Termux side

say "Installing Termux packages"
pkg update -y
pkg install -y proot-distro curl
pkg install -y play-audio || echo "(play-audio unavailable; -p won't work)"

say "Setting up the Debian container '$DISTRO'"
if proot-distro login "$DISTRO" -- true >/dev/null 2>&1; then
  echo "Already installed."
else
  proot-distro install --override-alias "$DISTRO" debian
fi

# Stop a running server so it picks up the new engine.
tok="$PREFIX/tmp/kokoro-server/token"
if [ -f "$tok" ]; then
  curl -fsS -m 5 -X POST -H "X-Kokoro-Token: $(cut -d' ' -f2 "$tok")" \
    "http://127.0.0.1:$(cut -d' ' -f1 "$tok")/shutdown" >/dev/null 2>&1 || true
  rm -f "$tok"
fi

say "Installing Kokoro inside the container"
proot-distro login "$DISTRO" --shared-tmp -- bash /tmp/kokoro-setup/setup.sh

say "Adding the 'kokoro' command"
{ printf '#!%s/bin/bash\n' "$PREFIX"; tail -n +2 "$WORK/kokoro"; } > "$PREFIX/bin/kokoro"
chmod +x "$PREFIX/bin/kokoro"

CONF="$HOME/.config/kokoro/config"
if [ ! -f "$CONF" ]; then
  mkdir -p "${CONF%/*}"
  cat > "$CONF" <<'CFG'
# kokomux settings, used by every `kokoro` command. Edit with: kokoro --config

KOKORO_VOICE=af_heart     # see: kokoro --list-voices
KOKORO_SPEED=1.0          # 0.5 to 2.0
KOKORO_LANG=en-us         # en-us en-gb es fr-fr it pt-br hi
KOKORO_OUT=kokoro.wav     # file name when -o isn't given (.wav .mp3 .ogg .flac)

# Keep Kokoro loaded in the background so each command starts instantly.
# It stops by itself after KOKORO_IDLE_MINUTES without use.
KOKORO_AUTOSERVE=1
KOKORO_IDLE_MINUTES=30
KOKORO_PORT=8880
CFG
  echo "Settings: $CONF"
fi

# Write a helper script unless the user already has their own there.
# Ours carry a "# kokomux" line so updates and --uninstall can recognise them.
put() {
  local path="$1" body="$2"
  if [ -f "$path" ] && ! grep -q '^# kokomux' "$path"; then
    echo "Skipped $path (you already have one)"
    return
  fi
  mkdir -p "${path%/*}"
  printf '#!%s/bin/bash\n# kokomux\n%s\n' "$PREFIX" "$body" > "$path"
  chmod +x "$path"
}

say "Adding Share-to-Termux handlers and widget shortcuts"
# Share a link to Termux -> read the page. Share text or a file -> read it.
put "$HOME/bin/termux-url-opener" 'exec kokoro -p -u "$1"'
put "$HOME/bin/termux-file-editor" 'if grep -Iq . "$1"; then exec kokoro -p -f "$1"; fi
echo "kokoro: that file is not text"; sleep 3'
# Termux:Widget runs scripts in ~/.shortcuts/tasks in the background.
put "$HOME/.shortcuts/tasks/Kokoro read clipboard" 'exec kokoro -p -c'
put "$HOME/.shortcuts/tasks/Kokoro stop" 'exec kokoro --stop'

say "Testing"
kokoro -o "$HOME/kokoro-test.wav" "Kokoro is installed and working."

say "Done!"
cat <<'TXT'
Try:
  kokoro -p "Hello from my phone"
  kokoro -c -p                     (read the clipboard; needs Termux:API)
  kokoro -u https://example.com -o page.mp3
  kokoro --help
TXT
command -v play-audio >/dev/null && play-audio "$HOME/kokoro-test.wav" || true
