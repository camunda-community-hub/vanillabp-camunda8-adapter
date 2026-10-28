# Decision log

This log holds the decisions which the code of this repository points at. A number is handed out
once. It is never reused or renumbered, so a citation always finds its entry. A decision which
gets overturned keeps its entry. The entry is marked as superseded and names the entry which
replaced it.

A citation in code reads `see decision 3 in the repository's DECISIONS.md`, and it names an entry
of THIS repository only. A decision which the platform shares has its own entry in
`adapter-platform-integration`, written from that side. A pointer into another repository breaks
easily, and this log exists to avoid that kind of pointer.

Links below point into this repository's [`README.md`](./README.md). The README carries the
details which an entry leaves out on purpose.

### 1. A command carries the shared aggregate values and the aggregate-ID variable, nothing else

Camunda 8 has no business key. The variable named after the aggregate's ID attribute is therefore
the only way back from a process instance to the workflow. It is written whatever the sync model
says. Next to it travel the values the aggregate shares, because a gateway right behind a service
task decides on what the handler just computed. Nothing else travels: a correlated message carries
no content of its own.

A LISTENER job splits this into three cases, and the job itself says which one applies. An
execution listener on `end` completes exactly like a service task, with the shared values and the
aggregate-ID variable. They reach the process instance, so a gateway behind the element decides on
what the method wrote. The listener of a BPMS-initiated start has always completed that way. A
listener somebody modelled does so too, and that is what entry 27 is about.

An execution listener on `start` completes with nothing. The cluster keeps the variables of such a
completion local to the element. There they hide the process variables of the same name. They
swallow every later write of that name from inside the element, also the one of the element's own
job, and they die with the element. So sending the aggregate would take the values of the
element's own task with it. Measured against cluster and client 8.9.19 in September 2026.

A task listener completes with nothing as well, and there is nothing to weigh up. The cluster
refuses a task-listener completion which carries variables. It names its issue 23702 and says the
payload is not supported yet. The user-task lifecycle listeners which VanillaBP writes itself are
such jobs. Their job would be the wrong place in any case. It gates a transition of a task which
stays in the cluster, and writing there would overwrite what a form or a task list put into the
instance.

That is the way OUT. The way IN is a separate question. It follows decision 8 for every worker,
also for the listeners VanillaBP writes itself. A user-task listener fetches what the method
serving that task reads. The end of a workflow fetches only the aggregate's id. A start which the
cluster fires itself fetches every variable, because VanillaBP copies all of them into the
aggregate it builds. A listener somebody modelled is served by a `@WorkflowTask` method which may
declare `@TaskParam`, so its worker fetches exactly what that method asks for. So a user's
listener sees more than zero variables, and what it may write back is the question above.

### 2. Workflow modules are kept apart by scoping the identifiers

The adapter always talks to the cluster with the SCOPED identifiers: process ids, message and
signal names, error codes and task definitions. The core's registries stay keyed by the plain
ones, and everything coming back from the cluster is translated before the core sees it. The
workflow module's configuration decides which shape scoping takes: a tenant, a prefix, or nothing
at all. So no code may assume one of them. Two processes must not end up under the same scoped
identifier. The collision check which runs while a model is prepared is there for that.

