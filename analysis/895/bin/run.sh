#!/bin/bash
# One measurement of analysis 895.
#
#   run.sh <disturbance> <run-dir> [disturbance-seconds]
#
# disturbance: storage-stop (the secondary storage answers with an error), storage-pause (it
# does not answer at all) or export-pause (the exporter stands, the storage answers from an
# old state). The run builds a fresh cluster, lets the application run under load, takes the
# read model away for the given time (default 720 s, longer than every patience the adapter
# and the core have built in), restarts the application in the middle of it, gives the read
# model back, keeps starting for two more minutes, lets everything drain and then runs the
# check. RESTART=no leaves the application running through the disturbance. Every step is written to <run-dir>/timeline.txt.
set -uo pipefail
HERE=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
DISTURBANCE=$1
RUN=$2
DURATION=${3:-720}
BASELINE=${BASELINE:-120}
AFTER=${AFTER:-120}
DRAIN=${DRAIN:-900}
mkdir -p "$RUN"
T="$RUN/timeline.txt"
mark() { echo "$(date +%s%3N) $(date +%T) $*" | tee -a "$T"; }
sample() {
  # one sample every 30 s, bounded to the longest run this script makes
  for _ in $(seq 1 240); do
    PID=$(cat "$RUN/app.pid" 2>/dev/null)
    THREADS=$(ls /proc/$PID/task 2>/dev/null | wc -l)
    RSS=$(awk '/VmRSS/{print $2}' /proc/$PID/status 2>/dev/null)
    C8=$(docker stats --no-stream --format '{{.MemUsage}} {{.CPUPerc}}' a895-camunda 2>/dev/null)
    echo "$(date +%T) app-threads=$THREADS app-rss-kb=$RSS camunda=$C8" >> "$RUN/samples.txt"
    sleep 30
  done
}
case "$DISTURBANCE" in
  storage-stop|storage-pause) BACK=storage-back;;
  export-pause) BACK=export-resume;;
  *) echo "unknown disturbance $DISTURBANCE"; exit 2;;
esac
"$HERE/bin/cluster.sh" down >/dev/null
"$HERE/bin/cluster.sh" up
mark "cluster up"
"$HERE/bin/app.sh" start "$RUN"
sample & SAMPLER=$!
mark "app started, load running"
sleep "$BASELINE"
"$HERE/bin/cluster.sh" "$DISTURBANCE"
mark "DISTURBANCE-BEGIN $DISTURBANCE"
sleep $((DURATION / 2))
if [ "${RESTART:-yes}" = "yes" ]; then
  "$HERE/bin/app.sh" stop "$RUN"
  mark "app stopped during the disturbance"
  "$HERE/bin/app.sh" start "$RUN"
  mark "app started during the disturbance"
fi
sleep $((DURATION - DURATION / 2))
"$HERE/bin/cluster.sh" "$BACK"
mark "DISTURBANCE-END $BACK"
sleep "$AFTER"
touch "$RUN/control/starts-off"
mark "starts switched off, draining"
sleep "$DRAIN"
"$HERE/bin/app.sh" stop "$RUN"
kill $SAMPLER
mark "app stopped, checking"
FROM=$(grep DISTURBANCE-BEGIN "$T" | cut -d' ' -f1)
TO=$(grep DISTURBANCE-END "$T" | cut -d' ' -f1)
java -cp "$HERE/target/classes:$(cat "$HERE/target/classpath.txt")" io.vanillabp.camunda8.analysis895.Check895 \
  "$RUN/a895" http://localhost:18080 "$FROM" "$TO" 2>&1 | grep -v "^SLF4J" > "$RUN/check.txt"
docker logs a895-camunda > "$RUN/camunda.log" 2>&1
mark "=== RUN DONE"
