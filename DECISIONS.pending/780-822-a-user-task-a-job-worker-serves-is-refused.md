# A new decision, from story 780 and rewritten by story 822: a user task a job worker serves is refused in a process the application claims

This is a new decision and needs a number. It says why one finding has two answers, which is the
thing a reader of `Camunda8DeploymentService#refuseOrReportJobWorkerUserTasks` asks first, and it is
what the WARN of decision 24 no longer covers for a user task.

Two places read it: `Camunda8DeploymentService#refuseOrReportJobWorkerUserTasks` and
`Camunda8TaskWiring#jobWorkerUserTasksOf`. `UPGRADE.md` and the wiki pages *Configuration* and
*Deviations* carry the user-facing half.

Stephan decided it on 2026-09-29, out of his answer to story 752, and sharpened it on 2026-10-01:
the shape itself is refused, not only an element nothing serves.

## The entry

> ### A user task a job worker serves is refused in a process the application claims, and only warned about in one it does not
>
> Camunda 8 knows two kinds of user task. One carries `zeebe:userTask` and the CLUSTER manages it,
> which is the kind this adapter serves. The other carries none and a job worker serves it, which is
> how VanillaBP 1 worked up to its release 1.6.3. **This adapter does not accept the second kind.**
> The question is the shape of the element and nothing else, so nothing asks whether some worker
> would fetch the job the cluster hands out: the model already says who serves the task.
>
> Why the shape is refused rather than left alone is what it would cost. The cluster hands out a job
> of `io.camunda.zeebe:userTask`, this version opens no worker on that job type, and the workflow
> stands at the element until the job's retries are used up. No notification arrives, and
> `completeUserTask` cannot answer such a task either, because the id it hands out is a job key
> while the cluster expects a user-task key. Nobody sees any of that until somebody waits for a task
> which never appears.
>
> A `@WorkflowService` class claiming a BPMN process says that this application stands in for the
> process. So the deployment refuses such a process. The message names the process and the elements
> per shape, and it names the ways out: make the user task a Camunda-managed one whose external form
> reference names the task definition of a `@WorkflowTask` method, or take the element out of the
> model.
>
> The third way out is not a user task at all, and the message names it because the boot would
> otherwise end for an application which is right. An element carrying a `zeebe:taskDefinition` is
> served by a worker of the APPLICATION, under a job type the application chose. Nothing of VanillaBP
> notifies anybody about it and nothing completes it, so it is no longer a user task this adapter is
> meant to serve, and the reader passes over it. That is the difference this entry draws: between a
> user task VanillaBP serves and an element the application serves itself.
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
> This is where decision 24 stops. Its reasoning holds for every unclaimed process: a file travels to
> the cluster as a whole, and ending the boot over a model somebody else owns would take an
> application down over an element it cannot change. What changed is the claimed process, where there
> is no somebody else.
>
> A version-1 application whose model carries such an element stops booting after the upgrade, which
> is the point. `UPGRADE.md` says so in the section about user task models. An application which
> cannot change its models at once has the way out every deployment failure has: a non-primary
> adapter configured with `deployment-failure: warn` logs the failure instead of ending the boot.
>
> `Camunda8JobWorkerUserTasksReportTest` holds both messages, the boundary of the element the
> application serves itself, the counter-test of a claimed process whose user tasks are all
> Camunda-managed, and that the WARN asks for nothing.

