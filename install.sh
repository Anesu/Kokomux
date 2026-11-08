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
            if len(chunks) > 1 and not stream and not SERVING:
                log(f"  part {i}/{len(chunks)}")
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

    return f"Made {total:.1f}s of audio in {time.time() - t0:.1f}s"


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
    for name in ("kokoro-v1.0.int8.onnx", "voices-v1.0.bin"):
        dest = os.path.join(MODELS, name)
        if os.path.exists(dest) and os.path.getsize(dest) > 0:
            print(f"{name}: already downloaded")
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
                        while block := r.read(1 << 20):
                            fh.write(block)
                            done += len(block)
                            pct = done * 100 // size if size else 0
                            if pct // 10 != shown:
                                shown = pct // 10
                                print(f"{name}: {pct}% of {size >> 20} MB", flush=True)
                os.replace(part, dest)
                break
            except OSError as e:
                print(f"{name}: download interrupted ({e}), retry {attempt}/5", flush=True)
                time.sleep(2 * attempt)
        else:
            sys.exit(f"Couldn't download {name}. Run the installer again to resume.")


# ------------------------------------------------------------------ main

def main():
    p = argparse.ArgumentParser(prog="engine.py")
    sub = p.add_subparsers(dest="cmd", required=True)
    j = sub.add_parser("job")
    j.add_argument("path")
    s = sub.add_parser("serve")
    s.add_argument("--port", type=int, default=8880)
    s.add_argument("--idle", type=int, default=1800, help="stop after this many idle seconds (0 = never)")
    sub.add_parser("voices")
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
        print("\n".join(sorted(tts().get_voices())))
    elif a.cmd == "download":
        download()


if __name__ == "__main__":
    main()
__KOKOMUX_ENGINE__

cat > "$WORK/setup.sh" <<'__KOKOMUX_SETUP__'
# kokomux: runs inside the "kokoro" Debian container (as root), from install.sh.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
APP=/opt/kokoro
SRC=/tmp/kokoro-setup

echo "--> apt packages"
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

echo "--> Python packages (the slow part)"
mkdir -p "$APP"
[ -x "$APP/venv/bin/python" ] || python3 -m venv "$APP/venv"
"$APP/venv/bin/pip" install --upgrade pip
"$APP/venv/bin/pip" install --upgrade kokoro-onnx soundfile

cp "$SRC/engine.py" "$APP/engine.py"
rm -f "$APP/speak.py"   # from older versions

echo "--> model files"
"$APP/venv/bin/python" "$APP/engine.py" download

