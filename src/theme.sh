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

theme_fg() { theme_sgr 38 "$1"; REPLY=$'\033['"$REPLY"m; }   # theme_fg RGB: REPLY = the escape for that text colour
theme_bg() { theme_sgr 48 "$1"; REPLY=$'\033['"$REPLY"m; }   # theme_bg RGB: REPLY = the escape for that background

# --------------------------------------------------------------- the terminal

theme_truecolor() {   # does the terminal show 24-bit colour?
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

theme_off() {    # no colours (not a terminal, or NO_COLOR)
  R="" B="" D="" PAST="" LINE="" GHOST="" ACC="" ACC2="" GRN="" RED="" YEL=""
  HL="" SURF="" SEL="" PSEL="" BASE="" ACC_SGR="" D_SGR=""
  THEME_ON=0
}

# shellcheck disable=SC2120  # (the installer calls it without "screen")
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
