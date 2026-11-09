# Working on kokomux

Notes for coding agents (and people) changing this repository. The user-facing
guide is README.md.

## Who it's for

kokomux is a **personal** app: Kokoro text-to-speech in Termux on one phone, a
Samsung S26 Ultra with 1 TB of storage (sometimes in DeX with a keyboard and
mouse). Build for that phone, not for every device:

- Always full precision (the fp32 model) and misaki pronunciation. Don't add
  quality options, smaller models or fallbacks for weaker hardware.
- No caveats, hedging or "if this fails, try…" wording in the UI or docs.
  Messages are short and plain.
- Don't keep compatibility or migration code for old versions once the update
  has shipped; remove it in the next clean-up.
- Fallbacks only where they're real on this phone (e.g. the terminal not
  answering a colour query).

## How it fits together

```
Termux (bash)                                Debian container "kokoro" (proot-distro)
─────────────                                ────────────────────────────────────────
$PREFIX/bin/kokoro         ← src/kokoro      /opt/kokoro/engine.py   ← src/engine.py
$PREFIX/share/kokomux/     ← src/theme.sh    /opt/kokoro/words.py    ← src/words.py
                             src/tui.sh      /opt/kokoro/venv        (kokoro-onnx, misaki, spaCy,
                             src/player.sh                            trafilatura, pypdf, soundfile)
                                             /opt/kokoro/models      (fp32 timestamped model, voices)
$PREFIX/tmp  ═════════════ shared ═══════════ /tmp
```

1. **A run** (`kokoro -p -f book.epub`) makes a job folder
   `$PREFIX/tmp/kokoro.XXXXXX`, writes `job` (key=value lines) and `in.txt`,
   and hands it to the engine: over HTTP to the background server
   (`engine.py serve`, 127.0.0.1:$KOKORO_PORT, with a token from
   `$PREFIX/tmp/kokoro-server/token`), or one-off via
   `proot-distro login kokoro -- python engine.py job …`. The job protocol
   (keys and the files written back) is documented at the top of
   `src/engine.py`; keep that docstring in step with the code.
2. **The engine** reads the source (text, EPUB, PDF or a web page), tidies it
   (`normalise`), splits it into parts with golden-ratio pauses
   (`split_text`), and writes each part to `stream/NNNN.{txt,words,wav}` (the
   `.wav` last, so its presence means the part is ready), then `stream/end`.
   Parts are kept in `/tmp/kokomux-cache/<book>/<settings hash>/` and the
   stream files are relative links into it; a part is reused when its text
   matches. The cache is deleted when a book is finished and trimmed to
   KOKORO_CACHE_GB.
3. **The player** (`src/player.sh`) runs one `mpv --idle` for the whole
   listen and drives it over its IPC socket through a `socat` coproc: parts
   are appended to a gapless playlist as they appear (`player_bg`), and the
   word highlight follows mpv's `playback-time`.
4. **The screens** (`src/tui.sh`): home, reader, menu, chapters, settings,
   voice picker. Colours from `src/theme.sh`, made from the terminal's own
   background (OSC 10/11 queries).
