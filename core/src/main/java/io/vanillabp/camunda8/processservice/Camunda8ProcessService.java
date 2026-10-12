package io.vanillabp.camunda8.processservice;

import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.search.enums.ElementInstanceType;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import io.camunda.client.api.search.response.Job;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.AdHocSubProcess;
import io.camunda.zeebe.model.bpmn.instance.FlowNode;
import io.camunda.zeebe.model.bpmn.instance.Message;
import io.camunda.zeebe.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.SubProcess;
import io.vanillabp.camunda8.Camunda8ReleaseLine;
import io.vanillabp.camunda8.client.Camunda8BusinessId;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.client.Camunda8CommandRetry;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.client.Camunda8InstanceProbe;
import io.vanillabp.camunda8.client.Camunda8QueryApi;
import io.vanillabp.camunda8.client.Camunda8RefusedStart;
import io.vanillabp.camunda8.client.Camunda8UserTaskProbe;
import io.vanillabp.camunda8.deployment.Camunda8ModelsTheClusterHolds;
import io.vanillabp.camunda8.wiring.Camunda8ConfiguredTenant;
import io.vanillabp.camunda8.wiring.Camunda8MessageTimeToLiveResolver;
import io.vanillabp.camunda8.wiring.Camunda8MultiInstance;
import io.vanillabp.camunda8.wiring.Camunda8Scoping;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.TaskNotFoundException;
import io.vanillabp.spi.process.WorkflowHistory;
import lombok.extern.slf4j.Slf4j;

/**
 * Camunda 8 implementation of the {@link MigratableProcessService}. One instance is
 * created per configured adapter ID (not per adapter type).
 * <p>
 * Camunda 8 is a <b>remote</b>, eventually consistent BPMS. The engine cannot join the
 * local database transaction of the application. So every operation which reaches the
 * cluster goes through the outbox of the core, as a pair of phases.
 * {@link #phaseOperations()} answers which operations this adapter serves, and what it does
 * in each of their phases.
 * <ul>
 *   <li>Phase one runs inside the transaction of the caller. It must never do anything
 *       that <i>advances</i> the BPMN process (e.g. creating an instance). That would race
 *       the local transaction, which is not committed yet. On rollback it would leave a
 *       ghost workflow instance behind. It <i>may</i> contact the cluster for a check which
 *       advances nothing. The only purpose of that check is to abort the local transaction
 *       early, if the phase-two action is already known to be impossible. Completing a
 *       service task does exactly that. It checks that the task still exists by extending
 *       the timeout of its job. It does so from a pre-commit transaction synchronization.
 *       That keeps the window between the check and the phase-two action short, and with
 *       it the number of stale outbox entries. Starting a workflow has nothing to check
 *       against the cluster. An unreachable cluster only makes the phase-two start wait in
 *       the outbox until it is reachable again. So its phase one resolves the aggregate ID
 *       and checks that the adapter is configured.</li>
 *   <li>Phase two runs after the commit, and there the cluster is changed, e.g.
 *       {@link #createProcessInstance(String, java.util.Map, Object)} for a start. It is
 *       dispatched at least once, so it has to survive being run a second time.</li>
 * </ul>
 *
 * @param <A> The workflow-aggregate type
 */
@Slf4j
// see decision 4 in the repository's DECISIONS.md
@SuppressWarnings("LombokSetterMayBeUsed")
public class Camunda8ProcessService<A> implements MigratableProcessService<A> {

  private final String adapterId;

  private final Camunda8ClientFactory clientFactory;

  /**
   * The one-time extension of a job lock which awareness probes and phase-one checks
   * apply. It is the same duration which the job worker grants a dormant async task.
   */
  private final Duration asyncTaskLockRenewal;

  /**
   * Runs phase-one existence checks right before the commit of the transaction of the
   * workflow aggregate. The platform supplies it. It keeps the window between the check and
   * phase two small. The platform resolves the runner of the aggregate. So the check hooks
   * into a unit of work which the APPLICATION brought.
   */
  private final PreCommitRegistrar preCommitRegistrar;

  /**
   * The sync model of the core: which aggregate attributes are shared with the cluster.
   * Camunda 8 is REMOTE, so its default is {@link AggregateSyncMode#FULL}. A BPMN expression
   * can only see what VanillaBP pushed as a process variable. May be <code>null</code>
   * (tests). Then only the technical aggregate-ID variable is written.
   */
  private final WorkflowAggregateSync aggregateSync;

  /**
   * Builds the process service of one configured adapter id. The platform bean of Spring Boot
   * or Quarkus calls it, once per adapter id it found in the configuration.
   * <p>
   * Written out, not generated, because javadoc does not run Lombok. A generated
   * constructor is missing from the published documentation. The documentation then shows
   * a no-arg constructor which this class never had.
   *
   * @param adapterId The configured adapter id this instance answers for
   * @param clientFactory The clients of that adapter id
   * @param asyncTaskLockRenewal How far a probe pushes the lock of a job it looks at
   * @param preCommitRegistrar Where phase one hooks its check into the caller's unit of work
   * @param aggregateSync Which aggregate attributes reach the cluster, or <code>null</code>
   *          to send the aggregate id alone
   */
  public Camunda8ProcessService(
      final String adapterId,
      final Camunda8ClientFactory clientFactory,
      final Duration asyncTaskLockRenewal,
      final PreCommitRegistrar preCommitRegistrar,
      final WorkflowAggregateSync aggregateSync) {

    this.adapterId = adapterId;
    this.clientFactory = clientFactory;
    this.asyncTaskLockRenewal = asyncTaskLockRenewal;
    this.preCommitRegistrar = preCommitRegistrar;
    this.aggregateSync = aggregateSync;

  }

  /**
   * How often the probe is repeated while waiting. It is not configurable on purpose. An
   * operator may have to raise the window, but not the sampling rate.
   */
  private static final Duration WORKFLOW_VISIBILITY_PROBE_INTERVAL = Duration
      .ofMillis(250);

  /**
   * Camunda 8 answers awareness probes from its query API, which an exporter fills
   * asynchronously. A workflow started moments ago exists in the engine, but cannot be
   * searched yet. The core waits out this window if it knows that this cluster holds the
   * workflow. So the everyday case "start a workflow, then correlate the message which lets
   * it continue" works without the application retrying.
   */
  @Override
  public WorkflowVisibilityDelay workflowVisibilityDelay() {

    return delayOf(clientFactory.getConfiguration().workflowVisibilityWindow());

  }

  /**
   * The same window asked for ONE workflow, which is what the core asks.
   * <p>
   * Two cases hide behind the long window, and the engine has already told this adapter
   * which one it is. The long window is there for a workflow which was just STARTED. The
   * engine holds it, and the read model needs up to ten seconds to hear about it. A workflow
   * which the engine no longer holds ended long enough ago to be in that read model. Only
   * its END is still on the way there. That was measured at hundreds of milliseconds on the
   * newer lines and two seconds on the oldest one. {@code ended-workflow-visibility-timeout}
   * covers it.
   * <p>
   * Which case this workflow is in comes from the probe of
   * {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object, String)}.
   * The core ran it on this thread right before asking. It remembers a workflow for which
   * the engine answered <code>404</code>. Nothing else makes the window shorter. A probe
   * which was skipped, which failed, or which said the engine HOLDS the workflow leaves the
   * long window, because none of them says that the workflow is over.
   * <p>
   * The short window changes how long the core waits. It never changes WHAT this adapter
   * answers afterwards. The search below still tells {@link WorkflowAwareness#COMPLETED}
   * from {@link WorkflowAwareness#UNKNOWN_TO_BPMS}. An ended workflow read as unknown would
   * send the next operation of a migration to the wrong BPMS.
   *
   * @param workflowId The process instance key, or <code>null</code> where VanillaBP holds
   *          none
   * @return The window to wait for that workflow
   */
  @Override
  public WorkflowVisibilityDelay workflowVisibilityDelay(
      final String workflowId) {

    if ((workflowId != null) && workflowId.equals(theEngineHasForgottenThisWorkflow.get())) {
      return delayOf(clientFactory.getConfiguration().endedWorkflowVisibilityWindow());
    }
    return workflowVisibilityDelay();

  }

  /**
   * A window as the core reads it. A window of zero or less means no waiting at all.
   */
  private static WorkflowVisibilityDelay delayOf(
      final Duration window) {

    return window.isZero() || window.isNegative()
        ? WorkflowVisibilityDelay.none()
        : new WorkflowVisibilityDelay(window, WORKFLOW_VISIBILITY_PROBE_INTERVAL);

  }

  /**
   * The workflow the engine last answered "I do not hold that" about, on THIS thread.
   * <p>
   * {@link #workflowVisibilityDelay(String)} reads it. A thread is the right place for it,
   * because the core asks the two questions one after the other on the thread of the
   * election. It probes, and if the answer is unknown, it asks how long to keep asking.
   * Nothing is cached beyond that. The next probe on this thread writes its own answer, and
   * an id which is not the one remembered gets the long window.
   */
  private final ThreadLocal<String> theEngineHasForgottenThisWorkflow = new ThreadLocal<>();

  /**
   * How deep the scope hierarchy is walked when a task-scoped push looks for the scope a
   * task runs in. Nobody can read a model with ten levels of nested subprocesses any more.
   * And the limit keeps a broken answer of the query API from looping.
   */
  private static final int MAX_SCOPE_DEPTH = 10;

  /**
   * The default of this adapter: everything is shared, unless the application
   * excludes it ({@code @NoSyncWithBPMS}).
   */
  public static final AggregateSyncMode SYNC_MODE = AggregateSyncMode.FULL;

  /**
   * The name-clash-avoidance model of the core. It translates BPMN process ids, message
   * names and error codes into what the cluster knows. It decides the tenant an operation
   * runs in. May be <code>null</code> (tests). Then identifiers are passed through, and
   * the configured tenant is used, as before.
   */
  private NameClashAvoidanceSupport scoping;

  /**
   * Sets the name-clash-avoidance support (constructor injection is not possible -
   * the platform modules call the constructor of this class, which does not take it).
   *
   * @param scoping The name-clash-avoidance support
   */
  public void setScoping(
      final NameClashAvoidanceSupport scoping) {

    this.scoping = scoping;

  }

  /**
   * What the tenant of a workflow module is CONFIGURED as. The platform modules resolve it
   * over the levels the name may be set at. It is <code>null</code> for a module which no
   * setting names a tenant for. Then the workflow module id names it. It may be
   * <code>null</code> itself (tests). Then the section of the adapter is the only level.
   */
  private Function<String, Camunda8ConfiguredTenant> configuredTenants;

  /**
   * Sets the tenant names the application configured. This is the configuration of this
   * adapter itself, unlike the name-clash-avoidance support, which comes with the
   * collaborators.
   *
   * @param configuredTenants What a workflow module's tenant is configured as
   */
  public void setConfiguredTenants(
      final Function<String, Camunda8ConfiguredTenant> configuredTenants) {

    this.configuredTenants = configuredTenants;

  }

  /**
   * Resolves how long the cluster keeps a message which this adapter publishes, per
   * adapter, workflow module, workflow and message. May be <code>null</code> (tests, and a
   * platform written before this). Then the default of the client applies, and VanillaBP
   * sets nothing on the command.
   */
  private Camunda8MessageTimeToLiveResolver messageTimeToLiveResolver;

  /**
   * The platform module injects it after construction, like the scoping next to it. It is
   * an optional collaborator, not a constructor argument. So a test which builds this
   * service by hand does not have to know about it.
   *
   * @param messageTimeToLiveResolver The resolver, or <code>null</code>
   */
  public void setMessageTimeToLiveResolver(
      final Camunda8MessageTimeToLiveResolver messageTimeToLiveResolver) {

    this.messageTimeToLiveResolver = messageTimeToLiveResolver;

  }

