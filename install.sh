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

KOKOMUX_VERSION="0.3.0"
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
need_mb=600; [ "$MODE" = update ] && need_mb=150
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
  bullet "Python, Kokoro and 54 voices (a 200 MB download)"
  bullet "The kokoro command, plus Share and widget shortcuts"
  blank
  wrap "  " "${D}Takes 5–15 minutes and about 600 MB. You can use your phone meanwhile; it stays awake until done.${R}"
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
import urllib.request
from html.parser import HTMLParser

APP = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.join(APP, "models")
MODEL = os.path.join(MODELS, "kokoro-v1.0.int8.onnx")
VOICES = os.path.join(MODELS, "voices-v1.0.bin")
MODEL_URL = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/"
SERVER_DIR = "/tmp/kokoro-server"
JOB_DIR_RE = re.compile(r"^/tmp/kokoro\.[A-Za-z0-9]+$")
FORMATS = ("wav", "mp3", "ogg", "flac")
USER_AGENT = "Mozilla/5.0 (Linux; Android) kokomux"
MAX_CHUNK = 200  # characters per synthesis chunk
SERVING = False


class JobError(Exception):
    """A problem with the request itself (bad voice, empty text...)."""


def log(msg):
    print(msg, file=sys.stderr, flush=True)


# ------------------------------------------------------------------ model

_tts = None
_tts_lock = threading.Lock()


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

    def text(self):
        self._flush()
        art = [t for a, t in self.blocks if a]
        use = art if sum(map(len, art)) > 500 else [t for _, t in self.blocks]
        # Headings and list items often lack a full stop; end each block so it
        # gets its own pause.
        use = [t if t[-1] in ".!?…:;" else t + "." for t in use]
        title = re.sub(r"\s+", " ", self.title).strip()
        if title and (not use or title.split(" - ")[0].split(" | ")[0] not in use[0]):
            use.insert(0, title + ".")
        return "\n".join(use)


def fetch_url(url):
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
        return body
    if "html" not in ctype and "xml" not in ctype:
        raise JobError(f"That link is {ctype}, not a web page")
    ex = _Extractor()
    ex.feed(body)
    text = ex.text()
    if not text.strip():
        raise JobError("Couldn't find any readable text on that page")
    return text


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
    if job.get("stream") == "1":
        stream = os.path.join(jobdir, "stream")
        os.makedirs(stream, exist_ok=True)

    try:
        if job.get("url"):
            log(f"Fetching {job['url']}")
            text = fetch_url(job["url"])
        else:
            with open(os.path.join(jobdir, "in.txt"), encoding="utf-8", errors="replace") as fh:
                text = fh.read()
            bad = text.count("\ufffd") + text.count("\x00")
            if text and bad > max(3, len(text) // 100):
                raise JobError("That doesn't look like text (is it a PDF, image or other binary file?)")
        chunks = split_text(text)
        if not chunks:
            raise JobError("No text to read")

        parts, rate, total = [], 24000, 0.0
        for i, chunk in enumerate(chunks, 1):
            # The kokoro command deletes its job folder when it exits or is
            # stopped, so a vanished folder means nobody is waiting any more.
            if not os.path.isdir(jobdir) or (stream and not os.path.isdir(stream)):
                raise JobError("Cancelled")
            _write_atomic(os.path.join(jobdir, "progress"), f"{i} {len(chunks)}\n")
            samples, rate = engine.create(chunk, voice=voice, speed=speed, lang=lang)
            total += len(samples) / rate
            if stream:
                tmp = os.path.join(stream, f"{i:04d}.tmp.wav")
                sf.write(tmp, samples, rate)
                os.replace(tmp, os.path.join(stream, f"{i:04d}.wav"))
            if out:
                parts.append(samples)
                parts.append(np.zeros(int(rate * 0.2), dtype=samples.dtype))
        if out:
            sf.write(out, np.concatenate(parts[:-1]), rate)
    finally:
        if stream and os.path.isdir(stream):
            open(os.path.join(stream, "end"), "w").close()

    elapsed = time.time() - t0
    _write_atomic(os.path.join(jobdir, "result"), f"{total:.1f} {elapsed:.1f} {len(chunks)}\n")
    return f"Made {total:.1f}s of audio in {elapsed:.1f}s"


def _write_atomic(path, text):
    try:
        with open(path + ".tmp", "w") as fh:
            fh.write(text)
        os.replace(path + ".tmp", path)
    except OSError:
        pass  # the job folder vanished (cancelled); the loop will notice


# ------------------------------------------------------------------ server

def serve(port, idle):
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from urllib.parse import parse_qs, urlparse

    global SERVING
    SERVING = True
    log("Loading model...")
    tts()
    token = secrets.token_hex(16)
    job_lock = threading.Lock()
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
                with job_lock:
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


if __name__ == "__main__":
    main()
__KOKOMUX_ENGINE__

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
    "$APP/venv/bin/pip" install --upgrade kokoro-onnx soundfile | tee /tmp/kokoro-setup/.pip
    grep -q '^Successfully installed' /tmp/kokoro-setup/.pip || printf 'up to date' > "$SRC/.note"
    cp "$SRC/engine.py" "$APP/engine.py"
    rm -f "$APP/speak.py"   # from older versions
    ;;
  models)
    cp "$SRC/engine.py" "$APP/engine.py"
    if [ -s "$APP/models/kokoro-v1.0.int8.onnx" ] && [ -s "$APP/models/voices-v1.0.bin" ]; then
      printf 'already downloaded' > "$SRC/.note"
    fi
    "$APP/venv/bin/python" -u "$APP/engine.py" download
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

