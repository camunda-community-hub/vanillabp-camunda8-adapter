# Upgrade notes

Contributor-facing list of what a VanillaBP 1 application on Camunda 8 has to do, organised per
version line. It describes the step to the release, not how the release was built - the development
history is in git. These entries feed the user-facing
[migration guide](https://github.com/vanillabp/adapter-platform-integration/wiki/Migrating-from-version-1);
the same file exists for
[VanillaBP itself](https://github.com/vanillabp/adapter-platform-integration/blob/main/UPGRADE.md)
and for the [Camunda 7 adapter](https://github.com/vanillabp/camunda7-adapter/blob/main/UPGRADE.md).

## 2.0

### The artifact version names the Camunda 8 minor

Visible to every consumer, because the coordinates change.

The adapter is published once per Camunda 8 minor, and the minor is part of the version:
`2.0.0-8.8`, `2.0.0-8.9`, and `2.0.0-8.10-rc<n>` for the preview line built against a
pre-release of the next minor. Which one you take is decided by your cluster, and the table in the
[wiki](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki) says which client
and which tested cluster each line stands for.

The reason is that Camunda promises a client against clusters of its own version and newer, and
nothing about the other direction. The client a build was compiled against is therefore the lowest
cluster version that build accepts. Without lines, the day the adapter used anything only an 8.10
cluster offers, every later bugfix would have been deliverable only together with a cluster upgrade,
and a Camunda 8 cluster upgrade costs more organizationally than technically.

What you have to do is add the suffix of your cluster's line to the adapter version in your POM, and
nothing else. The groupId, the artifactIds and the API stay as they are: the methods are identical on
every line, checked in the adapter's CI, so you never have to read a suffix to find out what exists.
Where your cluster cannot do something, the same method is there and fails with a message naming
your line.

Extend the preset the adapter ships and Renovate reads the suffix as a compatibility value it never
changes on its own, so no automatic update moves you to another cluster minor:

```json
{
  "extends": ["github>vanillabp/camunda8-adapter//renovate/camunda8-lines.json"]
}
```

Without it, plain maven versioning would eventually offer you a boundary crossing, because Maven
sorts `2.2.0-8.8` above `2.1.0-8.9`.

At startup each configured `camunda8` adapter id logs its release line and the client it was built
against, which is the lowest cluster version it accepts.

A line lives until the next minor goes GA, so two GA lines exist at a time plus the preview. When
8.10 goes GA, 8.9 becomes the previous GA and 8.8 ends, although Camunda supports 8.8 until April
2027. That is the project's policy rather than a technical limit.

### The cluster has to be one the adapter can search

The Camunda 8 adapter of 2.0 requires a cluster which answers searches, and it says so while it
deploys instead of running on and answering questions it cannot answer. Two things make a cluster
answer them: it brings secondary storage (`camunda.data.secondary-storage.type`, an Elasticsearch or
OpenSearch it exports to), and the credentials the adapter is configured with may read process
instances, process definitions, jobs, user tasks and element instances. Where either is missing the
cluster refuses every search with HTTP 403 and says which of the two it was in prose only, so the
message names both.

What the adapter uses a search for is the list a reader can check themselves against: locating a
workflow by its aggregate's id, which is what elects the BPMS of an operation and what a pushed
workflow aggregate is written by; resolving a version specification which names a
`zeebe:versionTag`; the viewer's element history and the definitions of earlier application
versions; and the startup report about the versions the cluster still holds. There is no second
behaviour for a cluster which refuses: no optimistic yes with a warning, no empty version list, no
viewer serving what this application version deployed.

Where you are coming from decides whether this is news.

From 1.7.0 or later your application already needed such a cluster and nobody wrote it down. It read
every active process definition of its tenant through the search API while it started, to parse the
BPMN of earlier deployments for the SPI wiring, with no property switching it off and no fallback
around it. A cluster refusing that search kept the application from starting, so what changes for you
is the message: a named requirement with both reasons and both ways out, instead of whatever the
first failing read said.

From 1.6.3 or earlier this is a real change. Those versions ran against the 8.6 client and kept the
metadata of earlier deployments in tables of the application's own database, which is what their
README said out loud, and they searched nothing. If your cluster is still one without secondary
storage, 2.0 does not serve it: give the cluster its secondary storage before you upgrade. The
tables are gone either way, 1.7.0 dropped them, so what answers the questions those tables used to
answer is the cluster, and it has to be askable.

An adapter which is not the first-priority adapter of a workflow module and carries
`vanillabp.adapters.<id>.deployment-failure: warn` boots degraded against such a cluster with a
guiding warning rather than ending the start. That is the way out for the old BPMS of a migration
AWAY from a cluster like this, and it takes one more property: an adapter which deployed nothing
cannot answer the BPMS election either, so a workflow module serving two adapters also needs
`vanillabp.workflow-modules.<id>.election.guessing-adapters: ACCEPTED`. The application says both,
one message per step, and comes up once both are set.

### Two tables of version 1 can be dropped by hand

Up to release 1.6.3 the Camunda 8 adapter kept its own record of what it had deployed, in the
tables `CAMUNDA8_DEPLOYMENTS` and `CAMUNDA8_RESOURCES`, on MongoDB in two collections of the same
names. It read them to get at the models of older process versions. Release 1.7.0 dropped that
record and 2.0 asks the cluster instead, so after the upgrade nothing reads them and nothing
writes them.

They hold every BPMN file your application ever deployed, so they are the one thing worth dropping
by hand. Do that once you no longer want what is in them. An application coming from 1.7.0 or later
never had them.

### Check your user task models, and finish what is open

Up to release 1.6.3 a user task was a plain BPMN user task served by a job worker, and its
`zeebe:formDefinition` named a `formKey`. Release 1.7.0 replaced that with a Camunda-managed user
task (`zeebe:userTask`) whose external form reference carries the task definition, and 2.0 serves
only that one.

So grep your models for `formKey` before you upgrade. Where one sits on a user task, change the
model: make the task a Camunda-managed one and set "External form reference"
(`zeebe:formDefinition externalReference`) to what the `formKey` said. VanillaBP then wires the
lifecycle listeners itself.

Finish or cancel the tasks which are still open on such an element before you upgrade, because
afterwards nothing can complete them. The id such a task hands out is a job key while the cluster
expects a user-task key, so `ProcessService#completeUserTask` cannot answer it, and no
notification arrives when the task is created or canceled.

The deployment says all this rather than failing over it. One WARN per BPMN process names the
elements it found and how many tasks are open on them right now. The model is valid, the workflow
runs, and an application may well serve such a task with a job worker of its own, so ending the
boot would be the wrong answer. Of the two numbers only the first is certain: the elements come
from the model this boot deploys, while the open tasks are a search, and a cluster which is not up
yet costs you that count.

### A task your cockpit showed while a check answered 404

If you have seen this under version 1 - the Business Cockpit shows a user task as active while a
check about that task answers `404` - then it was one of two things, and 2.0 has neither.

The first one is the index. Version 1 asked whether a user task still exists with a `UserTaskGet`,
which reads the cluster's searchable storage, and that storage is written by an exporter running
behind the engine. Measured on 2026-09-28 against `camunda/camunda:8.8.39`,
`camunda/camunda:8.9.21` and `camunda/camunda:8.10.0-rc1`, counting from the moment the
`creating` listener job of the task arrived, which is the moment your application and the cockpit
learn of it: the storage answered `404` for 1667 ms, 649 ms respectively 219 ms. Under load that
window is as wide as your exporter's backlog. 2.0 asks the partition instead, with an
`UpdateUserTask` carrying nothing but an audit action, and in the same runs that answered `204`
after 12 ms, 21 ms respectively 13 ms.

The second one is the shape of the task, and there the `404` never goes away. A user task served
by a job worker, which is what version 1 modelled up to its release 1.6.3, has no user-task
record in the cluster at all: in the same runs `UserTaskGet`, the empty `UpdateUserTask` and the
user-task search answered nothing but "not found" for a full minute, while the job command on the
same key was accepted and the engine said it still holds the instance. The id such a task hands
out is a job key, so every question asked as if it were a user task misses. 2.0 does not serve
that shape and says so while it deploys - see the section above, and finish what is open before
you upgrade.

### Your user-task models get one new process version

VanillaBP writes the lifecycle listeners of a Camunda-managed user task into the model it deploys.
Version 1 gave them `retries="0"`, 2.0 gives them `retries="1"`, so the file your first boot after
the upgrade deploys differs from the one the cluster holds and the cluster gives that process a new
version. Nothing of yours has to change for it, and your `@WorkflowTask` methods keep serving the same
tasks.

The retry is not a second attempt for a notification which failed. A failed notification is still
reported with no retries left, so the first failure raises the incident as before. It is what the
gateway hands back when it could not deliver the job to the worker it activated it for, and a job
without a retry dies of such a lost delivery: the cluster writes an incident and the user task
stands in `CREATING`, where no command reaches it any more.

Workflows you brought with you stay on the version they were started on, and their user tasks keep
the listeners version 1 deployed. A lost delivery of one of those still ends that way until the
workflow is over, and there is nothing to do about it other than letting them finish.

### A task id is decimal, and version 1's hexadecimal ids are data to migrate

Version 1 could hand out a task id in hexadecimal, through `task-id-as-hex-string`, which was off
by default. 2.0 reads task ids decimally everywhere and has no such setting, so there is no
property to move here. The ids an application stored while the setting was on outlive the upgrade,
and they sit in that application's own tables, so converting them is work on your data.

Where such an id reaches the adapter, the failure names the old setting. It is answered as a
permanent one, so the outbox entry is blocked after a single attempt instead of being retried ten
times against a key which will never become a number.

### `cancelUserTask` has no command on the cluster yet

No cluster up to 8.9 can cancel a Camunda-managed user task by BPMN error. The engine offers no
command for it. Throwing a BPMN error is job-based, and a Camunda-managed user task is no job;
version 1's workaround with a marker variable was broken by version 1's own admission. The call is
answered with a guiding error naming your release line.

What makes it possible are the listeners Camunda 8.10 brings, so the operation can only ever
arrive on a line built against 8.10 or later. Until then the way to take such a task away is the
model: give the user task an interrupting boundary event and let your application trigger it, for
example by correlating a message.

### Workflows you brought with you end in silence, so keep the old service task for now

Version 1 could not tell an application that a workflow had ended, so applications modelled a
service task in front of every end event. `@WorkflowEnded` replaces that and the old service task
keeps working, which makes the change one to make at leisure.

On Camunda 8 the moment matters all the same. The notification hangs off an execution listener
this adapter writes into the model it deploys, and a workflow which is already running stays on
the process version it was started on. A workflow you brought with you therefore triggers nothing.
Delete the old service task right after the upgrade and those workflows end without telling
anybody, so wait until they have ended.

The startup report about the versions the cluster still holds names it per old version, together
with the user-task notifications and the message correlation those models miss as well. Camunda 7
needs no such wait, because it attaches its listener while the engine parses a process definition,
which reaches every version that engine holds.

### `allow-connectors` moved under the adapter

Version 1 read `vanillabp.allow-connectors` at the root of the tree, with a workflow-module and a
workflow level below it. The key is back and does the same thing, but it sits where it belongs now:

```
vanillabp.adapters.<id>.allow-connectors
vanillabp.workflow-modules.<m>.adapters.<id>.allow-connectors
vanillabp.workflow-modules.<m>.workflows.<w>.adapters.<id>.allow-connectors
```

Connectors are a Camunda 8 concept and no other BPMS has anything to do with the marker, so the key
belongs to this adapter. The three levels are the ones version 1 had, and the default is `false` as
before. Four things an upgrading application has to act on.

The resolution changed direction. Version 1 held the flag in primitive booleans, so a more specific
level could only turn it ON: a global `true` plus a module `false` still yielded `true`. Now the
most specific configured value wins in both directions, like every other key of this adapter. If you
relied on the old OR, read your configuration once: a module or workflow which says `false` under an
adapter which says `true` now switches the rule off for itself.

A user task built from an element template stays wired. Version 1 passed a user task carrying
`zeebe:modelerTemplate` over together with the service tasks. This version does not. There is no
user-task connector: a `zeebe:userTask` is served by the cluster's task list, and a template on it
presets an assignee or a form. Passing it over would take away its lifecycle listeners, its CREATED
and CANCELED notifications and the ability of `ProcessService#completeUserTask` to complete it, for
a marker which says nothing about who serves the task. If a user task of yours was passed over in
version 1, it is wired here and needs a `@WorkflowTask` method or the model has to stop claiming it.

Every boot with the switch on writes a warning. Version 1 logged nothing at all near the property,
so switching it on was silent and a typo in a task definition was indistinguishable from an
intentional connector. This version writes one framed WARN per workflow module which allows
connectors, naming the key, the module, every element it handed over with its element template, and
what that costs. No key silences it, and that is deliberate: what it says stays true for as long as
the connector is in the model.

Under `use-prefix` a connector's job type stays unprefixed. Version 1 had no prefixing mode, so the
question could not arise there. Here the job type of such an element is left as the modeller wrote
it, because it names a runtime somebody else deployed cluster-wide. Under `by-adapter` the module is
kept apart by a tenant instead, and a connector runtime then has to be able to see that tenant,
which is a condition on your cluster rather than something the adapter arranges.

### A start waits for its cluster instead of failing at once

Version 1 ended a start which could not reach its Camunda 8 cluster at the first round it tried to
make, usually the deployment. This version waits: before that round the adapter asks the cluster for
its topology and gives it `vanillabp.adapters.<id>.startup-wait`, ten minutes by default, to answer.
The case this is for is a cluster booting together with the application, which lets every round of a
start fail rather than only the deployment.

What an application has to look at is the ten minutes. A deployment pipeline which expects a start
to fail fast against a cluster which is deliberately not there now waits them out; set
`startup-wait: PT0S` for such a setup and the behaviour is the one you had. Nothing waits silently in
the meantime: a line before the first attempt names the address and the deadline, and one every few
seconds carries the time gone and the cluster's last answer.

An answer the cluster will repeat ends the start at once rather than after the deadline. Which
answers those are is `Camunda8Errors`, the classification the whole adapter reads, so a rejected
request and a refused tenant fail as fast as before. A `401` is not among them, on purpose, because
the client refreshes an expired token, so wrong credentials are waited out with the `401` in every
line the wait writes.

Two more things are checked while the application boots, both about `request-timeout`. A value which
is not positive ends the boot, because there is no request without time. A value below one second is
a warning: it is the deadline of every request this adapter sends, the deployment of a workflow
module and every search included, so a healthy cluster answers too late and it reads like a network
problem.

### The `retryBackoff` task header of a model is read again

Version 1 let a Camunda 8 model name the backoff of a single element in the task header
`retryBackoff`, which is how it filled the gap Camunda 8 leaves next to Camunda 7's
`camunda:failedJobRetryTimeCycle`. The header is read, and no model has to be touched for that.

Two things are different from version 1. The header is read from the JOB rather than from the model
while deploying, so it also holds for process versions this application never deployed and no
redeployment is needed. And version 1 only looked at the header where the element's
`zeebe:taskDefinition` carried a `retries` attribute as well; that condition is gone, because whoever
models a backoff means it either way.

An application which already moved the value into the configuration has one thing to check. Where it
landed at the TASK level
(`vanillabp.workflow-modules.<m>.workflows.<w>.tasks.<t>.adapters.<id>.retry-backoff`), nothing
changes: that level still applies, and one line per element says so where the model disagrees with
it. Where it landed at the workflow, the workflow-module or the adapter level while the header stayed
in the model, the header applies. Both say something about one single task, so the more specific one
wins, and between two of the same reach the one you can change without deploying a new process
version does. So look at the models you migrated: a header you meant to retire has to leave the
model, not just the configuration.

A header which is no ISO-8601 duration costs a warning naming the workflow module, the BPMN process,
the element and the value, and then the configured value applies. Version 1 answered such a typo with
`Duration.ZERO`, which reads like "no backoff wanted" and hands the job out again at once. The
warning falls when a job of that element fails, once per element and not once per job, and
deliberately not while the application boots: a model deployed long ago cannot be corrected by the
boot which would complain about it.

The default changed: without a header and without configuration a failed job is handed out again
after ten seconds, where version 1 sent no backoff at all.

### A listener in your model has to be allowed now

Version 1 served a listener with a `@WorkflowTask` method and documented it nowhere. From its release
1.7.0 to its last one, 1.10.0, it did so in one place only: a `zeebe:taskListener` of a
`zeebe:userTask`, which needs a cluster of 8.8 or newer. The listener's `type` attribute was the task
definition verbatim, so a method named after it served the listener. A `zeebe:executionListener` was
never served on Camunda 8 at all.

If your models carry such a listener and a `@WorkflowTask` method of yours names its job type, this is
the entry to act on. This version does not serve it unless you say so, and a model carrying one ends
the boot with a message naming the elements, the key and what it costs. A listener whose job type no
method of yours names is left alone: a worker you run yourself may well be the answer. The boot names
it all the same, because the cluster creates that job either way and a workflow reaching the element
stands there. Say so per adapter, per workflow module or per workflow, and the most specific
configured value wins in both directions:

```yaml
vanillabp:
  adapters:
    camunda8:
      allow-listeners: true
  workflow-modules:
    loan-approval:
      adapters:
        camunda8:
          allow-listeners: false   # this module does not, whatever the adapter says
```

There is no task level for the key, because a task level is keyed by the task DEFINITION and whether a
listener becomes a task at all is what this key decides. A value written there earns one guiding
warning and the boot goes on.

The boot failure is the good case. Without the key there is no worker for the listener's job type,
the cluster creates the job all the same, and the workflow stops right there: no incident, no message,
nothing in any log. That is what an upgrade without the key would have bought you, found by whoever
noticed that workflows stopped arriving. The Camunda 7 adapter has the same key, where instead the
engine evaluates the listener's expression itself and the workflow fails at the element or runs a
method nobody meant for it.

Read what the key costs before you set it. A listener is where a BPMS lets an application in at a
moment the BPMS owns, and every BPMS draws that moment differently, so the model stops being portable:
another BPMS has no listener at this element and a migration of the model stops at the method serving
it. Every boot of a workflow module whose listeners are served writes a framed WARN saying it, naming
each listener and the way back, and no key silences it. Where you can, move what the listener does
into a task of the model with a `@WorkflowTask` method behind it, which is the way back the report
names.

A `zeebe:executionListener` is served as well now. Any element may carry one, so the door is wider
than version 1's, and the key is what keeps it shut by default. One placement is refused whatever the
key says: a `start` execution listener on a start event, because the cluster refuses the whole file
over it. Use `end` there, which is what VanillaBP attaches to a start event itself. Under the mode
`use-prefix` a served listener's job type is prefixed like every other task definition of the workflow
module, because that is what it has become.

`@TaskEvent` tells the method nothing any more. On version 1 a task-listener method could tell the
`canceling` event from the rest through that parameter. Now the event is part of the wiring: one method
serves one event of one element, the parameter receives `CREATED` for every listener because a method
without it subscribes to `CREATED` alone, and `TaskEvent.Event` has no value for a listener's event at
all. Drop the parameter where it only carried noise, and model one listener per event where a method
needs to know.

A `@TaskId` parameter is refused while the process is wired. The cluster completes a listener job
the moment the method returns, so such a task can never stay open and the id would complete nothing.
Version 1 accepted the method and the workflow went on without it.

What a listener method may write into the process instance depends on the listener. An execution
listener on `end` completes the way a task completes, so a method serving it may change the workflow
aggregate and a gateway behind the element decides on what it wrote. An execution listener on `start`
writes nothing into the instance: the cluster would keep those values local to the element, where
they shadow the process variables of the same name and swallow the element's own writes of that name,
so model a task of the process where something has to be written. A task listener writes nothing
either, because the cluster refuses a completion carrying variables and names its issue 23702. In the
latter two the change is kept by your application and reaches the cluster at the next real sync point
of that workflow. No method signature shows whether a method writes the aggregate, so the startup
report says this for every served listener. On Camunda 7 every listener writes the shared values onto
the execution inside the engine's own transaction.

Two listeners of one element under ONE job type end the boot naming both. Version 1 ran one of them
and which one was undefined, so the model said something it could not deliver. Give every listener of an
element a job type of its own and write a method per job type.

The [README section](https://github.com/vanillabp/camunda8-adapter/blob/main/README.md#listeners-somebody-modelled)
and the [BPMN model](https://github.com/vanillabp/camunda8-adapter/wiki/Configuration#the-bpmn-model-for-camunda-8)
section of the wiki carry the details.

### A handler asking for an item the model hands none over for ends the boot

`@MultiInstanceElement` reads the entry of the collection the current round is on. Camunda 8 gives
that entry a name only where the model writes it into the `inputElement` of the element's
`zeebe:loopCharacteristics`, which is the "Input element" field of the modeller. Version 1 left the
parameter at `null` once a job arrived, and nothing said why.

The deployment says it now. It reads the iterations around each wired task, asks the core which of
them a `@WorkflowTask` method wants the item of, and ends the boot where the two meet. The message
names the task, the element, the attribute the model would have to carry and the two ways out.

Either write the attribute:

```xml
<zeebe:loopCharacteristics inputCollection="=items" inputElement="item" />
```

Or drop the parameter. `@MultiInstanceIndex` and `@MultiInstanceTotal` are answered by every
multi-instance element, so a handler which only counts needs no change to its model.

Nothing else is refused. An element without an `inputElement` still deploys where no handler asks
for its item.

### An ad-hoc subprocess in your model earns two warnings

Version 1 said nothing about the element and neither executed nor reported it. This version serves
the flavour whose activities the model names through `zeebe:adHoc activeElementsCollection`, and it
says two things about a model carrying an ad-hoc subprocess which version 1 kept quiet about.

The element is named as a source of a second token, so a workflow aggregate without a version
attribute earns the warning about two writers on one aggregate, whichever flavour the model uses.

The flavour carrying a `zeebe:taskDefinition` of its own earns one WARN per BPMN process saying that
nothing serves it. That was true in version 1 as well; the difference is that it is said now.
Nothing is said about an element which also carries a `zeebe:modelerTemplate`, because a connector
runtime owns that one.

### Two workflow modules with the same BPMN process id end the boot

Two of your workflow modules may bring a BPMN process of the same id as long as the cluster keeps
the two modules apart. Where it does not, the second definition takes the identifier from the
first, and one of the two modules then runs on a model nobody deployed. Which of them it is, is
the cluster's decision. Nothing said so before, because a check per workflow module can only ever
see one of the two sides.

It is a boot failure now, raised while the SECOND of the two modules deploys, with the first one
already in the cluster. The message names both workflow modules, both process ids, the identifier
they share and the property which brought them together. Two configurations of a version-1
application do not boot after the upgrade:

- `vanillabp.adapters.<id>.tenant-id` is set, so every workflow module is deployed into that one
  tenant instead of into one named after it.
- `name-clash-avoidance` is `none` for both modules, where nothing is scoped at all. This is what
  a cluster without multi-tenancy leaves you with.

Nothing is wrong with your models, so renaming one of the two processes is only one of the ways
out. The way which keeps every other workflow module where it is: give one of the two a tenant of
its own. The name is settable per workflow module, which it was not in version 1.

```yaml
vanillabp:
  adapters:
    camunda8:
      tenant-id: shared-tenant
  workflow-modules:
    loan-approval:
      adapters:
        camunda8:
          tenant-id: loan-approval   # a scope of its own, for this workflow module
```

Where the shared tenant was not deliberate at all, dropping the adapter's name gives every module
a tenant named after it, which is the default. The remaining way out is
`name-clash-avoidance: use-prefix`, which drops the tenant and prefixes the identifiers with the
workflow module id instead. On a cluster without multi-tenancy that is the mode which keeps the
modules apart without asking anything of the cluster.

If you neither set a `tenant-id` nor use `none`, nothing changes for you: the default deploys each
workflow module into a tenant named after it, and under `use-prefix` the module id is part of
every identifier, so the two processes never meet.

### A start says which of your names the cluster already held

Version 1 compared the identifiers of a deployment against each other and said nothing about the
ones the cluster already held. This version asks the cluster about them, once per workflow module
while it deploys, and writes one WARN per module listing what it found. Nothing fails for it and no
property turns it off.

What is asked about is the BPMN process ids of the module, in one search, and its DMN decision ids,
one search each. Nothing is asked about message names, signal names, error codes, escalation codes
or job types, because the cluster keeps no index of those.

The line you are most likely to read is about a process you RENAMED. A cluster records no owner of
a definition, so the only marker the adapter has is the resource a definition was deployed from: a
definition of yours which came from a file you have since renamed looks like somebody else's. The
message says that it cannot tell the two apart, and the version and the definition key it names are
what you look the definition up by. Where the finding is your own old file, deleting that definition
from the cluster ends the line, which is the same remedy the report about an old process version
asks for.

A finding under the mode `none` is worth reading twice. Nothing is prefixed and no tenant separates
anybody there, so a second application on the same cluster really does share the name, and which of
the two a start reaches is the cluster's decision and not yours. The ways out are a tenant
(`name-clash-avoidance: by-adapter`), a prefix (`use-prefix`) or a name nobody else uses.

Two more lines can appear, and both are about your own application. Two workflow modules which
declare the same message name, signal name, error code, escalation code, job type or decision id are
named with both sides, because under `none` and under one adapter-wide `tenant-id` the cluster sees
one name where you mean two. The same is said where a version the cluster still holds carries such a name,
which is the clash a workflow module deployed years ago leaves behind.

### A restart waits a few seconds longer, and the application after it does not

No new property, and nothing to configure. What changes is how long a shutdown takes and how quickly
the next start gets its first job.

A worker asks the cluster for work with a long poll which waits at the cluster for up to
`request-timeout`, ten seconds by default. Closing the worker does not cancel that request, and
neither does closing the client. Measured against `camunda/camunda:8.9.16` with the plain Camunda
client: a job created while such a request is still parked is handed to it, counts as activated and
is answered by nobody, so the worker of the application which is running by then sees it only once
`job-timeout` expired. With seven seconds between the two applications that was the full lock in all
twenty runs; with twelve seconds, beyond the request window, twenty milliseconds. It is the REST
transport, which is the default: the same scenario over gRPC, and over REST with `stream-enabled`,
delivers in milliseconds.

The shutdown of a workflow module therefore waits for its workers to be released before the client is
closed, within the `shutdown-grace` it already had. In those runs the wait cost 8,2 to 8,5 seconds
and turned a first job of 20 seconds into one of 30 milliseconds. Two things follow for an
application:

- an ordinary restart takes those seconds longer. `shutdown-grace` (default `PT20S`) bounds it, and
  it still sits below the shutdown budgets of Spring Boot and Kubernetes. `PT0S` waives the wait
  together with the handler drain,
- a process which is killed rather than asked to stop cannot pay it, so a workflow started within ten
  seconds of a `SIGKILL` may still wait for its lock. A shorter `job-timeout` bounds what that costs
  where restarts are frequent, and `stream-enabled: true` or `prefer-rest-over-grpc: false` avoids
  the case altogether,
- an application with more workers than its client has HTTP connections pays the request window more
  than once. The requests of the surplus workers are queued in the client and go out as connections
  free, so the workers are rounds of the pool and every round costs a `request-timeout`. `PT20S`
  carries one round and not two. The start says so, naming the rounds, the grace the drain needs and
  the pool which would make them one round again; how many workers an application opens and how to
  size the pool is on the wiki page
  [Sizing](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki/Sizing).

The line the shutdown writes says how many workers were closed and whether the cluster released them,
and it warns where one of them still holds its request when the grace passes.
