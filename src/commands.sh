# kokomux: the admin commands. Sourced by `kokoro` (bash).
# shellcheck shell=bash disable=SC2034,SC2153,SC2154  # shares its globals with kokoro
#
#   kokoro --help, --status, --settings, --config, --list-voices
#   kokoro --add, --queue [list|skip|clear]      the queue: shared links and files, in order
#   kokoro --continue [MARK]                     carry on with a bookmark (the last one)
#   kokoro --serve, --benchmark, --update, --uninstall
#
# Each cmd_* function takes the whole command line ($1 is the option itself)
# and exits; `kokoro` calls the one its first argument names (admin_cmd).
#
# Sections: help, the queue, bookmarks, the server, keeping kokomux up to date.
#
# Expects from kokoro: the settings, the paths (CONF BOOKS QUEUE QUEUE_DIR
# CACHE SRV_DIR PLAYER_STATE), the colours, die/info/done_msg/status,
# server_up/start_server/stop_server, control, stop_playback, mark_get, and
# from tui.sh conf_set, tui_settings.

admin_cmd() {    # admin_cmd ARGS...: run the admin command ARGS names (returns 1 if it's not one)
  case "${1:-}" in
    -h|--help) usage; exit 0 ;;
    --version) echo "kokomux $KOKOMUX_VERSION"; exit 0 ;;
    --stop) if stop_playback; then done_msg "Stopped."; else info "Nothing is playing."; fi; exit 0 ;;
    --pause)
      if [ "$(cat "$PLAYER_STATE" 2>/dev/null)" = paused ]; then control toggle "Playing again."
      else control toggle "Paused."; fi
      exit 0 ;;
    --next) control next "Skipping to the next part."; exit 0 ;;
    --again) control again "Playing this part again."; exit 0 ;;
    --add) cmd_add "$@" ;;
    --queue) cmd_queue "$@" ;;
    --continue) cmd_continue "$@" ;;
    --serve) cmd_serve ;;
    --stop-server) stop_server; exit 0 ;;
    --benchmark) cmd_benchmark ;;
    --status) cmd_status ;;
    --settings) screen_run tui_settings || exec "$0" --status; exit 0 ;;
    --config)
      [ -f "$CONF" ] || die "There's no settings file at $(tilde "$CONF")." "Run the installer again to create it."
      exec "${EDITOR:-nano}" "$CONF" ;;
    --update) cmd_update "$@" ;;
    --uninstall) cmd_uninstall ;;
    --list-voices) cmd_list_voices ;;
  esac
  return 1
}

screen_run() {   # screen_run FUNC: run a full-screen FUNC with keys on, giving the terminal back however it ends (1: no terminal)
  [ "$LIVE" = 1 ] && [ -t 0 ] || return 1
  keys_on
  # However it ends, give back taps and typing (a tap opens Termux's keyboard again).
  trap 'tui_close; keys_off' EXIT
  trap 'exit 130' INT TERM HUP
  "$1"
  keys_off; trap - EXIT INT TERM HUP
  return 0
}

# ------------------------------------------------------------ help

# Option rows "flags|what it does": two columns on wide screens, stacked on
# narrow ones (phones in portrait).
opt_rows() {     # (reads "flags|what it does" lines)
  local w o d
  w="$(cols)"
  while IFS='|' read -r o d; do
    if [ "$w" -ge 72 ]; then printf '  %s%-18s%s %s\n' "$ACC2" "$o" "$R" "$d"
    else printf '  %s%s%s\n      %s%s%s\n' "$ACC2" "$o" "$R" "$D" "$d" "$R"; fi
  done
}

usage() {        # kokoro --help
  printf '%skokoro%s %sv%s%s\n' "$B" "$R" "$ACC" "$KOKOMUX_VERSION" "$R"
  printf '%sKokoro text-to-speech for Termux%s\n\n' "$D" "$R"
  printf '%sUsage%s kokoro [options] [text]\n' "$B" "$R"
  printf '%sText can also come from -f, -c, -u or a pipe.%s\n' "$D" "$R"
  printf '\n%sSpeak%s\n' "$B" "$R"
  opt_rows <<'ROWS'
-p, --play|play as it's made
-o FILE|save as .wav .mp3 .ogg .flac, or .m4b (an audiobook)
-v VOICE|voice, e.g. af_heart, or a blend: af_heart:70+af_bella:30
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
--add -u URL / -f FILE|add to the queue (Share to Termux does this)
--queue [list|skip|clear]|play the queue in order
--restart|with -p: start from the top, not the bookmark
ROWS
  printf '\n%sMore%s\n' "$B" "$R"
  opt_rows <<'ROWS'
--list-voices|all 54 voices by language
--status|server status and settings
--settings|voice, speed, highlighting and more
--config|edit the settings file directly
--serve / --stop-server|start or stop the server
--benchmark|time Kokoro on this phone and keep the fastest way
--update [BRANCH]|update kokomux and Kokoro (from main, or a branch)
--uninstall|remove kokomux
ROWS
  printf '\n%sExamples%s\n' "$B" "$R"
  printf '  kokoro -p "Hello there"\n  kokoro -c -p\n  kokoro -u URL -o page.mp3\n'
  printf '\n%sWithout -p, audio is saved to %s.%s\n' "$D" "$KOKORO_OUT" "$R"
}

