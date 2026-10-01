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
import io.camunda.zeebe.model.bpmn.instance.Message;
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
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.TaskNotFoundException;
import io.vanillabp.spi.process.WorkflowHistory;
import lombok.extern.slf4j.Slf4j;

/**
 * Camunda 8 implementation of the {@link MigratableProcessService}. One instance is
 * created per configured adapter ID (not per adapter type).
 * <p>
 * Camunda 8 is a <b>remote</b>, eventually consistent BPMS: the engine cannot join the
 * application's local database transaction, so every operation which reaches the cluster
 * is routed through the core's outbox as a pair of phases. Which operations this adapter
 * serves, and what it does in each of their phases, is what {@link #phaseOperations()}
 * answers.
 * <ul>
 *   <li>Phase one runs inside the caller's transaction. It must never perform an action
 *       that <i>advances</i> the BPMN process (e.g. creating an instance) - that would
 *       race the still-uncommitted local transaction and, on rollback, leave a ghost
 *       workflow instance behind. It <i>may</i> contact the cluster for a non-advancing
 *       check whose only purpose is to abort the local transaction early when the
 *       phase-two action is already known to be impossible. Completing a service task
 *       does exactly that: it verifies the task still exists by extending its job worker
 *       timeout, and it does so from a pre-commit transaction synchronization, which
 *       keeps the window between the check and the phase-two action short and with it the
 *       number of stale outbox entries. Starting a workflow has nothing to check against
 *       the cluster - an unreachable cluster only makes the phase-two start wait in the
 *       outbox until it is reachable again - so its phase one resolves the aggregate ID
 *       and verifies the adapter is configured.</li>
 *   <li>Phase two runs after the commit and is where the cluster is changed, e.g.
 *       {@link #createProcessInstance(String, java.util.Map, Object)} for a start. It is
 *       dispatched at-least-once, so it has to survive being run a second time.</li>
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
   * The one-time job-lock extension applied by awareness probes and phase-one
   * checks (the same duration the job worker grants a dormant async task).
   */
  private final Duration asyncTaskLockRenewal;

  /**
   * Runs phase-one existence checks right before the commit of the workflow aggregate's
   * transaction (platform-supplied) - minimizes the window between check and
   * phase two. The platform resolves the runner of the aggregate, so a unit of work the
   * APPLICATION brought is the one hooked into.
   */
  private final PreCommitRegistrar preCommitRegistrar;

  /**
   * The core's sync model: which aggregate attributes are shared with
   * the cluster. Camunda 8 is REMOTE, so its default is {@link AggregateSyncMode#FULL} - a
   * BPMN expression can only see what VanillaBP pushed as a process variable. May be
   * <code>null</code> (tests): only the
   * technical aggregate-ID variable is written then.
   */
  private final WorkflowAggregateSync aggregateSync;

  /**
   * Builds the process service of one configured adapter id. The platform bean of Spring Boot
   * or Quarkus is the caller, once per adapter id it found in the configuration.
   * <p>
   * Written out rather than generated, because javadoc does not run Lombok: a generated
   * constructor is missing from the published documentation, which then advertises a no-arg
   * constructor this class has never had.
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
   * How often the probe is repeated while waiting - deliberately not configurable:
   * the window is what an operator may have to raise, the sampling rate is not.
   */
  private static final Duration WORKFLOW_VISIBILITY_PROBE_INTERVAL = Duration
      .ofMillis(250);

  /**
   * Camunda 8 answers awareness probes from its query API, which an exporter feeds
   * asynchronously: a workflow started moments ago exists in the engine and is not
   * searchable yet. The core waits this window out where it knows this cluster
   * holds the workflow, so the everyday "start a workflow, then correlate the
   * message which lets it continue" works without the application retrying.
   */
  @Override
  public WorkflowVisibilityDelay workflowVisibilityDelay() {

    return delayOf(clientFactory.getConfiguration().workflowVisibilityWindow());

  }

  /**
   * The same window asked for ONE workflow, which is what the core asks.
   * <p>
   * Two cases hide behind the long window, and the engine has already told this adapter
   * which of them it is in. A workflow which was just STARTED is the case the long window
   * exists for: the engine holds it and the read model needs up to ten seconds to hear
   * about it. A workflow the engine no longer holds ended long enough ago to be in that
   * read model, and what is still on its way there is only the END of it - measured in
   * hundreds of milliseconds on the newer lines and two seconds on the oldest one, which
   * is what {@code ended-workflow-visibility-timeout} covers.
   * <p>
   * Which of the two this workflow is in comes from the probe of
   * {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object, String)},
   * which the core ran on this thread immediately before asking: it remembers a workflow
   * the engine answered <code>404</code> for. Nothing else shortens anything - a probe
   * which was skipped, which failed, or which said the engine HOLDS the workflow all leave
   * the long window, because none of them says the workflow is over.
   * <p>
   * The short window changes how long the core waits and never WHAT this adapter answers
   * afterwards. The search below still tells {@link WorkflowAwareness#COMPLETED} from
   * {@link WorkflowAwareness#UNKNOWN_TO_BPMS}, and an ended workflow read as unknown is
   * what sends the next operation of a migration to the wrong BPMS.
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
   * A window as the core reads it: a window of zero or less is no waiting at all.
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
   * It is what {@link #workflowVisibilityDelay(String)} reads, and a thread is the right
   * place for it because the core asks the two questions one after the other on the thread
   * of the election: it probes, and where the answer is unknown it asks how long to keep
   * asking. Nothing is cached beyond that - the next probe on this thread writes its own
   * answer, and an id which is not the one remembered gets the long window.
   */
  private final ThreadLocal<String> theEngineHasForgottenThisWorkflow = new ThreadLocal<>();

  /**
   * How deeply the scope hierarchy is walked when a task-scoped push looks for the
   * scope a task runs in. Ten levels of nested subprocesses are a model nobody reads
   * any more, and the bound keeps a broken answer of the query API from looping.
   */
  private static final int MAX_SCOPE_DEPTH = 10;

  /**
   * The default of this adapter: everything is shared unless the application
   * excludes it ({@code @NoSyncWithBPMS}).
   */
  public static final AggregateSyncMode SYNC_MODE = AggregateSyncMode.FULL;

  /**
   * The core's name-clash-avoidance model: translates BPMN process ids,
   * message names and error codes into what the cluster knows, and decides the
   * tenant an operation runs in. May be <code>null</code> (tests): identifiers are
   * passed through and the configured tenant is used, as before.
   */
  private NameClashAvoidanceSupport scoping;

  /**
   * Sets the name-clash-avoidance support (constructor injection is not possible -
   * this class is built by Lombok's all-args constructor, which the platform
   * modules call).
   *
   * @param scoping The name-clash-avoidance support
   */
  public void setScoping(
      final NameClashAvoidanceSupport scoping) {

    this.scoping = scoping;

  }

  /**
   * What a workflow module's tenant is CONFIGURED as, resolved by the platform modules over
   * the levels the name may be set at, or <code>null</code> for a module nothing names a
   * tenant for - then the workflow module id names it. May be <code>null</code> itself
   * (tests), and then the adapter's own section is the only level.
   */
  private Function<String, Camunda8ConfiguredTenant> configuredTenants;

  /**
   * Sets the tenant names the application configured - this adapter's own configuration,
   * unlike the name-clash-avoidance support, which arrives with the collaborators.
   *
   * @param configuredTenants What a workflow module's tenant is configured as
   */
  public void setConfiguredTenants(
      final Function<String, Camunda8ConfiguredTenant> configuredTenants) {

    this.configuredTenants = configuredTenants;

  }

  /**
   * Resolves how long the cluster keeps a message this adapter publishes, per adapter,
   * workflow module, workflow and message. May be <code>null</code> (tests, and a platform
   * written before this): the client's own default applies then and VanillaBP sets nothing
   * on the command.
   */
  private Camunda8MessageTimeToLiveResolver messageTimeToLiveResolver;

  /**
   * Injected by the platform module after construction, like the scoping next to it - an
   * optional collaborator rather than a constructor argument, so a test building this
   * service by hand does not have to know about it.
   *
   * @param messageTimeToLiveResolver The resolver, or <code>null</code>
   */
  public void setMessageTimeToLiveResolver(
      final Camunda8MessageTimeToLiveResolver messageTimeToLiveResolver) {

    this.messageTimeToLiveResolver = messageTimeToLiveResolver;

  }

  /**
   * The window the cluster keeps the given message in, or <code>null</code> where nothing
   * configures one.
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
   * The tenant an operation of the given workflow module runs in - see the
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
   * What Camunda 8 does with that type, so the startup check can say what happens to a value
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
   * The process variables written whenever this adapter talks to the cluster on
   * behalf of a workflow: the aggregate's shared attributes PLUS - always, no
   * matter what the sync model says - the technical variable carrying the
   * aggregate's ID (named after the aggregate's ID property). Camunda 8 has no
   * business key: that variable is how VanillaBP finds the workflow again.
   *
   * <p>
   * Package-private so {@code Camunda8SharedValuesTest} can hold the second half of that
   * promise without a cluster: an aggregate annotated {@code @NoSyncWithBPMS} shares
   * nothing, and Camunda 8 has no business key, so losing the ID variable would start a
   * workflow nobody can find again.
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
      // no persistence at hand (e.g. a test driving the SPI directly): neither the
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
   * Phase one is what a REMOTE cluster can be asked without advancing anything: a job
   * timeout renewal, an empty user-task update, the model's message names - and it runs
   * as a pre-commit hook, so the window to the phase-two dispatch stays small. Phase two
   * sends the command and tolerates what the at-least-once dispatch of the outbox
   * implies, up to the message deduplication the cluster runs on its own.
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

    // job workers report the outcome AFTER the local transaction was committed, so a
    // crash in between makes the cluster hand the same job to a worker again. The
    // identity across such a redelivery is the JOB KEY, reported by every
    // invocation context.
    return true;

  }

  @Override
  public Long openTaskCount(
      final String workflowModuleId,
      final String bpmnProcessId) {

    // asked once per BPMN process at startup, so one search is affordable where the
    // cluster can serve it at all. Both kinds VanillaBP delivers are jobs: a service
    // task IS a job, and a Camunda-managed user task reaches the application through
    // its listener job
    try {
      // the TOTAL rather than the page which came back, and one item fetched because
      // only the number is wanted
      final var found = clientFactory
          .getClient()
          .newJobSearchRequest()
          .filter(filter -> filter.processDefinitionId(scopedProcessId(workflowModuleId, bpmnProcessId)))
          .page(page -> page.limit(1))
          .send()
          .join();
      return found.page().totalItems();
    } catch (final Exception e) {
      // a startup diagnostic, so a cluster which did not answer costs the number and
      // nothing else
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

    // the probe is UpdateJobTimeout - a NON-ADVANCING command which doubles as a lock
    // renewal (the job's lock is set to the renewal window, the same value the worker
    // granted when the task was left open). Camunda 8 cannot
    // answer COMPLETED for jobs (a completed job is indistinguishable from a
    // never-existing one without the eventually-consistent search API), so a
    // successful "not found" maps to UNKNOWN_TO_BPMS.
    //
    // A job key is unique per CLUSTER, so where another adapter id addresses
    // the same one the probe would answer for its job and extend its lock on the way
    // (see decision 3 in the repository's DECISIONS.md).
    // Which scope the key belongs to is asked FIRST there, and nowhere else - the
    // question costs a query-API round trip.
    if (!belongsToThisAdapter(scope, taskId, false)) {
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }
    try {
      updateJobTimeout(taskId);
      return WorkflowAwareness.ACTIVE;
    } catch (final Exception e) {
      if (Camunda8Errors.jobAlreadyGone(e)) {
        // the platform throws on this answer at once, so the rejection which produced it
        // is said out loud rather than left to a level nobody had turned on - the same
        // reason answersForTheScopeOf gives for the other branch
        log.info(
            "Camunda8[{}]: task '{}' is unknown to the cluster - it refused the probe's "
                + "UpdateJobTimeout with {}",
            adapterId,
            taskId,
            Camunda8Errors.rejection(e));
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }
      if (Camunda8Errors.jobIsThereButNotActive(e)) {
        // the cluster holds the job and refused to move its deadline, which is what it
        // answers for a job nobody has activated right now. The everyday case is an
        // asynchronous task whose lock ran out, and the task is alive, so the probe says
        // so instead of sending the caller into retries
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
   * The workflow aggregate whose transaction a phase-one check belongs into.
   * <p>
   * The core always hands the aggregate's persistence along; only tests call phase one
   * without one, and {@code Object.class} then resolves the platform's own runner - which
   * is what a test without any aggregate persistence has anyway.
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
   * The phase-one contract for remote BPMS: a NON-ADVANCING existence check whose
   * only purpose is to abort the local transaction early when the task is already
   * gone. Registered as a PRE-COMMIT synchronization (not run at method-call time)
   * so the window between check and phase-two dispatch - and therefore the number
   * of stale outbox entries - stays minimal (the V1 refinement). The check is the
   * same UpdateJobTimeout used by the awareness probe: it refreshes the dormant
   * job's lock as a side effect and never advances the process.
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
   * The type is the one the SPI documents for exactly this outcome, so an application
   * catching {@link TaskNotFoundException} catches it here as well - whether the task
   * turned out to be gone while a BPMS was probed or while this check ran is the
   * adapter's business, not the caller's. That exception carries a message and nothing
   * else, so the cluster's own words about the rejection are written to the log before
   * it is thrown; the awareness probe says them for the same reason.
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
   * The command asks about a USER-TASK key, so its <code>404</code> means "I hold no user task of
   * that key" and not "the task is over". For a key which really is a user-task key the two are
   * the same thing. For a JOB key they are not: a user task served by a job worker, which is what
   * VanillaBP modelled up to its release 1.6.3, has no user-task record in the cluster at all, so
   * every user-task command answers <code>404</code> however open the task is.
   * <p>
   * An application meets this while it upgrades. The task ids of version 1 are data it brought
   * with it, and the ones belonging to that shape of user task are job keys. Saying "completed or
   * canceled meanwhile" about one of them names the one thing the cluster did not say.
   * <p>
   * So the job side is asked, once and only here, and what it answers goes into the message. The
   * caller still gets what it got before, a {@code TaskNotFoundException} respectively
   * {@code UNKNOWN_TO_BPMS}: what kind of key somebody handed in changes the sentence, not the
   * outcome.
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
      // the engine has the job and the index has not written it yet, so the message says what is
      // certain and leaves out the element
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
   * Whether the cluster holds a JOB of the given key - the question which decides whether a
   * <code>404</code> of a user-task command was about the key.
   * <p>
   * It is the ENGINE which is asked, with the <code>UpdateJobTimeout</code> this adapter sends
   * as its existence check for a service task anyway: <code>404</code> for a key it holds no job
   * of, a <code>400</code> saying nobody has it activated for one it holds, and an accepted
   * command for one somebody is holding right now. The index cannot answer it. Measured on
   * 2026-09-28 against <code>camunda/camunda:8.9.21</code> and
   * <code>camunda/camunda:8.10.0-rc1</code> by {@code Camunda8ProbeOfAnOpenUserTaskIT}: this
   * command was accepted for a job which was open and answered <code>404</code> once that job was
   * gone, while the job search answered "no job of that key" for the open one and still answered
   * with the job once it was over.
   * <p>
   * What it costs is the one thing worth naming: where a worker of the application holds that job
   * right now, its deadline is pushed out to <code>async-task-lock-renewal</code>, the same as the
   * existence check of a service task does. The job of this case is a user task nothing in this
   * version fetches, so the answer is a <code>400</code> and the cluster writes nothing.
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
      // only an answer which SAYS the cluster holds it counts: an unreachable cluster answers
      // neither way, and a message claiming a job key from a failed request would be a guess
      return Camunda8Errors.jobIsThereButNotActive(e);
    }

  }

  @Override
  public WorkflowAwareness awarenessOfUserTask(
      final WorkflowScope scope,
      final Object workflowAggregateId,
      final String taskId) {

    // the probe is an EMPTY UpdateUserTask - an engine COMMAND rather than a search,
    // so it answers from the partition instead of waiting for the exporter, and it
    // never advances the task; it answers NOT_FOUND for gone tasks. Side effect: modeller-defined
    // 'updating' task listeners fire - documented in the README.
    //
    // A task in the middle of a transition of its own is refused rather than answered,
    // and a refusal about a task is a task the cluster has, see
    // Camunda8Errors#refusedAboutAUserTaskItHolds. CREATING is the state to expect here:
    // the application is notified from the 'creating' listener, so it may ask about a task
    // the cluster has not finished creating.
    //
    // As for service tasks, a user-task key is unique per cluster, and on a
    // shared one the scope is asked before the task is claimed
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

    // an update carrying ONLY an 'action' (an audit metadatum) is the minimal
    // valid update - no task attribute changes, nothing advances
    clientFactory
        .getClient()
        .newUpdateUserTaskCommand(taskKeyOf(taskId))
        .action(Camunda8UserTaskProbe.ACTION)
        .send()
        .join();

  }

  private void preflightCompleteUserTask(
      final PhaseOneRequest<A> request) {

    // pre-commit existence check (non-advancing empty update) - same shape as
    // service tasks, see registerPreCommitExistenceCheck
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
          // this check aborts a transaction whose task is GONE, and a task the cluster
          // refuses a command ABOUT is not gone. The common case is a task the cluster is
          // still creating: the application was notified from the 'creating' listener of
          // that very task, so it may answer while the creation is still on the way. The
          // completion of phase two waits that out, see completeUserTask
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
      // read ONCE and not per attempt: every attempt carries what the caller committed, and
      // reading the aggregate again would cost a transaction per attempt
      final var variables = variablesOf(request.aggregatePersistence(), request.workflowAggregateId());
      // the task may still be in CREATING, because the application was notified from the
      // 'creating' listener of this very task and may have answered in the same breath
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

    // fail EARLY inside the caller's transaction: see cancelUserTaskPhaseTwo
    throw newCancelUserTaskUnsupported(request.taskId(), request.bpmnProcessId());

  }

  private void cancelUserTask(
      final PhaseTwoRequest<A> request) {

    throw newCancelUserTaskUnsupported(request.taskId(), request.bpmnProcessId());

  }

  private UnsupportedOperationException newCancelUserTaskUnsupported(
      final String taskId,
      final String bpmnProcessId) {

    // No Camunda 8 cluster up to 8.9 offers a command to cancel a Camunda-managed user
    // task by BPMN error: ThrowError is job-based (a zeebe:userTask has no job), and the
    // V1 workaround (completing the task with a marker variable evaluated by a listener)
    // is marked "currently not working" in the V1 adapter itself. The task/execution
    // listeners of 8.10 are what this needs, so it can only ever arrive on a line built
    // against 8.10. The message names the line
    // because that is what the reader has to change to get it.
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
          // The aggregate changed before the task was completed - the
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
      // stale outbox entry: the job disappeared between the dispatch-time probe
      // and this command - the at-least-once residual, the entry is consumed
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
          // The error boundary's outgoing path may branch on the
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
   * Normally yes: the deployment ends the boot on a cluster which refuses to be searched,
   * see decision 20 in the repository's DECISIONS.md. One case survives that, and it is
   * the reason this reads the probe instead of answering <code>true</code> from a
   * constant: an adapter which is not the first-priority adapter of its workflow module
   * and carries <code>deployment-failure: warn</code> boots DEGRADED against such a
   * cluster. It serves nothing, and the core has to hear that it cannot locate a workflow
   * before it builds a migration on it.
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

    // Zeebe offers NO engine command answering "does an instance for this
    // aggregate exist" - only the eventually-consistent query API, which is why this
    // adapter requires a cluster it can search. The search filters by the aggregate-ID
    // process variable.
    //
    // It does NOT filter by state, although only an ACTIVE instance can be advanced:
    // an ended workflow is COMPLETED, not UNKNOWN_TO_BPMS. The difference is the whole
    // point of the two values - "unknown" permits falling back to the next adapter and
    // is what the viewer/history API reports as WorkflowNotFoundException, while
    // "completed" says this BPMS is the one which held the workflow. Filtering here
    // made every read of an ended workflow fail and turned an operation arriving too
    // late into a lookup failure.
    //
    // A workflow somebody cancelled is reported as ACTIVE until it has really ended,
    // and that can be much longer than the search needs. The engine answers 404 to a
    // second cancellation within milliseconds, but an instance holding a Camunda-managed
    // user task only terminates once the canceling listener job of that task is
    // answered. Measured against a cluster of the 8.9 line on 2026-09-25: with that job
    // answered at once the search stopped reporting the instance after 0,8 to 1,0
    // seconds, and with nobody answering it the instance was still reported 130 seconds
    // later and ended half a second after a worker finally took the job. ACTIVE is the
    // right answer for all of that time - the instance is there, and an operation sent
    // to it is taken. What is NOT an answer is the engine's 404, which is why nothing
    // here reads one.
    try {
      final var found = clientFactory
          .getClient()
          .newProcessInstanceSearchRequest()
          .filter(filter -> Camunda8Searches
              .byAggregateId(
                  filter, aggregateIdVariableName(aggregatePersistence), workflowAggregateId))
          .send()
          .join();
      // On a cluster shared with another adapter id the variable alone finds
      // the other deployment's instance too - only what THIS adapter deployed counts
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
      // a cluster of this adapter can be searched (the deployment refuses one which
      // cannot), so a search failing here is an outage - never a guess about what the
      // cluster holds, which in a migration setup would route the operation to the
      // wrong BPMS. The credentials are named because one which loses its read
      // permission mid-run arrives here looking exactly like an outage
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
   * The same question with the BPMS' own id of the workflow, which is what the election
   * passes on the paths which WAIT for the read model to catch up.
   * <p>
   * <b>What the key buys.</b>
   * Measured on 8.10.0-alpha5, 8.9.19 and 8.8.37: the engine says "this instance exists" 16
   * to 19 ms after the create was sent, while the search below finds it after 167 to 1324
   * ms. An extension asking where a workflow started moments ago is therefore answered in
   * milliseconds instead of sitting out the visibility window.
   * <p>
   * <b>The rule which must not be broken.</b>
   * The probe may shorten the YES and nothing else. The engine forgets an instance the
   * moment it ends, so a key it does not hold covers a completed workflow, a canceled one
   * and a key which never existed alike - and this method has to tell
   * {@link WorkflowAwareness#COMPLETED} from {@link WorkflowAwareness#UNKNOWN_TO_BPMS},
   * because only the second lets the election move on to the next BPMS. So every answer but
   * "the engine holds it" falls through to the search below, unchanged.
   * <p>
   * A probe which cannot answer at all - a timeout, a broken connection, a cluster which is
   * not there - is not a 404 and is never read as one. It falls through as well, and the
   * search then decides, which is what reports {@link WorkflowAwareness#BPMS_UNAVAILABLE}
   * for a cluster nobody can reach. A mechanism which turned an outage into "unknown" would
   * empty a migration setup into the next BPMS.
   * <p>
   * On a cluster this adapter SHARES with another adapter id nothing is asked at all. An
   * instance key is unique per cluster and names no scope, and the election hands the same
   * id to every adapter of its list, so the probe would answer for the other one's instance
   * and end the election at the wrong adapter.
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
   * Asks the ENGINE whether it holds the given instance, which is a question it answers from
   * the partition rather than from the index an exporter feeds.
   * <p>
   * Which command carries the question is
   * {@link Camunda8InstanceProbe#askTheEngine(io.camunda.client.CamundaClient, long, String, String) per release line}
   * and follows the business id, see decision 35 in the repository's DECISIONS.md. Both of
   * them are REFUSED by an instance the engine holds, so the cluster writes no state and an
   * operator never finds a modification in the history of a workflow nobody modified. The
   * refusal is the answer:
   * <ul>
   * <li>HTTP <code>400</code> - the modification names an element the model does not have,
   * which the engine can only say about an instance it holds;</li>
   * <li>HTTP <code>409</code> - the instance already carries a business id, same thing;</li>
   * <li>anything the command took - the instance is there, whatever the command did.</li>
   * </ul>
   * A <code>404</code> means the engine does not hold it, which is the answer this method
   * may not turn into anything, and so is every failure which is not an answer at all.
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

    // whatever this probe finds out replaces what it found out last time, so a question
    // it does not answer at all leaves nothing behind for the window to read
    theEngineHasForgottenThisWorkflow.remove();
    if ((workflowId == null) || workflowId.isBlank()) {
      return false;
    }
    if (clientFactory.sharesItsCluster()) {
      // an instance key is unique per CLUSTER and says nothing about which adapter id
      // deployed the process (see decision 3 in the repository's DECISIONS.md), and the
      // election hands the same id to every adapter of its list. So on a shared cluster
      // this probe would answer ACTIVE for the instance of the OTHER adapter id and end
      // the election at the wrong one. The search below asks that question, and here the
      // engine cannot be asked it without a round trip which would cost what the probe
      // saves
      return false;
    }
    if (aModelOfTheScopeCarriesTheReservedElement(scope)) {
      return false;
    }
    final long processInstanceKey;
    try {
      processInstanceKey = Long.parseLong(workflowId);
    } catch (final NumberFormatException e) {
      // an instance key of this cluster is a number, so this id belongs to another BPMS
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
        // the engine has forgotten this instance, which says nothing about whether the
        // workflow completed or never existed - the search below is what tells those apart.
        // What it does say is that nothing is on its way into the read model but the END of
        // this workflow, which is what the short window of workflowVisibilityDelay waits for
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
   * Whether any BPMN process of the scope carries the element id the probe reserved, which
   * is what keeps the probe away from a workflow it might modify instead of ask about.
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
   * Asked this way round on purpose. The client's state enum grows inside a line, and it
   * reports a state older than the cluster as <code>UNKNOWN_ENUM_VALUE</code>: 8.10 adds
   * <code>SUSPENDED</code> here, which is a workflow somebody can still act on. Since
   * {@link WorkflowAwareness#COMPLETED} says the operation comes too late, a state nobody
   * here knows has to count as running rather than as finished.
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
   * <li>no state filter - a workflow COMPLETED since the crashed start still
   * proves the start succeeded;</li>
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
          // as in awarenessOfWorkflow: an instance of the other adapter id on
          // this cluster does not prove that THIS one started the workflow
          .noneMatch(instance -> isInScope(scope, instance.getTenantId(), instance.getProcessDefinitionId()))
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
   * Whether the given process instance, job or user task belongs to the scope the probe
   * was asked about.
   * <p>
   * The scope is the workflow module and the BPMN processes of the CALL, translated into
   * what the cluster knows them by: the tenant of that module and the SCOPED process
   * definition ids. Two facts make the comparison necessary, and neither alone would be
   * enough. Two <code>camunda8</code> adapter ids may address ONE cluster, which is the
   * supported setup migrating a workflow module from tenants to prefixed identifiers, and
   * there every key is global. And one adapter id serves several workflow modules, whose
   * aggregate ids are unique per aggregate type rather than across an application, so
   * "one of mine" is not the same question as "the one you asked about".
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
   * Whether the task behind the given key belongs to the scope the probe was asked about
   * - asked only where another <code>camunda8</code> adapter id
   * addresses the same cluster, because a key is unique per cluster and the two ids would
   * otherwise answer for each other's tasks.
   * <p>
   * The answer is a search, which every cluster this adapter serves answers: one which
   * does not is refused while the adapter deploys, whether one adapter id addresses it or
   * two (see {@code Camunda8SearchableClusterCheck}). A task no search knows is left to the
   * probe itself: it is either gone or not exported yet, and both are answered by the
   * command which follows.
   * <p>
   * <b>Why not always.</b> Where one adapter id owns the cluster, the read would buy very
   * little for a query-API round trip on every task election: the key of another workflow
   * module of the same application still addresses the task the operation then acts on,
   * because completing or cancelling goes by that key, and a key of ANOTHER BPMS is not a
   * Camunda 8 key at all. The workflow probes, whose answer routes a message or a pushed
   * aggregate, compare the scope always - there it is free.
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
        // not exported yet or gone - the probe's own command answers that
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
   * Whether the scope the cluster reports for a task is one the probe was asked about, and
   * where it is not, the line which says why the task is unknown.
   * <p>
   * <b>Why this is not a DEBUG line.</b> The caller answers UNKNOWN_TO_BPMS on a
   * <code>false</code>, and the platform turns that into a
   * {@code WorkflowNotFoundException} at once: a task is an exact question, so there is no
   * visibility window and no second attempt. A run which ends that way used to leave the
   * platform's exception and not one word from the adapter, and the next one would have
   * looked exactly the same. So both values which decided it are collected rather than
   * filtered away by a level nobody had turned on.
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
   * untenanted instance while the adapter has no tenant configured at all, so both are
   * folded into the same value.
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
   * The name of the process variable holding the workflow-aggregate ID: Camunda 8
   * has no business key, so every lookup of a workflow filters by that variable.
   * <p>
   * It is ALWAYS derived from the aggregate persistence of the call at hand, never
   * remembered between calls. One process service serves every workflow module and
   * aggregate of its adapter id, so a remembered name would be the one of whichever
   * aggregate was handled last, which was a defect of this adapter once: the
   * awareness probe ran before any call carrying a persistence and searched for the
   * placeholder name, which found nothing on a cluster with secondary storage, so
   * every operation locating its workflow by the probe failed.
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

    // The outbox repeats what a second attempt may fix - a cluster which
    // is busy, unreachable or lost a conflict. A command the cluster REJECTS looks
    // the same on every attempt, and so does a task key which is not a number. The
    // list of those cases lives in Camunda8Errors, next to the job-gone rule.
    // A start adds two answers to it which mean something else for every other
    // operation, and it adds them by wrapping its refusal rather than by asking here,
    // because this method is handed the failure and never the operation
    return !Camunda8Errors.permanentFailure(failure);

  }

  /**
   * Phase one of a message correlation asks the MODEL, not the cluster.
   * <p>
   * A subscription search exists since the client version this adapter builds
   * against, and it would be the wrong check: the cluster BUFFERS a message for its
   * time-to-live, so correlating before the subscription exists is legitimate and a
   * search would reject exactly that case - besides reading the eventually consistent
   * secondary storage, whose window the caller would wait out inside their
   * transaction.
   * <p>
   * What can be checked without asking anybody is whether the deployed models of this
   * workflow module declare the message at all. A name no model knows is a typo or a
   * renamed message, and phase two would publish it into the void: the cluster accepts
   * the publication, the TTL passes, nothing ever correlates. So the mistake is
   * reported where the application made the call.
   * <p>
   * The check stays silent where its set of models is incomplete: where this
   * application version deployed no process of the workflow module (a workflow still
   * running on a definition of a previous version - see
   * {@code Camunda8DeployedProcesses}), and where the module declares a BPMN process
   * id nothing was deployed under (the old id of a renamed process, whose models the
   * cluster holds). In both cases the declared names are unknown rather than absent,
   * and a check which cannot see every model that could carry the answer must stay
   * silent, never refuse.
   */
  private void preflightCorrelateMessage(
      final PhaseOneRequest<A> request) {

    clientFactory.validateConfigured();
    validateMessageIsDeclared(request.workflowModuleId(), request.messageName());

  }

  /**
   * Reports a message name no model of the workflow module declares - reading the
   * models the CLUSTER holds for the ids this application declares, not only what
   * this application version deployed, so the verdict does not depend on which
   * application version deployed the model carrying the name (see decision 21 in
   * the repository's DECISIONS.md). Where those models cannot be read, the check
   * stays silent.
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
      // a workflow still running on a definition of a previous version, in a module
      // this boot deployed nothing for: the declared names are unknown rather than
      // absent
      return;
    }
    // the models carry the SCOPED names - messages are renamed while deploying - so
    // the name of the call is scoped the same way the publication scopes it
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
      // unconfigured): the models beyond the current deployment cannot be read, so
      // a module whose ids were not all deployed is answered with silence
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
      // the kept picture may be outdated by another node's deployment (rolling
      // upgrade), and a refusal may only rest on models read now
      answer = modelsTheClusterHolds.heldForAfterReadingAgain(workflowModuleId);
    }
    if (!(answer instanceof Camunda8ModelsTheClusterHolds.Answer.Known known)) {
      // the cluster could not be asked, so the set of models is incomplete: the
      // check says nothing rather than refusing a correlation the application may
      // have made correctly
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
   * The refusal of {@link #validateMessageIsDeclared}, built where the caller can say
   * which models were read.
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
   * The message names a deployed model declares: message catch events (intermediate,
   * boundary, event-subprocess start) and receive tasks - the same elements
   * {@code Camunda8TaskWiring#wireMessageSubscriptions} wires a correlation key into.
   * Message START events are included as well: a start by message goes through
   * {@code startWorkflowByMessage}, but declaring the name is what matters here.
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
    // (V1 semantics; the wired zeebe:subscription evaluates '=<idName>' - a
    // correlation id requires a model-side subscription on the matching variable).
    // messageId WHERE A CORRELATION ID EXISTS: the engine then deduplicates a second
    // publication of the same message for as long as the message time-to-live lasts,
    // which is a net of its own and a shorter one than VanillaBP's outbox - that one
    // ends with the dispatch, while this one runs for the TTL (the client's default
    // hour unless 'message-time-to-live' says otherwise). A repetition inside the TTL
    // is therefore swallowed by the ENGINE, whatever VanillaBP does, so a repeating
    // scope has to vary the correlation id. Without a correlation id there is no
    // messageId either, and an at-least-once redelivery may double-correlate
    // (documented).
    // PAYLOAD DOCTRINE: no message CONTENT travels - what does travel is the
    // aggregate state shared with the BPMS, because the cluster can
    // only evaluate BPMN expressions against variables it was given.
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
      // The ACTIVATION belongs in here for the same reason it belongs in VanillaBP's own
      // idempotency key: three elements of a multi-instance call activity agree in every
      // other part, because a called process is a secondary workflow of the SAME
      // aggregate. Without it they are three operations for the outbox and ONE message
      // for the cluster, and the two which lose are lost silently. Absent where the
      // correlation was planned outside any activation, which keeps the id a REST
      // endpoint produces exactly what it was
      command = command
          .messageId(
              messageIdOf(request.workflowModuleId(), request.bpmnProcessId(), request.workflowAggregateId(),
                  request.messageName(), request.correlationId(),
                  request.activationId()));
    }
    final var timeToLive = messageTimeToLiveFor(request.workflowModuleId(), request.bpmnProcessId(),
        request.messageName());
    if (timeToLive != null) {
      // per message, because the number buffers AND deduplicates and those two want it
      // to go in opposite directions. Nothing configured means nothing set: the client's
      // own default then applies, as it always did
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
      // the engine deduplicated by messageId. Which of the two it was cannot be told
      // from here, and the entry counts as consumed either way - repeating the publish
      // would be refused again
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
   * The id this adapter hands the cluster for a correlated message, which is what the
   * cluster deduplicates by for as long as the message lives.
   *
   * <h4>Why it looks like VanillaBP's own key and is not the same thing</h4>
   *
   * Both are derived from the same values, and they guard different windows: VanillaBP's
   * key deduplicates the entries which have not been dispatched yet, this one deduplicates
   * publications inside the message time-to-live. An operation can pass the first net and
   * be dropped by the second, which is why the adapter says so when the cluster refuses a
   * publication.
   *
   * <h4>Why the activation is part of it</h4>
   *
   * A called process is a secondary workflow of the SAME aggregate, so the three elements
   * of a multi-instance call activity agree in module, process, aggregate, message name
   * and - where it comes from business data - correlation id. Without the activation they
   * are three operations for the outbox and ONE message for the cluster.
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
   * A remote BPMS must not act before the caller's transaction committed: phase one
   * does nothing, the broadcast happens in phase two through the outbox.
   */
  private void preflightSendSignal(
      final PhaseOneRequest<A> request) {

  }

  private void sendSignal(
      final PhaseTwoRequest<A> request) {

    // no variables travel with a signal, and there is nothing to deduplicate by:
    // unlike a message, a broadcast carries no correlation key the cluster could
    // recognize a redelivery from (documented at-least-once residual)
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

    // a remote BPMS: writing here would show the cluster values of a transaction
    // which may still roll back - the push happens in phase two

  }

  private void pushChangedAggregate(
      final PhaseTwoRequest<A> request) {

    final var variables = variablesOf(request.aggregatePersistence(), request.workflowAggregateId());

    if (request.taskId() == null) {
      final var processInstanceKey = processInstanceKeyOf(
          WorkflowScope.of(request.workflowModuleId(), request.bpmnProcessId()),
          request.aggregatePersistence(),
          request.workflowAggregateId());
      if (processInstanceKey == null) {
        // at-least-once residual: the workflow ended between the dispatch-time
        // election and now - there is nothing left to write to
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
      return;
    }

    final var elementInstanceKey = flowScopeKeyOf(request.taskId());
    if (elementInstanceKey == null) {
      log.warn(
          "Camunda8[{}]: the scope of task '{}' of aggregate '{}' was not found within {} - skipping "
              + "the push of the changed aggregate. Either the task was completed meanwhile, or the "
              + "query API did not catch up with it: raise "
              + "'vanillabp.adapters.{}.workflow-visibility-timeout' if this cluster's exporter "
              + "regularly needs longer. The workflow's own scope is deliberately NOT written "
              + "instead - it is read by every branch, and the task asked for its own scope",
          adapterId,
          request.taskId(),
          request.workflowAggregateId(),
          workflowVisibilityDelay().window(),
          adapterId);
      return;
    }
    clientFactory
        .getClient()
        .newSetVariablesCommand(elementInstanceKey)
        .variables(variables)
        // the scope the task RUNS IN - a workflow-wide write would be a lost update
        // between the iterations of a multi-instance subprocess
        .local(true)
        .send()
        .join();
    log.info(
        "Camunda8[{}]: pushed the changed aggregate '{}' into element instance '{}' (task '{}')",
        adapterId,
        request.workflowAggregateId(),
        elementInstanceKey,
        request.taskId());

  }

  /**
   * The key of the ACTIVE process instance carrying the aggregate's ID variable -
   * Camunda 8 has no business key, so the eventually-consistent query API answers
   * (like {@link #awarenessOfWorkflow}).
   *
   * @param workflowAggregateId The aggregate's ID
   * @return The process instance key or <code>null</code> if none is active
   */
  private Long processInstanceKeyOf(
      final WorkflowScope scope,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    // A failing search is not caught here on purpose. The adapter's cluster can be
    // searched - the deployment refuses one which cannot - so a failure is an outage, and
    // an outage of the push is what the outbox entry behind it is retried for
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
    // Writing into the instance of ANOTHER adapter id of this cluster would
    // put the values of one migration half into the other one
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
   * Not the task's own element instance: in Camunda 8 every element instance is a
   * variable scope of its own, and one belonging to a task disappears with the task -
   * values written there would be read by nothing. The scope AROUND the task is what
   * the rest of that scope evaluates.
   * <p>
   * Camunda 8 reports no parent for an element instance, so the scope is found by
   * walking DOWN from the process instance (the query API filters element instances
   * by their scope) until the task's element instance shows up. A multi-instance BODY
   * on the way is skipped: it is the technical wrapper around the instances, not a
   * scope of the model.
   * <p>
   * The query API is fed by an exporter, so the task this push belongs to may not be
   * reported yet - which is why the search is repeated for as long as
   * {@link #workflowVisibilityDelay()} allows. Repeating is allowed HERE because this
   * runs in phase two, on the outbox dispatcher's thread: no application transaction is
   * open, so the waiting costs the entry an attempt rather than a database connection
   * (decision 27 of the platform's DECISIONS.md, which draws that line for the core's
   * election as well). A scope which stays unknown yields
   * <code>null</code>: the process instance is NOT used as a substitute, because
   * writing there is exactly the lost update between the iterations of a
   * multi-instance subprocess this scoping exists to prevent.
   *
   * @param taskId The task ID reported to the application (the job key)
   * @return The element instance key to write at, or <code>null</code> if the scope
   *         did not become known within the window
   */
  private Long flowScopeKeyOf(
      final String taskId) {

    final var delay = workflowVisibilityDelay();
    final var deadline = System.currentTimeMillis() + (delay.isWaiting()
        ? delay.window().toMillis()
        : 0);
    while (true) {
      final var scopeKey = searchFlowScopeKeyOf(taskId);
      if (scopeKey != null) {
        return scopeKey;
      }
      if (System.currentTimeMillis() >= deadline) {
        return null;
      }
      try {
        Thread.sleep(delay.interval().toMillis());
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }

  }

  /**
   * One attempt of {@link #flowScopeKeyOf(String)}.
   *
   * @param taskId The task ID reported to the application (the job key)
   * @return The element instance key to write at, or <code>null</code> if the query
   *         API knows neither the task nor its scope (yet)
   */
  private Long searchFlowScopeKeyOf(
      final String taskId) {

    final var job = jobOf(taskId);
    if (job == null) {
      return null;
    }
    final var scopes = scopePathOf(job.getProcessInstanceKey(), job.getElementInstanceKey());
    // innermost first: the scope holding the task, then its own scopes
    for (final var scope : scopes) {
      if (scope.type() != ElementInstanceType.MULTI_INSTANCE_BODY) {
        return scope.key();
      }
    }
    return null;

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
   * The scopes containing the given element instance, innermost first. Walks down
   * from the process instance, because Camunda 8 reports children of a scope but
   * never the parent of one.
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
   * Depth-first walk down the scope hierarchy, collecting the scopes containing the
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
    // every child of the scope, not the first page of them: a multi-instance over 100
    // items puts more children into one scope than a search hands out without being
    // asked, and the task of the 101st iteration would be a task below no scope at all.
    // Reading stops on the page the wanted element instance is on, because the children
    // after it are not walked into either
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
   * The job behind a task ID - a VanillaBP task ID IS the job's key, and the job
   * knows which element instance and which process instance it belongs to.
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

    // message START events ignore the correlation key; the aggregate-ID variable
    // is the ONLY variable published (the same technical field a regular start
    // sets - not message content). messageId is derived from the same values as the
    // start's idempotency key, so the engine deduplicates a redelivered dispatch within
    // the message TTL - a net of the cluster's, alongside the outbox' own.
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
      // The ADAPTER level only, deliberately: this message starts a workflow, so its
      // deduplication is wanted for as long as possible and the subscription of a message
      // START event exists as long as the process is deployed - the buffering half of the
      // number does not apply here at all. A per-message override meant for a repeating
      // catch event must not shorten the protection against a double-started workflow
      final var startTimeToLive = clientFactory.getConfiguration().getMessageTimeToLive();
      if (startTimeToLive != null) {
        startCommand = startCommand.timeToLive(startTimeToLive);
      }
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
      // a start is different from a correlation: a workflow is started at most once per
      // aggregate anyway, so a refused start message says the start happened and this
      // stays an INFO rather than a warning about something possibly lost
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

    // the aggregate id was validated (non-null, non-blank) once in the core's
    // MigrationProcessService before phase one is invoked

    // For starting a workflow there is nothing to check against the cluster in phase
    // one, so this only verifies the adapter is configured. Phase one runs inside the
    // caller's DB transaction: it must never advance the process (that races the local
    // transaction), but it MAY contact the cluster for non-advancing checks that abort
    // the transaction early - not needed here, since an unavailable cluster just makes
    // the phase-two start wait in the outbox until it is reachable again.
    clientFactory.validateConfigured();

    log.debug("Validated phase one of starting Camunda 8 workflow '{}' of workflow module '{}' "
        + "(adapter '{}')", request.bpmnProcessId(), request.workflowModuleId(), adapterId);

  }

  private void startWorkflow(
      final PhaseTwoRequest<A> request) {

    createProcessInstance(
        scopedProcessId(request.workflowModuleId(), request.bpmnProcessId()),
        variablesOf(request.aggregatePersistence(), request.workflowAggregateId()),
        request.workflowAggregateId(),
        tenantIdOf(request.workflowModuleId()));

  }

  /**
   * Creates a Camunda 8 process instance of the latest version of the given BPMN process,
   * passing the workflow aggregate's ID as a single process variable. How the aggregate's
   * ID is stored in the BPMS is the adapter's decision: Camunda 8 stores the aggregate as
   * process variables, so the variable carrying the ID is named after the aggregate's ID
   * property (see {@link AggregatePersistenceAware#getAggregateIdName()}). This is what
   * phase two of {@link PhaseOperation#START_WORKFLOW} does.
   * <p>
   * <b>Idempotency limitation:</b> a crash between a successful create and the removal of
   * the phase-two outbox entry can create the instance twice (at-least-once, duplicates
   * possible). That residual is accepted rather than pending: a workflow is located by
   * asking, not by a persistent registry (decision 25 of the platform's DECISIONS.md),
   * and what narrows the window is the core probing
   * {@link #awarenessOfWorkflowForRedispatch} before it dispatches a start again. What is
   * left of it is documented in this repository's README under "Idempotency limitation".
   * No Camunda-8-side workaround is attempted here.
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
   * Creates the instance in the given tenant - which the name-clash-avoidance mode
   * decides: the workflow module id under {@code by-adapter}, none under
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
    // the aggregate's id in the field Operate shows first, where the application asked for
    // that. Nothing of VanillaBP reads it back, and on a line without the field nothing is
    // sent, see decision 37 in the repository's DECISIONS.md
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
   * What a failed create is thrown on as: the cluster's own failure where another
   * attempt may still get through, and a {@link Camunda8RefusedStart} where it may not.
   * <p>
   * The wrapping is what carries the OPERATION into the classification, which sees the
   * failure alone (see {@link Camunda8RefusedStart}), and the message is what an
   * operator reads next to an outbox entry which will not move again. It names both ways
   * out without guessing which of them applies: the cluster says "no such process" with
   * the same code whether a workflow module never reached it or whether it reached
   * another one.
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
   * The viewer/history API - see {@link Camunda8WorkflowViewer} for the
   * two data sources (what this application version deployed vs. the cluster's
   * query API) and the consistency caveats.
   */
  private volatile Camunda8WorkflowViewer viewer;

  private Camunda8WorkflowViewer viewer() {

    // built on first use: this class is constructed by Lombok's all-args
    // constructor, so a field initializer could not reference the final fields
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
   * A VanillaBP task id turned into the key Camunda 8 commands expect - the job's key
   * for a service task, the user task's key for a user task. Always a decimal number,
   * which is why the failure is worth a message of its own.
   *
   * <h4>Why this exists</h4>
   *
   * VanillaBP 1 could hand out the same key in HEXADECIMAL
   * (<code>task-id-as-hex-string</code>, off by default), and an application which
   * switched it on stored those ids in its own data - a user task it is holding, a
   * service task waiting for its <code>completeTask</code>. Those ids outlive the
   * upgrade. Version 2 has no such setting and parses decimally everywhere, so what the
   * developer got was a bare NumberFormatException naming neither the setting which
   * produced the number nor the fact that the operation is not retried: a task key which
   * is not a number is a PERMANENT phase-two failure, so the outbox entry is blocked
   * after a single attempt.
   * <p>
   * The classification stays exactly as it was - the NumberFormatException travels as the
   * cause, which is what {@code Camunda8Errors.permanentFailure} walks the chain for.
   * Version 2 deliberately does NOT accept hexadecimal ids again: one representation of a
   * task id is simpler than two, and an application which has them in its data has a
   * migration of its own, which the message points at.
   *
   * <p>
   * Package-private so the message can be asserted without a client.
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
   * Whether a task id reads like one of version 1's hexadecimal ids: not a decimal
   * number, but a valid hexadecimal one. A hint rather than a claim, which is how the
   * message states it.
   *
   * @param taskId The task id which failed to parse
   * @return <code>true</code> where hexadecimal would have worked
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
