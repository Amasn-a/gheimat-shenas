#!/usr/bin/env bash
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$DIR/backend.pid" ]; then
  PID="$(cat "$DIR/backend.pid")"
  pkill -TERM -g "$PID" 2>/dev/null || kill "$PID" 2>/dev/null || true
  rm -f "$DIR/backend.pid"
fi
