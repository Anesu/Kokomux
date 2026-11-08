# kokomux: the full-screen interface. Sourced by `kokoro` (bash).
# shellcheck shell=bash disable=SC2034,SC2154  # shares its globals with kokoro
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
# Expects from kokoro: the colours (R B D ACC ACC2 GRN YEL HL), $tmpd, $KBD
# (a read descriptor on /dev/tty), KEY (set by tick), and for the home screen
# BOOKS, CONF, mark_get, KOKORO_VOICE and KOKORO_SPEED.

TUI=0; TUI_TTY=""; TUI_RESIZED=0
TW=80; TH=24; CW=72; LM=4             # screen size; reading column width and margin
declare -A TUI_WRAP=() TUI_OFFS=()    # each part's text wrapped to the column, by part
tui_hist=()                           # parts already heard, oldest first
TUI_PART=0; TUI_PARTS=0; TUI_A=0; TUI_B=0
TUI_STATE=""; TUI_EXTRA=""; TUI_SYM=""; TUI_PAUSED=0; TUI_TITLE=""; TUI_INFO=""
TUI_BTN=()                            # clickable buttons: "x0 x1 command"
SYNC_ON=$'\033[?2026h'; SYNC_OFF=$'\033[?2026l'

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

tui_open() {
  [ "$TUI" = 1 ] && return 0
  TUI_TTY="$(stty -g 2>/dev/null </dev/tty)" || TUI_TTY=""
  printf '\033[?1049h' >&2
  tui_raw
  TUI=1
  trap 'TUI_RESIZED=1' WINCH
  tui_size
  tui_draw
}

tui_close() {
  [ "$TUI" = 1 ] || return 0
  printf '\033[?1000l\033[?1006l%s\033[?25h\033[?1049l' "$SYNC_OFF" >&2
  [ -n "$TUI_TTY" ] && stty "$TUI_TTY" 2>/dev/null </dev/tty
  trap - WINCH
  TUI=0
}

tui_refresh() {  # after a resize: measure again and draw everything
  [ "$TUI_RESIZED" = 1 ] || return 0
  TUI_RESIZED=0
  tui_size
  tui_draw
}

rule() { local line; printf -v line '%*s' "$1" ''; printf '%s' "${line// /─}"; }

# ---------------------------------------------------------------- drawing

