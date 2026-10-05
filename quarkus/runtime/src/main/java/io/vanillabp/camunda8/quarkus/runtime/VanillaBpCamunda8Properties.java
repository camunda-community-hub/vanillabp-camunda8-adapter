package io.vanillabp.camunda8.quarkus.runtime;

import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8AuthConfiguration;
import io.vanillabp.camunda8.wiring.Camunda8AllowConnectorsResolver;
import io.vanillabp.camunda8.wiring.Camunda8AllowListenersResolver;
import io.vanillabp.camunda8.wiring.Camunda8Connectors;
import io.vanillabp.camunda8.wiring.Camunda8FetchVariables;
import io.vanillabp.camunda8.wiring.Camunda8FetchVariablesResolver;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.camunda8.wiring.Camunda8Listeners;
import io.vanillabp.camunda8.wiring.Camunda8RetryBackoffResolver;

/**
 * The Camunda 8 adapter's OVERLAY of the shared <code>vanillabp.*</code> configuration
 * tree: the adapter's connection settings live at the canonical per-adapter location
 * <code>vanillabp.adapters.&lt;id&gt;.*</code> (see
 * {@link Camunda8AdapterConfiguration}). A second RUN_TIME {@code @ConfigMapping} over
 * the same prefix coexists with the platform's mapping; since the platform dropped the
 * blanket {@code withMappingIgnore}, this overlay doubles as the unknown-key
 * validation coverage for the adapter's keys.
 * <p>
 * The adapter-id set is NEVER derived from this overlay map - it always comes from the
 * platform's core properties ({@code adapterTypes()} filtered by type
 * {@code camunda8}); the overlay is a per-known-id lookup only.
 */
