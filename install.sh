#!/data/data/com.termux/files/usr/bin/bash
# kokomux: Kokoro text-to-speech for Termux. https://github.com/Anesu/kokomux
#
#   bash install.sh [--yes] [--verbose]
#
# Sets up a small Debian container ("kokoro") with proot-distro, installs
# Kokoro in it, and adds the `kokoro` command to Termux. Kokoro's Python
# packages only ship glibc builds, which don't run on Termux directly.
# Safe to run again: it skips what's done and updates the rest.
#
# GENERATED FILE: edit the files in src/ and run ./build.sh.
set -euo pipefail

KOKOMUX_VERSION="0.5.0"
DISTRO=kokoro

ASSUME_YES=0; VERBOSE=0
for arg in "$@"; do
  case "$arg" in
    -y|--yes) ASSUME_YES=1 ;;
    -v|--verbose) VERBOSE=1 ;;
    -h|--help) echo "Usage: bash install.sh [--yes] [--verbose]"; exit 0 ;;
    *) echo "Unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

# ================================================================ look & feel

FANCY=0   # colours + spinners, only on a real terminal
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-dumb}" != dumb ]; then FANCY=1; fi
if [ "$FANCY" = 1 ]; then
  R=$'\033[0m' B=$'\033[1m' D=$'\033[2m'
  ACC=$'\033[38;5;141m' ACC2=$'\033[38;5;117m'
  GRN=$'\033[38;5;114m' RED=$'\033[38;5;203m' YEL=$'\033[38;5;221m'
else
  R="" B="" D="" ACC="" ACC2="" GRN="" RED="" YEL=""
fi
SPIN=(⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏)

COLS="$(stty size 2>/dev/null </dev/tty | cut -d' ' -f2)" || true
[[ "${COLS:-}" =~ ^[0-9]+$ ]] && [ "$COLS" -ge 20 ] || COLS=48
W=$(( COLS > 72 ? 72 : COLS ))   # layout width

