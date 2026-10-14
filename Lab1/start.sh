#!/usr/bin/env sh
# Starts the system in Docker for a demo (macOS / Linux / Git Bash on Windows):
#   - broker + publisher web UI (http://localhost:3000), opened in the browser
#   - a terminal window with the broker's log
# then turns this terminal into a prompt where you create receivers while everything runs:
#   add <name> <topics>   e.g.  add carol weather   or   add dave news,sports,weather
#   stop <name>, list, help, quit
# Each receiver opens in its own terminal window.
#
# If no terminal window can be opened (e.g. over SSH), or with --no-windows, the broker and the
# receivers run in the background and their logs appear in this terminal, prefixed with their name.
#
# Usage:  sh Lab1/start.sh                    build, then start
#         sh Lab1/start.sh --skip-build       start without rebuilding
#         sh Lab1/start.sh --demo-receivers   also start the demo receivers below
#         sh Lab1/start.sh --no-windows       everything in this terminal
# (options can be combined)

cd "$(dirname "$0")" || exit 1
UI_URL=http://localhost:3000

# Started only with --demo-receivers, as <name>:<comma-separated topics>.
DEMO_RECEIVERS="alice:news,sports bob:news carol:weather dave:news,sports,weather"

SKIP_BUILD=false
USE_WINDOWS=true
START_DEMO=false
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=true ;;
    --no-windows) USE_WINDOWS=false ;;
    --demo-receivers) START_DEMO=true ;;
    *) echo "Unknown option: $arg"; exit 1 ;;
  esac
done

# Every receiver container is named pad-receiver-<name>, so they can be found without keeping a list.
receiver_containers() { docker ps -a --filter 'name=^pad-receiver-' --format '{{.Names}}' 2>/dev/null; }
remove_all_receivers() { for c in $(receiver_containers); do docker rm -f "$c" >/dev/null 2>&1; done; }
prefix() { while IFS= read -r line; do printf '%s | %s\n' "$1" "$line"; done; }

# Opens a terminal window titled $1 that runs $2. With a third argument (a hint), the command can
# be stopped with Ctrl+C and started again in that window. Returns 1 if no terminal is available.
open_window() {
  title=$1
  command=$2
  hint=$3
  case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*)
      command -v powershell.exe >/dev/null 2>&1 || return 1
      # Ctrl+C ends a running PowerShell script, so instead of a loop the window defines a
      # Start-Receiver function that restarts the receiver when typed.
      script="\$Host.UI.RawUI.WindowTitle = '$title'
Set-Location '$(pwd -W)'
function Start-Receiver { $command }
Write-Host '$hint' -ForegroundColor DarkGray
Start-Receiver"
      # -EncodedCommand (base64 UTF-16LE) avoids every quoting problem between sh and PowerShell.
      encoded=$(printf '%s' "$script" | iconv -f UTF-8 -t UTF-16LE | base64 | tr -d '\n')
      powershell.exe -NoProfile -Command "Start-Process powershell -ArgumentList '-NoExit','-NoProfile','-EncodedCommand','$encoded'" >/dev/null 2>&1
      return $?
      ;;
  esac

  # macOS and Linux: an sh loop. The trap keeps the loop alive on Ctrl+C while the command itself stops.
  loop="cd '$PWD'; echo '$hint'; trap : INT; while true; do $command; echo; printf 'Stopped. Press Enter to start it again. '; read -r _; done"
  if [ "$(uname -s)" = "Darwin" ]; then
    osascript -e "tell application \"Terminal\" to do script \"$loop\"" >/dev/null 2>&1
    return $?
  fi
  [ -n "$DISPLAY" ] || [ -n "$WAYLAND_DISPLAY" ] || return 1
  if command -v gnome-terminal >/dev/null 2>&1; then gnome-terminal --title="$title" -- sh -c "$loop" >/dev/null 2>&1
  elif command -v konsole >/dev/null 2>&1; then konsole -e sh -c "$loop" >/dev/null 2>&1 &
  elif command -v xfce4-terminal >/dev/null 2>&1; then xfce4-terminal --title="$title" -x sh -c "$loop" >/dev/null 2>&1 &
  elif command -v x-terminal-emulator >/dev/null 2>&1; then x-terminal-emulator -e sh -c "$loop" >/dev/null 2>&1 &
  elif command -v xterm >/dev/null 2>&1; then xterm -T "$title" -e sh -c "$loop" >/dev/null 2>&1 &
  else return 1
  fi
}

restart_hint() {
  case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) echo "type Start-Receiver and press Enter" ;;
    *) echo "press Enter" ;;
  esac
}