@StaticInitSafe
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface VanillaBpCamunda8Properties {

  /**
   * The adapter sections of the shared tree, keyed by adapter ID - only the
   * Camunda 8 connection keys are modeled here.
   *
   * @return The sections, keyed by adapter id, empty where the application wrote none
   */
  Map<String, Camunda8AdapterKeys> adapters();

  /**
   * The workflow-module sections of the shared tree - the overlay mirrors the
   * levels of the most-specific-wins resolution of scope-specific adapter keys
   * (task &gt; workflow &gt; workflow-module &gt; adapter), currently:
   * <code>job-timeout</code>, <code>retry-backoff</code> and
   * <code>fetch-variables</code>. <code>tenant-id</code> is here as well, with the workflow
   * module as its ONLY level below the adapter: a tenant id is an attribute of the
   * deployment, and this adapter deploys once per workflow module.
   *
   * @return The workflow-module sections, keyed by workflow module ID
   */
  Map<String, ModuleOverlay> workflowModules();

  /**
   * The Camunda 8 tenant configured for one workflow module of one adapter id: the module's
   * own name where it has one, the adapter's otherwise. What the mode then makes of it is the
   * adapter's business.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module ID
   * @return The name and the key it was read from, or <code>null</code> where nothing
   *         configured one
   */
  default io.vanillabp.camunda8.wiring.Camunda8ConfiguredTenant configuredTenantFor(
      final String adapterId,
      final String workflowModuleId) {

    final var module = workflowModuleId != null
        ? workflowModules().get(workflowModuleId)
        : null;
    final var perWorkflowModule = module != null
        ? module.adapters().get(adapterId)
        : null;
    final var adapter = adapters().get(adapterId);
    return io.vanillabp.camunda8.wiring.Camunda8ConfiguredTenant
        .firstConfigured(
            adapterId,
            workflowModuleId,
            perWorkflowModule != null
                ? perWorkflowModule
                    .tenantId()
                    .orElse(null)
                : null,
            adapter != null
                ? adapter
                    .tenantId()
                    .orElse(null)
                : null);

  }

  /**
   * Resolves the job timeout for a task with most-specific-wins semantics across
   * the four levels; falls back to the adapter-level value and finally the
   * default.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinition The task definition (job type)
   * @param adapterId The adapter ID
   * @return The most specific configured job timeout or the default
   */
  default Duration jobTimeoutFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    final var scoped = scopedKeysMostSpecificFirst(workflowModuleId, bpmnProcessId, taskDefinition, adapterId)
        .map(Camunda8ScopedKeys::jobTimeout)
        .flatMap(Optional::stream)
        .findFirst();
    if (scoped.isPresent()) {
      return scoped.get();
    }
    final var adapter = adapters().get(adapterId);
    return adapter != null
        ? adapter
            .jobTimeout()
            .orElse(Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT)
        : Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT;

  }

  /**
   * Resolves the backoff of a FAILED job with the same most-specific-wins semantics;
   * falls back to the adapter-level value and finally the default of ten seconds.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinition The task definition (job type)
   * @param adapterId The adapter ID
   * @return The most specific configured backoff or the default
   */
  default Duration retryBackoffFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    return configuredRetryBackoffFor(workflowModuleId, bpmnProcessId, taskDefinition, adapterId)
        .duration();

  }

  /**
   * The same answer, plus the one thing a BPMN model can argue with: whether the TASK
   * level is where the value comes from. A task header
   * {@value io.vanillabp.camunda8.wiring.Camunda8RetryBackoffHeader#HEADER_NAME} says as
   * much about one task as that level does and beats every level above it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinition The task definition (job type)
   * @param adapterId The adapter ID
   * @return The most specific configured backoff and the level it was found at
   */
  default Camunda8RetryBackoffResolver.Configured configuredRetryBackoffFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    final var perTask = taskLevelKeys(workflowModuleId, bpmnProcessId, taskDefinition, adapterId);
    if ((perTask != null) && perTask.retryBackoff().isPresent()) {
      return new Camunda8RetryBackoffResolver.Configured(
          perTask.retryBackoff().get(), true);
    }
    final var scoped = scopedKeysMostSpecificFirst(workflowModuleId, bpmnProcessId, taskDefinition, adapterId)
        .map(Camunda8ScopedKeys::retryBackoff)
        .flatMap(Optional::stream)
        .findFirst();
    if (scoped.isPresent()) {
      return new Camunda8RetryBackoffResolver.Configured(scoped.get(), false);
    }
    final var adapter = adapters().get(adapterId);
    return new Camunda8RetryBackoffResolver.Configured(
        adapter != null
            ? adapter
                .retryBackoff()
                .orElse(Camunda8RetryBackoffResolver.DEFAULT_RETRY_BACKOFF)
            : Camunda8RetryBackoffResolver.DEFAULT_RETRY_BACKOFF, false);

  }

  /**
   * The <code>adapters.&lt;id&gt;</code> section of the TASK level alone, or
   * <code>null</code> where the configuration says nothing about this task.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinition The task definition (job type)
   * @param adapterId The adapter ID
   * @return The section or <code>null</code>
   */
  private Camunda8ScopedKeys taskLevelKeys(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules().get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.workflows().get(bpmnProcessId)
        : null;
    final var task = (workflow != null) && (taskDefinition != null)
        ? workflow.tasks().get(taskDefinition)
        : null;
    return task != null
        ? task.adapters().get(adapterId)
        : null;

  }

  /**
   * Resolves whether a worker fetches the DERIVED variables or all of them with the same
   * most-specific-wins semantics; falls back to the adapter-level value and finally the
   * default {@code derived}.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinition The task definition (job type)
   * @param adapterId The adapter ID
   * @return The most specific configured mode or the default
   */
  default Camunda8FetchVariables.Mode fetchVariablesFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    final var scoped = scopedKeysMostSpecificFirst(workflowModuleId, bpmnProcessId, taskDefinition, adapterId)
        .map(Camunda8ScopedKeys::fetchVariables)
        .flatMap(Optional::stream)
        .findFirst();
    if (scoped.isPresent()) {
      return scoped.get();
    }
    final var adapter = adapters().get(adapterId);
    return adapter != null
        ? adapter
            .fetchVariables()
            .orElse(Camunda8FetchVariablesResolver.DEFAULT_FETCH_VARIABLES)
        : Camunda8FetchVariablesResolver.DEFAULT_FETCH_VARIABLES;

  }

  /**
   * Resolves whether the listeners somebody modelled are served, over the three levels
   * <code>allow-connectors</code> is read at and with the same most-specific-wins rule in
   * both directions.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param adapterId The adapter ID
   * @return The most specific configured setting together with the key it stands in
   */
  default Camunda8AllowListenersResolver.Setting allowListenersFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules().get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.workflows().get(bpmnProcessId)
        : null;
    final var perWorkflow = workflow != null
        ? workflow.adapters().get(adapterId)
        : null;
    if ((perWorkflow != null) && perWorkflow.allowListeners().isPresent()) {
      return new Camunda8AllowListenersResolver.Setting(
          perWorkflow.allowListeners().get(), "vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.%s"
              .formatted(
                  workflowModuleId, bpmnProcessId, adapterId, Camunda8Listeners.ALLOW_LISTENERS_KEY));
    }
    final var perModule = module != null
        ? module.adapters().get(adapterId)
        : null;
    if ((perModule != null) && perModule.allowListeners().isPresent()) {
      return new Camunda8AllowListenersResolver.Setting(
          perModule.allowListeners().get(), "vanillabp.workflow-modules.%s.adapters.%s.%s"
              .formatted(workflowModuleId, adapterId, Camunda8Listeners.ALLOW_LISTENERS_KEY));
    }
    final var adapter = adapters().get(adapterId);
    if ((adapter != null) && adapter.allowListeners().orElse(Boolean.FALSE)) {
      return new Camunda8AllowListenersResolver.Setting(
          true, Camunda8Listeners.propertyKeyOf(adapterId));
    }
    return Camunda8AllowListenersResolver.Setting.NOTHING_CONFIGURED;

  }

  /**
   * Every <code>allow-listeners</code> this configuration puts at TASK level, fully spelled
   * out - the level which does not resolve this key.
   *
   * @param adapterId The adapter ID
   * @return The keys found
   */
  default List<String> allowListenersKeysAtTaskLevel(
      final String adapterId) {

    return workflowModules()
        .entrySet()
        .stream()
        .flatMap(module -> module
            .getValue()
            .workflows()
            .entrySet()
            .stream()
            .flatMap(workflow -> workflow
                .getValue()
                .tasks()
                .entrySet()
                .stream()
                .filter(task -> {
                  final var keys = task.getValue().adapters().get(adapterId);
                  return (keys != null) && keys.allowListeners().isPresent();
                })
                .map(task -> "vanillabp.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.%s"
                    .formatted(
                        module.getKey(), workflow.getKey(), task.getKey(), adapterId,
                        Camunda8Listeners.ALLOW_LISTENERS_KEY))))
        .toList();

  }

  /**
   * Resolves the time-to-live of a published message with most-specific-wins semantics,
   * where the MOST specific level is the message rather than a task; falls back to the
   * adapter-level value and finally to <code>null</code>, which leaves the command alone
   * and lets the client's own default apply.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param messageName The BPMN message name as the application wrote it
   * @param adapterId The adapter ID
   * @return The most specific configured time-to-live or <code>null</code>
   */
  default Duration messageTimeToLiveFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String messageName,
      final String adapterId) {

    final var scoped = messageScopedKeysMostSpecificFirst(
        workflowModuleId, bpmnProcessId, messageName, adapterId)
        .map(Camunda8ScopedKeys::messageTimeToLive)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .findFirst();
    if (scoped.isPresent()) {
      return scoped.get();
    }
    final var adapter = adapters().get(adapterId);
    return (adapter == null) || adapter.messageTimeToLive().isEmpty()
        ? null
        : adapter.messageTimeToLive().get();

  }

  /**
   * Resolves whether an element built from an element template is left to the runtime which
   * owns it, over THREE levels rather than four: workflow, workflow module, adapter, the
   * most specific configured value winning in both directions. Which is the deliberate
   * difference to version 1, whose primitive booleans let a more specific level turn the
   * flag on and never off.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param adapterId The adapter ID
   * @return The most specific configured setting together with the key it stands in
   */
  default Camunda8AllowConnectorsResolver.Setting allowConnectorsFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules().get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.workflows().get(bpmnProcessId)
        : null;
    final var perWorkflow = workflow != null
        ? workflow.adapters().get(adapterId)
        : null;
    if ((perWorkflow != null) && perWorkflow.allowConnectors().isPresent()) {
      return new Camunda8AllowConnectorsResolver.Setting(
          perWorkflow.allowConnectors().get(), "vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.%s"
              .formatted(
                  workflowModuleId, bpmnProcessId, adapterId, Camunda8Connectors.ALLOW_CONNECTORS_KEY));
    }
    final var perModule = module != null
        ? module.adapters().get(adapterId)
        : null;
    if ((perModule != null) && perModule.allowConnectors().isPresent()) {
      return new Camunda8AllowConnectorsResolver.Setting(
          perModule.allowConnectors().get(), "vanillabp.workflow-modules.%s.adapters.%s.%s"
              .formatted(workflowModuleId, adapterId, Camunda8Connectors.ALLOW_CONNECTORS_KEY));
    }
    final var adapter = adapters().get(adapterId);
    if ((adapter != null) && adapter.allowConnectors().orElse(Boolean.FALSE)) {
      return new Camunda8AllowConnectorsResolver.Setting(
          true, Camunda8Connectors.propertyKeyOf(adapterId));
    }
    return Camunda8AllowConnectorsResolver.Setting.NOTHING_CONFIGURED;

  }

  /**
   * Every <code>allow-connectors</code> this configuration puts at TASK level, fully
   * spelled out - the level which does not resolve this key, and where saying so is the
   * only thing the boot can do about it.
   *
   * @param adapterId The adapter ID
   * @return The keys found
   */
  default List<String> allowConnectorsKeysAtTaskLevel(
      final String adapterId) {

    return workflowModules()
        .entrySet()
        .stream()
        .flatMap(module -> module
            .getValue()
            .workflows()
            .entrySet()
            .stream()
            .flatMap(workflow -> workflow
                .getValue()
                .tasks()
                .entrySet()
                .stream()
                .filter(task -> {
                  final var keys = task.getValue().adapters().get(adapterId);
                  return (keys != null) && keys.allowConnectors().isPresent();
                })
                .map(task -> "vanillabp.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.%s"
                    .formatted(
                        module.getKey(), workflow.getKey(), task.getKey(), adapterId,
                        Camunda8Connectors.ALLOW_CONNECTORS_KEY))))
        .toList();

  }

  /**
   * The <code>adapters.&lt;id&gt;</code> sections of the three levels below the adapter for
   * a MESSAGE, most specific first. Separate from
   * {@link #scopedKeysMostSpecificFirst} because the most specific level is a different
   * map: a message is not a task, and giving it the task level would make an override
   * meant for one apply to the other.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param messageName The BPMN message name
   * @param adapterId The adapter ID
   * @return The sections which exist, most specific first
   */
  private Stream<Camunda8ScopedKeys> messageScopedKeysMostSpecificFirst(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String messageName,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules().get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.workflows().get(bpmnProcessId)
        : null;
    final var message = (workflow != null) && (messageName != null)
        ? workflow.messages().get(messageName)
        : null;

    final var levelsMostSpecificFirst = new LinkedList<Map<String, ? extends Camunda8ScopedKeys>>();
    if (message != null) {
      levelsMostSpecificFirst.add(message.adapters());
    }
    if (workflow != null) {
      levelsMostSpecificFirst.add(workflow.adapters());
    }
    if (module != null) {
      levelsMostSpecificFirst.add(module.adapters());
    }
    return levelsMostSpecificFirst
        .stream()
        .<Camunda8ScopedKeys>map(level -> level.get(adapterId))
        .filter(Objects::nonNull);

  }

  /**
   * The <code>adapters.&lt;id&gt;</code> sections of the three levels below the adapter,
   * most specific first - what every scope-specific key is resolved through.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinition The task definition (job type)
   * @param adapterId The adapter ID
   * @return The sections which exist, most specific first
   */
  private Stream<Camunda8ScopedKeys> scopedKeysMostSpecificFirst(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules().get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.workflows().get(bpmnProcessId)
        : null;
    final var task = (workflow != null) && (taskDefinition != null)
        ? workflow.tasks().get(taskDefinition)
        : null;

    final var levelsMostSpecificFirst = new LinkedList<Map<String, ? extends Camunda8ScopedKeys>>();
    if (task != null) {
      levelsMostSpecificFirst.add(task.adapters());
    }
    if (workflow != null) {
      levelsMostSpecificFirst.add(workflow.adapters());
    }
    if (module != null) {
      levelsMostSpecificFirst.add(module.adapters());
    }
    return levelsMostSpecificFirst
        .stream()
        .<Camunda8ScopedKeys>map(level -> level.get(adapterId))
        .filter(Objects::nonNull);

  }

  /**
   * The Camunda 8 connection keys of one <code>vanillabp.adapters.&lt;id&gt;</code>
   * section (see {@link Camunda8AdapterConfiguration} for the semantics).
   */
  interface Camunda8AdapterKeys {

    /**
     * Connection mode: <code>self-managed</code> (default) or <code>saas</code>.
     *
     * @return The mode, empty where the application wrote none
     */
    Optional<Camunda8AdapterConfiguration.Mode> mode();

    /**
     * REST API address of a self-managed cluster (e.g.
     * <code>http://localhost:8080</code>).
     *
     * @return The address, empty where the application wrote none
     */
    Optional<String> restAddress();

    /**
     * gRPC address of a self-managed cluster (required when
     * <code>prefer-rest-over-grpc</code> is <code>false</code>).
     *
     * @return The address, empty where the application wrote none
     */
    Optional<String> grpcAddress();

    /**
     * Whether the client uses the REST API (recommended, default) or gRPC for its
     * commands.
     *
     * @return The choice, empty where the application wrote none
     */
    Optional<Boolean> preferRestOverGrpc();

    /**
     * The Camunda 8 multi-tenancy tenant (optional, both modes).
     *
     * @return The tenant id, empty where the application wrote none
     */
    Optional<String> tenantId();

    /**
     * SaaS cluster ID.
     *
     * @return The cluster id, empty where the application wrote none
     */
    Optional<String> clusterId();

    /**
     * SaaS region.
     *
     * @return The region, empty where the application wrote none
     */
    Optional<String> region();

    /**
     * SaaS OAuth client ID.
     *
     * @return The client id, empty where the application wrote none
     */
    Optional<String> clientId();

    /**
     * SaaS OAuth client secret.
     *
     * @return The client secret, empty where the application wrote none
     */
    Optional<String> clientSecret();

    /**
     * OPTIONAL acknowledgement that the application's identifiers are unique across
     * all of its workflow modules - it silences the WARN logged while the
     * name-clash-avoidance mode <code>none</code> applies. Default
     * <code>false</code>.
     *
     * @return Whether unscoped identifiers are accepted deliberately
     */
    Optional<Boolean> acceptUnscopedIdentifiers();

    /**
     * OPTIONAL: whether an element built from an element template is left to the runtime
     * which owns it, a Camunda connector in almost every case. Adapter-level base of a
     * resolution over three levels (workflow &gt; workflow-module &gt; adapter), default
     * <code>false</code>, see
     * {@link io.vanillabp.camunda8.wiring.Camunda8AllowConnectorsResolver}.
     *
     * @return Whether connectors are allowed for this adapter instance
     */
    Optional<Boolean> allowConnectors();

    /**
     * OPTIONAL: whether the listeners somebody MODELLED are served by
     * <code>@WorkflowTask</code> methods. Adapter-level base of a resolution over three levels
     * (workflow &gt; workflow-module &gt; adapter), default <code>false</code>, see
     * {@link io.vanillabp.camunda8.wiring.Camunda8AllowListenersResolver}.
     *
     * @return Whether the modelled listeners are served for this adapter instance
     */
    Optional<Boolean> allowListeners();

    /**
     * The worker's job timeout (lock duration) - adapter-level base of the
     * most-specific-wins resolution.
     *
     * @return The job timeout
     */
    Optional<Duration> jobTimeout();

    /**
     * How long the cluster waits before it hands a FAILED job out again - adapter-level
     * base of the most-specific-wins resolution. Default: ten seconds.
     *
     * @return The backoff of a failed job
     */
    Optional<Duration> retryBackoff();

    /**
     * Whether the workers of this adapter instance ask the cluster for the variables the
     * adapter derived or for all of them - adapter-level base of the most-specific-wins
     * resolution. Default: <code>derived</code>.
     *
     * @return The mode
     */
    Optional<Camunda8FetchVariables.Mode> fetchVariables();

    /**
     * The window the lock of a job left open by a <code>&#64;TaskId</code> handler is
     * renewed in.
     *
     * @return The renewal window
     */
    Optional<Duration> asyncTaskLockRenewal();

    /**
     * The key which used to carry a dormancy horizon of days. Bound only so the boot can
     * REJECT it with a message naming its successor.
     *
     * @return The removed key's value, if somebody still configures it
     */
    Optional<Duration> asyncTaskTimeout();

    /**
     * How long the shutdown of a workflow module waits for the handlers this adapter has
     * in flight before the client is closed under them. Default: 20 seconds.
     *
     * @return The grace period
     */
    Optional<Duration> shutdownGrace();

    /**
     * How long the health check waits for the cluster to answer its topology request,
     * <code>PT0S</code> switching the check off.
     *
     * @return The timeout
     */
    Optional<Duration> healthTimeout();

    /**
     * How long the start waits for a cluster which is not answering yet,
     * <code>PT0S</code> switching the waiting off. Default: ten minutes.
     *
     * @return The wait
     */
    Optional<Duration> startupWait();

    /**
     * What this adapter does with a task the core reports as older than
     * <code>vanillabp.delivery.max-task-age</code>.
     *
     * @return The action
     */
    Optional<Camunda8AdapterConfiguration.AsyncTaskMaxAgeAction> asyncTaskMaxAgeAction();

    /**
     * How long a workflow this cluster holds may stay invisible to the query API
     * the awareness probe searches (the exporter feeding it runs behind the
     * engine). Zero switches the waiting off. Default: 10 seconds.
     *
     * @return The visibility window
     */
    Optional<Duration> workflowVisibilityTimeout();

    /**
     * The same window for a workflow the engine no longer holds, which is the case where
     * only the END of it is still on its way into the read model. Zero switches the
     * waiting off. Default: 3 seconds.
     *
     * @return The visibility window of an ended workflow
     */
    Optional<Duration> endedWorkflowVisibilityTimeout();

    /**
     * Whether the jobs this adapter holds from the activation to the answer are activated
     * with a lease. There is no default: on a release line whose cluster has a lease, the
     * boot stops until this says which of the two the application wants.
     *
     * @return What the application decided
     */
    Optional<Camunda8AdapterConfiguration.JobLease> jobLease();

    /**
     * Whether a workflow this adapter starts carries the workflow aggregate's id as its
     * business id, which is there to be read in Operate and nowhere read back by
     * VanillaBP. Default: <code>false</code>.
     *
     * @return Whether the aggregate's id is written there
     */
    Optional<Boolean> aggregateIdAsBusinessId();

    /**
     * Whether the check which looks at the other open tasks of a workflow asks the cluster
     * about each Camunda-managed user task of an instance which is still running. Default:
     * <code>false</code>.
     *
     * @return Whether a user task gets a probe of its own
     */
    Optional<Boolean> probeOpenUserTasks();

    /**
     * How this adapter instance runs what it delivers: a positive number of platform
     * threads, or the literal <code>virtual</code>. Default: four platform threads.
     *
     * @return The execution model
     */
    Optional<String> workerThreads();

    /**
     * How many handlers may run at the same time while <code>worker-threads</code> is
     * <code>virtual</code>. Default: the number the platform-thread mode would use.
     *
     * @return The bound of the virtual-thread executor
     */
    Optional<Integer> workerThreadsBound();

    /**
     * How many jobs one worker may hold at the same time. Default: eight per execution
     * slot, capped at the client's 32.
     *
     * @return The worker's job capacity
     */
    Optional<Integer> maxJobsActive();

    /**
     * How long a worker waits between two activation requests. Default: the client's
     * 100 milliseconds.
     *
     * @return The poll interval
     */
    Optional<Duration> pollInterval();

    /**
     * How long a request to the cluster may take, which for an activation request is
     * also the long-polling window. Default: the client's 10 seconds.
     *
     * @return The request timeout
     */
    Optional<Duration> requestTimeout();

    /**
     * Whether the cluster pushes jobs to the workers instead of only answering their
     * polls. Default: the client's <code>false</code>.
     *
     * @return Whether job streaming is switched on
     */
    Optional<Boolean> streamEnabled();

    /**
     * How long a job stream stays open before the client re-opens it. Default: the
     * client's.
     *
     * @return The stream timeout
     */
    Optional<Duration> streamTimeout();

    /**
     * How long the cluster buffers a published message waiting for a subscription,
     * which is also the window a message id deduplicates in. Default: the client's one
     * hour.
     *
     * @return The message time-to-live
     */
    Optional<Duration> messageTimeToLive();

    /**
     * The client's maximum inbound message size in bytes. Default: the client's.
     *
     * @return The maximum message size
     */
    Optional<Integer> maxMessageSize();

    /**
     * The keep-alive interval of the client's connections. Default: the client's.
     *
     * @return The keep-alive interval
     */
    Optional<Duration> keepAlive();

    /**
     * How many HTTP connections the REST transport may open. Default: the client's.
     *
     * @return The connection limit
     */
    Optional<Integer> maxHttpConnections();

    /**
     * The authority the TLS certificate is verified against. Default: none.
     *
     * @return The overridden authority
     */
    Optional<String> overrideAuthority();

    /**
     * How this adapter instance authenticates against its cluster.
     *
     * @return The authentication block
     */
    AuthKeys auth();

  }

  /**
   * The <code>vanillabp.adapters.&lt;id&gt;.auth.*</code> keys (see
   * {@link Camunda8AuthConfiguration} for the semantics and
   * the defaults the Camunda client brings).
   */
  interface AuthKeys {

    /**
     * <code>none</code>, <code>basic</code> or <code>oidc</code>. Absent means the
     * method is detected from the keys which are set, and the detection is logged.
     *
     * @return The method
     */
    Optional<Camunda8AuthConfiguration.Method> method();

    /**
     * The user name of the method <code>basic</code>.
     *
     * @return The user name
     */
    Optional<String> username();

    /**
     * The password of the method <code>basic</code>.
     *
     * @return The password
     */
    Optional<String> password();

    /**
     * The OIDC client requesting the token.
     *
     * @return The client id
     */
    Optional<String> clientId();

    /**
     * The secret of the OIDC client.
     *
     * @return The client secret
     */
    Optional<String> clientSecret();

    /**
     * The token endpoint of the identity provider.
     *
     * @return The authorization server URL
     */
    Optional<String> authorizationServerUrl();

    /**
     * The audience the cluster expects in the token.
     *
     * @return The audience
     */
    Optional<String> audience();

    /**
     * The scopes requested with the token.
     *
     * @return The scope
     */
    Optional<String> scope();

    /**
     * Where the client caches the tokens it fetched. Default: the client's
     * <code>${user.home}/.camunda/credentials</code>.
     *
     * @return The cache file
     */
    Optional<String> credentialsCachePath();

    /**
     * How long connecting to the authorization server may take. Default: the client's 5
     * seconds.
     *
     * @return The connect timeout
     */
    Optional<Duration> connectTimeout();

    /**
     * How long reading the token response may take. Default: the client's 5 seconds.
     *
     * @return The read timeout
     */
    Optional<Duration> readTimeout();

    /**
     * The keystore holding the client certificate the AUTHORIZATION SERVER asks for.
     *
     * @return The keystore file
     */
    Optional<String> keystorePath();

    /**
     * The password of that keystore.
     *
     * @return The keystore password
     */
    Optional<String> keystorePassword();

    /**
     * The password of the key inside that keystore.
     *
     * @return The key password
     */
    Optional<String> keystoreKeyPassword();

    /**
     * The truststore the AUTHORIZATION SERVER's certificate is verified against.
     *
     * @return The truststore file
     */
    Optional<String> truststorePath();

    /**
     * The password of that truststore.
     *
     * @return The truststore password
     */
    Optional<String> truststorePassword();

    /**
     * The certificate authority the CLUSTER's TLS certificate is verified against.
     *
     * @return The certificate file
     */
    Optional<String> caCertificatePath();

  }


  /**
   * The scope-specific Camunda 8 keys of one <code>adapters.&lt;id&gt;</code>
   * section below a workflow-module/workflow/task level.
   */
  interface Camunda8ScopedKeys {

    /**
     * The worker's job timeout (lock duration) at this level.
     *
     * @return The job timeout
     */
    Optional<Duration> jobTimeout();

    /**
     * How long the cluster waits before it hands a FAILED job out again, at this level.
     *
     * @return The backoff of a failed job
     */
    Optional<Duration> retryBackoff();

    /**
     * Whether a worker fetches the derived variables or all of them, at this level.
     *
     * @return The mode
     */
    Optional<Camunda8FetchVariables.Mode> fetchVariables();

    /**
     * How long the cluster keeps a published message, at this level.
     *
     * @return The message time-to-live
     */
    Optional<Duration> messageTimeToLive();

    /**
     * Whether an element built from an element template is left to the runtime which owns
     * it, at this level. Empty rather than <code>false</code> where nothing is configured,
     * which is what lets a workflow module switch OFF what the adapter switched on.
     *
     * @return Whether connectors are allowed here
     */
    Optional<Boolean> allowConnectors();

    /**
     * Whether the listeners somebody modelled are served at this level. Empty rather than
     * <code>false</code> where nothing is configured, for the reason
     * {@link #allowConnectors()} is.
     *
     * @return Whether the modelled listeners are served here
     */
    Optional<Boolean> allowListeners();

  }

  /**
   * The Camunda 8 keys of one workflow module's adapter section: the scoped keys every level
   * has, plus the tenant, which only a workflow module may override because a tenant id is an
   * attribute of the deployment this adapter makes per workflow module.
   */
  interface Camunda8ModuleScopedKeys extends Camunda8ScopedKeys {

    /**
     * The Camunda 8 tenant this workflow module is deployed into, overriding the name the
     * adapter section gives every module of this application.
     *
     * @return The tenant of this workflow module
     */
    Optional<String> tenantId();

  }

  /**
   * The Camunda 8 adapter's view of one workflow-module section.
   */
  interface ModuleOverlay {

    /**
     * The module-level adapter sections, keyed by adapter ID.
     *
     * @return The adapter sections
     */
    Map<String, Camunda8ModuleScopedKeys> adapters();

    /**
     * The workflow sections of the module, keyed by BPMN process ID.
     *
     * @return The workflow sections
     */
    Map<String, WorkflowOverlay> workflows();

  }

  /**
   * The Camunda 8 adapter's view of one workflow section.
   */
  interface WorkflowOverlay {

    /**
     * The workflow-level adapter sections, keyed by adapter ID.
     *
     * @return The adapter sections
     */
    Map<String, Camunda8ScopedKeys> adapters();

    /**
     * The task sections of the workflow, keyed by task definition.
     *
     * @return The task sections
     */
    Map<String, TaskOverlay> tasks();

    /**
     * The message sections of the workflow, keyed by message name - the most specific
     * level of the keys which are about a MESSAGE rather than about a task.
     *
     * @return The message sections
     */
    Map<String, MessageOverlay> messages();

  }

  /**
   * The Camunda 8 adapter's view of one task section - the MOST specific level for a key
   * about a job.
   */
  interface TaskOverlay {

    /**
     * The task-level adapter sections, keyed by adapter ID.
     *
     * @return The adapter sections
     */
    Map<String, Camunda8ScopedKeys> adapters();

  }

  /**
   * The Camunda 8 adapter's view of one message section - the MOST specific level for a
   * key about a published message.
   */
  interface MessageOverlay {

    /**
     * The message-level adapter sections, keyed by adapter ID.
     *
     * @return The adapter sections
     */
    Map<String, Camunda8ScopedKeys> adapters();

  }

}
