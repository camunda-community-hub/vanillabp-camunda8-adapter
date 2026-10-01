# A new decision, from story 745 and rewritten by story 782: a name written as FEEL gets the prefix inside it

This is a new decision and needs a number. It says what `use-prefix` does with a called process and a
called decision whose id is a FEEL expression, and it is the reason `Camunda8Scoping` writes the prefix
INTO such an expression while `Camunda8DeploymentService` refuses a file whose expression composes the
prefix itself.

Three things read it: the code (`Camunda8Scoping#withThePrefixInside`,
`Camunda8Scoping#whatAQuotedExpressionIncludes` and
`Camunda8DeploymentService#refuseAnExpressionWhichAlreadyCarriesThePrefix`), the wiki section
[A called process named by an expression](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki/Configuration#a-called-process-named-by-an-expression)
and `UPGRADE.md`. The choice was between two ways and the other one is a real option, so a comment at
one of those places would not carry it.

**The question this entry used to put to the maintainer is answered.** The earlier version of it left
the expression alone and asked the application to compose the scoped id, which gave decision 2 a
boundary: a call activity naming its process by FEEL addressed the cluster with whatever the
application's expression yielded. That boundary is gone. The adapter composes the scoped id in every
case now, so decision 2 holds as it is written and needs neither an edit nor a successor.

## The entry

> ### A name written as FEEL gets the prefix inside the expression
>
> Two attributes of a model can hold a FEEL expression instead of an identifier: the `processId` of a
> `zeebe:calledElement` and the `decisionId` of a `zeebe:calledDecision`. Under `use-prefix` both are
> deployed with the workflow module's prefix written INSIDE the expression, so `=whichProcess` reaches
> the cluster as `="loan-approval__" + string(whichProcess)`. The application writes no prefix
> anywhere, which is what keeps its model portable: the same file runs on another BPMS, and the same
> business code with it.
>
> The prefix cannot go in front of such a name. An expression takes up the whole attribute value, so
> `loan-approval__=whichProcess` names no process and parses as no expression either. Camunda 7 gets
> away with the same rewrite because `${processToCall}` is one part of a string the engine composes.
>
> Every part of the frame was measured. Camunda's FEEL concatenates two strings with `+`, which goes
> beyond the DMN standard where `+` is arithmetic. `string(...)` around the application's part makes
> that concatenation work whatever the part returns, a number included. The parentheses are what carry
> the shape of that part, so an `if ... then ... else ...` returning one of several ids, a
> `get value(...)` over a context, a text the expression composes itself and an expression written
> over several lines all survive being wrapped.
>
> Measured on 2026-10-01 against `camunda/camunda:8.8.40`, `8.9.21` and `8.10.0-rc3`, which answered
> the same in every case down to the wording of an incident. The alternative frame,
> `string join(["loan-approval__", string(whichProcess)], "")`, works as well and was not taken: where
> the application's part is `null` it drops that part and asks the cluster for the bare prefix
> (`CALLED_ELEMENT_ERROR: Expected process with BPMN process id 'loan-approval__' to be deployed, but
> not found.`), while `+` raises an incident which names the application's own variable
> (`EXTRACT_VALUE_ERROR: Expected result of the expression '"loan-approval__" + string(whichProcess)'
> to be 'STRING', but was 'NULL'. The evaluation reported the following warnings: [NO_VARIABLE_FOUND]
> No variable found with name 'whichProcess' [INVALID_TYPE] Can't add 'null' to '"loan-approval__"'`).
> A frame which hides a mistake of the application is the worse frame.
>
> The price is that the cluster then holds an expression nobody typed, and it is stated rather than
> hidden. Camunda 8 parses the FEEL of a model while it deploys it, so a syntax error in the
> application's part refuses the whole deployment quoting the framed expression, and the column it
> reports is counted from the opening quote: `string(whichProcess +)` inside the frame was reported at
> `:1:27`, the same mistake without a frame at `:1:14`. The deployment therefore says what a quoted
> expression includes, once, where it reports such a refusal
> (`Camunda8Scoping#whatAQuotedExpressionIncludes`). Nothing is said while a deployment goes through,
> because then there is nothing for anybody to do.
>
> An expression which composes the prefix ITSELF ends the boot, naming the element, the file and the
> expression to change. The rewrite would give it a second prefix, the cluster would be asked for
> `loan-approval__loan-approval__PaymentHandling`, and every call of that element would fail once a
> workflow reached it. An earlier snapshot of VanillaBP 2 asked an application to compose the prefix
> and warned about every such element, so that model is the one case this can come from, and a boot
> which says it is cheaper than one incident per instance.
>
> What this does NOT reach is the model knowledge behind such a call. Which process the expression
> names is known at execution time, so a call activity naming it by FEEL stays outside the call graph
> the deployment links and outside what the workflow viewer can draw. Both read the attribute the same
> way and both still see an expression. The iteration chain is not lost with it: the caller writes its
> own levels into the called instance instead, which is the addendum to decision 30 of story 767.
>
> Where the mode is not `use-prefix` there is no prefix, so nothing of this applies and nothing is
> said.
>
> `Camunda8CalledProcessScopingTest` holds what the adapter writes into the model, per shape and for
> both attributes, and that the frame survives the XML the deploy command sends.
> `Camunda8PrefixInsideAnExpressionTest` holds the refused boot and the sentence about a quoted
> expression. `Camunda8PrefixInsideAnExpressionCanaryIT` holds the cluster to what was measured.
>
> See [Keeping workflow modules apart](./README.md#keeping-workflow-modules-apart).

