package io.vanillabp.camunda8.wiring;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import io.vanillabp.camunda8.client.Camunda8CommandRetry;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.spi.service.BpmsStartTrigger;
import lombok.extern.slf4j.Slf4j;

/**
 * Consumes the execution-listener jobs of the start events of a process. That means EVERY
 * start event, the plain one included. The listener gates the workflow. Nothing of the
 * process runs before this job is completed. VanillaBP needs exactly this window. In it,
 * it decides what this start is. And where nobody started the workflow through VanillaBP,
 * it asks the application for its workflow aggregate and writes its id into the instance.
 * <p>
 * The name the cluster holds for a workflow is the process variable named after the id
 * attribute of the workflow aggregate. Every other part of this adapter reads it there as
 * well. The job fetches every variable. So the core finds that name, and the handler does
 * not have to know the attribute. See decision 49 in the repository's DECISIONS.md.
 * <p>
 * The job is completed with the ID of the aggregate plus the values shared per
 * {@code @SyncWithBPMS}. The ID is named after the ID attribute of the aggregate, which is
 * how this adapter addresses workflows. A failing build fails the job with one retry less,
 * the way the handler of a service task does. So the cluster retries and finally raises an
 * incident. A workflow without an aggregate could never be processed.
 * <p>
 * While the workflow module is shutting down, the job is left to its lock instead. A
 * listener cut off by a restart is not a defect of the application. The cluster hands the
 * job out again with its retries intact. Both commands this handler sends back are
 * repeated where the cluster rejected them for backpressure. A job which is failed after
 * all gets a <code>retry-backoff</code>.
 * <p>
 * A job the cluster no longer holds is a late answer and not a failure. A redelivery
 * answered it first, or the instance ended another way. So either command which meets it
 * writes a debug line, and the handler goes on.
 * <p>
 * Why the start event carries a listener the adapter injected is decision 5 in the repository's
 * DECISIONS.md; why this handler stays silent about a job during shutdown is decision 6 in the repository's
 * DECISIONS.md.
 */
@Slf4j
public class Camunda8BpmsInitiatedStartHandler implements JobHandler {

  private final String adapterId;

  private final String workflowModuleId;

  /**
   * The PLAIN BPMN process id (what the application and the core know).
   */
  private final String bpmnProcessId;

  private final String startEventId;

  private final BpmsStartTrigger.Kind kind;

  private final String signalName;

  private final BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker;

  /**
   * What the workflow module has in flight, and whether it is going down.
   * Never <code>null</code>. A handler built without one (tests) gets a drain of its own,
   * which never shuts down.
   */
  private final Camunda8Drain drain;

  /**
   * What kind of worker this is, in the messages about a shutdown.
   */
  static final String KIND = "start-event listener";

  /**
   * How long the cluster waits before it hands a failed job out again. May be
   * <code>null</code> (tests). Then
   * {@link Camunda8RetryBackoffResolver#DEFAULT_RETRY_BACKOFF} applies.
   */
  private final Camunda8RetryBackoffResolver retryBackoffResolver;

  /**
   * Builds the handler of one start event. The deployment service calls it. The drain and the
   * retry-backoff resolver may be <code>null</code>, and each one says here what that means.
   *
   * @param adapterId The adapter id this handler belongs to
   * @param workflowModuleId The workflow module the start event belongs to
   * @param bpmnProcessId The PLAIN BPMN process id the start event belongs to
   * @param startEventId The BPMN element id of the start event
   * @param kind What fires the start event: a timer, a signal or a condition
   * @param signalName The signal name where the kind is a signal, <code>null</code> otherwise
   * @param bpmsInitiatedStartInvoker What the platform calls when the cluster starts a workflow
   * @param drain Where the running handler is counted, or <code>null</code> for a drain of its own
   * @param retryBackoffResolver Answers how long the cluster waits before it offers a failed job
   *          again, or <code>null</code> for VanillaBP's default of ten seconds
   */
  public Camunda8BpmsInitiatedStartHandler(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String startEventId,
      final BpmsStartTrigger.Kind kind,
      final String signalName,
      final BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker,
      final Camunda8Drain drain,
      final Camunda8RetryBackoffResolver retryBackoffResolver) {

    this.retryBackoffResolver = retryBackoffResolver;
    this.drain = drain == null
        ? new Camunda8Drain(adapterId, workflowModuleId)
        : drain;
    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.startEventId = startEventId;
    this.kind = kind;
    this.signalName = signalName;
    this.bpmsInitiatedStartInvoker = bpmsInitiatedStartInvoker;

  }