add_receiver() {
  name=$1
  topics=$2
  if [ -z "$name" ] || [ -z "$topics" ]; then
    echo "Usage: add <name> <topics>    e.g.  add carol weather   or   add dave news,sports,weather"; return
  fi
  if ! echo "$name" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9_.-]*$'; then
    echo "Invalid name '$name': use letters, digits, '.', '_' or '-'."; return
  fi
  if ! echo "$topics" | grep -Eq '^[A-Za-z0-9_.-]+(,[A-Za-z0-9_.-]+)*$'; then
    echo "Invalid topics '$topics': separate topics with commas, e.g. news,sports."; return
  fi
  if receiver_containers | grep -qx "pad-receiver-$name"; then
    echo "Receiver '$name' is already running. Use 'stop $name' first."; return
  fi
  pretty=$(echo "$topics" | sed 's/,/, /g')
  if [ "$USE_WINDOWS" = true ]; then
    hint="Receiver $name ($pretty). Ctrl+C stops it. To start it again (missed messages are replayed), $(restart_hint)."
    if open_window "Receiver $name" "docker compose run --rm --name pad-receiver-$name receiver -t '$topics' -c $name -w 1" "$hint"; then
      echo "Started receiver '$name' on [$pretty] in a new window."
      return
    fi
    echo "Could not open a terminal window; receivers will run in the background, with logs in this terminal."
    USE_WINDOWS=false
  fi
  docker compose run -d --name "pad-receiver-$name" receiver -t "$topics" -c "$name" -w 1 >/dev/null || return
  docker logs -f "pad-receiver-$name" 2>&1 | prefix "$(printf '%-6s' "$name")" &
  echo "Started receiver '$name' on [$pretty]; its log lines start with '$name |'."
}

stop_receiver() {
  name=$1
  if [ -z "$name" ]; then echo "Usage: stop <name>"; return; fi
  if ! receiver_containers | grep -qx "pad-receiver-$name"; then
    echo "No running receiver named '$name'. Type 'list' to see them."; return
  fi
  # docker stop sends SIGTERM, so the receiver finishes and ACKs what it already received.
  docker stop "pad-receiver-$name" >/dev/null 2>&1
  docker rm "pad-receiver-$name" >/dev/null 2>&1
  echo "Stopped receiver '$name'. The broker keeps its messages until it comes back with the same name."
}

list_receivers() {
  rows=$(docker ps --filter 'name=^pad-receiver-' --no-trunc --format '{{.Names}}|{{.Command}}' 2>/dev/null)
  if [ -z "$rows" ]; then echo "No receivers running. Create one with: add <name> <topics>"; return; fi
  echo "$rows" | while IFS='|' read -r container command; do
    topics=$(echo "$command" | sed -n "s/.*-t '*\([^ '\"]*\).*/\1/p" | sed 's/,/, /g')
    printf '  %-12s %s\n' "${container#pad-receiver-}" "$topics"
  done
}

show_help() {
  echo "Commands:"
  echo "  add <name> <topics>   create a receiver, e.g.  add carol weather   or   add dave news,sports,weather"
  echo "                        (reusing a name gets the messages that receiver missed while it was stopped)"
  echo "  stop <name>           stop a receiver"
  echo "  list                  show running receivers"
  echo "  quit                  stop everything and exit"
}

cleanup() {
  echo
  echo "Stopping receivers, broker and publisher UI..."
  remove_all_receivers
  docker compose --profile clients down --remove-orphans
  [ "$USE_WINDOWS" = true ] && echo "Stopped. You can close the receiver and log windows."
  exit 0
}

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running. Start Docker Desktop (or the Docker daemon) and try again."
  exit 1
fi

if [ "$SKIP_BUILD" = false ]; then
  echo "Building images (the first build takes a few minutes)..."
  docker compose --profile clients build || exit 1
fi

trap cleanup INT TERM
remove_all_receivers
docker compose up -d broker publisher-ui || exit 1

i=0
until curl -fs "$UI_URL" >/dev/null 2>&1 || [ $i -ge 30 ]; do i=$((i + 1)); sleep 1; done

if [ "$USE_WINDOWS" = true ] && ! open_window "Broker log" "docker compose logs -f broker" "Broker log. Closing this window does not stop the broker."; then
  echo "Could not open a terminal window; logs will appear in this terminal instead."
  USE_WINDOWS=false
fi
if [ "$USE_WINDOWS" = false ]; then
  docker compose logs -f --no-log-prefix broker 2>&1 | prefix "broker" &
fi

if command -v open >/dev/null 2>&1; then open "$UI_URL"
elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$UI_URL" >/dev/null 2>&1
elif command -v cmd.exe >/dev/null 2>&1; then cmd.exe //c start "" "$UI_URL"
fi

echo
echo "Everything is running:"
echo "  Publisher UI   $UI_URL"
if [ "$USE_WINDOWS" = true ]; then
  echo "  Broker         localhost:5000  (log in the \"Broker log\" window)"
else
  echo "  Broker         localhost:5000  (log lines below start with 'broker |')"
fi
echo
if [ "$START_DEMO" = true ]; then
  for entry in $DEMO_RECEIVERS; do add_receiver "${entry%%:*}" "${entry#*:}"; done
  echo
fi
show_help
echo

while printf '> '; IFS= read -r line; do
  set -f
  # shellcheck disable=SC2086
  set -- $line
  set +f
  verb=$(echo "$1" | tr '[:upper:]' '[:lower:]')
  case "$verb" in
    "") ;;
    add) name=$2; shift 2 2>/dev/null || shift $#; add_receiver "$name" "$(echo "$*" | tr -d ' ')" ;;
    stop) stop_receiver "$2" ;;
    list) list_receivers ;;
    help) show_help ;;
    quit | exit) break ;;
    *) echo "Unknown command '$1'."; show_help ;;
  esac
done
cleanup
