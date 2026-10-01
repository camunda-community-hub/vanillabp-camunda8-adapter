### A job type written as an expression is refused in a claimed process, and warned about in one nobody claims

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
