#!/data/data/com.termux/files/usr/bin/bash
# kokomux: Kokoro text-to-speech for Termux. https://github.com/Anesu/kokomux
#
#   bash install.sh [--verbose]
#
# Sets up a small Debian container ("kokoro") with proot-distro, installs
# Kokoro in it, and adds the `kokoro` command to Termux. Kokoro's Python
# packages only ship glibc builds, which don't run on Termux directly.
# Safe to run again: it skips what's done and updates the rest.
#
# GENERATED FILE: edit the files in src/ and run ./build.sh.
set -euo pipefail

KOKOMUX_VERSION="@@VERSION@@"
DISTRO=kokoro

VERBOSE=0
for arg in "$@"; do
  case "$arg" in
    -y|--yes) ;;   # kokoro --update before v0.17.1 passes it
    -v|--verbose) VERBOSE=1 ;;
    -h|--help) echo "Usage: bash install.sh [--verbose]"; exit 0 ;;
    *) echo "Unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

# ================================================================ look & feel

# The colours: the same palette as kokoro (theme.sh, built in below), made
# from the terminal's own background, which we ask it for once (and remember,
# so kokoro's messages match from the start).
# shellcheck disable=SC2034  # it also sets the colours only kokoro's screens use
{
#@EMBED theme.sh
}

FANCY=0   # colours + spinners, only on a real terminal
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-dumb}" != dumb ]; then FANCY=1; fi
if [ "$FANCY" = 1 ]; then
  KOKORO_THEME="$(sed -n 's/^KOKORO_THEME=\([a-z]*\).*/\1/p' "$HOME/.config/kokoro/config" 2>/dev/null | tail -n 1)" || true   # no settings yet on a first install
  [ -t 0 ] && { theme_detect || true; }
  theme_apply
else
  theme_off
fi
SPIN=(⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏)

COLS="$(stty size 2>/dev/null </dev/tty | cut -d' ' -f2)" || true
[[ "${COLS:-}" =~ ^[0-9]+$ ]] && [ "$COLS" -ge 20 ] || COLS=48
W=$(( COLS > 72 ? 72 : COLS ))   # layout width

say()   { printf '%s\n' "$*"; }
blank() { printf '\n'; }
heading() { blank; printf '  %s%s%s\n' "$B" "$1" "$R"; }
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
  local i=0 line
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
  GRAD=("" "" "" "" "" "")
  if [ "$COLS" -ge 66 ]; then
    [ "$FANCY" = 1 ] && theme_grad 6              # violet to cyan, one colour a line
    for line in "${big[@]}"; do
      printf '  %s%s%s\n' "${GRAD[i]}" "$line" "$R"
      i=$((i + 1))
    done
  else
    [ "$FANCY" = 1 ] && theme_grad 3
    for line in "${small[@]}"; do
      # the ░ texture recedes so the letters stand out
      [ "$FANCY" = 1 ] && line="${line//░/$GHOST░${GRAD[i]}}"
      printf '  %s%s%s\n' "${GRAD[i]}" "$line" "$R"
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
      # (with line wrapping off while it's drawn: a line too long for the screen is cut, not wrapped)
      printf '\033[?7l\r\033[K  %s%s%s %s%s%s %s%s  %s%s%s%s\033[?7h' "$ACC" "${SPIN[i % 10]}" "$R" "$D" "$tag" "$R" \
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
      # Not `( ... ) || rc=$?`: that would switch off set -e inside the step, so a
      # step could pass here and fail on a terminal (where it runs as below).
      set +e; ( set -e; "$@" ) >> "$LOG" 2>&1 < /dev/null; rc=$?; set -e
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
  # This step's output only, wrapped, last 8 lines.
  awk '/^=== \[/ { n = 0; next } !/^@@progress/ && NF { l[n++] = $0 }
       END { for (i = (n > 8 ? n - 8 : 0); i < n; i++) print l[i] }' "$LOG" |
    tr -d '\r' | tr -cd '\11\12\40-\176' | fold -s -w $(( W - 7 )) | tail -n 8 |
    while IFS= read -r line; do say "$edge  $D$line$R"; done
  say "$edge"
  wrap "$edge  " "$hint"
  say "$edge  ${D}Log:${R} ${ACC2}$(tilde "$LOG")${R}"
  printf '  %s╰─%s %sRun the installer again.%s\n' "$RED" "$R" "$D" "$R"
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
  say "  Stopped. Run the installer again to carry on."
  blank
  exit 130
}
trap on_exit EXIT
trap on_interrupt INT TERM

# ================================================================ start

banner

