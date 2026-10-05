package io.vanillabp.camunda8.wiring;

import java.time.Instant;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import io.vanillabp.camunda8.client.Camunda8CommandRetry;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.spi.service.WorkflowEnd;
import lombok.extern.slf4j.Slf4j;

/**
 * Consumes the END execution-listener jobs of a process whose application wants to
 * be told that a workflow ended. The job is activated after the last
 * element of the process completed and gates the disappearance of the instance,
 * which is the window VanillaBP uses to call the application.
 * <p>
 * From release line 8.10 the same worker also consumes the CANCEL execution-listener job of
 * the process, which the cluster runs when an instance is terminated through the API. The two
 * jobs carry the same job type and are told apart by the event the job reports, so the
 * application hears {@link WorkflowEnd.Kind#CANCELED} for a canceled instance and
 * {@link WorkflowEnd.Kind#COMPLETED} for one which reached an end event. On the lines before
 * that one no cancel job exists, the boot says so, and a canceled instance is removed without
 * a word.
 * <p>
 * Two ends are NOT cancelations, whatever they look like in a model: a terminate end event and
 * an interrupting event subprocess both COMPLETE the instance, the end listener runs and no
 * cancel job is created. That is the cluster's view and not a gap this adapter can close.
 * <p>
 * A failing notification fails the job with one retry less, the way the handler of a
 * service task does, so the cluster retries and finally raises an incident. While the
 * workflow module is shutting down the job is left to its lock instead: a
 * notification cut off by a restart is not a defect of the application, and the cluster
 * hands the job out again with its retries intact. Both commands this handler sends back
 * are repeated where the cluster rejected them for backpressure, and a job which is failed
 * after all gets a <code>retry-backoff</code>.
 * <p>
 * Why the end of a workflow is reported through a listener the adapter injected is decision 5 in
 * the repository's DECISIONS.md; why this handler stays silent about a job during shutdown is
 * decision 6 in the repository's DECISIONS.md.
 */
@Slf4j
public class Camunda8WorkflowEndedHandler implements JobHandler {

  private final String adapterId;

  private final String workflowModuleId;

  /**
   * The PLAIN BPMN process id (what the application and the core know).
   */
  private final String bpmnProcessId;

  /**
   * The name of the variable carrying the workflow aggregate's ID.
   */
  private final String aggregateIdVariable;

  private final WorkflowEndedInvoker workflowEndedInvoker;

  /**
   * What the workflow module has in flight, and whether it is going down.
   * Never <code>null</code> - a handler built without one (tests) gets a drain of its own,
   * which never shuts down.
   */
  private final Camunda8Drain drain;

  /**
   * What kind of worker this is, in the messages about a shutdown.
   */
  static final String KIND = "workflow-end listener";

  /**
   * How long the cluster waits before it hands a failed job out again. May be
   * <code>null</code> (tests) - then
   * {@link Camunda8RetryBackoffResolver#DEFAULT_RETRY_BACKOFF} applies.
   */
  private final Camunda8RetryBackoffResolver retryBackoffResolver;

  /**
   * Builds the handler of one process. The deployment service calls it. The drain and the
   * retry-backoff resolver may be <code>null</code>, and each one says here what that means.
   *
   * @param adapterId The adapter id this handler belongs to
   * @param workflowModuleId The workflow module the process belongs to
   * @param bpmnProcessId The PLAIN BPMN process id whose end is reported
   * @param aggregateIdVariable The process variable carrying the workflow aggregate's id
   * @param workflowEndedInvoker What the platform calls when a workflow of that process ended
   * @param drain Where the running handler is counted, or <code>null</code> for a drain of its own
   * @param retryBackoffResolver Answers how long the cluster waits before it offers a failed job
   *          again, or <code>null</code> for the cluster's own backoff
   */
  public Camunda8WorkflowEndedHandler(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String aggregateIdVariable,
      final WorkflowEndedInvoker workflowEndedInvoker,
      final Camunda8Drain drain,
      final Camunda8RetryBackoffResolver retryBackoffResolver) {

    this.retryBackoffResolver = retryBackoffResolver;
    this.drain = drain == null
        ? new Camunda8Drain(adapterId, workflowModuleId)
        : drain;
    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.aggregateIdVariable = aggregateIdVariable;
    this.workflowEndedInvoker = workflowEndedInvoker;

  }

