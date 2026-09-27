![Header](./readme/vanillabp-headline.png)

# VanillaBP adapter for Camunda 8

[![](https://img.shields.io/badge/Lifecycle-Incubating-blue)](https://github.com/Camunda-Community-Hub/community/blob/main/extension-lifecycle.md#incubating-)
[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

This is the [VanillaBP](https://www.vanillabp.io) adapter for
[Camunda 8](https://camunda.com/platform/) (Version 2). It lets a VanillaBP business
application run its workflows on a Camunda 8 cluster without the business code depending
on the Camunda API.

Developers who want to **use** this adapter should refer to the
[Wiki](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki); the VanillaBP concepts it builds
on are documented in the [VanillaBP Wiki](https://github.com/vanillabp/adapter-platform-integration/wiki). This
`README.md` is aimed at contributors.

## Status

**Feature-complete against the VanillaBP 2 adapter SPI.** The adapter connects to a
Camunda 8 cluster, deploys each workflow module's BPMN on startup, starts workflows
through the two-phase outbox (see [Behavior](#behavior)), executes `@WorkflowTask`
methods through polling job workers, completes and cancels asynchronous tasks, serves
user tasks incl. their lifecycle notifications, correlates messages, pushes the
aggregate's shared attributes as process variables and answers the viewer/history API.

What this adapter cannot deliver is listed under [Known deviations](#known-deviations),
`cancelUserTask` being the most prominent one. Everything that cannot be answered honestly
(e.g. cancelling a Camunda-managed user task by BPMN error, which no cluster up to 8.9
offers a command for) is documented as such rather than guessed.

## Documentation and supported platforms

This adapter runs on both platforms VanillaBP supports:

1. **Spring Boot**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fcamunda8-adapter%2Fspring-boot-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/camunda8-adapter/spring-boot-report)
2. **Quarkus**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fcamunda8-adapter%2Fquarkus-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/camunda8-adapter/quarkus-report)

Coverage is measured separately per platform - a platform's tests never cover the other
platform's code. Click a badge to open the respective report.

The mechanisms this adapter plugs into are drawn in `migration-adapter/README.md` of
`adapter-platform-integration`, each picture in the section which describes it, and
[`diagrams/README.md`](https://github.com/vanillabp/adapter-platform-integration/blob/main/diagrams)
there lists them. Camunda 8 appears in most of them as one branch beside Camunda 7 and the
Process-Engine-API, which is a comparison this repository cannot draw on its own.

## Release lines

A Camunda 8 cluster upgrade is expensive, more so organizationally than technically, and
users sit on different minors at the same time. VanillaBP still has to move onto every new
Camunda version quickly, because some of what users ask for exists only in the newest
cluster and some of it is what the adapter itself needs.

One artifact cannot do both. Camunda promises a client against clusters of its own version
and newer, and says nothing about the other direction, so the client a build was compiled
against IS the lowest cluster version that build accepts. As soon as an artifact uses
anything only an 8.10 cluster has, every later bugfix in it is deliverable only together
with a cluster upgrade.

That the other direction really does fail is measured rather than assumed: the blueprints
ran a build against `camunda/camunda:8.8.34` while the adapter was compiled against the
8.9 client, and every job activation was rejected with `Request property [tenantFilter]
cannot be parsed`, so a workflow started and its task was never delivered.

The adapter is therefore published once per Camunda 8 minor, with the minor in the version:

|   Channel   |        Version        |  Line  |            What lands there            |
|-------------|-----------------------|--------|----------------------------------------|
| previous GA | `2.x.y-8.8`           | `8.8`  | bugfixes only                          |
| current GA  | `2.x.y-8.9`           | `8.9`  | everything                             |
| preview     | `2.x.y-8.10-alpha<n>` | `8.10` | everything, plus what only 8.10 can do |

The table names the minor of each line and no patch level, because a patch level moves
without anybody editing this file. The patch a line is pinned to is one property per line
in the root POM, `camunda8.version.line-8.8` and its two siblings, and Renovate moves them
one at a time. Read the pins there.

The tested cluster is the pinned client: the cluster image is built from the same property,
so the two cannot drift apart. No other cluster version is a supported one here, and a newer
cluster is not tested rather than not supported. Camunda documents its clients as forward
compatible, so a line normally serves clusters above its pin as well, but that is Camunda's
promise and not this project's test result.

A client downgrade inside a line is not supported. Camunda adds enum literals and interface
methods in patch releases and does not count that as breaking, so a build compiled against
`8.9.19` can call a method `8.9.11` never had. Going back therefore fails at runtime, with a
`NoSuchMethodError` or a value no comparison in the adapter expects, and it fails nowhere
near the downgrade. Move the pin forward instead, or move to the line whose pin you want.

The same habit is why a client bump here is read rather than trusted. Every pull request
which raises a pin gets a comment naming the enum literals and the interface methods the new
version added, and on a GA line it also gets a red check, so the patch automerge cannot carry
such a change through unread. That is `.github/workflows/client-api-changes.yaml` with
`bin/client-api-changes.sh`, and `Camunda8UnknownClientEnumsTest` holds what the adapter does
with a literal it has never seen.

The preview line runs against `8.10.0-rc1`, and that pin is what makes it usable again. Up
to `8.10.0-alpha5` the REST gateway of that line dropped a whole activate-jobs batch when it
met a task-listener job whose event carried no user task action in its headers, and the two
events without one are `creating` and `canceling`. Every Camunda-managed user task this
adapter deploys carries a `creating` listener, so on that alpha the application never heard
that the task existed. The bug is `camunda/camunda#58193`: the engine writes the action header
only where the command carried an action, creation and cancelation carry none, and the
gateway's response mapper demanded one anyway and threw a `NullPointerException`, which lost
the whole batch and not just the one job.

Camunda closed the issue on 2026-09-01, a day after `8.10.0-alpha5` was built, and the fix is
in the candidate. Measured against `camunda/camunda:8.10.0-rc1` on 2026-09-25: a user task with
a `creating` listener hands its job out, `Camunda8UserTaskStillCreatingIT` and
`Camunda8UserTaskProbeIT` pass, and the tests which create such a task run on this line again.
The exclusions the alpha needed are gone from the `line-8.10` profile, and so is the tag which
carried them.

The second defect this pin was moved for is SUPPORT-34723, the worker which stopped asking for
work after its first empty poll while a job of it was still in a handler. Camunda's fix is
`#59633` and it is in the candidate as well, and our own run met it from the other side: with four
execution slots and one handler blocked, the cluster handed the same job back to the same
worker as soon as its lock ran out, four times over. That has a consequence worth knowing. The
adapter hands the client an executor as wide as `worker-threads` and the client answers its own
requests on it, so a handler which occupies every slot also stops the client from completing a
request of that same application. `Camunda8JobLeaseIT` blocks a handler on purpose and now
sends its second activation with a client of its own for that reason.

Two things changed with the candidate which an alpha bump never asked for. The client renamed
the lease API to `getJobLeaseToken()` and `withJobLeaseToken(...)`, which is the delta source
of this line and nothing else. And the cluster now refuses the answer to a leased job which
carries no token, with `409 INVALID_STATE`; the adapter always sent one, a test which used the
raw client did not.

One more thing showed up on this line first, and it is not a defect of the line. The adapter
opens one worker per process and kind, and every one of them holds a REST activation request
open. The Camunda client caps its connection pool at 100 by default, the same number in the
`8.8`, `8.9` and `8.10` clients. An application with more workers than that does not get them
all served: the surplus workers take turns, and whatever one of them is waiting for arrives a
whole `request-timeout` late. The preview line reaches the cap first because it deploys a
cancel listener per process, which the GA lines have not: the Spring Boot test module opens 85
workers on 8.9 and 115 on 8.10.

Measured on 2026-09-26 with `Camunda8RestartDeliveryIT` at 115 workers on
`camunda/camunda:8.10.0-rc1`: 10412 ms with the client's 100 connections and 215 ms with 256.
The same line with the 92 workers the module opened before the start-event listener of story
653 answers in 184 ms, and 8.10 held to the 85 workers of the GA lines drains cleanly. So the
number of workers against the size of the pool is what decides, not the version of the client.
The test module therefore configures `max-http-connections`, and an application which grows
past a hundred workers has to do the same. Sizing or checking that pool for the developer is
adapter work which has not been done yet.

Snapshots have no suffix yet. Until the first release they are `2.0.0-SNAPSHOT` of the
current GA line, which is what a build without a profile produces.

### Which line to use, and moving to the next one

Take the line whose pin is at or below your cluster's minor. On 8.8 that is `-8.8`, on 8.9
and above `-8.9`, and `-8.10-alpha<n>` if you run an alpha, usually because the cluster is
not productive yet and you want everything the newest one offers.

Moving to the next line means upgrading the cluster, so it is one decision and not two.
Renovate will not do it behind your back: the version suffix is read as a compatibility
value, and Renovate never proposes an update that changes it. Extend the preset shipped
here to inherit that in your own application:

```json
{
  "extends": ["github>vanillabp/camunda8-adapter//renovate/camunda8-lines.json"]
}
```

The VanillaBP-facing API is identical on every line, checked in CI by
`bin/api-identity.sh`. You never have to read a version suffix to find out which methods
exist. Where a line's cluster cannot do something, the same method is there and fails with
a message naming your line, the way `cancelUserTask` does. The adapter logs the line and
the client it was built against once per adapter id at startup, so the log says which
cluster minimum is in effect.

### How long a line lives

A GA line lives until the next minor goes GA, so there are two GA lines at a time plus the
preview. When 8.10 goes GA in October 2026, 8.9 becomes the previous GA and 8.8 ends, even
though Camunda supports 8.8 until April 2027. That is our policy and not a technical limit:
it keeps the matrix at three builds and three cluster runs.

### How the lines are built

Every line is a build variant of this one source tree, not a maintenance branch. A line is
a Maven profile that selects the client pin, and with it the cluster the integration tests
run against:

```bash
mvn install                                          # current GA line, 2.0.0-SNAPSHOT
mvn -Pline-8.8 -Drevision=2.1.0-8.8 clean install    # a release of the previous GA line
mvn -Pline-8.10 -Drevision=2.1.0-8.10-alpha1 clean install
```

Switching a line always needs `clean`, and the CI does it that way. Classes compiled
against one client are binary compatible with no other one: a method the 8.9 model library
inherits from a type 8.8 does not have at all is called through the owner the compiler saw,
so a stale `target/` fails at runtime with a `NoClassDefFoundError` rather than at compile
time. Building the same line again is fine.

The version is `${revision}`, resolved into the published POMs by `flatten-maven-plugin`,
so the same commit produces every line. A fix therefore exists on every line the moment it
is committed, and the version number proves it is the same fix. Branches cannot promise
that, and the adapter changes constantly for reasons that have nothing to do with Camunda,
which would mean cherry-picking every one of those changes into every line.

The same plugin writes the POM an application reads, and that POM has to stand on its own.
It is flattened in the `oss` mode: every version resolved, no parent, no
`dependencyManagement`, no profiles. A property is no help there. A consumer activates none
of our profiles, so a published POM which still named `${camunda8.version}` would resolve it
to the default of the file, which is the current GA line, on every line. That is what every
line did until September 2026. Nobody had looked, because the source POM reads right and the
build of a line uses the source POM. The first sample application resolved per platform and
line showed it: the 8.8 line handed an application the 8.9 client, whose job activations an
8.8 cluster rejects, and the preview line handed it a client older than the code it runs.
`Camunda8PublishedPomTest` reads the published POM on every line since and compares the
client version in it with the client the build was compiled against.

What that test knows about a published POM sits in `PublishedPom`, published as
`org.camunda.community.vanillabp:camunda8-adapter-published-pom` from the module
`published-pom`. The Business Cockpit's Camunda 8 adapter makes the same promise and calls
the same assertions, so neither repository keeps a copy which could drift. The module has
no dependencies at all, because the class reads a file and throws an `AssertionError`. A
caller says with `inTheRun(...)` which run it is asking for, and every failure message
repeats it: a version like `2.0.0-8.9-SNAPSHOT` names the line by itself, a pull request
build's plain version does not, and this module knows the line it was built for rather than
the line the caller is testing.

Nothing else of ours reaches an application either, and that is the point of dropping the
parent. What this repository pins for its own build is chosen for the newest line, and a
user of the oldest line has no reason to be given it. See decision 39.

Code that cannot be shared goes into a per-line source directory added by
`build-helper-maven-plugin`, `src/main/java-line-<id>` and `src/test/java-line-<id>`. Only
two kinds of code belong there: code that cannot compile against every supported client,
and code that uses something only a newer cluster has. The test directories hold one test
per line, which proves the pin reached the runtime.

The main directories hold exactly one class today, `Camunda8JobExecutors`, and it is the
textbook case for the scheme. The virtual-thread execution model hands the client an
executor of the adapter's own, and which builder method takes it changed with 8.9: the 8.8
client knows one executor for both the polling and the handler invocations, while 8.9 asks
for `jobWorkerSchedulingExecutor` and `jobHandlingExecutor` separately and its
`jobWorkerExecutor` sets only the first of the two. A client configured through the shared
method alone would therefore run its handlers on the client's own pool from 8.9 on, which
is one thread unless something says otherwise - and that is the very defect the model
exists to fix. The class is package-private and has no public members, so the API identity
check sees the same declaration on every line.

What a line did need so far is a dependency pin rather than code. Each client brings
generated protobuf code, and protobuf refuses a runtime older than its gencode, while the
Spring Boot BOM manages a version of its own and an imported BOM beats a transitive one. The
parent POM therefore manages `protobuf-java` itself, before that import, high enough for
every pinned client. A newer runtime serves an older gencode, so one number covers all three
lines and it is the gencode of the newest client among them.

The number cannot be derived. A `dependencyManagement` version has to be written down before
the client is resolved, so Maven has no way of taking it out of the client's own POM. What it
can do is read that POM and compare, which is `Camunda8ProtobufPinTest`: the build copies the
POM of the client this line was compiled against, reads the `protobuf-java` version it
declares, and reads what really ends up on the classpath by reflection on
`com.google.protobuf.RuntimeVersion`. When the two disagree the build fails with both numbers
and the line to change. Without it the failure is an `ExceptionInInitializerError` on the
first command that touches the protocol, thirty lines below a message about a closed port,
which is how a client bump used to go red. It now breaks with a sentence instead, and the
Renovate pull request which proposes such a bump says the same thing in its body.

That pin is the classpath of this build and reaches no application, because the published
POMs carry no `dependencyManagement`. One number per line would therefore change nothing for
a user and would only lower what the older lines are tested against, which is why there is
one. What an application gets instead was measured on 2026-09-20, with a sample project per
platform and line:

| Line | Client gencode | Spring Boot 4.0 | Spring Boot 4.1.1 | Quarkus 3.39.3 | No platform BOM |
|------|----------------|-----------------|-------------------|----------------|-----------------|
| 8.8  | 4.31.1         | 4.31.1          | 4.35.1            | 4.35.0         | 4.31.1          |
| 8.9  | 4.33.6         | 4.33.6          | 4.35.1            | 4.35.0         | 4.33.6          |
| 8.10 | 4.36.2         | 4.36.2          | **4.35.1**        | **4.35.0**     | 4.36.2          |

Spring Boot manages `protobuf-java` from 4.1 on and Quarkus manages it in every version, and
an imported BOM wins over anything the adapter brings. So on the two GA lines an application
runs a protobuf newer than its client asks for, which protobuf allows. On the preview line
both platforms hand it an older one, and that is the failure this pin exists to avoid: the
application dies with `Detected incompatible Protobuf Gencode/Runtime versions` on the first
command that touches the protocol. Measured by loading the gateway protocol class of client
`8.10.0-rc1` against runtime `4.35.1` and `4.35.0`.

An application on the preview line therefore pins `protobuf-java` to the gencode of that
line's client itself, in its own `dependencyManagement`, above the platform BOM. Nothing this
repository publishes can do it for it. The GA lines need nothing.

What the adapter can do is say it. `Camunda8ProtobufRuntime` loads one generated class while
the adapter validates its configuration, which is a class load the client does a moment later
anyway, and turns protobuf's refusal into a message naming the version to pin and where to put
it. So the application fails at startup like every other configuration problem here, instead of
starting and dying on its first command with an `ExceptionInInitializerError` somewhere in
business code. Anything else which goes wrong while loading that class is no answer about
protobuf and keeps nobody from booting.

### The tripwire

This scheme was chosen because the per-line delta is small. If the delta grows past a
handful of classes, or the shared code stops compiling on a line in a way a small shim
cannot bridge, then it has become a branch scheme in disguise, and the line is to be split
off deliberately as a maintenance branch. Whoever hits that limit will not be
the person who chose the scheme, so it is written down here.

### Version ordering, and why Renovate does not use maven versioning

Maven orders the suffix as an addition rather than as a pre-release, which is what makes it
usable at all: `2.1.0-8.8 > 2.1.0`, and `2.1.0-8.9 < 2.1.0-8.10` numerically rather than
lexically. One comparison goes wrong, and it is the whole risk of a suffix:
`2.1.0-8.9 < 2.2.0-8.8`, so "the newest version" can cross a line boundary. Renovate reads
the suffix as a compatibility value instead of a version part, which fixes exactly that.
`renovate/verify-line-gating.js` runs the check in CI, including the case above.

A pre-release of the preview line is `2.2.0-8.10-alpha1`: the qualifier comes after the
line, so the line always sits in the same place, and Maven sorts
`2.2.0-8.10-alpha1 < 2.2.0-8.10-alpha2 < 2.2.0-8.10`. `preview1` was rejected, because
Maven ranks an unknown qualifier ABOVE the release: `2.2.0-8.10-preview1 > 2.2.0-8.10`.

### What CI runs

The nightly matrix (`.github/workflows/line-matrix.yaml`) builds every live line with its
own version string and runs its integration tests against its own cluster. The matrix is
read out of the `line-*` profiles, so it cannot fall behind the build. A pull request runs
the same matrix: `checks.yaml` calls it without a condition, and the result reports as
`line-pins-verified`. A pull request used to build the current GA line alone, and then a night
found on `main` what the pull request of the same commit had not. See decision 42.

That check reads the GA lines of the matrix, and so does `lines-verified` inside it. The preview
line builds there like every other line and its job is red where it broke, but it decides neither
name: a defect of the alpha cluster it pins must not stop somebody who needs a fix on a released
line. What it gets instead is an issue of its own, and the job summary of the matrix names the
lines which decided the run. A release still waits for every line, the preview one included. See
decision 42.

The matrix takes about forty minutes, where a pull request on its own took about twenty, and a wave
of stories pays that once instead of once per story. Whoever opened the wave watches it and starts
on a red line at once.

In front of the matrix stands `orphaned-javadoc-check`, which runs
`bin/check-orphaned-javadoc.sh` over the sources. Javadoc keeps the last block before an element
and drops every earlier one without a word, and no build says so. The check answers in seconds, so
nobody waits out the matrix to hear about a comment.

`checks.yaml` still says in its log whether a pull request moves a client pin, in the job
`pin-change`. It gates nothing any more, and it is there because a client patch which merges
itself looks like every other pull request. Only the added and removed lines count: a diff carries
three lines of context around every hunk, so reading all of it called every change near a pin a
pin move.

A night which goes red does not stay buried in the list of runs. `release-lines-issue.yaml` opens
one issue per red line, labelled `release-lines` and titled after the line, and comments on that
issue while the line stays red. A line which is green again gets a comment and the issue stays
open, because a green night is not a fix. Whoever merges the fix closes it. See decision 31.

A preview line which breaks on a pull request gets an issue as well, and a separate one.
`checks.yaml` calls `preview-line-issue.yaml` after the matrix, which opens it under the label
`preview-line` and a title naming the line, and writes a comment instead while such an issue is
open. The pull request stays green, so the break would otherwise turn up in the night after the
change was merged, with nothing pointing back at what caused it. Nothing closes that issue either:
a later pull request builds another branch and says nothing about this break.

### Release and CI plumbing

A release of one line consists of:

1. `mvn -Pline-<id> -Drevision=<version>-<id> deploy` from the release commit, once per
   live line, all from the same commit. The preview line publishes as a pre-release with
   `-alpha<n>` appended.
2. Every current line green in the full matrix, run by the release itself rather than looked
   up from last night. The release makes its first job a call of `line-matrix.yaml`, and every
   job which builds or publishes a line comes after it. No input skips it. See decision 31.
3. The API identity check green, so no line gained or lost a method.
4. The line table of this README and of the wiki updated when a pin moved.
5. The version property of every consumer pointed at the suffixed coordinates. The
   blueprints carry `camunda8-adapter.version`, which is `2.0.0-SNAPSHOT` today and has to
   become a suffixed version with the first release. That switch is the moment the suffix
   becomes visible to users, and `UPGRADE.md` describes it.
6. The consumers of the snapshot follow the current GA line too. The blueprints start a
   Camunda 8 cluster of their own for the CI (`bin/camunda8_cluster.sh`), and that cluster
   has to be at least the client the adapter was built against, so it moves with the
   default line.
7. One real `renovate --dry-run` against the published artifacts. The gating is proven
   today by `renovate/verify-line-gating.js`, which asks Renovate's own versioning module
   what it would offer a consumer of each line; a full dry run needs versions in a
   datasource, and until the first release there are none.

Rotating the lines when a minor goes GA touches four places: the `line-*` profiles and the
pin properties of the parent POM, the boundary rule of `renovate.json`, the table above,
and the wiki.

## Dependencies

All artifacts use the groupId `org.camunda.community.vanillabp`. Their version carries the
release line once the adapter is released, e.g. `2.1.0-8.9`; until then it is
`2.0.0-SNAPSHOT` of the current GA line, see [Release lines](#release-lines).

### Spring Boot

Add a single dependency; it transitively pulls in the platform-neutral core and the
required VanillaBP platform integration:

```xml
<dependency>
  <groupId>org.camunda.community.vanillabp</groupId>
  <artifactId>camunda8-adapter-spring-boot</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

### Quarkus

Both VanillaBP and the adapter are Quarkus extensions, so both must be added explicitly:

```xml
<dependency>
  <groupId>io.vanillabp</groupId>
  <artifactId>vanillabp-quarkus-integration</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>org.camunda.community.vanillabp</groupId>
  <artifactId>camunda8-adapter-quarkus</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

The adapter is a *type* named `camunda8`. Configure one or more adapter *instances* of
that type and reference them in the prioritized-adapters list:

```yaml
vanillabp:
  adapters:
    myengine:
      type: camunda8
  prioritized-adapters:
    - myengine
```

The adapter ID (`myengine` above) identifies an adapter *instance*; the same BPMS type
may be configured multiple times with different IDs (the central migration scenario:
e.g. an old on-prem cluster and a new SaaS cluster side by side).

### Connecting to a Camunda 8 cluster

Each adapter instance is connected to a cluster through the **canonical per-adapter
configuration location** `vanillabp.adapters.<adapter-id>.*` - the adapter contributes
its own keys to the shared VanillaBP tree via platform OVERLAYS (Spring Boot: a second
`@ConfigurationProperties("vanillabp")` class; Quarkus: a second RUN_TIME
`@ConfigMapping(prefix = "vanillabp")`, which also provides the unknown-key validation
coverage for these keys). The values are turned into a plain-Java `CamundaClient` built
EAGERLY at startup for every completely configured adapter instance. The adapter-id
set always comes from the platform's core properties (ids of type `camunda8`); the
overlay maps are per-known-id lookups only.

|                    Property                     |  Applies to  |                  Required                  |                                  Description                                  |
|-------------------------------------------------|--------------|--------------------------------------------|-------------------------------------------------------------------------------|
| `vanillabp.adapters.<id>.mode`                  | both         | no (default `self-managed`)                | `self-managed` or `saas`                                                      |
| `vanillabp.adapters.<id>.rest-address`          | self-managed | yes (unless `prefer-rest-over-grpc=false`) | REST API address, e.g. `http://localhost:8080`                                |
| `vanillabp.adapters.<id>.grpc-address`          | self-managed | only if `prefer-rest-over-grpc=false`      | gRPC address, e.g. `http://localhost:26500`                                   |
| `vanillabp.adapters.<id>.prefer-rest-over-grpc` | self-managed | no (default `true`)                        | use the REST API (recommended) or gRPC                                        |
| `vanillabp.adapters.<id>.cluster-id`            | saas         | yes                                        | SaaS cluster ID                                                               |
| `vanillabp.adapters.<id>.region`                | saas         | yes                                        | SaaS region                                                                   |
| `vanillabp.adapters.<id>.client-id`             | saas         | yes                                        | OAuth client ID                                                               |
| `vanillabp.adapters.<id>.client-secret`         | saas         | yes                                        | OAuth client secret                                                           |
| `vanillabp.adapters.<id>.tenant-id`             | both         | no                                         | Camunda 8 multi-tenancy tenant, also settable per workflow module             |
| `vanillabp.adapters.<id>.auth.*`                | both         | no (default: no credentials)               | how the adapter authenticates, see [below](#authenticating-against-a-cluster) |

Example (self-managed):

```yaml
vanillabp:
  adapters:
    myengine:
      type: camunda8
      mode: self-managed
      rest-address: http://localhost:8080
```

**Boot behavior (validated at startup):** Every configured adapter instance's
connection configuration is validated AT STARTUP:

- entirely unconfigured → the application still boots; a guiding WARN names the
  adapter id and the exact keys to add (e.g. `vanillabp.adapters.myengine.rest-address`);
- inconsistent (e.g. `mode: saas` without `cluster-id`) → the boot FAILS naming the
  missing keys - unless the adapter is nowhere first in any prioritized-adapters list
  and its `deployment-failure` policy is `warn` (then the application boots DEGRADED
  with a warning; the migration scenario's old BPMS must not block the boot);
- fully configured → the client is built eagerly (building never contacts the
  cluster).

Messages name property KEYS only - values, especially credentials like
`client-secret`, are never echoed. Using an unconfigured adapter at runtime keeps a
guiding failure message as backstop.

Each of the three outcomes has its test, on both platforms: `Camunda8StartupValidationTest`
and `Camunda8StartupValidationBootTest` for the adapter nobody configured,
`Camunda8InconsistentConfigurationTest` together with
`Camunda8StartupValidationBootTest#inconsistentNowhereFirstAdapterWithWarnPolicyBootsDegraded`
for the half configured one, and `Camunda8ClientFactoryTest` for the client which is built
without asking the cluster anything. That no message carries a secret is
`Camunda8StartupValidationBootTest#fullyConfiguredAdapterBootsWithoutWarningAndWithoutEchoingSecrets`.

### Authenticating against a cluster

The adapter used to authenticate against Camunda SaaS and against nothing else.
`client-id` and `client-secret` hung on the cloud builder, the self-managed branch set
addresses, the transport preference and the tenant, and never a credentials provider. A
self-managed cluster with its authentication switched on, which is what a self-managed
installation normally looks like, was therefore unreachable, and no message said why, because
the adapter had no property to offer. Our own integration tests hid it: every cluster here ran
with `CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI=true`, so an adapter sending no
credentials passed all of them. `Camunda8AuthenticationIT` is the test that would have caught
it, and it runs against a cluster where nothing is unprotected.

`vanillabp.adapters.<id>.auth.*` carries the credentials of one adapter instance, at adapter
level only: a credential is a property of the connection, and a per-workflow level would be a
promise the connection cannot keep.

**Three methods, because the client has three.** `none`, `basic` and `oidc` are what
`CredentialsProvider` can build, and both builders are used as they are rather than
reimplemented, so the OIDC token is cached in a file and refreshed by the code Camunda
maintains. A fourth value `mtls` was considered and dropped: the Camunda Java client offers no
keystore for its own gRPC or REST connection on any of the three release lines, not through
`CamundaClientBuilder`, not through `ClientProperties`, not through an environment variable.
Its keystore and truststore belong to the token request against the identity provider, and the
`auth` block says so. A property which quietly configures the token request while the user
believes it configures the cluster connection is worse than no property, so this is a
documented deviation instead.

**An absent `method` is detected and the detection is logged.** A user name detects `basic`, a
client id detects `oidc`, a SaaS adapter is `oidc` through its connection keys, and nothing at
all is `none`. The startup line which names the address names the method next to it, with
`(detected)` where nobody wrote it down. A `none` nobody noticed is how the gap survived in the
first place, so it does not get to be quiet a second time. Credentials which cannot belong to
the resolved method fail the boot rather than being ignored, and so do two methods configured
at once: a key nobody sends is a key somebody wrote for nothing.

**A self-managed OIDC adapter has to name its authorization server.** The Camunda client falls
back to `https://login.cloud.camunda.io/oauth/token/` when an OIDC client names none, which no
on-premises installation ever means, so `authorization-server-url` and `audience` are required
outside SaaS. `issuer-url` and `well-known-configuration-url`, which the 8.9 client added, are
deliberately not modelled: the VanillaBP-facing API is identical on every release line, and the
8.8 client has neither.

**SaaS shares the code path.** Its connection keys become the OIDC client with two presets,
Camunda's login endpoint and the audience `zeebe.camunda.io`, which is byte for byte what the
cloud builder would have built. Existing SaaS configurations therefore keep working, and they
gain the rest of the `auth` block: the credentials cache and the timeouts of the token request
were previously unreachable.

**The runtime message rides on `shouldRetryRequest`.** Whether a cluster accepts a credential
is only learnable by asking it, so `method: none` cannot be validated at startup. The client
asks the credentials provider `shouldRetryRequest` on every request a cluster refused, on both
transports and for commands as well as for job activation, which makes that one method the
place where an adapter learns it is unwelcome. The provider handed to the client is wrapped in
`Camunda8Authentication.Observing`, which reports once per adapter id: for `none` with the YAML
which names a method, otherwise with the fact that the configured credentials reached the
cluster and were refused. Building on the classification in `Camunda8Errors` was the
alternative and was dropped, because it would have needed a call at every one of the two dozen
places a command is sent and would still have missed the workers, whose activation failures the
adapter never sees.

**`none` plus credentials in the environment sets no provider at all.** The client builds one
from `CAMUNDA_CLIENT_ID`/`CAMUNDA_CLIENT_SECRET` or `CAMUNDA_BASIC_AUTH_USERNAME`/
`CAMUNDA_BASIC_AUTH_PASSWORD`, but only while the application set none, so setting a Noop
provider would have switched off a deployment which relied on those variables. That case
therefore hands the client nothing, at the price of the runtime message, and says so in the
startup line. Every other case sets a provider, and where the environment carries credentials
alongside, a WARN names the variables and what they no longer decide.

**Who the adapter authenticates as is part of its instance identity.** Two adapter ids on one
self-managed address were previously the same instance and failed the boot; with separate
accounts they are two, which is the same reasoning that already made the SaaS client id count.

What the paragraphs above promise is pinned below the round trip as well:
`Camunda8AuthConfigurationTest` for the detection, the refusals and the keys a message names,
`Camunda8AuthenticationTest` for the provider the client ends up with and for the one runtime
message, `Camunda8ClientFactoryTest#startupLineNamesTheAuthentication` for the startup line,
and `Camunda8InstanceIdentityTest` for the identity of the paragraph above.

### Behavior

- **Deployment (on startup):** the BPMN resources of each workflow module are deployed in
  a single `DeployResourceCommand` per module. Which scope they land in is decided by the
  name-clash-avoidance mode, see
  [Keeping workflow modules apart](#keeping-workflow-modules-apart).
- **Starting a workflow (two-phase):** Camunda 8 is remote and eventually consistent and
  cannot join the application's database transaction.
  - *Phase one* runs inside the caller's transaction and only **validates** (resolves the
    aggregate ID, verifies the client is configured). It never contacts the cluster - a
    remote call here would reintroduce ghost workflows on rollback.
  - *Phase two* runs after the commit (through the core phase-two outbox) and creates the
    process instance of the latest version. The create command carries the process
    variable holding the workflow aggregate's ID (as a string), named after the
    aggregate's ID property (`AggregatePersistenceAware.getAggregateIdName()`), plus the
    values the aggregate shares through `@SyncWithBPMS` - see decision 1 in this
    repository's `DECISIONS.md` for why both travel with every command sent on behalf of
    a workflow.

`Camunda8DeploymentServiceTest` holds the deployment half.
`Camunda8DeploymentAndStartIT#instanceAppearsOnlyAfterCommit` and `#noInstanceAfterRollback`
drive the two-phase start against a real cluster, the Quarkus twin being
`Camunda8WorkflowLifecycleTest#aRolledBackStartCreatesNothing`.

### When a phase-one check runs

The non-advancing checks of phase one - the job-timeout update for a service task, the empty
update for a user task - run right before the caller's transaction commits, not when the
application calls. The later the check, the smaller the window in which its answer can go
stale before phase two acts on it, and a failing check aborts the commit, which is how the
application learns about a task which is already gone.

Where the hook sits relative to the caller's commit and to the dispatcher which runs phase two is
drawn under [The transaction the work runs in](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/README.md#the-transaction-the-work-runs-in).

The adapter no longer carries that mechanism. It hands the check to the platform
(`PreCommitRegistrar` of the adapter SPI), naming the workflow aggregate, and the platform
asks the transaction runner of that aggregate - which may be a unit of work the
application brought. A runner which cannot offer a pre-commit hook runs the check immediately,
the behaviour this adapter had before.

A check which meets a task the cluster no longer knows throws
`io.vanillabp.spi.process.TaskNotFoundException`, the type the SPI documents for a task no
BPMS knows any more. Whether that was found out by probing the configured BPMS or by this
check is the adapter's business, and an application catching the documented exception
catches both. The message names the task; the cluster's own words about the rejection go to
the log, because that exception carries a message and no cause.

`Camunda8PreCommitCheckTest` pins both halves of the timing: phase one registers without
contacting the cluster, and the check reaches the cluster when the hook fires. The type is
held against a real cluster by
`Camunda8TaskProcessingIT#aStaleCompletionRaisesTheGuidingException`, once more on a
primary process in `#aStaleCompletionOnAPrimaryProcessRaisesTheGuidingException`, and on
Quarkus by `Camunda8WorkflowLifecycleTest#aStaleCompletionRaisesTheGuidingException`. The
Quarkus one reads the exception out of the `RollbackException` that JTA wraps a failed
`beforeCompletion` in.

### A user task the cluster is still creating

VanillaBP tells an application about a Camunda-managed user task from the `creating` task
listener it writes into the model, and a task whose `creating` listener has not been answered
stands in state `CREATING`. The `@WorkflowTask` method runs inside that listener job, so the
application holds a valid task key while the cluster is still creating the task. Completing the
task right there is allowed, and an application which answers a user task from its notification
does exactly that.

The cluster refuses every command against a task in that state with HTTP `409` and the title
`INVALID_STATE`. That is the answer of the empty update phase one sends and of the completion
phase two sends. The adapter reads it as what it says, which is that the task is there. Phase
one lets the transaction commit: it aborts a transaction whose task is GONE, and a task the
cluster refuses a command about is not gone. Phase two waits the state out, five attempts
spread over less than half a second, which is more than the state needs once the listener job
has been answered. Where those attempts are used up the outbox takes the operation over as it
does for any other repeatable failure, so nothing is lost and the ordinary case stays fast.

Two more places read the same answer. `awarenessOfUserTask` reports such a task as `ACTIVE`
instead of reporting the cluster as unavailable, and the check of the other open tasks has
always read it that way. The other state behind that answer is `UPDATING`, which a modelled
`updating` listener can produce.

The adapter also keeps the window as short as it can. A user-task listener job is completed
before the check of the other open tasks of that workflow runs, because completing that job is
what ends state `CREATING`, and every millisecond before it is a millisecond the application
cannot use the task key it was just handed.

`Camunda8UserTaskStillCreatingTest` holds both phases without a cluster.
`Camunda8UserTaskStillCreatingIT` forces the state against a real one, with a model whose
second `creating` listener nobody answers, so the task stays in `CREATING` until the test
answers that job itself.

### The delivery identity is a job key, so it belongs to one cluster

`Camunda8JobHandler` reports the job key as the delivery id, which is what the core remembers a processed delivery
by. A key is stable across every redelivery of that job and is never handed out twice - within one
cluster. The delivery key of the core starts with the adapter id
(`TaskDeliveryKey`: `<adapterId>|<workflowModuleId>|<bpmnProcessId>|<event>|<deliveryId>`), so two adapter ids have
separate identities and a migration between two clusters works even though both count their keys from the same
range.

Replacing the cluster BEHIND one adapter id is the case to know about: a rebuilt cluster starts its keys over, so a
record written for the old one can answer a delivery of the new one, and that task is skipped without a word. The
delivery table of that adapter id has to be emptied then. Recognising it automatically was considered and left out
(2026-08-19): the client offers no cluster identity - `Topology` knows brokers, size and version, nothing unique -
and every heuristic (comparing process definition keys, watching for keys which suddenly become smaller) either
misses cases or risks the opposite mistake, processing a task twice after a harmless redeployment. So it is
documented here and in the wiki instead.

`Camunda8ActivationIdentityTest#aRedeliveryRepeatsBoth` pins that a redelivery keeps its
delivery id, and `Camunda8InboundIdempotencyIT#redeliveredJobsSkipTheHandler` shows what the
core makes of it against a real cluster. The rebuilt cluster is an assumption: no test rebuilds
a cluster and keeps the delivery table, and a rebuilt cluster continuing its key range instead
of starting over would disprove it.

### The activation identity is the ELEMENT instance key, not the job key

Next to the delivery identity the core asks which activation of a BPMN element is running, and this
adapter answers `ActivatedJob#getElementInstanceKey()`. The job key would answer it correctly today,
which is precisely why it is not given: the two contracts are opposite, a delivery identity has to
stay equal across redeliveries while an activation identity has to differ between two activations of
one element, and a job created a second time for ONE element must not read as a new element. What
the core does with it is put it into the idempotency key of a message correlation planned while the
handler runs, so the elements of a multi-instance activity stop sharing a key
(`Camunda8ActivationIdentityTest` pins both contracts against each other).

### What else a delivery record carries

Two values travel with every delivery without steering anything: `ActivatedJob#getElementId()` and
`ActivatedJob#getProcessInstanceKey()`. The core writes both into the delivery record, and they are
what somebody addresses the task by outside VanillaBP - an operator searching Operate, an extension
linking a task to a place in the model. All three handlers of this adapter answer them, the job
handler and the two listener handlers, because all three run on a job the cluster ships those values
with.

The element id is not the task definition. A task definition here is the job type, and two elements
may share one; the element id names the element itself.
`Camunda8InboundIdempotencyIT#theRecordNamesTheElementAndTheWorkflow` reads both back out of the
table against a real cluster.

**The cluster's own net knows about it too.** `correlateMessagePhaseTwo` derives the `messageId` it
hands to Zeebe from workflow module, BPMN process, aggregate id, message name, correlation id and the
activation, and the cluster deduplicates by that for as long as the message lives. Without the last
part the three siblings would reach the OUTBOX as three operations and the cluster as ONE message,
which VanillaBP cannot see and cannot fix from its side. The activation reaches phase two with the
outbox entry (`PhaseTwoCall.ARG_ACTIVATION_ID`), because the thread which knew it is long gone by
then. A correlation planned outside any activation derives the id it always did
(`Camunda8MessageIdTest`, and
`Camunda8TaskProcessingIT#theActivationTellsSiblingsApartInTheClustersOwnNet` against a
cluster).

### How long the cluster keeps a message

`message-time-to-live` decides it, and unlike the client default it applied before, it resolves per
adapter, workflow module, workflow and MESSAGE - the same most-specific-wins machinery `job-timeout`
uses, with a message as the most specific level instead of a task:

```
vanillabp.workflow-modules.<m>.workflows.<w>.messages.<messageName>.adapters.<id>.message-time-to-live
```

The number does two jobs which pull apart. It BUFFERS a message published before its subscription
exists, which wants it large, and it is the window a message id DEDUPLICATES in, which wants it
small. A catch event whose message may legitimately repeat every minute and one whose message is
published long before the workflow reaches it are different messages in one application, so one
number for the whole application has to be wrong for one of them. Nothing configured means nothing
set: the client's own default applies, as it always did.

**Shortening it does not buy a short deduplication window.** The cluster forgets an expired message
id on a sweep of its own rather than at the moment it expires. Measured against
`camunda/camunda:8.9.16` on 2026-08-27, a two-second time-to-live was still deduplicating five
seconds later and forgotten after 75
(`Camunda8TaskProcessingIT#theTimeToLiveDecidesHowLongTheClustersNetLasts` pins it). What tells two
legitimate correlations apart is what they carry - a varying correlation id, or the activation.

`startWorkflowByMessagePhaseTwo` deliberately reads the ADAPTER level only. That message starts a
workflow, so its deduplication is wanted for as long as possible and the subscription of a message
start event exists as long as the process is deployed; a per-message override meant for a repeating
catch event must not shorten the protection against a double-started workflow.

The four levels resolve the way `job-timeout` does
(`Camunda8JobTimeoutOverlayTest#messageTimeToLiveResolvesThroughAllFourLevels`, and
`Camunda8TaskProcessingIT#messageTimeToLiveResolvesThroughAllFourLevels` against a cluster).
That a start message reads the adapter level alone is an assumption: nothing pins it, and a
per-message `message-time-to-live` turning up on a start message would disprove it.

### Which phase-two failures are repeated

The phase-two outbox repeats a failed operation until the entry is blocked. That is right for
a cluster which is busy, unreachable or lost a conflict, and pointless for a command the
cluster rejects: the answer will not change, and the retries only fill the log while
operations wait for the entry to block. The adapter therefore classifies a failure as
permanent when the chain of causes holds one of these:

|                  Failure                  |             Why a repetition cannot help              |
|-------------------------------------------|-------------------------------------------------------|
| HTTP `400`, gRPC `INVALID_ARGUMENT`       | the cluster rejected the request itself               |
| HTTP `403`, gRPC `PERMISSION_DENIED`      | credentials or tenant are wrong, not late             |
| HTTP `405` / `501`, gRPC `UNIMPLEMENTED`  | this cluster version has no such endpoint             |
| `NumberFormatException`                   | the task or instance key of the entry is not a number |
| a START answered with `404` / `NOT_FOUND` | the process is not deployed on this cluster           |
| a START answered with `409`               | the model has no plain start event                    |

`Camunda8ErrorsTest` holds the table, case by case, for both transports.

Everything else is repeated, including three cases which look permanent at first glance.
`404` is the signature of eventual consistency, and for job commands it never reaches the
classification at all - a gone job is the accepted at-least-once residual and consumes the
entry. `401` is usually an expired token, which the client refreshes. `409`, `429` and every
`5xx` are what the outbox exists for.

The last two rows of the table are the same codes, and they are permanent for a START only.
The code alone cannot say it: a `404` a read meets is the exporter lagging behind, and a `409`
a publication meets is a message of that id which still lives, while a start meets a process
this cluster does not hold and a model nothing can start without its message or its timer.
Neither of those passes while the application waits. A deployment reaches the cluster before
the outbox dispatches anything, and a model changes through a deployment rather than through a
repetition. So the start says which operation was refused by wrapping the cluster's answer into
a `Camunda8RefusedStart`, and the classification reads that wrapper. Nothing about those codes
changes for the operations which did not send a start.

The codes were measured against `camunda/camunda:8.9.19` on 2026-09-11, and
`Camunda8RefusedStartIT` pins them. Over gRPC only the `404` half could be measured: that cluster
answers a gRPC create of a process it holds by refusing the permission instead. So a start which
meets a model without a plain start event over gRPC still pays the full row of attempts, and that
half of the case is open. A request above the cluster's maximum message size comes back as `400`
and was permanent all along. A model which cannot evaluate an expression is not refused at
all. Camunda 8 creates the instance and raises an incident on it, which is where it differs from
Camunda 7: there an expression of the start is evaluated while the instance is created, the
command fails, and the application is left with a committed aggregate and no workflow.

The same classification serves the commands a job handler sends back to the
cluster (`Camunda8Errors.repeatableJobCommandFailure`), adding the one case which is
permanent there and not here: a job which is gone. One classification serving both
directions is the point - a second opinion about what a repetition can change would drift
away from this one.

### A request which ran out of time

A socket against a Camunda 8 cluster runs out of time now and then, and an answer which never
arrived says nothing about what the cluster did: the command may well have been carried out and
only the reply got lost. Camunda met the same case in its own migration tooling and wrapped a
small retry around `activateJobs`. This adapter needs none, and the six places a timeout can reach
say why.

**A command a job handler sends back is repeated.** Completion, BPMN error, failure and lock
renewal run inside `Camunda8CommandRetry`, and a timeout is repeated there. `Camunda8Errors` names
what cannot be repeated by the cluster's own codes, and no shape of a timeout carries one of them;
neither is a timeout mistaken for a job which is gone, because that question reads HTTP `404`
respectively `NOT_FOUND` and no message text at all. What bounds the repetition is the job's
remaining lock, and for this failure the arithmetic leaves room: a delivered job is locked for
`job-timeout`, five minutes by default, and a command gives up after `request-timeout`, ten
seconds, so a completion which ran out of time still has four minutes fifty of lock against a
first backoff of 50 ms. The retry is not merely responsible here, it runs
(`Camunda8CommandRetryTest#aTimedOutCommandIsSentAgain` and
`#aTimeoutLeavesEnoughLockForASecondAttempt`).

**A poll which runs out of time is the client's own business.** The `JobWorker` polls, not the
adapter, and the client carries a failed poll itself: `JobWorkerImpl#onPollError` releases the
poller, lengthens the poll interval and schedules the next poll. A poll which runs out of time
costs one interval and nothing else, which is why Camunda's commit has no counterpart here. What
the adapter does contribute is the deadline, and `request-timeout` is a trap worth knowing: it is
the window an activation request waits at the cluster AND the deadline of every other command, the
deployment of a workflow module and every search included. Shortening it does not make the long
poll time out - the client adds ten seconds to the response deadline of an activation, so the
window is never its own timeout - but it does make the workers ask again more often, and it does
make a healthy cluster answer a deploy too late. Below a second the boot says so
(`Camunda8RequestTimeoutTest`).

**An outbox entry repeats a timeout like anything else.** Phase two is repeated while
`Camunda8Errors.permanentFailure` answers false, which it does for every shape a timeout arrives
in. The list of permanent cases reads codes and one exception type, and a timeout matches nothing
in it.

**A start which cannot reach its cluster used to end at the first round it could not make.** It
waits now, see [the start waits for the cluster](#the-start-waits-for-the-cluster).

**A lookup which times out never answers "not here".** That would be the dangerous one, because
such an answer sends a workflow to the next adapter. It cannot happen. A search which fails on a
cluster that can be searched reports `BPMS_UNAVAILABLE`, which suppresses the fallback; the task
probes report the same and read a job which is gone from its code rather than from a message; and
the question which adapter id a key belongs to on a shared cluster answers "this one" whenever it
cannot be read, so an unanswered question never hands a workflow away.

**A health probe reports a timeout rather than waiting it out.** `Camunda8Health` answers DOWN for
a cluster which did not answer in time, and that is left exactly as it is. A probe reports a
state, it does not wait one out.

The shapes a timeout arrives in are read off the client and pinned by `Camunda8ErrorsTest`: a
`java.net.SocketTimeoutException` from the socket below the REST transport, a
`java.util.concurrent.TimeoutException` from a bounded wait on the future, and the gRPC status
`DEADLINE_EXCEEDED`, each of them plain or wrapped into a `ClientException` respectively a
`CompletionException`.

### The start waits for the cluster

A cluster booting together with the application is the commonest reason a start cannot reach it,
and it lets every round the start makes fail: the tenant check, the deploy command, the question
whether the cluster can be searched, and the version queries of the startup check. So the adapter
waits once, before the first of those rounds, and repeats none of them
[(why)](./DECISIONS.md#17-a-start-waits-once-for-its-cluster-instead-of-repeating-each-round).

`Camunda8ClusterWait` asks for the topology, the same question the health check asks, and it is
answered by a cluster which has neither secondary storage nor a tenant - which is exactly why it
is the question asked first: the requirement of a searchable cluster comes after it, and a cluster
which is merely booting must not fail that requirement. What ends the waiting is
the cluster answering, `vanillabp.adapters.<id>.startup-wait` running out, or an answer
`Camunda8Errors` classifies as permanent - the last one ends the start at once, which is what lets
the default be as long as ten minutes. Before the first attempt a line names the address and the
deadline, and every few seconds another one carries the time gone and the cluster's last answer,
so a typo in the address reads as "connection refused" from the start rather than as ten silent
minutes.

The rounds a start makes to the cluster are four, and the wait sits in front of all of them, in
this order: the question whether this cluster can be searched at all (`Camunda8QueryApi`, asked
once per adapter id and required to answer yes), `Camunda8TenantCheck` asking whether the tenant
can be used, the deploy command itself, and the version queries of `Camunda8ProcessVersions` for
the process versions the cluster still holds. A fifth request exists and deliberately stays in front of the wait: while
wiring, a process which still carries version 1's user tasks is counted against the cluster for
the warning naming them. That one swallows every failure and decides nothing, so a cluster which
is not up yet costs it one debug line.

Two consequences worth writing down. Credentials the cluster answers with `401` are not permanent
here, deliberately: the classification is the one the whole adapter uses, and there `401` is an
expired token the client refreshes. Such a boot waits out its deadline, and the repeating line
names the `401` from the first attempt on. And an adapter with nothing to deploy for a workflow
module makes no round to the cluster for it, so it waits for nothing.

Every way the waiting ends is in `Camunda8ClusterWaitTest`, and
`Camunda8StartupWaitTest#theStartWaitsForTheClusterBeforeItDeploys` boots an application which
is faster than its cluster.

### Why correlating a message has no cluster preflight

Since 8.8 the client can search message subscriptions, so a preflight would be possible - and
it would be wrong. The cluster BUFFERS a published message for its time-to-live, so
correlating before the subscription exists is legitimate, and a search would reject exactly
that case. The search also reads the eventually consistent secondary storage, whose window the
caller would wait out inside their own transaction.

What phase one does check is the MODEL: if no BPMN model declares the message, the correlation
fails where the application called it. That is the mistake a preflight could have caught - a
typo, or a message renamed in the model - and without the check phase two would publish into
the void: the cluster accepts the publication, the time-to-live passes, nothing correlates and
nothing fails.

Which models is the point, and it must not matter who deployed them (decision 21). The check
reads the models of the current deployment first, for nothing, and where the name is not among
them it asks `Camunda8ModelsTheClusterHolds`: the models the cluster holds for every BPMN
process id the application declares, versions of a renamed process' old id included. A refusal
rests on a read made in that very call, so a version another node deployed moments ago is seen.
Where the models cannot be read - the module deployed nothing, the picture was never built, or
the cluster does not answer - the declared names are unknown rather than absent and the check
stays silent, never refusing a correlation the application may have made correctly. All of it
is `Camunda8MessageDeclarationTest`, and `Camunda8RenamedProcessIT` correlates a message only
the old id's model declares against a real cluster.

### Idempotency limitation

The phase-two outbox has at-least-once semantics. The duplicate-start window is
**minimized** by several layers: the outbox entry's unique idempotency key (one entry
per workflow module, BPMN process and aggregate), the DONE-retention of dispatched
entries, and — since the election story — a probe before every RE-dispatched start
(`awarenessOfWorkflowForRedispatch`: an entry dispatched before checks whether the
workflow already exists via the process-instance search; if so, the entry is consumed
without a second `CreateProcessInstance`). A **residual window remains and is
accepted** as an eventual-consistency property: after a hard crash between a
successful `CreateProcessInstance` and recording the dispatch, the retry's probe may
not see the instance yet (query-API lag), which the probe answers honestly with
"unknown" so the idempotent start proceeds, deliberately NOT an optimistic ACTIVE,
which would skip and thereby LOSE workflows. Do not build on exactly-once semantics.

The layers have their tests: `Camunda8InboundIdempotencyIT#redeliveredJobsSkipTheHandler` for a
repeated delivery, `Camunda8RestartDeliveryIT` with its Quarkus twin
`Camunda8RestartDeliveryTest` for a delivery which survives a restart, and
`Camunda8ProcessServiceTest#redispatchProbeIsNeverOptimisticOnFailure`,
`Camunda8AwarenessWhenSearchFailsTest#theRedispatchProbeReportsAnOutage` and
`Camunda8DeploymentAndStartIT#aWorkflowNobodyStartedIsUnknownToBothProbes` for the probe which
must not guess. The residual window itself is an assumption and stays one: producing it needs a crash
between a successful `CreateProcessInstance` and the record of it, and a run in which the
second dispatch of such a start finds the instance every time would disprove it.

### How the adapter runs what it delivers

The Camunda client owns one executor per client and this adapter owns one client per adapter
id, so a single number decides how much of everything an adapter delivers may be in flight
at once. That number used to be the client's own default of one, nothing passed it
through, and on the 8.8 client that one thread ran the handler invocations AND the poll
scheduling of every worker. Measured against a real cluster: an unrelated job of another
worker waited 8013 ms behind a blocking handler and 13 ms with four threads, and a poll
scheduled with a delay of 100 ms started 4837 ms late while the broker's counter of
completed activation requests stood still. The second half is why this was worth a story of
its own: the backlog was invisible to every client-side signal.

`vanillabp.adapters.<id>.worker-threads` takes a positive number or the literal `virtual`,
and it sits at adapter level because the executor is per client. A workflow-module level
would be a lie.

**The adapter hands the client the executor, whichever mode is configured and whichever line
the artifact was built for.** The 8.9 client keeps the polling and the handlers on separate
executors by itself, the 8.8 client does not, and building the separation only where it is
missing would leave the other lines without what the rest of this section describes. So both
modes build one of the adapter's own executors: two platform threads for the timing, which
no handler can occupy, and either a virtual thread per handler or a pool as wide as the
configured number for the work. `worker-threads` therefore counts handlers running at once
rather than threads shared with the scheduling of every poll, and the two slot gauges say
something in both modes. It costs two threads per adapter id which are idle almost always.

**A worker asks the cluster for work only while an execution slot is free.** The separation
above removes a back pressure nobody designed: on 8.8 an adapter whose handlers held every
slot stopped polling. On 8.9 and 8.10 there was never one, so a queue of activated jobs in
front of the slots is the normal state of those lines, and every job in it spends its lock
waiting rather than being worked on. What the adapter does instead is deliberate: a scheduled
poll runs when a slot is free and is looked at again 100 ms later when none is, so a job
which nobody could run is not fetched at all and the cluster can hand it to another node.
Three things this does not cover, all by design. An activation request is a long poll the
gateway holds for `request-timeout` and answers as soon as a job appears, so a request
already parked when the last slot filled still brings its batch; what the gate stops is the
asking AGAIN, which is what turns one batch into a queue. The client tops a worker up
directly from the thread on which one of its handlers just finished, which never passes the
executor - and that worker has just given a slot back. And the gate decides how often work
is fetched, not how much one activation brings; that is `max-jobs-active` below.

**Four platform threads by default.** More than one, because one is the defect above. Small,
because every concurrent handler holds a database connection inside VanillaBP's transaction
and the usual pools are ten (Hikari) to twenty (Agroal) connections wide, so four leaves room
for the rest of the application. Four was also what turned 8013 ms into 13 ms in the probe.
The number to size against is the connection pool, not the CPU, and the wiki says so where a
user looks for it.

**The virtual mode was measured before it was offered.** On Java 21 a `synchronized` block
around a blocking call pins the carrier thread, which inside a transaction would be a silent
regression, so the question was settled with a probe rather than an opinion:
`-Djdk.tracePinnedThreads=full` plus the `jdk.VirtualThreadPinned` JFR event over 64 virtual
threads x 50 transactions, against Spring's `DataSourceTransactionManager` with HikariCP and
against Narayana with Agroal, each on embedded H2, on H2 over a TCP socket and on a real
PostgreSQL 16. Zero pinning events in all six combinations; a positive control (a
`Thread.sleep` inside `synchronized`) produced four, so the detection was working. The
drivers moved off `synchronized` for exactly this reason (pgjdbc since 42.5.1, H2 2.x), and
JDK 24 removes the question altogether. So `virtual` is a supported mode rather than a
caveat, and the default stays at four platform threads: at the same bound it buys nothing now
that the adapter separates scheduling from handling itself, and a platform pool is what the
clients do natively.

`Camunda8VirtualThreadExecutor` runs a virtual thread per submitted task, bounded by a
semaphore whose permits `worker-threads-bound` sizes. Two details are deliberate. The permit
is taken INSIDE the virtual thread, not in `execute`, because the thread calling `execute` is
the client's and blocking it would stall the delivery of every other worker - the defect the
mode exists to avoid. And the bound defaults to the number the platform mode would use, so
switching the mode changes how threads are made and not how much runs at once. With
`stream-enabled` the client wraps whatever executor it was given in its own semaphore of
`max-jobs-active` permits whose acquire waits for the job timeout, so the effective limit is
then the smaller of the two.

**The worker settings are set on the CLIENT, not on every worker.** A worker builder inherits
the client's defaults, and setting them per worker would defeat the environment variables the
next paragraph is about. Only `stream-timeout` has no client-wide equivalent and is therefore
set per worker. `max-jobs-active` defaults to eight per execution slot capped at the client's
32, which is the familiar 32 at four slots and scales down to 8 at one, so the last job of a
batch waits for seven handler runtimes instead of thirty-one. A value below the slot count
fails the boot: some slots could never be busy.

What that setting bounds is one worker's queue and nothing beyond it. The client counts the
jobs it activated and has not finished, activates at most `max-jobs-active` minus that number,
and asks for more as soon as the number is down to thirty percent of it - ten of thirty-two,
two of eight. The workers of one adapter id share the execution slots, so fifteen workers may
hold fifteen times that many jobs in front of four slots, which is why the gate above exists
next to it.

**The three hard coded one-minute locks are gone.** The user-task lifecycle listener, the
BPMS-initiated start and the workflow-ended worker run application code in a transaction
exactly like a task does, so their lock resolves through `Camunda8JobTimeoutResolver` at
adapter, workflow-module and workflow level (no task level, there being no task to key them
by) and defaults to the same five minutes as `job-timeout`. There is no reason for two rules.
One user-task listener job type may belong to several BPMN processes of a module; where those
resolve to different locks the deployment fails guiding, the same way conflicting job timeouts
of one task definition do.

**Environment variables keep their power and lose their silence.** The client applies
`CAMUNDA_*` variables (with legacy `ZEEBE_*` fallbacks) over everything the builder set, and
the probe proved that nothing is logged about it, not even at TRACE: the addresses, the
transport preference, the CA certificate, the TLS authority, the default tenant and the
streaming default could all be replaced without a word in VanillaBP's own log, right after
VanillaBP had validated and reported them. Switching the override off was rejected, because
it is today the only way to reach a client option this adapter does not model.
`Camunda8EnvironmentOverrides` compares what the adapter configured against what the built
client reports and logs a WARN naming every value a variable changed, with the variable, the
property key and both values. Credentials are not among the compared values, so no message
can carry a secret. What that means for credentials is settled by
[Authenticating against a cluster](#authenticating-against-a-cluster): the client installs
a provider from the environment only while the application set
none.

The tests: `Camunda8ExecutionModelTest` for what the two modes resolve to and for the values
which end the boot, `Camunda8VirtualThreadExecutorTest` for the split and its bound,
`Camunda8WorkerThreadsIT` with `Camunda8VirtualThreadsIT` for the same property against a real
cluster, `Camunda8DeploymentServiceTest#listenerLockDefaultsToTheJobTimeout` and
`#conflictingListenerLocksFailGuiding` for the three locks which are no longer hard coded, and
`Camunda8EnvironmentOverridesTest` for the WARN a variable earns. The two numbers above are
measurements: they say what one setup did on one day, and no test repeats them.

### Task processing

`@WorkflowTask` methods are served by **polling job workers**: at
`startWorkflowProcessing` the adapter opens ONE worker per distinct task definition
(the `zeebe:taskDefinition` type) found in the workflow module's BPMN files. Task
wiring is validated during `wireBpmn` (every BPMN task needs a matching
`@WorkflowTask` method - service, send, business-rule and script tasks are
scanned). The other direction is checked as well, and this adapter does not have to
remember it: a `@WorkflowTask` method matching no task of any BPMN process of its
workflow module ends the boot, and the core runs that check itself once every adapter of
the module finished deploying (story 158; classes whose processes are served by another
adapter are not reported - the migration policy).

Execution model per delivered job (at-least-once ordering):

1. open a NEW local transaction, load the aggregate by the ID variable
   (named after `AggregatePersistenceAware.getAggregateIdName()`),
2. invoke the `@WorkflowTask` method through the core's `WorkflowTaskInvoker`,
3. save the aggregate and COMMIT,
4. only then report the outcome to the cluster:
   - normal return → `CompleteJob`; a `NOT_FOUND` answer is tolerated with a WARN
     (the job was already completed by an earlier delivery - the documented
     at-least-once residual, the handler must be idempotent);
   - `TaskException` → `ThrowError` with the error code (BPMN error; the
     aggregate changes stay COMMITTED - the V1 contract);
   - any other exception → the local transaction is rolled back and the job is
     failed with decremented retries and a `retryBackoff` (Camunda 8 redelivers
     after it, see below).

`Camunda8TaskProcessingIT` walks that list against a real cluster
(`#happyPathAndBpmnErrorRoutesBoundary`, `#technicalExceptionFailsJobAndRollsBack` and
`#redeliveryConverges`); `Camunda8WorkflowLifecycleTest` does the same on Quarkus.

**Asynchronous tasks (`@TaskId`) and the renewal of their lock:** a handler
receiving the task ID completes the task later via `ProcessService#completeTask`.
Such a job must not be redelivered while it waits, so after the commit the adapter
extends the job's lock via `UpdateJobTimeout` by `async-task-lock-renewal` (default
`PT1H`). When the window passes, the cluster hands the same job out again, the core
answers that delivery from its delivery record with `COMPLETION_PENDING`, and this
branch extends the lock once more: the renewal is driven by the cluster's own
redelivery and needs no timer of the adapter's. The worker's own job timeout stays
SHORT - it is the crash-recovery horizon for synchronous handlers.

The window has to sit clearly below `vanillabp.delivery.retention` (seven days, following
`vanillabp.outbox.retention` where it is not set itself), since
the delivery record is what answers the redelivery which renews the lock; a value
which is not below it ends the boot naming both properties and both values. The key
was called `async-task-timeout` once and meant a horizon of fourteen days
which outlived that record, so an asynchronous task open longer than it ran the
handler a second time; the old key now ends the boot naming its successor.
`Camunda8AsyncTaskLockRenewalTest` pins the window and the two ways it ends a boot; the
renewal itself is `Camunda8AsyncTaskAgeTest#anOpenTaskIsRenewed`, with
`Camunda8TaskProcessingIT#asyncTaskStaysDormant` and `#completeTaskEndsDormantProcess` against
a cluster.

The core measures how long such a task has been open (`vanillabp.delivery.max-task-age`,
`P30D`, report only) and reports it once. Where `async-task-max-age-action` is
`incident` this adapter stops renewing the lock of an overdue task and fails its job
with no retries left, so the cluster raises an incident naming the workflow aggregate
and the age (`Camunda8AsyncTaskAgeTest` and `Camunda8AsyncTaskAgeIT#anOverdueTaskEndsInAnIncident`).

**The command which reports the outcome is repeated:** a cluster which
cannot keep up rejects commands, as `RESOURCE_EXHAUSTED` on gRPC and as HTTP 503 on
REST, and the client repeats neither of them (its gRPC retry policy is off by default
and would not cover REST anyway; probe P5b measured 19.433 of 20.000 gRPC commands
rejected at the caller against one node). The outbox covers the phase-two commands, so
what was left unprotected was the command the handler itself sends back: a rejected
completion of committed work escaped into the client's fail path and cost the job a
retry. `Camunda8CommandRetry` now wraps the completion, the BPMN error, the fail command
and the lock renewal of all four worker kinds. It repeats only what
`Camunda8Errors.repeatableJobCommandFailure` calls repeatable, which is the outbox
classification named above plus the gone job (repeating a command against a job which no
longer exists would turn the tolerated at-least-once residual into a storm). It stops at
the job's remaining lock, read from `ActivatedJob#getDeadline()` rather than from the
configured timeout, at five attempts, and at once when the module is shutting down (the
job keeps its lock then, and a retry loop must not hold the drain). The waits are the
client's own activation backoff numbers: 50 ms initially, factor 1.6, a tenth of jitter
and a 5s ceiling the five attempts never reach, which keeps the whole sequence below half
a second because a waiting handler occupies an execution slot. When the bound is reached
the original failure is rethrown, so the behaviour after the retry is exactly what it was
before. The bounds and the waits are `Camunda8CommandRetryTest`, and
`Camunda8OutcomeCommandRetryTest` sends through it from all four kinds of worker.

`retry-backoff` (default `PT10S`, resolvable per module, workflow and task like
`job-timeout`, resolved per COMMAND rather than per worker, so nothing has to be aligned
between the processes one worker serves) travels with every fail command which leaves the
job retries. A job failed with `retries(0)` carries none, there being no next attempt. The
error message of a fail command carries the exception's TYPE next to its message, because
that text is what an operator reads in Operate and `NullPointerException` used to write
`null` there. Held by `Camunda8JobTimeoutOverlayTest#retryBackoffResolvesThroughAllFourLevels`,
`Camunda8TaskProcessingIT#aFailedJobIsHandedOutAgainOnlyAfterTheBackoff` and
`Camunda8OutcomeCommandRetryTest#theIncidentNamesTheExceptionType`.

A model may name the backoff of a single element itself, in the task header `retryBackoff`
version 1 read. It is read from the JOB and not from the model while deploying: an
`ActivatedJob` carries the headers of its element, so nothing has to be scanned, and the
value holds for process versions this application never deployed. That is the situation of
an application arriving from version 1.

Which of the two applies follows one rule, the more specific statement, plus a tie-break.
The header speaks about one task, so it beats `retry-backoff` at the workflow, the
workflow-module and the adapter level. Against the task level it loses, because between two
statements of the same reach the one which can be changed without a new process version
wins; where both are set and differ, one line per element says which value went out. A
header which is no ISO-8601 duration costs one line per element and leaves the configured
value in force, where version 1 fell back to `Duration.ZERO` and thereby handed the job out
again at once. `Camunda8RetryBackoffHeaderTest` pins the rule and the tie-break,
`Camunda8TaskProcessingIT#theModelledBackoffReachesTheCluster` the cluster's half of it.

**Shutting down while work is in flight:** the client does not drain. A
worker's `close()` returns without waiting for the jobs it already handed to a handler,
and `CamundaClient.close()` interrupts every running handler milliseconds later. So
`stopWorkflowProcessing` closes the module's workers and then waits `shutdown-grace`
(default `PT20S`) for the handlers which are still inside the application; every handler
registers its delivery in a per-module `Camunda8Drain`, which is what the wait watches.

**And for the workers themselves.** The handler drain deliberately did not wait for
`JobWorker#isClosed()`, because that answer also covers the activation request in flight
and closing a worker does not cancel it, so an idle worker keeps reporting open for up to
`request-timeout`. What that costs was known, what it buys was not. Measured against
`camunda/camunda:8.9.16` with a plain client and no VanillaBP: an activation request which
is parked at the cluster when its client is closed **stays parked**, and a job created
afterwards is activated into it and answered by nobody. `job-timeout` `PT20S`, one
application closed and the next one starting after the gap below, twenty runs for the two
rows which say so and three respectively five for the others:

|            gap between the two applications            |    first job of the new one     |
|--------------------------------------------------------|---------------------------------|
| 3 s                                                    | 20109 / 20120 / 20202 ms        |
| 7 s (20 runs)                                          | 20027 to 21559 ms, median 20829 |
| 12 s (beyond `request-timeout`)                        | 15 / 23 / 25 ms                 |
| 7 s, workers left open instead of closed               | 20159 / 20194 / 20244 ms        |
| 7 s, the shutdown waiting for `isClosed()` (20 runs)   | 10 to 29 ms, median 10          |
| 7 s over gRPC, nothing waited for (5 runs)             | 7 to 22 ms                      |
| 7 s with `stream-enabled`, nothing waited for (5 runs) | 8 to 26 ms                      |

So the hole is exactly as long as an activation request can outlive its client, closing
the workers first does not shut it, and waiting for them does. The last two rows say where
it lives: over gRPC and over the push path the cluster releases what a gone client held,
and only the REST poll, which `prefer-rest-over-grpc` defaults to, keeps it. The wait is part of
`shutdown-grace` and cost 8,2 to 8,5 seconds in those runs, which is the remainder of the
ten-second request window. `PT0S` switches it off together with the handler drain, and the
shutdown then says at INFO what stays open.

The line the drain writes reports what this adapter knows: how many workers IT closed, and
whether the cluster released them. A worker still holding its request when the grace passes
is a WARN naming `job-timeout` as the delay the next application pays. And because the
promise is only worth what the last shutdown path does, `Camunda8ClientFactory.close()`
closes the workers of every workflow module which never reached `stopWorkflowProcessing`
before it closes the client, with a warning that a hook was missing.

What is still running when the grace passes is named per job (job key and task) and then
cut off. Such a delivery is not reported as a job failure: while the module is shutting
down, all four handlers leave the job to its lock rather than sending `newFailCommand`,
so the cluster redelivers it with its retries intact and the delivery record answers the
redelivery. The rule is the adapter's STATE and never the exception type - a handler
interrupted by the closing client throws like any other. The default sits below the
shutdown budgets of Spring Boot (`spring.lifecycle.timeout-per-shutdown-phase`) and
Kubernetes (`terminationGracePeriodSeconds`), both 30 seconds, so VanillaBP is never the
reason a container is killed; a larger value warns at startup that those have to be
raised with it. Held by `Camunda8ShutdownDrainTest`, `Camunda8DrainTest` and
`Camunda8ShutdownGraceTest`, and against a real cluster by
`Camunda8ShutdownDrainIT#aCutOffHandlerCostsNoRetry` with `#aHandlerWithinTheGraceFinishes`.
The table above is a measurement.

Task-scoped configuration (see the four-level pattern of the VanillaBP
configuration model - the most specific configured value wins):

```yaml
vanillabp:
  adapters:
    myengine:
      type: camunda8
      job-timeout: PT5M                  # adapter level (default PT5M)
      async-task-lock-renewal: PT1H      # adapter level only (default PT1H)
      retry-backoff: PT10S               # adapter level (default PT10S)
      async-task-max-age-action: report  # adapter level only (default report)
      shutdown-grace: PT20S              # adapter level only (default PT20S)
  workflow-modules:
    loan-approval:
      adapters:
        myengine:
          job-timeout: PT2M       # per workflow module
      workflows:
        LoanApproval:
          adapters:
            myengine:
              job-timeout: PT1M   # per workflow (BPMN process ID)
          tasks:
            assessRisk:
              adapters:
                myengine:
                  job-timeout: PT10S     # per task (task definition)
                  retry-backoff: PT30S   # per task, for a slow dependency
                  fetch-variables: all   # per task, for a @TaskParam nobody can derive
```

Limitation: Camunda 8 workers subscribe by job type only. If the SAME task
definition appears with DIFFERENT resolved job timeouts within one module, the
startup fails with a guiding message (one worker per job type - give the
definitions distinct names or align the timeouts), see
`Camunda8DeploymentServiceTest#conflictingTaskLocksFailGuiding`.

**Completing/canceling async tasks (`ProcessService#completeTask`/`#cancelTask`):**
the adapter locates the job by its key (the `@TaskId` value). The
awareness probe and the phase-one check are the same NON-ADVANCING command -
`UpdateJobTimeout` by `async-task-lock-renewal` (which conveniently renews the open
job's lock): success means the job exists, `NOT_FOUND` maps to
"unknown", a connection failure to "BPMS unavailable" (never falls back to
another adapter). A refusal which is neither - HTTP 400, on gRPC `INVALID_ARGUMENT` -
means the cluster HAS the job and no worker has it activated right now, so the probe
answers `ACTIVE`. That is the everyday answer for an asynchronous task whose lock ran out:
the job waits in the queue until a worker takes it again, and reading the gap as an outage
would send the caller into retries for a task which is alive. Measured on 8.8.37, 8.9.19 and
8.10.0-alpha5, where a job never activated, a job whose lock expired and a job with an open
incident all answer the same way.

The word probe makes this sound like a read, and it is not. `UpdateJobTimeout` WRITES the
timeout it carries, so a probe moves the deadline of a job another worker is holding, in
whichever direction `async-task-lock-renewal` points. Measured on all three lines: a probe
which set two seconds on a job activated for five minutes let a second activation pick the
same job key three seconds later, while the first holder still believed it owned the job. A
short renewal is therefore not only a shorter lock, it also shortens the lock of whoever
holds the job while somebody asks about it.

The phase-one check runs as a PRE-COMMIT transaction
synchronization - as late as possible, minimizing the window between check and
the phase-two dispatch (fewer stale outbox entries). Phase two (after the
commit, through the outbox) sends `CompleteJob` respectively `ThrowError` (the
BPMN error code routes boundary events); a `NOT_FOUND` answer is tolerated with
a WARN (at-least-once residual). Zeebe notifies no worker about a job it took away, so
`@TaskEvent CANCELED` is not delivered at the moment the task goes: it arrives at the next
wake-up of the same workflow, see
[Task cancellation arrives at the next wake-up](#task-cancellation-arrives-at-the-next-wake-up-not-at-the-moment).

**User tasks:** Camunda-managed user tasks (`zeebe:userTask`) with an
EXTERNAL form reference - the reference IS the task definition (V1 convention).
During `wireBpmn` the adapter adds the V1-COMPATIBLE lifecycle task listeners to
the BPMN model: per user task `creating` (→ `@TaskEvent CREATED`) and `canceling`
(→ CANCELED), type `io.vanillabp.userTask:<external form reference>`,
`retries="1"`; the VanillaBP `creating` listener is inserted as the FIRST and the
`canceling` listener as the LAST listener (modeller-defined ones stay in
between). Version 1 wrote the same listeners with `retries="0"`, so the models an
upgraded application deploys differ in that one attribute and the cluster gives
them a new process version, see
[the retry a lost listener delivery needs](#the-retry-a-lost-listener-delivery-needs).
Listener jobs are consumed like normal jobs (one worker per
listener job type), ALWAYS completed, and deliver the USER-TASK KEY as `@TaskId`;
a failing notification fails the listener job with no retries left, so the first
failure is the incident. The notification handler is OPTIONAL. `completeUserTask` sends `CompleteUserTask` by
the user-task key after the commit (phase one re-checks existence pre-commit via
an empty `UpdateUserTask` carrying only an audit `action` - also the awareness
probe; note: modeller-defined `updating` listeners would fire on probes).
**`cancelUserTask` is NOT supported by any cluster up to 8.9:** the engine
offers no command to cancel a Camunda-managed user task by BPMN error (ThrowError
is job-based) and V1's marker-variable workaround is broken by V1's own admission
- a guiding error naming the release line explains it; the listeners it needs
arrive with Camunda 8.10, so it can only ever come on a line built against 8.10
or later. The wiring and the V1 order of the listeners are `Camunda8UserTaskWiringTest`. The
lifecycle against a cluster is `Camunda8TaskProcessingIT#userTaskCreatedAndCompleted`,
`#userTaskCanceledOnInstanceCancellation`, `#userTaskEdgeCases` and
`#cancelUserTaskUnsupportedGuiding`, with
`Camunda8WorkflowLifecycleTest#userTaskNotificationAndCompletion` on Quarkus.

**Message correlation:** `correlateMessage` publishes AFTER the commit
(outbox) with `correlationKey = correlationId ?? aggregate ID` and NO variables
(payload doctrine). During `wireBpmn` the adapter INJECTS the `zeebe:subscription`
correlation-key expression `=<aggregate-ID variable>` into message subscriptions
lacking one - catch events correlate via the aggregate ID without manual model
tweaks (existing expressions stay untouched; V1 models deploy byte-identically).
The injection needs a workflow aggregate, and a BPMN process no `@WorkflowService`
class of this application claims has none. Such a file is REFUSED while starting,
in `prepareBpmn` and before any element of it was rewritten: the cluster demands a
`zeebe:subscription` on the message of every executable process which waits for one,
and it answers a missing one by rejecting the whole FILE (8.9.16 says *Must have
exactly one zeebe:subscription extension element*; a static value is refused too, the
key has to be an expression). So the file would not deploy either way, the process
next to that one included, and ending the boot here is the earlier half of a failure
which happens anyway. The message says which file, which process and which element it
is about, and it asks for one of the two things which fix the model: the correlation
key, or an `isExecutable` taken off a process nothing is meant to run. What VanillaBP
does NOT do is put a substitute into a model it does not own.
`Camunda8UnclaimedProcessTest` holds the verdict, the message and the claimed process
which still gets its real key next to an unclaimed one;
`Camunda8RenamedProcessIT#theClusterRejectsTheWholeFileOverAMessageWithoutASubscription`
sends such a file to a cluster, so the premise is measured on every run instead of
remembered. An unclaimed process whose model IS complete costs the boot nothing, and
`Camunda8RenamedProcessIT#theWorkflowOfTheOldIdIsFinishedAfterTheRename` deploys one.
WITH a correlation id the outbox idempotency key doubles as the Zeebe `messageId`,
so redelivered dispatches are rejected engine-side WITHIN THE MESSAGE TTL (engine
default; a redelivery after the TTL could correlate again - the documented
uniqueness window). WITHOUT one, deduplication is deliberately absent.
`startWorkflowByMessage` publishes with an empty correlation key, the start's
idempotency key as `messageId` and ONLY the aggregate-ID variable.
`awarenessOfWorkflow` locates the workflow with a process-instance search, which is
one of the reasons the adapter requires a cluster it can search, see
[What needs a cluster which can be searched](#what-needs-a-cluster-which-can-be-searched).
A search which fails is `BPMS_UNAVAILABLE` and never a guess, because a wrong yes
routes the correlation to the wrong BPMS.
`Camunda8TaskProcessingIT#correlateMessageResumesInstanceViaInjectedSubscription`,
`#duplicateCorrelationDispatchIsDeduplicated` and `#startWorkflowByMessageStartsInstance` hold
the three commands, `Camunda8WorkflowLifecycleTest` the Quarkus half.

### The lease of an activation

From the 8.10 line on, a worker can activate a job WITH A LEASE. The job then carries a token, and
the cluster takes the completion, the failure and the BPMN error of that job only from whoever
holds the current token. An activation which follows an expired lock supersedes the token before
it.

It is worth having where the lock expires while the business method is still running. The cluster
hands the job out again, the method runs a second time, and both runs try to answer. Without a
lease the first answer wins, and which run that is comes down to which one finishes first. The age
of the activation decides nothing. Measured against `camunda/camunda:8.9.19` on 2026-09-21 with one
pod and one worker, both orders were run: the run which answered first was taken both times, and
the other run got its command rejected with `NOT_FOUND`, which reads exactly like a job that is
long gone. With a lease the older answer is refused and the workflow continues with what the run
which finished last wrote. The work is done twice either way; the result is better and the
rejection is visible.

Whether the second run happens at all depends on the client. Up to `8.8.36` and `8.9.17` a worker
which still held a job stopped asking for work, so its own expired job could only be picked up
somewhere else, in practice by a second pod. From `8.8.37` and `8.9.18` on the worker keeps asking
while its handler runs, and the measurement above is that case: the same worker activated its own
expired job again about a second after the lock had run out. This build pins `8.8.39` and `8.9.21`,
so both GA lines behave that way. The 8.10 client still carries the old behaviour up to
`8.10.0-alpha5`, and 8.10 is the only line which has a lease at all: measured there on 2026-09-21,
a single worker holding its job saw no second activation for 120 seconds, with the lease and
without it. So on the line which can lease, the race a lease decides still needs a second worker
today. Only a repaired 8.10 client has both halves at once.

The workers which lease are the ones which hold their job from the activation to the answer: the
user-task listeners, the listeners somebody modelled, the cancel listeners VanillaBP writes, the
start events the cluster fires itself and the end of a workflow. A task worker leases only where
none of the task definitions of its job type completes asynchronously - phase two completes such a
task by key, hours later and from a dispatcher which holds no token, so a leased job of an
asynchronous task could never be completed at all.

Because a lease is a ratchet - no command removes one, and a worker of the same job type which does
not lease never sees a leased job again - the application has to say what it wants. There is no
default:

```yaml
vanillabp:
  adapters:
    c8:
      job-lease: use          # or: do-not-use
```

On a line whose cluster has no lease the same key is accepted and ignored, with one line in the
boot log saying so, so one configuration serves an application on either line. On a line which has
one, a missing key ends the boot with a message explaining the choice. What a rollback costs is the
reason for that: an application which leased jobs and then moves back to the 8.9 line leaves those
jobs standing, because its new workers do not lease and the cluster does not hand a leased job to
them.

An extension which opens listener workers on the same cluster leases with the adapter, through
`Camunda8Workers.leaseTheActivations`. Two components serving one job type with different opinions is the
starvation the ratchet describes, and the extension's workers are the ones which would starve, so an
application running an extension which has not followed leaves the key at `do-not-use`.

An answer refused because another activation holds the job arrives as HTTP `409` (`INVALID_STATE`),
on gRPC as `FAILED_PRECONDITION`. The adapter neither repeats it nor fails the job over it: the run
converged with a redelivery, and the newer run has answered. Why the code alone decides that is
decision 36 in the repository's DECISIONS.md.

### Elements another runtime serves

An element carrying the attribute `zeebe:modelerTemplate` was configured from an ELEMENT TEMPLATE.
A Camunda connector is the most common of those, and this is the only thing in the model which says
that somebody else's runtime owns an element. It is not read unless the application says so, because
a company writes element templates for its own plain job-worker tasks as well, and passing those
over would leave a task this very application serves without a worker and without a validation.

`vanillabp.adapters.<id>.allow-connectors` is the switch, default `false`, resolvable at three
levels with the most specific configured value winning:

```
vanillabp.adapters.<id>.allow-connectors
vanillabp.workflow-modules.<m>.adapters.<id>.allow-connectors
vanillabp.workflow-modules.<m>.workflows.<w>.adapters.<id>.allow-connectors
```

There is no TASK level. That level is keyed by the task DEFINITION, and the task definition of a
connector is the connector's own type, which every element using that connector shares and which
carries dots and colons a relaxed binder splits on. Which single element is left alone is decided
by the model. A value set at task level earns one guiding warning naming the three levels which
work, and the boot goes on.

Where the switch is on, an element carrying the marker AND a `zeebe:taskDefinition` is left to the
runtime which owns it:

- `Camunda8TaskWiring#tasksOf` passes it over, so it produces no `BpmnTaskSpec`, the wiring
  validation never asks for a `@WorkflowTask` method and `startWorkflowProcessing` opens no worker
  for its job type. That is the whole mechanism: the connector runtime subscribes to the job type
  and serves the job;
- `Camunda8Scoping#apply` leaves its job type alone under `use-prefix`, and the
  `zeebe:formDefinition externalReference` of the same element with it. The job type names a
  runtime somebody else deployed cluster-wide, and prefixing it would rename something this
  application does not own. That reaches wider than the wiring does: an ad-hoc subprocess with an
  agent connector, a message throw event and an end event all carry a task definition, and none of
  them is a task `tasksOf` collects;
- a Camunda-managed user task is deliberately NOT passed over, although VanillaBP 1 passed it over.
  A `zeebe:userTask` is served by the cluster's task list, and an element template on it presets an
  assignee or a form. Passing it over would cost its lifecycle listeners, its CREATED and CANCELED
  notifications and the ability of `ProcessService#completeUserTask` to complete it, for a marker
  which says nothing about who serves the task;
- an element carrying the marker without a task definition is none of this. An inbound connector is
  the case, it correlates by a message name rather than by a job type, and covering it needs a
  second rule about message names which this version does not have.

Every boot of a workflow module which allows connectors writes a framed WARN naming the key, the
module, every element it handed over with its element template, what that costs at runtime and what
the application gives up while it runs connectors. Nothing silences it, see
[decision 23](./DECISIONS.md#23-connectors-are-allowed-per-adapter-and-every-boot-says-what-they-cost).
Where the switch is on and no element of the module uses it, the boot writes one line instead of
the frame. Where it is off and elements carry the marker, the adapter names them and the three
levels before the core's wiring validation ends the boot over the job type nobody serves.

`Camunda8ConnectorsTest` holds what is passed over and what is not, `Camunda8ConnectorsReportTest`
the three messages, `Camunda8AllowConnectorsBootTest` and `Camunda8JobTimeoutOverlayTest` the three
levels per platform, and `Camunda8ConnectorsIT` with
`Camunda8WorkflowLifecycleTest#aConnectorElementIsLeftToItsOwnRuntime` the same against a cluster on
both platforms.

### Listeners somebody modelled

A `zeebe:taskListener` of a `zeebe:userTask` and a `zeebe:executionListener` of any element are
places where the cluster lets the application in. Both name a job type, both produce a job when the
cluster reaches them, and a job type nothing subscribes to stops the workflow right there without an
incident and without a line in any log. VanillaBP 1 served such a listener with a `@WorkflowTask`
method and said nothing about it; this version serves it where the application asks for it, and says
what that costs.

`vanillabp.adapters.<id>.allow-listeners` is the switch, default `false`, resolvable at three levels
with the most specific configured value winning:

```
vanillabp.adapters.<id>.allow-listeners
vanillabp.workflow-modules.<m>.adapters.<id>.allow-listeners
vanillabp.workflow-modules.<m>.workflows.<w>.adapters.<id>.allow-listeners
```

There is no TASK level, and the reason is a different one than for `allow-connectors`: a task level
is keyed by the task DEFINITION, and whether a listener becomes a task at all is what this key
decides, so at the moment the key is read there is no task definition to key a level by. A value set
at task level earns one guiding warning naming the three levels which work, and the boot goes on.

A listener is served only where a `@WorkflowTask` method names its job type, and the job type IS the
task definition such a method names. A job type is a name in the cluster which anybody may subscribe
to, so a model carrying one says nothing about who serves it while a method naming it does. Only the
task-definition route counts: `@WorkflowTask(id = ...)` names the ELEMENT, and one element may carry a
task and a listener at once. A listener no method names is not refused and not passed over in silence
either: `sayWhichListenerJobsNothingServes` names it and the boot goes on, the way
`reportUnservedAdHocSubProcesses` does, because a worker the application runs itself may be the answer
while the cluster creates the job either way.

`Camunda8Listeners#listenersOf` is what reads a model, and it is asked while the BPMN file is
PREPARED rather than while a process of it is wired. Two things follow from the moment. The job
types are still the ones the modeller typed, so a message can quote them, and the listeners
VanillaBP writes itself are not in the model yet: `wireBpmn` adds the user-task lifecycle listeners
and the start listeners afterwards. Beyond the moment there is the job-type prefix
`io.vanillabp.`, which every listener of VanillaBP and of its extensions carries, the Business
Cockpit extension included. A third-party extension choosing a prefix of its own is not known here
and nothing can ask for one, which is why the startup report names every listener this adapter
treats as the modeller's: a job type a reader does not recognise is the one line worth a second
look.

Where the switch is off and a model carries a served listener, `readTheListenersTheModelCarries` ends
the boot. That is deliberately not left to the core's wiring validation, which is what
`guideTowardsAllowingConnectors` does for a connector: a connector asks VanillaBP to leave an
element alone, so the validation finds a task nothing serves and ends the boot by itself. A listener
asks VanillaBP to serve something, so without the key there is no task spec and nothing for the
validation to miss. The message names every listener of the process with its element, its event and
its job type, the three levels and the cost.

Where the switch is on, the listener is a task like any other one:

- it becomes a `BpmnTaskSpec`, so `validateTaskWiring` asks for a `@WorkflowTask` method and ends
  the boot where none exists, and `validateNoUnwiredWorkflowTaskMethods` reports a method which
  matches no listener of any wired process. Version 1 wired its listeners privately and had neither
  direction;
- `Camunda8Scoping#apply` prefixes its job type under `use-prefix` like every other task definition
  of the workflow module, because that is what it is. The handler translates the prefix away again
  before the core is asked, and a listener no method names keeps the name the modeller typed, for the
  reason a connector's job type keeps its;
- `Camunda8ModelledListenerHandler` consumes its jobs. It is a class of its own rather than a flag
  on `Camunda8UserTaskListenerHandler`: there the job type is known by construction, the
  notification is optional and the user-task key is reported so the task can be completed later, and
  none of that holds for a modelled listener;
- what the completion carries depends on the listener, in the three cases of
  [decision 1](./DECISIONS.md#1-a-command-carries-the-shared-aggregate-values-and-the-aggregate-id-variable-nothing-else).
  An execution listener on `end` completes like a service task, with the shared values and the
  aggregate-ID variable, so a method serving it may change the workflow aggregate and the gateway
  behind the element decides on what it wrote. An execution listener on `start` completes with
  nothing, because the cluster would keep those values local to the element, where they shadow the
  process variables of the same name and swallow the element's own writes of that name. A task
  listener completes with nothing either, because the cluster refuses that payload and names its
  issue 23702. In the latter two a change of the aggregate is kept by the application and reaches
  the cluster at the next real sync point of that workflow. On Camunda 7 every listener writes in
  the engine's own transaction, which is worth knowing when a module runs on both.

The event is part of the listener's identity: the wiring made one task of one listener, so one
method serves one event of one element. `@TaskEvent` receives `TaskEvent.Event#CREATED` for every
listener, which is the only value that works at all, because a method without that parameter
subscribes to `CREATED` alone and any other value would leave such a method silently uncalled.
`TaskEvent.Event` has no value for a listener's own event, and the startup report says so.

Four shapes end the boot, each with a message naming the listener and the way out.
`refuseListenersSharingAJobType` answers two served listeners of one element under ONE job type: one
method would serve both events and nothing it could ask would say which one it is in. Two listeners of
one element under DIFFERENT job types are served, one method each.
`refuseAStartListenerTheClusterRefuses` answers a `start` execution listener on a start event, whatever
the key says: the cluster refuses the whole file over it, so every process the file declares would be
lost, and `end` is what VanillaBP attaches to a start event itself.
`refuseAsynchronousListenerMethods` answers a method declaring `@TaskId`: the cluster completes a
listener job the moment the handler returns, so such a task can never stay open and the id would
complete nothing. A method throwing `TaskException` is
answered at runtime rather than at boot, because no signature shows it: the handler names the cause
instead of letting an incident say nothing about it, since the cluster is inside a transition of its
own and has no token to route.

Every boot of a workflow module whose listeners are served writes one framed WARN naming the key,
the module, every served listener with its element, its event and its job type, what it costs and
the way back. Nothing silences it, see
[decision 27](./DECISIONS.md#27-a-listener-somebody-modelled-is-a-task-and-only-where-the-application-asked-for-it).
Where the switch is on and no model of the module carries a listener, the boot writes one line
instead of the frame.

`Camunda8ListenersTest` holds what is read out of a model, the pairs of listeners and the prefixing, and
`Camunda8ListenersReportTest` the report of a boot, the refusal without the key and the one over a
`@TaskId` method.

### Ad-hoc subprocesses

An ad-hoc subprocess holds activities without sequence flows between them and runs the ones somebody
picked. Nothing was written here to support it, and that is the point: `Camunda8TaskWiring#tasksOf`
reads service, send, business rule and script tasks, an `adHocSubProcess` is none of them, and
`owningProcessId` walks the parent chain up to the `bpmn:process`, straight through the element. So
an activity inside it lands in the task specs like any other task, is validated as mandatory, gets a
worker and is served through the ordinary path. There is deliberately no `@WorkflowTask` method for
the element itself.

What the model reads is `zeebe:adHoc activeElementsCollection`, a FEEL expression over a process
variable, evaluated once when the workflow enters the element. VanillaBP shares the attributes of the
workflow aggregate with every command it sends, and a collection travels as a list, so a
`List<String>` attribute holding element ids is all an application needs. Who filled that attribute
is none of the adapter's business: a user task, a decision table the cluster evaluated, or a model
provider.

Two things the element brings are not visible in the model and are therefore reported.

`Camunda8TaskWiring#concurrentTokenElementIdsOf` names the element. `AdHocSubProcessImpl extends
SubProcessImpl`, so it reaches the `SubProcess` branch and is dropped there by
`triggeredByEvent()` - it is read as an `AdHocSubProcess` of its own instead. It is named whichever
flavour the model uses and however short the list looks, because the collection is an expression: a
list of one today is a list of two as soon as the data behind it changes. The activities INSIDE the
element are not named, since the element is where the second token comes from and five inner ids
would make the warning unreadable.

`Camunda8TaskWiring#unservedAdHocSubProcessIdsOf` names the flavour this adapter does not serve. An
ad-hoc subprocess carrying a `zeebe:taskDefinition` of its own expects a worker which decides round
by round which activities to activate, by completing the job with
`newCompleteJobCommand(key).withResult(r -> r.forAdHocSubProcess().activateElement(...))`. Neither
the `@WorkflowTask` contract nor the adapter SPI can express that outcome, so no worker is opened,
the workflow stops at the element and the job ends in an incident once its retries are used up. One
WARN per BPMN process says so, and the boot goes on, see
[decision 24](./DECISIONS.md#24-an-ad-hoc-subprocess-nothing-serves-is-named-and-the-boot-goes-on).
An element carrying a `zeebe:modelerTemplate` as well is left out of that report, through
`Camunda8Connectors#elementTemplateOf`: the Camunda AI agent is an element template on exactly this
element, and a connector runtime fetches its job.

`Camunda8ConcurrentTokensTest` and `Camunda8AdHocSubProcessTest` hold both readers and the message,
and `Camunda8AdHocSubProcessIT` runs the served flavour against a cluster: two of three activities
are named by the aggregate, the two run, the third does not, and the workflow leaves the element
with no completion condition modelled.

### What a worker fetches

A Camunda 8 worker which names no variables receives the complete variable scope of the
process instance with every job, which Camunda warns can be "tens or more variables, of
arbitrary size" and advises against: fetch only what the handler needs. VanillaBP can be
stricter than a plain client user, because the workflow aggregate is the source of truth.
The handler is served from the application's own database, so the job has to carry only
what the ADAPTER reads out of it, and that is a short list the adapter derives from the
deployed models:

- the variable holding the workflow aggregate's id, named after the aggregate's id
  attribute. Every worker kind begins by reading it;
- the multi-instance variables of the iterations enclosing the element the job belongs to,
  which this adapter injected into the model while deploying. They depend on
  the element, which is why the list is not a constant;
- every variable a `@TaskParam` of the served tasks reads, reported by the core
  (`WorkflowTaskInvoker#taskParameterNames`). The adapter used to read those names off the
  MODEL instead - the mapping targets, script and decision result variables and
  multi-instance output collections a Camunda 8 process declares - because that was the
  only place the adapter could see one. It was a guess in both directions: a model declares
  names nobody reads, and a handler may read a name no model declares. The core scanned the
  annotations while wiring anyway, so it answers exactly, and the model scan is gone rather
  than kept as a second source. The core is asked with BOTH keys a method can be wired by,
  the job type and the element id, because a method carrying `@WorkflowTask(id = ...)`
  answers to the element alone and asking for the job type left its variables unfetched.
  The workflow-end listener is the exception: a `@WorkflowEnded` method cannot declare a
  `@TaskParam`, so that worker stays at the aggregate's id.

What stays out is everything nobody reads: what only the aggregate sync wrote into the
instance, and what the model declares for its own purposes. On an aggregate with a few
large attributes that is the whole of what Camunda's warning is about, and it is a copy of
data the handler is holding anyway.

The list belongs to the WORKER and not to the delivery. One worker serves a job type
across the BPMN processes of a workflow module, so its list is the union over everything
it serves; two processes disagreeing about the name of the aggregate id are no conflict,
`fetchVariables` being a list. The list is sorted, because the gateway treats two job
streams as equivalent only when job type, worker name, timeout and fetch variables match
and that comparison has to survive a restart of the same application version.

Two cases fetch everything instead. A worker serving a start event the cluster fires
itself hands the variables of that start to the core, which copies them into the aggregate
it builds, so there is nothing to leave out. And where no workflow service serves the BPMN
process, the aggregate's id variable cannot be named at all; such a worker asks for
everything rather than for a list which may be missing exactly what its handler needs.

`vanillabp.adapters.<id>.fetch-variables: all` is the escape hatch, resolvable per
workflow module, workflow and task. A statically named `@TaskParam` does not need it, which
leaves the case the scanner cannot see: a name assembled while the delivery runs. Such a
read is not answered with a null. It fails the delivery with a message
naming the variable, the list and the property, and saying that the name is not on the
method - so the cluster raises an incident instead of the handler computing on a value which
was quietly dropped.

Every worker logs at DEBUG what it fetches when it opens. When somebody reports a variable
their handler no longer sees, that line answers the first question.

`Camunda8FetchVariablesTest` holds the derivation, the union, the escape hatch and that line,
`Camunda8UnfetchedVariableTest` the two messages a delivery writes for a name outside the list,
and `Camunda8TaskProcessingIT#aDeclaredTaskParameterIsFetched` with
`Camunda8WorkflowLifecycleTest#declaredTaskParametersAreFetched` the same against a cluster.

### What a `@TaskParam` receives

Binding a value to the type a handler declared belongs to the platform, not to this
adapter. `Camunda8JobHandler#getTaskParameter` returns what `job.getVariablesAsMap()`
holds and converts nothing. Which types a `@TaskParam` may be declared as, and when a
value is refused instead of converted, is written down once, in the section "What a
`@TaskParam` may be declared as" of `migration-adapter/README.md` in
`adapter-platform-integration`.

What belongs here is which value a handler is given in the first place, because Camunda 8
answers that differently from the other engines VanillaBP serves.

The cluster holds JSON. A decimal comes back as a `Double` and a whole number as a `Long`,
whatever an aggregate shared, and no value a job carries is ever a `BigDecimal`, a
`BigInteger` or a `Float`. A parameter which names one of those types is still served, the
platform converting the `Double` the cluster returned into it. What shows the difference is
`@TaskParam Object`, which hands the handler the class the cluster returned: on Camunda 7
the same model gives a `BigDecimal` where this one gives a `Double`.

The scale of a decimal is dropped by the broker. An aggregate sharing `120.50` is read back
as `120.5`, on every route and whatever `ObjectMapper` the application configures, because
the broker keeps its variables as MessagePack and MessagePack has no decimal type. A
`BigDecimal` parameter therefore arrives with the scale of the number the cluster holds and
not with the one the application wrote.

A task carrying no `zeebe:ioMapping` still receives the value. Zeebe resolves a job's
variables up the scope hierarchy, so a `@TaskParam` finds a process variable wherever its
task sits, a branch of a parallel gateway included. The same model on Camunda 7 hands the
handler `null` as soon as the task does not stand straight in the process, that engine
reading the task's own scope. An application porting a model between the two meets that
difference, and no message of either adapter names it.

A value the declared type cannot hold costs the job its retries before anybody reads about
it. The conversion ends the invocation, the adapter fails the job, and the cluster hands
the job out again, because a job's retries do not know that this failure will read the same
on every attempt. The message shows up three times: in the job's own `errorMessage`, in the
incident of type `JOB_NO_RETRIES` which follows the last attempt, and in the adapter's WARN
log, the only one of the three carrying the stack trace. Up to that incident an operator
waits `retry-backoff` per attempt (default `PT10S`, resolvable per workflow module,
workflow and task), which is the property to lower where such an incident should arrive
quickly.

`Camunda8ParamTypesIT` holds nine pairs against a cluster, five which are served and four
which are refused:

|                what the model maps in                | what the handler declares |                               what happens                               |
|------------------------------------------------------|---------------------------|--------------------------------------------------------------------------|
| `=total`, a shared `BigDecimal` of `120.50`          | `Double`                  | `120.5`                                                                  |
| `=total`                                             | `BigDecimal`              | `120.5`, converted from the `Double` the cluster returned                |
| `=total`                                             | `int`                     | refused, naming the `120` the parameter would have held                  |
| `=rate`, a shared `Float` of `0.1f`                  | `Double`                  | `0.1`                                                                    |
| `=count`, a shared `Long` of `3000000000`            | `long`                    | `3000000000`                                                             |
| `=count`                                             | `int`                     | refused, naming the `-1294967296` the parameter would have held          |
| `=huge`, a shared `BigInteger` of `9007199254740993` | `long`                    | `9007199254740993`                                                       |
| `=huge`                                              | `Double`                  | refused, naming the `9.007199254740992E15` the parameter would have held |
| `=string(total)`, the text of the decimal            | `int`                     | refused the same way, the text of a number being a number                |

The two statements no test of this repository holds are the one about `@TaskParam Object`
and the one about the missing input mapping. Both were measured on 2026-09-16 against a
`camunda/camunda:8.9.19` cluster with one partition and secondary storage in the RDBMS mode
of the 8.9 line, the same setup the table above ran on, and neither of them is a promise
this repository keeps: a cluster which changes its answer would change them.

### Viewing workflows

`ProcessService#getProcessDefinitions`, `#getBpmnXml` and `#getWorkflowHistory` are served
from two sources:

1. **What this application version deployed** - VanillaBP's deployment pipeline reads every
   workflow module's BPMN at each boot, so the adapter keeps those models (per adapter id,
   with the process definition key and version the CLUSTER assigned at deployment) and serves
   definitions and BPMN XML from them: no cluster round trip and no consistency lag, which is
   what a viewer opened right after a deployment would otherwise run into.
2. **The cluster's query API** for everything instance-related: which version a running
   workflow actually uses, the element history, and definitions deployed by PREVIOUS
   application versions (a long-running workflow surviving a redeployment).

**Consistency caveats - by design, never errors:**

- The query API is eventually consistent: a workflow started moments ago may not be visible
  yet. The adapter reports what is visible - a viewer polling shortly after sees the data. A
  cluster which did not answer at all costs the element history, reported as `null` (the SPI's
  "not supported by the underlying BPMS") with a WARN naming the reason once per adapter id,
  and never as an error.
- Definitions of previous application versions are only resolvable through the cluster, so a
  cluster which did not answer makes `getBpmnXml` answer with the core's guiding
  `ProcessDefinitionNotFoundException`.

The adapter-native process definition id is the **process definition key**, the history
context of a call activity its called **process instance key**, and the XML returned is the
model AS DEPLOYED (VanillaBP's wiring modifications included).

`Camunda8WorkflowViewerTest` covers what comes from the deployment, `Camunda8ViewerQueryTest`
what comes from the cluster and what each answer does where the cluster stops answering, and
`Camunda8ViewerApiIT`, `Camunda8LocatingWorkflowsIT#theViewerFindsTheWorkflow` and
`Camunda8WorkflowLifecycleTest#theViewerServesTheDeployedModelAndItsHistory` the whole thing
against a real cluster on both platforms.

### Decision tables

The `.dmn` files of a workflow module are deployed by the boot, in the SAME
`DeployResourceCommand` as its BPMN files, so process and decision are one deployment and
one version step. A business rule task naming `zeebe:calledDecision` then has the CLUSTER
evaluate the decision, which is why the wiring leaves such a task alone: there is no
`@WorkflowTask` method to ask for. A business rule task carrying a `zeebe:taskDefinition`
is an ordinary VanillaBP task and is validated like a service task.

What the decision produced is a variable of the workflow, so a following task reads it
through its input mapping and a `@TaskParam` parameter -
`Camunda8DecisionTableIT` runs both rules of a table against a real cluster and asserts
what reaches the handler.

Under `use-prefix` the decision ids are rewritten like the process ids, and the
`decisionId` of the business rule tasks is rewritten with them, so both name the same
decision (`Camunda8DeploymentServiceTest#aBusinessRuleTaskFindsItsRenamedDecision`). A
`decisionId` given as a FEEL expression stays untouched. What this mode cannot follow is a
reference to a decision the module does not deploy: that one is renamed here and not in the
cluster.

### Keeping workflow modules apart

The [name-clash-avoidance mode](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-modules#how-name-clashes-are-avoided)
decides where a workflow module's models land. `by-adapter` deploys into a multi-tenancy
tenant named after the module (`tenant-id` overrides the name, for the whole adapter or for
one workflow module) and the job workers subscribe for that tenant; `use-prefix` deploys into the default tenant with prefixed
identifiers instead, process ids, message names, error codes, signal and escalation names,
JOB TYPES and the user-task form reference, the latter two additionally scoped by their
BPMN process; `none` scopes nothing.

Prefixing is what makes tenants avoidable, which matters because Camunda licenses per
tenant, and it is transparent: BPMN, business code and configuration keep the plain
identifiers while the adapter translates at every boundary.

**The default is `by-adapter`, which is version 1's behaviour** (its `use-tenants` was on
and the tenant id defaulted to the workflow module id), so an application upgrading without
touching its configuration keeps addressing the workflows it started before. What the
cluster owes that mode is multi-tenancy plus an existing tenant: a cluster from the stock
image has multi-tenancy switched off, and `Camunda8TenantCheck` ends the boot naming both
ways out, `use-prefix` (modules stay apart, no tenant needed) and `none` (version 1's
`use-tenants: false`). The default stood at `none` between 2026-08-11 and 2026-08-22, which
left an upgraded version-1 application deploying into no tenant while its workflows lived in
theirs. While `none` applies, a WARN per workflow module names the alternatives
until `accept-unscoped-identifiers` acknowledges that the identifiers are unique.

**Two adapter ids on one cluster.** Migrating a module from tenants to prefixes
runs both scopes side by side: two ids of type `camunda8`, one cluster, differing only in
the mode, the new one first in `prioritized-adapters`. What tells them apart is the scope a
workflow was deployed under, never the key of a task: job keys, user-task keys and
process-instance keys are unique per CLUSTER, and the credential of a migration is a member
of both tenants, so the cluster accepts an operation of the wrong adapter without a word.
The awareness probes therefore compare (tenant, scoped process definition id) against what
THIS adapter id deployed (`Camunda8DeployedProcesses`) before answering `ACTIVE`, and
`processInstanceKeyOf`, which `aggregateChanged` writes through, drops what is not its own.
The set comes from the deployment rather than from the call because one process service
serves every workflow module of its adapter id; an empty set (a module whose deployment
failed under the `warn` policy, a test) answers as before.

The workflow probes filter the result they already have, which is free. The two task probes
have to READ the job respectively the user task to learn its scope, so they do that only
where `Camunda8ClientFactoryRegistry` saw a second adapter id on the same cluster. That read
is a search, and a cluster which serves none is refused while the adapter deploys, for one
adapter id as well as for two - see
[What needs a cluster which can be searched](#what-needs-a-cluster-which-can-be-searched).
`Camunda8WorkflowViewer` and `Camunda8ProcessVersions` were scope-correct from the start
and are what the probes now copy.

**The scope is the one of the CALL.** A probe is handed a
`WorkflowScope` naming the workflow module and the BPMN processes the asking process
service serves, so the comparison is not "one of my deployments" any more but "the module
and process you asked about", translated into the tenant and the scoped process definition
ids. That closes the second half of the gap: two workflow modules of one adapter id no
longer answer for each other, which mattered because aggregate ids are unique per
aggregate type and not across an application.

One case stays coarse on purpose. The two task probes only READ the job respectively the
user task where a second adapter id shares the cluster, because that read is a search on
every task election. Without a second id the key of another workflow module
of the same application is still claimed, and it costs nothing: completing or cancelling
addresses that same key, so the operation acts on the task the key names, and a key of
another BPMS is not a Camunda 8 key at all. The workflow probes, whose answer routes a
message or a pushed aggregate, compare the scope always.

Where `by-adapter` applies, the adapter looks the tenant up in the cluster BEFORE deploying,
so the two ways this can go wrong are named as VanillaBP properties instead of as the
engine's rejection: multi-tenancy switched off (the deploy command would answer `Failed with
code 400 ... but multi-tenancy is disabled`, true but naming no property to change) and a
tenant which does not exist. Only an answer of the cluster counts; an unreachable cluster is
left to the deployment, which runs into it right after and reports it as the connection
problem it is.

**Which workflow modules the cluster keeps apart is a question the core puts here.** It refuses
two BPMN processes of one application which reach the cluster under the same identifier, and
under `by-adapter` it cannot judge that alone: nothing is prefixed, so it holds two equal
strings while the tenant keeps the two modules apart. So it asks this adapter, and the answer is
the tenant each of the two modules would really be deployed to, compared. Two modules without a
configured `tenant-id` land in two tenants named after them and are separated; one adapter-wide
`tenant-id` puts both into one tenant and separates nothing, which is the configuration where
the boot of the SECOND module now ends, and the way out it offers is a tenant for one of the
two modules alone. A module under `use-prefix` or `none` reaches the
cluster in the `<default>` tenant, and that is a scope like any other: two such modules share
it, one of them against a tenanted module does not. On a cluster without multi-tenancy the
`<default>` tenant is the only scope there is, so the answer there is that nothing separates
anybody, which is what such a cluster really does. Decision 26 in
[`DECISIONS.md`](./DECISIONS.md) carries why the answer is the resolved tenant and not the
property.

The question costs nothing. Both tenants come out of configuration, so no request reaches the
cluster, and the core asks once per pair of workflow modules rather than once per process.

**The tenant name is resolved per workflow module**, the module's own section first
(`vanillabp.workflow-modules.<module>.adapters.<id>.tenant-id`) and the adapter's after it.
That is what makes the way out of the refusal above writable: the message tells the developer
to give one of the two modules a scope of its own, and before this the only name an
application could write was one for every module at once. There is no name per workflow,
because a tenant id is an attribute of the deployment and this adapter deploys once per
workflow module, so two workflows of one module cannot reach the cluster in two tenants. A
name the mode would ignore still ends the boot, once per property key rather than once per
adapter, so the message quotes the line which was written. Decision 29 in
[`DECISIONS.md`](./DECISIONS.md) carries the reasoning.

`Camunda8DeploymentServiceTest` holds the three modes and the default, `Camunda8TenantCheckTest`
the two ways `by-adapter` fails, `Camunda8IsolationSeparatesModulesTest` which pairs of modules
the tenants separate, `Camunda8CollidingProcessIdsBootTest` the refusal of two modules sharing a
process id and the module tenant which resolves it, `Camunda8TenantResolutionBootTest` and
`Camunda8JobTimeoutOverlayTest` the resolution over the levels on both platforms, `Camunda8SharedClusterTest` and `Camunda8InstanceIdentityTest`
which ids count as one, and `Camunda8SharedClusterElectionIT` the election of two adapter ids
on one cluster.

### A name the cluster already holds

The checks above compare the identifiers of one deployment against each other. A name another
application deployed into the same cluster years ago is invisible to them: both deployments
succeed, and the cluster alone decides which of two definitions a start reaches or which
subscription a message finds. So the adapter asks the cluster, once per workflow module and right
after the deploy command answered, and the core words the warning out of what comes back. The rule
both halves follow is decision 25 in [`DECISIONS.md`](./DECISIONS.md).

Two kinds can be asked about. The BPMN process ids of a workflow module go into one paged
definition search, which also leaves the definitions an operator deleted out of the answer. A DMN
decision id needs a search of its own, because that filter takes one exact id. Both searches carry
the tenant where the mode uses one, so what lives in another tenant is not held against this
application.

What the answer cannot say is who holds the name. A cluster records no owner, so the adapter
compares what came back against what this deployment brought: the resource the cluster recorded for
a process definition, and the decision requirements of the DMN file for a decision. A definition
under one of those markers belongs to this application, its earlier versions included, and nothing
is said about it. Everything else is reported as a finding which VanillaBP cannot prove, and the
message says so in those words. The two ways the marker misleads are a second application which
deploys a file of the same name and a file of our own which was renamed, and a reader can tell both
from the line they get.

The reason such a finding is reported at all is the mode `none`, which is what a cluster without
multi-tenancy leaves an application with. Nothing is prefixed and no tenant separates anybody
there, so a second application on the cluster shares every name by construction, and a check
staying silent because nothing is provable would leave that case unguarded.

A second question costs no request. While a model is scoped, the adapter holds every message name,
signal name, error code, escalation code and job type of it, and while a decision table is read it
holds the ids of the decisions the module brings, so all of those are handed to the core as well and
it names the case where two workflow modules of ONE application end up under the same name. A job
type is the severe one on Camunda 8: a worker subscribes to it cluster-wide, so two modules sharing
one job type means the worker of one module fetches the jobs of the other. A job type is also the
one kind this report carries which the Camunda 7 adapter does not, because there a task definition
is resolved inside its process and nothing subscribes to it engine-wide; everything else, the
decision ids included, is the same on both. The same names of a version the cluster still HOLDS are
read off that model while the old-versions check reads it anyway, which is the only place a name a
workflow module deployed years ago still lives.

No property switches any of this on or off, and none of it can end a boot: a failed search is
logged at debug and the deployment goes on. What a cluster without the query API would answer is
nothing at all, which is one of the reasons such a cluster is refused while a module deploys, see
[What needs a cluster which can be searched](#what-needs-a-cluster-which-can-be-searched).

### Sharing the workflow aggregate

The cluster can only evaluate what it was given, so the default of this adapter is that
everything is shared unless `@NoSyncWithBPMS` excludes it. The shared attributes travel at
every sync point: starting a workflow (also by message), completing the job at the end of a
`@WorkflowTask` method (a `TaskException` becoming a BPMN error included), completing or
canceling an asynchronous task, completing a user task and correlating a message.

The push at the end of a `@WorkflowTask` is what makes a gateway directly behind a service
task work. The values are read AFTER the method's local transaction committed, in an own
transaction, which keeps the at-least-once order of the worker untouched; if that read
fails, the job is still completed, with the aggregate-ID variable only and a warning naming
the workflow. User-task lifecycle listener jobs push nothing, because they gate a transition
of a user task which stays in the cluster: after `creating` nothing downstream is evaluated
yet, and `canceling` means the task is being removed.

`aggregateChanged(aggregate)` sends `SetVariables` for the process instance,
`aggregateChanged(aggregate, taskId)` sends it with `local(true)` for the element instance
of the scope the task RUNS in, never for the task's own element instance: in Camunda 8 every
element instance is a variable scope, and the one belonging to a task disappears with the
task, so nothing would ever read what was written there. Finding that scope takes a few
queries, since the API reports the children of a scope but never the parent of one, so the
adapter walks down from the process instance until the task's element instance shows up. The
operation carries no idempotency key at all, because the values are read when the push is
dispatched and a retry is therefore harmless.

Independent of the annotations the workflow aggregate's ID is written as a process variable
named after the aggregate's ID attribute, and always as a string. That variable is what
VanillaBP reads a workflow back by, the business id below is not, and a string is what a
variable search of this cluster can compare without knowing the aggregate's id type. A cluster stores variables as JSON and compares against that JSON, so an
instance search has to quote the value (`{"name":"id","value":"\"4711\""}`); an unquoted
filter finds nothing, which is what `Camunda8VariableFilters` encodes for the process
service and the viewer alike (`Camunda8VariableFilterTest`).

From 8.9 on an instance also carries a BUSINESS ID, and this adapter can write the
aggregate's id there as well:

```yaml
vanillabp:
  adapters:
    c8:
      aggregate-id-as-business-id: true    # default: false
```

It is there for the eye. Operate shows the business id where a reader looks first, and the
variable above is two clicks further away, so a person tracing a workflow finds it faster.
VanillaBP itself never reads the field back, on any line: the variable stays what finds a
workflow again, and a search by business id is served by the same index as every other
search, so it is no shortcut either.

Off by default, because the field is the application's until this adapter takes it. Assigning
one is single and irreversible, so an installation which wants its own value there would lose
it without being asked. The id is written by the create command and never assigned
afterwards, which means a workflow somebody else started keeps whatever business id it has.
An aggregate id longer than the cluster's limit of 256 characters is CUT to that length
rather than refused: nothing reads it back, and the start of a workflow is the worst place
for a refusal. The boot says that once per adapter id, and nothing is written per started
workflow. On the 8.8 line the field does not exist at all - the cluster answers a create
carrying one with `400` - so the key is accepted there, nothing is sent, and the boot says
so. Why, and what the probe of the election does with the same value, is decision 37 in the
repository's `DECISIONS.md`.

What travels with a command is `Camunda8SharedValuesTest`. The two scopes a push writes are
`Camunda8AggregateChangedIT` and its Quarkus twins
`Camunda8WorkflowLifecycleTest#aGlobalPushWritesTheWorkflowScope` and
`#aTaskScopedPushReachesTheEnclosingScope`, and the gateway right behind a service task is
`Camunda8TaskProcessingIT#gatewayAfterTaskSeesTheNewValues`.

### Signals

`sendSignal(name)` broadcasts through the cluster's `BroadcastSignal` command after the
local transaction was committed, riding an outbox entry, so a rolled-back transaction never
reaches the cluster. The command carries no payload, and there is nothing to deduplicate a
signal by (unlike a message, which VanillaBP can give a message id), so a redelivered outbox
entry broadcasts a second time.

`Camunda8SendSignalIT#broadcastContinuesEveryWaitingWorkflow` and `#rollbackBroadcastsNothing`
hold both halves, `Camunda8WorkflowLifecycleTest#sendSignalContinuesTheWaitingWorkflow` the
Quarkus one. The second broadcast is an assumption: it is the absence of a deduplication rather
than a behaviour, and a cluster dropping the repeated broadcast would disprove it.

### The start of a workflow, and the end of a workflow

While deploying, the adapter adds an execution listener to EVERY start event a process
itself holds, with event type `end`: the cluster rejects `start` listeners on start events,
and an `end` listener still gates the transition, so nothing of the process runs before the
listener job is completed. The listener job reports the start, and the core decides what
that start is.

A workflow is named by its workflow aggregate, and Camunda 8 keeps that name in the process
variable called after the aggregate's id attribute - the same variable a start through
`ProcessService` writes and every task of the workflow reads. The listener job fetches every
variable, so the core sees the name if the workflow has one: then the workflow is already
ours and nothing is built. Where there is no name, somebody started the workflow past
VanillaBP, the application's `@WorkflowStartedByBpms` method builds the aggregate and names
it, and the job completion writes that name into the instance. Where the variable holds a
name no workflow aggregate carries, the start is refused, because VanillaBP names a workflow
and nobody else. The reasoning and the choice of the variable's name are
`DECISIONS.pending/653.md`.

The kind of the start event decides none of it, which is also what makes a redelivered
listener job harmless: the first attempt wrote the name into the instance, so the second one
finds the workflow instead of building a second aggregate.

What the listener costs the model is one `zeebe:executionListeners` element per start event,
measured by `Camunda8StartListenerCostTest`, and what it costs a started workflow is one job:
create, activate, complete. A workflow the application started pays for that round trip and
for one load of its workflow aggregate, which its first task would read a moment later
anyway.

An event subprocess is left out of this. Its start event fires inside a workflow which is
already running and already has its aggregate, so nothing is started there. Only the start
events the process itself holds count. `Camunda8EventSubprocessStartsNoWorkflowTest` holds
what the core is told and which start event the model reaches the cluster with a listener
on, and `Camunda8EventSubprocessIT` runs a model whose event subprocess takes a waiting
workflow over.

An event subprocess is left out of this, although its start event can carry a timer or a
signal too. It fires inside a workflow which is already running and already has its
aggregate, so nothing is started there and no method has to build anything. Only the start
events the process itself holds count. `Camunda8EventSubprocessStartsNoWorkflowTest` holds
what the core is told and which start event the model reaches the cluster with a listener
on, and `Camunda8EventSubprocessIT` runs a model whose event subprocess takes a waiting
workflow over.

Where a workflow service declares a `@WorkflowEnded` method, the adapter adds an `end`
execution listener to the PROCESS element and opens a worker for it. The job is activated
after the last element completed, and its completion lets the instance disappear.

The core also wants that notification where no application method asks for it: a workflow
module which releases the records of its processed task deliveries, or the hints of its
election cache, when a workflow ends. So `workflowEndedHandlerExists` can answer `true` for
every process of the module, an unclaimed one included. Such a process is left out anyway.
The worker answering the listener's job reads the aggregate-ID variable, and a listener whose
job nobody activates would stop the workflow at its own end, so the guard sits in `wireBpmn`,
before the listener is attached. Held by
`Camunda8UnclaimedProcessTest#anUnclaimedProcessGetsNoWorkflowEndListener`. Leaving it out
is enough because the listener is this adapter's own addition and the cluster wants nothing
of it, unlike the correlation key of a message subscription, which is why that one ends the
boot instead.

`Camunda8BpmsInitiatedStartIT#timerStartCreatesTheAggregate` drives a timer start and the end
behind it, `Camunda8WorkflowLifecycleTest#theClusterStartsAWorkflowOnItsOwn` the same on
Quarkus, and `Camunda8OutcomeCommandRetryTest#theStartEventListenerFollowsTheSameRule` with
`#theWorkflowEndListenerBacksItsFailureOff` the way both listeners report a failure.

### Versions of a process

The cluster counts a process definition's version upwards per BPMN process id, and every
activated job carries the version of the definition its instance runs on, which the adapter
reports with every task, user-task listener job, BPMS-initiated start and workflow end. A
version made of numbers therefore costs no query.

A boundary naming the model's `zeebe:versionTag` is a different matter, since a job never
carries the tag: the adapter asks the query API which version carries which tag
(`newProcessDefinitionSearchRequest`). The queries are few by design, one per process while
the application starts (after the deployment) and one for a version this application never
deployed itself, which is what a rolling deployment produces while another node is already
ahead. The version of the model deployed by this very start needs no query at all: the deploy
command reports it and the tag is read from the model.

One more query joins them per BPMN process id a workflow module DECLARES without deploying a
model under it, which is what renaming a process leaves behind. The core asks the adapter for
the catalog of such an id once the module was deployed
(`AdapterDeploymentService#processVersionCatalogOf`), and the catalog reads the versions the
cluster still holds under it, so the startup check reaches the workflows which are still
running there.

What that catalog says about ONE of those versions is read from the models the cluster holds
(`Camunda8ModelsTheClusterHolds`), the picture every check judging a model asks. Next to the
tasks of a version it answers the start events the cluster fires on its own there, and that
answer is what judges a `@WorkflowStartedByBpms` method kept for a declared-only id. Nothing
wires such an id while the application boots, so a method naming a start event none of the
held versions declares stayed silent for the life of the application, while the cluster kept
firing the old model's timer every day. Where the cluster cannot be asked, the adapter says
so and the check stays silent instead of judging the method by an answer nobody has.

The same picture answers which elements of a held version can put a second token into one of
its workflows, so a version whose parallel gateway the newest model dropped is named by the
concurrent-token report as well. Those workflows keep forking the way they did when that
version was deployed, and they are the ones which run longest, which is why a report reading
this boot's model alone missed exactly the case which lasts.

Whether the workflows under such a declared id keep RUNNING is a second question, and it is
answered by workers rather than by queries. A job worker asks for one task definition, and
under `use-prefix` that name carries the id of the process the task was deployed with
(`prefix-task-definitions-per-process`), so the jobs of the old id reach no worker of the
deployed processes and their workflows used to stand still without an incident. So
`startWorkflowProcessing` asks the core which ids the module declares without a model and what
it serves for each of them
(`WorkflowTaskWiring#taskWiringOfProcessesNobodyDeployed`) and opens one more worker per
name those jobs carry, composed the way the deployed ones were: the task definition, scoped by
the declared process id. Where a name is already served nothing is opened, which is every mode
but `use-prefix` and `use-prefix` without `prefix-task-definitions-per-process`, so an
application which does not scope task definitions per process notices none of it.

Two things about those workers are worth knowing, and
[decision 19](./DECISIONS.md#19-the-workers-of-a-declared-process-id-are-composed-from-what-the-application-serves)
carries the reasoning for both. A served task definition may belong to a service task or to a
user task, and which of the two cannot be told without the model this application no longer
brings, so both subscriptions are opened and the one whose kind the task never was stays idle.
And such a worker asks for every variable rather than a derived list, because deriving one
needs the elements of that model. What cannot be reached at all is a `@WorkflowTask` method
wired to a BPMN element id: composing a job type from an element needs the model, and the start
says so with the two ways out. This is the one place where such a method is out of reach.
Everywhere else the cluster names the element of the job and the core routes by it.

`Camunda8DeclaredProcessWorkersTest` holds which workers are opened per mode,
`Camunda8RenamedProcessIT` the same against a cluster with prefixed identifiers.

`Camunda8ProcessVersionIT#theVersionDecidesWhichMethodRuns` and `Camunda8OldProcessVersionsIT`
say which method serves which version, `Camunda8DeletedProcessVersionsTest` a version the
cluster no longer has, `Camunda8RenamedProcessTest` with `Camunda8RenamedProcessIT` the
declared id and a workflow which outlives the rename, `Camunda8StartEventsOfHeldVersionsTest`
what a held version starts on and `Camunda8ConcurrentTokensOfHeldVersionsTest` what it forks
into, and `Camunda8StartupQuestionCostTest` counts the queries the claim above is about.

### Multi-instance

A `@WorkflowTask` method may ask what the engine knows about the iteration it runs in:
`@MultiInstanceElement`, `@MultiInstanceIndex` and `@MultiInstanceTotal`, each naming the
BPMN id of the multi-instance element it asks about. On Camunda 8 all three are answered,
and none of them can be read off a job directly.

What a job carries is the variable `loopCounter` and whatever `inputElement` names. Both are
local to the innermost iteration, so a task inside a multi-instance subprocess sees its own
values and none of the subprocess', and there is no `nrOfInstances` at all - this engine
does not report how many instances a multi-instance element has.

The adapter closes that while deploying, which is the stage it modifies models anyway. Every
multi-instance element of a deployed process gets input mappings named after that element:

```
vanillabpMiIndex_<element id>    = loopCounter
vanillabpMiTotal_<element id>    = count(<the element's input collection>)
vanillabpMiElement_<element id>  = <the element's input element>
```

A model which names no `inputElement` gets no element mapping, because there is nothing to map:
the cluster hands each instance its entry of the collection under no name at all. Such a model is
fine on its own, and so is a handler which only wants to know how far the iteration got. The two
together are not, and `Camunda8MultiInstanceItems` ends the boot over it. The adapter reads the
chain of iterations around each wired task and the core answers
`WorkflowTaskWiring#multiInstanceElementNames`, which is the element ids the methods serving that
task declare `@MultiInstanceElement` for. Where the two meet, the message names the task, the
element, the attribute and the two ways out. Before that check the parameter received `null` once a
job arrived and nothing said why.

Only the elements of the process being wired are judged. A level a CALLER contributes is linked
once the whole workflow module is wired, and it belongs to the model of that caller, where the same
question is asked about it.

Those names cannot be shadowed, so a job of a nested task carries one set per iteration it
runs in. Which iterations enclose which element is model knowledge and is remembered while
wiring, since a job reports the id of its own element only. The mappings are added once and
a redeployment produces the same model - the BPMN is not rewritten twice, and no new process
version comes out of an unchanged model.

An application which already ran its models on an earlier version of this adapter deploys a
NEW process version once, because the mappings are what changes the model. A model whose call
activity gains `propagateAllParentVariables="true"` is rewritten once for the same reason,
even where it carries no multi-instance element at all. Workflows already
running stay on the version they were started on, and a task of such an instance reports no
iteration at all rather than a wrong one - the guiding message of the platform then names the
element it was asked about.

#### A called process

A call activity used for decomposition is an embedded subprocess which lives in another file,
so a task in the called process runs in the iterations of the call activity and is told about
them. The values need nothing from the adapter: the cluster copies the variables of every
scope a call activity sits in into the called instance, and it keeps doing so through a second
call activity below that. What the adapter adds is the link between the two models, because a
BPMN process does not say who calls it. The call activities of the callers are read once every
file of a workflow module is wired, and the chain of an element in a called process becomes the
chain of the call site followed by its own, outermost first.

A call activity only counts where its `zeebe:calledElement processId` is a plain id and where
the called process works on the same workflow aggregate. An id given as an expression is
decided per instance, and a process with an aggregate of its own runs a business case of its
own. A task in either of those reports no iteration of its caller, although the cluster still
copies the values into the instance, so a `@TaskParam` naming one of the variables would
find it.

Where the attribute is missing, the adapter writes `propagateAllParentVariables="true"` at
such a call activity. That is what it already means today, and writing it says what the chain
relies on. A call activity saying `false` is left alone, and it stays out of the chain as
well: the modeller switched the caller's context off on purpose, the values never reach the
called instance, and there is nothing to report.

What that costs where a call graph is not a straight line:

- A process called from several places gets the levels of all of them. Only one of those paths
  reached the instance at hand, and the variables of the other path are not in the job, so
  nothing wrong is reported. The cost is the fetch list, which asks for every call site's
  variables on every activation. A level two paths share is reported once, in the place the
  first of those paths gave it.
- A process which calls itself ends at the first repetition, and a handler sees the round it
  runs in rather than all the rounds above it. Every round writes the same variable names, so
  the innermost one is what the job carries.
- Two call sites whose multi-instance elements share a BPMN id but hand over different things
  end the boot, because `@MultiInstanceElement` of that id would mean two things.

Two details of this engine are worth knowing when modelling:

- **There is no loop cardinality.** A multi-instance element always iterates over an
  `inputCollection`, and the collection is a process variable, so it should hold identifiers
  rather than objects: it travels to the cluster with every sync point, and the business code
  can look up the rest. `inputCollection="=partnerIds"` reads an attribute of the workflow
  aggregate like any other expression does. A fixed number of rounds is still modellable, since
  the collection is a FEEL expression which has to result in an array and `=for i in 1..5
  return i` is one. The element handed over is then the counter, so `@MultiInstanceElement`
  answers with the number while the index and the total answer as usual.
- **The index counts from 0** in the application, as it does on every other BPMS, although
  Camunda 8 counts iterations from 1. The adapter translates.

Characters an element id may hold but a variable name may not are replaced by `_`. Two
multi-instance elements of one process whose ids differ only in such characters would end up
sharing variables, which fails the deployment with a message naming both.

A model which already carries an input mapping of one of these names, reading something else,
fails the deployment too. Nothing the application modelled is overwritten, and the modelled
expression cannot be used either, because the handler would then read the values of another
iteration. The message names the element, the variable, the expression found and what to do
about it.

A parallel multi-instance element creates one token per instance, and each of them loads and
saves the workflow aggregate. Two instances writing the same attribute means the one
committing last puts back what it read, so an iteration should write a row of its own - see
[workflow aggregates](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-aggregates).

`Camunda8MultiInstanceTest` covers the injection, its idempotency, the ambiguous element ids,
the chain across a call activity, the union over call sites and the recursion stop.
`Camunda8FetchVariablesTest#theListFollowsTheChainAcrossTheProcessBoundary` holds that the
fetch list follows the chain without a change of its own, and
`#aProcessOfItsOwnStaysOutsideTheChain` that a called process with a workflow aggregate of its
own gets none of it. What a handler really sees is
`Camunda8MultiInstanceIT#theIterationIsReported` with its Quarkus twin
`Camunda8WorkflowLifecycleTest#multiInstanceBindsElementIndexAndTotal`, and across a call
activity `Camunda8MultiInstanceIT#theIterationCrossesTheCallActivity`. The parallel tokens of
the paragraph above are `Camunda8ConcurrentTokensTest#parallelMultiInstance`, and that the
index reaches the application counting from 0 is `Camunda8MultiInstanceTest#valuesAreTranslated`.
That this engine offers no loop cardinality is an assumption about Camunda 8, disproved by a
model which deploys with one.

### Testing

Every integration test here starts the cluster of the active release line through
`ClusterUnderTest`, which decides from `camunda8-cluster.properties` what that costs. On the
lines whose cluster keeps its secondary storage in a database of its own process a test class
starts ONE container, an embedded H2 inside the cluster serving every search; line 8.8 exports
to an Elasticsearch and the cluster takes that container along and stops it again with itself.
The test asks for a cluster either way and declares one field, see decision 22 in
[`DECISIONS.md`](./DECISIONS.md).

`ClusterUnderTest` and the log writer beside it live in the module `test-support` and are
published as `org.camunda.community.vanillabp:camunda8-adapter-test-support`, on the same
release line as everything else here, because the cluster a test needs follows the client the
line pins. Four modules used to carry a copy of those two classes, and the copies had drifted:
different startup timeouts, different messages, one of them without a log writer at all. The
classes sit in `src/main/java` although nothing but a test calls them, because a test classpath
cannot read another module's test classes - which is the whole reason the module exists.

What it offers: `cluster()` and `cluster(logName)` for the everyday cluster,
`clusterWhichRefusesSearches()` for the one the boot has to refuse (decision 20),
`withAuthentication()` for an installation with its authentication switched on,
`clusterWithTenants()` plus `createTenant(...)` and `awaitTenant(...)` for the tenant
separation `by-adapter` deploys into, and `ClusterLog.FILE` for the file a red build uploads.
An extension of this adapter takes the artifact as a test dependency and meets the cluster the
adapter is tested against.

The module holds the cluster and nothing else. `PublishedPom` used to sit beside it and moved
into `published-pom`, because Testcontainers is an honest dependency of a cluster and dead
weight on the classpath of a module which only reads a file. No module ever wanted both.

Nearly every workflow of the test applications carries `allow-full-sync-with-bpms: true`.
VanillaBP stops an application whose workflow aggregate hands every attribute to the BPMS,
unless that workflow says it may. The aggregates here are test data. A test writes one so it
can read it back out of the cluster, so everything really does travel, and the permission is
the place to say it. `TaskDockerAggregate` and `C8E2eAggregate` are the two without the line.
Their tests ask whether a `@NoSyncWithBPMS` attribute stays at home, so each of them holds
one attribute back, and an aggregate which holds something back is never asked for a
permission. A test application added later needs the same line for its workflow, or it will
not boot.

- **Core unit tests** (no Docker): BPMN parsing / executable-process extraction, client
  configuration validation (missing-property messages, self-managed/SaaS), and the
  process-service phase behavior.
- **Spring Boot** `Camunda8DeploymentAndStartIT` (real Camunda 8 via Testcontainers, the
  cluster of `ClusterUnderTest`): boots the application (deploying the BPMN to the cluster
  on startup) and drives the full two-phase start through `ProcessService#startWorkflow`
  inside a JPA transaction with the phase-two outbox. It asserts that the process instance
  appears only **after** the transaction commits, carrying the aggregate's ID as the `id`
  variable (named after the test aggregate's ID property; observed by a raw Camunda 8 job
  worker on the service task), and **never** after a rollback (the outbox entry is gone
  and no job is ever activated). Skipped automatically when Docker is unavailable
  (`@Testcontainers(disabledWithoutDocker = true)`).
- **Spring Boot** `Camunda8WorkerThreadsIT` and `Camunda8VirtualThreadsIT` (real cluster): the
  acceptance test of the execution slots. A handler blocks its slot for four seconds while a
  workflow of ANOTHER worker of the same adapter is started, and its job has to be served
  meanwhile - which one execution thread could not do. The virtual variant asserts the same
  property plus that the handler really ran on a virtual thread and that the client runs its
  workers on the adapter's own bounded executor. `Camunda8PollWhenASlotIsFreeIT` is the other
  half of the same picture, with one slot instead of four: the client's own activation counter
  stays at zero for the second worker while that slot is busy, so the job stays at the cluster
  rather than in front of the application. The bound and the gate themselves are unit tests
  (`Camunda8ExecutorTest` for both execution models, `Camunda8VirtualThreadExecutorTest` and
  `Camunda8PlatformThreadExecutorTest` for what each of them does on its own), where more
  concurrent jobs than the bound can be thrown at them without a cluster. Which builder method
  of which line carries the two roles is `Camunda8JobExecutorsTest`, once per release line.
- **Spring Boot / Quarkus discovery tests:** the adapter is discovered and the deployment
  service (one per configured adapter id), process service and client-factory registry
  beans are created (no cluster needed).
- **Quarkus deployment-pipeline test** (`Camunda8DeploymentPipelineTest`, no Docker):
  the Quarkus platform integration runs the deployment pipeline at boot. The test provides
  a BPMN below the configured `resources-location` and a REST address pointing to a closed
  port: the pipeline reads/parses the BPMN and attempts the deployment, whose connection
  failure aborts the boot (the adapter is first-priority) - proving the pipeline mechanics
  without a cluster.
- **Quarkus** `Camunda8WorkflowLifecycleTest` (`quarkus/integration-tests`, real cluster):
  the same documented features the Spring Boot suite runs, on a booted application. The
  duplication is deliberate - a correct
  platform-neutral core says nothing about a platform's glue ever calling it, which is why
  coverage is measured per platform. `QuarkusProdModeTest` runs the application in a forked
  JVM, so the tests observe it through its own `introspect/...` endpoints and the JaCoCo
  agent is forwarded into that JVM, otherwise the run would prove the features and count as
  nothing. One class carries all of it because a prod-mode test boots its application once
  per class and every boot costs a cluster. What it does NOT repeat is named in its
  class comment: the startup check for old process versions (several boots against one
  cluster), authentication and the shutdown drain (a cluster respectively a lifecycle of
  their own) and `cancelUserTask` (answered by the release line, so it belongs to a
  per-line test source).

## Outbound operations: one handler per operation

Everything this adapter sends to the cluster is a `PhaseOperationHandler`, contributed per
operation in `Camunda8ProcessService.phaseOperations()`: `phaseOne` asks inside the caller's
transaction, `phaseTwo` acts after the commit. The operation itself - its persisted name, what
deduplicates it, which BPMS serves it, how a failure is worded - belongs to VanillaBP's
`PhaseOperation`, so an operation added later costs this adapter one entry in that map.

What phase one can ask of a remote cluster is little, and it is all here: a job timeout
renewal, an empty user-task update, and the message names the deployed model declares. None of
them advances anything, and all of them run as a pre-commit hook so the window to the phase-two
dispatch stays small. Phase two carries the activation the correlation was planned in
(`PhaseTwoRequest#activationId()`), which is what keeps three multi-instance siblings from
becoming one message in the cluster's own deduplication net.

`Camunda8ProcessServiceTest` holds what phase one may and may not do, and
`Camunda8PreCommitCheckTest` when the check reaches the cluster.

## Decision log

Decisions several places in this repository rely on live in [`DECISIONS.md`](./DECISIONS.md), the
one thing the code is allowed to cite. A citation reads `see decision 3 in the repository's
DECISIONS.md`, numbers are never reused, and an overturned entry stays and names its successor, so
a citation written today still resolves in a year.

## Known deviations

What this adapter does not deliver, mirrored in one sentence each on the wiki's
[Deviations](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki/Deviations)
page. The two-phase start and the at-least-once dispatch are not among them: that is what a
remote BPMS looks like in VanillaBP, see [Behavior](#behavior).

### What needs a cluster which can be searched

This adapter requires one. A cluster answers searches where it brings secondary storage
(`camunda.data.secondary-storage.type`) and where the adapter's credentials may read what it
asks for; where either is missing the adapter says so while it deploys and the boot ends, see
decision 20 in [`DECISIONS.md`](./DECISIONS.md). What follows is what the adapter uses those
searches FOR, which is what a reader sizing their cluster needs:

1. `awarenessOfWorkflow`, the BPMS-election probe, which also carries `completeTask`,
   `cancelTask`, the user-task operations, message correlation, `aggregateChanged` and the
   viewer. Finding a workflow by its aggregate's ID is a search
   (`newProcessInstanceSearchRequest` filtered by the aggregate-ID variable).
2. `aggregateChanged`, which needs the process-instance respectively element-instance key
   `SetVariables` addresses. Camunda 8 has no command addressing a workflow by one of its
   variables, so a search is the only way from the aggregate's ID to those keys. The business
   id of 8.9 and later is no way round it: searching by it reads the same index as every
   other search.
3. Version boundaries naming a `zeebe:versionTag`, since resolving a tag to a version is a
   definition search. Boundaries made of numbers need no search: a job carries its version.
4. The viewer's instance-related answers: which version a running workflow uses, the element
   history and the definitions of previous application versions. The definitions of the
   RUNNING version come from the deployment record instead, which spares a round trip and a
   consistency window rather than a capability.

The redispatch probe of a start (`awarenessOfWorkflowForRedispatch`) reads the same searches
under a stricter contract: it never answers optimistically, because an optimistic answer would
skip the start and thereby LOSE the workflow, see
[Idempotency limitation](#idempotency-limitation).

**How the adapter knows.** It asks once, while it deploys a workflow module and after the
start has waited for its cluster, with a search of one page holding one item, and remembers
the answer per adapter id (`Camunda8QueryApi`). Every later failure of a search is read
against that answer rather than examined itself: it is an outage, and the probe reports
`BPMS_UNAVAILABLE`. A cluster which is merely unreachable while the probe runs is not declared
incapable, so the answer stays open and the next question asks again - which is why the
requirement is checked after the wait and not among the configuration checks of the start.

A refusal is an HTTP `403`, and that code covers two cases the cluster separates in prose
only: no secondary storage, or credentials which are not allowed to read. Both are permanent
and cost the adapter the same thing, so every message about this state names both instead of
picking the likelier one - including the messages about an outage, because a credential losing
its read permission while the application runs looks exactly like one. Reading the prose was
how the adapter used to decide, and a reworded message would have turned "this cluster cannot
tell" into "this cluster is down", after which every operation of the adapter fails after a
second instead of proceeding, see decision 16 in [`DECISIONS.md`](./DECISIONS.md).

`Camunda8QueryApiTest` pins the one question and the memory of its answer,
`Camunda8SearchableClusterCheckTest` the refusal and what its message names,
`Camunda8ErrorsTest#aRefusedSearchIsRecognisedByItsStatus` that the code decides and not the
prose, and `Camunda8AwarenessWhenSearchFailsTest` that a failed search after that is an outage
whatever it says. Against a real cluster refusing a real search it is
`Camunda8UnsearchableClusterIT`, which also holds the warning an adapter allowed to degrade
gets instead of the boot ending; `Camunda8LocatingWorkflowsIT` is the other side, where the
search answers.

### Eventual consistency of the query API

The window this section is about, and the waiting the dispatch does inside it, is the second of the
two walks drawn under [Awareness contract](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/README.md#awareness-contract-workflowawareness).

The query API lags behind the engine, which everything in the list above inherits. The viewer
tolerates it by design, since a viewer polling shortly after sees the data. The awareness
probe cannot: a workflow started moments ago is not searchable yet, and reporting
`UNKNOWN_TO_BPMS` would make the core raise `WorkflowNotFoundException` with causes that all
do not apply.

**Where VanillaBP holds the cluster's own key of the workflow, the ENGINE is asked before
the search.** Measured on 8.10.0-alpha5, 8.9.19 and 8.8.37: the create answered after 10 ms,
the engine said "this instance exists" after 16 to 19 ms, and the search found it after 167
to 1324 ms. The key arrives with the election, and the question is a command the engine
REFUSES for an instance it holds, so the cluster writes no state: a process instance
modification naming the element id `vanillabp-existence-probe`, which no model has, or, where
this adapter writes the business id of an instance and the line has the command, the business
id assignment. Which of the two and why is decision 35 in the repository's DECISIONS.md.

The probe shortens the YES and nothing else. The engine forgets an instance the moment it
ends, so a key it does not hold covers a completed workflow, a canceled one and a key which
never existed alike, and only the search tells those apart. Every answer but "the engine holds
it" falls through to the search below, unchanged, and so does a probe which could not be sent
at all - an unreachable engine says nothing about whether the workflow is young. A model which
happens to carry the reserved element id is found while it is deployed, the boot names it, and
no probe is sent for a workflow of that process.

Nothing is asked on a cluster this adapter SHARES with another adapter id. An instance key is
unique per cluster and names no scope, and the election hands the same key to every adapter of
its list, so the probe would answer about the other one's instance and end the election at the
wrong adapter. There the search, which filters by scope, is the whole answer as before.

The adapter reports a window for the rest
(`workflowVisibilityDelay()`, configured as
`vanillabp.adapters.<id>.workflow-visibility-timeout`, default 10 seconds, zero switches it
off), and the core keeps asking for that long - but only while probing an adapter its
`WorkflowAdapterCache` names for that workflow, which VanillaBP fills after phase two of a
start and on every inbound delivery. A workflow nobody ever started has no such hint and
still fails immediately.

**The window is asked per workflow, and a workflow the engine has forgotten gets the short
one.** Ten seconds are there for a workflow which was just started, which is exactly the case
the probe above answers before the waiting begins. A workflow the engine no longer holds has
been in the read model for as long as it ran, and what is still on its way there is the END
of it: 176 to 445 ms on 8.10.0-alpha5, 255 ms on 8.9.19 and 2068 ms on 8.8.37. So the adapter
answers `vanillabp.adapters.<id>.ended-workflow-visibility-timeout`, 3 seconds by default,
for a workflow its own probe just met a 404 for, and the long window for everything else. The
core asks `workflowVisibilityDelay(workflowId)` right after the probe and on the same thread,
which is how the adapter knows which of the two cases it is in. A probe which was skipped,
one which failed and one which found the instance all leave the long window, because none of
them says the workflow is over.

The residual: an application on several nodes without a SHARED adapter cache. An operation
reaching a node which neither started the workflow nor received a delivery for it knows
nothing about where the workflow lives, so it does not wait. Retrying the business operation
works, and an application bean implementing `WorkflowAdapterCache` removes the case
altogether. The alternative - asking the phase-two outbox whether a start for this aggregate
is open or was just dispatched - was weighed and dropped; the reasoning is in
[`migration-adapter/README.md`](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/README.md).

The engine probe is `Camunda8EngineProbeIT` and `Camunda8EngineBeforeTheSearchTest`. The
window is `Camunda8LocatingWorkflowsIT`, in `#theProbeFindsTheWorkflow`,
`#correlatingRightAfterTheStartWorks` and `#theViewerRightAfterTheStartWorks`. The residual of
the paragraph above is an assumption: it needs an application on several nodes without a shared
adapter cache, and an operation waiting on a node which never heard of the workflow would
disprove it.

### The job worker flavour of an ad-hoc subprocess

An ad-hoc subprocess whose activities are named by the model
(`zeebe:adHoc activeElementsCollection`) is served like any other part of a process, see
[Ad-hoc subprocesses](#ad-hoc-subprocesses). The other flavour is not: where the element carries a
`zeebe:taskDefinition` of its own, a worker has to complete that job with a result naming the
elements to activate, and a `@WorkflowTask` method has no way to say that. Serving it would mean a
new outcome of a workflow task, which is a design of its own rather than something this element
gets on the side.

A model using that flavour deploys and its workflow stops at the element, so the deployment writes
one WARN per BPMN process naming it, what it costs and the two ways out. An element built from an
element template is left alone there, because then a connector runtime owns it.

### Cancel user task

No Camunda 8 cluster up to 8.9 offers a command to cancel a Camunda-managed user task by
BPMN error: *throw error* is job-based, and a user task is not a job. Version 1's
marker-variable workaround is broken by Version 1's own admission, so `cancelUserTask`
throws a guiding error naming the [release line](#release-lines) rather than pretending to
work. The task listeners it needs arrive with Camunda 8.10, so support for it can only ever
come on a line built against 8.10 or later.
`Camunda8TaskProcessingIT#cancelUserTaskUnsupportedGuiding` asserts the error. That no cluster up
to 8.9 offers the command is an assumption about those releases: a cancel command turning up in
an 8.9 patch would disprove it.

### Task cancellation arrives at the next wake-up, not at the moment

Zeebe notifies no worker about a job it took away, so nothing the cluster sends says that an
open asynchronous task's activity was canceled. That has not changed and will not: the
`cancel` execution listener of the 8.10 line sits on the process element and reports a
terminated INSTANCE, which is a different question, see decision 33 in the repository's
DECISIONS.md.

What arrives instead is the same event one moment later. Whenever the cluster hands this
application a job of a workflow, the core looks at the tasks it still believes are open in
that workflow, this adapter asks the cluster about them, and the ones the cluster no longer
has are reported as `@TaskEvent CANCELED`. Three handlers do it: the job handler after the
outcome went back to the cluster, and the two listener handlers after their notification. The
end of a workflow carries the same derivation and is left out here, because the two would
report the same cancelation twice.

The cheapest question comes first: does the ENGINE still hold the process instance those
records belong to? That is one command, one to two milliseconds, and it is refused rather
than carried out, exactly the way the election's probe asks it (see decision 35 in the
repository's DECISIONS.md). A `404` there answers the whole list at once, because an instance
the engine has forgotten holds nothing open. It is asked once per workflow and not once per
record, so twenty records of one workflow cost one of these.

Then the tasks, and what kind of task decides how. A job is asked about with the
`UpdateJobTimeout` this adapter sends anyway: `NOT_FOUND` is gone, a 400 saying nobody has
the job activated right now means the task is alive, and anything else is "cannot say".

A user task is not a job, and the RECORD says which of the two is being asked about. The
record of a user-task delivery keeps the user-task key, and a job command answers `NOT_FOUND`
for such a key as long as the task is open, which read as gone would cancel a task the
cluster is holding out to somebody. What tells the two apart is the task definition the
record carries, which the core passes with the question and which this adapter knows its own
user tasks by: the external form reference their listener job type is built from. The user
tasks need no question of their own in the everyday case: VanillaBP writes a `canceling` task
listener next to every user task it manages, and the cluster delivers `CANCELED` for it
straight from there. So a record naming such a user task is answered with "cannot say" by
default, and every other record of the same process is answered with what the cluster said.

`vanillabp.adapters.<id>.probe-open-user-tasks: true` adds the question for whoever wants
task-level certainty anyway. It sends an empty `UpdateUserTask` per user task of an instance
which is still running: `204` for a task which is open, `404` for a task which is gone, `409`
for a task standing in `UPDATING` or one whose listener denied the update, which both mean it
is there. How long the `204` takes depends on whether the element carries an `updating`
listener. Measured on 2026-09-19, one container each on an idle machine: 5 to 21 milliseconds
against `camunda/camunda:8.9.19` and `camunda/camunda:8.10.0-alpha5` with no listener modelled,
106 to 111 milliseconds on 8.9.19 and 15 to 78 milliseconds on 8.10.0-alpha5 with a modelled
listener a worker answered. A `400` says nothing, because no run has ever produced
one for a user task. It is off by default because it costs a command per task and because it
fires a modelled `updating` listener while it is at it, measured on 8.9 and on 8.10, although
the update changes nothing at all.

That listener job is this adapter's own doing, so this adapter closes it. A job whose
`getUserTask().getAction()` is `io.vanillabp:probe` and whose `getChangedAttributes()` is
empty is completed at once and no `@WorkflowTask` method runs for it. Both halves, not one:
the action is a string anybody may send, and an empty change list alone is not ours either.
See decision 38 in the repository's `DECISIONS.md`.

An `updating` listener of a job type this application does NOT serve is the case the mark
cannot reach. A foreign worker or a connector behind such a listener sees a real update, and
a job nobody answers holds the task in `UPDATING` for fifteen seconds while assign and
complete are refused with `409`. The adapter knows at deployment which listeners it serves,
so THIS check sends no probe for such an element and answers "cannot say" for its tasks,
whatever the key says. `awarenessOfUserTask` and the pre-commit check of `completeUserTask`
still ask about the one task their caller named, because a caller holding that task is the
party entitled to wait for the answer.

Whoever else sends a command to a probed task is refused while the probe runs. A task under
update stands in `UPDATING` until the update and its listener job are through, and every
command against it answers `409 INVALID_STATE` for that long. Measured on 2026-09-22 against
8.9.19, over twenty probes of a task whose listener this application serves: 60 to 145
milliseconds per probe. They lie closest together in the first second of an instance, because
this check runs after every listener job and after every job the application is handed. No
sender of this adapter is troubled by it, because all four read the status code and not the
name of the state: `preflightCompleteUserTask` lets the transaction commit, `completeUserTask`
repeats through `sendWhileTheUserTaskIsStillChanging`, `awarenessOfUserTask` answers `ACTIVE`
and this check answers `STILL_THERE`. A sender past VanillaBP gets the refusal raw, and a task
list written on the client of the BPMS is the likely one. That is what the wiki says where the
key is documented:
[asking about open user tasks](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki/Configuration#asking-about-open-user-tasks).
`Camunda8UserTaskProbeIT` is the one place here which sends the way such a task list does, and
it waits the window out.

Three cases keep the wider answer for the same reason. A BPMN process this application
declares without deploying a model has no model to read, so a record of it may name either
kind of task. A record which kept no task definition at all names nothing to look up. And the
foreign `updating` listener above. In all three nothing is sent and nothing is derived.

What is left of the deviation is the timing. A workflow which walks into a timer or a message
wait after the boundary event produces no job, so nothing wakes the application up and the
cancellation waits for whatever comes next: the next job of that workflow, the end of the
workflow, or the next operation which names the task.

`Camunda8OpenTaskProbeTest` holds the answers and the order the two commands are sent in,
`Camunda8UserTaskProbeTest` the mark, `Camunda8OpenTaskProbeWiringTest` what the deployment
tells the probe a record names, and
`Camunda8OtherOpenTasksIT` lets a boundary event take one of two open tasks away against a
cluster. The application switches the whole check off with
`vanillabp.delivery.check-open-tasks-on-delivery`.

### The end of a workflow

What a `@WorkflowEnded` method hears depends on the [release line](#release-lines).

From the 8.10 line on it hears both kinds. The `cancel` execution listener of the PROCESS
element fires when an instance is terminated through the API, so such an instance reports
`CANCELED`, and the core then reports every task VanillaBP still believes is open in it as
`CANCELED`. Both listeners carry the same job type, the handler tells them apart by the event
the job reports, and an event this build does not know completes the job and reports nothing.
See decision 34 in the repository's DECISIONS.md.

On the lines before that one the cluster runs end listeners of COMPLETED instances only, so a
`@WorkflowEnded` method sees `COMPLETED` and never `CANCELED`: a cancelled instance is
removed without running them, and the boot of a workflow module names every BPMN process this
is about rather than leaving it to be found.

Two paths are not cancelations on any line, however they look in a model. A terminate end
event and an interrupting event subprocess both COMPLETE the instance: the end listener runs,
no cancel job is created, and the application hears `COMPLETED` while an open task may have
gone with it. That is the cluster's view and not a gap this adapter can close.

Independently of the line, the notification names no end event, because the listener sits on
the process element rather than on an end event, which is structural rather than a gap to
close. The completed case is held in
`Camunda8BpmsInitiatedStartIT#timerStartCreatesTheAggregate`, the canceled one in
`Camunda8WorkflowCanceledIT`, which runs on the 8.10 line of the nightly matrix.

### Conditional events

Camunda 8 has no conditional start, catch or boundary events, and a model carrying one is
rejected by the cluster while deploying. `aggregateChanged` is still useful, since the cluster
evaluates a gateway behind the current element against the values it holds, but there is
nothing which reacts to a variable change on its own. An assumption about the engine: a cluster
deploying a model with a conditional event would disprove it.

### Multi-instance has no loop cardinality

Camunda 8 iterates a multi-instance element over an `inputCollection` and offers no
cardinality, so a model saying "run this five times" says it as `=for i in 1..5 return i`,
which is an array like any other. The element handed to the handler is then the counter. The
count of the instances is not reported by the engine either; the adapter derives it from the
collection while deploying, see [Multi-instance](#multi-instance), where this is an assumption
as well. Nothing announced.

### Client certificates for the cluster connection

The Camunda Java client cannot send one. On 8.8.35, 8.9.16 and 8.10.0-alpha4 alike,
`CamundaClientBuilder` has `caCertificatePath` and `overrideAuthority` and nothing else about
TLS material, `ClientProperties` lists no keystore, and the environment variables the client
reads carry none either. The keystore and truststore of `CredentialsProvider`'s OAuth builder
apply to the token request against the identity provider, not to gRPC or REST against the
gateway, and the `auth` block documents them that way. A cluster which demands a client
certificate is therefore out of reach until the client grows the option, and the adapter says
so rather than offering a property which would silently do something else. An assumption read
off those three client versions and nothing else, disproved by a `CamundaClientBuilder` growing
a keystore; `Camunda8ClientFactoryTest#caCertificateReachesTheClient` covers the one piece of
TLS material the client does take.

### Message deduplication lasts for the message TTL

A correlation on its way through both nets, the outbox' and the cluster's, is drawn under
[Waiting for a workflow to become visible](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/README.md#waiting-for-a-workflow-to-become-visible).

A correlation carrying a correlation id deduplicates engine-side, because a message id derived
from the same values as the outbox' idempotency key travels to the cluster, and the engine
remembers a message id for the message TTL only. A redelivery after the TTL could correlate a
second time. Without a correlation id there is no deduplication at all, on purpose: the same
message may legitimately arrive several times over a workflow's lifetime.

This net is the cluster's and it is LONGER than the platform's: VanillaBP's outbox deduplicates
the operations still waiting for their dispatch, which is over in seconds, while the cluster
keeps the message id for the TTL — one hour unless the application sets `message-time-to-live`.
So a second, legitimate correlation of the same message name and correlation id for one aggregate
is refused by the CLUSTER within that hour, no matter what the platform does, and varying the
correlation id per round or element is the only way around it. The adapter logs such a refusal
naming both possibilities, because from here a repeated dispatch and a lost second correlation
look the same; the entry counts as done either way, since repeating the publish would be refused
again.

A refusal is recognised by the code its transport carries, never by the sentence around it:
HTTP `409` on REST, the gRPC status `ALREADY_EXISTS` on gRPC, and which of the two carries a
publication is what `prefer-rest-over-grpc` decides per adapter id. No other conflict reaches
a publication, so the code settles it on its own and a cluster rewording its rejection changes
nothing, see decision 16 in [`DECISIONS.md`](./DECISIONS.md).

`Camunda8TaskProcessingIT#duplicateCorrelationDispatchIsDeduplicated` holds the refusal,
`#theTimeToLiveDecidesHowLongTheClustersNetLasts` how long it lasts, and
`Camunda8ErrorsTest#aRepeatedPublicationIsRecognisedOnBothTransports` that the code is what
recognises it. The hour is the client's default rather than a number measured here, so it is an
assumption: a cluster forgetting a message id earlier would disprove it.

### A job activated for a worker which never saw it

Activating a job is a round trip, and the cluster commits its half of it before the worker holds
anything: it locks what it activated and then hands the batch to the request it was activated for.
Where that request has ended by then, the job carries a lock for a worker which never saw it. Both
shapes of that were measured against `camunda/camunda:8.9.16` on 2026-08-30, with a job timeout of
twenty seconds.

**The gateway notices that the request is gone.** It fails the job back to the broker with the
retries it had and no backoff, so the job is activatable again at once, 25 ms after it was created
in the measurement. One line of the gateway says so: `Failed to send 1 activated jobs for type ...
to client, because: Failed to send activated jobs to client`. Whichever worker of that type polls
next receives the job.

That sentence only holds for a job which HAS a retry, which is why the user-task listeners carry
one, see [the retry a lost listener delivery needs](#the-retry-a-lost-listener-delivery-needs).

**The gateway does not notice.** Over REST the response is written when the request ends, so a
connection which died while the request was parked is found too late for the reactivation above.
The batch counts as delivered, the job keeps its lock, and it comes back when the lock runs out:
20.2 to 21.1 seconds, in six rounds out of six.

Nothing is lost either way. What the application gets is a delivery which is late by at most the
`job-timeout` of that task, a user task's notification included, since CREATED and CANCELED travel
as listener jobs. That timeout is the only knob, and it is not free: it is also the lock a handler
runs under, so a value below what a handler needs buys the faster recovery at the price of a second
delivery of work which is still running.

One observation is explained by neither shape. In a build of 2026-08-30 against 8.9.16 the CREATED
notification of a user task did not arrive within three minutes, and the cluster's log carries the
gateway line of the first shape for exactly that listener job and nothing else. Both measured
shapes recover within seconds, so something after the reactivation kept that job from being fetched
again, and the logs of that run do not say what. The state of the job at the deadline would: a job
the cluster still reports as CREATED was there to be fetched and nobody fetched it, while a job
which is gone reached somebody. So the wait for a listener notification now asks the query API for
the jobs of the workflow before it gives up, and says every quarter minute what it is still
missing.

An adapter which cannot poll was the first suspect, and it used to be a real one on the 8.8 line,
where the client gave a single executor both jobs. The adapter now hands the client an executor
which keeps them apart on every line, so a blocked handler no longer stops a worker from asking
for work. What it does stop is a worker asking while every execution slot is busy, which is
deliberate and visible in the slot gauges. The observation above happened on 8.9 and with free
slots, so it was neither.

### The retry a lost listener delivery needs

The lifecycle listeners VanillaBP writes into a user task are modelled with `retries="1"`.
Version 1 wrote `retries="0"` there and 2.0 did too, until a nightly run showed what that costs.

The number is not the one a failed notification has left. The handler fails such a job with no
retries whatever the model says, so a notification which really failed still raises the incident an
operator acts on, and it raises it at the first attempt. The modelled number is what the GATEWAY
hands back when it could not deliver the activated batch, as the section before this one describes.
A job modelled without a retry has nothing to be handed back: it dies of the lost delivery, the
cluster writes an incident carrying `Failed to send activated jobs to client`, and the task stands in
`CREATING`, where every command against it is refused. That was measured on the 8.9 line on
2026-09-24, on the `creating` listener of a user task nobody listens to.

So the two numbers answer two different questions, and only the modelled one belongs to the
delivery. `Camunda8UserTaskWiringTest` holds what the model carries,
`Camunda8ShutdownHandlingTest#aListenerFailingIsReported` that a failed notification still has
nothing left, and `Camunda8TaskProcessingIT#aLostListenerDeliveryComesBack` that a job failed back
the way the gateway fails it is activatable again instead of ending in an incident.

What it costs an upgrading application is one new process version per model with a Camunda-managed
user task, because the deployed file differs in that attribute. Workflows which are already running
stay on the version they were started on.

## What an operator gets to see

The platform integration measures every task delivery, every outbox dispatch and puts a
logging context around both; the [Observability wiki
page](https://github.com/vanillabp/adapter-platform-integration/wiki/Observability)
describes all of it. What this adapter adds is documented in the
[Configuration wiki page](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki/Configuration),
section "What an operator gets to see": the client's own job counters bridged into the
same registry, the execution slots as gauges, and a health contribution
asking the cluster for its topology.

The reasoning behind the shape of it - why the client's Micrometer implementation is not
used, why the health check has a timeout of its own and where the slot gauges are read from -
is in [`core/README.md`](./core/README.md).

A task probe answering that the BPMS does not know the task writes one INFO line saying which
of its two branches decided. Either the cluster reports the key in another scope, and the line
carries the scope it reported next to the scopes the probe was asked about, or the cluster
refused the probe's own command, and the line carries the code and the reason it refused with.
The platform turns that answer into a `WorkflowNotFoundException` on the spot, a task being an
exact question with no visibility window, so a run which ends there used to leave the
platform's exception and not one word from the adapter.

`MicrometerCamunda8MetricsTest` covers the meters and the gauges, `Camunda8HealthTest` the
health contribution with its own timeout, `Camunda8UnknownTaskProbeTest` both lines of the
probe, and `Camunda8HealthBootTest` with
`Camunda8AdapterDiscoveryTest#anAdapterWithoutAConnectionIsNotUnhealthy` the booted
application's side of it.

## What this adapter says about a value type

The platform refuses to start a workflow whose values may not arrive as what they were, and it asks
every adapter of that workflow what its BPMS does with a type
(`MigratableProcessService#whatThisBpmsDoesWith`). `Camunda8ValueTypes` is this adapter's answer.

A Camunda 8 variable is a JSON value. The broker knows a text, a boolean, a number, a list and an
object, so the Java types a JSON value carries there and back are the texts, the boolean and the
numbers. An enum arrives as its name, which is a text.

The costly case is the decimal. The broker holds a number and not the way it was written, so
`120.50` comes back as `120.5` (measured while story 241 was implemented). That is reported as
changed in both directions. Everything else is answered with "cannot say", which never ends a
startup: an application whose cluster is unreachable while it boots still has to boot.

## Camunda 8 client

The adapter uses the plain Java client `io.camunda:camunda-client-java`, pinned per
[release line](#release-lines), **not**
Camunda's Spring SDK / Spring Zeebe: VanillaBP does platform wiring and configuration
itself, so a client that carries its own platform integration would conflict with it.
(The deprecated `io.camunda:zeebe-client-java` is deliberately avoided.)

Camunda 8 is a remote, eventually consistent engine that cannot join the application's
local database transaction. Starting a workflow therefore uses VanillaBP's two-phase
commit: phase one only validates, the actual process-instance creation runs in phase two
through the core phase-two outbox.

## Native images

The Quarkus extension builds into a native image, and an application needs no
configuration of its own for it:

```bash
mvn package -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
```

What that costs is registrations, and they belong here rather than into every
application, because the path they sit on is the DEPLOYMENT: every boot of every
application reads each BPMN file through the Camunda model API, modifies it, serializes
it back, sends it to the cluster and builds the Camunda client on the way.
`Camunda8NativeImageProcessor` names them:

- the message bundles of the JDK's XML parser, which the model API validates against.
  The first thing that parser wanted to say - where it had looked for the schema - came
  out as a `MissingResourceException`;
- `BPMN20.xsd` and the four schemas it imports, resources of the model API's jar;
- the model API's entry point, initialized at run time rather than at build time: it
  builds its parser in a static initializer and keeps the URL the schema was found
  under, which at build time points into the builder container;
- the gRPC providers behind the service loader, and the client's implementation package,
  whose job workers seed a `Random`;
- the types of the cluster's REST API, which Jackson builds by reflection. Taken from the
  Jandex index of the client rather than written down type by type.

Netty and Apache HttpClient 5 come along with the client and needed the same kind of
answer, one level lower:

- the runtime module depends on Quarkus' own **Netty extension** instead of repeating what
  it registers. An application which brings a Netty-using extension anyway - a REST layer,
  for instance - had that by accident, which is why the gap stayed invisible for so long;
- three GraalVM substitutions in `io.vanillabp.camunda8.quarkus.runtime.graal` answer the
  HTTP client's questions for optional libraries nobody put on the classpath: Conscrypt,
  zstd and brotli4j. Their absence is what a native build reports as `Discovered
  unresolved type during parsing`, because it resolves every type a reachable method
  names. The price is one line in the wiki's deviations: a native image accepts gzip and
  deflate responses, and adding one of those libraries to the application does not change
  that.

Netty's version here is the Quarkus platform's, and that took a build to notice. This
repository imports the Spring Boot BOM before the Quarkus one, so every module sees Spring
Boot's newer Netty - right for the Spring Boot modules, wrong for a module reproducing what
a Quarkus application sees, because Quarkus' Netty substitutions do not match it (`Could
not find target method: Target_io_netty_handler_ssl_JdkSslClientContext`, before the
analysis even starts). `quarkus/native-image-tests` pins `netty-bom` to
`netty.version.quarkus` of the parent POM, which follows the Quarkus version and never
leads it.

Held by `quarkus/native-image-tests` (`Camunda8NativeImageIT#theNativeBinaryRunsAWorkflow`)
and the `native-build` job of the publishing
workflow: the module builds an image AND runs the binary against a real cluster, where it
deploys its workflow module, starts a workflow through the phase-two outbox and has the
job served in a handler of its own. Both halves are needed, and the demo which found all
this is why: its image built in about a minute and then stopped at the first BPMN file it
read. Measured on 2026-08-25 with Mandrel 25.0.4 on the 8.9 line: 1m30s for the image,
101 MB of binary, on twelve cores.

## Building

Prerequisites (built and installed into the local Maven repository first, in this
order): `spi-for-java`, then `adapter-platform-integration`. Then:

```bash
mvn install
```

That is the current GA line as `2.0.0-SNAPSHOT`, no property to remember. Another line is
a profile, another version a `-Drevision`, and `bin/api-identity.sh` compares the public
API of the lines; see [Release lines](#release-lines).

`install` and not `install verify`: install runs every phase verify has, so naming both walks
two lifecycles per module. The tests skip their second run, the compiler does not, and every
warning is then reported twice. The workflows build it the same way.

What a pull request needs beyond a green build is in [`CONTRIBUTING.md`](./CONTRIBUTING.md).

## Test coverage

`mvn install` builds one aggregated JaCoCo report per platform:

1. **Spring Boot** (core + Spring Boot integration) - into `test-coverage-report/spring-boot/report`
2. **Quarkus** (core + Quarkus extension) - into `test-coverage-report/quarkus/report`

Both are published to GitHub Pages by the *Publish to GitHub Packages* workflow on every push to
the default branch. Click the [platform's badge](#documentation-and-supported-platforms) to open
the respective report.

The build breaks below the line: `test-coverage-report/coverage-gate` is the last module of the
reactor, reads both reports and fails whenever a platform is below its threshold in the root POM
(`coverage.threshold.spring-boot`, `coverage.threshold.quarkus`, in percent of covered instructions -
the number the badges above show). Both properties hold 85, the same number every VanillaBP
repository gates on, and that is not the target: the rule is 90 per platform, so a report between
85 and 90 passes the build and still names a gap. The gate is where the gap has grown too big to
carry, which is why it is never edited to make a build pass. It also compares every module
producing a `jacoco.exec` against the two aggregates, so a module added to the build without being
added to its report cannot stay unnoticed. Both are `CoverageGateTest`, and the conventions
every test class of this repository follows are `TestClassConventionsTest`.

`TestClassConventionsTest` also reads the main sources of this repository, for a guiding
message whose sentence fell apart: a run of spaces between two words, or two words a line
continuation glued into one.

The gate reports what it measured on every run, green ones included, which is the one place in
VanillaBP where a passing test prints. The angle brackets stand for the numbers of the run:

```
coverage gate | Spring Boot: <percent> % instructions (<missed> of <total> missed) | at the rule of 90 %
coverage gate | Quarkus: <percent> % instructions (<missed> of <total> missed) | <gap> points below the rule of 90 %, build breaks below 85 %
```

A build which stops at `package` never reaches the phase which writes the reports. The gate then
prints a line per platform saying that the coverage was not checked, and those two tests are
reported as skipped, instead of failing over a file the run could not have written.

Every release line is judged by that one number. Line 8.10 is the reason it is not the rule itself:
that line excludes the tests of an open cluster bug it cannot pass, which costs it about a point and
a quarter on either platform, and a gate standing at 90 turned the nightly matrix red over coverage
nobody was in a position to write.

Both platforms run the documented features end to end against a real cluster: Spring Boot in the
`spring-boot` module's `*IT` classes, Quarkus in `quarkus/integration-tests`. That duplication is
deliberate. The adapter core is platform-neutral, but a core being correct says nothing about a
platform's glue ever calling it, so a core line one platform never reaches names a feature that
platform never runs.

The two platforms still reach different numbers, by what one suite can produce and the other
cannot. The startup check for old process versions needs several boots against one cluster, each
deploying a different model, and a Quarkus prod-mode test boots its application once per test class - which is
why `Camunda8ProcessVersions` stands at 27 % on Quarkus against 79 % on Spring Boot. The rest is
what one suite can produce and the other cannot at all: `cancelUserTask` is answered by the release
line, which belongs to a per-line test source rather than to a prod-mode test, and the refusal of a
cluster which cannot be searched needs an application booted per configuration. The Quarkus suite's
class comment lists all of it. Everything else is at parity, `Camunda8DeploymentService`
above the Spring Boot number.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2026 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
