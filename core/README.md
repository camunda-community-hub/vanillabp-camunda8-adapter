# Camunda 8 adapter - core

Contributor documentation for the platform-neutral core of the VanillaBP Camunda 8
adapter. Documentation for users is in
[this adapter's wiki](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki).
The `README.md` at the root of the repository documents the repository for contributors.

The `core` module is **plain Java**, with no Spring or Quarkus dependencies. It holds the
adapter SPI implementations and all Camunda 8 client logic. The platform modules
(`spring-boot`, `quarkus`) only construct and register these core objects (read
configuration, create beans, run the bean lifecycle).

## Client construction

- `Camunda8AdapterConfiguration` - resolved, platform-neutral connection configuration of
  one adapter instance (mode, REST/gRPC address, SaaS credentials, tenant). Populated by
  the platform modules from `vanillabp.adapters.<adapter-id>.*` (see the root `README.md`).
  `Camunda8StartupValidation` validates it at startup. `validate(adapterId)` stays as the
  last check before a client is used. It throws an exception which names the exact missing
  property. A self-managed adapter without an address is not missing anything. It uses the
  address of the local cluster of its release line, and the start warns about it.
  `Camunda8LocalCluster` holds that address once per line, next to the other line sources.
- `Camunda8ClientFactory` - owns the single `CamundaClient` of one adapter instance. The
  client is built **eagerly at startup** for every self-managed instance and every completely
  configured SaaS instance, and closed on `close()`. Building it never contacts the cluster.
  That happens on the first command. `newClientBuilder()` is used for self-managed,
  `newCloudClientBuilder()` for SaaS.
- `Camunda8ClientFactoryRegistry` - map adapter ID &rarr; factory. It is registered as a
  managed bean, so all clients are closed on shutdown. It is also the only place which sees
  every configured id at once. So it answers which ids address the SAME cluster. Keys are
  unique per cluster. Where two ids share one cluster, the awareness probes have to ask which
  scope a key belongs to before they claim it. The factory knows which workflow modules have
  workers open. Before it closes the client, it closes the modules which never stopped
  themselves. So the order holds on every shutdown path, not only on the path the lifecycle of
  each platform takes.

`Camunda8ClientFactoryTest` covers this, including that building the client contacts nothing.
`Camunda8SharedClusterTest` and `Camunda8InstanceIdentityTest` cover which ids the registry
counts as one.

## What a worker sends back to the cluster

Four classes share the way back, so the rules live in one place and not in four handlers:

- `Camunda8Errors` classifies a failure. `permanentFailure` answers for the outbox.
  `repeatableJobCommandFailure` answers for a job command. It adds the one case which is
  permanent only there: a job which is gone. That case is `notFound`, read for what it means
  to a job command. So the way both transports say it is recognised in one place.
  `incidentMessage` builds the text an operator reads in Operate, with the type of the
  exception in front of its message.
- `Camunda8CommandRetry` repeats a rejected command. Three things limit it: the remaining
  lock of the job (`ActivatedJob#getDeadline()`), five attempts, and the shutdown. Between
  attempts it waits as long as the client's own activation backoff. When the limit is
  reached, the outcome is the same as without the retry: the original failure is rethrown
  and the caller does what it did before.
- `Camunda8RetryBackoffResolver` resolves `retry-backoff` over the four configuration
  levels. It does this per command, not per worker. The value goes with every fail command
  which leaves the job some retries. Its answer names the level the value comes from,
  because only the task level has to be weighed against what the model says.
- `Camunda8RetryBackoffHeader` reads the `retryBackoff` task header which version 1 read.
  It reads it from the job, not from the model. So the header also holds for process
  versions this application never deployed, and an application coming from version 1 brings
  such versions. The header wins over every configuration level above the task and loses to
  the task level. A value which is not an ISO-8601 duration costs one log line per element.
  Version 1 fell back to `Duration.ZERO` instead.
- `Camunda8Drain` decides whether a failure belongs to the shutdown. If it does, no command
  is sent at all and the job is left to its lock. The class also holds what a shutdown waits
  for, and the log line it writes about it. A shutdown waits for the handlers of the module
  and for the workers to report themselves closed. An activation request which is parked at
  the cluster when the client is closed stays parked, and it swallows the first job of the
  next application.

The rules have their tests: `Camunda8ErrorsTest` for the classification,
`Camunda8CommandRetryTest` for the limits and the waits, `Camunda8OutcomeCommandRetryTest` for
the four kinds of worker sending through it, `Camunda8RetryBackoffHeaderTest` for the tie-break
between the header and the task level, and `Camunda8DrainTest` with
`Camunda8ShutdownHandlingTest` for what a shutdown leaves alone.

## What a worker asks the cluster for

`Camunda8FetchVariables` holds both halves of it: the list of variables a worker asks for,
and the two messages a delivery writes when it is asked for a variable outside that list.

The list is computed in `Camunda8DeploymentService#fetchVariablesOf`, once per worker while
`startWorkflowProcessing` opens the workers. Its input is one `ServedElement` per BPMN
element the worker serves. That is why the four kinds of worker share one method. A task
worker serves the tasks of one job type across the processes of the module. A user-task
listener worker serves the user tasks of its listener job type. The workflow-end worker
serves one process. Three sources feed it, and all three are bookkeeping of the core or of
this adapter:

- `resolveWorkflowAggregateIdName` per BPMN process;
- the multi-instance registry, filled during `wireBpmn`, with the process id the CLUSTER knows
  plus the element id as key;
- `taskParameterNames` per served task definition.

That last one replaced a scan of the model. A Camunda 8 model declares a variable with four
constructs: the targets of `zeebe:ioMapping`, the result variable of an inline script, the
result variable of a called decision, and the output collection of a multi-instance element.
The scan collected all four, because a `@TaskParam` might read one of them. The model looked
like the only place where this adapter could see such a name. But the core had the names all
along: it reads them from the annotations while it builds the parameter binders. Keeping both
would have left two sources for one answer. The model was the weaker source in both
directions, so `declaredVariablesOf` is gone.

Two answers are deliberate. The list is a sorted `TreeSet`, because job streaming compares
it. And if the core cannot name the aggregate-id variable of a process, the worker asks for
all variables. A list could miss exactly the variable its handler reads.
No configuration changes the list. In snapshots of version 2.0 the key `fetch-variables`
did. The platforms still bind it, at all four levels, for one reason only:
`Camunda8FetchVariables#rejectTheRemovedKey` ends the start with a message which names
every key that sets it.

The handlers carry the `Selection` only because they need it in a message. They do not
decide anything with it. `Camunda8JobHandler` and `Camunda8UserTaskListenerHandler` name it
when the aggregate-id variable is absent. Their invocation contexts throw when
`getTaskParameter` is asked for a name outside it. That throw can hardly ever happen,
because a statically named `@TaskParam` is always in the list. It stays for a name which a
handler computes at runtime, because the scanner cannot see such a name. The message says
so, because the first thing a reader checks is the annotation. It also points to the
workflow aggregate as the place to read such a value from.

`Camunda8FetchVariablesTest` covers how the list is computed, the union, the two deliberate
answers and the refused key. `Camunda8UnfetchedVariableTest` covers the two messages for
which the handlers carry the `Selection`.

## What an operator gets to see

The core measures every delivery on every BPMS. This adapter adds what only makes sense
here, and all of it hangs on two places in the code.

All four kinds of worker pass through `applyWorkerOptions`. So the client's own metrics hook
is installed there and nowhere else. The hook itself belongs to the client, but the meter
names are ours. That is why `JobWorkerMetrics.micrometer()` is deliberately NOT used. It
would publish `camunda.job.invocations` and similar meters next to `vanillabp.*`, and a
reader should not have to learn two naming schemes for one dashboard.

`Camunda8Metrics` is plain Java with a no-op `NONE`. `MicrometerCamunda8Metrics` implements
it plus `MeterBinder`. Micrometer stays optional, exactly as in the platform integration.
The execution slots come from the executor which the adapter hands to the client. Both
execution models build that executor, so the three slot meters say something whichever
model is configured. An adapter which booted degraded has no client and so no executor.
There only the configured number is published. The other two are absent, not guessed.

**Reading a metric must not cost anything.** The rule of the platform applies here too. A
gauge is read on every collection. Prometheus collects every fifteen seconds by default, a
dashboard collects as well, and every instance answers each of them. So a gauge which asks a
database or a cluster turns watching the system into load on it. None of the gauges of this
adapter do that:

- `execution.slots.configured` reads a record field;
- `execution.slots.in.use` reads a counter which the executor keeps of the handlers that are
  running;
- `jobs.waiting` reads the queue in which the handlers without a free slot wait;
- the client increments the two job counters, nobody polls them.

So they are exact, and caching them would only make them stale.

Later someone may add a gauge here which DOES have to ask the cluster, a query API or
anything else remote. Such a gauge goes through `CachedGaugeValue` of the adapter SPI
(`io.vanillabp.integration.adapter.spi.observability`). It holds one measurement for the
time set in `vanillabp.metrics.gauge-cache` of the platform. That class lives in the SPI so
that an adapter can keep the same promise. `migration-adapter/README.md` explains why it is
built the way it is.

`checkHealth()` asks for the topology. Two decisions are worth remembering:

- The timeout is a property of its own (`health-timeout`, two seconds), not the client's
  `request-timeout`. Ten seconds is right for a command which carries work. It is wrong for
  a question from a readiness probe which waits only one second.
- It is set TWICE: on the request and around the waiting. The client's own timeout stops
  the request, ours stops the waiting. Without the first one, a cluster which never answers
  would leave the request running long after the endpoint gave up on it.

A SaaS adapter whose connection is not configured yet answers UNKNOWN. The startup validation
follows the same rule: an application which booted degraded on purpose has not failed. A
self-managed adapter without an address is different. Its client talks to the address of the
local cluster, so the check asks the cluster there and answers UP or DOWN, naming that address.

`MicrometerCamunda8MetricsTest` covers the meters, the gauges and the no-op hook a worker gets
without a registry. `Camunda8HealthTest` covers the two timeouts and the UNKNOWN above.

## Adapter SPI implementations

- `Camunda8DeploymentService implements AdapterDeploymentService<BpmnModelInstance,
  Camunda8ProcessingContext>` - one instance per configured adapter ID (not per type).
  `readBpmn` parses with `io.camunda.zeebe.model.bpmn.Bpmn.readModelFromStream` and returns
  one entry per executable `<process>`. `prepareBpmn` collects the deployable resources in
  the context, one per filename. `deployResources` sends one `DeployResourceCommand` per
  workflow module, to the configured tenant or the default one. `wireBpmn` validates the task
  wiring against the core's `WorkflowTaskInvoker`. It also injects what V1 injected:
  user-task listeners and subscription correlation keys. `startWorkflowProcessing` opens one
  polling job worker per task definition plus one per user-task listener type.
  `stopWorkflowProcessing` closes them again.
- `Camunda8ProcessService<A> implements MigratableProcessService<A>` - phase one only
  validates: it resolves the aggregate ID and checks that the client is configured. It does
  not call the cluster. Phase two creates the instance with
  `createProcessInstance(bpmnProcessId, variables, aggregateId)` (latest version). The
  instance gets the values the aggregate shares, plus the technical variable named after the
  ID property of the aggregate.
- `Camunda8ProcessingContext` - the adapter-specific processing context. It is passed through
  the deployment pipeline and holds the adapter id and the workflow-module ID of the run, the
  deployable resources (per filename) and the discovered BPMN process IDs.

The adapter SPI is served completely: deployment, workflow start (two-phase), task
processing, user tasks, message correlation, aggregate sync and the viewer/history API.
There is ONE deliberate gap: `cancelUserTask`. No cluster offers a command for it. So it
throws an `UnsupportedOperationException` which says what to do instead, rather than
pretending to work, on every line, 8.10 included. The listeners of 8.10 make it possible, but
this adapter does not build on them yet. The election
awareness probes are implemented:

- `awarenessOfTask` (refreshes the job timeout);
- `awarenessOfUserTask` (an empty user-task update);
- `awarenessOfWorkflow` (an instance search);
- the stricter `awarenessOfWorkflowForRedispatch` (an instance search without state filter,
  and never an optimistic answer; see the idempotency section of the root README).

Whether the cluster can be searched is asked once while a workflow module deploys. The answer
is remembered per adapter id (`Camunda8QueryApi`). The deployment REQUIRES a yes
(`Camunda8SearchableClusterCheck`). So if a search fails later, that is an outage and nothing
else.

`Camunda8DeploymentServiceTest` pins the pipeline calls and `Camunda8ProcessServiceTest` the two
phases. `Camunda8AwarenessWhenSearchFailsTest` covers the probes, `Camunda8QueryApiTest` that
the question is asked once, and `Camunda8SearchableClusterCheckTest` the refusal after a no.

## What an extension of the pipeline is told

An extension implementing `ExtensionWiringService` runs inside this deployment pipeline, on
the same BPMN files. What it needs to place its own wiring, the adapter hands over. The
extension should not have to work it out a second time.

`Camunda8ProcessingContext` names the adapter id and the workflow module of its run. The
pipeline calls every extension once per configured Camunda 8 adapter. Each run works on that
adapter's own copy of the module's files, because the resource location is configured per
adapter. The model handed over carries the identifiers of THAT adapter, and name-clash
avoidance may have rewritten them. Without the id, an extension cannot tell whose call it is
looking at. Reading the id back from the identifiers of the model does not work: two adapter
ids may avoid name clashes differently, and then the identifiers do not decide it.

`Camunda8VariableFilters.aggregateIdSearchValue` shows how a search value for the id of the
workflow aggregate has to look: quoted as the JSON the cluster stores. It is public and not
copied, because there must be only one such expression. A second spelling of it returns an
empty result, and that looks like a workflow the cluster does not hold.

`Camunda8Errors.notFound` recognises the cluster's "I do not hold that" on both transports:
the REST `404` and the gRPC `NOT_FOUND`. An extension may read something the cluster was told
about a moment ago. Then that answer means exporter lag, not a failure. If only one of the
two codes is recognised, the answer of the other transport turns into a hard failure.

`Camunda8ProcessingContext.getMultiInstanceRegistry` answers which multi-instance elements
enclose an element of the models this adapter wired. Camunda 8 tells a job its own element id
and nothing about the iteration it runs in. So the chain of enclosing elements is knowledge
about the model, read while the model is deployed. An extension which wants to name the
iteration of a task asks the registry instead of reading the models a second time. A caller
may hand down a chain by an expression. Such a chain is not in the models, it arrives in a
variable of the instance. So an extension which fetches the variables of a task itself asks
`Registry#mayBeHandedAChain` whether it has to fetch that variable as well.

`Camunda8TaskWiring.readUserTasksOf` reports the Camunda-managed user tasks of a model and
changes nothing. Its sibling `userTasksOf` is used on the deployment path. It writes the
lifecycle listeners which version 1 used into the model. Both report the same list, and an
extension calls the reading one. A model belongs to the adapter. If a second party writes
listeners into it, idempotent writing does not make that safe. And nothing in the pipeline
promises that the adapter went first.

`Camunda8Workers.applyWorkerOptions` sets what a worker cannot inherit from the client: the job
counters, which carry the adapter id and the job type, and the stream timeout. A worker an
extension opens with it looks to an operator like a worker of the adapter.

`Camunda8Workers.leaseTheActivations` opens a worker with a lease on every activation, if the
application asked for one and the release line has one. It is not part of `applyWorkerOptions`.
A worker which may ever serve an asynchronous task must not lease. Such a task is completed in
phase two by a dispatcher which holds no token, and only the caller knows what its worker
serves. An extension whose workers hold their job from the activation to the answer calls it,
for the same reason it calls `applyWorkerOptions`. If two components lease the same job type
with different settings, the result is the starvation the ratchet describes. So the decision
belongs to the adapter's configuration, not to the extension. Decision 36 in the repository's
`DECISIONS.md` explains why there is no default, and what a lease costs a rollback.

`Camunda8Workers.open` opens the worker and counts it among the workers of its adapter id. The
workers of one client share its HTTP connection pool. Each of them holds a connection while it
waits for work. So the number the adapter compares with the pool size has to be the number of
workers which are really open. The counting happens here, where a worker is opened, and not
where the adapter ordered its own workers. That way the worker of an extension is counted too,
and a worker opened long after the start makes the check run again. An extension which brings
its own client has its own pool and does not come through here. Decision 50 in the repository's
`DECISIONS.md` says what the number is compared with.

`Camunda8ClientFactory.countTheOpenWorkers` is that number. Anything else which wants to know
how much of the pool is taken asks it. A worker which was closed gave its
connection back and is not in it.

An extension author has to know the three calls above and cannot learn them from a signature.
So they are also on the wiki page
[Extending the adapter](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki/Extending-the-adapter),
with what happens when one of them is left out. An extension author looks there. This page
says why the calls are separate.

`Camunda8ListenerJobs.completeOrFail` runs a listener job the way this adapter runs its own:
registered with the drain, both answers sent through `Camunda8CommandRetry`, and a failure
during a shutdown left to its lock instead of reported. A listener whose failure leaves no
retries depends on that last part. With no attempt left, failing the job IS the incident, and
a restart is not something anybody did wrong.

`Camunda8ClientFactory.drainOf` is the drain of one workflow module of one adapter id. The
listener of an extension has to take part in it. The shutdown does not wait for a handler which
is not in it, and then the client is closed while the handler runs.

`Camunda8ClientFactory.workflowModuleStarted` hands back a registration. It does not take the
single slot of the module. A workflow module holds one hook for each party which opened workers
for it. The hooks run in reverse order of registration. If a party removes its own hook, the
others stay registered.

`Camunda8ClientFactory.getJobTimeoutResolver` answers how long a job of this adapter id stays
locked, resolved over the four configuration levels. A workflow module which raised the
adapter's `job-timeout` raised it for an extension's worker too.

`Camunda8AdapterConfiguration.workflowVisibilityWindow` is how long a reader of this cluster may
treat "not there" as "not there yet". The number belongs to the cluster. An operator raises it
once for a slow exporter. A reader with a window of its own keeps dropping what the adapter now
waits for.

`Camunda8Searches` builds the filter a search of this adapter needs: the process id as the
CLUSTER knows it, the tenant, and the aggregate id as the JSON the cluster stores.
`scopedTo` takes a process-instance filter, a process-definition filter or a user-task filter.
`byAggregateId` is the unscoped search an awareness probe runs. The client spells the same
conditions differently for each search. That is why there is a user-task overload, and not a
second filter built by hand. It names the process id `bpmnProcessId`, and it compares the
aggregate id as a variable of the process instance, where VanillaBP writes it. Each of them
only adds conditions, so a caller can narrow the search further afterwards. For user tasks the
caller says whether it means a single task key and whether only open tasks count.

`Camunda8CancelListeners` does three things. It says whether an instance on the release line of
this build can report its own cancelation. It writes the `cancel` execution listener of a
process into a model. And it recognises the job of such a listener. The three parts live
together because all three name a client constant which the older lines do not have. For the
same reason there is one class per release line. An extension writes the same listener with
its own retries, so the retries are a parameter. Before it writes one, the extension must ask
whether the line has the construct. A listener which nobody serves holds the instance until
its job is answered.

The tests:

- `Camunda8ErrorsTest` covers both transports and the wrapped answer;
- `Camunda8VariableFilterTest` the quoting;
- `Camunda8DeploymentServiceTest` that a context knows which adapter, which workflow module and
  which registry its run is for;
- `Camunda8UserTasksReadAndPrepareTest` what separates reading from preparing;
- `Camunda8WorkersTest` what a worker carries;
- `Camunda8ListenerJobsTest` the protocol, including the shutdown;
- `Camunda8ShutdownHooksTest` the hooks, the drain and the resolver;
- `Camunda8VisibilityWindowTest` the window;
- `Camunda8SearchesTest` the filter;
- `Camunda8CancelListenersTest` what each line writes and recognises.

Decision 28 in the repository's `DECISIONS.md` says why the list of calls in this section
exists and what is deliberately not on it.

## BPMN model type

The BPMN model type is `io.camunda.zeebe.model.bpmn.BpmnModelInstance`, shipped in the
artifact `io.camunda:zeebe-bpmn-model`. It is a transitive dependency of the Camunda 8 client
`io.camunda:camunda-client-java`, in the same version, which the release line pins in the parent
`pom.xml`. Class and artifact are **unchanged** from Camunda 7-era Zeebe; 8.8 did not rename
them. `readBpmn` parses with it. `prepareBpmn` and `wireBpmn` modify the model: they inject
listeners and subscriptions. `deployResources` serializes it back.

## Client artifact

The core depends on `io.camunda:camunda-client-java` (which brings `zeebe-bpmn-model`
transitively). The plain Java client is used deliberately instead of Camunda's Spring
SDK - see the root `README.md`.

## Platform version guard

`META-INF/vanillabp/adapter-camunda8.properties` carries this adapter's version, its Maven
coordinates and the version of the VanillaBP platform integration it was built against
(`platform.version=${adapter-platform.version}`, filled by resource filtering configured
in `pom.xml`). The `Camunda8DeploymentService` constructor passes it to
`VanillaBpParts.requireAdapterFitsPlatform(...)`. The platform checks the same descriptor
once more while it boots. The boot ends in two cases: if the platform integration on the
classpath is older than the one this adapter was built against, or if this adapter is older
than the oldest adapter that platform integration still serves. The message names both
versions and the dependency to change. Maven does not report either case as a conflict. A
version managed by the application always wins over the version this adapter requires
transitively, even if that is a downgrade. See `migration-adapter/README.md`, section "Parts which
do not belong together", of the VanillaBP platform repository.

The check itself belongs to the platform SPI. So from this repository, the abort is an
assumption. A boot against an older platform integration which does not end would prove it
wrong.
