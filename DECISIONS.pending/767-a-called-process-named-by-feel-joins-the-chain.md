# An addendum to decision 30, from story 767: a called process named by an expression joins the chain

This is not a new decision. It extends decision 30, and it shortens one paragraph of it to the half
which stays true. Decision 30 says today:

> Two call activities stay out of the graph. One naming its process by an expression decides per
> instance which process it reaches, which no deployment can resolve. One calling a process with a
> workflow aggregate of its own is not decomposition, and the core answers that with
> `workflowsShareTheWorkflowAggregate`. Such a process reports no iteration of its caller although
> the cluster still copies the values into its instance, and that is the only place the line can
> honestly be drawn.

The first sentence of that is still right and its conclusion is not. A call activity naming its
process by an expression is indeed outside the GRAPH, because the graph links two models and here
only one of them is known. What was wrong is the step from there to "reports no iteration": the
values are in the called instance, and the levels they belong to are model knowledge of the CALLER,
which is complete and available while the module is deployed. So the caller writes them down.

`Camunda8MultiInstance#handTheChainDown`, `Camunda8MultiInstance#valuesOf(Registry, …)`,
`Camunda8DeploymentService#handTheChainDownWhereTheProcessIsNamedByAnExpression` and
`Camunda8FetchVariables#collect` rely on this, and so do the README section
[A called process](./README.md#a-called-process) and the wiki page `Configuration`. It is the same
subject as decision 30, so it belongs in that entry rather than beside it.

**The maintainer is asked once**, because the rule in `AGENTS.md` is that an entry is changed only
after asking: the paragraph quoted above is replaced by the paragraphs below. Nothing else of
decision 30 changes, no sentence of it becomes untrue, and the entry keeps its number. If the
maintainer would rather have a second entry, these paragraphs are one.

## What replaces that paragraph

> One call activity stays out of the graph. One calling a process with a workflow aggregate of its
> own is not decomposition, and the core answers that with `workflowsShareTheWorkflowAggregate`.
> Such a process reports no iteration of its caller although the cluster still copies the values
> into its instance, and that is the only place the line can honestly be drawn.
>
> A call activity naming its process by an EXPRESSION is outside the graph as well, and it is told
> its iteration all the same. Which process it reaches is decided per instance, so there are not two
> models to link - but the levels are the CALLER's model knowledge, complete while the module is
> deployed, and the caller hands them down. Such a call activity gets one more input mapping,
> appending one entry to the process variable `vanillabpMiParents`: the calling process as the
> cluster knows it, and its levels outermost first, each with the BPMN element id, the index, the
> total and the item. An enclosing level reads the variables of its own mappings from decision 5, and
> the call activity's own round reads `loopCounter`, `count(...)` over its input collection and its
> input element, because one input mapping of an element must not depend on another one of the same
> element.
>
> An input mapping rather than a start listener. The cluster evaluates it in the same record which
> creates the called instance, so there is no window in which the variable is missing, and it
> evaluates it per multi-instance instance, so each called instance gets its own round. A listener
> would cost a job per instance for bookkeeping, a start listener at the process does not see the
> start variables, and an element listener on a call activity is not taken by any current line. A
> second such call activity further down appends its own entry, which is how a chain several
> processes long comes about.
>
> The values travel IN the entry rather than being named by it. A worker has one fetch list, fixed at
> registration, so it cannot read the variable, look inside and then ask for the names it finds.
> Either it fetches everything, which makes the payload unbounded, or the values are in the entry.
> The keys are the readable ones (`process`, `levels`, `element`, `index`, `total`, `item`): short
> keys would save about two percent of an entry and cost the one thing the variable is looked at for,
> which is somebody in Operate asking why an index is missing.
>
> Two call activities get nothing of this. A statically named one is linked model to model and keeps
> its payload at not one byte more. One saying `propagateAllParentVariables="false"` is left alone,
> because the modeller switched the caller's context off on purpose; a mapping travels even then, so
> writing one would undo that rule instead of following it.
>
> The workflow aggregate is the one question left for the runtime, and only its lookup is. While the
> module is deployed every process of it is held against the caller, and the ones sharing the
> caller's aggregate are recorded as processes which may use that chain. At runtime the reader checks
> whether the pair in front of it is one of them, so a call reaching a process with an aggregate of
> its own is dropped, and so is one which crossed the boundary of a workflow module, where this
> adapter never saw the caller's model. Dropped, not guessed at, and a DEBUG line says which caller
> it was.
>
> The reading side keeps one way. `chainOf` answers what the deployment knows, the handed-down levels
> go in front of it, and `valuesOf` stays the only place turning a level into a `MultiInstanceValue`.
> A level whose element id the called process uses itself is dropped, because both write the same
> variable names and the inner scope overwrites the outer one - the same rule the graph follows. Once
> the two sources are together, nothing tells them apart.
>
> `vanillabpMiParents` is not a protected name, and the reading side is where that is survived. An
> input mapping of it is refused like every name VanillaBP writes, but a start variable, an output
> mapping or the write-back of the workflow aggregate can all set it and nothing in the cluster
> objects. Measured on 2026-10-01 against `camunda/camunda:8.8.40`, `8.9.21` and `8.10.0-rc3`: a FEEL
> expression which reaches into nothing becomes `null`, the cluster writes that `null` without an
> incident, `append` on a text results in `null` as well, and a foreign list is appended to. So a
> value which is not a list is read as if the variable were not there, an entry which describes no
> level is left out while the real ones still count, and nothing of it reaches a `@WorkflowTask` as an
> exception. A DEBUG line names the variable and the process; a WARN would repeat itself for every job
> of an application which uses the name on purpose, and it could change nothing about a job which
> already ran.
>
> The chain counts against the cluster's `MAX_MESSAGE_SIZE` and the adapter cannot catch that limit:
> it arrives as an incident on the call activity rather than as a refused command, so there is nothing
> to classify and nothing to refuse. It is documented instead. Three levels with 1 KB element values
> cost about 3.3 KB while the limit bites between 1000 and 2000 such entries, which is three orders of
> magnitude of room. What gets expensive is the element VALUE and not the depth.
>
> `Camunda8MultiInstanceTest` holds the expression written into the model, the three call activities
> which get nothing, the inherited level of a caller travelling on, and the reading side against a
> foreign value. `Camunda8FetchVariablesTest` holds that every worker serving an element carries the
> one name and that the worker of a whole process does not.
> `Camunda8MultiInstanceIT#theIterationCrossesACallActivityNamedByAnExpression` is what a handler
> really sees, and `#theApplicationMayWriteTheChainVariableItself` what it sees once the application
> took the chain away from itself. `Camunda8CallActivityVariablesCanaryIT` holds the cluster to the
> three properties all of this rests on.