[ -n "${PREFIX:-}" ] && [ -d "$PREFIX/bin" ] || { blank; say "  ${RED}✗${R} This installer runs inside Termux."; blank; exit 1; }
OLD_VERSION=""
if [ -x "$PREFIX/bin/kokoro" ] && grep -q '^# kokomux' "$PREFIX/bin/kokoro" 2>/dev/null; then
  MODE=update
  OLD_VERSION="$(sed -n 's/^KOKOMUX_VERSION="\(.*\)"$/\1/p' "$PREFIX/bin/kokoro" | head -n 1)"
else
  MODE=install
fi

if [ "$MODE" = install ]; then
  heading "Installing"
elif [ -z "$OLD_VERSION" ] || [ "$OLD_VERSION" = "$KOKOMUX_VERSION" ]; then
  heading "Reinstalling"
else
  heading "Updating ${D}v$OLD_VERSION →${R}${B} ${ACC}v$KOKOMUX_VERSION"
fi

if command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock >/dev/null 2>&1; then
  WAKELOCK=1
fi

rm -rf "$WORK"; mkdir -p "$WORK"

cat > "$WORK/engine.py" <<'__KOKOMUX_ENGINE__'
#@EMBED engine.py
__KOKOMUX_ENGINE__

cat > "$WORK/words.py" <<'__KOKOMUX_WORDS__'
#@EMBED words.py
__KOKOMUX_WORDS__

cat > "$WORK/tui.sh" <<'__KOKOMUX_TUI__'
#@EMBED tui.sh
__KOKOMUX_TUI__

cat > "$WORK/player.sh" <<'__KOKOMUX_PLAYER__'
#@EMBED player.sh
__KOKOMUX_PLAYER__

cat > "$WORK/theme.sh" <<'__KOKOMUX_THEME__'
#@EMBED theme.sh
__KOKOMUX_THEME__

cat > "$WORK/setup.sh" <<'__KOKOMUX_SETUP__'
#@EMBED setup-container.sh
__KOKOMUX_SETUP__

cat > "$WORK/kokoro" <<'__KOKOMUX_WRAPPER__'
#@EMBED kokoro
__KOKOMUX_WRAPPER__

# ================================================================ the work

termux_packages() {
  local apt_opts=(-y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)
  export DEBIAN_FRONTEND=noninteractive
  # Finish anything an earlier run left half set up, then bring Termux up to date:
  # new packages (mpv, ffmpeg) installed onto older libraries can't be configured.
  dpkg --configure -a --force-confdef --force-confold || true
  pkg update "${apt_opts[@]}"
  apt-get "${apt_opts[@]}" --fix-broken install || true
  pkg upgrade "${apt_opts[@]}"
  # mpv plays (gaplessly), socat talks to it, ffmpeg makes audiobooks.
  pkg install "${apt_opts[@]}" proot-distro curl mpv socat ffmpeg termux-api
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
  mkdir -p "$PREFIX/share/kokomux"
  cp "$WORK/tui.sh" "$PREFIX/share/kokomux/tui.sh"         # the full-screen screens
  cp "$WORK/player.sh" "$PREFIX/share/kokomux/player.sh"   # playing a text
  cp "$WORK/theme.sh" "$PREFIX/share/kokomux/theme.sh"     # the colours

  local conf="$HOME/.config/kokoro/config"
  if [ ! -f "$conf" ]; then
    mkdir -p "${conf%/*}"
    cat > "$conf" <<'CFG'
# kokomux settings, used by every `kokoro` command. Edit with: kokoro --config

KOKORO_VOICE=af_heart     # see: kokoro --list-voices
KOKORO_BLEND=             # mix in a second voice, and how much of it: e.g. af_bella:30
KOKORO_SPEED=1.0          # 0.5 to 2.0
KOKORO_LANG=en-us         # en-us en-gb es fr-fr it pt-br hi
KOKORO_OUT=kokoro.wav     # file name when -o isn't given (.wav .mp3 .ogg .flac)

# Keep Kokoro loaded in the background so each command starts instantly.
# It stops by itself after KOKORO_IDLE_MINUTES without use.
KOKORO_AUTOSERVE=1
KOKORO_IDLE_MINUTES=30
KOKORO_PORT=8880

# Light up each word as it's spoken (with -p, on a terminal), from Kokoro's own
# timings. Raise the offset if the highlight runs ahead of the voice, lower it if
# it lags.
KOKORO_WORDS=1
KOKORO_WORDS_OFFSET_MS=0

# While listening, a notification with pause / next / again buttons (needs the
# Termux:API app). Swiping it away stops.
KOKORO_NOTIFY=1

# -p on a terminal opens the full-screen reader (Space pauses, arrows skip, q
# stops; click or tap the buttons). Set to 0 to keep the inline view.
KOKORO_TUI=1

# Colours: auto follows your terminal's own (dark or light). dark or light
# uses that palette in the full-screen views, with its own background if the
# terminal is the other kind.
KOKORO_THEME=auto

# The reader's focus effect: text fades the further it is from the part being
# read, like a camera's depth of field. 0 shows it all evenly.
KOKORO_FOCUS=1
CFG
  fi

  # Shared links, text and files go to the queue (kokoro --queue plays it).
  put "$HOME/bin/termux-url-opener" 'kokoro --add -u "$1"; sleep 1'
  put "$HOME/bin/termux-file-editor" 'case "$(head -c 4 "$1")" in %PDF*|PK*) kokoro --add -f "$1"; sleep 1; exit ;; esac   # PDF, EPUB
if grep -Iq . "$1"; then kokoro --add -f "$1"; sleep 1; exit; fi
echo "kokoro: that file is not text, an EPUB or a PDF"; sleep 3'
  # Termux:Widget runs scripts in ~/.shortcuts/tasks in the background.
  put "$HOME/.shortcuts/tasks/Kokoro read clipboard" 'exec kokoro -p -c'
  put "$HOME/.shortcuts/tasks/Kokoro stop" 'exec kokoro --stop'
  put "$HOME/.shortcuts/tasks/Kokoro pause" 'exec kokoro --pause'
  put "$HOME/.shortcuts/tasks/Kokoro next" 'exec kokoro --next'
  put "$HOME/.shortcuts/tasks/Kokoro continue" 'exec kokoro --continue'
  put "$HOME/.shortcuts/tasks/Kokoro play queue" 'exec kokoro --queue'

  in_container tidy
}

