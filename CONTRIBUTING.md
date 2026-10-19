# Contributing

This repository is the VanillaBP adapter for Camunda 8. It implements the adapter SPI of the
[platform integration](https://github.com/vanillabp/adapter-platform-integration). It talks to a
remote cluster, and that makes it the adapter with the most rules: work arrives at least once, the
cluster learns about an instance only after a delay, and a call may time out although it did not
fail. Business code never sees any of that. An application is written against
[`spi-for-java`](https://github.com/vanillabp/spi-for-java). The
[wiki](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki) describes
everything this adapter does for its users.

Where the rules are: [`README.md`](./README.md) explains how the adapter works and why. Read it
first. [`AGENTS.md`](./AGENTS.md) says how work is done here, written for an agent to read.
[`DECISIONS.md`](./DECISIONS.md) holds the decisions several places rely on. It is the only thing
the code is allowed to cite.

## Getting started

Start from the [development workspace](https://github.com/vanillabp/development-workspace). Clone it
with `git clone --recurse-submodules`. This repository builds on `spi-for-java` and
`adapter-platform-integration`, and the workspace holds all three side by side. The [README of the
workspace](https://github.com/vanillabp/development-workspace#readme) says how to build them in
order without published snapshots, and how to open a pull request, with or without the right to push
here. It also shows how a coding agent can take a bug off your hands. The rest of this file is about
this repository.

## Building and testing

You need a JDK 21 or newer, and Maven. There is no Maven wrapper. The workflows build with the JDK
named in `.github/workflows`, currently 25. Build with that one if you want to see what the pipeline
sees. The class files stay at Java 21 either way, because the property `version.java` in the root
`pom.xml` says so. Two repositories come first, in this order: `spi-for-java`, then
`adapter-platform-integration`. Either build and install them in the workspace (see [Getting
started](#getting-started)), or use their [published snapshots](#snapshots-on-maven-central-and-in-github-packages).
Then build here:

```bash
mvn spotless:apply
mvn install
```

That builds the current GA (general availability) line as `2.0.0-8.10-SNAPSHOT`. Almost every change is
written against this line. Write `install`, not `install verify`. `install` already runs every phase
of `verify`. Naming both runs two lifecycles per module and reports every compiler warning twice.

One source tree serves several Camunda 8 versions. A line is a Maven profile which selects the
pinned client version. Switching to another line always needs `clean`, and so does switching back:

```bash
mvn -Pline-8.8 -Drevision=2.1.0-8.8 clean install
```

Classes compiled against one client are not binary compatible with any other client. So a stale
`target` directory still compiles, and then fails at runtime with a `NoClassDefFoundError`. See
[Release lines](./README.md#release-lines) for which lines are alive and how long.

Two tools read the javadoc here. The compiler compiles every module with
`-Xdoclint:all,-missing`. So it also reads package private classes, and it reads the per-line source
tree of the line you build. The javadoc plugin reads what a release publishes, so it starts at
protected. It shows one thing below protected as well: the fields which a serializable class writes
into its serialized form. That is why a private field of an exception needs a comment too. A broken
`{@link}`, or an HTML tag which current HTML no longer has, fails the build in both places. The
compiler only sees the line it builds. So a change in `src/main/java-line-8.8` or
`src/main/java-line-8.10` is only checked by a build of that line.

A missing comment breaks the build. Everything this repository publishes has a comment now, and the
plugin fails on a warning so that it stays that way. Write the sentence instead of switching the
check off, and write the sentence a reader needs. This is the API an application is built against,
and `@return the value` is the same gap, only longer. The modules which publish nothing do not run
the goal at all. These are `smoke-test`, `election-integration-test` and the two Quarkus test
modules.

The javadoc plugin cannot see what Lombok generates. The plugin reads the source, and Lombok writes
bytecode. So a published comment names a property in words. It does not link a getter which is not
in the file. A published class which gets its constructor from Lombok writes that constructor out.
Otherwise the documentation shows a constructor without parameters, which does not exist.

Two javadoc blocks in a row are a gap which neither tool sees. Javadoc keeps the last block before
an element and silently drops the earlier ones. A comment somebody wrote and kept up to date then
appears nowhere. `bin/check-orphaned-javadoc.sh` finds this case. A block it reports describes
something, usually the element next to it. Move it back there instead of deleting it.

Because of the per-line source trees, this check runs per line. Each line has its own
`Camunda8BusinessId`, `Camunda8JobLease` and `Camunda8CancelListeners`. A build sees only the tree
of the line it selects, so a comment written on one line says nothing about the other two. Build all
three before you call the javadoc clean:

```bash
for line in 8.8 8.9 8.10; do mvn -B -Pline-$line clean package -Dmaven.test.skip=true -DskipITs; done
```

The integration tests start a Camunda 8 cluster in Docker through Testcontainers. They are the
slowest thing in the whole VanillaBP workspace. Where Docker is not available they are skipped, and
a full run silently becomes a small one. So give them Docker before you trust a green build to say
anything about behaviour. `test-coverage-report/coverage-gate` is the last module of the reactor.
It fails below 85 percent of covered instructions per platform, while the rule is 90.

One cluster serves a whole module. A new integration test of `spring-boot` extends
`TestOnTheSharedCluster`. If it boots its application with `@SpringBootTest`, it extends
`SpringBootTestOnTheSharedCluster` instead. Either way it gets the addresses of that one container,
and a cluster which runs nothing: the base class cancels what the previous test class left behind.
Write `@Container` only where the test needs a cluster of its own, for example one configured
differently or one which has never seen its model. Say in the class why.

Deploy all modules or none. The POMs a build publishes belong together, and `-pl` would send only
half of them. The result is a new parent next to old module POMs. Each half is valid on its own, so
nobody notices until a user resolves the artifact and Maven says "The POM is invalid, transitive
dependencies (if any) will not be available". A local build repairs that, but a registry keeps it. The publish workflow always deploys the whole reactor, so this is a mistake
only a person can make.

## Snapshots on Maven Central and in GitHub Packages

If you clone only this repository, Maven takes `spi-for-java` and `adapter-platform-integration`
from the snapshot repository of Maven Central, `https://central.sonatype.com/repository/maven-snapshots/`.
Every push to `main` of those two repositories publishes a snapshot there. The root `pom.xml` names
that repository. Reading it needs no login and no token.

The snapshots of this adapter stay in GitHub Packages, because the namespace
`org.camunda.community.vanillabp` belongs to the Camunda Community Hub. Every push to `main`
publishes one to `https://maven.pkg.github.com/camunda-community-hub/vanillabp-camunda8-adapter`.
Reading it takes a personal access token (classic) with `read:packages` and a few entries in your
`~/.m2/settings.xml`. [Using the published
snapshots](https://github.com/vanillabp/development-workspace#using-the-published-snapshots) shows
both. The old URL `https://maven.pkg.github.com/vanillabp/camunda8-adapter` still answers, but with
an old snapshot which no longer changes.

## What a POM hands an application

A tool which is only needed to compile our source belongs in scope `provided`. Write the scope at
the declaration in the module which uses the tool. Lombok is such a tool, and so is an annotation
processor. An application asked for a workflow engine. Every jar it did not ask for is one more
thing to ship and one more thing to answer a CVE report about.

Writing `<optional>true</optional>` in a `dependencyManagement` does not help. Maven copies the
managed version, scope and exclusions into a dependency, but not the optional flag. So the POM we
publish says nothing at all about that dependency. Lombok reached the runtime classpath of
every application that way, here and in the platform.

## How we write

Most people who read this repository read English as a second language, and so does the maintainer.
Long sentences, rare words and stacked nouns slow them down. Write so that nobody has to read a
sentence twice.

Short main sentences, one thought each. One subordinate clause is enough. Active voice. The common
word instead of the rare one: `use` instead of `leverage`, `about` instead of `regarding`, `so`
instead of `consequently`, `has` instead of `possesses`. A technical term stays a technical term,
but say what it means the first time it turns up, and write an abbreviation out once. If a sentence
trips you up when you read it aloud, rewrite it.

This holds for every English text here, the javadoc and comments, in the main code and in the
tests, the commit message and the pull request included, and for the [wiki](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki)
as well. Nothing a program reads is renamed for the sake of language: type and method names,
configuration keys and artifact coordinates stay as they are, because code in other repositories
points at them. The section [How we write](./AGENTS.md#how-we-write) in `AGENTS.md` has the same
rule with examples from this repository.

## What is asked before the code is written

If a change would make an entry of [`DECISIONS.md`](./DECISIONS.md) untrue, ask before you write it
and wait for the answer. An entry is never deleted. It stays, is marked as superseded and names its
successor. The new decision takes the next free number.

The second question is the SPI (service provider interface). The platform integration publishes the
SPI this adapter implements, and three other adapters implement it as well. So a change to the SPI
is discussed there, not worked around here. An adapter may refuse what its BPMS (business process
management system) cannot do, with a message naming the adapter. That is not the same as bending
the contract.

The third question is about this repository only. The public API of a line is the promise a
release of that line makes. `bin/api-identity.sh` compares the lines against each other. If your
change would make them differ, say so in the pull request and ask before you write it.

## Opening a pull request

Work on a branch of your own and keep one subject per pull request. The description says what
changed and why. It may cite an issue or a conversation, because a pull request records one moment
in time. The code does not.

Before you open it, check the numbers your branch hands out. While you were writing, another branch
may have taken the decision number you used. Once a pull request is merged, a `see decision 7` in a
Java file can no longer be corrected on GitHub:

```bash
bin/check-decision-numbers.sh
```

After you have corrected a number, `bin/check-decision-citations.sh` says whether every citation of
a decision still points at an entry of `DECISIONS.md`. The *Checks* workflow runs it on every pull
request.

Two workflows run on a pull request. *Publish to GitHub Packages* builds and tests the current GA
line. From a branch it publishes nothing. *Checks* runs everything which needs no cluster, the API
identity of the lines included. It also calls the matrix, which builds every release line against
that line's cluster. The matrix takes about forty minutes. So watch it while it runs, and start on a
red line at once. A red preview line does not block your pull request. The job summary of the
matrix says which lines decided the result. Before the matrix runs `orphaned-javadoc-check`. It runs
`bin/check-orphaned-javadoc.sh` and answers in seconds, because a comment which javadoc drops is not
worth forty minutes of waiting. A red check is a finding about your change. Read the log and fix
what it says. Do not push again just to see whether it goes away.

If this is somebody's first pull request in the repository, a maintainer approves the first run of
the workflows. A pull request from a fork builds and tests like any other, because the
snapshots it needs can be read without a login. It cannot publish anything, because GitHub gives it
no secrets of this repository. [What the build of a pull request can
do](https://github.com/vanillabp/development-workspace#what-the-build-of-a-pull-request-can-do) says
more.

This repository lives in the Camunda Community Hub. The Hub asks every contributor to sign its
[contributor license agreement](https://cla-assistant.io/camunda-community-hub/community). On your
first pull request here, the check `license/cla` asks you to sign it. [The license agreement of the
Camunda Community
Hub](https://github.com/vanillabp/development-workspace#the-license-agreement-of-the-camunda-community-hub)
says how.

`main` has a ruleset. It requires eight green checks before a merge:

- `publish`, the build of the current GA line;
- `native-build`, which builds the adapter into a native image and starts it against a real cluster;
- `orphaned-javadoc-check`;
- `api-identity`, which makes sure the public API is the same on every line;
- `line-pins-verified`, which reads the GA lines out of the matrix;
- `renovate-configuration`, which runs the Renovate validator over the configuration of this
  repository and over the shared preset;
- `client-api-changes`, which says on the pull request what a client version bump added;
- `client-api-changes-selftest`, which tests that check against three published client bumps.

While one of them is red, GitHub does not offer the merge.

## License

VanillaBP is published under the [Apache License, Version 2.0](./LICENSE), and by contributing you
agree that your contribution is licensed the same way. [`NOTICE`](./NOTICE) names who holds the
copyright.
