# Camunda 8 adapter - core

Contributor documentation for the platform-neutral core of the VanillaBP Camunda 8
adapter. User-facing documentation lives in
[this adapter's wiki](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki); the
repository-root `README.md` documents the repository for contributors.

The `core` module is **plain Java** - no Spring or Quarkus dependencies. It holds the
adapter SPI implementations and all Camunda 8 client logic. The platform modules
(`spring-boot`, `quarkus`) only construct and register these core objects (read
configuration, create beans, run the bean lifecycle).

## Client construction

- `Camunda8AdapterConfiguration` - resolved, platform-neutral connection configuration of
  one adapter instance (mode, REST/gRPC address, SaaS credentials, tenant). Populated by
  the platform modules from `vanillabp.adapters.<adapter-id>.*` (see the root `README.md`).
  Validated at startup by `Camunda8StartupValidation`; `validate(adapterId)` stays as the
  backstop before a client is used and throws naming the exact missing property. A
  self-managed adapter without an address is not missing anything: it then uses the address
  of the local cluster of its release line, and the start warns about it.
  `Camunda8LocalCluster` holds that address once per line, next to the other line sources.
- `Camunda8ClientFactory` - owns the single `CamundaClient` of one adapter instance, built
  **eagerly at startup** (for every self-managed instance and every completely configured SaaS
  instance) and closed on `close()`. Building never contacts the cluster
  (that happens on the first command). `newClientBuilder()` is used for self-managed,
  `newCloudClientBuilder()` for SaaS.
- `Camunda8ClientFactoryRegistry` - map adapter ID &rarr; factory, registered as a managed
  bean so all clients are closed on shutdown. It is also the only place which sees every
  configured id at once, so it answers which ids address the SAME cluster:
  keys are unique per cluster, and where two ids share one, the awareness probes have to
  ask which scope a key belongs to before they claim it. The factory knows which workflow modules
  have workers open and closes the ones which never stopped themselves before it closes
  the client, so the order holds on every shutdown path and not only on the
  one each platform's lifecycle takes.

`Camunda8ClientFactoryTest` covers it, including that building the client contacts nothing, and
`Camunda8SharedClusterTest` with `Camunda8InstanceIdentityTest` which ids the registry counts
as one.

## What a worker sends back to the cluster

Four classes share the way back, so the rules live in one place rather than in four
handlers:

- `Camunda8Errors` classifies a failure. `permanentFailure` answers the outbox,
  `repeatableJobCommandFailure` answers a job command and adds the one case which is
  permanent only there, a job which is gone. That case is `notFound` read for what it means
  to a job command, so both transports' way of saying it is recognised in one place.
  `incidentMessage` builds the text an operator reads in Operate, with the exception's type
  in front of its message.
- `Camunda8CommandRetry` repeats a rejected command. It is bounded by the job's
  remaining lock (`ActivatedJob#getDeadline()`), by five attempts and by the shutdown, and
  its waits are the client's own activation backoff numbers. Nothing about the outcome
  changes once the bound is reached: the original failure is rethrown and the caller does
  what it did before.
- `Camunda8RetryBackoffResolver` resolves `retry-backoff` over the four configuration
  levels, per command rather than per worker. It travels with every fail command which
  leaves the job retries. Its answer names the level it comes from, because only the task
  level has to be weighed against what the model says.
- `Camunda8RetryBackoffHeader` reads the `retryBackoff` task header version 1 read, from
  the job rather than from the model: the header then holds for process versions this
  application never deployed, which is what an application arriving from version 1
  brings. It beats every configuration level above the task and loses to the task level,
  and a value which is no ISO-8601 duration costs one line per element instead of the
  `Duration.ZERO` version 1 fell back to.
- `Camunda8Drain` decides whether a failure belongs to the shutdown, in which
  case no command is sent at all and the job is left to its lock. It also holds what a
  shutdown waits for and the line it writes about it: the handlers of the
  module, and the workers reporting themselves closed, because an activation request which
  is parked at the cluster when the client is closed stays parked and swallows the first
  job of the next application.

The rules have their tests: `Camunda8ErrorsTest` for the classification,
`Camunda8CommandRetryTest` for the bounds and the waits, `Camunda8OutcomeCommandRetryTest` for
the four kinds of worker sending through it, `Camunda8RetryBackoffHeaderTest` for the tie-break
between the header and the task level, and `Camunda8DrainTest` with
`Camunda8ShutdownHandlingTest` for what a shutdown leaves alone.

## What a worker asks the cluster for

`Camunda8FetchVariables` holds both halves of it: the list a worker names, and the two
messages a delivery writes when it is asked for a variable outside that list.

The derivation runs in `Camunda8DeploymentService#fetchVariablesOf`, once per worker while
`startWorkflowProcessing` opens them. Its input is a `ServedElement` per BPMN element the
worker serves, which is why the four worker kinds share one method: a task worker serves
the tasks of a job type across the module's processes, a user-task listener worker the
user tasks of its listener job type, and the workflow-end worker one process. Three
sources feed it, and all three are the core or this adapter's own bookkeeping:
`resolveWorkflowAggregateIdName` per BPMN process, the multi-instance registry filled during
`wireBpmn` and keyed by the process id the CLUSTER knows plus the element id, and
`taskParameterNames` per served task definition.

That last one replaced a scan of the model. The scan collected the four constructs a Camunda
8 model declares a variable with - the targets of `zeebe:ioMapping`, the result variable of
an inline script, the result variable of a called decision, the output collection of a
multi-instance element - because a `@TaskParam` might read one of them and the model looked
like the only place this adapter could see such a name. The core had the names all along: it reads
them off the annotations while it builds the parameter binders. Keeping both would have left
two sources for one answer, and the model was the weaker of them in both directions, so
`declaredVariablesOf` is gone.

Three answers are deliberate. The list is a sorted `TreeSet`, because job streaming
compares it. Where any level of the configuration says `all`, the whole worker asks for
everything, without the guiding failure two conflicting job timeouts would produce -
fetching more than derived is never wrong. And where the core cannot name the aggregate-id
variable of a process, the worker asks for everything too, rather than for a list which
may be missing exactly what its handler reads.

The handlers carry the `Selection` because they need it in a message, not to decide
anything: `Camunda8JobHandler` and `Camunda8UserTaskListenerHandler` name it when the
aggregate-id variable is absent, and their invocation contexts throw when
`getTaskParameter` is asked for a name outside it. That throw is practically
unreachable - a statically named `@TaskParam` is in the list by construction - and it stays
for the name a handler computes at runtime, which the scanner cannot see. Its message says
so, because the first thing a reader checks is the annotation.

`Camunda8FetchVariablesTest` holds the derivation, the union and the three deliberate answers,
`Camunda8UnfetchedVariableTest` the two messages the handlers carry the `Selection` for.

## What an operator gets to see

The core measures every delivery on every BPMS. This adapter adds what only makes sense
here, and the whole of it hangs on two seams.

`applyWorkerOptions` is the one place all four kinds of worker pass through, so the
client's own metrics hook is installed there and nowhere else. The hook itself is the
client's; what is ours are the meter names, which is why `JobWorkerMetrics.micrometer()`
is deliberately NOT used: it would publish `camunda.job.invocations` and friends next to
`vanillabp.*`, and a reader should not have to learn two naming schemes for one dashboard.

`Camunda8Metrics` is plain Java with a no-op `NONE`, `MicrometerCamunda8Metrics`
implements it plus `MeterBinder`, and Micrometer stays optional exactly as in the platform
integration. The execution slots come from the executor the adapter hands the client, which
both execution models build, so the three of them say something whichever one is configured.
An adapter which booted degraded has no client and therefore no executor; there
only the configured number is published, the other two being absent instead of guessed.

**Reading a metric must not cost anything.** The platform's rule applies here too: a gauge
is read on every collection, Prometheus collects every fifteen seconds by default, a
dashboard collects alongside it, and every instance answers each of them - so a gauge which
asks a database or a cluster turns watching the system into load on it. None of this
adapter's gauges do. `execution.slots.configured` reads a record field,
`execution.slots.in.use` reads a counter the executor keeps of the handlers inside their
invocation, `jobs.waiting` reads the queue the handlers which found no slot are waiting in,
and the two job counters are incremented by the client rather than polled. They are therefore exact, and holding them would only make
them stale.

A gauge added here later which DOES have to ask - the cluster, a query API, anything remote
- goes through `CachedGaugeValue` of the adapter SPI
(`io.vanillabp.integration.adapter.spi.observability`), which holds one measurement for the
platform's `vanillabp.metrics.gauge-cache`. That class lives in the SPI precisely so an
adapter can keep the same promise; see `migration-adapter/README.md` for why it is built the
way it is.

`checkHealth()` asks for the topology. Two decisions are worth remembering:

- The timeout is a property of its own (`health-timeout`, two seconds), not the client's
  `request-timeout`. Ten seconds is right for a command carrying work and wrong for a
  question a readiness probe asks with a one-second patience.
- It is set TWICE, on the request and around the waiting. The client's own timeout stops
  the request, ours stops the waiting; without the first one a cluster which never answers
  would leave the request running long after the endpoint gave up on it.

A SaaS adapter whose connection is not configured yet answers UNKNOWN. That is the health side
of the same rule the startup validation follows: an application which booted degraded on
purpose has not failed. A self-managed adapter without an address is different. Its client
talks to the address of the local cluster, so the check asks the cluster there and answers UP or
DOWN, naming that address.

`MicrometerCamunda8MetricsTest` covers the meters, the gauges and the no-op hook a worker gets
without a registry, `Camunda8HealthTest` the two timeouts and the UNKNOWN above.

## Adapter SPI implementations

- `Camunda8DeploymentService implements AdapterDeploymentService<BpmnModelInstance,
  Camunda8ProcessingContext>` - one instance per configured adapter ID (not per type).
  `readBpmn` parses with `io.camunda.zeebe.model.bpmn.Bpmn.readModelFromStream` and returns
  one entry per executable `<process>`; `prepareBpmn` accumulates the deployable resources
  (deduplicated per filename) into the context; `deployResources` sends one
  `DeployResourceCommand` per workflow module (configured tenant or default). `wireBpmn`
  validates the task wiring against the core's `WorkflowTaskInvoker` and injects what V1
  injected (user-task listeners, subscription correlation keys);
  `startWorkflowProcessing` opens one polling job worker per task definition plus one per
  user-task listener type, `stopWorkflowProcessing` closes them again.
- `Camunda8ProcessService<A> implements MigratableProcessService<A>` - phase one validates
  only (resolve aggregate ID, verify client configured; no cluster call). Phase two creates the
  instance via `createProcessInstance(bpmnProcessId, variables, aggregateId)` (latest
  version), carrying the values the aggregate shares plus the technical variable named
  after the aggregate's ID property.
- `Camunda8ProcessingContext` - the adapter-specific processing context threaded through
  the deployment pipeline: the adapter id and the workflow-module ID of the run, the
  deployable resources (per filename) and the discovered BPMN process IDs.

The adapter SPI is served completely - deployment, workflow start (two-phase), task
processing, user tasks, message correlation, aggregate sync and the viewer/history API.
The ONE deliberate gap is `cancelUserTask`, which Camunda 8.8 offers no command for: it
throws a guiding `UnsupportedOperationException` instead of pretending to work (expected
to arrive with the 8.10 listener support). The election awareness probes are
implemented: `awarenessOfTask` (job-timeout refresh), `awarenessOfUserTask` (empty
user-task update), `awarenessOfWorkflow` (instance search) and the stricter
`awarenessOfWorkflowForRedispatch` (instance search without state filter, and never an
optimistic answer, see the root README's idempotency section). Whether the cluster can be
searched is asked once while a workflow module deploys and remembered per adapter id
(`Camunda8QueryApi`); the deployment REQUIRES a yes
(`Camunda8SearchableClusterCheck`), so a search failing later is an outage and nothing else.

`Camunda8DeploymentServiceTest` pins the pipeline calls, `Camunda8ProcessServiceTest` the two
phases, `Camunda8AwarenessWhenSearchFailsTest` the probes, `Camunda8QueryApiTest` the question
asked once and `Camunda8SearchableClusterCheckTest` the refusal which follows a no.

## What an extension of the pipeline is told

An extension implementing `ExtensionWiringService` runs inside this deployment pipeline,
on the same BPMN files, and what it needs to place its own wiring is the adapter's to hand
over rather than to work out a second time.

`Camunda8ProcessingContext` names the adapter id and the workflow module of the run it
belongs to. The pipeline calls every extension once per configured Camunda 8 adapter, each
run over that adapter's own copy of the module's files - the resource location is
configured per adapter - and the model handed over carries the identifiers of THAT adapter,
which name-clash avoidance may have rewritten. Without the id an extension cannot say whose
call it is looking at, and reading it back off the model's identifiers is no substitute:
two adapter ids may avoid name clashes differently, and then the identifiers do not decide
it.

`Camunda8VariableFilters.aggregateIdSearchValue` is how a search value for the workflow
aggregate's id has to look, quoted as the JSON the cluster stores. It is public rather than
copied because it is one expression: a second spelling of it returns an empty result, which
reads like a workflow the cluster does not hold.

`Camunda8Errors.notFound` reads the cluster's "I do not hold that" off both transports, the
REST `404` and the gRPC `NOT_FOUND`. An extension reading something the cluster was told
about a moment ago meets that answer as exporter lag rather than as a failure, and
recognising only one of the two codes turns the other transport's answer into a hard one.

`Camunda8ProcessingContext.getMultiInstanceRegistry` answers which multi-instance elements
enclose an element of the models this adapter wired. Camunda 8 tells a job its own element id
and nothing about the iteration it runs in, so the chain is model knowledge read while the
model is deployed. An extension which wants to name the iteration a task belongs to asks the
registry instead of reading the models a second time.

`Camunda8TaskWiring.readUserTasksOf` reports the Camunda-managed user tasks of a model and
changes nothing. Its sibling `userTasksOf` is the deployment path and writes the
version-1-compatible lifecycle listeners into the model. Both report the same list, and the
reading one is what an extension calls: a second party writing listeners into a model the
adapter owns is not made safe by the writing being idempotent, and nothing about the pipeline
promises that the adapter went first.

`Camunda8Workers.applyWorkerOptions` sets what a worker cannot inherit from the client: the job
counters, which carry the adapter id and the job type, and the stream timeout. A worker an
extension opens with it looks to an operator like a worker of the adapter.

`Camunda8Workers.leaseTheActivations` opens a worker with a lease on every activation, where the
application asked for one and the release line has one. It is not part of `applyWorkerOptions`,
because a worker which can ever serve an asynchronous task must not lease: such a task is
completed in phase two by a dispatcher holding no token, and only the caller knows what its
worker serves. An extension whose workers hold their job from the activation to the answer calls
it for the same reason it calls `applyWorkerOptions`. Two components leasing the same job type
with different opinions is the starvation the ratchet describes, and the decision belongs to the
adapter's configuration rather than to the extension. Why there is no default, and what a lease
costs a rollback, is decision 36 in the repository's `DECISIONS.md`.

`Camunda8Workers.open` opens the worker and counts it among the workers of its adapter id. The
workers of one client share its HTTP connection pool, and every one of them holds a connection
while it waits for work, so the number the adapter holds against that pool has to be the number
of workers really open. Counting happens here, where a worker is opened, rather than where the
adapter ordered its own: an extension's worker is then in the number too, and one opened long
after the start makes the check run again. An extension which brings a client of its own has a
pool of its own and does not come through here. What the number is held against is decision 50
in the repository's `DECISIONS.md`.

`Camunda8ClientFactory.countTheOpenWorkers` is that number, and it is the answer for anything
else which wants to know how much of the pool is taken. A worker which was closed gave its
connection back and is not in it.

The three calls above are what an extension author has to know and cannot find out by reading a
signature, so they are also on the wiki page
[Extending the adapter](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki/Extending-the-adapter),
with what happens when one of them is left out. That page is where an extension author looks;
this one says why the calls are separate.

`Camunda8ListenerJobs.completeOrFail` runs a listener job the way this adapter runs its own -
registered with the drain, both answers through `Camunda8CommandRetry`, and a failure during a
shutdown left to its lock rather than reported. That last part is what a listener whose failure
leaves no retries depends on: with no attempt left, failing the job IS the incident, and a
restart is not something anybody did wrong.

`Camunda8ClientFactory.drainOf` is the drain of one workflow module of one adapter id, and it is
the one an extension's listener has to take part in. A handler which is not in it is a handler
the shutdown does not wait for, and the client is then closed while it runs.

`Camunda8ClientFactory.workflowModuleStarted` hands back a registration rather than taking the
module's single slot. A workflow module holds a hook per party which opened workers of it, they
run in reverse registration order, and a party removing its own leaves the others registered.

`Camunda8ClientFactory.getJobTimeoutResolver` answers how long a job of this adapter id stays
locked, resolved over the four configuration levels. A workflow module which raised the
adapter's `job-timeout` raised it for an extension's worker too.

`Camunda8AdapterConfiguration.workflowVisibilityWindow` is how long a reader of this cluster may
treat "not there" as "not there yet". The number belongs to the cluster: an operator who raises
it for a slow exporter raises it once, and a reader with a window of its own keeps dropping what
the adapter now waits for.

`Camunda8Searches` builds the filter a search of this adapter needs - the process id as the
CLUSTER knows it, the tenant, and the aggregate id as the JSON the cluster stores.
`scopedTo` takes a process-instance filter, a process-definition filter or a user-task filter,
and `byAggregateId` is the unscoped search an awareness probe runs. The client spells the same
conditions differently per search, which is why the user-task overload exists rather than a
second hand-built filter: it names the process id `bpmnProcessId` and compares the aggregate id
as a variable of the process instance, where VanillaBP writes it. Every one of them only adds
conditions, so a caller narrows further afterwards. For user tasks the caller is the one which
says whether it means a single task key and whether only open tasks count.

`Camunda8CancelListeners` says whether an instance of the release line this build belongs to
can report its own cancelation, writes the `cancel` execution listener of a process into a
model, and recognises the job of such a listener. The three halves live together because all
three name a client constant the older lines do not have, and the class is per release line
for the same reason. An extension writes the same listener with retries of its own, so the
retries are a parameter, and it must ask whether the line has the construct before writing
one: a listener nobody serves holds the instance until its job is answered.

`Camunda8ErrorsTest` holds both transports and the wrapped answer, `Camunda8VariableFilterTest`
the quoting, `Camunda8DeploymentServiceTest` that a context knows which adapter, which workflow
module and which registry its run is for, `Camunda8UserTasksReadAndPrepareTest` what separates
reading from preparing, `Camunda8WorkersTest` what a worker carries,
`Camunda8ListenerJobsTest` the protocol including the shutdown, `Camunda8ShutdownHooksTest` the
hooks, the drain and the resolver, `Camunda8VisibilityWindowTest` the window,
`Camunda8SearchesTest` the filter and `Camunda8CancelListenersTest` what each line writes and
recognises.

Why this list exists and what is deliberately not on it is decision 28 in the repository's
`DECISIONS.md`.

## BPMN model type

The BPMN model type is `io.camunda.zeebe.model.bpmn.BpmnModelInstance`, shipped in the
artifact `io.camunda:zeebe-bpmn-model`. Against the resolved Camunda 8 client
`io.camunda:camunda-client-java:8.8.31`, `zeebe-bpmn-model:8.8.31` is a transitive
dependency and the class/artifact are **unchanged** from Camunda 7-era Zeebe (no rename
in 8.8). `readBpmn` parses with it, `prepareBpmn`/`wireBpmn` modify the model (listener
and subscription injection) and `deployResources` serializes it back.

## Client artifact

The core depends on `io.camunda:camunda-client-java` (which brings `zeebe-bpmn-model`
transitively). The plain Java client is used deliberately instead of Camunda's Spring
SDK - see the root `README.md`.

## Platform version guard

`META-INF/vanillabp/adapter-camunda8.properties` carries this adapter's version, its Maven
coordinates and the version of the VanillaBP platform integration it was built against
(`platform.version=${adapter-platform.version}`, filled by resource filtering configured
in `pom.xml`). The `Camunda8DeploymentService` constructor passes it to
`VanillaBpParts.requireAdapterFitsPlatform(...)`, and the platform judges the same
descriptor once more while it boots. The boot ends with a message naming both versions and
the dependency to change if the platform integration on the classpath is older than the one
this adapter was built against, or if this adapter is older than the oldest one that
platform integration still serves. Maven does not report either as a conflict, because a
version managed by the application always wins over the version required transitively by
this adapter, even as a downgrade. See `migration-adapter/README.md`, section "Parts which
do not belong together", of the VanillaBP platform repository.

The check itself belongs to the platform SPI, so from this repository the abort is an
assumption: what would disprove it is a boot against an older platform integration which does
not end.