GREETING="Hi! kokomux is ready. Type kokoro, dash p, and some text, and I'll read it to you."
voice_test() {
  kokoro -o "$WORK/hello.wav" "$GREETING"
  # Is the Termux:API app there? (termux-* commands hang without it.)
  mkdir -p "$HOME/.config/kokoro"
  if command -v termux-clipboard-get >/dev/null && timeout 4 termux-clipboard-get >/dev/null 2>&1; then
    touch "$WORK/.api"; echo yes > "$HOME/.config/kokoro/termux-api"
  else
    echo no > "$HOME/.config/kokoro/termux-api"
  fi
  note "sounds good"
}

hide_cursor
step "Termux packages" "Pick another mirror with termux-change-repo." termux_packages
step "Debian container" "Check the connection." debian_container
step "Python" "Check the connection." in_container apt
step "Kokoro" "Check the connection." in_container pip
step "Download" "Check the connection. It resumes where it stopped." in_container models
step "kokoro command" "The log says why." kokoro_command
step "Voice test" "The log says why." voice_test
show_cursor

# ================================================================ done

took="$(human_time $(( SECONDS - T_START )))"

blank
if [ "$MODE" = install ]; then
  bar="${ACC}│${R}"
  printf '  %s╭─%s %s%s✓ kokomux is ready%s\n' "$ACC" "$R" "$B" "$GRN" "$R"
  say "  $bar"
  for t in 'kokoro|home' 'kokoro -p -f book.epub|read a book' 'kokoro --help|everything else'; do
    printf '  %s  %s%-22s%s %s%s%s\n' "$bar" "$ACC2" "${t%%|*}" "$R" "$D" "${t#*|}" "$R"
  done
  say "  $bar"
  printf '  %s╰─%s %s%s%s\n' "$ACC" "$R" "$D" "$took" "$R"
elif [ "$OLD_VERSION" = "$KOKOMUX_VERSION" ] || [ -z "$OLD_VERSION" ]; then
  printf '  %s✓ kokomux reinstalled%s %s%s%s\n' "$GRN" "$R" "$D" "$took" "$R"
else
  printf '  %s✓ kokomux updated to v%s%s %s%s%s\n' "$GRN" "$KOKOMUX_VERSION" "$R" "$D" "$took" "$R"
fi
# Only what needs doing.
[ -f "$WORK/.api" ] || say "  ${YEL}!${R} Install the Termux:API app to read the clipboard."
if [ -s "$SKIPPED" ]; then
  while read -r f; do say "  ${YEL}!${R} Kept your own $f."; done < "$SKIPPED"
fi
blank

[ "$FANCY" = 1 ] && printf '\a'
if [ -f "$WORK/hello.wav" ]; then
  printf '  %s♪%s %sSaying hello...%s' "$ACC" "$R" "$D" "$R"
  mpv --no-video --really-quiet "$WORK/hello.wav" </dev/null >/dev/null 2>&1 || true
  printf '\r\033[K'
fi
