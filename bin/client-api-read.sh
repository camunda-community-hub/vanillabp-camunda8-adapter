#!/usr/bin/env bash
#
# Has a person read this client version for this line? The answer is the entry
# 'line-<line>=<version>' of camunda8-client-read.properties. See decision 86 in the
# repository's DECISIONS.md.
#
# client-api-changes.yaml asks this when a client bump on a GA line adds enum literals or
# abstract interface methods. Without an answer the check stays red, and the pull request
# cannot merge. A person who read the additions raises the entry with a commit on the
# pull request's branch. The check runs again on that commit and asks this again.
#
# The entry counts as read when it names the version or a higher release. Higher is only
# decided between two plain releases such as 8.10.6 and 8.10.7. A pre-release like
# 8.11.0-alpha2 sorts in different ways for different tools, so it counts only when the
# entry names it exactly.
#
# Exit code 0 means read, 1 means not read. Both say on stderr what was read. Exit code 2
# and a sentence on stderr mean the file cannot be read. Nobody may then pick an answer.
#
# Usage:  bin/client-api-read.sh <line> <version> [file]
#
# The file is camunda8-client-read.properties at the root of this repository unless a
# third argument names another one. The 'client-api-changes-selftest' job of
# .github/workflows/checks.yaml holds this against an entry which names the version, one
# which names a higher one, one which names a lower one, a missing entry, a missing file
# and an entry written twice.
#
set -euo pipefail

if [ $# -lt 2 ] || [ $# -gt 3 ]; then
  echo "Usage: $(basename "$0") <line> <version> [file]" >&2
  exit 2
fi

line="$1"
version="$2"
file="${3:-$(dirname "$0")/../camunda8-client-read.properties}"

if [ ! -f "$file" ]; then
  echo "There is no ${file}, so nobody has said that line ${line} was read." >&2
  exit 1
fi

# Only 'line-<line>=<version>'. A comment starts with '#'. Spaces around '=' are allowed.
entries="$(sed -n "s|^[[:space:]]*line-${line//./\\.}[[:space:]]*=[[:space:]]*\([^[:space:]]*\)[[:space:]]*$|\1|p" "$file")"
count="$(printf '%s' "$entries" | grep -c . || true)"

if [ "$count" -gt 1 ]; then
  echo "${file} names line ${line} ${count} times. Which entry counts is nothing this" >&2
  echo "script may pick. Leave one." >&2
  exit 2
fi
if [ "$count" -eq 0 ]; then
  echo "${file} has no entry for line ${line}." >&2
  exit 1
fi

read_version="$entries"
release='^[0-9]+\.[0-9]+\.[0-9]+$'

if [ "$read_version" = "$version" ]; then
  echo "${file} says line ${line} was read at ${read_version}." >&2
  exit 0
fi
if [[ "$read_version" =~ $release ]] && [[ "$version" =~ $release ]] \
  && [ "$(printf '%s\n%s\n' "$version" "$read_version" | sort -V | tail -n 1)" = "$read_version" ]; then
  echo "${file} says line ${line} was read at ${read_version}, which is newer than ${version}." >&2
  exit 0
fi
echo "${file} says line ${line} was read at ${read_version}, not at ${version}." >&2
exit 1
