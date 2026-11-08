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
# word's byte range in NNNN.txt). The time is mpv's own playback clock.
# A line taller than the screen is shown as a window of rows that follows the
# lit word, then printed in full when it ends.

# The soft highlight is HL, from the theme: the background moved φ⁻² towards the accent.
blk_lines=(); blk_offs=(); view_top=0; view_h=0
w_s=(); w_e=(); w_a=(); w_b=(); w_state=none
TERM_ROWS=24
in_line=0

part_of() {      # PART="part I of N · " while a job has more than one part; MADE="made 40%" until all is
  local parts=1 made=0
  [ -f "$tmpd/progress" ] && { read -r made parts < "$tmpd/progress" || parts=1; }
  PART=""; [ "${parts:-1}" -gt 1 ] && PART="part $1 of $parts · "
  MADE=""; [ "${parts:-1}" -gt 1 ] && [ ! -f "$tmpd/stream/end" ] && MADE="made $(( made * 100 / parts ))%"
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
    out '\r\033[K  '
    if [ "$a" -lt 0 ] || [ $(( o + len )) -le "$a" ]; then
      out '%s' "$row"                                  # already spoken
    elif [ "$o" -ge "$b" ]; then
      out '%s%s%s' "$D" "$row" "$R"                    # still to come
    else
      x=$(( a > o ? a - o : 0 )); y=$(( b - o < len ? b - o : len ))
      out '%s%s%s%s%s%s' "${row:0:x}" "$HL" "${row:x:y-x}" "$R$D" "${row:y}" "$R"
    fi
    out '\n'
  done
  flush                                       # the whole block in one write
}

block_redraw() { # back up over the visible rows and draw them again, moving the window to the lit word
  local a=$1 k=0
  if [ "$a" -ge 0 ]; then
    while [ $(( k + 1 )) -lt "${#blk_lines[@]}" ] && [ "${blk_offs[k + 1]}" -le "$a" ]; do k=$(( k + 1 )); done
    if [ "$k" -lt "$view_top" ] || [ "$k" -ge $(( view_top + view_h )) ]; then
      view_top=$(( k + view_h > ${#blk_lines[@]} ? ${#blk_lines[@]} - view_h : k ))
    fi
  fi
  out '\r\033[K\033[%dA' "$view_h"
  block_draw "$@"
}

block_finish() { # the whole line as read, nothing lit (it stays on screen as a transcript)
  out '\r\033[K\033[%dA' "$view_h"
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
bookmark_done() {  # read to the end: the bookmark and the book's kept audio go
  [ -n "$book_id" ] && rm -rf "$BOOKS/$book_id.mark" "$BOOKS/$book_id.txt" "${CACHE:?}/${book_id:?}"
  return 0
}

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
  mpv_stop
  if [ -n "$gen_pid" ]; then kill "$gen_pid" 2>/dev/null || true; wait "$gen_pid" 2>/dev/null || true; gen_pid=""; fi
  tmpd="$(mktemp -d "$PREFIX/tmp/kokoro.XXXXXX")"
  for f in in.txt title chapters; do [ -f "$old/$f" ] && cp "$old/$f" "$tmpd/"; done
  rm -rf "$old"
  start=$target; i=$target; was_parts=0          # (a part in this run's own numbering)
  write_job
  run_engine > "$tmpd/engine.log" 2>&1 & gen_pid=$!
  if [ "$ui" = tui ]; then tui_hist=(); TUI_PART=0; TUI_WRAP=(); TUI_OFFS=(); TUI_INFO="$(voice_label "$voice") · ${speed}×"; TUI_SPEED=$speed; tui_draw; fi
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
    menu)                                       # the menu: some choices are the player's to carry out
      [ "$ui" = tui ] || return 0
      tui_menu
      case "$MENU_CMD" in
        settings|voice|chapters) listen_cmd "$MENU_CMD" "$at" ;;
        "") ;;
        *) CMD_PENDING=$MENU_CMD ;;
      esac ;;
    settings|voice)                             # change things mid-listen: a new voice, speed or language
      [ "$ui" = tui ] || return 0              # picks up at this part
      local before after
      before="$(voice_mix)|$KOKORO_SPEED|$KOKORO_LANG"
      if [ "$cmd" = settings ]; then tui_settings; else tui_voices; fi
      after="$(voice_mix)|$KOKORO_SPEED|$KOKORO_LANG"
      if [ "$after" != "$before" ]; then
        voice="$(voice_mix)"; speed=$KOKORO_SPEED; lang=$KOKORO_LANG; JUMP=$at
      fi
      tui_draw ;;
    *) return 1 ;;
  esac
  return 0
}

