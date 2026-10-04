#!/bin/bash
# Starts (start) or stops (stop) the application of analysis 895 for one run directory.
#   app.sh start <run-dir>   the H2 file, the control directory and app.log live there
#   app.sh stop  <run-dir>   SIGTERM, then waits up to 60 s for the process to end
set -euo pipefail
HERE=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
RUN=$2
mkdir -p "$RUN/control"
case "$1" in
  start)
    cd "$RUN"
    nohup java -Xmx1g ${A895_JAVA_OPTS:-} -cp "$HERE/target/classes:$(cat "$HERE/target/classpath.txt")" \
      -Da895.data-directory="$RUN" -Da895.control-directory="$RUN/control" \
      io.vanillabp.camunda8.analysis895.ReadModelApplication >> "$RUN/app.log" 2>&1 &
    echo $! > "$RUN/app.pid"
    echo "app started pid $(cat "$RUN/app.pid") $(date +%T)";;
  stop)
    PID=$(cat "$RUN/app.pid")
    kill "$PID" 2>/dev/null || true
    timeout 60 bash -c "while kill -0 $PID 2>/dev/null; do sleep 1; done" || kill -9 "$PID"
    echo "app stopped $(date +%T)";;
esac
