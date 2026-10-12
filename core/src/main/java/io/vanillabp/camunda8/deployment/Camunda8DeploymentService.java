package io.vanillabp.camunda8.deployment;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.camunda.client.CamundaClient;
import io.camunda.client.CamundaClientConfiguration;
import io.camunda.client.api.command.DeployResourceCommandStep1;
import io.camunda.client.api.command.DeployResourceCommandStep1.DeployResourceCommandStep2;
import io.camunda.client.api.worker.JobWorker;
import io.camunda.client.api.worker.JobWorkerBuilderStep1;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.vanillabp.camunda8.Camunda8Adapter;
import io.vanillabp.camunda8.Camunda8ProcessingContext;
import io.vanillabp.camunda8.Camunda8ReleaseLine;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.camunda8.client.Camunda8InstanceIdentity;
import io.vanillabp.camunda8.client.Camunda8SearchableClusterCheck;
import io.vanillabp.camunda8.client.Camunda8TenantCheck;
import io.vanillabp.camunda8.client.Camunda8UnservedUserTaskJobs;
import io.vanillabp.camunda8.client.Camunda8WorkerConnections;
import io.vanillabp.camunda8.client.Camunda8Workers;
import io.vanillabp.camunda8.health.Camunda8Health;
import io.vanillabp.camunda8.observability.Camunda8Metrics;
import io.vanillabp.camunda8.wiring.Camunda8AllowConnectorsResolver;
import io.vanillabp.camunda8.wiring.Camunda8AllowListenersResolver;
import io.vanillabp.camunda8.wiring.Camunda8BpmsInitiatedStartHandler;
import io.vanillabp.camunda8.wiring.Camunda8CancelListeners;
import io.vanillabp.camunda8.wiring.Camunda8ConfiguredTenant;
import io.vanillabp.camunda8.wiring.Camunda8Connectors;
import io.vanillabp.camunda8.wiring.Camunda8FetchVariables;
import io.vanillabp.camunda8.wiring.Camunda8JobHandler;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.camunda8.wiring.Camunda8Listeners;
import io.vanillabp.camunda8.wiring.Camunda8ModelExpressions;
import io.vanillabp.camunda8.wiring.Camunda8ModelledListenerHandler;
import io.vanillabp.camunda8.wiring.Camunda8MultiInstance;
import io.vanillabp.camunda8.wiring.Camunda8OpenTaskProbe;
import io.vanillabp.camunda8.wiring.Camunda8RetryBackoffResolver;
import io.vanillabp.camunda8.wiring.Camunda8Scoping;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.camunda8.wiring.Camunda8UserTaskListenerHandler;
import io.vanillabp.camunda8.wiring.Camunda8WorkflowEndedHandler;
import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.spi.parts.VanillaBpParts;
import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;
import lombok.extern.slf4j.Slf4j;

/**
 * Camunda 8 implementation of the {@link AdapterDeploymentService}. One instance is
 * created per configured adapter ID, not per adapter type. The same BPMS type may be
 * configured several times, for a BPMS migration.
 * <p>
 * The BPMN model type is {@link BpmnModelInstance}, shipped with the Camunda 8 client in
 * {@code io.camunda:zeebe-bpmn-model}. The processing context is
 * {@link Camunda8ProcessingContext}. It collects all deployable resources of a workflow
 * module, so they are deployed in a single {@code DeployResourceCommand}.
 * <p>
 * Task wiring ({@code wireBpmn}) checks the job-worker tasks of the BPMN
 * (zeebe:taskDefinition) against the registered {@code @WorkflowTask} methods.
 * {@code startWorkflowProcessing} opens one polling job worker per task definition.
 * {@code stopWorkflowProcessing} closes them.
 */
@Slf4j
// see decision 4 in the repository's DECISIONS.md
@SuppressWarnings("LombokSetterMayBeUsed")
public class Camunda8DeploymentService implements AdapterDeploymentService<BpmnModelInstance, Camunda8ProcessingContext> {

  /**
   * The adapter type of the Camunda 8 adapter. It is the same for all instances. The
   * adapter ID (see {@link #getAdapterId()}) tells instances apart.
   */
  public static final String ADAPTER_TYPE = Camunda8Adapter.ADAPTER_TYPE;

  private final String adapterId;

  private final Camunda8ClientFactory clientFactory;

  /**
   * Everything the platform hands over. An adapter which is registered incompletely does
   * not come into existence (see {@link AdapterCollaborators}).
   */
  private final AdapterCollaborators collaborators;

  /**
   * Where the core processes tasks. It checks the wiring during {@link #wireBpmn} and
   * dispatches jobs at runtime.
   */
  private final WorkflowTaskWiring workflowTaskWiring;

  /**
   * The runtime half of the split SPI. This service does not only wire. It also opens the
   * job workers at {@code startWorkflowProcessing}, and those hand every delivery to the
   * core. So it holds both halves and passes this one on.
   */
  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * What this adapter knows about the multi-instance elements of the processes it
   * deployed. Filled while a model is wired, read while a job is dispatched. A job
   * carries the ID of its own element and nothing about the iterations around it.
   */
  private final Camunda8MultiInstance.Registry multiInstanceRegistry = new Camunda8MultiInstance.Registry();

  /**
   * Where the core takes the workflows which the cluster starts on its own. The start
   * events of a process are reported here while wiring. The workers of the start execution
   * listeners dispatch through it. May be <code>null</code> (tests).
   */
  private final BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker;

  /**
   * Where the core takes the workflows which ended. May be <code>null</code> (tests).
   * Then no end listener is attached.
   */
  private final WorkflowEndedInvoker workflowEndedInvoker;

  /**
   * Whether this application respects the element-template marker of a model, resolved per
   * workflow module and per workflow. The platform module hands it in after construction,
   * not through the constructor, whose parameter list is long enough. <code>null</code>
   * (tests) means the default: VanillaBP wires every element.
   */
  private Camunda8AllowConnectorsResolver allowConnectorsResolver;

  /**
   * Hands over how <code>allow-connectors</code> resolves for this adapter instance.
   *
   * @param allowConnectorsResolver The resolver, or <code>null</code> for the default
   */
  public void setAllowConnectorsResolver(
      final Camunda8AllowConnectorsResolver allowConnectorsResolver) {

    this.allowConnectorsResolver = allowConnectorsResolver;

  }

  /**
   * What the configuration says about one process of one workflow module, together with
   * the key it said it in.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The setting, never <code>null</code>
   */
  private Camunda8AllowConnectorsResolver.Setting connectorsAllowedFor(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return Camunda8AllowConnectorsResolver
        .resolve(allowConnectorsResolver, workflowModuleId, bpmnProcessId);

  }

  /**
   * What the configuration says about the listeners somebody modelled. It is
   * <code>null</code> until a platform module hands one over. That is the default, and
   * then no listener is served.
   */
  private Camunda8AllowListenersResolver allowListenersResolver;

  /**
   * Hands over how <code>allow-listeners</code> resolves for this adapter instance.
   *
   * @param allowListenersResolver The resolver, or <code>null</code> for the default
   */
  public void setAllowListenersResolver(
      final Camunda8AllowListenersResolver allowListenersResolver) {

    this.allowListenersResolver = allowListenersResolver;

  }

  /**
   * Whether the listeners somebody modelled are served for one BPMN process, and which key
   * said so.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id
   * @return The setting, never <code>null</code>
   */
  private Camunda8AllowListenersResolver.Setting listenersAllowedFor(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return Camunda8AllowListenersResolver
        .resolve(allowListenersResolver, workflowModuleId, bpmnProcessId);

  }

  /**
   * What this adapter instance measures in addition to what the core measures. The
   * platform module hands it in after construction, because it exists once per
   * application, while deployment services exist per adapter id. It is
   * {@link Camunda8Metrics#NONE} for an application without a metrics backend.
   */
  private Camunda8Metrics metrics = Camunda8Metrics.NONE;

  /**
   * Hands over what to measure into, and registers the execution slots of this adapter
   * instance right away. The client is built before this, so there is nothing to wait
   * for.
   *
   * @param metrics What to measure into, never <code>null</code>
   */
  public void setMetrics(
      final Camunda8Metrics metrics) {

    this.metrics = metrics == null
        ? Camunda8Metrics.NONE
        : metrics;
    registerExecutionSlots();

  }

  /**
   * Publishes how many handlers this adapter instance may run, how many of them run right
   * now and how many jobs wait for a slot, plus how old the oldest running handler is and
   * how many handlers lost the lock of their job.
   * <p>
   * The first three come from the executor of the adapter. Both execution models build
   * one now. So a stalled application looks the same, whichever model is configured.
   * The last two come from the slot watch, which reads what the workflow modules have in
   * flight. An adapter which booted without a connection has neither. Then only the
   * configured number is published.
   */
  private void registerExecutionSlots() {

    final var executionModel = clientFactory.getExecutionModel();
    final var executor = clientFactory.getExecutor();
    metrics
        .registerExecutionSlots(
            adapterId,
            executionModel::slots,
            executor == null
                ? null
                : () -> executor.getBound() - executor.getFreeSlots(),
            executor == null
                ? null
                : executor::getWaiting);
    final var slotWatch = clientFactory.getSlotWatch();
    metrics
        .registerRunningExecutions(
            adapterId,
            slotWatch == null
                ? null
                : slotWatch::getOldestRunningSeconds,
            slotWatch == null
                ? null
                : slotWatch::getOverdueExecutions);

  }

  @Override
  public AdapterHealth checkHealth() {

    return Camunda8Health.check(adapterId, clientFactory);

  }

  /**
   * Where a finding of this adapter goes. It is <code>null</code> if the adapter runs
   * without a platform integration, as in a test which builds this service by hand. Then
   * a finding is written to the log, the way every finding was written before the
   * collected block existed.
   */
  private StartupReport startupReport;

  /**
   * Hands over where the findings of this adapter id go.
   *
   * @param startupReport The collection point both platform integrations publish, or
   *          <code>null</code> to write the findings to the log directly
   */
  public void setStartupReport(
      final StartupReport startupReport) {

    this.startupReport = startupReport;

  }

  /**
   * Where the findings of this adapter id go.
   * <p>
   * It can be read because the wiring of both platforms hands it over. If that wiring
   * breaks, the findings go into the log instead of the block. Both are one line, so nobody
   * would notice. So a test of a booting application asks this instead.
   *
   * @return The collection point this service reports into, or <code>null</code> where it
   *         writes its findings to the log
   */
  public StartupReport getStartupReport() {

    return startupReport;

  }

  /**
   * Says a finding about this adapter id. It goes into the collected block, if the
   * platform published one.
   *
   * @param topic The artifact the fix lies in
   * @param message The whole message, ending with what to do. It never carries the adapter
   *          id, because the scope does. So if two adapter ids find the same thing, there is
   *          one entry which names both
   */
  private void warnAboutThisAdapter(
      final StartupTopic topic,
      final String message) {

    final var scope = "camunda8 adapter '%s'".formatted(adapterId);
    if (startupReport != null) {
      startupReport.warn(topic, scope, message);
      return;
    }
    log.warn("{}: {}", scope, message);

  }

  /**
   * Whether a workflow module of this adapter id is opening its workers right now.
   * <p>
   * While it is, a worker which opens is not held against the pool yet. The check names a
   * number, and the number halfway through is not the one the module ends with. The module
   * asks the check itself once its last worker is open. One case is left: a worker which an
   * EXTENSION opens while a module starts. It is then counted with the workers of that
   * module.
   */
  private volatile boolean aModuleIsOpeningItsWorkers = false;

  /**
   * Whether this start was already told that the workers outgrew the connection pool. The
   * number grows with every workflow module. Saying it once per module would fill the block
   * with the same sentence and different numbers. The developer does the same thing either
   * way. After they raised the pool, the next start says how many are still missing, if
   * any.
   * <p>
   * Volatile, because an extension which opens a worker asks the check from its own
   * thread. It must see the answer to "was this said already" which the start wrote.
   */
  private volatile boolean saidThatTheWorkersOutgrewThePool = false;

  /**
   * Whether this start was already told that the shutdown grace is too short to drain
   * these workers. It is said once, for the same reason as the sentence above. The number
   * grows per workflow module, and the developer takes the same step at every one of them.
   */
  private volatile boolean saidThatTheGraceCannotDrainTheWorkers = false;

  /**
   * Compares the workers which are open on the client of this adapter id with its
   * connection pool, and says so if they do not fit. It looks at what a running application
   * waits for, and at what its shutdown needs.
   * <p>
   * The pool belongs to the client, and the client belongs to the adapter id. So the number
   * which counts covers all workflow modules of this service AND the workers which an
   * extension opened on the same client. It is known as soon as a module opened its
   * workers. That is why this is a startup check. Otherwise a running application would
   * find out the hard way, one <code>request-timeout</code> at a time (see
   * {@link Camunda8WorkerConnections}).
   * <p>
   * The grace is the second question about the same pool. It is asked here, not in the
   * startup validation of the configuration, because only here is the number of workers
   * known. The validation checks the grace against the request timeout. That is the lower
   * limit for a client whose workers fit into its pool once. Workers beyond the pool raise
   * that limit, and before this check nothing compared the grace with it.
   * <p>
   * A client which prefers gRPC activates its jobs over that transport. There this pool
   * does not limit the workers, so nothing is said for it. The drain of such a client has
   * nothing to wait for either, because the cluster releases a request of a gone gRPC
   * client.
   *
   * @param workers How many workers are open on that client
   * @param clientConfiguration The configuration of the client of this adapter id, as the
   *          CLIENT resolved it. So the pool is the configured one or the default of the
   *          client
   */
  void holdTheWorkersAgainstTheConnectionPool(
      final int workers,
      final CamundaClientConfiguration clientConfiguration) {

    if (!clientConfiguration.preferRestOverGrpc()) {
      return;
    }
    if (!saidThatTheWorkersOutgrewThePool) {
      final var message = Camunda8WorkerConnections.moreWorkersThanConnections(
          workers,
          clientConfiguration.getMaxHttpConnections(),
          clientConfiguration.getDefaultRequestTimeout());
      if (message != null) {
        saidThatTheWorkersOutgrewThePool = true;
        warnAboutThisAdapter(StartupTopic.CONFIGURATION, message);
      }
    }
    if (saidThatTheGraceCannotDrainTheWorkers) {
      return;
    }
    final var aboutTheGrace = Camunda8WorkerConnections.aGraceTooShortForTheDrain(
        workers,
        clientConfiguration.getMaxHttpConnections(),
        clientConfiguration.getDefaultRequestTimeout(),
        shutdownGrace(),
        Camunda8AdapterConfiguration.PLATFORM_SHUTDOWN_BUDGET);
    if (aboutTheGrace == null) {
      return;
    }
    saidThatTheGraceCannotDrainTheWorkers = true;
    warnAboutThisAdapter(StartupTopic.CONFIGURATION, aboutTheGrace);

  }

  /**
   * The same check, on the workers the client has open right now.
   * <p>
   * It runs at the end of the start of a module. It runs again whenever a worker opens
   * outside a module start. That is the worker of an EXTENSION, opened through
   * {@link Camunda8Workers#open(JobWorkerBuilderStep1.JobWorkerBuilderStep3, Camunda8ClientFactory)}
   * on the client of this adapter id. Such a worker holds a connection of the same pool. So
   * an application which fitted while only the adapter counted can cross the limit
   * afterwards, and without this check nothing would ever say so.
   */
  void holdTheOpenWorkersAgainstTheConnectionPool() {

    if (aModuleIsOpeningItsWorkers) {
      return;
    }
    holdTheWorkersAgainstTheConnectionPool(
        clientFactory.countTheOpenWorkers(),
        clientFactory.getClient().getConfiguration());

  }

  /**
   * What this adapter instance does with a task which the core reports as older than
   * <code>vanillabp.delivery.max-task-age</code>. It is read from the configuration of
   * the adapter, not passed through the wiring. Like every other adapter-level key, it
   * belongs to the connection. A setup without the resolver (tests) only reports.
   *
   * @return The action, never <code>null</code>
   */
  private Camunda8AdapterConfiguration.AsyncTaskMaxAgeAction asyncTaskMaxAgeAction() {

    if (configurations == null) {
      return Camunda8AdapterConfiguration.AsyncTaskMaxAgeAction.REPORT;
    }
    final var configuration = configurations.apply(adapterId);
    return (configuration == null) || (configuration.getAsyncTaskMaxAgeAction() == null)
        ? Camunda8AdapterConfiguration.AsyncTaskMaxAgeAction.REPORT
        : configuration.getAsyncTaskMaxAgeAction();

  }

  /**
   * How long the shutdown of this adapter instance waits for the handlers it has in
   * flight. It is read from the configuration of the adapter, not passed through the
   * wiring. Like every other adapter-level key, it belongs to the connection. A setup
   * without the resolver (tests) uses the default.
   *
   * @return The grace period, never <code>null</code>
   */
  private Duration shutdownGrace() {

    if (configurations == null) {
      return Camunda8AdapterConfiguration.DEFAULT_SHUTDOWN_GRACE;
    }
    final var configuration = configurations.apply(adapterId);
    return configuration == null
        ? Camunda8AdapterConfiguration.DEFAULT_SHUTDOWN_GRACE
        : configuration.resolvedShutdownGrace();

  }

  /**
   * What each workflow module of this adapter instance has in flight, and whether it is
   * going down. There is one per workflow module. The client factory holds it, because an
   * EXTENSION which serves jobs of the same module has to take part in the same drain.
   *
   * @param workflowModuleId The workflow module
   * @return The drain of that module, created on first use
   */
  Camunda8Drain drainOf(
      final String workflowModuleId) {

    return clientFactory.drainOf(workflowModuleId);

  }

  /**
   * What removes the shutdown hook which this adapter added to a workflow module. It is
   * kept per module from the moment its workers were opened. A module holds one hook per
   * party which opened workers of it. So the adapter removes ITS hook, not every hook of
   * the module.
   */
  private final Map<String, Camunda8ClientFactory.WorkflowModuleShutdownRegistration> shutdownRegistrations = new ConcurrentHashMap<>();

  /**
   * Resolves the job timeout per task from the levels of the adapter configuration
   * (task &gt; workflow &gt; workflow-module &gt; adapter, the most specific one wins).
   */
  private final Camunda8JobTimeoutResolver jobTimeoutResolver;

  /**
   * Resolves how long the cluster waits before it hands a FAILED job out again. It comes
   * from the same four levels as the job timeout. Unlike the timeout, it does not belong to
   * the worker but to each fail command. So the processes which one worker serves need not
   * agree on it. May be <code>null</code> (tests). Then the default of ten seconds applies.
   */
  private final Camunda8RetryBackoffResolver retryBackoffResolver;

  /**
   * A {@code @TaskId} handler leaves its job open. This is the window in which the lock of
   * such a job is renewed (see {@link Camunda8JobHandler}).
   */
  private final Duration asyncTaskLockRenewal;

  /**
   * Resolves the connection configuration of an adapter id. The platform supplies it, and
   * {@link #validateDistinctAdapterInstances(List)} uses it. May be <code>null</code>
   * (tests). Then the check is skipped.
   */
  private final Function<String, Camunda8AdapterConfiguration> configurations;

  /**
   * The core's name-clash-avoidance model: decides whether a workflow
   * module is isolated by a TENANT ({@code by-adapter}, version 1's behavior and this
   * adapter's default), by PREFIXING the identifiers ({@code use-prefix} - no tenant, which
   * is what makes tenant licenses avoidable) or not at all ({@code none}).
   * May be <code>null</code> (tests): nothing is scoped then.
   */
  private final NameClashAvoidanceSupport scoping;

  /**
   * The tenants already checked against the cluster. The cluster is asked once per tenant,
   * not once per workflow module, because several modules may share a configured tenant.
   */
  private final Set<String> verifiedTenants = ConcurrentHashMap.newKeySet();

  /**
   * The property keys whose tenant was already checked against the mode. It is a key, not a
   * flag. The name may come from the section of the adapter or from that of a workflow
   * module, and the message has to quote the one which is set.
   */
  private final Set<String> tenantKeysCheckedAgainstTheMode = ConcurrentHashMap.newKeySet();

  /**
   * What the tenant of a workflow module is CONFIGURED as. The platform modules resolve it
   * over the levels the name may be set at: the workflow module, then the adapter. It is
   * <code>null</code> for a module which no setting names a tenant for. Then the workflow
   * module ID names it, as in VanillaBP 1. It may be <code>null</code> itself (tests). Then
   * the section of the adapter is the only level.
   */
  private Function<String, Camunda8ConfiguredTenant> configuredTenants;

  /**
   * Builds the service of one adapter id. The platform bean of Spring Boot or Quarkus calls
   * it with everything the application configured. The last three arguments may be
   * <code>null</code>, and each one says here what that means.
   *
   * @param adapterId The configured adapter id this service deploys for
   * @param clientFactory The clients of that adapter id
   * @param collaborators What the platform integration hands every adapter
   * @param jobTimeoutResolver Answers how long a job of a task stays locked
   * @param asyncTaskLockRenewal How far a probe pushes the lock of a job it looks at
   * @param configurations The adapter section per adapter id, or <code>null</code> to leave two ids of
   *          this type unchecked for distinctness
   * @param scoping How identifiers are kept apart where two adapter ids share a cluster, or
   *          <code>null</code> for none
   * @param retryBackoffResolver Answers how long the cluster waits before it offers a failed job again,
   *          or <code>null</code> for the cluster's own backoff
   */
  public Camunda8DeploymentService(
      final String adapterId,
      final Camunda8ClientFactory clientFactory,
      final AdapterCollaborators collaborators,
      final Camunda8JobTimeoutResolver jobTimeoutResolver,
      final Duration asyncTaskLockRenewal,
      final Function<String, Camunda8AdapterConfiguration> configurations,
      final NameClashAvoidanceSupport scoping,
      final Camunda8RetryBackoffResolver retryBackoffResolver) {

    this.retryBackoffResolver = retryBackoffResolver;

    VanillaBpParts.requireAdapterFitsPlatform(ADAPTER_TYPE, Camunda8DeploymentService.class);

    // which release line this application runs, once per adapter id. The client named
    // here is the LOWEST cluster version these artifacts accept. A reader who compares it
    // with their cluster sees at a glance whether they are on the right line
    log.info(
        "Camunda8[{}]: release line {} of the adapter, built against Camunda client {}, "
            + "which is the lowest cluster version it accepts",
        adapterId,
        Camunda8ReleaseLine.id(),
        Camunda8ReleaseLine.clientVersion());

    this.adapterId = adapterId;
    this.clientFactory = clientFactory;
    this.collaborators = collaborators;
    this.workflowTaskWiring = collaborators.workflowTaskWiring();
    this.workflowTaskInvoker = collaborators.workflowTaskInvoker();
    this.bpmsInitiatedStartInvoker = collaborators.bpmsInitiatedStartInvoker().orElse(null);
    this.workflowEndedInvoker = collaborators.workflowEndedInvoker().orElse(null);
    this.jobTimeoutResolver = jobTimeoutResolver;
    this.asyncTaskLockRenewal = asyncTaskLockRenewal;
    this.configurations = configurations;
    this.scoping = scoping;
    // the versions of the process definitions in the cluster. The version travels with
    // every job. The version TAGS come from here
    this.processVersions = new Camunda8ProcessVersions(
        adapterId, clientFactory::getClient, this::scopedProcessId, this::tenantIdOf);
    // which models the cluster holds for the ids this application declares. Every check
    // of a model asks this picture. So no verdict depends on which application version
    // deployed the model (see decision 21 in the repository's DECISIONS.md). It is built
    // here, because only the deployment service can read its cluster. It is handed to the
    // factory, because the message check of the process service reads it
    clientFactory
        .provideModelsTheClusterHolds(
            new Camunda8ModelsTheClusterHolds(
                adapterId, clientFactory.getDeployedProcesses(), this::readModelsTheClusterHolds));
    // and what happens when a worker opens on this client. The workers share its
    // connection pool. If an extension opens one after the start, nothing else would ever
    // compare it with that pool
    clientFactory.provideTheOpenWorkerCheck(this::holdTheOpenWorkersAgainstTheConnectionPool);
    // and how the version catalog gets one model out of that picture. It is set here, not
    // while a process is wired. The questions it serves are also asked about ids which
    // this application wires nothing for
    processVersions.setHeldModelOfVersion(this::heldModelOfVersion);
    // the wiring reads the model this boot brings. The same reading serves the models of
    // OLDER versions, so both directions see a model the same way. It is handed over here,
    // not while a process is wired, for the reason above. The check reads versions of an
    // id which this application may bring no model for at all
    processVersions.setTasksOfModel(this::taskSpecsOf);
    processVersions.setStartEventsOfModel(this::startEventSpecsOf);
    processVersions.setConcurrentTokenElementsOfModel(this::concurrentTokenElementIdsOf);
    processVersions.setIdentifiersOfModel(this::identifiersOfModel);

  }

