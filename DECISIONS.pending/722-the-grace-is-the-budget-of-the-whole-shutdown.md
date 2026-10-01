# A new decision, from story 722: the shutdown grace is the budget of the whole shutdown

This is a new decision and needs a number. Five places rely on it:
`Camunda8DeploymentService#stopWorkflowProcessing`, `Camunda8Drain#awaitEveryModuleQuiet`,
`Camunda8WorkerConnections` with its floor, the javadoc of
`Camunda8AdapterConfiguration#shutdownGrace` and the wiki page *Sizing*.

**It also amends decision 6**, whose second paragraph says the wait happens in the call which stops
one module. That paragraph is no longer true and the replacement is at the end of this file.
Everything else of decision 6 stands. Nothing of decision 50 becomes untrue: its floor is now the
floor of the application's whole shutdown, which is what it was always read as.

Stephan has not seen this yet. The story named the shared budget as its default and the measurement
backs it, so it is written as decided; the entry stays pending until he says yes.

## The entry

> ### The shutdown grace is the budget of the whole shutdown, and the workflow modules are one wait
>
> The platform stops the workflow modules one after another, on the shutdown thread, and it calls
> the adapter once per module and per adapter instance. `shutdown-grace` sits at adapter level, so
> each of those calls used to close the workers of its module and then wait the whole grace for
> them. An application with three workflow modules therefore had three waits of up to twenty
> seconds in a shutdown its runtime grants thirty.
>
> The waits do not overlap. A module whose workers are still open keeps renewing their activation
> request while another module is drained, and closing a worker does not cancel the request it has
> in flight, so the module stopped next has a request of its own to sit out. The closed workers of
> the module being drained also wait behind the still polling workers of the others for the client's
> executor, which costs more than one request timeout.
>
> Measured on 2026-10-01 with `Camunda8WhatSeveralModulesPayForAShutdownIT` against
> `camunda/camunda:8.9.21`, one client with a pool of 256, `request-timeout` `PT10S`,
> `shutdown-grace` `PT20S` and thirty workers per module. Each case read twice where the reading
> moves with the phase the close falls into:
>
> | modules | drained one after another | every module closed first, then one wait |
> |---------|---------------------------|------------------------------------------|
> | 1       | 823 ms                    | the same reading                         |
> | 2       | 12519 and 21421 ms        | 5436 ms                                  |
> | 3       | 32743 and 15424 ms        | 5242 ms                                  |
>
> Three modules one after another reached past the thirty seconds the runtime grants, and one module
> of that run gave up after the whole grace with its workers still holding a request, which is the
> case the wait exists to prevent. Waiting once stayed at about half a request timeout whether there
> were two modules or three, and every module was quiet.
>
> So the grace is spent once. Each module closes its workers as it is stopped and waits for nothing
> yet; the module stopped last, which is the one leaving no registration of this adapter behind,
> waits for every module of the adapter instance at once. The number the application configured is
> then the number its whole shutdown takes at most, and that is the number which can be held against
> the budget of the runtime. Nothing else can: only the application knows how many workflow modules
> it has.
>
> **Why not a share per module.** The adapter knows at shutdown how many modules are still to come,
> so it could hand each of them an equal share of what is left. It would not help. What a module
> needs is a whole request timeout, three of them do not fit into twenty seconds, and three modules
> on a share of under seven seconds each would every one of them leave a parked request behind. One
> wait needs one request timeout for all of them, because the requests of every module are parked at
> the same time and come back at the same time. The readings show it: one module alone was released
> in 823 ms and two modules together in 5436 ms, while a module drained while another one still
> polled sat out the whole grace twice.
>
> **What the SPI allows.** `stopWorkflowProcessing` of the extension SPI says that the workers of
> that module stop. It does not promise that the module is quiet when the call returns, and between
> two of those calls nothing touches the cluster and nothing closes the client. The client is closed
> later, by `Camunda8ClientFactory`, whose backstop stops a module which never reached the adapter
> before it closes the client, so the one wait happens before the client goes down on every shutdown
> path.
>
> **The key stays at adapter level.** It is read against one shutdown budget, and only the
> application has one of those. A value per workflow module would be a number nobody could hold
> against that budget, which is why `shutdown-grace` is not resolvable over the four levels the rest
> of this adapter's scoped properties use.
>
> **What this leaves open.** An application with two configured adapter instances has two of these
> budgets, because each instance waits for its own modules and knows nothing about the other. The
> adapter cannot close that: it sees neither how many other instances there are nor what they
> configured.
>
> `Camunda8Drain#awaitEveryModuleQuiet` is the wait, `Camunda8ShutdownDrainTest#twoModulesAreOneWait`
> holds it, and `Camunda8WhatSeveralModulesPayForAShutdownIT` holds the numbers.

## The paragraph of decision 6 this replaces

The paragraph as it stands:

> So `stopWorkflowProcessing` closes the workers of the module first and then waits, within
> `shutdown-grace`, for two things: the handlers which are still inside the application, and the
> cluster releasing the workers. The client factory closes whatever never reached that path before
> it closes the client, so the order holds on every shutdown path and not only on the one the
> platform lifecycles happen to take.

What it becomes:

> So `stopWorkflowProcessing` closes the workers of the module first, and the shutdown then waits,
> within `shutdown-grace`, for two things: the handlers which are still inside the application, and
> the cluster releasing the workers. The wait is one wait for every workflow module of the adapter
> instance rather than one per module, which is the decision above. The client factory closes
> whatever never reached that path before it closes the client, so the order holds on every shutdown
> path and not only on the one the platform lifecycles happen to take.