echo "--> tidying up"
apt-get clean
rm -rf /var/lib/apt/lists/* /root/.cache/pip /usr/share/man/* /usr/share/info/*
find /usr/share/doc -mindepth 2 ! -name copyright -delete 2>/dev/null || true
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

CONF_DIR="$HOME/.config/kokoro"
CONF="$CONF_DIR/config"

# Precedence: environment (KOKORO_VOICE=am_adam kokoro ...) > settings file
# > these defaults.
env_names="${!KOKORO_@}"
env_overrides=""
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

die() { echo "kokoro: $*" >&2; exit 1; }

usage() {
  cat <<'TXT'
Usage: kokoro [options] [text...]

Text comes from the arguments, -f FILE, -c (clipboard), -u URL, or a pipe.

  -p, --play        play as it's made (starts after the first sentence)
  -o FILE           save to FILE: .wav .mp3 .ogg or .flac
                    (default kokoro.wav; with -p, nothing is saved unless -o)
  -v VOICE          voice, e.g. af_heart, am_adam, bf_emma (see --list-voices)
  -s SPEED          0.5 to 2.0
  -l LANG           en-us en-gb es fr-fr it pt-br hi
  -f FILE           read text from FILE
  -c, --clipboard   read the clipboard (a copied link reads that page)
  -u URL            read a web page

  --list-voices     show all voices
  --stop            stop playback
  --serve           start the background server now
  --stop-server     stop the background server
  --status          show server status and settings
  --config          edit default voice, speed, etc.
  --update          update kokomux and Kokoro
  --uninstall       remove everything kokomux installed

Examples:
  kokoro -p "Hello there"
  kokoro -c -p
  kokoro -u https://example.com/article -o article.mp3
TXT
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
  if [ "$KOKORO_IDLE_MINUTES" = 0 ]; then
    echo "Starting the Kokoro server (runs until: kokoro --stop-server)..." >&2
  else
    echo "Starting the Kokoro server (stops after ${KOKORO_IDLE_MINUTES} min unused)..." >&2
  fi
  # Ignore Ctrl+C and hang-ups so the server outlives the command that started it.
  ( trap '' INT HUP
    exec nohup proot-distro login "$DISTRO" --shared-tmp -- \
      "$PY" "$ENGINE" serve --port "$KOKORO_PORT" --idle "$((KOKORO_IDLE_MINUTES * 60))"
  ) > "$SRV_DIR/log" 2>&1 < /dev/null &
  local pid=$! i
  for i in $(seq 1 240); do
    server_up && return 0
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
  done
  echo "kokoro: server didn't start. Log ($SRV_DIR/log):" >&2
  tail -n 5 "$SRV_DIR/log" >&2 || true
  return 1
}

stop_server() {
  if ! server_up; then echo "Server isn't running."; return 0; fi
  curl -fsS -m 5 -X POST -H "X-Kokoro-Token: $(server_token)" \
    "http://127.0.0.1:$(server_port)/shutdown" >/dev/null || true
  local i
  for i in $(seq 1 20); do server_up || break; sleep 0.25; done
  echo "Server stopped."
}

# ------------------------------------------------------------ playback

stop_playback() {
  local pid
  pid="$(cat "$PLAYER_PID" 2>/dev/null)" || return 0
  [ -n "$pid" ] && [ "$pid" != "$$" ] && kill "$pid" 2>/dev/null || true
  rm -f "$PLAYER_PID"
}

# ------------------------------------------------------------ admin commands

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  --stop) stop_playback; exit 0 ;;
  --serve) start_server && echo "Server running on port $(server_port)."; exit ;;
  --stop-server) stop_server; exit 0 ;;
  --status)
    if server_up; then echo "Server: running on port $(server_port)"; else echo "Server: not running"; fi
    echo "Voice: $KOKORO_VOICE   Speed: $KOKORO_SPEED   Language: $KOKORO_LANG"
    echo "Auto-start server: $([ "$KOKORO_AUTOSERVE" = 1 ] && echo yes || echo no)   Idle stop: ${KOKORO_IDLE_MINUTES} min"
    echo "Settings: $CONF"
    exit 0 ;;
  --config)
    [ -f "$CONF" ] || die "no settings file at $CONF (re-run the installer)"
    exec "${EDITOR:-nano}" "$CONF" ;;
  --update)
    stop_server >/dev/null
    tmp="$(mktemp "$PREFIX/tmp/kokomux-install.XXXXXX")"
    if curl -fsSL "$KOKORO_REPO_RAW/install.sh" -o "$tmp"; then
      rc=0; bash "$tmp" || rc=$?; rm -f "$tmp"; exit "$rc"
    fi
    rm -f "$tmp"
    echo "Couldn't download the latest installer (is the repo private?)." >&2
    echo "Updating Kokoro's Python packages only..." >&2
    exec proot-distro login "$DISTRO" -- "${PY%/python}/pip" install --no-cache-dir --upgrade kokoro-onnx soundfile ;;
  --uninstall)
    read -r -p "Remove kokomux, its Debian container and settings? [y/N] " ans
    [[ "$ans" =~ ^[Yy] ]] || exit 0
    stop_playback; stop_server >/dev/null
    proot-distro remove "$DISTRO" || true
    for f in "$HOME/bin/termux-url-opener" "$HOME/bin/termux-file-editor" \
             "$HOME/.shortcuts/tasks/"Kokoro*; do
      if [ -f "$f" ] && grep -q '^# kokomux' "$f"; then rm -f "$f"; echo "Removed $f"; fi
    done
    rm -rf "$CONF_DIR" "$SRV_DIR" "$HOME/kokoro-test.wav"
    rm -f "$0"
    echo "kokomux removed."
    exit 0 ;;
  --list-voices)
    exec proot-distro login "$DISTRO" -- "$PY" "$ENGINE" voices ;;
esac

# ------------------------------------------------------------ options

out=""; infile=""; url=""; play=0; clip=0; words=()
voice="$KOKORO_VOICE"; speed="$KOKORO_SPEED"; lang="$KOKORO_LANG"
need() { [ $# -ge 2 ] || die "$1 needs a value"; }
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
    -?*)            die "unknown option $1 (see kokoro --help)" ;;
    *)              words+=("$1"); shift ;;
  esac
done

[[ "$voice" =~ ^[A-Za-z0-9_]+$ ]] || die "bad voice name: $voice"
[[ "$speed" =~ ^[0-9]*\.?[0-9]+$ ]] || die "speed must be a number, e.g. 1.2"
[[ "$lang" =~ ^[A-Za-z0-9-]+$ ]] || die "bad language code: $lang"

[ "$play" = 1 ] || out="${out:-$KOKORO_OUT}"
ext=""
if [ -n "$out" ]; then
  ext="$(printf '%s' "${out##*.}" | tr '[:upper:]' '[:lower:]')"
  case "$ext" in wav|mp3|ogg|flac) ;; *) die "-o must end in .wav, .mp3, .ogg or .flac" ;; esac
fi
if [ "$play" = 1 ] && ! command -v play-audio >/dev/null; then
  die "playback needs play-audio: pkg install play-audio"
fi

# ------------------------------------------------------------ job folder

tmpd="$(mktemp -d "$PREFIX/tmp/kokoro.XXXXXX")"
gen_pid=""; play_pid=""
cleanup() {
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
    die "-c needs the Termux:API app and: pkg install termux-api"
  timeout 15 termux-clipboard-get > "$text" ||
    die "couldn't read the clipboard (is the Termux:API app installed?)"
elif [ -n "$infile" ]; then
  [ -f "$infile" ] || die "no such file: $infile"
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
  die "no text to read"
fi
[[ "$url" != *$'\n'* ]] || die "bad URL"

{
  echo "voice=$voice"
  echo "speed=$speed"
  echo "lang=$lang"
  [ -n "$url" ] && echo "url=$url"
  [ -n "$out" ] && echo "out=out.$ext"
  [ "$play" = 1 ] && echo "stream=1"
} > "$tmpd/job"
job="/tmp/$(basename "$tmpd")/job"   # the same file, as the container sees it

run_engine() {
  if server_up || { [ "$KOKORO_AUTOSERVE" = 1 ] && start_server; }; then
    local rc=0
    curl -s --fail-with-body -X POST -H "X-Kokoro-Token: $(server_token)" \
      "http://127.0.0.1:$(server_port)/job?path=$job" >&2 || rc=$?
    # 22 = the server answered with an error, which it has already printed.
    [ "$rc" = 0 ] || [ "$rc" = 22 ] || echo "kokoro: lost contact with the server (curl error $rc)" >&2
    [ "$rc" = 0 ] || return 1
  else
    proot-distro login "$DISTRO" --shared-tmp -- "$PY" "$ENGINE" job "$job"
  fi
}

# ------------------------------------------------------------ run

if [ "$play" = 0 ]; then
  run_engine
else
  stop_playback
  echo "$$" > "$PLAYER_PID"
  run_engine & gen_pid=$!
  i=1
  while :; do
    f="$(printf '%s/stream/%04d.wav' "$tmpd" "$i")"
    if [ -f "$f" ]; then
      play-audio "$f" & play_pid=$!
      wait "$play_pid" || true
      play_pid=""
      rm -f "$f"; i=$((i + 1))
    elif [ -f "$tmpd/stream/end" ]; then
      [ -f "$f" ] && continue   # a last chunk may have landed just now
      break
    elif ! kill -0 "$gen_pid" 2>/dev/null; then
      [ -f "$f" ] && continue
      break
    else
      sleep 0.1
    fi
  done
  rc=0; wait "$gen_pid" || rc=$?
  gen_pid=""
  [ "$rc" = 0 ] || exit "$rc"
fi

if [ -n "$out" ]; then
  [ -f "$tmpd/out.$ext" ] || die "no audio was made"
  mv "$tmpd/out.$ext" "$out"
  echo "Saved $out" >&2
fi
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
