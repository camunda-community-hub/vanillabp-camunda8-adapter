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
 * definition): builds the neutral invocation context from the {@link ActivatedJob}
 * and dispatches through the core's {@link WorkflowTaskInvoker}. Camunda 8 job
 * workers deliver AT LEAST ONCE - the handler (and its completion) are idempotent
 * by design:
 * <ul>
 * <li>the business method runs in a NEW local transaction which commits BEFORE the
 * job is completed (order: open TX - load aggregate - invoke - save - commit -
 * complete job). A crash between commit and complete causes a redelivery which
 * converges: the handler runs again on the already-updated aggregate and the
 * completion succeeds;</li>
 * <li>completing a job that was already completed by a parallel redelivery is
 * tolerated (WARN - the documented at-least-once residual);</li>
 * <li>{@code TaskException} maps to a BPMN error (error-boundary routing, aggregate
 * changes committed);</li>
 * <li>any other exception fails the job with decremented retries - the local
 * transaction was already rolled back by the core;</li>
 * <li>the completion CARRIES THE AGGREGATE STATE: the values the
 * aggregate shares with the BPMS plus - always - the technical aggregate-ID
 * variable. Without them a gateway right after the service task would evaluate the
 * values of the last {@code ProcessService}-driven sync point, i.e. STALE data. The
 * values are read through the core's
 * {@link WorkflowTaskInvoker#syncedWorkflowAggregateValues} AFTER the local
 * transaction committed (in its own transaction) - a failing read never prevents
 * the completion, the job is then completed with the ID variable only. The same
 * holds for the BPMN_ERROR path: the error boundary's outgoing flow may branch on
 * the aggregate, too;</li>
 * <li>a {@code @TaskId} method returning without completing leaves the job OPEN:
 * its lock is extended by the adapter's <code>async-task-lock-renewal</code> (one
 * hour by default), so the handler is not re-invoked while the workflow waits for
 * the asynchronous completion (<code>ProcessService#completeTask</code>). When the
 * window passes, the cluster hands the job out again, the core answers that
 * redelivery from its delivery record with COMPLETION_PENDING, and the lock is
 * extended by another window - the renewal is driven by the cluster's own
 * redelivery and the application never notices it. The worker's regular job timeout
 * stays short: crash recovery of non-async tasks is not delayed.</li>
 * <li>the command which reports the outcome is REPEATED where the cluster rejected it for
 * backpressure: a completion of work which is already committed must not cost
 * the job a retry just because the cluster was busy. The retry is bounded by the job's
 * remaining lock and by five attempts, see
 * {@link Camunda8CommandRetry} - a handler waiting occupies
 * an execution slot, which is why the bound is small. A job which is failed after all gets
 * a <code>retry-backoff</code>, which the element's own task header may name instead of the
 * configuration - see {@link Camunda8RetryBackoffHeader};</li>
 * <li>a failure, a BPMN error or an overdue failure which finds the job GONE is a late
 * answer and not a failure: a redelivery answered the job first, or its element or workflow
 * ended while the handler was working. The cluster has moved on, so the handler writes a
 * debug line and goes on, see {@link Camunda8CommandRetry#sendUnlessTheJobIsGone};</li>
 * <li>a delivery which fails while the workflow module is SHUTTING DOWN is not reported
 * as a job failure: the adapter's state decides, not the exception, because a
 * handler interrupted by the closing client throws like any other. The job keeps its lock
 * and its retries, the cluster hands it out again once the lock expires, and VanillaBP's
 * delivery record decides whether the work has to run again. Before that, the shutdown
 * WAITS for the handlers in flight - see {@link Camunda8Drain};</li>
 * <li>the core measures how long such a task has been open and says when it passed
 * <code>vanillabp.delivery.max-task-age</code>. Where
 * <code>async-task-max-age-action</code> is <code>incident</code>, the renewal stops
 * there and the job is failed with a guiding message, so the cluster raises an
 * incident naming the workflow aggregate and the age.</li>
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
   * The full client - job-lock extension (dormancy) is not part of the
   * {@link JobClient} handed to handlers.
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
   * What the workflow module has in flight, and whether it is going down:
   * this handler registers its delivery there, and a failure while the module is going
   * down is the shutdown rather than the application. Never <code>null</code> - a handler
   * built without one (tests) gets a drain of its own, which never shuts down.
   */
  private final Camunda8Drain drain;

  /**
   * What kind of worker this is, in the messages about a shutdown.
   */
  static final String KIND = "task";

  /**
   * Translates the identifiers the cluster reports back into the plain ones the core
   * knows - a no-op unless the workflow module uses prefixes. May be
   * <code>null</code> (tests).
   */
  private final NameClashAvoidanceSupport scoping;

  /**
   * Which multi-instance elements enclose the job's element. May be
   * <code>null</code> (tests) - then no iteration is reported, which is what this
   * adapter did before.
   */
  private final Camunda8MultiInstance.Registry multiInstanceRegistry;

  /**
   * How long the cluster waits before it hands a failed job out again, resolved
   * per task. May be <code>null</code> (tests) - then
   * {@link Camunda8RetryBackoffResolver#DEFAULT_RETRY_BACKOFF} applies.
   */
  private final Camunda8RetryBackoffResolver retryBackoffResolver;

  /**
   * What the models say about the backoff of this worker's task, and what was already
   * said about them. One per worker, and a worker serves one task definition, so a model
   * which got its header wrong costs one line per element rather than one per job.
   */
  private final Camunda8RetryBackoffHeader retryBackoffHeader;

  /**
   * What this worker asked the cluster for - a job carries these variables and
   * no others. Read twice: a missing aggregate-ID variable now has a second possible
   * cause, and a <code>&#64;TaskParam</code> outside the list is a question this delivery
   * cannot answer. Never <code>null</code>; a handler built without one (tests) sees
   * every variable, which is what a worker naming no list gets.
   */
  private final Camunda8FetchVariables.Selection fetchVariables;

  /**
   * Answers whether a workflow of (scoped process id, version) was started before the
   * version this boot deployed - see
   * {@link TaskInvocationContext#predatesDeployedVersion()}.
   * A predicate rather than the version catalog itself: the handler needs one answer, and
   * a test needs to hand it in without building a catalog.
   */
  private final BiPredicate<String, Integer> predatesDeployedVersion;

  /**
   * Asks the cluster about the OTHER tasks this workflow has open, once the outcome of this
   * job went back. May be <code>null</code> (tests, and an adapter run of a core which does
   * not drive the check), which asks nothing.
   */
  private final Camunda8OpenTaskProbe openTaskProbe;

  /**
   * The worker this handler serves. Built through the generated
   * <code>Camunda8JobHandler.builder()</code>: seven of these twelve values may be left
   * out, and a positional list of that length no longer says which is which.
   *
   * @param adapterId The adapter whose worker delivers here
   * @param workflowModuleId The workflow module the subscribed tasks belong to
   * @param camundaClient The full client, for the lock extension a {@link JobClient} has no
   *          command for
   * @param workflowTaskInvoker The core's runtime entry point
   * @param asyncTaskLockRenewal How long an open asynchronous task's job lock is extended by
   * @param scoping Translates the cluster's identifiers back, or <code>null</code>
   * @param multiInstanceRegistry Which multi-instance elements enclose an element, or
   *          <code>null</code> for no iteration
   * @param asyncTaskMaxAgeAction What to do with an overdue task, or <code>null</code> for
   *          <code>report</code>
   * @param drain What the workflow module has in flight, or <code>null</code> for a drain of
   *          this handler's own which never shuts down
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

    // the cluster reports the identifiers IT knows - translate them back into the
    // plain ones the core's registries are keyed by
    final var bpmnProcessId = NameClashAvoidanceSupport
        .plainProcessId(scoping, workflowModuleId, job.getBpmnProcessId(), adapterId);
    final var taskDefinition = NameClashAvoidanceSupport
        .plainTaskDefinition(scoping, workflowModuleId, bpmnProcessId, job.getType(), adapterId);

    // From here until the finally, the shutdown of this workflow module waits
    // for this handler instead of pulling the client away from under it
    drain.jobStarted(job.getKey(), KIND, taskDefinition, bpmnProcessId);
    try {
      handleJob(client, job, bpmnProcessId, taskDefinition);
    } catch (final RuntimeException e) {
      // what reaches this point is the way BACK to the cluster (a completion, a BPMN
      // error, a lock renewal); the invocation itself is answered below. Letting it
      // escape during a shutdown would make the client fail the job with one retry less
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
    // built once and read twice: the invocation is what it is for, and the check of the
    // other open tasks of this workflow is handed the same context afterwards
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
      // While the module is going down, the failure is the shutdown and not the
      // application - the job keeps its lock and its retries
      if (drain.leaveJobToItsLock(job.getKey(), KIND, taskDefinition, e)) {
        return;
      }
      // the core rolled the local transaction back - fail the job so Camunda 8
      // applies its retry semantics (retries reach 0 -> incident)
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
                      // without it the cluster hands the job out again at once, so a
                      // handler failing on something which needs a moment burns its
                      // retries before the cause has a chance to pass
                      .retryBackoff(retryBackoff)
                      // the type belongs into the incident as much as the message does:
                      // what a NullPointerException says on its own is 'null'
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
        // read ONCE and not per attempt: a repeated command carries what the handler
        // produced, and reading the aggregate again would cost a transaction per retry
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
                        // the model's error codes are prefixed too, so the code the
                        // business method raised has to be translated on its way to the
                        // cluster. The prefix is this task's workflow module, which is the
                        // catcher's module as well: a call activity of this engine names its
                        // called process by id, that id carries the calling module's prefix
                        // under 'use-prefix', and under 'by-adapter' the cluster resolves it
                        // in the tenant of the calling instance. There is no attribute on a
                        // called element which could name another one, so a call activity
                        // cannot leave its workflow module here
                        .errorCode(
                            NameClashAvoidanceSupport
                                .scopedIdentifier(
                                    scoping, workflowModuleId, outcome.errorCode(), adapterId))
                        .errorMessage(String.valueOf(outcome.errorName()))
                        // the error boundary's outgoing path may branch on the aggregate
                        .variables(errorVariables),
                    Camunda8JobLease.tokenOf(job))
                .send()
                .join());
      }
      case COMPLETION_PENDING -> {
        if (outcome
            .maxAgeExceeded() && (asyncTaskMaxAgeAction == Camunda8AdapterConfiguration.AsyncTaskMaxAgeAction.INCIDENT)) {
          // the renewal stops here: a task nobody will ever complete belongs where
          // operators look, and on Camunda 8 that is an incident rather than a log line
          failAsOverdue(client, job, taskDefinition, bpmnProcessId, String.valueOf(aggregateId), outcome.openFor());
          return;
        }
        renewTheLock(job, taskDefinition);
      }
    }

    // and now, with the outcome of THIS job back at the cluster, the other tasks the core
    // believes are open in the same workflow. After and not before, because the completion
    // of this very job is what can fire the boundary event which takes another one away
    if (openTaskProbe != null) {
      openTaskProbe.reportWhatTheClusterNoLongerHas(bpmnProcessId, context);
    }

  }

  /**
   * Extends the lock of a job whose task stays open by the renewal window, so the handler
   * is not run again while the workflow waits for <code>ProcessService#completeTask</code>.
   * When the window passes, the cluster hands the job out again, the core answers from its
   * delivery record, and this renews the lock once more.
   * <p>
   * The renewal can come too late, and that is not a failure. The task is open from the
   * moment the handler decides so, which is before the renewal reaches the cluster. An
   * application which completes the task in that gap closes the job first. The cluster then
   * refuses the renewal: with HTTP <code>400</code> (gRPC <code>INVALID_ARGUMENT</code>)
   * while it still holds the closed job, and with <code>404</code> once it does not. A lock
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
   * <code>vanillabp.delivery.max-task-age</code> allows, with no retries left, so the
   * cluster raises an incident right away. The message is what an operator sees in the
   * incident, so it names the workflow aggregate, the age and both ways out.
   *
   * @param client The job client of this delivery
   * @param job The activated job of the open task
   * @param taskDefinition The task definition, as the core knows it
   * @param bpmnProcessId The BPMN process id, as the core knows it
   * @param aggregateId The workflow aggregate's id
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
    // no retry backoff: with no retries left there is no next attempt to delay
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
   * the element's own task header models, or the most specific configured
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
      // the header speaks about one task, so it outranks every level which speaks about
      // more than that one
      return modelled;
    }
    retryBackoffHeader
        .reportConfigurationWins(job, bpmnProcessId, taskDefinition, modelled, configured.duration());
    return configured.duration();

  }

  /**
   * The variables the completion of a job carries: the values the workflow
   * aggregate shares with the cluster (the {@code @WorkflowTask} method just changed
   * it and a gateway right after this task has to see the NEW values; see decision 1
   * in the repository's DECISIONS.md)
   * plus - always, no matter what the sync model says - the technical variable
   * holding the aggregate's ID.
   *
   * @param bpmnProcessId The BPMN process ID
   * @param aggregateIdName The name of the aggregate's ID property
   * @param aggregateId The aggregate's ID as it arrived in the job's variables
   * @return The variables (never <code>null</code>)
   */
  private Map<String, Object> variablesOf(
      final String bpmnProcessId,
      final String aggregateIdName,
      final Object aggregateId) {

    final var variables = new LinkedHashMap<String, Object>(
        // the core loads the aggregate in its OWN transaction (the task's one is
        // committed) and never throws - a failed read yields an empty map
        workflowTaskInvoker.syncedWorkflowAggregateValues(
            workflowModuleId,
            bpmnProcessId,
            String.valueOf(aggregateId),
            Camunda8ProcessService.SYNC_MODE));
    variables.put(aggregateIdName, String.valueOf(aggregateId));
    return variables;

  }

  /**
   * Completes the job, tolerating that a parallel redelivery (at-least-once) has
   * already completed it - the documented residual of completing AFTER the local
   * transaction committed.
   */
  private void completeTolerantly(
      final JobClient client,
      final ActivatedJob job,
      final String taskDefinition,
      final Map<String, Object> variables) {

    try {
      // the work is committed at this point, so a cluster which is momentarily too busy
      // must not cost the job a retry
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
     * deployed - computed by the handler, which is where the predicate lives.
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
     * registry is keyed by the process id the CLUSTER knows, which is what the job
     * reports - element ids themselves are never scoped.
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

      // the version of the deployed process definition this job belongs to - the
      // cluster ships it with every job, so nothing has to be queried
      return String.valueOf(job.getProcessDefinitionVersion());

    }

    @Override
    public String getWorkflowAggregateId() {

      return workflowAggregateId;

    }

    @Override
    public String getBpmnElementId() {

      // the element of the model this job was created for. The task definition next to
      // it is the job type, and a job type may be shared by several elements, so this is
      // the answer to "which element of the model is this"
      return job.getElementId();

    }

    @Override
    public String getWorkflowId() {

      // the process instance key of the cluster - what Operate is searched by
      return String.valueOf(job.getProcessInstanceKey());

    }

    @Override
    public String getTaskId() {

      // the job key identifies the open job - used by ProcessService#completeTask
      return String.valueOf(job.getKey());

    }

    @Override
    public TaskKind getTaskKind() {

      // this handler serves jobs and nothing else, so the id above is a job key. The
      // cluster takes it back through UpdateJob and CompleteJob. The user-task commands of
      // the same cluster find nothing under a job key, and saying exactly that is what the
      // record is read for
      return TaskKind.TASK;

    }

    @Override
    public boolean predatesDeployedVersion() {

      // both halves come from what is already at hand: the job names its process and
      // its version, and the adapter remembers what it deployed. No query, no cost
      return predatesDeployedVersion;

    }

    @Override
    public String getDeliveryId() {

      // the job key is stable across redeliveries of the same job (a failed job is
      // re-activated under its key, and the key of a completed job is never handed out
      // again), while every new element activation creates a new job - exactly the
      // identity the core remembers a processed delivery by
      return String.valueOf(job.getKey());

    }

    @Override
    public String getActivationId() {

      // the ELEMENT instance, not the job: the two elements of a multi-instance
      // activity are two element instances, while a job created a second time for ONE
      // element must not read as a new element. That the job key would answer this
      // question as well today is not a reason to give it - it answers the delivery
      // question, and the two contracts are opposite
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

    // runInCurrentTransaction stays false: job workers run on client threads
    // without a transaction - the core opens a NEW one which commits BEFORE the
    // job is completed (at-least-once ordering)

  }

}
