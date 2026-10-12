package io.vanillabp.camunda8.wiring;


import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.spi.service.TaskEvent;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Consumes USER-task lifecycle listener jobs. The V1-compatible
 * <code>zeebe:taskListener</code>s added at deployment deliver <code>creating</code>
 * (→ {@link TaskEvent.Event#CREATED}) and <code>canceling</code>
 * (→ {@link TaskEvent.Event#CANCELED}) as NORMAL JOBS, and this handler consumes them.
 * A user task without a notified <code>&#64;WorkflowTask</code> method is skipped. The
 * startup let it through only where the application marked it as served by something
 * else, for example a form or a task list. A method never completes the user task on
 * return. The completion arrives through <code>ProcessService#completeUserTask</code>,
 * with the USER-TASK KEY reported as <code>&#64;TaskId</code>.
 * <p>
 * Listener jobs GATE the task lifecycle. So they are ALWAYS completed, also deliveries
 * without a handler. A failing notification fails the listener job with no retries left,
 * whatever the model says. So the first failure raises the incident for the operator,
 * because notification defects must not be silently lost. The exception is a workflow
 * module which is SHUTTING DOWN. There the job is left to its lock instead. A notification
 * cut off by a restart is not a notification defect, and an incident would be raised for
 * something nobody did wrong. Both commands this handler sends back are repeated where the
 * cluster rejected them for backpressure. That matters here more than anywhere else. With
 * no retries left, a rejected failure would be an incident caused by the load of the
 * cluster.
 * <p>
 * The model carries one retry all the same, and the two are not the same number. A gateway
 * gives back the modelled one when it could not hand the activated batch to the request it
 * was activated for. A job with none left dies of that lost delivery and does not come back
 * ({@code Camunda8TaskWiring#ONE_ATTEMPT_LEFT_FOR_A_DELIVERY_THE_GATEWAY_LOST}). The number
 * this handler sends is what a notification which really failed has left, and that is
 * none.
 * <p>
 * <b>The listener completion carries NO variables</b> (see decision 1 in the
 * repository's DECISIONS.md). On these jobs it could not carry any. The cluster answers a
 * TASK-listener completion with a variables payload with INVALID_ARGUMENT. It says the
 * payload is not supported yet and names its issue 23702. Even if it were supported, it
 * would not be sent here. A service-task job is different. Its completion is the moment
 * the process moves past the task, so a gateway right behind it needs the new values. A
 * listener job only gates a lifecycle transition of a user task which stays in the
 * cluster:
 * <ul>
 * <li><code>creating</code>: nothing downstream is evaluated yet. The aggregate state
 * reaches the cluster at the next real sync point. That is the completion of that very
 * user task ({@code ProcessService#completeUserTask}), which DOES push all shared
 * values.</li>
 * <li><code>canceling</code>: the task is being removed. Whatever canceled it decides the
 * path taken (an interrupting event, or {@code ProcessService#cancelUserTask}), not this
 * job.</li>
 * </ul>
 * A listener notification is also OPTIONAL. An application may not even implement it. If
 * variables were pushed from that path, the view of the cluster would depend on whether a
 * notification handler exists.
 */
@Slf4j
public class Camunda8UserTaskListenerHandler implements JobHandler {

  private final String adapterId;

  private final String workflowModuleId;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * Translates the identifiers the cluster reports back into the plain ones. It does
   * nothing unless the workflow module uses prefixes. May be
   * <code>null</code> (tests).
   */
  private final NameClashAvoidanceSupport scoping;

  /**
   * Which multi-instance elements enclose the user task. A user task may be
   * multi-instance like any other activity. May be <code>null</code> (tests).
   */
  private final Camunda8MultiInstance.Registry multiInstanceRegistry;

  /**
   * What the workflow module has in flight, and whether it is going down.
   * Never <code>null</code>. A handler built without one (tests) gets a drain of its
   * own, which never shuts down.
   */
  private final Camunda8Drain drain;

  /**
   * What kind of worker this is, in the messages about a shutdown.
   */
  static final String KIND = "user-task listener";

  /**
   * What this worker asked the cluster for. A listener job carries these
   * variables and no others. Never <code>null</code>. A handler built without one
   * (tests) sees every variable.
   */
  private final Camunda8FetchVariables.Selection fetchVariables;

  /**
   * Asks the cluster about the OTHER tasks this workflow has open, once this notification is
   * through. May be <code>null</code>, which asks nothing.
   */
  private final Camunda8OpenTaskProbe openTaskProbe;

  /**
   * The user-task listener worker this handler serves. Built through the generated
   * <code>Camunda8UserTaskListenerHandler.builder()</code>: four of these seven values may
   * be left out, and a positional list of that length no longer says which is which.
   *
   * @param adapterId The adapter whose worker delivers here
   * @param workflowModuleId The workflow module the subscribed user tasks belong to
   * @param workflowTaskInvoker The runtime entry point of the core
   * @param scoping Translates the identifiers of the cluster back, or <code>null</code>
   * @param multiInstanceRegistry Which multi-instance elements enclose the user task, or
   *          <code>null</code> for no iteration
   * @param drain What the workflow module has in flight, or <code>null</code> for a drain
   *          of its own, which never shuts down
   * @param fetchVariables What the worker asked for, or <code>null</code> for every variable
   * @param openTaskProbe What asks the cluster about the other open tasks of the workflow, or
   *          <code>null</code> to ask nothing
   */
  @Builder
  public Camunda8UserTaskListenerHandler(
      final String adapterId,
      final String workflowModuleId,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final NameClashAvoidanceSupport scoping,
      final Camunda8MultiInstance.Registry multiInstanceRegistry,
      final Camunda8Drain drain,
      final Camunda8FetchVariables.Selection fetchVariables,
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

  }

  @Override
  public void handle(
      final JobClient client,
      final ActivatedJob job) {

    final var bpmnProcessId = NameClashAvoidanceSupport
        .plainProcessId(scoping, workflowModuleId, job.getBpmnProcessId(), adapterId);
    final var event = whatHappenedToTheTask(job);
    // the USER-TASK KEY is the @TaskId. It completes the task later through
    // ProcessService#completeUserTask (V1-compatible decimal representation).
    final var userTaskKey = job.getUserTask() != null
        ? String.valueOf(job.getUserTask().getUserTaskKey())
        : String.valueOf(job.getKey());
    // the listener job type is '<marker><external form reference>'. The form reference
    // is the task definition, prefixed like any other one.
    final var scopedTaskDefinition = job
        .getType()
        .substring(Camunda8TaskWiring.TASKDEFINITION_USERTASK_ZEEBE.length());
    final var taskDefinition = NameClashAvoidanceSupport
        .plainTaskDefinition(scoping, workflowModuleId, bpmnProcessId, scopedTaskDefinition, adapterId);

    // what the application was told about this user task. The work below writes it. It
    // is read once the cluster has the answer to this listener job. It stays empty where
    // no notification happened at all.
    final var theNotification = new AtomicReference<TaskInvocationContext>();

    Camunda8ListenerJobs
        .completeOrFail(
            adapterId,
            client,
            job,
            drain,
            KIND,
            taskDefinition,
            bpmnProcessId,
            // a notification which failed has no attempt left, whatever the model says. The
            // failure IS the incident, and there is no next attempt a backoff could delay.
            () -> Camunda8ListenerJobs.Failure.NO_RETRIES_LEFT,
            () -> {
              if (event == null) {
                log
                    .warn(
                        "Camunda8[{}]: the listener job '{}' of user task '{}' (BPMN process '{}' of "
                            + "workflow module '{}') reports the event '{}', which this adapter does not "
                            + "serve - completing the job without a notification. The deployment adds a "
                            + "'creating' and a 'canceling' task listener and nothing else, so either "
                            + "the deployed model carries another one, or this cluster is newer than "
                            + "the Camunda 8 client this build was compiled against.",
                        adapterId,
                        job.getKey(),
                        taskDefinition,
                        bpmnProcessId,
                        workflowModuleId,
                        job.getListenerEventType());
                return Map.of();
              }
              if (workflowTaskInvoker.workflowTaskHandlerExists(workflowModuleId, bpmnProcessId, taskDefinition)) {
                final var aggregateIdName = workflowTaskInvoker
                    .resolveWorkflowAggregateIdName(workflowModuleId, bpmnProcessId);
                final var aggregateId = job.getVariablesAsMap().get(aggregateIdName);
                if (aggregateId == null) {
                  throw new IllegalStateException(
                      Camunda8FetchVariables
                          .missingAggregateId(
                              "The user-task listener job",
                              job.getKey(),
                              job.getType(),
                              bpmnProcessId,
                              aggregateIdName,
                              fetchVariables));
                }
                final var context = new Camunda8UserTaskInvocationContext(
                    adapterId, taskDefinition, String
                        .valueOf(
                            aggregateId), userTaskKey, event, job, multiInstanceRegistry, fetchVariables);
                final var outcome = workflowTaskInvoker
                    .invokeWorkflowTask(workflowModuleId, bpmnProcessId, context);
                if (outcome.kind() == WorkflowTaskOutcome.Kind.BPMN_ERROR) {
                  throw new IllegalStateException(
                      ("The @WorkflowTask method notified about the %s event of user task '%s' (BPMN "
                          + "process '%s' of workflow module '%s') threw a TaskException! User-task "
                          + "notification handlers must not raise BPMN errors - route errors via "
                          + "ProcessService#cancelUserTask instead.")
                          .formatted(event, taskDefinition, bpmnProcessId, workflowModuleId));
                }
                theNotification.set(context);
              } else {
                log
                    .trace(
                        "Camunda8[{}]: no @WorkflowTask handler for user task '{}' of BPMN process '{}' - "
                            + "completing the {} listener job without a notification",
                        adapterId,
                        taskDefinition,
                        bpmnProcessId,
                        event);
              }
              // the listener completion carries NO variables, see the class javadoc
              return Map.of();
            },
            () -> {
              // the creation or the cancelation of a user task is a wake-up of its workflow
              // like any other. So the other tasks which the core believes are open in it
              // are looked at here too. This happens AFTER the listener job was answered,
              // not inside the work. On a 'creating' listener, the completion of that job
              // ends state CREATING. Until it reaches the partition, the cluster refuses
              // the completion which the application may have sent for this very task. It
              // still runs inside the drain of this workflow module. So a shutdown waits
              // for it and does not close the client under it.
              final var wakeUp = theNotification.get();
              if ((openTaskProbe != null) && (wakeUp != null)) {
                openTaskProbe.reportWhatTheClusterNoLongerHas(bpmnProcessId, wakeUp);
              }
            });

  }

  /**
   * What happened to the user task, as the SPI tells the cases apart. This handler serves
   * the two task listeners the deployment adds. Those deliver <code>creating</code> and
   * <code>canceling</code>.
   * <p>
   * Every other event is answered with <code>null</code>, not with one of the two. The
   * event types of the client grow inside a line. 8.10 adds <code>CANCEL</code> beside
   * <code>CANCELING</code>. And a client older than the cluster reports an event it does
   * not know as <code>UNKNOWN_ENUM_VALUE</code>. Reading such a job as a creation would
   * tell the application about a task it never got. So the caller completes the job
   * without notifying anybody instead.
   *
   * @param job The listener job
   * @return The event, or <code>null</code> where the job carries one this handler does
   *         not serve
   */
  private static TaskEvent.Event whatHappenedToTheTask(
      final ActivatedJob job) {

    final var eventType = job.getListenerEventType();
    if (eventType == ListenerEventType.CREATING) {
      return TaskEvent.Event.CREATED;
    }
    if (eventType == ListenerEventType.CANCELING) {
      return TaskEvent.Event.CANCELED;
    }
    return null;

  }

  /**
   * The neutral invocation context built from a user-task lifecycle listener job.
   */
  static class Camunda8UserTaskInvocationContext implements TaskInvocationContext {

    private final String adapterId;

    private final String taskDefinition;

    private final String workflowAggregateId;

    private final String userTaskKey;

    private final TaskEvent.Event event;

    private final ActivatedJob job;

    private final Camunda8MultiInstance.Registry multiInstanceRegistry;

    private final Camunda8FetchVariables.Selection fetchVariables;

    Camunda8UserTaskInvocationContext(
        final String adapterId,
        final String taskDefinition,
        final String workflowAggregateId,
        final String userTaskKey,
        final TaskEvent.Event event,
        final ActivatedJob job,
        final Camunda8MultiInstance.Registry multiInstanceRegistry,
        final Camunda8FetchVariables.Selection fetchVariables) {

      this.fetchVariables = fetchVariables == null
          ? Camunda8FetchVariables.Selection.everything()
          : fetchVariables;
      this.adapterId = adapterId;
      this.taskDefinition = taskDefinition;
      this.workflowAggregateId = workflowAggregateId;
      this.userTaskKey = userTaskKey;
      this.event = event;
      this.job = job;
      this.multiInstanceRegistry = multiInstanceRegistry;

    }

    /**
     * What the cluster knows about the iteration this user task belongs to.
     */
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

      // the version of the deployed process definition this job belongs to. The
      // cluster ships it with every job, so nothing has to be queried.
      return String.valueOf(job.getProcessDefinitionVersion());

    }

    @Override
    public String getWorkflowAggregateId() {

      return workflowAggregateId;

    }

    @Override
    public String getBpmnElementId() {

      // the user task element the listener fired for. It is the element of the model, not
      // the type of the listener job.
      return job.getElementId();

    }

    @Override
    public String getWorkflowId() {

      // the process instance key of the cluster. Operate is searched by it.
      return String.valueOf(job.getProcessInstanceKey());

    }

    @Override
    public String getTaskId() {

      return userTaskKey;

    }

    @Override
    public TaskKind getTaskKind() {

      // this handler serves the listeners of a user task and nothing else, so the id above
      // is a user-task key. The cluster takes it back through UpdateUserTask and
      // CompleteUserTask. The job commands of the same cluster find nothing under a
      // user-task key. The record is read to say exactly that.
      return TaskKind.USER_TASK;

    }

    @Override
    public TaskEvent.Event getTaskEvent() {

      return event;

    }

    @Override
    public String getDeliveryId() {

      // the key of the listener job. The notification of one user-task event is one job.
      // It is delivered again under the same key until the cluster learns the result.
      // The user-task key would be the wrong choice. Creation and cancellation of the
      // same user task share it, and they are two deliveries with two outcomes.
      return String.valueOf(job.getKey());

    }

    @Override
    public String getActivationId() {

      // the element instance of the user task, which is what an activation means. Here
      // the sharing which the delivery id had to avoid is right. Creation and
      // cancellation happen within ONE activation of one element.
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
