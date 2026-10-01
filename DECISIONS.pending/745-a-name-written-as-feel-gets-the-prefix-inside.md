# A new decision, from story 745, rewritten by story 782 and widened by story 821: a value written as FEEL gets the prefix inside it

This is a new decision and needs a number. It says what `use-prefix` does with a value of a model
which is written as a FEEL expression instead of a name, and it is the reason `Camunda8Scoping` writes
the prefix INTO such an expression while `Camunda8DeploymentService` refuses a file whose expression
composes the prefix itself.

Three things read it: the code (`Camunda8Scoping#forEveryPrefixedValue`,
`Camunda8Scoping#withThePrefixInside`, `Camunda8Scoping#whatAQuotedExpressionIncludes` and
`Camunda8DeploymentService#refuseAnExpressionWhichAlreadyCarriesThePrefix`), the wiki section
[A name written as an expression](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/wiki/Configuration#a-name-written-as-an-expression)
and `UPGRADE.md`. The choice was between two ways and the other one is a real option, so a comment at
one of those places would not carry it.

**The question this entry used to put to the maintainer is answered.** The earlier version of it left
the expression alone and asked the application to compose the scoped id, which gave decision 2 a
boundary: a call activity naming its process by FEEL addressed the cluster with whatever the
application's expression yielded. That boundary is gone. The adapter composes the scoped id in every
case now, so decision 2 holds as it is written and needs neither an edit nor a successor. Story 821
finished that off at the remaining places: a message name, a job type or an error code written as an
expression reached the cluster unprefixed until then, which was the last way an identifier of a
prefixed module could arrive without its prefix.

## The entry

> ### A value written as FEEL gets the prefix inside the expression, at every place a prefix is written
>
> Under `use-prefix` a value which starts with `=` is deployed with the prefix of its place written
> INSIDE the expression, so `=whichProcess` reaches the cluster as
> `="loan-approval__" + string(whichProcess)`. The application writes no prefix anywhere, which is what
> keeps its model portable: the same file runs on another BPMS, and the same business code with it.
>
> **One rule, and one list of the places it is used at.** The rule is: starts with `=`, the prefix
> goes inside; anything else, the prefix goes in front. The places are the `processId` of a
> `zeebe:calledElement`, the `decisionId` of a `zeebe:calledDecision`, a `bpmn:message` name, a
> `bpmn:signal` name, a `bpmn:error` code, a `bpmn:escalation` code, a `zeebe:taskDefinition` type, a
> `zeebe:formDefinition` external reference and the job type of a listener this application serves.
> `Camunda8Scoping#forEveryPrefixedValue` is that list, and both the rewrite and the refusal below read
> it, so a place added to it is covered by both in one change. A job type carries the prefix of its
> BPMN process as well, so its frame is `="loan-approval__LoanApproval__" + string(...)`.
>
> **Whether Camunda 8 evaluates an expression at a given place is not asked.** The maintainer decided
> that on 2026-10-01: a list of the places Camunda evaluates ages with every Camunda release, and
> writing one down would freeze today's answer into this adapter. The rule cannot be wrong instead.
> Where an expression is not evaluated, a value starting with `=` does not appear and the rule costs
> nothing; where Camunda learns to evaluate one, nothing here has to change. What is still watched is
> that the cluster ACCEPTS a model carrying the frame at those places, which the canary does by
> deploying one model twice, once plain and once framed. Measured on 2026-10-01 against
> `camunda/camunda:8.9.21`: both deployments went through. The first attempt did not, and what it
> refused was the test's own XML rather than the frame (`Element type "bpmn:message" must be followed
> by either attribute specifications, ">" or "/>"`), because a FEEL expression carries quotes of its
> own and those values sit in XML attributes. A model written by a modeller is escaped by the
> modeller; a model composed in a test is not.
>
> The prefix cannot go in front of such a value. An expression takes up the whole attribute, so
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
> the same in every case down to the wording of an incident. Those measurements are about the called
> process and the called decision, which were the places Camunda evaluated an expression at on that
> day; the frame is the same everywhere else, and the paragraph above says why it is not measured per
> place. The alternative frame,
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
> An expression which composes the prefix ITSELF ends the boot, naming the element, the file, the
> attribute and the expression to change, at any of the places above. The rewrite would give it a second prefix, the cluster would be asked for
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
> both of the attributes above, and that the frame survives the XML the deploy command sends.
> `Camunda8PrefixInEveryPlaceTest` holds the two forms at every other place, the longer prefix of a
> job type and the refusal covering all of them. `Camunda8PrefixInsideAnExpressionTest` holds the
> refused boot and the sentence about a quoted expression.
> `Camunda8PrefixInsideAnExpressionCanaryIT` holds the cluster to what was measured and to accepting
> the frame everywhere else.
>
> See [Keeping workflow modules apart](./README.md#keeping-workflow-modules-apart).

