package io.vanillabp.camunda8.springboot.client;

import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8AuthConfiguration;
import io.vanillabp.camunda8.wiring.Camunda8AllowConnectorsResolver;
import io.vanillabp.camunda8.wiring.Camunda8AllowListenersResolver;
import io.vanillabp.camunda8.wiring.Camunda8ConfiguredTenant;
import io.vanillabp.camunda8.wiring.Camunda8Connectors;
import io.vanillabp.camunda8.wiring.Camunda8FetchVariables;
import io.vanillabp.camunda8.wiring.Camunda8FetchVariablesResolver;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.camunda8.wiring.Camunda8Listeners;
import io.vanillabp.camunda8.wiring.Camunda8RetryBackoffResolver;

/**
 * The Camunda 8 adapter's OVERLAY of the shared <code>vanillabp.*</code> configuration
 * tree: the adapter's connection settings live at the canonical per-adapter location
 * <code>vanillabp.adapters.&lt;id&gt;.*</code> (keys documented in
 * {@link Camunda8AdapterConfiguration}: <code>mode</code>, <code>rest-address</code>,
 * <code>grpc-address</code>, <code>prefer-rest-over-grpc</code>, <code>tenant-id</code>,
 * <code>cluster-id</code>, <code>region</code>, <code>client-id</code>,
 * <code>client-secret</code>, and the <code>auth.*</code> block of
 * {@link Camunda8AuthConfiguration}). A second {@code @ConfigurationProperties} class over
 * the same prefix coexists with the platform's binding of the core model; keys unknown to
 * either view are ignored by the JavaBean binding.
 * <p>
 * The adapter-id set is NEVER derived from this overlay map - it always comes from the
 * platform's core properties ({@code adapterTypes()} filtered by type
 * {@code camunda8}); the overlay is a per-known-id lookup only (environment-variable
 * overrides can materialize phantom map entries in the overlay).
 */
@ConfigurationProperties("vanillabp")
public class VanillaBpCamunda8Properties {

  /**
   * Bound by Spring Boot, which builds the section with this constructor and then calls the
   * setters.
   */
  public VanillaBpCamunda8Properties() {
  }

  /**
   * The adapter sections of the shared tree, keyed by adapter ID - only the
   * Camunda 8 connection keys are modeled here (bound directly onto the
   * platform-neutral {@link Camunda8AdapterConfiguration}).
   */
  private Map<String, Camunda8AdapterConfiguration> adapters = Map.of();

  /**
   * The adapter sections of the shared tree, keyed by adapter id.
   *
   * @return The sections, keyed by adapter id, never <code>null</code>
   */
  public Map<String, Camunda8AdapterConfiguration> getAdapters() {

    return adapters;

  }

  /**
   * The adapter sections of the shared tree, keyed by adapter id.
   *
   * @param adapters The sections, keyed by adapter id, never <code>null</code>
   */
  public void setAdapters(
      final Map<String, Camunda8AdapterConfiguration> adapters) {

    this.adapters = adapters;

  }

  /**
   * The workflow-module sections of the shared tree - the overlay mirrors the
   * levels of the most-specific-wins resolution of scope-specific adapter keys
   * (task &gt; workflow &gt; workflow-module &gt; adapter), currently:
   * <code>job-timeout</code>, <code>retry-backoff</code> and
   * <code>fetch-variables</code>. <code>message-time-to-live</code> resolves the same way
   * with a MESSAGE as its most specific level instead of a task, and
   * <code>allow-connectors</code> the same way with the workflow as its most specific
   * level. <code>tenant-id</code> is here as well, with the workflow module as its ONLY
   * level below the adapter: a tenant id is an attribute of the deployment, and this
   * adapter deploys once per workflow module.
   */
  private Map<String, ModuleOverlay> workflowModules = Map.of();

  /**
   * The workflow-module sections of the shared tree, keyed by workflow module id.
   *
   * @return The sections, keyed by workflow module id, never <code>null</code>
   */
  public Map<String, ModuleOverlay> getWorkflowModules() {

    return workflowModules;

  }

  /**
   * The workflow-module sections of the shared tree, keyed by workflow module id.
   *
   * @param workflowModules The sections, keyed by workflow module id, never <code>null</code>
   */
  public void setWorkflowModules(
      final Map<String, ModuleOverlay> workflowModules) {

    this.workflowModules = workflowModules;

  }

