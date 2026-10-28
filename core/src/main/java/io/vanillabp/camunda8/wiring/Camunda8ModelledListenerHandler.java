package io.vanillabp.camunda8.wiring;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.JobKind;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.camunda8.client.Camunda8UserTaskProbe;
import io.vanillabp.camunda8.processservice.Camunda8ProcessService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.spi.service.TaskEvent;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Consumes the jobs of a listener SOMEBODY MODELLED, which the application asked for with
 * {@code allow-listeners} (see decision 27 in the repository's DECISIONS.md).
 *
 * <h2>Why this is not the user-task listener handler with a flag</h2>
 *
 * {@link Camunda8UserTaskListenerHandler} serves the listeners VanillaBP writes itself. It
 * knows their job type by construction. Its notification is OPTIONAL. And it reports the
 * user-task key, so the task can be completed later. None of that holds here. The job type
 * of a modelled listener is what the modeller typed. A method for it is MANDATORY, because
 * the wiring validation asked for one. And there is no user task to complete. So the two
 * handlers share the commands they send, but not a class.
 *
 * <h2>What the method is told, and what it is not</h2>
 *
 * The event is part of the identity of the listener. The wiring made one task of one
 * listener. So the method serves one event of one element and cannot be in doubt about which.
 * So {@code @TaskEvent} receives {@link TaskEvent.Event#CREATED} for every listener. That is
 * the only value which works. A method without a {@code @TaskEvent} parameter subscribes to
 * CREATED alone. So with any other value, such a method would silently not be called.
 * {@code TaskEvent.Event} has no value for the event of a listener, and the startup
 * report says that out loud.
 *
 * <h2>What the completion carries</h2>
 *
 * Three cases, and the job says which one it is.
 * <p>
 * An EXECUTION listener on {@code end} completes like a service task. It sends the values
 * the workflow aggregate shares plus the aggregate-ID variable. The cluster puts them into
 * the process instance. So a method serving such a listener may change the aggregate, and
 * the gateway behind the element decides on what it wrote (see decision 1 in the
 * repository's DECISIONS.md).
 * <p>
 * An EXECUTION listener on {@code start} completes with nothing. Variables of that
 * completion would not reach the process instance. The cluster keeps them local to the
 * element. The local copy then swallows every later write of the same name from inside the
 * element, including the one of the job of the element itself. And it dies with the element.
 * Sending the aggregate there would silently cost the task of the element its values. Whoever
 * has to write into the process models a task of the process.
 * <p>
 * A TASK listener completes with nothing either, and here there is no choice. The cluster
 * answers a task-listener completion carrying variables with INVALID_ARGUMENT. It says the
 * payload is not supported yet and names its issue 23702. The day it is supported,
 * {@code Camunda8TaskListenerVariablesCanaryIT} of the spring-boot module turns red to
 * report it. This place then decides what such a listener may write.
 */
@Slf4j
public class Camunda8ModelledListenerHandler implements JobHandler {

  private final String adapterId;

  private final String workflowModuleId;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * Translates the identifiers the cluster reports back into the plain ones. It does nothing
   * unless the workflow module uses prefixes. May be <code>null</code> (tests).
   */
  private final NameClashAvoidanceSupport scoping;

  /**
   * Which multi-instance elements enclose the element the listener sits on.
   * May be <code>null</code> (tests).
   */
  private final Camunda8MultiInstance.Registry multiInstanceRegistry;

  /**
   * What the workflow module has in flight, and whether it is going down. Never
   * <code>null</code>. A handler built without one (tests) gets a drain of its own, which
   * never shuts down.
   */
  private final Camunda8Drain drain;

  /**
   * What this worker asked the cluster for. Never <code>null</code>. A handler built without
   * one (tests) sees every variable.
   */
  private final Camunda8FetchVariables.Selection fetchVariables;

  /**
   * How long the cluster waits before it hands a failed listener job out again. May be
   * <code>null</code>. Then the default of the resolver applies.
   */
  private final Camunda8RetryBackoffResolver retryBackoffResolver;

  /**
   * Whether the incident of a failed listener job carries the whole stack trace. May be
   * <code>null</code>. Then the default of the resolver applies.
   */
  private final Camunda8ReportErrorsAsStackTraceResolver reportErrorsAsStackTraceResolver;

  /**
   * Asks the cluster about the OTHER tasks this workflow has open, once this notification is
   * through. May be <code>null</code>, which asks nothing.
   */
  private final Camunda8OpenTaskProbe openTaskProbe;

  /**
   * Answers whether the model of a listener asks for retries, see decision 87 in the
   * repository's DECISIONS.md. May be <code>null</code> (tests). Then a failed job keeps one
   * retry less, as Camunda does on its own.
   */
  private final Camunda8ModelledRetries.ListenerModels modelledRetries;

  /**
   * The listeners whose model was already reported as unreadable, or as writing no
   * <code>retries</code>. Each is reported once, not once per failed job.
   */
  private final Set<String> retriesReported = ConcurrentHashMap.newKeySet();

  /**
   * What kind of worker this is, in the messages about a shutdown.
   */
  static final String KIND = "modelled listener";

  /**
   * The worker this handler serves.
   *
   * @param adapterId The adapter whose worker delivers here
   * @param workflowModuleId The workflow module the process of the listener belongs to
   * @param workflowTaskInvoker The runtime entry point of the core
   * @param scoping Translates the identifiers of the cluster back, or <code>null</code>
   * @param multiInstanceRegistry Which multi-instance elements enclose the element, or
   *          <code>null</code> for no iteration
   * @param drain What the workflow module has in flight, or <code>null</code> for a drain
   *          of its own, which never shuts down
   * @param fetchVariables What the worker asked for, or <code>null</code> for every variable
   * @param retryBackoffResolver How long a failed job waits, or <code>null</code> for the
   *          default
   * @param reportErrorsAsStackTraceResolver Whether an incident carries the stack trace, or
   *          <code>null</code> for the default
   * @param openTaskProbe What asks the cluster about the other open tasks of the workflow, or
   *          <code>null</code> to ask nothing
   * @param modelledRetries Whether the model of a listener asks for retries, or
   *          <code>null</code> to keep one retry less, as Camunda does on its own
   */
  @Builder
  public Camunda8ModelledListenerHandler(
      final String adapterId,
      final String workflowModuleId,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final NameClashAvoidanceSupport scoping,
      final Camunda8MultiInstance.Registry multiInstanceRegistry,
      final Camunda8Drain drain,
      final Camunda8FetchVariables.Selection fetchVariables,
      final Camunda8RetryBackoffResolver retryBackoffResolver,
      final Camunda8ReportErrorsAsStackTraceResolver reportErrorsAsStackTraceResolver,
      final Camunda8OpenTaskProbe openTaskProbe,
      final Camunda8ModelledRetries.ListenerModels modelledRetries) {

    this.openTaskProbe = openTaskProbe;
    this.modelledRetries = modelledRetries;
    this.fetchVariables = fetchVariables == null
        ? Camunda8FetchVariables.Selection.everything()
        : fetchVariables;
    this.drain = drain == null
        ? new Camunda8Drain(adapterId, workflowModuleId)
        : drain;
    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;
    this.workflowTaskInvoker = workflowTaskInvoker;
    this.scoping = scoping;
    this.multiInstanceRegistry = multiInstanceRegistry;
    this.retryBackoffResolver = retryBackoffResolver;
    this.reportErrorsAsStackTraceResolver = reportErrorsAsStackTraceResolver;

  }

  @Override
  public void handle(
      final JobClient client,
      final ActivatedJob job) {

    final var bpmnProcessId = NameClashAvoidanceSupport
        .plainProcessId(scoping, workflowModuleId, job.getBpmnProcessId(), adapterId);
    // the job type of the listener IS the task definition. It is prefixed like any other
    // one where the workflow module avoids name clashes that way.
    final var taskDefinition = NameClashAvoidanceSupport
        .plainTaskDefinition(scoping, workflowModuleId, bpmnProcessId, job.getType(), adapterId);

    Camunda8ListenerJobs
        .completeOrFail(
            adapterId,
            client,
            job,
            drain,
            KIND,
            taskDefinition,
            bpmnProcessId,
            () -> howToFailThisJob(job, bpmnProcessId, taskDefinition),
            () -> {
              if (Camunda8UserTaskProbe.isOurOwnProbe(job)) {
                // this job exists because THIS adapter asked whether the user task is
                // still open. The empty update fires a modelled 'updating' listener,
                // although it changes nothing. Closing it here keeps the question away
                // from the application. A method serving an 'updating' listener is written
                // for an update somebody made, and a probe made none.
                // Returning early also skips the check of the other open tasks below.
                // That matters more than it looks. That check sent this probe. Running it
                // again from the job it caused would probe the next user task, fire the
                // next listener and start over.
                log
                    .debug(
                        "Camunda8[{}]: the {} job '{}' (type '{}') was fired by this adapter's own "
                            + "user-task probe - completing it without calling the application",
                        adapterId,
                        KIND,
                        job.getKey(),
                        job.getType());
                return Map.of();
              }
              final var aggregateIdName = workflowTaskInvoker
                  .resolveWorkflowAggregateIdName(workflowModuleId, bpmnProcessId);
              final var aggregateId = job.getVariablesAsMap().get(aggregateIdName);
              if (aggregateId == null) {
                throw new IllegalStateException(
                    Camunda8FetchVariables
                        .missingAggregateId(
                            "The listener job",
                            job.getKey(),
                            job.getType(),
                            bpmnProcessId,
                            aggregateIdName,
                            fetchVariables));
              }
              final var context = new Camunda8ModelledListenerInvocationContext(
                  adapterId, taskDefinition, String
                      .valueOf(aggregateId), job, multiInstanceRegistry, fetchVariables);
              final var outcome = workflowTaskInvoker
                  .invokeWorkflowTask(workflowModuleId, bpmnProcessId, context);
              if (outcome.kind() == WorkflowTaskOutcome.Kind.BPMN_ERROR) {
                throw new IllegalStateException(
                    ("The @WorkflowTask method serving the listener '%s' (BPMN process '%s' of workflow "
                        + "module '%s') threw a TaskException! A listener cannot raise a BPMN error: the "
                        + "cluster is inside a transition of its own while the listener job runs and has no "
                        + "token to route. Model the logic as a task of the process where an error has to "
                        + "change the path.")
                        .formatted(taskDefinition, bpmnProcessId, workflowModuleId));
              }
              // a listener firing is a wake-up of its workflow like any other. So the
              // other tasks which the core believes are open in it are looked at here too.
              // It runs INSIDE the listener work, because the drain of this workflow
              // module brackets that work. So a shutdown waits for it and does not close
              // the client under it.
              if (openTaskProbe != null) {
                openTaskProbe.reportWhatTheClusterNoLongerHas(bpmnProcessId, context);
              }
              return whatTheCompletionCarries(job, bpmnProcessId, aggregateIdName, aggregateId);
            });

  }

  /**
   * How a failed listener job is reported to the cluster.
   * <p>
   * The same rule applies as for the job of a task (decision 83 in the repository's
   * DECISIONS.md, extended to listeners by decision 87). Where the listener in the model of
   * the job's process version writes <code>retries</code>, the job keeps one retry less and
   * gets a backoff. Where it does not, the job keeps none, and the cluster raises the
   * incident at once. A listener may write, and running it again is safe only where somebody
   * said so.
   * <p>
   * A job with nothing left is failed with nothing left and no backoff. There is no next
   * attempt to delay. A negative number would give a command the cluster refuses. A listener
   * modelled with {@code retries="0"} is in that state on its first delivery.
   *
   * @param job The listener job
   * @param bpmnProcessId The plain BPMN process id
   * @param taskDefinition The task definition, which is the job type of the listener
   * @return What the fail command says
   */
  private Camunda8ListenerJobs.Failure howToFailThisJob(
      final ActivatedJob job,
      final String bpmnProcessId,
      final String taskDefinition) {

    final var withStackTrace = Camunda8ReportErrorsAsStackTraceResolver
        .resolve(reportErrorsAsStackTraceResolver, workflowModuleId, bpmnProcessId, taskDefinition);
    final var retriesLeft = retriesLeftAfterThisFailure(job, bpmnProcessId, taskDefinition);
    if (retriesLeft <= 0) {
      return Camunda8ListenerJobs.Failure.NO_RETRIES_LEFT.withStackTrace(withStackTrace);
    }
    return new Camunda8ListenerJobs.Failure(
        retriesLeft, Camunda8RetryBackoffResolver
            .resolve(retryBackoffResolver, workflowModuleId, bpmnProcessId, taskDefinition)
            .duration(), withStackTrace);

  }

  /**
   * How many retries a failed listener job keeps: one less than it arrived with where the
   * listener writes <code>retries</code> in the model, and none where it does not.
   * <p>
   * A model which cannot be read leaves the decision to Camunda, which counts down by one,
   * exactly as for a task. One more attempt does no harm then, and the next failure asks the
   * model again. An incident would wait for an operator because of a model nobody could read
   * for a moment.
   *
   * @param job The listener job
   * @param bpmnProcessId The plain BPMN process id
   * @param taskDefinition The task definition, which is the job type of the listener
   * @return The retries the fail command leaves, never negative
   */
  private int retriesLeftAfterThisFailure(
      final ActivatedJob job,
      final String bpmnProcessId,
      final String taskDefinition) {

    final var countedDown = Math.max(0, job.getRetries() - 1);
    if (modelledRetries == null) {
      return countedDown;
    }
    final Boolean modelled;
    try {
      // the job type as the cluster reports it. The model as deployed carries the same one,
      // prefix included where the workflow module avoids name clashes that way.
      modelled = modelledRetries
          .retriesModelledForListener(
              workflowModuleId, bpmnProcessId, job.getProcessDefinitionVersion(), job.getElementId(), job
                  .getType());
    } catch (final RuntimeException e) {
      reportModelNotRead(job, bpmnProcessId, taskDefinition, e);
      return countedDown;
    }
    if (modelled == null) {
      reportModelNotRead(job, bpmnProcessId, taskDefinition, null);
      return countedDown;
    }
    if (modelled) {
      return countedDown;
    }
    if (countedDown > 0) {
      reportNoRetriesModelled(job, bpmnProcessId, taskDefinition);
    }
    return 0;

  }

  private void reportModelNotRead(
      final ActivatedJob job,
      final String bpmnProcessId,
      final String taskDefinition,
      final RuntimeException cause) {

    if (!retriesReported.add(onceKeyOf("unread", job, bpmnProcessId))) {
      return;
    }
    log
        .warn(
            "Camunda8[{}]: the model of version {} of BPMN process '{}' (workflow module '{}') could "
                + "not be read, so it is unknown whether the listener '{}' of element '{}' writes "
                + "'retries'. A failed job of it keeps one retry less, as Camunda does on its own, and "
                + "the next failure asks the model again",
            adapterId,
            job.getProcessDefinitionVersion(),
            bpmnProcessId,
            workflowModuleId,
            taskDefinition,
            job.getElementId(),
            cause);

  }

  private void reportNoRetriesModelled(
      final ActivatedJob job,
      final String bpmnProcessId,
      final String taskDefinition) {

    if (!retriesReported.add(onceKeyOf("none", job, bpmnProcessId))) {
      return;
    }
    log
        .info(
            "Camunda8[{}]: the listener '{}' of element '{}' (BPMN process '{}', version {}) writes no "
                + "'retries' in the model, so a failed job of it raises the incident at once. Write "
                + "'retries' on the listener where running it again is safe",
            adapterId,
            taskDefinition,
            job.getElementId(),
            bpmnProcessId,
            job.getProcessDefinitionVersion());

  }

  private static String onceKeyOf(
      final String what,
      final ActivatedJob job,
      final String bpmnProcessId) {

    return "%s/%s/%s/%s/%s"
        .formatted(what, bpmnProcessId, job.getProcessDefinitionVersion(), job.getElementId(), job.getType());

  }

  /**
   * The variables the completion of this listener job carries. They depend on the kind of
   * listener and on its event.
   * <p>
   * Only an execution listener on {@code end} carries anything. It carries what a service
   * task carries: the shared values of the workflow aggregate, read after the transaction of
   * the method committed, plus the aggregate-ID variable (see decision 1 in the repository's
   * DECISIONS.md). The other two carry nothing, for two different reasons. One is a local
   * scope which would swallow what the element itself writes. The other is a cluster which
   * refuses the payload.
   * <p>
   * A job whose kind or event the client does not know gets the empty map. Sending nothing
   * leaves the process instance as it was. Sending into a scope nobody checked can take
   * values with it.
   *
   * @param job The listener job
   * @param bpmnProcessId The plain BPMN process id
   * @param aggregateIdName The name of the ID property of the aggregate
   * @param aggregateId The ID of the aggregate as it arrived in the variables of the job
   * @return The variables, empty where the completion carries none (never <code>null</code>)
   */
  private Map<String, Object> whatTheCompletionCarries(
      final ActivatedJob job,
      final String bpmnProcessId,
      final String aggregateIdName,
      final Object aggregateId) {

    if ((job.getKind() != JobKind.EXECUTION_LISTENER) || (job.getListenerEventType() != ListenerEventType.END)) {
      return Map.of();
    }
    final var variables = new LinkedHashMap<String, Object>(
        // read in a transaction of its own, after the one of the method committed. It never
        // throws. A failed read gives an empty map, exactly as for a service task.
        workflowTaskInvoker.syncedWorkflowAggregateValues(
            workflowModuleId,
            bpmnProcessId,
            String.valueOf(aggregateId),
            Camunda8ProcessService.SYNC_MODE));
    variables.put(aggregateIdName, String.valueOf(aggregateId));
    return variables;

  }

  /**
   * The neutral invocation context built from the job of a modelled listener.
   */
  static class Camunda8ModelledListenerInvocationContext implements TaskInvocationContext {

    private final String adapterId;

    private final String taskDefinition;

    private final String workflowAggregateId;

    private final ActivatedJob job;

    private final Camunda8MultiInstance.Registry multiInstanceRegistry;

    private final Camunda8FetchVariables.Selection fetchVariables;

    Camunda8ModelledListenerInvocationContext(
        final String adapterId,
        final String taskDefinition,
        final String workflowAggregateId,
        final ActivatedJob job,
        final Camunda8MultiInstance.Registry multiInstanceRegistry,
        final Camunda8FetchVariables.Selection fetchVariables) {

      this.fetchVariables = fetchVariables == null
          ? Camunda8FetchVariables.Selection.everything()
          : fetchVariables;
      this.adapterId = adapterId;
      this.taskDefinition = taskDefinition;
      this.workflowAggregateId = workflowAggregateId;
      this.job = job;
      this.multiInstanceRegistry = multiInstanceRegistry;

    }

    @Override
    public Map<String, MultiInstanceValue> getMultiInstances() {

      if (multiInstanceRegistry == null) {
        return Map.of();
      }
      return Camunda8MultiInstance
          .valuesOf(
              multiInstanceRegistry,
              job.getBpmnProcessId(),
              job.getElementId(),
              job.getVariablesAsMap());

    }

    @Override
    public String getAdapterId() {

      return adapterId;

    }

    @Override
    public String getTaskDefinition() {

      return taskDefinition;

    }

    @Override
    public String getProcessVersion() {

      return String.valueOf(job.getProcessDefinitionVersion());

    }

    @Override
    public String getWorkflowAggregateId() {

      return workflowAggregateId;

    }

    @Override
    public String getBpmnElementId() {

      // the element the listener sits on. A reader of the record looks for this element
      // of the model.
      return job.getElementId();

    }

    @Override
    public String getWorkflowId() {

      // the process instance key of the cluster. Operate is searched by it.
      return String.valueOf(job.getProcessInstanceKey());

    }

    @Override
    public String getTaskId() {

      // this handler completes a listener job when the method returns. So there is
      // nothing an application could complete later. The key is reported all the same,
      // because a message about a delivery has to name something to search a log for.
      return String.valueOf(job.getKey());

    }

    @Override
    public String getDeliveryId() {

      // the key of the listener job. One listener event is one job. It is delivered again
      // under the same key until the cluster learns the result.
      return String.valueOf(job.getKey());

    }

    @Override
    public String getActivationId() {

      // the element instance the listener fires for. Two listeners of one element share it.
      // They are two deliveries within one activation.
      return String.valueOf(job.getElementInstanceKey());

    }

    @Override
    public Object getTaskParameter(
        final String name) {

      if (!fetchVariables.covers(name)) {
        throw new IllegalStateException(
            Camunda8FetchVariables.unfetchedTaskParameter(name, taskDefinition, fetchVariables));
      }
      return job.getVariablesAsMap().get(name);

    }

  }

}
