# A new decision, from story 741: the 404 of a user-task probe is about the key it was handed

This is a new decision and needs a number. It states the condition under which the `404` of the
user-task probe means what decisions 35 and 38 read out of it, and it carries the measurement which
answers the open question of story 643. Nothing of the adapter changed for it: the measurement found
the promise kept.

Stephan reported on 2026-09-28 the case those decisions do not allow - a user task the Business
Cockpit shows as active while a check about it answers `404`. His application runs VanillaBP 1. The
measurement below asked every question about the same open task, on all three release lines, and the
case is real. It belongs to version 1 and to the question version 1 asked.

## The entry

> ### The 404 of a user-task probe is about the key it was handed
>
> A user task which is simply open never makes the probe say `404`. Decision 38 reads that answer as
> "the task is gone", and story 643 measured it inside the window of a running cancelation only.
> `Camunda8ProbeOfAnOpenUserTaskIT` measures the plain case: a task nobody is cancelling, asked from
> the moment its `creating` listener job arrived, which is the moment an application - and with it
> the Business Cockpit - learns of the task.
>
> Measured on 2026-09-28, one cluster at a time on a machine with 16 GB, client and cluster of the
> same line, the questions asked one after another so every number carries the one before it:
>
> |                     the question                      |                      8.8.39                       |                      8.9.21                       |                    8.10.0-rc1                     |
> |-------------------------------------------------------|---------------------------------------------------|---------------------------------------------------|---------------------------------------------------|
> | the empty `UpdateUserTask`, answered by the partition | `204` after 12 ms, and over five further readings | `204` after 21 ms, and over five further readings | `204` after 13 ms, and over five further readings |
> | `UserTaskGet`, answered by the index                  | `404` until 1667 ms                               | `404` until 649 ms                                | `404` until 219 ms                                |
> | the user-task search, the other read of the index     | holds it after 1674 ms                            | after 659 ms                                      | after 229 ms                                      |
> | `UpdateJobTimeout` on that user-task key              | `404` for 60 s                                    | `404` for 60 s                                    | `404` for 60 s                                    |
> | the instance probe                                    | `400`, so the engine holds the instance           | `400`                                             | `400`                                             |
>
> So the probe is AHEAD of the index rather than behind it, which is the reason it is a command and
> not a search, and the `404` of decision 38 keeps its meaning for a key which is a user-task key.
>
> **For a key of the other kind it means nothing.** The same run measured a plain BPMN user task,
> the shape VanillaBP 1 served up to its release 1.6.3, whose id is a JOB key: `UpdateUserTask` and
> `UserTaskGet` answered `404` for a full minute, the user-task search never held a record of the
> task at all, while the job command on that key was accepted and the instance probe said the engine
> holds the instance. The cluster keeps no user-task record for such a task, on any of the three
> lines. A `404` there is the answer to "do you hold a user task under this key", and the task is
> open.
>
> This adapter does not meet that case, and not by accident. `Camunda8OpenTaskProbe` reads the kind
> of task from the model and sends no user-task command for a job, the two probes asking about a task
> a caller named are called for a user task by the core, and the deployment reports a user task of
> the version-1 shape rather than serving it. So the promise of decision 38 is conditional, the
> condition is held by the adapter, and it is said in the javadoc of `Camunda8UserTaskProbe` so that
> nobody rebuilding the question outside VanillaBP reads more out of a `404` than it says.
>
> What version 1 did differently, for the record, because this is the entry somebody looking for
> Stephan's case will find: its existence check for a user task was a `UserTaskGet`, so it read the
> index and met the first row of the table above; it served both shapes of user task, so it met the
> second one as well; and its Business Cockpit wrote its record from the listener job, which is why
> the cockpit shows a task the index does not have yet. `UPGRADE.md` says it where an application
> coming from version 1 will look.

## What decisions 35 and 38 are owed

Story 643 asked whether the two entries should each carry a line of evidence. They should, and these
are the two lines. Both are additions and change no statement, so neither entry needs to be
superseded - but the call is Stephan's, because an entry is not edited without him.

Decision 35, after "The engine forgets an instance the moment it ends":

> That an open user task does not produce such a `404` was measured as well: with the task open and
> nobody cancelling, the instance probe answered `400` on 8.8.39, 8.9.21 and 8.10.0-rc1
> (`Camunda8ProbeOfAnOpenUserTaskIT`, 2026-09-28).

Decision 38, in "What an answer means", after "`404` is gone":

> For a user-task key, which is the condition the adapter holds. An open task never produced one:
> `204` after 12, 21 respectively 13 ms on 8.8.39, 8.9.21 and 8.10.0-rc1, while the same task read
> from the index answered `404` for up to 1667 ms. Handed a JOB key the command answers `404` for as
> long as the task is open (`Camunda8ProbeOfAnOpenUserTaskIT`, 2026-09-28).

## What was not measured

A version-1 application end to end. What is measured above is what version 1's commands ask and what
the cluster answers them, against the clusters of the three lines VanillaBP 2 serves; version 1's own
code was read, not run. Stephan's cluster version and the shape of his user task are what decide
which of the two rows above he has, and only he can say.
