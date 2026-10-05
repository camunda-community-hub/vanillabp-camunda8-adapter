package io.vanillabp.camunda8.client;

import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.camunda.client.CamundaClient;
import io.camunda.client.CamundaClientBuilder;
import io.camunda.client.api.worker.JobWorker;
import io.vanillabp.camunda8.deployment.Camunda8DeployedProcesses;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds and owns the single {@link CamundaClient} of one Camunda 8 adapter instance
 * (adapter ID). One factory exists <b>per adapter ID</b> (not per adapter type)
 * because the same BPMS type may be configured multiple times for a BPMS migration.
 * <p>
 * The client is built EAGERLY at construction time (i.e. at application startup) if
 * the connection configuration is complete - configuration defects surface at boot,
 * not first at runtime (see {@link Camunda8StartupValidation}). Building the client
 * neither opens a connection nor contacts the cluster - that happens only when the
 * first command is sent. The factory is closed on {@link #close()} (called on
 * application shutdown by the platform bean lifecycle).
 * <p>
 * A self-managed adapter without an address gets a client too: the client then uses its
 * own default address, which is a cluster on this machine. An application whose SaaS
 * adapter is configured incompletely may still boot under the degraded 'warn' policy: no
 * client is built then, and
 * {@link #getClient()} fails as a runtime BACKSTOP with a message naming the missing
 * properties (see {@link Camunda8AdapterConfiguration#validate(String)}).
 * <p>
 * Why the factory closes workers which never reached the module lifecycle before it closes the
 * client is decision 6 in the repository's DECISIONS.md.
 */
@Slf4j
// see decision 4 in the repository's DECISIONS.md
@SuppressWarnings("LombokGetterMayBeUsed")
public class Camunda8ClientFactory implements AutoCloseable {

  @Getter
  private final String adapterId;

  /**
   * The bound configuration of this adapter instance - read by the process service where
   * a command needs an adapter-level setting the four-level resolution deliberately does
   * not apply to.
   */
  @Getter
  private final Camunda8AdapterConfiguration configuration;

  /**
   * The per-adapter-id record of what this application version deployed, which the
   * viewer API serves from. It lives here because the factory is the one object BOTH
   * the deployment service (which fills it) and the process service (which reads
   * it) already receive per adapter id on both platforms.
   */
  @Getter
  private final Camunda8DeployedProcesses deployedProcesses = new Camunda8DeployedProcesses();

  /**
   * Whether this adapter's cluster can be searched, asked once and remembered - here for
   * the same reason as the record above: deployment service, process service, version
   * catalog and viewer all ask it, and all of them already receive the factory per
   * adapter id.
   */
  @Getter
  private final Camunda8QueryApi queryApi;

  /**
   * Which models the cluster holds for the BPMN process ids the application declares -
   * here for the same reason as the record above: the deployment service assembles it,
   * because only it can read its cluster, and the process service's message check
   * reads it. <code>null</code> until the deployment service provided it (tests, and
   * an adapter which booted degraded): the checks reading it then fall back to
   * staying silent wherever a model outside the current deployment could carry the
   * answer.
   */
  @Getter
  private volatile io.vanillabp.camunda8.deployment.Camunda8ModelsTheClusterHolds modelsTheClusterHolds;

  /**
   * Hands over the picture of what the cluster holds, called by the deployment
   * service while it is created.
   *
   * @param modelsTheClusterHolds The picture
   */
  public void provideModelsTheClusterHolds(
      final io.vanillabp.camunda8.deployment.Camunda8ModelsTheClusterHolds modelsTheClusterHolds) {

    this.modelsTheClusterHolds = modelsTheClusterHolds;

  }

  private CamundaClient client;

  /**
   * Builds the factory of one configured adapter id. The registry does this once per id, and
   * everything the id cannot live without is resolved here so a bad value fails the boot
   * rather than the first workflow.
   *
   * @param adapterId The configured adapter id
   * @param configuration The section the application wrote for that id
   */
  public Camunda8ClientFactory(
      final String adapterId,
      final Camunda8AdapterConfiguration configuration) {

    this.adapterId = adapterId;
    this.configuration = configuration;
    this.queryApi = new Camunda8QueryApi(adapterId, this::getClient);
    // how this adapter runs what it delivers - resolved before anything is built, so an
    // unusable number fails the boot with a guiding message instead of being inherited
    this.executionModel = configuration.executionModel(adapterId);
    // and how it proves who it is - checked here for the same reason: credentials which
    // cannot be built are a boot failure, not a surprise on the first command
    configuration.validateAuthentication(adapterId);
    // eager: configuration defects surface at startup, not first at runtime; a degraded
    // adapter builds no client and fails on first use instead (backstop)
    if (configuration.missingConnectionProperties().isEmpty()) {
      this.authentication = Camunda8Authentication.of(adapterId, configuration, System::getenv);
      this.client = build();
    } else {
      // there is no cluster to authenticate against yet, and a SaaS adapter without its
      // client id cannot even build a provider - the missing connection keys are the
      // message that boot has to give
      this.authentication = null;
    }

  }

  /**
   * Validates that the adapter instance is configured without building the client or
   * contacting the cluster. Used by phase one of starting a workflow (which runs inside
   * the caller's database transaction and must not do any remote call).
   *
   * @throws IllegalStateException If a required connection property is missing
   */
  public void validateConfigured() {

    configuration.validate(adapterId);

  }

  /**
   * Whether the cluster was already waited for - the wait belongs to the adapter instance
   * and not to a workflow module, so the second module of an application finds the question
   * answered.
   */
  private final AtomicBoolean clusterWaitedFor = new AtomicBoolean();

  /**
   * Waits ONCE for this adapter instance's cluster to answer, before the first round of the
   * start which decides anything is made (see {@link Camunda8ClusterWait}).
   * <p>
   * An adapter which booted degraded has no client and therefore no cluster
   * to wait for; the guiding failure of {@link #getClient()} stays the answer there.
   *
   * @throws IllegalStateException If the cluster did not answer within
   *           <code>startup-wait</code>, or answered something a repetition cannot change
   */
  public void waitUntilTheClusterAnswers() {

    if ((client == null) || !clusterWaitedFor.compareAndSet(false, true)) {
      return;
    }
    Camunda8ClusterWait.untilTheClusterAnswers(adapterId, configuration, client);

  }

  /**
   * Whether {@link #close()} was called: a dispatch racing the shutdown must not
   * use a client which is about to be closed.
   */
  private volatile boolean closed = false;

  /**
   * The client of this adapter instance, which everything reaching the cluster goes through.
   *
   * @return The eagerly built {@link CamundaClient} of this adapter instance
   * @throws IllegalStateException If the adapter's connection configuration is
   *         incomplete (runtime backstop naming the missing properties) or the
   *         factory was already closed (application shutdown)
   */
  public CamundaClient getClient() {

    if (closed) {
      throw new IllegalStateException(
          "The Camunda 8 client factory of adapter '%s' was already closed (application shutdown)!"
              .formatted(adapterId));
    }
    if (client == null) {
      // backstop for adapters which booted degraded - throws with a
      // guiding message naming the missing properties
      configuration.validate(adapterId);
    }
    return client;

  }

  private CamundaClient build() {

    final CamundaClientBuilder builder;
    if (configuration.getMode() == Camunda8AdapterConfiguration.Mode.SAAS) {
      log.info("Building Camunda 8 SaaS client for adapter '{}' (cluster '{}', region '{}', authentication {})",
          adapterId, configuration.getClusterId(), configuration.getRegion(), authentication.describe());
      builder = CamundaClient
          .newCloudClientBuilder()
          .withClusterId(configuration.getClusterId())
          .withClientId(configuration.getClientId())
          .withClientSecret(configuration.getClientSecret())
          .withRegion(configuration.getRegion());
      if (hasText(configuration.getTenantId())) {
        builder.defaultTenantId(configuration.getTenantId());
      }
    } else {
      log.info("Building Camunda 8 self-managed client for adapter '{}' (rest-address '{}', grpc-address '{}', "
          + "prefer-rest-over-grpc {}, authentication {})",
          adapterId,
          addressOrClientDefault(configuration.getRestAddress(),
              Camunda8AdapterConfiguration.CLIENT_DEFAULT_REST_ADDRESS),
          addressOrClientDefault(configuration.getGrpcAddress(),
              Camunda8AdapterConfiguration.CLIENT_DEFAULT_GRPC_ADDRESS),
          configuration.isPreferRestOverGrpc(), authentication.describe());
      builder = CamundaClient
          .newClientBuilder()
          .preferRestOverGrpc(configuration.isPreferRestOverGrpc());
      if (hasText(configuration.getRestAddress())) {
        builder.restAddress(URI.create(configuration.getRestAddress()));
      }
      if (hasText(configuration.getGrpcAddress())) {
        builder.grpcAddress(URI.create(configuration.getGrpcAddress()));
      }
      if (hasText(configuration.getTenantId())) {
        builder.defaultTenantId(configuration.getTenantId());
      }
    }

    applyExecutionModel(builder);
    applyWorkerDefaults(builder);
    applyTransportOptions(builder);
    applyAuthentication(builder);

    final var built = builder.build();
    reportSizing();
    reportEnvironmentOverrides(built);
    return built;

  }

  /**
   * What the startup line says about an address: the configured one, or the client's default
   * marked as such, so the line never shows <code>null</code> where the client talks to a
   * real address.
   */
  private static String addressOrClientDefault(
      final String configured,
      final String clientDefault) {

    return hasText(configured)
        ? configured
        : "%s (client default)".formatted(clientDefault);

  }

  /**
   * The resolved execution model of this adapter instance - what the startup line names
   * and what decides whether the {@link #executor} is a
   * {@link Camunda8VirtualThreadExecutor} or a {@link Camunda8PlatformThreadExecutor}.
   */
  @Getter
  private final Camunda8ExecutionModel executionModel;

  /**
   * How this adapter instance authenticates: the resolved method, the provider handed to
   * the client, and the message said once when the cluster refuses a request.
   * <code>null</code> while the connection configuration is incomplete, where no client
   * was built either.
   */
  @Getter
  private final Camunda8Authentication authentication;

  /**
   * The executor the client runs its workers on, in both execution models: the adapter
   * supplies it rather than letting the client build one, so the scheduling of the polls
   * and the running of the handlers are separate on every release line.
   */
  @Getter
  private Camunda8Executor executor;

  private void applyExecutionModel(
      final CamundaClientBuilder builder) {

    executor = executionModel.virtual()
        ? new Camunda8VirtualThreadExecutor(adapterId, executionModel.slots())
        : new Camunda8PlatformThreadExecutor(adapterId, executionModel.slots());
    // which builder methods take it differs per release line, see Camunda8JobExecutors.
    // 'numJobWorkerExecutionThreads' is deliberately not set: it sizes the pool the
    // client would build for itself, and there is none once it is handed one
    Camunda8JobExecutors.install(builder, executor);
    startWatchingTheSlots();

  }

  /**
   * Watches whether the execution slots of this adapter id are all held by handlers which
   * are not coming back. <code>null</code> for an adapter which booted without a
   * connection, where there is no executor to watch either.
   */
  @Getter
  private Camunda8SlotWatch slotWatch;

  private void startWatchingTheSlots() {

    // a factory which builds its client a second time builds a second executor, and the
    // watch of the first one would keep a thread and read an executor nothing uses
    if (slotWatch != null) {
      slotWatch.close();
    }
    slotWatch = new Camunda8SlotWatch(
        adapterId, Camunda8AdapterConfiguration
            .propertyKey(adapterId, executionModel.virtual()
                ? "worker-threads-bound"
                : "worker-threads"), executionModel::slots, () -> executor.getBound() - executor
                    .getFreeSlots(), this::getRunningExecutions, job -> jobTimeoutResolver
                        .jobTimeoutFor(job.workflowModuleId(), job.bpmnProcessId(), job.name()));
    slotWatch.start();

  }

  /**
   * What every workflow module of this adapter id has inside a handler right now. The
   * drains hold it per module, and the slots are shared by all of them, so the watch of
   * the slots reads them together.
   *
   * @return The deliveries whose handler is running, across all workflow modules
   */
  public Collection<Camunda8Drain.InFlightJob> getRunningExecutions() {

    return drains
        .values()
        .stream()
        .map(Camunda8Drain::getInFlight)
        .flatMap(Collection::stream)
        .toList();

  }

  private void applyWorkerDefaults(
      final CamundaClientBuilder builder) {

    // set on the CLIENT rather than on every worker: the worker builder inherits the
    // client's defaults, and an environment variable can then still overrule what was
    // configured - which is the escape hatch reportEnvironmentOverrides makes visible
    builder.defaultJobWorkerMaxJobsActive(configuration.resolvedMaxJobsActive(adapterId));
    if (configuration.getPollInterval() != null) {
      builder.defaultJobPollInterval(configuration.getPollInterval());
    }
    if (configuration.getRequestTimeout() != null) {
      builder.defaultRequestTimeout(configuration.getRequestTimeout());
    }
    if (configuration.getStreamEnabled() != null) {
      builder.defaultJobWorkerStreamEnabled(configuration.getStreamEnabled());
    }
    if (configuration.getMessageTimeToLive() != null) {
      builder.defaultMessageTimeToLive(configuration.getMessageTimeToLive());
    }

  }

  private void applyTransportOptions(
      final CamundaClientBuilder builder) {

    if (configuration.getMaxMessageSize() != null) {
      builder.maxMessageSize(configuration.getMaxMessageSize());
    }
    if (configuration.getKeepAlive() != null) {
      builder.keepAlive(configuration.getKeepAlive());
    }
    if (configuration.getMaxHttpConnections() != null) {
      builder.maxHttpConnections(configuration.getMaxHttpConnections());
    }
    if (hasText(configuration.getOverrideAuthority())) {
      builder.overrideAuthority(configuration.getOverrideAuthority());
    }
    if (hasText(configuration.getAuth().getCaCertificatePath())) {
      builder.caCertificatePath(configuration.getAuth().getCaCertificatePath());
    }

  }

  /**
   * Hands the client the credentials provider of this adapter instance - or, where the
   * method is <code>none</code> and the environment carries credentials, hands it none,
   * so the client keeps building the provider it always built from those variables (see
   * {@link Camunda8Authentication}).
   */
  private void applyAuthentication(
      final CamundaClientBuilder builder) {

    final var provider = authentication.providerFor(message -> log.warn("{}", message));
    if (provider != null) {
      builder.credentialsProvider(provider);
    }

  }

  /**
   * The four numbers which together are the sizing decision of an adapter instance, in
   * one line, because none of them appeared anywhere before: how the handlers run, how
   * many may run at once, how many jobs one worker holds while they do, and how long a
   * delivered job stays locked meanwhile.
   */
  private void reportSizing() {

    log.info(
        "Camunda8[{}]: {} run every worker of this adapter id, max-jobs-active {} per worker, "
            + "job-timeout {} by default. All workflow modules of this adapter share those {} execution "
            + "slots, a handler holds one for its whole runtime, and a worker asks the cluster for work "
            + "only while one of them is free",
        adapterId,
        executionModel.describe(),
        configuration.resolvedMaxJobsActive(adapterId),
        configuration.getJobTimeout() == null
            ? Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT
            : configuration.getJobTimeout(),
        executionModel.slots());

  }

  private void reportEnvironmentOverrides(
      final CamundaClient client) {

    final var credentialSelection = Camunda8EnvironmentOverrides
        .describeCredentialSelection(adapterId, authentication, System::getenv);
    if (credentialSelection != null) {
      log.warn("{}", credentialSelection);
    }
    final var overrides = Camunda8EnvironmentOverrides.detect(
        adapterId, configuration, client.getConfiguration(), System::getenv);
    if (overrides.isEmpty()) {
      return;
    }
    log.warn("{}", Camunda8EnvironmentOverrides.describe(adapterId, overrides));

  }

  private static boolean hasText(
      final String value) {

    return value != null && !value.isBlank();

  }

  /**
   * The other <code>camunda8</code> adapter ids addressing the SAME cluster,
   * told by {@link Camunda8ClientFactoryRegistry} at startup. Empty for the ordinary
   * application with one Camunda 8 adapter.
   * <p>
   * What it decides: keys are unique per cluster and not per tenant or prefix, so where
   * this list is not empty an awareness probe has to find out which scope a key belongs
   * to before it claims the task. That answer costs a search, which is why it is only paid
   * where two ids can actually be confused.
   */
  private List<String> adapterIdsSharingTheCluster = List.of();

  /**
   * @param adapterIds The other adapter ids addressing this cluster
   */
  void sharesItsClusterWith(
      final List<String> adapterIds) {

    this.adapterIdsSharingTheCluster = List.copyOf(adapterIds);

  }

  /**
   * Which other adapter ids of this application address the same cluster.
   *
   * @return The other adapter ids addressing this cluster, empty where this id is alone
   */
  public List<String> getAdapterIdsSharingTheCluster() {

    return adapterIdsSharingTheCluster;

  }

  /**
   * Whether this adapter id shares its cluster, which is what makes a name clash between two
   * ids possible in the first place.
   *
   * @return Whether another <code>camunda8</code> adapter id addresses the same cluster
   */
  public boolean sharesItsCluster() {

    return !adapterIdsSharingTheCluster.isEmpty();

  }

  /**
   * How the factory stops the workers of one workflow module on a path which did not
   * reach {@code stopWorkflowProcessing}. Implemented by the deployment
   * service, which owns the workers and the drain, and by every EXTENSION which opened
   * workers of its own for that module.
   */
  @FunctionalInterface
  public interface WorkflowModuleShutdown {

    /**
     * Closes the workers of that workflow module and waits for it the way the ordinary
     * shutdown does. Has to be idempotent: the module may stop itself a moment later.
     */
    void stopWorkflowProcessing();

  }

  /**
   * What a registered hook is removed by. Closing it removes THAT hook and no other, which
   * is what lets several of them share one workflow module: the adapter stopping its own
   * workers does not deregister an extension whose workers are still open.
   */
  @FunctionalInterface
  public interface WorkflowModuleShutdownRegistration extends AutoCloseable {

    /**
     * Removes the hook this registration was handed out for. Idempotent.
     */
    @Override
    void close();

  }

  /**
   * The workflow modules of this adapter instance whose workers are open right now, and per
   * module the hooks which close them, in registration order.
   * <p>
   * The workers of a module are closed before its client, and on the ordinary path
   * that is the order the platform's lifecycle produces. This map is
   * what makes the promise hold on EVERY path: whatever is left in it when the client
   * goes down is stopped here first, and the operator is told that a hook was missing.
   * Order matters more than it looks: an activation request which is parked
   * at the cluster when its client is closed stays parked, and a job created within the
   * request timeout afterwards waits for its lock instead of reaching the next worker.
   * <p>
   * A module holds MORE THAN ONE hook because an extension opens workers of the same module
   * on the same client, and a single slot meant the second registration overwrote the first.
   * The hooks of a module run in REVERSE registration order, which puts the workers opened
   * last down first: the adapter registers while workflow processing starts and every
   * extension registers after it, so an extension's workers are closed before the adapter's
   * and the client is closed under none of them.
   */
  private final Map<String, List<WorkflowModuleShutdown>> openWorkflowModules = new LinkedHashMap<>();

  /**
   * Registers workers of a workflow module which are now open.
   *
   * @param workflowModuleId The workflow module
   * @param shutdown How to stop them if the client is closed before they stopped themselves
   * @return What removes this hook again when those workers stopped
   */
  public synchronized WorkflowModuleShutdownRegistration workflowModuleStarted(
      final String workflowModuleId,
      final WorkflowModuleShutdown shutdown) {

    openWorkflowModules
        .computeIfAbsent(workflowModuleId, module -> new LinkedList<>())
        .add(shutdown);
    return () -> removeShutdownHook(workflowModuleId, shutdown);

  }

  /**
   * Removes one hook, and the module with its last one.
   */
  private synchronized void removeShutdownHook(
      final String workflowModuleId,
      final WorkflowModuleShutdown shutdown) {

    final var hooks = openWorkflowModules.get(workflowModuleId);
    if (hooks == null) {
      return;
    }
    hooks.remove(shutdown);
    if (hooks.isEmpty()) {
      openWorkflowModules.remove(workflowModuleId);
    }

  }

  /**
   * Which workflow modules of this adapter id still have workers running.
   *
   * @return The workflow modules of this adapter instance whose workers are open
   */
  public synchronized Set<String> getOpenWorkflowModules() {

    return Set.copyOf(openWorkflowModules.keySet());

  }

  /**
   * The workers which are open on the client of this adapter id, whoever opened them.
   * <p>
   * They live here for the reason the deployed processes and the drains do: the factory is
   * the one object per adapter id which the deployment service, the process service and an
   * EXTENSION all already hold. Every one of these workers holds a connection of the same
   * pool while it waits for work, so the number which counts is the number over all of them
   * and not the number one workflow module opened (see {@link Camunda8WorkerConnections}).
   * <p>
   * A worker which is CLOSED gave its connection back, so it leaves the count again. It is
   * dropped while the workers are counted rather than by a hook of its own, because a
   * worker is closed on more paths than it is opened on: the module which stops, the
   * backstop below, and an extension closing its own.
   */
  private final List<JobWorker> openWorkers = new LinkedList<>();

  /**
   * Takes a worker which just opened on this client into that count, and lets the check
   * which reads it run.
   * <p>
   * Called by {@link Camunda8Workers#open(io.camunda.client.api.worker.JobWorkerBuilderStep1.JobWorkerBuilderStep3, Camunda8ClientFactory)},
   * which is how every worker of the adapter and of an extension reaches this client.
   *
   * @param worker The worker which just opened
   */
  void aWorkerWasOpened(
      final JobWorker worker) {

    synchronized (openWorkers) {
      openWorkers.add(worker);
    }
    openWorkerCheck.run();

  }

  /**
   * How many workers are open on the client of this adapter id right now, the adapter's own
   * and an extension's alike.
   *
   * @return The number of workers which have not been closed again
   */
  public int countTheOpenWorkers() {

    synchronized (openWorkers) {
      openWorkers.removeIf(JobWorker::isClosed);
      return openWorkers.size();
    }

  }

  /**
   * What is asked after a worker opened on this client. Provided by the deployment service
   * of this adapter id, which holds the check and the place its finding goes to; until it
   * does, and in a test building a factory of its own, a worker which opens is counted and
   * nothing else happens.
   */
  private volatile Runnable openWorkerCheck = () -> {
  };

  /**
   * Hands over what runs after a worker opened, called by the deployment service while it
   * is created.
   * <p>
   * This is how a worker an EXTENSION opens long after the start is held against the
   * connection pool as well: it opens through {@link Camunda8Workers}, which brings it
   * here, and here is where the check of the adapter hangs.
   *
   * @param openWorkerCheck What to run once a worker opened
   */
  public void provideTheOpenWorkerCheck(
      final Runnable openWorkerCheck) {

    this.openWorkerCheck = openWorkerCheck;

  }

  /**
   * What each workflow module of this adapter id has in flight, and whether it is going
   * down - one drain per module, created on first use.
   * <p>
   * It lives here for the reason the deployed processes and the query-api answer do: the
   * factory is the one object per adapter id which the deployment service, the process
   * service and an EXTENSION all already hold. A listener job of an extension has to take
   * part in the same drain as the adapter's own, otherwise a shutdown closes the client
   * while its handler runs and the job it was serving buys an incident.
   */
  private final Map<String, Camunda8Drain> drains = new ConcurrentHashMap<>();

  /**
   * How long a job of this adapter id stays locked for its worker, resolved over the four
   * levels of the adapter's configuration (task &gt; workflow &gt; workflow module &gt;
   * adapter). Provided by the platform module which binds that configuration, which is the
   * only place able to read it.
   * <p>
   * It lives here for the reason the deployed processes and the query-api answer do: the
   * factory is the one object per adapter id which the deployment service, the process
   * service and an EXTENSION all already hold. An extension opening a worker of its own on
   * this cluster asks it what lock to use, so a workflow module which raised the adapter's
   * job timeout raised it for that worker too. Building a second reader of the same keys is
   * how the two start disagreeing.
   * <p>
   * Before the platform provided one - a unit test, an adapter which booted degraded -
   * it answers {@link Camunda8JobTimeoutResolver#DEFAULT_JOB_TIMEOUT} for everything.
   */
  private volatile Camunda8JobTimeoutResolver jobTimeoutResolver = (
      workflowModuleId,
      bpmnProcessId,
      taskDefinition) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT;

  /**
   * Hands over the resolver of this adapter id, called by the platform module while the
   * adapter's beans are created.
   *
   * @param jobTimeoutResolver The resolver reading this adapter's configuration
   */
  public void provideJobTimeoutResolver(
      final Camunda8JobTimeoutResolver jobTimeoutResolver) {

    this.jobTimeoutResolver = jobTimeoutResolver;

  }

  /**
   * The resolver which answers how long a job of this adapter id stays locked.
   *
   * @return That resolver, never <code>null</code>
   */
  public Camunda8JobTimeoutResolver getJobTimeoutResolver() {

    return jobTimeoutResolver;

  }

  /**
   * The drain of one workflow module of this adapter id.
   *
   * @param workflowModuleId The workflow module
   * @return Its drain, created on first use
   */
  public Camunda8Drain drainOf(
      final String workflowModuleId) {

    return drains
        .computeIfAbsent(workflowModuleId, moduleId -> new Camunda8Drain(adapterId, moduleId));

  }

  /**
   * Gives a workflow module which starts processing a drain which is NOT shutting down.
   * <p>
   * A module may be started again after it was stopped - a test, and a platform which
   * restarts its lifecycle beans - and the drain of the previous run stays shut down
   * forever. Its handlers would then leave every job to its lock, so the new run gets one of
   * its own. Called by the adapter while workflow processing starts, before any worker of
   * that run is opened.
   *
   * @param workflowModuleId The workflow module starting its workers
   * @return The fresh drain the new workers register their deliveries in
   */
  public Camunda8Drain freshDrainOf(
      final String workflowModuleId) {

    final var drain = new Camunda8Drain(adapterId, workflowModuleId);
    drains.put(workflowModuleId, drain);
    return drain;

  }

  @Override
  public synchronized void close() {

    closeWorkersOfModulesWhichDidNotStop();
    closed = true;
    if (slotWatch != null) {
      slotWatch.close();
      slotWatch = null;
    }
    if (client != null) {
      log.info("Closing Camunda 8 client of adapter '{}'", adapterId);
      client.close();
      client = null;
    }

  }

  /**
   * The backstop: a workflow module which never reached
   * {@code stopWorkflowProcessing} is stopped here, before the client goes down, and the
   * missing hook is named. The client is still open at this point, so the module's
   * handlers can finish and its workers can be released the ordinary way.
   */
  private void closeWorkersOfModulesWhichDidNotStop() {

    if (openWorkflowModules.isEmpty()) {
      return;
    }
    final var pending = List.copyOf(openWorkflowModules.entrySet());
    log.warn(
        "Camunda8[{}]: the client is being closed while the workers of the workflow module(s) '{}' are "
            + "still open, which means the shutdown of this application did not stop workflow processing. "
            + "Closing them now, before the client, so no activation request of theirs stays parked at the "
            + "cluster - a job created within '{}' of a client closed under its open workers waits for '{}' "
            + "before any worker sees it. Report this: on both supported platforms the lifecycle reaches "
            + "the adapter, so a path which does not is a defect of the wiring rather than of the "
            + "application",
        adapterId,
        String.join("', '", openWorkflowModules.keySet()),
        Camunda8AdapterConfiguration.propertyKey(adapterId, "request-timeout"),
        Camunda8AdapterConfiguration.propertyKey(adapterId, "job-timeout"));
    pending.forEach(entry -> {
      // reverse registration order: what was opened last goes down first, so an
      // extension's workers are closed before the adapter's
      final var hooks = new LinkedList<>(entry.getValue());
      Collections.reverse(hooks);
      hooks.forEach(hook -> {
        try {
          hook.stopWorkflowProcessing();
        } catch (final Exception e) {
          log.warn(
              "Camunda8[{}]: stopping the workers of workflow module '{}' failed while the client was being "
                  + "closed. The client goes down now anyway",
              adapterId,
              entry.getKey(),
              e);
        }
      });
    });
    openWorkflowModules.clear();

  }

}
