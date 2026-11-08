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

KOKOMUX_VERSION="@@VERSION@@"
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
  KOKORO_THEME="$(sed -n 's/^KOKORO_THEME=\([a-z]*\).*/\1/p' "$HOME/.config/kokoro/config" 2>/dev/null | tail -n 1)"
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
need_mb=600; [ "$MODE" = update ] && need_mb=200
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
KOKORO_WORDS_OFFSET_MS=100

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
  mkdir -p "$HOME/.config/kokoro"
  if command -v termux-clipboard-get >/dev/null && timeout 4 termux-clipboard-get >/dev/null 2>&1; then
    touch "$WORK/.api"; echo yes > "$HOME/.config/kokoro/termux-api"
  else
    echo no > "$HOME/.config/kokoro/termux-api"
  fi
  # The container's size, for the summary. Only a nicety: du complains about the
  # odd file it can't read inside a proot container, and that mustn't fail the step.
  local root size
  for root in "$PREFIX/var/lib/proot-distro/containers/$DISTRO" "$PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO"; do
    if [ -d "$root" ]; then
      size="$(du -sm "$root" 2>/dev/null | cut -f1)" || true
      [ -n "$size" ] && echo "$size" > "$WORK/.size"
      break
    fi
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
  'kokoro|home: books, clipboard, files'
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
