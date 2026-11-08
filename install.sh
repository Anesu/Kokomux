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

KOKOMUX_VERSION="0.10.0"
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
# kokomux: colours. Sourced by `kokoro` and built into the installer (bash).
# shellcheck shell=bash disable=SC2034  # the colours are used by whoever sources this
#
#   theme_apply [screen]   set the colours below for the terminal (or, with
#                          "screen", for a full-screen view, which may paint
#                          its own background)
#   theme_detect           ask the terminal what its colours are (OSC 10/11)
#   theme_grad N           GRAD: N colours from violet to cyan (the logo)
#   theme_tone LEVEL       REPLY: the text colour faded to LEVEL thousandths
#                          (the reader's focus effect)
#
# One palette for dark and light, derived from the terminal's own background:
# every grey is the background moved part of the way towards the text colour,
# in steps of the golden ratio (φ = 1.618…). Each step is φ times the one
# before it, so the levels read as evenly spaced, and there are only five:
#
#   φ⁻¹  .618  D      secondary text: hints, labels, what's still to be read
#   φ⁻²  .382  LINE   rules, the progress bar's track
#   φ⁻³  .236  GHOST  the logo's texture; PSEL, a selected row on a panel
#   φ⁻⁴  .146  SURF   buttons and panels; SEL, a selected row (accent-tinted)
#   ½          PAST   what's already been heard: it recedes, but stays legible
#
# The accents (violet ACC, sky ACC2, GRN, RED, YEL) come in a dark and a light
# set, each checked against the backgrounds of popular terminal themes
# (tests/test_theme.py): text ≥ 10:1, accents and secondary text ≈ 4.5:1 or
# better. The word being spoken (HL) sits on the background moved φ⁻² of the
# way towards the accent: soft, but clearly lit.
#
# KOKORO_THEME picks: auto (follow the terminal), dark or light. A full-screen
# view on a terminal of the other kind paints its own background; plain
# command-line output always follows the terminal, since it can't.

THEME_STEPS=(1000 618 382 236 146 90)            # φ⁻ⁿ, in thousandths
# φ^(−i/4), in thousandths: a falloff in quarter golden steps (the focus effect)
THEME_DECAY=(1000 887 786 697 618 548 486 431 382 339 300 266 236 209 186 165 146 129 115 102
             90 80 71 63 56 49 44 39 34 31 27 24 21 19 17 15 13 12 10 9 8)
declare -gA THEME_TONES=()            # theme_tone's colours, by level
# name:   background  text    violet  sky     green   red     amber   cyan (logo end)
THEME_DARK=(16151d    e8e6f0  b69cff  7cd0f5  86d39a  ff7a7a  efc764  9ff0ec)
THEME_LIGHT=(fbfaf8   1c1b22  6438c9  0b67a0  21743a  c0262d  855300  0f7c86)
THEME_TERM_BG=""; THEME_TERM_FG=""    # the terminal's colours as rrggbb (asked, or remembered)
THEME_ASKED=0                         # 1 once theme_detect has asked, this run
THEME_MODE=dark; THEME_PAINT=0; THEME_TERM_MODE=""; THEME_TRUE=0; THEME_ON=0
THEME_CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/kokomux/theme"

# --------------------------------------------------------------- colour maths
# Colours are rrggbb strings; results come back in REPLY (no subshells: this
# runs every time kokoro starts).

theme_mix() {    # theme_mix A B T: A moved T thousandths of the way to B
  local a=$1 b=$2 t=$3 i x y out=""
  for i in 0 2 4; do
    x=$(( 16#${a:i:2} )) y=$(( 16#${b:i:2} ))
    printf -v out '%s%02x' "$out" $(( x + ((y - x) * t + ((y - x) * t >= 0 ? 500 : -500)) / 1000 ))
  done
  REPLY=$out
}

theme_luma() {   # theme_luma RGB: perceived brightness, 0 to 255
  REPLY=$(( (2126 * 16#${1:0:2} + 7152 * 16#${1:2:2} + 722 * 16#${1:4:2}) / 10000 ))
}

theme_sgr() {    # theme_sgr 38|48 RGB: the SGR parameters for that colour (REPLY)
  local r=$(( 16#${2:0:2} )) g=$(( 16#${2:2:2} )) b=$(( 16#${2:4:2} ))
  if [ "$THEME_TRUE" = 1 ]; then REPLY="$1;2;$r;$g;$b"; return 0; fi
  # The nearest of the 256 colours: the 6×6×6 cube or the 24 greys.
  local -a lv=(0 95 135 175 215 255)
  local ri gi bi k v d1 d2 grey
  for k in r g b; do
    v=${!k}; if [ "$v" -lt 48 ]; then v=0; elif [ "$v" -lt 115 ]; then v=1; else v=$(( (v - 35) / 40 )); fi
    printf -v "${k}i" '%s' "$v"
  done
  d1=$(( (r - lv[ri]) ** 2 + (g - lv[gi]) ** 2 + (b - lv[bi]) ** 2 ))
  grey=$(( (r + g + b) / 3 )); k=$(( grey > 238 ? 23 : (grey < 8 ? 0 : (grey - 3) / 10) ))
  v=$(( 8 + 10 * k ))
  d2=$(( (r - v) ** 2 + (g - v) ** 2 + (b - v) ** 2 ))
  if [ "$d2" -lt "$d1" ]; then REPLY="$1;5;$(( 232 + k ))"; else REPLY="$1;5;$(( 16 + 36 * ri + 6 * gi + bi ))"; fi
}

theme_fg() { theme_sgr 38 "$1"; REPLY=$'\033['"$REPLY"m; }
theme_bg() { theme_sgr 48 "$1"; REPLY=$'\033['"$REPLY"m; }

# --------------------------------------------------------------- the terminal

theme_truecolor() {
  case "${KOKORO_TRUECOLOR:-}" in 1) return 0 ;; 0) return 1 ;; esac
  case "${COLORTERM:-}" in truecolor|24bit) return 0 ;; esac
  [ -n "${TERMUX_VERSION:-}" ] && return 0                  # Termux has 24-bit colour
  case "${TERM_PROGRAM:-}" in iTerm.app|WezTerm|vscode|ghostty) return 0 ;; esac
  case "${TERM:-}" in *-direct|xterm-kitty|alacritty|foot*|wezterm) return 0 ;; esac
  return 1
}

theme_detect() { # ask the terminal for its background and text colours
  # OSC 11 and 10 ask; DA1 (which every terminal answers) marks the end of the
  # replies, so a terminal that doesn't know OSC 11 costs no waiting.
  local fd saved="" buf="" c n=0 hex x wait=1 got=0
  THEME_ASKED=1
  [ -t 2 ] || return 1
  { exec {fd}<>/dev/tty; } 2>/dev/null || return 1
  saved="$(stty -g <&"$fd" 2>/dev/null)" || saved=""
  stty -echo -icanon <&"$fd" 2>/dev/null || true
  printf '\033]11;?\033\\\033]10;?\033\\\033[c' >&"$fd"
  while [ "$n" -lt 160 ] && IFS= read -r -s -n1 -t "$wait" -u "$fd" c; do
    buf+=$c; n=$(( n + 1 )) wait=0.2                    # the first reply may take a moment
    [[ "$buf" =~ $'\033'\[\?[0-9\;]*c$ ]] && break
  done
  [ -n "$saved" ] && stty "$saved" <&"$fd" 2>/dev/null
  exec {fd}>&-
  for x in 11 10; do
    [[ "$buf" =~ \]$x\;rgb:([0-9a-fA-F]+)/([0-9a-fA-F]+)/([0-9a-fA-F]+) ]] || continue
    hex=""
    for c in "${BASH_REMATCH[@]:1:3}"; do              # 1 to 4 hex digits per channel
      [ "${#c}" = 1 ] && c="$c$c"
      hex+="${c:0:2}"
    done
    hex=${hex,,}
    if [ "$x" = 11 ]; then THEME_TERM_BG=$hex got=1; else THEME_TERM_FG=$hex; fi
  done
  [ "$got" = 1 ] || return 1                          # no answer: what we remembered stands
  # Remember it, so plain command-line output can follow the terminal too.
  if [ "$(cat "$THEME_CACHE" 2>/dev/null)" != "$THEME_TERM_BG $THEME_TERM_FG" ]; then
    mkdir -p "${THEME_CACHE%/*}" 2>/dev/null && printf '%s %s\n' "$THEME_TERM_BG" "$THEME_TERM_FG" > "$THEME_CACHE" 2>/dev/null
  fi
  return 0
}

theme_term() {   # THEME_TERM_MODE (dark, light or "") and THEME_TERM_BG, from what we know
  local f="" bgi
  if [ -z "$THEME_TERM_BG" ] && [ -f "$THEME_CACHE" ]; then
    read -r f _ < "$THEME_CACHE" 2>/dev/null || f=""
    [[ "$f" =~ ^[0-9a-f]{6}$ ]] && THEME_TERM_BG=$f
  fi
  THEME_TERM_MODE=""
  if [ -n "$THEME_TERM_BG" ]; then
    theme_luma "$THEME_TERM_BG"; if [ "$REPLY" -ge 128 ]; then THEME_TERM_MODE=light; else THEME_TERM_MODE=dark; fi
  elif [[ "${COLORFGBG:-}" =~ ([0-9]+)$ ]]; then          # "15;0": text white, background black
    bgi=${BASH_REMATCH[1]}
    if [ "$bgi" = 7 ] || [ "$bgi" -ge 9 ]; then THEME_TERM_MODE=light; else THEME_TERM_MODE=dark; fi
  fi
}

# --------------------------------------------------------------- the palette

theme_off() {
  R="" B="" D="" PAST="" LINE="" GHOST="" ACC="" ACC2="" GRN="" RED="" YEL=""
  HL="" SURF="" SEL="" PSEL="" BASE="" ACC_SGR="" D_SGR=""
  THEME_ON=0
}

theme_apply() {  # theme_apply [screen]
  local want=${KOKORO_THEME:-auto} screen=${1:-} bg fg
  local -a p
  case "$want" in dark|light) ;; *) want=auto ;; esac
  theme_term
  THEME_PAINT=0
  if [ "$want" != auto ] && [ "$screen" = screen ]; then
    THEME_MODE=$want
    [ "$THEME_TERM_MODE" = "$want" ] || THEME_PAINT=1           # paint our own canvas
  elif [ -n "$THEME_TERM_MODE" ]; then THEME_MODE=$THEME_TERM_MODE
  elif [ "$want" != auto ]; then THEME_MODE=$want                # the best guess we have
  else THEME_MODE=dark; fi
  if [ "$THEME_MODE" = light ]; then p=("${THEME_LIGHT[@]}"); else p=("${THEME_DARK[@]}"); fi
  # The greys are made from the real background when we know it, and always
  # lead towards the palette's own text colour, so they keep their contrast
  # even with a low-contrast terminal theme.
  bg=${p[0]} fg=${p[1]}
  [ "$THEME_PAINT" = 0 ] && [ -n "$THEME_TERM_BG" ] && [ "$THEME_TERM_MODE" = "$THEME_MODE" ] && bg=$THEME_TERM_BG
  THEME_BG=$bg THEME_FG=$fg THEME_ACC=${p[2]} THEME_END=${p[7]}
  THEME_TONES=()
  if theme_truecolor; then THEME_TRUE=1; else THEME_TRUE=0; fi

  R=$'\033[0m' B=$'\033[1m' BASE=""
  if [ "$THEME_PAINT" = 1 ]; then
    theme_bg "$bg"; BASE=$REPLY; theme_fg "$fg"; BASE+=$REPLY
    R+=$BASE                                    # a reset goes back to our canvas
  fi
  theme_mix "$bg" "$fg" "${THEME_STEPS[1]}"; theme_fg "$REPLY"; D=$REPLY
  D_SGR=${D#$'\033['}; D_SGR=${D_SGR%m}
  theme_mix "$bg" "$fg" 500;                  theme_fg "$REPLY"; PAST=$REPLY
  theme_mix "$bg" "$fg" "${THEME_STEPS[2]}"; theme_fg "$REPLY"; LINE=$REPLY
  theme_mix "$bg" "$fg" "${THEME_STEPS[3]}"; theme_fg "$REPLY"; GHOST=$REPLY
  theme_mix "$bg" "$fg" "${THEME_STEPS[4]}"; theme_bg "$REPLY"; SURF=$REPLY
  local tint; theme_mix "$fg" "${p[2]}" "${THEME_STEPS[1]}"; tint=$REPLY
  theme_mix "$bg" "$tint" "${THEME_STEPS[4]}"; theme_bg "$REPLY"; SEL=$REPLY
  theme_mix "$bg" "$tint" "${THEME_STEPS[3]}"; theme_bg "$REPLY"; PSEL=$REPLY
  theme_mix "$bg" "${p[2]}" "${THEME_STEPS[2]}"; theme_bg "$REPLY"; HL=$REPLY
  theme_fg "$fg"; HL+=$REPLY
  theme_fg "${p[2]}"; ACC=$REPLY; ACC_SGR=${ACC#$'\033['}; ACC_SGR=${ACC_SGR%m}
  theme_fg "${p[3]}"; ACC2=$REPLY
  theme_fg "${p[4]}"; GRN=$REPLY
  theme_fg "${p[5]}"; RED=$REPLY
  theme_fg "${p[6]}"; YEL=$REPLY
  THEME_ON=1
}

theme_grad() {   # theme_grad N: GRAD, N colours from the accent to the logo's cyan
  local n=$1 k
  GRAD=()
  for (( k = 0; k < n; k++ )); do
    theme_mix "$THEME_ACC" "$THEME_END" $(( n > 1 ? k * 1000 / (n - 1) : 0 ))
    theme_fg "$REPLY"; GRAD+=("$REPLY")
  done
}

theme_tone() {   # theme_tone LEVEL: REPLY = the text colour, LEVEL thousandths of the way from the background
  local k=$(( ($1 + 5) / 10 * 10 ))                # in steps of 1%, so there are few to remember
  if [ -z "${THEME_TONES[$k]+x}" ]; then
    theme_mix "$THEME_BG" "$THEME_FG" "$k"; theme_fg "$REPLY"; THEME_TONES[$k]=$REPLY
  fi
  REPLY=${THEME_TONES[$k]}
}

theme_name() {   # what the settings menu shows beside "Colours"
  case "${KOKORO_THEME:-auto}" in
    dark) REPLY="Dark" ;; light) REPLY="Light" ;;
    *) REPLY="Auto" ;;
  esac
}
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
# Kokoro 82M v1.0, 8-bit, exported with a "durations" output: it reports when each
# sound is spoken, which is what lights up each word. Pinned to a commit.
MODEL = os.path.join(MODELS, "kokoro-v1.0-timestamped.int8.onnx")
MODEL_SRC = ("https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX-timestamped/resolve/"
             "dd4401a9add81ac692d20e240d22ec9dda82cc29/onnx/model_quantized.onnx")
OLD_MODEL = os.path.join(MODELS, "kokoro-v1.0.int8.onnx")   # before 0.8: no timings
VOICES = os.path.join(MODELS, "voices-v1.0.bin")
VOICES_SRC = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin"
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
            model = MODEL if os.path.exists(MODEL) else OLD_MODEL
            _tts = Kokoro(model, VOICES)
            # kokoro-onnx looks for an output called "duration"; this export calls it
            # "durations". It's the second output either way, which is what it reads.
            _tts.has_timings = len(_tts.sess.get_outputs()) > 1
    return _tts


