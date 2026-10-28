# Upgrade notes

A list for contributors: what a VanillaBP 1 application on Camunda 8 has to do, organised per
version line. It describes the step to the release, not how the release was built. The development
history is in git. These entries feed the user-facing
[migration guide](https://github.com/vanillabp/adapter-platform-integration/wiki/Migrating-from-version-1);
the same file exists for
[VanillaBP itself](https://github.com/vanillabp/adapter-platform-integration/blob/main/UPGRADE.md)
and for the [Camunda 7 adapter](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/blob/main/UPGRADE.md).

## 2.0

### The artifact version names the Camunda 8 minor

Visible to every consumer, because the coordinates change.

The adapter is published once per Camunda 8 minor version, and the minor is part of the version:
`2.0.0-8.8`, `2.0.0-8.9` and `2.0.0-8.10`. Your cluster decides which one you take. The table in
the [wiki](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki) says which
client and which tested cluster each line stands for.

The reason: Camunda promises that a client works with clusters of its own version and newer. It
promises nothing for the other direction. So the client a build was compiled against is the lowest
cluster version that build accepts. Without lines, there would be a problem from the day the adapter
used anything which only an 8.10 cluster offers. From then on, every bugfix could only be delivered
together with a cluster upgrade. And a Camunda 8 cluster upgrade costs more in organization than in
technology.

All you have to do is add the suffix of your cluster's line to the adapter version in your POM. The
groupId, the artifactIds and the API stay as they are. The methods are identical on every line, and
the adapter's CI checks that. So you never have to read a suffix to find out what exists. If your
cluster cannot do something, the method is still there and fails with a message naming your line.

Extend the preset the adapter ships. Renovate then reads the suffix as a compatibility value which
it never changes on its own, so no automatic update moves you to another cluster minor:

```json
{
  "extends": ["github>camunda-community-hub/vanillabp-camunda8-adapter//renovate/camunda8-lines.json"]
}
```

Without the preset, plain Maven versioning would sooner or later offer you an update to another
line, because Maven sorts `2.2.0-8.8` above `2.1.0-8.9`.

At startup each configured `camunda8` adapter id logs its release line and the client it was built
against. That client is the lowest cluster version it accepts.

All three lines get bugfixes. New features land on 8.10. 8.8 and 8.9 get fixes for as long as
they are carried. The project does not end a line on the day the next minor goes GA (general
availability). Camunda supports 8.8 until April 2027. Ending the line would mean telling a cluster
which is still supported to upgrade before it can get the next fix.

### The cluster has to be one the adapter can search

The Camunda 8 adapter of 2.0 requires a cluster which answers searches. It says so while it
deploys. It does not run on and then fail at questions it cannot answer. A cluster answers searches
if two things are true:

- it has secondary storage (`camunda.data.secondary-storage.type`, an Elasticsearch or OpenSearch
  it exports to);
- the credentials the adapter is configured with may read process instances, process definitions,
  jobs, user tasks and element instances.

If either is missing, the cluster refuses every search with HTTP 403. Only the prose of its answer
says which of the two it was, so the message names both.

Here is what the adapter uses a search for, so you can check your case against it:

- finding a workflow by the id of its aggregate. The BPMS election of an operation uses this, and
  so does the write of a pushed workflow aggregate;
- resolving a version specification which names a `zeebe:versionTag`;
- the viewer's element history and the definitions of earlier application versions;
- the startup report about the versions the cluster still holds.

For a cluster which refuses, there is no second behaviour: no optimistic yes with a warning, no
empty version list, no viewer which serves only what this application version deployed.

Where you are coming from decides whether this is news.

From 1.7.0 or later, your application already needed such a cluster. Nobody wrote it down. While
it started, it read every active process definition of its tenant through the search API. It parsed
the BPMN of earlier deployments for the SPI wiring. No property switched that off, and there was no
fallback. A cluster which refused that search kept the application from starting. So only the
message changes for you. It now names the requirement, both reasons and both ways out. Before, you
got whatever the first failing read said.

From 1.6.3 or earlier, this is a real change. Those versions ran against the 8.6 client. They kept
the metadata of earlier deployments in tables of the application's own database, as their README
said, and they searched nothing. If your cluster still has no secondary storage, 2.0 does not serve
it. Give the cluster its secondary storage before you upgrade. The tables are gone either way,
because 1.7.0 dropped them. So the cluster now answers the questions those tables used to answer,
and it has to be able to answer them.

Some adapters boot degraded against such a cluster, with a warning which says what to do, and do
not end the start. That is an adapter which is not the first-priority adapter of a workflow module
and has `vanillabp.adapters.<id>.deployment-failure: warn`. It is the way out for the old BPMS of a
migration AWAY from a cluster like this. It needs one more property. An adapter which deployed
nothing cannot answer the BPMS election either. So a workflow module served by two adapters also
needs `vanillabp.workflow-modules.<id>.election.guessing-adapters: ACCEPTED`. The application asks
for both, one message per step, and comes up once both are set.

### Two tables of version 1 can be dropped by hand

Up to release 1.6.3 the Camunda 8 adapter kept its own record of what it had deployed, in the
tables `CAMUNDA8_DEPLOYMENTS` and `CAMUNDA8_RESOURCES`, on MongoDB in two collections of the same
names. It read them to get the models of older process versions. Release 1.7.0 dropped that
record, and 2.0 asks the cluster instead. So after the upgrade nothing reads or writes them.

They hold every BPMN file your application ever deployed. That makes them the one thing worth
dropping by hand. Do that once you no longer want what is in them. An application coming from 1.7.0
or later never had them.

### Check your user task models, and finish what is open

Up to release 1.6.3, a user task was a plain BPMN user task served by a job worker. Its
`zeebe:formDefinition` named a `formKey`. Release 1.7.0 replaced that with a Camunda-managed user
task (`zeebe:userTask`). Its external form reference carries the task definition. **2.0 does not
accept the older shape at all.** This is not about what gets served. A user task without
`zeebe:userTask` is served by a job worker, and this adapter takes a user task only if the
cluster manages it.

So grep your models for `formKey` before you upgrade. Where one sits on a user task, change the
model: make the task a Camunda-managed one and set "External form reference"
(`zeebe:formDefinition externalReference`) to what the `formKey` said. VanillaBP then wires the
lifecycle listeners itself.

That grep finds what version 1 served, but not every element this applies to. A user task without
any `formKey` is a plain BPMN user task too, and the cluster treats it exactly the same way. It
hands out a job of `io.camunda.zeebe:userTask`, which VanillaBP 2 does not fetch. So the workflow
waits at the element until the job's retries are used up. Measured on 2026-09-28 against
`camunda/camunda:8.9.21` and `camunda/camunda:8.10.0-rc1`: the model is accepted, the job appears,
and the cluster keeps no user-task record for such a task at all. The way out is the one above. If
you want a search which finds both, look for `bpmn:userTask` elements whose extension elements have
no `zeebe:userTask` in them.

Before you upgrade, finish or cancel the tasks which are still open on such an element. Afterwards
nothing can complete them. Such a task hands out a job key as its id, but the cluster expects a
user-task key. So `ProcessService#completeUserTask` cannot answer it, and no notification arrives
when the task is created or canceled.

**Your application does not boot until those models are changed, and that is on purpose.** A
`@WorkflowService` class which claims a BPMN process says that your application stands in for that
process. So the deployment refuses a process with a shape this adapter does not take. The message
names the process, the elements, which of the two shapes each element has, and the ways out. If a
worker of your own serves the element, give the element a `zeebe:taskDefinition` naming that
worker's job type. The element is then yours to serve, not a user task of VanillaBP, and the check
skips it.

A BPMN process which no `@WorkflowService` class of yours claims is not checked, once you marked it
as somebody else's. See [A process none of your classes
claims](#a-process-none-of-your-classes-claims).

The message says how many tasks are open on the elements right now. Only the count of elements is
certain. The elements come from the model this boot deploys. The open tasks come from a search of
the cluster's index. The search asks how many jobs of the element's job type the process has, and
how many of those the index has already seen end. The message names the difference. This number
falls to zero as you work off the tasks. The plain total of the search never did. The number is
close, not exact. An exporter feeds the index, and the index runs behind the engine. A task which
finished a moment ago can still be counted, and one which opened a moment ago can still be missing.
The boot does not wait for the cluster before it counts. The model is the cause, and a wait would
only put a message about the cluster in front of it. If the cluster is not up yet, the message has
no number, and it says so instead of writing a zero.

### A task your cockpit showed while a check answered 404

Maybe you have seen this under version 1: the Business Cockpit shows a user task as active, while
a check about that task answers `404`. Then it was one of two things, and 2.0 has neither.

The first one is the index. Version 1 asked with a `UserTaskGet` whether a user task still exists.
That reads the cluster's searchable storage, and an exporter writes that storage behind the engine.
We measured this on 2026-09-28 against `camunda/camunda:8.8.39`, `camunda/camunda:8.9.21` and
`camunda/camunda:8.10.0-rc1`. We counted from the moment the `creating` listener job of the task
arrived. That is the moment your application and the cockpit learn of the task. The storage
answered `404` for 1667 ms, 649 ms and 219 ms, in that order. Under load the window is as wide as
your exporter's backlog. 2.0 asks the partition instead, with an `UpdateUserTask` which carries
nothing but an audit action. In the same runs that answered `204` after 12 ms, 21 ms and 13 ms.

The second one is the shape of the task, and there the `404` never goes away. Up to release 1.6.3,
version 1 modelled user tasks served by a job worker. Such a task has no user-task record in the
cluster at all. In the same runs, `UserTaskGet`, the empty `UpdateUserTask` and the user-task
search answered nothing but "not found" for a full minute. At the same time the job command on the
same key was accepted, and the engine said it still holds the instance. Such a task hands out a job
key as its id, so every question which treats it as a user task misses. 2.0 does not serve that
shape and says so while it deploys. See the section above, and finish what is open before you
upgrade.

### Your user-task models get one new process version

VanillaBP writes the lifecycle listeners of a Camunda-managed user task into the model it deploys.
Version 1 gave them `retries="0"`, 2.0 gives them `retries="1"`. So the file your first boot after
the upgrade deploys differs from the one the cluster holds, and the cluster gives that process a new
version. Nothing of yours has to change for it, and your `@WorkflowTask` methods keep serving the
same tasks.

The retry is not a second attempt for a notification which failed. A failed notification is still
reported with no retries left, so the first failure raises the incident as before. The retry is
what the gateway hands back when it could not deliver the job to the worker it activated it for.
A job without a retry dies of such a lost delivery: the cluster writes an incident, and the user
task stays in `CREATING`, where no command reaches it any more.

Workflows you brought with you stay on the version they were started on. Their user tasks keep the
listeners which version 1 deployed. A lost delivery on one of those still ends that way until the
workflow is over. You cannot do anything about it except let those workflows finish.

### A task id is decimal, and version 1's hexadecimal ids are data to migrate

Version 1 could hand out a task id in hexadecimal, with `task-id-as-hex-string`. It was off by
default. 2.0 reads task ids as decimal numbers everywhere and has no such setting, so there is no
property to move. The ids an application stored while the setting was on survive the upgrade. They
sit in the application's own tables, so converting them is work on your data.

If such an id reaches the adapter, the failure names the old setting. It counts as a permanent
failure. So the outbox entry is blocked after a single attempt. It is not retried ten times
against a key which will never become a number.

### `cancelUserTask` has no command on the cluster yet

No cluster up to 8.9 can cancel a Camunda-managed user task by BPMN error. The engine offers no
command for it. Throwing a BPMN error is job-based, and a Camunda-managed user task is not a job.
Version 1 had a workaround with a marker variable, and version 1 itself said it was broken. The
call is answered with an error which names your release line and says what to do.

The listeners which Camunda 8.10 brings make it possible. So the operation can only ever arrive on
a line built against 8.10 or later. Until then, use the model to take such a task away: give the
user task an interrupting boundary event and let your application trigger it, for example by
correlating a message.

### Workflows you brought with you end in silence, so keep the old service task for now

Version 1 could not tell an application that a workflow had ended. So applications modelled a
service task in front of every end event. `@WorkflowEnded` replaces that, and the old service task
keeps working. So you can make the change whenever it suits you.

On Camunda 8 the timing still matters. The notification comes from an execution listener which
this adapter writes into the model it deploys. A workflow which is already running stays on the
process version it was started on. So a workflow you brought with you triggers nothing. If you
delete the old service task right after the upgrade, those workflows end without telling anybody.
So wait until they have ended.

The startup report about the versions the cluster still holds names this for each old version. It
also names the user-task notifications and the message correlation which those models miss.
Camunda 7 needs no such wait. It attaches its listener while the engine parses a process
definition, and that reaches every version the engine holds.

### Every key of version 1, and where it went

Version 1 read the keys of this adapter below `vanillabp.workflow-modules.<m>.adapters.camunda8`,
and some of them also below a workflow (`.workflows.<w>`) and a task (`.workflows.<w>.tasks.<t>`).
In 2.0 the section is named after the adapter id, not after the BPMS. If you name your adapter
`camunda8`, the paths stay as they were. The table says what became of each key. "All four levels"
means the adapter level `vanillabp.adapters.<id>` plus the three levels version 1 had.

|        Key of version 1        |                                                  In 2.0                                                  |
|--------------------------------|----------------------------------------------------------------------------------------------------------|
| `tenant-id`                    | Same name, at the workflow-module level. The adapter level is new.                                       |
| `use-tenants`                  | Gone. `true` is the default now. For `false` write `vanillabp.adapters.<id>.name-clash-avoidance: none`. |
| `task-id-as-hex-string`        | Gone, see "A task id is decimal" above.                                                                  |
| `report-errors-as-stack-trace` | Same name and default, at all four levels. See the next section.                                         |
| `task-timeout`                 | Renamed to `job-timeout`, at all four levels.                                                            |
| `retry-backoff`                | Same name, at all four levels.                                                                           |
| `poll-interval`                | Same name, at the adapter level only: `vanillabp.adapters.<id>.poll-interval`.                           |
| `poll-request-timeout`         | Renamed to `request-timeout`, at the adapter level only.                                                 |
| `stream-enabled`               | Same name, at the adapter level only.                                                                    |
| `stream-timeout`               | Same name, at the adapter level only.                                                                    |
| `allow-connectors`             | Moved into the adapter section, see "`allow-connectors` moved under the adapter" below.                  |

You do not have to find these keys yourself. Spring Boot and Quarkus both ignore a key they do not
know, so a key of version 1 would do nothing without a word. So the start writes one WARN for each
key of the table which 2.0 no longer reads at that place. The WARN names the key you wrote and the
key to write instead. The start goes on, because each of these keys only tunes something which
works without it. Only keys in a configuration file are found this way. An environment variable
spells a key in a form which cannot be taken apart again without guessing.

### `report-errors-as-stack-trace` writes the causes first

Version 1.9.1 added `report-errors-as-stack-trace`. With `true`, the incident of a failed job
carries the whole stack trace of what your code threw, and not only its message. 2.0 reads the key
at the same place, with the same default `false`. It can also be set for the adapter, for one
workflow and for one task.

The text looks different. Version 1 wrote the stack trace as Java prints it, and the version 1
README warned about the length: a long trace was cut at the end, and with an exception of Spring
that often took away the cause. 2.0 starts the text with the chain of causes, one line per cause,
with type and message, and the innermost cause last. The stack trace follows. Where the text is
longer than the 4000 characters the cluster keeps, the adapter cuts the end of the stack trace
itself and says so in a last line. So the cause is always in the incident.

The key now covers every job which runs your code: a service task, a user-task notification, a
listener somebody modelled, a start event the cluster fires and the end of a workflow. A job the
core refuses because it belongs to a workflow this application does not own keeps its one-line
message. That message already says everything there is to say.

### `allow-connectors` moved under the adapter

Version 1 read `vanillabp.allow-connectors` at the root of the tree, with a workflow-module and a
workflow level below it. The key is back and does the same thing, but it sits where it belongs now:

```
vanillabp.adapters.<id>.allow-connectors
vanillabp.workflow-modules.<m>.adapters.<id>.allow-connectors
vanillabp.workflow-modules.<m>.workflows.<w>.adapters.<id>.allow-connectors
```

Connectors are a Camunda 8 concept, and no other BPMS has anything to do with the marker. So the
key belongs to this adapter. The three levels are the ones version 1 had, and the default is
`false` as before. An upgrading application has to act on four things.

The resolution changed direction. Version 1 held the flag in primitive booleans, so a more specific
level could only turn it ON: a global `true` plus a module `false` still gave `true`. Now the most
specific configured value wins in both directions, like every other key of this adapter. If you
relied on the old OR, read your configuration once. A module or workflow which says `false` under
an adapter which says `true` now switches the rule off for itself.

A user task built from an element template stays wired. Version 1 handed a user task with
`zeebe:modelerTemplate` over to connectors, together with the service tasks. This version does
not. There is no user-task connector. The cluster's task list serves a `zeebe:userTask`, and a
template on it presets an assignee or a form. Handing it over would take away its lifecycle
listeners, its CREATED and CANCELED notifications, and the ability of
`ProcessService#completeUserTask` to complete it. All that for a marker which says nothing about
who serves the task. If version 1 handed over a user task of yours, it is wired here. It needs a
`@WorkflowTask` method, or the model has to stop claiming it.

Every boot with the switch on writes a warning. Version 1 logged nothing at all about the
property. So switching it on was silent, and you could not tell a typo in a task definition from
an intentional connector. This version writes one framed WARN per workflow module which allows
connectors. It names the key, the module, every element it handed over with its element template,
and what that costs. No key silences it, and that is deliberate: what it says stays true for as
long as the connector is in the model.

Under `use-prefix` the job type of a connector gets no prefix. Version 1 had no prefixing mode, so
the question did not come up there. Here the job type of such an element stays as the modeller
wrote it, because it names a runtime which somebody else deployed for the whole cluster. Under
`by-adapter` a tenant keeps the module apart instead. A connector runtime then has to be able to
see that tenant. That is a condition on your cluster, not something the adapter arranges.

### A start waits for its cluster instead of failing at once

If version 1 could not reach its Camunda 8 cluster, the start ended at the first round trip it
tried, usually the deployment. This version waits. Before that round trip, the adapter asks the
cluster for its topology. It gives the cluster `vanillabp.adapters.<id>.startup-wait` to answer,
ten minutes by default. This is meant for a cluster which boots together with the application.
Such a cluster lets every round trip of a start fail, not only the deployment.

An application has to look at the ten minutes. Maybe a deployment pipeline expects a start to fail
fast against a cluster which is deliberately not there. It now waits the ten minutes. Set
`startup-wait: PT0S` for such a setup, and you get the old behaviour. Nothing waits silently in the
meantime. A log line before the first attempt names the address and the deadline. Every few
seconds another line shows the time passed and the cluster's last answer.

An answer which the cluster will repeat ends the start at once, not after the deadline.
`Camunda8Errors` decides which answers those are. The whole adapter uses this classification, so a
rejected request and a refused tenant fail as fast as before. A `401` is deliberately not among
them, because the client refreshes an expired token. So with wrong credentials the start waits
until the deadline, with the `401` in every line the wait writes.

Two more things are checked while the application boots, both about `request-timeout`. A value
which is not positive ends the boot, because there is no request without time. A value below one
second causes a warning. It is the deadline of every request this adapter sends, including the
deployment of a workflow module and every search. So a healthy cluster answers too late, and that
looks like a network problem.

### The `retryBackoff` task header of a model is read again

Version 1 let a Camunda 8 model set the backoff of a single element in the task header
`retryBackoff`. That filled a gap: Camunda 8 has nothing like Camunda 7's
`camunda:failedJobRetryTimeCycle`. The header is read again, and no model has to change for that.

One thing is different from version 1. The header is read from the JOB, not from the model while
deploying. So it also holds for process versions this application never deployed, and no
redeployment is needed. Like in version 1, the header only matters where the element's
`zeebe:taskDefinition` has a `retries` attribute. Without it, a failed job is not retried at all,
see the next section.

### A failed task is retried only where your model writes `retries`

A Camunda 8 job comes with three retries, unless the model writes another number in the attribute
`retries` of the `zeebe:taskDefinition`. This adapter fails the job of a `@WorkflowTask` method with
no retries left where the element has no such attribute. So the cluster raises an incident at the
first failure. Where the attribute is there, whatever its value, the job keeps one retry less, as
Camunda does on its own.

What this means for you depends on the version you come from:

- Coming from 1.7.1 up to 1.10.0, nothing changes. Version 1 did the same since 1.7.1. One detail is
  better now: version 1 read only the model it deployed, so a workflow of an older process version
  was judged by the newest model. This adapter reads the model of the version the job belongs to.
- Coming from 1.7.0 or older, a task which used to get three attempts now gets one. Look for the
  service tasks whose handler can fail for a reason which passes, like a remote system which does
  not answer. Write `retries="3"` (or the number you want) on their `zeebe:taskDefinition`, and a
  `retryBackoff` header or `retry-backoff` where the next attempt should wait. Do not add it where
  running the handler a second time is not safe. On Camunda 8 a changed model is a new process
  version. Workflows already running stay on their old version and its old model.

One case is easy to miss: tasks which run at the same time and write one workflow aggregate with a
version attribute. When two of them commit at once, one loses with an optimistic locking error. It
needs a second attempt, so those tasks need `retries` as well. This adapter's own tests met exactly
this in three models.

If the model of a failed job cannot be read, for example while the search of the cluster does not
answer, the job keeps one retry less, and one warning per element says so. Why the adapter decides
this way is decision 83 in [`DECISIONS.md`](./DECISIONS.md).

### A user task which is already ending rolls the completion back

`completeUserTask` checks before the commit whether the cluster still has the task. A task in
state `CANCELING` or `COMPLETING` is still there, but it cannot be completed any more. The cluster
is canceling it, or somebody else is completing it. Such a task now ends your transaction with a
`TaskNotFoundException`, so your changes roll back. The adapter reads the state from the search,
which runs a little behind the cluster. So a task which started to end a moment ago can still slip
through, as it could in version 1.

Coming from 1.10.0, this is what you know. Coming from an older version, a completion in these
states used to commit, and the completion sent afterwards failed in the log. Catch
`TaskNotFoundException` where you complete user tasks, if you do not already.

An application which already moved the value into the configuration has one thing to check. If the
value landed at the TASK level
(`vanillabp.workflow-modules.<m>.workflows.<w>.tasks.<t>.adapters.<id>.retry-backoff`), nothing
changes. That level still applies, and where the model disagrees, one log line per element says
so. If the value landed at the workflow, workflow-module or adapter level, and the header stayed in
the model, the header applies. Both say something about one single task, so the more specific one
wins. Between two which are equally specific, the one you can change without deploying a new
process version wins. So look at the models you migrated. A header you meant to retire has to leave
the model, not just the configuration.

A header which is not an ISO-8601 duration causes a warning. It names the workflow module, the BPMN
process, the element and the value. Then the configured value applies. Version 1 answered such a
typo with `Duration.ZERO`. That looks like "no backoff wanted" and hands the job out again at once.
The warning comes when a job of that element fails, once per element and not once per job. It
deliberately does not come while the application boots, because that boot cannot correct a model
deployed long ago.

The default changed. Without a header and without configuration, a failed job is handed out again
after ten seconds. Version 1 sent no backoff at all.

### A listener in your model has to be allowed now

Version 1 served a listener with a `@WorkflowTask` method and documented this nowhere. From its
release 1.7.0 to its last one, 1.10.0, it did so in one place only: a `zeebe:taskListener` of a
`zeebe:userTask`, which needs a cluster of 8.8 or newer. The listener's `type` attribute was used as
the task definition, unchanged. So a method named after it served the listener. A
`zeebe:executionListener` was never served on Camunda 8 at all.

Act on this entry if your models have such a listener and a `@WorkflowTask` method of yours names
its job type. This version does not serve the listener unless you allow it. A model with such a
listener ends the boot, with a message naming the elements, the key and what it costs. You can
allow it per adapter, per workflow module or per workflow. The most specific configured value wins
in both directions:

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

There is no task level for the key. A task level is keyed by the task DEFINITION, and this key
decides whether a listener becomes a task at all. A value written there causes one warning which
says what to do, and the boot goes on.

The boot failure is the good case. Without the key, there is no worker for the listener's job type.
The cluster creates the job anyway, and the workflow stops right there: no incident, no message,
nothing in any log. That is what an upgrade without the key would have given you, and somebody
would have found it only when workflows stopped arriving. The Camunda 7 adapter has the same key.
There the engine evaluates the listener's expression itself, and the workflow fails at the element
or runs a method nobody meant for it.

Read what the key costs before you set it. A listener lets an application in at a moment which the
BPMS owns. Every BPMS defines that moment differently, so the model is no longer portable. Another
BPMS has no listener at this element, and a migration of the model stops at the method which serves
it. Every boot of a workflow module whose listeners are served writes a framed WARN about it. It
names each listener and the way back, and no key silences it. Where you can, move what the listener
does into a task of the model, with a `@WorkflowTask` method behind it. That is the way back the
report names.

**A listener whose job type NO method of yours names ends the boot as well, if one of your
`@WorkflowService` classes claims the process.** Version 1 said nothing at all about such a
listener. The cluster creates the job the moment it reaches the listener. So a workflow waits
inside the element until something takes that job, with no incident and nothing in any log. A
class which claims the process says your application stands in for it. That is why this silence
now ends the boot.

If a worker of yours answers that job next to VanillaBP, say so in your configuration, by the job
type or by the element id:

```properties
vanillabp.workflow-modules.<module>.workflows.<process>.tasks.<job-type>.implemented-externally=true
```

A job type with a dot or a colon needs escaping in a property key. The message which ends the boot
prints the line for Spring Boot and for Quarkus. A `zeebe:modelerTemplate` on the element still
marks it as external for a job element such as a connector. It does not on a user task, because
there the Camunda Modeler sets a template for a form or an assignee. A process which none of your
`@WorkflowService` classes claims keeps its WARN. That model reaches the cluster only because of
the file it sits in, and there is nothing in it for you to change.

**A Camunda-managed user task needs a method, or the same line.** Version 1 ended the boot if no
method served a user task, and version 2 does too. If a task list works off the task on purpose,
mark it with `implemented-externally=true`, by its element id or by its form reference.

A `zeebe:executionListener` is served now as well. Any element may have one, so the door is wider
than in version 1, and the key keeps it shut by default. One placement is refused whatever the key
says: a `start` execution listener on a start event, because the cluster refuses the whole file
for it. Use `end` there. VanillaBP attaches `end` to a start event itself. Under the mode
`use-prefix` the job type of a served listener gets a prefix like every other task definition of
the workflow module, because that is what it has become.

`@TaskEvent` tells the method nothing any more. In version 1 a task-listener method could tell the
`canceling` event from the others through that parameter. Now the event is part of the wiring. One
method serves one event of one element. The parameter receives `CREATED` for every listener,
because a method without it subscribes only to `CREATED`. And `TaskEvent.Event` has no value for a
listener's event at all. Drop the parameter where it only carried noise. Where a method needs to
know the event, model one listener per event.

A `@TaskId` parameter is refused while the process is wired. The cluster completes a listener job
the moment the method returns. So such a task can never stay open, and the id would complete
nothing. Version 1 accepted the method, and the workflow went on without it.

What a listener method may write into the process instance depends on the listener:

- An execution listener on `end` completes the way a task completes. So a method serving it may
  change the workflow aggregate, and a gateway behind the element decides on what it wrote.
- An execution listener on `start` writes nothing into the instance. The cluster would keep those
  values local to the element. There they hide the process variables of the same name and swallow
  the element's own writes of that name. Where something has to be written, model a task of the
  process.
- A task listener writes nothing either, because the cluster refuses a completion with variables
  (its issue 23702).

In the last two cases your application keeps the change, and it reaches the cluster at the next
real sync point of that workflow. No method signature shows whether a method writes the aggregate.
So the startup report says this for every served listener. On Camunda 7 every listener writes the
shared values onto the execution inside the engine's own transaction.

Two listeners of one element with ONE job type end the boot, and the message names both. Version 1
ran one of them, and which one was undefined. So the model promised something it could not
deliver. Give every listener of an element its own job type, and write one method per job type.

The [README section](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/blob/main/README.md#listeners-somebody-modelled)
and the [BPMN model](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki/Configuration#the-bpmn-model-for-camunda-8)
section of the wiki carry the details.

### A handler asking for an item the model hands none over for ends the boot

`@MultiInstanceElement` reads the entry of the collection which the current round is on. Camunda 8
gives that entry a name only if the model writes it into the `inputElement` of the element's
`zeebe:loopCharacteristics`. That is the "Input element" field of the modeller. Version 1 left the
parameter at `null` when a job arrived, and nothing said why.

The deployment says it now. It reads the iterations around each wired task. It asks the core for
which of them a `@WorkflowTask` method wants the item. Where an iteration without a named item
meets such a method, the boot ends. The message
names the task, the element, the attribute the model would have to carry and the two ways out.

Either write the attribute:

```xml
<zeebe:loopCharacteristics inputCollection="=items" inputElement="item" />
```

Or drop the parameter. `@MultiInstanceIndex` and `@MultiInstanceTotal` are answered by every
multi-instance element, so a handler which only counts needs no change to its model.

Nothing else is refused. An element without an `inputElement` still deploys if no handler asks for
its item.

One more case ends the boot. Two processes which call the same process may have multi-instance
elements whose ids differ only in characters which a Camunda 8 variable name cannot hold, such as
`my-task` and `my.task`. Both write the same variable. So a handler in the called process could be
told about a round which never ran. The message names both elements. Rename one of them.

### A standard loop in your model ends the boot

Camunda 8 does not run a standard loop. An activity with `standardLoopCharacteristics` is meant to
repeat while a condition holds. On Camunda 8 it deploys without a word, runs once, and the workflow
moves on. Version 1 deployed such a model the same way. So a model which counts on the loop has
always done its work only once.

If one of your `@WorkflowService` classes claims the process, version 2 refuses the model while it
deploys it, and the boot ends there. The message names the activity, the BPMN process and the
workflow module. A model which deployed in version 1 does not deploy any more, and this is on
purpose: the upgrade is where you learn that the loop never ran. A process nobody claims is not
checked, because the model is somebody else's.

Change the model to one of the two forms which really repeat an activity. Either draw a loop in the
sequence flow, with a gateway after the activity which leads back to it while the condition holds.
Or make the activity a multi-instance element. A handler then reads its round with
`@MultiInstanceElement`, `@MultiInstanceIndex` and `@MultiInstanceTotal`.

A version the cluster already holds is not refused, because nobody can change it any more. If
workflows still run on it, the start logs a WARN. It names the version, the activity and how many
workflows are on it.

### An ad-hoc subprocess in your model ends the boot

Version 1 said nothing about the element. It neither executed it nor reported it. This version
serves the flavour whose activities the model names through `zeebe:adHoc activeElementsCollection`.
It also says two things about a model with an ad-hoc subprocess which version 1 kept quiet about.

The element is named as a source of a second token. So a workflow aggregate without a version
attribute gets the warning about two writers on one aggregate, whichever flavour the model uses.

Nothing here serves the flavour with a `zeebe:taskDefinition` of its own. A worker would have to
complete its job with a result which names the activities to activate, and a `@WorkflowTask`
method cannot say that. **If one of your `@WorkflowService` classes claims the process, your
application does not boot.** A workflow would reach the element and stop there, and the job ends in
an incident once its retries are used up. The message names the element and the two ways out.
Either let the model say which activities to run, and fill the aggregate attribute which that
expression reads. Or leave the element to a runtime which does serve it. A process which none of
your classes claims is not checked. Nothing at all is said about an element which also has a
`zeebe:modelerTemplate`, because a connector runtime owns that one.

### A process none of your classes claims

Version 1 ended the start if no `@WorkflowService` class declared a BPMN process. That included a
process which only sits in the same file as yours. Version 2 does the same. But it offers a way out
for a process which somebody else serves:

```properties
vanillabp.workflow-modules.<module>.workflows.<process>.implemented-externally=true
```

Such a process goes to the cluster with its file, and this adapter leaves it as you modelled it. No
listener, no correlation key and no multi-instance mapping is written into it. No worker serves its
jobs. No check ends the boot or warns because of it. It still has to meet what the cluster demands
of every executable process. A message the process waits for needs a correlation key which you
model yourself, or the cluster rejects the whole file.

### Two workflow modules with the same BPMN process id end the boot

Two of your workflow modules may bring a BPMN process with the same id, as long as the cluster
keeps the two modules apart. If it does not, the second definition takes the identifier from the
first. Then one of the two modules runs on a model which it did not deploy. The cluster decides
which one. Nothing reported this before, because a check per workflow module only ever sees one of
the two sides.

Now it is a boot failure. It is raised while the SECOND of the two modules deploys, when the first
one is already in the cluster. The message names both workflow modules, both process ids, the
identifier they share and the property which brought them together. Two configurations of a
version-1 application do not boot after the upgrade:

- `vanillabp.adapters.<id>.tenant-id` is set, so every workflow module is deployed into that one
  tenant instead of into one named after it.
- `name-clash-avoidance` is `none` for both modules, where nothing is scoped at all. This is what
  a cluster without multi-tenancy leaves you with.

Nothing is wrong with your models, so renaming one of the two processes is only one way out. This
way keeps every other workflow module where it is: give one of the two its own tenant. You can set
the name per workflow module, which you could not in version 1.

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

If the shared tenant was not deliberate at all, remove the adapter's tenant name. Then every
module gets a tenant named after it, which is the default. The last way out is
`name-clash-avoidance: use-prefix`. It drops the tenant and puts the workflow module id in front of
the identifiers instead. On a cluster without multi-tenancy, this mode keeps the modules apart
without asking anything of the cluster.

If you neither set a `tenant-id` nor use `none`, nothing changes for you. The default deploys each
workflow module into a tenant named after it. Under `use-prefix` the module id is part of every
identifier. Either way the two processes never meet.

### Under `use-prefix` a value your model writes as FEEL is rewritten for you

This applies only under that mode. And only where your model writes a FEEL expression instead of a
plain name, at a place which this version prefixes. These places are the `processId` of a
`zeebe:calledElement`, the `decisionId` of a `zeebe:calledDecision`, a message or signal name, an
error or escalation code, a job type, and the external form reference of a user task. Version 1
had no prefixing mode, so such an expression reached the cluster as you wrote it.

This version prefixes every identifier of the workflow module. An expression cannot be prefixed from
the outside. It takes up the whole attribute value, so anything written in front of it becomes part
of its text. So the prefix goes INSIDE the expression. Your `=whichProcess` is deployed as
`="loan-approval__" + string(whichProcess)`, and your expression still returns the plain id which
your own model declares. A job type carries the prefix of its BPMN process as well, so its frame
reads `="loan-approval__LoanApproval__" + string(...)`. There is nothing to change in your model and
nothing to change in your code.

One kind of model does not boot: an expression which builds the prefix itself, at any of those
places. It would get a second prefix, and the cluster would be asked for
`loan-approval__loan-approval__PaymentHandling`. Whatever reads that name would fail once a
workflow reached the element. So the boot ends instead. The message names the file, the element,
the attribute, and the expression to take the prefix out of.

Know what the rewrite costs you before you read a message about it. Camunda 8 parses the FEEL of a
model while it deploys it. So a syntax error in your own part makes the deployment fail. The
cluster's answer quotes the whole wrapped expression, and it counts the column from the opening
quote, not from your text. So a refused deployment of a prefixed module says what such a quote
includes and names the elements it can be about. The same wrapping shows up in an incident if your
expression returns `null`, together with the name of the variable it could not read.

### A job type your model writes as an expression ends the boot

This is about one attribute value in two places: the `type` of a `zeebe:taskDefinition`, and the
`type` of a listener you modelled. If that value starts with `=`, it is a FEEL expression and not a
name. Then your application does not start, if one of your `@WorkflowService` classes claims the
process.

A job type is the name a worker subscribes to. VanillaBP opens one worker per job type it reads
from your model. It subscribes to exactly the string the model says. So with an expression there,
nobody serves the element. We measured this against clusters of the 8.9 and 8.10 lines. The job
the cluster creates carries the result of the expression, while the worker waits for the
expression itself. So this check does not take away any element you could have served. What
changes: you hear about it before a workflow reaches the element, and you read what is really
wrong. Before, the boot ended because of the missing `@WorkflowTask` method. It asked you for a
method whose name nobody can write.

The message now names the attribute, the element and the expression you typed, and the two ways
out. Either write a fixed name as the job type, and a `@WorkflowTask` method of that name. If the
work differs from workflow to workflow, let the method branch on the workflow aggregate it gets.
Or leave the element to the runtime which serves it. Give the element a `zeebe:modelerTemplate`
and allow such elements with `allow-connectors`. After that, nothing here asks about its job type.
A BPMN process which none of your classes claims is not checked.

### A form reference your model writes as an expression ends the boot

This is about the external form reference of a user task. If it starts with `=`, it is a FEEL
expression and not a name. Then your application does not start, if one of your
`@WorkflowService` classes claims the process.

The reference is the task definition of the user task. Your `@WorkflowTask` method is found by it.
The job type of the listeners which tell your application about the task is built from it too.
That job type is built the way version 1 built it. So such a user task was only served if the
method repeated the expression as its task definition, `taskDefinition = "=whichForm"`. We measured
this against `camunda/camunda:8.10.0` before the refusal was added. Without prefixes, that was
still what happened. Under `use-prefix` the boot ended with a message about that method. Now the
message names the user task and the expression you typed.

Write a fixed name as the reference, and a `@WorkflowTask` method of that name. If the form to show
differs from workflow to workflow, model one user task per form behind a gateway. Or keep one name
and let your task list choose the form from the data of the workflow. A BPMN process which none of
your classes claims is not checked.

### A start says which of your names the cluster already held

Version 1 compared the identifiers of a deployment with each other. It said nothing about the ones
the cluster already held. This version asks the cluster about them, once per workflow module while
it deploys. It writes one WARN per module which lists what it found. Nothing fails because of it,
and no property turns it off.

It asks about the BPMN process ids of the module, in one search, and about its DMN (Decision Model
and Notation) decision ids, one search each. Nothing is asked about message names, signal names, error codes, escalation codes
or job types, because the cluster keeps no index of those.

The line you will most likely read is about a process you RENAMED. A cluster records no owner of a
definition. So the only marker the adapter has is the resource a definition was deployed from. A
definition of yours which came from a file you have since renamed looks like somebody else's. The
message says that it cannot tell the two apart. Use the version and the definition key it names to
look up the definition. If the finding is your own old file, delete that definition from the
cluster, and the line goes away. The report about an old process version asks for the same
remedy.

Read a finding under the mode `none` twice. There nothing is prefixed and no tenant separates
anybody. So a second application on the same cluster really does share the name, and the cluster,
not you, decides which of the two a start reaches. The ways out are a tenant
(`name-clash-avoidance: by-adapter`), a prefix (`use-prefix`) or a name nobody else uses.

Two more lines can appear, and both are about your own application. The first: two workflow modules
declare the same message name, signal name, error code, escalation code, job type or decision id.
The line names both sides, because under `none`, and under one adapter-wide `tenant-id`, the
cluster sees one name where you mean two. The second: a version the cluster still holds has such a
name. That is the clash which a workflow module deployed years ago leaves behind.

### What the outbox costs where version 1 sent from your thread

Version 1 sent its commands to the cluster from the thread which had just committed your
transaction, through a transaction aspect. It retried them in memory with `spring-retry`. Nothing
was written down. So if the process died between your commit and the cluster's answer, the
operation was lost. Version 2 writes an outbox entry in your transaction and sends it afterwards.
What that costs is written once for all adapters, in the platform's
[upgrade notes](https://github.com/vanillabp/adapter-platform-integration/blob/main/UPGRADE.md#what-the-upgrade-costs-under-load).
Two things are special here. It is one transaction more than version 1 needed, for the start and
for every other operation. And the thread which talks to the cluster is no longer yours.

The threads are what changes for sizing. In version 1, the number of threads which talked to the
cluster was the number of your application's threads which committed. So a burst of requests
became a burst of commands. Version 2 dispatches on four
threads, `vanillabp.outbox.dispatch-threads`. Count them into the connection pool of the database
your workflow aggregates live in. What the cluster side costs is on the wiki page
[Sizing](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki/Sizing).

There is no factor for how much slower or faster this is, and there cannot be one yet. A run which
compares version 1 with version 2 needs one cluster version which both accept. The lowest cluster
each side accepts is the client it was built against. The last release of version 1, 1.10.0, was
built against the Camunda client 8.8.33. So only the 8.8 line of version 2 shares a cluster
generation with it. For the 8.9 and 8.10 lines there is no such cluster. A run which gave each side
a cluster of its own would measure Camunda, not VanillaBP.

### A restart waits a few seconds longer, and the application after it does not

There is no new property, and nothing to configure. What changes is how long a shutdown takes, and
how quickly the next start gets its first job.

A worker asks the cluster for work with a long poll. The request waits at the cluster for up to
`request-timeout`, ten seconds by default. Closing the worker does not cancel that request, and
closing the client does not either. We measured this against `camunda/camunda:8.9.16` with the
plain Camunda client. A job created while such a request is still parked is handed to that
request. It counts as activated, and nobody answers it. So the worker of the application which is
running by then sees the job only after `job-timeout` expired. With seven seconds between the two
applications, that was the full lock in all twenty runs. With twelve seconds, beyond the request
window, it was twenty milliseconds. This happens on the REST transport, which is the default. The
same scenario over gRPC, and over REST with `stream-enabled`, delivers in milliseconds.

So the shutdown waits for the workers to be released before the client is closed. It waits within
the `shutdown-grace` it already had. In those runs the wait cost 8.2 to 8.5 seconds. It turned a
first job of 20 seconds into one of 30 milliseconds. There is one wait for all workflow modules of
an adapter instance. Each module closes its own workers when it is stopped, and the module stopped
last waits for all of them. So the grace limits the whole shutdown, however many modules an
application has. Three things follow for an application:

- an ordinary restart takes those seconds longer. `shutdown-grace` (default `PT20S`) limits it, and
  it still stays below the shutdown budgets of Spring Boot and Kubernetes. `PT0S` skips the wait
  together with the handler drain,
- a process which is killed, and not asked to stop, cannot pay it. So a workflow started within ten
  seconds of a `SIGKILL` may still wait for its lock. Where restarts are frequent, a shorter
  `job-timeout` limits what that costs. `stream-enabled: true` or `prefer-rest-over-grpc: false`
  avoids the case altogether,
- an application with more workers than its client has HTTP connections pays the request window
  more than once. The requests of the extra workers are queued in the client and go out when
  connections become free. So the workers use the pool in rounds, and every round costs a
  `request-timeout`. `PT20S` covers one round, not two. The start says so. It names the rounds,
  the grace the drain needs, and the pool size which would make it one round again. The wiki page
  [Sizing](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki/Sizing) says how
  many workers an application opens and how to size the pool.

The log line the shutdown writes says how many workers were closed and whether the cluster released
them. It warns if one of them still holds its request when the grace time is over.

### Without a cluster address the adapter connects to `localhost`, on the port of the line

If the address was missing, version 1 left it to Camunda's client. The client takes
`http://0.0.0.0:8080` for REST and `http://0.0.0.0:26500` for gRPC. This version takes the address
where Camunda's own docker compose file of the line publishes the cluster:

| Line |          REST           |           gRPC           |
|------|-------------------------|--------------------------|
| 8.8  | `http://localhost:8088` | `http://localhost:26500` |
| 8.9  | `http://localhost:8080` | `http://localhost:26500` |
| 8.10 | `http://localhost:8080` | `http://localhost:26500` |

An application which sets `rest-address` or `grpc-address` sees no change. One which relied on the
default and runs on the 8.8 line now talks to port 8088. If your 8.8 cluster listens on 8080, set
`vanillabp.adapters.<id>.rest-address: http://localhost:8080`. Every start without an address logs
a WARN which names the address in use.

On 8.9 and 8.10 Camunda's docker compose takes host port 8080, which is also the default port of
Spring Boot and Quarkus. An application on the same machine then needs another `server.port` or
`quarkus.http.port`. The WARN says this too and names the key of the platform.
