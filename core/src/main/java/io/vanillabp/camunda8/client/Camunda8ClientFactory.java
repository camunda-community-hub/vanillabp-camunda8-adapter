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
 * (adapter ID). There is one factory <b>per adapter ID</b>, not per adapter type. The
 * same BPMS type may be configured several times for a BPMS migration.
 * <p>
 * The client is built EAGERLY in the constructor, so at application startup, if the
 * connection configuration is complete. So a configuration defect shows up at boot and
 * not first at runtime (see {@link Camunda8StartupValidation}). Building the client does
 * not open a connection and does not contact the cluster. That happens only when the
 * first command is sent. The platform bean lifecycle calls {@link #close()} on
 * application shutdown.
 * <p>
 * A self-managed adapter without an address gets a client too. It talks to the address
 * of the local cluster of its release line. An application whose SaaS adapter is not
 * configured completely may still boot under the degraded 'warn' policy. Then no client
 * is built, and {@link #getClient()} fails as a runtime BACKSTOP. Its message names the
 * missing properties (see {@link Camunda8AdapterConfiguration#validate(String)}).
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
   * The bound configuration of this adapter instance. The process service reads it where
   * a command needs a setting of the adapter level. The four-level resolution is left out
   * for such a setting on purpose.
   */
  @Getter
  private final Camunda8AdapterConfiguration configuration;

  /**
   * The record of what this application version deployed, one per adapter id. The viewer
   * API answers from it. It lives here because the factory is the one object which BOTH
   * services already receive per adapter id, on both platforms. The deployment service
   * fills the record, and the process service reads it.
   */
  @Getter
  private final Camunda8DeployedProcesses deployedProcesses = new Camunda8DeployedProcesses();

  /**
   * Whether this adapter's cluster can be searched, asked once and remembered - here for
   * the same reason as the record above: the deployment service and the process service
   * both ask it, and both already receive the factory per adapter id.
   */
  @Getter
  private final Camunda8QueryApi queryApi;

  /**
   * Which models the cluster holds for the BPMN process ids the application declares. It
   * lives here for the same reason as the record above. The deployment service puts it
   * together, because only that service can read its cluster. The message check of the
   * process service reads it. It is <code>null</code> until the deployment service
   * provided it (in tests, and for an adapter which booted degraded). Then the checks which
   * read it stay silent wherever a model outside the current deployment could hold the
   * answer.
   */
  @Getter
  private volatile io.vanillabp.camunda8.deployment.Camunda8ModelsTheClusterHolds modelsTheClusterHolds;

  /**
   * Hands over the picture of what the cluster holds. The deployment service calls this
   * while it is created.
   *
   * @param modelsTheClusterHolds The picture
   */
  public void provideModelsTheClusterHolds(
      final io.vanillabp.camunda8.deployment.Camunda8ModelsTheClusterHolds modelsTheClusterHolds) {

    this.modelsTheClusterHolds = modelsTheClusterHolds;

  }

  private CamundaClient client;

  /**
   * Builds the factory of one configured adapter id. The registry does this once per id.
   * Everything the id cannot work without is resolved here. So a bad value fails the boot
   * and not the first workflow.
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
    // how this adapter runs what it delivers. It is resolved before anything is built. So
    // a number which cannot be used fails the boot with a guiding message, and nothing
    // built later inherits it
    this.executionModel = configuration.executionModel(adapterId);
    // and how it proves who it is. This is checked here for the same reason. Credentials
    // which cannot be built fail the boot, and not as a surprise on the first command
    configuration.validateAuthentication(adapterId);
    // eager, so configuration defects show up at startup and not first at runtime. A
    // degraded adapter builds no client and fails on first use instead (backstop)
    if (configuration.missingConnectionProperties().isEmpty()) {
      this.authentication = Camunda8Authentication.of(adapterId, configuration, System::getenv);
      this.client = build();
    } else {
      // there is no cluster to authenticate against yet. A SaaS adapter without its client
      // id cannot even build a provider. The message the boot has to give is about the
      // missing connection keys
      this.authentication = null;
    }

  }

  /**
   * Validates that the adapter instance is configured. It does not build the client and
   * does not contact the cluster. Phase one of starting a workflow uses it. That phase runs
   * inside the caller's database transaction and must not make any remote call.
   *
   * @throws IllegalStateException If a required connection property is missing
   */
  public void validateConfigured() {

    configuration.validate(adapterId);

  }

  /**
   * Whether the cluster was already waited for. The wait belongs to the adapter instance
   * and not to a workflow module. So the second module of an application finds the
   * question answered.
   */
  private final AtomicBoolean clusterWaitedFor = new AtomicBoolean();

  /**
   * Waits ONCE for the cluster of this adapter instance to answer. This happens before the
   * start makes its first round which decides anything (see {@link Camunda8ClusterWait}).
   * <p>
   * An adapter which booted degraded has no client, so there is no cluster to wait for.
   * There the guiding failure of {@link #getClient()} stays the answer.
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
   * Whether {@link #close()} was called. A dispatch which races the shutdown must not use
   * a client which is about to be closed.
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
      // backstop for adapters which booted degraded. It throws with a guiding message
      // which names the missing properties
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
          addressOrLocalCluster(configuration.getRestAddress(), configuration.restAddressInUse()),
          addressOrLocalCluster(configuration.getGrpcAddress(), configuration.grpcAddressInUse()),
          configuration.isPreferRestOverGrpc(), authentication.describe());
      // both addresses are always set. On every line the client's own defaults point at
      // 0.0.0.0 and at port 8080, and that is not where the local cluster of every line
      // answers. An environment variable of the client still overrules them
      builder = CamundaClient
          .newClientBuilder()
          .preferRestOverGrpc(configuration.isPreferRestOverGrpc())
          .restAddress(URI.create(configuration.restAddressInUse()))
          .grpcAddress(URI.create(configuration.grpcAddressInUse()));
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
   * What the startup line says about an address. It is the configured one, or the address
   * of the local cluster, marked as such. So the line never shows <code>null</code> where
   * the client talks to a real address.
   */
  private static String addressOrLocalCluster(
      final String configured,
      final String inUse) {

    return hasText(configured)
        ? configured
        : "%s (local cluster)".formatted(inUse);

  }

  /**
   * The resolved execution model of this adapter instance. The startup line names it. It
   * decides whether the {@link #executor} is a
   * {@link Camunda8VirtualThreadExecutor} or a {@link Camunda8PlatformThreadExecutor}.
   */
  @Getter
  private final Camunda8ExecutionModel executionModel;

  /**
   * How this adapter instance authenticates. It holds the resolved method, the provider
   * handed to the client, and the message given once when the cluster refuses a request.
   * It is <code>null</code> while the connection configuration is incomplete. Then no
   * client was built either.
   */
  @Getter
  private final Camunda8Authentication authentication;

  /**
   * The executor the client runs its workers on, in both execution models. The adapter
   * supplies it, so the client does not build one. That keeps the scheduling of the polls
   * and the running of the handlers apart on every release line.
   */
  @Getter
  private Camunda8Executor executor;

  private void applyExecutionModel(
      final CamundaClientBuilder builder) {

    executor = executionModel.virtual()
        ? new Camunda8VirtualThreadExecutor(adapterId, executionModel.slots())
        : new Camunda8PlatformThreadExecutor(adapterId, executionModel.slots());
    // which builder methods take it differs per release line, see Camunda8JobExecutors.
    // 'numJobWorkerExecutionThreads' is not set on purpose. It sizes the pool the client
    // would build for itself, and the client builds none once it is handed one
    Camunda8JobExecutors.install(builder, executor);
    startWatchingTheSlots();

  }

  /**
   * Watches whether the execution slots of this adapter id are all held by handlers which
   * are not coming back. It is <code>null</code> for an adapter which booted without a
   * connection. There is no executor to watch then either.
   */
  @Getter
  private Camunda8SlotWatch slotWatch;

  private void startWatchingTheSlots() {

    // a factory which builds its client a second time builds a second executor. The watch
    // of the first one would keep a thread and read an executor which nothing uses
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
   * drains hold it per module. All modules share the slots, so the watch of the slots
   * reads them together.
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

    // set on the CLIENT and not on every worker. The worker builder inherits the client's
    // defaults. Then an environment variable can still overrule what was configured. That
    // is the way out which reportEnvironmentOverrides makes visible
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
   * Hands the client the credentials provider of this adapter instance. If the method is
   * <code>none</code> and the environment carries credentials, it hands the client none.
   * Then the client keeps building the provider it always built from those variables (see
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
   * The four numbers which together make the sizing of an adapter instance, in one line.
   * None of them was shown anywhere before. They say how the handlers run, how many may
   * run at once, how many jobs one worker holds while they run, and how long a delivered
   * job stays locked meanwhile.
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
   * The other <code>camunda8</code> adapter ids which address the SAME cluster.
   * {@link Camunda8ClientFactoryRegistry} sets them at startup. The list is empty for the
   * usual application with one Camunda 8 adapter.
   * <p>
   * What it decides: keys are unique per cluster, not per tenant or prefix. So if this list
   * is not empty, an awareness probe has to find out which scope a key belongs to before it
   * claims the task. That answer costs a search. So it is only paid where two ids can
   * really be confused.
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
   * Whether this adapter id shares its cluster. Only then is a name clash between two ids
   * possible at all.
   *
   * @return Whether another <code>camunda8</code> adapter id addresses the same cluster
   */
  public boolean sharesItsCluster() {

    return !adapterIdsSharingTheCluster.isEmpty();

  }

  /**
   * How the factory stops the workers of one workflow module on a path which did not
   * reach {@code stopWorkflowProcessing}. The deployment service implements it, because it
   * owns the workers and the drain. So does every EXTENSION which opened workers of its own
   * for that module.
   */
  @FunctionalInterface
  public interface WorkflowModuleShutdown {

    /**
     * Closes the workers of that workflow module and waits for it, as the ordinary
     * shutdown does. It has to be idempotent, because the module may stop itself a moment
     * later.
     */
    void stopWorkflowProcessing();

  }

  /**
   * What removes a registered hook. Closing it removes THAT hook and no other. This lets
   * several hooks share one workflow module. When the adapter stops its own workers, it
   * does not deregister an extension whose workers are still open.
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
   * The workers of a module are closed before its client. On the ordinary path, the
   * lifecycle of the platform gives that order. This map makes the promise hold on EVERY
   * path. Whatever is left in it when the client goes down is stopped here first, and the
   * operator is told that a hook was missing. The order matters more than it seems. An
   * activation request which waits at the cluster when its client is closed keeps waiting
   * there. A job created within the request timeout after that waits for its lock, and
   * does not reach the next worker.
   * <p>
   * A module holds MORE THAN ONE hook, because an extension opens workers of the same
   * module on the same client. With a single slot, the second registration overwrote the
   * first. The hooks of a module run in REVERSE registration order, so the workers opened
   * last go down first. The adapter registers while workflow processing starts, and every
   * extension registers after it. So the workers of an extension are closed before the
   * adapter's, and the client is closed under none of them.
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
   * They live here for the same reason as the deployed processes and the drains. The
   * factory is the one object per adapter id which the deployment service, the process
   * service and an EXTENSION all already hold. Each of these workers holds a connection of
   * the same pool while it waits for work. So the number which counts is the number over
   * all of them, not the number one workflow module opened (see
   * {@link Camunda8WorkerConnections}).
   * <p>
   * A worker which is CLOSED gave its connection back, so it leaves the count again. It is
   * dropped while the workers are counted, not by a hook of its own. A worker is closed on
   * more paths than it is opened on: the module which stops, the backstop below, and an
   * extension which closes its own.
   */
  private final List<JobWorker> openWorkers = new LinkedList<>();

  /**
   * Adds a worker which just opened on this client to that count. Then it runs the check
   * which reads the count.
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
   * What is asked after a worker opened on this client. The deployment service of this
   * adapter id provides it, because it holds the check and the place its finding goes to.
   * Until then, and in a test which builds a factory of its own, a worker which opens is
   * counted and nothing else happens.
   */
  private volatile Runnable openWorkerCheck = () -> {
  };

  /**
   * Hands over what runs after a worker opened. The deployment service calls this while it
   * is created.
   * <p>
   * This way a worker which an EXTENSION opens long after the start is checked against the
   * connection pool as well. It opens through {@link Camunda8Workers}, which brings it
   * here. And here is where the check of the adapter hangs.
   *
   * @param openWorkerCheck What to run once a worker opened
   */
  public void provideTheOpenWorkerCheck(
      final Runnable openWorkerCheck) {

    this.openWorkerCheck = openWorkerCheck;

  }

  /**
   * What each workflow module of this adapter id has in flight, and whether it is going
   * down. There is one drain per module, created on first use.
   * <p>
   * It lives here for the same reason as the deployed processes and the query-api answer.
   * The factory is the one object per adapter id which the deployment service, the process
   * service and an EXTENSION all already hold. A listener job of an extension has to take
   * part in the same drain as the adapter's own jobs. Otherwise a shutdown closes the
   * client while its handler runs, and the job it was serving ends in an incident.
   */
  private final Map<String, Camunda8Drain> drains = new ConcurrentHashMap<>();

  /**
   * How long a job of this adapter id stays locked for its worker, resolved over the four
   * levels of the adapter's configuration (task &gt; workflow &gt; workflow module &gt;
   * adapter). The platform module which binds that configuration provides it. It is the
   * only place which can read it.
   * <p>
   * It lives here for the same reason as the deployed processes and the query-api answer.
   * The factory is the one object per adapter id which the deployment service, the process
   * service and an EXTENSION all already hold. An extension which opens a worker of its
   * own on this cluster asks it what lock to use. So a workflow module which raised the
   * adapter's job timeout raised it for that worker too. A second reader of the same keys
   * is how the two would start to disagree.
   * <p>
   * Until the platform provided one, it answers
   * {@link Camunda8JobTimeoutResolver#DEFAULT_JOB_TIMEOUT} for everything. That is the case
   * in a unit test and for an adapter which booted degraded.
   */
  private volatile Camunda8JobTimeoutResolver jobTimeoutResolver = (
      workflowModuleId,
      bpmnProcessId,
      taskDefinition) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT;

  /**
   * Hands over the resolver of this adapter id. The platform module calls this while the
   * beans of the adapter are created.
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
   * A module may be started again after it was stopped. A test does that, and so does a
   * platform which restarts its lifecycle beans. The drain of the previous run stays shut
   * down forever. Its handlers would then leave every job to its lock. So the new run gets
   * a drain of its own. The adapter calls this while workflow processing starts, before any
   * worker of that run is opened.
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
   * The backstop. A workflow module which never reached {@code stopWorkflowProcessing} is
   * stopped here, before the client goes down, and the missing hook is named. The client is
   * still open at this point. So the handlers of the module can finish, and its workers
   * can be released the ordinary way.
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
      // reverse registration order: what was opened last goes down first. So the workers
      // of an extension are closed before the adapter's
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
