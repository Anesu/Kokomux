#!/data/data/com.termux/files/usr/bin/bash
# Kokoro TTS for Termux: one-file installer.
#
#   bash install.sh
#
# What it does:
#   1. Installs proot-distro (and play-audio) from the Termux repos.
#   2. Creates a small Debian container called "kokoro". Kokoro's Python
#      packages only ship glibc builds, which do not run on Termux directly,
#      but they run fine inside Debian.
#   3. Installs kokoro-onnx in a venv there and downloads the int8 model
#      (~92 MB) and voices (~28 MB).
#   4. Adds a `kokoro` command to Termux.
#
# Safe to run again: it skips anything already done.
set -euo pipefail

DISTRO=kokoro
say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31mError: %s\033[0m\n' "$*" >&2; exit 1; }

[ -n "${PREFIX:-}" ] && [ -d "$PREFIX/bin" ] || die "Run this inside Termux."
case "$(uname -m)" in
  aarch64|arm64|x86_64) ;;
  *) die "Your CPU is $(uname -m). Kokoro needs a 64-bit device (aarch64)." ;;
esac

WORK="$PREFIX/tmp/kokoro-setup"   # seen as /tmp/kokoro-setup inside the container
rm -rf "$WORK"; mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------- speak.py
cat > "$WORK/speak.py" <<'PY'
#!/usr/bin/env python3
"""Tiny Kokoro TTS command line: text in, WAV out.

Runs inside the Debian container that install.sh sets up. Use the `kokoro`
command in Termux rather than calling this directly.
"""
import argparse
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.path.join(HERE, "models", "kokoro-v1.0.int8.onnx")
VOICES = os.path.join(HERE, "models", "voices-v1.0.bin")


def main():
    p = argparse.ArgumentParser(prog="kokoro", description="Kokoro text-to-speech",
                                epilog="In Termux, add -p/--play to play the result straight away.")
    p.add_argument("text", nargs="*", help="text to speak (or use -f, or pipe via stdin)")
    p.add_argument("-f", "--file", help="read text from this file")
    p.add_argument("-o", "--out", default="kokoro.wav", help="output WAV (default: kokoro.wav)")
    p.add_argument("-v", "--voice", default="af_heart", help="voice name (default: af_heart)")
    p.add_argument("-s", "--speed", type=float, default=1.0, help="0.5 to 2.0 (default: 1.0)")
    p.add_argument("-l", "--lang", default="en-us", help="espeak language code (default: en-us)")
    p.add_argument("--list-voices", action="store_true", help="print available voices and exit")
    a = p.parse_args()

    # Import late so --help is instant.
    import soundfile as sf
    from kokoro_onnx import Kokoro

    tts = Kokoro(MODEL, VOICES)

    if a.list_voices:
        print("\n".join(sorted(tts.get_voices())))
        return

    if a.file:
        with open(a.file, encoding="utf-8") as fh:
            text = fh.read()
    elif a.text:
        text = " ".join(a.text)
    elif not sys.stdin.isatty():
        text = sys.stdin.read()
    else:
        p.error("give some text, -f FILE, or pipe text in")

    text = text.strip()
    if not text:
        p.error("text is empty")

    t0 = time.time()
    samples, rate = tts.create(text, voice=a.voice, speed=a.speed, lang=a.lang)
    sf.write(a.out, samples, rate)
    secs = len(samples) / rate
    print(f"Made {secs:.1f}s of audio in {time.time() - t0:.1f}s", file=sys.stderr)


if __name__ == "__main__":
    main()
PY

# ------------------------------------------- setup run inside the container
cat > "$WORK/setup.sh" <<'SH'
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
APP=/opt/kokoro
BASE=https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0

echo "--> apt packages"
apt-get update
apt-get install -y --no-install-recommends python3 python3-venv curl ca-certificates

echo "--> Python packages (this is the slow part)"
mkdir -p "$APP/models"
[ -x "$APP/venv/bin/python" ] || python3 -m venv "$APP/venv"
"$APP/venv/bin/pip" install --upgrade pip
"$APP/venv/bin/pip" install --upgrade kokoro-onnx soundfile

echo "--> model files"
cd "$APP/models"
for f in kokoro-v1.0.int8.onnx voices-v1.0.bin; do
  if [ -s "$f" ]; then echo "$f already downloaded"; continue; fi
  curl -fL --retry 5 -C - -o "$f.part" "$BASE/$f"
  mv "$f.part" "$f"
done

cp /tmp/kokoro-setup/speak.py "$APP/speak.py"

echo "--> test run"
"$APP/venv/bin/python" "$APP/speak.py" -o /tmp/kokoro-setup/test.wav \
  "Kokoro is installed and working."
SH

# ------------------------------------------------------- Termux side
say "Installing Termux packages"
pkg update -y
pkg install -y proot-distro
pkg install -y play-audio || echo "(play-audio unavailable; --play will try termux-media-player)"

say "Setting up the Debian container '$DISTRO'"
if proot-distro login "$DISTRO" -- true >/dev/null 2>&1; then
  echo "Already installed."
else
  proot-distro install --override-alias "$DISTRO" debian
fi

say "Installing Kokoro inside the container"
proot-distro login "$DISTRO" --shared-tmp -- bash /tmp/kokoro-setup/setup.sh

say "Adding the 'kokoro' command"
{
  printf '#!%s/bin/bash\n' "$PREFIX"
  cat <<'SH'
# Kokoro TTS wrapper: runs speak.py in the "kokoro" proot-distro container.
# Audio goes through $PREFIX/tmp (shared as /tmp) and is moved to -o.
set -euo pipefail
out=kokoro.wav; infile=""; play=0; args=()
while [ $# -gt 0 ]; do
  case "$1" in
    -o|--out)  out="${2:?-o needs a file name}"; shift 2 ;;
    --out=*)   out="${1#*=}"; shift ;;
    -f|--file) infile="${2:?-f needs a file name}"; shift 2 ;;
    --file=*)  infile="${1#*=}"; shift ;;
    -p|--play) play=1; shift ;;
    --)        args+=("$@"); break ;;
    *)         args+=("$1"); shift ;;
  esac
done

tmpd="$(mktemp -d "$PREFIX/tmp/kokoro.XXXXXX")"
trap 'rm -rf "$tmpd"' EXIT
run=(proot-distro login kokoro --shared-tmp --
     /opt/kokoro/venv/bin/python /opt/kokoro/speak.py
     -o "/tmp/$(basename "$tmpd")/out.wav" "${args[@]}")

if [ -n "$infile" ]; then "${run[@]}" < "$infile"; else "${run[@]}"; fi

[ -f "$tmpd/out.wav" ] || exit 0   # e.g. --list-voices or --help
mv "$tmpd/out.wav" "$out"
echo "Saved $out" >&2
if [ "$play" = 1 ]; then
  if command -v play-audio >/dev/null; then play-audio "$out"
  elif command -v termux-media-player >/dev/null; then termux-media-player play "$out"
  else echo "No player found: pkg install play-audio" >&2; fi
fi
SH
} > "$PREFIX/bin/kokoro"
chmod +x "$PREFIX/bin/kokoro"

mv "$WORK/test.wav" "$HOME/kokoro-test.wav"
say "Done! Test file: ~/kokoro-test.wav"
cat <<'TXT'
Try:
  kokoro -p "Hello from my phone"
  kokoro -o story.wav -v bf_emma -f story.txt
  kokoro --list-voices
TXT
command -v play-audio >/dev/null && play-audio "$HOME/kokoro-test.wav" || true