  /**
   * Resolves the job timeout for a task with most-specific-wins semantics across
   * the four levels; falls back to the adapter-level value and finally the
   * default.
   *
   * @param workflowModuleId The workflow module the task belongs to
   * @param bpmnProcessId The BPMN process the task belongs to
   * @param taskDefinition The task definition (job type)
   * @param adapterId The adapter id whose keys are read
   * @return The most specific configured timeout, or the default where no level configures one
   */
  public Duration jobTimeoutFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    final var scoped = scopedKeysMostSpecificFirst(workflowModuleId, bpmnProcessId, taskDefinition, adapterId)
        .map(Camunda8ScopedKeys::getJobTimeout)
        .filter(Objects::nonNull)
        .findFirst();
    if (scoped.isPresent()) {
      return scoped.get();
    }
    final var adapter = adapters.get(adapterId);
    return (adapter != null) && (adapter.getJobTimeout() != null)
        ? adapter.getJobTimeout()
        : Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT;

  }

  /**
   * Resolves the backoff of a FAILED job with the same most-specific-wins semantics;
   * falls back to the adapter-level value and finally the default of ten seconds.
   *
   * @param workflowModuleId The workflow module the task belongs to
   * @param bpmnProcessId The BPMN process the task belongs to
   * @param taskDefinition The task definition (job type)
   * @param adapterId The adapter id whose keys are read
   * @return The most specific configured backoff, or the default where no level configures one
   */
  public Duration retryBackoffFor(
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
  public Camunda8RetryBackoffResolver.Configured configuredRetryBackoffFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    final var perTask = taskLevelKeys(workflowModuleId, bpmnProcessId, taskDefinition, adapterId);
    if ((perTask != null) && (perTask.getRetryBackoff() != null)) {
      return new Camunda8RetryBackoffResolver.Configured(
          perTask.getRetryBackoff(), true);
    }
    final var scoped = scopedKeysMostSpecificFirst(workflowModuleId, bpmnProcessId, taskDefinition, adapterId)
        .map(Camunda8ScopedKeys::getRetryBackoff)
        .filter(Objects::nonNull)
        .findFirst();
    if (scoped.isPresent()) {
      return new Camunda8RetryBackoffResolver.Configured(scoped.get(), false);
    }
    final var adapter = adapters.get(adapterId);
    return new Camunda8RetryBackoffResolver.Configured(
        adapter != null
            ? adapter.resolvedRetryBackoff()
            : Camunda8RetryBackoffResolver.DEFAULT_RETRY_BACKOFF, false);

  }

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
  public Camunda8ConfiguredTenant configuredTenantFor(
      final String adapterId,
      final String workflowModuleId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var perWorkflowModule = module != null
        ? module.getAdapters().get(adapterId)
        : null;
    final var adapter = adapters.get(adapterId);
    return Camunda8ConfiguredTenant
        .firstConfigured(
            adapterId,
            workflowModuleId,
            perWorkflowModule != null
                ? perWorkflowModule.getTenantId()
                : null,
            adapter != null
                ? adapter.getTenantId()
                : null);

  }

  /**
   * The <code>adapters.&lt;id&gt;</code> section of the TASK level alone, or
   * <code>null</code> where the configuration says nothing about this task.
   */
  private Camunda8ScopedKeys taskLevelKeys(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.getWorkflows().get(bpmnProcessId)
        : null;
    final var task = (workflow != null) && (taskDefinition != null)
        ? workflow.getTasks().get(taskDefinition)
        : null;
    return task != null
        ? task.getAdapters().get(adapterId)
        : null;

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
  public Duration messageTimeToLiveFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String messageName,
      final String adapterId) {

    final var scoped = messageScopedKeysMostSpecificFirst(
        workflowModuleId, bpmnProcessId, messageName, adapterId)
        .map(Camunda8ScopedKeys::getMessageTimeToLive)
        .filter(Objects::nonNull)
        .findFirst();
    if (scoped.isPresent()) {
      return scoped.get();
    }
    final var adapter = adapters.get(adapterId);
    return adapter == null
        ? null
        : adapter.getMessageTimeToLive();

  }

  /**
   * The <code>adapters.&lt;id&gt;</code> sections of the three levels below the adapter for
   * a MESSAGE, most specific first. Separate from
   * {@link #scopedKeysMostSpecificFirst} because the most specific level is a different
   * map: a message is not a task, and giving it the task level would make an override
   * meant for one apply to the other.
   */
  private Stream<Camunda8ScopedKeys> messageScopedKeysMostSpecificFirst(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String messageName,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.getWorkflows().get(bpmnProcessId)
        : null;
    final var message = (workflow != null) && (messageName != null)
        ? workflow.getMessages().get(messageName)
        : null;

    final var levelsMostSpecificFirst = new LinkedList<Map<String, ? extends Camunda8ScopedKeys>>();
    if (message != null) {
      levelsMostSpecificFirst.add(message.getAdapters());
    }
    if (workflow != null) {
      levelsMostSpecificFirst.add(workflow.getAdapters());
    }
    if (module != null) {
      levelsMostSpecificFirst.add(module.getAdapters());
    }
    return levelsMostSpecificFirst
        .stream()
        .<Camunda8ScopedKeys>map(level -> level.get(adapterId))
        .filter(Objects::nonNull);

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
  public Camunda8FetchVariables.Mode fetchVariablesFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    final var scoped = scopedKeysMostSpecificFirst(workflowModuleId, bpmnProcessId, taskDefinition, adapterId)
        .map(Camunda8ScopedKeys::getFetchVariables)
        .filter(Objects::nonNull)
        .findFirst();
    if (scoped.isPresent()) {
      return scoped.get();
    }
    final var adapter = adapters.get(adapterId);
    return adapter != null
        ? adapter.resolvedFetchVariables()
        : Camunda8FetchVariablesResolver.DEFAULT_FETCH_VARIABLES;

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
  public Camunda8AllowConnectorsResolver.Setting allowConnectorsFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.getWorkflows().get(bpmnProcessId)
        : null;
    final var perWorkflow = (workflow != null)
        ? workflow.getAdapters().get(adapterId)
        : null;
    if ((perWorkflow != null) && (perWorkflow.getAllowConnectors() != null)) {
      return new Camunda8AllowConnectorsResolver.Setting(
          perWorkflow.getAllowConnectors(), "vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.%s"
              .formatted(
                  workflowModuleId, bpmnProcessId, adapterId, Camunda8Connectors.ALLOW_CONNECTORS_KEY));
    }
    final var perModule = (module != null)
        ? module.getAdapters().get(adapterId)
        : null;
    if ((perModule != null) && (perModule.getAllowConnectors() != null)) {
      return new Camunda8AllowConnectorsResolver.Setting(
          perModule.getAllowConnectors(), "vanillabp.workflow-modules.%s.adapters.%s.%s"
              .formatted(workflowModuleId, adapterId, Camunda8Connectors.ALLOW_CONNECTORS_KEY));
    }
    final var adapter = adapters.get(adapterId);
    if ((adapter != null) && adapter.isAllowConnectors()) {
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
   * @return The keys found, in configuration order
   */
  public List<String> allowConnectorsKeysAtTaskLevel(
      final String adapterId) {

    return workflowModules
        .entrySet()
        .stream()
        .flatMap(module -> module
            .getValue()
            .getWorkflows()
            .entrySet()
            .stream()
            .flatMap(workflow -> workflow
                .getValue()
                .getTasks()
                .entrySet()
                .stream()
                .filter(task -> {
                  final var keys = task.getValue().getAdapters().get(adapterId);
                  return (keys != null) && (keys.getAllowConnectors() != null);
                })
                .map(task -> "vanillabp.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.%s"
                    .formatted(
                        module.getKey(), workflow.getKey(), task.getKey(), adapterId,
                        Camunda8Connectors.ALLOW_CONNECTORS_KEY))))
        .toList();

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
  public Camunda8AllowListenersResolver.Setting allowListenersFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.getWorkflows().get(bpmnProcessId)
        : null;
    final var perWorkflow = (workflow != null)
        ? workflow.getAdapters().get(adapterId)
        : null;
    if ((perWorkflow != null) && (perWorkflow.getAllowListeners() != null)) {
      return new Camunda8AllowListenersResolver.Setting(
          perWorkflow.getAllowListeners(), "vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.%s"
              .formatted(
                  workflowModuleId, bpmnProcessId, adapterId, Camunda8Listeners.ALLOW_LISTENERS_KEY));
    }
    final var perModule = (module != null)
        ? module.getAdapters().get(adapterId)
        : null;
    if ((perModule != null) && (perModule.getAllowListeners() != null)) {
      return new Camunda8AllowListenersResolver.Setting(
          perModule.getAllowListeners(), "vanillabp.workflow-modules.%s.adapters.%s.%s"
              .formatted(workflowModuleId, adapterId, Camunda8Listeners.ALLOW_LISTENERS_KEY));
    }
    final var adapter = adapters.get(adapterId);
    if ((adapter != null) && adapter.isAllowListeners()) {
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
   * @return The keys found, in configuration order
   */
  public List<String> allowListenersKeysAtTaskLevel(
      final String adapterId) {

    return workflowModules
        .entrySet()
        .stream()
        .flatMap(module -> module
            .getValue()
            .getWorkflows()
            .entrySet()
            .stream()
            .flatMap(workflow -> workflow
                .getValue()
                .getTasks()
                .entrySet()
                .stream()
                .filter(task -> {
                  final var keys = task.getValue().getAdapters().get(adapterId);
                  return (keys != null) && (keys.getAllowListeners() != null);
                })
                .map(task -> "vanillabp.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.%s"
                    .formatted(
                        module.getKey(), workflow.getKey(), task.getKey(), adapterId,
                        Camunda8Listeners.ALLOW_LISTENERS_KEY))))
        .toList();

  }

  /**
   * The <code>adapters.&lt;id&gt;</code> sections of the three levels below the adapter,
   * most specific first - what every scope-specific key is resolved through.
   */
  private Stream<Camunda8ScopedKeys> scopedKeysMostSpecificFirst(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.getWorkflows().get(bpmnProcessId)
        : null;
    final var task = (workflow != null) && (taskDefinition != null)
        ? workflow.getTasks().get(taskDefinition)
        : null;

    final var levelsMostSpecificFirst = new LinkedList<Map<String, ? extends Camunda8ScopedKeys>>();
    if (task != null) {
      levelsMostSpecificFirst.add(task.getAdapters());
    }
    if (workflow != null) {
      levelsMostSpecificFirst.add(workflow.getAdapters());
    }
    if (module != null) {
      levelsMostSpecificFirst.add(module.getAdapters());
    }
    return levelsMostSpecificFirst
        .stream()
        .<Camunda8ScopedKeys>map(level -> level.get(adapterId))
        .filter(Objects::nonNull);

  }

  /**
   * The scope-specific Camunda 8 keys of one <code>adapters.&lt;id&gt;</code>
   * section below a workflow-module/workflow/task level.
   */
  public static class Camunda8ScopedKeys {

    /**
     * Bound by Spring Boot, which builds the section with this constructor and then calls the
     * setters.
     */
    public Camunda8ScopedKeys() {
    }

    /**
     * How long a job of this scope stays locked.
     */
    private Duration jobTimeout;

    /**
     * How long a job of this scope stays locked.
     *
     * @return The timeout, or <code>null</code> where this level says nothing
     */
    public Duration getJobTimeout() {

      return jobTimeout;

    }

    /**
     * How long a job of this scope stays locked.
     *
     * @param jobTimeout The timeout, or <code>null</code> where this level says nothing
     */
    public void setJobTimeout(
        final Duration jobTimeout) {

      this.jobTimeout = jobTimeout;

    }

    /**
     * How long the cluster waits before it hands a failed job of this scope out again.
     */
    private Duration retryBackoff;

    /**
     * How long the cluster waits before it hands a failed job of this scope out again.
     *
     * @return The backoff, or <code>null</code> where this level says nothing
     */
    public Duration getRetryBackoff() {

      return retryBackoff;

    }

    /**
     * How long the cluster waits before it hands a failed job of this scope out again.
     *
     * @param retryBackoff The backoff, or <code>null</code> where this level says nothing
     */
    public void setRetryBackoff(
        final Duration retryBackoff) {

      this.retryBackoff = retryBackoff;

    }

    /**
     * Whether a worker of this scope asks for the derived variables or for all of them.
     */
    private Camunda8FetchVariables.Mode fetchVariables;

    /**
     * Whether a worker of this scope asks for the derived variables or for all of them.
     *
     * @return The mode, or <code>null</code> where this level says nothing
     */
    public Camunda8FetchVariables.Mode getFetchVariables() {

      return fetchVariables;

    }

    /**
     * Whether a worker of this scope asks for the derived variables or for all of them.
     *
     * @param fetchVariables The mode, or <code>null</code> where this level says nothing
     */
    public void setFetchVariables(
        final Camunda8FetchVariables.Mode fetchVariables) {

      this.fetchVariables = fetchVariables;

    }

    /**
     * How long the cluster keeps a message published in this scope.
     */
    private Duration messageTimeToLive;

    /**
     * How long the cluster keeps a message published in this scope.
     *
     * @return The time to live, or <code>null</code> where this level says nothing
     */
    public Duration getMessageTimeToLive() {

      return messageTimeToLive;

    }

    /**
     * How long the cluster keeps a message published in this scope.
     *
     * @param messageTimeToLive The time to live, or <code>null</code> where this level says nothing
     */
    public void setMessageTimeToLive(
        final Duration messageTimeToLive) {

      this.messageTimeToLive = messageTimeToLive;

    }

    /**
     * Whether an element built from an element template is left to the runtime which owns
     * it. A {@code Boolean} rather than a primitive, because unset has to be told apart
     * from {@code false}: this is what lets a workflow module switch OFF what the adapter
     * switched on.
     */
    private Boolean allowConnectors;

    /**
     * Whether an element built from an element template is left to the runtime which owns it.
     *
     * @return The setting, or <code>null</code> where this level says nothing
     */
    public Boolean getAllowConnectors() {

      return allowConnectors;

    }

    /**
     * Whether an element built from an element template is left to the runtime which owns it.
     *
     * @param allowConnectors The setting, or <code>null</code> where this level says nothing
     */
    public void setAllowConnectors(
        final Boolean allowConnectors) {

      this.allowConnectors = allowConnectors;

    }

    /**
     * Whether the listeners somebody modelled are served by <code>@WorkflowTask</code>
     * methods. A {@code Boolean} for the reason {@link #allowConnectors} is one.
     */
    private Boolean allowListeners;

    /**
     * Whether the listeners somebody modelled are served by <code>&#64;WorkflowTask</code>
     * methods.
     *
     * @return The setting, or <code>null</code> where this level says nothing
     */
    public Boolean getAllowListeners() {

      return allowListeners;

    }

    /**
     * Whether the listeners somebody modelled are served by <code>&#64;WorkflowTask</code>
     * methods.
     *
     * @param allowListeners The setting, or <code>null</code> where this level says nothing
     */
    public void setAllowListeners(
        final Boolean allowListeners) {

      this.allowListeners = allowListeners;

    }

  }

  /**
   * The Camunda 8 keys of one workflow module's adapter section: the scoped keys every level
   * has, plus the tenant, which only a workflow module may override because a tenant id is an
   * attribute of the deployment this adapter makes per workflow module.
   */
  public static class Camunda8ModuleScopedKeys extends Camunda8ScopedKeys {

    /**
     * Bound by Spring Boot, which builds the section with this constructor and then calls the
     * setters.
     */
    public Camunda8ModuleScopedKeys() {
    }

    /**
     * The Camunda 8 tenant this workflow module is deployed into, overriding the name the
     * adapter section gives every module of this application.
     */
    private String tenantId;

    /**
     * The Camunda 8 tenant this workflow module is deployed into.
     *
     * @return The tenant name, or <code>null</code> where this level says nothing
     */
    public String getTenantId() {

      return tenantId;

    }

    /**
     * The Camunda 8 tenant this workflow module is deployed into.
     *
     * @param tenantId The tenant name, or <code>null</code> where this level says nothing
     */
    public void setTenantId(
        final String tenantId) {

      this.tenantId = tenantId;

    }

  }

  /**
   * The Camunda 8 adapter's view of one workflow-module section.
   */
  public static class ModuleOverlay {

    /**
     * Bound by Spring Boot, which builds the section with this constructor and then calls the
     * setters.
     */
    public ModuleOverlay() {
    }

    /**
     * The adapter sections of this workflow module, keyed by adapter id.
     */
    private Map<String, Camunda8ModuleScopedKeys> adapters = Map.of();

    /**
     * The adapter sections of this workflow module, keyed by adapter id.
     *
     * @return The sections, keyed by adapter id, never <code>null</code>
     */
    public Map<String, Camunda8ModuleScopedKeys> getAdapters() {

      return adapters;

    }

    /**
     * The adapter sections of this workflow module, keyed by adapter id.
     *
     * @param adapters The sections, keyed by adapter id, never <code>null</code>
     */
    public void setAdapters(
        final Map<String, Camunda8ModuleScopedKeys> adapters) {

      this.adapters = adapters;

    }

    /**
     * The workflow sections of this workflow module, keyed by BPMN process id.
     */
    private Map<String, WorkflowOverlay> workflows = Map.of();

    /**
     * The workflow sections of this workflow module, keyed by BPMN process id.
     *
     * @return The sections, keyed by BPMN process id, never <code>null</code>
     */
    public Map<String, WorkflowOverlay> getWorkflows() {

      return workflows;

    }

    /**
     * The workflow sections of this workflow module, keyed by BPMN process id.
     *
     * @param workflows The sections, keyed by BPMN process id, never <code>null</code>
     */
    public void setWorkflows(
        final Map<String, WorkflowOverlay> workflows) {

      this.workflows = workflows;

    }

  }

  /**
   * The Camunda 8 adapter's view of one workflow section.
   */
  public static class WorkflowOverlay {

    /**
     * Bound by Spring Boot, which builds the section with this constructor and then calls the
     * setters.
     */
    public WorkflowOverlay() {
    }

    /**
     * The adapter sections of this workflow, keyed by adapter id.
     */
    private Map<String, Camunda8ScopedKeys> adapters = Map.of();

    /**
     * The adapter sections of this workflow, keyed by adapter id.
     *
     * @return The sections, keyed by adapter id, never <code>null</code>
     */
    public Map<String, Camunda8ScopedKeys> getAdapters() {

      return adapters;

    }

    /**
     * The adapter sections of this workflow, keyed by adapter id.
     *
     * @param adapters The sections, keyed by adapter id, never <code>null</code>
     */
    public void setAdapters(
        final Map<String, Camunda8ScopedKeys> adapters) {

      this.adapters = adapters;

    }

    /**
     * The task sections of this workflow, keyed by task definition.
     */
    private Map<String, TaskOverlay> tasks = Map.of();

    /**
     * The task sections of this workflow, keyed by task definition.
     *
     * @return The sections, keyed by task definition, never <code>null</code>
     */
    public Map<String, TaskOverlay> getTasks() {

      return tasks;

    }

    /**
     * The task sections of this workflow, keyed by task definition.
     *
     * @param tasks The sections, keyed by task definition, never <code>null</code>
     */
    public void setTasks(
        final Map<String, TaskOverlay> tasks) {

      this.tasks = tasks;

    }

    /**
     * The message sections of this workflow - the most specific level of the keys which
     * are about a MESSAGE rather than about a task.
     */
    private Map<String, MessageOverlay> messages = Map.of();

    /**
     * The message sections of this workflow, keyed by BPMN message name.
     *
     * @return The sections, keyed by message name, never <code>null</code>
     */
    public Map<String, MessageOverlay> getMessages() {

      return messages;

    }

    /**
     * The message sections of this workflow, keyed by BPMN message name.
     *
     * @param messages The sections, keyed by message name, never <code>null</code>
     */
    public void setMessages(
        final Map<String, MessageOverlay> messages) {

      this.messages = messages;

    }

  }

  /**
   * The Camunda 8 adapter's view of one message section - the most specific level for a
   * key about a published message.
   */
  public static class MessageOverlay {

    /**
     * Bound by Spring Boot, which builds the section with this constructor and then calls the
     * setters.
     */
    public MessageOverlay() {
    }

    /**
     * The adapter sections of this message, keyed by adapter id.
     */
    private Map<String, Camunda8ScopedKeys> adapters = Map.of();

    /**
     * The adapter sections of this message, keyed by adapter id.
     *
     * @return The sections, keyed by adapter id, never <code>null</code>
     */
    public Map<String, Camunda8ScopedKeys> getAdapters() {

      return adapters;

    }

    /**
     * The adapter sections of this message, keyed by adapter id.
     *
     * @param adapters The sections, keyed by adapter id, never <code>null</code>
     */
    public void setAdapters(
        final Map<String, Camunda8ScopedKeys> adapters) {

      this.adapters = adapters;

    }

  }

  /**
   * The Camunda 8 adapter's view of one task section - the MOST specific level.
   */
  public static class TaskOverlay {

    /**
     * Bound by Spring Boot, which builds the section with this constructor and then calls the
     * setters.
     */
    public TaskOverlay() {
    }

    /**
     * The adapter sections of this task, keyed by adapter id.
     */
    private Map<String, Camunda8ScopedKeys> adapters = Map.of();

    /**
     * The adapter sections of this task, keyed by adapter id.
     *
     * @return The sections, keyed by adapter id, never <code>null</code>
     */
    public Map<String, Camunda8ScopedKeys> getAdapters() {

      return adapters;

    }

    /**
     * The adapter sections of this task, keyed by adapter id.
     *
     * @param adapters The sections, keyed by adapter id, never <code>null</code>
     */
    public void setAdapters(
        final Map<String, Camunda8ScopedKeys> adapters) {

      this.adapters = adapters;

    }

  }

}
