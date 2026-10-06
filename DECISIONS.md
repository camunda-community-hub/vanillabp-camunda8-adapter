# Decision log

Decisions this repository's code points at. A number is handed out once and never reused or
renumbered, so a citation stays resolvable; a decision which gets overturned keeps its entry,
marked as superseded and naming the entry which replaced it.

A citation in code reads `see decision 3 in the repository's DECISIONS.md`, and it names an entry
of THIS repository only. A decision which the platform shares has its own entry in
`adapter-platform-integration`, written from that side; a pointer into another repository is the
fragile kind this log exists to avoid.

Links below point into this repository's [`README.md`](./README.md), which carries the detail an
entry deliberately leaves out.

### 1. A command carries the shared aggregate values and the aggregate-ID variable, nothing else

Camunda 8 has no business key, so the variable named after the aggregate's ID attribute is
the only way back from a process instance to the workflow, and it is written no matter what
the sync model says. Beside it travel the values the aggregate shares, because a gateway right
behind a service task decides on what the handler just computed. Nothing else does: a
correlated message carries no content of its own.

A LISTENER job splits this into three cases, and the job itself says which one applies. An
execution listener on `end` completes exactly like a service task, with the shared values and the
aggregate-ID variable. They reach the process instance, so a gateway behind the element decides on
what the method wrote. The listener of a BPMS-initiated start has always completed that way, and a
listener somebody modelled does so too, which is what entry 27 is about.

An execution listener on `start` completes with nothing. The cluster keeps the variables of such a
completion local to the element. There they shadow the process variables of the same name, they
swallow every later write of that name from inside the element - the element's own job included -
and they die with the element. So sending the aggregate would take the element's own task's values
with it. Measured against cluster and client 8.9.19 in September 2026.

A task listener completes with nothing as well, and there is nothing to weigh up: the cluster
refuses a task-listener completion carrying variables, names its issue 23702 and says the payload
is not supported yet. The user-task lifecycle listeners VanillaBP writes itself are those jobs, and
their job would be the wrong place in any case - it gates a transition of a task which stays in the
cluster, and writing there would overwrite what a form or a task list put into the instance.

That is the way OUT. The way IN is a separate question, and it follows decision 8 for every
worker, the listeners VanillaBP writes itself included. A user-task listener fetches what the
method serving that task reads, the end of a workflow fetches only the aggregate's id, and a start
the cluster fires itself fetches every variable, because VanillaBP copies all of them into the
aggregate it builds. A listener somebody modelled is served by a `@WorkflowTask` method which may
declare `@TaskParam`, so its worker fetches exactly what that method asks for. A user's listener
therefore sees more than zero variables, and what it may write back is the question above.

### 2. Workflow modules are kept apart by scoping the identifiers

The cluster is always addressed with the SCOPED identifiers - process ids, message and signal
names, error codes and task definitions - while the core's registries stay keyed by the plain
ones, and everything coming back from the cluster is translated before the core sees it. Which
shape scoping takes is the workflow module's configuration: a tenant, a prefix, or nothing at
all, so no code may assume either. Two processes must not end up under the same scoped
identifier, which is what the collision check while preparing a model is for.