cmd_status() {   # kokoro --status: the server, the settings and the last bookmark
  local idle last where
  printf '%skokomux%s %sv%s%s\n\n' "$B" "$R" "$ACC" "$KOKOMUX_VERSION" "$R"
  if server_up; then
    printf '  %s●%s Server    %srunning%s %s(port %s)%s\n' "$GRN" "$R" "$GRN" "$R" "$D" "$(server_port)" "$R"
  elif [ "$KOKORO_AUTOSERVE" = 1 ]; then
    printf '  %s○%s Server    sleeping %s(wakes on next use)%s\n' "$D" "$R" "$D" "$R"
  else
    printf '  %s○%s Server    off %s(KOKORO_AUTOSERVE=0)%s\n' "$D" "$R" "$D" "$R"
  fi
  idle="${KOKORO_IDLE_MINUTES} min"; [ "$KOKORO_IDLE_MINUTES" = 0 ] && idle="never"
  printf '    Voice     %s%s%s\n' "$ACC2" "$(voice_mix)" "$R"
  printf '    Speed     %s\n' "$KOKORO_SPEED"
  printf '    Language  %s\n' "$KOKORO_LANG"
  printf '    Idle stop %s\n' "$idle"
  last="$(ls -t "$BOOKS"/*.mark 2>/dev/null | head -n 1)" || true
  if [ -n "$last" ]; then
    where="part $(mark_get "$last" part) of $(mark_get "$last" parts)"
    [ "$(mark_get "$last" kind)" = novel ] && where="$(mark_get "$last" chapter)"
    if [[ "$(mark_get "$last" left)" =~ ^[0-9]+$ ]]; then dur_label "$(mark_get "$last" left)"; where+=", $REPLY left"; fi
    printf '    Bookmark  %s %s(%s · kokoro --continue)%s\n' "$(mark_get "$last" title)" "$D" "$where" "$R"
  fi
  printf '    Settings  %s%s%s\n' "$D" "$(tilde "$CONF")" "$R"
  printf '    %sChange them with: kokoro --settings%s\n' "$D" "$R"
  exit 0
}

cmd_list_voices() {   # kokoro --list-voices: every voice, by language
  local flags errf out
  status "Loading voices"
  flags=(--width "$(cols)" --default "$KOKORO_VOICE")
  [ "$LIVE" = 1 ] && [ -t 1 ] && flags+=(--color --accent "$ACC_SGR" --muted "$D_SGR")
  errf="$(mktemp "$PREFIX/tmp/kokoro-err.XXXXXX")"
  out="$(proot-distro login "$DISTRO" -- "$PY" "$ENGINE" voices "${flags[@]}" 2>"$errf")" ||
    die "Couldn't list the voices." "$(tail -n 1 "$errf"; rm -f "$errf")"
  rm -f "$errf"
  status_clear
  printf '%s\n' "$out"
  exit 0
}

# ------------------------------------------------------------ the queue
# $QUEUE: one item per line, "id<TAB>kind<TAB>source<TAB>title"; kind is url
# or file (a copy kept in $QUEUE_DIR, deleted when it leaves the queue).

queue_drop() {   # queue_drop ID KIND SOURCE: take an item out of the queue
  [ "$2" = file ] && rm -f "$3"
  sed -i "/^$1\t/d" "$QUEUE"
  return 0
}

