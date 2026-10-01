# A new decision, from story 780: who claims the process decides whether the boot ends

This is a new decision and needs a number. It says why one finding has two answers, which is the
thing a reader of `Camunda8DeploymentService#refuseOrReportUnservedUserTasks` asks first, and it is
what the WARN of decision 24 no longer covers for a user task.

Two places read it: `Camunda8DeploymentService#refuseOrReportUnservedUserTasks` and
`Camunda8TaskWiring#unservedUserTasksOf`. `UPGRADE.md` and the wiki page *Configuration* carry the
user-facing half.

Stephan decided it on 2026-09-29, out of his answer to story 752.

## The entry

> ### A plain BPMN user task ends the boot of a process the application claims, and only warns about one it does not
>
> A `@WorkflowService` class claiming a BPMN process is a promise: this application serves this
> process. A plain BPMN user task in such a process breaks that promise, and it breaks it quietly.
> The cluster serves the element with a job of `io.camunda.zeebe:userTask`, this version opens no
> worker on that job type, and the workflow stands at the element until the job's retries are used
> up. Nobody sees it until somebody waits for a task which never appears.
>
> So the deployment refuses the process. The message names the process and the elements per shape,
> and it names the two ways out: make the user task a Camunda-managed one whose external form
> reference names the task definition of a `@WorkflowTask` method, or take the element out of the
> model. It also names
> the one case which is neither, because the boot would otherwise end for an application which is
> right: an element carrying a `zeebe:taskDefinition` is served by a worker of the application, and
> the reader passes over it.
>
> A process no class of this application claims keeps the WARN it always had, without the sentences
> which asked the reader to change something. Such a process reaches the cluster because it sits in a
> file next to a process this application does serve, `validateTaskWiring` asks nothing of it for the
> same reason, and whoever owns it may serve such a job with a worker of their own. There is nothing
> for the reader of this application's log to do about it, so nothing is asked of them.
>
> Whether the process is claimed is read the way everything else in this adapter reads it: the core
> answers the name of the workflow aggregate's id for a claimed process and nothing for an unclaimed
> one. The module-level report of the core names the unclaimed processes once per workflow module,
> and an adapter neither implements nor calls that one.
>
> This is where decision 24 stops. Its reasoning holds for the element it is about, an ad-hoc
> subprocess nothing serves, and for every unclaimed process: a file travels to the cluster as a
> whole, and ending the boot over a model somebody else owns would take an application down over an
> element it cannot change. What changed is the claimed process, where there is no somebody else.
> Whether the same answer is owed to the other unserved elements of a claimed process is a question
> of its own and not settled here.
>
> A version-1 application whose model carries such an element stops booting after the upgrade, which
> is the point. `UPGRADE.md` says so in the section about user task models. An application which
> cannot change its models at once has the way out every deployment failure has: a non-primary
> adapter configured with `deployment-failure: warn` logs the failure instead of ending the boot.
>
> `Camunda8UnservedUserTasksReportTest` holds both messages, the counter-test of a claimed process
> whose user tasks are all Camunda-managed, and that the WARN asks for nothing.

