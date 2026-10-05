#!/usr/bin/env bash
#
# The VanillaBP-facing API of every release line has to be IDENTICAL. A user
# must never read the version suffix of an artifact to find out which methods exist; where
# a line's cluster cannot do something, the method is still there and degrades with a
# guiding message.
#
# This script proves it. It builds the given lines one after the other and compares the
# public API of every JAR, taken from the class files with javap. It fails when a line
# gains or loses a type, a method or a field, which is the mistake that would otherwise
# reach users as "works on 8.10, missing on 8.8".
#
# Usage:  bin/api-identity.sh [line-id ...]        (default: every live line)
#
set -euo pipefail

cd "$(dirname "$0")/.."

lines=("$@")
if [ ${#lines[@]} -eq 0 ]; then
  # the live lines are the line-<number> profiles of the parent POM, so this cannot fall
  # behind the build; the digit keeps the build-helper execution ids out
  mapfile -t lines < <(sed -n 's|.*<id>line-\([0-9][^<]*\)</id>.*|\1|p' pom.xml)
fi
if [ ${#lines[@]} -lt 2 ]; then
  echo "Needs at least two lines to compare, got: ${lines[*]-none}" >&2
  exit 1
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

dump_api() {
  local jar="$1" out="$2"
  local classes="$work/classes"
  rm -rf "$classes"
  mkdir -p "$classes"
  # a JAR without classes has no API to compare, and unzip says so with exit code 11
  unzip -q -o "$jar" -d "$classes" '*.class' || true
  # -public only, and sorted, so the dump depends on the API and on nothing else
  find "$classes" -name '*.class' -print0 \
    | sort -z \
    | xargs -0 -r javap -public \
    | grep -v '^Compiled from' \
    > "$out"
}

for line in "${lines[@]}"; do
  echo "== building line ${line}"
  # CLEAN, always: classes compiled against another line's client stay binary
  # compatible with nothing. A method inherited in one client and declared in the
  # other is called through the owner the compiler saw, so a stale target/ turns into
  # a NoClassDefFoundError at runtime instead of a compile error.
  mvn --batch-mode --no-transfer-progress -q \
    "-Pline-${line}" "-Drevision=0.0.0-${line}-api-identity" -DskipTests clean package
  mkdir -p "$work/$line"
  # only the JARs a module builds itself, '/target/<name>.jar' and nothing nested below
  # it: the Quarkus applications of the test modules put a runner and a copy of every
  # dependency into target/quarkus-app, and none of that is an API of this repository
  while IFS= read -r jar; do
    module="$(basename "$(dirname "$(dirname "$jar")")")"
    dump_api "$jar" "$work/$line/$module.api"
  done < <(find . -path ./target -prune -o -name '*.jar' -path '*/target/*' -print \
    | grep -E '/target/[^/]+\.jar$' \
    | grep -v -- '-sources\|-javadoc\|original-')
done

reference="${lines[0]}"
status=0
for line in "${lines[@]:1}"; do
  echo "== comparing line ${line} against line ${reference}"
  if ! diff -u "$work/$reference" "$work/$line" --recursive; then
    echo "The public API of line ${line} differs from line ${reference}." >&2
    status=1
  fi
done

if [ $status -eq 0 ]; then
  echo "The public API is identical on the lines: ${lines[*]}"
else
  echo >&2
  echo "A line may only differ in what it does, never in what it offers. Either move the" >&2
  echo "code out of the per-line source directory, or add the same signature to every" >&2
  echo "line and let it degrade with a guiding message on the cluster which cannot do it." >&2
fi
exit $status
