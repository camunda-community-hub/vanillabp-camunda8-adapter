package io.vanillabp.camunda8.client;

import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.vanillabp.camunda8.wiring.Camunda8FetchVariables;
import io.vanillabp.camunda8.wiring.Camunda8MessageTimeToLiveResolver;
import io.vanillabp.camunda8.wiring.Camunda8RetryBackoffResolver;

/**
 * The resolved connection configuration of one Camunda 8 adapter instance, keyed by
 * adapter ID. It does not depend on a platform. The platform modules (Spring Boot,
 * Quarkus) read their own configuration source and fill this object. The plain-Java
 * {@link Camunda8ClientFactory} turns it into a {@code CamundaClient}.
 * <p>
 * <b>Canonical configuration namespace</b>, documented in the {@code README.md} at the
 * repository root. It is keyed by adapter ID. The keys of the adapter live in the shared
 * VanillaBP tree at {@code vanillabp.adapters.<adapter-id>.*}. The platform overlays add
 * them, see the platform modules:
 * <ul>
 *   <li>{@code vanillabp.adapters.<adapter-id>.mode} - {@code self-managed} (default) or
 *       {@code saas}</li>
 *   <li>self-managed: {@code .rest-address} and {@code .grpc-address} (both optional).
 *       Where the address of the protocol in use is missing, the adapter uses the address of
 *       the local cluster, and the start warns about it (see
 *       {@link #usesTheLocalClusterAddress()})</li>
 *   <li>saas: {@code .cluster-id}, {@code .region}, {@code .client-id},
 *       {@code .client-secret} (all required)</li>
 *   <li>{@code .tenant-id} (optional, both modes) - the tenant of Camunda 8 multi-tenancy.
 *       One workflow module may name a tenant of its own under
 *       {@code vanillabp.workflow-modules.<module>.adapters.<id>.tenant-id}. That one wins
 *       over this one</li>
 *   <li>{@code .prefer-rest-over-grpc} (optional, default {@code true}) - whether the
 *       client uses the REST API (recommended) or gRPC for its commands</li>
 *   <li>{@code .auth.*} - how the adapter authenticates, see
 *       {@link Camunda8AuthConfiguration}</li>
 *   <li>{@code .async-task-lock-renewal} (optional, default one hour) - the window in
 *       which the lock of a job is renewed which a {@code @TaskId} handler left open, see
 *       {@link #asyncTaskLockRenewal}</li>
 *   <li>{@code .async-task-max-age-action} (optional, default {@code report}) - what
 *       this adapter does with a task the core reports as older than
 *       {@code vanillabp.delivery.max-task-age}, see {@link #asyncTaskMaxAgeAction}</li>
 *   <li>{@code .retry-backoff} (optional, default
 *       {@value io.vanillabp.camunda8.wiring.Camunda8RetryBackoffResolver#DEFAULT_RETRY_BACKOFF_ISO},
 *       resolvable per workflow module, workflow and task like {@code job-timeout}) - how
 *       long the cluster waits before it hands a FAILED job out again, see
 *       {@link #retryBackoff}</li>
 *   <li>{@code .report-errors-as-stack-trace} (optional, default {@code false}, resolvable
 *       per workflow module, workflow and task like {@code job-timeout}) - whether the
 *       incident of a failed job carries the whole stack trace, see
 *       {@link #reportErrorsAsStackTrace}</li>
 *   <li>{@code .allow-connectors} (optional, default {@code false}, resolvable per
 *       workflow module and workflow) - whether an element built from an element template
 *       is left to the runtime which owns it, see
 *       {@link io.vanillabp.camunda8.wiring.Camunda8Connectors}</li>
 *   <li>{@code .allow-listeners} (optional, default {@code false}, resolvable per
 *       workflow module and workflow) - whether {@code @WorkflowTask} methods serve the
 *       listeners somebody modelled, see
 *       {@link io.vanillabp.camunda8.wiring.Camunda8Listeners}</li>
 *   <li>{@code .health-timeout} (optional, default {@value #DEFAULT_HEALTH_TIMEOUT_ISO}) -
 *       how long the health check waits for the cluster's topology, see
 *       {@link #healthTimeout}</li>
 *   <li>{@code .shutdown-grace} (optional, default {@value #DEFAULT_SHUTDOWN_GRACE_ISO}) -
 *       how long a shutdown waits for the handlers it has in flight, see
 *       {@link #shutdownGrace}</li>
 *   <li>{@code .startup-wait} (optional, default {@value #DEFAULT_STARTUP_WAIT_ISO}) - how
 *       long the start waits for a cluster which is not answering yet, see
 *       {@link #startupWait}</li>
 *   <li>{@code .workflow-visibility-timeout} (optional, default 10 seconds) - how long a
 *       workflow this cluster holds may stay invisible to the query API, see
 *       {@link #workflowVisibilityWindow()}</li>
 *   <li>{@code .ended-workflow-visibility-timeout} (optional, default 3 seconds) - the same
 *       window for a workflow the engine no longer holds, see
 *       {@link #endedWorkflowVisibilityWindow()}</li>
 *   <li>{@code .job-lease} ({@code use} or {@code do-not-use}) - REQUIRED on a release
 *       line whose cluster has a lease, accepted and ignored on the others. It says whether
 *       the jobs this adapter holds from the activation to the answer are leased, see
 *       {@link #validateJobLease(String, Consumer)}</li>
 *   <li>{@code .aggregate-id-as-business-id} (optional, default {@code false}) - whether a
 *       workflow this adapter starts carries the id of the workflow aggregate as its
 *       business id. That id is there to be read and for nothing else, see
 *       {@link #validateAggregateIdAsBusinessId(String, Consumer)}</li>
 *   <li>{@code .probe-open-user-tasks} (optional, default {@code false}) - a check looks
 *       at the other open tasks of a workflow. This key says whether that check asks the
 *       cluster about each Camunda-managed user task of a running instance, see
 *       {@link #validateProbeOpenUserTasks(String, Consumer)}</li>
 * </ul>
 * All fields are optional at binding time. The start validates them (see
 * {@link Camunda8StartupValidation}), and {@link #validate(String)} is the backstop before a
 * client is used.
 */
public class Camunda8AdapterConfiguration {

  /**
   * Opens an adapter section with the default values of its keys. The platform integration
   * builds one per configured adapter id and fills it through the setters.
   */
  public Camunda8AdapterConfiguration() {
  }

  /**
   * The prefix of the canonical configuration namespace of each adapter (see the class
   * javadoc). It is the adapters section of the shared VanillaBP tree.
   */
  public static final String CONFIGURATION_PREFIX = "vanillabp.adapters";

  /**
   * Connection mode of a Camunda 8 adapter instance.
   */
  public enum Mode {
    /** Self-managed cluster (on-premises or self-hosted) addressed by REST/gRPC. */
    SELF_MANAGED,
    /** Camunda 8 SaaS, addressed via the cloud client builder. */
    SAAS
  }

  private Mode mode = Mode.SELF_MANAGED;

  /**
   * How this adapter instance reaches its cluster.
   *
   * @return The mode, never <code>null</code>
   */
  public Mode getMode() {

    return mode;

  }

  /**
   * Sets the connection mode.
   *
   * @param mode The mode the application wrote
   */
  public void setMode(
      final Mode mode) {

    this.mode = mode;

  }

  /**
   * The REST address a self-managed adapter uses where none is configured. It points to a
   * cluster on this machine, started with Camunda's docker compose of this release line. So
   * the port depends on the line.
   */
  public static final String LOCAL_CLUSTER_REST_ADDRESS = Camunda8LocalCluster.REST_ADDRESS;

  /**
   * The gRPC address a self-managed adapter uses where none is configured. It means the
   * same as {@link #LOCAL_CLUSTER_REST_ADDRESS}.
   */
  public static final String LOCAL_CLUSTER_GRPC_ADDRESS = Camunda8LocalCluster.GRPC_ADDRESS;

  /**
   * The REST address of a self-managed cluster.
   */
  private String restAddress;

  /**
   * The REST address of a self-managed cluster.
   *
   * @return The address, or <code>null</code> where none is configured
   */
  public String getRestAddress() {

    return restAddress;

  }

  /**
   * The REST address of a self-managed cluster.
   *
   * @param restAddress The address, or <code>null</code> where none is configured
   */
  public void setRestAddress(
      final String restAddress) {

    this.restAddress = restAddress;

  }

  /**
   * The gRPC address of a self-managed cluster.
   */
  private String grpcAddress;

  /**
   * The gRPC address of a self-managed cluster.
   *
   * @return The address, or <code>null</code> where none is configured
   */
  public String getGrpcAddress() {

    return grpcAddress;

  }

  /**
   * The gRPC address of a self-managed cluster.
   *
   * @param grpcAddress The address, or <code>null</code> where none is configured
   */
  public void setGrpcAddress(
      final String grpcAddress) {

    this.grpcAddress = grpcAddress;

  }

  /**
   * Which of the two protocols the client talks. Default: REST.
   */
  private boolean preferRestOverGrpc = true;

  /**
   * Whether the client talks REST rather than gRPC.
   *
   * @return Whether REST comes first
   */
  public boolean isPreferRestOverGrpc() {

    return preferRestOverGrpc;

  }

  /**
   * Sets which of the two protocols is preferred.
   *
   * @param preferRestOverGrpc What the application wrote
   */
  public void setPreferRestOverGrpc(
      final boolean preferRestOverGrpc) {

    this.preferRestOverGrpc = preferRestOverGrpc;

  }

  /**
   * Whether a self-managed adapter has no address for the protocol its client talks. Then
   * it uses the address of the local cluster.
   *
   * @return Whether the address of the local cluster is used
   */
  public boolean usesTheLocalClusterAddress() {

    if (mode == Mode.SAAS) {
      return false;
    }
    return preferRestOverGrpc
        ? isBlank(restAddress)
        : isBlank(grpcAddress);

  }

  /**
   * The REST address the client of a self-managed adapter uses. It is the configured one,
   * or else the address of the local cluster.
   *
   * @return The address, never <code>null</code>
   */
  public String restAddressInUse() {

    return isBlank(restAddress)
        ? LOCAL_CLUSTER_REST_ADDRESS
        : restAddress;

  }

  /**
   * The gRPC address the client of a self-managed adapter uses. It is the configured one,
   * or else the address of the local cluster.
   *
   * @return The address, never <code>null</code>
   */
  public String grpcAddressInUse() {

    return isBlank(grpcAddress)
        ? LOCAL_CLUSTER_GRPC_ADDRESS
        : grpcAddress;

  }

  /**
   * The key, relative to <code>vanillabp.adapters.&lt;id&gt;.</code>, which sets the
   * address of the protocol the client talks.
   *
   * @return <code>rest-address</code> or <code>grpc-address</code>
   */
  public String addressKeyInUse() {

    return preferRestOverGrpc
        ? "rest-address"
        : "grpc-address";

  }

