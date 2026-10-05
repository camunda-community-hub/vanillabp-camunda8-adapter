#!/usr/bin/env bash
#
# The release-line table of the wiki, written from the POM.
#
# Home.md tells a reader which version to take for their cluster, and three of its four
# columns are client pins. Renovate moves those pins and knows nothing about the wiki, so
# the table went three bumps stale before anybody noticed. Nothing in it is a decision:
# the lines are the line-<line> profiles of the POM, the pin of each is its
# camunda8.version.line-<line> property, the image the tests run is that pin by
# construction (camunda8.cluster.image), and which line is the preview one is what
# bin/line-preview.sh reads. So the table is generated instead of maintained.
#
# The wiki is a repository of its own without pull requests, so a check in a build could
# only report. This writes instead, and .github/workflows/wiki-line-table.yaml pushes what
# it wrote after a pin moved on main.
#
# Usage:  bin/wiki-line-table.sh <home.md> [pom]
#         bin/wiki-line-table.sh --check <home.md> [pom]
#
# Without --check the file is rewritten and the script says whether anything changed.
# With --check nothing is written and exit code 1 means the table and the POM disagree.
# That is also how to bite-test it: move a pin, run --check, and see it named.
#
set -euo pipefail

begin='<!-- line-table: written by bin/wiki-line-table.sh from pom.xml -->'
end='<!-- /line-table -->'

check=false
if [ "${1:-}" = "--check" ]; then
  check=true
  shift
fi

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
  echo "Usage: $(basename "$0") [--check] <home.md> [pom]" >&2
  exit 2
fi

home="$1"
pom="${2:-$(dirname "$0")/../pom.xml}"

for file in "$home" "$pom"; do
  if [ ! -f "$file" ]; then
    echo "There is no ${file}, so the release-line table cannot be written." >&2
    exit 2
  fi
done

if ! grep -qF "$begin" "$home" || ! grep -qF "$end" "$home"; then
  echo "${home} carries no line-table block. Put these two lines around the table:" >&2
  echo "  ${begin}" >&2
  echo "  ${end}" >&2
  exit 2
fi

# The lines in the order a reader climbs them, oldest cluster first.
lines="$(sed -n 's|.*<id>line-\([0-9][^<]*\)</id>.*|\1|p' "$pom" | sort -t. -k1,1n -k2,2n)"
if [ -z "$lines" ]; then
  echo "${pom} defines no line-<line> profile, so there is no table to write." >&2
  exit 2
fi

pin_of() {
  local line="$1"
  local anchor="${1//./\\.}"
  local pin
  pin="$(sed -n "s|.*<camunda8\.version\.line-${anchor}>\([^<]*\)</camunda8\.version\.line-${anchor}>.*|\1|p" "$pom")"
  if [ -z "$pin" ]; then
    echo "Line ${line} has a profile but no camunda8.version.line-${line} in ${pom}." >&2
    exit 2
  fi
  printf '%s\n' "$pin"
}

# What the preview pin calls itself, taken from the pin rather than from a word somebody
# typed once: 8.10.0-alpha5 is an alpha, and the day it reads 8.10.0-rc1 the table says rc.
qualifier_of() {
  local pin="$1"
  local tail="${pin##*-}"
  if [ "$tail" = "$pin" ]; then
    echo "preview"
    return
  fi
  printf '%s\n' "${tail%%[0-9]*}"
}

# The newest GA line serves every cluster above it, the older ones serve their own minor.
newest_ga=""
for line in $lines; do
  if [ "$("$(dirname "$0")/line-preview.sh" "$line" "$pom")" != "true" ]; then
    newest_ga="$line"
  fi
done

rows=""
for line in $lines; do
  pin="$(pin_of "$line")"
  preview="$("$(dirname "$0")/line-preview.sh" "$line" "$pom")"
  if [ "$preview" = "true" ]; then
    qualifier="$(qualifier_of "$pin")"
    cluster="${line} ${qualifier}"
    version="\`2.x.y-${line}-${qualifier}<n>\`"
  else
    version="\`2.x.y-${line}\`"
    if [ "$line" = "$newest_ga" ]; then
      cluster="${line}.x and above"
    else
      cluster="${line}.x"
    fi
  fi
  rows="${rows}| ${cluster} | ${version} | \`${pin}\` | \`camunda/camunda:${pin}\` |"$'\n'
done

table="${begin}
| Your cluster | Version to use | Client it is built against | Cluster the tests ran against |
|---|---|---|---|
${rows}${end}"

written="$(mktemp)"
trap 'rm -f "$written"' EXIT
awk -v begin="$begin" -v end="$end" -v table="$table" '
  $0 == begin { print table; inside = 1; next }
  $0 == end { inside = 0; next }
  !inside { print }
' "$home" > "$written"

if cmp -s "$home" "$written"; then
  echo "${home} already says what ${pom} pins."
  exit 0
fi

if [ "$check" = true ]; then
  echo "${home} and ${pom} disagree about the release lines:" >&2
  diff -u "$home" "$written" >&2 || true
  echo "Run 'bin/wiki-line-table.sh ${home}' and push the wiki." >&2
  exit 1
fi

cat "$written" > "$home"
echo "Wrote the release-line table of ${home} from ${pom}."