The task definition of a connector is none of these identifiers. Its implementation does not come
from the workflow module, it comes from a runtime somebody else deployed cluster-wide, so there is
nothing in that name for a prefix to keep apart and the adapter leaves it as the modeller wrote it.
Under a tenant the model is still deployed into the module's tenant, so a connector runtime has to
be able to see that tenant. Decision 23 says which elements carry such a name and what allowing
them costs.
See [Keeping workflow modules apart](./README.md#keeping-workflow-modules-apart).

### 3. A cluster key never says which scope it belongs to

Job keys, user-task keys and process-instance keys are unique per CLUSTER and carry neither
tenant nor prefix. Where two adapter ids address one cluster - the setup which migrates a
workflow module from tenants to prefixes - a credential which is a member of both scopes gets
an operation of the wrong adapter accepted without a word. Ownership is therefore decided by
the scope a workflow was deployed under, never by a key: the awareness probes compare tenant
and scoped process definition id against the scope of the CALL before they answer, and the two
task probes read the job respectively the user task to learn its scope. That read is a
query-API call, which is why two ids on a cluster without secondary storage end the boot
rather than misrouting silently.

Since decision 20 the boot ends for ONE adapter id on such a cluster as well, so the check
which counted the ids is gone and the requirement is what refuses that setup. What this entry
decided is untouched: ownership is decided by the scope a workflow was deployed under, never
by a key.

### 4. A class opens its fields one by one, not as a whole

The process service, the deployment service and the client classes of this adapter hold dozens
of fields, most of them collaborators nobody outside the class needs. Which of them a caller
may read belongs to the surface of the class, so an accessor is declared per field, and
`@Getter` on the class is refused even where an IDE offers it: it would publish the current
field list and then keep publishing whatever field a later change adds.
`@SuppressWarnings("LombokGetterMayBeUsed")` on such a class is what keeps that offer from
coming back.

### 5. The deployed model is rewritten so the cluster can do what an embedded engine does for free

Camunda 8 runs remote and answers only what its protocol carries, so several things an embedded
engine offers as a side effect have to be put INTO the model before it reaches the cluster.
`prepareBpmn` and `wireBpmn` therefore add the scoped identifiers of decision 2, the user-task
listeners whose jobs become the CREATED and CANCELED notifications, a `correlationKey` expression
on every message subscription which has none so a catch event correlates by the aggregate id
without the application modelling anything, an `end` execution listener on the process element for
the end of a workflow and on a start event for a workflow the cluster starts itself, and the input
mappings which make the multi-instance index, total and element readable at all.

The correlation key has a boundary it always had. The aggregate id that expression points at is
the aggregate of a process this application serves; a process no workflow service claims has none
to name one from. Such a file is refused instead of rewritten, and before anything of it is
prepared: the cluster demands a subscription on the message of every executable process which waits
for one and rejects the whole file over a missing one, so the boot ends with a message which says
where in the model the gap is. Whether that model gains a correlation key or loses its
`isExecutable` is the modeller's decision, and this adapter does not take it for them by writing
something into a process the application does not serve.

Two rules keep that predictable. Every addition is idempotent, so re-wiring the same model does
not stack listeners, and nothing the application modelled itself is overwritten. The price is
stated rather than hidden: a model which the adapter rewrites is a new process version in the
cluster, so an application with multi-instance models deploys one on the upgrade.

### 6. A restart waits for the cluster to let go of the workers

`JobWorker#close()` does not drain, and `CamundaClient#close()` interrupts a running handler
within milliseconds. Worse, an activation request of the REST transport survives the client which
sent it: a job created while it is pending is assigned to it, counts as activated, and is answered
by nobody until its lock expires, so the application which starts next waits out `job-timeout`
rather than milliseconds. Measured against a real cluster, that turned a seven-second restart into
a twenty-second gap.

So `stopWorkflowProcessing` closes the workers of the module first, and the shutdown then waits,
within `shutdown-grace`, for two things: the handlers which are still inside the application, and
the cluster releasing the workers. The wait is one wait for every workflow module of the adapter
instance rather than one per module, which is decision 51. The client factory closes
whatever never reached that path before it closes the client, so the order holds on every shutdown
path and not only on the one the platform lifecycles happen to take.

While that shutdown runs, no worker reports a job as failed. The job keeps its lock and its
retries, the cluster redelivers it, and the delivery record of the platform decides whether the
work runs again. What decides is the STATE of the adapter, never the type of the exception,
because an interrupted handler throws like any other.

### 7. The adapter chooses how many handlers run at once

The reasoning of this entry is superseded by decision 18. What it decided still holds - the adapter
picks how many handlers run at once, the default is four, and `virtual` is a regular mode - but the
number no longer means threads shared between the handlers and the scheduling of every poll, and
what the 8.8 line does on its own no longer decides anything.

The client's default is a single thread, and on the 8.8 line that same thread also schedules the
polling of every worker, so one blocking handler stopped the adapter from asking for work at all.
`worker-threads` therefore has a default of its own, four platform threads, and accepts `virtual`
for an unbounded number of virtual threads whose concurrency `worker-threads-bound` limits to the
same figure. Switching the mode changes how threads come into being, not how much runs at once.

Four rather than one because of the defect, and small rather than large because every running
handler holds a database connection. Virtual threads are a regular mode rather than a caveat: a
pinning probe over six driver and transaction-manager combinations measured no pinning at all.

Everything except the stream timeout is configured on the CLIENT rather than per worker, so an
environment variable can still win, and `Camunda8EnvironmentOverrides` compares what the adapter
asked for with what the client reports and warns with variable, property key and both values.

### 8. A worker fetches only the variables somebody actually reads

Nothing asked for `fetchVariables`, so every activated job carried the complete variable scope of
its instance, which with a `FULL` sync model means every shared attribute of an aggregate the
handler is about to load from its own database anyway.

The list is derived per WORKER, as the union over everything that worker serves, and sorted,
because job streaming compares the list and the comparison has to survive a restart. In it are the
aggregate-id variable of each served process, the multi-instance variables of the iterations
around the served element, and the union of the `@TaskParam` names the core reports for that
element. The core is the source for the last part rather than a scan of the model, because a model
declares names nobody reads and misses names no model carries.

A `@TaskParam` outside the list fails the delivery with the variable, the list and the property
instead of arriving as `null`. A `null` would be indistinguishable from a value which genuinely
does not exist, which is the silent loss this avoids. `fetch-variables: all` is the way out, per
task if need be.

### 9. What a job command may repeat, and how long it may keep trying

A cluster under load rejects commands, and the client retries none of them. Phase two is carried
by the outbox, but the command inside a handler is not: a rejected completion of already committed
work costs the job a retry and, under sustained load, produces an incident.

So completion, BPMN error, failure and lock renewal of every worker kind run inside
`Camunda8CommandRetry`. What may be repeated is what `Camunda8Errors` classifies as repeatable,
plus the explicit exception that a job which is gone is final, or the at-least-once residual would
turn into a storm. A socket which timed out belongs to that class, and it is worth saying so
because the classification alone does not name it: no answer arrived, so the command may or may
not have run, which is the case the job's lock exists for. The arithmetic is what makes repeating
it affordable. A lock of five minutes against a request timeout of ten seconds leaves room for
every attempt this entry allows, and a completion which did arrive comes back as a job which is
gone, which is final. What bounds it is the job's REMAINING lock, taken from the activated job rather
than from the configured timeout, five attempts with the client's own backoff figures, and the
shutdown of decision 6, which ends the retry at once so the job stays on its lock instead of being
reported.

### 10. Authentication belongs to the connection, not to a workflow

`vanillabp.adapters.<id>.auth.*` exists only at adapter level, because a credential is a property
of the connection. The method is named or detected from the keys which are set, and the detection
is printed next to the address at startup so nobody has to guess which one applies.

The runtime message hangs on `CredentialsProvider.shouldRetryRequest` rather than on the error
classification. The client calls it for every rejected request, on both transports, for commands
as well as for job activation, which makes it the one place where an adapter learns that it is
unwelcome. Going through the error classification would have meant two dozen call sites and would
still have missed the workers.

`mtls` is deliberately not a key. The client has no keystore for its own connection to the
cluster on any of the supported lines, only for the token request, and a property which quietly
configures something other than what its name says is worse than no property.
See [Authenticating against a cluster](./README.md#authenticating-against-a-cluster).

### 11. The client an artifact was built against is the minimum cluster version

Camunda does not promise that a newer client works against an older cluster, and it has been
measured that it does not. So a single artifact per adapter version would mean that every bugfix
becomes deliverable only together with a cluster upgrade, as soon as anything in the code touches
what only the newest cluster has.

The adapter is therefore released once per Camunda minor, with the minor in the version
(`2.1.0-8.9`), built from ONE source tree through the `line-8.8`, `line-8.9` and `line-8.10`
profiles. A fix exists on every line with the same commit. Line-specific source folders exist for
the rare real difference and stay empty otherwise, and the API identity check keeps the public
surface identical across lines. See [Release lines](./README.md#release-lines).

### 12. A task id is decimal, and version 1's hexadecimal ids are a data migration

VanillaBP 1 could hand out a task key in hexadecimal (`task-id-as-hex-string`, off by default), and an
application which switched it on stored those ids in its own data. They outlive an upgrade, while this
version parses decimally everywhere and has no such setting.

Offering the setting again was rejected. One representation of a task id is simpler than two, the second
one would have to be carried forever because nobody can prove it unused, and an application which holds
hexadecimal ids has a conversion to do either way - the ids are in ITS tables, not in ours. What the
adapter owes such an application is the sentence which names the setting, and that is what the parse site
says now. The failure stays a permanent one, so the outbox entry is still blocked after a single attempt
rather than retried ten times against a key which will not become a number.

### 13. A start asks the cluster for numbers, and asks as many of them on the last day as on the first

The questions this adapter answers while an application boots read from secondary storage, which
grows for as long as the application is in production: how many workflows still run on an old
version, how many jobs of version 1's user-task construction are still open, how many tasks the
cluster is holding open for a process. A start of ten seconds must not become a start of two
minutes because the application did its job for two years, and the platform states the rule for
every adapter as decision 19 of its own DECISIONS.md.

For Camunda 8 that means two things. A question about a quantity fetches one item and reads
`page().totalItems()`; the page which came back is not the answer, and transferring it to count it
would grow with the data while also capping the number at the page size. And a process definition
is searched for once for the whole process: `fetchDeployedVersions` reads every version anyway, so
it keeps the definition keys it saw, and the questions which follow are addressed with them rather
than each searching again.

What does grow is the number of versions the cluster holds, one per deployment which changed a
model, and the questions about older versions grow with it. That is deliberate: those questions are
what the check is for, and `outfaded-versions` is how an operator says which of them have stopped
being interesting. `Camunda8StartupQuestionCostTest` counts what a start asks.

### 14. What this adapter does per operation is a handler, not a pair of methods

VanillaBP's adapter SPI used to ask for two methods per outbound operation, and this adapter
had nineteen of them - the eighteen halves plus the seven-argument correlation overload which
carried the activation id past a default. It answers a map now: one `PhaseOperationHandler` per
`PhaseOperation`, and the request of a phase carries the operation's arguments behind named
accessors, the activation among them. The overload is gone with the pair it belonged to.

What the handlers do is unchanged. The same preflight commands run as pre-commit hooks, the
same job and user-task commands run after it, and the message id which lets the cluster
deduplicate a correlation is derived from the same values as before, activation included. Only
the shape moved.

The map is what states which operations this adapter serves. Everything a Camunda 8 cluster
cannot answer without a round trip - and there is more of that here than on an embedded engine -
stays where it was: in the handler, not in the operation, because the operation is the same one
every adapter serves.

### 15. The adapter sees process definitions in the state ACTIVE and no others

Deleting a process definition does not remove it from Camunda 8. The cluster keeps it, marks it
`DELETED` and keeps answering searches with it, so a search which names no state gets the deleted
versions back along with the live ones. The startup check for old versions then reads their models,
finds the tasks this application no longer serves and reports them, at every start, and the one
remedy the report itself suggests is the one the operator has already applied. A report which
cannot be switched off teaches everyone to ignore reports of its kind, which is worse than not
having it.

Every search this adapter runs for process definitions therefore restricts itself to the state
`ACTIVE`, in one place (`Camunda8ProcessVersions#onlyDefinitionsWhichStillCount`), so the answer to
"which definitions count" cannot drift apart between the callers. There is deliberately no property
turning it off: a deleted definition is not a state anybody wants to hear about.

The filter arrived in `camunda-client-java` 8.8.33, and by decision 11 the client an artifact was
built against is the lowest cluster version it accepts, so every supported line has it. A fallback
path for clusters without the filter would therefore be dead code and is not to be added.

### 16. What the cluster did is read from its codes, not from the words around them

Two answers of the cluster change what this adapter does with an operation: a publication
refused because a message of that id still lives, and a query endpoint refused because this
cluster cannot be searched. Both used to be recognised by looking for a phrase in the
exception's message, and every one of those phrases is the cluster's to reword in any patch
release. A rewording would have turned a harmless duplicate into an outbox entry which is
repeated and then blocked, and it would have turned "this cluster cannot tell" into "this
cluster is down", after which every operation of the adapter fails after a second instead of
proceeding.

Every classification therefore reads a code. A publication the cluster already knows is HTTP
`409` on REST and the status `ALREADY_EXISTS` on gRPC, a job which is gone is `404`
respectively `NOT_FOUND`, and both transports matter because `prefer-rest-over-grpc` decides
per adapter id which one carries a command.

The query API is the case where a code does not suffice: a cluster refuses a search with HTTP
`403` whether it holds no secondary storage or whether the adapter's credentials are not
allowed to read, and it separates the two in prose only. So the question is not asked per
failure at all. The adapter asks it once, while it starts processing a workflow module, with a
search whose only purpose is that answer, and remembers it per adapter id
(`Camunda8QueryApi`). Every later failure of a search is read against the remembered answer:
on a cluster which can be searched it is an outage and the probe reports `BPMS_UNAVAILABLE`,
on a cluster which refuses it is the missing capability and the probe answers optimistically.
Both reasons for a refusal are permanent and cost the adapter the same thing, so the messages
naming this state name both rather than guessing which one it was.

Since decision 20 the remembered answer decides ONE thing instead of five, because the
adapter requires a `true` and the deployment ends the boot on anything else. The reasoning
above is untouched by that, and it is the reason the answer is still remembered: a search
which fails after the deployment is an outage, and nothing but the probe can tell that apart
from a cluster which cannot serve a search at all.

One place keeps reading a wording, and it decides nothing the adapter does: a tenant request
which fails because the cluster has multi-tenancy switched off is answered with the same
`400` as any other rejected argument, so `Camunda8TenantCheck` picks the sharper of two
guiding messages by what the cluster wrote. A rewording costs the sharper sentence there and
nothing else.

### 17. A start waits once for its cluster instead of repeating each round

The commonest reason a start cannot reach its cluster is a cluster booting alongside the
application, and that lets every round the start makes fail, not only the deployment: the tenant
check, the deploy command, the question whether the cluster can be searched, and the version
queries of the startup check. A retry around one of them would have covered a quarter of the
cases, and it would have been a third repetition mechanic next to `Camunda8CommandRetry` and the
outbox.

So nothing is repeated. Before the first round which decides anything the adapter asks the
cluster for its topology, the cheapest question a Camunda 8 cluster answers, and waits while the
answer does not come - once per adapter instance, because what is waited for is the cluster and
not the workflow module. Everything behind the wait keeps ending the start the way it always did:
a cluster which breaks down in the middle of a deployment is not booting, it is failing.

Three things end the wait, whichever comes first. The cluster answers, which costs one request.
`vanillabp.adapters.<id>.startup-wait` is used up, and the start ends naming the address, the time
waited and the cluster's last answer. Or the cluster answers something `Camunda8Errors` classifies
as permanent, and the start ends at once - one classification, the same one everything else in
this adapter reads, and it is what makes a default of ten minutes bearable. The default is long
because the case it exists for takes minutes; it is paid for with a late abort and not with a late
diagnosis, since a line every few seconds carries the cluster's last answer from the first attempt
on.

### 18. The adapter supplies the executor, and a worker asks for work only while a slot is free

This supersedes the reasoning of decision 7, which stays where it is.

The 8.8 client gives ONE `ScheduledExecutorService` both jobs: it schedules the poll of every worker
on it and it runs every handler invocation on it. So `worker-threads` blocked handlers stopped that
adapter from asking the cluster for work at all, and nothing said so. Since 8.9 the client keeps the
two apart by itself, which made it tempting to fix only the line which needs fixing. Decision 11
rules that out - a line differs in what its cluster can do, never in what the adapter offers - and
building it once was cheaper anyway. So the adapter hands the client an executor of its own in both
execution models and on every line: a virtual thread per handler, or a pool as wide as the
configured number, and in each case two platform threads for the timing which no handler can occupy.
`worker-threads` therefore counts handlers running at once, which is what the README and the wiki
promised all along. The price is those two threads per adapter id, idle almost always.

Taking the roles apart also takes away a back pressure nobody designed but which did work on 8.8: an
adapter with every slot busy stopped asking for work. On 8.9 and 8.10 there was never such a thing,
so a queue of activated jobs in front of the slots is the normal state of those lines, and every job
in it spends the lock it was handed out with while waiting. What replaces it is deliberate. A
scheduled task runs when an execution slot is free and is looked at again a moment later when none
is, so a job nobody could run is not fetched, and the cluster keeps it for whoever can.

Nothing has to be told apart to do that, which is what made the rule buildable. On all three lines
exactly two kinds of task reach the executor: the poll of a worker, and the opening and re-opening
of a job stream where `stream-enabled` is on. Both fetch work, and neither keeps a job which was
already activated alive. The lock renewal of a long-running task is sent by this adapter's own
handler, on a thread which is holding a slot already, so it can never wait for one.

The one job kept alive by a delivery rather than by a handler is the one a `@TaskId` method left
open: its lock is renewed when the cluster hands it out again, which is an ordinary activation and
therefore waits for a slot like every other. What that costs is a late renewal on an application
which has no capacity, never the task - the lock lapses at the cluster, the job goes back to being
fetchable, and the delivery record answers the round which finally arrives.

Three limits are named rather than hidden. Asking is not the same as being answered: a Camunda 8
activation request is a long poll which the gateway holds for `request-timeout` and answers as soon
as a job appears, so a request already parked when the last slot filled still brings its batch. What
the rule stops is the asking AGAIN, which is what turns a single batch into a queue. The client also
tops a worker up directly from the thread on which one of its handlers just finished, which passes
no executor and is therefore not gated - and that worker has just given a slot back, which is the
one case where fetching more is the point. And the rule decides how often work is fetched, never how
much one fetch brings.

That last part is `max-jobs-active`, and it bounds one worker's queue and nothing beyond it. The
client counts the jobs it activated and has not finished yet, activates at most `max-jobs-active`
minus that number, and asks for more as soon as the number is down to thirty percent of it: ten of
thirty-two, two of eight. Meanwhile the workers of one adapter id share the execution slots, so
fifteen workers may hold fifteen times `max-jobs-active` jobs in front of four of them. That gap is
what the rule above is for.

### 19. The workers of a declared process id are composed from what the application serves

A workflow module may declare a BPMN process id it deploys nothing under, which is how a renamed
process keeps being served. Under `use-prefix` a task definition is deployed as
`<module>__<process>__<task>`, so the jobs of the workflows under the old id carry a name no
worker of the deployed processes asks for, and nobody notices: an unfetched job is not a failed
one, it is a workflow standing still. Those workflows need one more subscription each, and the
question is where the names come from.

They are COMPOSED from what the application serves. The core names it
(`taskWiringOfProcessesNobodyDeployed`, decision 34 of the platform's own DECISIONS.md), today
the task definition of every `@WorkflowTask` method registered for the declared id, and the
adapter scopes each of them by that id, exactly as the deployment scoped the ones it deployed.
What an entry holds is what to compose from rather than a job type, so this is the one place
which turns it into one. The alternative was to READ them:
the cluster holds the models of every version under the old id, and the catalog already fetches
their XML for the check of old process versions, so the job types could be taken from the models
verbatim, together with the element ids and the multi-instance elements around them.

Reading was rejected because it needs the query API. A cluster without secondary storage cannot
be searched at all, and that is exactly the cluster an application using `use-prefix` may be
running on - prefixes are what a module reaches for when a tenant is not available. A feature
which works on one cluster and silently does nothing on another is worse than one whose limits
are written down, so the composed form is the one which ships and the limits are named at
startup.

What composing costs shows in three places, and every one of them is a price paid on purpose:

- a task definition may belong to a service task or to a Camunda-managed user task, and nothing
  outside the model says which. Both subscriptions are opened, and the one whose kind the task
  never was stays idle. An idle worker costs one activation request;
- such a worker asks for every variable instead of a derived list, since deriving one needs the
  elements of the model and the multi-instance elements enclosing them. It fetches more than
  necessary, which is never wrong, only more expensive;
- a `@WorkflowTask` method wired to a BPMN element id (`@WorkflowTask(id = ...)`) names no task
  definition, so no job type can be composed for it. Those workflows are the one case which
  stands still after all, and the start says so, naming both ways out: wire the method by task
  definition, or keep deploying the old model under its old id until its workflows have ended.

For the same reason the multi-instance context of such a task is not reported: the registry is
filled from the models the module deploys, and this one is not among them. A rename whose old
model has a multi-instance task therefore stays a case for keeping both models deployed.

**That paragraph is superseded by decision 21.** The registry now also gets the multi-instance
chains of the models the cluster runs, so a job of such a workflow carries its iteration context,
and a multi-instance task is no longer a reason to keep the old model deployed.

Since decision 20 the reason the alternative was rejected has fallen away: the adapter requires
a cluster it can search, so reading the models is no longer a feature which would work on one
cluster and do nothing on another. What this entry decided stands as it is - the composed form is
what ships, and everything above about what composing costs is still what it costs. Reading the
models is an open alternative rather than a rejected one, and picking it up is its own piece of
work.

`Camunda8DeclaredProcessWorkersTest` holds which workers are opened per mode and what the start
says; `Camunda8RenamedProcessIT` proves the whole thing against a cluster with prefixed
identifiers.

### 20. A cluster which cannot be searched is not a cluster this adapter serves

Finding a workflow by its aggregate's id is a search, and so is everything the viewer asks and
everything the version catalog asks. A cluster started without secondary storage refuses all of
them, and so does a cluster whose credentials are not allowed to read what the adapter asks for.
The adapter used to have an answer per question for that state: the election probe answered
optimistically and warned once, the push of a changed aggregate failed with a guiding message, the
version list came back empty, the viewer served what this application version deployed and reported
no element history, and two adapter ids on one such cluster ended the boot. Five behaviours, five
messages, and two kinds of cluster in the integration tests to keep all of it measured.

None of it buys a capability. It buys the ability to boot against a cluster which cannot answer what
VanillaBP asks, and it costs a second behaviour behind every question the adapter answers, each of
which has to be documented and tested. One feature fewer that nobody has asked for is a better trade
than a set of internal gaps, and the version-1 applications we know all run a searchable cluster.
From 1.7.0 on they had no choice: version 1 read every active process definition of its tenant
through the search API while it started, with no way to switch it off and no fallback, so a cluster
which refused that search kept the application from coming up. What 2.0 does is name the requirement
instead of leaving it to the first thing which fails.

The requirement is therefore checked once per workflow module, while that module deploys, after the
start has waited for its cluster and next to the tenant check. Waiting first is what makes the check
honest: a cluster which cannot be REACHED is still not declared incapable, the answer stays open, and
the next question asks again. Throwing from the deployment is what makes it fair: an adapter which is
not the first-priority adapter of the module and carries `deployment-failure: warn` boots degraded
with a guiding warning, which is what the old BPMS of a migration off such a cluster needs.

That warning is the first half of the way out and not the whole of it. An adapter which deployed
nothing cannot answer the election either, and `canLocateWorkflows` says so, which is why it still
reads the probe rather than answering `true` from a constant. A workflow module serving two adapters
therefore also has to accept the routing by list order
(`vanillabp.workflow-modules.<id>.election.guessing-adapters: ACCEPTED`), which the core asks for in a
message of its own. Two messages, each guiding to the next step, is what a migration off such a
cluster costs - and it is the whole cost, because nothing after them is silent.

One place decides the capability and one place reads it, so letting such a cluster back in later is
one decision rather than fifteen. `Camunda8QueryApi` keeps the remembered answer, because telling an
outage apart from a cluster which cannot serve a search is still what every message about a failed
search depends on, and it keeps naming both reasons for a refusal: the cluster answers `403` for
either and separates them in prose only.

`Camunda8SearchableClusterCheckTest` holds the message and what it names,
`Camunda8UnsearchableClusterIT` the boot which ends against a real cluster refusing a real search,
and the same class the warning an adapter allowed to degrade gets instead.

### 21. One picture of the models the cluster holds, and every check asks it

A check against a BPMN model must not care whether the model comes from the current deployment
or was already in the cluster - the platform's decision 38 carries the rule, this entry carries
what it costs on Camunda 8. The case which forced it: `correlateMessage` validated the message
name against the models THIS application version deployed, and after a rename the name a
waiting workflow needs is declared only by the old id's model in the cluster. The check threw
inside the caller's transaction while the workflow sat right there, waiting for exactly that
name.

The adapter keeps one picture per adapter id (`Camunda8ModelsTheClusterHolds`) instead of
giving every check a query of its own: the models the cluster holds for every BPMN process id
the application declares. The models of the current deployment cost nothing, everything else
is read through `Camunda8ProcessVersions` on first use and kept, because a definition's model
never changes. The answer carries "cannot tell" as a value - a check reading it does not have
to invent that case, and it stays silent on it. "Cannot tell" itself is never kept: an
unreachable cluster says nothing about the next call, while the settled "cannot be searched"
of decision 20 answers without a request.

A refusal is allowed to rest on the picture only after reading the cluster again in the same
call. The kept picture may be outdated by another node's deployment (a rolling upgrade), and a
message declared moments ago must not be refused over yesterday's read - so the error path
pays one search, and the everyday path pays none.

The same picture hands the workers of a declared-only id (decision 19) the multi-instance
chains of the models the cluster runs, so a job of those workflows carries its iteration
context. And the mirror image of the rule ended a refusal of its own: a user task without an
external form reference used to cost the old-version startup check its whole answer when it
sat in a model an earlier application deployed - such a model is only being read, so it is
read by version 1's formKey convention where the reference is missing, and a task following
no convention is skipped rather than fought over.

`Camunda8MessageDeclarationTest` holds the check's answers, `Camunda8RenamedProcessIT`
correlates a message only the old id's model declares against a real cluster and proves the
refusal names what the cluster holds.

### 22. A test asks for a cluster, and the release line decides what that cluster costs

Every integration test of this repository needs a cluster which answers searches, because
decision 20 leaves it no other kind. Until Camunda 8.9 that meant a second container: the
cluster exported to an Elasticsearch beside it, so each of the twenty-odd test classes started
a pair, and the Elasticsearch alone asked for a gigabyte of heap it then filled with the four
documents one test wrote. From 8.9 on the cluster can keep its secondary storage in a database
instead, and the image ships an H2 driver, so an embedded in-memory database inside the
cluster's own process answers the same searches with no container beside it and nothing to
clean up afterwards.

Which of the two a line uses is a property of the LINE and not of a test:
`camunda8.cluster.secondary-storage` sits next to `camunda8.cluster.image` in the parent POM and
travels the same way, filtered into the `camunda8-cluster.properties` of the `test-support`
module, which every module starting a cluster reads from the classpath. The `line-8.8` profile
sets it to `elasticsearch`, because moving the previous-GA line onto a storage its own cluster
serves differently would change what that line is tested against, which is the one thing a
bugfix-only line may not do. Every other line takes the default, `rdbms`, and so will a preview
line when there is one again.

A test class sees none of this. It asks `ClusterUnderTest` for a cluster and gets a container it
can ask for its mapped ports. In most classes that is `sharedCluster()`, the one cluster all
classes of a module share: they run in one JVM, so one container serves them all, and a module
starts one cluster instead of some thirty. A class which needs a cluster nobody else touches,
because it is configured differently or must never have seen its model, declares a field
`ClusterUnderTest.cluster()` (or `cluster(logName)` where a module starts more than one) instead.
Either way, where the line needs an
Elasticsearch, the cluster container creates the network and that Elasticsearch itself, depends
on it so Testcontainers starts it first, and stops both again in its own `stop()`. That last
part is the reason the storage is not simply a second `@Container` field the way it used to be:
Testcontainers starts what a container depends on but stops only what a class declared, and a
module runs all its classes in one JVM, so an Elasticsearch nobody declared would outlive its
cluster and the module would hold twenty of them by the end of a run.

`Camunda8TaskProcessingIT` is the everyday proof of the database storage and
`Camunda8LocatingWorkflowsIT` of the searches on top of it. Both run the other half when they are
given `-Dcamunda8.cluster.secondary-storage=elasticsearch`, which is the quick way to check a
change to this mechanism on a machine. The pull request builds every line since decision 42, so
line 8.8 runs the Elasticsearch path there as well.

### 23. Connectors are allowed per adapter, and every boot says what they cost

A model may carry elements this application does not serve, and `zeebe:modelerTemplate` is what
marks them. The rule is not unconditional, because that attribute marks an element built from an
ELEMENT TEMPLATE and a connector is only the most common of those: a company writes templates for
its own plain job-worker tasks as well, and an unconditional rule would leave such a task without a
worker and without a validation, so its workflow would stand at it forever. `allow-connectors` is
therefore the gate, and the model still says WHICH elements: the property answers whether the
marker is read at all.

The key belongs to this ADAPTER and to no wider scope. Connectors are a Camunda 8 concept, no other
BPMS has anything to do with the marker, and a platform-wide key would ask every adapter about
something only this one can answer. It resolves at adapter, workflow-module and workflow level, the
most specific configured value winning in both directions, and it has no task level: that level is
keyed by the task definition, and a connector's task definition is the connector's own type, shared
by every element using that connector.

Such an element's job type is left as the modeller wrote it, because it was never an identifier
this workflow module owns. It names a runtime somebody else deployed cluster-wide, and prefixing it
would rename something this application does not own. The alternative would have been to refuse
`use-prefix` wherever a connector sits in a model, which takes the only isolation mode working
without a multi-tenant cluster away from every application that wants one connector. The price is
real and stated rather than hidden: such a job type reaches the cluster unscoped, which costs
nothing here because a connector runtime subscribes to it globally anyway.

No key silences the warning a boot with the switch on writes. `accept-unscoped-identifiers` is the
precedent for acknowledging a warning away, and it exists because the application can state a fact
the adapter cannot check, namely that its identifiers are unique. There is no equivalent fact here:
what the warning says stays true for as long as the connector is in the model, and a key turning it
off would only make the loss invisible.

See [Elements another runtime serves](./README.md#elements-another-runtime-serves).

### 24. An ad-hoc subprocess nothing serves is named, and the boot goes on

**Superseded for a BPMN process the application claims by decision 54.** What is written below
holds for a process no `@WorkflowService` class of the application claims. For one which is claimed,
the deployment refuses the file instead of warning about the element.

Camunda 8 knows two flavours of the element. The one where the model names the activities to run is
served by this adapter without anything having been written for it: the activities inside are
ordinary tasks and the list of ids is an attribute of the workflow aggregate. The one where the
element carries a `zeebe:taskDefinition` of its own is not, and cannot be. Completing such a job
means answering with the elements to activate, and a `@WorkflowTask` method has no way to say that.
Giving it one is a story about the outcome of a workflow task, not about this element.

What was decided is what happens to a model which uses that flavour anyway. It deploys, the workflow
reaches the element and stops there, and the job the cluster activated ends in an incident once its
retries are used up. Nothing later in the boot detects it, because the element is none the wiring
collects: it produces no task spec, so no validation misses a method. So the deployment says it
itself, once per BPMN process, naming the element, what it costs and the two ways out.

It is a warning rather than a refused deployment, for the same reason a BPMN process nobody serves
is one. The other processes of that workflow module are fine, and a file travels to the cluster as a
whole, so ending the boot would take an application down over one element of one model. A developer
has to see the defect, and the message is where they see it.

The element template is what tells the two cases apart. An ad-hoc subprocess carrying
`zeebe:modelerTemplate` is left out of the report, because then somebody else's runtime owns it: the
Camunda AI agent is an element template on exactly this element and a connector runtime fetches its
job. That is the marker of decision 23 and it is read through the same class rather than looked for
a second time.

Separately from all this the element is reported as a source of concurrent tokens, whichever flavour
it uses and however short the list of activities looks. `activeElementsCollection` is an expression
evaluated when the workflow enters the element, so the model cannot promise the list stays one entry
long, and a warning appearing only after a data change is worse than one appearing always.

See [Ad-hoc subprocesses](./README.md#ad-hoc-subprocesses).

### 25. The cluster is asked which of a module's names it already holds, and the answer is a hint

`validateNoCollidingProcessIds` compares the identifiers of one deployment against each other, so
a name another application deployed into the same cluster years ago is invisible to it. Both sides
deploy, and the cluster alone decides which of the two a start or a message reaches. The platform's
decision 40 carries the rule for every adapter; this entry carries what it costs and what it is
worth on Camunda 8.

What the cluster can be asked about is a BPMN process id and a DMN decision id, because it keeps a
searchable record of both. The process ids of a whole workflow module go into one paged
`newProcessDefinitionSearchRequest`, which the `in` form of the id filter allows; a decision id
needs one `newDecisionDefinitionSearchRequest` each, because that filter takes an exact string and
offers neither a list nor a pattern. Both run once per workflow module while it deploys, after the
deploy command answered, and both carry the tenant where the mode uses one. Nothing else has an
index to ask: a message name, a signal name, an error code, an escalation code and a job type live
inside a model, and reading one model per definition version the cluster holds is the growth
decision 13 forbids a start to have. Where the models are read anyway the same names are answered
for free, which is what `identifiersOfVersion` does for the versions of this application's own
processes.

The second report of the feature, the names the models of this deployment declare, carries a decision
id as well. It is read off the DMN file while that file is read, before the prefix is written into it,
which is the same moment a message name or a job type is read out of a BPMN model. So two workflow
modules of one application which bring a decision of the same id are named, the way two modules
sharing a message name are, and a workflow module moved between the two Camunda adapters keeps the
check. The deploy command answers with the decision ids too, and taking them from there was rejected:
the cluster reports the id IT knows, so the plain one would have to be won back by stripping a prefix,
while the file the command sends is the file this adapter just read.

The discriminator is a heuristic and the check says so rather than hiding it. A cluster records no
owner, so what a definition carries is the resource it was deployed from, and for a decision the
decision requirements of its DMN file. A definition under a marker this deployment brought is this
application's own, earlier versions included, and stays silent; everything else is reported with
`certainlyForeign` at `false`, which the core turns into a sentence saying the line may be
harmless. Two things make the marker a guess: another application may deploy a file of the same
name, and renaming a file of our own makes our own earlier definition look like somebody else's.

Reporting an unproven finding is still better than silence. The mode which needs this check most is
`none`, which is what a cluster without multi-tenancy leaves an application with, and staying
silent because nothing is provable would leave exactly that case unguarded. A cluster which cannot
be searched is no cluster this adapter serves (decision 20), so there is no third answer to write.

A finding warns and never ends a boot, which is the platform's decision 38 applied: whoever holds
the name may be an application which is running correctly, and ending this boot would not help it.
A diagnostic may never end a boot either way. Every search is wrapped twice, a failure is logged at
debug and the questions which can still be put are still put. There is no property switching any of
it on or off: it is one search per workflow module plus one per decision, and a check nobody turns
on is a check nobody runs.

Reading the model of a held version for the same question goes the way `tasksOfVersion` goes, by the
definition key and the XML request, and not through the picture of decision 21. That picture answers
the checks which JUDGE a model for the ids the application declares and keeps what it read for as
long as the application runs; what the old-versions check needs is one model at a time, so the model
of the version being asked about is held for that version's turn and the next version replaces it.
One model per adapter id at most, and the second question about a version therefore costs no second
fetch.

`Camunda8IdentifiersTheClusterHoldsTest` holds the filters and what a finding says,
`Camunda8StartupQuestionCostTest` the number of searches,
`Camunda8IdentifiersTheModelsDeclareTest` which kinds the declared report carries, and
`Camunda8IdentifiersTheClusterHoldsIT` the foreign definition on a real cluster.

See [A name the cluster already holds](./README.md#a-name-the-cluster-already-holds).

### 26. Two workflow modules are separated by the tenant they would really be deployed to

The core refuses two BPMN processes of one application which reach the cluster under the same
identifier, and it cannot judge that alone. Under `by-adapter`, the default, nothing is prefixed, so
the core holds two equal strings while the cluster keeps the two workflow modules perfectly well
apart. What keeps them apart is the tenant, and a tenant is Camunda 8 knowledge. So the core asks
this adapter, through `ownIsolationSeparatesWorkflowModules`, and the platform's decision 41 carries
why the question exists and why an unanswered one refuses.

The answer is the tenant each of the two modules would REALLY be deployed to, read through the same
function the deploy command goes through. Reading `tenant-id` instead would be wrong both ways
round: unset, that property means a tenant named after the workflow module, so two modules are in
two tenants; set, it is used only where the mode of the module asks for a tenant at all, and the
mode is resolvable per workflow module. An answer composed any other way could call two modules
separated which the very next deploy command puts into one tenant.

No tenant is a scope of its own. A module under `use-prefix` or `none` reaches the cluster in the
`<default>` tenant, so two such modules are in the SAME scope and nothing separates them, while one
of them against a tenanted module is separated. On a cluster without multi-tenancy that is the only
scope there is: such a cluster rejects a tenant id, which is why `by-adapter` ends the boot there,
and every module left in `use-prefix` or `none` shares the one unnamed scope. The answer there is
"nothing separates them", which is the truth about that cluster rather than a degraded guess.

What it costs is nothing. Both tenants come out of configuration which cannot change while an
application boots, so no request goes to the cluster and the adapter keeps no answer of its own; the
core asks once per pair of workflow modules. Decision 13 bounds what a start may ask, and this asks
the cluster nothing at all.

One configuration boots today and will not after this: an adapter-wide `tenant-id` with two workflow
modules bringing the same BPMN process id. Both modules were deployed into one tenant, the second
definition replaced the first under that identifier, and one of the two modules ran on a model
nobody deployed. It is now refused while the second module deploys. The report of decision 25 warns
about the same configuration where two modules share a message name or a job type. A shared process
id is the heavier case, because the cluster loses a model instead of mixing two names up, which is
why this one refuses.

`Camunda8IsolationSeparatesModulesTest` holds the pairs of tenants,
`Camunda8CollidingProcessIdsBootTest` the refusal and both deployment orders against the real core,
and `Camunda8CollidingProcessIdsTest` that the core reaches this adapter on Quarkus as well.

See [Keeping workflow modules apart](./README.md#keeping-workflow-modules-apart).

### 27. A listener somebody modelled is a task, and only where the application asked for it

Version 1 let a listener be served by a `@WorkflowTask` method and announced it nowhere, because a
model with application logic in a listener cannot be moved to another BPMS. Version 2 deleted the
reading side, which made such a listener silently unserved: the cluster creates a job for it, no
worker subscribes to its job type, and the workflow stops right there with no incident and no message.
That silence is the reason the listener is served again, behind a key which is off by default, and the
reason a boot which uses it says what it costs.

A served listener is a `zeebe:taskListener` of a `zeebe:userTask` or a `zeebe:executionListener` of
any element **whose job type a `@WorkflowTask` method of this application names**. The job type IS the
task definition such a method names, so nothing about the model has to be rewritten for a method to
find its listener; and a job type is a name in the cluster which anybody may subscribe to, so a model
carrying one says nothing about who serves it while a method naming it does. Only the task-definition
route counts: `@WorkflowTask(id = ...)` names the ELEMENT, and one element may carry a task and a
listener at once.

A listener no method names is therefore not refused, and it is not passed over in silence either. The
boot names it and goes on, the way it does for an ad-hoc subprocess waiting for a job worker: a worker
the application runs itself may be the answer and only the application knows, but the cluster creates
the job either way and a workflow reaching the element stands there with no incident and nothing in any
log.

A job type starting with `io.vanillabp.` is nobody's business here: that prefix carries the listeners
VanillaBP writes itself and the ones its extensions write, and those are served whatever the key says.
The prefix is not the whole separation either, because the listeners the framework writes are not in
the model yet while the file is read, which is why a modelled listener is collected while the file is
prepared rather than while a process of it is wired.

`vanillabp.adapters.<id>.allow-listeners` is the key, a boolean, `false` unless somebody writes it. It
resolves at adapter, workflow-module and workflow level, the most specific configured value winning in
both directions, so a module which serves its listeners can have one workflow which does not. There is
no task level: that level is keyed by a task definition, and whether a listener becomes a task at all
is what this key decides, so at the moment the key is read there is no task definition to key a level
by. A value written there anyway earns one guiding warning naming the three levels which work, and the
boot goes on. The Camunda 7 adapter reads the very same key at the very same three levels, because two
keys about what a model may contain, reaching different levels on different adapters, would be the
worse answer.

Where the key is off and a model carries a listener, the boot ends here in the adapter, and that is
not how decision 23 handles a connector. A connector asks VanillaBP to LEAVE an element alone, so the
core's wiring validation finds a task nothing serves and ends the boot by itself. A listener asks
VanillaBP to SERVE something, so without the key there is no task spec, nothing for the validation to
miss, and the workflow would stop at the listener's job on the cluster. The message therefore comes
from the adapter, and it names the elements, the three levels and the cost.

Where the key is on, a listener is a task like any other one from there on. `validateTaskWiring` asks
for a `@WorkflowTask` method and ends the boot where none exists,
`validateNoUnwiredWorkflowTaskMethods` reports a method which matches no listener of any wired
process, and under `use-prefix` the listener's job type is prefixed like every other task definition
of the workflow module, because that is what it is. Version 1 wired its listeners privately and had
neither direction.

The event is part of a listener's identity, because one method serves one event of one element.
`@TaskEvent` tells such a method nothing: `TaskEvent.Event` has `CREATED`, `CANCELED` and `ALL`, and a
listener's own event is none of those. What the parameter receives is therefore `CREATED` for every
listener, which is the only value that works at all, since a method without the parameter subscribes
to `CREATED` alone. Entry 32 replaced this paragraph for one release and entry 33 put it back. Two
listeners of one element under ONE job type end the boot naming both: one
method would serve two events and nothing it could ask would say which one it is in. Two listeners of
one element under different job types are fine, and a method then has to name the job type, because
`@WorkflowTask(id = ...)` names the element and cannot tell them apart. A method declaring `@TaskId`
ends the boot as well, since the cluster completes a listener job the moment the method returns and
the task can never stay open, and a method throwing `TaskException` is answered with the reason rather
than with an incident: the cluster is inside a transition of its own and has no token to route.

What a method may write into the process instance depends on the listener, in the three cases
decision 1 lists. An execution listener on `end` completes like a task, so such a method may change
the workflow aggregate and the process sees it. An execution listener on `start` and a task listener
complete with nothing, so a change of theirs is kept by the application and reaches the cluster at
the next real sync point of that workflow. No signature shows whether a method changes the aggregate,
so nothing here can detect which of the three a given method is in, which is why the report says it
for every served listener.

The default is off because of what serving a listener costs. A listener is where a BPMS lets an
application in at a moment the BPMS owns, and every BPMS draws that moment differently, so the model
stops being portable: another BPMS has no listener at this element and a migration of the model stops
at the method serving it. The Process-Engine-API has no listener concept at all, which is gap 16 and
gap 17 of that adapter's `GAPS.md`. So every boot of a workflow module whose listeners are served
writes one framed WARN naming each served listener, the key which switched it on, what it costs and
the way back, and no key silences it: what it says stays true for as long as the listener is in the
model, and a key turning it off would only hide what serving one means.

`Camunda8ListenersTest` holds what is read out of a model and what a pair of listeners amounts to,
`Camunda8ListenersReportTest` the report of a boot together with the refusals, and
`Camunda8ModelledListenerHandlerTest` the three cases of what a completion carries.

See [Listeners somebody modelled](./README.md#listeners-somebody-modelled).

### 28. What an extension of this adapter may use is this adapter's own API

An extension implementing `ExtensionWiringService` runs inside this adapter's deployment
pipeline, on the same BPMN files, against the same cluster, under the same shutdown. It is not an
application: it opens job workers, it serves listener jobs, it searches for workflows by their
aggregate id. Everything it does there, this adapter already does, and until now most of it was
package-private, so the extension wrote a second copy of a rule which was decided once.

A copy of a rule is not half a rule. It is a rule which will be wrong in one of the two places
and say nothing about it. The quoting of a search value answers nothing once it drifts, and
nothing reads exactly like a workflow which was never started. A worker assembled by hand is
simply missing from what an operator reads. Worst of them, a listener job answered without the
drain buys an incident on every rolling restart which catches one in flight. None of it fails a
build.

So the entry points an extension needs are public, they say in their javadoc what they promise and
what they do not, and a test holds each promise. They are public WHERE THEY ARE rather than moved
into a package of their own: the javadoc is the contract, and moving them would rename what the
adapter itself uses. The exceptions are `Camunda8Workers` and `Camunda8ListenerJobs`, which
took code out of the deployment service, and two modules of test code, `test-support` and
`published-pom`. Both modules exist because a test classpath cannot read another module's test
classes. They are two and not one because `test-support` starts containers and `published-pom`
only reads a file. No module wants both, and a module which only checks its published POM should
not get Testcontainers on its test classpath.

This is not the deployment service becoming public API. What an extension may use is the named
list in [What an extension of the pipeline is told](./core/README.md#what-an-extension-of-the-pipeline-is-told);
everything else stays the adapter's own and may move with the next change. Where an extension
needs something which is not on that list, the answer is to add it to the list, with its javadoc
and its test, rather than to reach around it.

The platform's own rule sits above this one: a mechanism which is the same for every BPMS belongs
to `adapter-platform-integration` and not here. This decision is about what is Camunda 8's and
therefore cannot live there.

See [What an extension of the pipeline is told](./core/README.md#what-an-extension-of-the-pipeline-is-told).

### 29. The tenant is named per workflow module, and only there

Decision 26 says the adapter answers whether its tenants keep two workflow modules apart. What
that decision did not settle is where the name comes from, and until now it came from one place:
`vanillabp.adapters.<id>.tenant-id`, one name for every workflow module of the application.

That is now the fallback, and a workflow module may carry a name of its own
(`vanillabp.workflow-modules.<module>.adapters.<id>.tenant-id`). The reason which decides it is
the message the core writes when it refuses two modules sharing a BPMN process id: it tells the
developer to give one of the two a scope of its own and names that key. A fix a message
recommends has to work. Camunda 7 already read the name at that level, so the same sentence
advised well on one BPMS and pointed at nothing on the other, and a workflow module moved from
Camunda 7 to Camunda 8 lost the way out without anybody noticing.

Two more reasons come with it. Without the level the adapter answers a question it has no way of
being right about: one name for every module means every pair is "separated by nothing", whatever
the application intended. And the level is where the deployment is - VanillaBP resolves an
adapter's properties over the levels anyway, and one tenant shared by every module is the special
case rather than the rule.

There is no name per workflow. The mode has one, a tenant cannot: a tenant id is an attribute of
the deployment and this adapter deploys once per workflow module, so two workflows of one module
cannot reach the cluster in two tenants. A key which looks honored and is ignored is worse than a
key nobody may write. For the same reason the check which refuses a name the mode would ignore
now runs once per property key instead of once per adapter: the developer has to be sent to the
line they wrote.

The name is not read anywhere else. `Camunda8InstanceIdentity` asks whether two adapter ids are
the same system, and that question is about an adapter id as a whole, so it keeps comparing the
adapter's own section; what one workflow module is called says nothing about the others. A cluster
without multi-tenancy is unchanged as well: the mode decides whether a tenant reaches the cluster
at all, a module name is dropped exactly like an adapter name where it does not, and the tenant
check still runs against the name which would really be used.

`Camunda8IsolationSeparatesModulesTest` holds the answer per configuration, the name set for one
module included, `Camunda8CollidingProcessIdsBootTest` the boot which ends on two modules under
one process id and the module tenant which lets both through, and
`Camunda8TenantResolutionBootTest` with its Quarkus twin in `Camunda8JobTimeoutOverlayTest` that
both platforms read the levels and report the key the name stands in.

See [Keeping workflow modules apart](./README.md#keeping-workflow-modules-apart).

### 30. A called process is told which iterations it runs in, and the caller's model is where that is read

A call activity used for decomposition is an embedded subprocess which happens to live in another
file. A task in it therefore expects the same answer about its iteration as a task in the calling
process, and until now it got nothing.

The cluster does its half already. The variables of every scope a call activity sits in are copied
into the called instance, the input mappings of decision 5 included, and they survive a second call
activity below that. That is measured against `camunda/camunda:8.9.19`, so the adapter writes no
mapping at a call activity and invents no variable. The only thing it writes there is
`propagateAllParentVariables="true"`, and only where the model says nothing, which is where it
means the same thing today. A model saying `false` switched the caller's context off on purpose. It
is left alone, and that call activity stays out of the graph as well: the values never reach the
called instance, so a chain naming them would promise something the cluster does not deliver, and
the worker would ask for names which cannot be there.

What was missing is the link between the two models. A BPMN process does not know who calls it, so
`chainOf` walked one model upwards and stopped at the process element. The call activities of the
CALLERS carry the link, and the adapter reads them once every file of a workflow module is wired.
For an element of a called process the chain is then the chain of the call site followed by the
element's own, outermost first. The fetch list follows without a change of its own, because it is
built from the same chain: before this, a worker of a called process did not even ask the cluster
for values the cluster was holding.

One call activity stays out of the graph. One calling a process with a workflow aggregate of its
own is not decomposition, and the core answers that with `workflowsShareTheWorkflowAggregate`.
Such a process reports no iteration of its caller although the cluster still copies the values
into its instance, and that is the only place the line can honestly be drawn.

A call activity naming its process by an EXPRESSION is outside the graph as well, and it is told
its iteration all the same. Which process it reaches is decided per instance, so there are not two
models to link - but the levels are the CALLER's model knowledge, complete while the module is
deployed, and the caller hands them down. Such a call activity gets one more input mapping,
appending one entry to the process variable `vanillabpMiParents`: the calling process as the
cluster knows it, and its levels outermost first, each with the BPMN element id, the index, the
total and the item. An enclosing level reads the variables of its own mappings from decision 5, and
the call activity's own round reads `loopCounter`, `count(...)` over its input collection and its
input element, because one input mapping of an element must not depend on another one of the same
element.

An input mapping rather than a start listener. The cluster evaluates it in the same record which
creates the called instance, so there is no window in which the variable is missing, and it
evaluates it per multi-instance instance, so each called instance gets its own round. A listener
would cost a job per instance for bookkeeping, a start listener at the process does not see the
start variables, and an element listener on a call activity is not taken by any current line. A
second such call activity further down appends its own entry, which is how a chain several
processes long comes about.

The values travel IN the entry rather than being named by it. A worker has one fetch list, fixed at
registration, so it cannot read the variable, look inside and then ask for the names it finds.
Either it fetches everything, which makes the payload unbounded, or the values are in the entry.
The keys are the readable ones (`process`, `levels`, `element`, `index`, `total`, `item`): short
keys would save about two percent of an entry and cost the one thing the variable is looked at for,
which is somebody in Operate asking why an index is missing.

Two call activities get nothing of this. A statically named one is linked model to model and keeps
its payload at not one byte more. One saying `propagateAllParentVariables="false"` is left alone,
because the modeller switched the caller's context off on purpose; a mapping travels even then, so
writing one would undo that rule instead of following it.

The workflow aggregate is the one question left for the runtime, and only its lookup is. While the
module is deployed every process of it is held against the caller, and the ones sharing the
caller's aggregate are recorded as processes which may use that chain. At runtime the reader checks
whether the pair in front of it is one of them, so a call reaching a process with an aggregate of
its own is dropped, and so is one which crossed the boundary of a workflow module, where this
adapter never saw the caller's model. Dropped, not guessed at, and a DEBUG line says which caller
it was.

The reading side keeps one way. `chainOf` answers what the deployment knows, the handed-down levels
go in front of it, and `valuesOf` stays the only place turning a level into a `MultiInstanceValue`.
A level whose variable the called process writes itself is dropped, because both write the same
variable names and the inner scope overwrites the outer one - the same rule the graph follows. Once
the two sources are together, nothing tells them apart.

`vanillabpMiParents` is not a protected name, and the reading side is where that is survived. An
input mapping of it is refused like every name VanillaBP writes, but a start variable, an output
mapping or the write-back of the workflow aggregate can all set it and nothing in the cluster
objects. Measured on 2026-10-01 against `camunda/camunda:8.8.40`, `8.9.21` and `8.10.0-rc3`: a FEEL
expression which reaches into nothing becomes `null`, the cluster writes that `null` without an
incident, `append` on a text results in `null` as well, and a foreign list is appended to. So a
value which is not a list is read as if the variable were not there, an entry which describes no
level is left out while the real ones still count, and nothing of it reaches a `@WorkflowTask` as an
exception. A DEBUG line names the variable and the process; a WARN would repeat itself for every job
of an application which uses the name on purpose, and it could change nothing about a job which
already ran.

The chain counts against the cluster's `MAX_MESSAGE_SIZE` and the adapter cannot catch that limit:
it arrives as an incident on the call activity rather than as a refused command, so there is nothing
to classify and nothing to refuse. It is documented instead. Three levels with 1 KB element values
cost about 3.3 KB while the limit bites between 1000 and 2000 such entries, which is three orders of
magnitude of room. What gets expensive is the element VALUE and not the depth.

`Camunda8MultiInstanceTest` holds the expression written into the model, the three call activities
which get nothing, the inherited level of a caller travelling on, and the reading side against a
foreign value. `Camunda8FetchVariablesTest` holds that every worker serving an element carries the
one name and that the worker of a whole process does not.
`Camunda8MultiInstanceIT#theIterationCrossesACallActivityNamedByAnExpression` is what a handler
really sees, and `#theApplicationMayWriteTheChainVariableItself` what it sees once the application
took the chain away from itself. `Camunda8CallActivityVariablesCanaryIT` holds the cluster to the
three properties all of this rests on.

Four rules keep the chain answerable where a graph is not a straight line.

A process called from several places gets the union over those call sites. Each path keeps its own
order, and at runtime the levels of the path which did not run are simply not in the job, which
`valuesOf` already leaves out. So a chain may name more levels than the instance at hand ran in.
A level two paths share appears once, in the place the first of those paths gave it; where two
paths nest the same two ids the other way round, one of the two orders is the one reported, and the
call sites are walked in a fixed order so the answer survives a restart. The price is the fetch
list, which asks for the variables of every call site on every activation.

Where two levels along ONE path write the same variable, the inner occurrence wins. Both write the
same variable names and the inner scope overwrites the outer one, so the job carries the inner
values and the chain says so. A process calling itself is where this happens by design. Two
different element ids can be such a pair, where they differ only in characters a variable name
cannot hold, and a DEBUG line names both.

Where two call sites carry multi-instance elements which write the same variable and do not mean
the same thing, the boot ends with a message naming both processes. Two different ids are such a
case as well: `my-task` in one caller and `my.task` in another both write
`vanillabpMiIndex_my_task`, and a job of an instance reached from one of them would answer a
handler asking for the other with a round which never ran. Nobody can say what
`@MultiInstanceElement` of that id means there, and answering it wrongly would hand a handler the
values of another iteration.

A call graph with a cycle stops at the first process already on the path and reports the levels
collected so far. Without that rule a process calling itself has a chain which grows with every
round.

The refusals happen while the application starts, not while it is built. The adapter has no
build-time pass over models on either platform, and on Quarkus the BPMN parser is initialized at
run time on purpose, so a build-time check would mean a second parse of every file for one
question. Startup is also where every other model refusal of this adapter happens, and it is still
before anything reaches the cluster.

An input mapping of a name VanillaBP writes, reading a different expression, ends the boot as well
rather than being ignored. It used to be left alone, which looked like the rule that nothing the
application modelled is overwritten. It is not the same case: the handler would read the values of
whatever the modelled expression points at while the chain promises the iteration.

`Camunda8MultiInstanceTest` holds the chain across the boundary, the union, the shared level, the
recursion stop, the call activity which keeps the caller's variables out and both refusals.
`Camunda8FetchVariablesTest` holds that the fetch list follows the chain and that a process with an
aggregate of its own gets nothing. What a handler really sees on a cluster, two call activities
deep, is `Camunda8MultiInstanceIT#theIterationCrossesTheCallActivity`.

See [Multi-instance](./README.md#multi-instance).

### 31. A release waits for every current line, a pull request does not

The first rule below is superseded by decision 42: a pull request runs the matrix too, so it waits
for every line as well. The two rules this entry was written for stand, the release gate and the
issue for a red night, and the workflows cite them here.

A pull request builds the current GA line and tests it against that line's cluster. Every other line
waits for the nightly matrix, and only a pull request which moves a client pin runs the matrix
itself, because a build of line 8.9 never compiles the pin of line 8.8. See
[What CI runs](./README.md#what-ci-runs).

The rule was set in September 2026, after a night went red in the Business Cockpit's Camunda 8
adapter and nobody saw it for a day. The waiting is safe because nothing between two releases is
released. Every artifact `main` produces is a snapshot, so a line which breaks in the night has
broken nothing anybody depends on, and the next morning is early enough to hear about it. Running
every line on every pull request would buy hours of cluster tests to learn the same thing earlier
than anybody needs it. Two rules pay for that, and they are what this entry is for.

The first rule is the release. This adapter publishes one artifact per line, so a release runs only
while every current line is green in the full matrix, its tests and its cluster included. The gate
is that matrix itself, called from the release workflow before anything is built for publication,
and not a look at what the matrix said last night. This repository compiles against snapshots of
`spi-for-java` and of `adapter-platform-integration`, and every build resolves them with
`--update-snapshots` at the moment it runs. Last night's green therefore says that this code worked
with last night's artifacts, while the release publishes against today's, and the commit is the
smaller half of what "the same thing" would have to mean. The matrix inside the release builds the
commit which is released against the artifacts it is released against, and it needs no rule about
what the same commit means after a merge. It costs the release about forty minutes. A release is
dispatched by hand and happens a few times a year, so that is cheap, and no input switches the gate
off: a published artifact cannot be taken back.

Which lines are asked is not decided a second time. The matrix reads the `line-*` profiles of the
POM, so it cannot fall behind the build, and the release reads the same list by running that
workflow. A preview line is one of them whenever there is one, and it excludes no test (decision
47). Right now there is none, and the next one comes with the first pre-release of 8.11 (decision
52). Should an alpha ever break so badly that waiting for it stops making sense, it is left out of
the matrix, and the gate follows.

The release workflow is `.github/workflows/deploy.yaml`. A published GitHub Release starts it, and a
start by hand is a rehearsal which publishes nothing. Its first job calls `line-matrix.yaml` with
`secrets: inherit`, and every job which builds or publishes a line comes after it. So the chain from
the gate to the published artifacts is hard and carries no input which skips it. `line-matrix.yaml`
declares `workflow_call` for exactly this.

The second rule is the issue. A line which breaks in the night gets a GitHub issue, so that the
break is seen and fixed rather than scrolled past. `release-lines-issue.yaml` opens it, one per
line, and writes the line, the commit, what the log said and a link to the run. A line which is
still red the next night gets a comment on the issue it already has, which is found again by the
label `release-lines` and a title naming only the line. A line which is green again gets a comment
saying so, and the issue stays open. A green night is not a fix: a defect which loses a workflow
in one run out of four leaves its line green on three nights out of four. Closing would also mean
that the next red night opens a second issue, and one break would end up spread over several. The
person who merged the fix is the one who closes it.

There is a second kind of issue, and it belongs to a pull request rather than to a line. Since
decision 42 every pull request builds every line, and a red preview line does not make the pull
request red. So a preview line which breaks there would be a red cross nobody has to act on, and
the change would be merged and show up in the night a day later. `preview-line-issue.yaml` opens
an issue for that break, under the label `preview-line` and a title naming the line. While such an
issue is open, the next broken run writes a comment on it instead of opening a second one.

The two kinds are kept apart because they answer different questions. The night's issue belongs
to a line on `main`. The night after a fix builds the same branch again, so it can say that the
line is green again. The preview issue belongs to a pull request, which is the one place where a
break still has an author: the change which broke the line is in front of the person who proposed
it. Nothing closes that issue automatically, not even with a comment. The next pull request builds
another branch, so its green preview line says nothing about this break. Whoever fixes the line
closes the issue. A preview line which breaks on `main` gets only the night's issue, so one break
never has two issues.

### 32. A served listener gets a cancel listener of VanillaBP's own, and the release line says which elements can have one

Superseded by decision 33. The construct this entry rests on cannot sit on an activity, so what it
describes was never deployable and it was taken out again. The entry stays because a reader of the
code and of its history has to be able to find out what was tried.

This superseded the paragraph of decision 27 about what `@TaskEvent` receives. The rest of that entry
stayed as it was.

A listener fires at the moment the modeller picked and at no other. An element taken away by an
interrupting boundary event or by a terminating end event never reaches that moment, so the method
serving the listener is never told that the work it was waiting for is gone. Version 1 had the same
hole and said nothing about it.

`TaskEvent.Event` is not widened for this. A listener is a construct no BPMS promises the same way:
Camunda 7 knows `start`, `end` and `take` on an execution listener, Camunda 8 knows `creating`,
`assigning`, `updating`, `completing` and `canceling` on a task listener, and a common set over the
two would be a promise VanillaBP cannot keep, with every new event of a BPMS forced onto a value which
does not mean it. So a listener knows the two events `TaskEvent.Event` already has. `CREATED` is the
modelled listener firing, whichever moment it is, and the event a method really wants is in the model:
one listener per event, one method per listener. `CANCELED` is the element going away.

VanillaBP writes the second listener itself, while the process is wired, one per served listener and
carrying the SAME job type. One listener with one job and one method, exactly as everywhere else in this
adapter, and the method which already serves the listener is the method which hears the cancellation.
The alternative was one job fanning out over every listener of the element, which would have put
`retries="0"` in front of somebody else's method, needed a delivery key per fanned-out call and a job
timeout covering all of them. Order in a model counts within one listener event and nowhere else, so
the added listeners move nothing a modeller wrote.

A listener the modeller put on the cancel moment itself gets none. Such a method hears the moment
through its own listener, as `CREATED`, and a second report would be the same moment twice. A modelled
`completing` listener does get `CANCELED`, and that is meant: it waits for the task to finish, and the
answer is that it never will.

The cancel listener carries no retry loop. The cluster holds the element while the job runs, so a
retry loop would hold the cancellation with it, and with no attempt left the first failure raises the
incident an operator can act on. What the model says and what this adapter passes at a failure are
two numbers since story 507, see decision 41: the model carries one attempt so a delivery the
gateway lost comes back, while a failed notification is still failed with none left. It costs one method and no other, which is what the listener per
method buys.

What can be written depends on the element and on the release line. A Camunda-managed user task
carries a `canceling` task listener on every line. Every other element needs a `cancel` execution
listener, which the cluster has from 8.10 on. `Camunda8CancelListeners` is therefore a per-line class,
which is the first case where a line decides what this adapter can DO rather than how it says
something, and it carries the writing half and the reading half together because both name a client
constant the older lines do not have. The offer stays identical across the lines: the boot of a
workflow module whose listeners are served names the listeners which hear no cancellation on this
line, so the gap is read at startup instead of being found in production.

A cancellation completes carrying nothing. That falls out of decision 1 rather than being a rule of
its own: only an execution listener on `end` carries values, and a canceled element has no gateway
behind it to decide on them.

`Camunda8CancelListenersTest` holds which element gets a listener and which does not,
`Camunda8CancelListenersOfAnElementTest` what each line writes for an element other than a user
task, and `Camunda8ModelledListenerHandlerTest` that a cancellation arrives as `CANCELED` and that a
job with no retry left is failed with none.

What those tests prove about 8.10 is the model and the client: the alpha client of that line accepts
the listener in a model and names the event of such a job. That a cluster of the line really runs the
listener when an element is canceled has not been measured here, and an integration test of the
spring-boot module against an 8.10 cluster is what would measure it. Until that test exists, a line
which stops running the listener would show up as a workflow which is canceled without a word, and
nothing in this repository would turn red.

See [Listeners somebody modelled](./README.md#listeners-somebody-modelled).

### 33. A cancel listener on the element is nothing the cluster takes, so a served listener hears CREATED again

This supersedes decision 32 and puts the paragraph of decision 27 about what `@TaskEvent` receives
back in force.

Decision 32 had VanillaBP write a `cancel` execution listener beside every served listener, on the
element the listener sits on. Camunda does not take it there. The 8.10 documentation lists the event
type under the limitations of execution listeners: "`cancel`: Supported only on the process element."
A cluster of that line says the same when it reads the model:

```
'listener-process.bpmn': - Element: Activity_Work > extensionElements > executionListeners
    - ERROR: The 'cancel' execution listener event type is not supported for the 'serviceTask' element.
      The 'cancel' event type is only supported on the 'process' element.
```

That is not a defect of the alpha and no patch of the line will change it. The event type answers
a different question from the one decision 32 asked. The same page says what such a listener does when it
fires: "Cancel listeners run when a process instance is terminated. They execute sequentially after
all child elements have terminated and before the process reaches its final terminated state." It
reports that the INSTANCE is gone, not that one element was taken away.

Nothing of the design survives the move to the process element. It rested on one listener per served
listener, each carrying the job type of the method which serves it, and a process element has one
place to hang a listener on and no job type of its own. Such a listener would also fire for a
terminated instance alone, which is one of the ways an element goes away and not the everyday one.
So the design is removed rather than bent into a shape it was never meant for.

The `canceling` task listener which decision 32 wrote beside a served listener of a Camunda-managed
user task goes with it, although that half deploys on every line. Half an answer costs more than a
gap somebody named: a method would hear `CANCELED` for a user task and nothing for the element next
to it, and no rule a developer could read would tell the two apart. The `canceling` listener VanillaBP
writes for a user task of its own is untouched. It is older than decision 32 and it still delivers
`CANCELED` for that task.

So a served listener is back where decision 27 left it. `@TaskEvent` receives `CREATED` whenever the
modelled listener fires, whichever moment the modeller picked for it, and a listener the modeller put
on the cancel moment is served as `CREATED` like any other. An element taken away by an interrupting
boundary event or by a terminating end event tells a served method nothing, and the startup report
says that out loud again.

What replaces it is designed in the stories which follow this one, and both of them start at the
process element, because that is where the cluster has the construct.

See [Listeners somebody modelled](./README.md#listeners-somebody-modelled).

### 34. The process element reports the cancelation of an instance, and only where a worker answers it

Decision 33 took the `cancel` execution listener off the element a served listener sits on, because
a cluster refuses it there. What the 8.10 documentation says about the event type is where this
entry starts: "`cancel`: Supported only on the process element", and "Cancel listeners run when a
process instance is terminated. They execute sequentially after all child elements have terminated
and before the process reaches its final terminated state."

So the construct answers one question, and it is a good one. An instance canceled through the API
reports its end, with the kind `CANCELED`, and the core then reports every task it still believes
is open in that instance to the application as `CANCELED`. Measured on 8.10.0-alpha5: the job says
`CANCEL`, `job.getKind()` is `EXECUTION_LISTENER`, the job carries the process variables including
the aggregate id, and `getProcessInstanceKey()` names the instance being terminated. A called
process whose parent is canceled gets a job of its own, which is why the notification reports that
key and never `getRootProcessInstanceKey()`: a derivation limited to one instance must not reach
for the root of the call tree.

Two paths are not cancelations however they look in a model. A terminate end event and an
interrupting event subprocess both COMPLETE the instance: the end listener runs, no cancel job is
created, and the application hears `COMPLETED`. That is the cluster's view and not a gap this
adapter can close.

**The listener and the worker are one decision.** The listener holds the instance until its job is
answered, so a model carrying one nobody serves turns a cancelation into a workflow which never
goes away and which raises no incident either - worse than an incident, because nothing says
anything at all. The listener is therefore written only where this adapter also opens the worker,
which is the same rule the end listener follows and the reason the two conditions stand next to
each other in `wireBpmn`.

**Where it is written is wider than where the end is reported.** A process whose end nobody asked
about still gets the cancel listener, as long as this application serves a task of it and the
aggregate id can be resolved. The reason is the derivation rather than the notification: such a
process can leave a task open, and a canceled instance is the only moment the core can tell the
application that the task is gone. Both listeners carry the SAME job type, so one worker answers
both, and `Camunda8WorkflowEndedHandler` tells them apart by `job.getListenerEventType()`. Anything
which is neither `END` nor `CANCEL` completes the job and reports nothing: the client's enum grows
inside a line, a newer cluster reports an event this build does not know as `UNKNOWN_ENUM_VALUE`,
and reading such a job as an end would tell the application something untrue.

Retries are the ones the end listener has, which is the model's default. A failed notification is
failed with one attempt less and a backoff, the cluster hands the job out again, and the last
failure raises the incident. Measured: the cluster raises `EXECUTION_LISTENER_NO_RETRIES` on the
process element with our message and the job key, the instance reads `ACTIVE` while the incident
stands, and update retries plus resolve incident hands the same job out again until the instance
reaches `TERMINATED`. That is deliberately not the way a task listener is failed: nothing else
is waiting behind this listener, and a notification which failed once on a database which was busy
deserves the second attempt. A task listener is failed with no retry left whatever its model says,
which decision 41 tells apart from the number the model carries.

`Camunda8CancelListeners` is per release line for the same reason it was under decision 32: the
writing half names `ZeebeExecutionListenerEventType.cancel` and the reading half
`ListenerEventType.CANCEL`, and neither exists in the client of 8.8 or 8.9. It is public this time,
with the retries as a parameter, because the Business Cockpit writes the same listener with retries
of its own and must not build its own copy (see decision 28). On the lines which do not have the
construct the boot names every BPMN process whose cancelation is therefore not reported, so the gap
is read at startup instead of being found in production.

`Camunda8CancelListenersTest` and `Camunda8CancelListenerDeploymentTest` hold what each line writes
and recognises, `Camunda8WorkflowEndedKindTest` what the handler reports for which event, and
`Camunda8WorkflowCanceledIT` that a cluster of the 8.10 line really runs the listener when an
instance is canceled.

See [The end of a workflow](./README.md#the-end-of-a-workflow).

### 35. The engine is asked before the search, and the question is a command it refuses

An extension asking where a workflow is waits for the search of `awarenessOfWorkflow`. Measured in
September 2026 on 8.10.0-alpha5, 8.9.19 and 8.8.37, one container each on an idle machine: the
create answered after 10 ms, the engine said "this instance exists" after 16 to 19 ms, and the
search found it after 167 to 1324 ms. After a cancelation the engine said "gone" after 21 to 25 ms
while the search still read ACTIVE and needed 176 to 2068 ms to turn.

So where VanillaBP holds the process instance key, the engine is asked first. The key arrives
through the fourth argument of `awarenessOfWorkflow`, which the platform added for exactly this.

**The probe may shorten the YES and nothing else.** The engine forgets an instance the moment it
ends, so a key it does not hold covers a completed workflow, a canceled one and a key which never
existed alike. `awarenessOfWorkflow` has to tell `COMPLETED` from `UNKNOWN_TO_BPMS`, because only
the second lets the election move on to the next BPMS, and a design which read the engine's 404 as
unknown would send every ended workflow of a migration setup to the wrong BPMS. Every answer but
"the engine holds it" therefore falls through to the search, unchanged. A probe which cannot answer
at all - a timeout, a broken connection, a cluster which is not there - is not a 404 and is never
read as one: it falls through as well, and the search is what reports `BPMS_UNAVAILABLE`. Turning
an outage into "unknown" is the one failure mode this must not add.

**Which command carries the question.** Two candidates were measured and both are REFUSED by an
instance the engine holds, which is what makes them a question: the cluster writes no state and an
operator never finds a modification in the history of a workflow nobody modified.

|              the command              | on which line  |  a live instance  | a key the engine does not hold |
|---------------------------------------|----------------|-------------------|--------------------------------|
| modification with one unknown element | every line     | 400               | 404                            |
| business id assignment                | 8.10 and later | 409 INVALID_STATE | 404                            |

Stephan decided on 2026-09-19 that the probe follows the business id. Where the business id of an
instance is this adapter's own and the line has the command, the assignment is sent; everywhere
else the modification. His reason reaches past this entry: Camunda supports three versions at a
time and VanillaBP follows, so 8.8 and 8.9 fall away in time and the assignment becomes the only
path which is left. Building it now means the good path is already there when the others go.

The trap that resolves is the one the assignment carries. It is a question only for an instance
which ALREADY carries a business id. An instance without one ACCEPTS it, and then a probe has
written a value into a field the application may have wanted for something else, which cannot be
undone. So the assignment is sent only where this adapter put its own id there in the first place,
which `Camunda8AdapterConfiguration.writesTheBusinessIdOfAnInstance` answers. That is the key
`aggregate-id-as-business-id` of decision 37, off by default, so an installation which does not
ask for it sends the modification on every line. What the assignment carries is the workflow
aggregate's id, so the one case where it is accepted writes the value this adapter would have
written anyway.

`Camunda8InstanceProbe` is per release line, because `newAssignProcessInstanceBusinessIdCommand`
does not exist in the client of 8.8 or 8.9.

**Nothing is asked on a shared cluster.** An instance key is unique per CLUSTER and names no
scope, which is decision 3, and the election hands the same key to every adapter of its
prioritized list. So where two `camunda8` adapter ids address one cluster, this probe would be
asked about the other one's instance, answer `ACTIVE` and end the election at the wrong adapter.
The probe is therefore skipped wherever `sharesItsCluster` is true, and the search, which filters
by scope, answers as it did before. The everyday installation with one Camunda 8 adapter keeps the
short path, because there that question is false anyway.

**The reserved element id.** The modification is a question because it names an element the model
does not have. An id which by accident matched one of the model would be ACTIVATED instead of
refused, which is a change to a running workflow nobody asked for. The id is therefore
`vanillabp-existence-probe`, and every model is read for it while it is deployed: a file which
carries it gets a warning naming the process, and no probe is sent for a workflow of that process -
such an election waits for the search the way it did before.

**And the wait which follows a no.** `WorkflowLocator.probeUntilVisible` used to wait ten seconds
in steps of 250 ms for an answer which is `UNKNOWN_TO_BPMS`, which after a 404 from the engine
means a workflow which ended - and neither of those gets better by waiting. The long window exists
for a workflow which was just STARTED: the engine holds it and the read model needs up to a second
to hear about it. A workflow the engine no longer holds has been in that read model for as long as
it ran, and what is still on its way there is the END of it. Measured in September 2026 on the
three lines, after an instance was canceled: 176 to 445 ms on 8.10.0-alpha5, 255 ms on 8.9.19 and
2068 ms on 8.8.37.

So the window is asked for one workflow. The platform carries the overload
`workflowVisibilityDelay(workflowId)` and the core calls it right after the probe, on the same
thread, so this adapter answers the short window
(`ended-workflow-visibility-timeout`, 3 seconds) for the workflow its own probe just met a 404 for
and the long one (`workflow-visibility-timeout`, 10 seconds) for everything else. Which of the two
it is comes from the probe above and from nothing else: a probe which was skipped, one which
failed, and one which found the instance all leave the long window, because none of them says the
workflow is over.

Three seconds is ONE number for every line although the lines measured differently. The release
line reaches the runtime for messages and not for behaviour (`Camunda8ReleaseLine`), a cluster is
free to be newer than the line a build was compiled against, and the slowest measurement fits under
three seconds with room to spare. What it costs the faster lines is two seconds on the case which
waits the window out to its end, which is a workflow no BPMS of the election knows - and an
operator who wants the second back writes the key.

It is not zero, because a workflow which starts and ends within a few milliseconds is gone from the
engine before the read model has heard of it at all. Answering `UNKNOWN_TO_BPMS` for a workflow
which really is `COMPLETED` is the answer which sends the next operation of a migration to the
wrong BPMS, which is the one thing neither half of this entry may do.

`Camunda8EngineBeforeTheSearchTest` holds the mapping of every answer, the process the probe stays
away from and which of the two windows each answer leaves behind, `Camunda8ErrorsTest` the codes,
and `Camunda8EngineProbeIT` runs all of it against a cluster on every line.

See [Eventual consistency of the query API](./README.md#eventual-consistency-of-the-query-api).

### 36. A job this adapter holds from the activation to the answer leases it, and the application says whether

**One sentence below is superseded by decision 67**, the one which says that an adapter id nobody
configured a cluster for is not asked. Such an adapter id now uses the local cluster, opens workers
there, and is asked for `job-lease` like every other one. The rest of this entry stands.

Camunda 8.10 lets a worker lease an activation. The job then carries a token, and the cluster takes
the completion, the failure and the BPMN error of that job only from whoever holds the current one.
An activation which follows an expired lock supersedes the token before it.

What it buys is small and real. Today, when the lock expires while the business method is still
running, the cluster hands the job out again, the method runs a second time, and both runs try to
complete. Without a lease the cluster has no opinion about which of the two runs is the current
one: the job goes to whoever answers first, and the other run is told there is no such job
(`NOT_FOUND`, a `404`), which reads exactly like a job that is long gone. The age of the activation
decides nothing. Measured against `camunda/camunda:8.9.19` on 2026-09-21 with one pod and one
worker, in both orders: the answer which arrived first was taken both times. With a lease the answer
of the older activation is refused whenever it arrives, with a `409` the adapter recognises, and the
workflow continues with what the newer run wrote. The same duplicate work, a result which no longer
depends on which run happens to be faster, and a refusal which says what happened.

**Which workers lease.** Those which hold their job from the activation to the answer: the user-task
listeners, the listeners somebody modelled, the cancel listeners of decision 32, the start events the
cluster fires itself and the end of a workflow. A task worker leases only where NO task definition of
its job type completes asynchronously (`workflowTaskCompletesAsynchronously`, asked per job type
because a worker subscribes to a job type). Phase two completes such a task by key, hours after the
activation and from a dispatcher which holds no token, so a leased job of an asynchronous task could
never be completed at all. One task definition of a job type which wants to stay open is therefore
enough to switch the lease off for that whole worker.

**Why the application decides and there is no default.** Leasing is a ratchet, per job. No command
removes a lease, and once a job has been leased, a worker of the same job type which does not lease
never sees that job again (measured on 8.10.0-alpha5: a non leasing activation never got the
abandoned job back, while a job of the same type nobody had leased reached it at once). So an
application rolled back to the 8.9 line leaves the jobs it leased standing, and under the name-clash
avoidance modes `use-prefix` and `none`, where two adapter ids can share a job type, a second
application still on 8.9 would starve on exactly those jobs. Under the default mode the job type
carries the adapter id, so that case does not arise there.

On a line whose client can lease, the boot therefore stops until `job-lease` says `use` or
`do-not-use`, with a message which explains what a lease does and that it cannot be taken back. On
8.8 and 8.9 the same key is accepted and ignored, and the boot says in one line that it has no
effect there: an application moves between lines with one configuration, and refusing the key is
what would make such a move hurt. An adapter id nobody configured a cluster for is not asked at
all, because it opens no worker.

**An extension opens its workers the same way.** `Camunda8Workers.leaseTheActivations` is public
next to `applyWorkerOptions`, and an extension which opens listener workers on this cluster calls
it for the same reason it calls that one: two components leasing the same job type with different
opinions is the starvation above, and the decision belongs to the adapter's configuration rather
than to the extension. It is deliberately not part of `applyWorkerOptions`, because only the
caller knows whether its worker can ever serve a task which stays open. The Camunda 8 extension of
the Business Cockpit calls it. An application running an extension which does not leaves
`job-lease` at `do-not-use`.

**A 409 of a job command means somebody else holds this activation.** Measured on 8.10.0-alpha5,
over both transports: a completion carrying a superseded token is refused with HTTP `409`, title
`INVALID_STATE`, on gRPC with `FAILED_PRECONDITION`, and so are a failure and a BPMN error; a
completion carrying no token at all against a leased job answers the same pair. Every other wrong
state of a job command measured there is a `404` - a key which never existed, a job already
completed, and a token handed to a job which carries no lease.

The code alone cannot say WHICH wrong state the cluster means, the REST specification says only
"the job is in the wrong state", and the words around the code are the cluster's to reword
(decision 16). So the rule is the honest one: a 409 of a job command means another activation holds
this job, whatever the reason. `Camunda8CommandRetry` stops on it instead of repeating until the
job's deadline, and it stops without failing the job: the newer run holds the job and answers it,
and failing the job would take it away from that run. The line it writes says the run converged
with a redelivery, which is what happened.

**What a lease does to the drain.** A shutdown leaves a job to its lock rather than failing it, and
a leased job is still leased when the next pod activates it. That activation gets a fresh token and
completes with it, measured on 8.10.0-alpha5, so the branch needs nothing of its own.

**What needs no token.** The update commands. An `UpdateJobTimeout` of a leased job is accepted
without one, and from a client which never activated that job, measured over both transports. The
probe of `Camunda8OpenTaskProbe`, the lock renewal of an open asynchronous task and the phase-one
check all rest on that.

`Camunda8JobLease` is per release line, because `withLease` and `withJobLeaseToken` do not exist in
the client of 8.8 or 8.9. `Camunda8JobLeaseTest` is per line too and holds what the client can do
and what the boot says about the key there; `Camunda8JobLeaseDeploymentTest` holds which worker
leases and which does not; `Camunda8ErrorsTest` and `Camunda8CommandRetryTest` hold the codes and
what the retry makes of them; and `Camunda8JobLeaseIT` runs the whole case against a cluster of
the 8.10 line - a handler slower than its lock, the older answer refused, the newer one accepted.

See [The lease of an activation](./README.md#the-lease-of-an-activation).

### 37. The workflow aggregate's id may be the business id of an instance, and only for the eye

Camunda 7 keeps the workflow aggregate's id in its business key. Camunda 8 grew a field of the
same kind with 8.9 and this adapter wrote none until now, so a person looking at a workflow in
Operate had to open the variables to see which aggregate it belongs to.

`vanillabp.adapters.<id>.aggregate-id-as-business-id` writes it there. Stephan decided on
2026-09-19 that it is a key and not a default, and that VanillaBP never reads the value back. The
reason for writing it is the human one, the reason for leaving it off is that the field belongs
to the application until this adapter takes it: an assignment is single and irreversible, so an
installation which wants its own value there would lose it without ever having been asked.

Three things follow from "only for the eye", and each of them makes the feature smaller.

**Nothing reads it.** The aggregate's id travels as a process variable at every start, and every
part of this adapter keeps reading that variable. A search by business id is served by the same
index as every other search, so the field is no shortcut to a workflow either.

**A value which does not fit is CUT.** The cluster refuses a business id longer than 256
characters with "The provided businessId exceeds the limit of 256 characters", and the aggregate
ids of VanillaBP are bounded nowhere. Refusing at the start of a workflow is the worst place for
a refusal, and nothing depends on reading the value back, so a longer id is truncated. The boot
says so once per adapter id, and nothing is written per started workflow: a line per start would
drown the log and say the same thing every time. An empty id and a single blank are refused by
the cluster as well ("No businessId provided"), so nothing is sent for one.

**It is written at creation and never assigned.** A workflow somebody else started therefore
keeps whatever business id it has, which is the whole of "an application which sets its own keeps
it" - there is no moment at which this adapter would overwrite one.

**The 8.8 line has no such field.** Its cluster answers a create carrying one with `400`,
"Request property [businessId] cannot be parsed". There the key is accepted and nothing is sent,
with one line at the boot saying so, because an application moves between lines with one
configuration.

**What the election's probe does with it.** Decision 35 left one branch waiting for this entry.
Where this key is on and the line has the assignment command, the probe of
`Camunda8ProcessService#awarenessOfWorkflow` sends the business id assignment, which an instance
already carrying this adapter's id refuses with `409` - a question which writes nothing.
Everywhere else it sends the process instance modification as before. Both commands carry the
value `Camunda8AdapterConfiguration.businessIdOf` answers, truncation included, because a probe
carrying a different string than the create wrote would be accepted where it has to be refused.

**What this refines in decision 1.** That entry opens with "Camunda 8 has no business key", which
was true of every cluster this adapter had met when it was written. It is the reason the
aggregate's id travels as a process variable, and that reason stands: the variable is still what
VanillaBP reads a workflow back by, on every line. What changes is only that the cluster now has
a second place to put the same value in, for a person to read.

`Camunda8BusinessId` is per release line, because `CreateProcessInstanceCommandStep3.businessId`
does not exist in the client of 8.8. `Camunda8BusinessIdTest` is per line too and holds what
reaches the command and what the boot says there, and `Camunda8InstanceProbeTest` of the 8.10
line holds which of the two commands the probe sends once the key is on.

See [Sharing the workflow aggregate](./README.md#sharing-the-workflow-aggregate).

### 38. A probe of a user task is recognised by its action AND its empty change list

The check which looks at the other open tasks of a workflow can ask about a Camunda-managed user
task, and the command which asks is an `UpdateUserTask` carrying nothing but an audit action. It
answers from the partition instead of the index: `204` for a task which is open and `404` for one
which is gone. How long the `204` takes depends on the model. Measured on 2026-09-19 against
8.9.19 and 8.10.0-alpha5, one container each on an idle machine: 5 to 21 milliseconds for a user
task with no `updating` listener modelled, and with a modelled listener a worker answered, 106 to
111 milliseconds on 8.9.19 and 15 to 78 milliseconds on 8.10.0-alpha5.

The command has a side effect nobody would guess from the documentation of the endpoint. It fires
a modelled `updating` task listener although it changes no attribute at all, measured on both
lines. A listener job which is not answered holds the task in state `UPDATING` for fifteen
seconds, and assigning or completing it is refused with `409` for as long as that lasts. So a
probe which nobody serves takes a task out of service.

**The mark.** `ActivatedJob.getUserTask().getAction()` hands the action of the probe back, and
`getChangedAttributes()` is empty because the update changed nothing. Both methods exist on
8.8.37, 8.9.19 and 8.10.0-alpha5. A job carrying both is completed at once by
`Camunda8ModelledListenerHandler` and no `@WorkflowTask` method runs for it.

Both halves and not one, which is the decision. The action is a string anybody may send, so
reading it alone would let a foreign update silence a listener the application wrote. An empty
change list alone is not this adapter's doing either: another party may send an empty update for
reasons of its own. Together they are specific enough, and a false positive costs a listener
notification rather than a task.

**What the mark cannot reach.** An `updating` listener whose job type this application does not
serve belongs to a foreign worker or to a connector runtime, and to that worker the probe is a
real update. The adapter knows at deployment which listeners it serves, so the check of the other
open tasks sends no probe for such an element at all and answers "cannot say" for its tasks. That
is one rule and not a second mechanism beside the mark. Since decision 54 a model reaches that case
in two ways only: a listener in a process no `@WorkflowService` class claims, and a listener on an
element carrying a `zeebe:modelerTemplate`. Anywhere else a listener no method serves ends the boot
before any probe could be sent. The template is the case this exception exists for, because there
somebody else really does answer the job.

The rule holds for that CHECK and not for the two probes which ask about a task somebody named.
`awarenessOfUserTask` and the pre-commit check of `completeUserTask` send the same empty update
for the one task a caller is about to work on, and they send it whatever listener sits there: a
caller which is holding that task in its hands is the party which may wait fifteen seconds for an
answer, and refusing to ask would leave it with no answer at all. The check above is the opposite
case, an uninvited question about a task nobody asked about, and that is the one which has to
stay out of the way. Both spellings of the event count, `update` as well as `updating`: the model
API carries the old name next to the new one on every line, and a model written with it produces
the same job.

**A probe of a served listener waits for this application.** The empty update does not return
until the listener job it fired has been answered, and the worker which answers it is one of
this application's own. So the probe holds the execution slot it runs on while a second slot
serves the job. An application with a single slot (`worker-threads: 1`) and a served `updating`
listener therefore waits out the cluster's fifteen seconds and reads `504`, which is "cannot
say" - no task is harmed and nothing is derived, but the wake-up is slow. The everyday four
slots have room for both.

**Off by default.** `vanillabp.adapters.<id>.probe-open-user-tasks` is `false`, because the
question costs one command per user task and because most applications never need it: VanillaBP
writes a `canceling` task listener next to every user task it manages, and the cluster delivers
`CANCELED` straight from there. What runs without the key is the question one level up - the
engine is asked whether it still holds the process instance, once per workflow, and a `404` there
answers every record of that workflow at once.

**What an answer means.** `404` is gone. `409` is a task the cluster has: it was measured for a
task standing in `UPDATING` and for a task whose `updating` listener denied the update. `400` is
"cannot say", because no run has ever produced one for a user task and a guess in that direction
would cancel an open task. Everything else is "cannot say" as well.

See [Task cancellation arrives at the next wake-up](./README.md#task-cancellation-arrives-at-the-next-wake-up-not-at-the-moment).

### 39. A line hands an application its own client and nothing else of ours

Decision 11 releases one artifact per Camunda minor so that an application can stay on the cluster
it has. The artifact keeps that promise through the POM it publishes, because that POM is what puts
a client on the application's classpath. Until September 2026 it did not keep it. The published POM
was a copy of the source POM with `${revision}` filled in, the client version stood in a property
the line profile sets, and a consumer activates no profile of ours. So every line published a POM
asking for the client of the current GA line: an application on the 8.8 line got the 8.9 client,
whose job activations an 8.8 cluster rejects, and an application on the preview line got a client
older than the code it runs.

**Every version is written into the published POM.** The flatten plugin runs in its `oss` mode. The
published POM of each module names its dependencies with resolved versions, and it has no parent,
no `dependencyManagement`, no properties and no profiles. Nothing in it depends on anything a
consumer would have to activate or inherit. `Camunda8PublishedPomTest` reads that POM and compares
the client version in it with the client the build was compiled against, on every line.

**What one line needs is no business of another line's users.** The rule is wider than the client,
and it is the reason the parent is dropped rather than only corrected. What this repository pins
for its own build is chosen for the newest line: the protobuf runtime, Micrometer, Testcontainers,
Lombok, the Spring Boot and Quarkus versions it compiles against. None of it may arrive at an
application through us. A pin a user is supposed to have is stated in the README and pinned by the
user, and every other pin stops at our own classpath.

**The protobuf pin stays one number.** It was the question which started this, and the measurement
answered it the other way round. The pin never reaches an application at all, so a value per line
would change nothing a user runs and would only lower what the older lines are tested against.
One number, at least the gencode of the newest pinned client, and `Camunda8ProtobufPinTest` checks
it against the client of the line being built. What an application really resolves is a table in
the README, together with the one case where an application has to pin protobuf itself.

See [Release lines](./README.md#release-lines).

### 40. An artifact says where it comes from and nothing about where we deploy

Decision 39 made the published POM a complete and self-contained description of what an
application resolves. What that POM says about the artifact itself was written for the eye of a
maintainer and was never read by anyone else, because it only existed once, in the parent. Since
every published POM carries its own resolved copy, every artifact prints it.

**Every artifact names the repository root.** Maven hands a child the parent's `url` and all three
`scm` elements with the child's own name appended, so each artifact advertised an address like
`https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/camunda8-adapter`, which is
no page. The four `inherit.append.path` attributes in the parent POM turn the appending off. The
same address for every artifact is deliberate: a link into a module directory breaks when the
module is renamed or moved, and a reader who wants the module finds it from the root in one click.

**`distributionManagement` leaves the published POM.** Where we deploy is nothing a user of the
artifact can use or act on, and on an artifact sitting on Maven Central it would point a reader at
our GitHub Packages registry. It stays in the source POM, because the deploy reads it from there,
and the flatten plugin removes it from what we publish.

**A deploy is every module or none.** The POMs of one build belong together, and a `-pl` deploy
publishes a mixture in which each half is valid on its own. Nobody notices until a user resolves
the artifact. The publish workflow deploys the whole reactor, so `CONTRIBUTING.md` says the rule
for the case a person runs the deploy by hand.

`Camunda8PublishedPomTest` reads the addresses and the absence back out of the published POM.

### 41. Two numbers answer two questions: the one in the model and the one at the failure

A listener job of this adapter carries two retry numbers, and until story 507 both were zero, so
nobody had to tell them apart. They answer different questions.

**The number in the model** is what the gateway hands back when it could not deliver an activated
job. The gateway fails such a job with the retries it had, which is meant to make it available
again at once. With zero there is nothing to hand back: the job dies, the cluster raises an
incident, and a Camunda-managed user task stays in `CREATING` forever. Measured on line 8.9 in the
nightly run 35958279261, with `Failed to send 1 activated jobs ... to client` in the cluster log
and the user task never arriving. So `creating` and `canceling` listeners are written with one
attempt left, `Camunda8TaskWiring#ONE_ATTEMPT_LEFT_FOR_A_DELIVERY_THE_GATEWAY_LOST`.

**The number at the failure** is what this adapter passes when a notification really failed. It
stays at none, whatever the model says: `Camunda8UserTaskListenerHandler` fails such a job with
`Camunda8ListenerJobs.Failure.NO_RETRIES_LEFT`, so the first failure raises the incident an
operator acts on, as before. Nothing waits behind a task listener except the task itself, and a
retry loop there would hold the element while a handler which just failed runs again.

What changed with 507 is therefore the answer to the first question only. A lost delivery comes
back, a failed notification does not, and the two numbers say so separately.

Decision 32 and decision 34 were written while both numbers were zero and read as if there were
only one. Their reasoning holds: the cancel listener still has no retry loop, and the process-level
cancel listener still keeps the retries of the end listener, which are the model's default. The
number each of them names is the one in the model, and this entry says what that number does.

`Camunda8TaskProcessingIT#aLostListenerDeliveryComesBack` fails the job back the way the gateway
does and reads it from the queue again;
`Camunda8ShutdownHandlingTest#aListenerFailingIsReported` holds the other half.

### 42. A pull request waits for every line, the same as the night does

Decision 31 let a pull request build the current GA line alone and left the other lines to the
nightly matrix. A pull request now runs the matrix, so it builds and tests every line against that
line's cluster. `checks.yaml` calls `line-matrix.yaml` without a condition, and the check
`line-pins-verified` reads the GA lines of it.

The old rule had a cost nobody saw while the lines stayed quiet. A pull request built line 8.9 and
was green, and in the night the same code failed three tests on two other lines. Whoever had
written it was a day further on by then. That happened here. Waiting for the night also means
reading the night, and a red night belongs to whoever merged the day before, which is a hand-over
no rule can make.

What paid for the old rule was the runtime, and the way we work changed under it. The gate came
when every story was its own pull request, and every one of them would have bought the matrix.
Stories now travel as a wave in one pull request, so the matrix runs once per wave, and the waves
do not follow each other back to back. Measured on this repository in September 2026: a pull
request took about twenty minutes and the full matrix takes about forty. A wave therefore waits
about twice as long, and it buys the answer the night used to give.

One duty comes with it, the one the night already had. Whoever opened the wave watches it, and a
line which goes red is looked at while the other lines are still running.

The preview line is built on every pull request and it decides none of them. Both names are
counted from the GA lines, `lines-verified` inside the matrix and `line-pins-verified` outside it,
so a red preview line leaves both green while a red GA line takes them down. The reason is what
that line is: an alpha which is rewritten under us, and a defect of the cluster it pins must not
stop somebody who needs a fix on a released line. We owe the alpha the work afterwards, and the
issue `preview-line-issue.yaml` opens is where that is written down. This was decided in September
2026, after the first wave to run the full matrix was held up by a cluster defect the adapter
cannot fix.

Nothing about a red preview line is hidden for it. The job of that line is red where it broke,
`ga-lines` writes a table into the job summary saying which lines decided the run and which one was
reported only, and a release still waits for every line.

Counting the lines is also what keeps silence from reading as success. The old check read the
result of the matrix as a whole, which cannot tell a red GA line from a red alpha, and a line which
never ran or was cancelled would have gone unnoticed in it. A line without a result is a line which
did not build.

The rest of decision 31 stands. A release still runs the matrix itself before it publishes
anything, and a line which breaks in the night still gets its issue. The release gate is the call
of the whole matrix and not the name `lines-verified`, so the release keeps waiting for the preview
line.

### 43. On the preview line no test creates a Camunda-managed user task on a shared cluster

Superseded by decision 47. The preview line runs on `8.10.0-rc1` now, that cluster hands out a
`creating` job, and the tag, the rule and the exclusions went together the way the last paragraph
of this entry said they would. The entry stays because it holds the measurement.

The REST gateway of `8.10.0-alpha5` loses a whole activate-jobs batch when it meets a `creating`
or a `canceling` task-listener job, which is `camunda/camunda#58193`. What that costs was read as
a timeout in the test which waited for the job, and the tag `user-task-listener-jobs` was written
for exactly those tests. The tag now covers every test which creates a Camunda-managed user task
on a shared cluster, because the waiting is the smaller half.

Measured against `camunda/camunda:8.10.0-alpha5` on 2026-09-25, with one user task and no
application:

- the activation over REST answered `503` after 30,7 seconds and the gateway logged the
  `NullPointerException`;
- cancelling the instance was accepted, and 24 milliseconds later the engine answered `404` to a
  second cancellation;
- the user task moved to `CANCELING` and stayed there. Eight minutes later it had not moved, and
  deleting the process definition did not move it either;
- its listener job stayed activatable the whole time, so every further activation of that job type
  lost its batch as well.

So such a task cannot be ended on that line. It cannot be completed, because the job which gates it
is never handed out, and it cannot be cancelled away, because the cancellation waits for a second
job which is never handed out either. The engine's `404` makes the cleanup of the next class
believe the instance is gone while it is not.

A cluster per class was the other candidate and it is not the answer. The Business Cockpit's
Camunda 8 tests take one cluster per class and met the same defect five times in their run
`36084108673`, fed by instances of the class itself. A cluster of its own bounds how long the
damage lasts, it does not prevent it. Our shared cluster widens the reach - twenty-six lost
activations in run `36099613510`, from the first user task to the end of the build, and
`Camunda8RestartDeliveryIT` red - but it is not what causes it.

What the tag costs on that line is a handful of tests which prove nothing there anyway, because
every one of them needs a job the line does not hand out. A test which really needs a user task on
that line brings a cluster of its own, which Testcontainers throws away with the class;
`Camunda8GrpcTransportIT` does that already, for a different reason.

Two things enforce it. `TestOnTheSharedCluster` ends what an earlier class left instead of
stopping at the engine's `404`: it answers the listener jobs a cancellation waits for, and it
fails the class with a sentence naming the class before when a user task is still between two
states afterwards. And every test which creates such a task carries the tag, which the `line-8.10`
profile excludes in both Surefire and Failsafe.

The same measurement explains a number decision 35 never claimed. On line 8.9 an instance without
a user task left the search 0,43 to 0,77 seconds after the cancellation, one with a user task 0,81
to 1,03 seconds, as long as the `canceling` listener job was answered at once. With that job left
unanswered the instance was still reported as running 130 seconds later, and it ended 0,5 seconds
after a worker finally took the job. The minute somebody once measured was an instance still
alive, not a search behind the engine, and `Camunda8ProcessService#awarenessOfWorkflow` reporting
`ACTIVE` for it was right.

When a cluster of this line hands out a `creating` job, the tag, this rule and the exclusions in
the `line-8.10` profile go together.

### 44. A `404` to a cancellation means the command is gone, not the instance

Cancelling a process instance answers `204`, and every further cancellation of the same instance
answers `404`. That reads like "the instance no longer exists", and it is the reading everything
around cancellation used to carry.

It is wrong while the instance holds a user task the cluster manages. Such an instance terminates
only after the `canceling` listener job of that task is answered, and while no application of ours
is running nobody answers it. Measured on 2026-09-25 against `8.10.0-alpha5` and against `8.9.21`:
the instance was still alive 130 seconds after the cancellation, and it ended 0.52 seconds after a
worker finally took the job. Until then it can hand a job to whichever application asks next.

So the `404` says one thing only: the engine will not take a second cancellation for this key. It
says nothing about whether the instance still runs, and nothing about whether its jobs are done
being handed out. What says that is the search, or the absence of a user task standing between two
of its states.

Our own cleanup does not rest on the wrong reading, and that was worth checking.
`TestOnTheSharedCluster#endWhateverAnEarlierClassLeftRunning` loops until two things hold at once:
no instance of the class before answers the cancellation any more, and no user task is left between
two states. It answers the listener jobs a cancellation waits for while it loops. Only the sentences
which describe it said "the `404` is how the engine says it has let go", which is the half that is
not true.

When this entry was written, one question was open: what the existence probe of
`Camunda8ProcessService` answers for an instance in that window. The probe is a refused
modification, not a cancellation, and the texts around it say "the engine forgets an instance the
moment it ends". That has been measured since. `Camunda8ProbeWhileAnInstanceTerminatesIT` holds the
window open, and on 2026-09-27, against `8.8.39`, `8.9.21` and `8.10.0-rc1`, the existence probe
answered `400 INVALID_ARGUMENT` inside it, the empty `UpdateUserTask` of the task probe answered
`409`, and both turned to `404` only once the listener job was answered. So neither probe reads a
terminating instance as gone.

Decision 43 carries the same measurement from the other side and needs nothing. Decision 35 says the
engine forgets an instance the moment it ends, and decision 38 says a `404` means gone. Both are
about the PROBE and not about a cancellation, and the measurement above shows that both hold, so
neither changes.

### 45. A job for a workflow this application does not own keeps its incident

Where two applications share a cluster and a listener job of one reaches the other, the job is
failed and the cluster raises an incident. The retries are not raised to make that quiet, and the
incident carries the core's message, which explains the situation instead of blaming whoever reads
it. The core's half of this is decision 99 of `adapter-platform-integration`.

Stephan decided this on 2026-09-27. The measurement behind it was made in story 605 and is kept
below, because the wording of the decision only makes sense next to it.

**What was measured.** A worker subscribes to a job type cluster-wide, so where two applications
deploy a BPMN process of the same name, the cluster hands each job to whichever of them asks first.
The same shape occurs with one application: a process instance left behind by an earlier run holds a
`canceling` listener job, and the next worker of that job type is served it.

The adapter reads the workflow aggregate id out of the job and hands the delivery to the core. The
core loads the aggregate, finds nothing, and throws. The user-task listener path fails such a job
with `NO_RETRIES_LEFT`, so the cluster raises an incident at the FIRST delivery, and the user task
stands in `CREATING` or `CANCELING` until an operator resolves it. No delivery record is written,
because the transaction rolled back.

So the job is not consumed quietly. It was, however, consumed under the wrong name: the message read
`No workflow aggregate of class '%s' having the ID '%s' was found ... it must not be deleted while
the workflow is active`, which tells a developer they deleted an aggregate and says nothing about
the job belonging somewhere else.

**What was decided.** The incident stays. It is what a reader should see, and it is cheap: the work
is still in the cluster, so the application which owns the workflow loses nothing by it. Raising the
retries so that the delivery costs that application nothing would mean nobody ever finds out that
two applications share a cluster and take each other's work.

The message changes, and it changes in the core, because that is where the aggregate is looked up
and where every adapter passes through. It is now `DeliveryOfAnUnknownWorkflowException`, it names
everything the cluster said about the delivery so the reader can look the workflow up on the other
side, and it names both situations it can be: a workflow another application owns, and a workflow
aggregate deleted here while its workflow was still running. Nothing in the code can tell the two
apart, so nothing in the message claims to.

Two options from the earlier draft of this entry were not taken. A new inbound outcome meaning "not
mine" was not introduced, because nothing is handed back. And the case is not left undocumented,
which the third option would have meant.

**What this adapter does with it.** `Camunda8ListenerJobs` fails the job the way it fails any other,
so the retries the caller chose apply unchanged and `Camunda8Errors#incidentMessage` puts the type
and the message into the incident. The one thing it does differently is the log line: a refusal of
this kind is reported without its stack trace, because the message is the whole finding and a trace
would only name the line of the core which read the database. Every other failure keeps its trace.

Nothing is counted here. The core counts the case as `vanillabp.task.deliveries.unknown.workflow`,
once per refusal, whichever adapter delivered it.

**What is not solved.** A job of a workflow NOBODY owns, which is what a left-behind instance
produces, stays where it is. It raises its incident, and an operator removes the instance. That is
the honest end: there is no application to hand it to.

`Camunda8ListenerJobsTest` holds the incident such a job leaves behind.

### 46. A slot nobody gives back is measured and named, and the adapter does not end it

A handler is application code and may block for as long as it likes. It holds one execution slot
while it does, and a worker of this adapter id only asks the cluster for work while a slot is free.
So a handler which never returns costs one slot forever, and losing every slot makes the whole
adapter id quiet. Nothing about that state is visible. The connection is up and the health check is
green, and no worker reports anything, because no worker does anything.

Camunda confirmed the mechanism behind it for us. Polling and handler execution run on separate
executors, but the routine which restarts the polling of a worker runs on the thread which just
finished a job. A thread which never finishes never restarts anything. Camunda's remedy for that is
to raise `numJobWorkerExecutionThreads`, and it does not reach us: this adapter hands the client an
executor of its own, so the client builds no pool to raise. Our number is `worker-threads`, and it
only decides how many stuck handlers it takes, four by default instead of one.

**The adapter reports the state and leaves the handler alone.** Ending it would mean interrupting a
thread which is inside application code, with an open transaction and an open database connection,
and no way to know whether the work it did so far may be abandoned. An interrupt there buys a second
defect in place of the first. The job itself is already safe without us: its lock ran out, the
cluster handed it to somebody else, and where the line carries a lease the late answer of the stuck
run is recognised and dropped. What is missing is not a repair, it is somebody knowing. So the
adapter measures the state and says it, and the operator decides.

A handler counts as overdue after the job timeout of its own task, not after an invented duration.
That number is already configured, it is already resolved per task over the four configuration
levels, and it is the exact point where the handler's claim on its job ended. A task which is
allowed to run long is measured against its own value and does not have to be excluded from
anything.

**The alarm is every slot held while one of the holders is overdue.** One slot held for a long time
is an application doing slow work, which is nobody's emergency. Every slot held while the oldest
holder has lost its lock is the moment this adapter id stops asking for work, and that is a
different event. The two gauges behind it are `vanillabp.camunda8.execution.slots.in.use` against
`vanillabp.camunda8.execution.slots.configured`, and `vanillabp.camunda8.execution.overdue`.
`vanillabp.camunda8.execution.oldest.seconds` is the third number and says the same thing earlier,
because a handler which is not coming back only ages.

The watch runs on a thread of its own. It has to answer while every thread this adapter runs
handlers on is blocked, which is the whole point, so it cannot share one with them. One daemon
thread per adapter id, looking at a handful of map entries every ten seconds.

The message carries the stack of each overdue handler. The drain already knows which thread each
running handler is on, so the top frames are free to take, and they are the difference between
knowing that something hangs and knowing what hangs. In practice they name the remote call which has
no time limit of its own. Twenty frames, because everything below that is the client and the
executor and reads the same every time.

It is said once. The state lasts until somebody acts, and a WARN every ten seconds would bury the
line which said it first. One WARN when it begins, one INFO when a slot comes free again. An alert
is built on the gauges; the log is what the operator reads afterwards.

`Camunda8SlotWatch` is where the watch lives.

### 47. The preview line runs on the release candidate, and it excludes nothing any more

Superseded by decision 52. Camunda released `8.10.0` on 2026-09-29, so the 8.10 line is the current
GA line and the pin is that release rather than a candidate. There is no preview line until 8.11
reaches its first pre-release. The entry stays because it holds what moving to the candidate cost.

This supersedes decision 43, which stays where it is. Decision 43 kept every test which creates a
Camunda-managed user task off the preview line. The reason was `camunda/camunda#58193`: the REST
gateway of `8.10.0-alpha5` lost a whole activate-jobs batch when it met a task-listener job whose
event carried no user task action, which is `creating` and `canceling`. Camunda closed the issue a
day after that alpha was built, so the alpha was a day too old for its own fix.

The pin now reads `8.10.0-rc1`, and the fix is in it. Measured on 2026-09-25 against
`camunda/camunda:8.10.0-rc1`, with the exclusions taken out of the `line-8.10` profile:
`Camunda8UserTaskStillCreatingIT` and `Camunda8UserTaskProbeIT` both pass, and the whole line runs
with the tagged tests back in it. Decision 43 said the tag, the rule and the exclusions go together
once a cluster of this line hands out a `creating` job. It does, so they went.

`TestOnTheSharedCluster` keeps what decision 43 built into it. Ending what an earlier class left is
not about one alpha: it answers the listener jobs a cancellation waits for, and it fails the class
with a sentence naming the class before when a user task is still between two states afterwards.
That is what a shared cluster needs on every line.

Moving to the candidate cost two changes nobody had to make for an alpha bump, and both are worth
writing down.

The client renamed the lease API. `getLeaseToken()` became `getJobLeaseToken()` and
`withLeaseToken(...)` became `withJobLeaseToken(...)`, which is the delta source of the 8.10 line
and nothing else. The cluster also got stricter about the token: `8.10.0-rc1` refuses the completion
of a leased job which carries none, with `409 INVALID_STATE` and `a matching lease token must be
provided because the job is currently leased`. On the alpha the same answer went through. The
adapter always sent the token, so nothing of it had to change; a test which completed a leased job
with the raw client did.

And the protobuf pin moved from `4.36.0` to `4.36.2`, because that is the gencode of the new client
and protobuf refuses a runtime older than the code linked against it. `Camunda8ProtobufPinTest` is
what says so, and an application on the preview line pins the same number in its own
`dependencyManagement`.

SUPPORT-34723 is the other defect this pin was moved for: up to `8.10.0-alpha5` the client planned
its next poll only while no job of that worker was in a handler, so a worker stopped asking after
the first empty poll. Camunda says the fix is `#59633` and that it is in `8.10.0-rc1`, and the run
showed it from the other side. `Camunda8JobLeaseIT` blocks a handler and lets the job's lock run
out, and on the alpha that job never came back to the worker holding it. On the candidate it came
back four times, once per free slot. So the worker does keep asking while a job of it is in a
handler.

That has a second consequence, and it cost two red runs before it was understood. The adapter hands
the client an executor as wide as `worker-threads`, and the client answers its own requests on it. A
handler which occupies every slot therefore also stops the client from completing a request of the
SAME application, whatever timeout that request was given: measured on 2026-09-25, the test's
activation died of its socket timeout at 3000 ms with a window of two seconds and at 6000 ms with
the module's five. Holding the slots down to one makes it worse rather than better.
`Camunda8JobLeaseIT` now sends its second activation with a client of its own, the way
`Camunda8TaskListenerVariablesCanaryIT` does, and it stopped counting the handler's runs, because
how often the cluster offers an expired job is the cluster's business. Four runs in a row after
that: 32.5, 38.2, 38.3 and 50.6 seconds.

What is still missing is a test which drives the defect itself rather than meeting it sideways, and
that is story 644.

See [Release lines](./README.md#release-lines).

### 48. An event subprocess does not start a workflow

A workflow starts when the BPMS creates an instance of a BPMN process. At that moment there is no
workflow aggregate, so VanillaBP asks the application to build one, and every start event the
cluster fires by itself needs a `@WorkflowStartedByBpms` method. That is what this adapter means by
a start of a workflow.

The start event of an event subprocess is not one. The cluster fires it inside a workflow which is
already running, and that workflow carries the aggregate it was started with. Building one here
would leave one workflow with two aggregates, and the application would hear that a workflow
started, long after it did.

So this adapter counts a start event only where the process itself holds it. Walking up to the
enclosing process is not enough. That walk gives the same answer for a start event at any depth of a
model. Once every start of a workflow had to be served by a method, no model with an event
subprocess booted any more.

The rule covers the start event of a plain embedded subprocess as well. BPMN allows only a none
start event there, and this adapter reports no none start event anyway, so nothing changes for such
a model. One rule for every nesting is shorter than two.

One walk reads the start events and injects the execution listener which tells VanillaBP about such
a start, so both follow the rule at once: the core hears about the start events of the process, and
the model reaches the cluster with a listener on those and on nothing else.

`Camunda8EventSubprocessStartsNoWorkflowTest` holds both halves without a cluster, and
`Camunda8EventSubprocessIT` runs a model whose event subprocess takes a waiting workflow over.

### 49. The cluster holds the workflow's name in the variable named after the aggregate's id

The id of a workflow is the id of its workflow aggregate, the application assigns it in the
`@WorkflowStartedByBpms` method, and nobody else does. Camunda 8 keeps no business key, so this
adapter keeps that id in a PROCESS VARIABLE. The rule itself and what the core does with it are
decision 98 of `adapter-platform-integration`.

**The name of the variable.** The variable is called after the workflow aggregate's id attribute: a
`Ride` whose `@Id` field is `rideId` names its workflow in the variable `rideId`.

That name is public. It stands in every process record of the cluster, every operator sees it and
every model can read it. So it was worth choosing rather than inheriting, and three alternatives
were weighed.

A fixed name such as `vanillaBpWorkflowId` would be the same everywhere, which reads well in a
cluster serving several applications. It was turned down because the model would then carry two
names for one thing: `${rideId}` is what a modeller writes in an expression, in a call activity's
input mapping and in a message correlation key, and version 1 of VanillaBP put the aggregate there
under exactly that name. A second name would have to be kept in step with the first forever.

A name derived from the BPMN process id would keep two processes of one model apart. It was turned
down because they are not apart: a call activity passes the aggregate's id down, and both processes
read it under the name the aggregate gave it.

So the attribute's name it is, which is also the name this adapter already used everywhere else - in
`Camunda8JobHandler`, in the completion of every task, in the variables a start writes. The only
thing story 653 changes is that the start listener now READS it before it decides.

**Why this works at the moment the listener runs.** A workflow the application starts is created
with that variable, because `Camunda8ProcessService` writes it into the create command. The start
execution listener of the start event runs after the instance exists and before anything else of the
process does, and its job fetches every variable, so the listener sees the name if there is one. A
workflow somebody started past VanillaBP has no such variable, and that is exactly what tells the
two apart.

**Only a process this application serves gets the listener.** The cluster runs whatever was deployed
to it, and a workflow module may deploy a BPMN process no workflow service of this application
claims. The listener holds the instance until its job is answered, and for such a process the core
has no workflow service to answer with - the start would fail, the retries would run out and the
instance would sit in an incident it never had before. So the wiring asks first, with the same
question the end and cancel listeners ask: does this process have a workflow aggregate id name? A
process without one is left exactly as it was.

**What the listener on every start event costs.** The listener is written into the model at
deployment, so the cost is paid twice: once in the model and once per started workflow.

In the model it is one `zeebe:executionListeners` element with one `zeebe:executionListener` child
per start event. Measured on a process with four start events, that is 294 characters of deployed
XML per start event: most of it the extension-element wrapper, the rest the job type, which carries
the process id and the element id. A model with ten start events therefore grows by some three
kilobytes, against the four megabytes a Camunda 8 deployment may carry. Wiring the same model twice
adds nothing. `Camunda8StartListenerCostTest` holds all three numbers, so a change shows up as a
failing test rather than as a surprise in a deployment.

Per workflow it is one job: the cluster creates the listener job, a worker of this adapter activates
it, the core answers, and the job is completed. A workflow the application started pays for one
round trip to the cluster and one load of its workflow aggregate. That is the price of telling a
foreign start from an own one on a BPMS which does not tell you itself, and a workflow whose first
task follows would load the same aggregate a moment later anyway.

### 50. The adapter says that the workers outgrew the connection pool, and raises nothing

The adapter opens one worker per process and per kind, and every worker holds a REST activation
request open while it waits for work. The Camunda client keeps at most 100 HTTP connections unless
the application says otherwise, the same number in the `8.8`, `8.9` and `8.10` clients
(`DEFAULT_MAX_HTTP_CONNECTIONS`, readable in the bytecode of `CamundaClientBuilderImpl`). An
application whose workers take that whole pool does not get them all served: the surplus workers
take turns, and whatever one of them waits for arrives a whole `request-timeout` later.

Measured on 2026-09-26 with `Camunda8RestartDeliveryIT` against `camunda/camunda:8.10.0-rc1`: 115
workers against the client's 100 connections took 10412 ms, the same 115 against 256 took 215 ms,
and 92 workers against the client's 100 took 184 ms. The number of workers against the size of the
pool is what decides, not the version of the client.

**The application is told, its pool is not raised.** The adapter could size the pool itself. It
knows the number of workers before it opens them, so it could hand the client a bigger number and
nobody would ever see this. It does not.

How many connections an application opens against its cluster is a decision about that application's
resources, and taking it behind its back is the wrong kind of help. A cluster behind a proxy with a
connection budget, an application which runs twenty replicas of itself: neither is visible from
here, and both are decided by somebody who never asked the adapter to decide them. So the adapter
says what it found, names the property and leaves the number to the application.

It is a warning and not a refusal. An application over the limit works, it is only slow in a way
nothing else explains, and refusing to start it over a number it can raise in one line would be
worse than the wait.

**Where the check sits, and what it counts.** At the end of `startWorkflowProcessing` of
`Camunda8DeploymentService`, once a workflow module opened its workers. That is the first moment the
number is known and the last one before the platform writes the block of the start, which is what
the rule "as early as possible" means here.

What it counts are the workers open on the CLIENT, not the workers the deployment service ordered.
`Camunda8Workers` is public so an extension can open workers on the same client, those hold the same
connections, and an application near the limit could cross it with nobody counting. So a worker is
counted where it is opened: `Camunda8Workers.open` opens every worker of the adapter and of an
extension, and hands it to `Camunda8ClientFactory`, which is the one object per adapter id both of
them already hold. A worker which is closed leaves the count again, so a workflow module which stops
gives its share back the way it did before.

A worker which opens while a workflow module is starting does not run the check: the number halfway
through a module is not the number it ends with, and the message names a number. The module runs the
check itself once its last worker is open. A worker which opens outside a module start runs it right
away, which is the case story 694 is about: an extension which opens its workers after the start
makes the check run a second time, and it runs on the number that is true at that moment.

An extension which brings a CLIENT OF ITS OWN has a connection pool of its own, and its workers are
not in this number. Such a worker never comes through `Camunda8Workers.open`, so nothing has to be
excluded; `core/README.md` says it where the extension API is listed.

A start says it once. The sum grows with every workflow module, and a start which repeated the
sentence per module would fill the block with one text per number while the developer's next step
stays the same. Where they raise the pool and are still short, the next start says the number they
are then short of, which is how every other startup message of VanillaBP converges.

Nothing is said for a client which prefers gRPC. Its workers activate over that transport, where
this pool is not what limits them.

**The same pool decides how long a shutdown takes.** A drain waits for the cluster to answer the
activation requests of the workers it closed, because closing a worker does not cancel the request
it has in flight, so its floor is a whole `request-timeout` (decision 6). That was the whole story
as long as the workers fitted into the pool. They do not always: the workers which found no
connection have their request QUEUED inside the client, that request still goes out once a
connection frees, and it then waits a request timeout of its own. So the workers of a client are
rounds of its pool, a round costs a request timeout, and the floor of the drain is the rounds plus
what the last worker needs to report itself closed.

`shutdown-grace` defaults to `PT20S` at a `request-timeout` of `PT10S`, which is two rounds exactly,
and nothing held the two against each other. On 2026-09-27 that cost a red integration test: an
application with 115 workers on a pool of 100 gave up after 20045 ms with its workers still holding
a request. The give-up is the finding, and what it says is that the pool was too small for the
workers of that application. What it does not say is that a job could then be delivered into a
parked request: giving up means the drain waited the whole grace out, so the window it exists to
close was closed in that ending too, which decision 61 measured. The application was over the pool,
the pool check warned about the delivery of its jobs, and about its shutdown nobody said anything.

Measured on 2026-09-28 with `Camunda8WhatADrainWaitsForIT` against the cluster of the current GA
line, a client pool of 30 and a request timeout of `PT10S`: 15 workers released after 6272 ms and 30
after 5566 ms, which is one round; 60 workers after 15424 ms, which is two; 90 workers after 25541
ms, which is three. Where inside its last round a shutdown lands depends on how far that round had
got when the workers were closed, so the floor takes the whole round. The two seconds on top come
from the run which ran OUT rather than from these readings.

**The number of workers is the part of this under review.** One worker per process and per kind is
what fills the pool. Task definitions shaped so that fewer workers carry the same work would need
fewer connections, and every number in this entry moves with them. Until they are shaped that way,
the rounds of the pool are what a drain costs.

**It warns, like the rest of this entry.** The adapter could raise the grace itself, and for the
same reason it does not raise the pool it does not raise the grace: the grace has to stay under the
shutdown budget of whatever runs the application, and that budget is not something the adapter can
see or set.

**Which way out the message names first depends on the runtime.** Spring Boot's
`spring.lifecycle.timeout-per-shutdown-phase` and Kubernetes' `terminationGracePeriodSeconds` both
default to 30 seconds, and from three rounds of the pool the floor is past that. So a grace which
carries the drain would get the application killed instead, and the message says that raising the
grace is not the way out here and asks for the pool. Below three rounds it offers both, the grace
first, because that is the smaller change.

**Why it sits next to the pool check and not in the startup validation.** The validation of the
grace knows the request timeout and not the number of workers, so the floor it can check is the
one-round floor, and it keeps doing exactly that. The rounds are known where the workers are
counted. Each of the two says its own sentence and neither says the other's: a grace under one
request timeout is reported by the validation only, because it cannot drain for a reason which has
nothing to do with the number of workers, and two messages about one value would leave the reader
choosing between them.

`Camunda8WorkerConnections.aDrainFloor` is the number and `Camunda8WorkerConnectionsTest` holds the
sentences.

**The first user of the collection point.** This is the first finding the Camunda 8 adapter reports
through `StartupReport`, the bean of decision 97 of `adapter-platform-integration`. Both platform
integrations publish one instance per application, and both producers of this adapter now hand it to
the deployment service. Where there is none, which is a test building the service by hand, the
message goes into the log where it was found.

`Camunda8WorkerConnections` holds the number and `Camunda8WorkerConnectionsTest` holds the sentence
it produces.

### 51. The shutdown grace is the budget of the whole shutdown, and the workflow modules are one wait

The platform stops the workflow modules one after another, on the shutdown thread, and it calls
the adapter once per module and per adapter instance. `shutdown-grace` sits at adapter level, so
each of those calls used to close the workers of its module and then wait the whole grace for
them. An application with three workflow modules therefore had three waits of up to twenty
seconds in a shutdown its runtime grants thirty.

The waits do not overlap. A module whose workers are still open keeps renewing their activation
request while another module is drained, and closing a worker does not cancel the request it has
in flight, so the module stopped next has a request of its own to sit out. The closed workers of
the module being drained also wait behind the still polling workers of the others for the client's
executor, which costs more than one request timeout.

Measured on 2026-10-01 with `Camunda8WhatSeveralModulesPayForAShutdownIT` against
`camunda/camunda:8.9.21`, one client with a pool of 256, `request-timeout` `PT10S`,
`shutdown-grace` `PT20S` and thirty workers per module. Each case read twice where the reading
moves with the phase the close falls into:

| modules | drained one after another | every module closed first, then one wait |
|---------|---------------------------|------------------------------------------|
| 1       | 823 ms                    | the same reading                         |
| 2       | 12519 and 21421 ms        | 5436 ms                                  |
| 3       | 32743 and 15424 ms        | 5242 ms                                  |

Three modules one after another reached past the thirty seconds the runtime grants, and one module
of that run gave up after the whole grace with its workers still holding a request. The give-up is
the finding, and what it says is that a module drained while the others still poll needs more than
the grace. What it does not say is that a job could then be delivered into a parked request: giving
up means the drain waited the whole grace out, so the window the wait exists to close was closed in
that ending too, which decision 61 measured. Waiting once stayed at about half a request timeout
whether there were two modules or three, and every module was quiet.

So the grace is spent once. Each module closes its workers as it is stopped and waits for nothing
yet; the module stopped last, which is the one leaving no registration of this adapter behind,
waits for every module of the adapter instance at once. The number the application configured is
then the number its whole shutdown takes at most, and that is the number which can be held against
the budget of the runtime. Nothing else can: only the application knows how many workflow modules
it has.

**Why not a share per module.** The adapter knows at shutdown how many modules are still to come,
so it could hand each of them an equal share of what is left. It would not help. What a module
needs is a whole request timeout, three of them do not fit into twenty seconds, and three modules
on a share of under seven seconds each would every one of them leave a parked request behind. One
wait needs one request timeout for all of them, because the requests of every module are parked at
the same time and come back at the same time. The readings show it: one module alone was released
in 823 ms and two modules together in 5436 ms, while a module drained while another one still
polled sat out the whole grace twice.

**What the SPI allows.** `stopWorkflowProcessing` of the extension SPI says that the workers of
that module stop. It does not promise that the module is quiet when the call returns, and between
two of those calls nothing touches the cluster and nothing closes the client. The client is closed
later, by `Camunda8ClientFactory`, whose backstop stops a module which never reached the adapter
before it closes the client, so the one wait happens before the client goes down on every shutdown
path.

**The key stays at adapter level.** It is read against one shutdown budget, and only the
application has one of those. A value per workflow module would be a number nobody could hold
against that budget, which is why `shutdown-grace` is not resolvable over the four levels the rest
of this adapter's scoped properties use.

**What this leaves open.** An application with two configured adapter instances has two of these
budgets, because each instance waits for its own modules and knows nothing about the other. The
adapter cannot close that: it sees neither how many other instances there are nor what they
configured.

`Camunda8Drain#awaitEveryModuleQuiet` is the wait, `Camunda8ShutdownDrainTest#twoModulesAreOneWait`
holds it, and `Camunda8WhatSeveralModulesPayForAShutdownIT` holds the numbers.

### 52. Three lines after the GA of 8.10, two of them for bugfixes, and no preview line until 8.11

The rule until 2026-09-29 was that a line ends when the next minor goes GA, so there were two GA
lines at a time plus a preview line. Under that rule the GA of 8.10 would have ended the 8.8 line on
the day it arrived.

It did not end it. The lines exist for one reason, which is decision 11: a VanillaBP bugfix has to
be deliverable without a Camunda cluster upgrade. Ending 8.8 on that day would have told everybody
on an 8.8 cluster to upgrade before they can have the next fix, and Camunda supports 8.8 until April
2027. Their cluster would have been in support while their adapter was not.

So the lines are 8.8, 8.9 and 8.10. 8.10 is the current GA line, which means a plain build produces
it and a feature lands on it. A cluster of 8.10 or newer takes it. 8.8 and 8.9 take fixes and
nothing which needs a newer cluster. There is no preview line, and the next one is the line built
against the first pre-release of 8.11.

The price is CI. Three lines are three cluster runs per pull request, and the 8.11 preview line will
make it four. The work per line is close to nothing, because every line is built from one source
tree, so what a line costs is the matrix and not the maintenance.

`2.0.0-8.10` is published with the 2.0 release, like the other two. The line is GA, and a user who
runs 8.10 should not have to point at a pre-release to get the adapter built for their cluster.

How long a bugfix line is carried is not said here. It is said in decision 60: a line is carried as
long as Camunda keeps its minor in standard maintenance, and it ends on that date and not earlier.
The Renovate boundary rule asks the question at the moment it matters: a minor bump of a pin waits
for approval and its body asks whether the oldest line is still carried.

See [Release lines](./README.md#release-lines).

### 53. A user task a job worker serves is refused in a process the application claims, and only warned about in one it does not

Camunda 8 knows two kinds of user task. One carries `zeebe:userTask` and the CLUSTER manages it,
which is the kind this adapter serves. The other carries none and a job worker serves it, which is
how VanillaBP 1 worked up to its release 1.6.3. **This adapter does not accept the second kind.**
The question is the shape of the element and nothing else, so nothing asks whether some worker would
fetch the job the cluster hands out: the model already says who serves the task.

Why the shape is refused rather than left alone is what it would cost. The cluster hands out a job
of `io.camunda.zeebe:userTask`, this version opens no worker on that job type, and the workflow
stands at the element until the job's retries are used up. No notification arrives, and
`completeUserTask` cannot answer such a task either, because the id it hands out is a job key while
the cluster expects a user-task key. Nobody sees any of that until somebody waits for a task which
never appears.

A `@WorkflowService` class claiming a BPMN process says that this application stands in for the
process. So the deployment refuses such a process. The message names the process and the elements
per shape, and it names the ways out: make the user task a Camunda-managed one whose external form
reference names the task definition of a `@WorkflowTask` method, or take the element out of the
model.

The third way out is not a user task at all, and the message names it because the boot would
otherwise end for an application which is right. An element carrying a `zeebe:taskDefinition` is
served by a worker of the APPLICATION, under a job type the application chose. Nothing of VanillaBP
notifies anybody about it and nothing completes it, so it is no longer a user task this adapter is
meant to serve, and the reader passes over it. That is the difference this entry draws: between a
user task VanillaBP serves and an element the application serves itself.

A process no class of this application claims keeps the WARN it always had, without the sentences
which asked the reader to change something. Such a process reaches the cluster because it sits in a
file next to a process this application does serve, `validateTaskWiring` asks nothing of it for the
same reason, and whoever owns it may serve such a job with a worker of their own. There is nothing
for the reader of this application's log to do about it, so nothing is asked of them.

Whether the process is claimed is read the way everything else in this adapter reads it: the core
answers the name of the workflow aggregate's id for a claimed process and nothing for an unclaimed
one. The module-level report of the core names the unclaimed processes once per workflow module, and
an adapter neither implements nor calls that one.

This is where decision 24 stops. Its reasoning holds for every unclaimed process: a file travels to
the cluster as a whole, and ending the boot over a model somebody else owns would take an
application down over an element it cannot change. What changed is the claimed process, where there
is no somebody else.

A version-1 application whose model carries such an element stops booting after the upgrade, which
is the point. `UPGRADE.md` says so in the section about user task models. An application which
cannot change its models at once has the way out every deployment failure has: a non-primary adapter
configured with `deployment-failure: warn` logs the failure instead of ending the boot.

`Camunda8JobWorkerUserTasksReportTest` holds both messages, the boundary of the element the
application serves itself, the counter-test of a claimed process whose user tasks are all
Camunda-managed, and that the WARN asks for nothing.

### 54. Every element of a claimed process has to be served, and an element template is how the model says it is served elsewhere

A `@WorkflowService` class claiming a BPMN process says that this application stands in for the
process. So no element of such a process may be left standing: where the cluster creates a job and
nothing answers it, the workflow stops inside the element, and both ends of that are quiet. There is
no incident until the job's retries are used up, and there is nothing in any log at all. The
deployment therefore refuses the process, and a process nobody claims keeps the WARN it always had.

Two elements were warned about before this entry, and both of them are now refused for a claimed
process.

An **ad-hoc subprocess with a `zeebe:taskDefinition` of its own** expects a worker which decides
round by round which inner activities to activate, by completing the job with a result naming them.
A `@WorkflowTask` method cannot say that, so this adapter opens no worker for the element. Nothing
later in the boot catches it either, because the element produces no task spec and no validation
misses a method.

A **modelled listener whose job type no `@WorkflowTask` method names** is the harder one, and the
reason is that the job type says nothing about who answers it. A worker somebody else runs and a
worker the application runs beside VanillaBP look exactly the same in the model. So the ELEMENT is
asked instead of the job type: an element built from an element template belongs to the runtime
which owns it, which is the marker of decision 23 and of decision 24, and it is read through the
same class rather than looked for a second time. A listener on such an element is named in a WARN of
its own and the boot goes on, whoever claims the process.

Using the element template for this costs one miss, and it is named rather than hidden. A developer
who meant VanillaBP to serve the listener of a templated element and forgot the method reads a
warning instead of a refusal. The alternative was a marker of this adapter's own, which would be a
second way of saying what the template already says, and a model carrying it would stop being a
model any Camunda 8 modeller understands.

One element was never part of this question. A service task without a `@WorkflowTask` method ends
the boot of a claimed process and always did: the reader hands the core a task spec whose task
definition is `null`, and `validateTaskWiring` ends the start over it. The core asks nothing of an
unclaimed process, which is the same split written in the core rather than here.

What the refusals say is what to do next. The ad-hoc message names the two ways out, the model and
the other runtime. The listener message names three: a method plus the key which lets VanillaBP
serve modelled listeners, the listener taken out of the model, and the element template for a job
somebody else answers. An application which cannot change its models at once has the way out every
deployment failure has: a non-primary adapter configured with `deployment-failure: warn` logs the
failure instead of ending the boot.

`Camunda8AdHocSubProcessTest` holds the refusal and the WARN of the ad-hoc subprocess,
`Camunda8ListenersReportTest` the refusal, the WARN of an unclaimed process and the WARN of the
templated element.

### 55. A value written as FEEL gets the prefix inside the expression, at every place a prefix is written

Under `use-prefix` a value which starts with `=` is deployed with the prefix of its place written
INSIDE the expression, so `=whichProcess` reaches the cluster as
`="loan-approval__" + string(whichProcess)`. The application writes no prefix anywhere, which is
what keeps its model portable: the same file runs on another BPMS, and the same business code with
it.

**One rule, and one list of the places it is used at.** The rule is: starts with `=`, the prefix
goes inside; anything else, the prefix goes in front. The places are the `processId` of a
`zeebe:calledElement`, the `decisionId` of a `zeebe:calledDecision`, a `bpmn:message` name, a
`bpmn:signal` name, a `bpmn:error` code, a `bpmn:escalation` code, a `zeebe:taskDefinition` type, a
`zeebe:formDefinition` external reference and the job type of a listener this application serves.
`Camunda8Scoping#forEveryPrefixedValue` is that list, and both the rewrite and the refusal below
read it, so a place added to it is covered by both in one change. A job type carries the prefix of
its BPMN process as well, so its frame is `="loan-approval__LoanApproval__" + string(...)`. That
frame only ever reaches a process nobody claims. A job type written as an expression is refused in a
process the application claims, which is decision 59, so the model left for the rewrite to frame is
one which reaches the cluster because of the file it sits in.

**Whether Camunda 8 evaluates an expression at a given place is not asked.** The maintainer decided
that on 2026-10-01: a list of the places Camunda evaluates ages with every Camunda release, and
writing one down would freeze today's answer into this adapter. The rule cannot be wrong instead.
Where an expression is not evaluated, a value starting with `=` does not appear and the rule costs
nothing; where Camunda learns to evaluate one, nothing here has to change. What is still watched is
that the cluster ACCEPTS a model carrying the frame at those places, which the canary does by
deploying one model twice, once plain and once framed. Measured on 2026-10-01 against
`camunda/camunda:8.9.21`: both deployments went through. The first attempt did not, and what it
refused was the test's own XML rather than the frame (`Element type "bpmn:message" must be followed
by either attribute specifications, ">" or "/>"`), because a FEEL expression carries quotes of its
own and those values sit in XML attributes. A model written by a modeller is escaped by the
modeller; a model composed in a test is not.

The prefix cannot go in front of such a value. An expression takes up the whole attribute, so
`loan-approval__=whichProcess` names no process and parses as no expression either. Camunda 7 gets
away with the same rewrite because `${processToCall}` is one part of a string the engine composes.

Every part of the frame was measured. Camunda's FEEL concatenates two strings with `+`, which goes
beyond the DMN standard where `+` is arithmetic. `string(...)` around the application's part makes
that concatenation work whatever the part returns, a number included. The parentheses are what carry
the shape of that part, so an `if ... then ... else ...` returning one of several ids, a
`get value(...)` over a context, a text the expression composes itself and an expression written
over several lines all survive being wrapped.

Measured on 2026-10-01 against `camunda/camunda:8.8.40`, `8.9.21` and `8.10.0-rc3`, which answered
the same in every case down to the wording of an incident. Those measurements are about the called
process and the called decision, which were the places Camunda evaluated an expression at on that
day; the frame is the same everywhere else, and the paragraph above says why it is not measured per
place. The alternative frame, `string join(["loan-approval__", string(whichProcess)], "")`, works as
well and was not taken: where the application's part is `null` it drops that part and asks the
cluster for the bare prefix (`CALLED_ELEMENT_ERROR: Expected process with BPMN process id
'loan-approval__' to be deployed, but not found.`), while `+` raises an incident which names the
application's own variable (`EXTRACT_VALUE_ERROR: Expected result of the expression
'"loan-approval__" + string(whichProcess)' to be 'STRING', but was 'NULL'. The evaluation reported
the following warnings: [NO_VARIABLE_FOUND] No variable found with name 'whichProcess'
[INVALID_TYPE] Can't add 'null' to '"loan-approval__"'`). A frame which hides a mistake of the
application is the worse frame.

The price is that the cluster then holds an expression nobody typed, and it is stated rather than
hidden. Camunda 8 parses the FEEL of a model while it deploys it, so a syntax error in the
application's part refuses the whole deployment quoting the framed expression, and the column it
reports is counted from the opening quote: `string(whichProcess +)` inside the frame was reported at
`:1:27`, the same mistake without a frame at `:1:14`. The deployment therefore says what a quoted
expression includes, once, where it reports such a refusal
(`Camunda8Scoping#whatAQuotedExpressionIncludes`). Nothing is said while a deployment goes through,
because then there is nothing for anybody to do.

An expression which composes the prefix ITSELF ends the boot, naming the element, the file, the
attribute and the expression to change, at any of the places above. The rewrite would give it a
second prefix, the cluster would be asked for `loan-approval__loan-approval__PaymentHandling`, and
every call of that element would fail once a workflow reached it. An earlier snapshot of VanillaBP 2
asked an application to compose the prefix and warned about every such element, so that model is the
one case this can come from, and a boot which says it is cheaper than one incident per instance.

What this does NOT reach is the model knowledge behind such a call. Which process the expression
names is known at execution time, so a call activity naming it by FEEL stays outside the call graph
the deployment links and outside what the workflow viewer can draw. Both read the attribute the same
way and both still see an expression. The iteration chain is not lost with it: the caller writes its
own levels into the called instance instead, which is the addendum to decision 30 of story 767.

Where the mode is not `use-prefix` there is no prefix, so nothing of this applies and nothing is
said.

`Camunda8CalledProcessScopingTest` holds what the adapter writes into the model, per shape and for
both of the attributes above, and that the frame survives the XML the deploy command sends.
`Camunda8PrefixInEveryPlaceTest` holds the two forms at every other place, the longer prefix of a
job type and the refusal covering all of them. `Camunda8PrefixInsideAnExpressionTest` holds the
refused boot and the sentence about a quoted expression. `Camunda8PrefixInsideAnExpressionCanaryIT`
holds the cluster to what was measured and to accepting the frame everywhere else.

See [Keeping workflow modules apart](./README.md#keeping-workflow-modules-apart).

A form reference (`zeebe:formDefinition externalReference`) is one of these places, and one
written as an expression is refused in a claimed process: decision 69.

### 56. The 404 of a user-task probe is about the key it was handed

A user task which is simply open never makes the probe say `404`. Decision 38 reads that answer as
"the task is gone", and story 643 measured it inside the window of a running cancelation only.
`Camunda8ProbeOfAnOpenUserTaskIT` measures the plain case: a task nobody is cancelling, asked from
the moment its `creating` listener job arrived, which is the moment an application - and with it the
Business Cockpit - learns of the task.

Measured on 2026-09-28, one cluster at a time on a machine with 16 GB, client and cluster of the
same line, the questions asked one after another so every number carries the one before it:

|                     the question                      |                      8.8.39                       |                      8.9.21                       |                    8.10.0-rc1                     |
|-------------------------------------------------------|---------------------------------------------------|---------------------------------------------------|---------------------------------------------------|
| the empty `UpdateUserTask`, answered by the partition | `204` after 12 ms, and over five further readings | `204` after 21 ms, and over five further readings | `204` after 13 ms, and over five further readings |
| `UserTaskGet`, answered by the index                  | `404` until 1667 ms                               | `404` until 649 ms                                | `404` until 219 ms                                |
| the user-task search, the other read of the index     | holds it after 1674 ms                            | after 659 ms                                      | after 229 ms                                      |
| `UpdateJobTimeout` on that user-task key              | `404` for 60 s                                    | `404` for 60 s                                    | `404` for 60 s                                    |
| the instance probe                                    | `400`, so the engine holds the instance           | `400`                                             | `400`                                             |

So the probe is AHEAD of the index rather than behind it, which is the reason it is a command and
not a search, and the `404` of decision 38 keeps its meaning for a key which is a user-task key.

**For a key of the other kind it means nothing.** The same run measured a plain BPMN user task, the
shape VanillaBP 1 served up to its release 1.6.3, whose id is a JOB key: `UpdateUserTask` and
`UserTaskGet` answered `404` for a full minute, the user-task search never held a record of the task
at all, while the job command on that key was accepted and the instance probe said the engine holds
the instance. The cluster keeps no user-task record for such a task, on any of the three lines. A
`404` there is the answer to "do you hold a user task under this key", and the task is open.

This adapter does not meet that case, and not by accident. `Camunda8OpenTaskProbe` reads the kind of
task from the model and sends no user-task command for a job, the two probes asking about a task a
caller named are called for a user task by the core, and the deployment reports a user task of the
version-1 shape rather than serving it. So the promise of decision 38 is conditional, the condition
is held by the adapter, and it is said in the javadoc of `Camunda8UserTaskProbe` so that nobody
rebuilding the question outside VanillaBP reads more out of a `404` than it says.

What version 1 did differently, for the record, because this is the entry somebody looking for
Stephan's case will find: its existence check for a user task was a `UserTaskGet`, so it read the
index and met the first row of the table above; it served both shapes of user task, so it met the
second one as well; and its Business Cockpit wrote its record from the listener job, which is why
the cockpit shows a task the index does not have yet. `UPGRADE.md` says it where an application
coming from version 1 will look.

### 57. A 404 about a user task is asked about on the job side once, and only the sentence changes

A user-task command answers `404` for a key it holds no user task under. For a user-task key that
means the task is over. For a JOB key it means nothing about the task at all: a user task served by
a job worker, the shape VanillaBP modelled up to its release 1.6.3, has no user-task record in the
cluster, so every user-task command answers `404` however open the task is.

An application meets such a key while it upgrades. `UPGRADE.md` says the task ids of version 1 are
data to migrate, and the ids of that shape of user task are job keys. The adapter used to answer one
with "gone (completed or canceled meanwhile)", which named the one thing the cluster had not said.

So on the `404`, and nowhere else, the job side is asked. It takes two questions and they are not
interchangeable.

**Whether the cluster holds a job of that key is asked of the ENGINE**, with the `UpdateJobTimeout`
this adapter sends as the existence check of a service task anyway: `404` for a key it holds no job
of, a `400` saying nobody has it activated for one it holds, an accepted command for one somebody
holds right now. Only an answer which says the cluster HAS it counts, so an unreachable cluster
claims nothing. The price is named rather than hidden: where a worker holds that job at that moment,
its deadline is pushed to `async-task-lock-renewal`. The job of this case is a user task nothing in
this version fetches, so the answer is the `400` and the cluster writes nothing.

**Which element the job belongs to is read from the INDEX**, and nothing else is. A search is behind
the engine on both ends and can carry neither half of the existence question. Measured on 2026-09-28
against `camunda/camunda:8.9.21` and `camunda/camunda:8.10.0-rc1` by
`Camunda8ProbeOfAnOpenUserTaskIT`, on the job of a plain BPMN user task: the job search answered "no
job of that key" while that job was activated and the task open, and it still answered with the job
once the job was over, as `TIMED_OUT` at once and `COMPLETED` five seconds later, while
`UpdateJobTimeout` was accepted for the open job and answered `404` once it was gone. Where the
index answers nothing the message says the rest without the element.

The state goes into the message as the cluster wrote it and is never read. The same job, five
seconds after it was activated, was `CREATED` on 8.9.21 and `TIMEOUT_UPDATED` on 8.10.0-rc1, and a
literal which is new in a patch release is nothing to build a decision on.

The empty `UpdateJob` was the candidate which would have been the read-only mirror of the empty
`UpdateUserTask`, and it is none: measured in the same runs it is refused with
`400 INVALID_ARGUMENT` ("At least one of [retries, timeout] is required", with `priority` in that
list on 8.10) both for a job which is open and for one which is over, so it tells the two cases
apart not at all.

The extra round trip costs nothing in the everyday case, because the everyday case is not a `404`.

What the caller gets does not change: `TaskNotFoundException` from the pre-commit check of
`completeUserTask`, `UNKNOWN_TO_BPMS` from `awarenessOfUserTask`. Which kind of key somebody handed
in changes the sentence, not the outcome, because an outcome which depended on it would make the
election behave differently for a migrating application than for any other.

`Camunda8A404AboutAJobKeyTest` holds the sentences and the unchanged outcomes.

### 58. A search whose answer is a set reads every page, and the paging stops at 10000 entries

A Camunda 8 search which says nothing about the page it wants is answered with 100 entries and
nothing in the answer says that there were more. Three searches of this adapter could have more
than 100 hits and read that one page: the versions a process has, the element instances of a
workflow history, and the children of a scope the walk for a task's own scope reads. None of them
failed at the ceiling. The startup check named the 100 oldest versions and missed the one just
deployed, the history showed the beginning of a workflow as the whole of it, and the aggregate of
a task in the 101st iteration of a multi-instance was written nowhere.

So a search whose answer is a SET reads every page, through `Camunda8SearchPages`, and a search
whose answer is ONE hit names its limit at the call site with a sentence saying why that number is
enough. Both halves are the decision: the second one is what keeps a search for a single workflow
from paging through a cluster.

The paging stops at 100 pages, which is 10000 entries. A reader which never stops is worse than
one which says where it stopped, and a set that large is a sign of a caller which should ask a
narrower question. Where the bound is reached, the caller writes a log line, because the records
these answers travel in have no field for "and there was more": the history record cannot say it,
and the version list cannot either. The version search therefore runs NEWEST first and is turned
back into oldest first afterwards, so the bound can only cut versions nobody asks about any more.

What this costs is one request per page while a workflow is being served. The scope walk reads
pages while an aggregate is pushed, and it stops on the page its element instance is on, so the
common case pays for one page. The walk itself still costs one search per element instance below
the scope, which is what it cost before.

### 59. A job type written as an expression is refused in a claimed process, and warned about in one nobody claims

A job type is the NAME a worker subscribes to. This adapter opens one worker per job type it reads
out of a model and subscribes exactly the string the model says, so a job type written as a FEEL
expression leaves the element unserved. Both answers Camunda 8 can give lead there. Where it
evaluates the expression, the job carries the result while the worker waits for the expression;
where it does not, the job carries the expression and no `@WorkflowTask` method can be named after
it. Measured on 2026-10-01 against `camunda/camunda:8.10.0` and `camunda/camunda:8.9.21`: the job
carried the result on both, and nothing answered under the expression itself
(`Camunda8JobTypeWrittenAsAnExpressionCanaryIT`, which the 8.8 line runs in the pull request's
matrix).

The refusal does not rest on that measurement, which is why the message says both. Decision 55 says
why no list of the places Camunda evaluates an expression at is written down in this adapter, and a
refusal built on such a list would age the same way. What the measurement would change is the
sentence about what a workflow reaching the element costs, not whether the model deploys.

**Two attributes say a job type**, and both of them are asked: the `zeebe:taskDefinition` type of a
service-like task and the type of a listener somebody modelled. The question is the shape of the
value and nothing else, the way decision 53 asks about the shape of a user task. Nothing asks
whether some worker somewhere would fetch such a job, and nothing asks whether the listeners of the
process were allowed: an expression is a name this adapter cannot subscribe to whatever a property
says.

Who claims the process decides what happens, which is where decisions 53 and 54 already stand. A
`@WorkflowService` class claiming the process says that this application stands in for it, so the
deployment refuses it. A process nobody claims reaches the cluster because of the file it sits in,
so it keeps a WARN naming what was found and asking nothing of the reader.

An element built from an element template is left out, which is the marker of decision 23 and
decision 24. Its job type names a runtime somebody else deployed, and such a runtime is free to
compose that name by expression.

**What the message had to stop saying.** The boot ended over such a model before this entry as
well, and it ended in the wrong place: the reader of a claimed process handed the core a task
definition which was the expression, `validateTaskWiring` found no `@WorkflowTask` method of that
name, and the message asked for a method nobody can write. A served listener was worse, because its
job type passed the same validation where a method happened to name it and the worker then waited
for a name no job carries. So the finding is named while the file is prepared, before the rewrite
and before the wiring validation, and the message quotes the expression as the modeller typed it
rather than the frame `use-prefix` writes around it.

**The two ways out** are in the message. Write a job type which is a fixed name and a
`@WorkflowTask` method of that name, and let the method branch on the workflow aggregate it is
handed where the work differs from workflow to workflow, which is where the data the expression
reads comes from anyway. Or leave the element to the runtime which does serve it: give the element a
`zeebe:modelerTemplate` and allow such elements with `allow-connectors`. An application which
cannot change its models at once has the way out every deployment failure has: a non-primary adapter
configured with `deployment-failure: warn` logs the failure instead of ending the boot.

`Camunda8JobTypeWrittenAsAnExpressionTest` holds the refusal, the WARN of an unclaimed process, the
listener half, the element template and that the quote is the expression the modeller typed.
`Camunda8JobTypeWrittenAsAnExpressionCanaryIT` holds the cluster to what was measured.

### 60. A bugfix line ends when Camunda stops maintaining its minor, and not before

Decision 52 says why there are three lines and why 8.8 did not end on the day 8.10 went GA. What it
did not say is when a line does end, so until now a line ended when somebody ended it on purpose.
That was no promise. Whoever picks `2.0.0-8.8` could read nowhere how long a fix will still reach
them, and the matrix grew by a column with every minor because nothing ever took one away.

A line is carried as long as Camunda keeps its minor in standard maintenance. It ends on that date
and not earlier.

The date is Camunda's own and not one invented here. Camunda's release policy says that it "provides
a standard support policy of 18 months for a particular minor version from the date it is released",
that a minor arrives in April and in October, and that at least the last three released minors get
patch releases. Read on 2026-10-01 at
`https://docs.camunda.io/docs/reference/announcements-release-notes/release-policy/` and at
`https://camunda.com/release-policy/`. The date per minor is a table of its own, in the release
notes overview of the 8.10 documentation, read on the same day at
`https://docs.camunda.io/docs/next/reference/announcements-release-notes/overview/`:

| Minor |    Released     | End of standard maintenance |
|-------|-----------------|-----------------------------|
| 8.7   | 8 April 2025    | 13 October 2026             |
| 8.8   | 14 October 2025 | 13 April 2027               |
| 8.9   | 14 April 2026   | 12 October 2027             |
| 8.10  | 13 October 2026 | 11 April 2028               |

One thing about that table matters before anybody reads a date off it. It names the planned release
date of a minor, not the day the artifact appeared. Camunda published `8.10.0` on 2026-09-29, which
is the date the `README.md` gives for the GA, and the table says 13 October 2026. The end of
maintenance is a date read out of that table and never one computed from a release, so the two never
have to be made to agree.

Why the rule has this shape comes from decision 11: the client an artifact was compiled against is
the lowest cluster version that artifact accepts. The other direction really does fail, which the
blueprints measured by running a build against `camunda/camunda:8.8.34` with the adapter compiled
against the 8.9 client, where every job activation came back with `Request property [tenantFilter]
cannot be parsed`. One artifact therefore cannot serve two minors, and decision 52 draws the
conclusion from it: a VanillaBP bugfix has to be deliverable without a Camunda cluster upgrade. So a
line ends at the moment the cluster it serves stops being a cluster anybody has to be able to get a
fix for, and Camunda's date is that moment. While Camunda still fixes your cluster, this project
still fixes the adapter for it.

**This says when a line ends, not which lines exist.** Which lines exist stays decision 52 and
whatever a release decides. 8.7 is maintained until 13 October 2026 and never got a line here, and
this rule does not open one for it.

Our side of it is one column per maintained minor. Camunda maintains three, and a preview line sits
beside them from the first pre-release of the next minor until its GA, so the matrix is three
columns for a short while after a GA and four for most of a cycle. 8.10 was the preview line from
its first alpha on 2026-05-11 to its release on 2026-09-29, which is about four and a half months
out of every six.

What that column costs is measured. Over the three nightly runs up to 2026-10-01 a line took about
33 minutes of a runner, and the lines run at the same time, so one run of the matrix costs about 100
runner minutes and about 35 minutes of waiting. How often that is paid is the other half of the
bill: `checks.yaml` calls the matrix for every pull request without a condition and the night calls
it once more, which was 55 pull request runs and 7 nights in the seven days up to 2026-10-01, so
about 103 runner hours in a week. A fourth line makes that about 137, and it adds nothing to the
waiting unless it is the slowest one. What a line costs in work is close to nothing, because every
line is built from this one source tree: the delta is `core/src/main/java-line-<id>`, the same six
class names on every line, 391 lines on 8.8, 395 on 8.9 and 437 on 8.10 (counted on 2026-10-06,
after decision 68 added the sixth). So the bill of a line
is the matrix, and nothing else about a line is expensive.

The dates line up on top of that. 8.8 leaves maintenance on 13 April 2027 and the next minor is due
in the same April, so the column 8.11 adds is the column 8.8 gives back.

**Why not a number of our own.** A line ending once it is no longer one of the three newest GA lines
would cap our side by construction, whatever Camunda does with its cadence, and the cap is all that
way buys: today it picks the same 8.8, 8.9 and 8.10 that Camunda's dates pick. The user side is
weaker, because a date is something somebody can plan with while a count has to be worked out by
watching our releases. The two rules also part company as soon as Camunda changes something. A
faster cadence would have us drop a line Camunda still patches, which is what decision 52 refused to
do, and a longer maintenance would have us keep a line Camunda has stopped patching.

A line stays in the POM and in the matrix until its date, and it gets its last release before that
date. After the date its `line-*` profile goes, which takes the line out of the matrix on its own,
and what was published stays in the registry.

See [How long a line lives](./README.md#how-long-a-line-lives).

### 61. The restart test takes both endings of the drain, and says which one it saw

`Camunda8RestartDeliveryIT` measures the one thing a restart can get wrong on Camunda 8. A workflow
started right afterwards waits a whole job timeout for its first job, because an activation request
of the application before is still parked at the cluster and the job is activated into it. The
adapter closes that window by draining its workers before the client goes down, and until now the
test read that drain by one sentence: the one the drain writes when the module went quiet inside the
grace.

That is not the only sentence the drain writes. It has two endings. Either the module is quiet, or
the grace runs out while the cluster still owes an answer for a request one of the closed workers
parked, and then the drain warns about exactly that. The second ending is no defect of the adapter.
It is a slow cluster, and the test was asserting that the cluster was not slow.

What that assertion cost is measured. Two runs of 2026-10-01 went red on it, word for word the same.
The publish run of `443a32d` (36871927557) stopped the first application of the test after 25049 ms
and the run of pull request 231 (36889710683) after 25012 ms, both with 119 closed workers of which
at least one had not been released, and both with no handler left inside the application. The second
application of each of those runs drained in 10123 ms.

The number the test exists for held in both of them: the first job after the restart came in 1.2
seconds and under, against a lock of 20 seconds. It holds whenever the drain gives up, because
giving up means it waited the whole grace, so the window the parked request could have swallowed the
job through was closed long before the second application started.

Raising the grace from 20 to 25 seconds after the finding of 2026-09-27 bought nothing, and the
measurement says why no further number would. Reading every closed worker while the drain ran showed
all 119 of them sitting on the activation request they had in flight when they were closed. They
come back within a second of each other, one request timeout after the shutdown began, which is
where the 10 to 12 seconds of an idle machine come from. Squeezing the cluster into four tenths of a
core reproduces the red runs on demand, with 105 of the 119 requests still open when the grace runs
out. So the floor of that wait is the cluster answering 119 parked requests, and the grace is a
number the test may configure while the floor is not.

**What the test demands now** is everything it ever claimed about the adapter. The drain runs before
the client is closed and says what it did, in either of its two endings. Nothing cuts a running
handler off. The ordinary platform shutdown reaches the adapter, so the backstop of the client
factory stays silent. And the first job after the restart arrives in milliseconds instead of in a
job timeout, which is the point of the test and is untouched. What it no longer demands is that the
cluster answers every parked activation request inside the grace. That is the cluster's promise, not
the adapter's. A drain which does nothing still fails the test, because it writes neither of the two
sentences, so the first assertion takes it and the delivery assertion takes it as well.

**The two endings are not equally good.** The quiet ending is the normal case and the other one is
the drain's own warning, so nothing here may read as if both were fine. Every assertion about the
shutdown names the ending it read, and the measurement the test writes down after a green run names
it too. A reader of a failure therefore sees whether the drain was quiet or warned, and nobody has
to take this entry as a weakened assertion.

`shutdown-grace` stays at 25 seconds in that test. It is still the budget of the whole shutdown,
which is what decision 51 made it, and it still bounds the wait. It is only no longer the thing an
assertion reads.

**Why not the two other ways.** Giving the drain fewer workers to wait for would have meant an
application of its own for this test, with one process instead of the thirty-five its module
deploys. This test is also the module's evidence for `max-http-connections: 256`, measured with 115
workers, and that evidence would have gone with it. Halving the window, with a request timeout of
five seconds and a gap of three, would have kept the ratio and widened the margin under the grace
from 15 to 20 seconds. It would have left the same bet running on a wider margin.

**What this leaves open.** The test no longer notices a drain which runs into the grace on every
run. The ending in the written measurement is what makes such a drift readable, and it has to be
read to be noticed.

### 62. The expressions of a model are reported for the model being deployed, not for the versions the cluster still holds

The core is told the expressions of a process while that process is wired, so what it hears is the
model this application version brings. The cluster keeps the older versions, workflows are still
running on them, and those models are not read for this.

The way to read them is there. `Camunda8ProcessVersions` walks the models of the picture this
adapter has of what the cluster holds, which is how `concurrentTokenElementsOfVersion` answers, and
the same walk would answer this question. So the question here is what such a message would be
worth.

An old model says nothing new. An expression which reads a path reads the same path in every version
which carries it, and the message names the element, the place and the expression. What differs is
that nobody can act on an old model. A model in the cluster cannot be edited, the deployed one is
where a developer writes the plain getter the message asks for, and the workflows on the old version
run out on their own. A warning about them would ask for work nobody can do.

There is a second reason, and it is the count. The message ends with how many of the expressions of
this process name a variable and nothing else, which is what tells a developer how far their model
is. Counting the held versions as well would count the same expression once per version, and the
number would stop meaning what it says.

`ConcurrentTokenCheck` asks about held versions for a reason this check has not got. A parallel
gateway the newest model dropped keeps forking every workflow which started before it, so the
finding only exists in the old version. An expression is not like that. It is read where the model
carries it, and the model carrying it is the one being deployed.

Whoever wants the held versions in the message gets a story of their own, and the place to hook it
is `Camunda8ProcessVersions`.

### 63. The expressions are read from the model before this adapter writes into it

`Camunda8DeploymentService.wireBpmn` asks for the expressions right after it reports compensation,
and that is before `wireMessageSubscriptions` and `Camunda8MultiInstance.wire` run. Both of those
write FEEL into the model: a message which carries no correlation key gets the one VanillaBP
correlates by, and a multi-instance element gets the input mappings this adapter needs. Reading
later would report those as the modeller's expressions, and the message would ask a developer to
simplify something they never wrote.

### 64. A failing read model only slows an application down, and everything it holds up comes back

A Camunda 8 cluster keeps its running state in the engine and a copy of it in the secondary storage, which an
exporter writes and the search API reads. Two things can go wrong with that copy while an application runs: the
storage stops answering, or the exporter stops writing and the storage answers with an old state.

What this adapter promises for both is the rule Stephan set on 2026-10-04. The application is held up for as long
as the copy is broken, and no longer. Afterwards everything continues, on its own or after somebody repeats
something, such as resolving an incident, calling an operation again or restarting a pod. Nothing may need more
than a repetition. That rules out:

- work which never arrives: a task, a message, a start, an `aggregateChanged`, a completion, a report to an
  extension;
- data somebody has to repair by hand: an aggregate which no longer matches its workflow, an outbox entry which
  hangs or has to be deleted, a delivery record which answers a later delivery wrongly;
- a pod which no longer starts, or which only recovers after somebody restarts it;
- resources used up during the outage which do not come back, such as connections, threads or outbox memory.

Work done twice is a case of its own. It is acceptable where VanillaBP's idempotency catches it: the delivery log
and its deduplication, the idempotency key of the outbox. Every such case is named, together with whether it is
caught.

**Why the rule can hold at all.** The engine does not need the copy. Job activation, job and user-task commands,
timers, messages, deployments and the existence checks of a single task are engine calls. What needs the copy is
finding a workflow by its aggregate, because Camunda 8 has no command for that. So an outage of the copy can only
hold up the operations which search: `correlateMessage`, `aggregateChanged`, the viewer, and the election an
extension asks for. Each of them either fails in the caller's transaction, which then rolls back and can be
repeated, or waits in the outbox, which repeats it.

**Where it holds today.** Measured on 2026-10-04 against `camunda/camunda:8.10.0` with PostgreSQL as its storage,
twelve minutes of outage under load, the application restarted in the middle (analysis `895`):

- With the storage stopped or frozen, every one of about 610 workflows per run ended exactly once. Phase one of a
  search-bound operation failed after the `request-timeout` and was repeated by the application; no outbox entry
  used more than six of its fifty attempts; a pod started during the outage, thirty seconds slower.
- With the exporter paused, the engine and every task kept going as well.

**Where it does not hold yet.** Three cases, all with a stopped exporter, each with its own story:

- `897`: a planned `aggregateChanged` or `correlateMessage` whose workflow the exporter has not written yet is
  dropped as stale by a node which has no memory of that workflow, after a restart or on a second node. 480 entries
  were dropped this way in the measurement. Reading the start row of `889` instead of the in-memory hint closes it,
  and on Camunda 8 the `workflowId` in that row lets the engine answer without the copy.
- `898`: waiting for the copy counts as an attempt, ten seconds each, so such an entry is blocked after about eight
  minutes (measured: 8 min 11 s, 153 entries) and needs a hand to reopen it. Waiting has to spend time, not
  attempts.
- `899`: a task-scoped `aggregateChanged` for a task created while the exporter stands still cannot find the scope
  of that task and is skipped after the `workflow-visibility-timeout`. It has to be repeated instead.

Until those are done, the wiki page "When the read model fails" says what an operator does meanwhile: not restart
application pods while the exporter stands still, raise `vanillabp.outbox.block-after-attempts` before maintenance
of the exporter, and reopen blocked entries afterwards.

**What was not chosen.** Treating "the search does not know this workflow" as an outage. It would stop the loss
and the blocking, but it would also make every operation on a workflow which really does not exist wait for hours
instead of failing at once, and it would end the fallback election of a migration, which lives on exactly that
answer. Reading what VanillaBP itself wrote down about the start is the cheaper and the exact answer.

The measurement can be repeated: `analysis/895/bin/run.sh` builds the cluster, runs the load, takes the read model
away and prints whether every workflow ended exactly once.

*Superseded in part on 2026-10-04: the three cases under "Where it does not hold yet" are closed for every workflow which has a start row. A planned operation is no longer dropped after a restart (`897`), waiting for the copy spends time and not attempts on every store (`898`, `902`), and a task-scoped push waits for its scope (`899`), into a user task as well (`903`, decision 66). A workflow without a start row, such as one started under version 1, still has the gaps described there.*

### 65. A task-scoped push which cannot find its scope asks the engine before it gives up

*Replaced in part by decision 66: the engine is asked only about a task the row of the
delivery log says rests. Without such a row the push waits for the read model.*

`aggregateChanged(aggregate, taskId)` writes into the scope the task runs in. Camunda 8 has no command which names
that scope, so the adapter reads it from the query API: first the job behind the task id, then the scopes from the
process instance down to the job. The query API is fed by an exporter. While that exporter stands still, it knows
nothing about a task created after it stopped.

The push used to read "the query API does not know the scope after the `workflow-visibility-timeout`" as "the task
is completed" and skipped the push. For a task created while the exporter stood still that was wrong, and the value
never arrived. Analysis `895` measured it against `camunda/camunda:8.10.0` on 2026-10-04: the exporter paused for
five minutes, and all 20 workflows whose task was created in that time lost their task-scoped push. Decision 64 does
not allow that, because it is work which never arrives.

So the adapter now asks the ENGINE when the query API has no answer within the window. The question is an
`UpdateJobTimeout` on the job key, the same non-advancing command the awareness probe of a task sends:

- `404`: the cluster holds no job of that key any more. The task is completed, and skipping the push with the WARN
  is right. The workflow's own scope is still not written instead, because every branch reads it.
- `400` (the job is not activated right now) or `409` (another activation holds it): the cluster has the job. The
  push throws `PhaseTwoRetryLater` with the visibility window, and the outbox brings the entry back after it.
- no answer at all, an outage: the failure is thrown as it is, and the outbox repeats it with its own backoff.

Where no visibility window is configured, the push throws an ordinary failure instead of `PhaseTwoRetryLater`. A
window of zero would bring the entry back at once, again and again.

The same rule covers the other end. The write itself is a command, so the engine answers it. A `404` there means the
scope ended between the search and the write, together with the task, and the push is skipped with a WARN. Repeating
it would meet the same answer until the entry is blocked.

**What it costs.** Each attempt still waits up to the window for the query API before it asks the engine, as before,
so the everyday case where the exporter is a moment behind stays one attempt. While the exporter stands still, each
attempt takes the window on the dispatching thread and then the window again until it is due. The entry counts an
attempt each time, so with the defaults it is blocked after `vanillabp.outbox.block-after-attempts` of them, about
16 minutes. Waiting which spends time instead of attempts is the platform's story `898`, and with it the push waits
for as long as the exporter stands still.

The probe has the side effect the awareness probe has as well: the lock of an activated job is set to
`async-task-lock-renewal`. It only runs where the query API did not know the scope within the window, which a
synchronous handler has normally finished long before.

**What was not chosen.** Giving up at once when the job search finds nothing, without waiting the window: that would
turn the everyday case, a read model a second behind, into a second attempt ten seconds later. And writing into the
workflow's own scope as a fallback: that is the lost update between iterations of a multi-instance subprocess which
the task scope exists to prevent.

Measured after the change on 2026-10-04 with the setup of analysis `895`, run E again (`camunda/camunda:8.10.0`,
exporter paused for five minutes, a timer of 30 seconds before the subprocess, no restart): the same 20 workflows
met a task the read model did not know, their pushes were repeated 90 times in all, every one of them reached the
scope of its subprocess once the exporter was back, and no entry used more than 8 attempts. `Camunda8AggregateChangedIT`
holds the case against the test cluster: it pauses the exporter, pushes into a task created after that, and waits
for the value. Without this change that test runs into its timeout.

### 66. A task-scoped push finds its task through the row of the delivery log, and asks the engine only about a task which rests

`aggregateChanged(aggregate, taskId)` writes into the scope the task runs in. Until now the adapter found that scope
through the job behind the task id. The id of a user task is a user-task key and no job key, so the job search found
nothing, the engine answered `404` to `UpdateJobTimeout`, and the push was skipped with the WARN "task ... is
completed". Measured on 2026-10-04 against `camunda/camunda:8.10.0` with `Camunda8AggregateChangedIT`: a push into
an open user task directly in its process, one into a user task in an iteration of a multi-instance subprocess, and
one into a user task while the exporter stood still. All three were skipped, and no value arrived. A user-task form
which calls `aggregateChanged` with its task id lost every push.

**The row of the task.** When the adapter leaves a task open, the core writes a row into the delivery log. Phase two of
a task-scoped push gets that row from the core as `PhaseTwoRequest#taskRecord()`, where the row names this adapter. The row names the process instance of the task (`workflowId`), its element
(`bpmnElementId`), its kind (`taskKind`) and the version of its process (`processVersion`). The adapter reads three
things from it:

- **Where the task runs.** Where the version is the one this application deployed, the deployed model says what
  encloses the element. A task directly in its process runs in the process instance of the row, so the push writes
  there without a search. That works while the exporter stands still. A task directly in an iteration of a
  multi-instance subprocess runs in that iteration. Every iteration carries the variable the adapter maps its index
  into (decision 5 in this log), and that variable is local to the iteration. So for a user task the adapter reads that one
  variable from the variables the user task sees, and the scope the cluster reports for it is the iteration. That is
  one search instead of the walk down from the process instance. Everything else is searched for as decision 65
  says: a deeper scope, a service task in an iteration (a job has no variable search), and a row of a version this
  application did not deploy, whose model may enclose the element differently.
- **Which kind of key the id is.** A user task is found by the user-task search, a service task by the job search.
  Without a row the job is looked for first and then the user task. Both kinds of key are unique within a cluster.
- **Whether the engine may be asked.** See below.

**The engine is asked only about a task which rests.** The probe of decision 65 is no harmless question. An
`UpdateJobTimeout` sets the lock of an activated job to `async-task-lock-renewal`, and so it can cut short the lock
of a job a handler is working on. A task rests where the adapter left it open: every user task, and a service task
whose method asked for the task id. Its job is renewed by the adapter to that same value anyway. The row says that
the task rests: it exists only for a task left open (`COMPLETION_PENDING`), and `taskClosedAt` stays empty until the
application closed it. So the engine is asked when the read model does not know the scope within the window and the
row says the task rests. A user task is asked with the empty `UpdateUserTask` of its awareness probe, a service task
with `UpdateJobTimeout`. `404` lets the push go with the WARN, any other refusal throws `PhaseTwoRetryLater`.

Without such a row nothing is asked, and the push throws `PhaseTwoRetryLater` until the read model knows the task.
That replaces the part of decision 65 which asked the engine about every task the search did not find. The read
model also reports a completed job and a completed scope, so such a push either finds its scope once the exporter
caught up, or meets the `404` of the write and is skipped there. The core counts such a wait in time and not in
attempts, and `vanillabp.outbox.wait-for-visibility-at-most` ends it. A task id which never existed in
this cluster is therefore blocked after that time instead of being skipped at once. That is accepted: nobody can
tell such an id from a task the exporter has not written yet without asking the engine, and the engine may not be
asked about a task nobody says rests.

**What was not chosen.**

- A column for the key of the scope in the delivery log. The job of a user-task listener carries the element
  instance of the task, but not the one of the scope around it, so there is nothing to write. It would also be a
  schema change for the one case the model and the row already answer.
- Deriving the iteration from the job of the task alone. The context of a multi-instance can be nested (an iteration
  inside an iteration) and does not come from this one task, so the adapter reads the variable it writes for every
  iteration instead.
- Asking the engine with a longer `UpdateJobTimeout` for a job which does not rest. It would lengthen the lock of a
  handler instead of cutting it short, but it would still change a job nobody asked the adapter to change.

Measured after the change on 2026-10-04 with the same three tests against `camunda/camunda:8.10.0`: all three values
arrived. The push into the user task directly in its process was written while the exporter still stood. The push
into the iteration was answered by the variable search alone, checked once with the walk switched off. The three
older tests of `Camunda8AggregateChangedIT` stayed green. `Camunda8TaskScopedPushWaitsForItsScopeTest` holds the
rules without a cluster: no row or a closed row asks nothing, a user task is asked as a user task, a task directly in
its process is written without a search, a row of another version is searched for.

### 67. A missing cluster address means the local cluster, and the start warns about it

**The choice of the address below is superseded by decision 68**: the paragraph which takes the
client's default `0.0.0.0` and the 8.8 exception after it. The address now follows Camunda's docker
compose of each line, with `localhost` as the host, and the 8.8 line needs no extra sentence any
more. `Camunda8ClientDefaultAddressTest` is called `Camunda8MissingClusterAddressTest` now. The
rest of this entry stands.

A self-managed adapter id which names no address for the protocol its client talks uses the
default address of the Camunda client: `http://0.0.0.0:8080` for REST and `http://0.0.0.0:26500`
for gRPC. The start goes on and logs a WARN. It names the address, says that this is the local
cluster, and names the key which changes it. Before, the start warned that the application boots
and then stopped in the deployment because `rest-address` was missing. The warning and the
behaviour did not agree.

Why `0.0.0.0` and not `localhost`. The rule is: whatever Camunda's docker compose binds to.
`camunda/camunda-distributions` publishes the ports in `docker-compose/versions/camunda-8.8` and
`camunda-8.9` without a host IP (`"26500:26500"`, `"8080:8080"`), so Docker binds them to
`0.0.0.0`. The client uses the same address as its default (`CamundaClientBuilderImpl`, the same
on all three lines), and the adapter reads it from there, so the two cannot drift apart. Version 1
used the same defaults through Camunda's Spring Boot starter, so an upgrade changes nothing here.

One exception is the 8.8 compose. It publishes REST as `"8088:8080"`, so the default REST address
finds nothing there. The 8.8 line adds one sentence to the WARN which says so and names the address
to set. The 8.9 and 8.10 lines publish `8080:8080` and add nothing.

Such an adapter opens its workers on the local cluster like any other. So on a line whose client
can lease, it is asked for `job-lease` as well, and the start stops until the key is set. This
replaces the sentence of decision 36 which left an adapter without a cluster out of that question.

SaaS is not affected. It has no default to fall back to, so its keys stay required, and a SaaS
adapter which is nowhere first with `deployment-failure: warn` still boots degraded.

`Camunda8ClientDefaultAddressTest` holds the WARN and the cases around it, `Camunda8LocalClusterTest`
the sentence of the 8.8 line, and `Camunda8StartupValidationBootTest` (Spring Boot) and
`Camunda8StartupValidationTest` (Quarkus) the boot. See
[Connecting to a Camunda 8 cluster](./README.md#connecting-to-a-camunda-8-cluster).

### 68. The address of the local cluster follows Camunda's docker compose of each line

A self-managed adapter id without an address for the protocol its client talks connects to the
local cluster, as decision 67 says. Which address that is depends on the release line. It is the
address where Camunda's own docker compose of that line publishes the cluster
(`camunda/camunda-distributions`, `docker-compose/versions/camunda-<line>`):

| Line |          REST           |           gRPC           |
|------|-------------------------|--------------------------|
| 8.8  | `http://localhost:8088` | `http://localhost:26500` |
| 8.9  | `http://localhost:8080` | `http://localhost:26500` |
| 8.10 | `http://localhost:8080` | `http://localhost:26500` |

The 8.8 compose publishes REST as `"8088:8080"`, the 8.9 and 8.10 composes as `"8080:8080"`. gRPC is
`"26500:26500"` on every line.

Why not the client's default. The client takes `http://0.0.0.0:8080` and `http://0.0.0.0:26500` on
every line. On 8.8 that REST port finds nothing, because the compose of that line publishes 8088.
An application which listens on 8080 itself then talks to itself. Following the compose of the line
makes the common case work without any address, and it removes the extra sentence decision 67 had to
add to the WARN on 8.8.

On 8.9 and 8.10 the compose takes host port 8080, which is also the default port of a Spring Boot or
Quarkus application. The application is the one which moves: it sets another `server.port` or
`quarkus.http.port`. The compose is Camunda's, and a default which pointed somewhere else would find
no cluster at all.

Why `localhost` and not `0.0.0.0`. Docker binds a port without a host IP to every interface, so both
reach the cluster on Linux and macOS. On Windows `0.0.0.0` is no address a client can connect to,
while `localhost` works everywhere.

The addresses sit in the line sources, in `Camunda8LocalCluster` of `core/src/main/java-line-<line>`,
so a new line brings its own and no shared code needs to know them. The client factory always sets
both addresses on the client builder. An environment variable of the client, such as
`CAMUNDA_REST_ADDRESS`, still overrules them, because the client applies it when it is built.

The WARN names the address in use and says that it matches Camunda's docker compose of the line.
Version 1 used the client's default through Camunda's Spring Boot starter, so this is a change
against version 1, and `UPGRADE.md` says so.

`Camunda8LocalClusterTest` of each line holds the two addresses and the WARN of that line.
`Camunda8MissingClusterAddressTest` holds the cases around the WARN, and
`Camunda8StartupValidationBootTest` (Spring Boot) and `Camunda8StartupValidationTest` (Quarkus) the
boot. See [Connecting to a Camunda 8 cluster](./README.md#connecting-to-a-camunda-8-cluster).

### 69. A form reference written as an expression is refused in a claimed process, and warned about in one nobody claims

The external form reference of a Camunda-managed user task is its task definition. The core finds
the `@WorkflowTask` method by it, and the job type of the lifecycle listeners is
`io.vanillabp.userTask:` plus the reference. A task definition is a name, and an expression
evaluated for each workflow names no method. So a reference written as a FEEL expression gets the
answer decision 59 gives a job type written as one: a BPMN process a `@WorkflowService` class
claims does not deploy, and a process nobody claims gets one WARN per process. The same mistake
reached on two ways gets one answer.

Measured on 2026-10-06 against `camunda/camunda:8.10.0`, with one model naming its form
`=whichForm` and one method written as `taskDefinition = "=whichForm"`, before this entry. Without
prefixes the cluster evaluated the reference, the user task carried the form `theFormToShow`, and
the listener job type stayed `io.vanillabp.userTask:=whichForm`, because it does not start with
`=` and the cluster takes it as written. The worker met that job and the method was called 305 ms
after the start. Under `use-prefix` the boot ended in the core's wiring validation instead: the
rewrite of decision 55 had framed the expression, so the method matched no task, and the message
named the method and said nothing about the model.

So the model was not broken everywhere, and that is the reason to refuse it rather than leave it.
It worked only for a method named after the text of an expression, it broke as soon as the
application switched on prefixes, and the one message it produced then pointed at the wrong place.
The refusal reads the model while the file is prepared, before the rewrite, so the message quotes
what the modeller typed, and it says why the reference has to be a name.

The question is the shape of the value and nothing else, as in decision 59. An element template
is no way out here, because a Camunda-managed user task has no job type another runtime could
subscribe to. A user task a job worker serves is not read, because decision 53 already refuses or
reports it for its shape.

This is a rule which refuses a model. Introduced after the 2.0 release it would stop an
application which boots today, which is why it comes with 2.0.

`Camunda8FormReferenceWrittenAsAnExpressionTest` holds the refusal under both modes, the WARN of
an unclaimed process and the quiet boot of a reference which is a name.

See [A form reference has to be a name as well](./README.md#a-form-reference-has-to-be-a-name-as-well).
