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
                "v|Voice  ${D}$(voice_label "$(voice_mix)") · ${KOKORO_SPEED}×${R}|voice" "s|Settings  ${D}speed, colours, highlighting…${R}|settings"; do
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

blend_amount() {  # how much of the blended voice is in the mix, in percent (30 if unsaid)
  local a=30; [[ "$KOKORO_BLEND" == *:* ]] && a=${KOKORO_BLEND#*:}
  [[ "$a" =~ ^[0-9]+$ ]] && [ "$a" -ge 10 ] && [ "$a" -le 90 ] || a=30
  echo "$a"
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
  "blend|Blend with|Mix a second voice into yours, for a voice of your own. Enter picks it, Space hears the mix, and ← turns blending off."
  "blendamt|Blend amount|How much of the second voice is in the mix, from 10% to 90%. ← and → change it in steps of 10; Space hears it."
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
    blend) if [ -z "$KOKORO_BLEND" ]; then printf '%snone%s' "$D" "$R"
           else printf '%s%s%s' "$ACC2" "${KOKORO_BLEND%%:*}" "$R"
                [ "$SET_NARROW" = 1 ] || printf '  %s%s%s' "$D" "$(voice_desc "${KOKORO_BLEND%%:*}")" "$R"; fi ;;
    blendamt)
      if [ -z "$KOKORO_BLEND" ]; then printf '%s—%s' "$D" "$R"; return 0; fi
      local w=15 a; [ "$SET_NARROW" = 1 ] && w=9
      a=$(blend_amount); f=$(( (a - 10) * w / 80 ))
      printf -v bar '%*s' "$f" ''; bar="$ACC${bar// /━}●$R"
      printf -v e '%*s' $(( w - f )) ''; bar+="$D${e// /─}$R"
      printf '%s  %s%%' "$bar" "$a" ;;
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
    blend)
      if [ "$step" = -1 ]; then
        [ -n "$KOKORO_BLEND" ] && { tui_save KOKORO_BLEND ""; SETTINGS_NOTE="Blending is off."; }
      else tui_voices blend; fi ;;
    blendamt)
      if [ -z "$KOKORO_BLEND" ]; then SETTINGS_NOTE="Choose a voice to blend with first (Blend with, above)."; return 0; fi
      [ "$step" = 0 ] && step=1
      t=$(( $(blend_amount) + step * 10 ))
      [ "$t" -ge 10 ] && [ "$t" -le 90 ] && tui_save KOKORO_BLEND "${KOKORO_BLEND%%:*}:$t" ;;
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
      " ") case "${items[sel]}" in voice|blend|blendamt) voice_sample "$(voice_mix)" ;; *) setting_change "${items[sel]}" 0 ;; esac ;;
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

tui_pick() {     # tui_pick VOICE MODE: choose it as your voice, or (MODE blend) as the one blended in
  if [ "$2" = blend ]; then
    if [ "$1" = "$KOKORO_VOICE" ]; then PICK_NOTE="That's your own voice. Pick another to blend with it."; return 1; fi
    tui_save KOKORO_BLEND "$1:$(blend_amount)"
  else
    tui_save KOKORO_VOICE "$1"
    tui_save KOKORO_LANG "${TUI_VOICE_LANG[${1:0:1}]:-$KOKORO_LANG}"   # its own language
  fi
}