tui_header() {
  local info=" ${TUI_INFO} " room title
  room=$(( TW - 14 - ${#info} ))
  title=$TUI_TITLE
  [ "${#title}" -gt "$room" ] && title="${title:0:$(( room > 1 ? room - 1 : 0 ))}…"
  printf '\033[1;1H\033[2K  %skokomux%s  %s%s%s' "$ACC" "$R" "$B" "$title" "$R" >&2
  printf '\033[1;%dH%s%s%s' $(( TW - ${#info} )) "$D" "$info" "$R" >&2
  printf '\033[2;1H\033[2K%s%s%s' "$D" "$(rule "$TW")" "$R" >&2
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

tui_text() {     # the reading column: what's been heard above, this part, what's next below
  local top=4 bottom=$(( TH - 4 )) h k j n r idx a hl=0 LC_ALL=C
  local -a past=() now_rows=() offs=() next=() rows=()
  h=$(( bottom - top + 1 ))
  if [ "$TUI_PART" = 0 ] || ! tui_wrap "$TUI_PART"; then   # nothing to show yet
    for (( r = top; r <= bottom; r++ )); do printf '\033[%d;1H\033[2K' "$r" >&2; done
    local msg="${TUI_STATE:-Getting ready}"
    printf '\033[%d;%dH%s%s%s %s%s%s' $(( top + h / 3 )) $(( (TW - ${#msg} - 2) / 2 )) \
      "$ACC" "${TUI_SYM:-·}" "$R" "$D" "$msg" "$R" >&2
    return 0
  fi
  mapfile -t now_rows <<< "${TUI_WRAP[$TUI_PART]%$'\n'}"
  read -ra offs <<< "${TUI_OFFS[$TUI_PART]}"
  for (( j = ${#tui_hist[@]} - 1; j >= 0 && ${#past[@]} < h; j-- )); do
    tui_wrap "${tui_hist[j]}" || continue
    mapfile -t rows <<< "${TUI_WRAP[${tui_hist[j]}]%$'\n'}"
    past=("${rows[@]}" "${past[@]}")
  done
  for (( k = TUI_PART + 1; ${#next[@]} < h; k++ )); do
    tui_wrap "$k" || break
    mapfile -t rows <<< "${TUI_WRAP[$k]%$'\n'}"
    next+=("${rows[@]}")
  done
  # This part starts a third of the way down, but the lit word always stays on screen.
  if [ "$TUI_A" -gt 0 ]; then
    while [ $(( hl + 1 )) -lt "${#offs[@]}" ] && [ "${offs[hl + 1]}" -le "$TUI_A" ]; do hl=$(( hl + 1 )); done
  fi
  a=$(( top + h / 3 ))
  [ $(( a + hl )) -le $(( bottom - 2 )) ] || a=$(( bottom - 2 - hl ))
  n=${#now_rows[@]}
  printf '%s' "$SYNC_ON" >&2
  for (( r = top; r <= bottom; r++ )); do
    idx=$(( r - a ))
    printf '\033[%d;1H\033[2K\033[%dG' "$r" $(( LM + 1 )) >&2
    if [ "$idx" -lt 0 ]; then
      j=$(( ${#past[@]} + idx ))
      [ "$j" -ge 0 ] && printf '\033[38;5;245m%s%s' "${past[j]}" "$R" >&2
    elif [ "$idx" -lt "$n" ]; then
      printf '\033[%dG%s▎%s' $(( LM - 1 )) "$ACC" "$R" >&2   # marks the part being read
      printf '\033[%dG' $(( LM + 1 )) >&2
      tui_row "${now_rows[idx]}" "${offs[idx]}"
    else
      j=$(( idx - n ))
      [ "$j" -lt "${#next[@]}" ] && printf '%s%s%s' "$D" "${next[j]}" "$R" >&2
    fi
  done
  printf '%s' "$SYNC_OFF" >&2
}

tui_row() {      # tui_row TEXT OFFSET: one row of this part, the lit word in the soft highlight
  local row=$1 o=$2 len=${#1} a=$TUI_A b=$TUI_B x y LC_ALL=C
  if [ "$a" -lt 0 ] || [ $(( o + len )) -le "$a" ]; then
    printf '%s' "$row" >&2                                    # already spoken
  elif [ "$o" -ge "$b" ]; then
    printf '%s%s%s' "$D" "$row" "$R" >&2                      # still to come
  else
    x=$(( a > o ? a - o : 0 )); y=$(( b - o < len ? b - o : len ))
    printf '%s%s%s%s%s%s' "${row:0:x}" "$HL" "${row:x:y-x}" "$R$D" "${row:y}" "$R" >&2
  fi
}

tui_progress() {
  local label bar bw filled
  if [ "$TUI_PARTS" -gt 1 ]; then label="part $TUI_PART of $TUI_PARTS"; else label=""; fi
  bw=$(( TW - 6 - ${#label} )); [ "$bw" -ge 4 ] || bw=4
  filled=0; [ "$TUI_PARTS" -gt 0 ] && filled=$(( TUI_PART * bw / TUI_PARTS ))
  printf -v bar '%*s' "$filled" ''; bar=${bar// /━}
  printf '\033[%d;1H\033[2K  %s%s%s' $(( TH - 2 )) "$ACC" "$bar" "$R" >&2
  printf -v bar '%*s' $(( bw - filled )) ''
  printf '%s%s%s  %s' "$D" "${bar// /─}" "$R" "$label" >&2
}

tui_footer() {   # the buttons (keys and clicks), and what's happening on the right
  local x=3 key label cmd text state room
  TUI_BTN=()
  printf '\033[%d;1H\033[2K\033[%d;1H\033[2K' $(( TH - 1 )) "$TH" >&2
  for spec in "␣|$( [ "$TUI_PAUSED" = 1 ] && echo Play || echo Pause)|toggle" "←|Again|again" "→|Next|next" "q|Stop|quit"; do
    IFS='|' read -r key label cmd <<< "$spec"
    text=" k $label "                 # (the key glyph is one column, whatever its bytes)
    printf '\033[%d;%dH\033[48;5;236m %s%s%s\033[48;5;236m %s \033[0m' "$TH" "$x" "$ACC2" "$key" "$R" "$label" >&2
    TUI_BTN+=("$x $(( x + ${#text} - 1 )) $cmd")
    x=$(( x + ${#text} + 2 ))
  done
  state="${TUI_SYM:+$TUI_SYM }$TUI_STATE${TUI_EXTRA:+ · $TUI_EXTRA}"
  room=$(( TW - x - 2 ))
  if [ "$room" -gt 6 ]; then
    [ "${#state}" -gt "$room" ] && state="${state:0:$(( room - 1 ))}…"
    printf '\033[%d;%dH%s%s%s' "$TH" $(( TW - ${#state} - 1 )) "$D" "$state" "$R" >&2
  fi
}

tui_draw() {
  printf '%s' "$SYNC_ON" >&2
  tui_header; tui_text; tui_progress; tui_footer
  printf '%s' "$SYNC_OFF" >&2
}

# ---------------------------------------------------------------- the reader's view API

tui_part() {     # tui_part I PARTS: a new part starts playing (dim until its words light)
  [ "$TUI_PART" -gt 0 ] && [ "$TUI_PART" != "$1" ] && tui_hist+=("$TUI_PART")
  [ "${#tui_hist[@]}" -le 40 ] || tui_hist=("${tui_hist[@]: -40}")
  TUI_PART=$1 TUI_PARTS=${2:-$TUI_PARTS} TUI_A=0 TUI_B=0
  tui_draw
}
tui_light() { TUI_A=$1 TUI_B=$2; tui_text; }
tui_state() {    # tui_state TEXT EXTRA SYMBOL PAUSED: only the footer is redrawn
  [ "$1|$2|$3|${4:-0}" = "$TUI_STATE|$TUI_EXTRA|$TUI_SYM|$TUI_PAUSED" ] && return 0
  TUI_STATE=$1 TUI_EXTRA=$2 TUI_SYM=$3 TUI_PAUSED=${4:-0}
  if [ "$TUI_PART" = 0 ]; then tui_text; fi
  tui_footer
}

tui_click() {    # tui_click REPORT: a left click or tap on a button sets CMD
  local b x y spec x0 x1 cmd
  [[ "$1" =~ ^$'\033'\[\<([0-9]+)\;([0-9]+)\;([0-9]+)M$ ]] || return 0
  b=${BASH_REMATCH[1]} x=${BASH_REMATCH[2]} y=${BASH_REMATCH[3]}
  [ "$b" = 0 ] && [ "$y" = "$TH" ] || return 0
  for spec in "${TUI_BTN[@]}"; do
    read -r x0 x1 cmd <<< "$spec"
    if [ "$x" -ge "$x0" ] && [ "$x" -le "$x1" ]; then CMD=$cmd; return 0; fi
  done
}

# ---------------------------------------------------------------- the home screen

tui_prompt() {   # tui_prompt LABEL [DEFAULT]: ask on the bottom line (with Tab completion)
  local ans=""
  printf '%s\033[%d;1H\033[2K\033[%d;1H\033[2K' "$SYNC_OFF" $(( TH - 1 )) "$TH" >&2
  tui_cooked
  printf '\033[%d;3H' "$TH" >&2
  IFS= read -e -r -p "$1 " -i "${2:-}" ans </dev/tty 2>&1 || ans=""
  tui_raw
  ANSWER=$ans
}

tui_home() {     # runs until something is chosen; prints the chosen kokoro arguments
  local sel=0 marks=() key r i m title part parts pct bw bar row y
  local -A rowact=()
  TUI_TTY="$(stty -g 2>/dev/null </dev/tty)" || TUI_TTY=""
  printf '\033[?1049h' >&2; tui_raw; TUI=1
  trap 'TUI_RESIZED=1' WINCH
  mapfile -t marks < <(ls -t "$BOOKS"/*.mark 2>/dev/null | head -n 6)
  HOME_CHOICE=()
  while :; do
    tui_size; rowact=()
    printf '%s\033[2J' "$SYNC_ON" >&2
    y=2
    if [ "$TH" -ge 22 ]; then                     # the logo, when there's room for it
      local c=(141 153 159) l=0 line
      for line in '░█░█░█▀█░█░█░█▀█░█▄█░█░█░█░█' '░█▀▄░█░█░█▀▄░█░█░█░█░█░█░▄▀▄' '░▀░▀░▀▀▀░▀░▀░▀▀▀░▀░▀░▀▀▀░▀░▀'; do
        line="${line//░/$'\033[38;5;237m'░$'\033[38;5;'"${c[l]}"m}"
        printf '\033[%d;%dH\033[38;5;%sm%s%s' "$y" $(( (TW - 28) / 2 )) "${c[l]}" "$line" "$R" >&2
        y=$(( y + 1 )); l=$(( l + 1 ))
      done
      printf '\033[%d;%dH%sKokoro text-to-speech%s' $(( y + 1 )) $(( (TW - 21) / 2 )) "$D" "$R" >&2
      y=$(( y + 3 ))
    else
      printf '\033[%d;3H%skokomux%s  %sKokoro text-to-speech%s' "$y" "$ACC" "$R" "$D" "$R" >&2
      y=$(( y + 2 ))
    fi
    local x0=$LM
    printf '\033[%d;%dH%sContinue listening%s' "$y" "$x0" "$B" "$R" >&2; y=$(( y + 1 ))
    if [ "${#marks[@]}" = 0 ]; then
      printf '\033[%d;%dH%sNothing in progress yet. Long texts and books are remembered here.%s' "$y" "$x0" "$D" "$R" >&2
      y=$(( y + 1 ))
    fi
    for i in "${!marks[@]}"; do
      m=${marks[i]}; title="$(mark_get "$m" title)"; part="$(mark_get "$m" part)"; parts="$(mark_get "$m" parts)"
      [ "${parts:-0}" -gt 0 ] 2>/dev/null || parts=1
      pct=$(( 100 * (part - 1) / parts )); bw=12
      printf -v bar '%*s' $(( pct * bw / 100 )) ''; bar="$ACC${bar// /━}$R"
      local rest; printf -v rest '%*s' $(( bw - pct * bw / 100 )) ''; bar+="$D${rest// /─}$R"
      local room=$(( CW - 30 )); [ "${#title}" -gt "$room" ] && title="${title:0:$(( room - 1 ))}…"
      if [ "$i" = "$sel" ]; then row=$'\033[48;5;237m'; else row=""; fi
      bar="${bar//$R/$R$row}"            # keep the selected row's background across the bar
      printf '\033[%d;%dH%s %s%d%s  %-*s %s %s%3d%%%s \033[0m' "$y" "$x0" "$row" "$ACC2" $(( i + 1 )) "$R$row" \
        "$room" "$title" "$bar$row" "$D" "$pct" "$R$row" >&2
      rowact[$y]="mark:$i"; y=$(( y + 1 ))
    done
    y=$(( y + 1 ))
    printf '\033[%d;%dH%sStart something new%s' "$y" "$x0" "$B" "$R" >&2; y=$(( y + 1 ))
    for spec in "c|Read the clipboard|clip" "f|Open a file  ${D}text, EPUB or PDF${R}|file" \
                "u|Read a web page|url" "t|Type something to hear|text" \
                "v|Voice  ${D}$KOKORO_VOICE${R}|voice" "s|Speed  ${D}${KOKORO_SPEED}×${R}|speed"; do
      IFS='|' read -r key r cmd <<< "$spec"
      printf '\033[%d;%dH %s%s%s  %s' "$y" "$x0" "$ACC2" "$key" "$R" "$r" >&2
      rowact[$y]=$cmd; y=$(( y + 1 ))
    done
    if [ "$TW" -ge 64 ]; then r="↑↓ choose · Enter play · click or tap anything · q quit"
    else r="↑↓ · Enter play · tap · q quit"; fi
    printf '\033[%d;3H%s%s%s' "$TH" "$D" "$r" "$R" >&2
    printf '%s' "$SYNC_OFF" >&2

    KEY=""; tick 1; [ -n "$KEY" ] || continue
    cmd=""
    case "$KEY" in
      [1-6]) [ "$(( KEY - 1 ))" -lt "${#marks[@]}" ] && cmd="mark:$(( KEY - 1 ))" ;;
      $'\n'|$'\r') [ "${#marks[@]}" -gt 0 ] && cmd="mark:$sel" ;;
      $'\033[A'|$'\033OA'|k|$'\033[<64;'*M) [ "$sel" -gt 0 ] && sel=$(( sel - 1 )) ;;   # also the mouse wheel
      $'\033[B'|$'\033OB'|j|$'\033[<65;'*M) [ "$sel" -lt $(( ${#marks[@]} - 1 )) ] && sel=$(( sel + 1 )) ;;
      c) cmd="clip" ;; f) cmd="file" ;; u) cmd="url" ;; t) cmd="text" ;; v) cmd="voice" ;; s) cmd="speed" ;;
      q|Q|$'\033') break ;;
      $'\033[<0;'*M) [[ "$KEY" =~ \;([0-9]+)M$ ]] && cmd="${rowact[${BASH_REMATCH[1]}]:-}" ;;
    esac
    case "$cmd" in
      mark:*)  HOME_CHOICE=(--continue "${marks[${cmd#mark:}]}"); break ;;
      clip)    HOME_CHOICE=(-p -c); break ;;
      file)    tui_prompt "File to read:"; [ -n "$ANSWER" ] && { HOME_CHOICE=(-p -f "${ANSWER/#\~/$HOME}"); break; } ;;
      url)     tui_prompt "Web address:" "https://"; [ "${ANSWER:-https://}" != "https://" ] && { HOME_CHOICE=(-p -u "$ANSWER"); break; } ;;
      text)    tui_prompt "Say:"; [ -n "$ANSWER" ] && { HOME_CHOICE=(-p "$ANSWER"); break; } ;;
      voice)   tui_prompt "Voice (see kokoro --list-voices):" "$KOKORO_VOICE"
               if [[ "$ANSWER" =~ ^[a-z]{2}_[a-z]+$ ]]; then KOKORO_VOICE=$ANSWER; tui_save KOKORO_VOICE "$ANSWER"; fi ;;
      speed)   tui_prompt "Speed, 0.5 to 2.0:" "$KOKORO_SPEED"
               if [[ "$ANSWER" =~ ^(0\.[5-9][0-9]*|1(\.[0-9]+)?|2(\.0+)?)$ ]]; then KOKORO_SPEED=$ANSWER; tui_save KOKORO_SPEED "$ANSWER"; fi ;;
    esac
  done
  tui_close
}

tui_save() {     # tui_save NAME VALUE: keep a choice made on the home screen in the settings
  [ -f "$CONF" ] || return 0
  if grep -q "^$1=" "$CONF"; then sed -i "s|^$1=[^ #]*|$1=$2|" "$CONF"; else echo "$1=$2" >> "$CONF"; fi
}