  /**
   * The Camunda 8 tenant every workflow module of this adapter is deployed into, unless the
   * module names one of its own.
   */
  private String tenantId;

  /**
   * The Camunda 8 tenant every workflow module of this adapter is deployed into.
   *
   * @return The tenant name, or <code>null</code> where none is configured
   */
  public String getTenantId() {

    return tenantId;

  }

  /**
   * The Camunda 8 tenant every workflow module of this adapter is deployed into.
   *
   * @param tenantId The tenant name, or <code>null</code> where none is configured
   */
  public void setTenantId(
      final String tenantId) {

    this.tenantId = tenantId;

  }

  /**
   * The cluster a SaaS adapter connects to.
   */
  private String clusterId;

  /**
   * The cluster a SaaS adapter connects to.
   *
   * @return The cluster id, or <code>null</code> where none is configured
   */
  public String getClusterId() {

    return clusterId;

  }

  /**
   * The cluster a SaaS adapter connects to.
   *
   * @param clusterId The cluster id, or <code>null</code> where none is configured
   */
  public void setClusterId(
      final String clusterId) {

    this.clusterId = clusterId;

  }

  /**
   * The region that SaaS cluster runs in.
   */
  private String region;

  /**
   * The region that SaaS cluster runs in.
   *
   * @return The region, or <code>null</code> where none is configured
   */
  public String getRegion() {

    return region;

  }

  /**
   * The region that SaaS cluster runs in.
   *
   * @param region The region, or <code>null</code> where none is configured
   */
  public void setRegion(
      final String region) {

    this.region = region;

  }

  /**
   * The OIDC client a SaaS adapter connects as.
   */
  private String clientId;

  /**
   * The OIDC client a SaaS adapter connects as.
   *
   * @return The client id, or <code>null</code> where none is configured
   */
  public String getClientId() {

    return clientId;

  }

  /**
   * The OIDC client a SaaS adapter connects as.
   *
   * @param clientId The client id, or <code>null</code> where none is configured
   */
  public void setClientId(
      final String clientId) {

    this.clientId = clientId;

  }

  /**
   * The secret that SaaS client proves itself with.
   */
  private String clientSecret;

  /**
   * The secret that SaaS client proves itself with.
   *
   * @return The client secret, or <code>null</code> where none is configured
   */
  public String getClientSecret() {

    return clientSecret;

  }

  /**
   * The secret that SaaS client proves itself with.
   *
   * @param clientSecret The client secret, or <code>null</code> where none is configured
   */
  public void setClientSecret(
      final String clientSecret) {

    this.clientSecret = clientSecret;

  }

  /**
   * How this adapter instance proves who it is. This is the block
   * <code>vanillabp.adapters.&lt;id&gt;.auth.*</code>. It is never <code>null</code>. An
   * adapter without the block authenticates with <code>none</code>, like every
   * configuration without it.
   */
  private Camunda8AuthConfiguration auth = new Camunda8AuthConfiguration();

  /**
   * How this adapter instance proves who it is.
   *
   * @return The auth block, never <code>null</code>
   */
  public Camunda8AuthConfiguration getAuth() {

    return auth;

  }

  /**
   * How this adapter instance proves who it is.
   *
   * @param auth The auth block, never <code>null</code>
   */
  public void setAuth(
      final Camunda8AuthConfiguration auth) {

    this.auth = auth;

  }

  /**
   * The job timeout of the worker, which is how long a job stays locked. This is the
   * adapter level of the resolution where the most specific value wins (task &gt;
   * workflow &gt; workflow-module &gt; adapter). Default: 5 minutes.
   */
  private Duration jobTimeout;

  /**
   * How long a job of this adapter stays locked, read at adapter level.
   *
   * @return The timeout, or <code>null</code> where no key set one
   */
  public Duration getJobTimeout() {

    return jobTimeout;

  }

  /**
   * How long a job of this adapter stays locked, read at adapter level.
   *
   * @param jobTimeout The timeout, or <code>null</code> where no key set one
   */
  public void setJobTimeout(
      final Duration jobTimeout) {

    this.jobTimeout = jobTimeout;

  }

  /**
   * Whether the application states that its identifiers are unique across all of its
   * workflow modules. The name-clash-avoidance mode <code>none</code> relies on that. The
   * key silences the WARN which the adapter logs per workflow module while that mode
   * applies. It is a deliberate acknowledgement, not a log-level setting. If the statement
   * is wrong, two workflow modules address the same processes and jobs. Default
   * <code>false</code>.
   */
  private boolean acceptUnscopedIdentifiers = false;

  /**
   * Whether the application states that its identifiers are unique across all of its
   * workflow modules.
   *
   * @return Whether unscoped identifiers are accepted
   */
  public boolean isAcceptUnscopedIdentifiers() {

    return acceptUnscopedIdentifiers;

  }

  /**
   * Whether the application states that its identifiers are unique across all of its
   * workflow modules.
   *
   * @param acceptUnscopedIdentifiers Whether unscoped identifiers are accepted
   */
  public void setAcceptUnscopedIdentifiers(
      final boolean acceptUnscopedIdentifiers) {

    this.acceptUnscopedIdentifiers = acceptUnscopedIdentifiers;

  }

  /**
   * Whether this application honours the element-template marker of a model. If it does,
   * an element built from a template is left to the runtime which owns it. In almost every
   * case that is a Camunda connector. This is the adapter level of the resolution where
   * the most specific value wins, over three levels (workflow &gt; workflow-module &gt;
   * adapter), see {@link io.vanillabp.camunda8.wiring.Camunda8AllowConnectorsResolver}.
   * Default <code>false</code>.
   * <p>
   * The boot log of every workflow module it applies to says what it costs, and no key
   * silences that. VanillaBP validates such an element against nothing. It opens no worker
   * for its job type, and it cannot tell whether anything serves it. The model also stops
   * being portable, because a connector is a Camunda 8 element. And what the connector
   * does runs outside the transaction VanillaBP owns.
   * <p>
   * There is no TASK level on purpose, although the keys next to this one have one. That
   * level is keyed by the task DEFINITION. The task definition of a connector is the
   * connector's own type. Every element using that connector shares it, and it carries
   * dots and colons which a relaxed binder splits on. So the model decides instead which
   * element another runtime serves, by <code>zeebe:modelerTemplate</code> on the element.
   */
  private boolean allowConnectors = false;

  /**
   * Whether an element built from an element template is left to the runtime which owns it,
   * read at adapter level.
   *
   * @return Whether such elements are allowed
   */
  public boolean isAllowConnectors() {

    return allowConnectors;

  }

  /**
   * Whether an element built from an element template is left to the runtime which owns it,
   * read at adapter level.
   *
   * @param allowConnectors Whether such elements are allowed
   */
  public void setAllowConnectors(
      final boolean allowConnectors) {

    this.allowConnectors = allowConnectors;

  }

  /**
   * Whether <code>@WorkflowTask</code> methods serve the listeners somebody MODELLED. This is
   * the adapter level of the resolution where the most specific value wins, over three levels
   * (workflow &gt; workflow-module &gt; adapter), see
   * {@link io.vanillabp.camunda8.wiring.Camunda8AllowListenersResolver}. Default
   * <code>false</code>.
   * <p>
   * Without it, a model with such a listener does not boot. The cluster creates a job for
   * every listener. A job type which nothing subscribes to stops the workflow there, with no
   * incident and no message. A boot which says so is better. With the key, the listener is a
   * task like any other. The adapter asks for a method, and it reports a method which serves
   * no listener. The boot log of every workflow module it applies to says what that costs.
   * <p>
   * There is no TASK level on purpose, although the keys next to this one have one. That
   * level is keyed by a task DEFINITION. But this key decides whether a listener becomes a
   * task at all.
   */
  private boolean allowListeners = false;

  /**
   * Whether modelled listeners are served by <code>&#64;WorkflowTask</code> methods, read at
   * adapter level.
   *
   * @return Whether modelled listeners are served
   */
  public boolean isAllowListeners() {

    return allowListeners;

  }

  /**
   * Whether modelled listeners are served by <code>&#64;WorkflowTask</code> methods, read at
   * adapter level.
   *
   * @param allowListeners Whether modelled listeners are served
   */
  public void setAllowListeners(
      final boolean allowListeners) {

    this.allowListeners = allowListeners;

  }

  /**
   * Whether the incident of a failed job carries the whole stack trace of what the
   * application threw. This is the adapter level of the resolution where the most specific
   * value wins, over four levels (task &gt; workflow &gt; workflow-module &gt; adapter), see
   * {@link io.vanillabp.camunda8.wiring.Camunda8ReportErrorsAsStackTraceResolver}.
   * <code>null</code> where this level says nothing, and then the default
   * <code>false</code> applies.
   */
  private Boolean reportErrorsAsStackTrace;

  /**
   * Whether the incident of a failed job carries the whole stack trace, read at adapter
   * level.
   *
   * @return The setting, or <code>null</code> where this level says nothing
   */
  public Boolean getReportErrorsAsStackTrace() {

    return reportErrorsAsStackTrace;

  }

  /**
   * Whether the incident of a failed job carries the whole stack trace, read at adapter
   * level.
   *
   * @param reportErrorsAsStackTrace The setting, or <code>null</code> where this level says
   *          nothing
   */
  public void setReportErrorsAsStackTrace(
      final Boolean reportErrorsAsStackTrace) {

    this.reportErrorsAsStackTrace = reportErrorsAsStackTrace;

  }

  /**
   * How long the lock of a job is extended which a <code>&#64;TaskId</code> handler left
   * open. This also sets how often that lock is renewed. When the window has passed, the
   * cluster hands the job out again. VanillaBP answers this redelivery from the record it
   * wrote when the handler ran. Then the adapter extends the lock by another window. So the
   * cluster's own redelivery drives the renewal, and the application never notices it.
   * Default: {@value #DEFAULT_ASYNC_TASK_LOCK_RENEWAL_ISO}.
   * <p>
   * <b>Why an hour.</b> The window has to stay clearly below
   * <code>vanillabp.delivery.retention</code> (seven days by default). The record is what
   * answers the redelivery. Once it is cleaned up, the redelivery reaches the method of the
   * application a second time. An hour is 168 times shorter than the default retention. So
   * it still works if somebody lowers the retention without thinking. Per open task and
   * window, the cost is one activation, one read of the delivery record and one lock
   * command. With 1.000 open asynchronous tasks that is 0,28 per second, with 10.000 it is
   * 2,8 per second. Those renewals run on the same execution slots as real work
   * ({@link #workerThreads}). That is why the window is not a few minutes. The age check is
   * only as fine as the window. Hourly is fine for something measured in days.
   * <p>
   * <b>No callback into the application.</b> There is no keep-alive hook on purpose, and no
   * aware interface which asks whether an open task is still wanted. An application which
   * knows its task is obsolete has <code>ProcessService#cancelTask</code>. That exists and
   * does not depend on the BPMS. An application which lost track of the task could not give
   * a true answer to such a question anyway. Instead, VanillaBP measures how long the task
   * has been open (<code>vanillabp.delivery.max-task-age</code>). This adapter then reacts
   * to it, see {@link #asyncTaskMaxAgeAction}.
   */
  private Duration asyncTaskLockRenewal;