  /**
   * The identifiers which ONE version the cluster still holds declares, as the application
   * knows them. The core compares them with the names another workflow module uses today.
   * A job type is among them, and it is the one which is live, not dormant. A worker
   * subscribes to it across the cluster. So a version which workflows still run on owns
   * that name as much as the model deployed now.
   * <p>
   * The names are read off the model the cluster hands back. That model carries the scoped
   * forms which the deployment wrote into it. They are turned back into the plain ones,
   * because the core builds the scoped forms itself.
   */
  private Collection<NameClashAvoidanceSupport.ModelIdentifier> identifiersOfModel(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final BpmnModelInstance model) {

    final var identifiers = new LinkedHashSet<NameClashAvoidanceSupport.ModelIdentifier>();
    Camunda8Scoping
        .moduleWideIdentifiersOf(model)
        .forEach(identifier -> identifiers
            .add(
                new NameClashAvoidanceSupport.ModelIdentifier(
                    identifier.kind(), plainIdentifier(workflowModuleId, identifier.plainIdentifier()), null)));
    // the job types of THIS process, read the same way the wiring reads them. So the form
    // reference of a user task counts as the listener job type it becomes. And an element
    // which another runtime serves stays out
    taskSpecsOf(workflowModuleId, bpmnProcessId, version, model)
        .stream()
        .map(BpmnTaskSpec::taskDefinition)
        .filter(Objects::nonNull)
        .forEach(taskDefinition -> identifiers
            .add(
                new NameClashAvoidanceSupport.ModelIdentifier(
                    NameClashAvoidanceSupport.ScopedIdentifierKind.TASK_DEFINITION, taskDefinition, bpmnProcessId)));
    return identifiers;

  }

