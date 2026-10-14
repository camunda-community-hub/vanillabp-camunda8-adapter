#!/usr/bin/env bash
#
# What a failed job of GitHub Actions says about why it failed, in at most thirty lines. The
# issue of a red night quotes it, so the reader learns the reason without opening the log.
#
# The step which broke first is the one quoted. A job runs its 'if: always()' steps after a
# failure as well, and one of them can fail in turn, but the first break is the cause.
#
# - A Maven step: Maven's own [ERROR] summary, without the lines Maven prints after every
#   failure about how to run it again.
# - Any other step: its last lines before the runner's '##[error]', without the script GitHub
#   echoes in front of every 'run' step. A shell check says why it failed in plain text, and
#   until story 1492 such a step left the excerpt empty: line 8.10 of
#   businesscockpit-camunda8-adapter went red on 2026-10-13 over three lines which named the
#   cluster and the client, and the issue quoted none of them.
#
# The log comes in either form GitHub hands out. 'gh run view --job <id> --log-failed' puts
# the job and the step in front of every line, and the step is 'UNKNOWN STEP' for some jobs,
# so the whole job arrives rather than one step. 'gh api .../actions/jobs/<id>/logs' gives the
# whole job with a timestamp in front of every line. Both are reduced to what was printed.
#
# Prints the excerpt, or nothing when the log holds no reason. Exit code 0 either way: a
# missing quote must not stop the issue from being written.
#
# Usage:  bin/job-log-excerpt.sh [log]          (reads stdin without a file)
#         bin/job-log-excerpt.sh --self-test
#
# The self test runs in the 'job-log-excerpt-selftest' job of .github/workflows/checks.yaml.
# The workflows of the Business Cockpit repositories check this repository out to call the
# script, so one reading of a job log serves all of them. See decision 79 in the repository's
# DECISIONS.md.
#
set -euo pipefail

# How many lines an issue quotes. More than thirty and the reason drowns in the noise around it.
max_lines=30

# Only what the step printed. The BOM sits in front of the first line of each step, the colours
# come from the script echo and from tools which think they write to a terminal.
plain() {
  LC_ALL=C sed -E \
    -e 's/^\xEF\xBB\xBF//' \
    -e '/^[0-9]{4}-[0-9]{2}-[0-9]{2}T/! s/^[^\t]*\t[^\t]*\t//' \
    -e 's/^\xEF\xBB\xBF//' \
    -e 's/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z ?//' \
    -e 's/\x1B\[[0-9;]*[A-Za-z]//g'
}

# The lines of the step which broke first: from its '##[group]Run' header up to the runner's
# error for it. A job which timed out or was cancelled ends in another '##[error]', which is
# taken when no step reported an exit code. A log with no error at all comes back whole.
failed_step() {
  awk '
    { line[NR] = $0 }
    /^##\[group\]Run / && !end { start = NR }
    /^##\[error\]Process completed with exit code/ && !end { end = NR }
    /^##\[error\]/ && !other { other = NR }
    END {
      if (!end) end = other
      if (!end) { start = 1; end = NR }
      if (!start || start > end) start = 1
      for (i = start; i <= end; i++) print line[i]
    }
  '
}

# Maven names its own failures. These lines repeat after every failure and say nothing about
# this one.
maven_summary() {
  grep -aE '^\[ERROR\]' \
    | grep -avE '^\[ERROR\][[:space:]]*$|-> \[Help|\[Help [0-9]+\] http|To see the full stack trace|Re-run Maven using|For more information about the errors|After correcting the problems|mvn <args> -rf|See dump files|See /home/runner/' \
    | head -"$max_lines" || true
}

# What a step printed after GitHub's echo of it. The echo of a 'run' step ends with the first
# '##[endgroup]', and everything after it is the step's own output. The runner's markers are
# not output, so they are dropped.
step_output() {
  awk '
    !header_done && /^##\[endgroup\]/ { header_done = 1; n = 0; next }
    /^##\[/ { next }
    { n++; kept[n] = $0 }
    END { for (i = 1; i <= n; i++) print kept[i] }
  ' | tail -"$max_lines"
}

excerpt() {
  local step summary
  step=$(plain | failed_step)
  summary=$(printf '%s\n' "$step" | maven_summary)
  if [ -n "$summary" ]; then
    printf '%s\n' "$summary"
    return
  fi
  printf '%s\n' "$step" | step_output | sed '/./,$!d'
}