  /**
   * How long the lock of a job left open by a <code>&#64;TaskId</code> handler is extended
   * for.
   *
   * @return The window, or <code>null</code> where no key set one
   */
  public Duration getAsyncTaskLockRenewal() {

    return asyncTaskLockRenewal;

  }

  /**
   * How long the lock of a job left open by a <code>&#64;TaskId</code> handler is extended
   * for.
   *
   * @param asyncTaskLockRenewal The window, or <code>null</code> where no key set one
   */
  public void setAsyncTaskLockRenewal(
      final Duration asyncTaskLockRenewal) {

    this.asyncTaskLockRenewal = asyncTaskLockRenewal;

  }

  /**
   * How long the cluster waits before it hands a FAILED job out again. This is the adapter
   * level of the resolution where the most specific value wins (task &gt; workflow &gt;
   * workflow-module &gt; adapter). {@link Camunda8RetryBackoffResolver} explains the default
   * of ten seconds.
   */
  private Duration retryBackoff;

  /**
   * How long the cluster waits before it hands a failed job out again, read at adapter
   * level.
   *
   * @return The backoff, or <code>null</code> where no key set one
   */
  public Duration getRetryBackoff() {

    return retryBackoff;

  }

  /**
   * How long the cluster waits before it hands a failed job out again, read at adapter
   * level.
   *
   * @param retryBackoff The backoff, or <code>null</code> where no key set one
   */
  public void setRetryBackoff(
      final Duration retryBackoff) {

    this.retryBackoff = retryBackoff;

  }

  /**
   * The removed key <code>fetch-variables</code> at adapter level. It exists only to be
   * REJECTED at startup, see {@link Camunda8FetchVariables#rejectTheRemovedKey}. It is a
   * text. So every value somebody wrote reaches that message, and not a conversion
   * error.
   */
  private String fetchVariables;

  /**
   * What the application wrote under the removed key <code>fetch-variables</code>, kept so
   * the startup can refuse it.
   *
   * @return The value of the removed key, or <code>null</code> where nobody wrote it
   */
  public String getFetchVariables() {

    return fetchVariables;

  }

  /**
   * What the application wrote under the removed key <code>fetch-variables</code>, kept so
   * the startup can refuse it.
   *
   * @param fetchVariables The value of the removed key, or <code>null</code> where nobody
   *          wrote it
   */
  public void setFetchVariables(
      final String fetchVariables) {

    this.fetchVariables = fetchVariables;

  }

  /**
   * The default of {@link #asyncTaskLockRenewal} in ISO-8601 notation, for javadoc and
   * messages.
   */
  public static final String DEFAULT_ASYNC_TASK_LOCK_RENEWAL_ISO = "PT1H";

  /**
   * The default of {@link #asyncTaskLockRenewal}: one hour.
   */
  public static final Duration DEFAULT_ASYNC_TASK_LOCK_RENEWAL = Duration
      .parse(DEFAULT_ASYNC_TASK_LOCK_RENEWAL_ISO);

  /**
   * The key this adapter used before the renewal window got a name which says what it is.
   * It exists only to be REJECTED at startup. It carried a horizon of fourteen days, which
   * was longer than the retention of the delivery records. So every asynchronous task which
   * stayed open longer than that retention ran the method of the application a second
   * time. VanillaBP 2 is not released, so a loud rename is better than a silent one.
   */
  private Duration asyncTaskTimeout;

  /**
   * What the application wrote under the old name of the lock renewal window, kept so the
   * startup can refuse it.
   *
   * @return The value of the retired key, or <code>null</code> where nobody wrote it
   */
  public Duration getAsyncTaskTimeout() {

    return asyncTaskTimeout;

  }

  /**
   * What the application wrote under the old name of the lock renewal window, kept so the
   * startup can refuse it.
   *
   * @param asyncTaskTimeout The value of the retired key, or <code>null</code> where nobody wrote it
   */
  public void setAsyncTaskTimeout(
      final Duration asyncTaskTimeout) {

    this.asyncTaskTimeout = asyncTaskTimeout;

  }

  /**
   * What this adapter does with a task the core reports as older than
   * <code>vanillabp.delivery.max-task-age</code>.
   */
  public enum AsyncTaskMaxAgeAction {
    /**
     * Nothing beyond the report the core writes anyway. This is the default. It is also
     * all that a BPMS without a lock to renew can do.
     */
    REPORT,
    /**
     * Stop renewing the lock of the job and fail the job. Then the cluster raises an
     * incident which names the workflow aggregate and the age. Operators watch incidents.
     * A log line is easier to miss.
     */
    INCIDENT
  }

  /**
   * Whether this adapter leases the activation of every job it holds from the activation to
   * the answer.
   */
  public enum JobLease {
    /**
     * Activate with a lease. Then the cluster takes the answer of the CURRENT activation
     * only. A run whose lock expired while it worked is refused. So it cannot overwrite
     * what the newer run wrote.
     */
    USE,
    /**
     * Activate without a lease. Every line before 8.10 does that, and an application
     * which rolls back to such a line needs it.
     */
    DO_NOT_USE
  }

  /**
   * Whether the jobs this adapter holds from the activation to the answer are leased. There
   * is NO default. On a line which can lease, the boot stops until the application says
   * which of the two it wants, see {@link #validateJobLease(String, Consumer)}.
   */
  private JobLease jobLease;

  /**
   * Whether the jobs this adapter holds from the activation to the answer are leased.
   *
   * @return The setting, or <code>null</code> where no key set one
   */
  public JobLease getJobLease() {

    return jobLease;

  }

  /**
   * Whether the jobs this adapter holds from the activation to the answer are leased.
   *
   * @param jobLease The setting, or <code>null</code> where no key set one
   */
  public void setJobLease(
      final JobLease jobLease) {

    this.jobLease = jobLease;

  }

  /**
   * What this adapter does about a task which stayed open longer than
   * <code>vanillabp.delivery.max-task-age</code> allows (thirty days by default). Default:
   * {@link AsyncTaskMaxAgeAction#REPORT}, which leaves it at the message of the core.
   * <p>
   * <code>incident</code> is what a cluster can do beyond a log line. The adapter stops
   * renewing the lock. It fails the job with a message which names the aggregate and the
   * age. So the incident shows up where operators already look. It is not the default, on
   * purpose. A task which waits for a person or for a partner may rightly run for weeks. An
   * incident on such a task would be worse than the leak the age check looks for.
   */
  private AsyncTaskMaxAgeAction asyncTaskMaxAgeAction = AsyncTaskMaxAgeAction.REPORT;

  /**
   * What this adapter does about a task which stayed open longer than the core allows.
   *
   * @return The action, never <code>null</code>
   */
  public AsyncTaskMaxAgeAction getAsyncTaskMaxAgeAction() {

    return asyncTaskMaxAgeAction;

  }

  /**
   * What this adapter does about a task which stayed open longer than the core allows.
   *
   * @param asyncTaskMaxAgeAction The action, never <code>null</code>
   */
  public void setAsyncTaskMaxAgeAction(
      final AsyncTaskMaxAgeAction asyncTaskMaxAgeAction) {

    this.asyncTaskMaxAgeAction = asyncTaskMaxAgeAction;

  }

  /**
   * How long the shutdown of this adapter instance waits for the handlers it has in flight.
   * After that, the client is closed under them. Default:
   * {@value #DEFAULT_SHUTDOWN_GRACE_ISO}.
   * <p>
   * <b>It is the budget of the whole shutdown, not of one workflow module.</b> The platform
   * stops the modules one after another. An adapter which spent this number per module would
   * spend it once for each module of the application. Then nobody could hold the total
   * against the budget of the runtime, and that total is the only number which matters. So
   * the key stays at adapter level. It is not resolvable per workflow module, per workflow
   * or per task. It is read against one shutdown budget, and only the application has one.
   * The workers of a module are closed when that module is stopped. The module stopped last
   * waits for all of them at once (see {@code Camunda8Drain#awaitEveryModuleQuiet}).
   * <p>
   * <b>Why twenty seconds.</b> The Camunda client does not drain. Closing a worker returns
   * without waiting for the jobs it already activated. Closing the client interrupts the
   * running handlers within milliseconds. An interrupted handler throws. A handler which
   * threw used to have its job failed with one retry less. So every restart cost one retry
   * per job in flight. So the number has to be longer than an ordinary handler (a remote
   * call plus a commit). And it has to be shorter than the shutdown budget of whatever runs
   * the application, so VanillaBP is never the reason a container is killed. Spring Boot's
   * <code>spring.lifecycle.timeout-per-shutdown-phase</code> and Kubernetes'
   * <code>terminationGracePeriodSeconds</code> both default to thirty seconds. Twenty leaves
   * a margin to both. If you raise this value, raise those two as well. The startup warning
   * says so.
   * <p>
   * <code>PT0S</code> switches the drain off. The workers are closed, and the client goes
   * down right after them. Nothing is lost that way either. A handler cut off this way
   * leaves its job to its lock, and the job is not failed. But the work it did up to the
   * interrupt is done again on the redelivery.
   */
  private Duration shutdownGrace;

  /**
   * How long a shutdown waits for the handlers this adapter has in flight.
   *
   * @return The grace, or <code>null</code> where no key set one
   */
  public Duration getShutdownGrace() {

    return shutdownGrace;

  }

  /**
   * How long a shutdown waits for the handlers this adapter has in flight.
   *
   * @param shutdownGrace The grace, or <code>null</code> where no key set one
   */
  public void setShutdownGrace(
      final Duration shutdownGrace) {

    this.shutdownGrace = shutdownGrace;

  }

  /**
   * How long the health check of this adapter instance waits for the cluster to answer its
   * topology request. Default: {@value #DEFAULT_HEALTH_TIMEOUT_ISO}.
   * <p>
   * <b>Why two seconds.</b> The check runs on the thread which serves the health request.
   * Whatever polls that endpoint has a timeout of its own. The readiness probe of Kubernetes
   * defaults to one second and gives up after three failures. A check which waits longer
   * than the probe turns a slow cluster into a failing probe, and it never reports DOWN.
   * That is the worst of both. Two seconds is far above the round trip to a healthy cluster,
   * and below what a probe is willing to wait. The client's own <code>request-timeout</code>
   * is NOT reused, on purpose. Ten seconds is right for a command which carries work, and
   * wrong for a question about liveness.
   * <p>
   * <code>PT0S</code> switches the check off. Then the adapter reports UNKNOWN with a note
   * which says so, and the endpoint stops talking to the cluster at all.
   */
  private Duration healthTimeout;