say()   { printf '%s\n' "$*"; }
blank() { printf '\n'; }
ok()    { printf '  %s✓%s %s%s\n' "$GRN" "$R" "$1" "${2:+ $D$2$R}"; }
warn()  { printf '  %s!%s %s%s\n' "$YEL" "$R" "$1" "${2:+ $D$2$R}"; }
bad()   { printf '  %s✗%s %s%s\n' "$RED" "$R" "$1" "${2:+ $D$2$R}"; }
heading() { blank; printf '  %s%s%s\n' "$B" "$1" "$R"; }
# A "›" bullet whose continuation lines line up under the text.
bullet() {
  local first=1 line
  while IFS= read -r line; do
    if [ "$first" = 1 ]; then printf '  %s›%s %s\n' "$ACC" "$R" "$line"; first=0
    else printf '    %s\n' "$line"; fi
  done < <(printf '%s\n' "$*" | fold -s -w $(( W - 5 )))
}
# Wrap text to the layout width with an indent: wrap "  │ " "text"
wrap() {
  local pre="$1" vis; shift
  vis="$(printf '%s' "$pre" | sed 's/\x1b\[[0-9;]*m//g')"
  printf '%s\n' "$*" | fold -s -w $(( W - ${#vis} - 1 )) | sed "s/^/$pre/"
}
# shellcheck disable=SC2088  # a literal ~ is the point
tilde() { case "$1" in "$HOME"/*) printf '~/%s' "${1#"$HOME"/}" ;; *) printf '%s' "$1" ;; esac; }
human_time() { local s=$1; if [ "$s" -ge 60 ]; then printf '%dm %02ds' $((s / 60)) $((s % 60)); else printf '%ds' "$s"; fi; }
# Hide the cursor and the "^C" echo while steps run; restore both after.
TTY_SAVED=""
hide_cursor() {
  [ "$FANCY" = 1 ] || return 0
  printf '\033[?25l'
  TTY_SAVED="$(stty -g 2>/dev/null </dev/tty)" && stty -echoctl 2>/dev/null </dev/tty || true
}
show_cursor() {
  [ "$FANCY" = 1 ] || return 0
  printf '\033[?25h'
  [ -n "$TTY_SAVED" ] && stty "$TTY_SAVED" 2>/dev/null </dev/tty
  TTY_SAVED=""
}

banner() {
  local c=(135 141 147 153 159 195) i=0 line
  local big=(
'██╗  ██╗ ██████╗ ██╗  ██╗ ██████╗ ███╗   ███╗██╗   ██╗██╗  ██╗'
'██║ ██╔╝██╔═══██╗██║ ██╔╝██╔═══██╗████╗ ████║██║   ██║╚██╗██╔╝'
'█████╔╝ ██║   ██║█████╔╝ ██║   ██║██╔████╔██║██║   ██║ ╚███╔╝ '
'██╔═██╗ ██║   ██║██╔═██╗ ██║   ██║██║╚██╔╝██║██║   ██║ ██╔██╗ '
'██║  ██╗╚██████╔╝██║  ██╗╚██████╔╝██║ ╚═╝ ██║╚██████╔╝██╔╝ ██╗'
'╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═╝ ╚═════╝ ╚═╝     ╚═╝ ╚═════╝ ╚═╝  ╚═╝')
  local small=(
'░█░█░█▀█░█░█░█▀█░█▄█░█░█░█░█'
'░█▀▄░█░█░█▀▄░█░█░█░█░█░█░▄▀▄'
'░▀░▀░▀▀▀░▀░▀░▀▀▀░▀░▀░▀▀▀░▀░▀')
  blank
  if [ "$COLS" -ge 66 ]; then
    for line in "${big[@]}"; do
      if [ "$FANCY" = 1 ]; then printf '  \033[38;5;%sm%s%s\n' "${c[i]}" "$line" "$R"; else say "  $line"; fi
      i=$((i + 1))
    done
  else
    for line in "${small[@]}"; do
      # the ░ texture in dark grey so the letters stand out
      if [ "$FANCY" = 1 ]; then
        line="${line//░/$'\033[38;5;237m'░$'\033[38;5;'"${c[i * 2]}"m}"
        printf '  \033[38;5;%sm%s%s\n' "${c[i * 2]}" "$line" "$R"
      else say "  $line"; fi
      i=$((i + 1))
    done
  fi
  printf '  %sKokoro text-to-speech for Termux%s %sv%s%s\n' "$D" "$R" "$ACC" "$KOKOMUX_VERSION" "$R"
}

# ================================================================ steps

LOG_DIR="$HOME/.cache/kokomux"; mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/install.log"
WORK="$PREFIX/tmp/kokoro-setup"   # the container sees this as /tmp/kokoro-setup
NOTE="$WORK/.note"                # a step can leave a short note for its ✓ line
STEP=0; TOTAL=7; STEP_PID=""; CUR_STEP=""; WAKELOCK=0; T_START=$SECONDS

note() { printf '%s' "$*" > "$NOTE"; }

# Draw a progress bar from an engine line: @@progress LABEL PCT DONE TOTAL
progress_bar() {
  local label=$1 pct=$2 done=$3 total=$4 room=$5 n bw filled
  local sizes="${done}/${total} MB"
  [ "$total" = 0 ] && sizes="done"
  # label, bar, percent, sizes; drop the sizes before shrinking the bar too far
  bw=$(( room - ${#label} - ${#sizes} - 7 ))
  if [ "$bw" -lt 8 ]; then sizes=""; bw=$(( room - ${#label} - 6 )); fi
  [ "$bw" -gt 24 ] && bw=24
  if [ "$bw" -lt 4 ]; then printf '%s %s%%' "$label" "$pct"; return; fi
  filled=$(( pct * bw / 100 ))
  printf '%s %s' "$label" "$ACC"
  for ((n = 0; n < bw; n++)); do
    if [ "$n" -lt "$filled" ]; then printf '█'; else printf '%s░' "$D"; fi
  done
  printf '%s %3s%%%s' "$R" "$pct" "${sizes:+ $D$sizes$R}"
}

# What the running step is doing right now, from the last line of the log.
step_detail() {
  local room=$1 line
  [ "$room" -ge 8 ] || return 0
  line="$(tail -n 1 "$LOG" 2>/dev/null | tr -d '\r' | sed 's/\x1b\[[0-9;?]*[A-Za-z]//g' | tr -cd '\11\40-\176')" || true
  case "$line" in
    "@@progress "*)
      # shellcheck disable=SC2086
      set -- $line; progress_bar "$2" "$3" "$4" "$5" "$room"; return ;;
    "=== "*|"") return ;;
  esac
  line="$(printf '%s' "$line" | sed -E \
    -e 's/^Get:[0-9]+ [^ ]+ [^ ]+ [^ ]+ ([^ ]+).*/downloading \1/' \
    -e 's/^(Unpacking|Setting up|Selecting previously unselected package) ([^ ]+).*/\L\1\E \2/' \
    -e 's/^Collecting ([A-Za-z0-9_.-]+).*/getting \1/' \
    -e 's/^Downloading ([A-Za-z0-9_]+)-[0-9][^ ]* \(([^)]*)\).*/downloading \1 (\2)/' \
    -e 's/^Installing collected packages.*/installing packages/' \
    -e 's/^Successfully installed.*/finishing up/' \
    -e 's/^Requirement already satisfied: ([A-Za-z0-9_.-]+).*/\1 is up to date/')"
  line="${line#"${line%%[![:space:]]*}"}"
  [ "${#line}" -gt "$room" ] && line="${line:0:$((room - 1))}…"
  printf '%s%s%s' "$D" "$line" "$R"
}

# step "Title" "how to fix it if it fails" command [args...]
step() {
  local title=$1 hint=$2 t0=$SECONDS rc=0 i=0 tag el room
  shift 2
  STEP=$((STEP + 1)); tag="$STEP/$TOTAL"
  CUR_STEP="$tag $title"
  rm -f "$NOTE"
  printf '\n=== [%s] %s (%s)\n' "$tag" "$title" "$(date '+%F %T')" >> "$LOG"

  if [ "$FANCY" = 1 ] && [ "$VERBOSE" = 0 ]; then
    ( "$@" ) >> "$LOG" 2>&1 < /dev/null &
    STEP_PID=$!
    while kill -0 "$STEP_PID" 2>/dev/null; do
      el=$(( SECONDS - t0 ))
      local tm=""; [ "$el" -ge 3 ] && tm=" $(human_time "$el")"
      room=$(( W - 9 - ${#tag} - ${#title} - ${#tm} ))   # stay off the last column
      printf '\r\033[K  %s%s%s %s%s%s %s%s  %s%s%s%s' "$ACC" "${SPIN[i % 10]}" "$R" "$D" "$tag" "$R" \
        "$B$title" "$R" "$(step_detail "$room")" "$D" "$tm" "$R"
      i=$((i + 1)); sleep 0.12
    done
    wait "$STEP_PID" || rc=$?
    STEP_PID=""
    printf '\r\033[K'
  else
    printf '  [%s] %s...\n' "$tag" "$title"
    if [ "$VERBOSE" = 1 ]; then
      set +e; ( "$@" ) 2>&1 < /dev/null | tee -a "$LOG"; rc=${PIPESTATUS[0]}; set -e
    else
      ( "$@" ) >> "$LOG" 2>&1 < /dev/null || rc=$?
    fi
  fi

  el=$(( SECONDS - t0 ))
  if [ "$rc" = 0 ]; then
    local extra; extra="$(cat "$NOTE" 2>/dev/null || true)"
    [ -n "$extra" ] || extra="$(human_time "$el")"
    printf '  %s✓%s %s%s%s %s %s%s%s\n' "$GRN" "$R" "$D" "$tag" "$R" "$title" "$D" "$extra" "$R"
    return 0
  fi

  printf '  %s✗%s %s%s%s %s%s%s\n' "$RED" "$R" "$D" "$tag" "$R" "$B" "$title" "$R"
  blank
  local edge="  $RED│$R"
  printf '  %s╭─%s %s%s failed%s\n' "$RED" "$R" "$B" "$title" "$R"
  say "$edge"
  say "$edge ${D}What happened${R}"
  # This step's output only, wrapped, last 8 lines.
  awk '/^=== \[/ { n = 0; next } !/^@@progress/ && NF { l[n++] = $0 }
       END { for (i = (n > 8 ? n - 8 : 0); i < n; i++) print l[i] }' "$LOG" |
    tr -d '\r' | tr -cd '\11\12\40-\176' | fold -w $(( W - 8 )) | tail -n 8 |
    while IFS= read -r line; do say "$edge   $D$line$R"; done
  say "$edge"
  say "$edge ${B}How to fix it${R}"
  wrap "$edge   " "$hint"
  say "$edge"
  say "$edge   ${D}Log:${R} ${ACC2}$(tilde "$LOG")${R}"
  printf '  %s╰─%s %sRun the installer again to retry.%s\n' "$RED" "$R" "$D" "$R"
  blank
  [ "$FANCY" = 1 ] && printf '\a'
  exit 1
}

on_exit() {
  show_cursor
  rm -rf "$WORK"
  [ "$WAKELOCK" = 1 ] && termux-wake-unlock >/dev/null 2>&1
  return 0
}
on_interrupt() {
  printf '\r\033[K'
  if [ -n "$STEP_PID" ]; then
    kill "$STEP_PID" 2>/dev/null
    printf '  %s✗%s %s%s%s %s%s\n' "$RED" "$R" "$D" "${CUR_STEP%% *}" "$R" "${CUR_STEP#* }" " ${D}stopped${R}"
  fi
  blank
  wrap "  " "Stopped. Nothing is broken: run the installer again and it picks up where it left off."
  blank
  exit 130
}
trap on_exit EXIT
trap on_interrupt INT TERM

# ================================================================ pre-flight

banner

[ -n "${PREFIX:-}" ] && [ -d "$PREFIX/bin" ] || { blank; bad "This installer runs inside Termux."; blank; exit 1; }
OLD_VERSION=""
if [ -x "$PREFIX/bin/kokoro" ] && grep -q '^# kokomux' "$PREFIX/bin/kokoro" 2>/dev/null; then
  MODE=update
  OLD_VERSION="$(sed -n 's/^KOKOMUX_VERSION="\(.*\)"$/\1/p' "$PREFIX/bin/kokoro" | head -n 1)"
else
  MODE=install
fi

heading "Checking your phone"
problems=0
arch="$(uname -m)"
case "$arch" in
  aarch64|arm64|x86_64) ok "64-bit processor" "$arch" ;;
  *) bad "Kokoro needs a 64-bit phone" "this one is $arch"; problems=1 ;;
esac

android="$(getprop ro.build.version.release 2>/dev/null || true)"
[ -n "$android" ] && ok "Android $android"

if [ -n "${TERMUX_VERSION:-}" ]; then
  ok "Termux $TERMUX_VERSION"
else
  warn "Termux looks old" "get the F-Droid or GitHub build if anything fails"
fi

free_kb="$(df -Pk "$HOME" 2>/dev/null | awk 'NR == 2 { print $4 }')" || true
need_mb=700; [ "$MODE" = update ] && need_mb=200
if [[ "${free_kb:-}" =~ ^[0-9]+$ ]]; then
  free_mb=$(( free_kb / 1024 ))
  if [ "$free_mb" -ge 1024 ]; then free_h="$(awk "BEGIN { printf \"%.1f GB\", $free_mb / 1024 }")"; else free_h="$free_mb MB"; fi
  if [ "$free_mb" -ge "$need_mb" ]; then
    ok "$free_h free"
  else
    bad "Only $free_h free" "kokomux needs about $need_mb MB"; problems=1
  fi
fi

# Any HTTP answer means we can reach the host (a proxy may refuse a HEAD).
reachable() { [ "$(curl -s -o /dev/null -m 12 -I -w '%{http_code}' "$1" 2>/dev/null)" != 000 ]; }
if reachable "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin" &&
   reachable "https://pypi.org/simple/kokoro-onnx/"; then
  ok "Online"
else
  bad "Can't reach the download servers" "check your internet connection"; problems=1
fi

if [ "$problems" = 1 ]; then
  blank; wrap "  " "Fix the items marked ✗ above, then run the installer again."; blank
  exit 1
fi

# ================================================================ plan

if [ "$MODE" = install ]; then
  heading "Here's the plan"
  bullet "A small Debian system to run Kokoro in"
  bullet "Python, Kokoro and 54 voices (a 250 MB download)"
  bullet "Whistle, which lights up each word as it's read"
  bullet "The kokoro command, plus Share and widget shortcuts"
  blank
  wrap "  " "${D}Takes 5–15 minutes and about 700 MB. You can use your phone meanwhile; it stays awake until done.${R}"
else
  if [ "$OLD_VERSION" = "$KOKOMUX_VERSION" ]; then
    heading "Reinstalling kokomux v$KOKOMUX_VERSION"
  elif [ -n "$OLD_VERSION" ]; then
    heading "Updating kokomux ${D}v$OLD_VERSION${R}${B} → ${ACC}v$KOKOMUX_VERSION"
  else
    heading "Updating kokomux to ${ACC}v$KOKOMUX_VERSION"
  fi
  say "  ${D}Your settings and downloaded voices are kept.${R}"
fi

if [ "$ASSUME_YES" = 0 ] && [ -r /dev/tty ] && [ -t 1 ]; then
  blank
  printf '  %sReady?%s %s[Y/n]%s ' "$B" "$R" "$D" "$R"
  read -r answer < /dev/tty || answer=""
  case "$answer" in [Nn]*) blank; say "  No changes made. See you next time."; blank; exit 0 ;; esac
fi

if command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock >/dev/null 2>&1; then
  WAKELOCK=1
fi

rm -rf "$WORK"; mkdir -p "$WORK"

cat > "$WORK/engine.py" <<'__KOKOMUX_ENGINE__'
#!/usr/bin/env python3
"""kokomux engine. Runs inside the "kokoro" Debian container.

The `kokoro` command in Termux writes a job folder under /tmp/kokoro.XXXXXX
(Termux's $PREFIX/tmp, shared with the container) and runs it either one-off
or through the background server:

  engine.py job /tmp/kokoro.XXXXXX/job      run one job
  engine.py serve [--port N] [--idle SECS]  keep the model loaded, serve jobs
  engine.py voices                          list voice names
  engine.py download                        fetch model files (resumable)

A job folder holds `job` (key=value lines: voice, speed, lang, and optionally
url, out, stream) and `in.txt` (the text, unless url is set). Output goes into
the same folder: `out.<ext>` for a saved file, and with stream=1 one WAV per
chunk in `stream/0001.wav`, `stream/0002.wav`, ... followed by `stream/end`.
"""
import argparse
import hmac
import os
import re
import secrets
import sys
import threading
import time
import urllib.parse
import urllib.request
import zipfile
from html.parser import HTMLParser

APP = os.path.dirname(os.path.abspath(__file__))
if APP not in sys.path:
    sys.path.insert(0, APP)   # words.py lives beside this file
MODELS = os.path.join(APP, "models")
MODEL = os.path.join(MODELS, "kokoro-v1.0.int8.onnx")
VOICES = os.path.join(MODELS, "voices-v1.0.bin")
MODEL_URL = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/"
SERVER_DIR = "/tmp/kokoro-server"
JOB_DIR_RE = re.compile(r"^/tmp/kokoro\.[A-Za-z0-9]+$")
FORMATS = ("wav", "mp3", "ogg", "flac")
USER_AGENT = "Mozilla/5.0 (Linux; Android) kokomux"
MAX_CHUNK = 200  # characters per synthesis chunk
AHEAD = 3        # while listening, parts made ahead of the one being heard
SERVING = False


class JobError(Exception):
    """A problem with the request itself (bad voice, empty text...)."""


def log(msg):
    print(msg, file=sys.stderr, flush=True)


# ------------------------------------------------------------------ model

_tts = None
_tts_lock = threading.Lock()
# One synthesis at a time, across every job the server is running: espeak isn't
# thread-safe, and the phone has only so much CPU. Held per part, not per job, so
# a book playing for hours doesn't block a quick command.
SYNTH_LOCK = threading.Lock()


def tts():
    global _tts
    with _tts_lock:
        if _tts is None:
            from kokoro_onnx import Kokoro
            _tts = Kokoro(MODEL, VOICES)
    return _tts


# ------------------------------------------------------------------ text

def split_text(text):
    """Split into chunks: a short first one (so playback starts quickly), then
    sentences grouped up to MAX_CHUNK characters."""
    pieces = [p.strip() for p in re.split(r"(?<=[.!?…;:])\s+|\s*\n\s*", text)]
    pieces = [p for p in pieces if re.search(r"\w", p)]
    chunks, cur = [], ""
    for p in pieces:
        if not cur:
            cur = p
        elif (not chunks and len(cur) < 40) or (chunks and len(cur) + len(p) < MAX_CHUNK):
            cur += " " + p
        else:
            chunks.append(cur)
            cur = p
    if cur:
        chunks.append(cur)
    return chunks


class _Extractor(HTMLParser):
    """Pull readable text out of a web page: paragraphs, headings, list items.
    Skips menus, sidebars, tables and citations, prefers <article>/<main>
    content when there's enough of it, and stops at "References" etc."""
    SKIP_TAGS = {"script", "style", "noscript", "nav", "header", "footer", "aside",
                 "form", "svg", "button", "select", "template", "iframe", "table",
                 "figure", "sup", "math"}
    # Whole words from class/id/role names that mark non-article parts.
    SKIP_WORDS = {
        "nav", "navbar", "navbox", "navigation", "menu", "footer", "sidebar",
        "comment", "comments", "share", "sharing", "social", "related",
        "reference", "references", "reflist", "infobox", "toc", "hatnote",
        "metadata", "editsection", "ad", "ads", "advert", "advertisement",
        "cookie", "cookies", "banner", "breadcrumb", "breadcrumbs", "subscribe",
        "newsletter", "caption", "thumb", "promo", "popup", "modal", "noprint"}
    NEVER_SKIP = {"html", "body", "main", "article"}
    VOID = {"br", "img", "input", "meta", "link", "hr", "source", "wbr", "area", "col"}
    BLOCK = {"p", "h1", "h2", "h3", "h4", "li", "blockquote", "pre", "dd"}
    HEADINGS = {"h1", "h2", "h3", "h4"}
    END_SECTIONS = {"references", "see also", "external links", "further reading",
                    "notes", "bibliography", "sources", "citations", "footnotes",
                    "related articles", "comments"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.skip_tag, self.skip_depth = None, 0
        self.article = 0
        self.block = None
        self.buf = []
        self.blocks = []  # (in_article, text)
        self.title = ""
        self.in_title = False
        self.done = False

    def handle_starttag(self, tag, attrs):
        if self.skip_tag:
            if tag == self.skip_tag:
                self.skip_depth += 1
            return
        if tag in self.VOID:
            if tag == "br":
                self.buf.append(" ")
            return
        a = dict(attrs)
        hint = f"{a.get('class') or ''} {a.get('id') or ''} {a.get('role') or ''}"
        words = set(re.split(r"[^a-z]+", hint.lower()))
        if tag in self.SKIP_TAGS or (tag not in self.NEVER_SKIP and words & self.SKIP_WORDS):
            self.skip_tag, self.skip_depth = tag, 1
        elif tag in ("article", "main"):
            self.article += 1
        elif tag == "title":
            self.in_title = True
        elif tag in self.BLOCK:
            self._flush()
            self.block = tag

    def handle_endtag(self, tag):
        if self.skip_tag:
            if tag == self.skip_tag:
                self.skip_depth -= 1
                if self.skip_depth == 0:
                    self.skip_tag = None
            return
        if tag in ("article", "main") and self.article:
            self.article -= 1
        elif tag == "title":
            self.in_title = False
        elif tag in self.BLOCK:
            self._flush()

    def handle_data(self, data):
        if self.in_title:
            self.title += data
        elif not self.skip_tag and self.block and not self.done:
            self.buf.append(data)

    def _flush(self):
        t = re.sub(r"\[\s*(?:\d+|[a-z]|edit|citation needed|note \d+)\s*\]", "", "".join(self.buf), flags=re.I)
        t = re.sub(r"\s+", " ", t.replace("¶", "")).strip()
        if t and not self.done:
            if self.block in self.HEADINGS and t.lower().strip(" :") in self.END_SECTIONS:
                self.done = True
            else:
                self.blocks.append((self.article > 0, t))
        self.buf = []
        self.block = None

    def text(self, with_title=True):
        self._flush()
        art = [t for a, t in self.blocks if a]
        use = art if sum(map(len, art)) > 500 else [t for _, t in self.blocks]
        # Headings and list items often lack a full stop; end each block so it
        # gets its own pause.
        use = [t if t[-1] in ".!?…:;" else t + "." for t in use]
        title = self.page_title()
        if with_title and title and (not use or title.split(" - ")[0].split(" | ")[0] not in use[0]):
            use.insert(0, title + ".")
        return "\n".join(use)

    def page_title(self):
        return re.sub(r"\s+", " ", self.title).strip()


def fetch_url(url):
    """A web page's (title, readable text)."""
    if not re.match(r"^https?://", url):
        raise JobError(f"Not a web address: {url}")
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            ctype = r.headers.get_content_type()
            charset = r.headers.get_content_charset() or "utf-8"
            body = r.read(5_000_000).decode(charset, errors="replace")
    except Exception as e:  # noqa: BLE001 - report any network error plainly
        raise JobError(f"Couldn't fetch {url}: {e}") from e
    if ctype == "text/plain":
        return url, body
    if "html" not in ctype and "xml" not in ctype:
        raise JobError(f"That link is {ctype}, not a web page")
    ex = _Extractor()
    ex.feed(body)
    text = ex.text()
    if not text.strip():
        raise JobError("Couldn't find any readable text on that page")
    return ex.page_title() or url, text


# ------------------------------------------------------------------ books

def read_epub(path, name):
    """An EPUB's (title, text): its chapters in reading order, as the reader sees them."""
    import posixpath
    import xml.etree.ElementTree as ET

    def local(tag):
        return tag.rsplit("}", 1)[-1]

    parts = []
    try:
        with zipfile.ZipFile(path) as z:
            names = set(z.namelist())
            if "META-INF/encryption.xml" in names and b"EncryptedData" in z.read("META-INF/encryption.xml"):
                raise JobError("That EPUB is DRM-protected, so its text can't be read")
            container = ET.fromstring(z.read("META-INF/container.xml"))
            opf_path = next(el.get("full-path") for el in container.iter() if local(el.tag) == "rootfile")
            opf = ET.fromstring(z.read(opf_path))
            title = next((el.text for el in opf.iter() if local(el.tag) == "title" and el.text), "")
            manifest = {el.get("id"): el.get("href") for el in opf.iter() if local(el.tag) == "item"}
            spine = [el.get("idref") for el in opf.iter()
                     if local(el.tag) == "itemref" and el.get("linear", "yes") != "no"]
            base = posixpath.dirname(opf_path)
            for idref in spine:
                href = manifest.get(idref)
                if not href:
                    continue
                inner = posixpath.normpath(posixpath.join(base, urllib.parse.unquote(href.split("#")[0])))
                if inner not in names:
                    continue
                ex = _Extractor()
                ex.feed(z.read(inner).decode("utf-8", "replace"))
                chapter = ex.text(with_title=False)
                if chapter.strip():
                    parts.append(chapter)
    except JobError:
        raise
    except (KeyError, StopIteration, zipfile.BadZipFile, ET.ParseError) as e:
        raise JobError(f"Couldn't read that EPUB ({type(e).__name__})") from None
    if not parts:
        raise JobError("That EPUB has no readable text")
    return (title or "").strip() or name, "\n\n".join(parts)


def read_pdf(path, name):
    """A PDF's (title, text), with its lines joined back into sentences."""
    try:
        from pypdf import PdfReader
    except ImportError:
        raise JobError("Reading PDFs needs a newer kokomux: kokoro --update") from None
    try:
        reader = PdfReader(path)
        if reader.is_encrypted and not reader.decrypt(""):
            raise JobError("That PDF is password-protected")
        pages = [page.extract_text() or "" for page in reader.pages]
        title = (reader.metadata.title if reader.metadata else None) or name
    except JobError:
        raise
    except Exception as e:  # noqa: BLE001 - pypdf raises many kinds of error for broken files
        raise JobError(f"Couldn't read that PDF ({type(e).__name__})") from None
    text = re.sub(r"(\w)-\n(\w)", r"\1\2", "\n".join(pages))        # words split across lines
    lines = [ln.strip() for ln in text.splitlines()]
    lines = [ln for ln in lines if ln and not re.fullmatch(r"\d{1,4}", ln)]   # bare page numbers
    sentences, buf = [], ""
    for ln in lines:   # a line that doesn't end a sentence carries on into the next
        buf = f"{buf} {ln}" if buf else ln
        if re.search(r"[.!?:;\"”’)]$", ln):
            sentences.append(buf)
            buf = ""
    if buf:
        sentences.append(buf)
    if not sentences:
        raise JobError("That PDF has no text to read (is it a scan?)")
    return str(title).strip() or name, "\n".join(sentences)


def read_source(jobdir, job):
    """What a job reads, as (title, text): a web page, or a file that is an EPUB,
    a PDF or plain text."""
    name = job.get("name", "")
    if job.get("url"):
        log(f"Fetching {job['url']}")
        return fetch_url(job["url"])
    file = job.get("file", "in.txt")
    if "/" in file:
        raise JobError(f"Bad input file name: {file}")
    path = os.path.join(jobdir, file)
    with open(path, "rb") as fh:
        head = fh.read(64)
    if head.startswith(b"%PDF"):
        return read_pdf(path, name)
    if head.startswith(b"PK") and zipfile.is_zipfile(path):
        return read_epub(path, name)
    with open(path, encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    bad = text.count("\ufffd") + text.count("\x00")
    if text and bad > max(3, len(text) // 100):
        raise JobError("That doesn't look like text, an EPUB or a PDF")
    return name, text


# ------------------------------------------------------------------ jobs

def read_job(path):
    real = os.path.realpath(path)
    jobdir = os.path.dirname(real)
    if not JOB_DIR_RE.match(jobdir) or os.path.basename(real) != "job":
        raise JobError(f"Refusing job outside /tmp/kokoro.*: {path}")
    job = {}
    with open(real, encoding="utf-8") as fh:
        for line in fh:
            key, sep, val = line.rstrip("\n").partition("=")
            if sep:
                job[key] = val
    return jobdir, job


def run_job(path):
    import numpy as np
    import soundfile as sf

    t0 = time.time()
    jobdir, job = read_job(path)
    engine = tts()

    voice = job.get("voice", "af_heart")
    if voice not in engine.get_voices():
        raise JobError(f"Unknown voice '{voice}'. See: kokoro --list-voices")
    try:
        speed = float(job.get("speed", "1.0"))
    except ValueError:
        raise JobError("Speed must be a number, e.g. 1.2") from None
    if not 0.5 <= speed <= 2.0:
        raise JobError("Speed must be between 0.5 and 2.0")
    lang = job.get("lang", "en-us")

    out = job.get("out")
    if out:
        if "/" in out or out.rsplit(".", 1)[-1] not in FORMATS:
            raise JobError(f"Output must be one of: {', '.join(FORMATS)}")
        out = os.path.join(jobdir, out)

    stream = None
    aligner = None
    if job.get("stream") == "1":
        stream = os.path.join(jobdir, "stream")
        os.makedirs(stream, exist_ok=True)
        if job.get("words") == "1":
            aligner = WordAligner(stream, lang)

    writer = None
    total = 0.0
    try:
        title, text = read_source(jobdir, job)
        _write_atomic(os.path.join(jobdir, "title"), (title or "").replace("\n", " ")[:120] + "\n")
        chunks = split_text(text)
        if not chunks:
            raise JobError("No text to read")
        try:
            first = int(job.get("start", "1"))
        except ValueError:
            first = 1
        if not 1 <= first <= len(chunks):
            first = 1   # a bookmark past the end: start again
        if stream:
            _write_atomic(os.path.join(stream, "first"), f"{first}\n")

        for i in range(first, len(chunks) + 1):
            chunk = chunks[i - 1]
            if stream:
                _wait_for_listener(jobdir, stream, i, first)
            # The kokoro command deletes its job folder when it exits or is
            # stopped, so a vanished folder means nobody is waiting any more.
            if not os.path.isdir(jobdir) or (stream and not os.path.isdir(stream)):
                raise JobError("Cancelled")
            _write_atomic(os.path.join(jobdir, "progress"), f"{i} {len(chunks)}\n")
            with SYNTH_LOCK:
                samples, rate = engine.create(chunk, voice=voice, speed=speed, lang=lang)
            total += len(samples) / rate
            if stream:
                # The text first, so the player can show the line the moment its audio lands.
                _write_atomic(os.path.join(stream, f"{i:04d}.txt"), chunk)
                tmp = os.path.join(stream, f"{i:04d}.tmp.wav")
                sf.write(tmp, samples, rate)
                os.replace(tmp, os.path.join(stream, f"{i:04d}.wav"))
                if aligner:
                    aligner.add(i, chunk, samples, rate)
            if out:
                # Written as it's made, so a whole book never has to fit in memory.
                if writer is None:
                    writer = sf.SoundFile(out, "w", samplerate=rate, channels=1)
                else:
                    writer.write(np.zeros(int(rate * 0.2), dtype=np.float32))
                writer.write(samples)
    finally:
        if writer is not None:
            writer.close()
        if stream and os.path.isdir(stream):
            open(os.path.join(stream, "end"), "w").close()
        if aligner:
            aligner.finish()   # timings for any line not yet heard

    elapsed = time.time() - t0
    _write_atomic(os.path.join(jobdir, "result"), f"{total:.1f} {elapsed:.1f} {len(chunks)}\n")
    return f"Made {total:.1f}s of audio in {elapsed:.1f}s"


def _wait_for_listener(jobdir, stream, i, first):
    """While listening, stay at most AHEAD parts ahead of the one being heard, so a
    whole book isn't rendered to disk while its first chapter plays (or is paused)."""
    while os.path.isdir(jobdir):
        try:
            with open(os.path.join(stream, "playing")) as fh:
                playing = int(fh.read().strip() or first)
        except (OSError, ValueError):
            playing = first
        if i <= playing + AHEAD:
            return
        time.sleep(0.2)


def _write_atomic(path, text):
    try:
        with open(path + ".tmp", "w") as fh:
            fh.write(text)
        os.replace(path + ".tmp", path)
    except OSError:
        pass  # the job folder vanished (cancelled); the loop will notice


# ------------------------------------------------------------------ word timings

class WordAligner(threading.Thread):
    """Times the words of a job's lines with Whistle, on the CPU beside speech, and
    publishes each line as stream/NNNN.words the moment it lands; the player lights
    the spoken word from those files. It works from the line being heard (the
    player keeps stream/playing up to date) and drops lines already heard, so a
    job never waits on timings nobody will see."""

    def __init__(self, stream, lang):
        super().__init__(daemon=True)
        self.stream, self.lang = stream, lang
        self.pending, self.done = {}, False
        self.cond = threading.Condition()
        self.off = None
        try:
            import words
            self.words = words
            if not words.available():
                self.off = "Whistle isn't installed (run kokoro --update)"
            elif not words.supported(lang):
                self.off = f"Whistle doesn't transcribe {lang}"
        except ImportError:
            self.off = "word timings aren't installed (run kokoro --update)"
        if self.off:
            _write_atomic(os.path.join(stream, "words-off"), self.off + "\n")
        else:
            self.start()

    def add(self, i, text, samples, rate):
        if self.off:
            return
        with self.cond:
            self.pending[i] = (text, samples, rate)
            self.cond.notify()

    def finish(self):
        if self.off:
            return
        with self.cond:
            self.done = True
            self.cond.notify()
        self.join()

    def _playing(self):
        try:
            with open(os.path.join(self.stream, "playing")) as fh:
                return int(fh.read().strip() or 1)
        except (OSError, ValueError):
            return 1

    def _next(self):
        """The line being heard, or the next one after it; None once every line
        left has already been heard."""
        playing = self._playing()
        for i in [i for i in self.pending if i < playing]:
            del self.pending[i]
        return min(self.pending) if self.pending else None

    def run(self):
        while True:
            with self.cond:
                while not self.pending and not self.done:
                    self.cond.wait()
                i = self._next()
                if i is None:
                    if self.done:
                        return
                    continue
                text, samples, rate = self.pending.pop(i)
            if not os.path.isdir(self.stream):
                return   # cancelled
            try:
                data = self.words.line_words(text, samples, rate, self.lang)
            except Exception as e:  # noqa: BLE001 - one bad line must not stop the others
                log(f"Word timings, line {i}: {type(e).__name__}: {e}")
                data = self.words.empty_result("error")
            _write_atomic(os.path.join(self.stream, f"{i:04d}.words"), self.words.player_rows(text, data))


# ------------------------------------------------------------------ server

def serve(port, idle):
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from urllib.parse import parse_qs, urlparse

    global SERVING
    SERVING = True
    log("Loading model...")
    tts()
    token = secrets.token_hex(16)
    state = {"last": time.time(), "busy": 0}

    class Handler(BaseHTTPRequestHandler):
        def _reply(self, code, text):
            body = (text + "\n").encode()
            self.send_response(code)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self):
            given = self.headers.get("X-Kokoro-Token", "")
            if hmac.compare_digest(given, token):
                return True
            self._reply(403, "bad token")
            return False

        def do_GET(self):
            if urlparse(self.path).path == "/health" and self._authorized():
                self._reply(200, "ok")
            elif urlparse(self.path).path != "/health":
                self._reply(404, "not found")

        def do_POST(self):
            if not self._authorized():
                return
            url = urlparse(self.path)
            if url.path == "/shutdown":
                self._reply(200, "bye")
                threading.Thread(target=httpd.shutdown, daemon=True).start()
                return
            if url.path != "/job":
                self._reply(404, "not found")
                return
            path = parse_qs(url.query).get("path", [""])[0]
            state["busy"] += 1
            try:
                msg = run_job(path)
                self._reply(200, msg)
            except JobError as e:
                self._reply(400, f"Error: {e}")
            except Exception as e:  # noqa: BLE001 - send the error to the client
                self._reply(500, f"Error: {type(e).__name__}: {e}")
            finally:
                state["busy"] -= 1
                state["last"] = time.time()

        def log_message(self, fmt, *args):
            pass

    httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    httpd.daemon_threads = True

    # Publish the token only once we're listening. $PREFIX/tmp is private to
    # Termux, so other apps on the phone can't use the server.
    os.makedirs(SERVER_DIR, exist_ok=True)
    tok = os.path.join(SERVER_DIR, "token")
    with open(tok + ".tmp", "w") as fh:
        fh.write(f"{port} {token}\n")
    os.chmod(tok + ".tmp", 0o600)
    os.replace(tok + ".tmp", tok)

    def watch_idle():
        while True:
            time.sleep(15)
            if idle and not state["busy"] and time.time() - state["last"] > idle:
                log(f"Idle for {idle // 60} min, stopping")
                httpd.shutdown()
                return

    threading.Thread(target=watch_idle, daemon=True).start()
    log(f"Ready on 127.0.0.1:{port}")
    try:
        httpd.serve_forever()
    finally:
        try:
            with open(tok) as fh:
                if token in fh.read():
                    os.remove(tok)
        except OSError:
            pass


# ------------------------------------------------------------------ download

def download():
    os.makedirs(MODELS, exist_ok=True)
    for name, label in (("kokoro-v1.0.int8.onnx", "model"), ("voices-v1.0.bin", "voices")):
        dest = os.path.join(MODELS, name)
        if os.path.exists(dest) and os.path.getsize(dest) > 0:
            print(f"@@progress {label} 100 0 0")
            print(f"{name}: already downloaded", flush=True)
            continue
        part = dest + ".part"
        for attempt in range(1, 6):
            have = os.path.getsize(part) if os.path.exists(part) else 0
            req = urllib.request.Request(MODEL_URL + name, headers={"User-Agent": USER_AGENT})
            if have:
                req.add_header("Range", f"bytes={have}-")
            try:
                with urllib.request.urlopen(req, timeout=60) as r:
                    if have and r.status != 206:
                        have = 0  # server ignored the range; start over
                    size = have + int(r.headers.get("Content-Length", 0))
                    with open(part, "ab" if have else "wb") as fh:
                        done, shown = have, -1
                        while block := r.read(1 << 18):
                            fh.write(block)
                            done += len(block)
                            pct = done * 100 // size if size else 0
                            if pct != shown:
                                shown = pct
                                # "@@progress LABEL PCT DONE_MB TOTAL_MB"
                                print(f"@@progress {label} {pct} {done / 2**20:.0f} {size / 2**20:.0f}", flush=True)
                os.replace(part, dest)
                break
            except OSError as e:
                print(f"{name}: download interrupted ({e}), retry {attempt}/5", flush=True)
                time.sleep(2 * attempt)
        else:
            sys.exit(f"Couldn't download {name}. Run the installer again to resume.")


# ------------------------------------------------------------------ voices

LANGS = {"a": ("American English", "en-us"), "b": ("British English", "en-gb"),
         "e": ("Spanish", "es"), "f": ("French", "fr-fr"), "h": ("Hindi", "hi"),
         "i": ("Italian", "it"), "j": ("Japanese", "ja"),
         "p": ("Brazilian Portuguese", "pt-br"), "z": ("Mandarin Chinese", "cmn")}


def print_voices(names, width, default):
    """Group voices by language and gender, wrapped to the terminal width.
    Colour only when stdout is a terminal."""
    tty = (sys.stdout.isatty() or "--color" in sys.argv) and not os.environ.get("NO_COLOR")
    bold, dim, acc, off = ("\033[1m", "\033[2m", "\033[38;5;141m", "\033[0m") if tty else ("",) * 4
    width = max(30, min(width, 100))
    groups = {}
    for n in names:
        groups.setdefault(n[0], {}).setdefault(n[1] if len(n) > 1 else "?", []).append(n)
    for code in sorted(groups, key=lambda c: list(LANGS).index(c) if c in LANGS else 99):
        title, lang = LANGS.get(code, (f"Other ({code})", ""))
        note = " (basic pronunciation)" if code in "jz" else ""
        print(f"{bold}{title}{off}{dim}{note}" + (f" -l {lang}" if lang else "") + off)
        for g, label in (("f", "female"), ("m", "male")):
            vs = groups[code].get(g)
            if not vs:
                continue
            line, cur = [], 9
            out = []
            for v in vs:
                shown = f"{acc}{v}*{off}" if v == default else v
                w = len(v) + (1 if v == default else 0) + 1
                if cur + w > width and line:
                    out.append(" ".join(line))
                    line, cur = [], 9
                line.append(shown)
                cur += w
            out.append(" ".join(line))
            print(f"  {dim}{label:<7}{off}" + f"\n  {'':<7}".join(out))
        print()
    if default:
        other = "bm_george" if default != "bm_george" else "af_heart"
        print(f"{dim}* your default{off}")
        print(f"{dim}Try another: kokoro -v {other} -p \"Hi\"{off}")


# ------------------------------------------------------------------ main

def main():
    p = argparse.ArgumentParser(prog="engine.py")
    sub = p.add_subparsers(dest="cmd", required=True)
    j = sub.add_parser("job")
    j.add_argument("path")
    s = sub.add_parser("serve")
    s.add_argument("--port", type=int, default=8880)
    s.add_argument("--idle", type=int, default=1800, help="stop after this many idle seconds (0 = never)")
    v = sub.add_parser("voices")
    v.add_argument("--plain", action="store_true", help="one name per line")
    v.add_argument("--width", type=int, default=60)
    v.add_argument("--default", default="")
    v.add_argument("--color", action="store_true", help="colour even when piped")
    sub.add_parser("download")
    sub.add_parser("whistle", help="fetch and load Whistle once (word timings)")
    a = p.parse_args()

    if a.cmd == "job":
        try:
            log(run_job(a.path))
        except JobError as e:
            sys.exit(f"Error: {e}")
    elif a.cmd == "serve":
        serve(a.port, a.idle)
    elif a.cmd == "voices":
        names = sorted(tts().get_voices())
        if a.plain:
            print("\n".join(names))
        else:
            print_voices(names, a.width, a.default)
    elif a.cmd == "download":
        download()
    elif a.cmd == "whistle":
        import words
        t0 = time.time()
        words.load()
        print(f"Whistle ready ({words.engine_name()}, {time.time() - t0:.1f}s)")


if __name__ == "__main__":
    main()
__KOKOMUX_ENGINE__

cat > "$WORK/words.py" <<'__KOKOMUX_WORDS__'
"""Word timings for spoken lines, from Whistle (Cactus Compute).

Runs inside the "kokoro" Debian container, next to engine.py.

  Recognise  Whistle transcribes a rendered line and returns each word it heard
             with start and end times.
  Match      Whistle is not a forced aligner, so its words are paired with the
             line's own words by an edit-distance alignment. Numbers match their
             spoken form ("60" <-> "sixty"), hyphenated words are matched as one
             ("worn-out" <-> "wornout"), and a respelled name takes the run of
             words the voice said for it ("Igdrasil" <-> "Igg Dressel").
  Store      Each line's result is cached as <audio_hash>.words.json:
             {v, engine, confidence, words: [{s, e, a, b, p}]} where s/e are the
             start and end in ms, a/b the character range in the line's text,
             and p the recogniser's confidence in that word.

The matching is a port of light-novel-studio's apps/server/src/words.ts.
"""
import hashlib
import json
import os
import re
import threading

LINE_WORDS_VERSION = 1
# Below this, a line's transcript disagrees with its text too much to trust:
# it gets no highlight rather than a wrong one.
WORDS_MIN_CONFIDENCE = 0.35
# Languages Whistle transcribes. Anything else would come back as a detected,
# useless transcript, so it isn't attempted.
SUPPORTED = {"en", "de", "fr", "es", "it", "nl", "pl"}
CACHE_DIR = "/opt/kokoro/cache/words"
# The longest audio given to Whistle in one pass. Measured with cactus-compute 4.1:
# one pass transcribes 90 s of speech completely, but 120 s comes back cut off at
# ~96 s. Longer lines are cut at their quietest moment into pieces no longer than
# this (one pass is ~10x faster than Whistle's streaming API).
ONE_PASS_MAX_S = 60.0
RATE = 16000     # Whistle listens at 16 kHz

os.environ.setdefault("CACTUS_TELEMETRY", "0")   # kokomux talks to nobody it doesn't have to
os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")


# ---------------------------------------------------------------- the words of a line

_TOKEN = re.compile(r"<[^>\n]*>|[^\W\d_][\w'’]*|\d+")


def supported(language):
    """Whistle's code for a language ("en-us" -> "en"), or None if it can't transcribe it."""
    code = (language or "")[:2].lower()
    return code if code in SUPPORTED else None


def empty_result(engine):
    """Timings for a line that has none usable: never lit, and never transcribed again."""
    return {"v": LINE_WORDS_VERSION, "engine": engine, "confidence": 0, "words": []}


def word_tokens(text):
    """The line's words as (start, end, word), by character. Inline tags are not words,
    and neither is a hyphen: "worn-out" is two words, as it is to a recogniser."""
    return [(m.start(), m.end(), m.group()) for m in _TOKEN.finditer(text) if not m.group().startswith("<")]


_NUMBERS = {
    "zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8,
    "nine": 9, "ten": 10, "eleven": 11, "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15,
    "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19, "twenty": 20, "thirty": 30,
    "forty": 40, "fifty": 50, "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90, "hundred": 100,
    "thousand": 1000,
}
_ORDINALS = {
    "first": 1, "second": 2, "third": 3, "fourth": 4, "fifth": 5, "sixth": 6, "seventh": 7, "eighth": 8,
    "ninth": 9, "tenth": 10, "eleventh": 11, "twelfth": 12, "thirteenth": 13, "fourteenth": 14,
    "fifteenth": 15, "sixteenth": 16, "seventeenth": 17, "eighteenth": 18, "nineteenth": 19,
    "twentieth": 20, "thirtieth": 30, "fortieth": 40, "fiftieth": 50, "sixtieth": 60, "seventieth": 70,
    "eightieth": 80, "ninetieth": 90,
}


def word_key(word):
    """What a word means for matching: "60", "60." and "sixty" are all #60."""
    t = re.sub(r"[^a-z0-9]", "", word.lower())
    m = re.fullmatch(r"(\d{1,6})(?:st|nd|rd|th)?", t)
    if m:
        return f"#{int(m.group(1))}"
    if t in _NUMBERS:
        return f"#{_NUMBERS[t]}"
    if t in _ORDINALS:
        return f"#{_ORDINALS[t]}"
    return re.sub(r"[^\w']|_", "", word.lower().replace("’", "'"))


def align_pairs(written, spoken):
    """For each written word, the index of the spoken word it is, or None.

    Edit-distance alignment (match 0, substitute 1, skip 1) that prefers pairing
    over skipping, so one dropped or inserted word resyncs the rest of the line
    instead of sliding every later word."""
    n, m = len(written), len(spoken)
    dp = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        dp[i][0] = i
    for j in range(1, m + 1):
        dp[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            pair = dp[i - 1][j - 1] + (0 if written[i - 1] == spoken[j - 1] else 1)
            dp[i][j] = min(pair, dp[i - 1][j] + 1, dp[i][j - 1] + 1)
    pairs = [None] * n
    i, j = n, m
    while i > 0 and j > 0:
        same = written[i - 1] == spoken[j - 1]
        if dp[i][j] == dp[i - 1][j - 1] + (0 if same else 1):
            pairs[i - 1] = j - 1   # keep a substituted word pinned to its own moment
            i, j = i - 1, j - 1
        elif dp[i][j] == dp[i - 1][j] + 1:
            i -= 1                 # a written word the recogniser never returned
        else:
            j -= 1                 # a spoken word with nothing written for it
    return pairs


def _units(written, text):
    """Hyphen-joined words matched as one unit ("worn-out" is "wornout" to a recogniser)."""
    out = []
    for i, (s, _e, _w) in enumerate(written):
        if out and text[written[i - 1][1]:s] == "-":
            out[-1].append(i)
        else:
            out.append([i])
    return out


def map_words(display, asr, engine):
    """A line's recogniser output as timings for the words in its text, or None.

    asr is Whistle's word list: [{"word", "start", "end", "probability"}], seconds.
    A recognised word takes its own times; the rest share out the time between the
    words around them, by length, with p = 0. Windows stay in order, never overlap
    and are never empty, so exactly one word is lit at a time."""
    written = word_tokens(display)
    if not written or not asr:
        return None
    groups = _units(written, display)

    def key(group):
        return "".join(word_key(written[i][2]) for i in group)

    spoken = [word_key(w["word"]) for w in asr]
    pairs = align_pairs([key(g) for g in groups], spoken)
    times = [None] * len(written)   # (from_ms, to_ms, p)

    def put(group, start, end, p):
        """Shares one window across the written words it covers, by length."""
        weight = sum(len(written[i][2]) for i in group) or 1
        cursor = start
        for i in group:
            share = (end - start) * len(written[i][2]) / weight
            times[i] = (cursor, cursor + share, p)
            cursor += share

    claimed = {j for j in pairs if j is not None}
    exact = matched = 0
    sure = 0.0
    for g, j in enumerate(pairs):
        if j is None:
            continue
        w = asr[j]
        first = last = j
        same = key(groups[g]) == spoken[j]
        # A word the recogniser didn't quite agree with has usually swallowed its
        # neighbours ("Igdrasil" heard as "Igg Dressel"): it takes any spoken words
        # beside it that nothing else claimed.
        if not same:
            while first > 0 and first - 1 not in claimed:
                first -= 1
                claimed.add(first)
            while last + 1 < len(asr) and last + 1 not in claimed:
                last += 1
                claimed.add(last)
        prob = float(w.get("probability", 0))
        put(groups[g], asr[first]["start"] * 1000, asr[last]["end"] * 1000, prob)
        matched += 1
        sure += prob
        exact += same
    if not matched:
        return None

    mean = sum(max(0.0, w["end"] - w["start"]) for w in asr) / len(asr) * 1000

    def spread(run, at, until):
        """Words between two recognised ones share the time between them."""
        weight = sum(len(key(g)) + 1 for g in run)
        room = max(0.0, until - at)
        cursor = at
        for g in run:
            share = room * (len(key(g)) + 1) / weight if room > 0 else mean
            put(g, cursor, cursor + max(40.0, share), 0.0)
            cursor += max(40.0, share)

    anchors = [g for g, j in enumerate(pairs) if j is not None]
    first, last = anchors[0], anchors[-1]
    if first > 0:
        spread(groups[:first], 0.0, times[groups[first][0]][0])
    for k in range(len(anchors) - 1):
        a, b = groups[anchors[k]], groups[anchors[k + 1]]
        spread(groups[anchors[k] + 1:anchors[k + 1]], times[a[0]][1], times[b[0]][0])
    if last < len(groups) - 1:
        start = times[groups[last][-1]][1]
        spread(groups[last + 1:], start, start + mean * (len(groups) - 1 - last))

    words = []
    floor = 0
    for i, (a, b, _w) in enumerate(written):
        t = times[i] or (floor, floor + mean, 0.0)
        s = max(floor, round(max(0.0, t[0])))
        nxt = times[i + 1][0] if i + 1 < len(written) and times[i + 1] else None
        ceiling = float("inf") if nxt is None else max(s + 1, round(nxt))
        e = min(max(s + 1, round(t[1])), ceiling)
        words.append({"s": int(s), "e": int(e), "a": a, "b": b, "p": round(t[2], 3)})
        floor = e

    return {
        "v": LINE_WORDS_VERSION,
        "engine": engine,
        "confidence": round(exact / len(groups) * (sure / matched), 3),
        "words": words,
    }


# ---------------------------------------------------------------- recognising

_model = None
_model_lock = threading.Lock()


def engine_name():
    try:
        import importlib.metadata
        return "whistle-" + importlib.metadata.version("cactus-compute")
    except Exception:
        return "whistle"


def available():
    import importlib.util
    return importlib.util.find_spec("cactus") is not None


def load():
    """Whistle, loaded once. Offline first: once its engine and weights are cached
    (the installer fetches them), it needs no network, which a phone often lacks.
    Only if something isn't cached yet does it go online to fetch it."""
    global _model
    with _model_lock:
        if _model is None:
            import huggingface_hub.constants as hub
            from cactus.agent.whistle import Whistle
            online = hub.HF_HUB_OFFLINE
            hub.HF_HUB_OFFLINE = True
            try:
                _model = Whistle()
            except Exception:
                hub.HF_HUB_OFFLINE = online
                _model = Whistle()
            finally:
                hub.HF_HUB_OFFLINE = online
    return _model


def to_16k(samples, rate):
    """Kokoro renders 24 kHz; Whistle listens at 16 kHz."""
    import numpy as np
    x = np.asarray(samples, dtype=np.float32).reshape(-1)
    if rate == RATE or not len(x):
        return x
    n = int(len(x) * RATE / rate)
    return np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)


def quiet_cuts(x, longest=ONE_PASS_MAX_S, search=10.0, frame=0.05):
    """Where to cut 16 kHz audio so no piece is longer than `longest` seconds: at the
    quietest 50 ms in the last `search` seconds before each limit, which is almost
    always a pause between words. Returns the piece boundaries in samples."""
    import numpy as np
    step, cuts, start = int(RATE * frame), [0], 0
    while len(x) - start > longest * RATE:
        lo, hi = start + int((longest - search) * RATE), start + int(longest * RATE)
        frames = x[lo:hi][: (hi - lo) // step * step].reshape(-1, step)
        start = lo + int(np.argmin((frames ** 2).mean(axis=1))) * step + step // 2
        cuts.append(start)
    return cuts + [len(x)]


def recognise(samples, rate, language):
    """Whistle's words for one line: [{"word", "start", "end", "probability"}],
    seconds from the start of the line."""
    model = load()
    x = to_16k(samples, rate)
    cuts = quiet_cuts(x)
    words = []
    for a, b in zip(cuts, cuts[1:]):
        out = model.transcribe(x[a:b], language=supported(language), word_timestamps=True)
        for w in out.get("words") or []:
            words.append({**w, "start": w["start"] + a / RATE, "end": w["end"] + a / RATE})
    return words


# ---------------------------------------------------------------- the cache

def audio_hash(samples):
    import numpy as np
    return hashlib.sha256(np.asarray(samples, dtype=np.float32).tobytes()).hexdigest()[:20]


def cache_path(h):
    return os.path.join(CACHE_DIR, f"{h}.words.json")


def read_cached(h):
    try:
        with open(cache_path(h), encoding="utf-8") as fh:
            data = json.load(fh)
        return data if data.get("v") == LINE_WORDS_VERSION else None   # an old mapping is redone
    except (OSError, ValueError):
        return None


def write_cached(h, data):
    os.makedirs(CACHE_DIR, exist_ok=True)
    tmp = cache_path(h) + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, separators=(",", ":"))
    os.replace(tmp, cache_path(h))


def line_words(text, samples, rate, language):
    """The cached or freshly made timings for one rendered line. A line whose
    transcript doesn't match is cached too (confidence 0), so it is never
    transcribed twice for the same answer."""
    h = audio_hash(samples)
    cached = read_cached(h)
    if cached is not None:
        return cached
    if supported(language):
        data = map_words(text, recognise(samples, rate, language), engine_name()) or empty_result(engine_name())
    else:
        data = empty_result("none")
    write_cached(h, data)
    return data


def player_rows(text, data):
    """A line's timings in the form the bash player reads (stream/NNNN.words): a
    "confidence" line, then one "start end a b" row per word, with a/b the word's
    UTF-8 byte range in the line. Below WORDS_MIN_CONFIDENCE there are no rows,
    so nothing is lit."""
    rows = [f"confidence\t{data.get('confidence', 0)}"]
    if data.get("confidence", 0) >= WORDS_MIN_CONFIDENCE:
        for w, (a, b) in zip(data["words"], byte_ranges(text, data["words"])):
            rows.append(f"{w['s']}\t{w['e']}\t{a}\t{b}")
    return "\n".join(rows) + "\n"


def byte_ranges(text, words):
    """The same words as UTF-8 byte ranges, which is what the bash player slices by."""
    out, pos, byte = [], 0, 0
    for w in words:
        byte += len(text[pos:w["a"]].encode("utf-8"))
        a = byte
        byte += len(text[w["a"]:w["b"]].encode("utf-8"))
        pos = w["b"]
        out.append((a, byte))
    return out
__KOKOMUX_WORDS__

cat > "$WORK/setup.sh" <<'__KOKOMUX_SETUP__'
# kokomux: runs inside the "kokoro" Debian container (as root), one phase
# per call, from install.sh:  bash setup.sh apt|pip|models|tidy
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
export PIP_PROGRESS_BAR=off
APP=/opt/kokoro
SRC=/tmp/kokoro-setup

case "${1:-}" in
  apt)
    # Skip docs, man pages and translations for everything apt installs.
    mkdir -p /etc/dpkg/dpkg.cfg.d
    cat > /etc/dpkg/dpkg.cfg.d/01-kokomux-nodoc <<'CFG'
path-exclude=/usr/share/doc/*
path-include=/usr/share/doc/*/copyright
path-exclude=/usr/share/man/*
path-exclude=/usr/share/info/*
path-exclude=/usr/share/locale/*
path-include=/usr/share/locale/locale.alias
CFG
    apt-get update
    apt-get install -y --no-install-recommends python3 python3-venv ca-certificates
    ;;
  pip)
    mkdir -p "$APP"
    [ -x "$APP/venv/bin/python" ] || python3 -m venv "$APP/venv"
    "$APP/venv/bin/pip" install --upgrade pip
    # cactus-compute is Whistle, the recogniser behind word highlighting; pypdf reads PDFs.
    "$APP/venv/bin/pip" install --upgrade kokoro-onnx soundfile cactus-compute pypdf | tee /tmp/kokoro-setup/.pip
    grep -q '^Successfully installed' /tmp/kokoro-setup/.pip || printf 'up to date' > "$SRC/.note"
    cp "$SRC/engine.py" "$SRC/words.py" "$APP/"
    rm -f "$APP/speak.py"   # from older versions
    ;;
  models)
    if [ -s "$APP/models/kokoro-v1.0.int8.onnx" ] && [ -s "$APP/models/voices-v1.0.bin" ]; then
      printf 'already downloaded' > "$SRC/.note"
    fi
    "$APP/venv/bin/python" -u "$APP/engine.py" download
    # Whistle's engine and weights (~17 MB), fetched now so the first -p doesn't
    # wait, and so highlighting works offline. Optional: kokomux reads without it.
    echo "@@progress whistle 0 0 17"
    if "$APP/venv/bin/python" -u "$APP/engine.py" whistle; then
      echo "@@progress whistle 100 17 17"
    else
      echo "Whistle couldn't be fetched; words won't be highlighted until it can." >&2
    fi
    ;;
  tidy)
    apt-get clean
    rm -rf /var/lib/apt/lists/* /root/.cache/pip /usr/share/man/* /usr/share/info/*
    find /usr/share/doc -mindepth 2 ! -name copyright -delete 2>/dev/null || true
    ;;
  *)
    echo "usage: setup.sh apt|pip|models|tidy" >&2; exit 2 ;;
esac
__KOKOMUX_SETUP__

cat > "$WORK/kokoro" <<'__KOKOMUX_WRAPPER__'
#!/data/data/com.termux/files/usr/bin/bash
# kokomux: the `kokoro` command. https://github.com/Anesu/kokomux
#
# Turns text into speech with Kokoro, which lives in the "kokoro"
# proot-distro container. Jobs go through $PREFIX/tmp, which the container
# sees as /tmp. If the background server is running, jobs go to it over
# localhost (fast); otherwise each call starts the container (slower).
set -euo pipefail

KOKOMUX_VERSION="0.5.0"
CONF_DIR="$HOME/.config/kokoro"
CONF="$CONF_DIR/config"

# Precedence: environment (KOKORO_VOICE=am_adam kokoro ...) > settings file
# > these defaults.
env_names="${!KOKORO_@}"
env_overrides=""
# shellcheck disable=SC2086
[ -n "$env_names" ] && env_overrides="$(declare -p $env_names)"
KOKORO_VOICE=af_heart
KOKORO_SPEED=1.0
KOKORO_LANG=en-us
KOKORO_OUT=kokoro.wav
KOKORO_AUTOSERVE=1
KOKORO_IDLE_MINUTES=30
KOKORO_PORT=8880
KOKORO_WORDS=1                 # light up each word as it's spoken (-p on a terminal)
KOKORO_WORDS_OFFSET_MS=100     # play-audio's start-up delay, so the highlight isn't early
KOKORO_NOTIFY=1                # a notification with pause / next / again buttons (Termux:API)
KOKORO_REPO_RAW=https://raw.githubusercontent.com/Anesu/kokomux/main
# shellcheck source=/dev/null
[ -f "$CONF" ] && . "$CONF"
eval "$env_overrides"

DISTRO=kokoro
PY=/opt/kokoro/venv/bin/python
ENGINE=/opt/kokoro/engine.py
SRV_DIR="$PREFIX/tmp/kokoro-server"
TOKEN_FILE="$SRV_DIR/token"
PLAYER_PID="$PREFIX/tmp/kokoro-player.pid"
CONTROL="$PREFIX/tmp/kokoro-control"      # kokoro --pause/--next/--again leave a word here
PLAYER_STATE="$PREFIX/tmp/kokoro-state"   # "playing" or "paused", for --pause to report
BOOKS="$HOME/.local/share/kokomux/bookmarks"

# ------------------------------------------------------------ look & feel
# Messages go to stderr. Colours and live status only on a terminal, so
# scripts and pipes get plain, quiet output.

LIVE=0
if [ -t 2 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-dumb}" != dumb ]; then LIVE=1; fi
if [ "$LIVE" = 1 ]; then
  R=$'\033[0m' B=$'\033[1m' D=$'\033[2m' ACC=$'\033[38;5;141m' ACC2=$'\033[38;5;117m'
  GRN=$'\033[38;5;114m' RED=$'\033[38;5;203m' YEL=$'\033[38;5;221m'
else
  R="" B="" D="" ACC="" ACC2="" GRN="" RED="" YEL=""
fi
SPIN=(⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏)
frame=0

cols() {
  local c
  c="$(stty size 2>/dev/null </dev/tty | cut -d' ' -f2)" || true
  [[ "${c:-}" =~ ^[0-9]+$ ]] && [ "$c" -ge 20 ] && echo "$c" || echo 60
}

# die "what went wrong" ["what to do about it"]
die() {
  status_clear
  printf '%s✗%s %s\n' "$RED" "$R" "$1" >&2
  [ -n "${2:-}" ] && printf '  %s%s%s\n' "$D" "$2" "$R" >&2
  exit 1
}
done_msg() { printf '%s✓%s %s%s\n' "$GRN" "$R" "$1" "${2:+ $D$2$R}" >&2; }
info() { printf '%s›%s %s\n' "$ACC" "$R" "$1" >&2; }

# One live status line, never wider than the screen (a wrapped line can't
# be redrawn in place): status "text" ["dim extra"] ["symbol"]
TERM_COLS=60; [ "$LIVE" = 1 ] && TERM_COLS="$(cols)"
status() {
  [ "$LIVE" = 1 ] || return 0
  local main=$1 extra=${2:-} sym=${3:-${SPIN[frame % 10]}} vis room
  vis="$(printf '%s' "$main" | sed 's/\x1b\[[0-9;]*m//g')"
  room=$(( TERM_COLS - 4 - ${#vis} ))
  if [ "$room" -lt 4 ]; then extra=""
  elif [ "${#extra}" -gt "$room" ]; then extra="${extra:0:$((room - 1))}…"; fi
  printf '\r\033[K%s%s%s %s %s%s%s' "$ACC" "$sym" "$R" "$main" "$D" "$extra" "$R" >&2
  frame=$((frame + 1))
}
status_clear() { [ "$LIVE" = 1 ] && printf '\r\033[K' >&2; return 0; }

# shellcheck disable=SC2088  # a literal ~ is the point
tilde() { case "$1" in "$HOME"/*) printf '~/%s' "${1#"$HOME"/}" ;; *) printf '%s' "$1" ;; esac; }
human_size() {
  local b=$1
  if [ "$b" -ge 1048576 ]; then awk "BEGIN { printf \"%.1f MB\", $b / 1048576 }"
  else echo "$(( (b + 1023) / 1024 )) KB"; fi
}
human_secs() {
  awk -v s="$1" 'BEGIN { if (s >= 60) printf "%dm %02ds", s / 60, s % 60; else printf "%.1fs", s }'
}

# Option rows "flags|what it does": two columns on wide screens, stacked on
# narrow ones (phones in portrait).
opt_rows() {
  local w o d
  w="$(cols)"
  while IFS='|' read -r o d; do
    if [ "$w" -ge 72 ]; then printf '  %s%-18s%s %s\n' "$ACC2" "$o" "$R" "$d"
    else printf '  %s%s%s\n      %s%s%s\n' "$ACC2" "$o" "$R" "$D" "$d" "$R"; fi
  done
}

usage() {
  printf '%skokoro%s %sv%s%s\n' "$B" "$R" "$ACC" "$KOKOMUX_VERSION" "$R"
  printf '%sKokoro text-to-speech for Termux%s\n\n' "$D" "$R"
  printf '%sUsage%s kokoro [options] [text]\n' "$B" "$R"
  printf '%sText can also come from -f, -c, -u or a pipe.%s\n' "$D" "$R"
  printf '\n%sSpeak%s\n' "$B" "$R"
  opt_rows <<'ROWS'
-p, --play|play as it's made
-o FILE|save as .wav .mp3 .ogg or .flac
-v VOICE|voice, e.g. af_heart, bm_george
-s SPEED|0.5 to 2.0
-l LANG|en-us en-gb es fr-fr it pt-br hi
ROWS
  printf '\n%sRead from%s\n' "$B" "$R"
  opt_rows <<'ROWS'
-f FILE|a text file, EPUB or PDF
-c, --clipboard|the clipboard (or the link in it)
-u URL|a web page
ROWS
  printf '\n%sWhile listening%s\n' "$B" "$R"
  opt_rows <<'ROWS'
--pause|pause, or carry on
--next|skip to the next part
--again|hear this part again
--stop|stop
--continue|carry on with the last long text
--restart|with -p: start from the top, not the bookmark
ROWS
  printf '\n%sMore%s\n' "$B" "$R"
  opt_rows <<'ROWS'
--list-voices|all 54 voices by language
--status|server status and settings
--config|change your defaults
--serve / --stop-server|start or stop the server
--update|update kokomux and Kokoro
--uninstall|remove kokomux
ROWS
  printf '\n%sExamples%s\n' "$B" "$R"
  printf '  kokoro -p "Hello there"\n  kokoro -c -p\n  kokoro -u URL -o page.mp3\n'
  printf '\n%sWithout -p, audio is saved to %s.%s\n' "$D" "$KOKORO_OUT" "$R"
}

# ------------------------------------------------------------ server

server_token() { [ -f "$TOKEN_FILE" ] && cut -d' ' -f2 "$TOKEN_FILE"; }
server_port() { [ -f "$TOKEN_FILE" ] && cut -d' ' -f1 "$TOKEN_FILE"; }

server_up() {
  local tok port
  tok="$(server_token 2>/dev/null)" || return 1
  port="$(server_port)"
  curl -fsS -m 2 -H "X-Kokoro-Token: $tok" "http://127.0.0.1:$port/health" >/dev/null 2>&1
}

start_server() {
  server_up && return 0
  mkdir -p "$SRV_DIR"
  rm -f "$TOKEN_FILE"
  # Ignore Ctrl+C and hang-ups so the server outlives the command that started it.
  ( trap '' INT HUP
    exec nohup proot-distro login "$DISTRO" --shared-tmp -- \
      "$PY" "$ENGINE" serve --port "$KOKORO_PORT" --idle "$((KOKORO_IDLE_MINUTES * 60))"
  ) > "$SRV_DIR/log" 2>&1 < /dev/null &
  local pid=$! n=0
  [ "$LIVE" = 1 ] || echo "Starting the Kokoro server..." >&2
  while [ "$n" -lt 1200 ]; do          # up to 2 minutes
    if [ $((n % 5)) = 0 ]; then
      server_up && { status_clear; return 0; }
      kill -0 "$pid" 2>/dev/null || break
    fi
    status "Warming up Kokoro" "(a few seconds)"
    sleep 0.1; n=$((n + 1))
  done
  status_clear
  printf '%s✗%s The Kokoro server didn'"'"'t start.\n' "$RED" "$R" >&2
  tail -n 5 "$SRV_DIR/log" 2>/dev/null | sed "s/^/  $D/; s/\$/$R/" >&2 || true
  printf '  %sFull log: %s%s\n' "$D" "${SRV_DIR}/log" "$R" >&2
  return 1
}

stop_server() {
  if ! server_up; then info "The server isn't running."; return 0; fi
  curl -fsS -m 5 -X POST -H "X-Kokoro-Token: $(server_token)" \
    "http://127.0.0.1:$(server_port)/shutdown" >/dev/null || true
  local i
  for i in $(seq 1 20); do server_up || break; sleep 0.25; done
  done_msg "Server stopped." "It restarts when needed."
}

# ------------------------------------------------------------ playback

player_alive() { local pid; pid="$(cat "$PLAYER_PID" 2>/dev/null)" && [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; }

# control WORD MESSAGE: tell the running player to pause, skip or replay
control() {
  player_alive || { info "Nothing is playing."; exit 0; }
  echo "$1" > "$CONTROL"
  [ -n "${2:-}" ] && done_msg "$2"
  return 0
}

# Bookmarks: where a long text was stopped, as key=value lines.
mark_get() { sed -n "s/^$2=//p" "$1" 2>/dev/null | head -n 1; }

stop_playback() {
  local pid
  pid="$(cat "$PLAYER_PID" 2>/dev/null)" || return 1
  rm -f "$PLAYER_PID"
  [ -n "$pid" ] && [ "$pid" != "$$" ] && kill "$pid" 2>/dev/null
}

# ------------------------------------------------------------ admin commands

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  --version) echo "kokomux $KOKOMUX_VERSION"; exit 0 ;;
  --stop)
    if stop_playback; then done_msg "Stopped."; else info "Nothing is playing."; fi
    exit 0 ;;
  --pause)
    if [ "$(cat "$PLAYER_STATE" 2>/dev/null)" = paused ]; then control toggle "Playing again."
    else control toggle "Paused." ; fi
    exit 0 ;;
  --next) control next "Skipping to the next part."; exit 0 ;;
  --again) control again "Playing this part again."; exit 0 ;;
  --continue)
    last="$(ls -t "$BOOKS"/*.mark 2>/dev/null | head -n 1)" || true
    [ -n "$last" ] || die "There's nothing to continue." "A bookmark is kept when you stop a long text partway."
    case "$(mark_get "$last" kind)" in
      url)  src=(-u "$(mark_get "$last" source)") ;;
      text) src=(-f "${last%.mark}.txt") ;;
      *)    src=(-f "$(mark_get "$last" source)")
            [ -f "${src[1]}" ] || die "Can't find \"${src[1]}\" any more." "Has the file moved? Open it again with kokoro -p -f." ;;
    esac
    exec "$0" -p -v "$(mark_get "$last" voice)" -s "$(mark_get "$last" speed)" -l "$(mark_get "$last" lang)" "${src[@]}" ;;
  --serve)
    if server_up; then info "The server is already running."; exit 0; fi
    start_server || exit 1
    done_msg "Server ready." "port $(server_port) · stops after ${KOKORO_IDLE_MINUTES} min unused"
    exit 0 ;;
  --stop-server) stop_server; exit 0 ;;
  --status)
    printf '%skokomux%s %sv%s%s\n\n' "$B" "$R" "$ACC" "$KOKOMUX_VERSION" "$R"
    if server_up; then
      printf '  %s●%s Server    %srunning%s %s(port %s)%s\n' "$GRN" "$R" "$GRN" "$R" "$D" "$(server_port)" "$R"
    elif [ "$KOKORO_AUTOSERVE" = 1 ]; then
      printf '  %s○%s Server    sleeping %s(wakes on next use)%s\n' "$D" "$R" "$D" "$R"
    else
      printf '  %s○%s Server    off %s(KOKORO_AUTOSERVE=0)%s\n' "$D" "$R" "$D" "$R"
    fi
    idle="${KOKORO_IDLE_MINUTES} min"; [ "$KOKORO_IDLE_MINUTES" = 0 ] && idle="never"
    printf '    Voice     %s%s%s\n' "$ACC2" "$KOKORO_VOICE" "$R"
    printf '    Speed     %s\n' "$KOKORO_SPEED"
    printf '    Language  %s\n' "$KOKORO_LANG"
    printf '    Idle stop %s\n' "$idle"
    last="$(ls -t "$BOOKS"/*.mark 2>/dev/null | head -n 1)" || true
    if [ -n "$last" ]; then
      printf '    Bookmark  %s %s(part %s of %s · kokoro --continue)%s\n' "$(mark_get "$last" title)" "$D" \
        "$(mark_get "$last" part)" "$(mark_get "$last" parts)" "$R"
    fi
    printf '    Settings  %s%s%s\n' "$D" "$(tilde "$CONF")" "$R"
    printf '    %sChange them with: kokoro --config%s\n' "$D" "$R"
    exit 0 ;;
  --config)
    [ -f "$CONF" ] || die "There's no settings file at $(tilde "$CONF")." "Run the installer again to create it."
    exec "${EDITOR:-nano}" "$CONF" ;;
  --update)
    server_up && stop_server >/dev/null 2>&1
    tmp="$(mktemp "$PREFIX/tmp/kokomux-install.XXXXXX")"
    if curl -fsSL "$KOKORO_REPO_RAW/install.sh" -o "$tmp" 2>/dev/null; then
      rc=0; bash "$tmp" --yes || rc=$?; rm -f "$tmp"; exit "$rc"
    fi
    rm -f "$tmp"
    printf '%s!%s Couldn'"'"'t download the latest installer %s(is the repo private?)%s\n' "$YEL" "$R" "$D" "$R" >&2
    info "Updating Kokoro's Python packages only..."
    exec proot-distro login "$DISTRO" -- "${PY%/python}/pip" install --no-cache-dir --upgrade kokoro-onnx soundfile ;;
  --uninstall)
    printf '%s\n\n' "This removes the kokoro command, its Debian container, the voices, your settings and shortcuts." |
      fold -s -w "$(( $(cols) - 1 ))" >&2
    read -r -p "Uninstall kokomux? [y/N] " ans
    [[ "$ans" =~ ^[Yy] ]] || { info "Nothing removed."; exit 0; }
    stop_playback || true
    server_up && stop_server >/dev/null 2>&1
    proot-distro remove "$DISTRO" >/dev/null 2>&1 && done_msg "Debian container removed" || true
    for f in "$HOME/bin/termux-url-opener" "$HOME/bin/termux-file-editor" \
             "$HOME/.shortcuts/tasks/"Kokoro*; do
      if [ -f "$f" ] && grep -q '^# kokomux' "$f"; then rm -f "$f"; done_msg "Removed $(tilde "$f")"; fi
    done
    rm -rf "$CONF_DIR" "$SRV_DIR" "$HOME/kokoro-test.wav" "$HOME/.cache/kokomux" "$HOME/.local/share/kokomux"
    done_msg "Settings removed"
    rm -f "$0"
    done_msg "kokomux is uninstalled." "Thanks for trying it!"
    exit 0 ;;
  --list-voices)
    status "Loading voices"
    flags=(--width "$(cols)" --default "$KOKORO_VOICE")
    [ "$LIVE" = 1 ] && [ -t 1 ] && flags+=(--color)
    errf="$(mktemp "$PREFIX/tmp/kokoro-err.XXXXXX")"
    out="$(proot-distro login "$DISTRO" -- "$PY" "$ENGINE" voices "${flags[@]}" 2>"$errf")" ||
      die "Couldn't list the voices." "$(tail -n 1 "$errf"; rm -f "$errf")"
    rm -f "$errf"
    status_clear
    printf '%s\n' "$out"
    exit 0 ;;
esac

# ------------------------------------------------------------ options

out=""; infile=""; url=""; play=0; clip=0; restart=0; words=()
voice="$KOKORO_VOICE"; speed="$KOKORO_SPEED"; lang="$KOKORO_LANG"
need() { [ $# -ge 2 ] || die "$1 needs a value." "See: kokoro --help"; }
while [ $# -gt 0 ]; do
  case "$1" in
    -o|--out)       need "$@"; out="$2"; shift 2 ;;
    --out=*)        out="${1#*=}"; shift ;;
    -f|--file)      need "$@"; infile="$2"; shift 2 ;;
    --file=*)       infile="${1#*=}"; shift ;;
    -u|--url)       need "$@"; url="$2"; shift 2 ;;
    --url=*)        url="${1#*=}"; shift ;;
    -v|--voice)     need "$@"; voice="$2"; shift 2 ;;
    --voice=*)      voice="${1#*=}"; shift ;;
    -s|--speed)     need "$@"; speed="$2"; shift 2 ;;
    --speed=*)      speed="${1#*=}"; shift ;;
    -l|--lang)      need "$@"; lang="$2"; shift 2 ;;
    --lang=*)       lang="${1#*=}"; shift ;;
    -p|--play)      play=1; shift ;;
    -c|--clipboard) clip=1; shift ;;
    --restart)      restart=1; shift ;;
    --)             shift; words+=("$@"); break ;;
    -?*)            die "Unknown option $1" "See: kokoro --help" ;;
    *)              words+=("$1"); shift ;;
  esac
done

[[ "$voice" =~ ^[A-Za-z0-9_]+$ ]] || die "\"$voice\" isn't a voice name." "See: kokoro --list-voices"
[[ "$speed" =~ ^[0-9]*\.?[0-9]+$ ]] || die "Speed must be a number, like 1.2"
[[ "$lang" =~ ^[A-Za-z0-9-]+$ ]] || die "\"$lang\" isn't a language code." "Try en-us, en-gb, es, fr-fr, it, pt-br or hi."

[ "$play" = 1 ] || out="${out:-$KOKORO_OUT}"
ext=""
if [ -n "$out" ]; then
  ext="$(printf '%s' "${out##*.}" | tr '[:upper:]' '[:lower:]')"
  case "$ext" in wav|mp3|ogg|flac) ;; *) die "Can't save as \"$out\"." "End the name with .wav, .mp3, .ogg or .flac" ;; esac
  [ -d "$(dirname "$out")" ] || die "The folder for \"$out\" doesn't exist."
fi
if [ "$play" = 1 ] && ! command -v play-audio >/dev/null; then
  die "Playback needs play-audio." "Install it with: pkg install play-audio"
fi

# ------------------------------------------------------------ job folder

tmpd="$(mktemp -d "$PREFIX/tmp/kokoro.XXXXXX")"
gen_pid=""; play_pid=""
cleanup() {
  set +e   # best effort from here: a process that's already gone mustn't change the exit code
  status_clear
  [ "${in_line:-0}" = 1 ] && [ "${show_words:-0}" = 1 ] && block_finish   # stopped mid-line: nothing lit
  if [ "${play:-0}" = 1 ]; then notify_clear; rm -f "$PLAYER_STATE"; fi
  [ -n "$play_pid" ] && kill "$play_pid" 2>/dev/null
  [ -n "$gen_pid" ] && kill "$gen_pid" 2>/dev/null
  rm -rf "$tmpd"
  if [ "$play" = 1 ] && [ "$(cat "$PLAYER_PID" 2>/dev/null)" = "$$" ]; then rm -f "$PLAYER_PID"; fi
  return 0
}
trap cleanup EXIT
# A stop signal (Ctrl+C, kokoro --stop) during the frame timer must not exit from
# inside `read -t`: if cleanup then outlives the timer, bash's alarm jumps back into
# a read that's gone and bash crashes (seen with bash 5.2). So during the timer the
# signal only sets a flag, and tick() exits once read has returned.
STOP=""; IN_TICK=0
on_signal() { if [ "$IN_TICK" = 1 ]; then STOP=1; else exit 130; fi; }
trap on_signal INT TERM HUP

text="$tmpd/in.txt"
book=0
if [ -n "$url" ]; then
  :
elif [ "$clip" = 1 ]; then
  command -v termux-clipboard-get >/dev/null ||
    die "Reading the clipboard needs Termux:API." "Install the Termux:API app, then: pkg install termux-api"
  status "Reading the clipboard"
  timeout 15 termux-clipboard-get > "$text" ||
    die "Couldn't read the clipboard." "Is the Termux:API app installed? Get it where you got Termux."
elif [ -n "$infile" ]; then
  [ -f "$infile" ] || die "There's no file called \"$infile\"."
  cp "$infile" "$text"
  magic=""; read -r -n 4 magic < "$infile" || true
  case "$magic" in %PDF*|PK*) book=1 ;; esac   # a PDF or EPUB: the engine reads it
elif [ ${#words[@]} -gt 0 ]; then
  printf '%s\n' "${words[*]}" > "$text"
elif [ ! -t 0 ]; then
  cat > "$text"
else
  usage >&2; exit 2
fi

# A copied link on its own means "read that page".
if [ -z "$url" ] && [ "$book" = 0 ] && [ "$(wc -l < "$text")" -le 1 ] &&
   grep -qE '^[[:space:]]*https?://[^[:space:]]+[[:space:]]*$' "$text"; then
  url="$(tr -d '[:space:]' < "$text")"
fi
if [ -z "$url" ] && [ "$book" = 0 ] && ! grep -q '[^[:space:]]' "$text"; then
  [ "$clip" = 1 ] && die "The clipboard is empty."
  die "There's no text to read."
fi
[[ "$url" != *$'\n'* ]] || die "That doesn't look like a web address."

# What it's called (the engine may find a better title inside), and, when
# listening, a bookmark keyed by what's being read: the same text, file or page
# picks up where it was stopped.
if [ -n "$url" ]; then kind=url source=$url name=$url
elif [ -n "$infile" ]; then kind=file source="$(realpath "$infile")" name="$(basename "$infile")"
else kind=text source="" name="$(head -c 60 "$text" | tr '\n' ' ')"; fi
book_id=""; start=1
if [ "$play" = 1 ] && [ -z "$out" ]; then
  if [ "$kind" = url ]; then book_id="$(printf '%s' "$url" | sha256sum | cut -c1-16)"
  else book_id="$(sha256sum < "$text" | cut -c1-16)"; fi
  mark="$BOOKS/$book_id.mark"
  if [ "$restart" = 1 ]; then rm -f "$mark" "$BOOKS/$book_id.txt"
  elif [ -f "$mark" ] && [ "$(mark_get "$mark" part)" -gt 1 ] 2>/dev/null; then
    start="$(mark_get "$mark" part)"
    info "Picking up at part $start of $(mark_get "$mark" parts)."
    printf '  %s(--restart starts from the top)%s\n' "$D" "$R" >&2
  fi
fi

# With -p on a terminal, show each line as it's read and light the spoken word.
show_words=0
[ "$play" = 1 ] && [ "$LIVE" = 1 ] && [ "$KOKORO_WORDS" = 1 ] && show_words=1

{
  echo "voice=$voice"
  echo "speed=$speed"
  echo "lang=$lang"
  [ -n "$url" ] && echo "url=$url"
  echo "name=$name"
  [ "$start" -gt 1 ] && echo "start=$start"
  [ -n "$out" ] && echo "out=out.$ext"
  [ "$play" = 1 ] && echo "stream=1"
  [ "$show_words" = 1 ] && echo "words=1"
} > "$tmpd/job"
job="/tmp/$(basename "$tmpd")/job"   # the same file, as the container sees it

# Use the server if it's up (or start it); otherwise run the job directly.
use_server=0
if server_up || { [ "$KOKORO_AUTOSERVE" = 1 ] && start_server; }; then use_server=1; fi

run_engine() {
  if [ "$use_server" = 1 ]; then
    local rc=0
    curl -s --fail-with-body -X POST -H "X-Kokoro-Token: $(server_token)" \
      "http://127.0.0.1:$(server_port)/job?path=$job" || rc=$?
    # 22 = the server answered with an error, which it has already printed.
    [ "$rc" = 0 ] || [ "$rc" = 22 ] || echo "Error: lost contact with the Kokoro server (curl error $rc)"
    [ "$rc" = 0 ] || return 1
  else
    proot-distro login "$DISTRO" --shared-tmp -- "$PY" "$ENGINE" job "$job"
  fi
}

# What's happening, for the live status line.
progress() {
  local p n i
  if [ -f "$tmpd/progress" ] && read -r i n < "$tmpd/progress"; then
    if [ "$n" -gt 1 ]; then p="part $i of $n"; else p=""; fi
    echo "$p"; return
  fi
  echo ""
}
waiting_label() {
  if [ "$play" = 1 ] && [ "$i" -gt "$start" ]; then echo "Preparing"
  elif [ -f "$tmpd/progress" ]; then echo "Making speech"
  elif [ -n "$url" ]; then echo "Fetching the page"
  else echo "Getting ready"; fi
}

# ------------------------------------------------------------ the line being heard
# Each line is printed wrapped to the screen while it plays, and a soft
# highlight moves from word to word. Times come from Whistle via the engine
# (stream/NNNN.words: "confidence", then "start end a b" per word, with a/b the
# word's byte range in NNNN.txt). play-audio has no clock to read, so the time
# is how long it has been playing, less its start-up delay.
# A line taller than the screen is shown as a window of rows that follows the
# lit word, then printed in full when it ends.

HL=$'\033[48;5;61m\033[38;5;231m'   # the soft highlight
blk_lines=(); blk_offs=(); view_top=0; view_h=0
w_s=(); w_e=(); w_a=(); w_b=(); w_state=none
TERM_ROWS=24
exec {TICK}<> <(:)                    # read -t on this is a sleep with no process
tick() { IN_TICK=1; read -r -t "$1" -u "$TICK" _ || true; IN_TICK=0; [ -z "$STOP" ] || exit 130; }
now_ms() { local t=${EPOCHREALTIME//[!0-9]/}; NOW=$(( t / 1000 )); }   # no subshell: called every frame
in_line=0

part_of() {      # PART="part I of N · " while a job has more than one part
  local parts=1
  [ -f "$tmpd/progress" ] && { read -r _ parts < "$tmpd/progress" || parts=1; }
  PART=""; [ "${parts:-1}" -gt 1 ] && PART="part $1 of $parts · "
  return 0
}

block_load() {   # wrap a line to the screen as it is now, remembering each row's byte offset
  local LC_ALL=C row off=0 size
  size="$(stty size 2>/dev/null </dev/tty)" || size=""
  if [[ "$size" =~ ^([0-9]+)\ ([0-9]+)$ ]]; then TERM_ROWS=${BASH_REMATCH[1]} TERM_COLS=${BASH_REMATCH[2]}; fi
  blk_lines=(); blk_offs=()
  while IFS= read -r row; do
    blk_lines+=("$row"); blk_offs+=("$off"); off=$(( off + ${#row} ))
  done < <(fold -s -w $(( TERM_COLS - 3 )) "$1"; echo)
  view_top=0
  view_h=$(( ${#blk_lines[@]} < TERM_ROWS - 2 ? ${#blk_lines[@]} : TERM_ROWS - 2 ))
  [ "$view_h" -ge 1 ] || view_h=1
}

block_draw() {   # block_draw A B: draw the visible rows with bytes A..B lit (A = -1: none lit)
  local LC_ALL=C a=$1 b=$2 k o row len x y
  for (( k = view_top; k < view_top + view_h; k++ )); do
    o=${blk_offs[k]} row=${blk_lines[k]} len=${#blk_lines[k]}
    printf '\r\033[K  ' >&2
    if [ "$a" -lt 0 ] || [ $(( o + len )) -le "$a" ]; then
      printf '%s' "$row" >&2                                  # already spoken
    elif [ "$o" -ge "$b" ]; then
      printf '%s%s%s' "$D" "$row" "$R" >&2                    # still to come
    else
      x=$(( a > o ? a - o : 0 )); y=$(( b - o < len ? b - o : len ))
      printf '%s%s%s%s%s%s' "${row:0:x}" "$HL" "${row:x:y-x}" "$R$D" "${row:y}" "$R" >&2
    fi
    printf '\n' >&2
  done
}

block_redraw() { # back up over the visible rows and draw them again, moving the window to the lit word
  local a=$1 k=0
  if [ "$a" -ge 0 ]; then
    while [ $(( k + 1 )) -lt "${#blk_lines[@]}" ] && [ "${blk_offs[k + 1]}" -le "$a" ]; do k=$(( k + 1 )); done
    if [ "$k" -lt "$view_top" ] || [ "$k" -ge $(( view_top + view_h )) ]; then
      view_top=$(( k + view_h > ${#blk_lines[@]} ? ${#blk_lines[@]} - view_h : k ))
    fi
  fi
  printf '\r\033[K\033[%dA' "$view_h" >&2
  block_draw "$@"
}

block_finish() { # the whole line as read, nothing lit (it stays on screen as a transcript)
  printf '\r\033[K\033[%dA' "$view_h" >&2
  view_top=0; view_h=${#blk_lines[@]}
  block_draw -1 -1
}

words_load() {   # read NNNN.words once it lands
  local f=$1 s e a b
  [ -f "$f" ] || return 1
  w_s=(); w_e=(); w_a=(); w_b=()
  { read -r _                                 # the "confidence" line
    while read -r s e a b; do w_s+=("$s"); w_e+=("$e"); w_a+=("$a"); w_b+=("$b"); done
  } < "$f"
  if [ "${#w_s[@]}" -gt 0 ]; then w_state=ready
  else w_state=none; block_redraw -1 -1; fi   # below the bar: the line plain, nothing lit
}

# ------------------------------------------------------------ listening
# Bookmarks, the notification and the controls that work while a text plays.

book_title=""
title_get() { [ -n "$book_title" ] || { [ -f "$tmpd/title" ] && read -r book_title < "$tmpd/title"; } || true; }

bookmark_save() {   # bookmark_save PART: so the same command, or kokoro --continue, picks up here
  local parts=1
  [ -n "$book_id" ] || return 0
  [ -f "$tmpd/progress" ] && { read -r _ parts < "$tmpd/progress" || parts=1; }
  [ "${parts:-1}" -gt 1 ] || return 0                     # nothing to come back to
  [ -d "$BOOKS" ] || mkdir -p "$BOOKS"
  [ "$kind" = text ] && [ ! -f "$BOOKS/$book_id.txt" ] && cp "$text" "$BOOKS/$book_id.txt"
  printf 'part=%s\nparts=%s\ntitle=%s\nkind=%s\nsource=%s\nvoice=%s\nspeed=%s\nlang=%s\n' \
    "$1" "$parts" "${book_title:-$name}" "$kind" "$source" "$voice" "$speed" "$lang" > "$BOOKS/$book_id.mark"
}
bookmark_done() { [ -n "$book_id" ] && rm -f "$BOOKS/$book_id.mark" "$BOOKS/$book_id.txt"; return 0; }

# The notification: what's playing, with pause / next / again buttons that run
# kokoro --pause / --next / --again. Swiping it away stops. In the background,
# with a timeout, because termux-* commands hang without the Termux:API app.
notify() {
  [ "$KOKORO_NOTIFY" = 1 ] && command -v termux-notification >/dev/null || return 0
  local k="$PREFIX/bin/kokoro"
  ( timeout 15 termux-notification --id kokomux --type media --alert-once \
      --title "${book_title:-$name}" --content "$1" --icon record_voice_over \
      --media-previous "$k --again" --media-pause "$k --pause" --media-play "$k --pause" \
      --media-next "$k --next" --on-delete "$k --stop" >/dev/null 2>&1 & )
  return 0
}
notify_clear() {
  [ "$KOKORO_NOTIFY" = 1 ] && command -v termux-notification-remove >/dev/null || return 0
  ( timeout 10 termux-notification-remove kokomux >/dev/null 2>&1 & )
  return 0
}

play_line() {    # play_line WAV IDX: play one part; light its words; obey pause / next / again
  local wav=$1 i=$2 base="${1%.wav}" cur=-1 lit=-2 lit_now t t0 label last="" cmd paused=0 restart=1
  w_state=pending
  [ -f "$tmpd/stream/words-off" ] && w_state=off
  echo "$i" > "$tmpd/stream/playing"          # the engine and aligner work from the part being heard
  title_get; part_of "$i"
  bookmark_save "$i"; notify "${PART% · }"
  if [ "$show_words" = 1 ]; then
    block_load "$base.txt"; status_clear
    if [ "$w_state" = off ]; then block_draw -1 -1; else block_draw 0 0; fi   # dim until words light
  fi
  in_line=1
  # Nothing in this loop starts a process except when something changes: it
  # runs ~30 times a second on a phone.
  while :; do
    if [ "$restart" = 1 ]; then               # (re)start this part from its beginning
      play-audio "$wav" >/dev/null 2>&1 & play_pid=$!
      now_ms; t0=$NOW; cur=-1; lit=-2; restart=0
      echo playing > "$PLAYER_STATE"
    fi
    if [ -f "$CONTROL" ]; then                # kokoro --pause / --next / --again
      read -r cmd < "$CONTROL" || cmd=""; rm -f "$CONTROL"
      case "$cmd" in
        toggle)
          if [ "$paused" = 1 ]; then paused=0; restart=1; notify "${PART% · }"   # carry on from the part's start
          else kill "$play_pid" 2>/dev/null; wait "$play_pid" || true
               paused=1; last=""; echo paused > "$PLAYER_STATE"; notify "Paused · ${PART% · }"; fi
          continue ;;
        next)  kill "$play_pid" 2>/dev/null; wait "$play_pid" || true; break ;;
        again) kill "$play_pid" 2>/dev/null; wait "$play_pid" || true; restart=1; continue ;;
      esac
    fi
    if [ "$paused" = 1 ]; then
      [ -n "$last" ] || { status "Paused" "${PART}--pause to carry on" "‖"; last=paused; }
      tick 0.1
      continue
    fi
    kill -0 "$play_pid" 2>/dev/null || break  # the part has finished
    if [ "$show_words" = 1 ]; then
      if [ "$w_state" = pending ]; then words_load "$base.words" || true; fi   # not landed yet
      if [ "$w_state" = ready ]; then
        now_ms; t=$(( NOW - t0 - KOKORO_WORDS_OFFSET_MS ))
        while [ $(( cur + 1 )) -lt "${#w_s[@]}" ] && [ "${w_s[cur + 1]}" -le "$t" ]; do cur=$(( cur + 1 )); done
        # Keep the word lit through the pause after it; the last one goes out when it ends.
        lit_now=$cur
        if [ "$cur" -eq $(( ${#w_s[@]} - 1 )) ] && [ "$t" -gt "${w_e[cur]}" ]; then lit_now=-1; fi
        if [ "$lit_now" -ne "$lit" ]; then
          if [ "$lit_now" -ge 0 ]; then block_redraw "${w_a[lit_now]}" "${w_b[lit_now]}"
          elif [ "$cur" -ge 0 ]; then block_redraw -1 -1    # past the last word
          else block_redraw 0 0; fi                          # before the first
          lit=$lit_now; last=""
        fi
      fi
    fi
    part_of "$i"
    label="${PART}Ctrl+C to stop"; [ "$show_words" = 1 ] && [ "$w_state" = pending ] && label="${PART}word timings…"
    if [ "$label" != "$last" ]; then status "Playing" "$label" "▶"; last=$label; fi
    tick 0.03
  done
  wait "$play_pid" || true
  play_pid=""; in_line=0
  echo $(( i + 1 )) > "$tmpd/stream/playing"  # heard: the aligner can drop this part
  [ "$show_words" = 1 ] && block_finish
  rm -f "$wav"
}

# ------------------------------------------------------------ run

if [ "$play" = 1 ]; then
  stop_playback || true
  rm -f "$CONTROL"                             # nothing left over from an earlier player
  echo "$$" > "$PLAYER_PID"
fi
run_engine > "$tmpd/engine.log" 2>&1 & gen_pid=$!

i=$start
while :; do
  # The engine starts over at part 1 if a bookmark points past the end (the text changed).
  [ "$i" = "$start" ] && [ -f "$tmpd/stream/first" ] && { read -r i < "$tmpd/stream/first" || i=$start; start=$i; }
  f="$(printf '%s/stream/%04d.wav' "$tmpd" "$i")"
  if [ "$play" = 1 ] && [ -f "$f" ]; then
    play_line "$f" "$i"; i=$((i + 1))
  elif [ "$play" = 1 ] && [ -f "$tmpd/stream/end" ]; then
    [ -f "$f" ] && continue   # a last chunk may have landed just now
    break
  elif ! kill -0 "$gen_pid" 2>/dev/null; then
    [ "$play" = 1 ] && [ -f "$f" ] && continue
    break
  else
    status "$(waiting_label)" "$(progress)"
    sleep 0.1
  fi
done
rc=0; wait "$gen_pid" || rc=$?
gen_pid=""
status_clear
[ "$play" = 1 ] && [ "$rc" = 0 ] && bookmark_done   # read to the end: nothing to come back to

if [ "$rc" != 0 ]; then
  msg="$(grep -m1 '^Error: ' "$tmpd/engine.log" | sed 's/^Error: //')" || true
  [ "$msg" = "Cancelled" ] && exit 130
  if [ -n "$msg" ]; then die "$msg"; fi
  printf '%s✗%s Something went wrong:\n' "$RED" "$R" >&2
  tail -n 5 "$tmpd/engine.log" | sed "s/^/  $D/; s/\$/$R/" >&2
  exit 1
fi

secs=""
if [ -f "$tmpd/result" ] && read -r a _ < "$tmpd/result"; then secs="$(human_secs "$a") of audio"; fi

if [ -n "$out" ]; then
  [ -f "$tmpd/out.$ext" ] || die "No audio was made."
  mv "$tmpd/out.$ext" "$out"
  if [ "$LIVE" = 1 ]; then
    size="$(human_size "$(stat -c %s "$out" 2>/dev/null || wc -c < "$out")")"
    done_msg "Saved ${B}$out${R}" "${secs:+$secs · }$size"
  else
    echo "Saved $out" >&2
  fi
elif [ "$LIVE" = 1 ]; then
  done_msg "Done" "$secs"
fi
__KOKOMUX_WRAPPER__

# ================================================================ the work

termux_packages() {
  local apt_opts=(-y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)
  pkg update "${apt_opts[@]}"
  pkg install "${apt_opts[@]}" proot-distro curl
  # Nice to have: playback and the clipboard.
  pkg install "${apt_opts[@]}" play-audio || echo "play-audio unavailable"
  pkg install "${apt_opts[@]}" termux-api || echo "termux-api unavailable"
}

debian_container() {
  if proot-distro login "$DISTRO" -- true >/dev/null 2>&1; then
    note "already there"
    return 0
  fi
  proot-distro install --override-alias "$DISTRO" debian
}

in_container() { proot-distro login "$DISTRO" --shared-tmp -- bash /tmp/kokoro-setup/setup.sh "$@"; }

stop_old_server() {
  local tok="$PREFIX/tmp/kokoro-server/token"
  [ -f "$tok" ] || return 0
  curl -fsS -m 5 -X POST -H "X-Kokoro-Token: $(cut -d' ' -f2 "$tok")" \
    "http://127.0.0.1:$(cut -d' ' -f1 "$tok")/shutdown" >/dev/null 2>&1 || true
  rm -f "$tok"
}

# Write a helper script unless the user already has their own there.
# Ours carry a "# kokomux" line so updates and --uninstall can recognise them.
SKIPPED="$WORK/.skipped"
put() {
  local path="$1" body="$2"
  if [ -f "$path" ] && ! grep -q '^# kokomux' "$path"; then
    tilde "$path" >> "$SKIPPED"; echo >> "$SKIPPED"
    return 0
  fi
  mkdir -p "${path%/*}"
  printf '#!%s/bin/bash\n# kokomux\n%s\n' "$PREFIX" "$body" > "$path"
  chmod +x "$path"
}

kokoro_command() {
  stop_old_server
  { printf '#!%s/bin/bash\n' "$PREFIX"; tail -n +2 "$WORK/kokoro"; } > "$PREFIX/bin/kokoro"
  chmod +x "$PREFIX/bin/kokoro"

  local conf="$HOME/.config/kokoro/config"
  if [ ! -f "$conf" ]; then
    mkdir -p "${conf%/*}"
    cat > "$conf" <<'CFG'
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

# Light up each word as it's spoken (with -p, on a terminal). Whistle listens to
# each line and its words are matched to the text. Raise the offset if the
# highlight runs ahead of the voice, lower it if it lags.
KOKORO_WORDS=1
KOKORO_WORDS_OFFSET_MS=100

# While listening, a notification with pause / next / again buttons (needs the
# Termux:API app). Swiping it away stops.
KOKORO_NOTIFY=1
CFG
  fi

  # Share a link to Termux -> read the page. Share text or a file -> read it.
  put "$HOME/bin/termux-url-opener" 'exec kokoro -p -u "$1"'
  put "$HOME/bin/termux-file-editor" 'case "$(head -c 4 "$1")" in %PDF*|PK*) exec kokoro -p -f "$1" ;; esac   # PDF, EPUB
if grep -Iq . "$1"; then exec kokoro -p -f "$1"; fi
echo "kokoro: that file is not text, an EPUB or a PDF"; sleep 3'
  # Termux:Widget runs scripts in ~/.shortcuts/tasks in the background.
  put "$HOME/.shortcuts/tasks/Kokoro read clipboard" 'exec kokoro -p -c'
  put "$HOME/.shortcuts/tasks/Kokoro stop" 'exec kokoro --stop'
  put "$HOME/.shortcuts/tasks/Kokoro pause" 'exec kokoro --pause'
  put "$HOME/.shortcuts/tasks/Kokoro next" 'exec kokoro --next'
  put "$HOME/.shortcuts/tasks/Kokoro continue" 'exec kokoro --continue'

  in_container tidy
}

GREETING="Hi! kokomux is ready. Type kokoro, dash p, and some text, and I'll read it to you."
voice_test() {
  rm -f "$HOME/kokoro-test.wav"   # left by older versions
  kokoro -o "$WORK/hello.wav" "$GREETING"
  # Is the Termux:API app there? (termux-* commands hang without it.)
  if command -v termux-clipboard-get >/dev/null && timeout 4 termux-clipboard-get >/dev/null 2>&1; then
    touch "$WORK/.api"
  fi
  local root
  for root in "$PREFIX/var/lib/proot-distro/containers/$DISTRO" "$PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO"; do
    if [ -d "$root" ]; then du -sm "$root" 2>/dev/null | cut -f1 > "$WORK/.size"; break; fi
  done
  note "sounds good"
}

[ "$MODE" = install ] && heading "Installing"
[ "$MODE" = update ] && blank
hide_cursor
step "Termux packages" \
  "Termux couldn't download packages. Pick another mirror with termux-change-repo, then run pkg upgrade." \
  termux_packages
step "Debian container" \
  "Check that you have enough free space and a steady connection." \
  debian_container
step "Python" \
  "Debian's servers may be busy. Wait a minute and try again." \
  in_container apt
step "Kokoro" \
  "Installing Python packages failed. Check your connection and try again." \
  in_container pip
step "Download" \
  "The download was interrupted. Running the installer again resumes it." \
  in_container models
step "kokoro command" \
  "Couldn't finish setting up. The log below says why." \
  kokoro_command
step "Voice test" \
  "Kokoro is installed but couldn't speak. Try: kokoro -p hello, and check the log." \
  voice_test
show_cursor

# ================================================================ done

took="$(human_time $(( SECONDS - T_START )))"
size=""; [ -s "$WORK/.size" ] && size=" · $(cat "$WORK/.size") MB"
bar="${ACC}│${R}"

blank
if [ "$MODE" = install ]; then
  printf '  %s╭─%s %s%s✓ kokomux is ready%s\n' "$ACC" "$R" "$B" "$GRN" "$R"
elif [ "$OLD_VERSION" = "$KOKOMUX_VERSION" ]; then
  printf '  %s╭─%s %s%s✓ kokomux reinstalled%s\n' "$ACC" "$R" "$B" "$GRN" "$R"
else
  printf '  %s╭─%s %s%s✓ kokomux updated to v%s%s\n' "$ACC" "$R" "$B" "$GRN" "$KOKOMUX_VERSION" "$R"
fi
say "  $bar"
tips=(
  'kokoro -p "Hello there"|speak, words lit as read'
  'kokoro -c -p|read the clipboard'
  'kokoro -u URL -p|read a web page'
  'kokoro -p -f book.epub|EPUB or PDF; resumes'
  'kokoro -o note.mp3 "Hi"|save a file'
  'kokoro --list-voices|54 voices'
  'kokoro --help|everything else'
)
for t in "${tips[@]}"; do
  cmd="${t%%|*}"; what="${t#*|}"
  if [ "$W" -ge 50 ]; then
    printf '  %s  %s%-24s%s %s%s%s\n' "$bar" "$ACC2" "$cmd" "$R" "$D" "$what" "$R"
  else
    printf '  %s  %s%s%s\n' "$bar" "$ACC2" "$cmd" "$R"
    printf '  %s    %s%s%s\n' "$bar" "$D" "$what" "$R"
  fi
done
say "  $bar"
wrap "  $bar  " "Tip: Share → Termux in any app reads links and text aloud."
say "  $bar"
if [ -f "$WORK/.api" ]; then
  say "  $bar  ${GRN}✓${R} Clipboard ready ${D}(Termux:API)${R}"
else
  say "  $bar  ${D}○${R} Clipboard: ${D}install the Termux:API app${R}"
fi
say "  $bar  ${D}○${R} Home-screen buttons: ${D}Termux:Widget app${R}"
if [ -s "$SKIPPED" ]; then
  while read -r f; do
    wrap "  $bar  " "${YEL}!${R} ${D}Kept your own $f, so sharing that type won't use kokoro.${R}"
  done < "$SKIPPED"
fi
say "  $bar"
printf '  %s╰─%s %s%s in %s%s%s\n' "$ACC" "$R" "$D" "$( [ "$MODE" = install ] && echo Installed || echo Updated )" "$took" "$size" "$R"
blank

[ "$FANCY" = 1 ] && printf '\a'
if [ -f "$WORK/hello.wav" ] && command -v play-audio >/dev/null; then
  printf '  %s♪%s %sSaying hello...%s' "$ACC" "$R" "$D" "$R"
  play-audio "$WORK/hello.wav" >/dev/null 2>&1 || true
  printf '\r\033[K'
fi
