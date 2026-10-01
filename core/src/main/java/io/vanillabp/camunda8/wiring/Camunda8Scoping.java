package io.vanillabp.camunda8.wiring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.BpmnModelElementInstance;
import io.camunda.zeebe.model.bpmn.instance.Error;
import io.camunda.zeebe.model.bpmn.instance.Escalation;
import io.camunda.zeebe.model.bpmn.instance.FlowElement;
import io.camunda.zeebe.model.bpmn.instance.Message;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.Signal;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledElement;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeFormDefinition;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskDefinition;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies {@link NameClashAvoidance#USE_PREFIX} to a Camunda 8 model:
 * every identifier a cluster resolves GLOBALLY is prefixed, so two workflow modules
 * may use the same names without a tenant.
 *
 * <table>
 * <caption>What is rewritten</caption>
 * <tr><th>Element</th><th>Scoped by</th><th>Why</th></tr>
 * <tr><td>{@code bpmn:process id}</td><td>workflow module</td><td>the process id addresses a process definition cluster-wide</td></tr>
 * <tr><td>{@code zeebe:calledElement processId}</td><td>workflow module</td><td>a call activity has to address the renamed process, and where it names it as FEEL the prefix goes inside the expression</td></tr>
 * <tr><td>{@code zeebe:calledDecision decisionId}</td><td>workflow module</td><td>the decisions of the module are deployed under prefixed ids, FEEL included</td></tr>
 * <tr><td>{@code bpmn:message name}</td><td>workflow module</td><td>messages are published and correlated by name</td></tr>
 * <tr><td>{@code bpmn:signal name}, {@code bpmn:escalation escalationCode}</td><td>workflow module</td><td>broadcast by name</td></tr>
 * <tr><td>{@code bpmn:error errorCode}</td><td>workflow module</td><td>completeness - a code is process-local, but the application may throw it via {@code ProcessService#cancelTask}</td></tr>
 * <tr><td>{@code zeebe:taskDefinition type}</td><td>workflow module + process</td><td>job types are what workers subscribe to, cluster-wide</td></tr>
 * <tr><td>{@code zeebe:formDefinition externalReference}</td><td>workflow module + process</td><td>it IS the user task's task definition and becomes a listener job type</td></tr>
 * </table>
 *
 * <p>
 * An element another runtime serves, see {@link Camunda8Connectors}, never enters that
 * table: its job type names a runtime somebody else deployed rather than an identifier of
 * this workflow module, so it is left as the modeller wrote it under every mode.
 * <p>
 * A name written as FEEL is rewritten too, and there are two of them: the process a call
 * activity calls and the decision a business rule task evaluates. Such a name is code which
 * yields an identifier at runtime, so the prefix cannot be written in front of it - it goes
 * INSIDE it, and {@code =whichProcess} is deployed as
 * {@code ="loan-approval__" + string(whichProcess)}. The application therefore writes no
 * prefix anywhere, which is what keeps a model portable between this BPMS and the others.
 * What that costs is named rather than hidden: the cluster holds an expression nobody typed,
 * so a parse error it reports quotes this frame around the application's own text
 * ({@link #elementIdsNamingTheirTargetByExpression} is read for the one message where that
 * matters). An expression which carries the prefix already is refused instead of being given
 * a second one ({@link #targetsWhoseExpressionAlreadyCarriesThePrefix}).
 * <p>
 * The same elements are READ rather than rewritten where somebody asks which names a
 * workflow module declares ({@link #moduleWideIdentifiersOf},
 * {@link #taskDefinitionsOf}). One reader is the deployment, which hands them to the core
 * so that two workflow modules ending up under one name are named; the other is a model
 * the cluster still holds, where the same names are read back out of what was deployed
 * years ago.
 * <p>
 * The rewriting happens in <code>prepareBpmn</code>, BEFORE wiring: everything after
 * it - the wiring validation, the listener injection, the workers - therefore sees
 * the identifiers the cluster will see, while the core keeps working with the plain
 * ones (the adapter translates at every boundary).
 * <p>
 * Why the deployed bytes carry the scoped identifiers while the registries stay plain is decision 2
 * in the repository's DECISIONS.md; that this rewrite is one of the model changes the adapter makes
 * is decision 5 in the repository's DECISIONS.md.
 */
@Slf4j
public final class Camunda8Scoping {

  private Camunda8Scoping() {
  }

  /**
   * The TENANT a workflow module is deployed to, respectively an operation of it runs
   * in: the workflow module id unless the application configured a name, and
   * <code>null</code> wherever the mode is not {@link NameClashAvoidance#BY_ADAPTER}
   * ({@code none} uses no tenant, and under {@code use-prefix} the prefix IS the
   * isolation - a tenant on top would defeat the purpose, since clusters are licensed
   * per tenant).
   * <p>
   * That a tenant is what {@code by-adapter} means here is CAMUNDA 8 knowledge, so it
   * lives in the adapter: the core answers the mode and nothing else.
   * <p>
   * Two questions read this one function. One is where a workflow module is deployed, the
   * other is whether the cluster keeps two workflow modules apart
   * ({@code Camunda8DeploymentService#ownIsolationSeparatesWorkflowModules}), which the core
   * asks while it looks for two BPMN processes reaching the cluster under one identifier. An
   * answer composed some other way could say "separated" about a module the very next deploy
   * command puts into the tenant of its neighbour.
   *
   * @param scoping The core's name-clash-avoidance support, or <code>null</code>
   *          (tests): the configured tenant is used as it is then
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @param configuredTenantId The tenant name the application configured for this workflow
   *          module, resolved over the levels it may be set at (see
   *          {@link Camunda8ConfiguredTenant}), or <code>null</code>
   * @return The tenant ID, or <code>null</code> if the mode uses none
   */
  public static String tenantIdFor(
      final NameClashAvoidanceSupport scoping,
      final String workflowModuleId,
      final String adapterId,
      final String configuredTenantId) {

    final var configured = (configuredTenantId != null) && !configuredTenantId.isBlank()
        ? configuredTenantId
        : null;
    if (scoping == null) {
      return configured;
    }
    if (scoping.modeFor(workflowModuleId, null, adapterId) != NameClashAvoidance.BY_ADAPTER) {
      return null;
    }
    return configured != null
        ? configured
        : workflowModuleId;

  }

  /**
   * Whether the given workflow module's identifiers are prefixed for this adapter -
   * asked by the deployment service for the decision tables of the module, whose ids are
   * rewritten outside a BPMN model.
   *
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @param scoping The core's name-clash-avoidance support (may be <code>null</code>)
   * @return Whether prefixing applies
   */
  public static boolean prefixes(
      final String workflowModuleId,
      final String adapterId,
      final NameClashAvoidanceSupport scoping) {

    return (scoping != null) && (scoping.modeFor(workflowModuleId, null, adapterId) == NameClashAvoidance.USE_PREFIX);

  }

  /**
   * The names of the given model which the workflow module scopes as a whole: a message
   * name, a signal name, a BPMN error code, an escalation code. Every one of them is a
   * name the cluster resolves globally, which is why {@link #apply} rewrites them, and
   * reading them costs nothing while the model is open anyway.
   * <p>
   * The names are returned as they STAND in the model, so the caller decides what they
   * are: a model about to be deployed carries the plain names as long as {@link #apply}
   * has not run over it, a model the cluster hands back carries the scoped ones.
   *
   * @param model The model of one BPMN file
   * @return One entry per name, without duplicates and with no BPMN process on it -
   *         these names belong to the workflow module, not to one of its processes
   */
  public static Collection<NameClashAvoidanceSupport.ModelIdentifier> moduleWideIdentifiersOf(
      final BpmnModelInstance model) {

    final var identifiers = new LinkedHashSet<NameClashAvoidanceSupport.ModelIdentifier>();
    model
        .getModelElementsByType(Message.class)
        .forEach(message -> collectIfWritten(
            identifiers, NameClashAvoidanceSupport.ScopedIdentifierKind.MESSAGE_NAME, message.getName(), null));
    model
        .getModelElementsByType(Signal.class)
        .forEach(signal -> collectIfWritten(
            identifiers, NameClashAvoidanceSupport.ScopedIdentifierKind.SIGNAL_NAME, signal.getName(), null));
    model
        .getModelElementsByType(Error.class)
        .forEach(error -> collectIfWritten(
            identifiers, NameClashAvoidanceSupport.ScopedIdentifierKind.ERROR_CODE, error.getErrorCode(), null));
    model
        .getModelElementsByType(Escalation.class)
        .forEach(escalation -> collectIfWritten(
            identifiers,
            NameClashAvoidanceSupport.ScopedIdentifierKind.ESCALATION_CODE,
            escalation.getEscalationCode(),
            null));
    return identifiers;

  }

  /**
   * The job types of the given model, each with the BPMN process it belongs to - a task
   * definition is scoped per process as well as per workflow module. A user task's
   * external form reference is among them, because it becomes a listener job type like any
   * other.
   * <p>
   * The element an element template hands to another runtime is left out, the same way
   * {@link #apply} leaves its job type alone: that name belongs to a runtime somebody else
   * deployed rather than to this workflow module.
   *
   * @param model The model of one BPMN file
   * @param workflowModuleId The workflow module ID
   * @param allowConnectorsResolver What the configuration says about the elements built
   *          from an element template (may be <code>null</code>: nothing is left out then)
   * @return One entry per job type, without duplicates, as the names stand in the model
   */
  public static Collection<NameClashAvoidanceSupport.ModelIdentifier> taskDefinitionsOf(
      final BpmnModelInstance model,
      final String workflowModuleId,
      final Camunda8AllowConnectorsResolver allowConnectorsResolver) {

    final var identifiers = new LinkedHashSet<NameClashAvoidanceSupport.ModelIdentifier>();
    model
        .getModelElementsByType(ZeebeTaskDefinition.class)
        .forEach(taskDefinition -> {
          if (isServedByAnotherRuntime(taskDefinition, workflowModuleId, allowConnectorsResolver)) {
            return;
          }
          collectIfWritten(
              identifiers,
              NameClashAvoidanceSupport.ScopedIdentifierKind.TASK_DEFINITION,
              taskDefinition.getType(),
              owningProcessId(taskDefinition));
        });
    model
        .getModelElementsByType(ZeebeFormDefinition.class)
        .forEach(formDefinition -> {
          if (isServedByAnotherRuntime(formDefinition, workflowModuleId, allowConnectorsResolver)) {
            return;
          }
          collectIfWritten(
              identifiers,
              NameClashAvoidanceSupport.ScopedIdentifierKind.TASK_DEFINITION,
              formDefinition.getExternalReference(),
              owningProcessId(formDefinition));
        });
    return identifiers;

  }

  /**
   * Adds one name to what was read, leaving out what the modeller did not write.
   */
  private static void collectIfWritten(
      final Collection<NameClashAvoidanceSupport.ModelIdentifier> identifiers,
      final NameClashAvoidanceSupport.ScopedIdentifierKind kind,
      final String identifier,
      final String bpmnProcessId) {

    if ((identifier == null) || identifier.isBlank()) {
      return;
    }
    identifiers.add(new NameClashAvoidanceSupport.ModelIdentifier(kind, identifier, bpmnProcessId));

  }

  /**
   * Rewrites the identifiers of the given model in place. A no-op unless the mode of
   * the workflow module is {@link NameClashAvoidance#USE_PREFIX}.
   *
   * @param model The model of one BPMN file
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @param scoping The core's name-clash-avoidance support
   * @param allowConnectorsResolver What the configuration says about the elements built
   *          from an element template, asked per BPMN process of the file (may be
   *          <code>null</code>: nothing is left alone then)
   * @param servedListenerJobTypes Whether this application serves the listener of the given
   *          PLAIN process id and job type, asked per listener of the file (may be
   *          <code>null</code>: no listener job type is rewritten then)
   */
  public static void apply(
      final BpmnModelInstance model,
      final String workflowModuleId,
      final String adapterId,
      final NameClashAvoidanceSupport scoping,
      final Camunda8AllowConnectorsResolver allowConnectorsResolver,
      final java.util.function.BiPredicate<String, String> servedListenerJobTypes) {

    if (!prefixes(workflowModuleId, adapterId, scoping)) {
      return;
    }

    // the job type of a listener this application serves is a task definition of this
    // workflow module like any other, so it is scoped like any other: without that, two
    // modules carrying the same listener job type would share one worker, which is the
    // clash this mode exists to avoid. A listener this application does NOT serve keeps the
    // name the modeller typed, for the reason a connector's job type keeps its: renaming it
    // would rename something this application does not own
    model
        .getModelElementsByType(io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListener.class)
        .forEach(listener -> scopeListenerJobType(
            listener.getType(),
            listener::setType,
            listener,
            workflowModuleId,
            adapterId,
            scoping,
            servedListenerJobTypes));
    model
        .getModelElementsByType(io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListener.class)
        .forEach(listener -> scopeListenerJobType(
            listener.getType(),
            listener::setType,
            listener,
            workflowModuleId,
            adapterId,
            scoping,
            servedListenerJobTypes));

    // task definitions are scoped per PROCESS, so they are rewritten while the
    // process ids are still the plain ones
    model
        .getModelElementsByType(ZeebeTaskDefinition.class)
        .forEach(taskDefinition -> {
          if (isServedByAnotherRuntime(taskDefinition, workflowModuleId, allowConnectorsResolver)) {
            return;
          }
          taskDefinition.setType(
              scoping.scopedTaskDefinition(
                  workflowModuleId,
                  owningProcessId(taskDefinition),
                  taskDefinition.getType(),
                  adapterId));
        });
    model
        .getModelElementsByType(ZeebeFormDefinition.class)
        .forEach(formDefinition -> {
          final var externalReference = formDefinition.getExternalReference();
          if ((externalReference == null) || externalReference.isBlank()) {
            return;
          }
          if (isServedByAnotherRuntime(formDefinition, workflowModuleId, allowConnectorsResolver)) {
            return;
          }
          formDefinition.setExternalReference(
              scoping.scopedTaskDefinition(
                  workflowModuleId,
                  owningProcessId(formDefinition),
                  externalReference,
                  adapterId));
        });

    model
        .getModelElementsByType(Message.class)
        .forEach(message -> message.setName(
            scoping.scopedIdentifier(workflowModuleId, message.getName(), adapterId)));
    model
        .getModelElementsByType(Signal.class)
        .forEach(signal -> signal.setName(
            scoping.scopedIdentifier(workflowModuleId, signal.getName(), adapterId)));
    model
        .getModelElementsByType(Escalation.class)
        .forEach(escalation -> escalation.setEscalationCode(
            scoping.scopedIdentifier(workflowModuleId, escalation.getEscalationCode(), adapterId)));
    // an error code carries the prefix of the module whose model declares it, and so does
    // the code a TaskException raises (Camunda8JobHandler). Both sides of a throw and its
    // catcher are therefore the same module, which they are: a called element below gets this
    // module's prefix, and a called element carries no tenant of its own, so the cluster
    // resolves it in the tenant of the calling instance. A call activity cannot leave its
    // workflow module here
    model
        .getModelElementsByType(Error.class)
        .forEach(error -> error.setErrorCode(
            scoping.scopedIdentifier(workflowModuleId, error.getErrorCode(), adapterId)));

    // call activities address another process BY ID - rewrite before the ids change. A
    // process named as an expression (it starts with '=') gets the prefix inside the
    // expression, like the decision id below: the expression takes up the whole value, so a
    // prefix in front of it would make a string which names no process and is no expression
    // either
    final var prefix = prefixOf(workflowModuleId, adapterId, scoping);
    model
        .getModelElementsByType(ZeebeCalledElement.class)
        .forEach(calledElement -> {
          final var processId = calledElement.getProcessId();
          if (namesNothing(processId)) {
            return;
          }
          calledElement
              .setProcessId(
                  isWrittenAsFeel(processId)
                      ? withThePrefixInside(processId, prefix, workflowModuleId)
                      : scoping.scopedProcessId(workflowModuleId, processId, adapterId));
        });

    // a business rule task addresses a decision BY ID, and the decisions this module
    // deploys were renamed the same way while their files were read. An id given as an
    // expression gets the prefix inside it, for the reason the called process does
    model
        .getModelElementsByType(io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledDecision.class)
        .forEach(calledDecision -> {
          final var decisionId = calledDecision.getDecisionId();
          if (namesNothing(decisionId)) {
            return;
          }
          calledDecision
              .setDecisionId(
                  isWrittenAsFeel(decisionId)
                      ? withThePrefixInside(decisionId, prefix, workflowModuleId)
                      : scoping.scopedIdentifier(workflowModuleId, decisionId, adapterId));
        });

    // ... and the process ids last
    model
        .getModelElementsByType(Process.class)
        .forEach(process -> {
          final var scoped = scoping.scopedProcessId(workflowModuleId, process.getId(), adapterId);
          if (scoped.equals(process.getId())) {
            return;
          }
          log.debug(
              "Camunda8: BPMN process '{}' of workflow module '{}' is deployed as '{}' (name-clash "
                  + "avoidance 'use-prefix')",
              process.getId(),
              workflowModuleId,
              scoped);
          process.setId(scoped);
        });

  }

  /**
   * Rewrites the job type of one listener where the workflow module serves it and prefixes
   * its identifiers.
   *
   * @param jobType What the listener names today
   * @param setJobType Where the scoped name goes
   * @param listener The listener element, for the process it belongs to
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @param scoping The core's name-clash-avoidance support
   * @param servedListenerJobTypes Whether this application serves that listener, or
   *          <code>null</code>
   */
  private static void scopeListenerJobType(
      final String jobType,
      final java.util.function.Consumer<String> setJobType,
      final BpmnModelElementInstance listener,
      final String workflowModuleId,
      final String adapterId,
      final NameClashAvoidanceSupport scoping,
      final java.util.function.BiPredicate<String, String> servedListenerJobTypes) {

    if ((jobType == null) || jobType.isBlank() || (servedListenerJobTypes == null)) {
      return;
    }
    final var element = Camunda8Connectors.owningElementOf(listener);
    if (element == null) {
      return;
    }
    final var bpmnProcessId = owningProcessId(element);
    if (!servedListenerJobTypes.test(bpmnProcessId, jobType)) {
      return;
    }
    setJobType.accept(scoping.scopedTaskDefinition(workflowModuleId, bpmnProcessId, jobType, adapterId));

  }

  /**
   * Whether the element carrying this extension element is one a runtime other than this
   * application serves, and connectors are allowed for its process.
   * <p>
   * Its job type was never an identifier of this workflow module. It names a runtime
   * somebody else deployed, cluster-wide, so prefixing it would rename something this
   * application does not own. The price is stated rather than
   * hidden: under {@code use-prefix} such a job type reaches the cluster unscoped, which is
   * the very clash the mode exists to avoid. It costs nothing here, because a connector
   * runtime subscribes to that type globally anyway and two workflow modules carrying the
   * same connector element are meant to reach the same runtime. Refusing the combination
   * instead would take the only isolation mode which works without a multi-tenant cluster
   * away from every application that wants one connector.
   */
  private static boolean isServedByAnotherRuntime(
      final BpmnModelElementInstance extensionElement,
      final String workflowModuleId,
      final Camunda8AllowConnectorsResolver allowConnectorsResolver) {

    final var element = Camunda8Connectors.owningElementOf(extensionElement);
    if ((element == null) || !Camunda8Connectors.isServedByAnotherRuntime(element)) {
      return false;
    }
    return Camunda8AllowConnectorsResolver
        .resolve(allowConnectorsResolver, workflowModuleId, owningProcessId(element))
        .allowed();

  }

  /**
   * Whether the given name of a called process or decision names nothing at all. Two forms do:
   * one the modeller left empty, and one holding nothing but <code>=</code>, which is an
   * expression with no expression in it. The cluster refuses both itself, and anything composed
   * around them would only hide what it refuses.
   *
   * @param identifier What the model says the call points at
   * @return Whether there is nothing to rewrite
   */
  private static boolean namesNothing(
      final String identifier) {

    return (identifier == null) || identifier.isBlank() || "=".equals(identifier.strip());

  }

  /**
   * Whether the given name of a called process or decision is FEEL rather than an identifier,
   * which it is where it starts with <code>=</code>. Such a name is code: it takes up the
   * WHOLE attribute value, so the prefix goes inside it.
   *
   * @param identifier What the model says the call points at
   * @return Whether it is an expression
   */
  private static boolean isWrittenAsFeel(
      final String identifier) {

    return identifier.startsWith("=");

  }

  /**
   * The given expression with the workflow module's prefix written into it, so that what it
   * yields at runtime is the id the cluster knows.
   * <p>
   * The frame is a concatenation of two strings, which Camunda's FEEL does with
   * <code>+</code>, and {@code string(...)} around the application's part makes the
   * concatenation work whatever that part returns. The parentheses are what carry every shape
   * an expression can have: an {@code if ... then ... else ...} returning one of several ids,
   * a {@code get value(...)} over a context, a text the expression composes itself, and an
   * expression written over several lines.
   * <p>
   * What this costs is that the cluster then holds an expression nobody typed. Where the
   * application's part does not parse, the cluster refuses the deployment quoting this whole
   * expression, and the column it counts is counted from the opening quote. The deployment
   * says so where it reports such a refusal.
   * <p>
   * Measured against a cluster of every release line on 2026-10-01, with
   * {@code Camunda8PrefixInsideAnExpressionCanaryIT} holding the result: the frame resolves
   * the called process respectively the called decision, and where the application's part
   * yields <code>null</code> the cluster raises an incident naming the missing variable AND
   * this frame.
   *
   * @param expression The expression as the modeller wrote it, <code>=</code> included
   * @param prefix The prefix of the workflow module, separator included
   * @param workflowModuleId The workflow module, for the log line
   * @return The expression to deploy
   */
  private static String withThePrefixInside(
      final String expression,
      final String prefix,
      final String workflowModuleId) {

    final var wrapped = "=\"%s\" + string(%s)".formatted(prefix, expression.substring(1).strip());
    log.debug(
        "Camunda8: '{}' of workflow module '{}' is deployed as '{}' (name-clash avoidance "
            + "'use-prefix' writes the prefix INTO the expression)",
        expression,
        workflowModuleId,
        wrapped);
    return wrapped;

  }

  /**
   * The prefix the scoped identifiers of the given workflow module start with, separator
   * included.
   * <p>
   * Read off a scoped identifier rather than composed here: the core owns how a prefix is
   * built, and a second way of spelling it out could produce something the core never writes.
   *
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @param scoping The core's name-clash-avoidance support
   * @return The prefix, and the empty string where nothing is prefixed
   */
  public static String prefixOf(
      final String workflowModuleId,
      final String adapterId,
      final NameClashAvoidanceSupport scoping) {

    final var marker = "TheProcessYouCall";
    final var scoped = scoping.scopedProcessId(workflowModuleId, marker, adapterId);
    return scoped.endsWith(marker)
        ? scoped.substring(0, scoped.length() - marker.length())
        : scoped;

  }

  /**
   * The elements of one BPMN process which name the process they call or the decision they
   * evaluate as a FEEL expression.
   * <p>
   * Read for the one message such an element owes a developer. Under {@code use-prefix} the
   * expression reaching the cluster is the application's part inside a frame of this adapter,
   * so a parse error the cluster reports for it quotes more than the developer wrote. The
   * deployment names these elements where the cluster refuses a deployment, and says nothing
   * about them otherwise: there is nothing for anybody to do.
   *
   * @param model The BPMN model of one file
   * @param bpmnProcessId The process id as the CLUSTER will know it
   * @return The element ids, empty where every call names its target statically
   */
  public static List<String> elementIdsNamingTheirTargetByExpression(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    final var elementIds = new LinkedHashSet<String>();
    collectElementsNamingTheirTargetByExpression(
        model, ZeebeCalledElement.class, ZeebeCalledElement::getProcessId, bpmnProcessId, elementIds::add);
    collectElementsNamingTheirTargetByExpression(
        model,
        io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledDecision.class,
        io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledDecision::getDecisionId,
        bpmnProcessId,
        elementIds::add);
    return List.copyOf(elementIds);

  }

  /**
   * Adds the ids of the elements of one BPMN process whose target is named by an expression,
   * for one kind of extension element.
   *
   * @param <T> The kind of extension element to read
   * @param model The BPMN model of one file
   * @param kind The extension element naming the target
   * @param identifierOf What that element says the call points at
   * @param bpmnProcessId The process the element has to belong to
   * @param found Where the element ids go
   */
  private static <T extends BpmnModelElementInstance> void collectElementsNamingTheirTargetByExpression(
      final BpmnModelInstance model,
      final Class<T> kind,
      final java.util.function.Function<T, String> identifierOf,
      final String bpmnProcessId,
      final java.util.function.Consumer<String> found) {

    model
        .getModelElementsByType(kind)
        .forEach(extensionElement -> {
          final var identifier = identifierOf.apply(extensionElement);
          if (namesNothing(identifier) || !isWrittenAsFeel(identifier)) {
            return;
          }
          final var element = Camunda8Connectors.owningElementOf(extensionElement);
          if ((element == null) || !bpmnProcessId.equals(owningProcessId(element))) {
            return;
          }
          found.accept(element.getId());
        });

  }

  /**
   * The elements of one BPMN FILE whose expression already carries the given prefix.
   * <p>
   * Such an expression composes the scoped id itself, which is what an earlier VanillaBP 2
   * snapshot asked an application to do. Writing the prefix into it a second time would deploy
   * an expression yielding the prefix twice, and every call of it would fail once a workflow
   * reached the element. So the deployment refuses the file and says what to take out, which
   * is a boot a developer can act on instead of an incident per instance.
   * <p>
   * Read BEFORE {@link #apply} runs over the model, because afterwards every expression
   * carries the prefix by design.
   *
   * @param model The BPMN model of one file, as the modeller wrote it
   * @param prefix The prefix of the workflow module, separator included
   * @return One entry per element, empty where no expression mentions the prefix
   */
  public static List<NamedByAnExpression> targetsWhoseExpressionAlreadyCarriesThePrefix(
      final BpmnModelInstance model,
      final String prefix) {

    final var found = new ArrayList<NamedByAnExpression>();
    collectExpressionsCarrying(
        prefix, model, ZeebeCalledElement.class, ZeebeCalledElement::getProcessId, found);
    collectExpressionsCarrying(
        prefix,
        model,
        io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledDecision.class,
        io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledDecision::getDecisionId,
        found);
    return found;

  }

  /**
   * Adds the elements of one kind whose expression already carries the prefix.
   *
   * @param <T> The kind of extension element to read
   * @param prefix The prefix of the workflow module, separator included
   * @param model The BPMN model of one file
   * @param kind The extension element naming the target
   * @param identifierOf What that element says the call points at
   * @param found Where the entries go
   */
  private static <T extends BpmnModelElementInstance> void collectExpressionsCarrying(
      final String prefix,
      final BpmnModelInstance model,
      final Class<T> kind,
      final java.util.function.Function<T, String> identifierOf,
      final Collection<NamedByAnExpression> found) {

    if (prefix.isEmpty()) {
      return;
    }
    model
        .getModelElementsByType(kind)
        .forEach(extensionElement -> {
          final var identifier = identifierOf.apply(extensionElement);
          if (namesNothing(identifier) || !isWrittenAsFeel(identifier) || !identifier.contains(prefix)) {
            return;
          }
          final var element = Camunda8Connectors.owningElementOf(extensionElement);
          if (element == null) {
            return;
          }
          found.add(new NamedByAnExpression(element.getId(), owningProcessId(element), identifier));
        });

  }

  /**
   * The sentence a developer needs where the cluster refuses a deployment and quotes an
   * expression this adapter wrote the prefix into.
   * <p>
   * Camunda 8 parses the FEEL of a model while it deploys it, and a parse error names the
   * element and quotes the whole expression with the column it stopped at. Under
   * {@code use-prefix} that expression is the application's own text inside the frame of
   * {@link #withThePrefixInside}, so both the quote and the column read as if the developer
   * had typed something they never typed. Measured on 2026-10-01 against a cluster of every
   * release line, with {@code string(whichProcess +)} inside the frame: the answer was
   * {@code failed to parse expression '"loan-approval__" + string(whichProcess +)' ... :1:27},
   * where the same mistake without a frame around it was reported at {@code :1:14}.
   * <p>
   * This is the only place the frame is mentioned at runtime. While a deployment goes through
   * there is nothing for anybody to do, and a line per boot about a model which is right is
   * what this adapter took away when it started writing the prefix itself.
   *
   * @param nameClashAvoidanceKey The property key which put the workflow module into this mode
   * @param prefix The prefix of the workflow module, separator included
   * @param elementsPerBpmnProcess The elements named by an expression, per BPMN process
   * @return The sentence, starting with a space, or an empty string where there is no such
   *         element
   */
  public static String whatAQuotedExpressionIncludes(
      final String nameClashAvoidanceKey,
      final String prefix,
      final java.util.Map<String, List<String>> elementsPerBpmnProcess) {

    if (elementsPerBpmnProcess.isEmpty()) {
      return "";
    }
    return ("""
         On the expressions this answer may quote: %d element(s) of this workflow module name \
        the process they call or the decision they evaluate by a FEEL expression, and \
        name-clash avoidance 'use-prefix' (%s) deploys such an expression with the prefix \
        written INSIDE it, as ="%s" + string(<your expression>). So an expression quoted above \
        is longer than the one you wrote, and a column counted in it starts at the opening \
        quote rather than at your own text. The elements are: %s.""")
        .formatted(
            elementsPerBpmnProcess
                .values()
                .stream()
                .mapToInt(List::size)
                .sum(),
            nameClashAvoidanceKey,
            prefix,
            elementsPerBpmnProcess
                .entrySet()
                .stream()
                .map(perProcess -> "%s of BPMN process '%s'"
                    .formatted(
                        String
                            .join(
                                ", ",
                                perProcess
                                    .getValue()
                                    .stream()
                                    .map("'%s'"::formatted)
                                    .toList()),
                        perProcess.getKey()))
                .collect(java.util.stream.Collectors.joining(", ")));

  }

  /**
   * One element naming the process it calls or the decision it evaluates by a FEEL
   * expression.
   *
   * @param elementId The call activity respectively the business rule task
   * @param bpmnProcessId The BPMN process it belongs to, as it stands in the model
   * @param expression The expression, <code>=</code> included
   */
  public record NamedByAnExpression(
                                    String elementId,
                                    String bpmnProcessId,
                                    String expression) {
  }

  /**
   * The id of the {@code bpmn:process} the given element belongs to - task
   * definitions are scoped per process, and at rewriting time the process ids are
   * still plain.
   */
  private static String owningProcessId(
      final BpmnModelElementInstance element) {

    var current = element.getParentElement();
    while (current != null) {
      if (current instanceof Process process) {
        return process.getId();
      }
      current = current.getParentElement();
    }
    // an element outside any process (should not happen for task definitions) -
    // scoping by the module alone is the safe fallback
    if (element instanceof FlowElement flowElement) {
      log.warn(
          "Camunda8: could not determine the BPMN process of element '{}' - its task definition is "
              + "scoped by the workflow module only",
          flowElement.getId());
    }
    return null;

  }

}