  /**
   * How long the health check waits for the cluster to answer its topology request.
   *
   * @return The timeout, or <code>null</code> where no key set one
   */
  public Duration getHealthTimeout() {

    return healthTimeout;

  }

  /**
   * How long the health check waits for the cluster to answer its topology request.
   *
   * @param healthTimeout The timeout, or <code>null</code> where no key set one
   */
  public void setHealthTimeout(
      final Duration healthTimeout) {

    this.healthTimeout = healthTimeout;

  }

  /**
   * How long the start of this adapter instance waits for its cluster to answer. Only then
   * does it make the first round which decides anything. Default:
   * {@value #DEFAULT_STARTUP_WAIT_ISO}.
   * <p>
   * <b>Why the default is long.</b> It is there for a cluster which boots together with the
   * application, and that takes minutes rather than seconds. The price of waiting is a late
   * abort, but not a late DIAGNOSIS. Every few seconds the wait writes an INFO line with the
   * address, the time gone and the last answer of the cluster. So a typo in the address
   * shows as "connection refused" from the first attempt on. And an answer which the cluster
   * will repeat ends the start at once, not after the deadline.
   * <p>
   * <code>PT0S</code> switches the waiting off. Then the start fails on the first round
   * which cannot reach the cluster.
   * <p>
   * Adapter level only. The start waits for the cluster, and a cluster does not belong to a
   * workflow module.
   */
  private Duration startupWait;

  /**
   * How long the start waits for the cluster to answer before it gives up.
   *
   * @return The wait, or <code>null</code> where no key set one
   */
  public Duration getStartupWait() {

    return startupWait;

  }

  /**
   * How long the start waits for the cluster to answer before it gives up.
   *
   * @param startupWait The wait, or <code>null</code> where no key set one
   */
  public void setStartupWait(
      final Duration startupWait) {

    this.startupWait = startupWait;

  }

  /**
   * The default of {@link #startupWait} in ISO-8601 notation, for javadoc and messages.
   */
  public static final String DEFAULT_STARTUP_WAIT_ISO = "PT10M";

  /**
   * The default of {@link #startupWait}: ten minutes.
   */
  public static final Duration DEFAULT_STARTUP_WAIT = Duration
      .parse(DEFAULT_STARTUP_WAIT_ISO);

  /**
   * The default of {@link #healthTimeout} in ISO-8601 notation, for javadoc and messages.
   */
  public static final String DEFAULT_HEALTH_TIMEOUT_ISO = "PT2S";

  /**
   * The default of {@link #healthTimeout}: two seconds.
   */
  public static final Duration DEFAULT_HEALTH_TIMEOUT = Duration
      .parse(DEFAULT_HEALTH_TIMEOUT_ISO);

  /**
   * The default of {@link #shutdownGrace} in ISO-8601 notation, for javadoc and messages.
   */
  public static final String DEFAULT_SHUTDOWN_GRACE_ISO = "PT20S";

  /**
   * The default of {@link #shutdownGrace}: twenty seconds.
   */
  public static final Duration DEFAULT_SHUTDOWN_GRACE = Duration
      .parse(DEFAULT_SHUTDOWN_GRACE_ISO);

  /**
   * The shutdown budget both Spring Boot and Kubernetes default to, which
   * {@link #shutdownGrace} has to stay below.
   */
  public static final Duration PLATFORM_SHUTDOWN_BUDGET = Duration.ofSeconds(30);

  /**
   * How this adapter instance runs what it delivers. It is a positive number of platform
   * threads, or the literal <code>virtual</code>. Default: four platform threads.
   * <p>
   * The number applies to the whole adapter, on purpose. The Camunda client owns one
   * executor, and this adapter owns one client per adapter id. So every worker of the
   * adapter shares that executor, across every workflow module the adapter serves.
   * {@link Camunda8ExecutionModel} says what to size the number against.
   */
  private String workerThreads;

  /**
   * How this adapter instance runs what it delivers: a number of platform threads, or
   * <code>virtual</code>.
   *
   * @return What the application wrote, or <code>null</code> where no key set one
   */
  public String getWorkerThreads() {

    return workerThreads;

  }

  /**
   * How this adapter instance runs what it delivers: a number of platform threads, or
   * <code>virtual</code>.
   *
   * @param workerThreads What the application wrote, or <code>null</code> where no key set one
   */
  public void setWorkerThreads(
      final String workerThreads) {

    this.workerThreads = workerThreads;

  }

  /**
   * How many handlers may run at the same time while {@link #workerThreads} is
   * <code>virtual</code>. Virtual threads have no limit of their own, and the limit of the
   * client is per worker. Default: the number the platform-thread mode would use. So
   * switching the mode changes how threads are made, but not how much runs at once. If it
   * is set without the virtual mode, the boot fails, because it would be ignored.
   */
  private Integer workerThreadsBound;

  /**
   * How many handlers may run at the same time while the threads are virtual.
   *
   * @return The bound, or <code>null</code> where no key set one
   */
  public Integer getWorkerThreadsBound() {

    return workerThreadsBound;

  }

  /**
   * How many handlers may run at the same time while the threads are virtual.
   *
   * @param workerThreadsBound The bound, or <code>null</code> where no key set one
   */
  public void setWorkerThreadsBound(
      final Integer workerThreadsBound) {

    this.workerThreadsBound = workerThreadsBound;

  }

  /**
   * How many jobs one worker may hold at the same time (the client's
   * <code>maxJobsActive</code>). Default: eight per execution slot, but no more than the
   * client's own 32. So at the default of four threads, the last job of a batch waits for
   * at most seven handler runtimes. With one thread it would wait for thirty-one.
   * Camunda's rule is
   * <code>maxJobsActive &lt; threads &times; (jobTimeout / avgHandlerDuration)</code>.
   */
  private Integer maxJobsActive;

  /**
   * How many jobs one worker may hold at the same time.
   *
   * @return The number, or <code>null</code> where no key set one
   */
  public Integer getMaxJobsActive() {

    return maxJobsActive;

  }

  /**
   * How many jobs one worker may hold at the same time.
   *
   * @param maxJobsActive The number, or <code>null</code> where no key set one
   */
  public void setMaxJobsActive(
      final Integer maxJobsActive) {

    this.maxJobsActive = maxJobsActive;

  }

  /**
   * How long a worker waits between two activation requests. Default: the client's 100
   * milliseconds.
   */
  private Duration pollInterval;

  /**
   * How long a worker waits between two activation requests.
   *
   * @return The interval, or <code>null</code> to leave the client's own default alone
   */
  public Duration getPollInterval() {

    return pollInterval;

  }

  /**
   * How long a worker waits between two activation requests.
   *
   * @param pollInterval The interval, or <code>null</code> to leave the client's own default alone
   */
  public void setPollInterval(
      final Duration pollInterval) {

    this.pollInterval = pollInterval;

  }

  /**
   * How long a request to the cluster may take. For an activation request this is also
   * the long-polling window. Default: the client's 10 seconds.
   */
  private Duration requestTimeout;

  /**
   * How long a request to the cluster may take.
   *
   * @return The timeout, or <code>null</code> where no key set one
   */
  public Duration getRequestTimeout() {

    return requestTimeout;

  }

  /**
   * How long a request to the cluster may take.
   *
   * @param requestTimeout The timeout, or <code>null</code> where no key set one
   */
  public void setRequestTimeout(
      final Duration requestTimeout) {

    this.requestTimeout = requestTimeout;

  }

  /**
   * Whether the cluster PUSHES jobs to the workers instead of only answering their
   * polls. Default: the client's <code>false</code>. It lowers the delivery latency and
   * adds no concurrency. It also makes the client wrap the execution slots in a second
   * semaphore of {@link #maxJobsActive} permits. Acquiring a permit waits up to the job
   * timeout.
   */
  private Boolean streamEnabled;

  /**
   * Whether the cluster pushes jobs to the workers instead of only answering their polls.
   *
   * @return The setting, or <code>null</code> to leave the client's own default alone
   */
  public Boolean getStreamEnabled() {

    return streamEnabled;

  }

  /**
   * Whether the cluster pushes jobs to the workers instead of only answering their polls.
   *
   * @param streamEnabled The setting, or <code>null</code> to leave the client's own default alone
   */
  public void setStreamEnabled(
      final Boolean streamEnabled) {

    this.streamEnabled = streamEnabled;

  }

  /**
   * How long a job stream stays open before the client re-opens it. Default: the
   * client's. Only relevant with {@link #streamEnabled}.
   */
  private Duration streamTimeout;

  /**
   * How long a job stream stays open before the client re-opens it.
   *
   * @return The timeout, or <code>null</code> to leave the client's own default alone
   */
  public Duration getStreamTimeout() {

    return streamTimeout;

  }

  /**
   * How long a job stream stays open before the client re-opens it.
   *
   * @param streamTimeout The timeout, or <code>null</code> to leave the client's own default alone
   */
  public void setStreamTimeout(
      final Duration streamTimeout) {

    this.streamTimeout = streamTimeout;

  }

  /**
   * How long the cluster keeps a published message. The number does two jobs which pull in
   * opposite directions. That is why it is resolvable per workflow module, workflow and
   * MESSAGE, and not only per adapter (see {@link Camunda8MessageTimeToLiveResolver}):
   * <ul>
   * <li>It BUFFERS a message published before its subscription exists. For that it should
   * be large. Say the workflow is still two steps away from its catch event. The message
   * is only correlated because the cluster held it.</li>
   * <li>It is the window in which a message id is DEDUPLICATED. For that it should be
   * small. As long as the message lives, a second publication of the same id is dropped
   * without a word, even if it is fully legitimate.</li>
   * </ul>
   * Default: whatever the client uses, one hour at the time of writing. If nothing is
   * configured, VanillaBP sets nothing on the command.
   * <p>
   * <b>Shortening it does not give a short deduplication window.</b> The cluster forgets an
   * expired message id on a sweep of its own, not at the moment it expires. We measured it
   * against camunda/camunda:8.9.16 on 2026-08-27. A two-second time-to-live still
   * deduplicated five seconds later, and the id was forgotten after 75
   * (see {@code Camunda8TaskProcessingIT#theTimeToLiveDecidesHowLongTheClustersNetLasts}).
   * Two legitimate correlations are told apart by what they carry. That is a correlation
   * id which varies, or the activation which VanillaBP puts into the message id.
   */
  private Duration messageTimeToLive;

  /**
   * How long the cluster keeps a published message, read at adapter level.
   *
   * @return The time to live, or <code>null</code> to leave the client's own default alone
   */
  public Duration getMessageTimeToLive() {

    return messageTimeToLive;

  }

