# A new decision, from story 745: a name written as FEEL gets no prefix

This is a new decision and needs a number. It says what `use-prefix` does with a called process and a
called decision whose id is a FEEL expression, and it is the reason `Camunda8Scoping` now skips both
and the deployment warns about the first.

Two things read it: the code (`Camunda8Scoping#nothingAPrefixCanBePutInFrontOf` and
`Camunda8DeploymentService#reportCallActivitiesNamingTheirProcessByExpression`) and the wiki section
[A called process named by an expression](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki/Configuration#a-called-process-named-by-an-expression).
The choice was between two ways and the other one is a real option, so a comment at one of those
places would not carry it.

**A question for the maintainer, before this entry gets a number:** decision 2 says "the cluster is
always addressed with the SCOPED identifiers - process ids, message and signal names, error codes and
task definitions". With this entry that sentence has a boundary: where a call activity names its
process by FEEL, the cluster is addressed with whatever the application's expression yields, and the
adapter cannot make that a scoped id. Decision 2 was not edited and nothing was written into it. Is
the entry below enough, or should decision 2 be superseded by one which carries the boundary?

## The entry

> ### A name written as FEEL gets no prefix, and the application composes the scoped id
>
> Two attributes of a model can hold a FEEL expression instead of an identifier: the `processId` of a
> `zeebe:calledElement` and the `decisionId` of a `zeebe:calledDecision`. Under `use-prefix` both are
> left exactly as the modeller wrote them, and the expression has to yield the id the cluster knows.
>
> The reason is what an expression is. It takes up the whole attribute value, so a prefix written in
> front of it lands in the expression's text rather than in the id it produces:
> `loan-approval__=whichProcess` names no process and parses as no expression either. Camunda 7 gets
> away with the same rewrite because `${processToCall}` is one part of a string the engine composes,
> so `loan-approval__${processToCall}` evaluates to `loan-approval__<value>`. FEEL has no such form.
>
> The other way was to rewrite the expression into a concatenation
> (`="loan-approval__" + whichProcess`). It was not taken. It would mean the adapter editing the
> application's own code, and not every expression survives a concatenation wrapped around it: an
> `if` returning one of several ids, a `get value(...)` over a context, an expression already
> composing a string. A rewrite which is right for the simple shape and wrong for the others is worse
> than none.
>
> So the developer writes the prefix, and this is the ONLY place under `use-prefix` where they have
> to. Which is why the deployment names those call activities, once per BPMN process, together with
> the prefix and the expression to write
> (`Camunda8DeploymentService#reportCallActivitiesNamingTheirProcessByExpression`). It is a WARN and
> no key silences it, the same choice connectors got in decision 23: the adapter cannot evaluate the
> expression, so it cannot tell an application which already composes the prefix from one which does
> not. A called decision is not reported, because the DMN files whose ids are rewritten belong to the
> same workflow module and the same developer.
>
> Where the mode is not `use-prefix` there is no prefix, so nothing of this applies and nothing is
> said.
>
> Measured on 2026-09-28 against `origin/main` before the fix: with `processId="=whichProcess"` in
> workflow module `loan-approval`, the deployed model carried `loan-approval__=whichProcess`.
> `Camunda8CalledProcessScopingTest` holds both forms now, and
> `Camunda8CalledProcessByExpressionReportTest` holds the message.
>
> See [Keeping workflow modules apart](./README.md#keeping-workflow-modules-apart).

