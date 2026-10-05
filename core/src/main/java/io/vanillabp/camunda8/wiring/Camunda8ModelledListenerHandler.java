package io.vanillabp.camunda8.wiring;

import java.util.LinkedHashMap;
import java.util.Map;

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
 * {@link Camunda8UserTaskListenerHandler} serves the listeners VanillaBP writes itself: it
 * knows their job type by construction, its notification is OPTIONAL, and it reports the
 * user-task key so the task can be completed later. None of that holds here. A modelled
 * listener's job type is what the modeller typed, a method for it is MANDATORY because the
 * wiring validation asked for one, and there is no user task to complete - so the two
 * handlers share the commands they send rather than a class.
 *
 * <h2>What the method is told, and what it is not</h2>
 *
 * The event is part of the listener's identity: the wiring made one task of one listener, so
 * the method serves one event of one element and cannot be in doubt about which. What
 * {@code @TaskEvent} receives is therefore {@link TaskEvent.Event#CREATED} for every
 * listener, and that is the only value which works: a method without a
 * {@code @TaskEvent} parameter subscribes to CREATED alone, so any other value would leave
 * such a method silently uncalled. {@code TaskEvent.Event} has no value for a listener's own
 * event, which the startup report says out loud.
 *
 * <h2>What the completion carries</h2>
 *
 * Three cases, and the job says which one it is.
 * <p>
 * An EXECUTION listener on {@code end} completes like a service task: the values the
 * workflow aggregate shares plus the aggregate-ID variable, and the cluster puts them into
 * the process instance. So a method serving such a listener may change the aggregate and the
 * gateway behind the element decides on what it wrote (see decision 1 in the repository's
 * DECISIONS.md).
 * <p>
 * An EXECUTION listener on {@code start} completes with nothing. Variables of that
 * completion would not reach the process instance: the cluster keeps them local to the
 * element, the local copy then swallows every later write of the same name from inside the
 * element - the element's own job included - and it dies with the element. Sending the
 * aggregate there would cost the element's own task its values without a word. Whoever has
 * to write into the process models a task of the process.
 * <p>
 * A TASK listener completes with nothing either, and that one is not a choice: the cluster
 * answers a task-listener completion carrying variables with INVALID_ARGUMENT, saying the
 * payload is not supported yet and naming its issue 23702. The day it is supported,
 * {@code Camunda8TaskListenerVariablesCanaryIT} of the spring-boot module turns red to
 * report it, and this is the place which then decides what such a listener may write.
 */
@Slf4j
public class Camunda8ModelledListenerHandler implements JobHandler {

  private final String adapterId;

  private final String workflowModuleId;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * Translates the identifiers the cluster reports back into the plain ones - a no-op unless
   * the workflow module uses prefixes. May be <code>null</code> (tests).
   */
  private final NameClashAvoidanceSupport scoping;

  /**
   * Which multi-instance elements enclose the element the listener sits on.
   * May be <code>null</code> (tests).
   */
  private final Camunda8MultiInstance.Registry multiInstanceRegistry;

  /**
   * What the workflow module has in flight, and whether it is going down. Never
   * <code>null</code> - a handler built without one (tests) gets a drain of its own, which
   * never shuts down.
   */
  private final Camunda8Drain drain;

  /**
   * What this worker asked the cluster for. Never <code>null</code>; a handler built without
   * one (tests) sees every variable.
   */
  private final Camunda8FetchVariables.Selection fetchVariables;

  /**
   * How long the cluster waits before it hands a failed listener job out again. May be
   * <code>null</code>, which is the resolver's own default.
   */
  private final Camunda8RetryBackoffResolver retryBackoffResolver;

  /**
   * Asks the cluster about the OTHER tasks this workflow has open, once this notification is
   * through. May be <code>null</code>, which asks nothing.
   */
  private final Camunda8OpenTaskProbe openTaskProbe;

  /**
   * What kind of worker this is, in the messages about a shutdown.
   */
  static final String KIND = "modelled listener";

  /**
   * The worker this handler serves.
   *
   * @param adapterId The adapter whose worker delivers here
   * @param workflowModuleId The workflow module the listener's process belongs to
   * @param workflowTaskInvoker The core's runtime entry point
   * @param scoping Translates the cluster's identifiers back, or <code>null</code>
   * @param multiInstanceRegistry Which multi-instance elements enclose the element, or
   *          <code>null</code> for no iteration
   * @param drain What the workflow module has in flight, or <code>null</code> for a drain of
   *          this handler's own which never shuts down
   * @param fetchVariables What the worker asked for, or <code>null</code> for every variable
   * @param retryBackoffResolver How long a failed job waits, or <code>null</code> for the
   *          default
   * @param openTaskProbe What asks the cluster about the other open tasks of the workflow, or
   *          <code>null</code> to ask nothing
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
      final Camunda8OpenTaskProbe openTaskProbe) {

    this.openTaskProbe = openTaskProbe;
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

  }

  @Override
  public void handle(
      final JobClient client,
      final ActivatedJob job) {

    final var bpmnProcessId = NameClashAvoidanceSupport
        .plainProcessId(scoping, workflowModuleId, job.getBpmnProcessId(), adapterId);
    // the listener's job type IS the task definition, prefixed like any other one where the
    // workflow module avoids name clashes that way
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
                // still open, and the empty update fires a modelled 'updating' listener
                // although it changes nothing. Closing it here is what keeps the question
                // from reaching the application: a method serving an 'updating' listener
                // is written for an update somebody made, and a probe made none.
                // Returning early also skips the check of the other open tasks below,
                // which matters more than it looks: that check is what sent this probe,
                // and running it again from the job it caused would probe the next user
                // task, fire the next listener and start over
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
                            adapterId,
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
              // a listener firing is a wake-up of its workflow like any other, so the
              // other tasks the core believes are open in it are looked at here too. It
              // runs INSIDE the listener work, because that is what the drain of this
              // workflow module brackets: a shutdown waits for it instead of closing the
              // client under it
              if (openTaskProbe != null) {
                openTaskProbe.reportWhatTheClusterNoLongerHas(bpmnProcessId, context);
              }
              return whatTheCompletionCarries(job, bpmnProcessId, aggregateIdName, aggregateId);
            });

  }

  /**
   * How a failed listener job is reported to the cluster.
   * <p>
   * One attempt is spent by the delivery which just failed, so what is left is one less than
   * what arrived. A job with nothing left is failed with nothing left and no backoff: the
   * incident is raised right away, there is no next attempt to delay, and a negative number
   * would be a command the cluster refuses. A listener modelled with {@code retries="0"} is in
   * that state on its first delivery.
   *
   * @param job The listener job
   * @param bpmnProcessId The plain BPMN process id
   * @param taskDefinition The task definition, which is the listener's job type
   * @return What the fail command says
   */
  private Camunda8ListenerJobs.Failure howToFailThisJob(
      final ActivatedJob job,
      final String bpmnProcessId,
      final String taskDefinition) {

    final var retriesLeft = job.getRetries() - 1;
    if (retriesLeft <= 0) {
      return Camunda8ListenerJobs.Failure.NO_RETRIES_LEFT;
    }
    return new Camunda8ListenerJobs.Failure(
        retriesLeft, Camunda8RetryBackoffResolver
            .resolve(retryBackoffResolver, workflowModuleId, bpmnProcessId, taskDefinition)
            .duration());

  }

  /**
   * The variables the completion of this listener job carries, which depends on the kind of
   * listener and on its event.
   * <p>
   * Only an execution listener on {@code end} carries anything, and what it carries is what a
   * service task carries: the shared values of the workflow aggregate, read after the method's
   * transaction committed, plus the aggregate-ID variable (see decision 1 in the repository's
   * DECISIONS.md). The other two carry nothing, for two different reasons - a local scope
   * which would swallow the element's own writes, and a cluster which refuses the payload.
   * <p>
   * A job whose kind or event the client does not know falls to the empty map. Sending
   * nothing leaves the process instance as it was, while sending into a scope nobody checked
   * can take values with it.
   *
   * @param job The listener job
   * @param bpmnProcessId The plain BPMN process id
   * @param aggregateIdName The name of the aggregate's ID property
   * @param aggregateId The aggregate's ID as it arrived in the job's variables
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
        // read in a transaction of its own, after the method's one committed, and never
        // throwing: a failed read yields an empty map, exactly as for a service task
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

      // the element the listener sits on, which is the element of the model a reader of
      // the record looks for
      return job.getElementId();

    }

    @Override
    public String getWorkflowId() {

      // the process instance key of the cluster - what Operate is searched by
      return String.valueOf(job.getProcessInstanceKey());

    }

    @Override
    public String getTaskId() {

      // a listener job is completed by this handler when the method returns, so there is
      // nothing an application could complete later. The key is reported all the same,
      // because a message about a delivery has to name something a log can be searched for
      return String.valueOf(job.getKey());

    }

    @Override
    public String getDeliveryId() {

      // the listener job's key: one listener event is one job, redelivered under the same
      // key until the cluster learns the result
      return String.valueOf(job.getKey());

    }

    @Override
    public String getActivationId() {

      // the element instance the listener fires for - two listeners of one element share it,
      // and they are two deliveries within one activation
      return String.valueOf(job.getElementInstanceKey());

    }

    @Override
    public Object getTaskParameter(
        final String name) {

      if (!fetchVariables.covers(name)) {
        throw new IllegalStateException(
            Camunda8FetchVariables.unfetchedTaskParameter(name, taskDefinition, adapterId, fetchVariables));
      }
      return job.getVariablesAsMap().get(name);

    }

  }

}
