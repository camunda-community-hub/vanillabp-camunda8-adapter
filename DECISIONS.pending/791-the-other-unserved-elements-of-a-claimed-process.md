# A new decision, from story 791: every element of a claimed process has to be served, and the model says where it is served

This is a new decision and needs a number. It says why the boot of a claimed process ends over an
ad-hoc subprocess and over a listener, and it says what a developer writes into the model where
something other than VanillaBP answers the job.

Three places read it: `Camunda8DeploymentService#refuseOrReportUnservedAdHocSubProcesses`,
`Camunda8DeploymentService#refuseOrReportListenerJobsNothingServes` and
`Camunda8DeploymentService#sayWhichListenersBelongToAnotherRuntime`. `UPGRADE.md` and the wiki pages
*Configuration* and *Deviations* carry the user-facing half.

Stephan decided it on 2026-10-01, with one sentence about the question story 780 left open: it holds
for all of them.

## What the merge has to do

This entry replaces one half of decision 24, so decision 24 gets a line of its own once this entry
has a number. Decision 24 stays as it is and the line goes under its heading:

> **Superseded for a BPMN process the application claims by decision `<n>`.** What is written below
> holds for a process no `@WorkflowService` class of the application claims. For one which is
> claimed, the deployment refuses the file instead of warning about the element.

## The entry

> ### Every element of a claimed process has to be served, and an element template is how the model says it is served elsewhere
>
> A `@WorkflowService` class claiming a BPMN process says that this application stands in for the
> process. So no element of such a process may be left standing: where the cluster creates a job and
> nothing answers it, the workflow stops inside the element, and both ends of that are quiet. There
> is no incident until the job's retries are used up, and there is nothing in any log at all. The
> deployment therefore refuses the process, and a process nobody claims keeps the WARN it always had.
>
> Two elements were warned about before this entry, and both of them are now refused for a claimed
> process.
>
> An **ad-hoc subprocess with a `zeebe:taskDefinition` of its own** expects a worker which decides
> round by round which inner activities to activate, by completing the job with a result naming them.
> A `@WorkflowTask` method cannot say that, so this adapter opens no worker for the element. Nothing
> later in the boot catches it either, because the element produces no task spec and no validation
> misses a method.
>
> A **modelled listener whose job type no `@WorkflowTask` method names** is the harder one, and the
> reason is that the job type says nothing about who answers it. A worker somebody else runs and a
> worker the application runs beside VanillaBP look exactly the same in the model. So the ELEMENT is
> asked instead of the job type: an element built from an element template belongs to the runtime
> which owns it, which is the marker of decision 23 and of decision 24, and it is read through the
> same class rather than looked for a second time. A listener on such an element is named in a WARN of
> its own and the boot goes on, whoever claims the process.
>
> Using the element template for this costs one miss, and it is named rather than hidden. A developer
> who meant VanillaBP to serve the listener of a templated element and forgot the method reads a
> warning instead of a refusal. The alternative was a marker of this adapter's own, which would be a
> second way of saying what the template already says, and a model carrying it would stop being a
> model any Camunda 8 modeller understands.
>
> One element was never part of this question. A service task without a `@WorkflowTask` method ends
> the boot of a claimed process and always did: the reader hands the core a task spec whose task
> definition is `null`, and `validateTaskWiring` ends the start over it. The core asks nothing of an
> unclaimed process, which is the same split written in the core rather than here.
>
> What the refusals say is what to do next. The ad-hoc message names the two ways out, the model and
> the other runtime. The listener message names three: a method plus the key which lets VanillaBP
> serve modelled listeners, the listener taken out of the model, and the element template for a job
> somebody else answers. An application which cannot change its models at once has the way out every
> deployment failure has: a non-primary adapter configured with `deployment-failure: warn` logs the
> failure instead of ending the boot.
>
> `Camunda8AdHocSubProcessTest` holds the refusal and the WARN of the ad-hoc subprocess,
> `Camunda8ListenersReportTest` the refusal, the WARN of an unclaimed process and the WARN of the
> templated element.