  /**
   * The model of ONE version the cluster holds. It is taken from the picture of what the
   * cluster holds for the ids this application declares (see decision 21 in the
   * repository's DECISIONS.md). So a question about a held version and a check against
   * one read the same models.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version the cluster assigned
   * @return The model, or <code>null</code> where the cluster could not be asked or does
   *         not hold that version any more
   */
  private BpmnModelInstance heldModelOfVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    final var modelsTheClusterHolds = clientFactory.getModelsTheClusterHolds();
    if (modelsTheClusterHolds == null) {
      return null;
    }
    final var answer = modelsTheClusterHolds.heldFor(workflowModuleId, bpmnProcessId);
    if (!(answer instanceof Camunda8ModelsTheClusterHolds.Answer.Known known)) {
      return null;
    }
    return known
        .models()
        .stream()
        .filter(heldModel -> heldModel.version().equals(version))
        .map(Camunda8ModelsTheClusterHolds.HeldModel::model)
        .findFirst()
        .orElse(null);

  }

  /**
   * The start events which the cluster fires on its own in a model it holds. They are
   * read the same way the wiring reads the model being deployed, so both directions speak
   * about the same thing. The model is only read. It carries the execution listener of
   * the deployment which brought it, and nothing here adds one.
   */
  private Collection<BpmsInitiatedStartSpec> startEventSpecsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final BpmnModelInstance model) {

    final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);
    return Camunda8TaskWiring
        .bpmsInitiatedStartsOfHeldModel(
            model,
            scopedBpmnProcessId,
            signalName -> plainIdentifier(workflowModuleId, signalName))
        .stream()
        .map(startEvent -> new BpmsInitiatedStartSpec(
            startEvent.startEventId(), startEvent.kind(), startEvent.signalName()))
        .toList();

  }

  /**
   * The elements which can put a SECOND token into a workflow of a model the cluster
   * holds. It is the same walk the wiring runs over the model being deployed. A walk over
   * the model of this boot never reaches the versions which run longest. A parallel
   * gateway which the newest model dropped still forks every workflow started before it.
   */
  private Collection<String> concurrentTokenElementIdsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final BpmnModelInstance model) {

    final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);
    final var elementIds = new java.util.LinkedHashSet<>(
        Camunda8TaskWiring.concurrentTokenElementIdsOf(model, scopedBpmnProcessId));
    // a version the cluster still holds carries its compensation flat, as element ids among
    // the others. The detailed report belongs to the model this boot deploys, because a
    // developer can still redraw that one. For an older version, there is only one thing to
    // say: its workflows can hold two tokens. Carrying the detail here as well would need a
    // second method on the version catalog. The flat list has no room for which throw event
    // starts which handlers. And nothing an old version could answer would change what a
    // developer does about it
    Camunda8TaskWiring
        .compensationOf(model, scopedBpmnProcessId)
        .stream()
        .filter(compensation -> compensation.handlerIds().size() > 1)
        .forEach(compensation -> {
          elementIds.add(compensation.throwEventId());
          elementIds.addAll(compensation.handlerIds());
        });
    return List.copyOf(elementIds);

  }

  /**
   * Reads the models of every version the cluster holds under one BPMN process id. The
   * picture of {@link Camunda8ModelsTheClusterHolds} is built from them. The version this
   * boot deployed comes from the local record and is not fetched again.
   */
  private List<Camunda8ModelsTheClusterHolds.HeldModel> readModelsTheClusterHolds(
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (!clientFactory.getQueryApi().answers()) {
      // that this cluster cannot be searched was said while the module deployed
      // (see decision 20 in the repository's DECISIONS.md). An adapter degraded to
      // 'warn' keeps booting, and every check which reads the picture stays silent
      return null;
    }
    final var deployed = clientFactory
        .getDeployedProcesses()
        .deployedVersionOf(workflowModuleId, bpmnProcessId);
    return processVersions
        .versionsHeldUnder(workflowModuleId, bpmnProcessId)
        .stream()
        .map(version -> {
          if ((deployed != null) && String.valueOf(deployed.version()).equals(version)) {
            return new Camunda8ModelsTheClusterHolds.HeldModel(bpmnProcessId, version, deployed.model());
          }
          final var model = processVersions.modelOfVersion(workflowModuleId, bpmnProcessId, version);
          return model == null
              ? null
              : new Camunda8ModelsTheClusterHolds.HeldModel(bpmnProcessId, version, model);
        })
        .filter(Objects::nonNull)
        .toList();

  }

  /**
   * The versions of the process definitions in this cluster. The core resolves version
   * TAGS through this catalog. The version itself travels with every job.
   */
  private final Camunda8ProcessVersions processVersions;

  /**
   * What the cluster holds for a BPMN process which this application declares without
   * deploying a model under it. An example is the old id of a renamed process. The cluster
   * keeps every version ever deployed under it, and the workflows which still run on them.
   * <p>
   * It is the same catalog with which every deployed process of this adapter is
   * registered. It searches by the process id as the cluster knows it. So a prefix and a
   * tenant apply to the old id like to any other.
   */
  @Override
  public io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog processVersionCatalogOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return processVersions;

  }

  /**
   * The BPMN process id as the CLUSTER knows it. The model carries the scoped ids after
   * {@code prepareBpmn}, while the core is keyed by the plain ones.
   */
  private String scopedProcessId(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return NameClashAvoidanceSupport.scopedProcessId(scoping, workflowModuleId, bpmnProcessId, adapterId);

  }

  /**
   * The inverse of {@link #scopedProcessId}.
   */
  private String plainProcessId(
      final String workflowModuleId,
      final String scopedBpmnProcessId) {

    return NameClashAvoidanceSupport
        .plainProcessId(scoping, workflowModuleId, scopedBpmnProcessId, adapterId);

  }

  /**
   * The identifier as the application modelled it. The model carries the scoped one if
   * the workflow module prefixes its identifiers.
   */
  private String plainIdentifier(
      final String workflowModuleId,
      final String scopedIdentifier) {

    // the null check is about the IDENTIFIER, not about the support. A name the model does
    // not carry stays absent, and a test double of the support need not answer for one
    return scopedIdentifier == null
        ? null
        : NameClashAvoidanceSupport
            .plainIdentifier(scoping, workflowModuleId, scopedIdentifier, adapterId);

  }

  /**
   * The task definition as the core knows it. The model carries the scoped one, and so
   * does the job type a worker subscribes to.
   */
  private String plainTaskDefinition(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedTaskDefinition) {

    return NameClashAvoidanceSupport
        .plainTaskDefinition(scoping, workflowModuleId, bpmnProcessId, scopedTaskDefinition, adapterId);

  }

  /**
   * Sets the tenant names the application configured. The platform modules read them from
   * the configuration, per workflow module.
   *
   * @param configuredTenants What a workflow module's tenant is configured as
   */
  public void setConfiguredTenants(
      final Function<String, Camunda8ConfiguredTenant> configuredTenants) {

    this.configuredTenants = configuredTenants;

  }

  /**
   * What the application configured as the tenant of one workflow module, with the key it
   * wrote it under, or <code>null</code>. Without the resolver of the platform, only the
   * section of the adapter is read. That is the case in a test built without a platform.
   */
  private Camunda8ConfiguredTenant configuredTenantOf(
      final String workflowModuleId) {

    if (configuredTenants != null) {
      return configuredTenants.apply(workflowModuleId);
    }
    return Camunda8ConfiguredTenant
        .firstConfigured(
            adapterId,
            workflowModuleId,
            null,
            clientFactory == null
                ? null
                : clientFactory
                    .getConfiguration()
                    .getTenantId());

  }

  /**
   * Fails the boot if a tenant is configured, but no workflow module is deployed into one.
   * That means the mode says {@code none} or {@code use-prefix} everywhere. This adapter
   * knows that only {@code by-adapter} can use a tenant. The core answers which modes apply.
   * It is checked while deploying, before anything reaches the cluster. It is checked once
   * per property key which set a name. The section of the adapter and the section of a
   * workflow module are two different lines for the developer to go to.
   *
   * @param workflowModuleId The workflow module being deployed
   */
  private void validateTenantConfiguration(
      final String workflowModuleId) {

    if (scoping == null) {
      return;
    }
    final var configured = configuredTenantOf(workflowModuleId);
    if ((configured == null) || !tenantKeysCheckedAgainstTheMode.add(configured.propertyKey())) {
      return;
    }
    scoping.validateNoneNameClashStrategy(adapterId, configured.propertyKey());

  }

  /**
   * The tenant a workflow module is deployed to, and in which its operations run. The
   * name-clash-avoidance mode decides it. Under {@code by-adapter}, the configured
   * <code>tenant-id</code> names it.
   *
   * @param workflowModuleId The workflow module ID
   * @return The tenant ID or <code>null</code> if no tenant is used
   */
  private String tenantIdOf(
      final String workflowModuleId) {

    final var configured = configuredTenantOf(workflowModuleId);
    return Camunda8Scoping.tenantIdFor(
        scoping, workflowModuleId, adapterId, configured != null
            ? configured.tenantId()
            : null);

  }

  /**
   * Whether the isolation of this adapter would put the two workflow modules into different
   * scopes of its cluster. The scope a Camunda 8 cluster offers is the TENANT. So the
   * answer compares the tenants which the two modules would REALLY be deployed to.
   * <p>
   * Both tenants are resolved through {@code tenantIdOf}, the same function the deploy
   * command uses. So this answer cannot drift away from where a module lands. Reading the
   * configured <code>tenant-id</code> instead would be wrong in two ways. Under
   * {@code by-adapter}, an unset name means the workflow module id, so two modules do sit
   * in two tenants. And a name which IS set is used only if the mode of the module asks
   * for a tenant at all. The mode is resolved per workflow module, so two modules of one
   * adapter id may differ in it. The same holds for the name itself. A workflow module may
   * carry a name of its own, and that is the way out which the refusal of the core
   * recommends.
   * <p>
   * A module under {@code use-prefix} or {@code none} reaches the cluster with no tenant of
   * its own, so it lands in the <code>&lt;default&gt;</code> one. Here that is a scope like
   * any other. Two such modules share it and are NOT separated. One of them and a module
   * with a tenant are separated. On a cluster without multi-tenancy every module lives in
   * that one unnamed scope, because such a cluster rejects a tenant id. So nothing
   * separates anybody there, and this method says so. Why the answer is the resolved
   * tenant is decision 26 in the repository's DECISIONS.md.
   * <p>
   * The core asks this while it checks whether two BPMN processes reach the cluster under
   * one identifier. The core keeps the answer per pair of modules. So nothing is asked of
   * the cluster here and nothing is remembered. Both tenants come from configuration, which
   * does not change while an application boots.
   */
  @Override
  public boolean ownIsolationSeparatesWorkflowModules(
      final String oneWorkflowModuleId,
      final String anotherWorkflowModuleId) {

    return !Objects.equals(tenantIdOf(oneWorkflowModuleId), tenantIdOf(anotherWorkflowModuleId));

  }

  /**
   * Two <code>camunda8</code> adapter ids are only distinct if they address different
   * clusters, or one cluster with different credentials or tenants (see
   * {@link Camunda8InstanceIdentity}).
   */
  @Override
  public void validateDistinctAdapterInstances(
      final List<String> adapterIdsOfThisType) {

    Camunda8InstanceIdentity
        .validateDistinct(adapterIdsOfThisType, configurations, scoping);

  }

  /**
   * Camunda 8 keeps the default of the SPI, {@code by-adapter}. On this BPMS it means a
   * TENANT named after the workflow module. VanillaBP 1 deployed the same when nothing was
   * configured: its {@code use-tenants} was on, and the tenant id defaulted to the workflow
   * module id. So an application which upgrades from version 1 without touching its
   * configuration still addresses the workflows it started back then.
   * <p>
   * <b>What it asks of the cluster.</b> A tenant id other than {@code <default>} needs
   * multi-tenancy enabled, and the tenant has to exist. A cluster started from the stock
   * image answers a deploy command which carries one with "multi-tenancy is disabled".
   * {@link Camunda8TenantCheck} turns that into a boot failure which names the properties
   * that lead out. That is the point. The other choice would be a default which quietly
   * deploys every workflow module into the {@code <default>} tenant. That is not a weaker
   * isolation but none at all. It is {@code none} by another name, without the warning
   * which {@code none} gives.
   * <p>
   * An application on such a cluster says so once, with
   * {@code vanillabp.adapters.<id>.name-clash-avoidance: none} ({@code use-tenants: false}
   * in version 1) or with {@code use-prefix}, which needs no cluster support at all. This
   * default stood at {@code none} between 2026-08-11 and 2026-08-22, and that was the
   * defect. {@code Camunda8DeploymentServiceTest} now holds the default.
   */
  @Override
  public NameClashAvoidance defaultNameClashAvoidance() {

    return NameClashAvoidance.BY_ADAPTER;

  }

  /**
   * Names what Camunda 8 offers instead of {@code none}: prefixing, a tenant per
   * workflow module (which needs a cluster with multi-tenancy enabled), or a cluster
   * per workflow module.
   * <p>
   * Silent if the application accepted unscoped identifiers on purpose
   * ({@code vanillabp.adapters.<id>.accept-unscoped-identifiers}). The warning asks for
   * a DECISION. Once the decision is on record, there is nothing left to ask.
   */
  @Override
  public void warnAboutUnscopedIdentifiers(
      final String workflowModuleId,
      final boolean fromDefault) {

    if (clientFactory
        .getConfiguration()
        .isAcceptUnscopedIdentifiers()) {
      log.debug(
          "Camunda8[{}]: workflow module '{}' is deployed with name-clash-avoidance 'none', accepted by "
              + "'{}'",
          adapterId,
          workflowModuleId,
          Camunda8AdapterConfiguration
              .propertyKey(adapterId, "accept-unscoped-identifiers"));
      return;
    }
    log.warn(
        """
            Workflow module '{}' is deployed to Camunda 8 (adapter '{}') with name-clash-avoidance \
            'none'{}. Its identifiers reach the cluster as they are - BPMN process ids, message and \
            signal names, error codes, job types and user-task form references - so a second workflow \
            module using the same identifier addresses the very same processes and jobs, and neither \
            VanillaBP nor the cluster can tell. Keep 'none' only as long as your identifiers are \
            unique across ALL workflow modules of this application. Otherwise choose:
              vanillabp.adapters.{}.name-clash-avoidance: use-prefix   # VanillaBP prefixes the identifiers, no tenant needed
              vanillabp.adapters.{}.name-clash-avoidance: by-adapter   # a tenant per workflow module - only on a cluster with multi-tenancy enabled
            A third option is a Camunda 8 cluster per workflow module, configured as one adapter id \
            per cluster. The same key may be set per workflow module \
            (vanillabp.workflow-modules.{}.adapters.{}.name-clash-avoidance). The mode is not a \
            runtime switch - changing it once workflows are running is a BPMS migration. If the \
            identifiers ARE unique, say so once and this warning is gone:
              vanillabp.adapters.{}.accept-unscoped-identifiers: true""",
        workflowModuleId,
        adapterId,
        fromDefault
            ? " (nothing is configured, so the adapter's default applies)"
            : "",
        adapterId,
        adapterId,
        workflowModuleId,
        adapterId,
        adapterId);

  }

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  @Override
  public String getAdapterType() {

    return ADAPTER_TYPE;

  }

  @Override
  public Class<BpmnModelInstance> getModelType() {

    return BpmnModelInstance.class;

  }

  @Override
  public Class<Camunda8ProcessingContext> getProcessContextType() {

    return Camunda8ProcessingContext.class;

  }

  @Override
  public List<Map.Entry<String, BpmnModelInstance>> readBpmn(
      final String workflowModuleId,
      final String filename,
      final InputStream bpmn,
      final boolean isVanillaBpBpmn) throws BpmnParseException {

    final BpmnModelInstance model;
    try {
      model = Bpmn.readModelFromStream(bpmn);
    } catch (final RuntimeException e) {
      throw new BpmnParseException(
          "Failed to parse BPMN file '%s' of workflow module '%s'!".formatted(filename, workflowModuleId), e);
    }

    // one entry per executable process. The value is always the whole model, because
    // Camunda 8 deploys the entire file as one resource. A file may hold several
    // executable processes
    final var executableProcesses = new ArrayList<Map.Entry<String, BpmnModelInstance>>();
    for (final var process : model.getModelElementsByType(Process.class)) {
      if (!process.isExecutable()) {
        continue;
      }
      executableProcesses.add(Map.entry(process.getId(), model));
    }
    return executableProcesses;

  }

  @Override
  public Camunda8ProcessingContext prepareBpmn(
      final String workflowModuleId,
      final Camunda8ProcessingContext existingContext,
      final String filename,
      final String bpmnProcessId,
      final BpmnModelInstance model) {

    // the core passes null for the first BPMN process of a workflow module
    final var context = existingContext != null
        ? existingContext
        : new Camunda8ProcessingContext(adapterId, workflowModuleId, multiInstanceRegistry);
    // Rewrite the identifiers which the cluster resolves globally BEFORE wiring. So
    // everything after it (wiring validation, listener injection, workers) sees what the
    // cluster will see. It does nothing unless the mode is 'use-prefix'. The core calls
    // prepareBpmn once per executable PROCESS, but all processes of a file share ONE
    // model. So scoping has to happen once per FILE. Otherwise a file with several
    // processes would collect one prefix per process.
    final var modelAlreadyScoped = context
        .getResources()
        .containsKey(filename);
    if (!modelAlreadyScoped) {
      // the file is read for one thing which only the untouched model can say. An expression
      // which names a called process or decision may build the prefix itself. The rewrite
      // below would then give it a second one
      refuseAnExpressionWhichAlreadyCarriesThePrefix(workflowModuleId, filename, model);
      // and for the job types written as an expression. No worker of this application can
      // subscribe to such a name. It is asked here, before the rewrite and before the wiring
      // validation. So the message quotes what the modeller typed and says what is really
      // wrong. Otherwise it would ask for a method nobody can write
      refuseJobTypesWrittenAsAnExpression(workflowModuleId, filename, model);
      // and for the form references written as an expression, for the same reason. The
      // reference of a user task is its task definition, and a task definition is a name
      refuseFormReferencesWrittenAsAnExpression(workflowModuleId, filename, model);
      // read while the process ids are still the plain ones, and once per FILE, not once
      // per process. After the rewrite below, an element cannot be matched any more to the
      // process which keys the configuration
      recordTheElementsAnotherRuntimeServes(workflowModuleId, model, context);
      // read while the job types of the listeners are still the ones the modeller typed.
      // And read before the listeners which VanillaBP writes itself are in the model.
      // wireBpmn adds those, so after it only their prefix could tell the two apart
      refuseAStartListenerTheClusterRefuses(workflowModuleId, filename, model);
      readTheListenersTheModelCarries(workflowModuleId, filename, model, context);
      // the names this file declares which the workflow module scopes. They are read while
      // they are still the ones the application knows. The rewrite below replaces them in
      // the model, and the core gets the plain ones
      context.recordIdentifiersAModelDeclares(Camunda8Scoping.moduleWideIdentifiersOf(model));
      context
          .recordIdentifiersAModelDeclares(
              Camunda8Scoping.taskDefinitionsOf(model, workflowModuleId, allowConnectorsResolver));
      Camunda8Scoping
          .apply(
              model,
              workflowModuleId,
              adapterId,
              scoping,
              allowConnectorsResolver,
              servedListenerJobTypesOf(workflowModuleId));
    }
    context.addResource(filename, model);
    context.recordDeployedProcess(bpmnProcessId);
    return context;

  }

  @Override
  public Camunda8ProcessingContext readDmn(
      final String workflowModuleId,
      final Camunda8ProcessingContext existingContext,
      final String filename,
      final java.io.InputStream dmn) {

    // the decision travels as bytes. The cluster reads it. This adapter only has to make
    // sure that the id it is deployed under matches what the business rule task points at
    final var file = io.vanillabp.integration.adapter.spi.DmnDecisionIds.bytesOf(dmn);
    final var prefixes = Camunda8Scoping.prefixes(workflowModuleId, adapterId, scoping);
    final var toDeploy = prefixes
        ? io.vanillabp.integration.adapter.spi.DmnDecisionIds
            .rewrite(file, id -> scoping.scopedIdentifier(workflowModuleId, id, adapterId))
        : file;
    if (prefixes) {
      log.debug(
          "Camunda8[{}]: the decisions of '{}' are deployed under prefixed ids ({}), matching the "
              + "'zeebe:calledDecision' of the business rule tasks calling them",
          adapterId,
          filename,
          io.vanillabp.integration.adapter.spi.DmnDecisionIds.of(toDeploy));
    }
    existingContext.addDecision(filename, toDeploy);
    // the ids as the application knows them, read off the FILE and not off the answer of the
    // deploy command. The command sends these bytes. So nothing read here can name a decision
    // which the cluster never got. The cluster answers with the id IT knows, and the plain id
    // would have to be won back by stripping the prefix off again
    existingContext
        .recordDecisionIds(io.vanillabp.integration.adapter.spi.DmnDecisionIds.of(file));
    return existingContext;

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final BpmnModelInstance model,
      final Camunda8ProcessingContext context) {

    // the model carries the identifiers the CLUSTER will know (prepareBpmn rewrote
    // them in mode 'use-prefix'). The core is keyed by the plain ones. So the model is
    // searched by the SCOPED process id, and the invoker is called with the plain one
    final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);
    // a process nobody claims travels with its file and is left as it was modelled. No
    // listener and no mapping is written into it, and no worker serves its jobs. No check
    // ends the boot or warns because of it (see decision 73 of DECISIONS.md). The core has
    // already ended the start over it, unless the application marked it as somebody else's
    if (!workflowTaskWiring.isClaimedByAWorkflowService(workflowModuleId, bpmnProcessId)) {
      log.debug(
          "Camunda8[{}]: BPMN process '{}' of file '{}' (workflow module '{}') is claimed by no "
              + "@WorkflowService and is deployed as it was modelled",
          adapterId,
          bpmnProcessId,
          filename,
          workflowModuleId);
      return;
    }
    // the cluster runs an activity which carries a standard loop once, and says nothing.
    // This is asked first. No other finding about this model matters while it does not do
    // what was drawn
    final var standardLoops = Camunda8StandardLoops.elementIdsOf(model, scopedBpmnProcessId);
    if (!standardLoops.isEmpty()) {
      throw new IllegalStateException(
          Camunda8StandardLoops.refusal(standardLoops, bpmnProcessId, workflowModuleId));
    }
    // extract the job-worker tasks (zeebe:taskDefinition type = VanillaBP task
    // definition) and check them against the registered @WorkflowTask methods.
    // Throwing here follows the deployment-failure policy
    final var connectorsAreAllowed = connectorsAllowedFor(workflowModuleId, bpmnProcessId).allowed();
    final var tasks = Camunda8TaskWiring.tasksOf(model, scopedBpmnProcessId, connectorsAreAllowed);
    // user tasks managed by Camunda. The lifecycle task listeners, which behave as in V1,
    // are ADDED TO THE MODEL here. wireBpmn is the stage of the pipeline which changes the
    // BPMN. deployResources deploys the changed model
    final var userTasks = Camunda8TaskWiring.userTasksOf(model, scopedBpmnProcessId, workflowModuleId, filename);
    final var specs = new ArrayList<BpmnTaskSpec>();
    tasks
        .stream()
        .map(task -> new BpmnTaskSpec(
            task.activityId(), plainTaskDefinition(workflowModuleId, bpmnProcessId, task.taskDefinition())))
        .forEach(specs::add);
    userTasks
        .stream()
        .map(userTask -> BpmnTaskSpec.userTask(
            userTask.activityId(),
            plainTaskDefinition(workflowModuleId, bpmnProcessId, userTask.externalFormReference())))
        .forEach(specs::add);
    // from here on, a listener somebody modelled is a task like any other. prepareBpmn read
    // it while the model was still as the modeller left it. Passing it through the validation
    // of the core gives both directions for free. A listener which nothing serves ends the
    // boot. A method which serves no listener of any wired process is reported
    final var listenersOfThisProcess = context
        .getModelledListeners()
        .stream()
        .filter(listener -> bpmnProcessId.equals(listener.bpmnProcessId()))
        .toList();
    listenersOfThisProcess
        .stream()
        .map(listener -> BpmnTaskSpec.listener(listener.elementId(), listener.taskDefinition(), null))
        .forEach(specs::add);
    // and a listener which no method names goes the same way. The core asks for a method, or
    // for the property which says that something else answers its job. It ends the boot if
    // neither is there
    context
        .getListenersNothingHereServes()
        .stream()
        .filter(listener -> bpmnProcessId.equals(listener.bpmnProcessId()))
        .map(listener -> BpmnTaskSpec.listener(listener.elementId(), listener.taskDefinition(), null))
        .forEach(specs::add);
    // the validation of the core below knows nothing about element templates. A sentence
    // about Camunda 8 in its message would be wrong for every other BPMS. So the adapter
    // says the missing half first, and the developer reads the guidance above the failure
    if (!connectorsAreAllowed) {
      guideTowardsAllowingConnectors(workflowModuleId, bpmnProcessId, scopedBpmnProcessId, model);
    }
    // nothing here serves an ad-hoc subprocess with a job of its own. If the application says
    // that something else serves it, it is a task like any other for the core. The core then
    // refuses a method next to that setting. The others are refused further down
    final var adHocSubProcessesWithAJob = Camunda8TaskWiring.unservedAdHocSubProcessIdsOf(model, scopedBpmnProcessId);
    final var adHocSubProcessesServedElsewhere = adHocSubProcessesWithAJob
        .stream()
        .map(elementId -> new BpmnTaskSpec(elementId, null))
        .filter(spec -> workflowTaskWiring.isImplementedExternally(adapterId, workflowModuleId, bpmnProcessId, spec))
        .toList();
    specs.addAll(adHocSubProcessesServedElsewhere);
    workflowTaskWiring.validateTaskWiring(adapterId, workflowModuleId, bpmnProcessId, specs);
    // a listener job is completed the moment the method returns. So a method which declares
    // @TaskId would wait for a completion nobody can send. The core is asked here, while a
    // modeller can still change the model, not at the first job
    refuseAsynchronousListenerMethods(workflowModuleId, bpmnProcessId, listenersOfThisProcess);
    // the cluster can be asked which versions of this process it has. A version
    // specification which names a version TAG needs that
    workflowTaskWiring
        .registerProcessVersions(adapterId, workflowModuleId, bpmnProcessId, processVersions);

    // which elements can put a second token into a running workflow. Two tokens are two
    // writers on the workflow aggregate, and the core knows whether that aggregate can
    // survive them
    workflowTaskWiring
        .reportConcurrentTokenElements(
            workflowModuleId,
            bpmnProcessId,
            Camunda8TaskWiring.concurrentTokenElementIdsOf(model, scopedBpmnProcessId));

    // compensation is the same second token, drawn in another way, and it needs its shape.
    // A throw event which compensates two finished activities starts both handlers. The
    // developer has to read WHICH event starts WHICH handlers
    workflowTaskWiring
        .reportCompensation(
            workflowModuleId,
            bpmnProcessId,
            Camunda8TaskWiring.compensationOf(model, scopedBpmnProcessId));

    // the expressions with which the model reads the data of the workflow. The core judges
    // them. An expression which reads more than the name of one variable ties the model to
    // the shape of the application's data and to FEEL. This is asked HERE, before the
    // correlation keys and the multi-instance mappings below are written into the model. So
    // the core gets the expressions of the modeller, not those of this adapter
    workflowTaskWiring
        .reportModelExpressions(
            workflowModuleId,
            bpmnProcessId,
            Camunda8ModelExpressions.of(model, scopedBpmnProcessId));

    // a user task without 'zeebe:userTask' is a user task which a job worker serves. This
    // adapter does not accept that shape in a process the application stands in for
    refuseJobWorkerUserTasks(
        workflowModuleId,
        bpmnProcessId,
        scopedBpmnProcessId,
        Camunda8TaskWiring.jobWorkerUserTasksOf(model, scopedBpmnProcessId));

    // under 'use-prefix', a called process or decision may be named by FEEL. Then its prefix
    // is written INSIDE the expression, so the cluster holds more than the developer typed.
    // Nothing is said about it while it works. The elements are remembered for the one
    // message where it matters: a deployment which the cluster refuses over an expression
    context
        .recordElementsNamingTheirTargetByExpression(
            bpmnProcessId,
            Camunda8Scoping.elementIdsNamingTheirTargetByExpression(model, scopedBpmnProcessId));

    // an ad-hoc subprocess which waits for a job worker is the other element which would
    // stop a workflow, and nothing would say so
    refuseUnservedAdHocSubProcesses(
        workflowModuleId,
        bpmnProcessId,
        adHocSubProcessesWithAJob
            .stream()
            .filter(elementId -> adHocSubProcessesServedElsewhere
                .stream()
                .noneMatch(spec -> spec.activityId().equals(elementId)))
            .toList());

    // message correlation: write the correlation-key expression
    // '=<aggregate-ID variable>' into each message subscription which has none. This V2
    // convention makes ProcessService#correlateMessage work without changing the model
    // by hand. Existing expressions stay as they are, and V1 models deploy unchanged.
    // Asking the core directly is safe here. Only a claimed process gets this far, and a
    // claimed process has a workflow aggregate to name
    Camunda8TaskWiring.wireMessageSubscriptions(
        model,
        scopedBpmnProcessId,
        () -> workflowTaskWiring.resolveWorkflowAggregateIdName(workflowModuleId, bpmnProcessId));
    // multi-instance: the input mappings are ADDED TO THE MODEL here. They let a job read
    // the element, the index and the total of every iteration. Which iterations enclose
    // which element is remembered for dispatch
    Camunda8MultiInstance
        .wire(model, scopedBpmnProcessId, multiInstanceRegistry);
    // a handler reads the item of an iteration out of the variable which the model names in
    // 'inputElement'. An element which names none hands no item over. Then the parameter
    // would receive null once a job arrives, and nothing would say why. Only this adapter
    // reads the model, and only the core scans the handlers. This is the one place where
    // the two halves meet
    refuseHandlersWantingAnItemTheModelHasNot(workflowModuleId, bpmnProcessId, scopedBpmnProcessId, specs);
    // the same question about the iterations of the processes which call this one is asked
    // once the call graph of the whole module is linked. It needs these specs then
    context.recordTaskSpecs(bpmnProcessId, specs);
    context.getTasksToWire().addAll(tasks);
    context.getUserTasksToWire().addAll(userTasks);

    // the messages which start this process. So the core can refuse a message, passed to
    // startWorkflowByMessage, which would start another process. Publishing a message in
    // Camunda 8 names no process, so only this check stops such a start. A name which the
    // cluster computes from a FEEL expression is unknown here. A process which has one is
    // not reported, and that tells the core not to check it
    if (bpmsInitiatedStartInvoker != null) {
      Camunda8TaskWiring
          .startMessageNamesOf(
              model,
              scopedBpmnProcessId,
              messageName -> plainIdentifier(workflowModuleId, messageName))
          .ifPresent(messageNames -> bpmsInitiatedStartInvoker
              .reportStartMessages(adapterId, workflowModuleId, bpmnProcessId, messageNames));
    }

    // the start of a workflow. The execution listener which decides what a start means is
    // ADDED TO THE MODEL here as well, on every start event of the process itself. A called
    // process starts no workflow of its own, so it gets no listener and no worker, see
    // decision 76 in the repository's DECISIONS.md
    if (bpmsInitiatedStartInvoker != null) {
      final var startsAWorkflow = bpmsInitiatedStartInvoker.startsAWorkflowOfItsOwn(workflowModuleId, bpmnProcessId);
      final var bpmsInitiatedStarts = startsAWorkflow
          ? Camunda8TaskWiring
              .bpmsInitiatedStartsOf(
                  model,
                  scopedBpmnProcessId,
                  signalName -> plainIdentifier(workflowModuleId, signalName))
          : Camunda8TaskWiring
              .startEventsOfACalledProcess(
                  model,
                  scopedBpmnProcessId,
                  signalName -> plainIdentifier(workflowModuleId, signalName));
      bpmsInitiatedStartInvoker
          .validateBpmsInitiatedStarts(
              workflowModuleId,
              bpmnProcessId,
              bpmsInitiatedStarts
                  .stream()
                  .map(startEvent -> new BpmsInitiatedStartSpec(
                      startEvent.startEventId(), startEvent.kind(), startEvent.signalName()))
                  .toList());
      if (startsAWorkflow) {
        context.getBpmsInitiatedStartsToWire().addAll(bpmsInitiatedStarts);
      }
    }

    // the element id which the probe of awarenessOfWorkflow reserved for itself. The probe
    // asks the engine by sending a modification which names an element no model has. If
    // the id matched an element by accident, that element WOULD BE ACTIVATED instead of
    // refused. So the models are searched for it here, where they are deployed, and the
    // probe stays away from such a process
    if (Camunda8TaskWiring.carriesTheReservedProbeElement(model)) {
      log
          .warn(
              "Camunda8[{}]: the model of BPMN process '{}' (file '{}', workflow module '{}') carries "
                  + "the element id '{}', which this adapter reserved for the probe asking the engine "
                  + "whether it holds a workflow. That probe is not sent for this process, so an "
                  + "extension asking where one of its workflows is waits for the search as it did "
                  + "before. Renaming the element gives the process the faster answer back.",
              adapterId,
              bpmnProcessId,
              filename,
              workflowModuleId,
              Camunda8TaskWiring.RESERVED_PROBE_ELEMENT_ID);
      clientFactory
          .getDeployedProcesses()
          .recordTheReservedProbeElement(workflowModuleId, bpmnProcessId);
    }

    // the end of a workflow is reported only if the application asked for it. A model
    // must not pay for a listener nobody wants
    final var theEndIsReported = (workflowEndedInvoker != null) && workflowEndedInvoker
        .workflowEndedHandlerExists(workflowModuleId, bpmnProcessId);
    // and the cancelation of an instance. The 8.10 line reports it through a listener of its
    // own, on the same element and with the same job type. It is worth having for a process
    // of which this application serves a task, even if nobody declared a @WorkflowEnded
    // method. The core reads the tasks it still thinks are open in the instance, and reports
    // each of them as canceled. On this BPMS, that is the only way an application hears
    // about them at all
    final var theCancelationIsReported = Camunda8CancelListeners
        .theProcessCanReportItsCancellation() && (workflowEndedInvoker != null) && (theEndIsReported || servesAnyTaskOf(
            workflowModuleId, bpmnProcessId, specs));
    // the listener holds the instance until its job is answered. So both halves are written
    // only if this adapter opens the worker which answers it. If a model carries a listener
    // nobody serves, a cancelation turns into a workflow which never goes away and raises
    // no incident either
    final var endListenerAttached = theEndIsReported && Camunda8TaskWiring.attachWorkflowEndedListener(model,
        scopedBpmnProcessId);
    final var cancelListenerAttached = theCancelationIsReported && Camunda8TaskWiring
        .attachWorkflowCanceledListener(model, scopedBpmnProcessId);
    if (endListenerAttached || cancelListenerAttached) {
      context.getWorkflowEndedProcessesToWire().add(scopedBpmnProcessId);
    }
    if (!Camunda8CancelListeners
        .theProcessCanReportItsCancellation() && (workflowEndedInvoker != null) && (theEndIsReported || servesAnyTaskOf(
            workflowModuleId, bpmnProcessId, specs))) {
      context.getProcessesWithoutACancelationReport().add(bpmnProcessId);
    }

    log.info(
        "Camunda8[{}]: wired {} task(s) of BPMN process '{}' (file '{}', workflow module '{}')",
        adapterId,
        tasks.size(),
        bpmnProcessId,
        filename,
        workflowModuleId);

  }

  /**
   * Whether this application serves at least one task of the given BPMN process. Only then
   * is the cancelation of an instance worth reporting. Only such a process can leave a
   * task open, which the core would then have to cancel.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The plain BPMN process id
   * @param specs What the model carries, as the core was asked to validate it
   * @return Whether a <code>&#64;WorkflowTask</code> method serves any of them
   */
  private boolean servesAnyTaskOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<BpmnTaskSpec> specs) {

    if (workflowTaskInvoker == null) {
      return false;
    }
    return specs
        .stream()
        .map(BpmnTaskSpec::taskDefinition)
        .filter(Objects::nonNull)
        .anyMatch(
            taskDefinition -> workflowTaskInvoker
                .workflowTaskHandlerExists(workflowModuleId, bpmnProcessId, taskDefinition));

  }

  /**
   * Ends the deployment if a <code>&#64;WorkflowTask</code> method wants the item of a
   * multi-instance element for which this model never names one.
   * <p>
   * It is judged per task, over the chain of iterations around it which this deployment
   * just recorded. So only elements of THIS process are looked at here. A level which a
   * caller adds is linked once the whole workflow module is wired.
   * {@link #refuseHandlersWantingAnItemACallerHasNot} asks about it then.
   * <p>
   * Every finding of the process goes into ONE message, as the wiring validation reports
   * every unwired task at once. A developer who fixes one model should not have to restart
   * to meet the next line of the same defect.
   */
  private void refuseHandlersWantingAnItemTheModelHasNot(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final List<BpmnTaskSpec> specs) {

    final var findings = new ArrayList<Camunda8MultiInstanceItems.Finding>();
    for (final var spec : specs) {
      final var withoutAnItem = Camunda8MultiInstanceItems
          .elementsWithoutAnItem(multiInstanceRegistry, scopedBpmnProcessId, spec.activityId());
      if (withoutAnItem.isEmpty()) {
        continue;
      }
      final var wanted = itemsTheMethodWants(workflowModuleId, bpmnProcessId, spec);
      wanted.retainAll(withoutAnItem);
      if (!wanted.isEmpty()) {
        findings
            .add(new Camunda8MultiInstanceItems.Finding(spec.activityId(), spec.taskDefinition(), wanted));
      }
    }
    if (findings.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        Camunda8MultiInstanceItems.refusal(findings, bpmnProcessId, workflowModuleId));

  }

  /**
   * The multi-instance elements whose item the method serving one task asks for.
   * <p>
   * The core is asked by the task definition AND by the element id.
   * {@code validateTaskWiring} matches a method against the same pair. A method may name
   * either of the two, and a method which names the element id would otherwise be missed.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param spec The task
   * @return The element ids named by <code>&#64;MultiInstanceElement</code>, to be narrowed
   *         down by the caller
   */
  private java.util.Set<String> itemsTheMethodWants(
      final String workflowModuleId,
      final String bpmnProcessId,
      final BpmnTaskSpec spec) {

    final var wanted = new java.util.LinkedHashSet<String>();
    wanted
        .addAll(workflowTaskWiring
            .multiInstanceElementNames(workflowModuleId, bpmnProcessId, spec.activityId()));
    if (spec.taskDefinition() != null) {
      wanted
          .addAll(workflowTaskWiring
              .multiInstanceElementNames(workflowModuleId, bpmnProcessId, spec.taskDefinition()));
    }
    return wanted;

  }

  /**
   * Ends the deployment if a <code>&#64;WorkflowTask</code> method of a called process
   * wants the item of a multi-instance element of a CALLER which names no
   * <code>inputElement</code>.
   * <p>
   * This is the second round of {@link #refuseHandlersWantingAnItemTheModelHasNot}. It is
   * asked once the call graph of the workflow module is linked. Only the levels which the
   * call sites add are judged. So a finding of the first round is not reported a second
   * time. The method asks by the element id of the caller. That is why the caller's own
   * model cannot answer for it.
   * <p>
   * There is one message per called process, and it names the calling process of each
   * element. The reader has two models in front of them.
   *
   * @param workflowModuleId The workflow module being deployed
   * @param bpmsProcessingContext Everything of it, as wired
   */
  private void refuseHandlersWantingAnItemACallerHasNot(
      final String workflowModuleId,
      final Camunda8ProcessingContext bpmsProcessingContext) {

    for (final var process : bpmsProcessingContext.getTaskSpecsByProcess().entrySet()) {
      final var bpmnProcessId = process.getKey();
      final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);
      final var findings = new ArrayList<Camunda8MultiInstanceItems.Finding>();
      for (final var spec : process.getValue()) {
        final var withoutAnItem = Camunda8MultiInstanceItems
            .inheritedElementsWithoutAnItem(multiInstanceRegistry, scopedBpmnProcessId, spec.activityId());
        if (withoutAnItem.isEmpty()) {
          continue;
        }
        final var wanted = itemsTheMethodWants(workflowModuleId, bpmnProcessId, spec);
        wanted.retainAll(withoutAnItem.keySet());
        if (wanted.isEmpty()) {
          continue;
        }
        final var callers = new java.util.LinkedHashMap<String, String>();
        wanted
            .forEach(elementId -> callers
                .put(elementId, plainProcessId(workflowModuleId, withoutAnItem.get(elementId))));
        findings
            .add(new Camunda8MultiInstanceItems.Finding(spec.activityId(), spec.taskDefinition(), wanted, callers));
      }
      if (!findings.isEmpty()) {
        throw new IllegalStateException(
            Camunda8MultiInstanceItems.refusal(findings, bpmnProcessId, workflowModuleId));
      }
    }

  }

  /**
   * Collects the elements of one BPMN file which a runtime other than this application
   * serves, per executable process, and remembers the key which allowed them.
   * <p>
   * It is asked while the file is prepared, not while a process of it is wired. The rule
   * reaches further than the wiring. An ad-hoc subprocess with an agent connector, a
   * message throw event and an end event all name a job type. None of them is a task this
   * adapter collects. And {@code use-prefix} would otherwise rename all of them.
   *
   * @param workflowModuleId The workflow module
   * @param model The model as it was read, with the plain process ids still in it
   * @param context What the module's report is assembled in
   */
  private void recordTheElementsAnotherRuntimeServes(
      final String workflowModuleId,
      final BpmnModelInstance model,
      final Camunda8ProcessingContext context) {

    for (final var process : model.getModelElementsByType(Process.class)) {
      if (!process.isExecutable()) {
        continue;
      }
      final var setting = connectorsAllowedFor(workflowModuleId, process.getId());
      if (!setting.allowed()) {
        continue;
      }
      context.recordConnectorsAllowed(process.getId(), setting.propertyKey());
      Camunda8Connectors
          .elementsServedByAnotherRuntime(model, process.getId())
          .forEach(context::recordElementServedByAnotherRuntime);
    }

  }

  /**
   * Names the elements of a process which were built from an element template while the
   * property is off, and says what switching it on means.
   * <p>
   * Version 1 never had this half. Its boot ended in the wiring validation of the core,
   * which asked for a {@code @WorkflowTask} method for a job type nobody recognised. Only
   * the documentation led from there to {@code allow-connectors}. The wiring validation of
   * the core runs directly after this guidance and still ends the boot. That is correct. An
   * element which nothing serves is a defect, until somebody says otherwise.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id, which is what a property key names
   * @param scopedBpmnProcessId The process id as it stands in the model
   * @param model The model
   */
  private void guideTowardsAllowingConnectors(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final BpmnModelInstance model) {

    final var elements = Camunda8Connectors
        .elementsServedByAnotherRuntime(model, scopedBpmnProcessId);
    if (elements.isEmpty()) {
      return;
    }
    log.warn(
        """
            Camunda8[{}]: BPMN process '{}' of workflow module '{}' carries {} element(s) built from \
            an element template (attribute 'zeebe:modelerTemplate'): {}. VanillaBP treats them like \
            any other task, so the wiring validation asks for a @WorkflowTask method per job type and \
            ends the boot where none exists. A Camunda connector is the usual reason for such an \
            element: its job type belongs to the connector runtime, which serves the job instead of \
            this application. Say so and VanillaBP leaves those elements alone, at one of three \
            levels, the most specific configured one winning:
            {}
            What it means: {}""",
        adapterId,
        bpmnProcessId,
        workflowModuleId,
        elements.size(),
        elements
            .stream()
            .map(Camunda8Connectors.ElementServedByAnotherRuntime::describe)
            .collect(Collectors.joining("; ")),
        Camunda8Connectors.levelsOf(adapterId, workflowModuleId, bpmnProcessId),
        Camunda8Connectors.WHAT_IT_COSTS);

  }

  /**
   * The report a workflow module which allows connectors writes on EVERY boot, once, after
   * every BPMN file of it has been read.
   * <p>
   * One record, not one per element. A module with eight connectors would produce eight
   * warnings, and a reader would skim all of them. It has a frame, and nothing else this
   * adapter logs has one. A WARN level alone gets lost in a boot log where every other
   * line is one line high. A second framed message would take away the effect of this one.
   * <p>
   * There is no key which silences it. {@code accept-unscoped-identifiers} is the earlier
   * case of a key which switches a warning off. It exists because the application can state
   * a fact which the adapter cannot check: that its identifiers are unique. There is no such
   * fact here. Everything below stays true for as long as the connector is in the model. So
   * a key which turns it off would only hide the loss. See decision 23 in the repository's
   * DECISIONS.md.
   *
   * @param workflowModuleId The workflow module
   * @param context The module's accumulated pipeline state
   */
  void reportWhatConnectorsCost(
      final String workflowModuleId,
      final Camunda8ProcessingContext context) {

    final var allowedBy = context.getConnectorsAllowedBy();
    if (allowedBy.isEmpty()) {
      return;
    }
    final var switchedOnBy = allowedBy
        .values()
        .stream()
        .filter(Objects::nonNull)
        .distinct()
        .collect(Collectors.joining(", "));
    final var elements = context.getElementsServedByAnotherRuntime();
    if (elements.isEmpty()) {
      reportASwitchNobodyNeeds(workflowModuleId, switchedOnBy);
      return;
    }
    log.warn(
        """

            {}
            CONNECTORS ARE SWITCHED ON: WORKFLOW MODULE '{}', CAMUNDA 8 ADAPTER '{}'
            {}
            Switched on by: {}
            VanillaBP leaves the following element(s) to the runtime which owns them, because each of \
            them was built from an element template and names a job type of its own:
            {}
            {}
            Two things this application gives up while it runs connectors, and both are what VanillaBP \
            is for. The model stops being portable: a connector is a Camunda 8 element, so the same \
            model on another BPMS has an element nothing serves, and a BPMS migration stops at it. And \
            what a connector does happens outside the workflow aggregate and outside the transaction \
            VanillaBP owns, so a redelivered job repeats it and no @WorkflowTask method of this \
            application can make it idempotent.
            The way back: model the element as an ordinary task with a @WorkflowTask method behind it, \
            or set '{}: false'.{}
            {}""",
        Camunda8Connectors.FRAME_LINE,
        workflowModuleId,
        adapterId,
        Camunda8Connectors.FRAME_LINE,
        switchedOnBy,
        elements
            .stream()
            .map(element -> "  "
                + element.describe())
            .collect(Collectors.joining("\n")),
        Camunda8Connectors.WHAT_IT_COSTS,
        Camunda8Connectors.propertyKeyOf(adapterId),
        whatPrefixingCostsAConnector(workflowModuleId),
        Camunda8Connectors.FRAME_LINE);

  }

  /**
   * A switch nobody needs is worth a sentence, not a frame. It is on, and nothing of this
   * module uses it. The reader wants the key to take it off again.
   *
   * @param workflowModuleId The workflow module
   * @param switchedOnBy The keys which switched it on
   */
  private void reportASwitchNobodyNeeds(
      final String workflowModuleId,
      final String switchedOnBy) {

    log.warn(
        "Camunda8[{}]: connectors are allowed for workflow module '{}' ({}), and no element of it "
            + "is built from an element template. Set '{}: false' where you do not need the switch.",
        adapterId,
        workflowModuleId,
        switchedOnBy,
        Camunda8Connectors.propertyKeyOf(adapterId));

  }

  /**
   * The sentence which the report carries under {@code use-prefix} and under no other mode.
   * It is empty in the other modes. Under that mode, an element which is passed over keeps
   * a job type which nothing scopes. That is the very clash the mode is there to avoid. The
   * report says so, so the reader does not have to find out.
   *
   * @param workflowModuleId The workflow module
   * @return The sentence, or an empty string
   */
  private String whatPrefixingCostsAConnector(
      final String workflowModuleId) {

    if (!Camunda8Scoping.prefixes(workflowModuleId, adapterId, scoping)) {
      return "";
    }
    return """

        Name-clash avoidance 'use-prefix' leaves the job types above unprefixed. They name a runtime \
        somebody else deployed cluster-wide, and prefixing one would rename something this application \
        does not own. So they reach the cluster unscoped, which is the clash that mode exists to avoid, \
        and it costs nothing here: a connector runtime subscribes to such a type globally anyway, and \
        two workflow modules carrying the same connector element are meant to reach the same runtime.""";

  }

  /**
   * Ends the boot over an execution listener which the CLUSTER would refuse: a {@code start}
   * listener on a start event.
   * <p>
   * It is asked whatever {@code allow-listeners} says, because this is not about who serves
   * the listener. The cluster refuses the whole FILE over it, so every process the file
   * declares is lost. And the message a developer would read otherwise comes from the cluster.
   * It names a rule, not an element. For the same reason, VanillaBP attaches its own listener
   * to a start event on {@code end}.
   *
   * @param workflowModuleId The workflow module
   * @param filename The file, named in the message
   * @param model The model as it was read
   */
  private void refuseAStartListenerTheClusterRefuses(
      final String workflowModuleId,
      final String filename,
      final BpmnModelInstance model) {

    for (final var process : model.getModelElementsByType(Process.class)) {
      if (!process.isExecutable()) {
        continue;
      }
      final var refused = Camunda8Listeners.listenersTheClusterRefuses(model, process.getId());
      if (refused.isEmpty()) {
        continue;
      }
      throw new IllegalStateException(
          """
              Camunda 8 adapter '%s' does not deploy BPMN file '%s' of workflow module '%s': the \
              cluster would refuse the file as a whole, and with it every process the file declares. \
              Its executable process '%s' carries a 'start' execution listener on a START EVENT: %s. \
              Camunda 8 allows no start listener there. Use the event type 'end' instead, which still \
              runs before the flow leaves the start event - it is what VanillaBP attaches to a start \
              event itself."""
              .formatted(
                  adapterId,
                  filename,
                  workflowModuleId,
                  process.getId(),
                  refused
                      .stream()
                      .map(Camunda8Listeners.ModelledListener::describe)
                      .collect(Collectors.joining("; "))));
    }

  }

  /**
   * Reads the listeners somebody modelled out of one BPMN file, per executable process, and
   * either refuses the file or remembers what is to be served.
   * <p>
   * It is asked while the file is prepared, not while a process of it is wired. There are
   * two reasons. The job types are still the ones the modeller typed, so a message can quote
   * them. And the listeners which VanillaBP writes itself are not in the model yet. {@code
   * wireBpmn} adds the user-task lifecycle listeners and the start listeners. After that,
   * only a prefix could tell the two kinds apart.
   *
   * @param workflowModuleId The workflow module
   * @param filename The file, named in a message
   * @param model The model as it was read, with the plain process ids still in it
   * @param context What the module's report is assembled in
   */
  private void readTheListenersTheModelCarries(
      final String workflowModuleId,
      final String filename,
      final BpmnModelInstance model,
      final Camunda8ProcessingContext context) {

    for (final var process : model.getModelElementsByType(Process.class)) {
      if (!process.isExecutable()) {
        continue;
      }
      // a process nobody claims is somebody else's model and is not judged here, see
      // decision 73 of DECISIONS.md
      if (!workflowTaskWiring.isClaimedByAWorkflowService(workflowModuleId, process.getId())) {
        continue;
      }
      final var listeners = Camunda8Listeners.listenersOf(model, process.getId());
      final var setting = listenersAllowedFor(workflowModuleId, process.getId());
      if (setting.allowed()) {
        // remembered even if the process carries no listener at all. So the report can say
        // that the key is on and nothing of this module uses it
        context.recordListenersAllowed(process.getId(), setting.propertyKey());
      }
      if (listeners.isEmpty()) {
        continue;
      }
      final var served = listeners
          .stream()
          .filter(listener -> servesThisListener(workflowModuleId, process.getId(), listener))
          .toList();
      // nothing here serves a job type which no method of this application names. On this
      // cluster the adapter may not simply pass over such a model. The cluster creates the job,
      // and the workflow stops at it. Who claims the process decides what is said about it
      final var unserved = listeners
          .stream()
          .filter(listener -> !served.contains(listener))
          .toList();
      handOverListenerJobsNothingServes(workflowModuleId, process.getId(), filename, model, unserved, context);
      // the open task check must not probe an element whose 'updating' listener nothing here
      // answers. The empty update fires that listener, and nobody closes its job. The id is
      // already the plain one, because this runs while the file is prepared, before
      // name-clash avoidance rewrites anything
      unserved
          .stream()
          .filter(Camunda8Listeners::isAnUpdatingTaskListener)
          .forEach(
              listener -> context
                  .recordUpdatingListenerNobodyServes(process.getId(), listener.elementId()));
      if (served.isEmpty()) {
        continue;
      }
      if (!setting.allowed()) {
        throw new IllegalStateException(
            refuseTheListenersNobodyAllowed(workflowModuleId, process.getId(), filename, served));
      }
      final var sharing = Camunda8Listeners.listenersSharingAJobType(served);
      if (!sharing.isEmpty()) {
        throw new IllegalStateException(
            refuseListenersSharingAJobType(workflowModuleId, process.getId(), filename, sharing));
      }
      served.forEach(context::recordModelledListener);
    }

  }

  /**
   * Whether a {@code @WorkflowTask} method of this application names the job type of the
   * listener.
   * <p>
   * This decides whether a listener is the business of this application. A job type is a
   * name in the cluster, and anybody may subscribe to it: another application, a connector
   * runtime, or a worker somebody runs beside this one. So a model which carries one says
   * nothing about who serves it. A method which names it does.
   * <p>
   * Only the task definition counts. {@code @WorkflowTask(id = ...)} names the ELEMENT, and
   * one element may carry a task and a listener at once.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param listener The listener
   * @return Whether a method names its job type
   */
  private boolean servesThisListener(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Camunda8Listeners.ModelledListener listener) {

    return (listener.taskDefinition() != null) && workflowTaskInvoker
        .workflowTaskHandlerExists(workflowModuleId, bpmnProcessId, listener.taskDefinition());

  }

  /**
   * Hands to the wiring validation of the core the listeners of a claimed process whose job
   * type no method of this application names. A process nobody claims never gets here.
   * <p>
   * The cluster creates a job the moment it reaches a listener. So a job type which nothing
   * subscribes to stops the workflow inside the element. There is no incident and nothing
   * in any log. A <code>&#64;WorkflowService</code> class which claims the process says that
   * this application stands in for it. So such a listener is a task like any other. It needs
   * a method, or the property <code>implemented-externally=true</code>, which says that a
   * worker somebody else runs answers it. The core holds that rule for every task of every
   * adapter. The listener reaches it as a task spec in {@code wireBpmn}.
   * <p>
   * One exception stays with this adapter: a listener on an element which is built from an
   * element template and is a job itself, like a connector. Such an element belongs to the
   * runtime which owns it, see decision 23 and decision 24 in the repository's DECISIONS.md.
   * Its listeners are named in a WARN of their own. On a user task the template does not
   * count. The Camunda Modeler uses one there to preset a form or an assignee, and that says
   * nothing about who answers a job.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param filename The file the model was read from
   * @param model The model the listeners were read from, for the elements they sit on
   * @param listeners The listeners nothing here serves, empty for a model where there are none
   * @param context Where the listeners for the core's validation are remembered
   */
  private void handOverListenerJobsNothingServes(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String filename,
      final BpmnModelInstance model,
      final java.util.List<Camunda8Listeners.ModelledListener> listeners,
      final Camunda8ProcessingContext context) {

    if (listeners.isEmpty()) {
      return;
    }
    final var onAnElementOfAnotherRuntime = listeners
        .stream()
        .filter(listener -> sitsOnAJobElementSomebodyElseOwns(model, listener))
        .toList();
    listeners
        .stream()
        .filter(listener -> !onAnElementOfAnotherRuntime.contains(listener))
        .forEach(context::recordListenerNothingHereServes);
    sayWhichListenersBelongToAnotherRuntime(
        workflowModuleId, bpmnProcessId, filename, onAnElementOfAnotherRuntime);

  }

  /**
   * Whether the element a listener sits on is a job built from an element template. That
   * says that a runtime other than this application owns the element.
   * <p>
   * It is read through {@link Camunda8Connectors}, not by looking for the attribute a second
   * time. So the marker means the same here as for a connector and for an ad-hoc
   * subprocess. A user task is left out, because a template there presets a form or an
   * assignee.
   *
   * @param model The model the listener was read from
   * @param listener The listener
   * @return Whether its element belongs to somebody else
   */
  private static boolean sitsOnAJobElementSomebodyElseOwns(
      final BpmnModelInstance model,
      final Camunda8Listeners.ModelledListener listener) {

    final var element = model.getModelElementById(listener.elementId());
    return (element instanceof io.camunda.zeebe.model.bpmn.instance.BaseElement baseElement) && !(element instanceof io.camunda.zeebe.model.bpmn.instance.UserTask) && (Camunda8Connectors
        .elementTemplateOf(baseElement) != null);

  }

  /**
   * Names the listeners of a CLAIMED process which sit on an element that the runtime of
   * somebody else owns, and lets the boot go on.
   * <p>
   * The developer said so by setting the element template, so there is nothing to ask of
   * them. It is still said, because a job type the reader does not recognise is the one line
   * worth a second look. For the same reason, the report about connectors names every element
   * it passes over.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param filename The file the model was read from
   * @param listeners The listeners sitting on an element of another runtime, possibly empty
   */
  private void sayWhichListenersBelongToAnotherRuntime(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String filename,
      final java.util.List<Camunda8Listeners.ModelledListener> listeners) {

    if (listeners.isEmpty()) {
      return;
    }
    log.warn(
        """
            Camunda8[{}]: BPMN process '{}' of workflow module '{}' (file '{}') carries {} \
            listener(s) whose job type no @WorkflowTask method of this application names, on an \
            element built from an element template: {}. That template says the element belongs to \
            the runtime which owns it, so VanillaBP asks for no method and this does not end the \
            boot. Whatever answers that job keeps answering it; where nothing does, the workflow \
            stands inside the element with no incident and nothing in any log.""",
        adapterId,
        bpmnProcessId,
        workflowModuleId,
        filename,
        listeners.size(),
        listeners
            .stream()
            .map(Camunda8Listeners.ModelledListener::describe)
            .collect(Collectors.joining("; ")));

  }

  /**
   * The message which ends a boot over a model whose listeners nobody allowed.
   * <p>
   * Here the adapter refuses by itself. It does not leave it to the wiring validation of the
   * core, as {@link #guideTowardsAllowingConnectors} does. A connector is an element which
   * VanillaBP is asked to LEAVE ALONE. So the validation finds a task which nothing serves,
   * and ends the boot by itself. A listener is the other way round. The key asks VanillaBP to
   * serve something. Without the key there is no task spec, and nothing for the validation
   * to miss. The workflow would stop at the job of the listener on the cluster, with nothing
   * in the log. So the refusal is written here, in the words the report uses.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id, which is what a property key names
   * @param filename The file the model was read from
   * @param listeners What the model carries
   * @return The message
   */
  private String refuseTheListenersNobodyAllowed(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String filename,
      final java.util.List<Camunda8Listeners.ModelledListener> listeners) {

    return """
        BPMN process '%s' of workflow module '%s' (file '%s') carries %d listener(s) somebody \
        modelled whose job type a @WorkflowTask method of this application names: %s. VanillaBP 1 \
        served such a listener and said nothing about it. This version does not serve it until you \
        ask for it, because the cluster creates a job for every one of them and a job type nothing \
        subscribes to stops the workflow right there - no incident, no message. Ask for it at one of \
        three levels, the most specific configured one winning:
        %s
        What it costs: %s
        %s
        %s
        Only a listener a @WorkflowTask method names is this message about. A listener VanillaBP or \
        one of its extensions writes is never among them either: those carry a job type starting with \
        '%s' and are served whatever this key says."""
        .formatted(
            bpmnProcessId,
            workflowModuleId,
            filename,
            listeners.size(),
            listeners
                .stream()
                .map(Camunda8Listeners.ModelledListener::describe)
                .collect(Collectors.joining("; ")),
            Camunda8Listeners.levelsOf(adapterId, workflowModuleId, bpmnProcessId),
            Camunda8Listeners.WHAT_IT_COSTS,
            Camunda8Listeners.WHAT_CAMUNDA8_ADDS,
            Camunda8Listeners.WHICH_METHOD_SERVES_WHICH,
            Camunda8Listeners.VANILLABP_JOB_TYPE_PREFIX);

  }

  /**
   * The message which ends a boot where one element carries two served listeners under one job
   * type.
   * <p>
   * This is the defect which version 1 left open, seen from the other side. There, two
   * listeners of one element became two entries with the same identity, and it was undefined
   * which of them ran. Here they would become one task, served by one method and called for
   * two events. Nothing the method could ask would say which event it is in.
   * {@code TaskEvent.Event} has no value for the event of a listener. A job type per event
   * is the fix. Naming the case is better than picking one of them.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param filename The file the model was read from
   * @param sharing Per element and job type the listeners sharing it
   * @return The message
   */
  private String refuseListenersSharingAJobType(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String filename,
      final java.util.List<java.util.List<Camunda8Listeners.ModelledListener>> sharing) {

    return """
        BPMN process '%s' of workflow module '%s' (file '%s') has one element carrying several \
        listeners under ONE job type: %s. One @WorkflowTask method would serve all of them and \
        nothing would tell it which event it is being called for, because the event is part of a \
        listener's identity and TaskEvent.Event has no value for it. Give every listener of an \
        element a job type of its own and write a method per job type."""
        .formatted(
            bpmnProcessId,
            workflowModuleId,
            filename,
            sharing
                .stream()
                .map(listeners -> listeners
                    .stream()
                    .map(Camunda8Listeners.ModelledListener::describe)
                    .collect(Collectors.joining(" and ")))
                .collect(Collectors.joining("; ")));

  }

  /**
   * Ends the boot where a method serving a listener declares {@code @TaskId}.
   * <p>
   * The cluster completes a listener job when the handler returns. The transition the
   * listener sits in waits for nothing else. So a method which wants to keep the task open
   * would wait for a completion no application can send. Version 1 accepted such a method,
   * and the workflow went on without it.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param listeners The listeners of this process
   */
  private void refuseAsynchronousListenerMethods(
      final String workflowModuleId,
      final String bpmnProcessId,
      final java.util.List<Camunda8Listeners.ModelledListener> listeners) {

    listeners
        .stream()
        .filter(listener -> workflowTaskWiring.workflowTaskCompletesAsynchronously(
            workflowModuleId,
            bpmnProcessId,
            listener.taskDefinition()))
        .findFirst()
        .ifPresent(listener -> {
          throw new IllegalStateException(
              """
                  The @WorkflowTask method serving the listener '%s' (BPMN process '%s' of workflow \
                  module '%s') declares a @TaskId parameter! A listener job is completed the moment \
                  the method returns, so such a task can never stay open and the id would complete \
                  nothing. Drop the parameter, or model the work as a task of the process where it \
                  has to stay open."""
                  .formatted(listener.taskDefinition(), bpmnProcessId, workflowModuleId));
        });

  }

  /**
   * The report a workflow module whose modelled listeners are served writes on EVERY boot,
   * once, after every BPMN file of it has been read.
   * <p>
   * It has a frame and the same shape as {@link #reportWhatConnectorsCost}, for the same
   * reason. It is one record, not one per listener, and no key silences it. What it says
   * stays true for as long as the listener is in the model. So a key which turns it off
   * would only hide the loss. See decision 27 in the repository's DECISIONS.md.
   *
   * @param workflowModuleId The workflow module
   * @param context The module's accumulated pipeline state
   */
  void reportWhatListenersCost(
      final String workflowModuleId,
      final Camunda8ProcessingContext context) {

    final var allowedBy = context.getListenersAllowedBy();
    if (allowedBy.isEmpty()) {
      return;
    }
    final var switchedOnBy = allowedBy
        .values()
        .stream()
        .filter(Objects::nonNull)
        .distinct()
        .collect(Collectors.joining(", "));
    if (context.getModelledListeners().isEmpty()) {
      log.warn(
          "Camunda8[{}]: the listeners of workflow module '{}' are served ({}), and no model of it "
              + "carries one. Set '{}: false' where you do not need the switch.",
          adapterId,
          workflowModuleId,
          switchedOnBy,
          Camunda8Listeners.propertyKeyOf(adapterId));
      return;
    }
    log.warn(
        """

            {}
            MODELLED LISTENERS ARE SERVED: WORKFLOW MODULE '{}', CAMUNDA 8 ADAPTER '{}'
            {}
            Switched on by: {}
            VanillaBP serves the following listener(s) with a @WorkflowTask method, one method per \
            listener:
            {}
            {}
            {}
            {}
            The way back: move what the listener does into a task of the model with a @WorkflowTask \
            method behind it, or set '{}: false'.
            {}""",
        Camunda8Listeners.FRAME_LINE,
        workflowModuleId,
        adapterId,
        Camunda8Listeners.FRAME_LINE,
        switchedOnBy,
        context
            .getModelledListeners()
            .stream()
            .map(listener -> "  "
                + listener.describe())
            .collect(Collectors.joining("\n")),
        Camunda8Listeners.WHAT_IT_COSTS,
        Camunda8Listeners.WHAT_CAMUNDA8_ADDS,
        Camunda8Listeners.WHICH_METHOD_SERVES_WHICH,
        Camunda8Listeners.propertyKeyOf(adapterId),
        Camunda8Listeners.FRAME_LINE);

  }

  /**
   * Ends the boot where a BPMN process this application claims carries a job-worker user task.
   * A process nobody claims never gets here.
   * <p>
   * The finding is the SHAPE of the element. A user task which carries
   * <code>zeebe:userTask</code> is managed by the cluster, and that is the one shape this
   * adapter takes. A user task without it is a job-worker user task. VanillaBP 1 used that
   * shape up to its release 1.6.3, and this adapter does not take it. So nothing here asks
   * whether some worker would fetch the job. The model says that a job worker serves the
   * task, and that is already the answer.
   * <p>
   * A <code>&#64;WorkflowService</code> class which claims a process says that the
   * application serves that process. So the boot ends over such an element. The cost of the
   * shape shows why ending the boot is the kinder answer. The cluster hands out a job of its
   * own user-task type, and nothing here fetches it. The workflow stands at the element until
   * the retries of the job are used up. Nobody sees that until somebody waits for a task
   * which never appears.
   * <p>
   * The two shapes are named apart, because only one of them can be searched for. Up to
   * release 1.6.3, VanillaBP 1 served this element and read its task definition off the
   * <code>formKey</code>. So an upgrading application finds those by searching its models.
   * A user task without a <code>formKey</code> does not follow that convention, and no such
   * search finds it. But the cluster does exactly the same with it.
   * <p>
   * Two numbers are given, and only the first one is certain. The elements come from the
   * model this boot deploys, and their number has to reach zero. The count of open tasks is a
   * search of the index of the cluster. {@link Camunda8UnservedUserTaskJobs} says what that
   * answer is worth. The index leaves out the jobs it has seen finish, and it runs behind the
   * engine at both ends. So the number is close, not exact, and the message says so. The
   * adapter does not wait for the cluster. If the cluster does not answer yet, the number is
   * missing. The model is the cause, and a wait would only put a message about the cluster
   * in front of it.
   *
   * @param workflowModuleId The workflow module id
   * @param bpmnProcessId The plain BPMN process id
   * @param scopedBpmnProcessId The process id as the cluster knows it
   * @param found The job-worker user tasks, per shape, both empty for a model whose user
   *          tasks are all Camunda-managed
   * @throws IllegalStateException If the model carries such a user task
   */
  private void refuseJobWorkerUserTasks(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final Camunda8TaskWiring.Camunda8JobWorkerUserTasks found) {

    if (found.isEmpty()) {
      return;
    }
    // no wait for the cluster. The boot ends over the model. If it waited first, a cluster
    // which is not up yet would sit out 'startup-wait'. Then it would end the boot with its
    // own message, and that hides the real cause
    final var howManyAreOpen = Camunda8UnservedUserTaskJobs
        .howManyAreOpen(whatTheIndexHoldsOfTheJobWorkerUserTasks(scopedBpmnProcessId));
    throw new IllegalStateException(
        """
            Camunda 8 adapter '%s' does not deploy BPMN process '%s' of workflow module '%s': it \
            carries %d user task(s) with no 'zeebe:userTask' extension element, which is a user \
            task a job worker serves, and this adapter does not accept that shape: %s. A \
            @WorkflowService class of this application claims this process, so the application \
            stands in for it, and VanillaBP serves a user task only where the CLUSTER manages it. \
            What the shape would cost you: the cluster hands out a job of '%s', nothing here \
            fetches it, and the workflow stands at the element until the job's retries are used \
            up. Nobody sees that until somebody waits for a task which never appears, so the boot \
            ends here instead. Two ways out. Make the user task a Camunda-managed one \
            ('zeebe:userTask') and set 'External form reference' (zeebe:formDefinition \
            externalReference) to the task definition your @WorkflowTask method names - VanillaBP \
            then wires its lifecycle listeners itself. Or take the element out of the model, if \
            that work is not done any more. Where a worker of your own serves the element, give it \
            a 'zeebe:taskDefinition' naming that worker's job type, and this check passes over it: \
            the element is then yours to serve rather than a user task of VanillaBP's. %s A task \
            already open on such an element stays as it is: its workflow runs on the process \
            version it was started on, which no change to your model reaches, so finish or cancel \
            those through your own task list."""
            .formatted(
                adapterId,
                bpmnProcessId,
                workflowModuleId,
                found.all().size(),
                whichShapeEachOfThemIs(found),
                Camunda8TaskWiring.TASKDEFINITION_USERTASK_WORKER_V1,
                howManyAreOpen));

  }

  /**
   * Names the elements found, per shape. So a developer reads which of them a search for
   * <code>formKey</code> would have found.
   *
   * @param found The job-worker user tasks, per shape
   * @return One sentence naming what there is, leaving out a shape which is not there
   */
  private static String whichShapeEachOfThemIs(
      final Camunda8TaskWiring.Camunda8JobWorkerUserTasks found) {

    final var sentences = new ArrayList<String>();
    if (!found.withVersionOnesFormKey().isEmpty()) {
      sentences
          .add(
              "carrying the formKey VanillaBP 1 read their task definition from, up to its release 1.6.3, which is what a search of your models for 'formKey' finds: %s"
                  .formatted(named(found.withVersionOnesFormKey())));
    }
    if (!found.withoutAFormKey().isEmpty()) {
      sentences
          .add(
              "carrying no formKey at all, so no search for one finds them although this cluster treats them the same: %s"
                  .formatted(named(found.withoutAFormKey())));
    }
    return String.join("; ", sentences);

  }

  /**
   * The tenant as the INFO line after a deployment names it. If the application set no
   * tenant, the line says {@code '<none>'}, the same word the Camunda 7 adapter writes for
   * it. It adds where the cluster puts the resources then. That place is the tenant whose
   * id on Camunda 8 is {@code <default>}. So the id keeps its own spelling, and nobody takes
   * it for a second word meaning "none".
   *
   * @param tenantId The tenant the deployment named, or {@code null} for none
   * @return The tenant in quotes, or {@code '<none>'} together with the cluster's tenant
   */
  static String tenantForTheLog(
      final String tenantId) {

    if ((tenantId == null) || tenantId.isBlank()) {
      return "'<none>' (cluster tenant <default>)";
    }
    return "'%s'".formatted(tenantId);

  }

  /**
   * @param elementIds The element ids to name
   * @return Them in quotes, comma separated
   */
  private static String named(
      final List<String> elementIds) {

    return String.join(", ", elementIds.stream().map("'%s'"::formatted).toList());

  }

  /**
   * Refuses a BPMN file if a FEEL expression which names a called process or decision
   * already carries the prefix of the workflow module.
   * <p>
   * Under {@code use-prefix}, this adapter writes that prefix into such an expression itself.
   * An expression which builds it as well would get it twice. Then every call of the element
   * would fail the moment a workflow reached it. An earlier VanillaBP 2 snapshot asked an
   * application to build the prefix. That is why this is refused and not quietly doubled.
   * Somebody can fix the model in a minute. At boot they learn about it once, not in one
   * incident per instance.
   * <p>
   * It is read off the model while it is still the one the modeller wrote. After the rewrite,
   * every such expression carries the prefix by design.
   *
   * @param workflowModuleId The workflow module
   * @param filename The BPMN file
   * @param model Its model, before anything of it was rewritten
   */
  private void refuseAnExpressionWhichAlreadyCarriesThePrefix(
      final String workflowModuleId,
      final String filename,
      final BpmnModelInstance model) {

    if (!Camunda8Scoping.prefixes(workflowModuleId, adapterId, scoping)) {
      return;
    }
    final var prefix = Camunda8Scoping.prefixOf(workflowModuleId, adapterId, scoping);
    final var carryingItAlready = Camunda8Scoping
        .whatAlreadyCarriesThePrefixInAnExpression(
            model,
            workflowModuleId,
            adapterId,
            scoping,
            allowConnectorsResolver,
            servedListenerJobTypesOf(workflowModuleId));
    if (carryingItAlready.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        """
            Camunda 8 adapter '%s' does not deploy BPMN file '%s' of workflow module '%s': %d \
            value(s) of it are written as a FEEL expression which composes the prefix '%s' \
            itself: %s. Name-clash avoidance 'use-prefix' (%s) writes that prefix INTO such an \
            expression, so what reached the cluster would carry it twice \
            ('%s%sTheIdentifierYouWrote' for an expression yielding 'TheIdentifierYouWrote') and \
            whatever reads it would fail the moment a workflow reached the element. Take the \
            prefix out of the expression(s) named above and let them yield the identifier your own \
            model declares, the one without any prefix. An earlier VanillaBP 2 snapshot asked for \
            the opposite and warned about every such element; this adapter writes the prefix itself \
            now."""
            .formatted(
                adapterId,
                filename,
                workflowModuleId,
                carryingItAlready.size(),
                prefix,
                carryingItAlready
                    .stream()
                    .map(Camunda8Scoping.ExpressionCarryingThePrefix::describe)
                    .collect(Collectors.joining(", ")),
                Camunda8AdapterConfiguration.propertyKey(adapterId, "name-clash-avoidance"),
                prefix,
                prefix));

  }

  /**
   * What a job type written as a FEEL expression is. Every report of the finding says it.
   */
  private static final String WHAT_A_JOB_TYPE_WRITTEN_AS_AN_EXPRESSION_IS = """
      A job type is the NAME a worker subscribes to, and an expression is not a name: this adapter \
      opens one worker per job type it reads out of the model and subscribes exactly the string \
      the model says.""";

  /**
   * What it costs when a workflow reaches such an element. Both answers Camunda 8 can give are
   * named, because this adapter cannot state which one it gives. Why no list of the places
   * where Camunda evaluates an expression is written down here is decision 55 in the
   * repository's DECISIONS.md. Neither answer serves the element.
   */
  private static final String WHAT_A_JOB_TYPE_WRITTEN_AS_AN_EXPRESSION_COSTS = """
      Where the cluster evaluates the expression, the job it creates carries the RESULT while the \
      worker waits for the expression; where it does not, the job carries the expression itself \
      and no @WorkflowTask method can be named after it. Either way a workflow reaches the \
      element and stands there, and the job ends in an incident once its retries are used up.""";

  /**
   * Ends the boot if a BPMN process which this application claims names a job type by a FEEL
   * expression. A process nobody claims is not looked at.
   * <p>
   * Two attributes give a job type: the <code>zeebe:taskDefinition</code> type of a
   * service-like task, and the type of a listener somebody modelled. This adapter subscribes a
   * worker to the string it finds at either place. So an expression there means a worker
   * which waits for a name no job ever carries. Nothing later in the boot says that. The
   * wiring validation of the core asks for a <code>&#64;WorkflowTask</code> method named after
   * the expression, and nobody can write such a method. The listener half asks for the same
   * method in its own words. So the finding is named here, in the words it is about.
   * <p>
   * Only a claimed process is judged. The same holds for a user task which a job worker serves
   * and for an ad-hoc subprocess which nothing serves - decisions 53 and 54 in the repository's
   * DECISIONS.md.
   * <p>
   * An element built from an element template is left out. That is the marker of decision 23
   * and decision 24. Its job type names a runtime which somebody else deployed, and that
   * runtime may well build it by expression.
   * <p>
   * It is read while the file is still as the modeller wrote it. So the message quotes the
   * expression as it was typed. Under <code>use-prefix</code> every expression carries the
   * frame of {@link Camunda8Scoping} after the rewrite.
   *
   * @param workflowModuleId The workflow module id
   * @param filename The file the model was read from
   * @param model The model as it was read, with the plain process ids still in it
   * @throws IllegalStateException If a workflow service of this application claims the process
   */
  private void refuseJobTypesWrittenAsAnExpression(
      final String workflowModuleId,
      final String filename,
      final BpmnModelInstance model) {

    for (final var process : model.getModelElementsByType(Process.class)) {
      if (!process.isExecutable()) {
        continue;
      }
      // a process nobody claims is somebody else's model and is not judged here, see
      // decision 73 of DECISIONS.md
      if (!workflowTaskWiring.isClaimedByAWorkflowService(workflowModuleId, process.getId())) {
        continue;
      }
      final var written = new ArrayList<String>();
      Camunda8TaskWiring
          .jobTypesWrittenAsAnExpressionOf(
              model, process.getId(), connectorsAllowedFor(workflowModuleId, process.getId()).allowed())
          .stream()
          .map(Camunda8TaskWiring.JobTypeWrittenAsAnExpression::describe)
          .forEach(written::add);
      // a listener is judged only by the shape of its job type. It does not matter whether
      // anybody allowed listeners for this process. Whatever that key says, this adapter cannot
      // subscribe to an expression
      Camunda8Listeners
          .listenersOf(model, process.getId())
          .stream()
          .filter(listener -> Camunda8Scoping.isWrittenAsFeel(listener.taskDefinition()))
          .filter(listener -> !sitsOnAJobElementSomebodyElseOwns(model, listener))
          .map(Camunda8Listeners.ModelledListener::describe)
          .forEach(written::add);
      if (written.isEmpty()) {
        continue;
      }
      throw new IllegalStateException(
          """
              Camunda 8 adapter '%s' does not deploy BPMN process '%s' of workflow module '%s' \
              (file '%s'): %d job type(s) of it are written as a FEEL expression: %s. %s A \
              @WorkflowService class of this application claims this process, so the application \
              stands in for it, and such an element is where that stops being true. %s Two ways \
              out. Write a job type which is a fixed name and a @WorkflowTask method of that \
              name - where the work differs from workflow to workflow, let that method branch on \
              the workflow aggregate it is handed, which is where the data the expression reads \
              comes from anyway. Or leave the element to the runtime which does serve it, a \
              connector or a worker you run beside this application: give the ELEMENT a \
              'zeebe:modelerTemplate', which is how this adapter is told that an element belongs \
              to somebody else, and allow such elements with '%s'."""
              .formatted(
                  adapterId,
                  process.getId(),
                  workflowModuleId,
                  filename,
                  written.size(),
                  String.join("; ", written),
                  WHAT_A_JOB_TYPE_WRITTEN_AS_AN_EXPRESSION_IS,
                  WHAT_A_JOB_TYPE_WRITTEN_AS_AN_EXPRESSION_COSTS,
                  Camunda8Connectors.propertyKeyOf(adapterId)));
    }

  }

  /**
   * What a form reference written as a FEEL expression is. Every report of the finding
   * says it.
   */
  private static final String WHAT_A_FORM_REFERENCE_WRITTEN_AS_AN_EXPRESSION_IS = """
      A form reference has to be a fixed name, because under VanillaBP's Camunda 8 convention it \
      IS the task definition of the user task: the @WorkflowTask method is found by it, and the \
      job type of the listeners which tell the application about the task is built from it. An \
      expression is evaluated for each workflow, so it names no method.""";

  /**
   * What such a model costs. This adapter causes both halves itself, so they are said as
   * facts. The listener job type is the reference behind a fixed prefix, and the cluster
   * takes it as written. And <code>use-prefix</code> frames the expression.
   */
  private static final String WHAT_A_FORM_REFERENCE_WRITTEN_AS_AN_EXPRESSION_COSTS = """
      Such a user task is served only where a @WorkflowTask method repeats the expression as its \
      taskDefinition, and under name-clash-avoidance 'use-prefix' not even then, because the \
      prefix is written into the expression.""";

  /**
   * Ends the boot if a BPMN process which this application claims names the form of a user
   * task by a FEEL expression. A process nobody claims is not looked at.
   * <p>
   * It is the same answer as for a job type written as an expression, for the same kind of
   * reason. The reference is the task definition, and a task definition is a name. Who claims
   * the process decides between the refusal and the WARN, see
   * decision 69 in the repository's DECISIONS.md.
   * <p>
   * Before this check, nothing was said in the one mode where the model seemed to work.
   * Without prefixes, a method written as <code>taskDefinition = "=whichForm"</code> matched
   * and was served. Under <code>use-prefix</code> the boot ended in the wiring validation of
   * the core. It named the method as matching no task and said nothing about the model.
   *
   * @param workflowModuleId The workflow module id
   * @param filename The file the model was read from
   * @param model The model as it was read, with the plain process ids still in it
   * @throws IllegalStateException If a workflow service of this application claims the process
   */
  private void refuseFormReferencesWrittenAsAnExpression(
      final String workflowModuleId,
      final String filename,
      final BpmnModelInstance model) {

    for (final var process : model.getModelElementsByType(Process.class)) {
      if (!process.isExecutable()) {
        continue;
      }
      // a process nobody claims is somebody else's model and is not judged here, see
      // decision 73 of DECISIONS.md
      if (!workflowTaskWiring.isClaimedByAWorkflowService(workflowModuleId, process.getId())) {
        continue;
      }
      final var written = Camunda8TaskWiring
          .formReferencesWrittenAsAnExpressionOf(model, process.getId())
          .stream()
          .map(Camunda8TaskWiring.FormReferenceWrittenAsAnExpression::describe)
          .toList();
      if (written.isEmpty()) {
        continue;
      }
      throw new IllegalStateException(
          """
              Camunda 8 adapter '%s' does not deploy BPMN process '%s' of workflow module '%s' \
              (file '%s'): %d user task(s) of it name their form by a FEEL expression: %s. %s %s \
              A @WorkflowService class of this application claims this process, so this is \
              yours to change: write a fixed name as the external form reference and a \
              @WorkflowTask method of that name. Where the form to show differs from workflow to \
              workflow, model one user task per form behind a gateway, or keep one name and let \
              your task list choose the form from the data of the workflow."""
              .formatted(
                  adapterId,
                  process.getId(),
                  workflowModuleId,
                  filename,
                  written.size(),
                  String.join("; ", written),
                  WHAT_A_FORM_REFERENCE_WRITTEN_AS_AN_EXPRESSION_IS,
                  WHAT_A_FORM_REFERENCE_WRITTEN_AS_AN_EXPRESSION_COSTS));
    }

  }

  /**
   * Whether this application serves the listener of the given PLAIN BPMN process id and job
   * type. That decides whether the job type is a task definition of the workflow module.
   * <p>
   * The rewrite asks it, and so does the refusal of an expression which already carries the
   * prefix. So both of them cover exactly the job types which the rewrite reaches.
   *
   * @param workflowModuleId The workflow module
   * @return The question, answered for one BPMN process and one job type at a time
   */
  private java.util.function.BiPredicate<String, String> servedListenerJobTypesOf(
      final String workflowModuleId) {

    return (
        bpmnProcessId,
        jobType) -> listenersAllowedFor(workflowModuleId, bpmnProcessId).allowed() && workflowTaskInvoker
            .workflowTaskHandlerExists(workflowModuleId, bpmnProcessId, jobType);

  }

  /**
   * The sentence a developer needs if the cluster refuses a deployment of this module and
   * quotes an expression into which this adapter wrote the prefix. It is an empty string if
   * this module has no such element or nothing is prefixed.
   *
   * @param workflowModuleId The workflow module
   * @param context What its files were read into
   * @return The sentence, starting with a space, or an empty string
   * @see Camunda8Scoping#whatAQuotedExpressionIncludes(String, String, java.util.Map)
   */
  private String whatAQuotedExpressionIncludes(
      final String workflowModuleId,
      final Camunda8ProcessingContext context) {

    if (!Camunda8Scoping.prefixes(workflowModuleId, adapterId, scoping)) {
      return "";
    }
    return Camunda8Scoping
        .whatAQuotedExpressionIncludes(
            Camunda8AdapterConfiguration.propertyKey(adapterId, "name-clash-avoidance"),
            Camunda8Scoping.prefixOf(workflowModuleId, adapterId, scoping),
            context.getElementsNamingTheirTargetByExpression());

  }

  /**
   * What an ad-hoc subprocess with a <code>zeebe:taskDefinition</code> of its own is. Every
   * report of the finding says it, because the refusal and the WARN have to say the same about
   * the element.
   */
  private static final String WHAT_A_JOB_WORKER_AD_HOC_SUBPROCESS_IS = """
      That is the flavour where a job worker decides round by round which of the inner activities \
      to activate, and VanillaBP does not serve it: a @WorkflowTask method has no way to name the \
      elements it wants activated, so this adapter opens no worker for such an element.""";

  /**
   * What it costs when a workflow reaches such an element. Both messages say it, for the same
   * reason as {@link #WHAT_A_JOB_WORKER_AD_HOC_SUBPROCESS_IS}.
   */
  private static final String WHAT_A_JOB_WORKER_AD_HOC_SUBPROCESS_COSTS = """
      A workflow reaches the subprocess and stops there, and the job the cluster activated is \
      fetched by nothing and ends in an incident once its retries are used up.""";

  /**
   * Ends the boot if a BPMN process which this application claims carries an ad-hoc subprocess
   * with a <code>zeebe:taskDefinition</code> of its own. A process nobody claims never gets
   * here.
   * <p>
   * The element deploys, and the workflow runs up to it. There it stops. The job of the
   * subprocess is activated, and nothing fetches it. Once the retries of that job are used up,
   * the cluster raises an incident. Nothing later in the boot detects that, because the wiring
   * does not collect the element. It produces no task spec, so no validation misses a method.
   * <p>
   * A <code>&#64;WorkflowService</code> class which claims the process says that this
   * application stands in for it. So the boot ends over such an element. For a claimed
   * process, decision 24 in the repository's DECISIONS.md no longer covers this.
   * <p>
   * An element which carries a <code>zeebe:modelerTemplate</code> never reaches this method.
   * That is the marker of decision 23 and decision 24. The Camunda AI agent is an element
   * template on exactly this element, and a connector runtime fetches its job.
   *
   * @param workflowModuleId The workflow module id
   * @param bpmnProcessId The plain BPMN process id
   * @param elementIds The ad-hoc subprocesses waiting for a worker, empty for every other
   *          model
   * @throws IllegalStateException If a workflow service of this application claims the process
   */
  private void refuseUnservedAdHocSubProcesses(
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<String> elementIds) {

    if (elementIds.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        """
            Camunda 8 adapter '%s' does not deploy BPMN process '%s' of workflow module '%s': %d \
            ad-hoc subprocess(es) of it carry a 'zeebe:taskDefinition' of their own: %s. %s A \
            @WorkflowService class of this application claims this process, so the application \
            stands in for it, and this element is where that stops being true. %s Nothing later in \
            the boot sees it, so the boot ends here instead. Two ways out. Let the MODEL say which \
            activities to run: put their element ids into 'zeebe:adHoc activeElementsCollection' \
            and let a task of your application fill the workflow-aggregate attribute that \
            expression reads - the activities inside the element are then ordinary tasks with \
            ordinary @WorkflowTask methods behind them. Or leave the element to a runtime which \
            does serve it, a connector or the Camunda AI agent: such an element carries a \
            'zeebe:modelerTemplate' as well, and one carrying that attribute is passed over here \
            without a word. Where a worker you run yourself serves the element, say so instead:
            %s"""
            .formatted(
                adapterId,
                bpmnProcessId,
                workflowModuleId,
                elementIds.size(),
                named(elementIds),
                WHAT_A_JOB_WORKER_AD_HOC_SUBPROCESS_IS,
                WHAT_A_JOB_WORKER_AD_HOC_SUBPROCESS_COSTS,
                elementIds
                    .stream()
                    .map(elementId -> io.vanillabp.integration.adapter.spi.workflowtask.ImplementedExternally
                        .propertyLine(workflowModuleId, bpmnProcessId, elementId))
                    .collect(Collectors.joining("\n"))));

  }

  /**
   * What the index of the cluster holds about the jobs of the job-worker user-task type for
   * one process. It covers both shapes the message names, because the cluster serves both
   * with a job of that one type.
   * <p>
   * {@link Camunda8UnservedUserTaskJobs} explains why two numbers come back and not one, and
   * why neither of them is exact.
   *
   * @param scopedBpmnProcessId The process id as the cluster knows it
   * @return What the index answered, or <code>null</code> where the cluster did not answer
   */
  private Camunda8UnservedUserTaskJobs.Count whatTheIndexHoldsOfTheJobWorkerUserTasks(
      final String scopedBpmnProcessId) {

    try {
      return Camunda8UnservedUserTaskJobs.countFor(clientFactory.getClient(), scopedBpmnProcessId);
    } catch (final RuntimeException e) {
      // a diagnostic never fails a deployment. If the cluster cannot answer, the message
      // says so instead
      log.debug(
          "Camunda8[{}]: the cluster did not answer how many job-worker user tasks of '{}' are open",
          adapterId,
          scopedBpmnProcessId,
          e);
      return null;
    }

  }

  /**
   * The tasks of ONE model the cluster holds, as the core checks them. The startup
   * check about older versions reads them through the version catalog. The user tasks
   * are read without any refusal. The check looks at a model which an earlier
   * application deployed. Refusing what is only being read would cost the check its
   * answer, over a model nobody can change any more.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version the cluster assigned, for messages
   * @param model The model of that version
   * @return The tasks of that model
   */
  private Collection<BpmnTaskSpec> taskSpecsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final BpmnModelInstance model) {

    final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);
    final var specs = new ArrayList<BpmnTaskSpec>();
    // the rounds in which a task of THIS model iterates without being handed their value.
    // They are read off the held model, not off the chains this boot recorded. Those belong
    // to the model just deployed, and the question is about the one the cluster still holds.
    // A model whose shape cannot be read answers null, and the core then asks nothing about it
    final var heldChains = Camunda8MultiInstance.chainsOf(model, scopedBpmnProcessId);
    Camunda8TaskWiring
        .tasksOf(model, scopedBpmnProcessId, connectorsAllowedFor(workflowModuleId, bpmnProcessId).allowed())
        .stream()
        .map(task -> new BpmnTaskSpec(
            task.activityId(), plainTaskDefinition(workflowModuleId, bpmnProcessId,
                task.taskDefinition()), false, null, itemsTheHeldModelNeverNames(heldChains, scopedBpmnProcessId,
                    task.activityId())))
        .forEach(specs::add);
    Camunda8TaskWiring
        .userTasksOfHeldModel(model, scopedBpmnProcessId)
        .stream()
        .map(userTask -> new BpmnTaskSpec(
            userTask.activityId(), plainTaskDefinition(workflowModuleId, bpmnProcessId,
                userTask.externalFormReference()), true, null, itemsTheHeldModelNeverNames(heldChains,
                    scopedBpmnProcessId, userTask.activityId())))
        .forEach(specs::add);
    // the listeners of a version the cluster still holds are tasks here as well. So a method
    // which serves one of them is not reported as unwired while workflows still run on that
    // version. Only if the key allows them. If nobody asked to serve the listener of a held
    // model, this application never had a method for it
    if (listenersAllowedFor(workflowModuleId, bpmnProcessId).allowed()) {
      Camunda8Listeners
          .listenersOf(model, scopedBpmnProcessId)
          .stream()
          // the rounds in which the element of the listener runs without being handed their
          // value, read the same way as for a task. A listener method reads its item out of the
          // same iteration. So the core has to be able to warn about it on a held version too
          .map(listener -> new BpmnTaskSpec(
              listener.elementId(), plainTaskDefinition(workflowModuleId, bpmnProcessId, listener
                  .taskDefinition()), false, null, itemsTheHeldModelNeverNames(heldChains, scopedBpmnProcessId,
                      listener.elementId())))
          // the same gate as for the deployed model. Only a listener which a method names is a
          // task of this application. A held model may carry one which nobody here ever served
          .filter(
              spec -> (spec.taskDefinition() != null) && workflowTaskInvoker
                  .workflowTaskHandlerExists(workflowModuleId, bpmnProcessId, spec.taskDefinition()))
          .forEach(specs::add);
    }
    return specs;

  }

  /**
   * The multi-instance elements enclosing one element of a HELD model which name no
   * <code>inputElement</code>, outermost first.
   *
   * @param heldChains The chains of that model, or <code>null</code> where it cannot be read
   * @param scopedBpmnProcessId The process id the cluster knows
   * @param elementId The element a method serves
   * @return The element ids, or <code>null</code> where the shape was not read
   */
  private static List<String> itemsTheHeldModelNeverNames(
      final Camunda8MultiInstance.Registry heldChains,
      final String scopedBpmnProcessId,
      final String elementId) {

    if (heldChains == null) {
      return null;
    }
    return List
        .copyOf(
            Camunda8MultiInstanceItems.elementsWithoutAnItem(heldChains, scopedBpmnProcessId, elementId));

  }

  /**
   * Reads which process of this workflow module calls which other one. It hands the graph to
   * the multi-instance registry. So an element of a called process gets the chain of its call
   * site in front of its own.
   * <p>
   * One kind of call activity is left out of the graph: one which calls a process with a
   * workflow aggregate of its own. That is not decomposition. Such a process runs a business
   * case of its own and is not told the iteration of whoever started it. The core answers
   * this with {@code workflowsShareTheWorkflowAggregate}.
   * <p>
   * A call activity whose <code>zeebe:calledElement processId</code> is an EXPRESSION is left
   * out of the graph too. No deployment can change that, because the process it reaches is
   * decided per instance. It still gets a chain, though - see
   * {@link #handTheChainDownWhereTheProcessIsNamedByAnExpression}, which runs right after the
   * graph is linked.
   * <p>
   * If the call activity is kept, the variables of the caller travel to the called process,
   * unless the model already said they do not. That is
   * {@link Camunda8MultiInstance#theCallersVariablesReachTheCalledProcess}. A call activity
   * which switched them off stays out of the graph.
   *
   * @param workflowModuleId The workflow module being deployed
   * @param bpmsProcessingContext Everything of it, as wired
   */
  void wireTheProcessesThisModuleCalls(
      final String workflowModuleId,
      final Camunda8ProcessingContext bpmsProcessingContext) {

    for (final var model : bpmsProcessingContext.getResources().values()) {
      for (final var process : model.getModelElementsByType(Process.class)) {
        if (!process.isExecutable()) {
          continue;
        }
        final var scopedCallerId = process.getId();
        final var plainCallerId = plainProcessId(workflowModuleId, scopedCallerId);
        final var calledProcesses = Camunda8MultiInstance.calledProcessesOf(model, scopedCallerId);
        for (final var callActivity : calledProcesses.entrySet()) {
          final var callActivityId = callActivity.getKey();
          final var scopedCalledId = callActivity.getValue();
          final var plainCalledId = plainProcessId(workflowModuleId, scopedCalledId);
          if (!workflowTaskWiring
              .workflowsShareTheWorkflowAggregate(workflowModuleId, plainCallerId, plainCalledId)) {
            continue;
          }
          // the model may say that the variables of the caller stay behind. Then nothing of the
          // iteration of the caller arrives. So nothing of it is registered either. Otherwise we
          // would promise a chain whose values the cluster never copies
          if (!Camunda8MultiInstance
              .theCallersVariablesReachTheCalledProcess(model, scopedCallerId, callActivityId)) {
            log
                .debug(
                    "Camunda8[{}]: the call activity '{}' of BPMN process '{}' keeps the variables "
                        + "of the enclosing scopes out of '{}', so a task there is told no iteration "
                        + "of its caller",
                    adapterId,
                    callActivityId,
                    plainCallerId,
                    plainCalledId);
            continue;
          }
          multiInstanceRegistry.registerCall(scopedCallerId, callActivityId, scopedCalledId);
        }
      }
    }
    multiInstanceRegistry.linkCalledProcesses();
    // now chainOf answers the levels of the callers as well, and a handler in a called process
    // may want the item of one of them. This runs before the cluster is asked anything, like
    // the first round of this question in wireBpmn
    refuseHandlersWantingAnItemACallerHasNot(workflowModuleId, bpmsProcessingContext);
    // what is left are the call activities which name their process by an expression. They
    // cannot be linked model to model, so they hand their chain down instead. That needs the
    // graph above to be linked already, because a caller passes on what IT inherited too
    handTheChainDownWhereTheProcessIsNamedByAnExpression(workflowModuleId, bpmsProcessingContext);

  }

  /**
   * Writes the iteration chain of every call activity which names its process by an
   * expression into the instance it calls, as the input mapping
   * {@link Camunda8MultiInstance#CHAIN_VARIABLE}.
   * <p>
   * <code>wireTheProcessesThisModuleCalls</code> cannot wire such a call activity, because the
   * process it reaches is decided per instance. But on the side of the CALLER the model is
   * complete. So the caller writes the chain down, and the reader of the job puts those
   * levels in front of its own. Nothing is written if the caller encloses the call activity
   * in no iteration at all. Nothing is written either if the model keeps the variables of the
   * caller out of the called instance.
   * <p>
   * The workflow aggregate is the one question left for the runtime. It is answered HERE as
   * far as possible. Every process of this module is compared with the caller. The ones which
   * share its aggregate are recorded as processes which may use that chain. At runtime the
   * reader only looks up whether the pair in front of it is one of them. So a call which
   * reaches a process with an aggregate of its own, or a process of another workflow module,
   * is dropped, not guessed.
   *
   * @param workflowModuleId The workflow module being deployed
   * @param bpmsProcessingContext Everything of it, as wired
   */
  private void handTheChainDownWhereTheProcessIsNamedByAnExpression(
      final String workflowModuleId,
      final Camunda8ProcessingContext bpmsProcessingContext) {

    final var namedByAnExpression = bpmsProcessingContext.getElementsNamingTheirTargetByExpression();
    if (namedByAnExpression.isEmpty()) {
      return;
    }
    final var processesOfThisModule = new ArrayList<String>();
    for (final var model : bpmsProcessingContext.getResources().values()) {
      model
          .getModelElementsByType(Process.class)
          .stream()
          .filter(Process::isExecutable)
          .map(Process::getId)
          .forEach(processesOfThisModule::add);
    }
    for (final var model : bpmsProcessingContext.getResources().values()) {
      for (final var process : model.getModelElementsByType(Process.class)) {
        if (!process.isExecutable()) {
          continue;
        }
        final var scopedCallerId = process.getId();
        final var plainCallerId = plainProcessId(workflowModuleId, scopedCallerId);
        var somethingWasHandedDown = false;
        for (final var elementId : namedByAnExpression.getOrDefault(plainCallerId, List.of())) {
          final var expression = Camunda8MultiInstance
              .handTheChainDown(model, scopedCallerId, elementId, multiInstanceRegistry);
          if (expression == null) {
            continue;
          }
          somethingWasHandedDown = true;
          log
              .debug(
                  "Camunda8[{}]: the call activity '{}' of BPMN process '{}' names the process it "
                      + "calls by an expression, so no deployment can say which process that is. "
                      + "Its iteration chain travels into the called instance in the variable "
                      + "'{}' instead: {}",
                  adapterId,
                  elementId,
                  plainCallerId,
                  Camunda8MultiInstance.CHAIN_VARIABLE,
                  expression);
        }
        if (!somethingWasHandedDown) {
          continue;
        }
        for (final var scopedCalledId : processesOfThisModule) {
          if (workflowTaskWiring
              .workflowsShareTheWorkflowAggregate(
                  workflowModuleId,
                  plainCallerId,
                  plainProcessId(workflowModuleId, scopedCalledId))) {
            multiInstanceRegistry.registerCallByExpression(scopedCallerId, scopedCalledId);
          }
        }
      }
    }

  }

  @Override
  public void deployResources(
      final String workflowModuleId,
      final Camunda8ProcessingContext bpmsProcessingContext) throws IllegalStateException {

    if (bpmsProcessingContext == null || bpmsProcessingContext.isEmpty()) {
      log.info("No executable BPMN resources for workflow module '{}' and adapter '{}' - "
          + "nothing to deploy to Camunda 8", workflowModuleId, adapterId);
      return;
    }

    // a task in a called process runs inside the iterations its CALL ACTIVITY sits in. Only
    // the model of the caller says which those are. Every file of this module is wired by
    // now, so this is the first moment the graph over them is complete. It runs before the
    // cluster is asked anything. So a model which this refuses is refused without a request
    wireTheProcessesThisModuleCalls(workflowModuleId, bpmsProcessingContext);

    // a cluster which boots together with the application lets every round of the start
    // fail. So it is waited for here, once per adapter instance. It is right before the first
    // round which decides anything: the searchable-cluster check below. That check would end
    // the boot of an application whose cluster is only coming up with it (see
    // io.vanillabp.camunda8.client.Camunda8ClusterWait). An adapter with nothing to deploy
    // makes no such round, so it waits for nothing
    clientFactory.waitUntilTheClusterAnswers();

    // and now that a cluster HAS answered: whether it answers a SEARCH. This adapter
    // serves no other kind. Per module, not once per adapter id, because the deployment
    // failure policy of THIS module reads the throw
    Camunda8SearchableClusterCheck
        .requireAClusterWhichCanBeSearched(adapterId, clientFactory.getQueryApi());

    // one DeployResourceCommand per workflow module with all its models
    final var client = clientFactory.getClient();
    DeployResourceCommandStep2 command = null;
    for (final var resource : bpmsProcessingContext.getResources().entrySet()) {
      final DeployResourceCommandStep1 next = command != null ? command : client.newDeployResourceCommand();
      command = next.addProcessModel(resource.getValue(), resource.getKey());
    }
    // the decision tables of the module go into the SAME command. A business rule task which
    // binds its decision to the deployment finds it, and both are versioned together
    for (final var decision : bpmsProcessingContext.getDecisions().entrySet()) {
      final DeployResourceCommandStep1 next = command != null ? command : client.newDeployResourceCommand();
      command = next.addResourceBytes(decision.getValue(), decision.getKey());
    }

    // the name-clash-avoidance mode decides which tenant a workflow module is deployed to.
    // 'by-adapter' (the default, as in version 1) uses the workflow module id. The
    // 'tenant-id' of the adapter can override it. 'use-prefix' and 'none' use no tenant at
    // all, because the identifiers were prefixed, or are unique by contract.
    validateTenantConfiguration(workflowModuleId);
    final var tenantId = tenantIdOf(workflowModuleId);
    if (tenantId != null) {
      // asking beforehand turns the "multi-tenancy is disabled" of the cluster, or an
      // unknown tenant, into a message which names the property to change (GAPS G2)
      if (verifiedTenants.add(tenantId)) {
        Camunda8TenantCheck
            .requireUsableTenant(adapterId, workflowModuleId, tenantId, client);
      }
      command = command.tenantId(tenantId);
    }
    // two BPMN processes may not reach the cluster under one identifier. Only the processes
    // of THIS module can be handed over, because a deployment is per workflow module. The
    // core holds what the earlier modules of this boot brought. It asks this adapter whether
    // its tenants keep two modules apart. This runs before the command is sent. So a module
    // which collides with an earlier one is refused, not deployed and then named
    if (scoping != null) {
      scoping.validateNoCollidingProcessIds(
          adapterId,
          bpmsProcessingContext
              .getDeployedProcessIds()
              .stream()
              .map(processId -> new NameClashAvoidanceSupport.DeployedProcess(
                  workflowModuleId, processId))
              .toList());
    }

    // what the cluster made of this deployment, collected while it is read anyway. The
    // question which identifiers the cluster ALREADY held needs the version it assigned and
    // the resource it recorded. Both are in the answer of the deploy command
    final var processesDeployed = new ArrayList<Camunda8IdentifiersTheClusterHolds.DeployedProcess>();
    final var decisionsDeployed = new ArrayList<Camunda8IdentifiersTheClusterHolds.DeployedDecision>();
    try {
      final var deployment = command
          .send()
          .join();
      // remember what was deployed. The viewer API serves definitions and BPMN XML
      // from these models, not from the eventually consistent query API (see
      // Camunda8DeployedProcesses)
      deployment
          .getProcesses()
          .forEach(process -> {
            final var model = bpmsProcessingContext
                .getResources()
                .get(process.getResourceName());
            if (model == null) {
              return;
            }
            // the cluster reports the id IT knows. The viewer API is keyed by the
            // PLAIN one, like every other identifier the core sees
            final var plainBpmnProcessId = NameClashAvoidanceSupport
                .plainProcessId(scoping, workflowModuleId, process.getBpmnProcessId(), adapterId);
            clientFactory
                .getDeployedProcesses()
                .record(
                    new Camunda8DeployedProcesses.DeployedProcess(
                        workflowModuleId, plainBpmnProcessId, String
                            .valueOf(process.getProcessDefinitionKey()), process.getVersion(), model));
            // the version the cluster just assigned, together with the version tag of the
            // deployed model. Neither needs a query
            processVersions.recordDeployedScoped(process.getBpmnProcessId(), process.getVersion());
            processVersions
                .recordDeployed(
                    workflowModuleId,
                    plainBpmnProcessId,
                    process.getVersion(),
                    Camunda8TaskWiring.versionTagOf(model, process.getBpmnProcessId()));
            // the border between the model this boot brought and the older
            // versions the cluster still holds
            workflowTaskWiring
                .registerDeployedVersion(
                    adapterId, workflowModuleId, plainBpmnProcessId, String.valueOf(process.getVersion()));
            processesDeployed
                .add(
                    new Camunda8IdentifiersTheClusterHolds.DeployedProcess(
                        plainBpmnProcessId, process.getBpmnProcessId(), process.getResourceName(), process
                            .getVersion()));
          });

      final var deployedDecisions = deployment.getDecisions();
      if ((deployedDecisions != null) && !deployedDecisions.isEmpty()) {
        deployedDecisions
            .forEach(decision -> decisionsDeployed
                .add(
                    new Camunda8IdentifiersTheClusterHolds.DeployedDecision(
                        plainIdentifier(workflowModuleId, decision.getDmnDecisionId()), decision
                            .getDmnDecisionId(), decision.getDmnDecisionRequirementsId(), decision.getVersion())));
        // the ids the CLUSTER knows, which is what a business rule task has to name
        log.info(
            "Deployed {} decision(s) of workflow module '{}' to Camunda 8 (adapter '{}'): {}",
            deployedDecisions.size(),
            workflowModuleId,
            adapterId,
            deployedDecisions
                .stream()
                .map(decision -> "%s (version %d)".formatted(decision.getDmnDecisionId(), decision.getVersion()))
                .toList());
      }
      log.info("Deployed {} BPMN resource(s) of workflow module '{}' to Camunda 8 "
          + "(adapter '{}', deployment key {}, tenant {}): {}",
          bpmsProcessingContext.getResources().size(),
          workflowModuleId,
          adapterId,
          deployment.getKey(),
          tenantForTheLog(tenantId),
          bpmsProcessingContext.getResources().keySet());
    } catch (final RuntimeException e) {
      throw new IllegalStateException(
          "Failed to deploy BPMN resources of workflow module '%s' to Camunda 8 (adapter '%s')!%s"
              .formatted(
                  workflowModuleId,
                  adapterId,
                  whatAQuotedExpressionIncludes(workflowModuleId, bpmsProcessingContext)), e);
    }

    // which identifiers of this module the cluster held before this deployment. This is
    // asked now, because the cluster has answered which versions and resources it recorded
    reportWhatTheClusterAlreadyHeld(workflowModuleId, processesDeployed, decisionsDeployed);
    // and which of them a second workflow module of this application uses as well. That
    // costs no request at all, because the names were read off the files themselves
    reportWhatTheModelsDeclare(workflowModuleId, bpmsProcessingContext);

    // last, and after every file of the module has been read. So the report names every
    // element at once, not one warning per file
    reportWhatConnectorsCost(workflowModuleId, bpmsProcessingContext);

    // and what the listeners somebody modelled cost, the same way
    reportWhatListenersCost(workflowModuleId, bpmsProcessingContext);

    // and what this release line cannot say about an instance which was canceled
    reportWhatACancelationCannotSay(workflowModuleId, bpmsProcessingContext);

  }

  /**
   * Says that an instance of this workflow module which is CANCELED tells the application
   * nothing. That is the case on every line before 8.10.
   * <p>
   * The gap is named per workflow module and on every boot. It stays true for as long as the
   * application runs on this line. If a workflow is terminated through the API, the tasks
   * which the application thinks are open in it stay open forever. Nothing else in the
   * running system says so. On 8.10 the list is empty and nothing is written.
   *
   * @param workflowModuleId The workflow module
   * @param context The module's accumulated pipeline state
   */
  void reportWhatACancelationCannotSay(
      final String workflowModuleId,
      final Camunda8ProcessingContext context) {

    final var processes = context.getProcessesWithoutACancelationReport();
    if (processes.isEmpty()) {
      return;
    }
    log
        .warn(
            "Camunda8[{}]: an instance of workflow module '{}' which is CANCELED reports nothing to "
                + "the application on release line {}. A 'cancel' execution listener on the process "
                + "element arrived with 8.10, and it is what lets VanillaBP report the end of a "
                + "terminated instance and cancel the tasks it still believes are open in it. Until "
                + "this application runs on a line built against 8.10 or later, those tasks stay "
                + "open and nothing says why. The BPMN processes it is about: {}.",
            adapterId,
            workflowModuleId,
            Camunda8ReleaseLine.id(),
            String.join(", ", processes));

  }

  /**
   * Hands the core the identifiers of this workflow module which the cluster already held.
   * So the warning can name our side, their side, and the change which frees the name.
   * <p>
   * It is wrapped from the outside as well as inside. A question about a name must not be
   * the reason why an application does not come up. And the deployment before it has
   * already succeeded.
   *
   * @param workflowModuleId The workflow module which was deployed
   * @param processes What this deployment brought, per BPMN process
   * @param decisions What this deployment brought, per DMN decision
   */
  private void reportWhatTheClusterAlreadyHeld(
      final String workflowModuleId,
      final List<Camunda8IdentifiersTheClusterHolds.DeployedProcess> processes,
      final List<Camunda8IdentifiersTheClusterHolds.DeployedDecision> decisions) {

    if (scoping == null) {
      return;
    }
    try {
      scoping
          .reportIdentifiersTheBpmsAlreadyHolds(
              adapterId,
              workflowModuleId,
              Camunda8IdentifiersTheClusterHolds
                  .askTheCluster(
                      adapterId,
                      workflowModuleId,
                      tenantIdOf(workflowModuleId),
                      clientFactory.getClient(),
                      processes,
                      decisions));
    } catch (final RuntimeException e) {
      log
          .debug(
              "Camunda8[{}]: could not find out which identifiers of workflow module '{}' the cluster "
                  + "already held",
              adapterId,
              workflowModuleId,
              e);
    }

  }

  /**
   * Hands the core the identifiers which the models of this workflow module declare. That is
   * how two workflow modules of this application which end up under one name get named. The
   * adapter rewrites every one of those names while it scopes a BPMN model or a decision
   * table. So it holds all of them, and the question costs no request.
   *
   * @param workflowModuleId The workflow module which was deployed
   * @param context What the pipeline collected for it
   */
  void reportWhatTheModelsDeclare(
      final String workflowModuleId,
      final Camunda8ProcessingContext context) {

    if (scoping == null) {
      return;
    }
    scoping
        .reportIdentifiersTheModelsDeclare(
            adapterId, workflowModuleId, context.getIdentifiersTheModelsDeclare());

  }

  /**
   * The options every worker of this adapter shares. {@link Camunda8Workers} applies them,
   * so a worker which an EXTENSION opens on the same cluster carries them too.
   *
   * @param builder The worker builder
   * @param jobType The job type the worker subscribes to
   * @return The same builder
   */
  JobWorkerBuilderStep1.JobWorkerBuilderStep3 applyWorkerOptions(
      final JobWorkerBuilderStep1.JobWorkerBuilderStep3 builder,
      final String jobType) {

    return Camunda8Workers
        .applyWorkerOptions(builder, adapterId, jobType, clientFactory.getConfiguration(), metrics);

  }

  /**
   * Opens this worker with a lease on every activation, if the application asked for one.
   * <p>
   * Whoever carries the token answers a leased activation. So the workflow continues with
   * the run which finished last. It works like a ratchet, per job. No command removes a
   * lease, and a worker of the same job type which does not lease never sees a leased job
   * again. That is why the application decides and there is no default, see decision 36 in
   * the repository's DECISIONS.md.
   * <p>
   * This is the answer for a worker which holds its job from the activation to the answer:
   * the listeners, the start events the cluster fires, and the end of a workflow. A worker
   * which serves TASKS asks {@link #leaseUnlessATaskStaysOpen} instead.
   *
   * @param builder The worker builder
   * @return The same builder
   */
  JobWorkerBuilderStep1.JobWorkerBuilderStep3 leaseTheActivations(
      final JobWorkerBuilderStep1.JobWorkerBuilderStep3 builder) {

    return Camunda8Workers.leaseTheActivations(builder, clientFactory.getConfiguration());

  }

  /**
   * Opens a worker of this adapter id through {@link Camunda8Workers}. There it is counted
   * against the connection pool of the client it polls with. The workers of the adapter take
   * the same path as those of an EXTENSION. So the number is right, whoever opened them.
   *
   * @param builder The worker builder, ready to open
   * @return The open worker
   */
  private JobWorker openWorker(
      final JobWorkerBuilderStep1.JobWorkerBuilderStep3 builder) {

    return Camunda8Workers.open(builder, clientFactory);

  }

  /**
   * The same, for a worker which serves tasks. It leases only if none of the tasks it serves
   * can stay open.
   * <p>
   * An ASYNCHRONOUS task is completed in phase two, hours after the activation, by a
   * dispatcher which holds no token. So a leased job of such a task could never be completed
   * at all. The question is asked per job type, not per task, because a worker subscribes
   * to a job type. If one task definition it serves wants to stay open, the whole worker
   * activates without a lease.
   *
   * @param builder The worker builder
   * @param workflowModuleId The workflow module
   * @param served What this job type serves, or <code>null</code> where nothing is known
   *          about it
   * @return The same builder
   */
  JobWorkerBuilderStep1.JobWorkerBuilderStep3 leaseUnlessATaskStaysOpen(
      final JobWorkerBuilderStep1.JobWorkerBuilderStep3 builder,
      final String workflowModuleId,
      final Collection<ServedElement> served) {

    if (!clientFactory.getConfiguration().leasesItsJobs()) {
      return builder;
    }
    final var somethingStaysOpen = (served == null) || served
        .stream()
        .anyMatch(
            element -> workflowTaskWiring
                .workflowTaskCompletesAsynchronously(
                    workflowModuleId,
                    plainProcessId(workflowModuleId, element.scopedBpmnProcessId()),
                    element.taskDefinition()));
    return somethingStaysOpen
        ? builder
        : Camunda8Workers.leaseTheActivations(builder, clientFactory.getConfiguration());

  }

  /**
   * The lock of a worker which serves no task: the user-task lifecycle listeners, the start
   * events the cluster fires itself, and the processes whose end is reported. All three run
   * application code inside a transaction, exactly like a task. So they are resolved like the
   * <code>job-timeout</code> of a task: at adapter, workflow-module and workflow level,
   * because there is no task to key them by. Their default is the same five minutes.
   * <p>
   * A worker subscribes by job type. One user-task listener job type may belong to several
   * BPMN processes of the module. If those resolve to different locks, the deployment fails
   * with guidance, in the same way as conflicting job timeouts of one task definition.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessIds The BPMN processes this worker serves (scoped ids)
   * @param kind What kind of worker it is, for the message
   * @param jobType The job type the worker subscribes to
   * @return The resolved lock
   */
  Duration listenerLockOf(
      final String workflowModuleId,
      final List<String> bpmnProcessIds,
      final String kind,
      final String jobType) {

    Duration resolved = null;
    String resolvedFor = null;
    for (final var bpmnProcessId : bpmnProcessIds) {
      final var plainBpmnProcessId = plainProcessId(workflowModuleId, bpmnProcessId);
      final var timeout = jobTimeoutResolver.jobTimeoutFor(workflowModuleId, plainBpmnProcessId, null);
      if (resolved == null) {
        resolved = timeout;
        resolvedFor = plainBpmnProcessId;
      } else if (!resolved.equals(timeout)) {
        throw new IllegalStateException(
            """
                The %s worker '%s' of workflow module '%s' serves the BPMN processes '%s' and '%s', \
                whose resolved job timeouts CONFLICT (%s vs. %s)! One worker serves a job type, so \
                its lock has to be the same for every process using it - align \
                'vanillabp.workflow-modules.%s.workflows.<workflow>.adapters.%s.job-timeout' for \
                those processes."""
                .formatted(
                    kind,
                    jobType,
                    workflowModuleId,
                    resolvedFor,
                    plainBpmnProcessId,
                    resolved,
                    timeout,
                    workflowModuleId,
                    adapterId));
      }
    }
    return resolved == null
        ? Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT
        : resolved;

  }

  /**
   * The variable in which a BPMN process carries the ID of the workflow aggregate. Every
   * worker of this adapter reads this one variable. Only a claimed process has one. A process
   * nobody claims has no workflow aggregate, and this adapter wires nothing for it (see
   * decision 73 of {@code DECISIONS.md}).
   *
   * @param workflowModuleId The workflow module
   * @param plainBpmnProcessId The BPMN process id as the core knows it
   * @return The variable's name, or <code>null</code> for a process nobody claims
   */
  private String aggregateIdNameOf(
      final String workflowModuleId,
      final String plainBpmnProcessId) {

    if (!workflowTaskWiring.isClaimedByAWorkflowService(workflowModuleId, plainBpmnProcessId)) {
      return null;
    }
    return workflowTaskWiring.resolveWorkflowAggregateIdName(workflowModuleId, plainBpmnProcessId);

  }


  /**
   * What one worker asks the cluster for. It is the union of the aggregate-ID variables,
   * the multi-instance contexts and the declared <code>&#64;TaskParam</code> names of
   * everything it serves.
   *
   * @param workflowModuleId The workflow module
   * @param served The elements this worker serves, as (scoped BPMN process id, BPMN
   *          element id, plain task definition or <code>null</code>)
   * @return The selection, never <code>null</code>
   */
  Camunda8FetchVariables.Selection fetchVariablesOf(
      final String workflowModuleId,
      final List<ServedElement> served) {

    final var variables = new TreeSet<String>();
    for (final var element : served) {
      final var plainBpmnProcessId = plainProcessId(workflowModuleId, element.scopedBpmnProcessId());
      final var aggregateIdName = aggregateIdNameOf(workflowModuleId, plainBpmnProcessId);
      if (aggregateIdName == null) {
        return Camunda8FetchVariables.Selection.everything();
      }
      if (element.elementId() == null) {
        // the workflow-end listener. It reports a process, not an element. A @WorkflowEnded
        // method cannot declare a @TaskParam at all (the core rejects one). So the id of the
        // aggregate is the complete answer here. It is also the one worker which reports no
        // iteration, so it asks for no multi-instance variable either
        variables.add(aggregateIdName);
        continue;
      }
      Camunda8FetchVariables.collect(
          variables,
          aggregateIdName,
          multiInstanceRegistry.chainOf(element.scopedBpmnProcessId(), element.elementId()));
      // and what the handlers of this element read with @TaskParam. The core read those
      // names off the methods while wiring. So the list is what the application asks for,
      // not what the model happens to mention. It is asked with BOTH keys a method can be
      // wired by, as the check for a multi-instance item asks them. A method which names the
      // element id serves this element too. Asking for the job type alone left its variables
      // unfetched. That failed the job instead of passing null, because the worker refuses
      // a @TaskParam it did not fetch
      variables
          .addAll(
              workflowTaskWiring
                  .taskParameterNames(workflowModuleId, plainBpmnProcessId, element.elementId()));
      if (element.taskDefinition() != null) {
        variables
            .addAll(
                workflowTaskWiring
                    .taskParameterNames(workflowModuleId, plainBpmnProcessId, element.taskDefinition()));
      }
    }
    return Camunda8FetchVariables.Selection.of(variables);

  }

  /**
   * One BPMN element a worker serves. The fetch list is built from these.
   *
   * @param scopedBpmnProcessId The BPMN process id as the CLUSTER knows it. The
   *          multi-instance registry is keyed by it
   * @param elementId The BPMN element id, or <code>null</code> if the worker serves a
   *          whole process, not an element (the workflow-end listener). In that case no
   *          <code>&#64;TaskParam</code> can occur either
   * @param taskDefinition The task definition as the CORE knows it, or <code>null</code>
   *          if there is no task level to configure
   */
  record ServedElement(String scopedBpmnProcessId,
                       String elementId,
                       String taskDefinition) {
  }

  /**
   * Tells the worker what to ask for, and says so once per worker, at DEBUG. If somebody
   * reports a variable their handler does not see any more, this line answers the first
   * question.
   *
   * @param builder The worker builder
   * @param workflowModuleId The workflow module
   * @param kind What kind of worker it is, for the message
   * @param jobType The job type the worker subscribes to
   * @param selection What the worker asks for
   * @return The same builder
   */
  JobWorkerBuilderStep1.JobWorkerBuilderStep3 applyFetchVariables(
      final JobWorkerBuilderStep1.JobWorkerBuilderStep3 builder,
      final String workflowModuleId,
      final String kind,
      final String jobType,
      final Camunda8FetchVariables.Selection selection) {

    log.debug(
        "Camunda8[{}]: the {} worker '{}' of workflow module '{}' fetches {}",
        adapterId,
        kind,
        jobType,
        workflowModuleId,
        selection.describe());
    return selection.all()
        ? builder
        : builder.fetchVariables(selection.names());

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Camunda8ProcessingContext bpmsProcessingContext) {

    aModuleIsOpeningItsWorkers = true;
    try {
      openTheWorkersOfTheWorkflowModule(workflowModuleId, bpmsProcessingContext);
    } finally {
      aModuleIsOpeningItsWorkers = false;
    }
    // every worker of this module is open now. So this is the first moment the number of
    // workers of this adapter id is known. And it is the last one before the block of the
    // start is written
    holdTheOpenWorkersAgainstTheConnectionPool();
    // a version which an earlier generation of the application deployed may carry a standard
    // loop, and the workflows on it repeat nothing
    warnAboutStandardLoopsOfHeldVersions(workflowModuleId);

  }

  /**
   * Warns about each version the cluster holds which carries a standard loop while workflows
   * still run on it. Such a model was deployed before this adapter refused it, by version 1 or
   * by an older snapshot. Nobody can change it any more. So this is a warning, not a refusal.
   * <p>
   * The models come from the picture of what the cluster holds. The startup check of the core
   * reads it anyway. If the cluster cannot be searched, the picture says so once, and nothing
   * is said here.
   *
   * @param workflowModuleId The workflow module which is about to process workflows
   */
  private void warnAboutStandardLoopsOfHeldVersions(
      final String workflowModuleId) {

    final var modelsTheClusterHolds = clientFactory.getModelsTheClusterHolds();
    if (modelsTheClusterHolds == null) {
      return;
    }
    if (!(modelsTheClusterHolds
        .heldFor(workflowModuleId) instanceof Camunda8ModelsTheClusterHolds.Answer.Known known)) {
      return;
    }
    known
        .models()
        .stream()
        .collect(Collectors.groupingBy(
            Camunda8ModelsTheClusterHolds.HeldModel::bpmnProcessId, TreeMap::new, Collectors.toList()))
        .forEach((
            bpmnProcessId,
            heldModels) -> Camunda8StandardLoops
                .heldVersionsToWarnAbout(
                    heldModels,
                    scopedProcessId(workflowModuleId, bpmnProcessId),
                    version -> workflowsRunningOn(workflowModuleId, bpmnProcessId, version))
                .forEach(heldVersion -> log
                    .warn(
                        "Camunda8[{}]: {}",
                        adapterId,
                        Camunda8StandardLoops.warningAboutAHeldVersion(heldVersion, bpmnProcessId, workflowModuleId))));

  }

  /**
   * How many workflows run on one version, or <code>null</code> if the cluster did not
   * answer. A failed search is no reason to stop a boot over a warning.
   */
  private Long workflowsRunningOn(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    try {
      return processVersions.activeInstanceCountOf(workflowModuleId, bpmnProcessId, version);
    } catch (final RuntimeException e) {
      log.debug(
          "Camunda8[{}]: could not count the workflows on version {} of BPMN process '{}'",
          adapterId,
          version,
          bpmnProcessId,
          e);
      return null;
    }

  }

  /**
   * Opens the workers of one workflow module: one per task definition it serves, plus the
   * ones its listeners, its BPMS-initiated starts and its reported ends need.
   *
   * @param workflowModuleId The module which starts processing
   * @param bpmsProcessingContext What preparing its models produced, and where its open
   *          workers are collected
   */
  private void openTheWorkersOfTheWorkflowModule(
      final String workflowModuleId,
      final Camunda8ProcessingContext bpmsProcessingContext) {

    // one polling worker per (adapter id, task definition). The job type routes
    // deliveries. Tasks of DIFFERENT processes which share a task definition are
    // served by one worker (job.getBpmnProcessId() routes to the right handlers).
    // For the job timeout, the most specific level wins. A task definition used by
    // several tasks with CONFLICTING configured timeouts fails with guidance.
    final var timeoutsByDefinition = new LinkedHashMap<String, Duration>();
    // what each worker serves. Its fetch list is the union over these
    final var servedByJobType = new LinkedHashMap<String, List<ServedElement>>();
    final var client = clientFactory.getClient();
    // what this module has in flight, and later whether it is going down. Every handler
    // registers its delivery here, and stopWorkflowProcessing waits for them
    final var drain = clientFactory.freshDrainOf(workflowModuleId);
    // and the client learns that this module has workers open. So a shutdown path which
    // never reaches stopWorkflowProcessing does not close the client under them
    shutdownRegistrations
        .put(
            workflowModuleId,
            clientFactory
                .workflowModuleStarted(
                    workflowModuleId,
                    () -> stopWorkflowProcessing(workflowModuleId, bpmsProcessingContext)));
    // what asks the cluster about the OTHER tasks which the core thinks are open in a
    // workflow, whenever this module serves a job of it. There is one per module, handed to
    // the three handlers which are a wake-up. A second copy of the rule would give a second
    // answer to one question. It knows whether this module carries user tasks managed by
    // Camunda. A user-task key handed to a job command answers NOT_FOUND and would read as
    // gone
    final var openTaskProbe = new Camunda8OpenTaskProbe(
        adapterId, workflowModuleId, workflowTaskInvoker, clientFactory::getClient, clientFactory::getConfiguration, asyncTaskLockRenewal, theKindOfTaskARecordNames(
            workflowModuleId, bpmsProcessingContext), bpmnProcessId -> clientFactory
                .getDeployedProcesses()
                .carriesTheReservedProbeElement(workflowModuleId, bpmnProcessId));
    bpmsProcessingContext
        .getTasksToWire()
        .forEach(task -> {
          if (task.taskDefinition() == null) {
            return; // already reported by the wiring validation
          }
          // the records carry what the CLUSTER knows, because the worker subscribes to it.
          // But the configuration is keyed by the PLAIN names
          final var plainBpmnProcessId = plainProcessId(workflowModuleId, task.bpmnProcessId());
          final var plainTaskDefinition = plainTaskDefinition(
              workflowModuleId,
              plainBpmnProcessId,
              task.taskDefinition());
          servedByJobType
              .computeIfAbsent(task.taskDefinition(), key -> new LinkedList<>())
              .add(new ServedElement(task.bpmnProcessId(), task.activityId(), plainTaskDefinition));
          final var timeout = jobTimeoutResolver.jobTimeoutFor(
              workflowModuleId,
              plainBpmnProcessId,
              plainTaskDefinition);
          final var previous = timeoutsByDefinition.putIfAbsent(task.taskDefinition(), timeout);
          if ((previous != null) && !previous.equals(timeout)) {
            throw new IllegalStateException(
                """
                    The task definition '%s' of workflow module '%s' is used by several tasks with \
                    CONFLICTING job timeouts (%s vs. %s)! One polling worker serves a task \
                    definition - configure the same 'job-timeout' for all its tasks (property \
                    levels: vanillabp.workflow-modules.%s.workflows.<workflow>.tasks.%s.adapters.%s.job-timeout)."""
                    .formatted(
                        task.taskDefinition(),
                        workflowModuleId,
                        previous,
                        timeout,
                        workflowModuleId,
                        task.taskDefinition(),
                        adapterId));
          }
        });
    // user-task lifecycle listeners: one worker per distinct listener job type.
    // Listener jobs are consumed like normal jobs
    final var userTasksByListenerJobType = new LinkedHashMap<String, List<String>>();
    bpmsProcessingContext
        .getUserTasksToWire()
        .forEach(userTask -> {
          userTasksByListenerJobType
              .computeIfAbsent(userTask.listenerJobType(), key -> new LinkedList<>())
              .add(userTask.bpmnProcessId());
          final var plainBpmnProcessId = plainProcessId(workflowModuleId, userTask.bpmnProcessId());
          servedByJobType
              .computeIfAbsent(userTask.listenerJobType(), key -> new LinkedList<>())
              .add(new ServedElement(userTask.bpmnProcessId(), userTask.activityId(), plainTaskDefinition(
                  workflowModuleId,
                  plainBpmnProcessId,
                  userTask.externalFormReference())));
        });
    userTasksByListenerJobType.forEach((
        listenerJobType,
        bpmnProcessIds) -> {
      final var listenerFetch = fetchVariablesOf(workflowModuleId, servedByJobType.get(listenerJobType));
      var listenerWorkerBuilder = applyFetchVariables(applyWorkerOptions(client
          .newWorker()
          .jobType(listenerJobType)
          .handler(Camunda8UserTaskListenerHandler
              .builder()
              .adapterId(adapterId)
              .workflowModuleId(workflowModuleId)
              .workflowTaskInvoker(workflowTaskInvoker)
              .scoping(scoping)
              .multiInstanceRegistry(multiInstanceRegistry)
              .drain(drain)
              .fetchVariables(listenerFetch)
              .openTaskProbe(openTaskProbe)
              .build())
          .timeout(
              listenerLockOf(workflowModuleId, bpmnProcessIds, "user-task listener", listenerJobType))
          .name("vanillabp-%s-%s".formatted(adapterId, listenerJobType)), listenerJobType),
          workflowModuleId,
          "user-task listener",
          listenerJobType,
          listenerFetch);
      listenerWorkerBuilder = leaseTheActivations(listenerWorkerBuilder);
      final var listenerTenantId = tenantIdOf(workflowModuleId);
      if (listenerTenantId != null) {
        // with 'by-adapter', jobs of a tenant are only delivered to workers which
        // subscribe for that tenant
        listenerWorkerBuilder = listenerWorkerBuilder.tenantId(listenerTenantId);
      }
      final var worker = openWorker(listenerWorkerBuilder);
      bpmsProcessingContext.getOpenWorkers().add(worker);
      log.info(
          "Camunda8[{}]: opened user-task listener worker for '{}' of workflow module '{}'",
          adapterId,
          listenerJobType,
          workflowModuleId);
    });

    // the listeners somebody modelled: one worker per distinct job type, the same way as
    // for the user-task listeners above. The records carry the PLAIN names, because the
    // configuration is keyed by those. So the job type is scoped again here
    final var modelledListenersByJobType = new LinkedHashMap<String, List<String>>();
    bpmsProcessingContext
        .getModelledListeners()
        .forEach(listener -> {
          final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, listener.bpmnProcessId());
          final var scopedJobType = NameClashAvoidanceSupport
              .scopedTaskDefinition(
                  scoping,
                  workflowModuleId,
                  listener.bpmnProcessId(),
                  listener.taskDefinition(),
                  adapterId);
          modelledListenersByJobType
              .computeIfAbsent(scopedJobType, key -> new LinkedList<>())
              .add(scopedBpmnProcessId);
          servedByJobType
              .computeIfAbsent(scopedJobType, key -> new LinkedList<>())
              .add(new ServedElement(scopedBpmnProcessId, listener.elementId(), listener.taskDefinition()));
        });
    modelledListenersByJobType.forEach((
        jobType,
        scopedBpmnProcessIds) -> {
      final var listenerFetch = fetchVariablesOf(workflowModuleId, servedByJobType.get(jobType));
      var builder = applyFetchVariables(applyWorkerOptions(client
          .newWorker()
          .jobType(jobType)
          .handler(Camunda8ModelledListenerHandler
              .builder()
              .adapterId(adapterId)
              .workflowModuleId(workflowModuleId)
              .workflowTaskInvoker(workflowTaskInvoker)
              .scoping(scoping)
              .multiInstanceRegistry(multiInstanceRegistry)
              .drain(drain)
              .fetchVariables(listenerFetch)
              .retryBackoffResolver(retryBackoffResolver)
              .openTaskProbe(openTaskProbe)
              .build())
          .timeout(listenerLockOf(workflowModuleId, scopedBpmnProcessIds, "modelled listener", jobType))
          .name("vanillabp-%s-%s".formatted(adapterId, jobType)), jobType),
          workflowModuleId,
          "modelled listener",
          jobType,
          listenerFetch);
      builder = leaseTheActivations(builder);
      final var listenerTenantId = tenantIdOf(workflowModuleId);
      if (listenerTenantId != null) {
        builder = builder.tenantId(listenerTenantId);
      }
      bpmsProcessingContext.getOpenWorkers().add(openWorker(builder));
      log.info(
          "Camunda8[{}]: opened listener worker for '{}' of workflow module '{}'",
          adapterId,
          jobType,
          workflowModuleId);
    });

    // start events which the cluster fires on its own: one worker per start event,
    // because its job type carries the process and the element
    bpmsProcessingContext
        .getBpmsInitiatedStartsToWire()
        .forEach(startEvent -> {
          final var plainProcessId = plainProcessId(workflowModuleId, startEvent.bpmnProcessId());
          var startWorkerBuilder = applyFetchVariables(applyWorkerOptions(client
              .newWorker()
              .jobType(startEvent.listenerJobType())
              .handler(new Camunda8BpmsInitiatedStartHandler(
                  adapterId, workflowModuleId, plainProcessId, startEvent.startEventId(), startEvent.kind(), startEvent
                      .signalName(), bpmsInitiatedStartInvoker, drain, retryBackoffResolver))
              .timeout(
                  listenerLockOf(workflowModuleId, List.of(startEvent.bpmnProcessId()), "start-event", startEvent
                      .listenerJobType()))
              .name("vanillabp-%s-%s".formatted(adapterId, startEvent.listenerJobType())),
              startEvent
                  .listenerJobType()),
              workflowModuleId,
              "start-event",
              startEvent.listenerJobType(),
              // nothing to derive here. VanillaBP copies every variable such a start
              // carries into the workflow aggregate it builds. So a list would decide
              // which of the application's own values survive
              Camunda8FetchVariables.Selection.everything());
          startWorkerBuilder = leaseTheActivations(startWorkerBuilder);
          final var startTenantId = tenantIdOf(workflowModuleId);
          if (startTenantId != null) {
            startWorkerBuilder = startWorkerBuilder.tenantId(startTenantId);
          }
          bpmsProcessingContext.getOpenWorkers().add(openWorker(startWorkerBuilder));
          log.info(
              "Camunda8[{}]: opened start-event worker for '{}' of workflow module '{}'",
              adapterId,
              startEvent.listenerJobType(),
              workflowModuleId);
        });

    // one worker per process whose end is reported
    bpmsProcessingContext
        .getWorkflowEndedProcessesToWire()
        .forEach(scopedProcessId -> {
          final var plainProcessId = plainProcessId(workflowModuleId, scopedProcessId);
          final var endFetch = fetchVariablesOf(
              workflowModuleId,
              List.of(new ServedElement(scopedProcessId, null, null)));
          var endWorkerBuilder = applyFetchVariables(applyWorkerOptions(client
              .newWorker()
              .jobType(Camunda8TaskWiring.workflowEndedJobTypeOf(scopedProcessId))
              // asking the core directly is safe here. wireBpmn put only processes
              // with a known workflow aggregate into this list
              .handler(new Camunda8WorkflowEndedHandler(
                  adapterId, workflowModuleId, plainProcessId, workflowTaskWiring
                      .resolveWorkflowAggregateIdName(workflowModuleId,
                          plainProcessId), workflowEndedInvoker, drain, retryBackoffResolver))
              .timeout(
                  listenerLockOf(workflowModuleId, List.of(scopedProcessId), "workflow-end", Camunda8TaskWiring
                      .workflowEndedJobTypeOf(scopedProcessId)))
              .name("vanillabp-%s-%s".formatted(adapterId, scopedProcessId)),
              Camunda8TaskWiring
                  .workflowEndedJobTypeOf(scopedProcessId)),
              workflowModuleId,
              "workflow-end",
              Camunda8TaskWiring.workflowEndedJobTypeOf(scopedProcessId),
              endFetch);
          endWorkerBuilder = leaseTheActivations(endWorkerBuilder);
          final var endTenantId = tenantIdOf(workflowModuleId);
          if (endTenantId != null) {
            endWorkerBuilder = endWorkerBuilder.tenantId(endTenantId);
          }
          bpmsProcessingContext.getOpenWorkers().add(openWorker(endWorkerBuilder));
          log.info(
              "Camunda8[{}]: opened workflow-end worker for BPMN process '{}' of workflow module '{}'",
              adapterId,
              plainProcessId,
              workflowModuleId);
        });

    timeoutsByDefinition.forEach((
        taskDefinition,
        timeout) -> {
      final var taskFetch = fetchVariablesOf(workflowModuleId, servedByJobType.get(taskDefinition));
      var workerBuilder = applyFetchVariables(applyWorkerOptions(client
          .newWorker()
          .jobType(taskDefinition)
          .handler(Camunda8JobHandler
              .builder()
              .adapterId(adapterId)
              .workflowModuleId(workflowModuleId)
              .camundaClient(client)
              .workflowTaskInvoker(workflowTaskInvoker)
              .asyncTaskLockRenewal(asyncTaskLockRenewal)
              .scoping(scoping)
              .multiInstanceRegistry(multiInstanceRegistry)
              .asyncTaskMaxAgeAction(asyncTaskMaxAgeAction())
              .drain(drain)
              .retryBackoffResolver(retryBackoffResolver)
              .fetchVariables(taskFetch)
              .predatesDeployedVersion(processVersions::predatesDeployedVersion)
              .openTaskProbe(openTaskProbe)
              .build())
          .timeout(timeout)
          .name("vanillabp-%s-%s".formatted(adapterId, taskDefinition)), taskDefinition),
          workflowModuleId,
          "task",
          taskDefinition,
          taskFetch);
      workerBuilder = leaseUnlessATaskStaysOpen(
          workerBuilder, workflowModuleId, servedByJobType.get(taskDefinition));
      final var workerTenantId = tenantIdOf(workflowModuleId);
      if (workerTenantId != null) {
        workerBuilder = workerBuilder.tenantId(workerTenantId);
      }
      final var worker = openWorker(workerBuilder);
      bpmsProcessingContext.getOpenWorkers().add(worker);
      log.info(
          "Camunda8[{}]: opened job worker for task definition '{}' of workflow module '{}' "
              + "(job timeout {})",
          adapterId,
          taskDefinition,
          workflowModuleId,
          timeout);
    });

    openTheWorkersOfTheProcessesNobodyDeployed(
        workflowModuleId,
        bpmsProcessingContext,
        client,
        drain,
        jobTypesAWorkerIsAlreadyOpenFor(servedByJobType.keySet(), bpmsProcessingContext),
        openTaskProbe);

  }

  /**
   * The job types this workflow module already has a worker for. These are the task
   * definitions and user-task listeners of its deployed processes, plus the ends of the
   * processes whose end is reported. What is in here needs no second worker for a declared
   * BPMN process id. A worker subscribes to the name. So if the name does not carry the
   * process id, the workers of the deployed processes reach the workflows of the old id too.
   *
   * @param servedJobTypes The job types the tasks and user tasks produced
   * @param bpmsProcessingContext The context of the module being started
   * @return The job types, in no particular order
   */
  private static Set<String> jobTypesAWorkerIsAlreadyOpenFor(
      final Set<String> servedJobTypes,
      final Camunda8ProcessingContext bpmsProcessingContext) {

    final var jobTypes = new java.util.HashSet<>(servedJobTypes);
    bpmsProcessingContext
        .getWorkflowEndedProcessesToWire()
        .forEach(scopedProcessId -> jobTypes.add(Camunda8TaskWiring.workflowEndedJobTypeOf(scopedProcessId)));
    return jobTypes;

  }

  /**
   * Opens the workers which reach the workflows of a BPMN process which this application
   * DECLARES without deploying a model under it. An example is the old id of a renamed
   * process.
   * <p>
   * A worker subscribes to a job type. Under <code>use-prefix</code>, a job type carries the
   * id of the process it was deployed with. So the jobs of the workflows under the old id are
   * named after the OLD id. No worker of the deployed processes asks for them, and nobody
   * notices, because a job nobody fetches does not fail. So the workflows need one more
   * subscription per name they produce. The names are built the same way as the deployed
   * ones: the task definitions which the application serves for that id, scoped by it.
   * <p>
   * If a job type is already served, nothing is opened. That is the case in every mode but
   * <code>use-prefix</code>, and in <code>use-prefix</code> with
   * <code>prefix-task-definitions-per-process: false</code>. So an application which does
   * not scope task definitions by their process notices none of this, as before.
   *
   * @param workflowModuleId The workflow module which is about to process workflows
   * @param bpmsProcessingContext The context whose open workers are closed on shutdown
   * @param client The client of this adapter id
   * @param drain What the module has in flight, handed to every handler
   * @param jobTypesAlreadyServed The job types the deployed processes opened a worker for
   */
  private void openTheWorkersOfTheProcessesNobodyDeployed(
      final String workflowModuleId,
      final Camunda8ProcessingContext bpmsProcessingContext,
      final CamundaClient client,
      final Camunda8Drain drain,
      final Set<String> jobTypesAlreadyServed,
      final Camunda8OpenTaskProbe openTaskProbe) {

    workflowTaskWiring
        .taskWiringOfProcessesNobodyDeployed(workflowModuleId)
        .forEach((
            bpmnProcessId,
            taskDefinitions) -> {
          // whatever scoping decides about extra workers below, the message check of
          // correlateMessage has to know that models of this module live only in the
          // cluster. So the declared id is recorded in every mode
          clientFactory
              .getDeployedProcesses()
              .recordDeclaredWithoutDeployment(workflowModuleId, bpmnProcessId);
          registerMultiInstanceChainsOf(workflowModuleId, bpmnProcessId);
          final var openedJobTypes = new TreeSet<String>();
          taskDefinitions
              .forEach(taskDefinition -> {
                final var jobType = NameClashAvoidanceSupport
                    .scopedTaskDefinition(scoping, workflowModuleId, bpmnProcessId, taskDefinition, adapterId);
                if (jobTypesAlreadyServed.contains(jobType)) {
                  return;
                }
                openTaskWorker(
                    workflowModuleId,
                    bpmnProcessId,
                    jobType,
                    bpmsProcessingContext,
                    client,
                    drain,
                    openTaskProbe);
                openedJobTypes.add(jobType);
                // a served task definition belongs either to a service task or to a user
                // task. Which of the two cannot be told without the model, and this
                // application no longer has it. So both subscriptions are opened, and the
                // one of the wrong kind stays idle - see decision 19 in the repository's
                // DECISIONS.md
                final var listenerJobType = Camunda8TaskWiring.TASKDEFINITION_USERTASK_ZEEBE + jobType;
                openUserTaskListenerWorker(
                    workflowModuleId,
                    bpmnProcessId,
                    listenerJobType,
                    bpmsProcessingContext,
                    client,
                    drain,
                    openTaskProbe);
                openedJobTypes.add(listenerJobType);
              });
          // a user task may have no method, and the cluster still waits for its creating
          // listener. For a deployed model, every user task gets a listener worker. For the old
          // id, the task definitions above only name the served ones. So the listeners of the
          // held models are read and served as well
          userTaskListenerJobTypesTheClusterHoldsFor(workflowModuleId, bpmnProcessId)
              .stream()
              .filter(listenerJobType -> !jobTypesAlreadyServed.contains(listenerJobType))
              .filter(listenerJobType -> !openedJobTypes.contains(listenerJobType))
              .forEach(listenerJobType -> {
                openUserTaskListenerWorker(
                    workflowModuleId,
                    bpmnProcessId,
                    listenerJobType,
                    bpmsProcessingContext,
                    client,
                    drain,
                    openTaskProbe);
                openedJobTypes.add(listenerJobType);
              });
          openWorkflowEndWorkerOfADeclaredId(
              workflowModuleId,
              bpmnProcessId,
              bpmsProcessingContext,
              client,
              drain,
              jobTypesAlreadyServed,
              openedJobTypes);
          reportWhatADeclaredIdIsServedWith(workflowModuleId, bpmnProcessId, taskDefinitions, openedJobTypes);
        });

  }

  /**
   * The job types of the user-task listeners in the models the cluster holds under a
   * declared BPMN process id. If the cluster cannot be asked, nothing is added. Then the
   * user tasks of those workflows are served only if a method names their task definition,
   * as before.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id
   * @return The listener job types, each once
   */
  private Set<String> userTaskListenerJobTypesTheClusterHoldsFor(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var modelsTheClusterHolds = clientFactory.getModelsTheClusterHolds();
    if (modelsTheClusterHolds == null) {
      return Set.of();
    }
    if (!(modelsTheClusterHolds
        .heldFor(workflowModuleId, bpmnProcessId) instanceof Camunda8ModelsTheClusterHolds.Answer.Known known)) {
      return Set.of();
    }
    final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);
    final var jobTypes = new TreeSet<String>();
    known
        .models()
        .forEach(heldModel -> jobTypes
            .addAll(Camunda8TaskWiring.userTaskListenerJobTypesOfHeldModel(heldModel.model(), scopedBpmnProcessId)));
    return jobTypes;

  }

  /**
   * Registers the multi-instance chains of the models the cluster holds under a
   * declared BPMN process id. So a job of those workflows gets its iteration context
   * (index, total and current element), like a job of a deployed process. The chains are
   * read from the models the CLUSTER runs. Those carry the input mappings which VanillaBP
   * wired into them when they were deployed. If the cluster cannot be asked, the jobs are
   * still served, but a task inside an iteration misses its multi-instance values. The
   * picture reported the failed read once.
   */
  private void registerMultiInstanceChainsOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var modelsTheClusterHolds = clientFactory.getModelsTheClusterHolds();
    if (modelsTheClusterHolds == null) {
      return;
    }
    final var answer = modelsTheClusterHolds.heldFor(workflowModuleId, bpmnProcessId);
    if (!(answer instanceof Camunda8ModelsTheClusterHolds.Answer.Known known)) {
      return;
    }
    final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);
    known
        .models()
        .forEach(heldModel -> Camunda8MultiInstance
            .wire(heldModel.model(), scopedBpmnProcessId, multiInstanceRegistry));

  }

  /**
   * What a delivery record of this workflow module names, asked by the PLAIN BPMN process
   * id and the PLAIN task definition. It decides which command the probe of
   * {@link Camunda8OpenTaskProbe} can ask with, and how it reads a
   * <code>NOT_FOUND</code>.
   * <p>
   * The record of a user-task delivery keeps the USER-TASK key. A job command answers
   * <code>NOT_FOUND</code> for such a key as long as the task is open. If that were read as
   * gone, it would cancel a task which the cluster is holding out to somebody. The task
   * definition of a user task is the external form reference which its listener job type
   * carries. The records of this adapter keep exactly that. So the models of this module
   * say per record which kind of task is being asked about.
   * <p>
   * Three answers are {@link Camunda8OpenTaskProbe.KindOfTask#CANNOT_TELL}, each because
   * nothing here narrows it further. A BPMN process which this application declares without
   * deploying a model has no model to read. So a record of it may name either kind. A record
   * which kept no task definition names nothing to look up. So it counts as the unclear case
   * if its process holds a user task at all. And the probe would leave a user task standing
   * in <code>UPDATING</code> if it carries an <code>updating</code> listener which no method
   * of this application serves. So it is not asked about either. A wrong "gone" costs more
   * than a check which says nothing.
   *
   * @param workflowModuleId The workflow module
   * @param bpmsProcessingContext What the pipeline collected while wiring it
   * @return What a record of that process and task definition names
   */
  BiFunction<String, String, Camunda8OpenTaskProbe.KindOfTask> theKindOfTaskARecordNames(
      final String workflowModuleId,
      final Camunda8ProcessingContext bpmsProcessingContext) {

    final var unserved = bpmsProcessingContext.getElementsWithAnUpdatingListenerNobodyServes();
    final var userTaskDefinitions = new HashMap<String, Set<String>>();
    final var userTasksNobodyMayProbe = new HashMap<String, Set<String>>();
    bpmsProcessingContext
        .getUserTasksToWire()
        .forEach(userTask -> {
          final var plainBpmnProcessId = plainProcessId(workflowModuleId, userTask.bpmnProcessId());
          final var plainTaskDefinition = plainTaskDefinition(
              workflowModuleId, plainBpmnProcessId, userTask.externalFormReference());
          userTaskDefinitions
              .computeIfAbsent(plainBpmnProcessId, process -> new HashSet<>())
              .add(plainTaskDefinition);
          if (unserved
              .getOrDefault(plainBpmnProcessId, Set.of())
              .contains(userTask.activityId())) {
            userTasksNobodyMayProbe
                .computeIfAbsent(plainBpmnProcessId, process -> new HashSet<>())
                .add(plainTaskDefinition);
          }
        });
    final var processesWithoutAModel = Set
        .copyOf(workflowTaskWiring.taskWiringOfProcessesNobodyDeployed(workflowModuleId).keySet());
    return (
        bpmnProcessId,
        taskDefinition) -> {
      if (processesWithoutAModel.contains(bpmnProcessId)) {
        return Camunda8OpenTaskProbe.KindOfTask.CANNOT_TELL;
      }
      final var userTasksOfTheProcess = userTaskDefinitions.getOrDefault(bpmnProcessId, Set.of());
      if (taskDefinition == null) {
        return userTasksOfTheProcess.isEmpty()
            ? Camunda8OpenTaskProbe.KindOfTask.A_JOB
            : Camunda8OpenTaskProbe.KindOfTask.CANNOT_TELL;
      }
      if (!userTasksOfTheProcess.contains(taskDefinition)) {
        return Camunda8OpenTaskProbe.KindOfTask.A_JOB;
      }
      return userTasksNobodyMayProbe
          .getOrDefault(bpmnProcessId, Set.of())
          .contains(taskDefinition)
              ? Camunda8OpenTaskProbe.KindOfTask.CANNOT_TELL
              : Camunda8OpenTaskProbe.KindOfTask.A_CAMUNDA_MANAGED_USER_TASK;
    };

  }

  /**
   * The multi-instance chains this adapter registered. The processing context hands them to
   * an extension, see
   * {@link Camunda8ProcessingContext#getMultiInstanceRegistry()}.
   */
  Camunda8MultiInstance.Registry multiInstanceRegistry() {

    return multiInstanceRegistry;

  }

  /**
   * One polling worker for the tasks of a declared BPMN process id. It asks for every
   * variable, not for a derived list. Deriving one needs the elements of the model, and this
   * application does not have the model of that id.
   */
  private void openTaskWorker(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String jobType,
      final Camunda8ProcessingContext bpmsProcessingContext,
      final CamundaClient client,
      final Camunda8Drain drain,
      final Camunda8OpenTaskProbe openTaskProbe) {

    final var plainTaskDefinition = plainTaskDefinition(workflowModuleId, bpmnProcessId, jobType);
    var workerBuilder = applyFetchVariables(applyWorkerOptions(client
        .newWorker()
        .jobType(jobType)
        .handler(Camunda8JobHandler
            .builder()
            .adapterId(adapterId)
            .workflowModuleId(workflowModuleId)
            .camundaClient(client)
            .workflowTaskInvoker(workflowTaskInvoker)
            .asyncTaskLockRenewal(asyncTaskLockRenewal)
            .scoping(scoping)
            .multiInstanceRegistry(multiInstanceRegistry)
            .asyncTaskMaxAgeAction(asyncTaskMaxAgeAction())
            .drain(drain)
            .retryBackoffResolver(retryBackoffResolver)
            .fetchVariables(Camunda8FetchVariables.Selection.everything())
            .predatesDeployedVersion(processVersions::predatesDeployedVersion)
            .openTaskProbe(openTaskProbe)
            .build())
        .timeout(jobTimeoutResolver.jobTimeoutFor(workflowModuleId, bpmnProcessId, plainTaskDefinition))
        .name("vanillabp-%s-%s".formatted(adapterId, jobType)), jobType),
        workflowModuleId,
        "task",
        jobType,
        Camunda8FetchVariables.Selection.everything());
    workerBuilder = leaseUnlessATaskStaysOpen(
        workerBuilder,
        workflowModuleId,
        List.of(new ServedElement(bpmnProcessId, null, plainTaskDefinition)));
    final var tenantId = tenantIdOf(workflowModuleId);
    if (tenantId != null) {
      workerBuilder = workerBuilder.tenantId(tenantId);
    }
    bpmsProcessingContext.getOpenWorkers().add(openWorker(workerBuilder));

  }

  /**
   * One worker for the user-task lifecycle listeners of a declared BPMN process id. It is
   * opened next to the task worker of the same task definition, because nothing outside the
   * model says which of the two kinds that definition belonged to. The worker of the wrong
   * kind stays idle, which costs one activation request.
   */
  private void openUserTaskListenerWorker(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String listenerJobType,
      final Camunda8ProcessingContext bpmsProcessingContext,
      final CamundaClient client,
      final Camunda8Drain drain,
      final Camunda8OpenTaskProbe openTaskProbe) {

    var workerBuilder = applyFetchVariables(applyWorkerOptions(client
        .newWorker()
        .jobType(listenerJobType)
        .handler(Camunda8UserTaskListenerHandler
            .builder()
            .adapterId(adapterId)
            .workflowModuleId(workflowModuleId)
            .workflowTaskInvoker(workflowTaskInvoker)
            .scoping(scoping)
            .multiInstanceRegistry(multiInstanceRegistry)
            .drain(drain)
            .fetchVariables(Camunda8FetchVariables.Selection.everything())
            .openTaskProbe(openTaskProbe)
            .build())
        .timeout(
            listenerLockOf(
                workflowModuleId,
                List.of(scopedProcessId(workflowModuleId, bpmnProcessId)),
                "user-task listener",
                listenerJobType))
        .name("vanillabp-%s-%s".formatted(adapterId, listenerJobType)), listenerJobType),
        workflowModuleId,
        "user-task listener",
        listenerJobType,
        Camunda8FetchVariables.Selection.everything());
    workerBuilder = leaseTheActivations(workerBuilder);
    final var tenantId = tenantIdOf(workflowModuleId);
    if (tenantId != null) {
      workerBuilder = workerBuilder.tenantId(tenantId);
    }
    bpmsProcessingContext.getOpenWorkers().add(openWorker(workerBuilder));

  }

  /**
   * The worker which reports the end of a workflow running under a declared BPMN process
   * id. It is opened only if the application has a <code>&#64;WorkflowEnded</code> method
   * for that id. The job type is built from the process id alone, so this one is exact
   * without any model.
   */
  private void openWorkflowEndWorkerOfADeclaredId(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Camunda8ProcessingContext bpmsProcessingContext,
      final CamundaClient client,
      final Camunda8Drain drain,
      final Set<String> jobTypesAlreadyServed,
      final Set<String> openedJobTypes) {

    if ((workflowEndedInvoker == null) || !workflowEndedInvoker
        .workflowEndedHandlerExists(workflowModuleId, bpmnProcessId)) {
      return;
    }
    final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);
    final var jobType = Camunda8TaskWiring.workflowEndedJobTypeOf(scopedBpmnProcessId);
    if (jobTypesAlreadyServed.contains(jobType)) {
      return;
    }
    final var aggregateIdName = aggregateIdNameOf(workflowModuleId, bpmnProcessId);
    if (aggregateIdName == null) {
      return;
    }
    var workerBuilder = applyFetchVariables(applyWorkerOptions(client
        .newWorker()
        .jobType(jobType)
        .handler(new Camunda8WorkflowEndedHandler(
            adapterId, workflowModuleId, bpmnProcessId, aggregateIdName, workflowEndedInvoker, drain, retryBackoffResolver))
        .timeout(listenerLockOf(workflowModuleId, List.of(scopedBpmnProcessId), "workflow-end", jobType))
        .name("vanillabp-%s-%s".formatted(adapterId, scopedBpmnProcessId)), jobType),
        workflowModuleId,
        "workflow-end",
        jobType,
        Camunda8FetchVariables.Selection.everything());
    workerBuilder = leaseTheActivations(workerBuilder);
    final var tenantId = tenantIdOf(workflowModuleId);
    if (tenantId != null) {
      workerBuilder = workerBuilder.tenantId(tenantId);
    }
    bpmsProcessingContext.getOpenWorkers().add(openWorker(workerBuilder));
    openedJobTypes.add(jobType);

  }

  /**
   * Says what the workflows of a declared BPMN process id are served with, once per start
   * and per id. That is the job types which were opened for it, or that nothing had to be
   * opened because the deployed processes already reach them.
   * <p>
   * One case is worth a warning: a declared id whose methods name no task definition at
   * all. A <code>&#64;WorkflowTask</code> method wired to a BPMN element id
   * (<code>&#64;WorkflowTask(id = ...)</code>) is matched through the model. This
   * application does not have the model of that id. So its job type cannot be built, and
   * those workflows stand still without an incident.
   */
  private void reportWhatADeclaredIdIsServedWith(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<String> taskDefinitions,
      final Set<String> openedJobTypes) {

    if (openedJobTypes.isEmpty() && !taskDefinitions.isEmpty()) {
      log.info(
          "Camunda8[{}]: the workflows of the declared BPMN process '{}' (workflow module '{}') are "
              + "served by the workers of the deployed processes - the task definitions of this module "
              + "do not carry the BPMN process id, so a job of the old id is named like any other",
          adapterId,
          bpmnProcessId,
          workflowModuleId);
      return;
    }
    if (!openedJobTypes.isEmpty()) {
      log.info(
          "Camunda8[{}]: opened {} worker(s) for the declared BPMN process '{}' of workflow module "
              + "'{}', so the workflows still running under that id keep being served: {}",
          adapterId,
          openedJobTypes.size(),
          bpmnProcessId,
          workflowModuleId,
          String.join(", ", openedJobTypes));
    }
    if (!taskDefinitions.isEmpty()) {
      return;
    }
    log.warn(
        """
            Camunda8[{}]: workflow module '{}' declares BPMN process '{}' without deploying a model \
            under it, and no @WorkflowTask method serving that id names a task definition - every one \
            of them is wired to a BPMN element id instead. A worker subscribes to a task definition, \
            and composing one needs the model of that process, which this application does not bring \
            any more. The workflows still running under that id therefore stand still at their next \
            task, without an incident, because an unfetched job is not a failed one. Either wire those \
            methods by task definition ('@WorkflowTask(taskDefinition = ...)', which is what the \
            model's 'zeebe:taskDefinition' carries), or keep deploying the old model under its old id \
            until those workflows have ended.""",
        adapterId,
        workflowModuleId,
        bpmnProcessId);

  }

  @Override
  public void stopWorkflowProcessing(
      final String workflowModuleId,
      final Camunda8ProcessingContext bpmsProcessingContext) {

    // from here on, a delivery which fails is caused by the shutdown, not by the
    // application. The handlers ask the drain before they report anything to the cluster
    final var drain = drainOf(workflowModuleId);
    drain.beginShutdown();

    // close the workers of this module, in reverse order. The Camunda8ClientFactory
    // closes the CamundaClient itself on application shutdown
    final var workers = bpmsProcessingContext.getOpenWorkers();
    for (var i = workers.size() - 1; i >= 0; --i) {
      workers.get(i).close();
    }

    // what this shutdown now has to wait for. First the handlers: closing a worker does not
    // drain it, and the client interrupts every running handler when it goes down right
    // afterwards. Then the workers themselves: an activation request which is parked at the
    // cluster when the client is closed stays parked, and it swallows the first job of the
    // next application
    final var closedWorkers = List.copyOf(workers);

    // and the connections they held are free again. A closed worker leaves the count of
    // the client factory, so a module which starts once more is not counted twice
    workers.clear();
    final var registration = shutdownRegistrations.remove(workflowModuleId);
    if (registration != null) {
      registration.close();
    }
    synchronized (whatThisShutdownClosed) {
      whatThisShutdownClosed
          .add(new Camunda8Drain.ClosedWorkers(
              drain, closedWorkers.size(), () -> closedWorkers.stream().allMatch(JobWorker::isClosed)));
    }
    log.info("Workflow processing stopped for workflow module '{}' (adapter '{}')",
        workflowModuleId, adapterId);

    // the grace belongs to the APPLICATION, and the platform stops the modules one after
    // another. So a module which is not the last one of this adapter instance waits for
    // nothing of its own. The wait of all of them together has to fit into the shutdown
    // budget of the runtime
    if (!shutdownRegistrations.isEmpty()) {
      log.debug(
          "Camunda8[{}]: workflow module '{}' is closed and the workers of {} further module(s) of this "
              + "adapter are still open, so this shutdown waits for all of them together",
          adapterId,
          workflowModuleId,
          Integer.valueOf(shutdownRegistrations.size()));
      return;
    }
    letEveryModuleOfThisAdapterBeReleased();

  }

  /**
   * What the shutdown of this adapter instance has closed and not yet waited for, one entry
   * per workflow module.
   * <p>
   * The grace period is one number for the whole application, and the platform stops the
   * workflow modules one after another. If each module spent the whole grace on its own
   * workers, the budget of the application was spent once per module. Measured with
   * {@code Camunda8WhatSeveralModulesPayForAShutdownIT}: three modules took 32743 ms, where
   * the runtime grants thirty seconds. One of them gave up while its workers still held a
   * request. The workers of a module which is still open keep renewing their request while
   * another module is drained. The closed workers of that other module wait behind them. So
   * every module closes its workers and puts them here. The last one waits for all of them
   * at once. That took 5242 ms for the same three modules.
   */
  private final List<Camunda8Drain.ClosedWorkers> whatThisShutdownClosed = new ArrayList<>();

  /**
   * Waits for every workflow module whose workers this shutdown closed, within one grace
   * period, and says per module what that wait ended with.
   * <p>
   * The module which was stopped last calls it. That is the one which leaves no registration
   * of this adapter behind. A module may be stopped once more afterwards, by the backstop of
   * the client factory or by a test. Then there is nothing left to wait for, and nothing is
   * reported twice.
   */
  private void letEveryModuleOfThisAdapterBeReleased() {

    final List<Camunda8Drain.ClosedWorkers> closed;
    synchronized (whatThisShutdownClosed) {
      if (whatThisShutdownClosed.isEmpty()) {
        return;
      }
      closed = List.copyOf(whatThisShutdownClosed);
      whatThisShutdownClosed.clear();
    }
    final var grace = shutdownGrace();
    if (closed.size() > 1) {
      log.info(
          "Camunda8[{}]: the workers of {} workflow modules are closed, and this shutdown waits for all of "
              + "them together within one '{}' of {}. The modules are stopped one after another, so a wait "
              + "per module would spend that grace once per module and reach past the shutdown budget of "
              + "the runtime",
          adapterId,
          Integer.valueOf(closed.size()),
          Camunda8AdapterConfiguration.propertyKey(adapterId, "shutdown-grace"),
          grace);
    }
    Camunda8Drain
        .awaitEveryModuleQuiet(closed, grace)
        .forEach((
            drain,
            outcome) -> drain.report(grace, outcome));

  }

}