cmd_add() {      # kokoro --add [-u URL | -f FILE | -c | TEXT]: to the queue, to hear later
  shift
  local id kind="file" src title ext n
  mkdir -p "$QUEUE_DIR"
  id="$(date +%s%N | sha256sum | cut -c1-12)"
  case "${1:-}" in
    -u|--url) [[ "${2:-}" =~ ^https?:// ]] || die "That doesn't look like a web address."
              kind=url src=$2 title=$2 ;;
    -f|--file) [ -f "${2:-}" ] || die "There's no file called \"${2:-}\"."
               ext="${2##*.}"; [ "$ext" = "$2" ] && ext=txt
               src="$QUEUE_DIR/$id.${ext,,}"; cp "$2" "$src"; title="$(basename "$2")" ;;
    -c|--clipboard) command -v termux-clipboard-get >/dev/null || die "Reading the clipboard needs Termux:API."
               src="$QUEUE_DIR/$id.txt"; timeout 6 termux-clipboard-get > "$src" || die "Couldn't read the clipboard."
               title="$(snippet "$src")" ;;
    "") die "What should go in the queue?" "kokoro --add -u URL, -f FILE, -c, or some text" ;;
    *) src="$QUEUE_DIR/$id.txt"; printf '%s\n' "$*" > "$src"; title="$(snippet "$src")" ;;
  esac
  # A copied link on its own is that page.
  if [ "$kind" = file ] && [ "${src##*.}" = txt ] && is_link "$src"; then
    kind=url title="$(tr -d '[:space:]' < "$src")"; rm -f "$src"; src=$title
  fi
  printf '%s\t%s\t%s\t%s\n' "$id" "$kind" "$src" "${title//$'\t'/ }" >> "$QUEUE"
  n=$(wc -l < "$QUEUE")
  done_msg "Added to your queue" "$n waiting · kokoro --queue plays it"
  command -v termux-toast >/dev/null && { timeout 5 termux-toast "kokoro: added to your queue ($n)" >/dev/null 2>&1 & }
  exit 0
}

cmd_queue() {    # kokoro --queue [list|skip|clear]: play the queue in order
  local id kind src title n args rc
  case "${2:-}" in
    list) [ -s "$QUEUE" ] || { info "Your queue is empty."; exit 0; }
          n=0; while IFS=$'\t' read -r _ _ _ title; do n=$((n + 1)); printf '  %s%2d%s  %s\n' "$ACC2" "$n" "$R" "$title"; done < "$QUEUE"
          exit 0 ;;
    clear) if [ -f "$QUEUE" ]; then
             while IFS=$'\t' read -r _ kind src _; do [ "$kind" = file ] && rm -f "$src"; done < "$QUEUE"
           fi
           rm -f "$QUEUE"; done_msg "Queue cleared."; exit 0 ;;
    skip) [ -s "$QUEUE" ] || { info "Your queue is empty."; exit 0; }
          IFS=$'\t' read -r id kind src title < "$QUEUE"
          queue_drop "$id" "$kind" "$src"; done_msg "Dropped \"$title\"."; exit 0 ;;
    "") ;;
    *) die "kokoro --queue plays the queue; --queue list shows it; --queue skip drops the first; --queue clear empties it." ;;
  esac
  [ -s "$QUEUE" ] || { info "Your queue is empty." ; printf '  %sShare a link or file to Termux, or: kokoro --add -u URL%s\n' "$D" "$R" >&2; exit 0; }
  while [ -s "$QUEUE" ]; do
    IFS=$'\t' read -r id kind src title < "$QUEUE"
    if [ "$kind" = url ]; then args=(-u "$src"); else args=(-f "$src"); fi
    rc=0; KOKORO_QUEUE=1 "$0" -p "${args[@]}" || rc=$?
    case "$rc" in
      0) ;;                                              # heard to the end
      10|130) break ;;                                   # stopped: it stays first in the queue
      *) info "Couldn't read \"$title\", so the queue stopped there."
         printf '  %skokoro --queue skip drops it.%s\n' "$D" "$R" >&2; break ;;
    esac
    queue_drop "$id" "$kind" "$src"
  done
  exit 0
}

# ------------------------------------------------------------ the bookshelf
# A book in progress is a bookmark, $BOOKS/ID.mark (key=value lines: part,
# parts, title, kind, source, voice, speed, lang; a novel's also chapter,
# number, novel), with ID.txt beside it for a text, and its made audio in
# $CACHE/ID. A book heard to the end is a line in $FINISHED.

