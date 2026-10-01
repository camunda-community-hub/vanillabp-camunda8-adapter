# The restart test takes both endings of the drain, and says which one it saw

`Camunda8RestartDeliveryIT` measures the one thing a restart can get wrong on Camunda 8. A
workflow started right afterwards waits a whole job timeout for its first job, because an
activation request of the application before is still parked at the cluster and the job is
activated into it. The adapter closes that window by draining its workers before the client goes
down, and until now the test read that drain by one sentence: the one the drain writes when the
module went quiet inside the grace.

That is not the only sentence the drain writes. It has two endings. Either the module is quiet,
or the grace runs out while the cluster still owes an answer for a request one of the closed
workers parked, and then the drain warns about exactly that. The second ending is not a defect of
the adapter. It is a slow cluster, and the test was asserting that the cluster was not slow.

## What that cost, measured

Two runs of 2026-10-01 went red on it, word for word the same. The publish run of `443a32d`
(36871927557) stopped the first application of the test after 25049 ms and the run of pull
request 231 (36889710683) after 25012 ms, both with 119 closed workers of which at least one had
not been released, and both with no handler left inside the application. The second application
of each of those runs drained in 10123 ms.

The number the test exists for held in both of them: the first job after the restart came in 1,2
seconds and under, against a lock of 20 seconds. It holds whenever the drain gives up, because
giving up means it waited the whole grace, so the window the parked request could have swallowed
the job through was closed long before the second application started.

Raising the grace from 20 to 25 seconds after the finding of 2026-09-27 bought nothing, and the
measurement says why no further number would. Reading every closed worker while the drain ran
showed all 119 of them sitting on the activation request they had in flight when they were
closed. They come back within a second of each other, one request timeout after the shutdown
began, which is where the 10 to 12 seconds of an idle machine come from. Squeezing the cluster
into four tenths of a core reproduces the red runs on demand, with 105 of the 119 requests still
open when the grace runs out. So the floor of that wait is the cluster answering 119 parked
requests, and the grace is a number the test may configure while the floor is not.

## What the test demands now

Everything it ever claimed about the adapter. The drain runs before the client is closed and says
what it did, in either of its two endings. Nothing cuts a running handler off. The ordinary
platform shutdown reaches the adapter, so the backstop of the client factory stays silent. And the
first job after the restart arrives in milliseconds instead of in a job timeout, which is the
point of the test and is untouched.

What it no longer demands is that the cluster answers every parked activation request inside the
grace. That is the cluster's promise, not the adapter's.

A drain which does nothing still fails the test. It writes neither of the two sentences, so the
first assertion takes it, and the delivery assertion takes it as well.

## The two endings are not equally good

The quiet ending is the normal case and the other one is the drain's own warning, so nothing here
may read as if both were fine. Every assertion about the shutdown names the ending it read, and
the measurement the test writes down after a green run names it too. A reader of a failure
therefore sees whether the drain was quiet or warned, and nobody has to take this entry as a
weakened assertion.

`shutdown-grace` stays at 25 seconds in that test. It is still the budget of the whole shutdown,
which is what decision 51 made it, and it still bounds the wait. It is only no longer the thing an
assertion reads.

## What this leaves open

The test no longer notices a drain which runs into the grace on every run. The ending in the
written measurement is what makes such a drift readable, and it has to be read to be noticed.

## Why not the two other ways

Giving the drain fewer workers to wait for would have meant an application of its own for this
test, with one process instead of the thirty-five its module deploys. This test is also the
module's evidence for `max-http-connections: 256`, measured with 115 workers, and that evidence
would have gone with it.

Halving the window, with a request timeout of five seconds and a gap of three, would have kept the
ratio and widened the margin under the grace from 15 to 20 seconds. It would have left the same
bet running on a wider margin.