KOKOMUX_VERSION="0.3.0"
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
-f FILE|a text file
-c, --clipboard|the clipboard (or the link in it)
-u URL|a web page
ROWS
  printf '\n%sMore%s\n' "$B" "$R"
  opt_rows <<'ROWS'
--list-voices|all 54 voices by language
--stop|stop playback
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
    rm -rf "$CONF_DIR" "$SRV_DIR" "$HOME/kokoro-test.wav" "$HOME/.cache/kokomux"
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

out=""; infile=""; url=""; play=0; clip=0; words=()
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
  status_clear
  [ -n "$play_pid" ] && kill "$play_pid" 2>/dev/null
  [ -n "$gen_pid" ] && kill "$gen_pid" 2>/dev/null
  rm -rf "$tmpd"
  if [ "$play" = 1 ] && [ "$(cat "$PLAYER_PID" 2>/dev/null)" = "$$" ]; then rm -f "$PLAYER_PID"; fi
  return 0
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP

text="$tmpd/in.txt"
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
elif [ ${#words[@]} -gt 0 ]; then
  printf '%s\n' "${words[*]}" > "$text"
elif [ ! -t 0 ]; then
  cat > "$text"
else
  usage >&2; exit 2
fi

# A copied link on its own means "read that page".
if [ -z "$url" ] && [ "$(wc -l < "$text")" -le 1 ] &&
   grep -qE '^[[:space:]]*https?://[^[:space:]]+[[:space:]]*$' "$text"; then
  url="$(tr -d '[:space:]' < "$text")"
fi
if [ -z "$url" ] && ! grep -q '[^[:space:]]' "$text"; then
  [ "$clip" = 1 ] && die "The clipboard is empty."
  die "There's no text to read."
fi
[[ "$url" != *$'\n'* ]] || die "That doesn't look like a web address."

{
  echo "voice=$voice"
  echo "speed=$speed"
  echo "lang=$lang"
  [ -n "$url" ] && echo "url=$url"
  [ -n "$out" ] && echo "out=out.$ext"
  [ "$play" = 1 ] && echo "stream=1"
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
  if [ "$play" = 1 ] && [ "$i" -gt 1 ]; then echo "Preparing"
  elif [ -f "$tmpd/progress" ]; then echo "Making speech"
  elif [ -n "$url" ]; then echo "Fetching the page"
  else echo "Getting ready"; fi
}

# ------------------------------------------------------------ run

if [ "$play" = 1 ]; then
  stop_playback || true
  echo "$$" > "$PLAYER_PID"
fi
run_engine > "$tmpd/engine.log" 2>&1 & gen_pid=$!

i=1
while :; do
  f="$(printf '%s/stream/%04d.wav' "$tmpd" "$i")"
  if [ "$play" = 1 ] && [ -f "$f" ]; then
    play-audio "$f" >/dev/null 2>&1 & play_pid=$!
    while kill -0 "$play_pid" 2>/dev/null; do
      n="$(cut -d' ' -f2 "$tmpd/progress" 2>/dev/null || echo 1)"
      if [ "${n:-1}" -gt 1 ]; then where="part $i of $n · "; else where=""; fi
      status "Playing" "${where}Ctrl+C to stop" "▶"
      sleep 0.15
    done
    wait "$play_pid" || true
    play_pid=""
    rm -f "$f"; i=$((i + 1))
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
CFG
  fi

  # Share a link to Termux -> read the page. Share text or a file -> read it.
  put "$HOME/bin/termux-url-opener" 'exec kokoro -p -u "$1"'
  put "$HOME/bin/termux-file-editor" 'if grep -Iq . "$1"; then exec kokoro -p -f "$1"; fi
echo "kokoro: that file is not text"; sleep 3'
  # Termux:Widget runs scripts in ~/.shortcuts/tasks in the background.
  put "$HOME/.shortcuts/tasks/Kokoro read clipboard" 'exec kokoro -p -c'
  put "$HOME/.shortcuts/tasks/Kokoro stop" 'exec kokoro --stop'

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
  'kokoro -p "Hello there"|speak out loud'
  'kokoro -c -p|read the clipboard'
  'kokoro -u URL -p|read a web page'
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