The task definition of a connector is none of these identifiers. Its implementation does not come
from the workflow module. It comes from a runtime which somebody else deployed for the whole
cluster. So there is nothing in that name for a prefix to keep apart, and the adapter leaves it as
the modeller wrote it. Under a tenant the model is still deployed into the module's tenant, so a
connector runtime has to be able to see that tenant. Decision 23 says which elements carry such a
name and what allowing them costs. See [Keeping workflow modules
apart](./README.md#keeping-workflow-modules-apart).

### 3. A cluster key never says which scope it belongs to

Job keys, user-task keys and process-instance keys are unique per CLUSTER and carry neither
tenant nor prefix. Two adapter ids may address one cluster. That is the setup which migrates a
workflow module from tenants to prefixes. If a credential is a member of both scopes, the
cluster then accepts an operation of the wrong adapter without a word. So the scope a workflow
was deployed under decides who owns it, never a key. The awareness probes compare tenant and
scoped process definition id against the scope of the CALL before they answer. The two task
probes read the job or the user task to learn its scope. That read is a call to the query API.
This is why two ids on a cluster without secondary storage end the boot instead of silently
sending an operation to the wrong place.

Since decision 20 the boot ends for ONE adapter id on such a cluster as well. So the check which
counted the ids is gone, and the requirement is what refuses that setup. What this entry
decided is untouched: ownership is decided by the scope a workflow was deployed under, never
by a key.

### 4. A class opens its fields one by one, not as a whole

The process service, the deployment service and the client classes of this adapter hold dozens
of fields. Most of them are collaborators which nobody outside the class needs. Which of them a
caller may read is part of the surface of the class. So an accessor is declared per field, and
`@Getter` on the class is refused even where an IDE offers it. It would publish the current field
list, and then also every field a later change adds. `@SuppressWarnings("LombokGetterMayBeUsed")`
on such a class keeps the IDE from offering it again.

### 5. The deployed model is rewritten so the cluster can do what an embedded engine does for free

*Superseded in part by decision 73: a file whose unclaimed process waits for a message without a correlation key is not refused any more. The cluster rejects it, and this adapter writes nothing into such a process.*

Camunda 8 runs remote and answers only what its protocol carries. An embedded engine offers
several things as a side effect, and here they have to be put INTO the model before it reaches the
cluster. So `prepareBpmn` and `wireBpmn` add:

- the scoped identifiers of decision 2;
- the user-task listeners whose jobs become the CREATED and CANCELED notifications;
- a `correlationKey` expression on every message subscription which has none, so a catch event
  correlates by the aggregate id without the application modelling anything;
- an `end` execution listener on the process element for the end of a workflow, and on a start
  event for a workflow which the cluster starts itself;
- the input mappings without which the multi-instance index, total and element cannot be read at
  all.

The correlation key has a limit it always had. The aggregate id which that expression points at
belongs to a process this application serves. A process which no workflow service claims has no
aggregate to take an id from. Such a file is refused instead of rewritten, and this happens before
any part of it is prepared. The cluster demands a subscription on the message of every executable
process which waits for one, and it rejects the whole file if one is missing. So the boot ends with
a message which says where in the model the gap is. The modeller decides whether that model gets a
correlation key or loses its `isExecutable`. This adapter does not decide it for them by writing
something into a process the application does not serve.

Two rules keep that predictable. Every addition is idempotent, so wiring the same model again does
not stack listeners. And nothing the application modelled itself is overwritten. The price is
stated openly: a model which the adapter rewrites is a new process version in the cluster, so an
application with multi-instance models deploys one on the upgrade.

### 6. A restart waits for the cluster to let go of the workers

`JobWorker#close()` does not drain, and `CamundaClient#close()` interrupts a running handler
within milliseconds. Worse, an activation request of the REST transport outlives the client which
sent it. A job created while the request is pending is assigned to it and counts as activated.
Nobody answers it until its lock expires. So the application which starts next waits out
`job-timeout` instead of milliseconds. Measured against a real cluster, that turned a seven-second
restart into a twenty-second gap.

So `stopWorkflowProcessing` closes the workers of the module first. The shutdown then waits,
within `shutdown-grace`, for two things: the handlers which are still inside the application, and
the cluster releasing the workers. There is one wait for all workflow modules of the adapter
instance, not one per module; that is decision 51. The client factory closes whatever never
reached that path before it closes the client. So the order holds on every shutdown path, not only
on the one the platform lifecycles happen to take.

While that shutdown runs, no worker reports a job as failed. The job keeps its lock and its
retries, and the cluster delivers it again. The delivery record of the platform decides whether
the work runs again. The STATE of the adapter decides this, never the type of the exception,
because an interrupted handler throws like any other.

### 7. The adapter chooses how many handlers run at once

Decision 18 replaces the reasoning of this entry. What it decided still holds: the adapter picks
how many handlers run at once, the default is four, and `virtual` is a regular mode. But the
number no longer means threads which the handlers share with the scheduling of every poll. And
what the 8.8 line does on its own no longer decides anything.

The client's default is a single thread. On the 8.8 line that same thread also schedules the
polling of every worker, so one blocking handler stopped the adapter from asking for work at all.
So `worker-threads` has a default of its own, four platform threads. It also accepts `virtual`,
which means any number of virtual threads; `worker-threads-bound` limits how many of them run at
once to the same figure. Switching the mode changes how threads are created, not how much runs at
once.

Four and not one because of the defect. Small and not large because every running handler holds a
database connection. Virtual threads are a regular mode, not a mode with a warning attached. A
pinning probe over six combinations of driver and transaction manager measured no pinning at all.

Everything except the stream timeout is configured on the CLIENT, not per worker, so an
environment variable can still win. `Camunda8EnvironmentOverrides` compares what the adapter asked
for with what the client reports. If they differ, it warns with the variable, the property key and
both values.

### 8. A worker fetches only the variables somebody actually reads

*Superseded in part by decision 74: the key `fetch-variables` is gone, and a start which still sets it ends. No message names it any more.*

Nothing asked for `fetchVariables`, so every activated job carried all variables of its instance.
With a `FULL` sync model that means every shared attribute of an aggregate which the handler is
about to load from its own database anyway.

The list is built per WORKER, as the union over everything that worker serves. It is sorted,
because job streaming compares the list and the comparison has to survive a restart. The list
holds the aggregate-id variable of each served process, the multi-instance variables of the
iterations around the served element, and all `@TaskParam` names which the core reports for that
element. The last part comes from the core and not from a scan of the model. A model declares
names nobody reads, and it misses names which no model carries.

A `@TaskParam` outside the list does not arrive as `null`. The delivery fails instead, naming the
variable, the list and the property. A `null` could not be told apart from a value which really
does not exist, and that is the silent loss this avoids. `fetch-variables: all` is the way out, per
task if need be.

### 9. What a job command may repeat, and how long it may keep trying

A cluster under load rejects commands, and the client retries none of them. The outbox carries
phase two, but it does not carry the command inside a handler. A rejected completion of work which
is already committed costs the job a retry. Under lasting load it produces an incident.

So completion, BPMN error, failure and lock renewal of every worker kind run inside
`Camunda8CommandRetry`. A command may be repeated when `Camunda8Errors` classifies the error as
repeatable. There is one explicit exception: a job which is gone is final. Otherwise the rare
duplicate which at-least-once delivery leaves would turn into a storm. A socket which timed out is
repeatable, and this is worth saying because the classification alone does not name it. No answer
arrived, so the command may or may not have run, and the job's lock exists for exactly that case.
The numbers make repeating it cheap enough. A lock of five minutes against a request timeout of ten
seconds leaves room for every attempt this entry allows. A completion which did arrive comes back
as a job which is gone, and that is final. Three things limit the retry. The first is the job's
REMAINING lock, taken from the activated job and not from the configured timeout. The second is
five attempts with the client's own backoff figures. The third is the shutdown of decision 6. It
ends the retry at once, so the job stays on its lock instead of being reported.

### 10. Authentication belongs to the connection, not to a workflow

`vanillabp.adapters.<id>.auth.*` exists only at adapter level, because a credential is a property
of the connection. The method is either named or detected from the keys which are set. At startup
the detected method is printed next to the address, so nobody has to guess which one applies.

The runtime message hangs on `CredentialsProvider.shouldRetryRequest`, not on the error
classification. The client calls it for every rejected request, on both transports, for commands
and for job activation. That makes it the one place where an adapter learns that the cluster
refuses it. Going through the error classification would have meant two dozen call sites, and it
would still have missed the workers.

`mtls` is not a key, on purpose. On none of the supported lines does the client have a keystore
for its own connection to the cluster. It has one only for the token request. A property which
quietly configures something other than what its name says is worse than no property.
See [Authenticating against a cluster](./README.md#authenticating-against-a-cluster).

*Checked on 2026-10-10 against the sources of `camunda-client-java` 8.8.40, 8.9.22 and 8.10.0, the
three current lines, and still true. The gRPC channel gets a trust manager and nothing else, and the
REST client builds its `SSLContext` with trust managers and no key managers. A client keystore
(`CAMUNDA_SSL_CLIENT_KEYSTORE_*`) and a client-assertion keystore exist only on the OAuth token
request.*

### 11. The client an artifact was built against is the minimum cluster version

Camunda does not promise that a newer client works against an older cluster, and measurements
show that it does not. Now suppose there were a single artifact per adapter version. As soon as
anything in the code touches what only the newest cluster has, every bugfix could then only be
delivered together with a cluster upgrade.

So the adapter is released once per Camunda minor version, with the minor in the version
(`2.1.0-8.9`), built from ONE source tree through the `line-8.8`, `line-8.9` and `line-8.10`
profiles. A fix exists on every line with the same commit. Line-specific source folders exist for
the rare real difference and stay empty otherwise, and the API identity check keeps the public
surface identical across lines. See [Release lines](./README.md#release-lines).

### 12. A task id is decimal, and version 1's hexadecimal ids are a data migration

VanillaBP 1 could hand out a task key in hexadecimal (`task-id-as-hex-string`, off by default). An
application which switched it on stored those ids in its own data. The ids outlive an upgrade, but
this version parses them as decimal everywhere and has no such setting.

Offering the setting again was rejected. One form of a task id is simpler than two. The second
form would have to be carried forever, because nobody can prove that it is unused. And an
application which holds hexadecimal ids has to convert them either way: the ids are in ITS tables,
not in ours. What the adapter owes such an application is a sentence which names the setting, and
the place which parses the id says that now. The failure stays permanent. So the outbox entry is
still blocked after a single attempt, and not retried ten times against a key which will not
become a number.

### 13. A start asks the cluster for numbers, and asks as many of them on the last day as on the first

While an application boots, this adapter answers questions which read from secondary storage.
That storage grows for as long as the application is in production. The questions are: how many
workflows still run on an old version, how many jobs of version 1's user-task construction are
still open, and how many tasks the cluster is holding open for a process. A start of ten seconds
must not become a start of two minutes just because the application did its job for two years. The
platform states this rule for every adapter as decision 19 of its own DECISIONS.md.

For Camunda 8 that means two things. First, a question about a quantity fetches one item and reads
`page().totalItems()`. The page which came back is not the answer. Transferring the page to count
it would grow with the data, and it would also cap the number at the page size. Second, a process
definition is searched for only once for the whole process. `fetchDeployedVersions` reads every
version anyway, so it keeps the definition keys it saw. The questions which follow use these keys
instead of each searching again.

What does grow is the number of versions the cluster holds: one per deployment which changed a
model. The questions about older versions grow with it. That is on purpose. Those questions are
what the check is for, and with `outfaded-versions` an operator says which of them are no longer
interesting. `Camunda8StartupQuestionCostTest` counts what a start asks.

### 14. What this adapter does per operation is a handler, not a pair of methods

VanillaBP's adapter SPI used to ask for two methods per outbound operation, and this adapter
had nineteen of them: the eighteen halves plus the correlation overload with seven arguments,
which carried the activation id past a default. Now the adapter answers with a map: one
`PhaseOperationHandler` per `PhaseOperation`. The request of a phase carries the operation's
arguments behind named accessors, and the activation is one of them. The overload is gone together
with the pair it belonged to.

What the handlers do has not changed. The same preflight commands run as pre-commit hooks, and the
same job and user-task commands run after the commit. The cluster uses a message id to drop a
duplicate correlation. That id is built from the same values as before, activation included. Only
the shape moved.

The map states which operations this adapter serves. Some questions a Camunda 8 cluster can only
answer with a round trip, and there are more of them here than on an embedded engine. All of that
stays where it was: in the handler, not in the operation, because the operation is the same for
every adapter.

### 15. The adapter sees process definitions in the state ACTIVE and no others

Deleting a process definition does not remove it from Camunda 8. The cluster keeps it, marks it
`DELETED` and keeps returning it in searches. So a search which names no state gets the deleted
versions back along with the live ones. The startup check for old versions then reads their
models, finds the tasks this application no longer serves and reports them at every start. And the
one remedy the report suggests is the one the operator has already applied. A report which cannot
be switched off teaches everyone to ignore reports of its kind. That is worse than having no
report.

So every search this adapter runs for process definitions limits itself to the state `ACTIVE`.
This happens in one place (`Camunda8ProcessVersions#onlyDefinitionsWhichStillCount`), so the
callers cannot drift apart on "which definitions count". On purpose, no property turns it off:
nobody wants to hear about a deleted definition.

The filter arrived in `camunda-client-java` 8.8.33. By decision 11 the client an artifact was built
against is the lowest cluster version it accepts, so every supported line has the filter. A
fallback for clusters without the filter would be dead code and must not be added.

### 16. What the cluster did is read from its codes, not from the words around them

Two answers of the cluster change what this adapter does with an operation. One is a publication
refused because a message with that id still lives. The other is a query endpoint refused because
this cluster cannot be searched. Both used to be recognised by looking for a phrase in the
exception's message. The cluster is free to reword any of those phrases in any patch release. A
rewording would have turned a harmless duplicate into an outbox entry which is repeated and then
blocked. It would also have turned "this cluster cannot tell" into "this cluster is down". After
that, every operation of the adapter fails after a second instead of going on.

So every classification reads a code. A publication the cluster already knows is HTTP `409` on
REST and the status `ALREADY_EXISTS` on gRPC. A job which is gone is `404` on REST and `NOT_FOUND`
on gRPC. Both transports matter, because `prefer-rest-over-grpc` decides per adapter id which one
carries a command.

For the query API a code is not enough. A cluster refuses a search with HTTP `403` in two cases:
when it holds no secondary storage, and when the adapter's credentials are not allowed to read. It
tells the two apart only in prose. So the question is not asked per failure at all. The adapter
asks it once, while it starts processing a workflow module, with a search which exists only for
that answer. It remembers the answer per adapter id (`Camunda8QueryApi`). Every later failure of a
search is read against the remembered answer. On a cluster which can be searched it is an outage,
and the probe reports `BPMS_UNAVAILABLE`. On a cluster which refuses searches it is the missing
capability, and the probe answers optimistically. Both reasons for a refusal are permanent and
cost the adapter the same. So the messages about this state name both reasons instead of guessing
which one it was.

Since decision 20 the remembered answer decides ONE thing instead of five. The adapter requires a
`true`, and the deployment ends the boot on anything else. This does not touch the reasoning
above, and that reasoning is why the answer is still remembered. A search which fails after the
deployment is an outage. Only the probe can tell that apart from a cluster which cannot serve a
search at all.

One place still reads a wording, and it decides nothing the adapter does. A tenant request can
fail because the cluster has multi-tenancy switched off. The cluster answers it with the same
`400` as any other rejected argument. So `Camunda8TenantCheck` uses what the cluster wrote to pick
the sharper of two guiding messages. A rewording costs the sharper sentence there and nothing
else.

### 17. A start waits once for its cluster instead of repeating each round

The most common reason why a start cannot reach its cluster is a cluster which boots at the same
time as the application. Then every round the start makes fails, not only the deployment: the
tenant check, the deploy command, the question whether the cluster can be searched, and the
version queries of the startup check. A retry around one of them would have covered a quarter of
the cases. It would also have been a third way of repeating things, next to `Camunda8CommandRetry`
and the outbox.

So nothing is repeated. Before the first round which decides anything, the adapter asks the
cluster for its topology. That is the cheapest question a Camunda 8 cluster answers. The adapter
waits as long as the answer does not come. It waits once per adapter instance, because it waits
for the cluster and not for the workflow module. Everything after the wait still ends the start as
it always did. A cluster which breaks down in the middle of a deployment is not booting, it is
failing.

Three things end the wait, whichever comes first. The cluster answers, which costs one request.
Or `vanillabp.adapters.<id>.startup-wait` is used up, and the start ends naming the address, the
time waited and the cluster's last answer. Or the cluster answers something which `Camunda8Errors`
classifies as permanent, and the start ends at once. This is one classification, the same one
everything else in this adapter reads, and it makes a default of ten minutes bearable. The default
is long because the case it exists for takes minutes. The cost is a late abort, not a late
diagnosis: from the first attempt on, a line every few seconds shows the cluster's last answer.

### 18. The adapter supplies the executor, and a worker asks for work only while a slot is free

This supersedes the reasoning of decision 7, which stays where it is.

The 8.8 client gives ONE `ScheduledExecutorService` both jobs. It schedules the poll of every
worker on it, and it runs every handler call on it. So `worker-threads` blocked handlers stopped
that adapter from asking the cluster for work at all, and nothing said so. Since 8.9 the client
keeps the two apart by itself. That made it tempting to fix only the line which needs the fix.
Decision 11 rules that out: a line differs in what its cluster can do, never in what the adapter
offers. And building it once was cheaper anyway. So the adapter hands the client an executor of its
own, in both execution models and on every line. It is either a virtual thread per handler or a
pool as wide as the configured number. In both cases two platform threads do the timing, and no
handler can occupy them. So `worker-threads` counts handlers running at once, which is what the
README and the wiki promised all along. The price is those two threads per adapter id, which are
idle almost always.

Separating the two roles also removes a back pressure. Nobody designed it, but it did work on 8.8:
an adapter with every slot busy stopped asking for work. On 8.9 and 8.10 there never was such a
thing. On those lines a queue of activated jobs in front of the slots is the normal state, and
every job in it uses up its lock while it waits. The replacement is deliberate. A scheduled task
runs when an execution slot is free. When none is free, it is looked at again a moment later. So
a job which nobody could run is not fetched, and the cluster keeps it for whoever can run it.

To do that, nothing has to be told apart, and that is what made the rule possible to build. On all
three lines exactly two kinds of task reach the executor: the poll of a worker, and the opening
and re-opening of a job stream where `stream-enabled` is on. Both fetch work, and neither keeps
alive a job which was already activated. The lock renewal of a long-running task is sent by this
adapter's own handler. That handler runs on a thread which already holds a slot, so it can never
wait for one.

Only one job is kept alive by a delivery and not by a handler: the one a `@TaskId` method left
open. Its lock is renewed when the cluster hands it out again. That is an ordinary activation, so
it waits for a slot like every other. On an application which has no capacity this costs a late
renewal, never the task. The lock runs out at the cluster, the job can be fetched again, and the
delivery record answers the round which finally arrives.

Three limits are named openly. First, asking is not the same as being answered. A Camunda 8
activation request is a long poll. The gateway holds it for `request-timeout` and answers as soon
as a job appears. So a request which was already waiting when the last slot filled still brings
its batch. The rule stops the asking AGAIN, and that is what turns a single batch into a queue.
Second, the client also tops up a worker directly from the thread on which one of the worker's
handlers just finished. That path passes no executor, so it is not gated. But that worker has just
given a slot back, and this is the one case where fetching more is the point. Third, the rule
decides how often work is fetched, never how much one fetch brings.

That last part is `max-jobs-active`. It limits the queue of one worker and nothing beyond it. The
client counts the jobs it activated and has not finished yet. It activates at most
`max-jobs-active` minus that number, and asks for more as soon as the number is down to thirty
percent of it: ten of thirty-two, two of eight. Meanwhile the workers of one adapter id share the
execution slots. So fifteen workers may hold fifteen times `max-jobs-active` jobs waiting for four
slots. The rule above exists for that gap.

### 19. A declared process id is served from what the application serves and from the models the cluster holds

A workflow module may declare a BPMN process id under which it deploys nothing. That is how a
renamed process keeps being served. Under `use-prefix` a task definition is deployed as
`<module>__<process>__<task>`. So the jobs of the workflows under the old id carry a name which no
worker of the deployed processes asks for. And nobody notices: a job nobody fetches has not
failed, it is a workflow standing still. Each of those workflows needs one more subscription, and
the question is where the names come from.

**Task workers are COMPOSED from what the application serves.** The core names what that is
(`taskWiringOfProcessesNobodyDeployed`, decision 34 of the platform's own DECISIONS.md). Today it
is the task definition of every `@WorkflowTask` method registered for the declared id. The adapter
scopes each of them by that id, exactly as the deployment scoped the ones it deployed. An entry
holds what to compose from, not a job type, so this is the one place which turns it into a job
type.

Composing has a cost in three places, and each of them is paid on purpose:

- a task definition may belong to a service task or to a user task managed by Camunda, and
  nothing outside the model says which. Both subscriptions are opened, and the one of the wrong
  kind stays idle. An idle worker costs one activation request;
- such a worker asks for every variable instead of a derived list. Deriving one needs the
  elements of the model and the multi-instance elements around them. The worker fetches more than
  necessary, which is never wrong, only more expensive;
- a `@WorkflowTask` method wired to a BPMN element id (`@WorkflowTask(id = ...)`) names no task
  definition, so no job type can be composed for it. Those workflows are the one case which
  stands still after all. The start says so and names both ways out: wire the method by task
  definition, or keep deploying the old model under its old id until its workflows have ended.

Reading the job types from the models was the other way, and it was first turned down because it
needs the query API. A cluster without secondary storage cannot be searched at all. Decision 20
removed that reason: the adapter requires a cluster it can search. So reading is an open
alternative for the task workers, and picking it up is a piece of work of its own. The composed
form is what ships for them.

**User-task listeners are composed AND read.** A user task may go without a method, and the
cluster still waits for its `creating` listener. For a model the application deploys, the adapter
opens a listener worker for every user task, served or not. Composing sees only the user tasks a
method names. So a user task without a method, created in a workflow of the old id after the
rename, would stand in `CREATING` for good. So the job types of these listeners are also READ from
the models the cluster holds under the declared id, as written there: every task listener whose
type starts with `io.vanillabp.userTask:`. A listener worker is opened for each of them which no
other worker of the module serves. Where the cluster cannot be asked for its models, nothing is
added, and the user tasks of those workflows are served only where a method names them.

Measured on 2026-10-06 against `camunda/camunda:8.10.0`, with
`Camunda8UnservedUserTaskOfARenamedProcessIT` (`use-prefix`). The first generation deploys
`UnservedRenameOld` and starts a workflow which waits for a message in front of a user task without
a method. The second generation deploys the model under `UnservedRenameNew`, declares the old id
and sends the message. Without reading, the user task stood in `CREATING`, and its listener job of
type `io.vanillabp.userTask:test-app__UnservedRenameOld__reviewByHand` stood in `CREATED` with no
worker, for the whole 60 seconds of the wait. With reading, the task reached `CREATED`, and the
class was green in 29 seconds.

**One picture of the models the cluster holds, and every check asks it.** A check against a BPMN
model must not care whether the model comes from the current deployment or was already in the
cluster. The platform's decision 38 holds the rule. This is what it costs on Camunda 8. A rename
is the case which forced it. `correlateMessage` validated the message name against the models
which THIS application version deployed. After a rename, only the old id's model in the cluster
declares the name which a waiting workflow needs. The check threw inside the caller's transaction,
while the workflow sat right there, waiting for exactly that name.

The adapter keeps one picture per adapter id (`Camunda8ModelsTheClusterHolds`) instead of giving
every check a query of its own. The picture holds the models the cluster has for every BPMN
process id the application declares. The models of the current deployment cost nothing.
Everything else is read through `Camunda8ProcessVersions` on first use and then kept, because the
model of a definition never changes. The answer can be "cannot tell", as a value. A check reading
it does not have to invent that case, and it stays silent on it. "Cannot tell" itself is never
kept. A cluster which cannot be reached says nothing about the next call. The settled "cannot be
searched" of decision 20, on the other hand, answers without a request.

A refusal may rest on the picture only after the cluster was read again in the same call. Another
node's deployment may have made the kept picture outdated (a rolling upgrade). A message declared
moments ago must not be refused because of yesterday's read. So the error path pays one search,
and the everyday path pays none.

The same picture gives the workers of a declared-only id the multi-instance chains of the models
the cluster runs. So a job of those workflows carries its iteration context, and a multi-instance
task is no reason to keep the old model deployed. And the rule turned the other way round ended a
refusal of its own. A user task without an external form reference used to cost the old-version
startup check its whole answer, if the task sat in a model which an earlier application deployed.
Such a model is only read. So where the reference is missing it is read by version 1's formKey
convention, and a task which follows no convention is skipped instead of fought over.

`Camunda8DeclaredProcessWorkersTest` holds which workers are opened per mode and what the start
says. `Camunda8RenamedProcessIT` proves the whole thing against a cluster with prefixed
identifiers. It also correlates a message which only the old id's model declares, and proves that
the refusal names what the cluster holds. `Camunda8MessageDeclarationTest` holds the check's
answers.

### 20. A cluster which cannot be searched is not a cluster this adapter serves

Finding a workflow by its aggregate's id is a search. So is everything the viewer asks and
everything the version catalog asks. A cluster started without secondary storage refuses all of
them. So does a cluster whose credentials are not allowed to read what the adapter asks for. The
adapter used to have a separate answer for each question in that state. The election probe
answered optimistically and warned once. The push of a changed aggregate failed with a guiding
message. The version list came back empty. The viewer served what this application version
deployed and reported no element history. And two adapter ids on one such cluster ended the boot.
That was five behaviours and five messages, and the integration tests needed two kinds of cluster
to keep all of it measured.

None of it gains a capability. It gains the ability to boot against a cluster which cannot answer
what VanillaBP asks. The cost is a second behaviour behind every question the adapter answers, and
each of those has to be documented and tested. One feature fewer, which nobody asked for, is a
better trade than a set of internal gaps. And the version-1 applications we know all run a cluster
which can be searched. From 1.7.0 on they had no choice. While it started, version 1 read every
active process definition of its tenant through the search API. There was no way to switch it off
and no fallback, so a cluster which refused that search kept the application from coming up. 2.0
names the requirement instead of leaving it to the first thing which fails.

So the requirement is checked once per workflow module, while that module deploys. The check runs
after the start has waited for its cluster, next to the tenant check. Waiting first keeps the
check honest. A cluster which cannot be REACHED is still not declared incapable: the answer stays
open, and the next question asks again. Throwing from the deployment keeps it fair. Take an
adapter which is not the first-priority adapter of the module and has `deployment-failure: warn`.
It boots degraded with a guiding warning. The old BPMS needs exactly that when a migration moves
off such a cluster.

That warning is the first half of the way out, not all of it. An adapter which deployed nothing
cannot answer the election either. `canLocateWorkflows` says so, and that is why it still reads
the probe instead of answering `true` from a constant. So a workflow module serving two adapters
also has to accept the routing by list order
(`vanillabp.workflow-modules.<id>.election.guessing-adapters: ACCEPTED`). The core asks for this in
a message of its own. A migration off such a cluster costs two messages, and each guides to the
next step. That is the whole cost, because nothing after them is silent.

One place decides the capability and one place reads it. So letting such a cluster back in later
is one decision, not fifteen. `Camunda8QueryApi` keeps the remembered answer. Every message about
a failed search still depends on telling an outage apart from a cluster which cannot serve a
search. It also keeps naming both reasons for a refusal: the cluster answers `403` for either and
tells them apart only in prose.

`Camunda8SearchableClusterCheckTest` holds the message and what it names.
`Camunda8UnsearchableClusterIT` holds the boot which ends against a real cluster refusing a real
search. The same class holds the warning which an adapter that may degrade gets instead.

### 21. One picture of the models the cluster holds, and every check asks it

*Merged into decision 19 on 2026-10-12, before the 2.0 release.* It said that the adapter keeps one
picture of the models the cluster holds, and that every check asks it.

### 22. A test asks for a cluster, and the release line decides what that cluster costs

Every integration test of this repository needs a cluster which answers searches, because
decision 20 leaves it no other kind. Until Camunda 8.9 that meant a second container: the cluster
exported to an Elasticsearch next to it. So each of the twenty or so test classes started a pair.
The Elasticsearch alone asked for a gigabyte of heap, and then filled it with the four documents
one test wrote. From 8.9 on the cluster can keep its secondary storage in a database instead, and
the image ships an H2 driver. So an embedded in-memory database inside the cluster's own process
answers the same searches. There is no container next to it and nothing to clean up afterwards.

Which of the two a line uses is a property of the LINE, not of a test.
`camunda8.cluster.secondary-storage` sits next to `camunda8.cluster.image` in the parent POM and
travels the same way. It is filtered into the `camunda8-cluster.properties` of the `test-support`
module, and every module which starts a cluster reads that file from the classpath. The `line-8.8`
profile sets it to `elasticsearch`. Its own cluster serves the other storage differently, so moving
the previous GA (general availability) line onto it would change what that line is tested
against. A line which only gets bugfixes must not do that. Every other line takes the
default, `rdbms`, and so will a preview line when there is one again.

A test class sees none of this. It asks `ClusterUnderTest` for a cluster and gets a container
which it can ask for its mapped ports. In most classes that is `sharedCluster()`, the one cluster
which all classes of a module share. They run in one JVM, so one container serves them all, and a
module starts one cluster instead of about thirty. Some class may need a cluster which nobody else
touches, because it is configured differently or must never have seen its model. Such a class
declares a field `ClusterUnderTest.cluster()` instead (or `cluster(logName)` where a module starts
more than one). In both cases, where the line needs an Elasticsearch, the cluster container creates
the network and that Elasticsearch itself. It depends on the Elasticsearch, so Testcontainers
starts that first, and it stops both again in its own `stop()`. That last part is why the storage
is no longer simply a second `@Container` field, as it used to be. Testcontainers starts what a
container depends on, but stops only what a class declared. A module runs all its classes in one
JVM. So an Elasticsearch which nobody declared would outlive its cluster, and the module would
hold twenty of them by the end of a run.

`Camunda8TaskProcessingIT` is the everyday proof of the database storage, and
`Camunda8LocatingWorkflowsIT` of the searches on top of it. Both run the other half when they get
`-Dcamunda8.cluster.secondary-storage=elasticsearch`. That is the quick way to check a change to
this mechanism on a local machine. The pull request builds every line since decision 42, so
line 8.8 runs the Elasticsearch path there as well.

### 23. Connectors are allowed per adapter, and every boot says what they cost

A model may carry elements which this application does not serve, and `zeebe:modelerTemplate`
marks them. The rule has a condition. That attribute marks an element built from an ELEMENT
TEMPLATE, and a connector is only the most common of those. A company writes templates for its own
plain job-worker tasks as well. A rule without a condition would leave such a task without a
worker and without a validation, so its workflow would stand at it forever. So `allow-connectors`
is the gate, and the model still says WHICH elements. The property only answers whether the marker
is read at all.

The key belongs to this ADAPTER and to no wider scope. Connectors are a Camunda 8 concept, and no
other BPMS has anything to do with the marker. A platform-wide key would ask every adapter about
something only this one can answer. The key resolves at adapter, workflow-module and workflow
level, and the most specific configured value wins in both directions. It has no task level. That
level is keyed by the task definition, and a connector's task definition is the connector's own
type, which every element using that connector shares.

The job type of such an element is left as the modeller wrote it, because this workflow module
never owned it as an identifier. It names a runtime which somebody else deployed for the whole
cluster, and prefixing it would rename something this application does not own. The alternative
would have been to refuse `use-prefix` wherever a connector sits in a model. That takes the only
isolation mode which works without a multi-tenant cluster away from every application which wants
a single connector. The price is real and stated openly: such a job type reaches the cluster
unscoped. That costs nothing here, because a connector runtime subscribes to it for the whole
cluster anyway.

When the switch is on, a boot writes a warning, and no key silences it.
`accept-unscoped-identifiers` is the earlier case of a key which acknowledges a warning away. It
exists because the application can state a fact which the adapter cannot check: that its
identifiers are unique. There is no such fact here. What the warning says stays true for as long
as the connector is in the model, and a key turning it off would only hide the loss.

See [Elements another runtime serves](./README.md#elements-another-runtime-serves).

### 24. An ad-hoc subprocess nothing serves is named, and the boot goes on

**Superseded for a BPMN process the application claims by decision 54.** What is written below
holds for a process which no `@WorkflowService` class of the application claims. For a claimed
process, the deployment refuses the file instead of warning about the element.

*Superseded for a process nobody claims by decision 73: such a process is not looked at, so it gets
no WARN either. Together with decision 54 this replaces the warning below completely.*

Camunda 8 knows two kinds of this element. In the first kind the model names the activities to
run. This adapter serves it without any extra code: the activities inside are ordinary tasks, and
the list of ids is an attribute of the workflow aggregate. In the second kind the element carries a
`zeebe:taskDefinition` of its own. This adapter does not serve it, and cannot. Completing such a
job means answering with the elements to activate, and a `@WorkflowTask` method has no way to say
that. Giving it one is a story about the outcome of a workflow task, not about this element.

The decision is about what happens to a model which uses the second kind anyway. It deploys. The
workflow reaches the element and stops there, and the job which the cluster activated ends in an
incident once its retries are used up. Nothing later in the boot detects it, because the wiring
does not collect this element. It produces no task spec, so no validation misses a method. So the
deployment says it itself, once per BPMN process. It names the element, what it costs and the two
ways out.

It is a warning and not a refused deployment, for the same reason as for a BPMN process which
nobody serves. The other processes of that workflow module are fine. A file travels to the cluster
as a whole, so ending the boot would take an application down over one element of one model. A
developer has to see the defect, and the message is where they see it.

The element template tells the two cases apart. An ad-hoc subprocess which carries
`zeebe:modelerTemplate` is left out of the report, because then somebody else's runtime owns it.
The Camunda AI agent is an element template on exactly this element, and a connector runtime
fetches its job. That is the marker of decision 23. It is read through the same class, not looked
for a second time.

Apart from all this, the element is reported as a source of concurrent tokens. This happens for
both kinds, however short the list of activities looks. `activeElementsCollection` is an
expression which is evaluated when the workflow enters the element. So the model cannot promise
that the list stays one entry long. And a warning which appears only after a data change is worse
than one which always appears.

See [Ad-hoc subprocesses](./README.md#ad-hoc-subprocesses).

### 25. The cluster is asked which of a module's names it already holds, and the answer is a hint

`validateNoCollidingProcessIds` compares the identifiers of one deployment with each other. So it
cannot see a name which another application deployed into the same cluster years ago. Both sides
deploy, and only the cluster decides which of the two a start or a message reaches. The platform's
decision 40 holds the rule for every adapter. This entry holds what it costs and what it is worth on
Camunda 8.

The cluster can be asked about a BPMN process id and a DMN decision id, because it keeps a record
of both which can be searched. The process ids of a whole workflow module go into one paged
`newProcessDefinitionSearchRequest`; the `in` form of the id filter allows that. A decision id
needs one `newDecisionDefinitionSearchRequest` each, because that filter takes an exact string and
offers neither a list nor a pattern. Both run once per workflow module while it deploys, after the
deploy command answered, and both carry the tenant where the mode uses one. Nothing else has an
index to ask. A message name, a signal name, an error code, an escalation code and a job type live
inside a model. Reading one model per definition version the cluster holds is the growth which
decision 13 forbids for a start. Where the models are read anyway, the same names come for free.
`identifiersOfVersion` does that for the versions of this application's own processes.

The feature has a second report: the names which the models of this deployment declare. It
carries a decision id as well. The id is read from the DMN file while that file is read, before
the prefix is written into it. That is the same moment at which a message name or a job type is
read from a BPMN model. So when two workflow modules of one application bring a decision with the
same id, they are named, just as two modules sharing a message name are. And a workflow module
moved between the two Camunda adapters keeps the check. The deploy command answers with the
decision ids too, but taking them from there was rejected. The cluster reports the id which IT
knows, so the plain id would have to be won back by stripping a prefix. The file which the command
sends, on the other hand, is the file this adapter just read.

Telling own from foreign definitions is a heuristic, and the check says so openly. A cluster
records no owner. What a definition carries is the resource it was deployed from, and for a
decision the decision requirements of its DMN file. A definition under a marker which this
deployment brought belongs to this application, earlier versions included, and stays silent.
Everything else is reported with `certainlyForeign` at `false`. The core turns that into a
sentence which says the line may be harmless. Two things make the marker a guess. Another
application may deploy a file of the same name. And renaming one of our own files makes our own
earlier definition look like somebody else's.

Reporting a finding which is not proven is still better than silence. The mode which needs this
check most is `none`, and a cluster without multi-tenancy leaves an application only that mode.
Staying silent because nothing can be proven would leave exactly that case unguarded. A cluster
which cannot be searched is no cluster this adapter serves (decision 20), so there is no third
answer to write.

A finding warns and never ends a boot. That is the platform's decision 40 applied: whoever holds
the name may be an application which runs correctly, and ending this boot would not help it. A
diagnostic may never end a boot anyway. Every search is wrapped twice. A failure is logged at
debug, and the questions which can still be asked are still asked. No property switches any of it
on or off. It is one search per workflow module plus one per decision, and a check which nobody
turns on is a check which nobody runs.

The same question may need the model of a version the cluster holds. That model is read the way
`tasksOfVersion` reads it, by the definition key and the XML request, and not through the picture
of decision 19. That picture answers the checks which JUDGE a model for the ids the application
declares, and it keeps what it read for as long as the application runs. The old-versions check
needs one model at a time. So the model of the version in question is kept for that version's
turn, and the next version replaces it. There is at most one model per adapter id, so a second
question about a version costs no second fetch.

`Camunda8IdentifiersTheClusterHoldsTest` holds the filters and what a finding says.
`Camunda8StartupQuestionCostTest` holds the number of searches.
`Camunda8IdentifiersTheModelsDeclareTest` holds which kinds the declared report carries, and
`Camunda8IdentifiersTheClusterHoldsIT` the foreign definition on a real cluster.

See [A name the cluster already holds](./README.md#a-name-the-cluster-already-holds).

### 26. Two workflow modules are separated by the tenant they would really be deployed to

The core refuses two BPMN processes of one application which reach the cluster under the same
identifier, and it cannot judge that alone. Under `by-adapter`, the default, nothing is prefixed.
So the core holds two equal strings, while the cluster keeps the two workflow modules apart without
trouble. The tenant keeps them apart, and knowing about tenants is Camunda 8 knowledge. So the core
asks this adapter, through `ownIsolationSeparatesWorkflowModules`. The platform's decision 41 says
why the question exists and why a question without an answer refuses.

The answer is the tenant into which each of the two modules would REALLY be deployed. It is read
through the same function which the deploy command uses. Reading `tenant-id` instead would be wrong
in both directions. When it is unset, that property means a tenant named after the workflow module,
so two modules are in two tenants. When it is set, it is used only where the mode of the module asks
for a tenant at all, and the mode can be resolved per workflow module. An answer built any other way
could call two modules separated which the very next deploy command puts into one tenant.

No tenant is a scope of its own. A module under `use-prefix` or `none` reaches the cluster in the
`<default>` tenant. So two such modules are in the SAME scope and nothing separates them. One of
them next to a module with a tenant, on the other hand, is separated. On a cluster without
multi-tenancy that is the only scope there is. Such a cluster rejects a tenant id, which is why
`by-adapter` ends the boot there, and every module left in `use-prefix` or `none` shares the one
unnamed scope. The answer there is "nothing separates them". That is the truth about that cluster,
not a degraded guess.

It costs nothing. Both tenants come from configuration which cannot change while an application
boots. So no request goes to the cluster, and the adapter keeps no answer of its own. The core
asks once per pair of workflow modules. Decision 13 limits what a start may ask, and this asks the
cluster nothing at all.

One configuration boots today and will not boot after this change: an adapter-wide `tenant-id`
with two workflow modules which bring the same BPMN process id. Both modules were deployed into
one tenant. The second definition replaced the first under that identifier, and one of the two
modules ran on a model which nobody deployed. It is now refused while the second module deploys.
The report of decision 25 warns about the same configuration where two modules share a message
name or a job type. A shared process id is the heavier case, because the cluster loses a model
instead of mixing two names up. That is why this one refuses.

`Camunda8IsolationSeparatesModulesTest` holds the pairs of tenants.
`Camunda8CollidingProcessIdsBootTest` holds the refusal and both deployment orders against the real
core. `Camunda8CollidingProcessIdsTest` holds that the core reaches this adapter on Quarkus as well.

See [Keeping workflow modules apart](./README.md#keeping-workflow-modules-apart).

### 27. A listener somebody modelled is a task where the application asked for it, and the process element reports a cancelation

Version 1 let a `@WorkflowTask` method serve a listener and announced it nowhere. The reason: a
model with application logic in a listener cannot be moved to another BPMS. Version 2 deleted the
reading side, and so nobody served such a listener any more, silently. The cluster creates a job
for it, no worker subscribes to its job type, and the workflow stops right there with no incident
and no message. Because of that silence the listener is served again, behind a key which is off by
default. And because of it, a boot which uses the key says what it costs.

A served listener is a `zeebe:taskListener` of a `zeebe:userTask`, or a `zeebe:executionListener`
of any element, **whose job type a `@WorkflowTask` method of this application names**. The job type
IS the task definition which such a method names. So nothing in the model has to be rewritten for
a method to find its listener. And a job type is a name in the cluster which anybody may subscribe
to. So a model which carries one says nothing about who serves it, but a method which names it
does. Only the route by task definition counts. `@WorkflowTask(id = ...)` names the ELEMENT, and
one element may carry a task and a listener at once.

**A listener which no method names.** In a process the application claims, such a listener is
handed to `validateTaskWiring` as a task of its own (`BpmnTaskSpec.listener`). The core asks for a
method or for the property `implemented-externally=true`, and ends the boot if neither is there.
The platform holds that rule for every adapter (decision 119 of `adapter-platform-integration`).
The property names the job type or the element id, and the element id covers every listener on the
element. A listener on a JOB element built from an element template, a connector for example,
belongs to the runtime which owns the element, as decision 23 and decision 24 say. It is named in
its WARN and does not reach the core. On a user task the template does not count: the Camunda
Modeler sets a template there to preset a form or an assignee, so it does not say that somebody
else serves the listener. A process nobody claims is not looked at at all (decision 73).

Three markers for "somebody else serves this" were weighed. First, the template: it costs nothing,
but on a user task it mixes two statements. Second, a naming rule for job types: it costs nothing
in the model, but it forces a worker we do not own to take a name we chose. And a look at the SDK,
the docs and about 230 published templates on 2026-10-07 found no format by which a connector could
be recognised either. Third, a configuration key: it stands next to the model and can go out of
date. The maintainer took the key. The core warns about a line which no deployed model needs, and
that answers the objection.

The same rule of the platform holds for the other elements of a claimed process. A user task
managed by Camunda without a method ends the boot unless the property marks it, as in version 1:
`Camunda8DeploymentAdapter` (1.9.1) wired every user task with `allowNoMethodFound=false`. Its
listener worker is opened all the same, so a marked user task is created and completed without this
application. An ad-hoc subprocess with a job of its own and without a template is refused in a
claimed process (decision 54), and the property lets it pass for a worker the application runs
itself. A marked one is handed to the core like a task, so a method next to the property is refused
there.

A job type starting with `io.vanillabp.` is not a matter for this rule. That prefix marks the
listeners which VanillaBP and its extensions write themselves, and those are served whatever the
key says. The prefix is not the whole separation either. The listeners the framework writes are
not in the model yet while the file is read. That is why a modelled listener is collected while
the file is prepared, and not while one of its processes is wired.

**The key.** It is `vanillabp.adapters.<id>.allow-listeners`, a boolean, `false` unless somebody
sets it. It resolves at adapter, workflow-module and workflow level, and the most specific
configured value wins in both directions. So a module which serves its listeners can have one
workflow which does not. There is no task level. That level is keyed by a task definition, and this
key decides whether a listener becomes a task at all. So when the key is read, there is no task
definition yet to key a level by. A value set there anyway gets one guiding warning, which names
the three levels which work, and the boot goes on. The Camunda 7 adapter reads the very same key at
the very same three levels. Two keys about what a model may contain, which reach different levels
on different adapters, would be the worse answer.

Where the key is off and a model carries a listener whose job type a method names, the boot ends
here in the adapter. Decision 23 handles a connector differently. A connector asks VanillaBP to
LEAVE an element alone, so the core's wiring validation finds a task which nothing serves and ends
the boot by itself. A listener asks VanillaBP to SERVE something. So without the key there is no
task spec and nothing for the validation to miss, and the workflow would stop at the listener's job
on the cluster. So the message comes from the adapter. It names the elements, the three levels and
the cost.

Where the key is on, a listener is from then on a task like any other. `validateTaskWiring` asks
for a `@WorkflowTask` method and ends the boot where none exists.
`validateNoUnwiredWorkflowTaskMethods` reports a method which matches no listener of any wired
process. And under `use-prefix` the listener's job type is prefixed like every other task
definition of the workflow module, because that is what it is. Version 1 wired its listeners
privately and had neither of these checks.

**What a method hears.** The event is part of a listener's identity, because one method serves one
event of one element. `@TaskEvent` tells such a method nothing. `TaskEvent.Event` has `CREATED`,
`CANCELED` and `ALL`, and the event of a listener is none of those. So the parameter receives
`CREATED` whenever the modelled listener fires, whatever moment the modeller picked for it. That is
the only value which works at all, because a method without the parameter subscribes to `CREATED`
alone. A listener which the modeller put on the cancel moment is served as `CREATED` like any
other. Two listeners of one element under ONE job type end the boot, and the message names both.
One method would serve two events, and nothing it could ask would tell it which one it is in. Two
listeners of one element under different job types are fine. A method then has to name the job
type, because `@WorkflowTask(id = ...)` names the element and cannot tell them apart. A method
declaring `@TaskId` ends the boot as well. The cluster completes a listener job the moment the
method returns, so the task can never stay open. A method throwing `TaskException` is answered
with the reason and not with an incident: the cluster is inside a transition of its own and has
no token to route.

When an interrupting boundary event or a terminating end event takes an element away, a served
method hears nothing, and the startup report says that openly. A `cancel` execution listener
beside every served listener was tried and taken out again. Camunda takes that event type only on
the process element. The 8.10 documentation lists it under the limitations of execution listeners:
"`cancel`: Supported only on the process element." A cluster of that line refuses the model with
"The 'cancel' execution listener event type is not supported for the 'serviceTask' element". A
`canceling` task listener beside a served listener of a user task would deploy on every line, but
it is not written either. Half an answer costs more than a gap which somebody named. A method would
hear `CANCELED` for a user task and nothing for the element next to it, and no rule a developer
could read would tell the two apart. The `canceling` listener VanillaBP writes for a user task of
its own is a different thing, and it delivers `CANCELED` for that task.

What a method may write into the process instance depends on the listener, in the three cases
which decision 1 lists. An execution listener on `end` completes like a task. So such a method may
change the workflow aggregate, and the process sees the change. An execution listener on `start`
and a task listener complete with nothing. So the application keeps their change, and it reaches
the cluster at the next real sync point of that workflow. No signature shows whether a method
changes the aggregate. So nothing here can detect which of the three cases a given method is in,
and that is why the report says it for every served listener.

The default is off because of what serving a listener costs. At a listener, a BPMS lets the
application in at a moment which the BPMS owns. Every BPMS draws that moment differently, so the
model stops being portable. Another BPMS has no listener at this element, and a migration of the
model stops at the method which serves it. The Process-Engine-API has no listener concept at all;
that is gap 16 and gap 17 of that adapter's `GAPS.md`. So every boot of a workflow module whose
listeners are served writes one framed WARN. It names each served listener, the key which switched
it on, what it costs and the way back. No key silences it. What it says stays true for as long as
the listener is in the model, and a key turning it off would only hide what serving one means.

**The process element reports the cancelation of an instance.** The 8.10 documentation says what
a `cancel` execution listener on the process element does: "Cancel listeners run when a process
instance is terminated. They execute sequentially after all child elements have terminated and
before the process reaches its final terminated state." So an instance canceled through the API
reports its end, with the kind `CANCELED`. The core then reports to the application, as `CANCELED`,
every task it still believes is open in that instance. Measured on 8.10.0-alpha5: the job says
`CANCEL`, `job.getKind()` is `EXECUTION_LISTENER`, the job carries the process variables including
the aggregate id, and `getProcessInstanceKey()` names the instance which is being terminated. A
called process whose parent is canceled gets a job of its own. That is why the notification
reports that key and never `getRootProcessInstanceKey()`: a derivation limited to one instance must
not reach for the root of the call tree.

Two paths are not cancelations, however they look in a model. A terminate end event and an
interrupting event subprocess both COMPLETE the instance. The end listener runs, no cancel job is
created, and the application hears `COMPLETED`. That is the cluster's view, and not a gap which
this adapter can close.

The listener and the worker are one decision. The listener holds the instance until its job is
answered. So a model with a listener which nobody serves turns a cancelation into a workflow which
never goes away and raises no incident either. That is worse than an incident, because nothing
says anything at all. So the listener is written only where this adapter also opens the worker.
The end listener follows the same rule, and that is why the two conditions stand next to each
other in `wireBpmn`.

The cancel listener is written in more places than where the end is reported. A process whose end
nobody asked about still gets it, as long as this application serves one of its tasks and the
aggregate id can be resolved. The reason is the derivation, not the notification. Such a process
can leave a task open, and a canceled instance is the only moment at which the core can tell the
application that the task is gone. Both listeners carry the SAME job type, so one worker answers
both, and `Camunda8WorkflowEndedHandler` tells them apart by `job.getListenerEventType()`. Anything
which is neither `END` nor `CANCEL` completes the job and reports nothing. The client's enum grows
within a line, and a newer cluster reports an event which this build does not know as
`UNKNOWN_ENUM_VALUE`. Reading such a job as an end would tell the application something untrue.

`Camunda8CancelListeners` is a class per release line. The writing half names
`ZeebeExecutionListenerEventType.cancel`, the reading half names `ListenerEventType.CANCEL`, and
neither exists in the client of 8.8 or 8.9. The class is public, with the retries as a parameter.
The Business Cockpit writes the same listener with retries of its own, and it must not build its
own copy (see decision 28). On the lines which do not have the construct, the boot names every BPMN
process whose cancelation is not reported for that reason. So the gap is read at startup instead of
being found in production.

**A called process.** The platform decided that a called process is not a workflow of its own
(decision 124 of `adapter-platform-integration`, story 949). Its start and its end do not reach the
application. The core tells an adapter which processes these are:
`BpmsInitiatedStartInvoker#startsAWorkflowOfItsOwn` and
`WorkflowEndedInvoker#workflowEndedHandlerExists` answer `false` for a process the application
declares only in `secondaryBpmnProcesses` and deploys a model for. So a called process gets no
start listener on its start events (decisions 48 and 49) and no worker for one. A listener nobody
needs still holds the instance until a worker answers its job, so leaving it in place would cost a
job per called instance for nothing. The start events are still read and handed to the core, which
refuses a called process with a start event the cluster fires by itself. It gets no end listener
either, because that is written only where `workflowEndedHandlerExists` says so.

The cancel listener stays where this application serves a task of the called process (release line
8.10 on). It does not report the end of a workflow there. The core reads the tasks it still
believes open in the canceled instance and reports them as canceled, and for a called process that
is all it does: no `@WorkflowEnded` method runs. A boundary event on the call activity is the usual
way such an instance is canceled, and its open tasks would otherwise never be reported.

A called process deployed by an earlier snapshot of this adapter still carries the start listener
and, where a method existed, the end listener. The core answers such a notification without effect
on the workflow. A start listener of such a model has no worker any more, so an instance of it
waits. Version 1 wrote no listener at all, so this concerns snapshots of 2.0 only.

**Two retry numbers.** A listener job of this adapter carries two retry numbers, and they answer
different questions. The number in the model is what the gateway hands back when it could not
deliver an activated job. The gateway fails such a job with the retries it had, so that it is
available again at once. With zero there is nothing to hand back. The job dies, the cluster raises
an incident, and a user task managed by Camunda stays in `CREATING` forever. Measured on line 8.9
in the nightly run 35958279261: the cluster log had `Failed to send 1 activated jobs ... to
client`, and the user task never arrived. So `creating` and `canceling` listeners are written with
one attempt left, `Camunda8TaskWiring#ONE_ATTEMPT_LEFT_FOR_A_DELIVERY_THE_GATEWAY_LOST`.

The number at the failure is what this adapter passes when a notification really failed. For a
task listener it stays at none, whatever the model says. `Camunda8UserTaskListenerHandler` fails
such a job with `Camunda8ListenerJobs.Failure.NO_RETRIES_LEFT`. So the first failure raises the
incident which an operator acts on. Nothing waits behind a task listener except the task itself. A
retry loop there would hold the element while a handler which just failed runs again. So a lost
delivery comes back, and a failed notification does not.

The cancel listener on the process keeps the retries the end listener has, which is the model's
default. A failed notification is failed with one attempt less and a backoff. The cluster hands the
job out again, and the last failure raises the incident. Measured: the cluster raises
`EXECUTION_LISTENER_NO_RETRIES` on the process element with our message and the job key. The
instance reads `ACTIVE` while the incident stands. Updating the retries and resolving the incident
hands the same job out again, until the instance reaches `TERMINATED`. Nothing else is waiting
behind this listener, and a notification which failed once on a busy database deserves a second
attempt.

`Camunda8ListenersTest` holds what is read out of a model and what a pair of listeners amounts to.
`Camunda8ListenersReportTest` holds the report of a boot together with the refusals, the listener
handed to the core, the template on a user task which does not count, and the WARN of a templated
job element. `Camunda8ModelledListenerHandlerTest` holds the three cases of what a completion
carries. `Camunda8CancelListenersTest` and `Camunda8CancelListenerDeploymentTest` hold what each
line writes and recognises. `Camunda8WorkflowEndedKindTest` holds what the handler reports for
which event. `Camunda8WorkflowCanceledIT` holds that a cluster of the 8.10 line really runs the
listener when an instance is canceled. `Camunda8CalledProcessDeploymentTest` holds that the start
events of a called process carry no listener and its process element no end listener, while the
caller gets both. `Camunda8TaskProcessingIT#aLostListenerDeliveryComesBack` fails a listener job
back as the gateway does, and reads it from the queue again.
`Camunda8ShutdownHandlingTest#aListenerFailingIsReported` holds the other half.

See [Listeners somebody modelled](./README.md#listeners-somebody-modelled) and
[The end of a workflow](./README.md#the-end-of-a-workflow).

### 28. What an extension of this adapter may use is this adapter's own API

An extension implementing `ExtensionWiringService` runs inside this adapter's deployment
pipeline: on the same BPMN files, against the same cluster, under the same shutdown. It is not an
application. It opens job workers, it serves listener jobs, and it searches for workflows by their
aggregate id. This adapter already does all of that. Until now most of it was package-private, so
the extension wrote a second copy of a rule which had been decided once.

A copy of a rule is not half a rule. It is a rule which will be wrong in one of the two places and
say nothing about it. Once the quoting of a search value drifts, the search answers nothing, and
"nothing" looks exactly like a workflow which was never started. A worker built by hand is simply
missing from what an operator reads. Worst of all, a listener job answered without the drain causes
an incident on every rolling restart which catches one in flight. None of it fails a build.

So the entry points which an extension needs are public. Their javadoc says what they promise and
what they do not, and a test holds each promise. They are public WHERE THEY ARE, not moved into a
package of their own. The javadoc is the contract, and moving them would rename what the adapter
itself uses. The exceptions are `Camunda8Workers` and `Camunda8ListenerJobs`, which
took code out of the deployment service, and two modules of test code, `test-support` and
`published-pom`. Both modules exist because a test classpath cannot read another module's test
classes. They are two and not one because `test-support` starts containers and `published-pom`
only reads a file. No module wants both, and a module which only checks its published POM should
not get Testcontainers on its test classpath.

This does not make the deployment service a public API. What an extension may use is the named
list in [What an extension of the pipeline is told](./core/README.md#what-an-extension-of-the-pipeline-is-told).
Everything else stays the adapter's own and may move with the next change. If an extension needs
something which is not on that list, the answer is to add it to the list, with its javadoc and its
test, and not to reach around it.

The platform's own rule ranks above this one: a mechanism which is the same for every BPMS belongs
to `adapter-platform-integration` and not here. This decision is about what belongs to Camunda 8
and so cannot live there.

See [What an extension of the pipeline is told](./core/README.md#what-an-extension-of-the-pipeline-is-told).

### 29. The tenant is named per workflow module, and only there

Decision 26 says the adapter answers whether its tenants keep two workflow modules apart. What
that decision did not settle is where the name comes from, and until now it came from one place:
`vanillabp.adapters.<id>.tenant-id`, one name for every workflow module of the application.

That is now the fallback, and a workflow module may carry a name of its own
(`vanillabp.workflow-modules.<module>.adapters.<id>.tenant-id`). The deciding reason is the
message which the core writes when it refuses two modules sharing a BPMN process id. It tells the
developer to give one of the two a scope of its own, and it names that key. A fix which a message
recommends has to work. Camunda 7 already read the name at that level. So the same sentence gave
good advice on one BPMS and pointed at nothing on the other. And a workflow module moved from
Camunda 7 to Camunda 8 lost the way out without anybody noticing.

Two more reasons come with it. Without the level, the adapter answers a question which it cannot
answer right. One name for every module means every pair is "separated by nothing", whatever the
application intended. And the level is where the deployment happens. VanillaBP resolves an
adapter's properties over the levels anyway, and one tenant shared by every module is the special
case, not the rule.

There is no name per workflow. The mode has one, but a tenant cannot. A tenant id is an attribute
of the deployment, and this adapter deploys once per workflow module. So two workflows of one
module cannot reach the cluster in two tenants. A key which looks honored but is ignored is worse
than a key which nobody may set. For the same reason, the check which refuses a name that the mode
would ignore now runs once per property key instead of once per adapter. The developer has to be
sent to the line they wrote.

The name is not read anywhere else. `Camunda8InstanceIdentity` asks whether two adapter ids are
the same system. That question is about an adapter id as a whole, so it keeps comparing the
adapter's own section. What one workflow module is called says nothing about the others. A cluster
without multi-tenancy is unchanged as well. The mode decides whether a tenant reaches the cluster
at all. Where it does not, a module name is dropped exactly like an adapter name. And the tenant
check still runs against the name which would really be used.

`Camunda8IsolationSeparatesModulesTest` holds the answer per configuration, including the name set
for one module. `Camunda8CollidingProcessIdsBootTest` holds the boot which ends on two modules
under one process id, and the module tenant which lets both through.
`Camunda8TenantResolutionBootTest`, with its Quarkus twin in `Camunda8JobTimeoutOverlayTest`,
holds that both platforms read the levels and report the key which the name comes from.

See [Keeping workflow modules apart](./README.md#keeping-workflow-modules-apart).

### 30. A called process is told which iterations it runs in, and the caller's model is where that is read

A call activity used for decomposition is an embedded subprocess which happens to live in another
file. So a task in it expects the same answer about its iteration as a task in the calling
process. Until now it got nothing.

The cluster already does its half. The variables of every scope a call activity sits in are copied
into the called instance, the input mappings of decision 5 included. They also survive a second
call activity below that. This is measured against `camunda/camunda:8.9.19`. So the adapter writes
no mapping at a call activity and invents no variable. The only thing it writes there is
`propagateAllParentVariables="true"`, and only where the model says nothing; there it means the
same thing today. A model which says `false` switched the caller's context off on purpose. It is
left alone, and that call activity also stays out of the graph. The values never reach the called
instance. So a chain naming them would promise something the cluster does not deliver, and the
worker would ask for names which cannot be there.

What was missing is the link between the two models. A BPMN process does not know who calls it.
So `chainOf` walked one model upwards and stopped at the process element. The call activities of
the CALLERS carry the link, and the adapter reads them once every file of a workflow module is
wired. For an element of a called process the chain is then the chain of the call site followed by
the element's own chain, outermost first. The fetch list follows without a change of its own,
because it is built from the same chain. Before this, a worker of a called process did not even
ask the cluster for values which the cluster was holding.

One call activity stays out of the graph: one which calls a process with a workflow aggregate of
its own. That is not decomposition, and the core answers it with
`workflowsShareTheWorkflowAggregate`. Such a process reports no iteration of its caller, although
the cluster still copies the values into its instance. That is the only place where the line can
honestly be drawn.

A call activity which names its process by an EXPRESSION is outside the graph as well, but it is
still told its iteration. Which process it reaches is decided per instance, so there are not two
models to link. But the levels are knowledge of the CALLER's model, complete while the module is
deployed, and the caller hands them down. Such a call activity gets one more input mapping. It
appends one entry to the process variable `vanillabpMiParents`. The entry holds the calling process
as the cluster knows it, and its levels outermost first, each with the BPMN element id, the index,
the total and the item. An enclosing level reads the variables of its own mappings from decision
5. The call activity's own round reads `loopCounter`, `count(...)` over its input collection, and
its input element. The reason is that one input mapping of an element must not depend on another
one of the same element.

Why an input mapping and not a start listener? The cluster evaluates the mapping in the same record
which creates the called instance, so there is no moment in which the variable is missing. And it
evaluates it per multi-instance instance, so each called instance gets its own round. A listener
would cost a job per instance just for bookkeeping. A start listener at the process does not see
the start variables. And no current line takes a `cancel` listener on a call activity (decision
27). A second such call activity further down appends its own entry. That is how a chain over
several processes comes about.

*Corrected on 2026-10-10: the sentence above used to say that no current line takes an element
listener on a call activity. That holds for `cancel` only. A `start` and an `end` execution listener
on a call activity deploy on `camunda/camunda:8.8.40`, `8.9.22` and `8.10.0`; a `cancel` listener
there is refused by all three. A start or end listener would still cost the job per instance named
above, so the choice of the input mapping stands.*

The values travel IN the entry; the entry does not just name them. A worker has one fetch list,
fixed at registration. So it cannot read the variable, look inside, and then ask for the names it
finds. Either it fetches everything, and the payload has no limit, or the values are in the entry.
The keys are the readable ones (`process`, `levels`, `element`, `index`, `total`, `item`). Short
keys would save about two percent of an entry. They would cost the one reason anybody looks at the
variable: somebody in Operate asking why an index is missing.

Two kinds of call activity get nothing of this. One which names its process statically is linked
model to model, and its payload grows by not one byte. One which says
`propagateAllParentVariables="false"` is left alone, because the modeller switched the caller's
context off on purpose. A mapping travels even then, so writing one would break that rule instead
of following it.

The workflow aggregate is the one question left for the runtime, and only its lookup is left.
While the module is deployed, each of its processes is compared with the caller. The ones which
share the caller's aggregate are recorded as processes which may use that chain. At runtime the
reader checks whether the pair in front of it is one of them. So a call which reaches a process
with an aggregate of its own is dropped. So is a call which crossed the boundary of a workflow
module, because there this adapter never saw the caller's model. It is dropped, not guessed at,
and a DEBUG line says which caller it was.

The reading side stays a single path. `chainOf` answers what the deployment knows, and the
handed-down levels go in front of it. `valuesOf` stays the only place which turns a level into a
`MultiInstanceValue`. A level is dropped if the called process writes its variable itself. Both
write the same variable names, and the inner scope overwrites the outer one; the graph follows the
same rule. Once the two sources are put together, nothing tells them apart.

`vanillabpMiParents` is not a protected name, and the reading side is where the adapter copes with
that. An input mapping of it is refused like every name VanillaBP writes. But a start variable, an
output mapping or the write-back of the workflow aggregate can all set it, and nothing in the
cluster objects. Measured on 2026-10-01 against `camunda/camunda:8.8.40`, `8.9.21` and
`8.10.0-rc3`: a FEEL expression which reaches into nothing becomes `null`, and the cluster writes
that `null` without an incident. `append` on a text results in `null` as well, and a foreign list
is appended to. So a value which is not a list is read as if the variable were not there. An entry
which describes no level is left out, and the real ones still count. None of it reaches a
`@WorkflowTask` as an exception. A DEBUG line names the variable and the process. A WARN would
repeat itself for every job of an application which uses the name on purpose, and it could change
nothing about a job which already ran.

The chain counts against the cluster's `MAX_MESSAGE_SIZE`, and the adapter cannot catch that
limit. It arrives as an incident on the call activity, not as a refused command, so there is
nothing to classify and nothing to refuse. It is documented instead. Three levels with 1 KB
element values cost about 3.3 KB, and the limit hits somewhere between 1000 and 2000 such entries.
That leaves room of three orders of magnitude. What gets expensive is the element VALUE, not the
depth.

`Camunda8MultiInstanceTest` holds the expression written into the model, the three call activities
which get nothing, the inherited level of a caller which travels on, and the reading side against
a foreign value. `Camunda8FetchVariablesTest` holds that every worker serving an element carries
the one name, and that the worker of a whole process does not.
`Camunda8MultiInstanceIT#theIterationCrossesACallActivityNamedByAnExpression` is what a handler
really sees. `#theApplicationMayWriteTheChainVariableItself` is what it sees once the application
took the chain away from itself. `Camunda8CallActivityVariablesCanaryIT` holds the cluster to the
three properties which all of this rests on.

Four rules keep the chain answerable where a graph is not a straight line.

A process which is called from several places gets the union over those call sites. Each path
keeps its own order. At runtime the levels of the path which did not run are simply not in the job,
and `valuesOf` already leaves them out. So a chain may name more levels than the current instance
ran in. A level which two paths share appears once, in the place the first of those paths gave it.
Two paths may nest the same two ids the other way round. Then one of the two orders is reported,
and the call sites are walked in a fixed order so the answer survives a restart. The price is the
fetch list, which asks for the variables of every call site on every activation.

Where two levels along ONE path write the same variable, the inner one wins. Both write the same
variable names and the inner scope overwrites the outer one. So the job carries the inner values,
and the chain says so. A process which calls itself does this by design. Two different element ids
can also be such a pair, if they differ only in characters which a variable name cannot hold. A
DEBUG line names both.

Two call sites may carry multi-instance elements which write the same variable but do not mean
the same thing. Then the boot ends with a message naming both processes. Two different ids can be
such a case as well: `my-task` in one caller and `my.task` in another both write
`vanillabpMiIndex_my_task`. A job of an instance reached from one of them would answer a handler
asking for the other with a round which never ran. Nobody can say what `@MultiInstanceElement` of
that id means there. Answering it wrongly would hand a handler the values of another iteration.

A call graph with a cycle stops at the first process which is already on the path, and reports the
levels collected so far. Without that rule, a process which calls itself has a chain which grows
with every round.

The refusals happen while the application starts, not while it is built. The adapter does not
check models at build time on either platform. On Quarkus the BPMN parser is initialized at run
time on purpose, so a build-time check would mean parsing every file a second time for one
question. Startup is also where every other model refusal of this adapter happens, and it is still
before anything reaches the cluster.

Take an input mapping of a name which VanillaBP writes, but with a different expression. It now
ends the boot as well, and is not ignored. It used to be left alone, which looked like the rule
that nothing the application modelled is overwritten. But it is not the same case. The handler
would read the values of whatever the modelled expression points at, while the chain promises the
iteration.

`Camunda8MultiInstanceTest` holds the chain across the boundary, the union, the shared level, the
stop of the recursion, the call activity which keeps the caller's variables out, and both refusals.
`Camunda8FetchVariablesTest` holds that the fetch list follows the chain and that a process with an
aggregate of its own gets nothing. What a handler really sees on a cluster, two call activities
deep, is `Camunda8MultiInstanceIT#theIterationCrossesTheCallActivity`.

See [Multi-instance](./README.md#multi-instance).

### 31. A release waits for every current line, and a red night opens an issue

Decision 42 replaces the first rule below: a pull request runs the matrix too, so it also waits for
every line. The two rules which this entry was written for still stand: the release gate and the
issue for a red night. The workflows cite them here.

A pull request builds the current GA line and tests it against that line's cluster. Every other
line waits for the nightly matrix. Only a pull request which moves a client pin runs the matrix
itself, because a build of line 8.9 never compiles the pin of line 8.8. See
[What CI runs](./README.md#what-ci-runs).

The rule was set in September 2026. A night had gone red in the Business Cockpit's Camunda 8
adapter, and nobody saw it for a day. The waiting is safe because nothing is released between two
releases. Every artifact which `main` produces is a snapshot. So a line which breaks in the night
has broken nothing anybody depends on, and the next morning is early enough to hear about it.
Running every line on every pull request would cost hours of cluster tests, only to learn the same
thing earlier than anybody needs it. Two rules pay for that, and this entry exists for them.

The first rule is the release. This adapter publishes one artifact per line. So a release runs only
while every current line is green in the full matrix, including its tests and its cluster. The
gate is that matrix itself, called from the release workflow before anything is built for
publication. It is not a look at what the matrix said last night. This repository compiles against
snapshots of `spi-for-java` and of `adapter-platform-integration`, and every build resolves them
with `--update-snapshots` at the moment it runs. So last night's green says that this code worked
with last night's artifacts. The release, however, publishes against today's. The commit is the
smaller half of what "the same thing" would have to mean. The matrix inside the release builds the
released commit against the artifacts it is released against. So it needs no rule about what the
same commit means after a merge. It costs the release about forty minutes. A release is started by
hand and happens a few times a year, so that is cheap. And no input switches the gate off, because
a published artifact cannot be taken back.

Which lines are asked is not decided a second time. The matrix reads the `line-*` profiles of the
POM, so it cannot fall behind the build. The release reads the same list by running that workflow.
A preview line is one of them whenever there is one, and it excludes no test (decision 47). Right
now there is none, and the next one comes with the first pre-release of 8.11 (decision 52). If an
alpha ever breaks so badly that waiting for it no longer makes sense, it is left out of the matrix,
and the gate follows.

The release workflow is `.github/workflows/deploy.yaml`. A published GitHub Release starts it. A
start by hand is a rehearsal which publishes nothing. Its first job calls `line-matrix.yaml` with
`secrets: inherit`, and every job which builds or publishes a line comes after it. So the chain from
the gate to the published artifacts is fixed, and no input can skip it. `line-matrix.yaml` declares
`workflow_call` for exactly this.

The second rule is the issue. A line which breaks in the night gets a GitHub issue, so that the
break is seen and fixed and not scrolled past. `release-lines-issue.yaml` opens it, one per line. It
writes the line, the commit, what the log said and a link to the run. If a line is still red the
next night, its existing issue gets a comment. The issue is found again by the label
`release-lines` and a title which names only the line. A line which is green again gets a comment
saying so, and the issue stays open. A green night is not a fix. A defect which loses a workflow in
one run out of four leaves its line green on three nights out of four. Closing would also mean that
the next red night opens a second issue, and one break would end up spread over several. The person
who merged the fix closes it.

There is a second kind of issue, and it belongs to a pull request, not to a line. Since decision 42
every pull request builds every line, and a red preview line does not make the pull request red. So
a preview line which breaks there would be a red cross which nobody has to act on. The change would
be merged, and the break would show up in the night a day later. `preview-line-issue.yaml` opens an
issue for that break, under the label `preview-line` and a title naming the line. While such an
issue is open, the next broken run writes a comment on it instead of opening a second one.

The two kinds are kept apart because they answer different questions. The night's issue belongs to a
line on `main`. The night after a fix builds the same branch again, so it can say that the line is
green again. The preview issue belongs to a pull request. That is the one place where a break still
has an author: the person who proposed the change which broke the line has it in front of them.
Nothing closes that issue automatically, not even with a comment. The next pull request builds
another branch, so its green preview line says nothing about this break. Whoever fixes the line
closes the issue. A preview line which breaks on `main` gets only the night's issue, so one break
never has two issues.

### 32. A served listener gets a cancel listener of VanillaBP's own, and the release line says which elements can have one

*Merged into decision 27 on 2026-10-12, before the 2.0 release.* It had VanillaBP write a cancel
listener next to every served listener. That could never be deployed and was taken out again.

### 33. A cancel listener on the element is nothing the cluster takes, so a served listener hears CREATED again

*Merged into decision 27 on 2026-10-12, before the 2.0 release.* It said why a cancel listener on
the element is nothing the cluster takes, and that a served listener hears `CREATED` again.

### 34. The process element reports the cancelation of an instance, and only where a worker answers it

*Merged into decision 27 on 2026-10-12, before the 2.0 release.* It said how the process element
reports the cancelation of an instance, and that the listener is written only where a worker answers
it.

### 35. The engine is asked before the search, and the question is a command it refuses

An extension which asks where a workflow is waits for the search of `awarenessOfWorkflow`.
Measured in September 2026 on 8.10.0-alpha5, 8.9.19 and 8.8.37, one container each on an idle
machine: the create answered after 10 ms, the engine said "this instance exists" after 16 to 19 ms,
and the search found it after 167 to 1324 ms. After a cancelation the engine said "gone" after 21
to 25 ms. The search still read ACTIVE at that point, and needed 176 to 2068 ms to change.

So where VanillaBP holds the process instance key, the engine is asked first. The key arrives
through the fourth argument of `awarenessOfWorkflow`, which the platform added for exactly this.

**The probe may shorten the YES and nothing else.** The engine forgets an instance the moment it
ends. So a key it does not hold can mean a completed workflow, a canceled one, or a key which never
existed. `awarenessOfWorkflow` has to tell `COMPLETED` from `UNKNOWN_TO_BPMS`, because only the
second lets the election move on to the next BPMS. A design which read the engine's 404 as unknown
would send every ended workflow of a migration setup to the wrong BPMS. So every answer except "the
engine holds it" falls through to the search, unchanged. Sometimes a probe cannot answer at all:
a timeout, a broken connection, a cluster which is not there. That is not a 404, and it is never
read as one. It falls through as well, and the search reports `BPMS_UNAVAILABLE`. Turning an
outage into "unknown" is the one failure this must not add.

**Which command carries the question.** Two candidates were measured. An instance which the engine
holds REFUSES both of them, and that is what makes them a question. The cluster writes no state,
and an operator never finds a modification in the history of a workflow which nobody modified.

|              the command              | on which line  |  a live instance  | a key the engine does not hold |
|---------------------------------------|----------------|-------------------|--------------------------------|
| modification with one unknown element | every line     | 400               | 404                            |
| business id assignment                | 8.10 and later | 409 INVALID_STATE | 404                            |

Stephan decided on 2026-09-19 that the probe follows the business id. Where the business id of an
instance is this adapter's own and the line has the command, the assignment is sent. Everywhere
else the modification is sent. His reason goes beyond this entry. Camunda supports three versions
at a time and VanillaBP follows, so 8.8 and 8.9 drop out in time, and the assignment becomes the
only path left. Building it now means the good path is already there when the others go.

The decision has to handle one trap, and the assignment carries it. The assignment is a question
only for an instance which ALREADY has a business id. An instance without one ACCEPTS it. Then a
probe has written a value into a field which the application may have wanted for something else,
and that cannot be undone. So the assignment is sent only where this adapter put its own id there
in the first place. `Camunda8AdapterConfiguration.writesTheBusinessIdOfAnInstance` answers that.
It is the key `aggregate-id-as-business-id` of decision 37, off by default. So an installation
which does not ask for it sends the modification on every line. The assignment carries the
workflow aggregate's id. So in the one case where it is accepted, it writes the value this adapter
would have written anyway.

`Camunda8InstanceProbe` is per release line, because `newAssignProcessInstanceBusinessIdCommand`
does not exist in the client of 8.8 or 8.9.

**Nothing is asked on a shared cluster.** An instance key is unique per CLUSTER and names no
scope; that is decision 3. The election hands the same key to every adapter of its prioritized
list. So where two `camunda8` adapter ids address one cluster, this probe would be asked about the
other one's instance. It would answer `ACTIVE` and end the election at the wrong adapter. So the
probe is skipped wherever `sharesItsCluster` is true. The search, which filters by scope, answers
as it did before. The everyday installation with one Camunda 8 adapter keeps the short path,
because there that question is false anyway.

**The reserved element id.** The modification is a question because it names an element which the
model does not have. If the id matched an element of the model by accident, that element would be
ACTIVATED instead of refused. That is a change to a running workflow which nobody asked for. So
the id is `vanillabp-existence-probe`, and every model is checked for it while it is deployed. A
file which carries it gets a warning naming the process, and no probe is sent for a workflow of
that process. Such an election waits for the search as it did before.

**And the wait which follows a no.** `WorkflowLocator.probeUntilVisible` used to wait ten seconds,
in steps of 250 ms, for an answer which is `UNKNOWN_TO_BPMS`. After a 404 from the engine, that
answer means a workflow which ended. Neither of those gets better by waiting. The long window
exists for a workflow which was just STARTED: the engine holds it, and the read model needs up to
a second to hear about it. A workflow which the engine no longer holds has been in that read model
for as long as it ran. What is still on its way there is its END. Measured in September 2026 on the
three lines, after an instance was canceled: 176 to 445 ms on 8.10.0-alpha5, 255 ms on 8.9.19 and
2068 ms on 8.8.37.

So the window is asked for per workflow. The platform has the overload
`workflowVisibilityDelay(workflowId)`, and the core calls it right after the probe, on the same
thread. So this adapter answers with the short window (`ended-workflow-visibility-timeout`, 3
seconds) for the workflow for which its own probe just got a 404. For everything else it answers
with the long one (`workflow-visibility-timeout`, 10 seconds). Only the probe above decides which
of the two it is. A probe which was skipped, one which failed, and one which found the instance
all leave the long window, because none of them says that the workflow is over.

Three seconds is ONE number for every line, although the measurements of the lines differ. The
release line reaches the runtime for messages, not for behaviour (`Camunda8ReleaseLine`). A
cluster may be newer than the line a build was compiled against. And the slowest measurement fits
under three seconds with room to spare. For the faster lines it costs two seconds in the case
which waits the window out to its end, which is a workflow that no BPMS of the election knows. An
operator who wants those two seconds back sets the key.

It is not zero. A workflow which starts and ends within a few milliseconds is gone from the engine
before the read model has heard of it at all. Answering `UNKNOWN_TO_BPMS` for a workflow which
really is `COMPLETED` sends the next operation of a migration to the wrong BPMS. That is the one
thing neither half of this entry may do.

`Camunda8EngineBeforeTheSearchTest` holds the mapping of every answer, the process the probe stays
away from, and which of the two windows each answer leaves behind. `Camunda8ErrorsTest` holds the
codes. `Camunda8EngineProbeIT` runs all of it against a cluster on every line.

See [Eventual consistency of the query API](./README.md#eventual-consistency-of-the-query-api).

### 36. A job this adapter holds from the activation to the answer leases it, and the application says whether

**Decision 67 supersedes one sentence below**: the one which says that an adapter id without a
configured cluster is not asked. Such an adapter id now uses the local cluster, opens workers
there, and is asked for `job-lease` like every other one. The rest of this entry stands.

Camunda 8.10 lets a worker lease an activation. The job then carries a token. The cluster takes the
completion, the failure and the BPMN error of that job only from whoever holds the current token.
An activation which follows an expired lock replaces the token before it.

The gain is small but real. Today, when the lock expires while the business method is still
running, the cluster hands the job out again. The method runs a second time, and both runs try to
complete. Without a lease the cluster does not care which of the two runs is the current one. The
job goes to whoever answers first. The other run is told that there is no such job (`NOT_FOUND`, a
`404`), which looks exactly like a job which is long gone. The age of the activation decides
nothing. Measured against `camunda/camunda:8.9.19` on 2026-09-21 with one pod and one worker, in
both orders: the answer which arrived first was taken both times. With a lease, the answer of the
older activation is refused whenever it arrives, with a `409` which the adapter recognises. The
workflow goes on with what the newer run wrote. The duplicate work is the same. But the result no
longer depends on which run happens to be faster, and the refusal says what happened.

**Which workers lease.** Those which hold their job from the activation to the answer: the
user-task listeners, the listeners somebody modelled, the cancel listener on the process element (decision 27), the
start events which the cluster fires itself, and the end of a workflow. A task worker leases only
where NO task definition of its job type completes asynchronously
(`workflowTaskCompletesAsynchronously`, asked per job type because a worker subscribes to a job
type). Phase two completes such a task by key, hours after the activation, from a dispatcher which
holds no token. So a leased job of an asynchronous task could never be completed at all. So one
task definition of a job type which wants to stay open is enough to switch the lease off for that
whole worker.

**Why the application decides and there is no default.** Leasing works like a ratchet, per job: it
only goes one way. No command removes a lease. Once a job has been leased, a worker of the same job
type which does not lease never sees that job again. Measured on 8.10.0-alpha5: an activation
without a lease never got the abandoned job back, while a job of the same type which nobody had
leased reached it at once. So an application rolled back to the 8.9 line leaves the jobs it leased
standing. Under the name-clash avoidance modes `use-prefix` and `none`, two adapter ids can share
a job type. There a second application which is still on 8.9 would starve on exactly those jobs.
Under the default mode the job type carries the adapter id, so that case does not come up there.

So on a line whose client can lease, the boot stops until `job-lease` says `use` or `do-not-use`.
The message explains what a lease does, and that it cannot be taken back. On 8.8 and 8.9 the same
key is accepted and ignored, and the boot says in one line that it has no effect there. An
application moves between lines with one configuration, and refusing the key would make such a
move hurt. An adapter id without a configured cluster is not asked at all, because it opens no
worker.

**An extension opens its workers the same way.** `Camunda8Workers.leaseTheActivations` is public,
next to `applyWorkerOptions`. An extension which opens listener workers on this cluster calls it
for the same reason it calls `applyWorkerOptions`. Two components which lease the same job type
with different settings cause the starvation above. And the decision belongs to the adapter's
configuration, not to the extension. It is not part of `applyWorkerOptions`, on purpose, because
only the caller knows whether its worker can ever serve a task which stays open. The Camunda 8
extension of the Business Cockpit calls it. An application which runs an extension that does not
call it leaves `job-lease` at `do-not-use`.

**A 409 of a job command means somebody else holds this activation.** Measured on 8.10.0-alpha5,
over both transports: a completion with a replaced token is refused with HTTP `409`, title
`INVALID_STATE`, and on gRPC with `FAILED_PRECONDITION`. The same goes for a failure and a BPMN
error. A completion with no token at all against a leased job gets the same pair. Every other wrong
state of a job command measured there is a `404`: a key which never existed, a job which is already
completed, and a token handed to a job which has no lease.

The code alone cannot say WHICH wrong state the cluster means. The REST specification says only
"the job is in the wrong state", and the cluster is free to reword the text around the code
(decision 16). So the rule is the honest one: a 409 of a job command means another activation holds
this job, whatever the reason. `Camunda8CommandRetry` stops on it instead of repeating until the
job's deadline. It stops without failing the job. The newer run holds the job and answers it, and
failing the job would take it away from that run. The line it writes says that the run converged
with a redelivery, and that is what happened.

**What a lease does to the drain.** A shutdown leaves a job to its lock and does not fail it. A
leased job is still leased when the next pod activates it. That activation gets a fresh token and
completes with it, measured on 8.10.0-alpha5. So this branch needs nothing of its own.

**What needs no token.** The update commands. An `UpdateJobTimeout` of a leased job is accepted
without a token, even from a client which never activated that job, measured over both transports.
The probe of `Camunda8OpenTaskProbe`, the lock renewal of an open asynchronous task and the
phase-one check all rest on that.

`Camunda8JobLease` is per release line, because `withLease` and `withJobLeaseToken` do not exist in
the client of 8.8 or 8.9. `Camunda8JobLeaseTest` is per line too. It holds what the client can do
and what the boot says about the key there. `Camunda8JobLeaseDeploymentTest` holds which worker
leases and which does not. `Camunda8ErrorsTest` and `Camunda8CommandRetryTest` hold the codes and
what the retry makes of them. `Camunda8JobLeaseIT` runs the whole case against a cluster of the
8.10 line: a handler slower than its lock, the older answer refused, the newer one accepted.

See [The lease of an activation](./README.md#the-lease-of-an-activation).

### 37. The workflow aggregate's id may be the business id of an instance, and only for the eye

Camunda 7 keeps the workflow aggregate's id in its business key. Camunda 8 got a field of the same
kind with 8.9, and until now this adapter wrote nothing into it. So a person looking at a workflow
in Operate had to open the variables to see which aggregate it belongs to.

`vanillabp.adapters.<id>.aggregate-id-as-business-id` writes it there. Stephan decided on
2026-09-19 that it is a key and not a default, and that VanillaBP never reads the value back. The
reason for writing it is the human one. The reason for leaving it off is that the field belongs to
the application until this adapter takes it. An assignment happens once and cannot be undone. So
an installation which wants its own value there would lose it without ever having been asked.

Three things follow from "only for the eye", and each of them makes the feature smaller.

**Nothing reads it.** The aggregate's id travels as a process variable at every start, and every
part of this adapter keeps reading that variable. A search by business id uses the same index as
every other search, so the field is no shortcut to a workflow either.

**A value which does not fit is CUT.** The cluster refuses a business id longer than 256
characters with "The provided businessId exceeds the limit of 256 characters". The aggregate ids of
VanillaBP have no length limit anywhere. The start of a workflow is the worst place for a refusal,
and nothing depends on reading the value back. So a longer id is truncated. The boot says so once
per adapter id, and nothing is written per started workflow: a line per start would drown the log
and say the same thing every time. The cluster also refuses an empty id and a single blank ("No
businessId provided"), so nothing is sent for those.

**It is written at creation and never assigned.** So a workflow which somebody else started keeps
whatever business id it has. That is all of "an application which sets its own keeps it": there is
no moment at which this adapter would overwrite one.

**The 8.8 line has no such field.** Its cluster answers a create which carries one with `400`,
"Request property [businessId] cannot be parsed". There the key is accepted and nothing is sent,
and one line at the boot says so. An application moves between lines with one configuration.

**What the election's probe does with it.** Decision 35 left one branch waiting for this entry.
Where this key is on and the line has the assignment command, the probe of
`Camunda8ProcessService#awarenessOfWorkflow` sends the business id assignment. An instance which
already carries this adapter's id refuses it with `409`, so it is a question which writes nothing.
Everywhere else the probe sends the process instance modification, as before. Both commands carry
the value which `Camunda8AdapterConfiguration.businessIdOf` answers, truncation included. A probe
with a different string than the one the create wrote would be accepted where it has to be refused.

**What this refines in decision 1.** That entry opens with "Camunda 8 has no business key". When it
was written, that was true of every cluster this adapter had met. It is the reason the aggregate's
id travels as a process variable, and that reason stands: VanillaBP still finds a workflow by that
variable, on every line. The only change is that the cluster now has a second place for the same
value, for a person to read.

`Camunda8BusinessId` is per release line, because `CreateProcessInstanceCommandStep3.businessId`
does not exist in the client of 8.8. `Camunda8BusinessIdTest` is per line too. It holds what
reaches the command and what the boot says there. `Camunda8InstanceProbeTest` of the 8.10 line
holds which of the two commands the probe sends once the key is on.

See [Sharing the workflow aggregate](./README.md#sharing-the-workflow-aggregate).

### 38. A probe of a user task is recognised by its action AND its empty change list

The check which looks at the other open tasks of a workflow can ask about a user task managed by
Camunda. The command which asks is an `UpdateUserTask` which carries nothing but an audit action.
It answers from the partition, not from the index: `204` for a task which is open, and `404` for
one which is gone. How long the `204` takes depends on the model. Measured on 2026-09-19 against
8.9.19 and 8.10.0-alpha5, one container each on an idle machine: 5 to 21 milliseconds for a user
task with no `updating` listener modelled. With a modelled listener which a worker answered: 106 to
111 milliseconds on 8.9.19, and 15 to 78 milliseconds on 8.10.0-alpha5.

The command has a side effect which nobody would guess from the documentation of the endpoint. It
fires a modelled `updating` task listener, although it changes no attribute at all, measured on
both lines. A listener job which is not answered holds the task in state `UPDATING` for fifteen
seconds. For as long as that lasts, assigning or completing the task is refused with `409`. So a
probe which nobody serves takes a task out of service.

**The mark.** `ActivatedJob.getUserTask().getAction()` returns the action of the probe, and
`getChangedAttributes()` is empty because the update changed nothing. Both methods exist on
8.8.37, 8.9.19 and 8.10.0-alpha5. `Camunda8ModelledListenerHandler` completes a job which carries
both at once, and no `@WorkflowTask` method runs for it.

The decision is to use both halves, not one. The action is a string which anybody may send. So
reading it alone would let a foreign update silence a listener which the application wrote. An
empty change list alone does not prove it was this adapter either: another party may send an empty
update for reasons of its own. Together they are specific enough. And a false positive costs a
listener notification, not a task.

**What the mark cannot reach.** An `updating` listener whose job type this application does not
serve belongs to a foreign worker or to a connector runtime. For that worker the probe is a real
update. The adapter knows at deployment which listeners it serves. So the check of the other open
tasks sends no probe at all for such an element, and answers "cannot say" for its tasks. That is
one rule, not a second mechanism next to the mark. Since decision 54 a model reaches that case in
only two ways: a listener in a process which no `@WorkflowService` class claims, and a listener on
an element which carries a `zeebe:modelerTemplate`. Anywhere else, a listener which no method
serves ends the boot before any probe could be sent. This exception exists for the template case,
because there somebody else really does answer the job.

The rule holds for that CHECK, not for the two probes which ask about a task that somebody named.
`awarenessOfUserTask` and the pre-commit check of `completeUserTask` send the same empty update for
the one task a caller is about to work on. They send it whatever listener sits there. A caller
which holds that task in its hands is the party which may wait fifteen seconds for an answer, and
refusing to ask would leave it with no answer at all. The check above is the opposite case: an
uninvited question about a task nobody asked about. That one has to stay out of the way. Both
spellings of the event count, `update` as well as `updating`. The model API has the old name next
to the new one on every line, and a model written with it produces the same job.

**A probe of a served listener waits for this application.** The empty update does not return
until the listener job it fired has been answered, and the worker which answers it belongs to this
application. So the probe holds the execution slot it runs on, while a second slot serves the job.
So an application with a single slot (`worker-threads: 1`) and a served `updating` listener waits
out the cluster's fifteen seconds and reads `504`, which is "cannot say". No task is harmed and
nothing is derived, but the wake-up is slow. The everyday four slots have room for both.

**Off by default.** `vanillabp.adapters.<id>.probe-open-user-tasks` is `false`. The question costs
one command per user task, and most applications never need it. VanillaBP writes a `canceling`
task listener next to every user task it manages, and the cluster delivers `CANCELED` straight from
there. Without the key, the question one level up still runs: the engine is asked, once per
workflow, whether it still holds the process instance. A `404` there answers every record of that
workflow at once.

**What an answer means.** `404` is gone. `409` is a task which the cluster has: it was measured for
a task standing in `UPDATING`, and for a task whose `updating` listener denied the update. `400` is
"cannot say". No run has ever produced one for a user task, and a guess in that direction would
cancel an open task. Everything else is "cannot say" as well.

See [Task cancellation arrives at the next wake-up](./README.md#task-cancellation-arrives-at-the-next-wake-up-not-at-the-moment).

### 39. A line hands an application its own client and nothing else of ours

Decision 11 releases one artifact per Camunda minor version, so that an application can stay on
the cluster it has. The artifact keeps that promise through the POM it publishes, because that POM
puts a client on the application's classpath. Until September 2026 it did not keep the promise.
The published POM was a copy of the source POM with `${revision}` filled in. The client version
stood in a property which the line profile sets, and a consumer activates none of our profiles. So
every line published a POM which asked for the client of the current GA line. An application on
the 8.8 line got the 8.9 client, and an 8.8 cluster rejects the job activations of that client. An
application on the preview line got a client older than the code it runs.

**Every version is written into the published POM.** The flatten plugin runs in its `oss` mode. The
published POM of each module names its dependencies with resolved versions. It has no parent, no
`dependencyManagement`, no properties and no profiles. Nothing in it depends on anything which a
consumer would have to activate or inherit. `Camunda8PublishedPomTest` reads that POM and compares
the client version in it with the client the build was compiled against, on every line.

**What one line needs is no business of another line's users.** The rule is wider than the client,
and it is the reason the parent is dropped and not only corrected. What this repository pins for
its own build is chosen for the newest line: the protobuf runtime, Micrometer, Testcontainers,
Lombok, and the Spring Boot and Quarkus versions it compiles against. None of it may reach an
application through us. If a user is supposed to have a pin, the README states it and the user
pins it. Every other pin stops at our own classpath.

**The protobuf pin stays one number.** That pin was the question which started this, and the
measurement gave the opposite answer. The pin never reaches an application at all. So a value per
line would change nothing a user runs, and would only lower what the older lines are tested
against. It is one number, at least the gencode version of the newest pinned client, and
`Camunda8ProtobufPinTest` checks it against the client of the line being built. What an
application really resolves is a table in the README, together with the one case where an
application has to pin protobuf itself.

See [Release lines](./README.md#release-lines).

### 40. An artifact says where it comes from and nothing about where we deploy

Decision 39 made the published POM a complete description of what an application resolves, and
it needs no other file. What that POM says about the artifact itself was written for a maintainer's
eye. Nobody else ever read it, because it existed only once, in the parent. Now every published
POM carries its own resolved copy, so every artifact prints it.

**Every artifact names the repository root.** Maven hands a child the parent's `url` and all three
`scm` elements with the child's own name appended. So each artifact advertised an address like
`https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/camunda8-adapter`, which is
no page. The four `inherit.append.path` attributes in the parent POM turn the appending off. The
same address for every artifact is on purpose. A link into a module directory breaks when the
module is renamed or moved, and a reader who wants the module finds it from the root in one click.

**`distributionManagement` leaves the published POM.** Where we deploy is nothing a user of the
artifact can use or act on. On an artifact on Maven Central it would point a reader at our GitHub
Packages registry. It stays in the source POM, because the deploy reads it from there, and the
flatten plugin removes it from what we publish.

**A deploy is every module or none.** The POMs of one build belong together. A `-pl` deploy
publishes a mixture in which each half is valid on its own, and nobody notices until a user
resolves the artifact. The publish workflow deploys the whole reactor. So `CONTRIBUTING.md` states
the rule for the case where a person runs the deploy by hand.

`Camunda8PublishedPomTest` reads the addresses and the absence back out of the published POM.

### 41. Two numbers answer two questions: the one in the model and the one at the failure

*Merged into decision 27 on 2026-10-12, before the 2.0 release.* It said which two retry numbers a
listener job of this adapter carries: the one in the model and the one at the failure.

### 42. A pull request waits for every line, the same as the night does

Decision 31 let a pull request build only the current GA line and left the other lines to the
nightly matrix. A pull request now runs the matrix, so it builds and tests every line against that
line's cluster. `checks.yaml` calls `line-matrix.yaml` without a condition, and the check
`line-pins-verified` reads the GA lines of it.

The old rule had a cost which nobody saw while the lines stayed quiet. A pull request built line
8.9 and was green. In the night the same code failed three tests on two other lines. By then,
whoever had written it was a day further on. That happened here. Waiting for the night also means
reading the night. And a red night belongs to whoever merged the day before, which is a hand-over
no rule can make.

The old rule paid off in run time, and the way we work changed since then. The gate came when every
story was its own pull request, and each of them would have paid for the matrix. Stories now travel
as a wave in one pull request. So the matrix runs once per wave, and the waves do not follow each
other back to back. Measured on this repository in September 2026: a pull request took about twenty
minutes, and the full matrix takes about forty. So a wave waits about twice as long, and in return
it gets the answer the night used to give.

One duty comes with it, the one the night already had. Whoever opened the wave watches it. A line
which goes red is looked at while the other lines are still running.

The preview line is built on every pull request, and it decides none of them. Both checks count
only the GA lines: `lines-verified` inside the matrix and `line-pins-verified` outside it. So a red
preview line leaves both green, while a red GA line makes them fail. The reason is what that line
is: an alpha which changes under us. A defect of the cluster it pins must not stop somebody who
needs a fix on a released line. We owe the alpha the work afterwards, and the issue which
`preview-line-issue.yaml` opens is where that is written down. This was decided in September 2026.
The first wave which ran the full matrix had been held up by a cluster defect which the adapter
cannot fix.

Nothing about a red preview line is hidden because of this. The job of that line is red where it
broke. `ga-lines` writes a table into the job summary which says which lines decided the run and
which one was only reported. And a release still waits for every line.

Counting the lines also keeps silence from looking like success. The old check read the result of
the matrix as a whole. That cannot tell a red GA line from a red alpha, and a line which never ran
or was cancelled would have gone unnoticed in it. A line without a result is a line which did not
build.

The rest of decision 31 stands. A release still runs the matrix itself before it publishes
anything, and a line which breaks in the night still gets its issue. The release gate is the call
of the whole matrix, not the name `lines-verified`. So the release keeps waiting for the preview
line.

*Until the first pre-release of the next release there is no preview line, see decision 52.*

### 43. On the preview line no test creates a Camunda-managed user task on a shared cluster

Superseded by decision 47. The preview line now runs on `8.10.0-rc1`, and that cluster hands out a
`creating` job. So the tag, the rule and the exclusions went together, as the last paragraph of
this entry said they would. The entry stays because it holds the measurement.

The REST gateway of `8.10.0-alpha5` loses a whole activate-jobs batch when it meets a `creating`
or a `canceling` task-listener job; that is `camunda/camunda#58193`. At first its cost was read as
a timeout in the test which waited for the job, and the tag `user-task-listener-jobs` was written
for exactly those tests. The tag now covers every test which creates a user task managed by
Camunda on a shared cluster, because the waiting is the smaller half of the cost.

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
is never handed out. And it cannot be cancelled away, because the cancellation waits for a second
job which is never handed out either. The engine's `404` makes the cleanup of the next class
believe that the instance is gone, while it is not.

A cluster per class was the other candidate, and it is not the answer. The Business Cockpit's
Camunda 8 tests use one cluster per class, and they met the same defect five times in their run
`36084108673`, caused by instances of the class itself. A cluster of its own limits how long the
damage lasts, but it does not prevent it. Our shared cluster makes the damage reach further:
twenty-six lost activations in run `36099613510`, from the first user task to the end of the
build, and `Camunda8RestartDeliveryIT` red. But the shared cluster is not what causes it.

On that line the tag costs a handful of tests which prove nothing there anyway, because every one
of them needs a job which the line does not hand out. A test which really needs a user task on that
line brings a cluster of its own, which Testcontainers throws away with the class.
`Camunda8GrpcTransportIT` does that already, for a different reason.

Two things enforce it. `TestOnTheSharedCluster` ends what an earlier class left, instead of
stopping at the engine's `404`. It answers the listener jobs which a cancellation waits for. If a
user task is still between two states afterwards, it fails the class with a sentence which names
the class before. And every test which creates such a task carries the tag, which the `line-8.10`
profile excludes in both Surefire and Failsafe.

The same measurement explains a number which decision 35 never claimed. On line 8.9 an instance
without a user task left the search 0,43 to 0,77 seconds after the cancellation. One with a user
task left it after 0,81 to 1,03 seconds, as long as the `canceling` listener job was answered at
once. With that job left unanswered, the instance was still reported as running 130 seconds later.
It ended 0,5 seconds after a worker finally took the job. The minute which somebody once measured
was an instance which was still alive, not a search lagging behind the engine. And
`Camunda8ProcessService#awarenessOfWorkflow` was right to report `ACTIVE` for it.

When a cluster of this line hands out a `creating` job, the tag, this rule and the exclusions in
the `line-8.10` profile go together.

### 44. A `404` to a cancellation means the command is gone, not the instance

Cancelling a process instance answers `204`, and every further cancellation of the same instance
answers `404`. That reads like "the instance no longer exists". Everything around cancellation
used to read it that way.

That reading is wrong while the instance holds a user task which the cluster manages. Such an
instance terminates only after the `canceling` listener job of that task is answered. While no
application of ours is running, nobody answers it. Measured on 2026-09-25 against `8.10.0-alpha5`
and against `8.9.21`: the instance was still alive 130 seconds after the cancellation, and it
ended 0.52 seconds after a worker finally took the job. Until then it can hand a job to whichever
application asks next.

So the `404` says only one thing: the engine will not take a second cancellation for this key. It
says nothing about whether the instance still runs, and nothing about whether it still hands out
jobs. The search says that, or the absence of a user task standing between two of its states.

Our own cleanup does not rest on the wrong reading, and that was worth checking.
`TestOnTheSharedCluster#endWhateverAnEarlierClassLeftRunning` loops until two things are true at
once: no instance of the class before answers the cancellation any more, and no user task is left
between two states. While it loops, it answers the listener jobs which a cancellation waits for.
Only the sentences which describe it said "the `404` is how the engine says it has let go", and
that is the half which is not true.

When this entry was written, one question was open: what does the existence probe of
`Camunda8ProcessService` answer for an instance in that window? The probe is a refused
modification, not a cancellation, and the texts around it say "the engine forgets an instance the
moment it ends". That has been measured since. `Camunda8ProbeWhileAnInstanceTerminatesIT` holds
the window open. On 2026-09-27, against `8.8.39`, `8.9.21` and `8.10.0-rc1`, the existence probe
answered `400 INVALID_ARGUMENT` inside the window, and the empty `UpdateUserTask` of the task probe
answered `409`. Both turned to `404` only once the listener job was answered. So neither probe
reads a terminating instance as gone.

Decision 43 has the same measurement from the other side and needs no change. Decision 35 says the
engine forgets an instance the moment it ends, and decision 38 says a `404` means gone. Both are
about the PROBE and not about a cancellation. The measurement above shows that both hold, so
neither changes.

### 45. A job for a workflow this application does not own keeps its incident

Two applications may share a cluster, and a listener job of one may reach the other. Then the job
is failed, and the cluster raises an incident. The retries are not raised to keep that quiet. The
incident carries the core's message, which explains the situation instead of blaming whoever reads
it. The core's half of this is decision 99 of `adapter-platform-integration`.

Stephan decided this on 2026-09-27. The measurement behind it was made in story 605. It is kept
below, because the wording of the decision only makes sense next to it.

**What was measured.** A worker subscribes to a job type for the whole cluster. So where two
applications deploy a BPMN process of the same name, the cluster hands each job to whichever of
them asks first. The same shape occurs with one application: a process instance left behind by an
earlier run holds a `canceling` listener job, and the next worker of that job type gets it.

The adapter reads the workflow aggregate id from the job and hands the delivery to the core. The
core loads the aggregate, finds nothing, and throws. The user-task listener path fails such a job
with `NO_RETRIES_LEFT`. So the cluster raises an incident at the FIRST delivery, and the user task
stays in `CREATING` or `CANCELING` until an operator resolves it. No delivery record is written,
because the transaction rolled back.

So the job is not consumed quietly. But it was consumed under the wrong name. The message read
`No workflow aggregate of class '%s' having the ID '%s' was found ... it must not be deleted while
the workflow is active`. That tells a developer they deleted an aggregate, and says nothing about
the job belonging somewhere else.

**What was decided.** The incident stays. It is what a reader should see, and it is cheap: the work
is still in the cluster, so the application which owns the workflow loses nothing. Raising the
retries, so that the delivery costs that application nothing, would mean that nobody ever finds out
that two applications share a cluster and take each other's work.

The message changes, and it changes in the core, because the aggregate is looked up there and
every adapter passes through there. It is now `DeliveryOfAnUnknownWorkflowException`. It names
everything the cluster said about the delivery, so the reader can look the workflow up on the other
side. And it names both situations it can be: a workflow which another application owns, and a
workflow aggregate deleted here while its workflow was still running. Nothing in the code can tell
the two apart, so nothing in the message claims to.

*Note 2026-10-12: this no longer holds everywhere. Since decision 108 of
`adapter-platform-integration` the delivery log keeps a start row for every workflow this
application starts, and the core reads it before it words the message (decision 99 there). The
sentence above holds only where no start row can be read.*

Two options from the earlier draft of this entry were not taken. A new inbound outcome meaning "not
mine" was not introduced, because nothing is handed back. And the case is not left undocumented,
which the third option would have meant.

**What this adapter does with it.** `Camunda8ListenerJobs` fails the job as it fails any other. So
the retries which the caller chose apply unchanged, and `Camunda8Errors#incidentMessage` puts the
type and the message into the incident. Only the log line is different. A refusal of this kind is
reported without its stack trace, because the message is the whole finding. A trace would only
name the line of the core which read the database. Every other failure keeps its trace.

Nothing is counted here. The core counts the case as `vanillabp.task.deliveries.unknown.workflow`,
once per refusal, whichever adapter delivered it.

**What is not solved.** A job of a workflow which NOBODY owns stays where it is. A left-behind
instance produces exactly that. The job raises its incident, and an operator removes the instance.
That is the honest end: there is no application to hand it to.

`Camunda8ListenerJobsTest` holds the incident such a job leaves behind.

### 46. A slot nobody gives back is measured and named, and the adapter does not end it

A handler is application code and may block for as long as it likes. While it does, it holds one
execution slot. A worker of this adapter id only asks the cluster for work while a slot is free. So
a handler which never returns costs one slot forever, and losing every slot makes the whole adapter
id go quiet. Nothing about that state is visible. The connection is up, the health check is green,
and no worker reports anything, because no worker does anything.

Camunda confirmed the mechanism behind it for us. Polling and handler execution run on separate
executors. But the routine which restarts the polling of a worker runs on the thread which just
finished a job. A thread which never finishes never restarts anything. Camunda's remedy for that is
to raise `numJobWorkerExecutionThreads`, and it does not help us. This adapter hands the client an
executor of its own, so the client builds no pool which could be raised. Our number is
`worker-threads`, and it only decides how many stuck handlers it takes: four by default instead of
one.

**The adapter reports the state and leaves the handler alone.** Ending the handler would mean
interrupting a thread which is inside application code. That thread has an open transaction and an
open database connection, and there is no way to know whether its work so far may be abandoned. An
interrupt there would replace the first defect with a second one. The job itself is already safe
without us. Its lock ran out, and the cluster handed it to somebody else. Where the line has a
lease, the late answer of the stuck run is recognised and dropped. What is missing is not a repair,
it is somebody knowing. So the adapter measures the state and reports it, and the operator decides.

A handler counts as overdue after the job timeout of its own task, not after an invented duration.
That number is already configured. It is already resolved per task over the four configuration
levels. And it is the exact point where the handler's claim on its job ended. A task which may run
long is measured against its own value, and does not have to be excluded from anything.

**The alarm is: every slot is held, and one of the holders is overdue.** One slot held for a long
time is an application doing slow work, and that is nobody's emergency. When every slot is held and
the oldest holder has lost its lock, this adapter id stops asking for work. That is a different
event. The two gauges behind it are `vanillabp.camunda8.execution.slots.in.use` compared with
`vanillabp.camunda8.execution.slots.configured`, and `vanillabp.camunda8.execution.overdue`.
`vanillabp.camunda8.execution.oldest.seconds` is the third number. It says the same thing earlier,
because a handler which is not coming back only gets older.

The watch runs on a thread of its own. It has to answer while every thread which runs this
adapter's handlers is blocked; that is the whole point. So it cannot share a thread with them. It is
one daemon thread per adapter id, which looks at a handful of map entries every ten seconds.

The message carries the stack of each overdue handler. The drain already knows which thread each
running handler is on, so the top frames cost nothing to take. They make the difference between
knowing that something hangs and knowing what hangs. In practice they name the remote call which
has no time limit of its own. Twenty frames, because everything below that is the client and the
executor and looks the same every time.

It is said once. The state lasts until somebody acts, and a WARN every ten seconds would bury the
line which said it first. One WARN when it begins, one INFO when a slot comes free again. Alerts are
built on the gauges; the log is what the operator reads afterwards.

`Camunda8SlotWatch` is where the watch lives.

### 47. The preview line runs on the release candidate, and it excludes nothing any more

Superseded by decision 52. Camunda released `8.10.0` on 2026-09-29. So the 8.10 line is the
current GA line, and the pin is that release, not a candidate. There is no preview line until 8.11
reaches its first pre-release. The entry stays because it holds what moving to the candidate cost.

This supersedes decision 43, which stays where it is. Decision 43 kept every test which creates a
user task managed by Camunda off the preview line. The reason was `camunda/camunda#58193`. The REST
gateway of `8.10.0-alpha5` lost a whole activate-jobs batch when it met a task-listener job whose
event carried no user task action; that is `creating` and `canceling`. Camunda closed the issue a
day after that alpha was built, so the alpha was a day too old for its own fix.

The pin now reads `8.10.0-rc1`, and the fix is in it. Measured on 2026-09-25 against
`camunda/camunda:8.10.0-rc1`, with the exclusions taken out of the `line-8.10` profile:
`Camunda8UserTaskStillCreatingIT` and `Camunda8UserTaskProbeIT` both pass, and the whole line runs
with the tagged tests back in it. Decision 43 said the tag, the rule and the exclusions go together
once a cluster of this line hands out a `creating` job. It does, so they went.

`TestOnTheSharedCluster` keeps what decision 43 built into it. Ending what an earlier class left is
not about one alpha. It answers the listener jobs which a cancellation waits for. If a user task is
still between two states afterwards, it fails the class with a sentence which names the class
before. A shared cluster needs that on every line.

Moving to the candidate cost two changes which nobody had to make for an alpha update, and both are
worth writing down.

The client renamed the lease API. `getLeaseToken()` became `getJobLeaseToken()`, and
`withLeaseToken(...)` became `withJobLeaseToken(...)`. That touched the line-specific source of the
8.10 line and nothing else. The cluster also got stricter about the token. `8.10.0-rc1` refuses the
completion of a leased job which carries no token, with `409 INVALID_STATE` and `a matching lease
token must be provided because the job is currently leased`. On the alpha the same answer went
through. The adapter always sent the token, so nothing in the adapter had to change. A test which
completed a leased job with the raw client did have to change.

And the protobuf pin moved from `4.36.0` to `4.36.2`. That is the gencode version of the new
client, and protobuf refuses a runtime older than the code linked against it.
`Camunda8ProtobufPinTest` checks this, and an application on the preview line pins the same number
in its own `dependencyManagement`.

SUPPORT-34723 is the other defect this pin was moved for. Up to `8.10.0-alpha5` the client planned
its next poll only while no job of that worker was in a handler. So a worker stopped asking after
the first empty poll. Camunda says the fix is `#59633` and that it is in `8.10.0-rc1`, and the run
showed it from the other side. `Camunda8JobLeaseIT` blocks a handler and lets the job's lock run
out. On the alpha that job never came back to the worker holding it. On the candidate it came back
four times, once per free slot. So the worker does keep asking while one of its jobs is in a
handler.

That has a second consequence, and it cost two red runs before it was understood. The adapter hands
the client an executor as wide as `worker-threads`, and the client answers its own requests on it.
So a handler which occupies every slot also stops the client from completing a request of the SAME
application, whatever timeout that request was given. Measured on 2026-09-25: the test's activation
died of its socket timeout at 3000 ms with a window of two seconds, and at 6000 ms with the
module's five. Reducing the slots to one makes it worse, not better. `Camunda8JobLeaseIT` now sends
its second activation with a client of its own, as `Camunda8TaskListenerVariablesCanaryIT` does.
It also stopped counting the handler's runs, because how often the cluster offers an expired job is
the cluster's business. Four runs in a row after that: 32.5, 38.2, 38.3 and 50.6 seconds.

What is still missing is a test which triggers the defect directly instead of meeting it on the
side. That is story 644.

See [Release lines](./README.md#release-lines).

### 48. An event subprocess does not start a workflow

A workflow starts when the BPMS creates an instance of a BPMN process. At that moment there is no
workflow aggregate, so VanillaBP asks the application to build one. So every start event which the
cluster fires by itself needs a `@WorkflowStartedByBpms` method. That is what this adapter means by
the start of a workflow.

The start event of an event subprocess is not such a start. The cluster fires it inside a workflow
which is already running, and that workflow has the aggregate it was started with. Building one
here would leave one workflow with two aggregates. And the application would hear that a workflow
started, long after it did.

So this adapter counts a start event only where the process itself holds it. Walking up to the
enclosing process is not enough, because that walk gives the same answer for a start event at any
depth of a model. Once every start of a workflow had to be served by a method, no model with an
event subprocess booted any more.

The rule covers the start event of a plain embedded subprocess as well. BPMN allows only a none
start event there, and this adapter reports no none start event anyway. So nothing changes for such
a model. One rule for every nesting is shorter than two.

One walk reads the start events and adds the execution listener which tells VanillaBP about such a
start. So both follow the rule at once. The core hears about the start events of the process, and
the model reaches the cluster with a listener on those and on nothing else.

`Camunda8EventSubprocessStartsNoWorkflowTest` holds both halves without a cluster, and
`Camunda8EventSubprocessIT` runs a model whose event subprocess takes a waiting workflow over.

### 49. The cluster holds the workflow's name in the variable named after the aggregate's id

*Decision 27 leaves a called process out: it gets no start listener.*

The id of a workflow is the id of its workflow aggregate. The application assigns it in the
`@WorkflowStartedByBpms` method, and nobody else does. Camunda 8 keeps no business key, so this
adapter keeps that id in a PROCESS VARIABLE. The rule itself and what the core does with it are
decision 69 of `adapter-platform-integration`.

*Note 2026-10-12: since 8.9 Camunda 8 has a business id field. Decision 37 may write the
aggregate's id there, for the eye only. VanillaBP never reads it back, so the variable stays the
place where the id lives.*

**The name of the variable.** The variable is called after the workflow aggregate's id attribute: a
`Ride` whose `@Id` field is `rideId` names its workflow in the variable `rideId`.

That name is public. It stands in every process record of the cluster. Every operator sees it, and
every model can read it. So it was worth choosing it on purpose instead of just inheriting it, and
three alternatives were weighed.

A fixed name such as `vanillaBpWorkflowId` would be the same everywhere. That reads well in a
cluster which serves several applications. It was turned down because the model would then have two
names for one thing. `${rideId}` is what a modeller writes in an expression, in a call activity's
input mapping and in a message correlation key. And version 1 of VanillaBP put the aggregate there
under exactly that name. A second name would have to be kept in step with the first forever.

A name derived from the BPMN process id would keep two processes of one model apart. It was turned
down because they are not apart. A call activity passes the aggregate's id down, and both processes
read it under the name the aggregate gave it.

So it is the attribute's name. This adapter already used that name everywhere else: in
`Camunda8JobHandler`, in the completion of every task, and in the variables a start writes. Story
653 changes only one thing: the start listener now READS the variable before it decides.

**Why this works at the moment the listener runs.** A workflow which the application starts is
created with that variable, because `Camunda8ProcessService` writes it into the create command. The
start execution listener of the start event runs after the instance exists, and before anything
else of the process runs. Its job fetches every variable, so the listener sees the name if there is
one. A workflow which somebody started without going through VanillaBP has no such variable, and
exactly that tells the two apart.

**Only a process this application serves gets the listener.** The cluster runs whatever was
deployed to it. A workflow module may deploy a BPMN process which no workflow service of this
application claims. The listener holds the instance until its job is answered. For such a process
the core has no workflow service to answer with. The start would fail, the retries would run out,
and the instance would sit in an incident it never had before. So the wiring asks first, with the
same question the end and cancel listeners ask: does this process have a name for the workflow
aggregate id? A process without one is left exactly as it was. Since decision 73 the question is
`WorkflowTaskWiring.isClaimedByAWorkflowService`, asked once at the top of `wireBpmn`. The rest of
the model of such a process is left as it was too.

**What the listener on every start event costs.** The listener is written into the model at
deployment, so the cost is paid twice: once in the model, and once per started workflow.

In the model it is one `zeebe:executionListeners` element with one `zeebe:executionListener` child
per start event. Measured on a process with four start events, that is 294 characters of deployed
XML per start event. Most of it is the extension-element wrapper. The rest is the job type, which
contains the process id and the element id. So a model with ten start events grows by about three
kilobytes, against the four megabytes which a Camunda 8 deployment may carry. Wiring the same model
twice adds nothing. `Camunda8StartListenerCostTest` holds all three numbers, so a change shows up
as a failing test and not as a surprise in a deployment.

Per workflow it is one job. The cluster creates the listener job, a worker of this adapter
activates it, the core answers, and the job is completed. A workflow which the application started
pays for one round trip to the cluster and one load of its workflow aggregate. That is the price of
telling a foreign start from our own on a BPMS which does not say so itself. And a workflow whose
first task follows would load the same aggregate a moment later anyway.

### 50. The adapter says that the workers outgrew the connection pool, and raises nothing

The adapter opens one worker per process and per kind. Every worker holds a REST activation request
open while it waits for work. The Camunda client keeps at most 100 HTTP connections unless the
application says otherwise. The number is the same in the `8.8`, `8.9` and `8.10` clients
(`DEFAULT_MAX_HTTP_CONNECTIONS`, readable in the bytecode of `CamundaClientBuilderImpl`). If an
application's workers need more than that whole pool, not all of them are served. The extra
workers take turns, and whatever one of them waits for arrives a whole `request-timeout` later.

Measured on 2026-09-26 with `Camunda8RestartDeliveryIT` against `camunda/camunda:8.10.0-rc1`: 115
workers against the client's 100 connections took 10412 ms. The same 115 against 256 took 215 ms,
and 92 workers against the client's 100 took 184 ms. The number of workers compared with the size of
the pool decides, not the version of the client.

**The application is told, its pool is not raised.** The adapter could size the pool itself. It
knows the number of workers before it opens them. So it could hand the client a bigger number, and
nobody would ever see this. It does not.

How many connections an application opens against its cluster is a decision about that
application's resources. Taking that decision behind its back is the wrong kind of help. Think of a
cluster behind a proxy with a connection budget, or an application which runs twenty replicas of
itself. Neither is visible from here, and both are decided by somebody who never asked the adapter
to decide them. So the adapter says what it found, names the property, and leaves the number to the
application.

It is a warning and not a refusal. An application over the limit works. It is only slow in a way
which nothing else explains. Refusing to start it over a number it can raise in one line would be
worse than the wait.

**Where the check sits, and what it counts.** At the end of `startWorkflowProcessing` of
`Camunda8DeploymentService`, once a workflow module has opened its workers. That is the first
moment the number is known. It is also the last moment before the platform writes the block of the
start. That is what the rule "as early as possible" means here.

It counts the workers open on the CLIENT, not the workers the deployment service ordered.
`Camunda8Workers` is public, so an extension can open workers on the same client. Those workers
hold the same connections, and an application near the limit could cross it with nobody counting.
So a worker is counted where it is opened. `Camunda8Workers.open` opens every worker of the adapter
and of an extension, and hands it to `Camunda8ClientFactory`. That is the one object per adapter id
which both of them already hold. A worker which is closed leaves the count again. So a workflow
module which stops gives its share back as before.

A worker which opens while a workflow module is starting does not run the check. The number halfway
through a module is not the number it ends with, and the message names a number. The module runs
the check itself once its last worker is open. A worker which opens outside a module start runs the
check right away. Story 694 is about that case: an extension which opens its workers after the
start makes the check run a second time, on the number which is true at that moment.

An extension which brings a CLIENT OF ITS OWN has a connection pool of its own, and its workers are
not in this number. Such a worker never comes through `Camunda8Workers.open`, so nothing has to be
excluded. `core/README.md` says so where the extension API is listed.

A start says it once. The sum grows with every workflow module. A start which repeated the sentence
per module would fill the block with one text per number, while the developer's next step stays the
same. If the developer raises the pool and it is still too small, the next start names the number
which is then missing. That is how every other startup message of VanillaBP gets to the end, step by
step.

Nothing is said for a client which prefers gRPC. Its workers activate over that transport, and
there this pool is not what limits them.

**The same pool decides how long a shutdown takes.** A drain waits for the cluster to answer the
activation requests of the workers it closed, because closing a worker does not cancel the request
it has in flight. So the minimum of a drain is a whole `request-timeout` (decision 6). That was the
whole story as long as the workers fitted into the pool. They do not always fit. The workers which
found no connection have their request QUEUED inside the client. That request still goes out once a
connection is free, and then it waits a request timeout of its own. So the workers of a client go
in rounds through its pool, and a round costs a request timeout. The minimum of the drain is the
rounds plus what the last worker needs to report itself closed.

`shutdown-grace` defaults to `PT20S` at a `request-timeout` of `PT10S`, which is exactly two
rounds, and nothing compared the two. On 2026-09-27 that cost a red integration test. An
application with 115 workers on a pool of 100 gave up after 20045 ms, with its workers still
holding a request. The give-up is the finding, and it says that the pool was too small for the
workers of that application. It does not say that a job could then be delivered into a parked
request. Giving up means that the drain waited out the whole grace, so the window it exists to close
was closed in that ending too; decision 61 measured that. The application was over the pool. The
pool check warned about the delivery of its jobs, but nobody said anything about its shutdown.

Measured on 2026-09-28 with `Camunda8WhatADrainWaitsForIT` against the cluster of the current GA
line, a client pool of 30 and a request timeout of `PT10S`: 15 workers released after 6272 ms and 30
after 5566 ms, which is one round; 60 workers after 15424 ms, which is two; 90 workers after 25541
ms, which is three. Where inside its last round a shutdown lands depends on how far that round had
got when the workers were closed. So the minimum counts the whole round. The two extra seconds come
from the run which ran OUT of time, not from these readings.

**The number of workers is the part of this which is under review.** One worker per process and per
kind fills the pool. Task definitions shaped so that fewer workers carry the same work would need
fewer connections, and every number in this entry would move with them. Until they are shaped that
way, the rounds of the pool are what a drain costs.

**It warns, like the rest of this entry.** The adapter could raise the grace itself. It does not,
for the same reason it does not raise the pool. The grace has to stay under the shutdown budget of
whatever runs the application, and the adapter can neither see nor set that budget.

**Which way out the message names first depends on the runtime.** Spring Boot's
`spring.lifecycle.timeout-per-shutdown-phase` and Kubernetes' `terminationGracePeriodSeconds` both
default to 30 seconds. From three rounds of the pool on, the minimum is past that. So a grace large
enough for the drain would get the application killed instead. The message then says that raising
the grace is not the way out here, and asks for a bigger pool. Below three rounds it offers both,
the grace first, because that is the smaller change.

**Why it sits next to the pool check and not in the startup validation.** The validation of the
grace knows the request timeout but not the number of workers. So the minimum it can check is the
one for a single round, and it keeps doing exactly that. The rounds are known where the workers are
counted. Each of the two says its own sentence, and neither says the other's. Only the validation
reports a grace below one request timeout. Such a grace cannot drain for a reason which has nothing
to do with the number of workers. Two messages about one value would leave the reader choosing
between them.

`Camunda8WorkerConnections.aDrainFloor` is the number, and `Camunda8WorkerConnectionsTest` holds the
sentences.

**The first user of the collection point.** This is the first finding which the Camunda 8 adapter
reports through `StartupReport`, the bean of decision 97 of `adapter-platform-integration`. Both
platform integrations publish one instance per application, and both producers of this adapter now
hand it to the deployment service. Where there is none, as in a test which builds the service by
hand, the message goes into the log where it was found.

### 51. The shutdown grace is the budget of the whole shutdown, and the workflow modules are one wait

The platform stops the workflow modules one after another, on the shutdown thread. It calls the
adapter once per module and per adapter instance. `shutdown-grace` sits at adapter level. So each
of those calls used to close the workers of its module and then wait the whole grace for them. An
application with three workflow modules therefore had three waits of up to twenty seconds, in a
shutdown for which its runtime grants thirty.

The waits do not overlap. While another module is drained, a module whose workers are still open
keeps renewing their activation requests. Closing a worker does not cancel the request it has in
flight, so the module stopped next has a request of its own to wait out. The closed workers of the
module being drained also wait for the client's executor, behind the workers of the other modules
which are still polling. That costs more than one request timeout.

Measured on 2026-10-01 with `Camunda8WhatSeveralModulesPayForAShutdownIT` against
`camunda/camunda:8.9.21`, one client with a pool of 256, `request-timeout` `PT10S`,
`shutdown-grace` `PT20S` and thirty workers per module. Where the reading depends on the phase in
which the close happens, the case was read twice:

| modules | drained one after another | every module closed first, then one wait |
|---------|---------------------------|------------------------------------------|
| 1       | 823 ms                    | the same reading                         |
| 2       | 12519 and 21421 ms        | 5436 ms                                  |
| 3       | 32743 and 15424 ms        | 5242 ms                                  |

Three modules one after another went past the thirty seconds which the runtime grants. One module
of that run gave up after the whole grace, with its workers still holding a request. The give-up
is the finding. It says that a module drained while the others still poll needs more than the
grace. It does not say that a job could then be delivered into a parked request. Giving up means
the drain waited out the whole grace, so the window which the wait exists to close was closed in
that ending too; decision 61 measured that. Waiting once stayed at about half a request timeout,
with two modules as with three, and every module was quiet.

So the grace is spent once. Each module closes its workers when it is stopped, and does not wait
yet. The module stopped last is the one which leaves no registration of this adapter behind. It
waits for every module of the adapter instance at once. The number the application configured is
then the longest its whole shutdown takes, and that number can be compared with the budget of the
runtime. No other number can, because only the application knows how many workflow modules it has.

**Why not a share per module.** At shutdown the adapter knows how many modules are still to come.
So it could hand each of them an equal share of what is left. That would not help. A module needs a
whole request timeout, and three of them do not fit into twenty seconds. Three modules with a share
of under seven seconds each would each leave a parked request behind. One wait needs one request
timeout for all of them, because the requests of every module are parked at the same time and come
back at the same time. The readings show it. One module alone was released in 823 ms, and two
modules together in 5436 ms. But a module drained while another one still polled waited out the
whole grace twice.

**What the SPI allows.** `stopWorkflowProcessing` of the extension SPI says that the workers of
that module stop. It does not promise that the module is quiet when the call returns. Between two
of those calls nothing touches the cluster and nothing closes the client. The client is closed
later, by `Camunda8ClientFactory`. Before it closes the client, its backstop stops any module which
never reached the adapter. So on every shutdown path the one wait happens before the client goes
down.

**The key stays at adapter level.** It is compared with one shutdown budget, and only the
application has such a budget. A value per workflow module would be a number which nobody could
compare with that budget. That is why `shutdown-grace` cannot be resolved over the four levels
which the rest of this adapter's scoped properties use.

**What this leaves open.** An application with two configured adapter instances has two of these
budgets, because each instance waits for its own modules and knows nothing about the other. The
adapter cannot close that gap. It sees neither how many other instances there are nor what they
configured.

`Camunda8Drain#awaitEveryModuleQuiet` is the wait, `Camunda8ShutdownDrainTest#twoModulesAreOneWait`
holds it, and `Camunda8WhatSeveralModulesPayForAShutdownIT` holds the numbers.

### 52. Three lines after the GA of 8.10, two of them for bugfixes, and no preview line until 8.11

Until 2026-09-29 the rule was that a line ends when the next minor version goes GA. So there were
two GA lines at a time, plus a preview line. Under that rule the GA of 8.10 would have ended the 8.8
line on the day it arrived.

It did not end it. The lines exist for one reason, which is decision 11: it must be possible to
deliver a VanillaBP bugfix without a Camunda cluster upgrade. Ending 8.8 on that day would have
told everybody on an 8.8 cluster to upgrade before they can get the next fix. And Camunda supports
8.8 until April 2027. Their cluster would have been in support while their adapter was not.

So the lines are 8.8, 8.9 and 8.10. 8.10 is the current GA line. That means a plain build produces
it, and new features land on it. A cluster of 8.10 or newer takes it. 8.8 and 8.9 take fixes, and
nothing which needs a newer cluster. There is no preview line. The next one is the line built
against the first pre-release of 8.11.

The price is CI. Three lines are three cluster runs per pull request, and the 8.11 preview line will
make it four. The work per line is close to nothing, because every line is built from one source
tree. So a line costs the matrix, not maintenance.

`2.0.0-8.10` is published with the 2.0 release, like the other two. The line is GA. A user who runs
8.10 should not have to point at a pre-release to get the adapter built for their cluster.

How long a bugfix line is kept is not said here, but in decision 60. A line is kept for as long as
Camunda keeps its minor version in standard maintenance. It ends on that date and not earlier. The
Renovate boundary rule asks the question at the moment it matters. A minor update of a pin waits
for approval, and its text asks whether the oldest line is still kept.

See [Release lines](./README.md#release-lines).

### 53. A user task a job worker serves is refused in a process the application claims, and only warned about in one it does not

*Superseded in part by decision 73: a process nobody claims is not looked at any more. No WARN, and no wait for the cluster to count its open jobs.*

Camunda 8 knows two kinds of user task. One carries `zeebe:userTask`, and the CLUSTER manages it.
This adapter serves that kind. The other carries no such element, and a job worker serves it. That
is how VanillaBP 1 worked up to its release 1.6.3. **This adapter does not accept the second
kind.** Only the shape of the element counts. So nothing asks whether some worker would fetch the
job the cluster hands out: the model already says who serves the task.

The shape is refused, and not left alone, because of what it would cost. The cluster hands out a
job of `io.camunda.zeebe:userTask`. This version opens no worker on that job type, so the workflow
stands at the element until the job's retries are used up. No notification arrives.
`completeUserTask` cannot answer such a task either, because the id it hands out is a job key,
while the cluster expects a user-task key. Nobody sees any of that until somebody waits for a task
which never appears.

A `@WorkflowService` class which claims a BPMN process says that this application stands in for
the process. So the deployment refuses such a process. The message names the process and the
elements per shape, and it names the ways out. Either make the user task one which Camunda
manages, whose external form reference names the task definition of a `@WorkflowTask` method. Or
take the element out of the model.

The third way out is not a user task at all. The message names it because otherwise the boot would
end for an application which is right. An element which carries a `zeebe:taskDefinition` is served
by a worker of the APPLICATION, under a job type which the application chose. Nothing of VanillaBP
notifies anybody about it, and nothing completes it. So it is no longer a user task which this
adapter is meant to serve, and the reader passes over it. That is the line this entry draws:
between a user task VanillaBP serves and an element the application serves itself.

A process which no class of this application claims keeps the WARN it always had, without the
sentences which asked the reader to change something. Such a process reaches the cluster because it
sits in a file next to a process which this application does serve. For the same reason
`validateTaskWiring` asks nothing of it. And whoever owns it may serve such a job with a worker of
their own. The reader of this application's log can do nothing about it, so nothing is asked of
them.

Whether the process is claimed is read the way everything else in this adapter reads it. For a
claimed process the core answers the name of the workflow aggregate's id, and for an unclaimed one
it answers nothing. The core's report at module level names the unclaimed processes once per
workflow module. An adapter neither implements nor calls that report.

This is where decision 24 stops. Its reasoning holds for every unclaimed process. A file travels to
the cluster as a whole, and ending the boot over a model which somebody else owns would take an
application down over an element it cannot change. What changed is the claimed process: there is
no somebody else there.

A version-1 application whose model has such an element stops booting after the upgrade, and that
is the point. `UPGRADE.md` says so in the section about user task models. An application which
cannot change its models at once has the same way out as for every deployment failure: a
non-primary adapter configured with `deployment-failure: warn` logs the failure instead of ending
the boot.

`Camunda8JobWorkerUserTasksReportTest` holds both messages, the boundary of the element which the
application serves itself, the counter-test of a claimed process whose user tasks are all managed by
Camunda, and that the WARN asks for nothing.

### 54. Every element of a claimed process has to be served, and an element template is how the model says it is served elsewhere

*Superseded in part by decision 73: a process nobody claims is not looked at any more, so it keeps no WARN.*

*Superseded in part by decision 27: the part about a modelled listener no method names. Such a listener now goes to the core's wiring validation, which asks for a method or for `implemented-externally=true`, and an element template counts only on a job element, not on a user task. The ad-hoc subprocess part stands, and a marked ad-hoc subprocess now passes as well.*

A `@WorkflowService` class which claims a BPMN process says that this application stands in for
the process. So no element of such a process may be left standing. Where the cluster creates a job
and nothing answers it, the workflow stops inside the element, and both sides stay quiet. There is
no incident until the job's retries are used up, and there is nothing in any log at all. So the
deployment refuses the process. A process which nobody claims keeps the WARN it always had.

Before this entry there were warnings about two elements. Both are now refused for a claimed
process.

An **ad-hoc subprocess with a `zeebe:taskDefinition` of its own** expects a worker which decides
round by round which inner activities to activate. It does so by completing the job with a result
which names them. A `@WorkflowTask` method cannot say that, so this adapter opens no worker for the
element. Nothing later in the boot catches it either, because the element produces no task spec
and no validation misses a method.

A **modelled listener whose job type no `@WorkflowTask` method names** is the harder one, because
the job type says nothing about who answers it. A worker which somebody else runs, and a worker
which the application runs next to VanillaBP, look exactly the same in the model. So the ELEMENT is
asked instead of the job type. An element built from an element template belongs to the runtime
which owns that template. That is the marker of decision 23 and of decision 24, and it is read
through the same class, not looked for a second time. A listener on such an element is named in a
WARN of its own, and the boot goes on, whoever claims the process.

Using the element template for this misses one case, and that is named openly. A developer who
meant VanillaBP to serve the listener of a templated element, and forgot the method, reads a
warning instead of a refusal. The alternative was a marker of this adapter's own. That would be a
second way of saying what the template already says. And a model with that marker would no longer
be a model which any Camunda 8 modeller understands.

One element was never part of this question. A service task without a `@WorkflowTask` method ends
the boot of a claimed process, and always did. The reader hands the core a task spec whose task
definition is `null`, and `validateTaskWiring` ends the start over it. The core asks nothing of an
unclaimed process. That is the same split, written in the core instead of here.

The refusals say what to do next. The ad-hoc message names the two ways out: the model and the
other runtime. The listener message names three: a method plus the key which lets VanillaBP serve
modelled listeners, taking the listener out of the model, and the element template for a job which
somebody else answers. An application which cannot change its models at once has the same way out
as for every deployment failure: a non-primary adapter configured with `deployment-failure: warn`
logs the failure instead of ending the boot.

`Camunda8AdHocSubProcessTest` holds the refusal and the WARN of the ad-hoc subprocess.
`Camunda8ListenersReportTest` holds the refusal, the WARN of an unclaimed process and the WARN of
the templated element.

### 55. A value written as FEEL gets the prefix inside the expression, at every place a prefix is written

Under `use-prefix`, a value which starts with `=` is deployed with the prefix of its place written
INSIDE the expression. So `=whichProcess` reaches the cluster as
`="loan-approval__" + string(whichProcess)`. The application writes no prefix anywhere, and that
keeps its model portable: the same file runs on another BPMS, and the same business code with it.

**One rule, and one list of the places where it is used.** The rule: if the value starts with `=`,
the prefix goes inside; otherwise the prefix goes in front. The places are the `processId` of a
`zeebe:calledElement`, the `decisionId` of a `zeebe:calledDecision`, a `bpmn:message` name, a
`bpmn:signal` name, a `bpmn:error` code, a `bpmn:escalation` code, a `zeebe:taskDefinition` type, a
`zeebe:formDefinition` external reference and the job type of a listener this application serves.
`Camunda8Scoping#forEveryPrefixedValue` is that list. Both the rewrite and the refusal below read
it, so a place added to it is covered by both in one change. A job type carries the prefix of its
BPMN process as well, so its frame is `="loan-approval__LoanApproval__" + string(...)`. That frame
only ever reaches a process which nobody claims. In a process the application claims, a job type
written as an expression is refused; that is decision 59. So the only model left for the rewrite
to frame is one which reaches the cluster because of the file it sits in.

**Nobody asks whether Camunda 8 evaluates an expression at a given place.** The maintainer decided
that on 2026-10-01. A list of the places where Camunda evaluates expressions gets outdated with
every Camunda release. Writing one down would freeze today's answer into this adapter. The rule,
on the other hand, cannot be wrong. Where an expression is not evaluated, a value starting with `=`
does not appear, and the rule costs nothing. Where Camunda learns to evaluate one, nothing here has
to change. What is still watched is that the cluster ACCEPTS a model with the frame at those
places. The canary checks that by deploying one model twice, once plain and once framed. Measured
on 2026-10-01 against `camunda/camunda:8.9.21`: both deployments went through. The first attempt
did not. What it refused was the test's own XML, not the frame (`Element type "bpmn:message" must
be followed by either attribute specifications, ">" or "/>"`). A FEEL expression contains quotes
of its own, and those values sit in XML attributes. A modeller escapes a model they write; a model
composed in a test is not escaped.

The prefix cannot go in front of such a value. An expression takes up the whole attribute. So
`loan-approval__=whichProcess` names no process, and it does not parse as an expression either.
Camunda 7 gets away with the same rewrite, because `${processToCall}` is one part of a string which
the engine composes.

Every part of the frame was measured. Camunda's FEEL joins two strings with `+`. That goes beyond
the DMN standard, where `+` is arithmetic. `string(...)` around the application's part makes the
join work whatever the part returns, a number included. The parentheses keep the shape of that
part. So all of these survive being wrapped: an `if ... then ... else ...` which returns one of
several ids, a `get value(...)` over a context, a text which the expression composes itself, and an
expression written over several lines.

Measured on 2026-10-01 against `camunda/camunda:8.8.40`, `8.9.21` and `8.10.0-rc3`. All three
answered the same in every case, down to the wording of an incident. Those measurements are about
the called process and the called decision. On that day these were the places where Camunda
evaluated an expression. The frame is the same everywhere else, and the paragraph above says why it
is not measured per place. The alternative frame,
`string join(["loan-approval__", string(whichProcess)], "")`, works as well, but was not taken.
Where the application's part is `null`, it drops that part and asks the cluster for the bare
prefix (`CALLED_ELEMENT_ERROR:
Expected process with BPMN process id 'loan-approval__' to be deployed, but not found.`). With `+`
the cluster raises an incident which names the application's own variable (`EXTRACT_VALUE_ERROR:
Expected result of the expression '"loan-approval__" + string(whichProcess)' to be 'STRING', but
was 'NULL'. The evaluation reported the following warnings: [NO_VARIABLE_FOUND] No variable found
with name 'whichProcess' [INVALID_TYPE] Can't add 'null' to '"loan-approval__"'`). A frame which
hides a mistake of the application is the worse frame.

The price is that the cluster then holds an expression which nobody typed, and that is stated
openly. Camunda 8 parses the FEEL of a model while it deploys it. So a syntax error in the
application's part refuses the whole deployment, quoting the framed expression. And the column it
reports is counted from the opening quote: `string(whichProcess +)` inside the frame was reported at
`:1:27`, the same mistake without a frame at `:1:14`. So where the deployment reports such a
refusal, it says once what a quoted expression includes
(`Camunda8Scoping#whatAQuotedExpressionIncludes`). Nothing is said while a deployment goes through,
because then there is nothing for anybody to do.

An expression which composes the prefix ITSELF ends the boot, at any of the places above. The
message names the element, the file, the attribute and the expression to change. The rewrite would
give it a second prefix. The cluster would be asked for
`loan-approval__loan-approval__PaymentHandling`, and every call of that element would fail once a
workflow reached it. An earlier snapshot of VanillaBP 2 asked an application to compose the prefix,
and warned about every such element. So that model is the one source of this case, and a boot which
says so is cheaper than one incident per instance.

This does NOT reach the model knowledge behind such a call. Which process the expression names is
known only at execution time. So a call activity which names it by FEEL stays outside the call
graph which the deployment links, and outside what the workflow viewer can draw. Both read the
attribute the same way, and both still see an expression. The iteration chain is not lost with it.
Instead, the caller writes its own levels into the called instance; that is the addendum to
decision 30 from story 767.

Where the mode is not `use-prefix` there is no prefix, so nothing of this applies and nothing is
said.

`Camunda8CalledProcessScopingTest` holds what the adapter writes into the model, per shape and for
both of the attributes above, and that the frame survives the XML which the deploy command sends.
`Camunda8PrefixInEveryPlaceTest` holds the two forms at every other place, the longer prefix of a
job type, and the refusal which covers all of them. `Camunda8PrefixInsideAnExpressionTest` holds the
refused boot and the sentence about a quoted expression. `Camunda8PrefixInsideAnExpressionCanaryIT`
holds the cluster to what was measured and to accepting the frame everywhere else.

See [Keeping workflow modules apart](./README.md#keeping-workflow-modules-apart).

A form reference (`zeebe:formDefinition externalReference`) is one of these places, and one
written as an expression is refused in a claimed process: decision 69.

### 56. The 404 of a user-task probe is about the key it was handed

A user task which is simply open never makes the probe say `404`. Decision 38 reads that answer as
"the task is gone", and story 643 measured it only inside the window of a running cancelation.
`Camunda8ProbeOfAnOpenUserTaskIT` measures the plain case: a task which nobody is cancelling, asked
about from the moment its `creating` listener job arrived. That is the moment at which an
application, and with it the Business Cockpit, learns of the task.

Measured on 2026-09-28, one cluster at a time on a machine with 16 GB, client and cluster of the
same line. The questions were asked one after another, so every number includes the one before it:

|                     the question                      |                      8.8.39                       |                      8.9.21                       |                    8.10.0-rc1                     |
|-------------------------------------------------------|---------------------------------------------------|---------------------------------------------------|---------------------------------------------------|
| the empty `UpdateUserTask`, answered by the partition | `204` after 12 ms, and over five further readings | `204` after 21 ms, and over five further readings | `204` after 13 ms, and over five further readings |
| `UserTaskGet`, answered by the index                  | `404` until 1667 ms                               | `404` until 649 ms                                | `404` until 219 ms                                |
| the user-task search, the other read of the index     | holds it after 1674 ms                            | after 659 ms                                      | after 229 ms                                      |
| `UpdateJobTimeout` on that user-task key              | `404` for 60 s                                    | `404` for 60 s                                    | `404` for 60 s                                    |
| the instance probe                                    | `400`, so the engine holds the instance           | `400`                                             | `400`                                             |

So the probe is AHEAD of the index, not behind it. That is why it is a command and not a search.
And the `404` of decision 38 keeps its meaning for a key which is a user-task key.

**For a key of the other kind it means nothing.** The same run measured a plain BPMN user task,
the shape which VanillaBP 1 served up to its release 1.6.3. Its id is a JOB key. `UpdateUserTask`
and `UserTaskGet` answered `404` for a full minute, and the user-task search never held a record of
the task at all. Meanwhile the job command on that key was accepted, and the instance probe said
that the engine holds the instance. The cluster keeps no user-task record for such a task, on any
of the three lines. A `404` there answers "do you hold a user task under this key?", and the task is
open.

This adapter does not meet that case, and not by accident. `Camunda8OpenTaskProbe` reads the kind
of task from the model and sends no user-task command for a job. The core calls the two probes
which ask about a task a caller named only for a user task. And the deployment reports a user task
of the version-1 shape instead of serving it. So the promise of decision 38 has a condition, and
the adapter keeps that condition. The javadoc of `Camunda8UserTaskProbe` says so, so that nobody
who rebuilds the question outside VanillaBP reads more out of a `404` than it says.

For the record, here is what version 1 did differently. Its existence check for a user task was a
`UserTaskGet`. So it read the index and met the first row of the table above. It served both shapes
of user task, so it met the second case as well. And its Business Cockpit wrote its record from the
listener job. That is why the cockpit shows a task which the index does not have yet. `UPGRADE.md`
says it where an application coming from version 1 will look.

The same `404` has a simpler cause, and it is the one which actually came up: a user task the
Business Cockpit showed as active while a check about it answered `404`. The code handed the key of
the user task to `completeTask` instead of `completeUserTask`. `completeTask` asks about a JOB, and
no job carries that key, so the answer is `404`. It says exactly what it always says. Whoever reads
a `404` first checks which command asked and which kind of key that command asks about.

### 57. A 404 about a user task is asked about on the job side once, and only the sentence changes

A user-task command answers `404` for a key under which it holds no user task. For a user-task key
that means the task is over. For a JOB key it means nothing about the task at all. A user task
served by a job worker, the shape VanillaBP modelled up to its release 1.6.3, has no user-task
record in the cluster. So every user-task command answers `404`, however open the task is.

An application meets such a key while it upgrades. `UPGRADE.md` says that the task ids of version 1
are data to migrate, and the ids of that shape of user task are job keys. The adapter used to
answer such a key with "gone (completed or canceled meanwhile)". That named the one thing the
cluster had not said.

So on the `404`, and nowhere else, the job side is asked. That takes two questions, and one cannot
replace the other.

**Whether the cluster holds a job with that key is asked of the ENGINE.** The adapter uses the
`UpdateJobTimeout` which it sends anyway as the existence check of a service task. The answer is
`404` for a key under which it holds no job. It is a `400`, saying nobody has it activated, for a
key it holds. And the command is accepted for a job which somebody holds right now. Only an answer
which says the cluster HAS the job counts, so a cluster which cannot be reached claims nothing. The
price is named openly: where a worker holds that job at that moment, its deadline is pushed to
`async-task-lock-renewal`. The job in this case is a user task which nothing in this version
fetches. So the answer is the `400`, and the cluster writes nothing.

**Which element the job belongs to is read from the INDEX**, and nothing else is. A search lags
behind the engine at both ends, so it can answer neither half of the existence question. Measured on
2026-09-28 against `camunda/camunda:8.9.21` and `camunda/camunda:8.10.0-rc1` by
`Camunda8ProbeOfAnOpenUserTaskIT`, on the job of a plain BPMN user task: the job search answered
"no job of that key" while that job was activated and the task open. And it still answered with the
job once the job was over: as `TIMED_OUT` at once, and as `COMPLETED` five seconds later. Meanwhile
`UpdateJobTimeout` was accepted for the open job, and answered `404` once it was gone. Where the
index answers nothing, the message says the rest without the element.

The state goes into the message as the cluster wrote it, and it is never read. The same job, five
seconds after it was activated, was `CREATED` on 8.9.21 and `TIMEOUT_UPDATED` on 8.10.0-rc1. A
literal which is new in a patch release is nothing to build a decision on.

The empty `UpdateJob` was the candidate for a read-only twin of the empty `UpdateUserTask`, and it
is not one. Measured in the same runs, it is refused with `400 INVALID_ARGUMENT` ("At least one of
[retries, timeout] is required", with `priority` in that list on 8.10). That happens both for a job
which is open and for one which is over. So it cannot tell the two cases apart at all.

The extra round trip costs nothing in the everyday case, because the everyday case is not a `404`.

What the caller gets does not change: `TaskNotFoundException` from the pre-commit check of
`completeUserTask`, `UNKNOWN_TO_BPMS` from `awarenessOfUserTask`. The kind of key somebody handed
in changes the sentence, not the outcome. If the outcome depended on it, the election would behave
differently for a migrating application than for any other.

`Camunda8A404AboutAJobKeyTest` holds the sentences and the unchanged outcomes.

### 58. A search whose answer is a set reads every page, and the paging stops at 10000 entries

A Camunda 8 search which says nothing about the page it wants is answered with 100 entries, and
nothing in the answer says that there were more. Three searches of this adapter could have more
than 100 hits, and they read only that one page: the versions of a process, the element instances
of a workflow history, and the children of a scope, which the walk for a task's own scope reads.
None of them failed at that limit. The startup check named the 100 oldest versions and missed the
one just deployed. The history showed the beginning of a workflow as if it were all of it. And the
aggregate of a task in the 101st iteration of a multi-instance was written nowhere.

So a search whose answer is a SET reads every page, through `Camunda8SearchPages`. A search whose
answer is ONE hit names its limit at the call site, with a sentence saying why that number is
enough. The decision is both halves. The second one keeps a search for a single workflow from
paging through a whole cluster.

The paging stops at 100 pages, which is 10000 entries. A reader which never stops is worse than one
which says where it stopped, and a set that large is a sign that the caller should ask a narrower
question. Where the limit is reached, the caller writes a log line. The records which carry these
answers have no field for "and there was more": neither the history record nor the version list
can say it. So the version search runs NEWEST first, and is turned back into oldest first
afterwards. That way the limit can only cut versions which nobody asks about any more.

The cost is one request per page while a workflow is being served. The scope walk reads pages while
an aggregate is pushed, and it stops on the page where its element instance is. So the common case
pays for one page. The walk itself still costs one search per element instance below the scope, as
it did before.

### 59. A job type written as an expression is refused in a claimed process, and warned about in one nobody claims

*Superseded in part by decision 73: a process nobody claims is not looked at any more, so it keeps no WARN.*

A job type is the NAME a worker subscribes to. This adapter opens one worker per job type which it
reads from a model, and subscribes to exactly the string the model says. So a job type written as a
FEEL expression leaves the element unserved. Both answers Camunda 8 can give lead there. Where it
evaluates the expression, the job carries the result, while the worker waits for the expression.
Where it does not, the job carries the expression, and no `@WorkflowTask` method can be named after
it. Measured on 2026-10-01 against `camunda/camunda:8.10.0` and `camunda/camunda:8.9.21`: the job
carried the result on both, and nothing answered under the expression itself
(`Camunda8JobTypeWrittenAsAnExpressionCanaryIT`, which the 8.8 line runs in the pull request's
matrix).

The refusal does not rest on that measurement, and that is why the message says both. Decision 55
says why this adapter keeps no list of the places where Camunda evaluates an expression. A refusal
built on such a list would get outdated the same way. The measurement would only change the
sentence about what it costs when a workflow reaches the element, not whether the model deploys.

**Two attributes give a job type**, and both are checked: the `zeebe:taskDefinition` type of a
service-like task, and the type of a listener somebody modelled. Only the shape of the value counts,
just as decision 53 asks about the shape of a user task. Nothing asks whether some worker somewhere
would fetch such a job. And nothing asks whether the listeners of the process were allowed. An
expression is a name this adapter cannot subscribe to, whatever a property says.

Who claims the process decides what happens, as in decisions 53 and 54. A `@WorkflowService` class
which claims the process says that this application stands in for it, so the deployment refuses it.
A process which nobody claims reaches the cluster because of the file it sits in. So it keeps a
WARN which names what was found and asks nothing of the reader.

An element built from an element template is left out; that is the marker of decision 23 and
decision 24. Its job type names a runtime which somebody else deployed, and such a runtime is free
to compose that name by expression.

**What the message had to stop saying.** Before this entry the boot also ended over such a model,
but it ended in the wrong place. The reader of a claimed process handed the core a task definition
which was the expression. `validateTaskWiring` found no `@WorkflowTask` method of that name, and the
message asked for a method which nobody can write. A served listener was worse. Where a method
happened to name its job type, it passed the same validation, and the worker then waited for a name
which no job carries. So the finding is named while the file is prepared, before the rewrite and
before the wiring validation. And the message quotes the expression as the modeller typed it, not
the frame which `use-prefix` writes around it.

**The two ways out** are in the message. One: write a job type which is a fixed name, plus a
`@WorkflowTask` method of that name. Where the work differs from workflow to workflow, let the
method branch on the workflow aggregate it is handed; the data the expression reads comes from
there anyway. Two: leave the element to the runtime which does serve it. Give the element a
`zeebe:modelerTemplate`, and allow such elements with `allow-connectors`. An application which
cannot change its models at once has the same way out as for every deployment failure: a
non-primary adapter configured with `deployment-failure: warn` logs the failure instead of ending
the boot.

`Camunda8JobTypeWrittenAsAnExpressionTest` holds the refusal, the WARN of an unclaimed process, the
listener half, the element template, and that the quote is the expression which the modeller typed.
`Camunda8JobTypeWrittenAsAnExpressionCanaryIT` holds the cluster to what was measured.

### 60. A bugfix line ends when Camunda stops maintaining its minor, and not before

Decision 52 says why there are three lines, and why 8.8 did not end on the day 8.10 went GA. It
did not say when a line does end. So until now a line ended when somebody ended it on purpose. That
was no promise. Whoever picks `2.0.0-8.8` could read nowhere how long fixes will still reach them.
And the matrix grew by a column with every minor version, because nothing ever took one away.

A line is kept for as long as Camunda keeps its minor version in standard maintenance. It ends on
that date and not earlier.

The date is Camunda's own, not one invented here. Camunda's release policy says that it "provides
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

One thing about that table matters before anybody reads a date from it. It names the planned
release date of a minor version, not the day the artifact appeared. Camunda published `8.10.0` on
2026-09-29, which is the date the `README.md` gives for the GA, and the table says 13 October 2026.
The end of maintenance is always a date read from that table, never one computed from a release.
So the two never have to agree.

The shape of the rule comes from decision 11: the client an artifact was compiled against is the
lowest cluster version which that artifact accepts. The other direction really does fail. The
blueprints measured it by running a build against `camunda/camunda:8.8.34` with the adapter
compiled against the 8.9 client. Every job activation came back with `Request property
[tenantFilter] cannot be parsed`. So one artifact cannot serve two minor versions, and decision 52
draws the conclusion: it must be possible to deliver a VanillaBP bugfix without a Camunda cluster
upgrade. So a line ends at the moment when nobody needs to be able to get a fix for the cluster it
serves any more, and Camunda's date is that moment. While Camunda still fixes your cluster, this
project still fixes the adapter for it.

**This says when a line ends, not which lines exist.** Which lines exist is still decision 52 plus
whatever a release decides. 8.7 is maintained until 13 October 2026 and never got a line here, and
this rule does not open one for it.

On our side this means one column per maintained minor version. Camunda maintains three. A preview
line sits next to them from the first pre-release of the next minor version until its GA. So the
matrix has three columns for a short while after a GA, and four for most of a cycle. 8.10 was the
preview line from its first alpha on 2026-05-11 to its release on 2026-09-29. That is about four
and a half months out of every six.

The cost of that column is measured. Over the three nightly runs up to 2026-10-01, a line took about
33 runner minutes. The lines run at the same time, so one run of the matrix costs about 100 runner
minutes and about 35 minutes of waiting. The other half of the bill is how often that is paid.
`checks.yaml` calls the matrix for every pull request without a condition, and the night calls it
once more. In the seven days up to 2026-10-01 that was 55 pull request runs and 7 nights, so about
103 runner hours in a week. A fourth line makes that about 137, and it adds nothing to the waiting
unless it is the slowest one. The work a line costs is close to nothing, because every line is
built from this one source tree. The line-specific part is `core/src/main/java-line-<id>`, with the
same six class names on every line: 391 lines on 8.8, 395 on 8.9 and 437 on 8.10 (counted on
2026-10-06, after decision 68 added the sixth). So the bill of a line is the matrix, and nothing
else about a line is expensive.

On top of that, the dates line up. 8.8 leaves maintenance on 13 April 2027, and the next minor
version is due in the same April. So the column which 8.11 adds is the column which 8.8 gives back.

**Why not a number of our own.** Take the rule "a line ends once it is no longer one of the three
newest GA lines". It would cap our side by design, whatever Camunda does with its release rhythm,
and the cap is all that rule gains. Today it picks the same 8.8, 8.9 and 8.10 as Camunda's dates.
For users it is weaker. A date is something somebody can plan with, while a count has to be worked
out by watching our releases. The two rules also split apart as soon as Camunda changes something.
A faster rhythm would make us drop a line which Camunda still patches, and decision 52 refused to
do that. Longer maintenance would make us keep a line which Camunda has stopped patching.

A line stays in the POM and in the matrix until its date, and it gets its last release before that
date. After the date its `line-*` profile goes. That takes the line out of the matrix by itself, and
what was published stays in the registry.

See [How long a line lives](./README.md#how-long-a-line-lives).

### 61. The restart test takes both endings of the drain, and says which one it saw

`Camunda8RestartDeliveryIT` measures the one thing a restart can get wrong on Camunda 8. A workflow
started right afterwards waits a whole job timeout for its first job. The reason is that an
activation request of the previous application is still parked at the cluster, and the job is
activated into it. The adapter closes that window by draining its workers before the client goes
down. Until now the test checked that drain by one sentence: the one the drain writes when the
module went quiet within the grace.

That is not the only sentence the drain writes. It has two endings. Either the module is quiet. Or
the grace runs out while the cluster still owes an answer for a request which one of the closed
workers parked, and then the drain warns about exactly that. The second ending is not a defect of
the adapter. It is a slow cluster, and the test was asserting that the cluster was not slow.

The cost of that assertion is measured. Two runs of 2026-10-01 went red on it, with exactly the
same wording. The publish run of `443a32d` (36871927557) stopped the first application of the test
after 25049 ms, and the run of pull request 231 (36889710683) after 25012 ms. Both had 119 closed
workers, of which at least one had not been released, and both had no handler left inside the
application. The second application of each of those runs drained in 10123 ms.

The number which the test exists for held in both runs: the first job after the restart came in
1.2 seconds or less, against a lock of 20 seconds. It always holds when the drain gives up. Giving
up means that the drain waited the whole grace. So the window through which the parked request
could have swallowed the job was closed long before the second application started.

Raising the grace from 20 to 25 seconds after the finding of 2026-09-27 gained nothing, and the
measurement says why no other number would either. Reading every closed worker while the drain ran
showed all 119 of them waiting on the activation request they had in flight when they were closed.
They come back within a second of each other, one request timeout after the shutdown began. That
is where the 10 to 12 seconds on an idle machine come from. Squeezing the cluster into four tenths
of a CPU core reproduces the red runs on demand, with 105 of the 119 requests still open when the
grace runs out. So the minimum of that wait is the cluster answering 119 parked requests. The test
may configure the grace, but it cannot configure that minimum.

**What the test demands now** is everything it ever claimed about the adapter. The drain runs
before the client is closed, and says what it did, in either of its two endings. Nothing cuts a
running handler off. The ordinary platform shutdown reaches the adapter, so the backstop of the
client factory stays silent. And the first job after the restart arrives in milliseconds instead of
after a job timeout; that is the point of the test, and it is untouched. The test no longer demands
that the cluster answers every parked activation request within the grace. That is the cluster's
promise, not the adapter's. A drain which does nothing still fails the test, because it writes
neither of the two sentences. So the first assertion catches it, and the delivery assertion catches
it as well.

**The two endings are not equally good.** The quiet ending is the normal case, and the other one is
the drain's own warning. So nothing here may read as if both were fine. Every assertion about the
shutdown names the ending it read, and so does the measurement which the test writes down after a
green run. So a reader of a failure sees whether the drain was quiet or warned, and nobody has to
read this entry as a weakened assertion.

`shutdown-grace` stays at 25 seconds in that test. It is still the budget of the whole shutdown, as
decision 51 made it, and it still limits the wait. Only, no assertion reads it any more.

**Why not the two other ways.** Giving the drain fewer workers to wait for would have meant a
separate application for this test, with one process instead of the thirty-five its module
deploys. This test is also the module's evidence for `max-http-connections: 256`, measured with 115
workers, and that evidence would have been lost. Halving the window, with a request timeout of five
seconds and a gap of three, would have kept the ratio. It would have widened the margin under the
grace from 15 to 20 seconds. But it would still have been the same bet, only with a wider margin.

**What this leaves open.** The test no longer notices a drain which runs into the grace on every
run. The ending in the written measurement makes such a drift visible, but somebody has to read it
to notice.

### 62. The expressions of a model are reported for the model being deployed, not for the versions the cluster still holds

The core is told the expressions of a process while that process is wired. So it hears about the
model which this application version brings. The cluster keeps the older versions, and workflows
are still running on them, but those models are not read for this.

A way to read them exists. `Camunda8ProcessVersions` walks the models in this adapter's picture of
what the cluster holds. That is how `concurrentTokenElementsOfVersion` answers, and the same walk
would answer this question. So the question here is what such a message would be worth.

An old model says nothing new. An expression which reads a path reads the same path in every
version which has it, and the message names the element, the place and the expression. The
difference is that nobody can act on an old model. A model in the cluster cannot be edited. The
deployed model is where a developer writes the plain getter which the message asks for. And the
workflows on the old version run out on their own. A warning about them would ask for work which
nobody can do.

The second reason is the count. The message ends with how many of the expressions of this process
name a variable and nothing else. That tells a developer how far along their model is. Counting the
held versions as well would count the same expression once per version, and the number would no
longer mean what it says.

`ConcurrentTokenCheck` asks about held versions for a reason which this check does not have. A
parallel gateway which the newest model dropped still forks every workflow which started before.
So that finding only exists in the old version. An expression is not like that. It is read where
the model has it, and that is the model being deployed.

Whoever wants the held versions in the message gets a story of their own, and the place to hook it
in is `Camunda8ProcessVersions`.

### 63. The expressions are read from the model before this adapter writes into it

`Camunda8DeploymentService.wireBpmn` asks for the expressions right after it reports compensation.
That is before `wireMessageSubscriptions` and `Camunda8MultiInstance.wire` run. Both of those write
FEEL into the model. A message which has no correlation key gets the one which VanillaBP
correlates by, and a multi-instance element gets the input mappings which this adapter needs.
Reading later would report those as the modeller's expressions. The message would then ask a
developer to simplify something they never wrote.

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

**Where it holds.** Measured on 2026-10-04 against `camunda/camunda:8.10.0` with PostgreSQL as its storage,
twelve minutes of outage under load, the application restarted in the middle (analysis `895`):

- With the storage stopped or frozen, every one of about 610 workflows per run ended exactly once. Phase one of a
  search-bound operation failed after the `request-timeout` and was repeated by the application; no outbox entry
  used more than six of its fifty attempts; a pod started during the outage, thirty seconds slower.
- With the exporter paused, the engine and every task kept going as well.

The same measurement found three cases with a stopped exporter where the rule did not hold. All three are closed
for every workflow which has a start row:

- `897`: a planned `aggregateChanged` or `correlateMessage` whose workflow the exporter has not written yet was
  dropped as stale by a node with no memory of that workflow, after a restart or on a second node. Reading the
  start row of `889` instead of the in-memory hint closed it. On Camunda 8 the `workflowId` in that row lets the
  engine answer without the copy.
- `898` and `902`: waiting for the copy counted as an attempt, so such an entry was blocked after about eight
  minutes. Now waiting spends time and not attempts, on every store.
- `899` and `903`: a task-scoped `aggregateChanged` for a task created while the exporter stands still could not
  find the scope of that task and was skipped. Now it waits for its scope, into a user task as well. How is said
  below.

A workflow without a start row, such as one started under version 1, still has these gaps. For such a workflow
only, the wiki page "When the read model fails" says what an operator does meanwhile: not restart application pods
while the exporter stands still, raise `vanillabp.outbox.block-after-attempts` before maintenance of the exporter,
and reopen blocked entries afterwards.

**A task-scoped push.** `aggregateChanged(aggregate, taskId)` writes into the scope the task runs in. Camunda 8 has
no command which names that scope, so the adapter reads it from the query API. That API is fed by an exporter.
While that exporter stands still, it knows nothing about a task created after it stopped. Reading "the query API
does not know the scope" as "the task is completed" would lose the value. Analysis `895` measured that on
2026-10-04: the exporter paused for five minutes, and all 20 workflows whose task was created in that time lost
their task-scoped push.

When the adapter leaves a task open, the core writes a row into the delivery log. Phase two of a task-scoped push
gets that row from the core as `PhaseTwoRequest#taskRecord()`, where the row names this adapter. The row names the
process instance of the task (`workflowId`), its element (`bpmnElementId`), its kind (`taskKind`) and the version of
its process (`processVersion`). The adapter reads three things from it:

- **Where the task runs.** Where the version is the one this application deployed, the deployed model says what
  encloses the element. A task directly in its process runs in the process instance of the row, so the push writes
  there without a search. That works while the exporter stands still. A task directly in an iteration of a
  multi-instance subprocess runs in that iteration. Every iteration carries the variable the adapter maps its index
  into (decision 5 in this log), and that variable is local to the iteration. So for a user task the adapter reads
  that one variable from the variables the user task sees, and the scope the cluster reports for it is the
  iteration. Everything else is searched for in the query API, first the job or user task behind the task id, then
  the scopes from the process instance down to it: a deeper scope, a service task in an iteration (a job has no
  variable search), and a row of a version this application did not deploy, whose model may enclose the element
  differently.
- **Which kind of key the id is.** A user task is found by the user-task search, a service task by the job search.
  Without a row the job is looked for first and then the user task. Both kinds of key are unique within a cluster.
- **Whether the engine may be asked.** See below.

Phase two searches once, and it does not sleep, because decision 49 of the platform says that phase two does not
sleep. If the query API reports the scope, the push writes there. If it does not, the engine is asked, but only
about a task which rests. A task rests where the adapter left it open: every user task, and a service task whose
method asked for the task id. The row says that the task rests: it exists only for a task left open
(`COMPLETION_PENDING`), and `taskClosedAt` stays empty until the application closed it. A user task is asked with
the empty `UpdateUserTask` of its awareness probe, a service task with `UpdateJobTimeout`:

- `404`: the cluster holds the task no more. It is completed, and the push is skipped with a WARN. The workflow's
  own scope is not written instead, because every branch reads it.
- any other refusal: the cluster has the task. The push throws `PhaseTwoRetryLater` with the visibility window, and
  the outbox brings the entry back after it.
- no answer at all, an outage: the failure is thrown as it is, and the outbox repeats it with its own backoff.

The question is not harmless for a task which does not rest. An `UpdateJobTimeout` sets the lock of an activated
job to `async-task-lock-renewal`, and so it can cut short the lock of a job a handler is working on. The job of a
task which rests is renewed by the adapter to that same value anyway. So without such a row nothing is asked, and
the push throws `PhaseTwoRetryLater` until the read model knows the task. The read model also reports a completed
job and a completed scope, so such a push either finds its scope once the exporter caught up, or meets the `404` of
the write and is skipped there. The core counts such a wait in time and not in attempts, and
`vanillabp.outbox.wait-for-visibility-at-most` ends it. A task id which never existed in this cluster is therefore
blocked after that time instead of being skipped at once. That is accepted. Without asking the engine, nobody can
tell such an id from a task which the exporter has not written yet.

Where no visibility window is configured, the push throws an ordinary failure instead of `PhaseTwoRetryLater`. A
window of zero would bring the entry back at once, again and again. The write itself is a command, so the engine
answers it. A `404` there means the scope ended between the search and the write, together with the task, and the
push is skipped with a WARN. Repeating it would meet the same answer until the entry is blocked.

What this costs: the everyday case, a read model a moment behind, is not answered in the first attempt. The entry
comes back one window later, ten seconds with the defaults. Where the row says that the task rests, the engine is
asked in that first attempt already.

**What was not chosen.**

- Treating "the search does not know this workflow" as an outage. It would stop the loss and the blocking. But it
  would also make every operation on a workflow which really does not exist wait for hours instead of failing at
  once. And it would end the fallback election of a migration, which depends on exactly that answer. Reading what
  VanillaBP itself wrote down about the start is cheaper, and it is the exact answer.
- Writing into the workflow's own scope when the task scope cannot be found. That is the lost update between
  iterations of a multi-instance subprocess, which the task scope exists to prevent.
- Searching again and again within the window, with a pause in between. Analysis `960` measured it with the
  exporter paused: two attempts of 10.1 seconds each, about 40 searches each. The answer at the end was right, but
  each push held a dispatching thread and a database connection while the exporter stood still. A short pause for
  the everyday case only has the same cost, and decision 49 of the platform makes no exception for a short one.
- A column for the key of the scope in the delivery log. The job of a user-task listener carries the element
  instance of the task, but not the one of the scope around it, so there is nothing to write. It would also be a
  schema change for the one case the model and the row already answer.
- Deriving the iteration from the job of the task alone. The context of a multi-instance can be nested (an
  iteration inside an iteration) and does not come from this one task, so the adapter reads the variable it writes
  for every iteration instead.
- Asking the engine with a longer `UpdateJobTimeout` for a job which does not rest. It would lengthen the lock of a
  handler instead of cutting it short, but it would still change a job nobody asked the adapter to change.

The measurement can be repeated: `analysis/895/bin/run.sh` builds the cluster, runs the load, takes the read model
away and prints whether every workflow ended exactly once. `Camunda8AggregateChangedIT` holds the task-scoped push
against the test cluster: into an open user task directly in its process, into a user task in an iteration of a
multi-instance subprocess, and into a task created while the exporter is paused.
`Camunda8TaskScopedPushWaitsForItsScopeTest` holds the rules without a cluster: no row or a closed row asks
nothing, a user task is asked as a user task, a task directly in its process is written without a search, a row of
another version is searched for, and a scope the query API does not know is given back at once, after one search.

### 65. A task-scoped push which cannot find its scope asks the engine before it gives up

*Merged into decision 64 on 2026-10-12, before the 2.0 release.* It said that a task-scoped push
which cannot find its scope asks the engine before it gives up.

### 66. A task-scoped push finds its task through the row of the delivery log, and asks the engine only about a task which rests

*Merged into decision 64 on 2026-10-12, before the 2.0 release.* It said that a task-scoped push
finds its task through the row of the delivery log, and asks the engine only about a task which
rests.

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

Why `0.0.0.0` and not `localhost`? The rule is: whatever Camunda's docker compose binds to.
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

SaaS is not affected. It has no default to fall back to, so its keys stay required. A SaaS adapter
which is not the first adapter of any module and has `deployment-failure: warn` still boots
degraded.

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

Why not the client's default? The client takes `http://0.0.0.0:8080` and `http://0.0.0.0:26500` on
every line. On 8.8 that REST port finds nothing, because the compose of that line publishes 8088.
An application which listens on 8080 itself then talks to itself. Following the compose of the line
makes the common case work without any address, and it removes the extra sentence decision 67 had to
add to the WARN on 8.8.

On 8.9 and 8.10 the compose takes host port 8080, which is also the default port of a Spring Boot or
Quarkus application. The application is the one which moves: it sets another `server.port` or
`quarkus.http.port`. The compose is Camunda's, and a default which pointed somewhere else would find
no cluster at all.

Why `localhost` and not `0.0.0.0`? Docker binds a port without a host IP to every interface, so both
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

*Superseded in part by decision 73: a process nobody claims is not looked at any more, so it keeps no WARN.*

The external form reference of a Camunda-managed user task is its task definition. The core finds
the `@WorkflowTask` method by it, and the job type of the lifecycle listeners is
`io.vanillabp.userTask:` plus the reference. A task definition is a name, and an expression
evaluated for each workflow names no method. So a reference written as a FEEL expression gets the
answer which decision 59 gives a job type written as one. A BPMN process which a `@WorkflowService`
class claims does not deploy, and a process which nobody claims gets one WARN per process. The same
mistake, reached in two ways, gets one answer.

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

This is a rule which refuses a model. If it came after the 2.0 release, it would stop an
application which boots today. That is why it comes with 2.0.

`Camunda8FormReferenceWrittenAsAnExpressionTest` holds the refusal under both modes, the WARN of
an unclaimed process and the quiet boot of a reference which is a name.

See [A form reference has to be a name as well](./README.md#a-form-reference-has-to-be-a-name-as-well).

### 70. The user-task listeners of a declared process id are read from the models the cluster holds

*Merged into decision 19 on 2026-10-12, before the 2.0 release.* It said that the user-task
listeners of a declared process id are read from the models the cluster holds.

### 71. A standard loop is refused in a claimed process, and a held version is warned about while workflows run on it

#### What was decided

Camunda 8 does not run a standard loop. An activity carrying `standardLoopCharacteristics` deploys
without a word, runs once, and the workflow moves on, with no error, no incident and no log line.
Measured on 8.8.39, 8.9.21 and 8.10.0-rc1. A model which counts on the loop does its work once, and
somebody finds out in production.

So the deployment reads the model for the marker before anything else, at any depth of its
subprocesses, and answers in two ways:

- A BPMN process a `@WorkflowService` class claims does not deploy. The message names the activity,
  the process, the workflow module, what the cluster does with the marker, and the two forms which
  do repeat an activity: a loop in the sequence flow, or a multi-instance element with
  `@MultiInstanceElement`, `@MultiInstanceIndex` and `@MultiInstanceTotal`.
- A version the cluster already holds is never refused, because nobody can change it. Where
  workflows still run on it, the start of the workflow module logs a WARN naming the version, the
  activity and how many workflows are on it. A version no workflow runs on any more is passed over.
  Where the cluster cannot count, the WARN says so instead of a number. The models come from the
  picture of decision 19, and a count is asked only for a version which carries the marker.

A process nobody claims is not looked at (decision 73). It is somebody else's model, and the file
travels to the cluster as a whole.

#### Why

The price is accepted on purpose: a model which deployed in version 1 does not deploy any more. The
upgrade is the moment somebody can still change the model, and the refusal is how they learn that
the loop never ran. Camunda 7 does the same with such a model, and its adapter refuses it the same
way, so a migration from Camunda 7 to Camunda 8 never stops at a model which was just as wrong on
Camunda 7.

This is a rule which refuses a model. If it came after the 2.0 release, it would stop an
application which boots today. That is why it comes with 2.0.

`Camunda8StandardLoopRefusalTest` holds the refusal, the unclaimed process which is not looked at,
the multi-instance element which must deploy, and which held versions are warned about.

See [A standard loop is refused while deploying](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki/Deviations#a-standard-loop-is-refused-while-deploying).

### 72. A listener nobody here serves is marked in the configuration, and an element template counts on a job element only

*Merged into decision 27 on 2026-10-12, before the 2.0 release.* It said that a listener nobody here
serves is marked in the configuration, that an element template counts on a job element only, and
what that means for user tasks and ad-hoc subprocesses.

### 73. A process nobody claims is left as it was modelled

Proposed by story 937. Decided by the maintainer on 2026-10-07.

The platform decided on 2026-10-07 that a process nobody claims is not supported, in a decision of
`adapter-platform-integration` of its own. The core ends the start over such a process unless the
application marks it with
`vanillabp.workflow-modules.<module>.workflows.<process>.implemented-externally=true`. So a process
nobody claims which reaches this adapter is one somebody else serves, and this adapter leaves it
alone:

- `wireBpmn` returns for it before anything else. No user-task listener, no start, end or cancel
  listener, no multi-instance mapping and no correlation key is written into its model, no worker
  is opened for its jobs, and its start messages are not reported to the core.
- No check ends the start or warns because of it. That ends the WARNs of decisions 24, 53, 54, 59, 69
  and 71 for a process nobody claims, the WARN about a listener nobody serves, and the wait for the
  cluster which decision 53 took to count the open jobs of such a process. The refusals for a
  claimed process stay as they were.
- The refusal of a file whose unclaimed process waits for a message without a correlation key is
  gone (decision 5). The cluster rejects such a file all the same, and its message says so. Writing
  a key into a process this application does not serve was ruled out before, and the boot now
  leaves the answer to the cluster.

What still reaches such a process is what belongs to the whole file: the prefix of `use-prefix`,
the checks of a file the cluster would refuse whoever serves its processes (a `start` listener on a
start event, a FEEL expression which carries the prefix already), and a message element the file
shares with a claimed process, which gets the correlation key of the claimed one.

Every question whether a process is claimed goes to `WorkflowTaskWiring.isClaimedByAWorkflowService`.
`aggregateIdNameOf` asks it first and is only there for the name of the variable.

The price: for an application which ran an earlier snapshot of 2.0, a file with a process nobody
claims is deployed as a new process version once, because the listeners and mappings this adapter
used to write into that process are gone. The maintainer accepted that.

`Camunda8UnclaimedProcessTest` holds the untouched model, the missing key, the shared message and
the missing workers. The tests of decisions 53, 59, 69 and 71 hold that a process nobody claims is
not looked at.

### 74. The key `fetch-variables` is gone, and a start which still sets it ends

Proposed by story 938. Decided by the maintainer on 2026-10-07 and 2026-10-09.

`vanillabp.adapters.<id>.fetch-variables: all` let a worker ask the cluster for every variable of
the process instance instead of the list of decision 8. It could be set at four levels, down to a
single task. It was there for a `@TaskParam` name the core cannot see because it is put together
while the delivery runs.

In VanillaBP 2.0 a handler reads its data from the workflow aggregate. A `@TaskParam` name which is
not on the method is not a case 2.0 has to support. A key is surface which cannot be removed after
the release without breaking every application which sets it, so it goes now:

- No worker reads the key. The list of decision 8 is the only answer, at every level.
- An application which still sets the key, with any value and at any of the four levels, does not
  start. The message names every key which sets it, says to remove it and says what a worker
  fetches now. The platforms still bind the key as text for this message only, the same way
  `async-task-timeout` is bound to be refused.
- The two messages of a delivery which misses a variable no longer name the key. The one for a
  `@TaskParam` outside the list points to the workflow aggregate.

What was checked before: no part of 2.0 needs `all`. The multi-instance variables are part of the
derived list, and a called process gets the chain of its caller through
`Camunda8MultiInstance.CHAIN_VARIABLE`, which every worker asks for. The worker of a start event the
cluster fires itself still asks for every variable, which is fixed in the code and was never this
key.

The Business Cockpit was the one user. On the Process-Engine-API adapter a `@TaskParam` of a
`@UserTaskDetailsProvider` got a variable which no `@WorkflowTask` method reads only through `all`.
On Camunda 8 such a parameter never gets a process variable, because the cockpit's own listener
workers ask for the aggregate's ID alone. So nothing changes on this adapter. A details provider
reads its data from the workflow aggregate, and the cockpit strand updates its documentation.

Version 1 had no such key, so `UPGRADE.md` does not mention it.

`Camunda8FetchVariablesTest#theRemovedKeyEndsTheStart` holds the message, and
`Camunda8RemovedFetchVariablesTest` of the Spring Boot and the Quarkus module holds that a start
with the key set at adapter and at task level ends with it. `Camunda8UnfetchedVariableTest` and
`Camunda8FetchVariablesTest#theMessagesNameTheWayOut` hold that no message names the key.

### 75. An extension asks the registry whether a process may be handed a chain

Proposed by story 946 for the Business Cockpit. Decided by the maintainer on 2026-10-09.

A process called by a call activity whose process is named by an expression inherits the
multi-instance chain of its caller at runtime, through the variable
`Camunda8MultiInstance.CHAIN_VARIABLE`. The deployment cannot know which process such a call
reaches, so `Registry#chainOf` stays empty for an element without an iteration of its own.

A worker of this adapter always asks for that variable, so the adapter itself never needs to know.
An extension which reads a task from the cluster's secondary storage and fetches its variables in a
second request has to know, or it never fetches the chain. `Registry#mayBeHandedAChain(String)`
answers it: `true` where a process of the same workflow aggregate calls this process by an
expression. It says that a chain may be there, not that it is.

The answer comes from the same record the adapter uses to accept a handed-down chain, so the two
cannot disagree. The method is in the sources every release line shares, so the public API of the
lines stays the same.

`Camunda8MultiInstanceTest#onlyAProcessCalledByAnExpressionMayBeHandedAChain` holds the answer for
a process called by an expression, a process called by name, the caller and an unknown process.

### 76. A called process gets no start listener and no end listener, and keeps the cancel listener for its tasks

*Merged into decision 27 on 2026-10-12, before the 2.0 release.* It said that a called process gets
no start listener and no end listener, and keeps the cancel listener for its tasks.

### 77. A setting of a called process is read under the called process' own id

Proposed by story 950. Decided by the maintainer on 2026-10-09.

The platform decided on 2026-10-09, in a decision of `adapter-platform-integration` of its own,
that a task belongs to the BPMN process which contains it. For a task of a called process that is
the called process. This adapter followed
the rule where the cluster hands a job to the application. A process service belongs to the
process at the top, though, so its requests name that process, and three places used that id for
something which belongs to a called process.

**The message time-to-live.** `message-time-to-live` resolves down to
`workflows.<process>.messages.<message>`. Whoever configures it for a message caught in a called
process writes it under the called process, but a correlation read it under the process at the top.
The cluster does not say which subscription will take a message, and a message may be published
before anything waits for it. So a subscription search cannot answer. The deployed models can. The
answer is the process of the call, if its own model waits for the message. Otherwise it is the one
other process of the workflow module whose model waits for it. Where no model does, or several
others do, the process of the call stays the answer, as before. A start by message reads the adapter
level only, as before.

**A push without a task.** `aggregateChanged(aggregate)` wrote only into the process instance at
the top. A called instance gets a copy of its caller's variables when the call activity starts it,
and nothing the caller is told later. Measured on 2026-10-09 against `camunda/camunda:8.10.0`
(`Camunda8CallActivityVariablesCanaryIT#aLaterVariableOfTheCallerDoesNotReachTheCalledInstance`):
a variable set on the caller after the call does not reach the called instance. So a gateway in a
called process decided on the values from the moment of the call. The push now also writes into
every running instance whose parent it wrote and which carries the aggregate's ID, level by level.
A called process without the ID has an aggregate of its own, and the walk stops there.

This half needs the search. The instance at the top is still written first, by the key of the start
row where there is one, so the read model never holds that write up (decision 64). A called
instance the exporter has not reported yet keeps the values of its call. A search which fails sends
the entry back to the outbox, which repeats the whole push; writing the same values twice is
harmless. Neither case was served before: the called instances never got the push at all.

**The viewer.** Under `use-prefix` the deployed model names the process behind a call activity
with its prefix, so the lookup in the deployment record, which keeps plain ids, found nothing and
the called process was left out. A definition only the cluster still holds was reported under its
prefixed id, while the deployed version was reported under its plain one. Every id the viewer
reports is plain now. It looks the plain id up among the processes which the workflow module
deploys or declares. It does not cut the prefix off a string. A prefix cut off that way drifts
apart from the one the deployment wrote, and an id which matches none of them is kept as it is.

**What stays, and is said instead.** A call activity saying `propagateAllParentVariables="false"`
is left as modelled, as decision 30 says. That keeps the aggregate's ID in the caller as well, so
every job of a called process sharing the aggregate fails. The adapter does not write an input
mapping of the ID there, for the reason decision 30 gives for the chain. The README section "A
called process", the wiki page `Configuration` and the message of a job without the ID now name the
call activity and the fix.

`Camunda8SettingsOfACalledProcessTest` holds the time-to-live and the push,
`Camunda8ViewerUnderAPrefixTest` the viewer, and `Camunda8FetchVariablesTest#theMessagesNameTheWayOut`
the message.

### 78. A task-scoped push searches for its scope once, and phase two does not sleep

*Merged into decision 64 on 2026-10-12, before the 2.0 release.*
It said that a task-scoped push searches for its scope once, and
that phase two does not sleep.

### 79. The issue of a red night quotes the step which broke, and one script reads the log for every repository

Built by story 1492 on 2026-10-14. It adds to the second rule of decision 31.

The issue of a red line quotes what the log of its job says. Until this story that was Maven's
`[ERROR]` lines, with a fallback here of the ten lines before the last `##[error]` of the log. The
Business Cockpit's Camunda 8 adapter had no fallback. On 2026-10-13 its line 8.10 went red in a
shell check whose reason was three lines of plain text, and the issue quoted nothing.

`bin/job-log-excerpt.sh` reads the log now. It finds the step which broke first, from its
`##[group]Run` header to the runner's `##[error]Process completed with exit code`. Steps with
`if: always()` run after a failure and can fail as well, but the first break is the cause. If that
step printed Maven's `[ERROR]` lines, those are the excerpt, as before. Otherwise the excerpt is
the step's own output, without the script GitHub echoes in front of it, at most the last thirty
lines. Only the failed step is read, so an `[ERROR]` which an earlier Maven step survived is not
quoted, and the clean-up of the job is not quoted either.

Every repository with such an issue uses this script: this one and the four Business Cockpit
repositories. They check this repository out and call it, the way the Business Cockpit's
Camunda 8 adapter already calls `bin/line-preview.sh`. Five copies of one pipeline had already drifted: one had a
fallback, four did not. Its self test runs in `checks.yaml`, with a Maven failure, a failing shell
check, a long output and a log without an error.

### 80. Every release line publishes its own snapshot

Built by story 988 on 2026-10-19.

A push to `main` publishes one snapshot per release line: `2.0.0-8.8-SNAPSHOT`,
`2.0.0-8.9-SNAPSHOT` and `2.0.0-8.10-SNAPSHOT`. The lines are the `line-*` profiles of the parent
POM, read the same way as in `line-matrix.yaml`, so there is no second list to keep. Nothing
publishes a plain `2.0.0-SNAPSHOT` any more.

The reason: a project has to be able to try a snapshot of its own line before that line is
released. Before this, only the current GA line had a snapshot. The 8.10 client needs a cluster
of at least 8.10, so a project on an 8.8 cluster had no snapshot it could use. The Business
Cockpit's Camunda 8 adapter had the same problem, because each of its lines read the same
snapshot of this adapter.

The snapshots stay in the GitHub Packages registry of this repository.

The default `<revision>` is `2.0.0-8.10-SNAPSHOT`, the current GA line. So a plain `mvn install`
builds the same coordinates the registry has. Another line names its version with `-Drevision`,
like a release does. A default of `2.0.0-${camunda8.line}-SNAPSHOT` would follow the profile by
itself, and Maven accepts it, but the Quarkus build stops on any placeholder inside `revision`.
So the default moves with the GA line when the lines rotate.

The job which publishes skips the tests. The pull request runs every line through the matrix, and
the nightly matrix runs them again. The job still compiles every line against that line's client
pin. The job `publish` still runs the tests of the current GA line on the push, because the
coverage report on GitHub Pages comes from them. A red test there does not stop the snapshots.

### 81. An incident with a stack trace starts with its causes, and the adapter cuts it to what the cluster keeps

Built by story 992 on 2026-10-21.

`report-errors-as-stack-trace` came with version 1.9.1 and is back, with the same name and the same
default `false`. Version 1 read it per workflow module. Here it is read at the four levels of every
scope-specific key, task, workflow, workflow module and adapter, and the most specific value wins.
It counts for every job which runs code of the application: a service task, a user-task
notification, a modelled listener, a start event the cluster fires and the end of a workflow.

**The text.** With `true`, `Camunda8Errors#incidentMessage(Throwable, boolean)` builds the text.
It starts with the chain of causes, one line per cause with type and message, the innermost cause
last. The chain takes at most half of the limit below. A longer chain keeps the outermost cause and
as many of the innermost ones as fit, and one line says how many were left out. The stack trace
follows, as Java prints it.

**The limit.** Neither the client nor the engine cuts the text. The client puts it into the fail
command as it is, over REST and over gRPC. The secondary storage cuts it, which is what Operate and
the search API read from. In the 8.10 artifacts every database the cluster can keep that storage
in has `errorMessage.size=4000`: H2, PostgreSQL, MySQL, MariaDB, SQL Server and Oracle. The incident
writer cuts the message to that many characters before it writes it. On Oracle it also cuts to
4000 bytes, because Oracle counts a column in bytes.

Measured on 2026-10-22 against `camunda/camunda:8.10.4` with H2 as secondary storage, over REST:
a text of 10000 or 40000 characters came back with 4000. A text of 10000 `ä` came back with 4000
characters, which are 8000 bytes, so H2 counts characters. A text of exactly 4000 characters came
back whole. The 8.8 and 8.9 lines were not measured, because their images could not be pulled in
time.

Elasticsearch and OpenSearch keep the whole text: the exporter of 8.8.42 only trims the white
space around it. So the limit belongs to a database as secondary storage, which the 8.9 and 8.10
lines use by default, and not to a release line. The adapter cuts on every line, because one
application does not know which storage its cluster uses.

The numbers come from the artifacts of the cluster: `camunda-db-rdbms-schema` 8.10.4 (the files
`db/vendor-properties/*.properties`), the `IncidentWriter` of `camunda-db-rdbms` 8.10.4 and the
`IncidentHandler` of `camunda-exporter` 8.8.42.

The adapter cuts the text itself, at the end of the stack trace, to
`Camunda8Errors.INCIDENT_MESSAGE_LIMIT` bytes in UTF-8. That is never more than 4000 characters
either, so every storage keeps the text whole. The last line says that it was cut. Version 1 sent the whole trace, and its README
warned that the cluster cut it after 4000 characters. With an exception of Spring, that cut took
away the cause, which sits at the bottom of the trace. Here the cause is at the top, and the cut is
at the bottom. `Camunda8IncidentMessageLengthCanaryIT` holds the cluster to the limit on every line.

**What gets no trace.** A `DeliveryOfAnUnknownWorkflowException` keeps its one line, whatever the
key says. It is a refusal of the core and not a defect of the code which ran. Its message names the
workflow and says what to do (decision 45).

### 82. The IDE learns every key from the metadata of the Spring Boot jar, and a test keeps it complete

Built by story 993 on 2026-10-21.

An IDE offers the keys of `application.yaml` and marks a key it does not know. It reads them from
`META-INF/spring-configuration-metadata.json` in the jars of the application. Until this story the
adapter shipped no such file, so every key of the adapter looked unknown. Version 1 had none either.

The Spring Boot module now writes the file in two parts:

- The configuration processor writes one entry for each map of `VanillaBpCamunda8Properties`:
  `vanillabp.adapters` and `vanillabp.workflow-modules`. Each entry names the type of the map's
  values. The processor never describes the keys below a map key, because the application chooses
  that key. An IDE which follows the value type finds the keys there, for every adapter id, workflow
  module, workflow, task and message. We expect IntelliJ IDEA to do this, but nobody tried it in an
  IDE yet.
- `additional-spring-configuration-metadata.json` is written by hand. It describes every key of the
  adapter level once more, under the adapter id `camunda8`. That is the id which needs no `type`
  key. These entries are the only place where a key gets a type, a
  description and a default which every IDE shows. The
  metadata format has no name for "any id", so the levels below a workflow module cannot get entries
  of their own. A key which is also read there names those levels in its description instead. A
  removed key is described as deprecated with level `error`, so the IDE marks it.
- A key of version 1 which this version does not read any more is described the same way, with the
  key to write instead where there is one: `task-timeout`, `poll-request-timeout`, `use-tenants`,
  `task-id-as-hex-string` and `vanillabp.allow-connectors`. The start warns about them
  (`Camunda8VersionOneKeys`), but nothing binds them. So the test lets an entry with level `error`
  stand without a field. Their old places below a workflow module cannot get entries, for the reason
  above.

`EveryKeyIsKnownToTheIdeTest` walks the overlay class the way the binder does. It fails when a key
has no entry, when an entry describes a key nobody reads, and when a type or a default in the file
differs from the code. So a new key needs its field in the overlay of both platforms and its entry
in the file, or the build is red.

The platform also writes an entry `vanillabp.adapters`, with a type of its own. Which entry an IDE
uses when two jars describe the same name was not tried in an IDE. The Quarkus module needs none of
this: it is a Quarkus extension, and its build writes a description of every key into
`META-INF/quarkus-config-doc/`.

### 83. A failed job is retried only where the model asks for it, and a user task which is ending rolls the completion back

Built by story 998 on 2026-10-28. Both halves bring back what version 1 did, which Stephan decided
on 2026-10-22.

**Retries.** A Camunda 8 job comes with three retries, unless the model writes another number in
the attribute `retries` of the `zeebe:taskDefinition`. Version 1 changed that from 1.7.1 on (V1
commit `89a30b9`, in every release up to 1.10.0). Where the element of a failed job has no
`retries` attribute, the adapter fails the job with no retries left, so the cluster raises an
incident at the first failure. Where the attribute is there, whatever its value, the job keeps one
retry less, as Camunda does on its own. The reason of version 1 still holds: a task is retried only
when somebody said it is safe to run it again.

The job does not tell the two cases apart. It carries three retries either way. So the answer
comes from the model of the process version the job belongs to: the model this boot deployed, or
the model the cluster holds for an older version (see decision 19). A model which cannot be read
leaves the decision to Camunda, and one line per element says so. The transaction of the failed
job was rolled back, so one more attempt does no harm, and the next failure asks the model again.
An incident instead would wait for an operator, because of a model nobody could read for a
moment. Version 1 read only the model it deployed, so an older version was judged by the newest
model. This adapter judges every version by its own model.

The rule covers the jobs of `@WorkflowTask` methods (`Camunda8JobHandler`). The listeners which
VanillaBP writes carry their retries in the model already. A listener somebody modelled keeps the
retries the cluster hands over.

The last sentence is superseded by decision 87: a listener somebody modelled follows the same rule
as a task.

**A user task which is ending.** The pre-commit check of `completeUserTask` sends an empty
update. For a task in `CANCELING` or `COMPLETING` the engine answers `409`, the same answer as for
a task which is still being created. Read alone, the transaction committed, and the completion
after the commit met a task which was gone. Nobody could tell the caller any more. Version 1
rolled back in these states (V1 commit `bf242ba`, released in 1.10.0). So after a `409` the check
asks the search for the state of the task. In `CANCELING`, `CANCELED`, `COMPLETING` and
`COMPLETED` it throws `TaskNotFoundException`, and the caller's transaction rolls back. In every
other state, and where the search cannot answer, the transaction commits as before. The state is
a value the cluster sends, not a sentence, so decision 16 holds. The search runs behind the
engine, so this narrows the window and does not close it. Version 1 had the same window.

### 84. An Apache HttpClient 5 which is too old is named when the client build fails, not by comparing versions

Built by story 999 on 2026-10-28.

The Camunda client talks REST through Apache HttpClient 5. The platform BOM of an application
manages its version, and an imported BOM beats what the client asks for. Spring Boot 4.0 manages
`httpclient5` 5.5. Every client this repository pins asks for 5.6, but only the clients of the 8.9
line call a method which 5.5 does not have. They fail with a `NoSuchMethodError` while the adapter
builds its client, so at startup. Nothing in that error names the dependency to pin.

**The adapter turns that error into a message, and it does nothing else.** `Camunda8ClientFactory`
builds the client through `Camunda8HttpClientRuntime`. A linkage error which names a class of
`org.apache.hc` becomes an `IllegalStateException`. Its message names the versions on the
classpath, the entries to add to the `dependencyManagement` of the application, and the properties
for `spring-boot-starter-parent`. Any other linkage error is thrown on as it was.

**The adapter does not compare version numbers.** A check of "5.5 is older than the 5.6 the client
asks for" would stop the 8.8 and the 8.10 line on Spring Boot 4.0. Both work there, measured with
the clients and BOMs of 2026-10-28. A rule which stops working applications is wrong. Waiting for
the real failure costs nothing, because the client is built at startup anyway. And if a later
client of the 8.8 or the 8.10 line starts to call something new, the same message appears without a
change here.

**The error is not the cause of the new exception.** Spring Boot has a failure analyzer for a
`NoSuchMethodError` anywhere in the causes, and its report would replace the message. So the
original error is added as a suppressed exception. Its stack trace stays in the log.

**The pin is the application's.** This is decision 39 again: nothing this repository pins reaches
an application. The README says which application has to pin what, in the section
[Apache HttpClient 5](./README.md#apache-httpclient-5).

### 85. Every map of the Quarkus configuration names its key, with the names all adapters share

Built by story 995 on 2026-10-28.

The Quarkus module is a Quarkus extension. Its build writes a description of every key into
`META-INF/quarkus-config-doc/quarkus-config-model.json` (see decision 82). A map is a level where
the application chooses the key, for example an adapter id. Quarkus writes such a key as a name in
quotes. Without help it takes the name of the Java method. So the file showed
`vanillabp.adapters."adapters".job-timeout`, and nobody could tell from it what to write there.

Now every map of `VanillaBpCamunda8Properties` carries `@ConfigDocMapKey`. The platform and every
adapter use the same names. The first three are also the names which the platform wiki writes in
angle brackets, for example `<adapter-id>`:

|        Map        |         Name         |
|-------------------|----------------------|
| `adapters`        | `adapter-id`         |
| `workflowModules` | `workflow-module-id` |
| `workflows`       | `bpmn-process-id`    |
| `tasks`           | `task-definition`    |
| `messages`        | `message-name`       |

So the key above now reads `vanillabp.adapters."adapter-id".job-timeout`.

`EveryMapKeyHasANameTest` reads the file which the build wrote. It fails when a map key has a name
which is not in this table. So a new map needs the annotation, or the build is red.

The Quarkus Tools plugin for IntelliJ IDEA (by Red Hat, checked in version 2.6.1) does not read this
file. It finds the keys in the `@ConfigMapping` interfaces themselves and writes every map key as
`{*}`. So the names help the generated documentation and everybody who reads the file. They do not
change what this plugin offers while you type.

### 86. A person who read a client bump says so with a commit, and that turns the red check green

Built by story 2008 on 2026-10-28.

On a GA line, `client-api-changes` is red when a client bump adds enum literals or abstract
interface methods. That stops the patch automerge, which is its purpose. The check is also
required by the ruleset of `main`, and the ruleset lets nobody bypass it. So the old advice "merge
by hand" could not work. A bump which added something could not merge at all, even after somebody
had read it.

Now `camunda8-client-read.properties` holds, per line, the last client a person read, written as
`line-<line>=<version>`. When a bump on a GA line adds something, the workflow asks
`bin/client-api-read.sh` whether that file names the new version or a newer release for the line.
If it does, the comment says so and the check is green. If not, the check stays red. The comment
and the log then name the exact entry to set. The person who read the additions pushes one commit
with that entry to the pull request's branch. The trigger `synchronize` runs the check again.

The file is read from the pull request's own checkout, so the commit on the branch is what counts.
A pre-release counts only when the entry names it exactly. A preview line is not blocked anyway.
A bump which adds nothing does not touch the file, so an entry may be older than its pin. The file
started with the pins of `main` on 2026-10-28. Those were read when they merged.

Two other ways were possible. A label `client-api-read` would have needed the trigger `labeled`
again, which once started this workflow four times for one commit. Admins in the bypass list of
the ruleset would have been the smallest change, but then nothing records who read what. The
commit records it in Git, without a bypass.

The commit has a price. Renovate stops updating a branch with a commit from somebody else, and it
does not merge that branch. The person who pushed the commit merges it. The README says what else
this means.

`businesscockpit-camunda8-adapter` keeps a file of the same name and format. There it records
which client was read against the cockpit's code, and its own pull request raises it.

### 87. A listener somebody modelled is retried only where the model asks for it, like a task

Built by story 2009 on 2026-10-28. Stephan decided it on 2026-10-28.

Decision 83 brought back the rule of version 1 for the job of a task. A listener somebody modelled
kept the retries the cluster hands over, which are three where the model writes none. Now the same
rule holds for such a listener. Stephan gave the reason: in VanillaBP, retries must be configured
explicitly. A task may write, and then a retry would be a mistake. A method which serves a listener
may write as well. An execution listener on `end` even writes into the process instance (see
decision 1). And a reader of a model should not need two rules.

So `Camunda8ModelledListenerHandler` fails the job of a listener with no retries left, unless the
listener writes `retries` in the model. A listener writes it on its own `zeebe:executionListener` or
`zeebe:taskListener`. The `zeebe:taskDefinition` of the element does not count, because it is about
the task and not about the listener. Where the attribute is there, whatever its value, the job keeps
one retry less and waits the configured `retry-backoff`.

The answer comes from the model of the job's process version, exactly as in decision 83, and a model
which cannot be read leaves the count to Camunda. The listener is found by its element and its job
type. That is enough, because the wiring refuses two served listeners of one element under one job
type.

Version 1 served only one kind of listener, a `zeebe:taskListener` of a `zeebe:userTask`, from 1.7.0
to 1.10.0. Every one of these releases failed the job of such a listener with no retries left. 1.7.0
failed every job that way. From 1.7.1 on it looked for `retries` on the `zeebe:taskDefinition`, and a
user task has none. So a listener without the attribute behaves as in version 1. A listener with it
now gets the retries it asks for.

A job failed with no retries left carries no `retryBackoff`, for a task, a listener or a job of
VanillaBP's own. There is no next attempt to delay.

### 88. Until Camunda fixes its gateway, the adapter advises against a lease, and its own tests keep one

Built by story 2002 on 2026-10-28.

The gateway of Camunda 8.10 parks an activation request which found no job. Its own timer answers
the request empty when the long poll runs out. If that timer fires while an activation for the
request is still on its way to the broker, the gateway cannot send the job. It then gives the job
back with a FAIL command, built in `RoundRobinActivateJobsHandler.toFailJobRequest`. That command
carries no lease token. So the broker refuses it for a leased job, and the job stays locked for
nobody until the lock of the lost activation runs out. The signature is `Failed to send 1 activated
jobs` followed by `Failed to reactivate job ... a matching lease token must be provided`.

**What was measured.** `Camunda8LongPollRaceIT` against `camunda/camunda:8.10.5`, on 2026-10-28.
It runs only when asked for (`-Dcamunda8.measure.long-poll-race=true`). The numbers are in the
README section [A job activated for a worker which never saw it](./README.md#a-job-activated-for-a-worker-which-never-saw-it).
Four of them decide this entry:

- A job which becomes free just before the long poll ends: 16 of 345 tries stuck with a lease, 0 of
  183 without one.
- A client which waits five seconds longer than the long poll instead of one: still 7 of 183 stuck.
  The client never ends the request first, so its HTTP timeout plays no part.
- A worker whose retry backoff equals its long poll, ten seconds each: 19 of 300 retries late by one
  or two locks with a lease, 0 of 300 without one.
- A long poll of 13 seconds against the same backoff: 3 of 300 late, from handlers which paused
  about three seconds. Two clocks of different length still meet, only at another handler duration.

So only the missing lease brings the rate to zero. A longer HTTP timeout and a long poll which
differs from the retry backoff do not. On 8.8 and 8.9 there is no lease, and the gateway's FAIL is
accepted. There the same race costs a few milliseconds (19 lost sends in 183 tries against
`camunda/camunda:8.9.26`, every job given back at once).

**The adapter advises, it does not decide.** Decision 36 stands: the application chooses, and
there is no default. The boot message, the README and the wiki now say that `do-not-use` is the
safer choice until Camunda fixes the gateway, and that switching to `use` later costs nothing. The
adapter does not switch the lease off on its own. That would overrule an application which chose
it. And the ratchet still holds on 8.10.5, measured in the same IT: after its lock ran out, a leased
job went to the next activation with a lease and not to one without. So an adapter which dropped
the lease by itself would strand the jobs which an application leased before.

**The tests of this repository keep `use`.** Many of them activate jobs with a lease themselves,
and they guard the code an application runs when it chooses the lease. A test whose wait is
shorter than one `job-timeout` can still meet the stall. `Camunda8JobLeaseIT` protects itself
with a short lock of its own.

**When Camunda fixes it**, run the IT with the lease against the fixed version. If no job is stuck,
remove the advice from the boot message, the README and the wiki, and mark this entry superseded.