shelf_load() {   # SHELF = the bookmarks, the one heard most recently first
  SHELF=()
  [ -d "$BOOKS" ] || return 0
  mapfile -t SHELF < <(ls -t "$BOOKS"/*.mark 2>/dev/null) || true
  return 0
}

shelf_remove() { # shelf_remove MARK: take a book off the shelf, with its kept text and audio
  local id
  id="$(basename "$1" .mark)"
  [[ "$id" =~ ^[0-9a-f]+$ ]] || return 0
  rm -rf "$1" "$BOOKS/$id.txt" "${CACHE:?}/$id"
  return 0
}

finished_add() { # finished_add MARK: remember a book heard to the end (newest last)
  local k vals=()
  mkdir -p "${FINISHED%/*}"
  for k in title kind source voice speed lang; do vals+=("$(mark_get "$1" "$k" | tr '\t' ' ')"); done
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date +%Y-%m-%d)" "${vals[@]}" >> "$FINISHED"
}

# ------------------------------------------------------------ bookmarks

cmd_continue() { # kokoro --continue [MARK]: carry on with a bookmark (the last one), in its voice and speed
  local last=${2:-} src
  case "$last" in "$BOOKS"/*.mark) [ -f "$last" ] || last="" ;; *) last="" ;; esac
  [ -n "$last" ] || last="$(ls -t "$BOOKS"/*.mark 2>/dev/null | head -n 1)" || true
  [ -n "$last" ] || die "There's nothing to continue." "A bookmark is kept when you stop a long text partway."
  case "$(mark_get "$last" kind)" in
    url|novel) src=(-u "$(mark_get "$last" source)") ;;
    text) src=(-f "${last%.mark}.txt") ;;
    *)    src=(-f "$(mark_get "$last" source)")
          [ -f "${src[1]}" ] || die "Can't find \"${src[1]}\" any more." "Has the file moved? Open it again with kokoro -p -f." ;;
  esac
  exec "$0" -p -v "$(mark_get "$last" voice)" -s "$(mark_get "$last" speed)" -l "$(mark_get "$last" lang)" "${src[@]}"
}

# ------------------------------------------------------------ the server

cmd_serve() {    # kokoro --serve: start the background server now
  local stays
  if server_up; then info "The server is already running."; exit 0; fi
  start_server || exit 1
  stays="stays loaded"; [ "$KOKORO_IDLE_MINUTES" = 0 ] || stays="stops after ${KOKORO_IDLE_MINUTES} min unused"
  done_msg "Server ready." "port $(server_port) · $stays"
  exit 0
}

cmd_benchmark() {   # kokoro --benchmark: time each way of running Kokoro here, and keep the fastest
  local best="" rows=() tag threads block secs speed label
  server_up && stop_server >/dev/null 2>&1           # (it would share the cores)
  info "Timing Kokoro on this phone, a few ways (about a minute)."
  while read -r tag threads block secs speed; do
    case "$tag" in
      @@bench) if [ "$threads" = 0 ]; then label="its own choice"; else label="$threads threads"; fi
               [ "$block" = 0 ] || label+=", block $block"
               rows+=("$(printf '  %-26s %s× real time' "$label" "$speed")")
               status "Timing" "$label: ${speed}× real time" ;;
      @@best) best="$threads $block $secs" ;;           # (secs is the speed here)
    esac
  done < <(proot-distro login "$DISTRO" -- "$PY" "$ENGINE" bench 2>/dev/null)
  status_clear
  [ -n "$best" ] || die "The benchmark didn't finish."
  printf '%s\n' "${rows[@]}" >&2
  read -r threads block speed <<< "$best"
  conf_set KOKORO_THREADS "$threads"; conf_set KOKORO_BLOCK "$block"
  if [ "$threads" = 0 ]; then label="threads chosen by onnxruntime"; else label="$threads threads"; fi
  [ "$block" = 0 ] || label+=", block $block"
  done_msg "Kokoro makes speech ${speed}× faster than it's spoken here." "Kept: $label"
  exit 0
}

# ------------------------------------------------------------ keeping kokomux up to date

cmd_update() {   # kokoro --update [BRANCH]: the installer from GitHub (main, or that branch)
  local branch=${2:-main} tmp new rc=0
  [[ "$branch" =~ ^[A-Za-z0-9._/-]+$ ]] || die "\"$branch\" isn't a branch name."
  tmp="$(mktemp "$PREFIX/tmp/kokomux-install.XXXXXX")"
  curl -fsSL "${KOKORO_REPO_RAW%/main}/$branch/install.sh" -o "$tmp" 2>/dev/null ||
    { rm -f "$tmp"; die "Couldn't download the installer from the $branch branch."; }
  new="$(sed -n 's/^KOKOMUX_VERSION="\(.*\)"$/\1/p' "$tmp" | head -n 1)"
  # Never go backwards by accident: main can be behind the version installed from a branch.
  if [ -z "${2:-}" ] && [ "$(printf '%s\n%s\n' "$new" "$KOKOMUX_VERSION" | sort -V | head -n 1)" = "$new" ] &&
     [ "$new" != "$KOKOMUX_VERSION" ]; then
    rm -f "$tmp"
    die "main has v$new, older than this v$KOKOMUX_VERSION." "To install from a branch: kokoro --update BRANCH"
  fi
  server_up && stop_server >/dev/null 2>&1
  bash "$tmp" || rc=$?; rm -f "$tmp"; exit "$rc"
}

cmd_uninstall() {   # kokoro --uninstall: the container, the command, settings, bookmarks and shortcuts
  local ans f
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
  rm -rf "$CONF_DIR" "$SRV_DIR" "$HOME/.cache/kokomux" "$HOME/.local/share/kokomux" "$PREFIX/share/kokomux" "$CACHE"
  done_msg "Settings removed"
  rm -f "$0"
  done_msg "kokomux is uninstalled." "Thanks for trying it!"
  exit 0
}
