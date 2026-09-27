# Amendment to decision 50, from story 694

This is not a new decision and needs no number. It replaces one paragraph of decision 50, `The
adapter says that the workers outgrew the connection pool, and raises nothing`, because story 694
moved where the workers are counted. Everything else in that entry stands.

## The paragraph as it is now

> **Where the check sits.** At the end of `startWorkflowProcessing` of
> `Camunda8DeploymentService`, once a workflow module opened its workers. That is the first moment
> the number is known and the last one before the platform writes the block of the start, which is
> what the rule "as early as possible" means here. The pool belongs to the client and the client
> belongs to the adapter id, so what counts is the sum over all workflow modules of one deployment
> service, and a module which is stopped gives its share back.

## The paragraph as it should read

> **Where the check sits, and what it counts.** At the end of `startWorkflowProcessing` of
> `Camunda8DeploymentService`, once a workflow module opened its workers. That is the first moment
> the number is known and the last one before the platform writes the block of the start, which is
> what the rule "as early as possible" means here.
>
> What it counts are the workers open on the CLIENT, not the workers the deployment service
> ordered. `Camunda8Workers` is public so an extension can open workers on the same client, those
> hold the same connections, and an application near the limit could cross it with nobody
> counting. So a worker is counted where it is opened: `Camunda8Workers.open` opens every worker
> of the adapter and of an extension, and hands it to `Camunda8ClientFactory`, which is the one
> object per adapter id both of them already hold. A worker which is closed leaves the count
> again, so a workflow module which stops gives its share back the way it did before.
>
> A worker which opens while a workflow module is starting does not run the check: the number
> halfway through a module is not the number it ends with, and the message names a number. The
> module runs the check itself once its last worker is open. A worker which opens outside a module
> start runs it right away, which is the case story 694 is about: an extension which opens its
> workers after the start makes the check run a second time, and it runs on the number that is
> true at that moment.
>
> An extension which brings a CLIENT OF ITS OWN has a connection pool of its own, and its workers
> are not in this number. Such a worker never comes through `Camunda8Workers.open`, so nothing has
> to be excluded; `core/README.md` says it where the extension API is listed.

