package io.vanillabp.camunda8.wiring;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiPredicate;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8CommandRetry;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.camunda8.processservice.Camunda8ProcessService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * The job handler of one polling worker (one worker per adapter ID and task
 * definition). It builds the neutral invocation context from the {@link ActivatedJob}
 * and dispatches through the {@link WorkflowTaskInvoker} of the core. Camunda 8 job
 * workers deliver AT LEAST ONCE. So the handler and its completion are idempotent by
 * design:
 * <ul>
 * <li>the business method runs in a NEW local transaction. It commits BEFORE the job is
 * completed (order: open TX - load aggregate - invoke - save - commit - complete job). A
 * crash between commit and complete causes a redelivery which converges. The handler
 * runs again on the aggregate which is already updated, and the completion
 * succeeds.</li>
 * <li>completing a job which a parallel redelivery already completed is tolerated. It
 * logs a WARN, because this is the documented residual of at-least-once.</li>
 * <li>{@code TaskException} maps to a BPMN error (error-boundary routing, aggregate
 * changes committed).</li>
 * <li>any other exception fails the job with one retry less. The core already rolled the
 * local transaction back.</li>
 * <li>the completion CARRIES THE AGGREGATE STATE: the values the aggregate shares with the
 * BPMS, plus always the technical aggregate-ID variable. Without them, a gateway right
 * after the service task would evaluate the values of the last sync point driven by
 * {@code ProcessService}. That is STALE data. The values are read through
 * {@link WorkflowTaskInvoker#syncedWorkflowAggregateValues} of the core, AFTER the local
 * transaction committed, in a transaction of its own. A failing read never prevents the
 * completion. The job is then completed with the ID variable only. The same holds for the
 * BPMN_ERROR path, because the outgoing flow of the error boundary may branch on the
 * aggregate too.</li>
 * <li>a {@code @TaskId} method returning without completing leaves the job OPEN. The
 * adapter extends its lock by <code>async-task-lock-renewal</code> (one hour by default).
 * So the handler is not invoked again while the workflow waits for the asynchronous
 * completion (<code>ProcessService#completeTask</code>). When the window passes, the
 * cluster hands the job out again. The core answers that redelivery from its delivery
 * record with COMPLETION_PENDING, and the lock is extended by another window. So the
 * redelivery of the cluster drives the renewal, and the application never notices it.
 * The regular job timeout of the worker stays short. So crash recovery of tasks which are
 * not asynchronous is not delayed.</li>
 * <li>the command which reports the outcome is REPEATED where the cluster rejected it for
 * backpressure. A completion of work which is already committed must not cost the job a
 * retry just because the cluster was busy. The retry is bounded by the remaining lock of
 * the job and by five attempts, see {@link Camunda8CommandRetry}. A waiting handler
 * occupies an execution slot, which is why the bound is small. A job which is failed after
 * all gets a <code>retry-backoff</code>. The task header of the element may name it
 * instead of the configuration, see {@link Camunda8RetryBackoffHeader}.</li>
 * <li>a failure, a BPMN error or an overdue failure which finds the job GONE is a late
 * answer and not a failure. A redelivery answered the job first, or its element or
 * workflow ended while the handler was working. The cluster has moved on. So the handler
 * writes a debug line and goes on, see
 * {@link Camunda8CommandRetry#sendUnlessTheJobIsGone}.</li>
 * <li>a delivery which fails while the workflow module is SHUTTING DOWN is not reported as
 * a job failure. The state of the adapter decides, not the exception, because a handler
 * interrupted by the closing client throws like any other. The job keeps its lock and its
 * retries. The cluster hands it out again once the lock expires. Then the delivery record
 * of VanillaBP decides whether the work has to run again. Before that, the shutdown WAITS
 * for the handlers in flight, see {@link Camunda8Drain}.</li>
 * <li>the core measures how long such a task has been open. It says when the task passed
 * <code>vanillabp.delivery.max-task-age</code>. Where
 * <code>async-task-max-age-action</code> is <code>incident</code>, the renewal stops
 * there and the job is failed with a guiding message. So the cluster raises an incident
 * which names the workflow aggregate and the age.</li>
 * </ul>
 * <p>
 * Why a job is not reported as failed while the adapter is shutting down is decision 6 in the
 * repository's DECISIONS.md; why the outcome command is retried is decision 9 in the repository's DECISIONS.md.
 */
@Slf4j
public class Camunda8JobHandler implements JobHandler {

  private final String adapterId;

  private final String workflowModuleId;

  /**
   * The full client. The {@link JobClient} handed to handlers cannot extend a job lock
   * (dormancy).
   */
  private final CamundaClient camundaClient;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  private final Duration asyncTaskLockRenewal;

  /**
   * What this adapter does with a task the core reports as older than the configured
   * maximum age. Never <code>null</code>.
   */
  private final Camunda8AdapterConfiguration.AsyncTaskMaxAgeAction asyncTaskMaxAgeAction;

  /**
   * What the workflow module has in flight, and whether it is going down. This handler
   * registers its delivery there. A failure while the module is going down comes from the
   * shutdown and not from the application. Never <code>null</code>. A handler built
   * without one (tests) gets a drain of its own, which never shuts down.
   */
  private final Camunda8Drain drain;

  /**
   * What kind of worker this is, in the messages about a shutdown.
   */
  static final String KIND = "task";

  /**
   * Translates the identifiers the cluster reports back into the plain ones the core
   * knows. It does nothing unless the workflow module uses prefixes. May be
   * <code>null</code> (tests).
   */
  private final NameClashAvoidanceSupport scoping;

  /**
   * Which multi-instance elements enclose the element of the job. May be
   * <code>null</code> (tests). Then no iteration is reported, as this adapter did
   * before.
   */
  private final Camunda8MultiInstance.Registry multiInstanceRegistry;

  /**
   * How long the cluster waits before it hands a failed job out again, resolved
   * per task. May be <code>null</code> (tests). Then
   * {@link Camunda8RetryBackoffResolver#DEFAULT_RETRY_BACKOFF} applies.
   */
  private final Camunda8RetryBackoffResolver retryBackoffResolver;

  /**
   * What the models say about the backoff of the task of this worker, and what was
   * already reported about them. There is one per worker, and a worker serves one task
   * definition. So a model with a wrong header costs one line per element, not one per
   * job.
   */
  private final Camunda8RetryBackoffHeader retryBackoffHeader;

  /**
   * What this worker asked the cluster for. A job carries these variables and no others.
   * It is read twice. A missing aggregate-ID variable now has a second possible cause. And
   * a <code>&#64;TaskParam</code> outside the list is a question this delivery cannot
   * answer. Never <code>null</code>. A handler built without one (tests) sees every
   * variable, like a worker which names no list.
   */
  private final Camunda8FetchVariables.Selection fetchVariables;

  /**
   * Answers whether a workflow of (scoped process id, version) was started before the
   * version this boot deployed, see
   * {@link TaskInvocationContext#predatesDeployedVersion()}.
   * It is a predicate and not the version catalog itself. The handler needs one answer.
   * And a test needs to hand it in without building a catalog.
   */
  private final BiPredicate<String, Integer> predatesDeployedVersion;

  /**
   * Asks the cluster about the OTHER tasks this workflow has open, once the outcome of this
   * job went back. May be <code>null</code>. Then it asks nothing. That is the case in tests,
   * and with a core which does not drive the check.
   */
  private final Camunda8OpenTaskProbe openTaskProbe;

  /**
   * The worker this handler serves. Built through the generated
   * <code>Camunda8JobHandler.builder()</code>: eight of these thirteen values may be left
   * out, and a positional list of that length no longer says which is which.
   *
   * @param adapterId The adapter whose worker delivers here
   * @param workflowModuleId The workflow module the subscribed tasks belong to
   * @param camundaClient The full client, for the lock extension a {@link JobClient} has no
   *          command for
   * @param workflowTaskInvoker The runtime entry point of the core
   * @param asyncTaskLockRenewal How long the job lock of an open asynchronous task is
   *          extended by
   * @param scoping Translates the identifiers of the cluster back, or <code>null</code>
   * @param multiInstanceRegistry Which multi-instance elements enclose an element, or
   *          <code>null</code> for no iteration
   * @param asyncTaskMaxAgeAction What to do with an overdue task, or <code>null</code> for
   *          <code>report</code>
   * @param drain What the workflow module has in flight, or <code>null</code> for a drain
   *          of its own, which never shuts down
   * @param retryBackoffResolver How long the cluster waits before it hands a failed job out
   *          again, or <code>null</code> for the default backoff
   * @param fetchVariables What the worker asked for, or <code>null</code> for every variable
   * @param predatesDeployedVersion Whether a workflow predates the deployed version, or
   *          <code>null</code> to answer no for all of them
   * @param openTaskProbe What asks the cluster about the other open tasks of the workflow, or
   *          <code>null</code> to ask nothing
   */
  @Builder
  public Camunda8JobHandler(
      final String adapterId,
      final String workflowModuleId,
      final CamundaClient camundaClient,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final Duration asyncTaskLockRenewal,
      final NameClashAvoidanceSupport scoping,
      final Camunda8MultiInstance.Registry multiInstanceRegistry,
      final Camunda8AdapterConfiguration.AsyncTaskMaxAgeAction asyncTaskMaxAgeAction,
      final Camunda8Drain drain,
      final Camunda8RetryBackoffResolver retryBackoffResolver,
      final Camunda8FetchVariables.Selection fetchVariables,
      final BiPredicate<String, Integer> predatesDeployedVersion,
      final Camunda8OpenTaskProbe openTaskProbe) {

    this.openTaskProbe = openTaskProbe;
    this.predatesDeployedVersion = predatesDeployedVersion == null
        ? (
            processId,
            version) -> false
        : predatesDeployedVersion;
    this.fetchVariables = fetchVariables == null
        ? Camunda8FetchVariables.Selection.everything()
        : fetchVariables;
    this.retryBackoffResolver = retryBackoffResolver;
    this.retryBackoffHeader = new Camunda8RetryBackoffHeader(adapterId, workflowModuleId);
    this.drain = drain == null
        ? new Camunda8Drain(adapterId, workflowModuleId)
        : drain;
    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;
    this.camundaClient = camundaClient;
    this.workflowTaskInvoker = workflowTaskInvoker;
    this.asyncTaskLockRenewal = asyncTaskLockRenewal;
    this.scoping = scoping;
    this.multiInstanceRegistry = multiInstanceRegistry;
    this.asyncTaskMaxAgeAction = asyncTaskMaxAgeAction == null
        ? Camunda8AdapterConfiguration.AsyncTaskMaxAgeAction.REPORT
        : asyncTaskMaxAgeAction;

  }

  @Override
  public void handle(
      final JobClient client,
      final ActivatedJob job) {

    // the cluster reports the identifiers IT knows. Translate them back into the
    // plain ones which key the registries of the core.
    final var bpmnProcessId = NameClashAvoidanceSupport
        .plainProcessId(scoping, workflowModuleId, job.getBpmnProcessId(), adapterId);
    final var taskDefinition = NameClashAvoidanceSupport
        .plainTaskDefinition(scoping, workflowModuleId, bpmnProcessId, job.getType(), adapterId);

    // From here until the finally, the shutdown of this workflow module waits
    // for this handler. It does not pull the client away from under it.
    drain.jobStarted(job.getKey(), KIND, taskDefinition, bpmnProcessId);
    try {
      handleJob(client, job, bpmnProcessId, taskDefinition);
    } catch (final RuntimeException e) {
      // what reaches this point is the way BACK to the cluster (a completion, a BPMN
      // error, a lock renewal). The invocation itself is answered below. If it escaped
      // during a shutdown, the client would fail the job with one retry less.
      if (drain.leaveJobToItsLock(job.getKey(), KIND, taskDefinition, e)) {
        return;
      }
      throw e;
    } finally {
      drain.jobFinished(job.getKey());
    }

  }

  private void handleJob(
      final JobClient client,
      final ActivatedJob job,
      final String bpmnProcessId,
      final String taskDefinition) {

    final WorkflowTaskOutcome outcome;
    final String aggregateIdName;
    final Object aggregateId;
    // built once and read twice. The invocation is what it is for. Afterwards the check
    // of the other open tasks of this workflow gets the same context.
    final Camunda8TaskInvocationContext context;
    try {
      aggregateIdName = workflowTaskInvoker.resolveWorkflowAggregateIdName(
          workflowModuleId,
          bpmnProcessId);
      aggregateId = job.getVariablesAsMap().get(aggregateIdName);
      if (aggregateId == null) {
        throw new IllegalStateException(
            Camunda8FetchVariables.missingAggregateId(
                "Job",
                job.getKey(),
                taskDefinition,
                bpmnProcessId,
                aggregateIdName,
                fetchVariables));
      }
      context = new Camunda8TaskInvocationContext(adapterId, taskDefinition, String
          .valueOf(aggregateId), job, multiInstanceRegistry, predatesDeployedVersion
              .test(job.getBpmnProcessId(), job.getProcessDefinitionVersion()), fetchVariables);
      outcome = workflowTaskInvoker.invokeWorkflowTask(workflowModuleId, bpmnProcessId, context);
    } catch (final Exception e) {
      // While the module is going down, the failure comes from the shutdown and not from
      // the application. The job keeps its lock and its retries.
      if (drain.leaveJobToItsLock(job.getKey(), KIND, taskDefinition, e)) {
        return;
      }
      // the core rolled the local transaction back. Fail the job, so Camunda 8
      // applies its retry semantics (retries reach 0 -> incident).
      final var retryBackoff = retryBackoffOf(job, bpmnProcessId, taskDefinition);
      log.warn(
          "Camunda8[{}]: processing job '{}' (type '{}') of BPMN process '{}' failed - failing the job "
              + "with {} retries left, to be handed out again in {}",
          adapterId,
          job.getKey(),
          taskDefinition,
          bpmnProcessId,
          job.getRetries() - 1,
          retryBackoff,
          e);
      Camunda8CommandRetry.sendUnlessTheJobIsGone(
          adapterId,
          "failure",
          job.getKey(),
          taskDefinition,
          job.getDeadline(),
          drain::isShuttingDown,
          () -> Camunda8JobLease
              .withToken(
                  client
                      .newFailCommand(job.getKey())
                      .retries(job.getRetries() - 1)
                      // without it, the cluster hands the job out again at once. A
                      // handler failing on something which needs a moment would use up
                      // its retries before the cause has a chance to pass.
                      .retryBackoff(retryBackoff)
                      // the type belongs into the incident as much as the message does.
                      // On its own, a NullPointerException just says 'null'.
                      .errorMessage(Camunda8Errors.incidentMessage(e)),
                  Camunda8JobLease.tokenOf(job))
              .send()
              .join());
      return;
    }

    switch (outcome.kind()) {
      case COMPLETED -> completeTolerantly(
          client,
          job,
          taskDefinition,
          variablesOf(bpmnProcessId, aggregateIdName, aggregateId));
      case BPMN_ERROR -> {
        // read ONCE and not per attempt. A repeated command carries what the handler
        // produced. Reading the aggregate again would cost a transaction per retry.
        final var errorVariables = variablesOf(bpmnProcessId, aggregateIdName, aggregateId);
        Camunda8CommandRetry.sendUnlessTheJobIsGone(
            adapterId,
            "BPMN error",
            job.getKey(),
            taskDefinition,
            job.getDeadline(),
            drain::isShuttingDown,
            () -> Camunda8JobLease
                .withToken(
                    client
                        .newThrowErrorCommand(job.getKey())
                        // the error codes of the model are prefixed too. So the code the
                        // business method raised has to be translated on its way to the
                        // cluster. The prefix is the workflow module of this task. It is
                        // also the module of the catcher. A call activity of this engine
                        // names its called process by id. Under 'use-prefix' that id carries
                        // the prefix of the calling module. Under 'by-adapter' the cluster
                        // resolves it in the tenant of the calling instance. No attribute on
                        // a called element could name another one. So a call activity
                        // cannot leave its workflow module here.
                        .errorCode(
                            NameClashAvoidanceSupport
                                .scopedIdentifier(
                                    scoping, workflowModuleId, outcome.errorCode(), adapterId))
                        .errorMessage(String.valueOf(outcome.errorName()))
                        // the outgoing path of the error boundary may branch on the aggregate
                        .variables(errorVariables),
                    Camunda8JobLease.tokenOf(job))
                .send()
                .join());
      }
      case COMPLETION_PENDING -> {
        if (outcome
            .maxAgeExceeded() && (asyncTaskMaxAgeAction == Camunda8AdapterConfiguration.AsyncTaskMaxAgeAction.INCIDENT)) {
          // the renewal stops here. A task nobody will ever complete belongs where
          // operators look. On Camunda 8 that is an incident, not a log line.
          failAsOverdue(client, job, taskDefinition, bpmnProcessId, String.valueOf(aggregateId), outcome.openFor());
          return;
        }
        renewTheLock(job, taskDefinition);
      }
    }

    // and now, with the outcome of THIS job back at the cluster, the other tasks which the
    // core believes are open in the same workflow. After and not before, because the
    // completion of this very job can fire the boundary event which takes another one away.
    if (openTaskProbe != null) {
      openTaskProbe.reportWhatTheClusterNoLongerHas(bpmnProcessId, context);
    }

  }

  /**
   * Extends the lock of a job whose task stays open by the renewal window. So the handler
   * is not run again while the workflow waits for <code>ProcessService#completeTask</code>.
   * When the window passes, the cluster hands the job out again. The core answers from its
   * delivery record, and this renews the lock once more.
   * <p>
   * The renewal can come too late, and that is not a failure. The task is open from the
   * moment the handler decides so, which is before the renewal reaches the cluster. An
   * application which completes the task in that gap closes the job first. The cluster then
   * refuses the renewal. It answers with HTTP <code>400</code> (gRPC
   * <code>INVALID_ARGUMENT</code>) while it still holds the closed job, and with
   * <code>404</code> once it does not. A lock
   * which ran out before the renewal arrived gets the <code>400</code> as well. In each case
   * there is no lock left to extend, and the cluster already holds what comes next: the
   * completion, or a redelivery which renews the lock again. So the refusal is a debug line
   * and the handler goes on. Any other answer still escapes, as before.
   *
   * @param job The job whose task stays open
   * @param taskDefinition The task definition, as the application knows it
   */
  private void renewTheLock(
      final ActivatedJob job,
      final String taskDefinition) {

    try {
      Camunda8CommandRetry.send(
          adapterId,
          "lock renewal",
          job.getKey(),
          taskDefinition,
          job.getDeadline(),
          drain::isShuttingDown,
          () -> camundaClient
              .newUpdateTimeoutCommand(job.getKey())
              .timeout(asyncTaskLockRenewal)
              .send()
              .join());
    } catch (final RuntimeException e) {
      if (!Camunda8Errors.jobIsThereButNotActive(e) && !Camunda8Errors.jobAlreadyGone(e)) {
        throw e;
      }
      log.debug(
          "Camunda8[{}]: job '{}' (type '{}') was not active any more when its lock was to be "
              + "renewed - it was completed in the meantime or its lock ran out, so there was "
              + "nothing to renew",
          adapterId,
          job.getKey(),
          taskDefinition,
          e);
      return;
    }
    log.debug(
        "Camunda8[{}]: job '{}' (type '{}') stays open for asynchronous completion - lock "
            + "renewed for {}",
        adapterId,
        job.getKey(),
        taskDefinition,
        asyncTaskLockRenewal);

  }

  /**
   * Fails the job of a task which stayed open longer than
   * <code>vanillabp.delivery.max-task-age</code> allows. It has no retries left, so the
   * cluster raises an incident right away. An operator sees the message in the incident.
   * So it names the workflow aggregate, the age and both ways out.
   *
   * @param client The job client of this delivery
   * @param job The activated job of the open task
   * @param taskDefinition The task definition, as the core knows it
   * @param bpmnProcessId The BPMN process id, as the core knows it
   * @param aggregateId The id of the workflow aggregate
   * @param openFor How long the task has been open
   */
  private void failAsOverdue(
      final JobClient client,
      final ActivatedJob job,
      final String taskDefinition,
      final String bpmnProcessId,
      final String aggregateId,
      final Duration openFor) {

    final var message = """
        Task '%s' of workflow aggregate '%s' (BPMN process '%s' of workflow module '%s') has been \
        waiting for its asynchronous completion for %s, which is longer than \
        'vanillabp.delivery.max-task-age' allows. The application owes this task a \
        ProcessService#completeTask respectively #cancelTask. Complete or cancel it and retry this \
        job, raise the maximum age where such a wait is legitimate, or set \
        '%s' back to 'report'."""
        .formatted(
            taskDefinition,
            aggregateId,
            bpmnProcessId,
            workflowModuleId,
            openFor,
            Camunda8AdapterConfiguration
                .propertyKey(adapterId, "async-task-max-age-action"));
    log.warn("Camunda8[{}]: {}", adapterId, message);
    // no retry backoff. With no retries left, there is no next attempt to delay.
    Camunda8CommandRetry.sendUnlessTheJobIsGone(
        adapterId,
        "overdue failure",
        job.getKey(),
        taskDefinition,
        job.getDeadline(),
        drain::isShuttingDown,
        () -> Camunda8JobLease
            .withToken(
                client
                    .newFailCommand(job.getKey())
                    .retries(0)
                    .errorMessage(message),
                Camunda8JobLease.tokenOf(job))
            .send()
            .join());

  }

  /**
   * How long the cluster waits before it hands a failed job of this task out again: what
   * the task header of the element models, or the most specific configured
   * <code>retry-backoff</code>, or ten seconds.
   *
   * @param job The job which is about to be failed
   * @param bpmnProcessId The BPMN process id, as the core knows it
   * @param taskDefinition The task definition, as the core knows it
   * @return The backoff, never <code>null</code>
   */
  private Duration retryBackoffOf(
      final ActivatedJob job,
      final String bpmnProcessId,
      final String taskDefinition) {

    final var configured = Camunda8RetryBackoffResolver
        .resolve(retryBackoffResolver, workflowModuleId, bpmnProcessId, taskDefinition);
    final var modelled = retryBackoffHeader.modelledIn(job, bpmnProcessId, configured.duration());
    if (modelled == null) {
      return configured.duration();
    }
    if (!configured.perTask()) {
      // the header speaks about one task. So it outranks every level which speaks about
      // more than that one.
      return modelled;
    }
    retryBackoffHeader
        .reportConfigurationWins(job, bpmnProcessId, taskDefinition, modelled, configured.duration());
    return configured.duration();

  }

  /**
   * The variables the completion of a job carries. First, the values the workflow
   * aggregate shares with the cluster. The {@code @WorkflowTask} method just changed it,
   * and a gateway right after this task has to see the NEW values (see decision 1 in the
   * repository's DECISIONS.md). Second, always and whatever the sync model says, the
   * technical variable holding the ID of the aggregate.
   *
   * @param bpmnProcessId The BPMN process ID
   * @param aggregateIdName The name of the ID property of the aggregate
   * @param aggregateId The ID of the aggregate as it arrived in the variables of the job
   * @return The variables (never <code>null</code>)
   */
  private Map<String, Object> variablesOf(
      final String bpmnProcessId,
      final String aggregateIdName,
      final Object aggregateId) {

    final var variables = new LinkedHashMap<String, Object>(
        // the core loads the aggregate in its OWN transaction (the one of the task is
        // committed). It never throws. A failed read gives an empty map.
        workflowTaskInvoker.syncedWorkflowAggregateValues(
            workflowModuleId,
            bpmnProcessId,
            String.valueOf(aggregateId),
            Camunda8ProcessService.SYNC_MODE));
    variables.put(aggregateIdName, String.valueOf(aggregateId));
    return variables;

  }

  /**
   * Completes the job. It tolerates that a parallel redelivery (at-least-once) has
   * already completed it. That is the documented residual of completing AFTER the local
   * transaction committed.
   */
  private void completeTolerantly(
      final JobClient client,
      final ActivatedJob job,
      final String taskDefinition,
      final Map<String, Object> variables) {

    try {
      // the work is committed at this point. So a cluster which is too busy for a moment
      // must not cost the job a retry.
      Camunda8CommandRetry.send(
          adapterId,
          "completion",
          job.getKey(),
          taskDefinition,
          job.getDeadline(),
          drain::isShuttingDown,
          () -> Camunda8JobLease
              .withToken(client.newCompleteCommand(job.getKey()), Camunda8JobLease.tokenOf(job))
              .variables(variables)
              .send()
              .join());
    } catch (final Exception e) {
      if (Camunda8Errors.jobAlreadyGone(e)) {
        log.warn(
            "Camunda8[{}]: job '{}' (type '{}') was already completed - a redelivery of the same "
                + "task converged (at-least-once semantics); the business method ran more than once",
            adapterId,
            job.getKey(),
            job.getType());
        return;
      }
      throw e;
    }

  }


  /**
   * The neutral invocation context built from an activated Camunda 8 job.
   */
  static class Camunda8TaskInvocationContext implements TaskInvocationContext {

    private final String adapterId;

    private final String taskDefinition;

    private final String workflowAggregateId;

    private final ActivatedJob job;

    private final Camunda8MultiInstance.Registry multiInstanceRegistry;

    private final Camunda8FetchVariables.Selection fetchVariables;

    /**
     * Whether the workflow of this job was started before the version this boot
     * deployed. The handler computes it, because the predicate lives there.
     */
    private final boolean predatesDeployedVersion;

    Camunda8TaskInvocationContext(
        final String adapterId,
        final String taskDefinition,
        final String workflowAggregateId,
        final ActivatedJob job,
        final Camunda8MultiInstance.Registry multiInstanceRegistry,
        final boolean predatesDeployedVersion,
        final Camunda8FetchVariables.Selection fetchVariables) {

      this.adapterId = adapterId;
      this.taskDefinition = taskDefinition;
      this.workflowAggregateId = workflowAggregateId;
      this.job = job;
      this.multiInstanceRegistry = multiInstanceRegistry;
      this.predatesDeployedVersion = predatesDeployedVersion;
      this.fetchVariables = fetchVariables == null
          ? Camunda8FetchVariables.Selection.everything()
          : fetchVariables;

    }

    /**
     * What the cluster knows about the iteration this job belongs to. The
     * registry is keyed by the process id the CLUSTER knows, and the job reports that
     * one. Element ids themselves are never scoped.
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

      // the element of the model this job was created for. The task definition is the
      // job type, and several elements may share a job type. So this is the answer to
      // "which element of the model is this"
      return job.getElementId();

    }

    @Override
    public String getWorkflowId() {

      // the process instance key of the cluster. Operate is searched by it.
      return String.valueOf(job.getProcessInstanceKey());

    }

    @Override
    public String getTaskId() {

      // the job key identifies the open job. ProcessService#completeTask uses it.
      return String.valueOf(job.getKey());

    }

    @Override
    public TaskKind getTaskKind() {

      // this handler serves jobs and nothing else, so the id above is a job key. The
      // cluster takes it back through UpdateJob and CompleteJob. The user-task commands of
      // the same cluster find nothing under a job key. The record is read to say exactly
      // that.
      return TaskKind.TASK;

    }

    @Override
    public boolean predatesDeployedVersion() {

      // both halves come from what is already at hand. The job names its process and
      // its version, and the adapter remembers what it deployed. No query, no cost.
      return predatesDeployedVersion;

    }

    @Override
    public String getDeliveryId() {

      // the job key is stable across redeliveries of the same job. A failed job is
      // activated again under its key, and the key of a completed job is never handed out
      // again. But every new element activation creates a new job. That is exactly the
      // identity by which the core remembers a processed delivery.
      return String.valueOf(job.getKey());

    }

    @Override
    public String getActivationId() {

      // the ELEMENT instance, not the job. Two elements of a multi-instance activity are
      // two element instances. But a job created a second time for ONE element must not
      // read as a new element. Today the job key would answer this question as well. That
      // is no reason to use it. It answers the delivery question, and the two contracts
      // are opposite.
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

    // runInCurrentTransaction stays false. Job workers run on client threads
    // without a transaction. The core opens a NEW one, which commits BEFORE the
    // job is completed (at-least-once ordering).

  }

}
