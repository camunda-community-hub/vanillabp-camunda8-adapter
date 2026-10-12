package io.vanillabp.camunda8.springboot.client;

import java.time.Duration;
import java.util.ArrayList;
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
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.camunda8.wiring.Camunda8Listeners;
import io.vanillabp.camunda8.wiring.Camunda8RetryBackoffResolver;

/**
 * The Camunda 8 adapter's OVERLAY of the shared <code>vanillabp.*</code> configuration
 * tree. The adapter's connection settings live where every adapter keeps them, at
 * <code>vanillabp.adapters.&lt;id&gt;.*</code>. {@link Camunda8AdapterConfiguration}
 * documents the keys: <code>mode</code>, <code>rest-address</code>,
 * <code>grpc-address</code>, <code>prefer-rest-over-grpc</code>, <code>tenant-id</code>,
 * <code>cluster-id</code>, <code>region</code>, <code>client-id</code>,
 * <code>client-secret</code>, and the <code>auth.*</code> block of
 * {@link Camunda8AuthConfiguration}.
 * <p>
 * This is a second {@code @ConfigurationProperties} class over the same prefix. It lives
 * next to the platform's binding of the core model. The JavaBean binding ignores keys which
 * one of the two views does not know.
 * <p>
 * The set of adapter ids is NEVER taken from this overlay map. It always comes from the
 * platform's core properties ({@code adapterTypes()} filtered by type {@code camunda8}).
 * The overlay is only used to look up an id the core already knows. The reason: overrides
 * by environment variables can create map entries in the overlay for ids which do not
 * exist.
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
   * The adapter sections of the shared tree, keyed by adapter ID. Only the Camunda 8
   * connection keys are modeled here. They are bound directly onto the
   * platform-neutral {@link Camunda8AdapterConfiguration}.
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
   * The workflow-module sections of the shared tree. The overlay has the same levels as
   * the resolution of the scope-specific adapter keys, where the most specific level wins
   * (task &gt; workflow &gt; workflow-module &gt; adapter). These keys are resolved like
   * this:
   * <ul>
   * <li><code>job-timeout</code> and <code>retry-backoff</code>, over all four levels.</li>
   * <li><code>message-time-to-live</code>, with a MESSAGE instead of a task as the most
   * specific level.</li>
   * <li><code>allow-connectors</code> and <code>allow-listeners</code>, with the workflow
   * as the most specific level.</li>
   * <li><code>tenant-id</code>, with the workflow module as the ONLY level below the
   * adapter. A tenant id belongs to the deployment, and this adapter deploys once per
   * workflow module.</li>
   * </ul>
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
   * Resolves the job timeout for a task over the four levels. The most specific level
   * wins. Without a value at a scope level, the adapter level counts, and without that the
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
   * Resolves the backoff of a FAILED job in the same way: the most specific level wins.
   * Without a value at a scope level, the adapter level counts, and without that the
   * default of ten seconds.
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
   * The same answer, plus whether the value comes from the TASK level. A BPMN model can
   * only compete with that level. A task header
   * {@value io.vanillabp.camunda8.wiring.Camunda8RetryBackoffHeader#HEADER_NAME} is as
   * specific as the task level and beats every level above it.
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
   * The Camunda 8 tenant configured for one workflow module of one adapter id. It is the
   * module's own tenant name if it has one, otherwise the adapter's. What the mode then
   * does with it is up to the adapter.
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
   * Resolves the time-to-live of a published message. The most specific level wins, and
   * here the MOST specific level is the message, not a task. Without a value at a scope
   * level, the adapter level counts. Without that the result is <code>null</code>: the
   * command stays as it is, and the client's own default applies.
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
   * a MESSAGE, most specific first. This is separate from
   * {@link #scopedKeysMostSpecificFirst} because the most specific level is a different
   * map. A message is not a task. If a message used the task level, a value meant for one
   * would also apply to the other.
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
   * Every key which still sets the removed <code>fetch-variables</code> for one adapter, at
   * the adapter level and at the three levels below it. The startup refuses them all in
   * one message, see {@link Camunda8FetchVariables#rejectTheRemovedKey}.
   *
   * @param adapterId The adapter ID
   * @return The full keys, empty where nobody sets it
   */
  public List<String> fetchVariablesKeys(
      final String adapterId) {

    final var keys = new ArrayList<String>();
    final var adapter = adapters.get(adapterId);
    if ((adapter != null) && (adapter.getFetchVariables() != null)) {
      keys.add("vanillabp.adapters.%s.%s".formatted(adapterId, Camunda8FetchVariables.REMOVED_KEY));
    }
    workflowModules.forEach((
        moduleId,
        module) -> {
      if (setsFetchVariables(module.getAdapters(), adapterId)) {
        keys.add("vanillabp.workflow-modules.%s.adapters.%s.%s"
            .formatted(moduleId, adapterId, Camunda8FetchVariables.REMOVED_KEY));
      }
      module.getWorkflows().forEach((
          workflowId,
          workflow) -> {
        if (setsFetchVariables(workflow.getAdapters(), adapterId)) {
          keys.add("vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.%s"
              .formatted(moduleId, workflowId, adapterId, Camunda8FetchVariables.REMOVED_KEY));
        }
        workflow.getTasks().forEach((
            taskId,
            task) -> {
          if (setsFetchVariables(task.getAdapters(), adapterId)) {
            keys.add("vanillabp.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.%s"
                .formatted(moduleId, workflowId, taskId, adapterId, Camunda8FetchVariables.REMOVED_KEY));
          }
        });
      });
    });
    return keys;

  }

  private static boolean setsFetchVariables(
      final Map<String, ? extends Camunda8ScopedKeys> level,
      final String adapterId) {

    final var keys = level == null
        ? null
        : level.get(adapterId);
    return (keys != null) && (keys.getFetchVariables() != null);

  }

  /**
   * Resolves whether an element built from an element template is left to the runtime which
   * owns it. There are THREE levels here, not four: workflow, workflow module, adapter. The
   * most specific configured value wins, both for on and for off. This is a deliberate
   * difference to version 1. Its primitive booleans let a more specific level turn the flag
   * on, but never off.
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
   * Every <code>allow-connectors</code> this configuration puts at TASK level, as full
   * keys. This key is not read at the task level. All the boot can do about it is say
   * so.
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
   * Resolves whether the listeners somebody modelled are served. It uses the three levels
   * of <code>allow-connectors</code> and the same rule: the most specific value wins, both
   * for on and for off.
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
   * Every <code>allow-listeners</code> this configuration puts at TASK level, as full keys.
   * This key is not read at the task level.
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
   * most specific first. Every scope-specific key is resolved through them.
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
     * The removed key <code>fetch-variables</code> at this level, bound only so the startup
     * can refuse it.
     */
    private String fetchVariables;

    /**
     * The removed key <code>fetch-variables</code> at this level, bound only so the startup
     * can refuse it.
     *
     * @return The value somebody wrote, or <code>null</code> where this level says nothing
     */
    public String getFetchVariables() {

      return fetchVariables;

    }

    /**
     * The removed key <code>fetch-variables</code> at this level, bound only so the startup
     * can refuse it.
     *
     * @param fetchVariables The value somebody wrote, or <code>null</code> where this level
     *          says nothing
     */
    public void setFetchVariables(
        final String fetchVariables) {

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
     * it. It is a {@code Boolean} and not a primitive, because "not set" must differ from
     * {@code false}. This lets a workflow module switch OFF what the adapter switched on.
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
   * The Camunda 8 keys of one workflow module's adapter section. These are the scoped keys
   * every level has, plus the tenant. Only a workflow module may override the tenant. A
   * tenant id belongs to the deployment, and this adapter deploys once per workflow
   * module.
   */
  public static class Camunda8ModuleScopedKeys extends Camunda8ScopedKeys {

    /**
     * Bound by Spring Boot, which builds the section with this constructor and then calls the
     * setters.
     */
    public Camunda8ModuleScopedKeys() {
    }

    /**
     * The Camunda 8 tenant this workflow module is deployed into. It overrides the name the
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
     * The message sections of this workflow. They are the most specific level of the keys
     * which are about a MESSAGE and not about a task.
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
   * The Camunda 8 adapter's view of one message section. It is the most specific level for
   * a key about a published message.
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
   * The Camunda 8 adapter's view of one task section. It is the MOST specific level.
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
