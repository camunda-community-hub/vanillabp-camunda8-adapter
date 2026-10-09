# Contributing

This repository is the VanillaBP adapter for Camunda 8. It implements the adapter SPI of the
[platform integration](https://github.com/vanillabp/adapter-platform-integration) and talks to a
remote cluster, which is what makes it the adapter with the most rules: work arrives at least once,
the cluster learns about an instance eventually, and a call may time out without having failed.
Business code never sees any of that. What an application writes against is
[`spi-for-java`](https://github.com/vanillabp/spi-for-java), and everything this adapter does for
its users is described in the [wiki](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki).

Where the rules are: [`README.md`](./README.md) explains how the adapter works and why, and it is
the first thing to read. [`AGENTS.md`](./AGENTS.md) says how work is done here, in the form an agent
reads. [`DECISIONS.md`](./DECISIONS.md) holds the decisions several places rely on, and it is the
only thing the code is allowed to cite.

## Getting started

Start from the [development workspace](https://github.com/vanillabp/development-workspace). Clone it
with `git clone --recurse-submodules`. This repository builds on `spi-for-java` and
`adapter-platform-integration`, and the workspace holds all three side by side. The [README of the
workspace](https://github.com/vanillabp/development-workspace#readme) says how to build them in
order without published snapshots, and how to open a pull request, with or without the right to push
here. It also shows how a coding agent can take a bug off your hands. The rest of this file is about
this repository.

## Building and testing

A JDK 21 or newer, and Maven, without a wrapper. The workflows build with the JDK named in
`.github/workflows`, currently 25, so build with that one if you want to see what the pipeline sees.
The class files stay at Java 21 either way, because that is what the property `version.java` in the
root `pom.xml` compiles against. Two repositories come first, in this order: `spi-for-java`, then
`adapter-platform-integration`. Build and install them in the workspace, see [Getting
started](#getting-started), or read their [published snapshots](#snapshots-on-maven-central-and-in-github-packages).
Then, here:

```bash
mvn spotless:apply
mvn install
```

That builds the current GA line as `2.0.0-SNAPSHOT`, which is the line almost every change is
written against. `install` and not `install verify`: `install` already runs every phase `verify`
has, so naming both walks two lifecycles per module and reports every compiler warning twice.

One source tree serves several Camunda 8 versions, and a line is a Maven profile selecting the
client pin. Building another line always needs `clean`, and so does coming back:

```bash
mvn -Pline-8.8 -Drevision=2.1.0-8.8 clean install
```

Classes compiled against one client are binary compatible with no other one, so a stale `target`
directory does not fail to compile, it fails at runtime with a `NoClassDefFoundError`. See
[Release lines](./README.md#release-lines) for which lines are alive and how long.

Two tools read the javadoc here. The compiler compiles every module with
`-Xdoclint:all,-missing`, so it reads a package private class as well, and it reads the per-line
source tree of the line you build. The javadoc plugin reads what a release publishes and therefore
starts at protected. One thing below protected is shown as well: the fields a serializable class
carries into its serialized form, which is why a private field of an exception is asked for a
comment too. A broken `{@link}` or a tag HTML no longer knows fails the build in either place.
Because the compiler only sees the line it builds, a change in `src/main/java-line-8.8` or
`src/main/java-line-8.10` is only checked by a build of that line.

A comment which is missing breaks the build. Everything this repository publishes has one now, and
the plugin fails on a warning so that it stays that way. Write the sentence rather than switching
the check off, and write the one a reader needs: this is the API an application is built against,
and `@return the value` is the same gap in a longer form. The modules which publish nothing -
`smoke-test`, `election-integration-test` and the two Quarkus test modules - do not run the goal at
all.

One thing the javadoc plugin cannot see is what Lombok generates, because it reads the source and
Lombok writes bytecode. So a published comment names a property in words rather than linking a
getter which is not in the file, and a published class which takes its constructor from Lombok
writes that constructor out, because the documentation otherwise shows a parameterless one which
does not exist.

Two javadoc blocks in a row are the gap neither tool sees. Javadoc keeps the last block before an
element and drops the earlier ones without a word, so a comment somebody wrote and kept up to date
appears nowhere. `bin/check-orphaned-javadoc.sh` finds that shape. A block it reports describes
something, usually the element next door, so hang it back there rather than delete it.

The per-line source trees make this a check per line. Each line carries its own
`Camunda8BusinessId`, `Camunda8JobLease` and `Camunda8CancelListeners`, and a build sees the tree of
the line it selects, so a comment written on one line says nothing about the other two. Build all
three before you call the javadoc clean:

```bash
for line in 8.8 8.9 8.10; do mvn -B -Pline-$line clean package -Dmaven.test.skip=true -DskipITs; done
```

The integration tests start a Camunda 8 cluster in Docker through Testcontainers. They are the
slowest thing in the whole VanillaBP workspace, and they are skipped where Docker is not available,
which quietly turns a full run into a small one. Give them the Docker they need before you read a
green build as an answer about behaviour. `test-coverage-report/coverage-gate` is the last module of
the reactor and fails below 85 percent of covered instructions per platform, while the rule is 90.

One cluster serves a whole module. A new integration test of `spring-boot` extends
`TestOnTheSharedCluster`, or `SpringBootTestOnTheSharedCluster` where it boots its application with
`@SpringBootTest`, and gets the addresses of that one container plus a cluster which runs nothing:
the base class cancels what the class before it left behind. Write `@Container` only where the test
needs a cluster of its own, one configured differently or one which has never seen its model, and
say in the class why.

Deploying is all modules or none. The POMs a build publishes belong together, and `-pl` would
send half of them: a parent in its new form next to module POMs from before it, each half valid
on its own, so nobody sees it until a user resolves the artifact and Maven says "The POM is
invalid, transitive dependencies (if any) will not be available". A local build repairs that, a
registry keeps it. The publish workflow always deploys the whole reactor, so this is a mistake
only a person can make.

## Snapshots on Maven Central and in GitHub Packages

If you clone only this repository, Maven takes `spi-for-java` and `adapter-platform-integration`
from the snapshot repository of Maven Central, `https://central.sonatype.com/repository/maven-snapshots/`.
Every push to `main` of those two repositories publishes a snapshot there. The root `pom.xml` names
that repository, and reading it needs no login and no token.

The snapshots of this adapter stay in GitHub Packages, because the namespace
`org.camunda.community.vanillabp` belongs to the Camunda Community Hub. Every push to `main`
publishes one to `https://maven.pkg.github.com/camunda-community-hub/vanillabp-camunda8-adapter`.
Reading it takes a personal access token (classic) with `read:packages` and a few entries in your
`~/.m2/settings.xml`. [Using the published
snapshots](https://github.com/vanillabp/development-workspace#using-the-published-snapshots) shows
both. The old URL `https://maven.pkg.github.com/vanillabp/camunda8-adapter` still answers, but with
an old snapshot which no longer changes.

## What a POM hands an application

A tool which only translates our source belongs in scope `provided`, and the scope stands at the
declaration in the module which uses the tool. Lombok is such a tool, an annotation processor is
another. An application asked for a workflow engine, and every jar it did not ask for is one more
thing to ship and to answer a CVE report about.

Writing `<optional>true</optional>` in a `dependencyManagement` does not do it. Maven copies a
managed version, scope and exclusions into a dependency and leaves the optional flag behind, so the
POM we publish says nothing at all about that dependency. Lombok reached the runtime classpath of
every application that way, here and in the platform.

## How we write

Most people who read this repository read English as a second language, and so does the maintainer.
Long sentences, rare words and stacked nouns slow them down. Write so that nobody has to read a
sentence twice.

Short main sentences, one thought each. One subordinate clause is enough. Active voice. The common
word instead of the rare one: `use` instead of `leverage`, `about` instead of `regarding`, `so`
instead of `consequently`. A technical term stays a technical term, but say what it means the first
time it turns up, and write an abbreviation out once. If a sentence trips you up when you read it
aloud, rewrite it.

This holds for every English text here, the javadoc, the commit message and the pull request
included. Nothing a program reads is renamed for the sake of language: type and method names,
configuration keys and artifact coordinates stay as they are, because code in other repositories
points at them.

## What is asked before the code is written

Where a change would make an entry of [`DECISIONS.md`](./DECISIONS.md) untrue, ask before you write
it and wait for the answer. An entry is never edited away: it stays, marked as superseded and
naming its successor, and the new decision takes the next free number.

The second question is the SPI. What this adapter implements is published by the platform
integration and served by three other adapters as well, so a change to the SPI is discussed there
and not worked around here. An adapter may refuse what its BPMS cannot do, with a message naming the
adapter, and that is a different thing from bending the contract.

The third question belongs to this repository alone. The public API of a line is the promise a
release of that line makes, and `bin/api-identity.sh` compares the lines against each other. Where
your change would make them differ, say so in the pull request and ask before you write it.

## Opening a pull request

Work on a branch of your own and keep one subject per pull request. The description says what moved
and why it had to. It may cite an issue or a conversation, because it is a record of a moment
itself, which the code is not.

Check the numbers your branch hands out before you open it. Another branch may have taken the
decision number you used while you were writing, and once a pull request is merged a
`see decision 7` in a Java file can no longer be corrected on GitHub:

```bash
bin/check-decision-numbers.sh
```

Two workflows answer a pull request. *Publish to GitHub Packages* builds and tests the current GA
line and publishes nothing from a branch. *Checks* runs what needs no cluster, the API identity of
the lines among it, and it calls the matrix which builds every release line against that line's
cluster. The matrix takes about forty minutes, so watch it while it runs and start on a red line at
once. A red preview line does not block your pull request, and the job summary of the matrix says
which lines did decide it. In front of it stands `orphaned-javadoc-check`, which runs `bin/check-orphaned-javadoc.sh`
and answers in seconds, because a comment which javadoc drops is not worth forty minutes of
waiting. A red check is a finding about your change. Read the log and fix what it says rather than
pushing again to see whether it goes away.

A maintainer approves the first run of the workflows for somebody whose first pull request this is
in the repository. A pull request from a fork builds and tests like any other, because the
snapshots it needs can be read without a login. It cannot publish anything, because GitHub gives it
no secrets of this repository. [What the build of a pull request can
do](https://github.com/vanillabp/development-workspace#what-the-build-of-a-pull-request-can-do) says
more.

This repository lives in the Camunda Community Hub, which asks every contributor to sign its
[contributor license agreement](https://cla-assistant.io/camunda-community-hub/community). On your
first pull request here, the check `license/cla` asks you to sign it. [The license agreement of the
Camunda Community
Hub](https://github.com/vanillabp/development-workspace#the-license-agreement-of-the-camunda-community-hub)
says how.

`main` carries a ruleset, and it requires eight green checks before a merge: `publish`, the build of
the current GA line; `native-build`, which builds the adapter into a native image and starts it
against a real cluster; `orphaned-javadoc-check`; `api-identity`, which holds the public API the
same on every line; `line-pins-verified`, which reads the GA lines out of the matrix;
`renovate-configuration`, which runs the Renovate validator over the configuration of this
repository and over the shared preset; `client-api-changes`, which says on the pull request what a
client bump added; and `client-api-changes-selftest`, which holds that check against three
published client bumps. While one of them is red, GitHub does not offer the merge.

## License

VanillaBP is published under the [Apache License, Version 2.0](./LICENSE), and by contributing you
agree that your contribution is licensed the same way. [`NOTICE`](./NOTICE) names who holds the
copyright.