# ------------------------------------------------------------ mpv
# One mpv for the whole listen, playing the parts as a gapless playlist: each
# part is queued as soon as it's made, so one runs straight into the next.
# kokomux talks to it over its IPC socket through one socat connection, and
# follows its clock (playback-time) and place in the playlist (playlist-pos).

MPV_PID=""; MPV_SOCK=""; MPV_IN=""; MPV_OUT=""; MPV_CARRY=""
MPV_T=0; MPV_POS=-1; MPV_N=0       # seconds into the current part; playlist index; entries queued
MPV_MAX=-1                         # the furthest playlist index reached (parts played while a menu was open)
CMD_PENDING=""                     # a command chosen in the menu, for the player to carry out
declare -A MPV_IDX=()              # part -> its playlist index
MPV_PART=()                        # playlist index -> part

mpv_start() {
  [ -n "$MPV_PID" ] && kill -0 "$MPV_PID" 2>/dev/null && return 0
  MPV_SOCK="$(realpath "$PREFIX/tmp")/kokomux-mpv.$$"   # (a socket's path can't be longer than 107 bytes)
  rm -f "$MPV_SOCK"
  # shellcheck disable=SC2086  # KOKORO_MPV_ARGS: extra mpv options, word by word
  mpv --idle=yes --no-video --no-terminal --really-quiet --gapless-audio=yes --audio-display=no \
      --input-ipc-server="$MPV_SOCK" ${KOKORO_MPV_ARGS:-} </dev/null >/dev/null 2>&1 & MPV_PID=$!
  play_pid=$MPV_PID                # (cleanup stops it)
  local n=0
  while [ ! -S "$MPV_SOCK" ]; do
    n=$(( n + 1 )); [ "$n" -lt 250 ] || die "mpv didn't start." "Try: mpv --version"
    sleep 0.02
  done
  coproc MPVC { exec socat - UNIX-CONNECT:"$MPV_SOCK" 2>/dev/null; }
  MPV_IN=${MPVC[0]} MPV_OUT=${MPVC[1]}
  mpv_send '"observe_property",1,"playback-time"'
  mpv_send '"observe_property",2,"playlist-pos"'
}
mpv_send() { printf '{"command":[%s]}\n' "$1" >&"$MPV_OUT"; }
mpv_pump() {     # take in what mpv has reported since last time
  local line
  while :; do
    if IFS= read -r -t 0.001 -u "$MPV_IN" line; then
      line=$MPV_CARRY$line; MPV_CARRY=""
      case "$line" in
        *'"name":"playback-time"'*) if [[ "$line" =~ \"data\":([0-9.]+) ]]; then MPV_T=${BASH_REMATCH[1]}; fi ;;
        *'"name":"playlist-pos"'*) if [[ "$line" =~ \"data\":(-?[0-9]+) ]]; then
                                     MPV_POS=${BASH_REMATCH[1]}; [ "$MPV_POS" -gt "$MPV_MAX" ] && MPV_MAX=$MPV_POS; fi ;;
      esac
    else
      MPV_CARRY+=$line             # half a line: the rest comes next time
      break
    fi
  done
}
mpv_ms() {       # MPV_T as whole milliseconds, in MS
  local s=${MPV_T%%.*} f=0
  [[ "$MPV_T" == *.* ]] && f=${MPV_T#*.}
  f="${f}000"; MS=$(( 10#${s:-0} * 1000 + 10#${f:0:3} ))
}
mpv_queue() {    # mpv_queue PART WAV: add a part to the playlist (it plays at once if nothing is)
  mpv_send "\"loadfile\",\"$2\",\"append-play\""
  MPV_IDX[$1]=$MPV_N; MPV_PART[MPV_N]=$1; MPV_N=$(( MPV_N + 1 ))
}
mpv_stop() {     # empty the playlist (a jump starts again from a new part)
  [ -n "$MPV_PID" ] && mpv_send '"stop"'
  MPV_IDX=(); MPV_PART=(); MPV_N=0; MPV_POS=-1; MPV_T=0; MPV_MAX=-1
  return 0
}
mpv_quit() {
  [ -n "$MPV_PID" ] && { kill "$MPV_PID" 2>/dev/null; wait "$MPV_PID" 2>/dev/null; }
  [ -n "$MPV_SOCK" ] && rm -f "$MPV_SOCK"
  MPV_PID=""
  return 0
}

player_bg() {    # keep mpv's playlist fed (also from tick, while a menu or settings is open)
  local k f
  mpv_pump
  [ "$MPV_N" -gt 0 ] || return 0
  k=$(( MPV_PART[MPV_N - 1] + 1 ))
  printf -v f '%s/stream/%04d.wav' "$tmpd" "$k"
  if [ -f "$f" ] && ! sleep_due "$k"; then mpv_queue "$k" "$f"; fi   # (not past where the sleep timer stops)
  return 0
}

go_home() {      # stop (keeping the place) and open the home screen
  [ "${KOKORO_QUEUE:-0}" = 1 ] && exit "$QUIT_RC"          # (from the queue: just stop it)
  trap - EXIT; cleanup; exec "$0"
}

play_line() {    # play_line WAV IDX: hear one part; light its words; obey pause / next / again
  local wav=$1 i=$2 base="${1%.wav}" cur=-1 lit=-2 lit_now t label last="" cmd paused=0 started=0 idx k f waited=0
  w_state=pending
  { [ -f "$tmpd/stream/words-off" ] || [ "$show_words" = 0 ]; } && w_state=off
  title_get; part_of "$i"
  bookmark_save "$i"; notify "${PART% · }"
  mpv_start
  [ -n "${MPV_IDX[$i]+x}" ] || mpv_queue "$i" "$wav"
  idx=${MPV_IDX[$i]}
  mpv_pump
  # Already heard (it played on while a menu was open): on to the next.
  if [ "$idx" -lt "$MPV_MAX" ] || { [ "$idx" = "$MPV_MAX" ] && [ "$MPV_POS" = -1 ]; }; then return 0; fi
  TICK_BG=player_bg
  view_part "$i" "$base"
  in_line=1
  echo playing > "$PLAYER_STATE"
  # Nothing in this loop starts a process: it runs ~30 times a second on a phone.
  while :; do
    player_bg                                 # the next part goes in the playlist as soon as it's made
    k=$(( i + 1 ))
    if [ "$MPV_POS" = "$idx" ]; then started=1
    elif [ "$started" = 1 ] || { [ "$MPV_POS" -gt "$idx" ] 2>/dev/null; }; then break   # on to the next, or done
    else waited=$(( waited + 1 )); [ "$waited" -lt 300 ] || break; fi                  # (mpv never got to it)
    cmd=$CMD_PENDING; CMD_PENDING=""         # the menu's choice, kokoro --pause / --next / --again, or a key or click
    if [ -z "$cmd" ] && [ -f "$CONTROL" ]; then read -r cmd < "$CONTROL" || cmd=""; rm -f "$CONTROL"; fi
    if [ -z "$cmd" ] && [ -n "$KEY" ]; then key_cmd; cmd=$CMD; fi
    if [ -n "$cmd" ]; then
      case "$cmd" in
        toggle)
          if [ "$paused" = 1 ]; then mpv_send '"set_property","pause",false'; paused=0; last=""
               echo playing > "$PLAYER_STATE"; notify "${PART% · }"
          else mpv_send '"set_property","pause",true'; paused=1; last=""
               echo paused > "$PLAYER_STATE"; notify "Paused · ${PART% · }"; fi ;;
        next)  if [ -n "${MPV_IDX[$k]+x}" ]; then mpv_send '"playlist-next"'
               else mpv_send '"playlist-next","force"'; fi
               mpv_send '"set_property","pause",false'
               break ;;
        again) mpv_send '"seek",0,"absolute"'; mpv_send '"set_property","pause",false'
               paused=0; cur=-1; lit=-2; last=""; continue ;;
        quit)  exit "$QUIT_RC" ;;                 # the bookmark is kept; cleanup says where
        home)  go_home ;;
        *)     if listen_cmd "$cmd" "$i"; then
                 last=""
                 [ -n "$JUMP" ] && break      # jump_to empties the playlist
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
    if [ "$show_words" = 1 ] && [ "$started" = 1 ]; then
      if [ "$w_state" = pending ]; then words_load "$base.words" || true; fi   # not landed yet
      if [ "$w_state" = ready ]; then
        mpv_ms; t=$(( MS - KOKORO_WORDS_OFFSET_MS ))
        if [ "$t" -lt "${w_s[$(( cur < 0 ? 0 : cur ))]:-0}" ]; then cur=-1; fi      # (after a seek back)
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
    if [ "$ui" = tui ]; then label="$SLEEP_LABEL${SLEEP_LABEL:+${MADE:+ · }}$MADE"
    else label="${PART}${SLEEP_LABEL:+$SLEEP_LABEL · }${MADE:+$MADE · }␣ pause · → next · q stop"; fi
    if [ "$label|playing" != "$last" ]; then view_state "Playing" "$label" "▶"; last="$label|playing"; fi
    tick 0.03
  done
  TICK_BG=""; in_line=0
  view_part_end
}