  @Override
  public void handle(
      final JobClient client,
      final ActivatedJob job) {

    drain.jobStarted(job.getKey(), KIND, startEventId, bpmnProcessId);
    try {

      final var result = bpmsInitiatedStartInvoker
          .startWorkflowByBpms(workflowModuleId, bpmnProcessId, contextOf(job));

      log
          .debug(
              "Camunda8[{}]: '{}' of workflow module '{}' started at start event '{}' - workflow "
                  + "aggregate '{}' {}",
              adapterId,
              bpmnProcessId,
              workflowModuleId,
              startEventId,
              result.workflowAggregateId(),
              result.created()
                  ? "built by the application, which named the workflow"
                  : "exists already, so this workflow is already ours");

      final var variables = result.variables();
      Camunda8CommandRetry.sendUnlessTheJobIsGone(
          adapterId,
          "completion",
          job.getKey(),
          startEventId,
          job.getDeadline(),
          drain::isShuttingDown,
          () -> Camunda8JobLease
              .withToken(client.newCompleteCommand(job.getKey()), Camunda8JobLease.tokenOf(job))
              .variables(variables)
              .send()
              .join());

    } catch (final Exception e) {
      // While the module is going down, the failure comes from the shutdown and not from
      // the application. The job keeps its lock and its retries.
      if (drain.leaveJobToItsLock(job.getKey(), KIND, startEventId, e)) {
        return;
      }
      // otherwise the same treatment a service task gets. The cluster counts the
      // retries down and raises an incident once they are used up.
      final var retryBackoff = Camunda8RetryBackoffResolver
          .resolve(retryBackoffResolver, workflowModuleId, bpmnProcessId, null)
          .duration();
      log.warn(
          "Camunda8[{}]: building the workflow aggregate for the start event '{}' of BPMN process '{}' "
              + "(job '{}') failed - failing the job with {} retries left, to be handed out again in {}",
          adapterId,
          startEventId,
          bpmnProcessId,
          job.getKey(),
          job.getRetries() - 1,
          retryBackoff,
          e);
      Camunda8CommandRetry.sendUnlessTheJobIsGone(
          adapterId,
          "failure",
          job.getKey(),
          startEventId,
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

  private BpmsInitiatedStartContext contextOf(
      final ActivatedJob job) {

    // whatever the model set before the start event completed: an input mapping of
    // the start event, or the payload a broadcast signal carried
    //
    // not Map.copyOf. The cluster holds a variable set to null as a value like any other,
    // and Map.copyOf throws on it. The native image test ran into exactly that. The start
    // died with a NullPointerException nobody could read.
    final Map<String, Object> variables = Collections
        .unmodifiableMap(new LinkedHashMap<>(job.getVariablesAsMap()));

    return new BpmsInitiatedStartContext() {

      @Override
      public String getAdapterId() {
        return adapterId;
      }

      @Override
      public String getStartEventId() {
        return startEventId;
      }

      @Override
      public BpmsStartTrigger.Kind getKind() {
        return kind;
      }

      @Override
      public String getSignalName() {
        return signalName;
      }

      @Override
      public Map<String, Object> getVariables() {
        return variables;
      }

      @Override
      public String getNativeInstanceId() {
        return String.valueOf(job.getProcessInstanceKey());
      }

      @Override
      public String getProcessVersion() {
        return String.valueOf(job.getProcessDefinitionVersion());
      }

      @Override
      public AggregateSyncMode getAggregateSyncMode() {
        // a remote BPMS holds the values it evaluates. So what the aggregate shares
        // has to travel with the completion of this job.
        return AggregateSyncMode.FULL;
      }

    };

  }

}
