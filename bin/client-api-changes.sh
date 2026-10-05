#!/usr/bin/env bash
#
# Camunda does not count a new enum literal or a new interface method as a breaking change,
# so neither appears in their release notes, not even between two patches of one line. Both
# break us: a new literal walks past every comparison against a single literal and past
# every exhaustive switch, and a new interface method breaks anything implementing that
# interface by hand. Our client pins are raised by Renovate and a patch inside a line merges
# itself, so this is the one class of change which would otherwise arrive unread.
#
# This script reads it out of the JARs instead of out of the release notes. It resolves the
# io.camunda artifacts the pinned client brings on the compile classpath, for the old and
# for the new version, takes their public API from the class files with javap the way
# bin/api-identity.sh compares the lines, and reports what the newer side added:
#
#   - every enum literal the older side does not have
#   - every abstract interface method the older side does not have (a default or a static
#     method is added too, and it breaks nobody, so it stays out)
#
# Output is Markdown, meant to be posted on the pull request which raises the pin. The exit
# code says what was found: 0 for nothing, 1 for at least one addition. Anything else is the
# script itself failing.
#
# It needs Maven Central and nothing else - no project, no credentials - because it works on
# the published artifacts rather than on this repository.
#
# Usage:  bin/client-api-changes.sh <groupId:artifactId> <old-version> <new-version>
#
# Held against two additions Camunda shipped inside a line, both of which it has to report:
#   8.8.32 -> 8.8.33 adds the enum ProcessDefinitionState and ProcessDefinition.getState()
#   8.8.3  -> 8.8.4  adds BatchOperationState.FAILED
# The 'client-api-changes-selftest' job of .github/workflows/checks.yaml runs both on every
# pull request, because a check which reports nothing looks exactly like a quiet week.
#
set -euo pipefail

if [ $# -ne 3 ]; then
  echo "Usage: $(basename "$0") <groupId:artifactId> <old-version> <new-version>" >&2
  exit 2
fi

coordinates="$1"
old_version="$2"
new_version="$3"
group="${coordinates%%:*}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Everything of the artifact's own group which one version puts on the compile classpath,
# as a sorted list of enum literals and abstract interface methods. A throwaway project is
# what resolves it, so both sides are read the same way and neither is guessed from the
# other.
dump_api() {
  local version="$1" out="$2"
  local project="$work/project/$version"

  mkdir -p "$project"
  cat > "$project/pom.xml" <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.vanillabp.tools</groupId>
  <artifactId>client-api-changes</artifactId>
  <version>0</version>
  <packaging>pom</packaging>
  <dependencies>
    <dependency>
      <groupId>${coordinates%%:*}</groupId>
      <artifactId>${coordinates##*:}</artifactId>
      <version>${version}</version>
    </dependency>
  </dependencies>
</project>
EOF

  ( cd "$project" && mvn --batch-mode --no-transfer-progress -q \
      org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
      "-DincludeGroupIds=${group}" \
      -DincludeScope=compile \
      -DoutputDirectory=jars )

  local classes="$work/classes"
  rm -rf "$classes"
  mkdir -p "$classes"
  local jar
  for jar in "$project"/jars/*.jar; do
    unzip -q -o "$jar" -d "$classes" '*.class'
  done

  # one javap over everything, sorted, so the dump depends on the API and on nothing else
  find "$classes" -name '*.class' -print0 \
    | sort -z \
    | xargs -0 -r javap -public \
    | awk '
        # javap prints a header per class file, then the members, then a closing brace
        /^Compiled from/ { next }
        / (class|interface) [a-zA-Z_$.]+/ && /\{[ ]*$/ {
          name = $0
          sub(/^.* (class|interface) /, "", name)
          sub(/[<{ ].*$/, "", name)
          kind = ""
          if ($0 ~ / extends java\.lang\.Enum</) { kind = "enum" }
          else if ($0 ~ / interface /) { kind = "interface" }
          next
        }
        # an enum literal is the only static final field whose type is the enum itself
        kind == "enum" && /^  public static final / && $0 ~ (" " name " ") {
          literal = $NF
          sub(/;$/, "", literal)
          print "literal\t" name "\t" literal
          next
        }
        # a default or a static method of an interface breaks no implementation of it
        kind == "interface" && /^  public abstract / {
          member = $0
          sub(/^ +/, "", member)
          sub(/^public abstract /, "", member)
          sub(/;$/, "", member)
          print "method\t" name "\t" member
          next
        }
      ' \
    | sort -u \
    > "$out"
}

echo "Reading ${group} of ${coordinates}:${old_version} ..." >&2
dump_api "$old_version" "$work/old.txt"
echo "Reading ${group} of ${coordinates}:${new_version} ..." >&2
dump_api "$new_version" "$work/new.txt"

comm -13 "$work/old.txt" "$work/new.txt" > "$work/added.txt"

added_literals="$(grep -c '^literal' "$work/added.txt" || true)"
added_methods="$(grep -c '^method' "$work/added.txt" || true)"

echo "### \`${coordinates}\` ${old_version} → ${new_version}"
echo

if [ "$added_literals" -eq 0 ] && [ "$added_methods" -eq 0 ]; then
  echo "No new enum literal and no new abstract interface method in \`${group}\`. The two"
  echo "changes Camunda ships without calling them breaking are not in this bump."
  exit 0
fi

echo "Camunda added members it does not call breaking and which break us. Read every one of"
echo "them before this is merged."

if [ "$added_literals" -gt 0 ]; then
  echo
  echo "**New enum literals (${added_literals})**"
  echo
  echo '```'
  awk -F'\t' '$1 == "literal" { print $2 "." $3 }' "$work/added.txt"
  echo '```'
  echo
  echo "A literal nobody here knows reaches every comparison against a single literal and"
  echo "every \`switch\`. Check that each one lands on the harmless side, the way"
  echo "\`Camunda8UnknownClientEnumsTest\` describes it."
fi

if [ "$added_methods" -gt 0 ]; then
  echo
  echo "**New abstract interface methods (${added_methods})**"
  echo
  echo '```'
  awk -F'\t' '$1 == "method" { print $2 ": " $3 }' "$work/added.txt"
  echo '```'
  echo
  echo "Anything implementing one of these interfaces by hand stops compiling, a test double"
  echo "included. A Mockito mock does not, which is why we write no such double by hand."
fi

exit 1
