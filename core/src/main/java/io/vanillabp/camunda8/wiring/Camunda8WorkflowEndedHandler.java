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
 * Consumes the END execution-listener jobs of a process whose application wants to be told
 * that a workflow ended. The job is activated after the last element of the process
 * completed. It gates the disappearance of the instance. VanillaBP uses that window to call
 * the application.
 * <p>
 * From release line 8.10 on, the same worker also consumes the CANCEL execution-listener job
 * of the process. The cluster runs it when an instance is terminated through the API. The two
 * jobs carry the same job type. They are told apart by the event the job reports. So the
 * application hears {@link WorkflowEnd.Kind#CANCELED} for a canceled instance and
 * {@link WorkflowEnd.Kind#COMPLETED} for one which reached an end event. On the lines before
 * 8.10 no cancel job exists, and the boot says so. There a canceled instance is removed
 * without a word.
 * <p>
 * Two ends are NOT cancelations, whatever they look like in a model. A terminate end event
 * and an interrupting event subprocess both COMPLETE the instance. The end listener runs and
 * no cancel job is created. That is how the cluster sees it, and this adapter cannot close
 * that gap.
 * <p>
 * A failing notification fails the job with one retry less, the way the handler of a
 * service task does. So the cluster retries and finally raises an incident. While the
 * workflow module is shutting down, the job is left to its lock instead. A notification cut
 * off by a restart is not a defect of the application. The cluster hands the job out again
 * with its retries intact. Both commands this handler sends back are repeated where the
 * cluster rejected them for backpressure. A job which is failed after all gets a
 * <code>retry-backoff</code>.
 * <p>
 * A job the cluster no longer holds is a late answer and not a failure. A redelivery
 * answered it first, or the instance ended another way. So either command which meets it
 * writes a debug line, and the handler goes on.
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
   * The name of the variable carrying the ID of the workflow aggregate.
   */
  private final String aggregateIdVariable;

  private final WorkflowEndedInvoker workflowEndedInvoker;

  /**
   * What the workflow module has in flight, and whether it is going down.
   * Never <code>null</code>. A handler built without one (tests) gets a drain of its own,
   * which never shuts down.
   */
  private final Camunda8Drain drain;

  /**
   * What kind of worker this is, in the messages about a shutdown.
   */
  static final String KIND = "workflow-end listener";

  /**
   * How long the cluster waits before it hands a failed job out again. May be
   * <code>null</code> (tests). Then
   * {@link Camunda8RetryBackoffResolver#DEFAULT_RETRY_BACKOFF} applies.
   */
  private final Camunda8RetryBackoffResolver retryBackoffResolver;

  /**
   * Whether the incident of a failed job carries the whole stack trace. May be
   * <code>null</code> (tests). Then
   * {@link Camunda8ReportErrorsAsStackTraceResolver#DEFAULT_REPORT_ERRORS_AS_STACK_TRACE}
   * applies.
   */
  private final Camunda8ReportErrorsAsStackTraceResolver reportErrorsAsStackTraceResolver;

  /**
   * Builds the handler of one process. The deployment service calls it. The drain and the
   * two resolvers may be <code>null</code>, and each one says here what that means.
   *
   * @param adapterId The adapter id this handler belongs to
   * @param workflowModuleId The workflow module the process belongs to
   * @param bpmnProcessId The PLAIN BPMN process id whose end is reported
   * @param aggregateIdVariable The process variable carrying the id of the workflow aggregate
   * @param workflowEndedInvoker What the platform calls when a workflow of that process ended
   * @param drain Where the running handler is counted, or <code>null</code> for a drain of its own
   * @param retryBackoffResolver Answers how long the cluster waits before it offers a failed job
   *          again, or <code>null</code> for VanillaBP's default of ten seconds
   * @param reportErrorsAsStackTraceResolver Answers whether an incident carries the stack
   *          trace, or <code>null</code> for no stack trace
   */
  public Camunda8WorkflowEndedHandler(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String aggregateIdVariable,
      final WorkflowEndedInvoker workflowEndedInvoker,
      final Camunda8Drain drain,
      final Camunda8RetryBackoffResolver retryBackoffResolver,
      final Camunda8ReportErrorsAsStackTraceResolver reportErrorsAsStackTraceResolver) {

    this.retryBackoffResolver = retryBackoffResolver;
    this.reportErrorsAsStackTraceResolver = reportErrorsAsStackTraceResolver;
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
        // the event types of the client grow inside a line. A cluster newer than this build
        // reports one it does not know as UNKNOWN_ENUM_VALUE. Reading such a job as an end
        // would tell the application something untrue. So the job is answered and nothing
        // is reported.
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
        // not a VanillaBP workflow, or its aggregate-ID variable was removed. There
        // is nothing this end could be reported for.
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

      Camunda8CommandRetry.sendUnlessTheJobIsGone(
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
      // While the module is going down, the failure comes from the shutdown and not from
      // the application. The job keeps its lock and its retries.
      if (drain.leaveJobToItsLock(job.getKey(), KIND, job.getType(), e)) {
        return;
      }
      // otherwise the same treatment a service task gets
      final var retryBackoff = Camunda8RetryBackoffResolver
          .resolve(retryBackoffResolver, workflowModuleId, bpmnProcessId, null)
          .duration();
      // never below zero. A job arrives with none left where the model writes retries="0".
      // The cluster takes -1 on a failure today, but refuses it as an update of the retries,
      // and the log line would tell the operator about -1 retries.
      final var retriesLeft = Math.max(0, job.getRetries() - 1);
      log.warn(
          "Camunda8[{}]: reporting the end of the instance '{}' of BPMN process '{}' (job '{}') failed - "
              + "failing the job with {} retries left, to be handed out again in {}",
          adapterId,
          job.getProcessInstanceKey(),
          bpmnProcessId,
          job.getKey(),
          retriesLeft,
          retryBackoff,
          e);
      Camunda8CommandRetry.sendUnlessTheJobIsGone(
          adapterId,
          "failure",
          job.getKey(),
          job.getType(),
          job.getDeadline(),
          drain::isShuttingDown,
          () -> {
            var command = client
                .newFailCommand(job.getKey())
                .retries(retriesLeft);
            // no backoff where no attempt is left. There is nothing to delay.
            if (retriesLeft > 0) {
              command = command.retryBackoff(retryBackoff);
            }
            Camunda8JobLease
                .withToken(
                    command
                        .errorMessage(Camunda8Errors
                            .incidentMessage(e, Camunda8ReportErrorsAsStackTraceResolver
                                .resolve(reportErrorsAsStackTraceResolver, workflowModuleId, bpmnProcessId, null))),
                    Camunda8JobLease.tokenOf(job))
                .send()
                .join();
          });
    } finally {
      drain.jobFinished(job.getKey());
    }

  }

  /**
   * How the instance of this job ended, or <code>null</code> where the job reports an event
   * this handler does not serve.
   * <p>
   * It is asked this way round on purpose. The enum of the client grows inside a line. It
   * reports an event it does not know as <code>UNKNOWN_ENUM_VALUE</code>. So reading anything
   * but the two known events as an end would tell the application something untrue.
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
        // the key of THIS instance. With it, the core can limit its derivation to this
        // instance. Never getRootProcessInstanceKey(). That names the root of the call tree.
        // And a called process whose parent was canceled gets a job of its own for its
        // own instance.
        return String.valueOf(job.getProcessInstanceKey());
      }

      @Override
      public Instant getEndTime() {
        return Instant.now();
      }

      @Override
      public String getEndEventId() {
        // the listener sits on the PROCESS. So the cluster reports the process as the
        // element. Which end event was reached is not part of the job.
        return null;
      }

      @Override
      public String getProcessVersion() {
        return String.valueOf(job.getProcessDefinitionVersion());
      }

    };

  }

}