  /**
   * How long the cluster keeps the given message, or <code>null</code> if nothing
   * configures it.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process
   * @param messageName The message name as the application wrote it
   * @return The time-to-live or <code>null</code>
   */
  private Duration messageTimeToLiveFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String messageName) {

    return messageTimeToLiveResolver == null
        ? null
        : messageTimeToLiveResolver.messageTimeToLiveFor(workflowModuleId, bpmnProcessId, messageName);

  }

  /**
   * The BPMN process id as the cluster knows it.
   */
  private String scopedProcessId(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return NameClashAvoidanceSupport.scopedProcessId(scoping, workflowModuleId, bpmnProcessId, adapterId);

  }

  /**
   * A message name / error code as the cluster knows it.
   */
  private String scopedIdentifier(
      final String workflowModuleId,
      final String identifier) {

    return NameClashAvoidanceSupport.scopedIdentifier(scoping, workflowModuleId, identifier, adapterId);

  }

  /**
   * The tenant in which an operation of the given workflow module runs. See the
   * name-clash-avoidance mode.
   */
  private String tenantIdOf(
      final String workflowModuleId) {

    final var configured = configuredTenants != null
        ? configuredTenants.apply(workflowModuleId)
        : Camunda8ConfiguredTenant
            .firstConfigured(
                adapterId,
                workflowModuleId,
                null,
                clientFactory
                    .getConfiguration()
                    .getTenantId());
    return Camunda8Scoping.tenantIdFor(
        scoping, workflowModuleId, adapterId, configured != null
            ? configured.tenantId()
            : null);

  }

  /**
   * What Camunda 8 does with that type. So the startup check can say what happens to a value
   * which travels between the application and this BPMS.
   *
   * @param valueType The declared type of the value
   * @param direction Which way the value travels
   * @return The verdict of this adapter
   */
  @Override
  public io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict whatThisBpmsDoesWith(
      final Class<?> valueType,
      final io.vanillabp.integration.adapter.spi.values.ValueDirection direction) {

    return Camunda8ValueTypes.verdictFor(valueType, direction);

  }

  /**
   * The process variables written whenever this adapter talks to the cluster on behalf of
   * a workflow. These are the shared attributes of the aggregate PLUS the technical
   * variable which carries the ID of the aggregate (named after its ID property). The ID
   * variable is always written, whatever the sync model says. VanillaBP never reads the
   * business id of Camunda 8 (decision 37 in the repository's DECISIONS.md): that variable
   * is how VanillaBP finds the workflow again.
   * <p>
   * Package-private, so {@code Camunda8SharedValuesTest} can hold the second half of that
   * promise without a cluster. An aggregate annotated {@code @NoSyncWithBPMS} shares
   * nothing, and VanillaBP does not read a business id, so losing the ID variable would
   * start a workflow nobody can find again.
   *
   * @param aggregatePersistence The aggregate's persistence
   * @param workflowAggregateId The aggregate's ID
   * @return The variables (never <code>null</code>)
   */
  Map<String, Object> variablesOf(
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    final var variables = new LinkedHashMap<String, Object>();
    if (aggregatePersistence == null) {
      // no persistence at hand (e.g. a test which drives the SPI directly). Neither the
      // shared attributes nor the technical ID variable can be determined
      return variables;
    }
    if (aggregateSync != null) {
      final var aggregate = aggregatePersistence.loadById(workflowAggregateId);
      if (aggregate != null) {
        variables.putAll(aggregateSync.syncedValues(aggregate, SYNC_MODE));
      } else {
        log.warn(
            "Camunda8[{}]: the workflow aggregate '{}' could not be loaded - only the technical "
                + "aggregate-ID variable is written to the cluster",
            adapterId,
            workflowAggregateId);
      }
    }
    variables.put(
        aggregatePersistence.getAggregateIdName(),
        workflowAggregateId == null
            ? null
            : workflowAggregateId.toString());
    return variables;

  }

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  /**
   * What this adapter does for each operation, in both phases.
   * <p>
   * Phase one is what a REMOTE cluster can be asked without advancing anything: a renewal
   * of a job timeout, an empty user-task update, the message names of the model. It runs as
   * a pre-commit hook, so the window to the phase-two dispatch stays small. Phase two sends
   * the command. It tolerates the at-least-once dispatch of the outbox, up to the message
   * deduplication which the cluster runs on its own.
   */
  @Override
  public Map<PhaseOperation, PhaseOperationHandler<A>> phaseOperations() {

    return Map
        .ofEntries(
            Map
                .entry(
                    PhaseOperation.START_WORKFLOW,
                    PhaseOperationHandler.of(this::preflightStart, this::startWorkflow)),
            Map
                .entry(
                    PhaseOperation.START_WORKFLOW_BY_MESSAGE,
                    PhaseOperationHandler.of(this::preflightStartByMessage, this::startWorkflowByMessage)),
            Map
                .entry(
                    PhaseOperation.COMPLETE_TASK,
                    PhaseOperationHandler.of(this::preflightCompleteTask, this::completeTask)),
            Map
                .entry(
                    PhaseOperation.CANCEL_TASK,
                    PhaseOperationHandler.of(this::preflightCancelTask, this::cancelTask)),
            Map
                .entry(
                    PhaseOperation.COMPLETE_USER_TASK,
                    PhaseOperationHandler.of(this::preflightCompleteUserTask, this::completeUserTask)),
            Map
                .entry(
                    PhaseOperation.CANCEL_USER_TASK,
                    PhaseOperationHandler.of(this::preflightCancelUserTask, this::cancelUserTask)),
            Map
                .entry(
                    PhaseOperation.CORRELATE_MESSAGE,
                    PhaseOperationHandler.of(this::preflightCorrelateMessage, this::correlateMessage)),
            Map
                .entry(
                    PhaseOperation.SEND_SIGNAL,
                    PhaseOperationHandler.of(this::preflightSendSignal, this::sendSignal)),
            Map
                .entry(
                    PhaseOperation.AGGREGATE_CHANGED,
                    PhaseOperationHandler.of(this::preflightAggregateChanged, this::pushChangedAggregate)));

  }

  @Override
  public boolean deliversTasksAtLeastOnce() {

    // job workers report the outcome AFTER the local transaction was committed. So after
    // a crash in between, the cluster hands the same job to a worker again. The JOB KEY
    // identifies the job across such a redelivery, and every invocation context reports it.
    return true;

  }

  @Override
  public Long openTaskCount(
      final String workflowModuleId,
      final String bpmnProcessId) {

    // asked once per BPMN process at startup, so one search is affordable if the cluster
    // can serve it at all. Both kinds which VanillaBP delivers are jobs. A service task
    // IS a job, and a user task managed by Camunda reaches the application through its
    // listener job
    try {
      // the TOTAL, not the page which came back. Only one item is fetched, because only
      // the number is wanted
      final var found = clientFactory
          .getClient()
          .newJobSearchRequest()
          .filter(filter -> filter.processDefinitionId(scopedProcessId(workflowModuleId, bpmnProcessId)))
          .page(page -> page.limit(1))
          .send()
          .join();
      return found.page().totalItems();
    } catch (final Exception e) {
      // a startup diagnostic. So if the cluster did not answer, only the number is lost
      log
          .debug(
              "Camunda8[{}]: the cluster did not answer how many tasks of '{}' are open",
              adapterId,
              bpmnProcessId,
              e);
      return null;
    }

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final WorkflowScope scope,
      final Object workflowAggregateId,
      final String taskId) {

    // the probe is UpdateJobTimeout. It is a NON-ADVANCING command which also renews the
    // lock. The lock of the job is set to the renewal window, the same value the worker
    // granted when the task was left open. Camunda 8 cannot answer COMPLETED for jobs.
    // Without the eventually consistent search API, a completed job looks the same as one
    // which never existed. So a successful "not found" maps to UNKNOWN_TO_BPMS.
    //
    // A job key is unique per CLUSTER. If another adapter id addresses the same cluster,
    // the probe would answer for its job and extend its lock on the way (see decision 3 in
    // the repository's DECISIONS.md). So in that case the scope of the key is asked FIRST,
    // and nowhere else, because the question costs a round trip to the query API.
    if (!belongsToThisAdapter(scope, taskId, false)) {
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }
    try {
      updateJobTimeout(taskId);
      return WorkflowAwareness.ACTIVE;
    } catch (final Exception e) {
      if (Camunda8Errors.jobAlreadyGone(e)) {
        // the platform throws on this answer at once. So the rejection which caused it is
        // logged here, not at a level nobody had turned on. answersForTheScopeOf gives the
        // same reason for the other branch
        log.info(
            "Camunda8[{}]: task '{}' is unknown to the cluster - it refused the probe's "
                + "UpdateJobTimeout with {}",
            adapterId,
            taskId,
            Camunda8Errors.rejection(e));
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }
      if (Camunda8Errors.jobIsThereButNotActive(e)) {
        // the cluster holds the job and refused to move its deadline. It answers that for a
        // job which nobody has activated right now. The everyday case is an asynchronous task
        // whose lock ran out. The task is alive, so the probe says so, and does not send the
        // caller into retries
        log.debug(
            "Camunda8[{}]: the cluster holds task '{}' but no worker has it activated - it "
                + "refused the probe's UpdateJobTimeout with {}",
            adapterId,
            taskId,
            Camunda8Errors.rejection(e));
        return WorkflowAwareness.ACTIVE;
      }
      log.warn(
          "Camunda8[{}]: could not determine awareness of task '{}' - reporting BPMS_UNAVAILABLE",
          adapterId,
          taskId,
          e);
      return WorkflowAwareness.BPMS_UNAVAILABLE;
    }

  }

  private void updateJobTimeout(
      final String taskId) {

    clientFactory
        .getClient()
        .newUpdateTimeoutCommand(taskKeyOf(taskId))
        .timeout(asyncTaskLockRenewal)
        .send()
        .join();

  }

  private void preflightCompleteTask(
      final PhaseOneRequest<A> request) {

    registerPreCommitExistenceCheck(aggregateClassOf(request.aggregatePersistence()), request.taskId(), "completing");

  }

  private void preflightCancelTask(
      final PhaseOneRequest<A> request) {

    registerPreCommitExistenceCheck(aggregateClassOf(request.aggregatePersistence()), request.taskId(), "canceling");

  }

  /**
   * The workflow aggregate to whose transaction a phase-one check belongs.
   * <p>
   * The core always hands the persistence of the aggregate along. Only tests call phase
   * one without one. Then {@code Object.class} resolves the runner of the platform, and a
   * test without any aggregate persistence has only that one anyway.
   *
   * @param aggregatePersistence The persistence of the call at hand, may be
   *          <code>null</code> in tests
   * @return The aggregate class
   */
  private Class<?> aggregateClassOf(
      final AggregatePersistenceAware<A> aggregatePersistence) {

    return aggregatePersistence == null
        ? Object.class
        : aggregatePersistence.getAggregateClass();

  }

  /**
   * The phase-one contract for a remote BPMS: a NON-ADVANCING existence check. Its only
   * purpose is to abort the local transaction early if the task is already gone. It is
   * registered as a PRE-COMMIT synchronization, not run when the method is called. So the
   * window between the check and the phase-two dispatch stays minimal, and with it the
   * number of stale outbox entries (the V1 refinement). The check is the same
   * UpdateJobTimeout which the awareness probe uses. It renews the lock of the dormant job
   * on the side, and it never advances the process.
   */
  private void registerPreCommitExistenceCheck(
      final Class<?> workflowAggregateClass,
      final String taskId,
      final String operationDescription) {

    preCommitRegistrar.beforeCommit(workflowAggregateClass, () -> {
      try {
        updateJobTimeout(taskId);
      } catch (final Exception e) {
        if (Camunda8Errors.jobAlreadyGone(e)) {
          throw newTaskNotFound(
              taskId,
              ("The task '%s' is gone (completed or canceled meanwhile) - aborting the transaction "
                  + "%s it! If this task was completed by a concurrent redelivery, retrying the "
                  + "business operation will end in the documented no-op.")
                  .formatted(taskId, operationDescription),
              e);
        }
        throw e;
      }
    });

  }

  /**
   * What a pre-commit check throws once the cluster has said the task is gone.
   * <p>
   * The type is the one which the SPI documents for exactly this outcome. So an
   * application which catches {@link TaskNotFoundException} catches it here as well. The
   * task may have turned out to be gone while a BPMS was probed, or while this check ran.
   * That is the business of the adapter, not of the caller. The exception carries a
   * message and nothing else. So the words of the cluster about the rejection are written
   * to the log before it is thrown. The awareness probe logs them for the same reason.
   *
   * @param taskId The task the cluster no longer knows
   * @param message What the caller reads, including what to do about it
   * @param rejection What the cluster answered the check with
   * @return The exception to throw
   */
  private TaskNotFoundException newTaskNotFound(
      final String taskId,
      final String message,
      final Exception rejection) {

    log.info(
        "Camunda8[{}]: the pre-commit check found task '{}' gone - the cluster refused it with {}",
        adapterId,
        taskId,
        Camunda8Errors.rejection(rejection));
    return new TaskNotFoundException(message);

  }

  /**
   * What a <code>404</code> of a user-task command is about, in one sentence for the caller.
   * <p>
   * The command asks about a USER-TASK key. So its <code>404</code> means "I hold no user task
   * of that key", not "the task is over". For a key which really is a user-task key, the two
   * are the same. For a JOB key they are not. A user task served by a job worker has no
   * user-task record in the cluster at all. VanillaBP modelled user tasks that way up to its
   * release 1.6.3. So every user-task command answers <code>404</code>, however open the task
   * is.
   * <p>
   * An application meets this while it upgrades. The task ids of version 1 are data it
   * brought with it, and the ones of that shape of user task are job keys. Saying "completed
   * or canceled meanwhile" about one of them names the one thing the cluster did not say.
   * <p>
   * So the job side is asked, once and only here, and its answer goes into the message. The
   * caller still gets what it got before: a {@code TaskNotFoundException} or
   * {@code UNKNOWN_TO_BPMS}. The kind of key somebody handed in changes the sentence, not
   * the outcome.
   *
   * @param taskId The id the caller named
   * @param whatTheCommandWas What was sent, for the sentence
   * @return The sentence, naming a job where the cluster holds one under that key
   */
  private String whatThe404WasAbout(
      final String taskId,
      final String whatTheCommandWas) {

    if (!theClusterHoldsAJobOfThatKey(taskId)) {
      return ("The user task '%s' is gone (completed or canceled meanwhile) - the cluster holds no "
          + "user task of that key and no job either.")
          .formatted(taskId);
    }
    final var job = Camunda8UserTaskProbe.theJobTheIndexHoldsFor(clientFactory.getClient(), taskKeyOf(taskId));
    if (job == null) {
      // the engine has the job, and the index has not written it yet. So the message says what
      // is certain and leaves out the element
      return ("The id '%s' is a JOB key, not a user-task key: the cluster still holds a job of it, "
          + "so the %s answered 404 about the KEY and says nothing about the task. Which element "
          + "that job belongs to is not in the cluster's index yet. A user task served by a job "
          + "worker has such a key, the way VanillaBP modelled one up to its release 1.6.3, and "
          + "this version does not serve that shape - your deployment names every such element "
          + "while this application starts.")
          .formatted(taskId, whatTheCommandWas);
    }
    if (Camunda8TaskWiring.TASKDEFINITION_USERTASK_WORKER_V1.equals(job.type())) {
      return ("The id '%s' is a JOB key, not a user-task key: the cluster holds a job of type '%s' "
          + "for element '%s' of process '%s', last seen by its index as %s. That is a user task "
          + "served by a job worker, the way VanillaBP modelled one up to its release 1.6.3, and "
          + "this version does not serve it - so the %s answered 404 whatever the task is doing, "
          + "and that 404 does NOT say the task is over. The way out is the model: make the user "
          + "task a Camunda-managed one ('zeebe:userTask') and set 'External form reference' "
          + "(zeebe:formDefinition externalReference) to the task definition your @WorkflowTask "
          + "method names, and finish the tasks which are still open on the old element before you "
          + "rely on this application to complete them. Your deployment names every such element "
          + "while this application starts.")
          .formatted(
              taskId,
              job.type(),
              job.elementId(),
              job.bpmnProcessId(),
              job.state(),
              whatTheCommandWas);
    }
    return ("The id '%s' is a JOB key, not a user-task key: the cluster holds a job of type '%s' "
        + "for element '%s' of process '%s', last seen by its index as %s. The %s therefore "
        + "answered 404 about the key rather than about the task. A task of that element is "
        + "completed with 'ProcessService#completeTask', not with 'completeUserTask'.")
        .formatted(
            taskId,
            job.type(),
            job.elementId(),
            job.bpmnProcessId(),
            job.state(),
            whatTheCommandWas);

  }

  /**
   * Whether the cluster holds a JOB of the given key. This decides whether a <code>404</code>
   * of a user-task command was about the key.
   * <p>
   * The ENGINE is asked, with the <code>UpdateJobTimeout</code> which this adapter sends as its
   * existence check for a service task anyway. It answers <code>404</code> for a key it holds
   * no job of. It answers <code>400</code> ("nobody has it activated") for a key it holds. And
   * it accepts the command for a job somebody is holding right now. The index cannot answer
   * this. Measured on 2026-09-28 against <code>camunda/camunda:8.9.21</code> and
   * <code>camunda/camunda:8.10.0-rc1</code> by {@code Camunda8ProbeOfAnOpenUserTaskIT}: this
   * command was accepted for a job which was open. It answered <code>404</code> once that job
   * was gone. The job search answered "no job of that key" for the open job, and still
   * answered with the job once it was over.
   * <p>
   * One cost is worth naming. If a worker of the application holds that job right now, its
   * deadline is pushed out to <code>async-task-lock-renewal</code>, as the existence check of
   * a service task does. In this case the job is a user task which nothing in this version
   * fetches. So the answer is a <code>400</code>, and the cluster writes nothing.
   *
   * @param taskId The id the caller named
   * @return Whether a job of that key is there
   */
  private boolean theClusterHoldsAJobOfThatKey(
      final String taskId) {

    try {
      updateJobTimeout(taskId);
      return true;
    } catch (final RuntimeException e) {
      // only an answer which SAYS that the cluster holds it counts. An unreachable cluster
      // answers neither way. A message which claims a job key from a failed request would be
      // a guess
      return Camunda8Errors.jobIsThereButNotActive(e);
    }

  }

  @Override
  public WorkflowAwareness awarenessOfUserTask(
      final WorkflowScope scope,
      final Object workflowAggregateId,
      final String taskId) {

    // the probe is an EMPTY UpdateUserTask. It is an engine COMMAND, not a search. So it
    // answers from the partition and does not wait for the exporter. It never advances the
    // task, and it answers NOT_FOUND for gone tasks. Side effect: 'updating' task listeners
    // which the modeller defined fire. The README documents this.
    //
    // A task in the middle of a transition of its own is refused, not answered. And a
    // refusal about a task means that the cluster has the task, see
    // Camunda8Errors#refusedAboutAUserTaskItHolds. CREATING is the state to expect here.
    // The application is notified from the 'creating' listener. So it may ask about a task
    // which the cluster has not finished creating.
    //
    // As for service tasks, a user-task key is unique per cluster. On a shared cluster the
    // scope is asked before the task is claimed
    if (!belongsToThisAdapter(scope, taskId, true)) {
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }
    try {
      updateUserTask(taskId);
      return WorkflowAwareness.ACTIVE;
    } catch (final Exception e) {
      if (Camunda8Errors.jobAlreadyGone(e)) {
        log.info(
            "Camunda8[{}]: user task '{}' is unknown to the cluster - it refused the probe's "
                + "UpdateUserTask with {}. {}",
            adapterId,
            taskId,
            Camunda8Errors.rejection(e),
            whatThe404WasAbout(taskId, "probe's UpdateUserTask"));
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }
      if (Camunda8Errors.refusedAboutAUserTaskItHolds(e)) {
        log.debug(
            "Camunda8[{}]: user task '{}' is in a transition of its own - the cluster refused the "
                + "probe's UpdateUserTask with {}, which is a task it holds",
            adapterId,
            taskId,
            Camunda8Errors.rejection(e));
        return WorkflowAwareness.ACTIVE;
      }
      log.warn(
          "Camunda8[{}]: could not determine awareness of user task '{}' - reporting BPMS_UNAVAILABLE",
          adapterId,
          taskId,
          e);
      return WorkflowAwareness.BPMS_UNAVAILABLE;
    }

  }

  private void updateUserTask(
      final String taskId) {

    // an update which carries ONLY an 'action' (an audit metadatum) is the smallest valid
    // update. No task attribute changes, and nothing advances
    clientFactory
        .getClient()
        .newUpdateUserTaskCommand(taskKeyOf(taskId))
        .action(Camunda8UserTaskProbe.ACTION)
        .send()
        .join();

  }

  private void preflightCompleteUserTask(
      final PhaseOneRequest<A> request) {

    // pre-commit existence check (an empty update which advances nothing). The same shape
    // as for service tasks, see registerPreCommitExistenceCheck
    preCommitRegistrar.beforeCommit(aggregateClassOf(request.aggregatePersistence()), () -> {
      try {
        updateUserTask(request.taskId());
      } catch (final Exception e) {
        if (Camunda8Errors.jobAlreadyGone(e)) {
          throw newTaskNotFound(
              request.taskId(),
              "%s Aborting the transaction completing it!"
                  .formatted(whatThe404WasAbout(request.taskId(), "empty UpdateUserTask of this check")),
              e);
        }
        if (Camunda8Errors.refusedAboutAUserTaskItHolds(e)) {
          // this check aborts a transaction whose task is GONE. If the cluster refuses a
          // command ABOUT a task, the task is not gone. The common case is a task which the
          // cluster is still creating. The application was notified from the 'creating'
          // listener of that very task. So it may answer while the creation is still on the
          // way. The completion of phase two waits that out, see completeUserTask
          log.debug(
              "Camunda8[{}]: the pre-commit check found user task '{}' in a transition of its own "
                  + "- the cluster refused the empty update with {}, which is a task it holds",
              adapterId,
              request.taskId(),
              Camunda8Errors.rejection(e));
          return;
        }
        throw e;
      }
    });

  }

  private void completeUserTask(
      final PhaseTwoRequest<A> request) {

    try {
      final var taskKey = taskKeyOf(request.taskId());
      // read ONCE, not per attempt. Every attempt carries what the caller committed, and
      // reading the aggregate again would cost a transaction per attempt
      final var variables = variablesOf(request.aggregatePersistence(), request.workflowAggregateId());
      // the task may still be in CREATING. The application was notified from the
      // 'creating' listener of this very task, and may have answered right away
      Camunda8CommandRetry
          .sendWhileTheUserTaskIsStillChanging(
              adapterId,
              "completion",
              request.taskId(),
              () -> clientFactory
                  .getClient()
                  .newCompleteUserTaskCommand(taskKey)
                  .variables(variables)
                  .send()
                  .join());
      log.info(
          "Camunda8[{}]: completed user task '{}' of BPMN process '{}' of workflow module '{}'",
          adapterId,
          request.taskId(),
          request.bpmnProcessId(),
          request.workflowModuleId());
    } catch (final Exception e) {
      if (!Camunda8Errors.jobAlreadyGone(e)) {
        throw e;
      }
      log.warn(
          "Camunda8[{}]: user task '{}' is gone - skipping the redelivered phase-two completion",
          adapterId,
          request.taskId());
    }

  }

  private void preflightCancelUserTask(
      final PhaseOneRequest<A> request) {

    // fail EARLY inside the caller's transaction: see newCancelUserTaskUnsupported
    throw newCancelUserTaskUnsupported(request.taskId(), request.bpmnProcessId());

  }

  private void cancelUserTask(
      final PhaseTwoRequest<A> request) {

    throw newCancelUserTaskUnsupported(request.taskId(), request.bpmnProcessId());

  }

  private UnsupportedOperationException newCancelUserTaskUnsupported(
      final String taskId,
      final String bpmnProcessId) {

    // no Camunda 8 cluster up to 8.9 offers a command to cancel a user task managed by
    // Camunda by BPMN error. ThrowError is based on jobs, and a zeebe:userTask has no job.
    // The V1 workaround (completing the task with a marker variable which a listener
    // evaluates) is marked "currently not working" in the V1 adapter itself. This needs the
    // task and execution listeners of 8.10. So it can only ever arrive on a line built
    // against 8.10. The message names the line, because the reader has to change the line
    // to get it.
    return new UnsupportedOperationException(
        ("Canceling user task '%s' of BPMN process '%s' by BPMN error is not supported by "
            + "the Camunda 8 cluster of release line %s! The engine offers no command for it "
            + "(ThrowError is job-based; a Camunda-managed user task has no job). Model the "
            + "error path explicitly, e.g. a boundary message or signal. The task listeners "
            + "this needs arrive with Camunda 8.10, so support for it can only ever come on a "
            + "line built against 8.10 or later.")
            .formatted(taskId, bpmnProcessId, Camunda8ReleaseLine.id()));

  }

  private void completeTask(
      final PhaseTwoRequest<A> request) {

    try {
      clientFactory
          .getClient()
          .newCompleteCommand(taskKeyOf(request.taskId()))
          // the aggregate changed before the task was completed, and the
          // cluster only sees what VanillaBP pushes
          .variables(variablesOf(request.aggregatePersistence(), request.workflowAggregateId()))
          .send()
          .join();
      log.info(
          "Camunda8[{}]: completed task '{}' of BPMN process '{}' of workflow module '{}'",
          adapterId,
          request.taskId(),
          request.bpmnProcessId(),
          request.workflowModuleId());
    } catch (final Exception e) {
      if (!Camunda8Errors.jobAlreadyGone(e)) {
        throw e;
      }
      // stale outbox entry. The job disappeared between the probe at dispatch time and
      // this command. This is what remains of at-least-once, and the entry is consumed
      log.warn(
          "Camunda8[{}]: task '{}' is gone - skipping the redelivered phase-two completion",
          adapterId,
          request.taskId());
    }

  }

  private void cancelTask(
      final PhaseTwoRequest<A> request) {

    try {
      clientFactory
          .getClient()
          .newThrowErrorCommand(taskKeyOf(request.taskId()))
          // the model's error codes are prefixed too
          .errorCode(scopedIdentifier(request.workflowModuleId(), request.bpmnErrorCode()))
          .errorMessage("canceled via ProcessService#cancelTask")
          // the outgoing path of the error boundary may branch on the
          // aggregate, which the caller changed before canceling the task
          .variables(variablesOf(request.aggregatePersistence(), request.workflowAggregateId()))
          .send()
          .join();
      log.info(
          "Camunda8[{}]: canceled task '{}' (error code '{}') of BPMN process '{}' of workflow module '{}'",
          adapterId,
          request.taskId(),
          request.bpmnErrorCode(),
          request.bpmnProcessId(),
          request.workflowModuleId());
    } catch (final Exception e) {
      if (!Camunda8Errors.jobAlreadyGone(e)) {
        throw e;
      }
      log.warn(
          "Camunda8[{}]: task '{}' is gone - skipping the redelivered phase-two cancellation",
          adapterId,
          request.taskId());
    }

  }

  /**
   * Whether this cluster can be ASKED which workflows it holds.
   * <p>
   * Normally yes. The deployment ends the boot on a cluster which refuses to be searched,
   * see decision 20 in the repository's DECISIONS.md. One case survives that. It is the
   * reason why this reads the probe and does not answer a constant <code>true</code>. An
   * adapter which is not the first-priority adapter of its workflow module and carries
   * <code>deployment-failure: warn</code> boots DEGRADED against such a cluster. It serves
   * nothing. The core has to hear that it cannot locate a workflow before it builds a
   * migration on it.
   *
   * @return Whether the query API answers
   */
  @Override
  public boolean canLocateWorkflows() {

    return clientFactory
        .getQueryApi()
        .answers();

  }

  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final WorkflowScope scope,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    // Zeebe offers NO engine command which answers "does an instance for this aggregate
    // exist". There is only the eventually consistent query API. That is why this adapter
    // needs a cluster it can search. The search filters by the aggregate-ID process
    // variable.
    //
    // It does NOT filter by state, although only an ACTIVE instance can be advanced. An
    // ended workflow is COMPLETED, not UNKNOWN_TO_BPMS. That difference is the whole point
    // of the two values. "Unknown" allows falling back to the next adapter, and the
    // viewer/history API reports it as WorkflowNotFoundException. "Completed" says that
    // this BPMS is the one which held the workflow. Filtering here made every read of an
    // ended workflow fail. And an operation which arrived too late became a lookup failure.
    //
    // A workflow which somebody cancelled is reported as ACTIVE until it has really ended.
    // That can take much longer than the search needs. The engine answers 404 to a second
    // cancellation within milliseconds. But an instance which holds a user task managed by
    // Camunda only terminates once the canceling listener job of that task is answered.
    // Measured against a cluster of the 8.9 line on 2026-09-25: with that job answered at
    // once, the search stopped reporting the instance after 0,8 to 1,0 seconds. With
    // nobody answering it, the instance was still reported 130 seconds later. It ended half
    // a second after a worker finally took the job. ACTIVE is the right answer for all of
    // that time. The instance is there, and an operation sent to it is taken. The 404 of
    // the engine is NOT an answer, and that is why nothing here reads one.
    try {
      final var found = clientFactory
          .getClient()
          .newProcessInstanceSearchRequest()
          .filter(filter -> Camunda8Searches
              .byAggregateId(
                  filter, aggregateIdVariableName(aggregatePersistence), workflowAggregateId))
          .send()
          .join();
      // on a cluster shared with another adapter id, the variable alone also finds the
      // instance of the other deployment. Only what THIS adapter deployed counts
      final var mine = found
          .items()
          .stream()
          .filter(instance -> isInScope(scope, instance.getTenantId(), instance.getProcessDefinitionId()))
          .toList();
      if (mine.isEmpty()) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }
      return mine
          .stream()
          .anyMatch(instance -> !hasEnded(instance.getState()))
              ? WorkflowAwareness.ACTIVE
              : WorkflowAwareness.COMPLETED;
    } catch (final Exception e) {
      // a cluster of this adapter can be searched, because the deployment refuses one
      // which cannot. So a search which fails here is an outage. It is never a guess about
      // what the cluster holds. In a migration setup, a guess would route the operation to
      // the wrong BPMS. The credentials are named, because credentials which lose their read
      // permission while the application runs look exactly like an outage here
      log.warn(
          "Camunda8[{}]: could not determine awareness of the workflow of aggregate '{}' - "
              + "reporting BPMS_UNAVAILABLE ({})",
          adapterId,
          workflowAggregateId,
          Camunda8QueryApi.WHY_A_SEARCH_FAILS_AFTER_THE_DEPLOYMENT,
          e);
      return WorkflowAwareness.BPMS_UNAVAILABLE;
    }

  }

  /**
   * The same question, with the id which the BPMS itself gives the workflow. The election
   * passes it on the paths which WAIT for the read model to catch up.
   * <p>
   * <b>What the key buys.</b>
   * Measured on 8.10.0-alpha5, 8.9.19 and 8.8.37: the engine says "this instance exists" 16
   * to 19 ms after the create was sent. The search below finds it after 167 to 1324 ms. So
   * an extension which asks where a workflow started moments ago gets an answer in
   * milliseconds. It does not have to sit out the visibility window.
   * <p>
   * <b>The rule which must not be broken.</b>
   * The probe may shorten the YES and nothing else. The engine forgets an instance the
   * moment it ends. So a key it does not hold may be a completed workflow, a canceled one,
   * or a key which never existed. This method has to tell {@link WorkflowAwareness#COMPLETED}
   * from {@link WorkflowAwareness#UNKNOWN_TO_BPMS}, because only the second lets the
   * election move on to the next BPMS. So every answer except "the engine holds it" falls
   * through to the search below, unchanged.
   * <p>
   * A probe which cannot answer at all (a timeout, a broken connection, a cluster which is
   * not there) is not a 404 and is never read as one. It falls through as well, and then
   * the search decides. The search reports {@link WorkflowAwareness#BPMS_UNAVAILABLE} for a
   * cluster nobody can reach. A mechanism which turned an outage into "unknown" would move
   * a whole migration setup to the next BPMS.
   * <p>
   * On a cluster which this adapter SHARES with another adapter id, nothing is asked at
   * all. An instance key is unique per cluster and names no scope. The election hands the
   * same id to every adapter of its list. So the probe would answer for the instance of the
   * other adapter, and end the election at the wrong adapter.
   *
   * @param scope The workflow module and BPMN processes being asked about
   * @param aggregatePersistence The workflow aggregate's persistence support
   * @param workflowAggregateId The id of the workflow aggregate
   * @param workflowId The process instance key, or <code>null</code> where VanillaBP holds
   *          none
   * @return The cluster's awareness of that workflow
   */
  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final WorkflowScope scope,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String workflowId) {

    return theEngineHoldsTheInstance(scope, workflowAggregateId, workflowId)
        ? WorkflowAwareness.ACTIVE
        : awarenessOfWorkflow(scope, aggregatePersistence, workflowAggregateId);

  }

  /**
   * Asks the ENGINE whether it holds the given instance. The engine answers this from the
   * partition, not from the index which an exporter fills.
   * <p>
   * Which command carries the question is
   * {@link Camunda8InstanceProbe#askTheEngine(io.camunda.client.CamundaClient, long, String, String) per release line}
   * and follows the business id, see decision 35 in the repository's DECISIONS.md. An
   * instance which the engine holds REFUSES both commands. So the cluster writes no state,
   * and an operator never finds a modification in the history of a workflow nobody
   * modified. The refusal is the answer:
   * <ul>
   * <li>HTTP <code>400</code> - the modification names an element the model does not have,
   * which the engine can only say about an instance it holds;</li>
   * <li>HTTP <code>409</code> - the instance already carries a business id, same thing;</li>
   * <li>anything the command took - the instance is there, whatever the command did.</li>
   * </ul>
   * A <code>404</code> means that the engine does not hold it. This method must not turn that
   * answer into anything. The same holds for every failure which is not an answer at all.
   *
   * @param scope What the election asked about
   * @param workflowAggregateId The aggregate, which is the business id this adapter writes
   * @param workflowId The process instance key VanillaBP holds, or <code>null</code>
   * @return Whether the engine reported the instance as one it holds
   */
  private boolean theEngineHoldsTheInstance(
      final WorkflowScope scope,
      final Object workflowAggregateId,
      final String workflowId) {

    // whatever this probe finds out replaces what it found out last time. So a question
    // it does not answer at all leaves nothing behind for the window to read
    theEngineHasForgottenThisWorkflow.remove();
    if ((workflowId == null) || workflowId.isBlank()) {
      return false;
    }
    if (clientFactory.sharesItsCluster()) {
      // an instance key is unique per CLUSTER. It says nothing about which adapter id
      // deployed the process (see decision 3 in the repository's DECISIONS.md). And the
      // election hands the same id to every adapter of its list. So on a shared cluster,
      // this probe would answer ACTIVE for the instance of the OTHER adapter id and end the
      // election at the wrong one. The search below asks that question. Here the engine
      // could only be asked it with an extra round trip, and that would cost what the
      // probe saves
      return false;
    }
    if (aModelOfTheScopeCarriesTheReservedElement(scope)) {
      return false;
    }
    final long processInstanceKey;
    try {
      processInstanceKey = Long.parseLong(workflowId);
    } catch (final NumberFormatException e) {
      // an instance key of this cluster is a number. So this id belongs to another BPMS
      return false;
    }
    try {
      Camunda8InstanceProbe
          .askTheEngine(
              clientFactory.getClient(),
              processInstanceKey,
              Camunda8TaskWiring.RESERVED_PROBE_ELEMENT_ID,
              clientFactory
                  .getConfiguration()
                  .businessIdOf(workflowAggregateId));
      return true;
    } catch (final Exception e) {
      if (Camunda8Errors.notFound(e)) {
        // the engine has forgotten this instance. That says nothing about whether the
        // workflow completed or never existed. The search below tells those apart. But it
        // does say that only the END of this workflow is still on its way into the read
        // model. The short window of workflowVisibilityDelay waits for exactly that
        theEngineHasForgottenThisWorkflow.set(workflowId);
        return false;
      }
      if (Camunda8Errors.refusedAboutAnInstanceItHolds(e)) {
        return true;
      }
      log
          .debug(
              "Camunda8[{}]: the engine could not be asked whether it holds the workflow '{}' of "
                  + "aggregate '{}' - answering from the search instead ({})",
              adapterId,
              workflowId,
              workflowAggregateId,
              Camunda8Errors.rejection(e),
              e);
      return false;
    }

  }

  /**
   * Whether any BPMN process of the scope carries the element id which the probe reserved.
   * That keeps the probe away from a workflow which it might modify instead of ask about.
   */
  private boolean aModelOfTheScopeCarriesTheReservedElement(
      final WorkflowScope scope) {

    return scope
        .bpmnProcessIds()
        .stream()
        .anyMatch(
            bpmnProcessId -> clientFactory
                .getDeployedProcesses()
                .carriesTheReservedProbeElement(scope.workflowModuleId(), bpmnProcessId));

  }

  /**
   * Whether a process instance is over.
   * <p>
   * Asked this way round on purpose. The state enum of the client grows within a line. A
   * client older than the cluster reports a new state as <code>UNKNOWN_ENUM_VALUE</code>.
   * 8.10 adds <code>SUSPENDED</code> here, and somebody can still act on such a workflow.
   * {@link WorkflowAwareness#COMPLETED} says that the operation comes too late. So a state
   * which nobody here knows has to count as running, not as finished.
   *
   * @param state The state the query API reports
   * @return Whether the instance reached its end
   */
  private static boolean hasEnded(
      final ProcessInstanceState state) {

    return (state == ProcessInstanceState.COMPLETED) || (state == ProcessInstanceState.TERMINATED);

  }

  /**
   * The START re-dispatch mitigation probe - STRICTER contract than
   * {@link #awarenessOfWorkflow}: the answer must NEVER be optimistic
   * (an optimistic ACTIVE would SKIP a recovered start = a lost workflow,
   * whereas a duplicate start is the accepted at-least-once residual).
   * Differences to the election probe:
   * <ul>
   * <li>an ended instance counts as well - a workflow COMPLETED since the crashed start
   * still proves the start succeeded. The election probe skips instances which ended;</li>
   * <li>a failed search is {@link WorkflowAwareness#BPMS_UNAVAILABLE} and never an
   * optimistic ACTIVE, so the outbox entry is retried instead of a recovered start
   * being skipped under the at-least-once contract of
   * {@link PhaseOperationHandler#phaseTwo}.</li>
   * </ul>
   */
  @Override
  public WorkflowAwareness awarenessOfWorkflowForRedispatch(
      final WorkflowScope scope,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    return awarenessOfWorkflowForRedispatch(scope, aggregatePersistence, workflowAggregateId, null);

  }

  /**
   * The same probe, counting only the instances started at or after the moment the start was
   * planned.
   * <p>
   * An aggregate may carry a second workflow once its first one ended. Say the first attempt to
   * dispatch the second start failed before it created anything. A search without a state
   * filter still finds the first workflow. Counting it would skip the start, and the second
   * workflow would never run. So only an instance whose <code>startDate</code> is at or after
   * <code>plannedAt</code> counts.
   * <p>
   * The <code>startDate</code> comes from the clock of the cluster. The moment comes from the
   * clock of the node which planned the start. The cluster may be behind by more than the time
   * between planning and the first dispatch. Then the workflow of this entry looks older than
   * the entry, and the answer is "unknown". That costs a duplicate, which the at-least-once
   * contract allows. The moment is never moved back to allow for the clock difference. A moment
   * moved back counts the first workflow again, and that loses the second one. An instance
   * without a <code>startDate</code> does not count either, for the same reason.
   *
   * @param scope The workflow module and BPMN processes being asked about
   * @param aggregatePersistence The workflow aggregate's persistence support
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param plannedAt When the start was planned, or <code>null</code> for an entry planned
   *          before the moment was recorded - then every instance of the aggregate counts
   * @return {@link WorkflowAwareness#ACTIVE} for an instance of the scope started since then,
   *         otherwise {@link WorkflowAwareness#UNKNOWN_TO_BPMS}, or
   *         {@link WorkflowAwareness#BPMS_UNAVAILABLE} where the search failed
   */
  @Override
  public WorkflowAwareness awarenessOfWorkflowForRedispatch(
      final WorkflowScope scope,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final java.time.Instant plannedAt) {

    try {
      final var found = clientFactory
          .getClient()
          .newProcessInstanceSearchRequest()
          .filter(filter -> Camunda8Searches
              .byAggregateId(
                  filter, aggregateIdVariableName(aggregatePersistence), workflowAggregateId))
          .send()
          .join();
      return found
          .items()
          .stream()
          // as in awarenessOfWorkflow: an instance of the other adapter id on this
          // cluster does not prove that THIS one started the workflow
          .filter(instance -> isInScope(scope, instance.getTenantId(), instance.getProcessDefinitionId()))
          .noneMatch(instance -> startedSince(instance.getStartDate(), plannedAt))
              ? WorkflowAwareness.UNKNOWN_TO_BPMS
              : WorkflowAwareness.ACTIVE;
    } catch (final Exception e) {
      log.warn(
          "Camunda8[{}]: could not probe the workflow of aggregate '{}' before re-dispatching "
              + "its start - reporting BPMS_UNAVAILABLE (the outbox entry is retried)",
          adapterId,
          workflowAggregateId,
          e);
      return WorkflowAwareness.BPMS_UNAVAILABLE;
    }

  }

  /**
   * Whether an instance started at or after the moment a start was planned.
   *
   * @param startDate When the cluster says the instance started, <code>null</code> where it
   *          does not say
   * @param plannedAt When the start was planned, <code>null</code> where nobody knows
   * @return Whether the instance counts for that start
   */
  private static boolean startedSince(
      final java.time.OffsetDateTime startDate,
      final java.time.Instant plannedAt) {

    if (plannedAt == null) {
      return true;
    }
    return (startDate != null) && !startDate.toInstant().isBefore(plannedAt);

  }

  /**
   * Whether the given process instance, job or user task belongs to the scope the probe
   * was asked about.
   * <p>
   * The scope is the workflow module and the BPMN processes of the CALL, translated into
   * the names the cluster knows: the tenant of that module and the SCOPED process
   * definition ids. Two facts make the comparison necessary, and neither alone would be
   * enough. First, two <code>camunda8</code> adapter ids may address ONE cluster. That is
   * the supported setup for migrating a workflow module from tenants to prefixed
   * identifiers, and there every key is global. Second, one adapter id serves several
   * workflow modules. Their aggregate ids are unique per aggregate type, not across an
   * application. So "one of mine" is not the same question as "the one you asked about".
   *
   * @param scope What the probe was asked about
   * @param tenantId The tenant the cluster reports, possibly {@code <default>}
   * @param processDefinitionId The process definition id the cluster reports, which is
   *          the SCOPED one wherever a prefix is used
   * @return Whether it belongs to the scope of the call
   */
  private boolean isInScope(
      final WorkflowScope scope,
      final String tenantId,
      final String processDefinitionId) {

    return scopeKeysOf(scope).contains(scopeKey(tenantId, processDefinitionId));

  }

  /**
   * @param scope What the probe was asked about
   * @return The (tenant, scoped process definition id) pairs that scope stands for
   */
  private Set<String> scopeKeysOf(
      final WorkflowScope scope) {

    final var tenantId = tenantIdOf(scope.workflowModuleId());
    return scope
        .bpmnProcessIds()
        .stream()
        .map(bpmnProcessId -> scopeKey(
            tenantId,
            scopedProcessId(scope.workflowModuleId(), bpmnProcessId)))
        .collect(Collectors.toSet());

  }

  /**
   * Whether the task behind the given key belongs to the scope the probe was asked about.
   * It is asked only if another <code>camunda8</code> adapter id addresses the same
   * cluster. A key is unique per cluster, and otherwise the two ids would answer for each
   * other's tasks.
   * <p>
   * The answer is a search. Every cluster this adapter serves answers a search. One which
   * does not is refused while the adapter deploys, whether one adapter id addresses it or
   * two (see {@code Camunda8SearchableClusterCheck}). A task which no search knows is left
   * to the probe itself. It is either gone or not exported yet, and the command which
   * follows answers both.
   * <p>
   * <b>Why not always.</b> If one adapter id owns the cluster, the read would buy very
   * little, for a round trip to the query API on every task election. The key of another
   * workflow module of the same application still addresses the task the operation then
   * acts on, because completing or cancelling goes by that key. And a key of ANOTHER BPMS
   * is not a Camunda 8 key at all. The workflow probes, whose answer routes a message or a
   * pushed aggregate, always compare the scope. There it costs nothing.
   *
   * @param scope What the probe was asked about
   * @param taskId The task id, which is the job respectively user-task key
   * @param userTask Whether it is a user task
   * @return Whether the probe may claim the task
   */
  private boolean belongsToThisAdapter(
      final WorkflowScope scope,
      final String taskId,
      final boolean userTask) {

    if (!clientFactory.sharesItsCluster()) {
      return true;
    }
    try {
      if (userTask) {
        final var task = clientFactory
            .getClient()
            .newUserTaskGetRequest(taskKeyOf(taskId))
            .send()
            .join();
        return answersForTheScopeOf(scope, taskId, task.getTenantId(), task.getBpmnProcessId());
      }
      final var job = jobOf(taskId);
      return (job == null) || answersForTheScopeOf(
          scope,
          taskId,
          job.getTenantId(),
          job.getProcessDefinitionId());
    } catch (final Exception e) {
      if (Camunda8Errors.jobAlreadyGone(e)) {
        // not exported yet, or gone. The command of the probe itself answers that
        return true;
      }
      log.debug(
          "Camunda8[{}]: could not read the scope of task '{}' - probing it as if it were this "
              + "adapter's",
          adapterId,
          taskId,
          e);
      return true;
    }

  }

  /**
   * Whether the scope which the cluster reports for a task is one the probe was asked
   * about. If it is not, this writes the line which says why the task is unknown.
   * <p>
   * <b>Why this is not a DEBUG line.</b> On <code>false</code>, the caller answers
   * UNKNOWN_TO_BPMS, and the platform turns that into a {@code WorkflowNotFoundException}
   * at once. A task is an exact question, so there is no visibility window and no second
   * attempt. A run which ended that way used to leave only the exception of the platform,
   * and not one word from the adapter. The next run would have looked exactly the same. So
   * both values which decided it are kept, not filtered away by a level nobody had turned
   * on.
   *
   * @param scope What the probe was asked about
   * @param taskId The task id, which is the job respectively user-task key
   * @param tenantId The tenant the cluster reports for the task
   * @param processDefinitionId The process id the cluster reports for the task
   * @return Whether the probe may claim the task
   */
  private boolean answersForTheScopeOf(
      final WorkflowScope scope,
      final String taskId,
      final String tenantId,
      final String processDefinitionId) {

    if (isInScope(scope, tenantId, processDefinitionId)) {
      return true;
    }
    log.info(
        "Camunda8[{}]: task '{}' is not this adapter id's to answer for - the cluster reports it "
            + "in scope '{}', while the probe asks about {}",
        adapterId,
        taskId,
        scopeKey(tenantId, processDefinitionId),
        scopeKeysOf(scope));
    return false;

  }

  /**
   * The comparable form of one scope. The cluster reports {@code <default>} for an
   * instance without a tenant, while the adapter has no tenant configured at all. So both
   * are turned into the same value.
   */
  private static String scopeKey(
      final String tenantId,
      final String processDefinitionId) {

    final var tenant = (tenantId == null) || tenantId.isBlank() || DEFAULT_TENANT.equals(tenantId)
        ? DEFAULT_TENANT
        : tenantId;
    return tenant
        + "|"
        + processDefinitionId;

  }

  /**
   * What Camunda 8 calls the tenant of everything which has none.
   */
  private static final String DEFAULT_TENANT = "<default>";

  /**
   * The name of the process variable holding the workflow-aggregate ID: VanillaBP never
   * reads the business id of Camunda 8, so every lookup of a workflow filters by that
   * variable.
   * <p>
   * It is ALWAYS taken from the aggregate persistence of the call at hand, and never
   * remembered between calls. One process service serves every workflow module and
   * aggregate of its adapter id. So a remembered name would be the one of whichever
   * aggregate was handled last. That was once a defect of this adapter. The awareness
   * probe ran before any call which carried a persistence, and it searched for the
   * placeholder name. That found nothing on a cluster with secondary storage. So every
   * operation which located its workflow by the probe failed.
   *
   * @param aggregatePersistence The persistence of the aggregate of this call
   * @return The variable name
   */
  private String aggregateIdVariableName(
      final AggregatePersistenceAware<A> aggregatePersistence) {

    final var name = aggregatePersistence == null
        ? null
        : aggregatePersistence.getAggregateIdName();
    if (name == null) {
      throw new IllegalStateException(
          """
              Camunda 8 cannot look up the workflow of an aggregate without knowing the name of \
              its ID attribute! The aggregate persistence has to answer getAggregateIdName() - \
              it names the process variable VanillaBP writes the aggregate's ID into.""");
    }
    return name;

  }

  @Override
  public boolean isPhaseTwoFailureRepeatable(
      final Throwable failure) {

    // the outbox repeats what a second attempt may fix: a cluster which is busy,
    // unreachable, or lost a conflict. A command which the cluster REJECTS looks the same
    // on every attempt, and so does a task key which is not a number. The list of those
    // cases lives in Camunda8Errors, next to the job-gone rule. A start adds two answers
    // to it, which mean something else for every other operation. It adds them by wrapping
    // its refusal, not by asking here, because this method gets the failure and never the
    // operation
    return !Camunda8Errors.permanentFailure(failure);

  }

  /**
   * Phase one of a message correlation asks the MODEL, not the cluster.
   * <p>
   * A subscription search exists since the client version this adapter builds against.
   * But it would be the wrong check. The cluster BUFFERS a message for its time-to-live.
   * So correlating before the subscription exists is fine, and a search would reject
   * exactly that case. It would also read the eventually consistent secondary storage,
   * and the caller would wait out its window inside their transaction.
   * <p>
   * One thing can be checked without asking anybody: whether the deployed models of this
   * workflow module declare the message at all. A name which no model knows is a typo or a
   * renamed message. Phase two would publish it into the void. The cluster accepts the
   * publication, the TTL passes, and nothing ever correlates. So the mistake is reported
   * where the application made the call.
   * <p>
   * The check stays silent where its set of models is incomplete. That is the case where
   * this application version deployed no process of the workflow module (a workflow still
   * running on a definition of a previous version - see
   * {@code Camunda8DeployedProcesses}). A module which declares a BPMN process id nothing
   * was deployed under (the old id of a renamed process) is judged by the models the
   * cluster holds for its ids, see decision 19 in the repository's DECISIONS.md. Only where
   * that picture is missing or the cluster cannot be asked are the declared names unknown
   * rather than absent. A check which cannot see every model that could carry the answer
   * must stay silent, never refuse.
   */
  private void preflightCorrelateMessage(
      final PhaseOneRequest<A> request) {

    clientFactory.validateConfigured();
    validateMessageIsDeclared(request.workflowModuleId(), request.messageName());

  }

  /**
   * Reports a message name which no model of the workflow module declares. It reads the
   * models the CLUSTER holds for the ids this application declares, not only what this
   * application version deployed. So the verdict does not depend on which application
   * version deployed the model which carries the name (see decision 19 in the
   * repository's DECISIONS.md). If those models cannot be read, the check stays silent.
   *
   * @param workflowModuleId The workflow module of the correlation
   * @param messageName The message name the application passed
   */
  private void validateMessageIsDeclared(
      final String workflowModuleId,
      final String messageName) {

    final var deployedProcesses = clientFactory.getDeployedProcesses();
    final var deployed = deployedProcesses.ofWorkflowModule(workflowModuleId);
    final var declaresUndeployedIds = deployedProcesses
        .declaresProcessesNobodyDeployed(workflowModuleId);
    if (deployed.isEmpty() && !declaresUndeployedIds) {
      // a workflow which still runs on a definition of a previous version, in a module
      // for which this boot deployed nothing. The declared names are unknown, not
      // absent
      return;
    }
    // the models carry the SCOPED names, because messages are renamed while deploying.
    // So the name of the call is scoped the same way as the publication scopes it
    final var scopedMessageName = scopedIdentifier(workflowModuleId, messageName);
    // the models of the current deployment answer without any request
    final var declaredByThisDeployment = deployed
        .stream()
        .flatMap(process -> declaredMessageNames(process.model()).stream())
        .collect(Collectors.toCollection(TreeSet::new));
    if (declaredByThisDeployment.contains(scopedMessageName)) {
      return;
    }
    final var modelsTheClusterHolds = clientFactory.getModelsTheClusterHolds();
    if (modelsTheClusterHolds == null) {
      // no deployment service provided the picture (tests, an adapter which booted
      // degraded). The models beyond the current deployment cannot be read. So for a
      // module whose ids were not all deployed, the check stays silent
      if (declaresUndeployedIds) {
        return;
      }
      throw undeclaredMessage(
          workflowModuleId,
          messageName,
          scopedMessageName,
          "Read were the models this application version deployed",
          declaredByThisDeployment);
    }
    var answer = modelsTheClusterHolds.heldFor(workflowModuleId);
    if ((answer instanceof Camunda8ModelsTheClusterHolds.Answer.Known kept) && !kept
        .freshlyRead() && !declaredMessageNamesOf(kept).contains(scopedMessageName)) {
      // the deployment of another node may have made the kept picture outdated (rolling
      // upgrade). A refusal may only rest on models read now
      answer = modelsTheClusterHolds.heldForAfterReadingAgain(workflowModuleId);
    }
    if (!(answer instanceof Camunda8ModelsTheClusterHolds.Answer.Known known)) {
      // the cluster could not be asked, so the set of models is incomplete. The check
      // says nothing. It does not refuse a correlation which the application may have
      // made correctly
      return;
    }
    final var declared = declaredMessageNamesOf(known);
    if (declared.contains(scopedMessageName)) {
      return;
    }
    throw undeclaredMessage(
        workflowModuleId,
        messageName,
        scopedMessageName,
        "Read was every model the cluster holds for the BPMN processes this workflow module declares",
        declared);

  }

  /**
   * The message names declared by the models the cluster holds.
   *
   * @param known What the picture answered
   * @return The declared names, sorted
   */
  private static TreeSet<String> declaredMessageNamesOf(
      final Camunda8ModelsTheClusterHolds.Answer.Known known) {

    return known
        .models()
        .stream()
        .flatMap(heldModel -> declaredMessageNames(heldModel.model()).stream())
        .collect(Collectors.toCollection(TreeSet::new));

  }

  /**
   * The refusal of {@link #validateMessageIsDeclared}. It is built where the caller can
   * say which models were read.
   */
  private static IllegalArgumentException undeclaredMessage(
      final String workflowModuleId,
      final String messageName,
      final String scopedMessageName,
      final String whatWasRead,
      final java.util.Set<String> declared) {

    return new IllegalArgumentException(
        """
            No BPMN model of workflow module '%s' declares a message '%s'! Camunda 8 would accept \
            the publication and buffer the message until its time-to-live passed, so nothing would \
            ever correlate and nothing would fail. %s, and the messages they declare are: %s. \
            Correct the name passed to correlateMessage, or declare the message at the event which \
            waits for it."""
            .formatted(
                workflowModuleId,
                scopedMessageName.equals(messageName)
                    ? messageName
                    : "%s (scoped: '%s')".formatted(messageName, scopedMessageName),
                whatWasRead,
                declared.isEmpty()
                    ? "none"
                    : declared));

  }

  /**
   * The message names which a deployed model declares: message catch events
   * (intermediate, boundary, event-subprocess start) and receive tasks. These are the
   * same elements into which {@code Camunda8TaskWiring#wireMessageSubscriptions} wires a
   * correlation key. Message START events are included as well. A start by message goes
   * through {@code startWorkflowByMessage}, but here only the declared name matters.
   *
   * @param model The model as deployed
   * @return The declared message names
   */
  private static Set<String> declaredMessageNames(
      final BpmnModelInstance model) {

    return model
        .getModelElementsByType(model
            .getModel()
            .getType(Message.class))
        .stream()
        .map(Message.class::cast)
        .map(Message::getName)
        .filter(Objects::nonNull)
        .filter(name -> !name.isBlank())
        .collect(Collectors.toSet());

  }

  private void correlateMessage(
      final PhaseTwoRequest<A> request) {

    // correlationKey: the correlation id if given, the aggregate ID otherwise
    // (V1 semantics). The wired zeebe:subscription evaluates '=<idName>'. A correlation
    // id needs a subscription in the model on the matching variable.
    // messageId ONLY IF A CORRELATION ID EXISTS. Then the engine deduplicates a second
    // publication of the same message for as long as the message time-to-live lasts.
    // That is a net of its own, separate from the outbox of VanillaBP. The outbox net
    // ends with the dispatch, while this one lasts for the TTL (one hour by default of
    // the client, unless 'message-time-to-live' says otherwise). So the ENGINE swallows
    // a repetition inside the TTL, whatever VanillaBP does. A scope which repeats has to
    // vary the correlation id. Without a correlation id there is no messageId either, and
    // an at-least-once redelivery may correlate twice (documented).
    // PAYLOAD DOCTRINE: no message CONTENT travels. What does travel is the aggregate
    // state shared with the BPMS, because the cluster can only evaluate BPMN expressions
    // against variables it was given.
    final var correlationKey = request.correlationId() != null
        ? request.correlationId()
        : String.valueOf(request.workflowAggregateId());
    var command = clientFactory
        .getClient()
        .newPublishMessageCommand()
        .messageName(scopedIdentifier(request.workflowModuleId(), request.messageName()))
        .correlationKey(correlationKey)
        .variables(variablesOf(request.aggregatePersistence(), request.workflowAggregateId()));
    final var correlationTenantId = tenantIdOf(request.workflowModuleId());
    if (correlationTenantId != null) {
      command = command.tenantId(correlationTenantId);
    }
    if (request.correlationId() != null) {
      // the ACTIVATION belongs in here, for the same reason as in the idempotency key of
      // VanillaBP itself. Three elements of a multi-instance call activity agree in every
      // other part, because a called process is a secondary workflow of the SAME
      // aggregate. Without it they are three operations for the outbox and ONE message
      // for the cluster, and the two which lose are lost silently. It is absent if the
      // correlation was planned outside any activation. So the id which a REST endpoint
      // produces stays exactly what it was
      command = command
          .messageId(
              messageIdOf(request.workflowModuleId(), request.bpmnProcessId(), request.workflowAggregateId(),
                  request.messageName(), request.correlationId(),
                  request.activationId()));
    }
    final var timeToLive = messageTimeToLiveFor(
        request.workflowModuleId(),
        processWaitingForTheMessage(request.workflowModuleId(), request.bpmnProcessId(), request.messageName()),
        request.messageName());
    if (timeToLive != null) {
      // per message, because the number buffers AND deduplicates, and those two want it
      // to go in opposite directions. If nothing is configured, nothing is set. Then the
      // default of the client applies, as it always did
      command = command.timeToLive(timeToLive);
    }
    try {
      command
          .send()
          .join();
      log.info(
          "Camunda8[{}]: published message '{}' (correlation key '{}') for BPMN process '{}' of "
              + "workflow module '{}'",
          adapterId,
          request.messageName(),
          correlationKey,
          request.bpmnProcessId(),
          request.workflowModuleId());
    } catch (final Exception e) {
      if (!Camunda8Errors.messageAlreadyPublished(e)) {
        throw e;
      }
      // the engine deduplicated by messageId. Which of the two cases it was cannot be
      // told from here. The entry counts as consumed either way, because repeating the
      // publish would be refused again
      log.warn(
          """
              Camunda8[{}]: the cluster refused message '{}' (correlation key '{}') for BPMN process \
              '{}' of workflow module '{}' because a message of the same id was published before, \
              within the message time-to-live. Either this dispatch is a repetition of one which \
              reached the cluster already - then nothing is lost - or it is a second, legitimate \
              correlation of the same message name and correlation id for this aggregate, and the \
              workflow will never see it. The entry counts as done in both cases. This net is the \
              cluster's own and lasts for the message time-to-live \
              ('vanillabp.adapters.<id>.message-time-to-live', resolvable down to the single \
              message); a scope which repeats within it has to vary the correlation id, unless the \
              repetitions are separate activations of a BPMN element, which the message id already \
              tells apart.""",
          adapterId,
          request.messageName(),
          correlationKey,
          request.bpmnProcessId(),
          request.workflowModuleId());
    }

  }

  /**
   * The BPMN process whose model waits for a message, which is the process a time-to-live
   * configured for that message belongs to.
   * <p>
   * A process service belongs to the process at the top, so the correlation names that
   * process. But the event which waits may sit in a called process. Whoever configures the
   * time-to-live of that message writes it under the called process
   * (<code>workflows.&lt;called process&gt;.messages.&lt;message&gt;</code>). The cluster does
   * not say which subscription will take a message, and it may take one which is published
   * before anything waits. So the deployed models answer. It is the process of the call, if
   * its own model waits for the message. Otherwise it is the one other process of the
   * workflow module whose model does. If none does, or several others do, the process of
   * the call stays the answer, as before. See decision 77 in the repository's DECISIONS.md.
   *
   * @param workflowModuleId The workflow module of the correlation
   * @param bpmnProcessId The BPMN process of the call
   * @param messageName The message name as the application wrote it
   * @return The plain id of the process the time-to-live is read for
   */
  private String processWaitingForTheMessage(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String messageName) {

    final var scopedMessageName = scopedIdentifier(workflowModuleId, messageName);
    final var waiting = clientFactory
        .getDeployedProcesses()
        .ofWorkflowModule(workflowModuleId)
        .stream()
        .filter(deployed -> waitsForTheMessage(
            deployed.model(),
            scopedProcessId(workflowModuleId, deployed.bpmnProcessId()),
            scopedMessageName))
        .map(io.vanillabp.camunda8.deployment.Camunda8DeployedProcesses.DeployedProcess::bpmnProcessId)
        .distinct()
        .toList();
    if (waiting.contains(bpmnProcessId) || (waiting.size() != 1)) {
      return bpmnProcessId;
    }
    return waiting.getFirst();

  }

  /**
   * Whether one process of a model waits for a message. That is a catch event or a receive
   * task of that process which names it. A file may carry several processes, and only the
   * elements of the given one count.
   *
   * @param model The model as deployed
   * @param scopedProcessId The process as the cluster knows it
   * @param scopedMessageName The message as the cluster knows it
   * @return Whether that process waits for the message
   */
  private static boolean waitsForTheMessage(
      final BpmnModelInstance model,
      final String scopedProcessId,
      final String scopedMessageName) {

    final java.util.function.Predicate<org.camunda.bpm.model.xml.instance.ModelElementInstance> inTheProcess = element -> {
      var current = element.getParentElement();
      while (current != null) {
        if (current instanceof Process process) {
          return scopedProcessId.equals(process.getId());
        }
        current = current.getParentElement();
      }
      return false;
    };
    final var byAnEvent = model
        .getModelElementsByType(io.camunda.zeebe.model.bpmn.instance.MessageEventDefinition.class)
        .stream()
        .filter(definition -> definition.getMessage() != null)
        .filter(definition -> scopedMessageName.equals(definition.getMessage().getName()))
        .anyMatch(inTheProcess);
    return byAnEvent || model
        .getModelElementsByType(io.camunda.zeebe.model.bpmn.instance.ReceiveTask.class)
        .stream()
        .filter(receiveTask -> receiveTask.getMessage() != null)
        .filter(receiveTask -> scopedMessageName.equals(receiveTask.getMessage().getName()))
        .anyMatch(inTheProcess);

  }

  /**
   * The id which this adapter hands the cluster for a correlated message. The cluster
   * deduplicates by it for as long as the message lives.
   *
   * <h4>Why it looks like VanillaBP's own key and is not the same thing</h4>
   *
   * Both are built from the same values, and they guard different windows. The key of
   * VanillaBP deduplicates the entries which have not been dispatched yet. This one
   * deduplicates publications inside the message time-to-live. An operation can pass the
   * first net and be dropped by the second. That is why the adapter says so when the
   * cluster refuses a publication.
   *
   * <h4>Why the activation is part of it</h4>
   *
   * A called process is a secondary workflow of the SAME aggregate. So the three elements
   * of a multi-instance call activity agree in module, process, aggregate, message name
   * and correlation id, if that comes from business data. Without the activation they are
   * three operations for the outbox and ONE message for the cluster.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process
   * @param workflowAggregateId The workflow aggregate's id
   * @param messageName The message name as the application wrote it
   * @param correlationId The correlation id (never <code>null</code> here - without one
   *          nothing is deduplicated and no id is sent)
   * @param activationId The activation the correlation was planned in, or
   *          <code>null</code> where it was planned outside any
   * @return The message id
   */
  static String messageIdOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId,
      final String messageName,
      final String correlationId,
      final String activationId) {

    final var withoutActivation = "%s|%s|%s|%s|%s"
        .formatted(workflowModuleId, bpmnProcessId, workflowAggregateId, messageName, correlationId);
    return activationId == null
        ? withoutActivation
        : "%s|%s".formatted(withoutActivation, activationId);

  }

  private void preflightStartByMessage(
      final PhaseOneRequest<A> request) {

    clientFactory.validateConfigured();

  }

  /**
   * A remote BPMS must not act before the transaction of the caller committed. So phase
   * one does nothing. The broadcast happens in phase two, through the outbox.
   */
  private void preflightSendSignal(
      final PhaseOneRequest<A> request) {

  }

  private void sendSignal(
      final PhaseTwoRequest<A> request) {

    // no variables travel with a signal, and there is nothing to deduplicate by. Unlike
    // a message, a broadcast carries no correlation key from which the cluster could
    // recognize a redelivery (documented, the rest of at-least-once)
    var command = clientFactory
        .getClient()
        .newBroadcastSignalCommand()
        .signalName(scopedIdentifier(request.workflowModuleId(), request.signalName()));
    final var signalTenantId = tenantIdOf(request.workflowModuleId());
    if (signalTenantId != null) {
      command = command.tenantId(signalTenantId);
    }
    command
        .send()
        .join();
    log.info(
        "Camunda8[{}]: broadcast signal '{}' of workflow module '{}'",
        adapterId,
        request.signalName(),
        request.workflowModuleId());

  }

  private void preflightAggregateChanged(
      final PhaseOneRequest<A> request) {

    // a remote BPMS. Writing here would show the cluster values of a transaction
    // which may still roll back. The push happens in phase two

  }

  private void pushChangedAggregate(
      final PhaseTwoRequest<A> request) {

    final var variables = variablesOf(request.aggregatePersistence(), request.workflowAggregateId());

    if (request.taskId() == null) {
      final var keyOfTheStartRow = pushedIntoTheInstanceOfTheStartRow(request, variables);
      if (keyOfTheStartRow != null) {
        pushIntoTheCalledInstances(request, keyOfTheStartRow, variables);
        return;
      }
      final var processInstanceKey = processInstanceKeyOf(
          WorkflowScope.of(request.workflowModuleId(), request.bpmnProcessId()),
          request.aggregatePersistence(),
          request.workflowAggregateId());
      if (processInstanceKey == null) {
        // what remains of at-least-once. The workflow ended between the election at
        // dispatch time and now, so there is nothing left to write to
        log.warn(
            "Camunda8[{}]: no active workflow found for aggregate '{}' - skipping the push of the "
                + "changed aggregate",
            adapterId,
            request.workflowAggregateId());
        return;
      }
      clientFactory
          .getClient()
          .newSetVariablesCommand(processInstanceKey)
          .variables(variables)
          // the workflow's own scope, which is what a gateway behind the current
          // element and every other branch reads
          .local(false)
          .send()
          .join();
      log.info(
          "Camunda8[{}]: pushed the changed aggregate '{}' into process instance '{}'",
          adapterId,
          request.workflowAggregateId(),
          processInstanceKey);
      pushIntoTheCalledInstances(request, processInstanceKey, variables);
      return;
    }

    final var taskRow = request.taskRecord();
    final var place = whereTheTaskRuns(request.workflowModuleId(), taskRow);
    final var elementInstanceKey = place.processInstanceKey() != null
        ? place.processInstanceKey()
        : flowScopeKeyOf(request.taskId(), taskRow, place);
    if (elementInstanceKey == null) {
      // the read model does not know the scope. That says nothing about whether the task
      // is still there. An exporter which stands still knows no task created after it
      // stopped. The engine is asked only about a task which this adapter left open,
      // because the question shortens the lock of a job a handler may hold right now.
      // Without that row, the push waits for the read model, which knows a completed task
      // as well (see decision 64 in the repository's DECISIONS.md)
      if (!theAdapterLeftTheTaskOpen(taskRow)) {
        throw scopeNotVisibleYet(request, false);
      }
      if (theEngineStillHoldsTheTask(request.taskId(), taskRow)) {
        throw scopeNotVisibleYet(request, true);
      }
      log.warn(
          "Camunda8[{}]: task '{}' of aggregate '{}' is completed - skipping the push of the "
              + "changed aggregate. The query API does not report the scope of the task, "
              + "and the cluster answered that it holds no such task any more. The "
              + "workflow's own scope is deliberately NOT written instead - it is read by every "
              + "branch, and the task asked for its own scope",
          adapterId,
          request.taskId(),
          request.workflowAggregateId());
      return;
    }
    try {
      clientFactory
          .getClient()
          .newSetVariablesCommand(elementInstanceKey)
          .variables(variables)
          // the scope the task RUNS IN. A write for the whole workflow would be a lost
          // update between the iterations of a multi-instance subprocess
          .local(true)
          .send()
          .join();
    } catch (final Exception e) {
      if (!Camunda8Errors.notFound(e)) {
        throw e;
      }
      // the engine answers a command. So this 404 does not come from a read model which
      // is behind. The scope ended between the search and the write, together with the
      // task. Repeating would get the same answer until the entry is blocked
      log.warn(
          "Camunda8[{}]: the scope '{}' of task '{}' of aggregate '{}' ended before the changed "
              + "aggregate could be written into it - skipping the push. The cluster refused it with {}",
          adapterId,
          elementInstanceKey,
          request.taskId(),
          request.workflowAggregateId(),
          Camunda8Errors.rejection(e));
      return;
    }
    log.info(
        "Camunda8[{}]: pushed the changed aggregate '{}' into element instance '{}' (task '{}')",
        adapterId,
        request.workflowAggregateId(),
        elementInstanceKey,
        request.taskId());

  }

  /**
   * Pushes the changed aggregate into the process instance the start row of the workflow
   * names, without asking the query API which instance carries the aggregate.
   * <p>
   * The core hands in that key as {@link PhaseTwoRequest#workflowId()} only if the start
   * row names THIS adapter. So the key belongs to an instance which this adapter created. The
   * engine answers a command by key. So the push no longer depends on an exporter which may
   * stand still. The search would not find a workflow started after the exporter stopped, and
   * then the push used to be skipped. A failing read model may hold an application up, but
   * must not lose its work, see decision 64 in the repository's DECISIONS.md.
   * <p>
   * The key is not used on a cluster which other adapter ids share. An instance key says
   * nothing about which adapter id deployed the process. The search below keeps one half of
   * a migration from writing into the instance of the other half. A key which is not a
   * number belongs to another BPMS and is not used either.
   * <p>
   * A <code>404</code> means that the engine holds no such instance any more. The workflow
   * ended, or the row is about an instance which is gone. So the caller falls back to the
   * search, which then reports what it finds.
   *
   * @param request The push
   * @param variables The values to write
   * @return The process instance the values were written into, <code>null</code> if the
   *         caller has to search
   */
  private Long pushedIntoTheInstanceOfTheStartRow(
      final PhaseTwoRequest<A> request,
      final Map<String, Object> variables) {

    final var workflowId = request.workflowId();
    if ((workflowId == null) || workflowId.isBlank() || clientFactory.sharesItsCluster()) {
      return null;
    }
    final long processInstanceKey;
    try {
      processInstanceKey = Long.parseLong(workflowId);
    } catch (final NumberFormatException e) {
      return null;
    }
    try {
      clientFactory
          .getClient()
          .newSetVariablesCommand(processInstanceKey)
          .variables(variables)
          // the workflow's own scope, which is what a gateway behind the current
          // element and every other branch reads
          .local(false)
          .send()
          .join();
    } catch (final Exception e) {
      if (!Camunda8Errors.notFound(e)) {
        throw e;
      }
      log
          .debug(
              "Camunda8[{}]: the engine holds no process instance '{}' (named by the start row of "
                  + "aggregate '{}') any more - searching for the workflow instead",
              adapterId,
              processInstanceKey,
              request.workflowAggregateId());
      return null;
    }
    log
        .info(
            "Camunda8[{}]: pushed the changed aggregate '{}' into process instance '{}'",
            adapterId,
            request.workflowAggregateId(),
            processInstanceKey);
    return processInstanceKey;

  }

  /**
   * Pushes the changed aggregate into every active called instance below a process instance
   * which continues its aggregate, at any depth.
   * <p>
   * A called instance gets a copy of the variables of the caller when the call activity
   * starts it. It gets nothing which the caller is told later. So a gateway in a called
   * process decided on the values at the moment of the call, however often the aggregate was
   * pushed since. A called instance continues the aggregate if it carries the id of the
   * aggregate. The propagation of the call activity hands over that variable. The walk stops
   * at a called process which does not carry it. What that one calls is not the business of
   * this aggregate.
   * <p>
   * The called instances are found through the query API. A called instance which the
   * exporter has not reported yet is not found, and keeps what it was given at the call. See
   * decision 77 in the repository's DECISIONS.md.
   *
   * @param request The push
   * @param processInstanceKey The instance at the top, which was written already
   * @param variables The values to write
   */
  private void pushIntoTheCalledInstances(
      final PhaseTwoRequest<A> request,
      final long processInstanceKey,
      final Map<String, Object> variables) {

    final var idName = aggregateIdVariableName(request.aggregatePersistence());
    var callers = List.of(processInstanceKey);
    while (!callers.isEmpty()) {
      final var called = new java.util.ArrayList<Long>();
      for (final var caller : callers) {
        clientFactory
            .getClient()
            .newProcessInstanceSearchRequest()
            .filter(filter -> {
              filter.state(ProcessInstanceState.ACTIVE);
              filter.parentProcessInstanceKey(caller);
              Camunda8Searches.byAggregateId(filter, idName, request.workflowAggregateId());
            })
            .send()
            .join()
            .items()
            .forEach(instance -> called.add(instance.getProcessInstanceKey()));
      }
      for (final var calledInstanceKey : called) {
        try {
          clientFactory
              .getClient()
              .newSetVariablesCommand(calledInstanceKey)
              .variables(variables)
              .local(false)
              .send()
              .join();
        } catch (final Exception e) {
          if (!Camunda8Errors.notFound(e)) {
            throw e;
          }
          // the called instance ended between the search and the write. So there is
          // nothing in it to tell
          continue;
        }
        log
            .info(
                "Camunda8[{}]: pushed the changed aggregate '{}' into called process instance '{}'",
                adapterId,
                request.workflowAggregateId(),
                calledInstanceKey);
      }
      callers = called;
    }

  }

  /**
   * Whether the row of a task says that this adapter left the task open, and the application
   * has not closed it since. Such a task RESTS. No handler holds its job, so asking the engine
   * about it disturbs nobody. A user task always rests. A service task rests if its method
   * asked for the task id and left the completion to the application. Why the engine is
   * asked only about such a task is decision 64 in the repository's DECISIONS.md.
   *
   * @param taskRow The row of the task, or <code>null</code> where there is none
   * @return Whether the task rests
   */
  private static boolean theAdapterLeftTheTaskOpen(
      final TaskDelivery taskRow) {

    return (taskRow != null) && (taskRow.taskClosedAt() == null);

  }

  /**
   * Whether the row of a task names a user task, whose id is a user-task key and no job key.
   *
   * @param taskRow The row of the task, or <code>null</code> where there is none
   * @return Whether the row names a user task
   */
  private static boolean isAUserTask(
      final TaskDelivery taskRow) {

    return (taskRow != null) && TaskKind.USER_TASK.name().equals(taskRow.taskKind());

  }

  /**
   * Asks the ENGINE whether a task which this adapter left open is still there. The read
   * model cannot answer that. It knows nothing which happened after its exporter stopped.
   * <p>
   * A user task is asked with the empty <code>UpdateUserTask</code> which the awareness probe
   * of a user task sends. A service task is asked with an <code>UpdateJobTimeout</code> on
   * the job key, the command which the awareness probe of a task sends. Its only effect is
   * that the lock of an activated job is set to <code>async-task-lock-renewal</code>. The
   * renewal of a job which the adapter left open does that anyway. That is why the caller
   * asks only about a task which rests. The lock of a job which a handler holds right now
   * would be cut short.
   * <p>
   * A <code>404</code> means that the cluster holds no such task any more. Any other refusal
   * is a refusal about a task the cluster HAS. A failure which is no answer of the cluster at
   * all, an outage, is thrown. The outbox repeats it like every other outage.
   *
   * @param taskId The task ID reported to the application
   * @param taskRow The row of the task, which says its kind
   * @return Whether the engine still holds the task
   */
  private boolean theEngineStillHoldsTheTask(
      final String taskId,
      final TaskDelivery taskRow) {

    try {
      if (isAUserTask(taskRow)) {
        updateUserTask(taskId);
      } else {
        updateJobTimeout(taskId);
      }
      return true;
    } catch (final Exception e) {
      if (Camunda8Errors.jobAlreadyGone(e)) {
        return false;
      }
      if (isAUserTask(taskRow)
          ? Camunda8Errors.refusedAboutAUserTaskItHolds(e)
          : Camunda8Errors.jobIsThereButNotActive(e) || Camunda8Errors.jobHeldByAnotherActivation(e)) {
        return true;
      }
      throw e;
    }

  }

  /**
   * What a task-scoped push throws while the read model does not report the scope of its task
   * yet. The entry is worth repeating. The visibility window is the time which this adapter
   * says its read model may need. If no window is configured, the failure is an ordinary one,
   * and the outbox repeats it with its own backoff. A window of zero would bring the entry
   * back at once.
   *
   * @param request The push
   * @param theEngineHoldsTheTask Whether the engine said that it holds the task. If it was
   *          not asked, the message says what is waited for instead
   * @return The failure to throw
   */
  private RuntimeException scopeNotVisibleYet(
      final PhaseTwoRequest<A> request,
      final boolean theEngineHoldsTheTask) {

    final var delay = workflowVisibilityDelay();
    final var message = theEngineHoldsTheTask
        ? """
            Camunda8[%s]: the cluster still holds task '%s' of aggregate '%s', but its query API does \
            not report the scope the task runs in. The push of the changed aggregate is repeated. If \
            this does not stop, look at the exporter of the cluster first: a read model which stopped \
            catching up knows no task created after it stopped."""
            .formatted(adapterId, request.taskId(), request.workflowAggregateId())
        : """
            Camunda8[%s]: the query API of the cluster does not report task '%s' of aggregate '%s' \
            or the scope it runs in. The push of the changed aggregate is repeated until it does. \
            The engine is not asked, because no row of the delivery log says that the task rests, \
            and asking would cut short the lock of a job a handler may hold. If this does not \
            stop, look at the exporter of the cluster first, and then at the task id the \
            application passed."""
            .formatted(adapterId, request.taskId(), request.workflowAggregateId());
    return delay.isWaiting()
        ? new PhaseTwoRetryLater(message, delay.window())
        : new IllegalStateException(message);

  }

  /**
   * The key of the ACTIVE process instance carrying the aggregate's ID variable -
   * VanillaBP finds a workflow by that variable, so the eventually-consistent query API answers
   * (like {@link #awarenessOfWorkflow}).
   *
   * @param workflowAggregateId The aggregate's ID
   * @return The process instance key or <code>null</code> if none is active
   */
  private Long processInstanceKeyOf(
      final WorkflowScope scope,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    // a failing search is not caught here, on purpose. The cluster of the adapter can be
    // searched, because the deployment refuses one which cannot. So a failure is an
    // outage. The outbox entry behind the push is retried for exactly such an outage
    final var found = clientFactory
        .getClient()
        .newProcessInstanceSearchRequest()
        .filter(filter -> {
          filter.state(ProcessInstanceState.ACTIVE);
          Camunda8Searches
              .byAggregateId(filter, aggregateIdVariableName(aggregatePersistence), workflowAggregateId);
        })
        .send()
        .join();
    // writing into the instance of ANOTHER adapter id of this cluster would put the
    // values of one half of a migration into the other half
    return found
        .items()
        .stream()
        .filter(instance -> isInScope(scope, instance.getTenantId(), instance.getProcessDefinitionId()))
        .findFirst()
        .map(instance -> instance.getProcessInstanceKey())
        .orElse(null);

  }

  /**
   * The element instance of the scope the task RUNS IN: the process instance, an
   * embedded subprocess, or the one iteration of a multi-instance embedded subprocess
   * it belongs to.
   * <p>
   * Not the element instance of the task itself. In Camunda 8 every element instance is a
   * variable scope of its own. The one which belongs to a task disappears with the task, so
   * nothing would read values written there. The rest of the scope AROUND the task reads
   * them.
   * <p>
   * A user task which runs directly in one iteration of a multi-instance subprocess is
   * answered by one search. The variable which holds the index of that iteration is a local
   * variable of the iteration. And the variables which a user task sees are reported
   * together with their scope. Every other task is found as an element instance. Camunda 8
   * reports no parent for an element instance. So its scope is found by walking DOWN from the
   * process instance until the element instance of the task shows up. The query API filters
   * element instances by their scope. A multi-instance BODY on the way is skipped. It is the
   * technical wrapper around the instances, not a scope of the model.
   * <p>
   * An exporter fills the query API, so the task of this push may not be reported yet. The
   * search is NOT repeated here. This runs in phase two, and phase two does not wait. If the
   * scope is unknown, the caller asks the engine, or gives the entry back to the outbox with
   * a due time, and the outbox brings it back then. Waiting here would hold a dispatching
   * thread and a database connection for the whole window. A scope which is unknown yields
   * <code>null</code>, and the caller then decides whether the engine may be asked. The
   * process instance is NOT used instead. Writing there is exactly the lost update between
   * the iterations of a multi-instance subprocess which this scoping is there to prevent.
   *
   * @param taskId The task ID reported to the application
   * @param taskRow The row of the task, or <code>null</code> where there is none
   * @param place What the row and the deployed model say about where the task runs
   * @return The element instance key to write at, or <code>null</code> if the query
   *         API knows neither the task nor its scope (yet)
   */
  private Long flowScopeKeyOf(
      final String taskId,
      final TaskDelivery taskRow,
      final WhereTheTaskRuns place) {

    if ((place.iterationIndexVariable() != null) && isAUserTask(taskRow)) {
      final var iteration = iterationOfTheUserTask(taskId, place.iterationIndexVariable());
      if (iteration != null) {
        return iteration;
      }
    }
    final var task = elementInstanceOfTheTask(taskId, taskRow);
    if (task == null) {
      return null;
    }
    final var scopes = scopePathOf(task.processInstanceKey(), task.elementInstanceKey());
    // innermost first: the scope which holds the task, then the scopes around it
    for (final var scope : scopes) {
      if (scope.type() != ElementInstanceType.MULTI_INSTANCE_BODY) {
        return scope.key();
      }
    }
    return null;

  }

  /**
   * Where a task runs, as far as its row and the deployed model can say it without a search.
   *
   * @param processInstanceKey The process instance, where the task runs directly in it, otherwise
   *          <code>null</code>
   * @param iterationIndexVariable The variable holding the index of the iteration, where the task
   *          runs directly in one iteration of a multi-instance subprocess, otherwise
   *          <code>null</code>
   */
  private record WhereTheTaskRuns(Long processInstanceKey, String iterationIndexVariable) {

    /** Nothing is known, so the scope is searched for. */
    static final WhereTheTaskRuns UNKNOWN = new WhereTheTaskRuns(null, null);

  }

  /**
   * Where a task runs, read from the row of the task and the model of its process.
   * <p>
   * The row names the process instance of the task, its element, and the version of its
   * process. If that version is the one which this application deployed, the model says what
   * encloses the element. An element directly in its process runs in the process instance.
   * The push can write there without asking the query API, so it works while the exporter
   * stands still. An element directly in a multi-instance subprocess runs in one iteration,
   * and only the query API knows the key of that iteration. Everything else, and every
   * version this application did not deploy, is searched for as before, because an older
   * model may enclose the element differently. Why the row is used and not a search is
   * decision 64 in the repository's DECISIONS.md.
   *
   * @param workflowModuleId The workflow module of the push
   * @param taskRow The row of the task, or <code>null</code> where there is none
   * @return Where the task runs, {@link WhereTheTaskRuns#UNKNOWN} if the row and the model
   *         do not say
   */
  private WhereTheTaskRuns whereTheTaskRuns(
      final String workflowModuleId,
      final TaskDelivery taskRow) {

    if ((taskRow == null) || (taskRow.workflowId() == null) || (taskRow.bpmnElementId() == null) || (taskRow
        .bpmnProcessId() == null) || (taskRow.processVersion() == null)) {
      return WhereTheTaskRuns.UNKNOWN;
    }
    final var deployed = clientFactory
        .getDeployedProcesses()
        .deployedVersionOf(workflowModuleId, taskRow.bpmnProcessId());
    if ((deployed == null) || !String.valueOf(deployed.version()).equals(taskRow.processVersion())) {
      return WhereTheTaskRuns.UNKNOWN;
    }
    if (!(deployed.model().getModelElementById(taskRow.bpmnElementId()) instanceof FlowNode task)) {
      return WhereTheTaskRuns.UNKNOWN;
    }
    final var enclosing = task.getParentElement();
    if (enclosing instanceof Process) {
      try {
        return new WhereTheTaskRuns(Long.valueOf(taskRow.workflowId()), null);
      } catch (final NumberFormatException e) {
        return WhereTheTaskRuns.UNKNOWN;
      }
    }
    // an ad-hoc subprocess runs its elements in an inner instance of its own. So its
    // iteration is not the scope of the task
    if ((enclosing instanceof SubProcess subProcess) && !(enclosing instanceof AdHocSubProcess) && (subProcess
        .getLoopCharacteristics() instanceof MultiInstanceLoopCharacteristics)) {
      return new WhereTheTaskRuns(null, Camunda8MultiInstance.indexVariableOf(subProcess.getId()));
    }
    return WhereTheTaskRuns.UNKNOWN;

  }

  /**
   * The iteration a user task runs in, read from the variables which the user task sees. The
   * index variable of the iteration is local to it. So the scope which the cluster reports for
   * that variable is the iteration.
   *
   * @param taskId The user-task key
   * @param indexVariable The variable holding the index of the enclosing iteration
   * @return The element instance key of the iteration, or <code>null</code> if the query API
   *         does not report it (yet)
   */
  private Long iterationOfTheUserTask(
      final String taskId,
      final String indexVariable) {

    final var found = clientFactory
        .getClient()
        .newUserTaskVariableSearchRequest(taskKeyOf(taskId))
        .filter(filter -> filter.name(indexVariable))
        .send()
        .join()
        .items();
    return found.isEmpty()
        ? null
        : found.getFirst().getScopeKey();

  }

  /**
   * A task as the query API knows it.
   *
   * @param processInstanceKey The process instance the task belongs to
   * @param elementInstanceKey The element instance of the task itself
   */
  private record TaskInstance(Long processInstanceKey, Long elementInstanceKey) {
  }

  /**
   * The element instance of a task. It comes from the job behind a job key, or from the user
   * task behind a user-task key. If no row says which of the two the id is, the job is looked
   * for first. Both kinds of key are unique within a cluster. So if the first search found
   * nothing, the second search cannot find the task of somebody else.
   *
   * @param taskId The task ID reported to the application
   * @param taskRow The row of the task, or <code>null</code> where there is none
   * @return The task, or <code>null</code> if the query API does not know it (yet)
   */
  private TaskInstance elementInstanceOfTheTask(
      final String taskId,
      final TaskDelivery taskRow) {

    if (!isAUserTask(taskRow)) {
      final var job = jobOf(taskId);
      if (job != null) {
        return new TaskInstance(job.getProcessInstanceKey(), job.getElementInstanceKey());
      }
      if (taskRow != null) {
        return null;
      }
    }
    final var found = clientFactory
        .getClient()
        .newUserTaskSearchRequest()
        .filter(filter -> filter.userTaskKey(taskKeyOf(taskId)))
        .send()
        .join()
        .items();
    return found.isEmpty()
        ? null
        : new TaskInstance(found.getFirst().getProcessInstanceKey(), found.getFirst().getElementInstanceKey());

  }

  /**
   * A scope on the way from the process instance down to an element instance.
   *
   * @param key The element instance key of the scope
   * @param type What kind of element it is
   */
  private record Scope(Long key, ElementInstanceType type) {
  }

  /**
   * The scopes which contain the given element instance, innermost first. It walks down
   * from the process instance, because Camunda 8 reports the children of a scope, but
   * never its parent.
   *
   * @param processInstanceKey The workflow's process instance
   * @param elementInstanceKey The element instance to find
   * @return The containing scopes, innermost first (empty if the element instance was
   *         not found below the process instance)
   */
  private List<Scope> scopePathOf(
      final Long processInstanceKey,
      final Long elementInstanceKey) {

    final var path = new LinkedList<Scope>();
    if ((processInstanceKey == null) || (elementInstanceKey == null)) {
      return path;
    }
    if (findScopePath(
        new Scope(processInstanceKey, ElementInstanceType.PROCESS),
        elementInstanceKey,
        path,
        0)) {
      return path;
    }
    return path;

  }

  /**
   * Depth-first walk down the scope hierarchy. It collects the scopes which contain the
   * wanted element instance.
   *
   * @param scope The scope to look below
   * @param elementInstanceKey The element instance to find
   * @param path Filled with the containing scopes, innermost first
   * @param depth The current nesting depth (bounded - a BPMN model is not a graph)
   * @return Whether the element instance was found below this scope
   */
  private boolean findScopePath(
      final Scope scope,
      final Long elementInstanceKey,
      final LinkedList<Scope> path,
      final int depth) {

    if (depth > MAX_SCOPE_DEPTH) {
      return false;
    }
    // every child of the scope, not only the first page. A multi-instance over 100 items
    // puts more children into one scope than the default page holds. Then the task of the
    // 101st iteration would be a task below no scope at all. Reading stops on the page
    // which holds the wanted element instance, because the children after it are not
    // walked into either
    final var read = Camunda8SearchPages
        .pagesUntil(
            child -> elementInstanceKey.equals(child.getElementInstanceKey()),
            Camunda8SearchPages.MAX_PAGES,
            cursor -> clientFactory
                .getClient()
                .newElementInstanceSearchRequest()
                .filter(filter -> filter.elementInstanceScopeKey(scope.key()))
                .page(page -> {
                  page.limit(Integer.valueOf(Camunda8SearchPages.PAGE_SIZE));
                  if (cursor != null) {
                    page.after(cursor);
                  }
                })
                .send()
                .join());
    final var children = read.items();
    for (final var child : children) {
      if (elementInstanceKey.equals(child.getElementInstanceKey())) {
        path.add(scope);
        return true;
      }
    }
    if (read.theClusterHadMore()) {
      log.warn(
          "Camunda8[{}]: stopped after {} children of element instance '{}' while looking for the scope "
              + "of element instance '{}', so that scope is reported as unknown although the cluster may "
              + "hold it",
          adapterId,
          Integer.valueOf(children.size()),
          scope.key(),
          elementInstanceKey);
    }
    for (final var child : children) {
      if (findScopePath(
          new Scope(child.getElementInstanceKey(), child.getType()),
          elementInstanceKey,
          path,
          depth + 1)) {
        path.add(scope);
        return true;
      }
    }
    return false;

  }

  /**
   * The job behind a task ID. A VanillaBP task ID IS the key of the job. The job knows
   * which element instance and which process instance it belongs to.
   *
   * @param taskId The task ID reported to the application
   * @return The job or <code>null</code> if the query API does not know it
   */
  private Job jobOf(
      final String taskId) {

    final var found = clientFactory
        .getClient()
        .newJobSearchRequest()
        .filter(filter -> filter.jobKey(taskKeyOf(taskId)))
        .send()
        .join();
    return found.items().isEmpty()
        ? null
        : found.items().getFirst();

  }

  private void startWorkflowByMessage(
      final PhaseTwoRequest<A> request) {

    // message START events ignore the correlation key. The aggregate-ID variable is the
    // ONLY variable published. It is the same technical field which a regular start sets,
    // not message content. messageId is built from the same values as the idempotency key
    // of the start. So the engine deduplicates a redelivered dispatch within the message
    // TTL. That is a net of the cluster, next to the net of the outbox.
    try {
      var startCommand = clientFactory
          .getClient()
          .newPublishMessageCommand()
          .messageName(scopedIdentifier(request.workflowModuleId(), request.messageName()))
          .correlationKey("")
          .messageId(
              "%s|%s|%s".formatted(request.workflowModuleId(), request.bpmnProcessId(), request.workflowAggregateId()))
          .variables(variablesOf(request.aggregatePersistence(), request.workflowAggregateId()));
      final var startTenantId = tenantIdOf(request.workflowModuleId());
      if (startTenantId != null) {
        startCommand = startCommand.tenantId(startTenantId);
      }
      // the ADAPTER level only, on purpose. This message starts a workflow, so its
      // deduplication should last as long as possible. The subscription of a message START
      // event exists as long as the process is deployed, so the buffering half of the
      // number does not apply here at all. An override per message, meant for a repeating
      // catch event, must not shorten the protection against a workflow started twice
      final var startTimeToLive = clientFactory.getConfiguration().getMessageTimeToLive();
      if (startTimeToLive != null) {
        startCommand = startCommand.timeToLive(startTimeToLive);
      }
      // nothing is reported to request.reportStartedWorkflow here. The answer to a published
      // message carries the key of the message, not of the instance it started. The key is
      // still written down by the worker of the start event. It reports the key as the
      // native instance id of the start it was handed
      startCommand
          .send()
          .join();
      log.info(
          "Camunda8[{}]: published start message '{}' for BPMN process '{}' of workflow module "
              + "'{}' (aggregate '{}')",
          adapterId,
          request.messageName(),
          request.bpmnProcessId(),
          request.workflowModuleId(),
          request.workflowAggregateId());
    } catch (final Exception e) {
      if (!Camunda8Errors.messageAlreadyPublished(e)) {
        throw e;
      }
      // a start is different from a correlation. A workflow is started at most once per
      // aggregate anyway. So a refused start message says that the start happened, and this
      // stays an INFO, not a warning about something which may be lost
      log.info(
          "Camunda8[{}]: the cluster refused the start message '{}' for aggregate '{}' because a "
              + "message of the same id was published before, within the message time-to-live - the "
              + "workflow was started already and the entry counts as done",
          adapterId,
          request.messageName(),
          request.workflowAggregateId());
    }

  }

  private void preflightStart(
      final PhaseOneRequest<A> request) {

    // the MigrationProcessService of the core checked the aggregate id once (not null,
    // not blank) before phase one is called

    // for starting a workflow, there is nothing to check against the cluster in phase
    // one. So this only checks that the adapter is configured. Phase one runs inside the
    // DB transaction of the caller. It must never advance the process, because that races
    // the local transaction. It MAY contact the cluster for checks which advance nothing
    // and abort the transaction early. That is not needed here. An unavailable cluster
    // only makes the phase-two start wait in the outbox until it is reachable again.
    clientFactory.validateConfigured();

    log.debug("Validated phase one of starting Camunda 8 workflow '{}' of workflow module '{}' "
        + "(adapter '{}')", request.bpmnProcessId(), request.workflowModuleId(), adapterId);

  }

  private void startWorkflow(
      final PhaseTwoRequest<A> request) {

    final var started = createProcessInstance(
        scopedProcessId(request.workflowModuleId(), request.bpmnProcessId()),
        variablesOf(request.aggregatePersistence(), request.workflowAggregateId()),
        request.workflowAggregateId(),
        tenantIdOf(request.workflowModuleId()));

    // the key in the same form as a task delivery reports its workflow id. A started
    // instance is always the root, so it is the instance the aggregate belongs to. Nobody
    // has to search the cluster for it while its search index still lags behind. The
    // version is the one of the process definition, counted the way a job reports it. So
    // the start and every later delivery of this workflow name the same version
    request
        .reportStartedWorkflow(
            String.valueOf(started.getProcessInstanceKey()),
            String.valueOf(started.getVersion()));

  }

  /**
   * Creates a Camunda 8 process instance of the latest version of the given BPMN process,
   * passing the given process variables, which carry the workflow aggregate's ID. How the aggregate's
   * ID is stored in the BPMS is the adapter's decision: Camunda 8 stores the aggregate as
   * process variables, so the variable carrying the ID is named after the aggregate's ID
   * property (see {@link AggregatePersistenceAware#getAggregateIdName()}). This is what
   * phase two of {@link PhaseOperation#START_WORKFLOW} does.
   * <p>
   * <b>Idempotency limitation:</b> a crash between a successful create and the removal of
   * the phase-two outbox entry can create the instance twice (at-least-once, duplicates
   * possible). That rest is accepted, and no fix is pending. A workflow is located by
   * asking, not by a persistent registry (decision 5 of the platform's DECISIONS.md). The
   * core makes the window smaller: it probes
   * {@link #awarenessOfWorkflowForRedispatch} before it dispatches a start again. What is
   * left is documented in the README of this repository under "Idempotency limitation".
   * No workaround on the side of Camunda 8 is attempted here.
   *
   * @param bpmnProcessId The BPMN process ID of the workflow to start
   * @param variables The variables the instance is created with
   * @param workflowAggregateId The workflow aggregate's ID (sent as a string variable)
   * @return The created process-instance event
   */
  public ProcessInstanceEvent createProcessInstance(
      final String bpmnProcessId,
      final Map<String, Object> variables,
      final Object workflowAggregateId) {

    return createProcessInstance(bpmnProcessId, variables, workflowAggregateId, tenantIdOf(null));

  }

  /**
   * Creates the instance in the given tenant. The name-clash-avoidance mode decides it:
   * the workflow module id under {@code by-adapter}, none under
   * {@code use-prefix}/{@code none}.
   *
   * @param bpmnProcessId The BPMN process ID AS THE CLUSTER KNOWS IT
   * @param variables The process variables
   * @param workflowAggregateId The workflow aggregate's ID (for logging)
   * @param tenantId The tenant or <code>null</code>
   * @return The created process-instance event
   */
  public ProcessInstanceEvent createProcessInstance(
      final String bpmnProcessId,
      final Map<String, Object> variables,
      final Object workflowAggregateId,
      final String tenantId) {

    final var client = clientFactory.getClient();
    var command = client
        .newCreateInstanceCommand()
        .bpmnProcessId(bpmnProcessId)
        .latestVersion()
        .variables(variables);

    if (tenantId != null && !tenantId.isBlank()) {
      command = command.tenantId(tenantId);
    }
    // the id of the aggregate in the field which Operate shows first, if the application
    // asked for that. Nothing of VanillaBP reads it back. On a line without the field,
    // nothing is sent, see decision 37 in the repository's DECISIONS.md
    command = Camunda8BusinessId
        .writeTo(
            command,
            clientFactory
                .getConfiguration()
                .businessIdOf(workflowAggregateId));

    final ProcessInstanceEvent event;
    try {
      event = command
          .send()
          .join();
    } catch (final RuntimeException e) {
      throw refusedForGood(e, bpmnProcessId, workflowAggregateId);
    }
    log.info("Started Camunda 8 workflow '{}' (adapter '{}', process-instance key {}) for aggregate '{}'",
        bpmnProcessId, adapterId, event.getProcessInstanceKey(), workflowAggregateId);
    return event;

  }

  /**
   * What a failed create is thrown as. It is the failure of the cluster itself, if another
   * attempt may still get through. It is a {@link Camunda8RefusedStart} if it may not.
   * <p>
   * The wrapping carries the OPERATION into the classification, which only sees the failure
   * (see {@link Camunda8RefusedStart}). An operator reads the message next to an outbox
   * entry which will not move again. It names both ways out, without guessing which of them
   * applies. The cluster says "no such process" with the same code, whether a workflow
   * module never reached it or reached another one.
   *
   * @param failure What the create command threw
   * @param bpmnProcessId The process id as the cluster knows it
   * @param workflowAggregateId Which aggregate waits for this workflow
   * @return What to throw
   */
  private RuntimeException refusedForGood(
      final RuntimeException failure,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    if (!Camunda8Errors.startRefusedForGood(failure)) {
      return failure;
    }
    return new Camunda8RefusedStart(
        ("Camunda 8 refused to start workflow '%s' for aggregate '%s' (adapter '%s'): %s. Every further "
            + "attempt is answered the same way, so this start is not repeated and its outbox entry is "
            + "blocked. Either this cluster does not hold the process - then deploy the workflow module "
            + "to the cluster this adapter is configured for - or its model has no plain start event, and "
            + "a workflow of it comes into being through the message or the timer the model names, not "
            + "through startWorkflow.")
            .formatted(bpmnProcessId, workflowAggregateId, adapterId, Camunda8Errors.rejection(failure)), failure);

  }


  /**
   * The viewer/history API. See {@link Camunda8WorkflowViewer} for the two data sources
   * (what this application version deployed, and the query API of the cluster) and for the
   * limits of consistency.
   */
  private volatile Camunda8WorkflowViewer viewer;

  private Camunda8WorkflowViewer viewer() {

    // built on first use: a field initializer runs before the constructor assigns the
    // final fields, so it would hand the viewer a client factory which is still null
    if (viewer == null) {
      viewer = new Camunda8WorkflowViewer(adapterId, clientFactory, this::scopedProcessId, this::tenantIdOf);
    }
    return viewer;

  }

  @Override
  public List<ProcessDefinition> getProcessDefinitions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String historyContext) {

    return viewer().getProcessDefinitions(
        workflowModuleId, bpmnProcessId, aggregateIdVariableName(aggregatePersistence), workflowAggregateId,
        historyContext);

  }

  @Override
  public InputStream getBpmnXml(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String processDefinitionId) {

    return viewer().getBpmnXml(processDefinitionId);

  }

  @Override
  public WorkflowHistory getWorkflowHistory(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String historyContext) {

    return viewer().getWorkflowHistory(
        workflowModuleId, bpmnProcessId, aggregateIdVariableName(aggregatePersistence), workflowAggregateId,
        historyContext);

  }

  /**
   * A VanillaBP task id turned into the key which Camunda 8 commands expect: the key of the
   * job for a service task, the key of the user task for a user task. It is always a decimal
   * number. That is why the failure is worth a message of its own.
   *
   * <h4>Why this exists</h4>
   *
   * VanillaBP 1 could hand out the same key in HEXADECIMAL
   * (<code>task-id-as-hex-string</code>, off by default). An application which switched it
   * on stored those ids in its own data: a user task it is holding, or a service task which
   * waits for its <code>completeTask</code>. Those ids outlive the upgrade. Version 2 has no
   * such setting and parses decimal numbers everywhere. So the developer got a bare
   * NumberFormatException. It named neither the setting which produced the number, nor the
   * fact that the operation is not retried. A task key which is not a number is a PERMANENT
   * phase-two failure, so the outbox entry is blocked after a single attempt.
   * <p>
   * The classification stays exactly as it was. The NumberFormatException travels as the
   * cause, and {@code Camunda8Errors.permanentFailure} walks the chain for it. Version 2 does
   * NOT accept hexadecimal ids again, on purpose. One form of a task id is simpler than two.
   * An application which has them in its data has a migration of its own, and the message
   * points at it.
   * <p>
   * Package-private, so the message can be asserted without a client.
   *
   * @param taskId The task id as the application knows it
   * @return The key
   */
  static long taskKeyOf(
      final String taskId) {

    try {
      return Long.parseLong(taskId);
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException(
          ("The task id '%s' is not a Camunda 8 task key! A task key is the decimal key of a job "
              + "respectively of a user task, and this operation is NOT retried, because the cluster "
              + "would refuse it the same way every time.%s")
              .formatted(taskId, looksHexadecimal(taskId)
                  ? " It does read like a HEXADECIMAL number, which is how VanillaBP 1 handed "
                      + "task ids out where 'task-id-as-hex-string' was switched on. Version 2 has no such "
                      + "setting and there is no configuration which makes it read them: the ids your "
                      + "application stored have to be converted to decimal."
                  : ""), e);
    }

  }

  /**
   * Whether a task id reads like one of the hexadecimal ids of version 1: not a decimal
   * number, but a valid hexadecimal one. It is a hint, not a claim, and the message says it
   * that way.
   *
   * @param taskId The task id which failed to parse
   * @return <code>true</code> if hexadecimal would have worked
   */
  private static boolean looksHexadecimal(
      final String taskId) {

    if (taskId == null) {
      return false;
    }
    try {
      Long.parseLong(taskId, 16);
      return true;
    } catch (final NumberFormatException e) {
      return false;
    }

  }

}