  /**
   * How long the cluster keeps a published message, read at adapter level.
   *
   * @param messageTimeToLive The time to live, or <code>null</code> to leave the client's own default alone
   */
  public void setMessageTimeToLive(
      final Duration messageTimeToLive) {

    this.messageTimeToLive = messageTimeToLive;

  }

  /**
   * The largest inbound message the client accepts, in bytes. Default: the client's.
   */
  private Integer maxMessageSize;

  /**
   * The largest inbound message the client accepts, in bytes.
   *
   * @return The size, or <code>null</code> to leave the client's own default alone
   */
  public Integer getMaxMessageSize() {

    return maxMessageSize;

  }

  /**
   * The largest inbound message the client accepts, in bytes.
   *
   * @param maxMessageSize The size, or <code>null</code> to leave the client's own default alone
   */
  public void setMaxMessageSize(
      final Integer maxMessageSize) {

    this.maxMessageSize = maxMessageSize;

  }

  /**
   * The keep-alive interval of the client's connections. Default: the client's.
   */
  private Duration keepAlive;

  /**
   * The keep-alive interval of the client's connections.
   *
   * @return The interval, or <code>null</code> to leave the client's own default alone
   */
  public Duration getKeepAlive() {

    return keepAlive;

  }

  /**
   * The keep-alive interval of the client's connections.
   *
   * @param keepAlive The interval, or <code>null</code> to leave the client's own default alone
   */
  public void setKeepAlive(
      final Duration keepAlive) {

    this.keepAlive = keepAlive;

  }

  /**
   * How many HTTP connections the REST transport may open. Default: the client's.
   */
  private Integer maxHttpConnections;

  /**
   * How many HTTP connections the REST transport may open.
   *
   * @return The number, or <code>null</code> to leave the client's own default alone
   */
  public Integer getMaxHttpConnections() {

    return maxHttpConnections;

  }

  /**
   * How many HTTP connections the REST transport may open.
   *
   * @param maxHttpConnections The number, or <code>null</code> to leave the client's own default alone
   */
  public void setMaxHttpConnections(
      final Integer maxHttpConnections) {

    this.maxHttpConnections = maxHttpConnections;

  }

  /**
   * The authority the TLS certificate is verified against. It is for a gateway reached
   * under another name than the one in its certificate. Default: none.
   */
  private String overrideAuthority;

  /**
   * The authority the TLS certificate of the cluster is verified against.
   *
   * @return The authority, or <code>null</code> where none is configured
   */
  public String getOverrideAuthority() {

    return overrideAuthority;

  }

  /**
   * The authority the TLS certificate of the cluster is verified against.
   *
   * @param overrideAuthority The authority, or <code>null</code> where none is configured
   */
  public void setOverrideAuthority(
      final String overrideAuthority) {

    this.overrideAuthority = overrideAuthority;

  }

  /**
   * How long a workflow this cluster holds may stay invisible to the query API. The
   * awareness probe searches that API. The exporter which feeds this read model runs behind
   * the engine. So a workflow started moments ago cannot be found yet.
   * <p>
   * VanillaBP waits this out where it knows the workflow is here. That is after a start it
   * dispatched, or after this cluster delivered a job of that workflow. It never waits for
   * a workflow nobody ever heard of. Raise it for a slow exporter. Set it to zero to switch
   * the waiting off. Default: 10 seconds.
   */
  private Duration workflowVisibilityTimeout;

  /**
   * How long a workflow this cluster holds may stay invisible to the query API.
   *
   * @return The window, or <code>null</code> where no key set one
   */
  public Duration getWorkflowVisibilityTimeout() {

    return workflowVisibilityTimeout;

  }

  /**
   * How long a workflow this cluster holds may stay invisible to the query API.
   *
   * @param workflowVisibilityTimeout The window, or <code>null</code> where no key set one
   */
  public void setWorkflowVisibilityTimeout(
      final Duration workflowVisibilityTimeout) {

    this.workflowVisibilityTimeout = workflowVisibilityTimeout;

  }

  /**
   * The same window for a workflow the ENGINE has already forgotten. This window is much
   * shorter.
   * <p>
   * The long window above is there for a workflow which has just been started. The engine
   * holds it, and the read model needs a moment to hear about it. A workflow the engine no
   * longer holds is a different case. It ended, or it never existed. It has been in the
   * read model for as long as it ran. So the only thing left to wait for is its END to
   * arrive there.
   * <p>
   * The adapter asks the engine before the search
   * ({@code Camunda8ProcessService#awarenessOfWorkflow}). So it knows which of the two cases
   * it is in. Raise it where an exporter is slow. Set it to zero to answer at once.
   * Default: 3 seconds.
   */
  private Duration endedWorkflowVisibilityTimeout;

  /**
   * How long the end of a workflow may take to reach the query API.
   *
   * @return The window, or <code>null</code> where no key set one
   */
  public Duration getEndedWorkflowVisibilityTimeout() {

    return endedWorkflowVisibilityTimeout;

  }

  /**
   * How long the end of a workflow may take to reach the query API.
   *
   * @param endedWorkflowVisibilityTimeout The window, or <code>null</code> where no key set one
   */
  public void setEndedWorkflowVisibilityTimeout(
      final Duration endedWorkflowVisibilityTimeout) {

    this.endedWorkflowVisibilityTimeout = endedWorkflowVisibilityTimeout;

  }

  /**
   * Whether a workflow this adapter starts carries the id of the workflow aggregate as its
   * business id. Default: <code>false</code>.
   * <p>
   * It is there for the eye and for nothing else. Operate shows the business id where a
   * reader looks first. Otherwise the id of the aggregate is a process variable two clicks
   * further away. VanillaBP never reads the value back, on any line. So switching the key
   * on changes nothing about how a workflow is found again.
   * <p>
   * It is off by default because the field belongs to the application until this adapter
   * takes it. The value can be set only once and never changed. An installation which
   * wants its own value there would lose it without ever being asked. See
   * {@link #validateAggregateIdAsBusinessId(String, Consumer)} and decision 37 in the
   * repository's DECISIONS.md.
   */
  private boolean aggregateIdAsBusinessId = false;

  /**
   * Whether a workflow this adapter starts carries the id of the workflow aggregate as its
   * business id.
   *
   * @return Whether the aggregate id is written there
   */
  public boolean isAggregateIdAsBusinessId() {

    return aggregateIdAsBusinessId;

  }

  /**
   * Whether a workflow this adapter starts carries the id of the workflow aggregate as its
   * business id.
   *
   * @param aggregateIdAsBusinessId Whether the aggregate id is written there
   */
  public void setAggregateIdAsBusinessId(
      final boolean aggregateIdAsBusinessId) {

    this.aggregateIdAsBusinessId = aggregateIdAsBusinessId;

  }

  /**
   * Whether the probe of {@code Camunda8OpenTaskProbe} is sent per task for the
   * Camunda-managed user tasks this adapter serves. Default: <code>false</code>.
   * <p>
   * A check looks at the other open tasks of a workflow. It first asks the ENGINE about the
   * process instance. A <code>404</code> there answers every record of that workflow at
   * once. This key adds a second question, one round trip per user task of an instance
   * which is still running: is THAT task still open? It is off by default. The answer costs
   * a command per task, and it also fires a modelled <code>updating</code> listener. And
   * most applications learn about a canceled user task from its cancel listener anyway. See
   * decision 38 in the repository's DECISIONS.md.
   */
  private boolean probeOpenUserTasks = false;

  /**
   * Whether the adapter asks the cluster about every Camunda-managed user task of a running
   * instance.
   *
   * @return Whether the per task probe is sent
   */
  public boolean isProbeOpenUserTasks() {

    return probeOpenUserTasks;

  }

  /**
   * Whether the adapter asks the cluster about every Camunda-managed user task of a running
   * instance.
   *
   * @param probeOpenUserTasks Whether the per task probe is sent
   */
  public void setProbeOpenUserTasks(
      final boolean probeOpenUserTasks) {

    this.probeOpenUserTasks = probeOpenUserTasks;

  }

  /**
   * How many characters a business id may carry. The cluster refuses a longer one with
   * "The provided businessId exceeds the limit of 256 characters". The START of a workflow
   * is the worst place for a refusal. So a longer aggregate id is cut and not rejected.
   * Nothing reads the value back anyway.
   */
  public static final int BUSINESS_ID_LIMIT = 256;

  /**
   * The connection properties which the configured {@link #mode} requires and which are
   * not set. These are property KEY names relative to
   * <code>vanillabp.adapters.&lt;id&gt;.</code>. Values are never part of messages. An
   * empty list means the configuration is complete.
   * <p>
   * Only SaaS requires keys. A self-managed adapter without an address uses the address of
   * the local cluster (see
   * {@link #usesTheLocalClusterAddress()}).
   *
   * @return The missing property keys
   */
  public List<String> missingConnectionProperties() {

    final var missing = new LinkedList<String>();
    if (mode == Mode.SAAS) {
      if (isBlank(clusterId)) {
        missing.add("cluster-id");
      }
      if (isBlank(region)) {
        missing.add("region");
      }
      if (isBlank(clientId)) {
        missing.add("client-id");
      }
      if (isBlank(clientSecret)) {
        missing.add("client-secret");
      }
    }
    return missing;

  }

