#!/usr/bin/env bash
# Starts the development backend in the background; writes dev/backend.pid and logs to dev/backend.log.
DIR="$(cd "$(dirname "$0")" && pwd)"
"$DIR/stop.sh" >/dev/null 2>&1 || true
setsid nohup "$DIR/run-backend.sh" > "$DIR/backend.log" 2>&1 < /dev/null &
echo $! > "$DIR/backend.pid"
for i in $(seq 1 60); do
  if curl -s -o /dev/null "http://127.0.0.1:${PE_HTTP_PORT:-8080}/v1/gamification/price-expert/bootstrap"; then echo "backend up (pid $(cat "$DIR/backend.pid"))"; exit 0; fi
  sleep 0.5
done
echo "backend did not start; see $DIR/backend.log"; exit 1
