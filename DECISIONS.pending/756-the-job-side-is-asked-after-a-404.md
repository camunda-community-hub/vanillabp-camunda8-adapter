# A new decision, from story 756: the job side is asked after a 404 about a user task

This is a new decision and needs a number. It says why a `404` of a user-task command makes the
adapter ask one more question, which half of the cluster answers which part of it, and that only the
sentence changes.

Three places read it: `Camunda8ProcessService#whatThe404WasAbout`,
`Camunda8ProcessService#theClusterHoldsAJobOfThatKey` and
`Camunda8UserTaskProbe#theJobTheIndexHoldsFor`. It also qualifies what decision 38 reads out of a
`404`, which is why it is an entry rather than a comment.

## The entry

> ### A 404 about a user task is asked about on the job side once, and only the sentence changes
>
> A user-task command answers `404` for a key it holds no user task under. For a user-task key that
> means the task is over. For a JOB key it means nothing about the task at all: a user task served by
> a job worker, the shape VanillaBP modelled up to its release 1.6.3, has no user-task record in the
> cluster, so every user-task command answers `404` however open the task is.
>
> An application meets such a key while it upgrades. `UPGRADE.md` says the task ids of version 1 are
> data to migrate, and the ids of that shape of user task are job keys. The adapter used to answer one
> with "gone (completed or canceled meanwhile)", which named the one thing the cluster had not said.
>
> So on the `404`, and nowhere else, the job side is asked. It takes two questions and they are not
> interchangeable.
>
> **Whether the cluster holds a job of that key is asked of the ENGINE**, with the `UpdateJobTimeout`
> this adapter sends as the existence check of a service task anyway: `404` for a key it holds no job
> of, a `400` saying nobody has it activated for one it holds, an accepted command for one somebody
> holds right now. Only an answer which says the cluster HAS it counts, so an unreachable cluster
> claims nothing. The price is named rather than hidden: where a worker holds that job at that moment,
> its deadline is pushed to `async-task-lock-renewal`. The job of this case is a user task nothing in
> this version fetches, so the answer is the `400` and the cluster writes nothing.
>
> **Which element the job belongs to is read from the INDEX**, and nothing else is. A search is
> behind the engine on both ends and can carry neither half of the existence question. Measured on
> 2026-09-28 against `camunda/camunda:8.9.21` and `camunda/camunda:8.10.0-rc1` by
> `Camunda8ProbeOfAnOpenUserTaskIT`, on the job of a plain BPMN user task: the job search answered
> "no job of that key" while that job was activated and the task open, and it still answered with the
> job once the job was over, as `TIMED_OUT` at once and `COMPLETED` five seconds later, while
> `UpdateJobTimeout` was accepted for the open job and answered `404` once it was gone. Where the
> index answers nothing the message says the rest without the element.
>
> The state goes into the message as the cluster wrote it and is never read. The same job, five
> seconds after it was activated, was `CREATED` on 8.9.21 and `TIMEOUT_UPDATED` on 8.10.0-rc1, and a
> literal which is new in a patch release is nothing to build a decision on.
>
> The empty `UpdateJob` was the candidate which would have been the read-only mirror of the empty
> `UpdateUserTask`, and it is none: measured in the same runs it is refused with
> `400 INVALID_ARGUMENT` ("At least one of [retries, timeout] is required", with `priority` in that
> list on 8.10) both for a job which is open and for one which is over, so it tells the two cases
> apart not at all.
>
> The extra round trip costs nothing in the everyday case, because the everyday case is not a `404`.
>
> What the caller gets does not change: `TaskNotFoundException` from the pre-commit check of
> `completeUserTask`, `UNKNOWN_TO_BPMS` from `awarenessOfUserTask`. Which kind of key somebody handed
> in changes the sentence, not the outcome, because an outcome which depended on it would make the
> election behave differently for a migrating application than for any other.
>
> `Camunda8A404AboutAJobKeyTest` holds the sentences and the unchanged outcomes.