  /**
   * Validates that all properties required for the configured {@link #mode} are
   * present. The PRIMARY validation happens at startup
   * ({@link Camunda8StartupValidation}). This method stays as the BACKSTOP at runtime,
   * called before the client is used. So a degraded adapter (startup policy
   * <code>warn</code>) still fails its first use with a guiding message, and not with an
   * obscure connection error.
   *
   * @param adapterId The adapter ID (used to build the property names in error messages)
   * @throws IllegalStateException If a required property is missing, naming the exact
   *         missing configuration properties
   */
  public void validate(
      final String adapterId) {

    final var missing = missingConnectionProperties();
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          ("Camunda 8 adapter '%s' is used but not configured: the %s missing. "
              + "Configure the Camunda 8 connection for this adapter instance.")
              .formatted(
                  adapterId,
                  missing.size() == 1
                      ? "property '%s' is".formatted(propertyKey(adapterId, missing.getFirst()))
                      : "properties %s are".formatted(missing
                          .stream()
                          .map(key -> "'%s'".formatted(propertyKey(adapterId, key)))
                          .collect(Collectors.joining(", ")))));
    }

  }

  /**
   * The client's own default for <code>max-jobs-active</code>. The default of this adapter
   * never goes above it.
   */
  public static final int CLIENT_MAX_JOBS_ACTIVE = 32;

  /**
   * How many jobs one worker holds per execution slot where nothing is configured.
   */
  public static final int JOBS_PER_SLOT = 8;

  /**
   * The resolved execution model of this adapter instance.
   *
   * @param adapterId The adapter id (used to build property keys in messages)
   * @return The model
   * @throws IllegalStateException If <code>worker-threads</code> or
   *           <code>worker-threads-bound</code> is not usable
   */
  public Camunda8ExecutionModel executionModel(
      final String adapterId) {

    return Camunda8ExecutionModel.resolve(adapterId, workerThreads, workerThreadsBound);

  }

  /**
   * The resolved <code>max-jobs-active</code> of this adapter instance. It is the
   * configured value, or else {@value #JOBS_PER_SLOT} per execution slot, but no more than
   * the client's {@value #CLIENT_MAX_JOBS_ACTIVE}.
   *
   * @param adapterId The adapter id (used to build property keys in messages)
   * @return How many jobs one worker of this adapter may hold at the same time
   * @throws IllegalStateException If the configured value would leave execution slots
   *           idle by construction
   */
  public int resolvedMaxJobsActive(
      final String adapterId) {

    final var model = executionModel(adapterId);
    if (maxJobsActive == null) {
      return Math.min(model.slots() * JOBS_PER_SLOT, CLIENT_MAX_JOBS_ACTIVE);
    }
    if (maxJobsActive < model.slots()) {
      throw new IllegalStateException(
          """
              Camunda 8 adapter '%s' has '%s: %d' while '%s' allows %d handlers at the same time. \
              A worker never holds more jobs than it was allowed to activate, so %d of the %d \
              execution slots would stay idle by construction. Raise the one or lower the other \
              (the default is %d per slot, capped at the client's %d)."""
              .formatted(
                  adapterId,
                  propertyKey(adapterId, "max-jobs-active"),
                  maxJobsActive,
                  propertyKey(adapterId, "worker-threads"),
                  model.slots(),
                  model.slots() - maxJobsActive,
                  model.slots(),
                  JOBS_PER_SLOT,
                  CLIENT_MAX_JOBS_ACTIVE));
    }
    return maxJobsActive;

  }

  /**
   * The renewal window for the open asynchronous tasks of this adapter instance. It is the
   * configured value, or else {@link #DEFAULT_ASYNC_TASK_LOCK_RENEWAL}.
   *
   * @return The window a dormant job's lock is extended by
   */
  public Duration resolvedAsyncTaskLockRenewal() {

    return asyncTaskLockRenewal != null
        ? asyncTaskLockRenewal
        : DEFAULT_ASYNC_TASK_LOCK_RENEWAL;

  }

  /**
   * The backoff of a failed job at adapter level. It is the configured value, or else
   * {@link Camunda8RetryBackoffResolver#DEFAULT_RETRY_BACKOFF}.
   *
   * @return The backoff, never <code>null</code>
   */
  public Duration resolvedRetryBackoff() {

    return retryBackoff != null
        ? retryBackoff
        : Camunda8RetryBackoffResolver.DEFAULT_RETRY_BACKOFF;

  }

  /**
   * Validates how long the cluster keeps a message this adapter publishes. It runs AT
   * STARTUP, because at runtime a wrong value fails silently, in both directions. If it is
   * too short, a message published before its subscription exists is gone. If it is too
   * long, a second legitimate correlation of the same id is dropped without a word.
   * <p>
   * Only the ADAPTER level is checked here, the same limit {@link #validateRetryBackoff}
   * has. The deeper levels live in the configuration overlay of the platform, and this class
   * cannot see them. A typo there shows when that message is published. That is the price
   * of not walking the overlay twice, once in each platform module.
   *
   * @param adapterId The adapter id
   * @throws IllegalStateException If the time-to-live is zero or negative
   */
  public void validateMessageTimeToLive(
      final String adapterId) {

    if ((messageTimeToLive == null) || (!messageTimeToLive.isZero() && !messageTimeToLive.isNegative())) {
      return;
    }
    throw new IllegalStateException(
        """
            Camunda 8 adapter '%s' has '%s: %s'. That number is how long the cluster keeps a published \
            message, so zero or less means every message this adapter publishes is dropped the moment \
            it arrives - a workflow waiting for one would wait forever. Give it a duration, or remove \
            the property to keep the client's own default of one hour. The number does two jobs which \
            pull apart: it buffers a message whose subscription does not exist yet, which wants it \
            large, and it is the window a message id deduplicates in, which wants it small. Where one \
            number cannot serve both, set it per workflow module, workflow or message \
            ('vanillabp.workflow-modules.<m>.workflows.<w>.messages.<message>.adapters.%s.message-time-to-live')."""
            .formatted(
                adapterId,
                propertyKey(adapterId, "message-time-to-live"),
                messageTimeToLive,
                adapterId));

  }

  /**
   * Validates the backoff of a failed job. It runs AT STARTUP, because the backoff only
   * matters long after the boot, when something has already gone wrong.
   *
   * @param adapterId The adapter id
   * @throws IllegalStateException If the backoff is negative
   */
  public void validateRetryBackoff(
      final String adapterId) {

    if ((retryBackoff == null) || !retryBackoff.isNegative()) {
      return;
    }
    throw new IllegalStateException(
        """
            Camunda 8 adapter '%s' has '%s: %s'. The backoff is how long the cluster waits before it hands \
            a failed job out again, so it cannot be negative. Give it a duration (the default is %s), or \
            'PT0S' to have the job handed out again as fast as the cluster can - which is what burns a \
            job's retries while the cause of the failure has not passed yet."""
            .formatted(
                adapterId,
                propertyKey(adapterId, "retry-backoff"),
                retryBackoff,
                Camunda8RetryBackoffResolver.DEFAULT_RETRY_BACKOFF_ISO));

  }

  /**
   * How long the health check of this adapter instance waits for the cluster. It is the
   * configured value, or else {@link #DEFAULT_HEALTH_TIMEOUT}.
   *
   * @return The timeout, never <code>null</code>
   */
  public Duration resolvedHealthTimeout() {

    return healthTimeout != null
        ? healthTimeout
        : DEFAULT_HEALTH_TIMEOUT;

  }

  /**
   * Validates the health timeout of this adapter instance. It runs AT STARTUP. People read
   * a health endpoint when something is already wrong, so it must not be a second problem.
   *
   * @param adapterId The adapter id
   * @throws IllegalStateException If the timeout is negative
   */
  public void validateHealthTimeout(
      final String adapterId) {

    if ((healthTimeout == null) || !healthTimeout.isNegative()) {
      return;
    }
    throw new IllegalStateException(
        """
            Camunda 8 adapter '%s' has '%s: %s'. The timeout is how long the health check waits for the \
            cluster to answer, so it cannot be negative. Give it a duration (the default is %s), or \
            'PT0S' to switch the check off."""
            .formatted(
                adapterId,
                propertyKey(adapterId, "health-timeout"),
                healthTimeout,
                DEFAULT_HEALTH_TIMEOUT_ISO));

  }

  /**
   * The client's own default for {@link #requestTimeout}. It is also the window in which an
   * activation request waits at the cluster. It is named here because the shutdown has to
   * outlast it. And the check has to know it even where nothing is configured.
   */
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /**
   * How long a request of this adapter instance may take. For an activation request this
   * is the long-polling window. It is the configured value, or else
   * {@link #DEFAULT_REQUEST_TIMEOUT}.
   *
   * @return The request timeout, never <code>null</code>
   */
  public Duration resolvedRequestTimeout() {

    return requestTimeout != null
        ? requestTimeout
        : DEFAULT_REQUEST_TIMEOUT;

  }

  /**
   * The shortest request timeout this adapter reports as usable. Below it, two things run
   * out of time even against a perfectly healthy cluster: the deployment of the models of a
   * workflow module, and a search over a busy cluster. And the activation request stops
   * being a long poll. The worker then asks again every <code>poll-interval</code>, and it
   * does not wait at the cluster.
   */
  public static final Duration SHORTEST_USABLE_REQUEST_TIMEOUT = Duration.ofSeconds(1);

  /**
   * Validates how long a request of this adapter instance may take - AT STARTUP, because
   * the value is the deadline of EVERY command the adapter sends and a value which is too
   * short looks like a network problem rather than like configuration.
   *
   * @param adapterId The adapter id
   * @param warnLogger Sink for the guiding warning
   * @throws IllegalStateException If the timeout is negative or zero
   */
  public void validateRequestTimeout(
      final String adapterId,
      final Consumer<String> warnLogger) {

    if (requestTimeout == null) {
      return;
    }
    if (requestTimeout.isNegative() || requestTimeout.isZero()) {
      throw new IllegalStateException(
          """
              Camunda 8 adapter '%s' has '%s: %s'. The timeout is the deadline of every request this \
              adapter sends, so it has to be a positive duration - there is nothing a request without \
              time could do. Give it a duration; the default is %s."""
              .formatted(
                  adapterId,
                  propertyKey(adapterId, "request-timeout"),
                  requestTimeout,
                  DEFAULT_REQUEST_TIMEOUT));
    }
    if (requestTimeout.compareTo(SHORTEST_USABLE_REQUEST_TIMEOUT) >= 0) {
      return;
    }
    warnLogger.accept(
        """
            Camunda 8 adapter '%s' has '%s: %s'. That value is the deadline of every request this \
            adapter sends - the deployment of a workflow module's models and every search over the \
            cluster included - so with it a healthy cluster answers too late and the failure reads \
            like a network problem. It is also the window an activation request waits at the cluster: \
            below a second the workers stop waiting there and ask again every '%s' instead. Raise it \
            to at least %s; the default is %s."""
            .formatted(
                adapterId,
                propertyKey(adapterId, "request-timeout"),
                requestTimeout,
                propertyKey(adapterId, "poll-interval"),
                SHORTEST_USABLE_REQUEST_TIMEOUT,
                DEFAULT_REQUEST_TIMEOUT));

  }

  /**
   * How long the start of this adapter instance waits for its cluster. It is the configured
   * value, or else {@link #DEFAULT_STARTUP_WAIT}.
   *
   * @return The wait, never <code>null</code>
   */
  public Duration resolvedStartupWait() {

    return startupWait != null
        ? startupWait
        : DEFAULT_STARTUP_WAIT;

  }

  /**
   * Validates how long the start of this adapter instance waits for its cluster. It runs AT
   * STARTUP, which is also the only moment the value is read.
   *
   * @param adapterId The adapter id
   * @throws IllegalStateException If the wait is negative
   */
  public void validateStartupWait(
      final String adapterId) {

    if ((startupWait == null) || !startupWait.isNegative()) {
      return;
    }
    throw new IllegalStateException(
        """
            Camunda 8 adapter '%s' has '%s: %s'. The wait is how long the start gives a cluster which \
            is not answering yet, so it cannot be negative. Give it a duration (the default is %s), or \
            'PT0S' to have the start fail on the first round it cannot make."""
            .formatted(
                adapterId,
                propertyKey(adapterId, "startup-wait"),
                startupWait,
                DEFAULT_STARTUP_WAIT_ISO));

  }

  /**
   * Where this adapter instance talks to, as an operator would write it down. That is the
   * SaaS cluster and its region, or the address of a self-managed gateway.
   * <p>
   * Every message about reaching the cluster carries it. Somebody should be able to act on
   * such a message without first opening the configuration of the application. A
   * self-managed adapter is described by the address of the protocol its client talks.
   * Where none is configured, that is the address of the local cluster.
   *
   * @return The address, or <code>null</code> for a SaaS adapter without cluster and region
   */
  public String describeAddress() {

    if (mode == Mode.SAAS) {
      return (clusterId == null) && (region == null)
          ? null
          : "cluster '%s' in region '%s'".formatted(clusterId, region);
    }
    return preferRestOverGrpc
        ? restAddressInUse()
        : grpcAddressInUse();

  }

  /**
   * How long the shutdown of this adapter instance waits for the handlers it has in flight.
   * It is the configured value, or else {@link #DEFAULT_SHUTDOWN_GRACE}.
   *
   * @return The grace period, never <code>null</code>
   */
  public Duration resolvedShutdownGrace() {

    return shutdownGrace != null
        ? shutdownGrace
        : DEFAULT_SHUTDOWN_GRACE;

  }

  /**
   * How long VanillaBP waits for a workflow this cluster holds to become findable by the
   * query API. Ten seconds is generous for a healthy exporter. It is still short enough to
   * stay inside the transaction of the caller, which stays open while VanillaBP waits.
   */
  public static final Duration DEFAULT_WORKFLOW_VISIBILITY_TIMEOUT = Duration.ofSeconds(10);

  /**
   * How long VanillaBP waits for the read model to report the END of a workflow the engine
   * no longer holds. Three seconds is the measurement of the slowest line, rounded up. The
   * engine answers the case behind it, so nothing has to be waited out there, see
   * {@link #endedWorkflowVisibilityWindow()}.
   */
  public static final Duration DEFAULT_ENDED_WORKFLOW_VISIBILITY_TIMEOUT = Duration.ofSeconds(3);

  /**
   * How long a read of this cluster may get an answer which the exporter has not caught up
   * with yet. It is the configured {@code workflow-visibility-timeout}, or else
   * {@link #DEFAULT_WORKFLOW_VISIBILITY_TIMEOUT}.
   * <p>
   * Camunda 8 answers every search from a read model. An exporter feeds that model
   * asynchronously. So something written a moment ago cannot be found yet. For this window,
   * anybody who reads THIS cluster may take "not there" as "not there yet". After it, "not
   * there" is the answer. Zero or less switches the waiting off.
   * <p>
   * It is public because an extension reads the same cluster and meets the same lag. The
   * number belongs to the cluster and not to the reader. An operator who raises it for a
   * slow exporter raises it once. A reader with a window of its own in the code keeps
   * dropping what the adapter now waits for.
   *
   * @return The window, never <code>null</code>
   */
  public Duration workflowVisibilityWindow() {

    return workflowVisibilityTimeout != null
        ? workflowVisibilityTimeout
        : DEFAULT_WORKFLOW_VISIBILITY_TIMEOUT;

  }

  /**
   * How long VanillaBP waits for the END of a workflow to become findable by the query API,
   * once the engine has said it does not hold that workflow any more.
   * <p>
   * Measured in September 2026 on all three lines. After an instance was canceled, the read
   * model needed 176, 199 and 445 ms on 8.10.0-alpha5, 255 ms on 8.9.19 and 2068 ms on
   * 8.8.37. Three seconds covers the slowest of them with room to spare. So it is ONE number
   * for every line, not one per line. The release line reaches the runtime for messages,
   * not for behaviour (see {@code Camunda8ReleaseLine}). And a cluster may be newer than
   * the line a build was compiled against.
   * <p>
   * Why it is not zero: a workflow which starts and ends within a few milliseconds is gone
   * from the engine before the read model has heard of it at all. The answer would then be
   * {@code UNKNOWN_TO_BPMS} for a workflow which really is {@code COMPLETED}. That answer
   * sends the next operation of a migration to the wrong BPMS.
   *
   * @return The window, never <code>null</code>
   */
  public Duration endedWorkflowVisibilityWindow() {

    return endedWorkflowVisibilityTimeout != null
        ? endedWorkflowVisibilityTimeout
        : DEFAULT_ENDED_WORKFLOW_VISIBILITY_TIMEOUT;

  }

  /**
   * Whether the business id an instance of this cluster carries is one this adapter wrote.
   * <p>
   * The probe which asks the engine whether it holds an instance reads it
   * ({@code Camunda8ProcessService#awarenessOfWorkflow}). If the answer is yes and the
   * release line has the command, the probe sends the business id assignment. Such an
   * instance refuses it with <code>409</code>, so it writes nothing at all. Everywhere else
   * the probe sends the process instance modification. That is refused just as cleanly, and
   * it needs neither 8.10 nor a business id.
   * <p>
   * The answer follows {@code aggregate-id-as-business-id} and the release line. The probe
   * asks this method and does not assume the answer, because the assignment has a trap. An
   * instance which carries NO business id ACCEPTS it. Then a probe has written into a field
   * which the application may have wanted for something else, and this cannot be undone.
   * Where this adapter writes the field itself, nothing is taken away. So the assignment is
   * sent there and nowhere else.
   * <p>
   * One case is left, on purpose. A workflow started BEFORE the key was switched on carries
   * no business id. So the first probe of it is accepted and writes one. It writes the value
   * the start would have written. That is why it is accepted and not guarded against.
   * Decision 35 in the repository's DECISIONS.md says why.
   *
   * @return Whether the business id of an instance is this adapter's to write
   */
  public boolean writesTheBusinessIdOfAnInstance() {

    return Camunda8BusinessId.supportedByThisLine() && aggregateIdAsBusinessId;

  }

  /**
   * The business id this adapter writes for a workflow aggregate, ready to be sent.
   * <p>
   * One place answers it, because two commands carry the value. The create of a workflow
   * writes it. On the 8.10 line, the probe of {@link Camunda8InstanceProbe} sends the same
   * value as a question. If the probe carried a different string than the create wrote, it
   * would be accepted where it should be refused. Then a question would become a change.
   *
   * @param workflowAggregateId The workflow aggregate's id
   * @return What to send, or <code>null</code> where this adapter writes none
   */
  public String businessIdOf(
      final Object workflowAggregateId) {

    if (!writesTheBusinessIdOfAnInstance() || (workflowAggregateId == null)) {
      return null;
    }
    final var id = String.valueOf(workflowAggregateId);
    if (id.isBlank()) {
      // The cluster answers "No businessId provided" for an empty string and for a single
      // blank. An aggregate with an id like that has bigger problems than how it shows.
      return null;
    }
    return id.length() > BUSINESS_ID_LIMIT
        ? id.substring(0, BUSINESS_ID_LIMIT)
        : id;

  }

  /**
   * Says what {@code probe-open-user-tasks} does here. It runs AT STARTUP. This key costs a
   * command per open user task. It fires a listener somebody modelled. And while it runs,
   * it holds the task against every other sender. A key with that much behind it should say
   * so once, and not be found later in the logs of a cluster.
   *
   * @param adapterId The adapter id
   * @param logger Sink for that line
   */
  public void validateProbeOpenUserTasks(
      final String adapterId,
      final Consumer<String> logger) {

    if (!probeOpenUserTasks) {
      return;
    }
    logger.accept(
        """
            Camunda 8 adapter '%s' asks the cluster about every open USER TASK of a workflow whenever \
            it looks at the other tasks of that workflow ('%s: true'). That is one command per user \
            task of an instance which is still running, and the command fires a modelled 'updating' \
            task listener although it changes nothing. While one of these updates runs, the task stands \
            in state UPDATING and the cluster refuses every command against it with 409. VanillaBP reads \
            that as 'the cluster has this task' and repeats, which is one reason a task list of yours \
            belongs on the ProcessService; one sending on the Camunda client has to repeat for itself. \
            A listener this application serves is closed by \
            the adapter without any method of yours running; an element whose 'updating' listener \
            belongs to a worker this application does not run is not asked about at all, because there \
            the probe would leave the task in state UPDATING for fifteen seconds. Switch the key off \
            where no user task of yours can be completed past VanillaBP - the 'canceling' listener \
            reports those anyway."""
            .formatted(adapterId, propertyKey(adapterId, "probe-open-user-tasks")));

  }

  /**
   * Says what {@code aggregate-id-as-business-id} does here. It runs AT STARTUP, once per
   * adapter id. Everything the key decides later happens per started workflow, and a line
   * per workflow would drown the log.
   * <p>
   * Two things are worth saying, and neither is a warning. On a release line without the
   * field, the key is accepted and nothing is sent. So one configuration can serve an
   * application on either line. And where the key is on, an aggregate id longer than the
   * limit of the cluster is CUT. People should know that before they read a cut id in
   * Operate and look for a defect.
   *
   * @param adapterId The adapter id
   * @param logger Sink for that line
   */
  public void validateAggregateIdAsBusinessId(
      final String adapterId,
      final Consumer<String> logger) {

    if (!aggregateIdAsBusinessId) {
      return;
    }
    if (!Camunda8BusinessId.supportedByThisLine()) {
      logger.accept(
          """
              Camunda 8 adapter '%s' has '%s: true', which has no effect on this release line: an \
              instance of its cluster carries no business id, and a create command carrying one is \
              refused with 400. The key is read on the 8.9 line and later, and it is kept here so one \
              configuration can serve an application on either line."""
              .formatted(adapterId, propertyKey(adapterId, "aggregate-id-as-business-id")));
      return;
    }
    logger.accept(
        """
            Camunda 8 adapter '%s' writes the workflow aggregate's id as the business id of every \
            workflow it starts ('%s: true'). It is there to be READ, in Operate and in the searches of \
            this cluster: VanillaBP finds a workflow by the process variable carrying that id and never \
            by this field. An aggregate id longer than %d characters is cut to that length, because the \
            cluster refuses a longer one and the start of a workflow is the worst place for a refusal. \
            A workflow somebody else started keeps the business id it has - this adapter writes the \
            field at creation and never assigns one afterwards."""
            .formatted(
                adapterId,
                propertyKey(adapterId, "aggregate-id-as-business-id"),
                BUSINESS_ID_LIMIT));

  }

  /**
   * Validates the shutdown grace of this adapter instance. It runs AT STARTUP, because the
   * grace matters when nobody is watching. A negative value is a typo and fails the boot. A
   * value which does not fit into the shutdown budget of the runtime is legitimate. But it
   * only works if that budget is raised too. So it gets a guiding warning, not a failure.
   * <p>
   * The two warnings read the grace in force, not the property. They compare it with other
   * values. A default can clash with one of those just as a written value can. For example,
   * a request timeout raised to thirty seconds outlasts the default grace of twenty. Only
   * the typo check is about what somebody wrote, and a default is never negative.
   *
   * @param adapterId The adapter id
   * @param warnLogger Sink for the guiding warning
   * @throws IllegalStateException If the grace is negative
   */
  public void validateShutdownGrace(
      final String adapterId,
      final Consumer<String> warnLogger) {

    final var grace = resolvedShutdownGrace();
    if (grace.isNegative()) {
      throw new IllegalStateException(
          """
              Camunda 8 adapter '%s' has '%s: %s'. The grace is how long a shutdown waits for the \
              handlers it has in flight, so it cannot be negative. Give it a duration (the default is \
              %s), or 'PT0S' to close the workers and the client without waiting at all."""
              .formatted(
                  adapterId,
                  propertyKey(adapterId, "shutdown-grace"),
                  grace,
                  DEFAULT_SHUTDOWN_GRACE_ISO));
    }
    // A warning about a value nobody wrote has to say where the value came from.
    final var whereTheGraceComesFrom = (shutdownGrace == null
        ? "runs on the default '%s: %s'"
        : "has '%s: %s'").formatted(propertyKey(adapterId, "shutdown-grace"), grace);
    if (!grace.isZero() && (grace.compareTo(resolvedRequestTimeout()) < 0)) {
      warnLogger.accept(
          """
              Camunda 8 adapter '%s' %s, which is shorter than '%s: %s'. An activation request \
              of a worker waits at the cluster for that long, closing the worker does not cancel it, and \
              a request which is still there when the client goes down keeps the first job created \
              afterwards until '%s' expires. The shutdown therefore waits for the cluster to release the \
              workers, and with this grace it gives up before that happens. Raise the grace above the \
              request timeout, or accept that a workflow started within seconds of a restart waits for \
              its lock."""
              .formatted(
                  adapterId,
                  whereTheGraceComesFrom,
                  propertyKey(adapterId, "request-timeout"),
                  resolvedRequestTimeout(),
                  propertyKey(adapterId, "job-timeout")));
    }
    if (grace.compareTo(PLATFORM_SHUTDOWN_BUDGET) < 0) {
      return;
    }
    warnLogger.accept(
        """
            Camunda 8 adapter '%s' %s, which reaches into the shutdown budget of the runtime \
            around it: 'spring.lifecycle.timeout-per-shutdown-phase' and Kubernetes' \
            'terminationGracePeriodSeconds' both default to %s. With this grace the application can be \
            killed while VanillaBP is still waiting for its handlers, which is the opposite of what the \
            grace is for. Raise both budgets above the grace, or lower the grace below them (the default \
            is %s)."""
            .formatted(
                adapterId,
                whereTheGraceComesFrom,
                PLATFORM_SHUTDOWN_BUDGET,
                DEFAULT_SHUTDOWN_GRACE_ISO));

  }

  /**
   * Validates that the application said whether it wants the jobs of this adapter leased.
   * It runs AT STARTUP, because a lease cannot be taken back from a job once it was handed
   * out.
   * <p>
   * On a release line whose client has no lease, the key is accepted and ignored. One log
   * line says so. An application moves between lines with one configuration. Refusing a key
   * there would be the one thing that makes such a move hurt.
   * <p>
   * Until Camunda fixes its gateway, the message advises <code>do-not-use</code>. A lease makes
   * a job which the gateway could not hand out wait one job timeout. Why the adapter advises and
   * does not decide is decision 88 in the repository's DECISIONS.md.
   *
   * @param adapterId The adapter id
   * @param logger Sink for the line which says the key does nothing here. It is not a
   *          warning. The key is right where it stands. Because it is kept on a line which
   *          has no lease, one configuration can serve an application on either line
   * @throws IllegalStateException On a line which can lease, where the key is not set
   */
  public void validateJobLease(
      final String adapterId,
      final Consumer<String> logger) {

    if (!Camunda8JobLease.supportedByThisLine()) {
      if (jobLease != null) {
        logger.accept(
            """
                Camunda 8 adapter '%s' has '%s: %s', which has no effect on this release line: its \
                cluster and its client know no lease, so every job is activated without one. The key is \
                read on the 8.10 line and later, and it is kept here so one configuration can serve an \
                application on either line."""
                .formatted(adapterId, propertyKey(adapterId, "job-lease"), valueOf(jobLease)));
      }
      return;
    }
    if (jobLease != null) {
      return;
    }
    throw new IllegalStateException(
        """
            Camunda 8 adapter '%s' does not say whether it leases the activation of its jobs - set '%s' \
            to '%s' or to '%s'.
            A lease makes the cluster take the answer of the CURRENT activation only. Where the lock of \
            a job expires while the business method is still running, the cluster hands the job out \
            again and the method runs a second time; with a lease the older run is refused and the \
            workflow continues with what the newer one wrote, and without a lease the older run wins \
            and overwrites it.
            It has to be decided rather than defaulted because it cannot be taken back: the cluster \
            never removes a lease from a job, a worker of the same job type which does not lease never \
            sees a leased job again, and an application rolled back onto an older line therefore leaves \
            the jobs it leased standing.
            Until Camunda fixes its gateway, a lease also costs time. Sometimes the cluster activates a \
            job for a long poll which ended in the same moment. Without a lease the gateway gives that job \
            back at once. With a lease it cannot, and the job waits one '%s' for nobody. So '%s' is the \
            safer choice for now. Switching to '%s' later costs nothing, only the way back does.
            Write '%s: %s' to switch it on, or '%s: %s' to keep what every line before this one does."""
            .formatted(
                adapterId,
                propertyKey(adapterId, "job-lease"),
                valueOf(JobLease.USE),
                valueOf(JobLease.DO_NOT_USE),
                propertyKey(adapterId, "job-timeout"),
                valueOf(JobLease.DO_NOT_USE),
                valueOf(JobLease.USE),
                propertyKey(adapterId, "job-lease"),
                valueOf(JobLease.USE),
                propertyKey(adapterId, "job-lease"),
                valueOf(JobLease.DO_NOT_USE)));

  }

  /**
   * An enum value the way it is written in a configuration file.
   */
  private static String valueOf(
      final JobLease value) {

    return value
        .name()
        .toLowerCase()
        .replace('_', '-');

  }

  /**
   * Whether the jobs this adapter holds from the activation to the answer are activated
   * with a lease. It is false wherever the release line has no lease, whatever is
   * configured.
   *
   * @return Whether to lease
   */
  public boolean leasesItsJobs() {

    return Camunda8JobLease.supportedByThisLine() && (jobLease == JobLease.USE);

  }

  /**
   * Validates how this adapter instance keeps an open asynchronous task alive. It runs AT
   * STARTUP, for every configured adapter id. Two things can be wrong here, and both are
   * silent at runtime:
   * <ul>
   * <li>The removed key <code>async-task-timeout</code> is still configured. It used to be
   * a horizon of days. Now it is a renewal window. A value meant as the one would be a
   * defect as the other.</li>
   * <li>The window is not shorter than <code>vanillabp.delivery.retention</code>. The
   * record of the delivery answers the redelivery which renews the lock. If the window
   * outlives the retention, the method of the application runs a second time. That is
   * exactly the defect this window is there to fix. It is the DELIVERY retention, not the
   * outbox one. Those two were one property until they were split, and this check was
   * always about the delivery half.</li>
   * </ul>
   *
   * @param adapterId The adapter id
   * @param deliveryRetention How long a delivery record is kept
   *          (<code>vanillabp.delivery.retention</code>, which follows
   *          <code>vanillabp.outbox.retention</code> where it is not set)
   * @throws IllegalStateException If the removed key is configured or the window does not
   *           fit below the retention, naming both properties and both values
   */
  public void validateAsyncTaskLockRenewal(
      final String adapterId,
      final Duration deliveryRetention) {

    if (asyncTaskTimeout != null) {
      throw new IllegalStateException(
          """
              Camunda 8 adapter '%s' configures '%s', which does not exist any more. The property was a \
              HORIZON of 14 days a dormant job's lock was extended to once, and it outlived the records \
              which keep a redelivery from running the @WorkflowTask method again - every asynchronous \
              task open longer than the horizon was processed twice. What the adapter does now is RENEW \
              the lock in a window, driven by the cluster's own redelivery. Rename the property to '%s' \
              and give it a window rather than a horizon; the default is %s."""
              .formatted(
                  adapterId,
                  propertyKey(adapterId, "async-task-timeout"),
                  propertyKey(adapterId, "async-task-lock-renewal"),
                  DEFAULT_ASYNC_TASK_LOCK_RENEWAL_ISO));
    }

    if (deliveryRetention == null) {
      return;
    }
    final var renewal = resolvedAsyncTaskLockRenewal();
    if (renewal.compareTo(deliveryRetention) < 0) {
      return;
    }
    throw new IllegalStateException(
        """
            Camunda 8 adapter '%s' has '%s: %s', which is not shorter than \
            'vanillabp.delivery.retention: %s'. The lock of a task left open by a @TaskId handler is renewed \
            whenever the cluster hands the job out again, and the redelivery is answered from the record \
            VanillaBP wrote when the handler ran. A record deleted before the next renewal therefore lets the \
            @WorkflowTask method run a second time. Choose a window well below the retention - at most a \
            tenth of it, %s or less here - or raise 'vanillabp.delivery.retention' (which follows \
            'vanillabp.outbox.retention' where it is not set itself)."""
            .formatted(
                adapterId,
                propertyKey(adapterId, "async-task-lock-renewal"),
                renewal,
                deliveryRetention,
                deliveryRetention.dividedBy(10)));

  }

  /**
   * Validates everything about the way this adapter instance runs its workers. That is the
   * execution model, its bound and <code>max-jobs-active</code>. It is called AT STARTUP for
   * every configured adapter id, whether the connection configuration is complete or not. A
   * number which cannot work is a typo, and a typo is worth stopping the boot for.
   *
   * @param adapterId The adapter id
   * @throws IllegalStateException If a value is not usable, naming the property and the
   *           way out
   */
  public void validateWorkerConfiguration(
      final String adapterId) {

    resolvedMaxJobsActive(adapterId);

  }

  /**
   * Validates how this adapter instance authenticates. It runs AT STARTUP, for every
   * configured adapter id, whether the connection configuration is complete or not.
   * Credentials which cannot be built are a defect of their own.
   *
   * @param adapterId The adapter id
   * @throws IllegalStateException If the authentication block cannot be used, naming the
   *           method, the missing keys and the YAML which completes them
   */
  public void validateAuthentication(
      final String adapterId) {

    auth.validate(adapterId, mode);

  }

  /**
   * Builds the full property key of a connection property of the given adapter
   * instance (e.g. <code>vanillabp.adapters.c8.rest-address</code>).
   *
   * @param adapterId The adapter ID
   * @param key The property key relative to the adapter's section
   * @return The full property key
   */
  public static String propertyKey(
      final String adapterId,
      final String key) {

    return "%s.%s.%s".formatted(CONFIGURATION_PREFIX, adapterId, key);

  }

  private static boolean isBlank(
      final String value) {

    return (value == null) || value.isBlank();

  }

}