5. **Web novels**: when a URL is a novel's chapter (`novel_chapter` in the
   engine), the job reads on from chapter to chapter, one chapter ahead of
   the player (which writes the part it's on into `at`). Parts are numbered
   across the whole listen; `serial` maps them to chapter addresses, and a
   restart mid-listen (a jump, a new speed) passes `base=`. The bookmark is
   per novel (`kind=novel`, `source=` the chapter's address); see "web
   novels" in `src/player.sh`. Pages are fetched with curl and a phone
   browser's user agent, which is what the phone was checked to get through
   with. `tests/test_novel.py` serves a made-up novel locally.

`install.sh` is what the phone downloads: `src/install.sh` with the other
files inlined by `./build.sh`. It sets up Termux packages, the container
(`src/setup-container.sh`, one phase per call: apt, pip, models, tidy), the
share handlers and the widgets. `kokoro --update [BRANCH]` re-runs it.

## Files

| File | What it is |
|---|---|
| `src/kokoro` | the `kokoro` command: settings (KOKORO_DEFAULTS), server, keys and the frame timer (`tick`), admin commands, options, the job folder, audiobooks, the run |
| `src/player.sh` | playing: the inline view, bookmarks, chapters, sleep timer, commands (`listen_cmd`), mpv, `play_line` |
| `src/tui.sh` | the full-screen screens; its header lists the drawing rules |
| `src/theme.sh` | colour maths, asking the terminal, the palette (`theme_apply`, `theme_off`) |
| `src/engine.py` | model, text, books, jobs, server, benchmark and download, voices |
| `src/words.py` | word timings from Kokoro's per-sound durations |
| `src/setup-container.sh` | installs everything inside the container |
| `src/install.sh` | installer template (`#@EMBED file` lines are filled by build.sh) |
| `install.sh` | **built** — never edit by hand |
| `VERSION` | stamped into both by build.sh (`@@VERSION@@`) |
| `tests/` | one file per area, each runnable on its own (`python3 tests/test_x.py`) or with pytest |
| `dev/` | the checks, the stand-in phone and the screen renderer (below) |

## Checking a change

```
dev/check.sh                 # build, bash -n, shellcheck -S warning, pyflakes, every test
```

Run it before every commit. `install.sh` must be rebuilt and committed with
any change in `src/` (check.sh rebuilds it).

To run the app for real, with the Kokoro model, on this machine:

```
dev/rig.sh                   # once: .dev/ with the container's venv, the models (~340 MB),
                             # a fake $PREFIX/$HOME and a proot-distro stand-in
dev/kokoro -p -f story.txt   # kokoro from src/, sound to nowhere (mpv --ao=null)
echo "Hello." | dev/kokoro -o /tmp/t.wav
```

`KOKOMUX_MODELS=DIR dev/rig.sh` links models you already have. With the rig
made, check.sh runs the tests with its Python, so the audio and web tests run
too.

To **see** a screen (do this after any UI change, at phone width and wide):

```
python3 dev/screen.py --out /tmp/reader.png --cols 56 --rows 40 --wait 12 \
    -- dev/kokoro -p -f story.txt
python3 dev/screen.py --out /tmp/settings.png --bg fafafa --fg 202020 \
    --keys '2:s' -- dev/kokoro           # light terminal; keys: "secs:keys;;secs:keys"
```

It answers the terminal colour queries like Termux, types keys on a schedule,
and renders the last screen to HTML and PNG (pyte + Playwright).

## Conventions and traps

**Bash (src/kokoro, tui.sh, player.sh)** runs under `set -euo pipefail`.

- Never write to the terminal directly in the screens: `out FORMAT ARGS`
  (like printf) adds to `FRAME`, and `flush` writes it in one go. `tick`
  flushes before waiting for a key; flush yourself before handing the
  terminal to something else. A stray `printf … >&2` brings back flicker.
- `x="$(cmd)"` takes the exit status of `cmd`, so under `set -e`
  `x="$( [ … ] && echo y )"` exits the script when the test is false. Use
  `if`, or `|| true`.
- A function whose last line is `[ cond ] && action` returns 1 when cond is
  false; end such functions with `return 0`.
- `read` clears its variables at end of file and returns 1: `read -r a b < f || …`.
- Don't name a variable `done` (a keyword), and quote values that look like
  commands (`kind="file"`) or shellcheck warns (SC2209).
- The mpv socket path must be ≤ 107 bytes; it's built from `realpath $PREFIX/tmp`.
- The job folder name must match `^/tmp/kokoro\.[A-Za-z0-9]+$` (the engine
  refuses others); use `mktemp -d "$PREFIX/tmp/kokoro.XXXXXX"`.
- Playback continues while an overlay (menu, settings, picker) is open:
  `TICK_BG` keeps mpv fed; a choice made there goes through `CMD_PENDING`.
- tui.sh, player.sh and theme.sh share globals with kokoro (hence the
  shellcheck disables in their headers); each header lists what it expects.

**A new setting** goes in four places, and the test catches a missed one:
`KOKORO_DEFAULTS` in src/kokoro, `TUI_SETTINGS`, `setting_value` and
`setting_change` in src/tui.sh, and `EXPECTED` in tests/test_settings.py.

**Python (src/engine.py)** must stay importable without numpy, onnxruntime,
misaki or trafilatura (tests import it bare); import those inside the
functions that use them.

**Design**: proportions and timings follow the golden ratio (φ): pauses,
greys, the reading line at φ⁻² down the screen, the focus fade. Keep new
work in that system rather than picking arbitrary numbers.

**Comments** say what the code is for, in plain words, in the same voice as
the rest. Remove comments about past versions, and update a file's header
when you change what the file does.

## Releasing

1. Bump `VERSION` (patch for fixes and clean-ups, minor for features).
2. Update README.md where behaviour changed (the Development table lists
   every file in src/, tests/ and dev/).
3. `dev/check.sh`.
4. Commit `src/`, `install.sh`, `VERSION` and docs together. Message: a title
   `What changed (vX.Y.Z)`, a blank line, then `- ` bullets saying what the
   user will notice, then the trailers.
5. Push to the working branch. Merge to `main` only when asked: the phone
   updates from `main` (`kokoro --update`; `kokoro --update BRANCH` for a
   branch).