def phonemize_many(words, lang):
    """Each word's phonemes on its own, the way Kokoro phonemises (call under SYNTH_LOCK)."""
    import phonemizer
    from kokoro_onnx.tokenizer import Tokenizer
    vocab = tts().tokenizer.vocab
    out = phonemizer.phonemize([Tokenizer.normalize_text(w) for w in words], lang,
                               preserve_punctuation=True, with_stress=True)
    return ["".join(ch for ch in p if ch in vocab) for p in out]


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
        self.heading = ""  # the first heading: an EPUB chapter's title
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
                if self.block in self.HEADINGS and not self.heading:
                    self.heading = t
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
    """An EPUB's (title, [(chapter title, text)]): its chapters in reading order."""
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
                    parts.append((ex.heading or f"Part {len(parts) + 1}", chapter))
    except JobError:
        raise
    except (KeyError, StopIteration, zipfile.BadZipFile, ET.ParseError) as e:
        raise JobError(f"Couldn't read that EPUB ({type(e).__name__})") from None
    if not parts:
        raise JobError("That EPUB has no readable text")
    return (title or "").strip() or name, parts


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
    title = str(title).strip() or name
    return title, [(title, "\n".join(sentences))]


def read_source(jobdir, job):
    """What a job reads, as (title, [(chapter title, text)]): a web page, or a file
    that is an EPUB, a PDF or plain text. Only an EPUB has more than one chapter."""
    name = job.get("name", "")
    if job.get("url"):
        log(f"Fetching {job['url']}")
        title, text = fetch_url(job["url"])
        return title, [(title, text)]
    file = job.get("file", "in.txt")
    if "/" in file:
        raise JobError(f"Bad input file name: {file}")
    path = os.path.join(jobdir, file)
    with open(path, "rb") as fh:
        head = fh.read(64)
    if head.startswith(b"%PDF"):
        return read_pdf(path, name)
    if head.startswith(b"PK") and zipfile.is_zipfile(path):
        return read_epub(path, name)   # already chapters
    with open(path, encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    bad = text.count("\ufffd") + text.count("\x00")
    if text and bad > max(3, len(text) // 100):
        raise JobError("That doesn't look like text, an EPUB or a PDF")
    return name, [(name, text)]


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
    timed = False
    if job.get("stream") == "1":
        stream = os.path.join(jobdir, "stream")
        os.makedirs(stream, exist_ok=True)
        # Word timings come from Kokoro itself, with the audio.
        timed = job.get("words") == "1" and engine.has_timings
        if job.get("words") == "1" and not timed:
            _write_atomic(os.path.join(stream, "words-off"), "this model reports no timings (kokoro --update)\n")

    writer = None
    total = 0.0
    try:
        title, chapters = read_source(jobdir, job)
        _write_atomic(os.path.join(jobdir, "title"), (title or "").replace("\n", " ")[:120] + "\n")
        # Each chapter is split on its own, so a part never runs across chapters, and
        # where each chapter starts is written down for the player's chapter list.
        chunks, starts = [], []
        for heading, text in chapters:
            parts = split_text(text)
            if parts:
                starts.append(f"{len(chunks) + 1}\t{heading.replace(chr(9), ' ').replace(chr(10), ' ')[:80]}")
                chunks += parts
        if not chunks:
            raise JobError("No text to read")
        if len(starts) > 1:
            _write_atomic(os.path.join(jobdir, "chapters"), "\n".join(starts) + "\n")
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
                if timed:
                    import words
                    samples, rate, timings = engine.create_timed(chunk, voice=voice, speed=speed, lang=lang)
                    marks = words.words_from_timings(chunk, timings, lambda ws: phonemize_many(ws, lang))
                else:
                    samples, rate = engine.create(chunk, voice=voice, speed=speed, lang=lang)
            total += len(samples) / rate
            if stream:
                # The text and its word timings first, so the player has them the
                # moment the audio lands.
                _write_atomic(os.path.join(stream, f"{i:04d}.txt"), chunk)
                if timed:
                    _write_atomic(os.path.join(stream, f"{i:04d}.words"), words.player_rows(chunk, marks))
                tmp = os.path.join(stream, f"{i:04d}.tmp.wav")
                sf.write(tmp, samples, rate)
                os.replace(tmp, os.path.join(stream, f"{i:04d}.wav"))
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
    for dest, src, label in ((MODEL, MODEL_SRC, "model"), (VOICES, VOICES_SRC, "voices")):
        name = os.path.basename(dest)
        if os.path.exists(dest) and os.path.getsize(dest) > 0:
            print(f"@@progress {label} 100 0 0")
            print(f"{name}: already downloaded", flush=True)
            continue
        part = dest + ".part"
        for attempt in range(1, 6):
            have = os.path.getsize(part) if os.path.exists(part) else 0
            req = urllib.request.Request(src, headers={"User-Agent": USER_AGENT})
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
    if os.path.exists(OLD_MODEL):
        os.remove(OLD_MODEL)   # the timestamped model replaces it (saves 88 MB)


# ------------------------------------------------------------------ voices

LANGS = {"a": ("American English", "en-us"), "b": ("British English", "en-gb"),
         "e": ("Spanish", "es"), "f": ("French", "fr-fr"), "h": ("Hindi", "hi"),
         "i": ("Italian", "it"), "j": ("Japanese", "ja"),
         "p": ("Brazilian Portuguese", "pt-br"), "z": ("Mandarin Chinese", "cmn")}


def sgr(params, fallback):
    """An SGR escape from kokoro's theme ("38;2;182;156;255"), or the fallback."""
    return f"\033[{params}m" if params and all(c in "0123456789;" for c in params) else fallback


def print_voices(names, width, default, accent="", muted=""):
    """Group voices by language and gender, wrapped to the terminal width.
    Colour only when stdout is a terminal, in kokoro's theme colours."""
    tty = (sys.stdout.isatty() or "--color" in sys.argv) and not os.environ.get("NO_COLOR")
    bold, dim, acc, off = ("\033[1m", sgr(muted, "\033[2m"), sgr(accent, "\033[38;5;141m"), "\033[0m") if tty else ("",) * 4
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
    v.add_argument("--accent", default="", help="SGR parameters for the accent colour")
    v.add_argument("--muted", default="", help="SGR parameters for secondary text")
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
            print_voices(names, a.width, a.default, a.accent, a.muted)
    elif a.cmd == "download":
        download()


if __name__ == "__main__":
    main()
__KOKOMUX_ENGINE__

cat > "$WORK/words.py" <<'__KOKOMUX_WORDS__'
"""Word timings for spoken lines: which word is being said, and when.

Runs inside the "kokoro" Debian container, next to engine.py.

Kokoro reports how long it spends on every sound it makes (the timestamped
model's "durations" output, read by kokoro-onnx as per-phoneme timings). Each
word of the line is phonemised on its own, and its sounds are lined up against
the sounds Kokoro actually spoke, so a word takes exactly the times of the
phonemes it became. No speech recogniser is needed, and it works in every
language Kokoro speaks.

A line's result has the shape {v, engine, confidence, words: [{s, e, a, b, p}]}:
s/e are the start and end in ms, a/b the character range in the line's text,
and p the confidence (the share of the word's sounds that lined up).
"""
import re

LINE_WORDS_VERSION = 2
# Below this, too few of a line's sounds lined up to trust: it gets no highlight
# rather than a wrong one.
WORDS_MIN_CONFIDENCE = 0.35


def empty_result(engine):
    """Timings for a line that has none usable: nothing is lit."""
    return {"v": LINE_WORDS_VERSION, "engine": engine, "confidence": 0, "words": []}


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


# ---------------------------------------------------------------- Kokoro's own timings

# Not sounds: spaces, stress marks and the punctuation espeak passes through.
_SILENT = set(" ˈˌ;:,.!?¡¿—…\"«»“”()'-")
# Stripped from the ends of a token to find the word to highlight (any script).
_EDGE_PUNCT = "\"'“”‘’«»()[]{}<>.,;:!?¡¿…—–-、。，！？：；（）「」『』।॥"
# Split points inside a token for scripts written without spaces.
_CJK_BREAK = re.compile(r"(?<=[、。，！？：；])")


def _tokens(text):
    """The words to highlight: whitespace-separated tokens (also split after CJK
    punctuation), with punctuation trimmed from their ends, as (start, end)."""
    out = []
    for m in re.finditer(r"\S+", text):
        pos = m.start()
        for part in _CJK_BREAK.split(m.group()):
            core = part.strip(_EDGE_PUNCT)
            if core:
                a = pos + part.index(core)
                out.append((a, a + len(core)))
            pos += len(part)
    return out


def words_from_timings(text, timings, phonemize_many):
    """Word timings for a line from Kokoro's own per-phoneme timings.

    Each word is phonemised on its own, and its sounds are lined up against the
    sounds Kokoro actually spoke (stress marks, spaces and punctuation aside), so
    a word takes the times of exactly the phonemes it became. This survives
    espeak running words together in a sentence ("in the" -> one spoken word)
    and expanding numbers ("1999" -> three). Confidence is the share of sounds
    that lined up. Returns the same shape as the cache: {v, engine, confidence, words}."""
    import difflib

    tokens = _tokens(text)
    spoken = [t for t in timings if t.phoneme not in _SILENT]
    if not tokens or not spoken:
        return empty_result("kokoro")
    per = phonemize_many([text[a:b] for a, b in tokens])
    mine, owner = [], []                       # each token's sounds, end to end
    for i, p in enumerate(per):
        for ch in p:
            if ch not in _SILENT:
                mine.append(ch)
                owner.append(i)
    said = [t.phoneme for t in spoken]
    match = [None] * len(mine)                 # the spoken sound each of mine lined up with
    blocks = difflib.SequenceMatcher(None, mine, said, autojunk=False).get_matching_blocks()
    for blk in blocks:
        for k in range(blk.size):
            match[blk.a + k] = blk.b + k
    matched = sum(m is not None for m in match)
    confidence = round(matched / max(len(mine), len(said)), 3) if mine else 0.0
    words, floor = [], 0
    for i, (a, b) in enumerate(tokens):
        hits = [match[k] for k in range(len(mine)) if owner[k] == i and match[k] is not None]
        if not hits:
            continue                           # nothing of it lined up: leave it unlit
        start, end = spoken[min(hits)].start, spoken[max(hits)].end
        s_ms = max(floor, round(start * 1000))
        e_ms = max(s_ms + 1, round(end * 1000))
        words.append({"s": s_ms, "e": e_ms, "a": a, "b": b, "p": confidence})
        floor = e_ms
    return {"v": LINE_WORDS_VERSION, "engine": "kokoro", "confidence": confidence, "words": words}
__KOKOMUX_WORDS__

cat > "$WORK/tui.sh" <<'__KOKOMUX_TUI__'
# kokomux: the full-screen interface. Sourced by `kokoro` (bash).
# shellcheck shell=bash disable=SC2034,SC2153,SC2154  # shares its globals with kokoro
#
#   tui_home              the home screen: books in progress, and ways to start
#   tui_open / tui_close  the reader: header, a centred reading column with the
#                         spoken word lit, a progress bar and clickable buttons
#
# Built for any terminal size: a phone held upright, or Samsung DeX with a
# keyboard and mouse. It uses the alternate screen (your scrollback is left as
# it was), redraws only what changes, wraps each frame in synchronised output
# where the terminal supports it, and redraws on resize. Mouse clicks and taps
# are read as SGR mouse reports.
#
# Colours come from theme.sh (R B D PAST LINE GHOST ACC ACC2 GRN HL SURF SEL
# PSEL): when a screen opens it asks the terminal for its colours, so the greys
# are made from its real background. Proportions follow the golden ratio: the
# line being read sits φ⁻² (38.2%) of the way down the reading area, where the
# eye rests; the home screen's free space is split 1 : φ above and below its
# content (the optical centre, a little above the middle); settings split
# their width φ⁻² : φ⁻¹ between labels and values.
#
# The focus effect (KOKORO_FOCUS): like a camera's depth of field, text fades
# the further it is from the part being read, by quarter golden steps, down to
# φ⁻⁴ at the edges. Ahead of the voice the next words are in sharpest focus,
# and the part just heard eases out of focus over 382 ms.
#
# Expects from kokoro: the colours, $tmpd, $KBD (a read descriptor on
# /dev/tty), KEY (set by tick), and for the home screen BOOKS, CONF, mark_get,
# KOKORO_VOICE and KOKORO_SPEED.

TUI=0; TUI_TTY=""; TUI_RESIZED=0
TW=80; TH=24; CW=72; LM=4             # screen size; reading column width and margin
declare -A TUI_WRAP=() TUI_OFFS=()    # each part's text wrapped to the column, by part
tui_hist=()                           # parts already heard, oldest first
TUI_PART=0; TUI_PARTS=0; TUI_A=0; TUI_B=0
TUI_STATE=""; TUI_EXTRA=""; TUI_SYM=""; TUI_PAUSED=0; TUI_TITLE=""; TUI_INFO=""
TUI_BTN=()                            # clickable buttons: "row x0 x1 command"
TUI_CHAPTER=""; TUI_HAS_CHAPTERS=0; TUI_BAR=(3 10)   # the progress bar's first column and width
SYNC_ON=$'\033[?2026h'; SYNC_OFF=$'\033[?2026l'
TUI_FOCUS=0; TUI_EASE_AT=0; TUI_EASE_MS=382   # the focus effect; the part just heard eases out

tui_size() {
  local size
  size="$(stty size 2>/dev/null </dev/tty)" || size=""
  if [[ "$size" =~ ^([0-9]+)\ ([0-9]+)$ ]]; then TH=${BASH_REMATCH[1]} TW=${BASH_REMATCH[2]}; fi
  CW=$(( TW - 6 < 76 ? TW - 6 : 76 )); [ "$CW" -ge 20 ] || CW=20
  LM=$(( (TW - CW) / 2 ))
  TUI_WRAP=(); TUI_OFFS=()
}

tui_fits() { tui_size; [ "$TW" -ge 40 ] && [ "$TH" -ge 12 ]; }

tui_raw() {      # keys arrive one at a time, unechoed; clicks and taps are reported
  stty -echo -icanon 2>/dev/null </dev/tty || true
  printf '\033[?25l\033[?1000h\033[?1006h' >&2
}
tui_cooked() {   # back to normal typing, for a prompt
  printf '\033[?1000l\033[?1006l\033[?25h' >&2
  stty echo icanon 2>/dev/null </dev/tty || true
}

tui_enter() {    # the alternate screen, raw keys, and the colours for a full screen
  TUI_TTY="$(stty -g 2>/dev/null </dev/tty)" || TUI_TTY=""
  printf '\033[?1049h' >&2
  tui_raw
  TUI=1
  trap 'TUI_RESIZED=1' WINCH
  tui_theme
}

tui_theme() {    # ask the terminal its colours (once), then pick the palette
  declare -F theme_apply >/dev/null || return 0
  [ "$THEME_ON" = 1 ] || return 0                             # no colours (NO_COLOR)
  [ "$THEME_ASKED" = 1 ] || theme_detect || true
  theme_apply screen
  TUI_FOCUS=0; [ "${KOKORO_FOCUS:-1}" = 1 ] && TUI_FOCUS=1
}

tui_open() {
  [ "$TUI" = 1 ] && return 0
  tui_enter
  tui_size
  tui_draw
}

tui_close() {
  [ "$TUI" = 1 ] || return 0
  printf '\033[0m\033[?1000l\033[?1006l%s\033[?25h\033[?1049l' "$SYNC_OFF" >&2
  [ -n "$TUI_TTY" ] && stty "$TUI_TTY" 2>/dev/null </dev/tty
  trap - WINCH
  TUI=0
  # back to the terminal's own colours for what's printed after
  [ "$THEME_PAINT" = 1 ] && declare -F theme_apply >/dev/null && theme_apply
  return 0
}

tui_refresh() {  # after a resize: measure again and draw everything
  [ "$TUI_RESIZED" = 1 ] || return 0
  TUI_RESIZED=0
  tui_size
  tui_draw
}

rule() { local line; printf -v line '%*s' "$1" ''; printf '%s' "${line// /─}"; }
golden() { REPLY=$(( $1 * 382 / 1000 )); }   # golden N: the smaller golden part of N (N/φ²)

# ---------------------------------------------------------------- drawing

tui_header() {
  local info=" ${TUI_INFO} " room title
  room=$(( TW - 14 - ${#info} ))
  title=$TUI_TITLE
  [ "${#title}" -gt "$room" ] && title="${title:0:$(( room > 1 ? room - 1 : 0 ))}…"
  printf '%s\033[1;1H\033[2K  %skokomux%s  %s%s%s' "$R" "$ACC" "$R" "$B" "$title" "$R" >&2
  printf '\033[1;%dH%s%s%s' $(( TW - ${#info} )) "$D" "$info" "$R" >&2
  printf '\033[2;1H\033[2K%s%s%s' "$LINE" "$(rule "$TW")" "$R" >&2
}

tui_wrap() {     # tui_wrap K: part K's text wrapped to the column, once
  local k=$1 f row off=0 lines="" offs=""
  [ -n "${TUI_WRAP[$k]+x}" ] && return 0
  printf -v f '%s/stream/%04d.txt' "$tmpd" "$k"
  [ -f "$f" ] || return 1
  local LC_ALL=C
  while IFS= read -r row; do
    lines+="$row"$'\n'; offs+="$off "; off=$(( off + ${#row} ))
  done < <(fold -s -w "$CW" "$f"; echo)
  TUI_WRAP[$k]=$lines; TUI_OFFS[$k]=$offs
}

tui_far() {      # tui_far NEAR DIST SCALE: REPLY = the tone DIST rows away (SCALE: rows per golden step, ×1000)
  local i=$(( ($2 * 4000 + $3 / 2) / $3 ))
  [ "$i" -le 40 ] || i=40
  REPLY=$(( 146 + ($1 - 146) * THEME_DECAY[i] / 1000 ))   # never fainter than φ⁻⁴
}

tui_text() {     # the reading column: what's been heard above, this part, what's next below
  local top=4 bottom=$(( TH - 5 )) h k j n r idx a hl=0 s ease=1000 last=0 lvl LC_ALL=C
  local -a past=() now_rows=() offs=() next=() rows=()
  h=$(( bottom - top + 1 ))
  if [ "$TUI_PART" = 0 ] || ! tui_wrap "$TUI_PART"; then   # nothing to show yet
    printf '%s' "$R" >&2
    for (( r = top; r <= bottom; r++ )); do printf '\033[%d;1H\033[2K' "$r" >&2; done
    local msg="${TUI_STATE:-Getting ready}"
    golden "$h"
    printf '\033[%d;%dH%s%s%s %s%s%s' $(( top + REPLY )) $(( (TW - ${#msg} - 2) / 2 )) \
      "$ACC" "${TUI_SYM:-·}" "$R" "$D" "$msg" "$R" >&2
    return 0
  fi
  mapfile -t now_rows <<< "${TUI_WRAP[$TUI_PART]%$'\n'}"
  read -ra offs <<< "${TUI_OFFS[$TUI_PART]}"
  for (( j = ${#tui_hist[@]} - 1; j >= 0 && ${#past[@]} < h; j-- )); do
    tui_wrap "${tui_hist[j]}" || continue
    mapfile -t rows <<< "${TUI_WRAP[${tui_hist[j]}]%$'\n'}"
    past=("${rows[@]}" "${past[@]}")
    [ "$j" = $(( ${#tui_hist[@]} - 1 )) ] && last=${#rows[@]}   # the part just heard
  done
  for (( k = TUI_PART + 1; ${#next[@]} < h; k++ )); do
    tui_wrap "$k" || break
    mapfile -t rows <<< "${TUI_WRAP[$k]%$'\n'}"
    next+=("${rows[@]}")
  done
  # This part starts on the golden line, φ⁻² of the way down; the lit word always stays on screen.
  if [ "$TUI_A" -gt 0 ]; then
    while [ $(( hl + 1 )) -lt "${#offs[@]}" ] && [ "${offs[hl + 1]}" -le "$TUI_A" ]; do hl=$(( hl + 1 )); done
  fi
  golden "$h"; a=$(( top + REPLY ))
  [ $(( a + hl )) -le $(( bottom - 2 )) ] || a=$(( bottom - 2 - hl ))
  n=${#now_rows[@]}
  # Focus: the fade takes a golden step every h·φ⁻⁵ rows, whatever the screen's height.
  s=$(( h * 90 )); [ "$s" -ge 1200 ] || s=1200
  if [ "$TUI_FOCUS" = 1 ] && [ "$TUI_EASE_AT" -gt 0 ]; then
    now_ms; ease=$(( (NOW - TUI_EASE_AT) * 1000 / TUI_EASE_MS ))
    if [ "$ease" -ge 1000 ]; then ease=1000 TUI_EASE_AT=0
    else ease=$(( ease * ease * (3000 - 2 * ease) / 1000000 )); fi   # smoothstep: slow, quick, slow
  fi
  printf '%s%s' "$SYNC_ON" "$R" >&2
  for (( r = top; r <= bottom; r++ )); do
    idx=$(( r - a ))
    printf '\033[%d;1H\033[2K\033[%dG' "$r" $(( LM + 1 )) >&2
    if [ "$idx" -lt 0 ]; then
      j=$(( ${#past[@]} + idx ))
      [ "$j" -ge 0 ] || continue
      if [ "$TUI_FOCUS" = 1 ]; then                # heard: from ½, fading upwards
        tui_far 500 $(( -idx - 1 )) "$s"; lvl=$REPLY
        [ "$j" -ge $(( ${#past[@]} - last )) ] && lvl=$(( lvl + (1000 - lvl) * (1000 - ease) / 1000 ))
        theme_tone "$lvl"; printf '%s%s%s' "$REPLY" "${past[j]}" "$R" >&2
      else printf '%s%s%s' "$PAST" "${past[j]}" "$R" >&2; fi
    elif [ "$idx" -lt "$n" ]; then
      printf '\033[%dG%s▎%s' $(( LM - 1 )) "$ACC" "$R" >&2   # marks the part being read
      printf '\033[%dG' $(( LM + 1 )) >&2
      tui_row "${now_rows[idx]}" "${offs[idx]}"
    else
      j=$(( idx - n ))
      [ "$j" -lt "${#next[@]}" ] || continue
      if [ "$TUI_FOCUS" = 1 ]; then                # still to come: from φ⁻¹, fading downwards
        tui_far 618 "$j" "$s"; theme_tone "$REPLY"; printf '%s%s%s' "$REPLY" "${next[j]}" "$R" >&2
      else printf '%s%s%s' "$D" "${next[j]}" "$R" >&2; fi
    fi
  done
  printf '%s' "$SYNC_OFF" >&2
}

tui_row() {      # tui_row TEXT OFFSET: one row of this part, the lit word in the soft highlight
  local row=$1 o=$2 len=${#1} a=$TUI_A b=$TUI_B x y LC_ALL=C
  if [ "$a" -lt 0 ] || [ $(( o + len )) -le "$a" ]; then
    printf '%s' "$row" >&2                                    # already spoken
  elif [ "$o" -ge "$b" ]; then
    tui_ahead "$row" $(( o - b ))                             # still to come
  else
    x=$(( a > o ? a - o : 0 )); y=$(( b - o < len ? b - o : len ))
    printf '%s%s%s%s' "${row:0:x}" "$HL" "${row:x:y-x}" "$R" >&2
    tui_ahead "${row:y}" $(( o + y - b ))
  fi
}

tui_ahead() {    # tui_ahead TEXT DIST: words still to come, DIST bytes after the lit word
  # In focus: the next word is halfway between full and φ⁻¹, and the words after
  # it settle to φ⁻¹ by a golden step every 12 characters (two or three words).
  local t=$1 d=$2 w i LC_ALL=C
  if [ "$TUI_FOCUS" != 1 ]; then printf '%s%s%s' "$D" "$t" "$R" >&2; return 0; fi
  while [ -n "$t" ]; do
    w=${t%% *}; [ "$w" = "$t" ] || w+=" "                     # a word and the space after it
    i=$(( d / 3 )); [ "$i" -le 40 ] || i=40
    theme_tone $(( 618 + 191 * THEME_DECAY[i] / 1000 )); printf '%s%s' "$REPLY" "$w" >&2
    d=$(( d + ${#w} )); t=${t:${#w}}
  done
  printf '%s' "$R" >&2
}

tui_progress() {
  local label bar bw filled ch=$TUI_CHAPTER room
  if [ "$TUI_PARTS" -gt 1 ]; then label="$TUI_PART/$TUI_PARTS"; else label=""; fi
  if [ -n "$ch" ]; then                       # the chapter, as long as the bar keeps some room
    room=$(( TW / 3 )); [ "${#ch}" -gt "$room" ] && ch="${ch:0:$(( room - 1 ))}…"
    label="$ch · $label"
  fi
  bw=$(( TW - 6 - ${#label} )); [ "$bw" -ge 4 ] || bw=4
  TUI_BAR=(3 "$bw")
  filled=0; [ "$TUI_PARTS" -gt 0 ] && filled=$(( TUI_PART * bw / TUI_PARTS ))
  printf -v bar '%*s' "$filled" ''; bar=${bar// /━}
  printf '%s\033[%d;1H\033[2K  %s%s%s' "$R" $(( TH - 3 )) "$ACC" "$bar" "$R" >&2
  printf -v bar '%*s' $(( bw - filled )) ''
  printf '%s%s%s  %s' "$LINE" "${bar// /─}" "$R" "$label" >&2
}

tui_button() {   # tui_button ROW KEY LABEL COMMAND: draw one at BX, remember where it is; BX advances by BGAP
  local text=" k $3 "                         # (the key glyph is one column, whatever its bytes)
  printf '\033[%d;%dH%s %s%s%s%s %s %s' "$1" "$BX" "$SURF" "$ACC2" "$2" "$R" "$SURF" "$3" "$R" >&2
  TUI_BTN+=("$1 $BX $(( BX + ${#text} - 1 )) $4")
  BX=$(( BX + ${#text} + BGAP ))
}

tui_buttons() {  # tui_buttons ROW "key|label|short|command"...: a row that always fits
  # Full labels two columns apart; if that's too wide, one apart; then short labels.
  local row=$1 spec k l s c w full=0 brief=0 n=$(( $# - 1 ))
  shift
  for spec in "$@"; do
    IFS='|' read -r k l s c <<< "$spec"
    full=$(( full + ${#l} + 4 )); brief=$(( brief + ${#s} + 4 ))
  done
  local short=0
  BGAP=2
  if [ $(( 2 + full + 2 * (n - 1) )) -gt "$TW" ]; then
    BGAP=1; [ $(( 2 + full + n - 1 )) -gt "$TW" ] && short=1
  fi
  BX=3
  for spec in "$@"; do
    IFS='|' read -r k l s c <<< "$spec"
    [ "$short" = 1 ] && l=$s
    tui_button "$row" "$k" "$l" "$c"
  done
}

tui_footer() {   # the buttons (keys and clicks), and what's happening on the right
  local state room play=Pause
  TUI_BTN=()
  printf '%s\033[%d;1H\033[2K\033[%d;1H\033[2K' "$R" $(( TH - 1 )) "$TH" >&2
  [ "$TUI_PAUSED" = 1 ] && play=Play
  tui_buttons "$TH" "␣|$play|$play|toggle" "←|Again|Back|again" "→|Next|Next|next" "q|Stop|Stop|quit"
  state="${TUI_SYM:+$TUI_SYM }$TUI_STATE${TUI_EXTRA:+ · $TUI_EXTRA}"
  room=$(( TW - BX - 2 + BGAP ))
  if [ "$room" -gt 6 ]; then
    [ "${#state}" -gt "$room" ] && state="${state:0:$(( room - 1 ))}…"
    printf '\033[%d;%dH%s%s%s' "$TH" $(( TW - ${#state} - 1 )) "$D" "$state" "$R" >&2
  fi
  local more=()                               # the second row: the rest
  [ "$TUI_HAS_CHAPTERS" = 1 ] && more+=("c|Chapters|Chap|chapters")
  more+=("−|Slower|Slow|slower" "+|Faster|Fast|faster" "t|Sleep|Sleep|sleep")
  tui_buttons $(( TH - 1 )) "${more[@]}"
}

tui_draw() {
  printf '%s%s\033[2J' "$SYNC_ON" "$R" >&2  # a full redraw clears what overlays left behind
  tui_header; tui_text; tui_progress; tui_footer
  printf '%s' "$SYNC_OFF" >&2
}

# ---------------------------------------------------------------- the reader's view API

tui_part() {     # tui_part I PARTS: a new part starts playing (dim until its words light)
  if [ "$TUI_PART" -gt 0 ] && [ "$TUI_PART" != "$1" ]; then
    tui_hist+=("$TUI_PART")
    [ "$TUI_FOCUS" = 1 ] && { now_ms; TUI_EASE_AT=$NOW; }    # it eases out of focus
  fi
  [ "${#tui_hist[@]}" -le 40 ] || tui_hist=("${tui_hist[@]: -40}")
  TUI_PART=$1 TUI_PARTS=${2:-$TUI_PARTS} TUI_A=0 TUI_B=0
  tui_draw
}
tui_light() { TUI_A=$1 TUI_B=$2; tui_text; }
tui_anim() { [ "$TUI_EASE_AT" -gt 0 ] && tui_text; return 0; }   # each tick while the part just heard eases out
tui_state() {    # tui_state TEXT EXTRA SYMBOL PAUSED: only the footer is redrawn
  [ "$1|$2|$3|${4:-0}" = "$TUI_STATE|$TUI_EXTRA|$TUI_SYM|$TUI_PAUSED" ] && return 0
  TUI_STATE=$1 TUI_EXTRA=$2 TUI_SYM=$3 TUI_PAUSED=${4:-0}
  if [ "$TUI_PART" = 0 ]; then tui_text; fi
  tui_footer
}

tui_click() {    # tui_click REPORT: a left click or tap on a button or the progress bar sets CMD
  local b x y spec row x0 x1 cmd
  [[ "$1" =~ ^$'\033'\[\<([0-9]+)\;([0-9]+)\;([0-9]+)M$ ]] || return 0
  b=${BASH_REMATCH[1]} x=${BASH_REMATCH[2]} y=${BASH_REMATCH[3]}
  [ "$b" = 0 ] || return 0
  for spec in "${TUI_BTN[@]}"; do
    read -r row x0 x1 cmd <<< "$spec"
    if [ "$y" = "$row" ] && [ "$x" -ge "$x0" ] && [ "$x" -le "$x1" ]; then CMD=$cmd; return 0; fi
  done
  # the progress bar: jump to that point (by part)
  if [ "$y" = $(( TH - 3 )) ] && [ "$TUI_PARTS" -gt 1 ] && [ "$x" -ge "${TUI_BAR[0]}" ] && [ "$x" -lt $(( TUI_BAR[0] + TUI_BAR[1] )) ]; then
    CMD="seek:$(( 1 + (x - TUI_BAR[0]) * TUI_PARTS / TUI_BAR[1] ))"
  fi
}

tui_chapters() { # tui_chapters CURRENT: pick a chapter over the reading area; CHOSEN = its index or ""
  local sel=${1:-0} top=0 i y view w x0 key title
  CHOSEN=""
  [ "$sel" -ge 0 ] || sel=0
  local -A rowsel=()
  while :; do
    tui_size
    w=$(( CW < 60 ? CW : 60 )); x0=$(( (TW - w) / 2 )); view=$(( TH - 10 ))
    [ "${#CH_TITLE[@]}" -lt "$view" ] && view=${#CH_TITLE[@]}          # as tall as it needs to be
    [ "$sel" -lt "$top" ] && top=$sel
    [ "$sel" -ge $(( top + view )) ] && top=$(( sel - view + 1 ))
    printf '%s' "$SYNC_ON" >&2
    printf '\033[3;%dH%s%*s\033[3;%dH %sChapters%s%s' "$x0" "$SURF" "$w" "" "$x0" "$B" "$R" "$SURF" >&2
    rowsel=()
    for (( i = top, y = 4; y < 4 + view; i++, y++ )); do
      printf '\033[%d;%dH%s%*s' "$y" "$x0" "$SURF" "$w" "" >&2
      [ "$i" -lt "${#CH_TITLE[@]}" ] || continue
      title=${CH_TITLE[i]}; [ "${#title}" -gt $(( w - 12 )) ] && title="${title:0:$(( w - 13 ))}…"
      if [ "$i" = "$sel" ]; then
        printf '\033[%d;%dH%s%*s\033[%d;%dH%s%s▎%s%s %-*s %spart %s%s' "$y" "$x0" "$PSEL" "$w" "" "$y" "$x0" \
          "$PSEL" "$ACC" "$R" "$PSEL" $(( w - 12 )) "$title" "$D" "${CH_START[i]}" "$R" >&2
      else
        printf '\033[%d;%dH%s  %-*s %spart %s%s' "$y" "$x0" "$SURF" $(( w - 12 )) "$title" "$D" "${CH_START[i]}" "$R" >&2
      fi
      rowsel[$y]=$i
    done
    printf '\033[%d;%dH%s%*s\033[%d;%dH %s↑↓ Enter jump · Esc close%s' $(( 4 + view )) "$x0" "$SURF" "$w" "" $(( 4 + view )) "$x0" "$D" "$R" >&2
    printf '%s' "$SYNC_OFF" >&2
    KEY=""; tick 0.5; [ -n "$KEY" ] || continue
    case "$KEY" in
      $'\033[A'|$'\033OA'|k|$'\033[<64;'*M) [ "$sel" -gt 0 ] && sel=$(( sel - 1 )) ;;
      $'\033[B'|$'\033OB'|j|$'\033[<65;'*M) [ "$sel" -lt $(( ${#CH_TITLE[@]} - 1 )) ] && sel=$(( sel + 1 )) ;;
      $'\n'|$'\r') CHOSEN=$sel; break ;;
      $'\033[<0;'*M) [[ "$KEY" =~ \;([0-9]+)M$ ]] && i=${rowsel[${BASH_REMATCH[1]}]:-} && [ -n "$i" ] && { CHOSEN=$i; break; } ;;
      $'\033'|q|c) break ;;
    esac
  done
  tui_draw
}

# ---------------------------------------------------------------- the home screen

tui_prompt() {   # tui_prompt LABEL [DEFAULT]: ask on the bottom line (with Tab completion)
  local ans=""
  printf '%s%s\033[%d;1H\033[2K\033[%d;1H\033[2K' "$SYNC_OFF" "$R" $(( TH - 1 )) "$TH" >&2
  tui_cooked
  printf '\033[%d;3H' "$TH" >&2
  IFS= read -e -r -p "$1 " -i "${2:-}" ans </dev/tty 2>&1 || ans=""
  tui_raw
  ANSWER=$ans
}

tui_home() {     # runs until something is chosen; prints the chosen kokoro arguments
  local sel=0 marks=() key r i m title part parts pct bw bar row y h free n
  local -A rowact=()
  tui_enter
  mapfile -t marks < <(ls -t "$BOOKS"/*.mark 2>/dev/null | head -n 6)
  HOME_CHOICE=()
  while IFS= read -rsn1 -t 0.05 -u "$KBD" _; do :; done   # drop keys typed before it opened
  local api=0
  [ "$(cat "${CONF%/*}/termux-api" 2>/dev/null)" = yes ] && api=1
  while :; do
    tui_size; rowact=()
    printf '%s%s\033[2J' "$SYNC_ON" "$R" >&2
    # The content as one block, its free space split 1 : φ above and below:
    # the optical centre, where a block looks centred rather than low.
    n=${#marks[@]}; [ "$n" -gt 0 ] || n=1
    if [ "$TH" -ge 22 ]; then h=6; else h=2; fi
    h=$(( h + 1 + n + 1 + 1 + 6 ))
    free=$(( TH - 2 - h )); [ "$free" -gt 0 ] || free=0
    golden "$free"; y=$(( 1 + REPLY ))
    if [ "$TH" -ge 22 ]; then                     # the logo, when there's room for it
      local l=0 line
      theme_grad 3 2>/dev/null || GRAD=("$ACC" "$ACC" "$ACC2")
      for line in '░█░█░█▀█░█░█░█▀█░█▄█░█░█░█░█' '░█▀▄░█░█░█▀▄░█░█░█░█░█░█░▄▀▄' '░▀░▀░▀▀▀░▀░▀░▀▀▀░▀░▀░▀▀▀░▀░▀'; do
        line="${line//░/$GHOST░${GRAD[l]}}"         # the ░ texture recedes, the letters stand out
        printf '\033[%d;%dH%s%s%s' "$y" $(( (TW - 28) / 2 )) "${GRAD[l]}" "$line" "$R" >&2
        y=$(( y + 1 )); l=$(( l + 1 ))
      done
      printf '\033[%d;%dH%sKokoro text-to-speech%s' $(( y + 1 )) $(( (TW - 21) / 2 )) "$D" "$R" >&2
      y=$(( y + 3 ))
    else
      printf '\033[%d;%dH%skokomux%s  %sKokoro text-to-speech%s' "$y" "$LM" "$ACC" "$R" "$D" "$R" >&2
      y=$(( y + 2 ))
    fi
    local x0=$LM
    printf '\033[%d;%dH%sContinue listening%s' "$y" "$x0" "$B" "$R" >&2; y=$(( y + 1 ))
    if [ "${#marks[@]}" = 0 ]; then
      r="Nothing in progress yet. Long texts and books are remembered here."
      [ "${#r}" -gt "$CW" ] && r="Nothing in progress yet."
      printf '\033[%d;%dH%s%s%s' "$y" "$x0" "$D" "$r" "$R" >&2
      y=$(( y + 1 ))
    fi
    for i in "${!marks[@]}"; do
      m=${marks[i]}; title="$(mark_get "$m" title)"; part="$(mark_get "$m" part)"; parts="$(mark_get "$m" parts)"
      [ "${parts:-0}" -gt 0 ] 2>/dev/null || parts=1
      pct=$(( 100 * (part - 1) / parts )); bw=12
      printf -v bar '%*s' $(( pct * bw / 100 )) ''; bar="$ACC${bar// /━}$R"
      local rest; printf -v rest '%*s' $(( bw - pct * bw / 100 )) ''; bar+="$LINE${rest// /─}$R"
      local room=$(( CW - 23 )); [ "${#title}" -gt "$room" ] && title="${title:0:$(( room - 1 ))}…"
      local mk=" "; row=""
      if [ "$i" = "$sel" ]; then row=$SEL; mk="$ACC▎"; fi
      bar="${bar//$R/$R$row}"            # keep the selected row's background across the bar
      printf '\033[%d;%dH%s%s%s%s%d%s  %-*s %s %s%3d%%%s %s' "$y" "$x0" "$row" "$mk" "$R$row" \
        "$ACC2" $(( i + 1 )) "$R$row" "$room" "$title" "$bar$row" "$D" "$pct" "$R$row" "$R" >&2
      rowact[$y]="mark:$i"; y=$(( y + 1 ))
    done
    y=$(( y + 1 ))
    local clip_note=""; [ "$api" = 1 ] || clip_note="  ${D}needs the Termux:API app${R}"
    printf '\033[%d;%dH%sStart something new%s' "$y" "$x0" "$B" "$R" >&2; y=$(( y + 1 ))
    for spec in "c|Read the clipboard$clip_note|clip" "f|Open a file  ${D}text, EPUB or PDF${R}|file" \
                "u|Read a web page|url" "t|Type something to hear|text" \
                "v|Voice  ${D}$KOKORO_VOICE · ${KOKORO_SPEED}×${R}|voice" "s|Settings  ${D}speed, colours, highlighting…${R}|settings"; do
      IFS='|' read -r key r cmd <<< "$spec"
      printf '\033[%d;%dH %s%s%s  %s' "$y" "$x0" "$ACC2" "$key" "$R" "$r" >&2
      rowact[$y]=$cmd; y=$(( y + 1 ))
    done
    if [ "$TW" -ge 64 ]; then r="↑↓ choose · Enter play · click or tap anything · q quit"
    else r="↑↓ · Enter play · tap · q quit"; fi
    printf '\033[%d;%dH%s%s%s' "$TH" "$LM" "$D" "$r" "$R" >&2
    printf '%s' "$SYNC_OFF" >&2

    KEY=""; tick 1; [ -n "$KEY" ] || continue
    cmd=""
    case "$KEY" in
      [1-6]) [ "$(( KEY - 1 ))" -lt "${#marks[@]}" ] && cmd="mark:$(( KEY - 1 ))" ;;
      $'\n'|$'\r') [ "${#marks[@]}" -gt 0 ] && cmd="mark:$sel" ;;
      $'\033[A'|$'\033OA'|k|$'\033[<64;'*M) [ "$sel" -gt 0 ] && sel=$(( sel - 1 )) ;;   # also the mouse wheel
      $'\033[B'|$'\033OB'|j|$'\033[<65;'*M) [ "$sel" -lt $(( ${#marks[@]} - 1 )) ] && sel=$(( sel + 1 )) ;;
      c) cmd="clip" ;; f) cmd="file" ;; u) cmd="url" ;; t) cmd="text" ;; v) cmd="voice" ;; s|o|,) cmd="settings" ;;
      q|Q|$'\033') break ;;
      $'\033[<0;'*M) [[ "$KEY" =~ \;([0-9]+)M$ ]] && cmd="${rowact[${BASH_REMATCH[1]}]:-}" ;;
    esac
    case "$cmd" in
      mark:*)  HOME_CHOICE=(--continue "${marks[${cmd#mark:}]}"); break ;;
      clip)    HOME_CHOICE=(-p -c); break ;;
      file)    tui_prompt "File to read:"; [ -n "$ANSWER" ] && { HOME_CHOICE=(-p -f "${ANSWER/#\~/$HOME}"); break; } ;;
      url)     tui_prompt "Web address:" "https://"; [ "${ANSWER:-https://}" != "https://" ] && { HOME_CHOICE=(-p -u "$ANSWER"); break; } ;;
      text)    tui_prompt "Say:"; [ -n "$ANSWER" ] && { HOME_CHOICE=(-p "$ANSWER"); break; } ;;
      voice)    tui_voices ;;
      settings) tui_settings ;;
    esac
  done
  tui_close
}


# ---------------------------------------------------------------- settings

# Kokoro's voices (model v1.0), by language. The first letter of a voice is its
# language and the second its gender, e.g. bf_emma is British English, female.
TUI_VOICES=(
  af_alloy af_aoede af_bella af_heart af_jessica af_kore af_nicole af_nova af_river af_sarah af_sky
  am_adam am_echo am_eric am_fenrir am_liam am_michael am_onyx am_puck am_santa
  bf_alice bf_emma bf_isabella bf_lily bm_daniel bm_fable bm_george bm_lewis
  ef_dora em_alex em_santa ff_siwis hf_alpha hf_beta hm_omega hm_psi if_sara im_nicola
  jf_alpha jf_gongitsune jf_nezumi jf_tebukuro jm_kumo pf_dora pm_alex pm_santa
  zf_xiaobei zf_xiaoni zf_xiaoxiao zf_xiaoyi zm_yunjian zm_yunxi zm_yunxia zm_yunyang
)
TUI_LANGS=(en-us en-gb es fr-fr it pt-br hi ja cmn)
SET_NARROW=0
declare -A TUI_LANG_NAME=([en-us]="American English" [en-gb]="British English" [es]="Spanish"
  [fr-fr]="French" [it]="Italian" [pt-br]="Brazilian Portuguese" [hi]="Hindi"
  [ja]="Japanese" [cmn]="Mandarin Chinese")
declare -A TUI_VOICE_LANG=([a]=en-us [b]=en-gb [e]=es [f]=fr-fr [h]=hi [i]=it [j]=ja [p]=pt-br [z]=cmn)
declare -A TUI_SAMPLE=(
  [en-us]="Hello! This is how I sound when I read to you." [en-gb]="Hello! This is how I sound when I read to you."
  [es]="¡Hola! Así sueno cuando leo para ti." [fr-fr]="Bonjour ! Voici ma voix quand je lis pour vous."
  [it]="Ciao! Ecco come suono quando leggo per te." [pt-br]="Olá! É assim que eu soo lendo para você."
  [hi]="नमस्ते! मैं ऐसे पढ़कर सुनाती हूँ।" [ja]="こんにちは。私はこんな声で読みます。" [cmn]="你好！我就是这样为你朗读的。")

voice_desc() {   # voice_desc VOICE: "British English, female"
  local v=$1 g="female"
  [ "${v:1:1}" = m ] && g="male"
  printf '%s, %s' "${TUI_LANG_NAME[${TUI_VOICE_LANG[${v:0:1}]:-}]:-Other}" "$g"
}

voice_sample() { # voice_sample VOICE: hear a voice say a line in its own language
  local v=$1 lang=${TUI_VOICE_LANG[${1:0:1}]:-en-us}
  KOKORO_TUI=0 KOKORO_WORDS=0 KOKORO_NOTIFY=0 \
    "$0" -p -v "$v" -s "$KOKORO_SPEED" -l "$lang" "${TUI_SAMPLE[$lang]}" </dev/null >/dev/null 2>&1 &
  disown 2>/dev/null || true
  SAVED_AT=0; SETTINGS_NOTE="Playing a sample of $v…"
}

tui_save() {     # tui_save NAME VALUE: keep a setting (and use it straight away)
  mkdir -p "${CONF%/*}"; [ -f "$CONF" ] || : > "$CONF"
  if grep -q "^$1=" "$CONF"; then sed -i "s|^$1=[^ #]*|$1=$2|" "$CONF"; else echo "$1=$2" >> "$CONF"; fi
  printf -v "$1" '%s' "$2"
  now_ms; SAVED_AT=$NOW
}

# Each setting: id | label | what it does (shown under the list for the one selected).
TUI_SETTINGS=(
  "#Voice and speech"
  "voice|Voice|The voice that reads to you. Enter opens the list of 54 voices; Space plays a sample."
  "speed|Speed|How fast it reads, from 0.5× to 2×. Use ← and → to change it in steps of 0.1."
  "lang|Language|How words are pronounced. Choosing a voice sets this to match; change it to read another language."
  "out|Save files as|The file name used when you save without -o. The ending picks the format: .wav .mp3 .ogg or .flac."
  "#Reading"
  "KOKORO_TUI|Full-screen reader|Open the full-screen reader when you listen on a terminal. Off: show the text below the command instead."
  "theme|Colours|Auto follows your terminal's own colours. Dark or Light uses that palette on these screens, with its own background if your terminal is the other kind."
  "KOKORO_FOCUS|Focus effect|Text fades the further it is from the part being read, like a camera's depth of field, and the words just ahead of the voice are in sharpest focus."
  "KOKORO_WORDS|Light up words|Highlight each word as it's spoken, using the timings Kokoro reports as it speaks, in every language."
  "offset|Highlight timing|Moves the highlight relative to the voice. Raise it if the highlight runs ahead, lower it if it lags."
  "#While listening"
  "KOKORO_NOTIFY|Notification controls|Show a notification with pause, next and again buttons, also on the lock screen. Needs the Termux:API app."
  "#Background server"
  "KOKORO_AUTOSERVE|Keep Kokoro loaded|Keep the voice model in memory between uses, so reading starts at once. Off: each use loads it (slower, but frees memory)."
  "idle|Unload after|How long Kokoro stays loaded when you're not using it. Takes effect the next time it starts."
  "port|Port|The local port the background server listens on. Only change it if another app uses 8880."
  "#"
  "reset|Reset to defaults|Put every setting back as it was when kokomux was installed."
  "edit|Edit the settings file|Open ~/.config/kokoro/config in your editor."
)

setting_value() {  # what the row shows on the right for setting $1
  local id=$1 t f e bar
  case "$id" in
    voice) printf '%s%s%s' "$ACC2" "$KOKORO_VOICE" "$R"
           [ "$SET_NARROW" = 1 ] || printf '  %s%s%s' "$D" "$(voice_desc "$KOKORO_VOICE")" "$R" ;;
    speed)
      local w=15; [ "$SET_NARROW" = 1 ] && w=9
      t=$(speed_tenths); f=$(( (t - 5) * w / 15 )); [ "$f" -lt 0 ] && f=0
      printf -v bar '%*s' "$f" ''; bar="$ACC${bar// /━}●$R"
      printf -v e '%*s' $(( w - f )) ''; bar+="$D${e// /─}$R"
      printf '%s  %s×' "$bar" "$KOKORO_SPEED" ;;
    lang)  printf '‹ %s%s%s ›' "$ACC2" "$KOKORO_LANG" "$R"
           [ "$SET_NARROW" = 1 ] || printf '  %s%s%s' "$D" "${TUI_LANG_NAME[$KOKORO_LANG]:-}" "$R" ;;
    out)   printf '%s' "$KOKORO_OUT" ;;
    theme) theme_name 2>/dev/null || REPLY=Auto
           printf '‹ %s%s%s ›' "$ACC2" "$REPLY" "$R"
           if [ "$SET_NARROW" = 0 ]; then
             if [ "${KOKORO_THEME:-auto}" = auto ]; then printf '  %s%s terminal%s' "$D" "${THEME_MODE^}" "$R"
             elif [ "$THEME_PAINT" = 1 ]; then printf '  %sits own background%s' "$D" "$R"; fi
           fi ;;
    offset) printf '‹ %+d ms ›' "$KOKORO_WORDS_OFFSET_MS" ;;
    idle)  if [ "$KOKORO_IDLE_MINUTES" = 0 ]; then printf '‹ never ›'; else printf '‹ %s min ›' "$KOKORO_IDLE_MINUTES"; fi ;;
    port)  printf '%s' "$KOKORO_PORT" ;;
    KOKORO_*) if [ "${!id}" = 1 ]; then printf '%s● On%s' "$GRN" "$R"; else printf '%s○ Off%s' "$D" "$R"; fi ;;
  esac
}

speed_tenths() {   # "1.25" -> 12 (tenths, for the slider and the arrow keys)
  local s=${KOKORO_SPEED:-1.0} d
  [[ "$s" == *.* ]] || s="$s.0"
  d=${s#*.}; d=${d:0:1}
  echo $(( 10#${s%%.*} * 10 + 10#${d:-0} ))
}

setting_change() { # setting_change ID STEP: ←/→ (-1/+1); 0 means Enter, a click or Space
  local id=$1 step=$2 t i
  case "$id" in
    voice) [ "$step" = 0 ] && tui_voices ;;
    speed)
      if [ "$step" = 0 ]; then tui_prompt "Speed, 0.5 to 2.0:" "$KOKORO_SPEED"
        [[ "$ANSWER" =~ ^(0\.[5-9][0-9]?|1(\.[0-9]{1,2})?|2(\.0+)?)$ ]] && tui_save KOKORO_SPEED "$ANSWER"
      else t=$(( $(speed_tenths) + step )); [ "$t" -ge 5 ] && [ "$t" -le 20 ] && tui_save KOKORO_SPEED "$(( t / 10 )).$(( t % 10 ))"; fi ;;
    lang)
      for i in "${!TUI_LANGS[@]}"; do [ "${TUI_LANGS[i]}" = "$KOKORO_LANG" ] && break; done
      [ "$step" = 0 ] && step=1
      tui_save KOKORO_LANG "${TUI_LANGS[$(( (i + step + ${#TUI_LANGS[@]}) % ${#TUI_LANGS[@]} ))]}" ;;
    theme)                                    # auto → dark → light, shown at once
      local themes=(auto dark light)
      for i in "${!themes[@]}"; do [ "${themes[i]}" = "${KOKORO_THEME:-auto}" ] && break; done
      [ "$step" = 0 ] && step=1
      tui_save KOKORO_THEME "${themes[$(( (i + step + 3) % 3 ))]}"
      tui_theme ;;
    out)
      tui_prompt "Save files as:" "$KOKORO_OUT"
      if [[ "$ANSWER" =~ ^[^/[:space:]]+\.(wav|mp3|ogg|flac)$ ]]; then tui_save KOKORO_OUT "$ANSWER"
      elif [ -n "$ANSWER" ]; then SETTINGS_NOTE="A file name ending in .wav, .mp3, .ogg or .flac, please."; fi ;;
    offset) t=$(( KOKORO_WORDS_OFFSET_MS + (step == 0 ? 20 : step * 20) ))
            [ "$t" -ge -500 ] && [ "$t" -le 1000 ] && tui_save KOKORO_WORDS_OFFSET_MS "$t" ;;
    idle)
      local opts=(5 10 15 30 60 120 0)
      for i in "${!opts[@]}"; do [ "${opts[i]}" = "$KOKORO_IDLE_MINUTES" ] && break; done
      [ "$step" = 0 ] && step=1
      tui_save KOKORO_IDLE_MINUTES "${opts[$(( (i + step + ${#opts[@]}) % ${#opts[@]} ))]}" ;;
    port)
      tui_prompt "Port (1024 to 65535):" "$KOKORO_PORT"
      if [[ "$ANSWER" =~ ^[0-9]+$ ]] && [ "$ANSWER" -ge 1024 ] && [ "$ANSWER" -le 65535 ]; then tui_save KOKORO_PORT "$ANSWER"
      elif [ -n "$ANSWER" ]; then SETTINGS_NOTE="A port between 1024 and 65535, please."; fi ;;
    KOKORO_*) tui_save "$id" $(( 1 - ${!id} )) ;;
    reset)
      [ "$step" = 0 ] || return 0
      tui_prompt "Reset every setting to its default? (y/N)"
      if [[ "$ANSWER" =~ ^[Yy] ]]; then
        local kv; for kv in "${KOKORO_DEFAULTS[@]}"; do tui_save "${kv%%=*}" "${kv#*=}"; done
        SETTINGS_NOTE="Everything is back to its default."
      fi ;;
    edit)
      [ "$step" = 0 ] || return 0
      tui_cooked; printf '\033[0m\033[?1049l' >&2
      "${EDITOR:-nano}" "$CONF" </dev/tty >/dev/tty 2>&1 || true
      # shellcheck source=/dev/null
      [ -f "$CONF" ] && . "$CONF"
      printf '\033[?1049h' >&2; tui_raw ;;
  esac
}

tui_settings() { # the settings menu; works on its own or from the home screen
  local own=0 sel=0 top=0 key rows=() i n n_this=0 y=0 r id label desc val first=4 last view lw=26
  local -A rowsel=()
  SAVED_AT=0; SETTINGS_NOTE=""
  if [ "$TUI" != 1 ]; then own=1; tui_enter; fi
  local items=(); for r in "${TUI_SETTINGS[@]}"; do [ "${r:0:1}" = "#" ] || items+=("${r%%|*}"); done
  while :; do
    tui_size
    # labels and values share the width φ⁻² : φ⁻¹, with room for the longest label
    SET_NARROW=0; [ "$CW" -lt 64 ] && SET_NARROW=1
    golden "$CW"; lw=$(( REPLY > 25 ? REPLY : 25 ))
    # what the selected setting does, wrapped: the bottom of the screen is laid out around it
    for r in "${TUI_SETTINGS[@]}"; do [ "${r%%|*}" = "${items[sel]}" ] && desc=${r##*|}; done
    [ -n "$SETTINGS_NOTE" ] && desc="$SETTINGS_NOTE"
    local -a dl=(); mapfile -t dl < <(printf '%s\n' "$desc" | fold -s -w "$CW")
    last=$(( TH - ${#dl[@]} - 3 )); view=$(( last - first + 1 ))
    # one entry per screen line: a section heading, a gap, or a setting
    rows=(); for r in "${TUI_SETTINGS[@]}"; do
      if [ "$r" = "#" ]; then rows+=("")                        # an unnamed section: just a gap
      elif [ "${r:0:1}" = "#" ]; then [ "${#rows[@]}" -gt 0 ] && rows+=(""); rows+=("$r"); else rows+=("$r"); fi
    done
    n=0; for i in "${!rows[@]}"; do [[ "${rows[i]}" == *"|"* ]] && { [ "$n" = "$sel" ] && y=$i; n=$(( n + 1 )); }; done
    [ "$y" -lt "$top" ] && top=$(( y > 0 && ${rows[y-1]:0:1} == "#" ? y - 1 : y ))
    [ "$y" -ge $(( top + view )) ] && top=$(( y - view + 1 ))
    [ "$y" -le 1 ] && top=0
    printf '%s%s\033[2J' "$SYNC_ON" "$R" >&2
    printf '\033[1;1H  %skokomux%s  %sSettings%s' "$ACC" "$R" "$B" "$R" >&2
    now_ms
    if [ "$SAVED_AT" -gt 0 ] && [ $(( NOW - SAVED_AT )) -lt 1500 ]; then
      printf '\033[1;%dH%s✓ Saved%s' $(( TW - 9 )) "$GRN" "$R" >&2
    fi
    printf '\033[2;1H%s%s%s' "$LINE" "$(rule "$TW")" "$R" >&2
    rowsel=(); n=0
    for i in "${!rows[@]}"; do
      r=${rows[i]}
      [[ "$r" == *"|"* ]] && n_this=$n
      if [[ "$r" == *"|"* ]]; then n=$(( n + 1 )); fi
      [ "$i" -lt "$top" ] || [ "$i" -ge $(( top + view )) ] && continue
      y=$(( first + i - top ))
      if [ "${r:0:1}" = "#" ]; then
        [ -n "${r:1}" ] && printf '\033[%d;%dH%s%s%s' "$y" "$LM" "$B" "${r:1}" "$R" >&2
      elif [ -n "$r" ]; then
        IFS='|' read -r id label desc <<< "$r"
        val="$(setting_value "$id")"
        if [ "$n_this" = "$sel" ]; then
          printf '\033[%d;%dH%s%*s\033[%d;%dH%s▎%s%s %s' "$y" "$LM" "$SEL" "$CW" "" "$y" "$LM" "$ACC" "$R" "$SEL" "$label" >&2
          printf '\033[%d;%dH%s%s%s' "$y" $(( LM + lw )) "$SEL" "${val//$R/$R$SEL}" "$R" >&2
        else
          printf '\033[%d;%dH  %s\033[%d;%dH%s' "$y" "$LM" "$label" "$y" $(( LM + lw )) "$val" >&2
        fi
        rowsel[$y]=$n_this
      fi
    done
    # "more" markers on their own rows, above and below the list
    [ "$top" -gt 0 ] && printf '\033[%d;%dH%s▲ more%s' $(( first - 1 )) $(( LM + CW - 6 )) "$D" "$R" >&2
    [ $(( top + view )) -lt "${#rows[@]}" ] && printf '\033[%d;%dH%s▼ more%s' $(( last + 1 )) $(( LM + CW - 6 )) "$D" "$R" >&2
    y=$(( TH - ${#dl[@]} - 1 ))
    printf '\033[%d;1H%s%s%s' "$y" "$LINE" "$(rule "$TW")" "$R" >&2
    for r in "${dl[@]}"; do y=$(( y + 1 )); printf '\033[%d;%dH%s' "$y" "$LM" "$r" >&2; done
    if [ "$TW" -ge 72 ]; then r="↑↓ choose · ←→ change · Enter edit · Space hear voice · q done"
    else r="↑↓ · ←→ change · Enter · q done"; fi
    printf '\033[%d;%dH%s%s%s' "$TH" "$LM" "$D" "$r" "$R" >&2
    printf '%s' "$SYNC_OFF" >&2

    KEY=""; tick 0.5; [ -n "$KEY" ] || continue
    SETTINGS_NOTE=""
    case "$KEY" in
      $'\033[A'|$'\033OA'|k|$'\033[<64;'*M) [ "$sel" -gt 0 ] && sel=$(( sel - 1 )) ;;
      $'\033[B'|$'\033OB'|j|$'\033[<65;'*M) [ "$sel" -lt $(( ${#items[@]} - 1 )) ] && sel=$(( sel + 1 )) ;;
      $'\033[D'|$'\033OD'|h|-) setting_change "${items[sel]}" -1 ;;
      $'\033[C'|$'\033OC'|l|+|=) setting_change "${items[sel]}" 1 ;;
      $'\n'|$'\r') setting_change "${items[sel]}" 0 ;;
      " ") if [ "${items[sel]}" = voice ]; then voice_sample "$KOKORO_VOICE"; else setting_change "${items[sel]}" 0; fi ;;
      $'\033[<0;'*M)
        [[ "$KEY" =~ \;([0-9]+)M$ ]] || continue
        r=${rowsel[${BASH_REMATCH[1]}]:-}
        [ -n "$r" ] || continue
        if [ "$r" = "$sel" ]; then setting_change "${items[sel]}" 0; else sel=$r; fi ;;   # a second click acts
      q|Q|$'\033') break ;;
    esac
  done
  if [ "$own" = 1 ]; then tui_close; fi
}

tui_voices() {   # the voice picker: type to filter, Space to hear, Enter to choose
  local filter="" sel=0 top=0 list=() v i y view lang prev g key
  local -A rowsel=()
  for i in "${!TUI_VOICES[@]}"; do [ "${TUI_VOICES[i]}" = "$KOKORO_VOICE" ] && sel=$i; done
  while :; do
    tui_size; view=$(( TH - 7 ))
    list=(); for v in "${TUI_VOICES[@]}"; do [[ -z "$filter" || "$v" == *"$filter"* ]] && list+=("$v"); done
    [ "$sel" -ge "${#list[@]}" ] && sel=$(( ${#list[@]} > 0 ? ${#list[@]} - 1 : 0 ))
    [ "$sel" -lt "$top" ] && top=$sel
    [ "$sel" -ge $(( top + view )) ] && top=$(( sel - view + 1 ))
    printf '%s%s\033[2J\033[1;1H  %skokomux%s  %sChoose a voice%s' "$SYNC_ON" "$R" "$ACC" "$R" "$B" "$R" >&2
    printf '\033[1;%dH%s%s%s' $(( TW - 20 )) "$D" "${#list[@]} of ${#TUI_VOICES[@]} voices" "$R" >&2
    printf '\033[2;1H%s%s%s' "$LINE" "$(rule "$TW")" "$R" >&2
    printf '\033[3;%dH%sFilter:%s %s%s▏' "$LM" "$D" "$R" "$filter" "$ACC" >&2; printf '%s' "$R" >&2
    rowsel=(); prev=""
    for (( i = top, y = 5; i < ${#list[@]} && y <= TH - 3; i++, y++ )); do
      v=${list[i]}; lang=${TUI_VOICE_LANG[${v:0:1}]:-}; g=female; [ "${v:1:1}" = m ] && g=male
      local mark=" "; [ "$v" = "$KOKORO_VOICE" ] && mark="$GRN✓$R"
      if [ "$i" = "$sel" ]; then
        printf '\033[%d;%dH%s%*s\033[%d;%dH%s▎%s%s %s%s %s%-16s%s %s%s · %s%s' \
          "$y" "$LM" "$SEL" "$CW" "" "$y" "$LM" "$ACC" "$R" "$SEL" "${mark//$R/$R$SEL}" "$SEL" "$ACC2" "$v" "$R$SEL" "$D" "${TUI_LANG_NAME[$lang]:-}" "$g" "$R" >&2
      else
        printf '\033[%d;%dH  %s %-16s %s%s · %s%s' "$y" "$LM" "$mark" "$v" "$D" "${TUI_LANG_NAME[$lang]:-}" "$g" "$R" >&2
      fi
      rowsel[$y]=$i
    done
    [ "${#list[@]}" = 0 ] && printf '\033[5;%dH%sNo voice matches "%s".%s' "$LM" "$D" "$filter" "$R" >&2
    printf '\033[%d;1H%s%s%s' $(( TH - 1 )) "$LINE" "$(rule "$TW")" "$R" >&2
    if [ "$TW" -ge 72 ]; then g="type to filter · ↑↓ choose · Space hear it · Enter use it · Esc back"
    else g="type · ↑↓ · Space hear · Enter use · Esc"; fi
    printf '\033[%d;%dH%s%s%s%s' "$TH" "$LM" "$D" "$g" "$R" "$SYNC_OFF" >&2

    KEY=""; tick 0.5; [ -n "$KEY" ] || continue
    case "$KEY" in
      $'\033[A'|$'\033OA'|$'\033[<64;'*M) [ "$sel" -gt 0 ] && sel=$(( sel - 1 )) ;;
      $'\033[B'|$'\033OB'|$'\033[<65;'*M) [ "$sel" -lt $(( ${#list[@]} - 1 )) ] && sel=$(( sel + 1 )) ;;
      $'\033[5~') sel=$(( sel > view ? sel - view : 0 )) ;;
      $'\033[6~') sel=$(( sel + view < ${#list[@]} ? sel + view : ${#list[@]} - 1 )) ;;
      " ") [ "${#list[@]}" -gt 0 ] && voice_sample "${list[sel]}" ;;
      $'\n'|$'\r')
        if [ "${#list[@]}" -gt 0 ]; then
          v=${list[sel]}; tui_save KOKORO_VOICE "$v"
          tui_save KOKORO_LANG "${TUI_VOICE_LANG[${v:0:1}]:-$KOKORO_LANG}"   # its own language
          return 0
        fi ;;
      $'\177'|$'\b') filter=${filter%?}; sel=0; top=0 ;;
      [a-z_]) filter+=$KEY; sel=0; top=0 ;;
      $'\033[<0;'*M)
        [[ "$KEY" =~ \;([0-9]+)M$ ]] || continue
        i=${rowsel[${BASH_REMATCH[1]}]:-}; [ -n "$i" ] || continue
        if [ "$i" = "$sel" ]; then v=${list[sel]}; tui_save KOKORO_VOICE "$v"
          tui_save KOKORO_LANG "${TUI_VOICE_LANG[${v:0:1}]:-$KOKORO_LANG}"; return 0
        else sel=$i; fi ;;
      $'\033'|q) return 0 ;;
    esac
  done
}
__KOKOMUX_TUI__

cat > "$WORK/player.sh" <<'__KOKOMUX_PLAYER__'
# kokomux: the player. Sourced by `kokoro` (bash) once a listening run is set up.
# shellcheck shell=bash disable=SC2034,SC2153,SC2154  # shares its globals with kokoro
#
#   play_line WAV IDX     plays one part: the inline view or the full-screen
#                         reader (via the view API below), word highlights,
#                         pause / next / again, bookmarks and the notification
#   listen_cmd CMD PART   chapters, speed, the sleep timer and seeking, which
#                         restart the reading at a part (jump_to)
#
# Expects from kokoro: the run's globals (tmpd, play, ui, show_words, voice,
# speed, lang, name, kind, source, text, book_id, start, i), the colours (HL),
# status/status_clear, tick, now_ms, write_job and run_engine.

# ------------------------------------------------------------ the line being heard
# Each line is printed wrapped to the screen while it plays, and a soft
# highlight moves from word to word. Times come from Kokoro itself via the engine
# (stream/NNNN.words: "confidence", then "start end a b" per word, with a/b the
# word's byte range in NNNN.txt). play-audio has no clock to read, so the time
# is how long it has been playing, less its start-up delay.
# A line taller than the screen is shown as a window of rows that follows the
# lit word, then printed in full when it ends.

# The soft highlight is HL, from the theme: the background moved φ⁻² towards the accent.
blk_lines=(); blk_offs=(); view_top=0; view_h=0
w_s=(); w_e=(); w_a=(); w_b=(); w_state=none
TERM_ROWS=24
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

# The view the player draws through: the full-screen reader or the inline block.
view_part() {    # view_part IDX BASE: a part starts playing
  local parts=1
  if [ "$ui" = tui ]; then
    [ -f "$tmpd/progress" ] && { read -r _ parts < "$tmpd/progress" || parts=1; }
    TUI_TITLE=${book_title:-$name}
    chapters_load; chapter_of "$1"
    TUI_CHAPTER=""; [ "$CH" -ge 0 ] && TUI_CHAPTER=${CH_TITLE[CH]}
    TUI_HAS_CHAPTERS=$(( ${#CH_START[@]} > 1 ))
    tui_part "$1" "$parts"
    [ "$w_state" = off ] && tui_light -1 -1
  elif [ "$show_words" = 1 ]; then
    block_load "$2.txt"; status_clear
    if [ "$w_state" = off ]; then block_draw -1 -1; else block_draw 0 0; fi   # dim until words light
  fi
  return 0
}
view_light() { if [ "$ui" = tui ]; then tui_light "$@"; elif [ "$show_words" = 1 ]; then block_redraw "$@"; fi; }
view_state() {   # view_state TEXT EXTRA [SYMBOL] [PAUSED]: what's happening (a spinner if no symbol)
  local sym=${3:-${SPIN[frame % 10]}}
  frame=$(( frame + 1 ))
  if [ "$ui" = tui ]; then tui_state "$1" "$2" "$sym" "${4:-0}"; else status "$1" "$2" "$sym"; fi
}
view_part_end() { if [ "$ui" = tui ]; then tui_light -1 -1; elif [ "$show_words" = 1 ]; then block_finish; fi; }
view_refresh() { [ "$ui" = tui ] && { tui_refresh; tui_anim; }; return 0; }

words_load() {   # read NNNN.words once it lands
  local f=$1 s e a b
  [ -f "$f" ] || return 1
  w_s=(); w_e=(); w_a=(); w_b=()
  { read -r _                                 # the "confidence" line
    while read -r s e a b; do w_s+=("$s"); w_e+=("$e"); w_a+=("$a"); w_b+=("$b"); done
  } < "$f"
  if [ "${#w_s[@]}" -gt 0 ]; then w_state=ready
  else w_state=none; view_light -1 -1; fi    # below the bar: the line plain, nothing lit
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

# Chapters (EPUBs): where each starts, read once the engine writes them.
CH_START=(); CH_TITLE=()
chapters_load() {
  [ "${#CH_START[@]}" -gt 0 ] || [ ! -f "$tmpd/chapters" ] && return 0
  local n t
  while IFS=$'\t' read -r n t; do CH_START+=("$n"); CH_TITLE+=("$t"); done < "$tmpd/chapters"
}
chapter_of() {   # chapter_of PART: CH = the index of the chapter PART is in (-1: none)
  local k; CH=-1
  for k in "${!CH_START[@]}"; do [ "${CH_START[k]}" -le "$1" ] && CH=$k; done
  return 0
}

# The sleep timer: off, a number of minutes, or the end of the chapter. When it's
# up, the part being read is finished and then it stops (keeping the bookmark).
SLEEP=off; SLEEP_AT=0
sleep_cycle() {
  case "$SLEEP" in
    off) SLEEP=15 ;; 15) SLEEP=30 ;; 30) SLEEP=60 ;;
    60) if [ "${#CH_START[@]}" -gt 1 ]; then SLEEP=chapter; else SLEEP=off; fi ;;
    *) SLEEP=off ;;
  esac
  now_ms
  case "$SLEEP" in off|chapter) SLEEP_AT=0 ;; *) SLEEP_AT=$(( NOW + SLEEP * 60000 )) ;; esac
}
sleep_label() {  # SLEEP_LABEL: what the timer shows ("" when off)
  SLEEP_LABEL=""
  case "$SLEEP" in
    off) ;;
    chapter) SLEEP_LABEL="☾ end of chapter" ;;
    *) now_ms; SLEEP_LABEL="☾ $(( (SLEEP_AT - NOW + 59999) / 60000 ))m" ;;
  esac
}
sleep_due() {    # sleep_due NEXT_PART: is it time to stop, before NEXT_PART starts?
  case "$SLEEP" in
    off) return 1 ;;
    chapter) chapter_of $(( $1 - 1 )); local was=$CH; chapter_of "$1"; [ "$CH" != "$was" ] ;;
    *) now_ms; [ "$NOW" -ge "$SLEEP_AT" ] ;;
  esac
}

# Jumping (a chapter, a click on the progress bar, a new speed) starts the
# reading again at a part: a fresh job folder, so the old job sees its folder
# go and stops, and the engine picks up from there.
JUMP=""
jump_to() {      # jump_to PART
  local old=$tmpd target=$1 f
  if [ -n "$play_pid" ]; then kill "$play_pid" 2>/dev/null || true; wait "$play_pid" 2>/dev/null || true; play_pid=""; fi
  if [ -n "$gen_pid" ]; then kill "$gen_pid" 2>/dev/null || true; wait "$gen_pid" 2>/dev/null || true; gen_pid=""; fi
  tmpd="$(mktemp -d "$PREFIX/tmp/kokoro.XXXXXX")"
  for f in in.txt title chapters; do [ -f "$old/$f" ] && cp "$old/$f" "$tmpd/"; done
  rm -rf "$old"
  start=$target; i=$target
  write_job
  run_engine > "$tmpd/engine.log" 2>&1 & gen_pid=$!
  if [ "$ui" = tui ]; then tui_hist=(); TUI_PART=0; TUI_WRAP=(); TUI_OFFS=(); TUI_INFO="$voice · ${speed}×"; tui_draw; fi
}

# Commands that change where or how it reads (from a key, a click or the menu).
# Sets JUMP to restart at a part; returns 1 if CMD isn't one of these.
listen_cmd() {   # listen_cmd CMD PART
  local cmd=$1 at=$2 t
  chapters_load
  case "$cmd" in
    faster|slower)
      t=$(printf '%s' "$speed" | awk '{ printf "%d", $1 * 10 + 0.5 }')
      if [ "$cmd" = faster ]; then t=$(( t + 1 )); else t=$(( t - 1 )); fi
      [ "$t" -ge 5 ] && [ "$t" -le 20 ] || return 0
      speed="$(( t / 10 )).$(( t % 10 ))"; JUMP=$at ;;              # this part again, at the new speed
    sleep) sleep_cycle ;;
    chapters)
      [ "${#CH_START[@]}" -gt 1 ] && [ "$ui" = tui ] || return 0
      chapter_of "$at"; tui_chapters "$CH"
      [ -n "$CHOSEN" ] && JUMP=${CH_START[CHOSEN]} ;;
    next_chapter) chapter_of "$at"; [ $(( CH + 1 )) -lt "${#CH_START[@]}" ] && JUMP=${CH_START[CH + 1]} ;;
    prev_chapter) chapter_of "$at"
                  if [ "$CH" -ge 0 ] && [ "$at" -gt "${CH_START[CH]}" ]; then JUMP=${CH_START[CH]}   # start of this one
                  elif [ "$CH" -gt 0 ]; then JUMP=${CH_START[CH - 1]}; fi ;;
    seek:*) JUMP=${cmd#seek:} ;;
    *) return 1 ;;
  esac
  return 0
}

play_line() {    # play_line WAV IDX: play one part; light its words; obey pause / next / again
  local wav=$1 i=$2 base="${1%.wav}" cur=-1 lit=-2 lit_now t t0 label last="" cmd paused=0 restart=1
  w_state=pending
  { [ -f "$tmpd/stream/words-off" ] || [ "$show_words" = 0 ]; } && w_state=off
  echo "$i" > "$tmpd/stream/playing"          # the engine and aligner work from the part being heard
  title_get; part_of "$i"
  bookmark_save "$i"; notify "${PART% · }"
  view_part "$i" "$base"
  in_line=1
  # Nothing in this loop starts a process except when something changes: it
  # runs ~30 times a second on a phone.
  while :; do
    if [ "$restart" = 1 ]; then               # (re)start this part from its beginning
      play-audio "$wav" >/dev/null 2>&1 & play_pid=$!
      now_ms; t0=$NOW; cur=-1; lit=-2; restart=0
      echo playing > "$PLAYER_STATE"
    fi
    cmd=""                                    # kokoro --pause / --next / --again, or a key or click
    if [ -f "$CONTROL" ]; then read -r cmd < "$CONTROL" || cmd=""; rm -f "$CONTROL"; fi
    if [ -z "$cmd" ] && [ -n "$KEY" ]; then key_cmd; cmd=$CMD; fi
    if [ -n "$cmd" ]; then
      case "$cmd" in
        toggle)
          if [ "$paused" = 1 ]; then paused=0; restart=1; notify "${PART% · }"   # carry on from the part's start
          else { kill "$play_pid" 2>/dev/null || true; }; wait "$play_pid" || true
               paused=1; last=""; echo paused > "$PLAYER_STATE"; notify "Paused · ${PART% · }"; fi
          continue ;;
        next)  { kill "$play_pid" 2>/dev/null || true; }; wait "$play_pid" || true; break ;;
        again) { kill "$play_pid" 2>/dev/null || true; }; wait "$play_pid" || true; restart=1; continue ;;
        quit)  exit 0 ;;                          # the bookmark is kept; cleanup says where
        *)     if listen_cmd "$cmd" "$i"; then
                 last=""
                 if [ -n "$JUMP" ]; then { kill "$play_pid" 2>/dev/null || true; }; wait "$play_pid" || true; break; fi
               fi ;;
      esac
    fi
    view_refresh
    if [ "$paused" = 1 ]; then
      if [ -z "$last" ]; then
        if [ "$ui" = tui ]; then view_state "Paused" "" "‖" 1; else view_state "Paused" "${PART}space to carry on" "‖"; fi
        last=paused
      fi
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
          if [ "$lit_now" -ge 0 ]; then view_light "${w_a[lit_now]}" "${w_b[lit_now]}"
          elif [ "$cur" -ge 0 ]; then view_light -1 -1      # past the last word
          else view_light 0 0; fi                            # before the first
          lit=$lit_now; last=""
        fi
      fi
    fi
    part_of "$i"; sleep_label
    if [ "$ui" = tui ]; then label="$SLEEP_LABEL"
    else label="${PART}${SLEEP_LABEL:+$SLEEP_LABEL · }␣ pause · → next · q stop"; fi
    if [ "$label|playing" != "$last" ]; then view_state "Playing" "$label" "▶"; last="$label|playing"; fi
    tick 0.03
  done
  wait "$play_pid" || true
  play_pid=""; in_line=0
  echo $(( i + 1 )) > "$tmpd/stream/playing"  # heard: the aligner can drop this part
  view_part_end
  rm -f "$wav"
}

__KOKOMUX_PLAYER__

cat > "$WORK/theme.sh" <<'__KOKOMUX_THEME__'
# kokomux: colours. Sourced by `kokoro` and built into the installer (bash).
# shellcheck shell=bash disable=SC2034  # the colours are used by whoever sources this
#
#   theme_apply [screen]   set the colours below for the terminal (or, with
#                          "screen", for a full-screen view, which may paint
#                          its own background)
#   theme_detect           ask the terminal what its colours are (OSC 10/11)
#   theme_grad N           GRAD: N colours from violet to cyan (the logo)
#   theme_tone LEVEL       REPLY: the text colour faded to LEVEL thousandths
#                          (the reader's focus effect)
#
# One palette for dark and light, derived from the terminal's own background:
# every grey is the background moved part of the way towards the text colour,
# in steps of the golden ratio (φ = 1.618…). Each step is φ times the one
# before it, so the levels read as evenly spaced, and there are only five:
#
#   φ⁻¹  .618  D      secondary text: hints, labels, what's still to be read
#   φ⁻²  .382  LINE   rules, the progress bar's track
#   φ⁻³  .236  GHOST  the logo's texture; PSEL, a selected row on a panel
#   φ⁻⁴  .146  SURF   buttons and panels; SEL, a selected row (accent-tinted)
#   ½          PAST   what's already been heard: it recedes, but stays legible
#
# The accents (violet ACC, sky ACC2, GRN, RED, YEL) come in a dark and a light
# set, each checked against the backgrounds of popular terminal themes
# (tests/test_theme.py): text ≥ 10:1, accents and secondary text ≈ 4.5:1 or
# better. The word being spoken (HL) sits on the background moved φ⁻² of the
# way towards the accent: soft, but clearly lit.
#
# KOKORO_THEME picks: auto (follow the terminal), dark or light. A full-screen
# view on a terminal of the other kind paints its own background; plain
# command-line output always follows the terminal, since it can't.

THEME_STEPS=(1000 618 382 236 146 90)            # φ⁻ⁿ, in thousandths
# φ^(−i/4), in thousandths: a falloff in quarter golden steps (the focus effect)
THEME_DECAY=(1000 887 786 697 618 548 486 431 382 339 300 266 236 209 186 165 146 129 115 102
             90 80 71 63 56 49 44 39 34 31 27 24 21 19 17 15 13 12 10 9 8)
declare -gA THEME_TONES=()            # theme_tone's colours, by level
# name:   background  text    violet  sky     green   red     amber   cyan (logo end)
THEME_DARK=(16151d    e8e6f0  b69cff  7cd0f5  86d39a  ff7a7a  efc764  9ff0ec)
THEME_LIGHT=(fbfaf8   1c1b22  6438c9  0b67a0  21743a  c0262d  855300  0f7c86)
THEME_TERM_BG=""; THEME_TERM_FG=""    # the terminal's colours as rrggbb (asked, or remembered)
THEME_ASKED=0                         # 1 once theme_detect has asked, this run
THEME_MODE=dark; THEME_PAINT=0; THEME_TERM_MODE=""; THEME_TRUE=0; THEME_ON=0
THEME_CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/kokomux/theme"

# --------------------------------------------------------------- colour maths
# Colours are rrggbb strings; results come back in REPLY (no subshells: this
# runs every time kokoro starts).

theme_mix() {    # theme_mix A B T: A moved T thousandths of the way to B
  local a=$1 b=$2 t=$3 i x y out=""
  for i in 0 2 4; do
    x=$(( 16#${a:i:2} )) y=$(( 16#${b:i:2} ))
    printf -v out '%s%02x' "$out" $(( x + ((y - x) * t + ((y - x) * t >= 0 ? 500 : -500)) / 1000 ))
  done
  REPLY=$out
}

theme_luma() {   # theme_luma RGB: perceived brightness, 0 to 255
  REPLY=$(( (2126 * 16#${1:0:2} + 7152 * 16#${1:2:2} + 722 * 16#${1:4:2}) / 10000 ))
}

theme_sgr() {    # theme_sgr 38|48 RGB: the SGR parameters for that colour (REPLY)
  local r=$(( 16#${2:0:2} )) g=$(( 16#${2:2:2} )) b=$(( 16#${2:4:2} ))
  if [ "$THEME_TRUE" = 1 ]; then REPLY="$1;2;$r;$g;$b"; return 0; fi
  # The nearest of the 256 colours: the 6×6×6 cube or the 24 greys.
  local -a lv=(0 95 135 175 215 255)
  local ri gi bi k v d1 d2 grey
  for k in r g b; do
    v=${!k}; if [ "$v" -lt 48 ]; then v=0; elif [ "$v" -lt 115 ]; then v=1; else v=$(( (v - 35) / 40 )); fi
    printf -v "${k}i" '%s' "$v"
  done
  d1=$(( (r - lv[ri]) ** 2 + (g - lv[gi]) ** 2 + (b - lv[bi]) ** 2 ))
  grey=$(( (r + g + b) / 3 )); k=$(( grey > 238 ? 23 : (grey < 8 ? 0 : (grey - 3) / 10) ))
  v=$(( 8 + 10 * k ))
  d2=$(( (r - v) ** 2 + (g - v) ** 2 + (b - v) ** 2 ))
  if [ "$d2" -lt "$d1" ]; then REPLY="$1;5;$(( 232 + k ))"; else REPLY="$1;5;$(( 16 + 36 * ri + 6 * gi + bi ))"; fi
}

theme_fg() { theme_sgr 38 "$1"; REPLY=$'\033['"$REPLY"m; }
theme_bg() { theme_sgr 48 "$1"; REPLY=$'\033['"$REPLY"m; }

# --------------------------------------------------------------- the terminal

theme_truecolor() {
  case "${KOKORO_TRUECOLOR:-}" in 1) return 0 ;; 0) return 1 ;; esac
  case "${COLORTERM:-}" in truecolor|24bit) return 0 ;; esac
  [ -n "${TERMUX_VERSION:-}" ] && return 0                  # Termux has 24-bit colour
  case "${TERM_PROGRAM:-}" in iTerm.app|WezTerm|vscode|ghostty) return 0 ;; esac
  case "${TERM:-}" in *-direct|xterm-kitty|alacritty|foot*|wezterm) return 0 ;; esac
  return 1
}

theme_detect() { # ask the terminal for its background and text colours
  # OSC 11 and 10 ask; DA1 (which every terminal answers) marks the end of the
  # replies, so a terminal that doesn't know OSC 11 costs no waiting.
  local fd saved="" buf="" c n=0 hex x wait=1 got=0
  THEME_ASKED=1
  [ -t 2 ] || return 1
  { exec {fd}<>/dev/tty; } 2>/dev/null || return 1
  saved="$(stty -g <&"$fd" 2>/dev/null)" || saved=""
  stty -echo -icanon <&"$fd" 2>/dev/null || true
  printf '\033]11;?\033\\\033]10;?\033\\\033[c' >&"$fd"
  while [ "$n" -lt 160 ] && IFS= read -r -s -n1 -t "$wait" -u "$fd" c; do
    buf+=$c; n=$(( n + 1 )) wait=0.2                    # the first reply may take a moment
    [[ "$buf" =~ $'\033'\[\?[0-9\;]*c$ ]] && break
  done
  [ -n "$saved" ] && stty "$saved" <&"$fd" 2>/dev/null
  exec {fd}>&-
  for x in 11 10; do
    [[ "$buf" =~ \]$x\;rgb:([0-9a-fA-F]+)/([0-9a-fA-F]+)/([0-9a-fA-F]+) ]] || continue
    hex=""
    for c in "${BASH_REMATCH[@]:1:3}"; do              # 1 to 4 hex digits per channel
      [ "${#c}" = 1 ] && c="$c$c"
      hex+="${c:0:2}"
    done
    hex=${hex,,}
    if [ "$x" = 11 ]; then THEME_TERM_BG=$hex got=1; else THEME_TERM_FG=$hex; fi
  done
  [ "$got" = 1 ] || return 1                          # no answer: what we remembered stands
  # Remember it, so plain command-line output can follow the terminal too.
  if [ "$(cat "$THEME_CACHE" 2>/dev/null)" != "$THEME_TERM_BG $THEME_TERM_FG" ]; then
    mkdir -p "${THEME_CACHE%/*}" 2>/dev/null && printf '%s %s\n' "$THEME_TERM_BG" "$THEME_TERM_FG" > "$THEME_CACHE" 2>/dev/null
  fi
  return 0
}

theme_term() {   # THEME_TERM_MODE (dark, light or "") and THEME_TERM_BG, from what we know
  local f="" bgi
  if [ -z "$THEME_TERM_BG" ] && [ -f "$THEME_CACHE" ]; then
    read -r f _ < "$THEME_CACHE" 2>/dev/null || f=""
    [[ "$f" =~ ^[0-9a-f]{6}$ ]] && THEME_TERM_BG=$f
  fi
  THEME_TERM_MODE=""
  if [ -n "$THEME_TERM_BG" ]; then
    theme_luma "$THEME_TERM_BG"; if [ "$REPLY" -ge 128 ]; then THEME_TERM_MODE=light; else THEME_TERM_MODE=dark; fi
  elif [[ "${COLORFGBG:-}" =~ ([0-9]+)$ ]]; then          # "15;0": text white, background black
    bgi=${BASH_REMATCH[1]}
    if [ "$bgi" = 7 ] || [ "$bgi" -ge 9 ]; then THEME_TERM_MODE=light; else THEME_TERM_MODE=dark; fi
  fi
}

# --------------------------------------------------------------- the palette

theme_off() {
  R="" B="" D="" PAST="" LINE="" GHOST="" ACC="" ACC2="" GRN="" RED="" YEL=""
  HL="" SURF="" SEL="" PSEL="" BASE="" ACC_SGR="" D_SGR=""
  THEME_ON=0
}

theme_apply() {  # theme_apply [screen]
  local want=${KOKORO_THEME:-auto} screen=${1:-} bg fg
  local -a p
  case "$want" in dark|light) ;; *) want=auto ;; esac
  theme_term
  THEME_PAINT=0
  if [ "$want" != auto ] && [ "$screen" = screen ]; then
    THEME_MODE=$want
    [ "$THEME_TERM_MODE" = "$want" ] || THEME_PAINT=1           # paint our own canvas
  elif [ -n "$THEME_TERM_MODE" ]; then THEME_MODE=$THEME_TERM_MODE
  elif [ "$want" != auto ]; then THEME_MODE=$want                # the best guess we have
  else THEME_MODE=dark; fi
  if [ "$THEME_MODE" = light ]; then p=("${THEME_LIGHT[@]}"); else p=("${THEME_DARK[@]}"); fi
  # The greys are made from the real background when we know it, and always
  # lead towards the palette's own text colour, so they keep their contrast
  # even with a low-contrast terminal theme.
  bg=${p[0]} fg=${p[1]}
  [ "$THEME_PAINT" = 0 ] && [ -n "$THEME_TERM_BG" ] && [ "$THEME_TERM_MODE" = "$THEME_MODE" ] && bg=$THEME_TERM_BG
  THEME_BG=$bg THEME_FG=$fg THEME_ACC=${p[2]} THEME_END=${p[7]}
  THEME_TONES=()
  if theme_truecolor; then THEME_TRUE=1; else THEME_TRUE=0; fi

  R=$'\033[0m' B=$'\033[1m' BASE=""
  if [ "$THEME_PAINT" = 1 ]; then
    theme_bg "$bg"; BASE=$REPLY; theme_fg "$fg"; BASE+=$REPLY
    R+=$BASE                                    # a reset goes back to our canvas
  fi
  theme_mix "$bg" "$fg" "${THEME_STEPS[1]}"; theme_fg "$REPLY"; D=$REPLY
  D_SGR=${D#$'\033['}; D_SGR=${D_SGR%m}
  theme_mix "$bg" "$fg" 500;                  theme_fg "$REPLY"; PAST=$REPLY
  theme_mix "$bg" "$fg" "${THEME_STEPS[2]}"; theme_fg "$REPLY"; LINE=$REPLY
  theme_mix "$bg" "$fg" "${THEME_STEPS[3]}"; theme_fg "$REPLY"; GHOST=$REPLY
  theme_mix "$bg" "$fg" "${THEME_STEPS[4]}"; theme_bg "$REPLY"; SURF=$REPLY
  local tint; theme_mix "$fg" "${p[2]}" "${THEME_STEPS[1]}"; tint=$REPLY
  theme_mix "$bg" "$tint" "${THEME_STEPS[4]}"; theme_bg "$REPLY"; SEL=$REPLY
  theme_mix "$bg" "$tint" "${THEME_STEPS[3]}"; theme_bg "$REPLY"; PSEL=$REPLY
  theme_mix "$bg" "${p[2]}" "${THEME_STEPS[2]}"; theme_bg "$REPLY"; HL=$REPLY
  theme_fg "$fg"; HL+=$REPLY
  theme_fg "${p[2]}"; ACC=$REPLY; ACC_SGR=${ACC#$'\033['}; ACC_SGR=${ACC_SGR%m}
  theme_fg "${p[3]}"; ACC2=$REPLY
  theme_fg "${p[4]}"; GRN=$REPLY
  theme_fg "${p[5]}"; RED=$REPLY
  theme_fg "${p[6]}"; YEL=$REPLY
  THEME_ON=1
}

theme_grad() {   # theme_grad N: GRAD, N colours from the accent to the logo's cyan
  local n=$1 k
  GRAD=()
  for (( k = 0; k < n; k++ )); do
    theme_mix "$THEME_ACC" "$THEME_END" $(( n > 1 ? k * 1000 / (n - 1) : 0 ))
    theme_fg "$REPLY"; GRAD+=("$REPLY")
  done
}

theme_tone() {   # theme_tone LEVEL: REPLY = the text colour, LEVEL thousandths of the way from the background
  local k=$(( ($1 + 5) / 10 * 10 ))                # in steps of 1%, so there are few to remember
  if [ -z "${THEME_TONES[$k]+x}" ]; then
    theme_mix "$THEME_BG" "$THEME_FG" "$k"; theme_fg "$REPLY"; THEME_TONES[$k]=$REPLY
  fi
  REPLY=${THEME_TONES[$k]}
}

theme_name() {   # what the settings menu shows beside "Colours"
  case "${KOKORO_THEME:-auto}" in
    dark) REPLY="Dark" ;; light) REPLY="Light" ;;
    *) REPLY="Auto" ;;
  esac
}
__KOKOMUX_THEME__

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
    # pypdf reads PDFs.
    "$APP/venv/bin/pip" install --upgrade kokoro-onnx soundfile pypdf | tee /tmp/kokoro-setup/.pip
    # Before 0.8, Whistle (cactus-compute) timed the words; Kokoro does that itself now.
    # It and the packages only it needed are removed (about 54 MB).
    "$APP/venv/bin/pip" uninstall -y cactus-compute pillow huggingface-hub hf-xet fsspec filelock \
      tqdm pyyaml click anyio h11 httpcore2 httpx2 truststore 2>/dev/null || true
    grep -q '^Successfully installed' /tmp/kokoro-setup/.pip || printf 'up to date' > "$SRC/.note"
    cp "$SRC/engine.py" "$SRC/words.py" "$APP/"
    rm -f "$APP/speak.py"   # from older versions
    ;;
  models)
    if [ -s "$APP/models/kokoro-v1.0-timestamped.int8.onnx" ] && [ -s "$APP/models/voices-v1.0.bin" ]; then
      printf 'already downloaded' > "$SRC/.note"
    fi
    "$APP/venv/bin/python" -u "$APP/engine.py" download
    ;;
  tidy)
    apt-get clean
    rm -rf /var/lib/apt/lists/* /root/.cache/pip /usr/share/man/* /usr/share/info/*
    rm -rf /root/.cache/cactus /root/.cache/cactus-needle /root/.cache/huggingface/hub/models--Cactus-Compute--*   # old Whistle files
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

KOKOMUX_VERSION="0.10.0"
CONF_DIR="$HOME/.config/kokoro"
CONF="$CONF_DIR/config"

# Precedence: environment (KOKORO_VOICE=am_adam kokoro ...) > settings file
# > these defaults.
env_names="${!KOKORO_@}"
env_overrides=""
# shellcheck disable=SC2086
[ -n "$env_names" ] && env_overrides="$(declare -p $env_names)"
# The defaults, in one place (the settings menu's "Reset to defaults" uses them too).
KOKORO_DEFAULTS=(
  KOKORO_VOICE=af_heart         # see: kokoro --list-voices
  KOKORO_SPEED=1.0              # 0.5 to 2.0
  KOKORO_LANG=en-us
  KOKORO_OUT=kokoro.wav         # file name when -o isn't given
  KOKORO_AUTOSERVE=1            # keep Kokoro loaded in the background
  KOKORO_IDLE_MINUTES=30        # ...until unused this long (0: never stop)
  KOKORO_PORT=8880
  KOKORO_WORDS=1                # light up each word as it's spoken (-p on a terminal)
  KOKORO_WORDS_OFFSET_MS=100    # play-audio's start-up delay, so the highlight isn't early
  KOKORO_NOTIFY=1               # a notification with pause / next / again buttons (Termux:API)
  KOKORO_TUI=1                  # -p on a terminal opens the full-screen reader (0: inline)
  KOKORO_THEME=auto             # colours: auto (follow the terminal), dark or light
  KOKORO_FOCUS=1                # the reader fades text with its distance from the line being read
)
for kv in "${KOKORO_DEFAULTS[@]}"; do declare "$kv"; done
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

# Count characters, not bytes ("…", "▶" and "␣" are one column each), so text
# lines up whatever locale the terminal was started with. Byte-exact slicing of
# the text being read still uses a local LC_ALL=C where it needs it.
case "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" in
  *UTF-8*|*utf8*|*UTF8*) ;;
  *) export LC_CTYPE=C.UTF-8 2>/dev/null ;;
esac
# Messages go to stderr. Colours and live status only on a terminal, so
# scripts and pipes get plain, quiet output.

# The colours (theme.sh) follow the terminal: a dark or light palette, made
# from its own background where it has told us what that is.
LIVE=0
if [ -t 2 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-dumb}" != dumb ]; then LIVE=1; fi
THEME_MODE=dark THEME_PAINT=0 THEME_ON=0 THEME_ASKED=0   # (theme.sh sets these properly)
THEME_LIB="$PREFIX/share/kokomux/theme.sh"
# shellcheck source=src/theme.sh
[ -f "$THEME_LIB" ] && . "$THEME_LIB"
# shellcheck disable=SC2034  # the screens and the player use them
if [ "$LIVE" = 1 ] && declare -F theme_apply >/dev/null; then
  theme_apply
elif [ "$LIVE" = 1 ]; then                    # theme.sh missing (a half-finished update)
  R=$'\033[0m' B=$'\033[1m' D=$'\033[2m' ACC=$'\033[38;5;141m' ACC2=$'\033[38;5;117m'
  GRN=$'\033[38;5;114m' RED=$'\033[38;5;203m' YEL=$'\033[38;5;221m'
  PAST=$'\033[38;5;245m' LINE=$D GHOST=$'\033[38;5;237m' HL=$'\033[48;5;61m\033[38;5;231m'
  SURF=$'\033[48;5;236m' SEL=$'\033[48;5;237m' PSEL=$'\033[48;5;238m' BASE="" ACC_SGR="38;5;141" D_SGR=2
else
  R="" B="" D="" ACC="" ACC2="" GRN="" RED="" YEL="" PAST="" LINE="" GHOST="" HL=""
  SURF="" SEL="" PSEL="" BASE="" ACC_SGR="" D_SGR=""
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
  declare -F tui_close >/dev/null && tui_close
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
Space ← → q|keys while it plays: pause, again, next, stop
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
--settings|voice, speed, highlighting and more
--config|edit the settings file directly
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

# ------------------------------------------------------------ keys and the frame timer
# tick waits a moment (no process) and, on a terminal, reads one key or click
# into KEY. A stop signal during the wait must not exit from inside `read -t`:
# if cleanup then outlives the timer, bash's alarm jumps back into a read that's
# gone and bash crashes (seen with bash 5.2). So during the wait the signal only
# sets a flag, and tick exits once read has returned.

STOP=""; IN_TICK=0; KEY=""; CMD=""; KBD=""; KEYS_TTY=""
exec {TICK}<> <(:)                    # read -t on this is a sleep with no process
now_ms() { local t=${EPOCHREALTIME//[!0-9]/}; NOW=$(( t / 1000 )); }   # no subshell: called every frame
keys_on() {      # read keys from the terminal, unechoed (restored on exit)
  [ -n "$KBD" ] && return 0
  [ "$LIVE" = 1 ] && [ -r /dev/tty ] || return 0
  { exec {KBD}</dev/tty; } 2>/dev/null || { KBD=""; return 0; }
  KEYS_TTY="$(stty -g 2>/dev/null </dev/tty)" || KEYS_TTY=""
  stty -echo -icanon 2>/dev/null </dev/tty || true
}
keys_off() { [ -n "$KEYS_TTY" ] && stty "$KEYS_TTY" 2>/dev/null </dev/tty; KEYS_TTY=""; return 0; }
tick() {
  local c rest=""
  IN_TICK=1; KEY=""
  if [ -n "$KBD" ]; then
    if IFS= read -rsn1 -d '' -t "$1" -u "$KBD" KEY && [ "$KEY" = $'\033' ]; then
      # an arrow key or a click: the rest of the sequence follows at once
      while IFS= read -rsn1 -d '' -t 0.01 -u "$KBD" c; do
        rest+=$c; [[ "$c" == [A-Za-z~] ]] && [ "${#rest}" -gt 1 ] && break
      done
      KEY=$'\033'$rest
    fi
  else
    read -r -t "$1" -u "$TICK" _ || true
  fi
  IN_TICK=0
  [ -z "$STOP" ] || exit 130
}
key_cmd() {      # what KEY asks for (or a clicked button): see the player for each
  CMD=""
  case "$KEY" in
    " "|p|P|k) CMD=toggle ;;
    n|N|l|$'\033[C'|$'\033OC') CMD=next ;;
    b|B|h|$'\033[D'|$'\033OD') CMD=again ;;
    q|Q|$'\033') CMD=quit ;;
    +|=) CMD=faster ;;
    -|_) CMD=slower ;;
    t|T) CMD="sleep" ;;
    c|C) CMD=chapters ;;
    ']') CMD=next_chapter ;;
    '[') CMD=prev_chapter ;;
    $'\033[<'*M) [ "${ui:-}" = tui ] && tui_click "$KEY" ;;
  esac
  KEY=""
}

TUI_LIB="$PREFIX/share/kokomux/tui.sh"
# shellcheck source=src/tui.sh
[ -f "$TUI_LIB" ] && . "$TUI_LIB"

# kokoro on its own, on a terminal: the home screen.
if [ $# = 0 ] && [ "$LIVE" = 1 ] && [ -t 0 ] && [ -t 1 ] && declare -F tui_home >/dev/null; then
  keys_on
  trap 'tui_close; keys_off; exit 130' INT TERM HUP
  tui_home
  keys_off; trap - INT TERM HUP
  [ "${#HOME_CHOICE[@]}" -gt 0 ] && exec "$0" "${HOME_CHOICE[@]}"
  exit 0
fi

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
    last="${2:-}"
    case "$last" in "$BOOKS"/*.mark) [ -f "$last" ] || last="" ;; *) last="" ;; esac
    [ -n "$last" ] || last="$(ls -t "$BOOKS"/*.mark 2>/dev/null | head -n 1)" || true
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
    printf '    %sChange them with: kokoro --settings%s\n' "$D" "$R"
    exit 0 ;;
  --settings)
    if [ "$LIVE" = 1 ] && [ -t 0 ] && declare -F tui_settings >/dev/null; then
      keys_on
      trap 'tui_close; keys_off; exit 130' INT TERM HUP
      tui_settings
      keys_off; trap - INT TERM HUP
      exit 0
    fi
    exec "$0" --status ;;
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
    rm -rf "$CONF_DIR" "$SRV_DIR" "$HOME/kokoro-test.wav" "$HOME/.cache/kokomux" "$HOME/.local/share/kokomux" "$PREFIX/share/kokomux"
    done_msg "Settings removed"
    rm -f "$0"
    done_msg "kokomux is uninstalled." "Thanks for trying it!"
    exit 0 ;;
  --list-voices)
    status "Loading voices"
    flags=(--width "$(cols)" --default "$KOKORO_VOICE")
    [ "$LIVE" = 1 ] && [ -t 1 ] && flags+=(--color --accent "$ACC_SGR" --muted "$D_SGR")
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
  # (the player's functions exist only once player.sh is loaded: a run can stop before that)
  [ "${in_line:-0}" = 1 ] && [ "${ui:-}" = inline ] && [ "${show_words:-0}" = 1 ] && declare -F block_finish >/dev/null && block_finish
  declare -F tui_close >/dev/null && tui_close
  keys_off
  if [ "${ui:-none}" != none ] && [ -n "${book_id:-}" ] && [ -f "$BOOKS/$book_id.mark" ]; then
    [ "${SLEPT:-0}" = 1 ] && info "Sleep timer: good night."
    info "Stopped at part $(mark_get "$BOOKS/$book_id.mark" part) of $(mark_get "$BOOKS/$book_id.mark" parts). ${D}kokoro --continue picks up there.${R}"
  fi
  if [ "${play:-0}" = 1 ]; then declare -F notify_clear >/dev/null && notify_clear; rm -f "$PLAYER_STATE"; fi
  [ -n "$play_pid" ] && kill "$play_pid" 2>/dev/null
  [ -n "$gen_pid" ] && kill "$gen_pid" 2>/dev/null
  rm -rf "$tmpd"
  if [ "$play" = 1 ] && [ "$(cat "$PLAYER_PID" 2>/dev/null)" = "$$" ]; then rm -f "$PLAYER_PID"; fi
  return 0
}
trap cleanup EXIT
# (see tick: during the frame timer a stop signal only sets a flag)
on_signal() { if [ "$IN_TICK" = 1 ]; then STOP=1; else exit 130; fi; }
trap on_signal INT TERM HUP

text="$tmpd/in.txt"
book=0
if [ -n "$url" ]; then
  :
elif [ "$clip" = 1 ]; then
  command -v termux-clipboard-get >/dev/null ||
    die "Reading the clipboard needs Termux:API." "Install the Termux:API app, then: pkg install termux-api"
  # termux-clipboard-get waits forever without the Termux:API app, so give it a few
  # seconds, show that it's waiting, and remember the answer for the home screen.
  termux-clipboard-get > "$text" 2>/dev/null & cpid=$!
  for n in $(seq 60); do
    kill -0 "$cpid" 2>/dev/null || break
    status "Reading the clipboard" "(this needs the Termux:API app)"; sleep 0.1
  done
  mkdir -p "$CONF_DIR"
  if kill -0 "$cpid" 2>/dev/null || ! wait "$cpid"; then
    kill "$cpid" 2>/dev/null || true
    echo no > "$CONF_DIR/termux-api"
    die "Couldn't read the clipboard: the Termux:API app didn't answer." \
        "Install the Termux:API app from F-Droid (where you got Termux), open it once, then try again."
  fi
  echo yes > "$CONF_DIR/termux-api"
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

# With -p on a terminal, show each line as it's read and light the spoken word,
# in the full-screen reader (ui=tui) or inline below the command (ui=inline).
show_words=0
[ "$play" = 1 ] && [ "$LIVE" = 1 ] && [ "$KOKORO_WORDS" = 1 ] && show_words=1
ui=none
if [ "$play" = 1 ] && [ "$LIVE" = 1 ]; then
  ui=inline; keys_on
  if [ "$KOKORO_TUI" = 1 ] && [ -n "$KBD" ] && declare -F tui_open >/dev/null && tui_fits; then ui=tui; fi
fi

write_job() {    # the job, for the engine (where it starts and how fast can change mid-listen)
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
}
write_job

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
  elif [ "$start" -gt 1 ]; then echo "Picking up at part $start"
  elif [ -f "$tmpd/progress" ]; then echo "Making speech"
  elif [ -n "$url" ]; then echo "Fetching the page"
  else echo "Getting ready"; fi
}

# ------------------------------------------------------------ the player
# play_line and everything it needs live in player.sh, beside tui.sh.

PLAYER_LIB="$PREFIX/share/kokomux/player.sh"
[ -f "$PLAYER_LIB" ] || die "kokomux is only partly installed." "Run the installer again: kokoro --update"
# shellcheck source=src/player.sh
. "$PLAYER_LIB"

# ------------------------------------------------------------ run

if [ "$play" = 1 ]; then
  stop_playback || true
  rm -f "$CONTROL"                             # nothing left over from an earlier player
  echo "$$" > "$PLAYER_PID"
fi
run_engine > "$tmpd/engine.log" 2>&1 & gen_pid=$!
if [ "$ui" = tui ]; then
  TUI_TITLE=$name; TUI_INFO="$voice · ${speed}×"; tui_open
fi

i=$start
while :; do
  # The engine starts over at part 1 if a bookmark points past the end (the text changed).
  [ "$i" = "$start" ] && [ -f "$tmpd/stream/first" ] && { read -r i < "$tmpd/stream/first" || i=$start; start=$i; }
  f="$(printf '%s/stream/%04d.wav' "$tmpd" "$i")"
  if [ "$play" = 1 ] && [ -f "$f" ]; then
    play_line "$f" "$i"
    if [ -n "$JUMP" ]; then jump_to "$JUMP"; JUMP=""; continue; fi
    i=$((i + 1))
    if sleep_due "$i"; then SLEPT=1; bookmark_save "$i"; exit 0; fi   # the sleep timer: stop between parts
  elif [ "$play" = 1 ] && [ -f "$tmpd/stream/end" ]; then
    [ -f "$f" ] && continue   # a last chunk may have landed just now
    break
  elif ! kill -0 "$gen_pid" 2>/dev/null; then
    [ "$play" = 1 ] && [ -f "$f" ] && continue
    break
  else
    view_state "$(waiting_label)" "$(progress)"
    view_refresh
    tick 0.1
    if [ -n "$KEY" ]; then
      key_cmd; [ "$CMD" = quit ] && exit 0
      [ -n "$CMD" ] && listen_cmd "$CMD" "$i" && [ -n "$JUMP" ] && { jump_to "$JUMP"; JUMP=""; }
    fi
  fi
done
rc=0; wait "$gen_pid" || rc=$?
gen_pid=""
declare -F tui_close >/dev/null && tui_close
status_clear
if [ "$play" = 1 ] && [ "$rc" = 0 ]; then bookmark_done; book_id=""; fi   # read to the end: no bookmark

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