  @Override
  public void handle(
      final JobClient client,
      final ActivatedJob job) {

    drain.jobStarted(job.getKey(), KIND, job.getType(), bpmnProcessId);
    try {

      final var kind = whatHappenedToTheInstance(job);
      final var aggregateId = job.getVariablesAsMap().get(aggregateIdVariable);
      if (kind == null) {
        // the client's event types grow inside a line, and a cluster newer than this build
        // reports one it does not know as UNKNOWN_ENUM_VALUE. Reading such a job as an end
        // would tell the application something untrue, so the job is answered and nothing
        // is reported
        log
            .warn(
                "Camunda8[{}]: the execution-listener job '{}' of the instance '{}' of '{}' reports "
                    + "the event '{}', which this adapter does not serve - completing the job without "
                    + "a notification. The deployment adds an 'end' listener and, from release line "
                    + "8.10 on, a 'cancel' listener, so either the deployed model carries another one, "
                    + "or this cluster is newer than the Camunda 8 client this build was compiled "
                    + "against.",
                adapterId,
                job.getKey(),
                job.getProcessInstanceKey(),
                bpmnProcessId,
                job.getListenerEventType());
      } else if (aggregateId == null) {
        // not a VanillaBP workflow, or its aggregate-ID variable was removed: there
        // is nothing this end could be reported for
        log
            .debug(
                "Camunda8[{}]: the instance '{}' of '{}' carries no '{}' variable - its end is not reported",
                adapterId,
                job.getProcessInstanceKey(),
                bpmnProcessId,
                aggregateIdVariable);
      } else {
        workflowEndedInvoker
            .workflowEnded(
                workflowModuleId,
                bpmnProcessId,
                contextOf(job, String.valueOf(aggregateId), kind));
      }

      Camunda8CommandRetry.send(
          adapterId,
          "completion",
          job.getKey(),
          job.getType(),
          job.getDeadline(),
          drain::isShuttingDown,
          () -> Camunda8JobLease
              .withToken(client.newCompleteCommand(job.getKey()), Camunda8JobLease.tokenOf(job))
              .send()
              .join());

    } catch (final Exception e) {
      // While the module is going down, the failure is the shutdown and not the
      // application - the job keeps its lock and its retries
      if (drain.leaveJobToItsLock(job.getKey(), KIND, job.getType(), e)) {
        return;
      }
      // and otherwise the same treatment a service task gets
      final var retryBackoff = Camunda8RetryBackoffResolver
          .resolve(retryBackoffResolver, workflowModuleId, bpmnProcessId, null)
          .duration();
      log.warn(
          "Camunda8[{}]: reporting the end of the instance '{}' of BPMN process '{}' (job '{}') failed - "
              + "failing the job with {} retries left, to be handed out again in {}",
          adapterId,
          job.getProcessInstanceKey(),
          bpmnProcessId,
          job.getKey(),
          job.getRetries() - 1,
          retryBackoff,
          e);
      Camunda8CommandRetry.send(
          adapterId,
          "failure",
          job.getKey(),
          job.getType(),
          job.getDeadline(),
          drain::isShuttingDown,
          () -> Camunda8JobLease
              .withToken(
                  client
                      .newFailCommand(job.getKey())
                      .retries(job.getRetries() - 1)
                      .retryBackoff(retryBackoff)
                      .errorMessage(Camunda8Errors.incidentMessage(e)),
                  Camunda8JobLease.tokenOf(job))
              .send()
              .join());
    } finally {
      drain.jobFinished(job.getKey());
    }

  }

  /**
   * How the instance of this job ended, or <code>null</code> where the job reports an event
   * this handler does not serve.
   * <p>
   * Asked this way round on purpose. The client's enum grows inside a line and reports an
   * event it does not know as <code>UNKNOWN_ENUM_VALUE</code>, so reading anything but the
   * two known events as an end would tell the application something untrue.
   * {@code Camunda8UserTaskListenerHandler.whatHappenedToTheTask} is the same shape.
   *
   * @param job The execution-listener job
   * @return The kind of end, or <code>null</code>
   */
  private static WorkflowEnd.Kind whatHappenedToTheInstance(
      final ActivatedJob job) {

    if (job.getListenerEventType() == ListenerEventType.END) {
      return WorkflowEnd.Kind.COMPLETED;
    }
    if (Camunda8CancelListeners.isCancellationOfTheProcess(job)) {
      return WorkflowEnd.Kind.CANCELED;
    }
    return null;

  }

  private WorkflowEndedContext contextOf(
      final ActivatedJob job,
      final String aggregateId,
      final WorkflowEnd.Kind kind) {

    return new WorkflowEndedContext() {

      @Override
      public String getAdapterId() {
        return adapterId;
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public WorkflowEnd.Kind getKind() {
        // what the job itself reported: an end listener for a completed instance, a
        // cancel listener for a terminated one
        return kind;
      }

      @Override
      public String getWorkflowId() {
        // the key of THIS instance, which is what lets the core limit its derivation to
        // it. Never getRootProcessInstanceKey(): that names the root of the call tree,
        // and a called process whose parent was canceled gets a job of its own for its
        // own instance
        return String.valueOf(job.getProcessInstanceKey());
      }

      @Override
      public Instant getEndTime() {
        return Instant.now();
      }

      @Override
      public String getEndEventId() {
        // the listener sits on the PROCESS, so the cluster reports the process as
        // the element - which end event was reached is not part of the job
        return null;
      }

      @Override
      public String getProcessVersion() {
        return String.valueOf(job.getProcessDefinitionVersion());
      }

    };

  }

}