tui_voices() {   # tui_voices [blend]: the voice picker: type to filter, Space to hear, Enter to choose
  local mode=${1:-} filter="" sel=0 top=0 list=() v i y view lang prev g key cur=$KOKORO_VOICE title="Choose a voice"
  local -A rowsel=()
  PICK_NOTE=""
  if [ "$mode" = blend ]; then cur=${KOKORO_BLEND%%:*}; title="Blend with a voice"; fi
  for i in "${!TUI_VOICES[@]}"; do [ "${TUI_VOICES[i]}" = "$cur" ] && sel=$i; done
  while :; do
    tui_size; view=$(( TH - 7 ))
    list=(); for v in "${TUI_VOICES[@]}"; do [[ -z "$filter" || "$v" == *"$filter"* ]] && list+=("$v"); done
    [ "$sel" -ge "${#list[@]}" ] && sel=$(( ${#list[@]} > 0 ? ${#list[@]} - 1 : 0 ))
    [ "$sel" -lt "$top" ] && top=$sel
    [ "$sel" -ge $(( top + view )) ] && top=$(( sel - view + 1 ))
    printf '%s%s\033[2J\033[1;1H  %skokomux%s  %s%s%s' "$SYNC_ON" "$R" "$ACC" "$R" "$B" "$title" "$R" >&2
    printf '\033[1;%dH%s%s%s' $(( TW - 20 )) "$D" "${#list[@]} of ${#TUI_VOICES[@]} voices" "$R" >&2
    printf '\033[2;1H%s%s%s' "$LINE" "$(rule "$TW")" "$R" >&2
    printf '\033[3;%dH%sFilter:%s %s%s▏' "$LM" "$D" "$R" "$filter" "$ACC" >&2; printf '%s' "$R" >&2
    g=""
    if [ -n "$PICK_NOTE" ]; then g=$PICK_NOTE
    elif [ "$mode" = blend ]; then g="mixed into $KOKORO_VOICE at $(blend_amount)%"; fi
    if [ -n "$g" ] && [ $(( LM + 14 + ${#filter} + ${#g} )) -lt "$TW" ]; then
      printf '\033[3;%dH%s%s%s' $(( LM + CW - ${#g} )) "$D" "$g" "$R" >&2
    fi
    rowsel=(); prev=""
    for (( i = top, y = 5; i < ${#list[@]} && y <= TH - 3; i++, y++ )); do
      v=${list[i]}; lang=${TUI_VOICE_LANG[${v:0:1}]:-}; g=female; [ "${v:1:1}" = m ] && g=male
      local mark=" "; [ "$v" = "$cur" ] && mark="$GRN✓$R"
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
    if [ "$mode" = blend ]; then
      if [ "$TW" -ge 72 ]; then g="type to filter · ↑↓ choose · Space hear the mix · Enter blend it · Esc back"
      else g="type · ↑↓ · Space hear · Enter blend · Esc"; fi
    elif [ "$TW" -ge 72 ]; then g="type to filter · ↑↓ choose · Space hear it · Enter use it · Esc back"
    else g="type · ↑↓ · Space hear · Enter use · Esc"; fi
    printf '\033[%d;%dH%s%s%s%s' "$TH" "$LM" "$D" "$g" "$R" "$SYNC_OFF" >&2

    KEY=""; tick 0.5; [ -n "$KEY" ] || continue
    PICK_NOTE=""
    case "$KEY" in
      $'\033[A'|$'\033OA'|$'\033[<64;'*M) [ "$sel" -gt 0 ] && sel=$(( sel - 1 )) ;;
      $'\033[B'|$'\033OB'|$'\033[<65;'*M) [ "$sel" -lt $(( ${#list[@]} - 1 )) ] && sel=$(( sel + 1 )) ;;
      $'\033[5~') sel=$(( sel > view ? sel - view : 0 )) ;;
      $'\033[6~') sel=$(( sel + view < ${#list[@]} ? sel + view : ${#list[@]} - 1 )) ;;
      " ") [ "${#list[@]}" -gt 0 ] || continue
           if [ "$mode" = blend ]; then voice_sample "$(voice_mix "$KOKORO_VOICE" "${list[sel]}:$(blend_amount)")"
           else voice_sample "${list[sel]}"; fi ;;
      $'\n'|$'\r') [ "${#list[@]}" -gt 0 ] && tui_pick "${list[sel]}" "$mode" && return 0 ;;
      $'\177'|$'\b') filter=${filter%?}; sel=0; top=0 ;;
      [a-z_]) filter+=$KEY; sel=0; top=0 ;;
      $'\033[<0;'*M)
        [[ "$KEY" =~ \;([0-9]+)M$ ]] || continue
        i=${rowsel[${BASH_REMATCH[1]}]:-}; [ -n "$i" ] || continue
        if [ "$i" = "$sel" ]; then tui_pick "${list[sel]}" "$mode" && return 0
        else sel=$i; fi ;;
      $'\033'|q) return 0 ;;
    esac
  done
}