self_test() {
  local got failures=0
  # global, because the EXIT trap runs after this function has returned
  playground=$(mktemp -d)
  trap 'rm -rf "$playground"' EXIT

  # A Maven step which failed, as the API hands it out, and an 'if: always()' step after it
  # which failed too. The excerpt is Maven's summary, and nothing of the later step.
  cat > "$playground/maven.log" <<'LOG'
2026-10-09T03:06:47.8908858Z ##[group]Run mvn -B clean install
2026-10-09T03:06:47.8909000Z mvn -B clean install
2026-10-09T03:06:47.8949572Z ##[endgroup]
2026-10-09T03:11:37.0000000Z [INFO] Building core
2026-10-09T03:11:37.1000000Z [ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0
2026-10-09T03:11:37.2000000Z [ERROR]
2026-10-09T03:11:37.3000000Z [ERROR] BUILD FAILURE
2026-10-09T03:11:37.4000000Z [ERROR] Re-run Maven using the -X switch to enable full debug logging.
2026-10-09T03:11:37.5453707Z ##[error]Process completed with exit code 1.
2026-10-09T03:11:38.7573488Z ##[group]Run echo later
2026-10-09T03:11:38.7614394Z ##[endgroup]
2026-10-09T03:11:38.7700000Z a later step which failed as well
2026-10-09T03:11:38.7800000Z ##[error]Process completed with exit code 1.
LOG
  printf '%s\n' \
    '[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0' \
    '[ERROR] BUILD FAILURE' > "$playground/maven.expected"

  # A shell check which failed, as 'gh run view --log-failed' hands it out, with the job and
  # the step in front, the BOM and the colours of the echoed script. The step before it ran
  # Maven, which said [ERROR] about something it survived. Modelled on line 8.10 of
  # businesscockpit-camunda8-adapter on 2026-10-13.
  {
    printf 'line (8.10)\tBuild\t\xEF\xBB\xBF2026-10-13T04:00:00.0000000Z ##[group]Run mvn -B install\n'
    printf 'line (8.10)\tBuild\t2026-10-13T04:00:00.1000000Z ##[endgroup]\n'
    printf 'line (8.10)\tBuild\t2026-10-13T04:00:01.0000000Z [ERROR] something Maven survived\n'
    printf 'line (8.10)\tBuild\t2026-10-13T04:00:02.0000000Z [INFO] BUILD SUCCESS\n'
    printf 'line (8.10)\tThe cluster\t\xEF\xBB\xBF2026-10-13T04:01:08.9687315Z ##[group]Run set -euo pipefail\n'
    printf 'line (8.10)\tThe cluster\t2026-10-13T04:01:08.9687680Z \x1B[36;1mset -euo pipefail\x1B[0m\n'
    printf 'line (8.10)\tThe cluster\t2026-10-13T04:01:08.9691515Z \x1B[36;1m  echo "the reason" >&2\x1B[0m\n'
    printf 'line (8.10)\tThe cluster\t2026-10-13T04:01:08.9756803Z shell: /usr/bin/bash -e {0}\n'
    printf 'line (8.10)\tThe cluster\t2026-10-13T04:01:08.9759371Z ##[endgroup]\n'
    printf 'line (8.10)\tThe cluster\t2026-10-13T04:01:08.9866772Z camunda8.line=8.10\n'
    printf 'line (8.10)\tThe cluster\t2026-10-13T04:01:08.9899306Z Line 8.10 ran camunda/camunda:8.10.1 against the client 8.10.0.\n'
    printf 'line (8.10)\tThe cluster\t2026-10-13T04:01:08.9900038Z raise camunda8.version.line-8.10 in pom.xml.\n'
    printf 'line (8.10)\tThe cluster\t2026-10-13T04:01:08.9911688Z ##[error]Process completed with exit code 1.\n'
  } > "$playground/shell.log"
  printf '%s\n' \
    'camunda8.line=8.10' \
    'Line 8.10 ran camunda/camunda:8.10.1 against the client 8.10.0.' \
    'raise camunda8.version.line-8.10 in pom.xml.' > "$playground/shell.expected"

  # A step which printed more than an issue should quote: the last thirty lines.
  {
    echo '2026-10-13T04:01:00.0000000Z ##[group]Run ./check.sh'
    echo '2026-10-13T04:01:00.0000000Z ##[endgroup]'
    for i in $(seq 1 40); do echo "2026-10-13T04:01:01.0000000Z output $i"; done
    echo '2026-10-13T04:01:02.0000000Z ##[error]Process completed with exit code 2.'
  } > "$playground/long.log"
  for i in $(seq 11 40); do echo "output $i"; done > "$playground/long.expected"

  # A log in which nothing failed has nothing to quote.
  printf '%s\n' \
    '2026-10-13T04:01:00.0000000Z ##[group]Run true' \
    '2026-10-13T04:01:00.0000000Z ##[endgroup]' > "$playground/quiet.log"
  : > "$playground/quiet.expected"

  for case in maven shell long quiet; do
    got=$(excerpt < "$playground/$case.log")
    if [ "$got" = "$(cat "$playground/$case.expected")" ]; then
      echo "job-log-excerpt: the $case log gives the excerpt it should"
    else
      echo "job-log-excerpt: the $case log gives a wrong excerpt. Expected:" >&2
      cat "$playground/$case.expected" >&2
      echo "and got:" >&2
      printf '%s\n' "$got" >&2
      failures=$((failures + 1))
    fi
  done
  [ "$failures" -eq 0 ]
}

case "${1:-}" in
  --self-test)
    self_test
    ;;
  "")
    excerpt
    ;;
  *)
    excerpt < "$1"
    ;;
esac
