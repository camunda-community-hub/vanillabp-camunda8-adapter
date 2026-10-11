package io.vanillabp.camunda8.quarkus.runtime;

import java.time.Duration;
import java.util.ArrayList;
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
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.camunda8.wiring.Camunda8Listeners;
import io.vanillabp.camunda8.wiring.Camunda8RetryBackoffResolver;

/**
 * The Camunda 8 adapter's OVERLAY of the shared <code>vanillabp.*</code> configuration
 * tree. The adapter's connection settings live where every adapter keeps them, at
 * <code>vanillabp.adapters.&lt;id&gt;.*</code> (see {@link Camunda8AdapterConfiguration}).
 * <p>
 * This is a second RUN_TIME {@code @ConfigMapping} over the same prefix. It lives next to
 * the platform's mapping. The platform no longer ignores all unknown keys with
 * {@code withMappingIgnore}. So this overlay is also what makes the adapter's keys known
 * to the check for unknown keys.
 * <p>
 * The set of adapter ids is NEVER taken from this overlay map. It always comes from the
 * platform's core properties ({@code adapterTypes()} filtered by type {@code camunda8}).
 * The overlay is only used to look up an id the core already knows.
 */
@StaticInitSafe
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface VanillaBpCamunda8Properties {

  /**
   * The adapter sections of the shared tree, keyed by adapter ID. Only the Camunda 8
   * connection keys are modeled here.
   *
   * @return The sections, keyed by adapter id, empty where the application wrote none
   */
  Map<String, Camunda8AdapterKeys> adapters();

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
   *
   * @return The workflow-module sections, keyed by workflow module ID
   */
  Map<String, ModuleOverlay> workflowModules();

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
   * Resolves the job timeout for a task over the four levels. The most specific level
   * wins. Without a value at a scope level, the adapter level counts, and without that the
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
   * Resolves the backoff of a FAILED job in the same way: the most specific level wins.
   * Without a value at a scope level, the adapter level counts, and without that the
   * default of ten seconds.
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
   * Every key which still sets the removed <code>fetch-variables</code> for one adapter, at
   * the adapter level and at the three levels below it. The startup refuses them all in
   * one message, see {@link Camunda8FetchVariables#rejectTheRemovedKey}.
   *
   * @param adapterId The adapter ID
   * @return The full keys, empty where nobody sets it
   */
  default List<String> fetchVariablesKeys(
      final String adapterId) {

    final var keys = new ArrayList<String>();
    final var adapter = adapters().get(adapterId);
    if ((adapter != null) && adapter.fetchVariables().isPresent()) {
      keys.add("vanillabp.adapters.%s.%s".formatted(adapterId, Camunda8FetchVariables.REMOVED_KEY));
    }
    workflowModules().forEach((
        moduleId,
        module) -> {
      if (setsFetchVariables(module.adapters(), adapterId)) {
        keys.add("vanillabp.workflow-modules.%s.adapters.%s.%s"
            .formatted(moduleId, adapterId, Camunda8FetchVariables.REMOVED_KEY));
      }
      module.workflows().forEach((
          workflowId,
          workflow) -> {
        if (setsFetchVariables(workflow.adapters(), adapterId)) {
          keys.add("vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.%s"
              .formatted(moduleId, workflowId, adapterId, Camunda8FetchVariables.REMOVED_KEY));
        }
        workflow.tasks().forEach((
            taskId,
            task) -> {
          if (setsFetchVariables(task.adapters(), adapterId)) {
            keys.add("vanillabp.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.%s"
                .formatted(moduleId, workflowId, taskId, adapterId, Camunda8FetchVariables.REMOVED_KEY));
          }
        });
      });
    });
    return keys;

  }

  /**
   * Whether one level sets the removed key for one adapter.
   *
   * @param level The <code>adapters</code> section of the level
   * @param adapterId The adapter ID
   * @return Whether the key is there
   */
  private static boolean setsFetchVariables(
      final Map<String, ? extends Camunda8ScopedKeys> level,
      final String adapterId) {

    final var keys = level == null
        ? null
        : level.get(adapterId);
    return (keys != null) && keys.fetchVariables().isPresent();

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
   * Every <code>allow-listeners</code> this configuration puts at TASK level, as full keys.
   * This key is not read at the task level.
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
   * Every <code>allow-connectors</code> this configuration puts at TASK level, as full
   * keys. This key is not read at the task level. All the boot can do about it is say
   * so.
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
   * a MESSAGE, most specific first. This is separate from
   * {@link #scopedKeysMostSpecificFirst} because the most specific level is a different
   * map. A message is not a task. If a message used the task level, a value meant for one
   * would also apply to the other.
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
   * most specific first. Every scope-specific key is resolved through them.
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
     * OPTIONAL. Confirms that the application's identifiers are unique across all of its
     * workflow modules. It silences the WARN logged while the name-clash-avoidance mode
     * <code>none</code> applies. Default <code>false</code>.
     *
     * @return Whether unscoped identifiers are accepted deliberately
     */
    Optional<Boolean> acceptUnscopedIdentifiers();

    /**
     * OPTIONAL. Whether an element built from an element template is left to the runtime
     * which owns it. In almost every case that runtime is a Camunda connector. This is the
     * adapter level of a resolution over three levels (workflow &gt; workflow-module &gt;
     * adapter). Default <code>false</code>. See
     * {@link io.vanillabp.camunda8.wiring.Camunda8AllowConnectorsResolver}.
     *
     * @return Whether connectors are allowed for this adapter instance
     */
    Optional<Boolean> allowConnectors();

    /**
     * OPTIONAL. Whether <code>@WorkflowTask</code> methods serve the listeners somebody
     * MODELLED. This is the adapter level of a resolution over three levels (workflow &gt;
     * workflow-module &gt; adapter). Default <code>false</code>. See
     * {@link io.vanillabp.camunda8.wiring.Camunda8AllowListenersResolver}.
     *
     * @return Whether the modelled listeners are served for this adapter instance
     */
    Optional<Boolean> allowListeners();

    /**
     * The worker's job timeout (how long a job stays locked). This is the adapter level of
     * the resolution where the most specific level wins.
     *
     * @return The job timeout
     */
    Optional<Duration> jobTimeout();

    /**
     * How long the cluster waits before it hands a FAILED job out again. This is the
     * adapter level of the resolution where the most specific level wins. Default: ten
     * seconds.
     *
     * @return The backoff of a failed job
     */
    Optional<Duration> retryBackoff();

    /**
     * The removed key <code>fetch-variables</code>. It is bound only so the startup can
     * refuse it with a guiding message. Otherwise SmallRye would only say "does not map to
     * any root".
     *
     * @return What somebody wrote under the removed key
     */
    Optional<String> fetchVariables();

    /**
     * The window the lock of a job left open by a <code>&#64;TaskId</code> handler is
     * renewed in.
     *
     * @return The renewal window
     */
    Optional<Duration> asyncTaskLockRenewal();

    /**
     * The key which used to carry a dormancy horizon of days. It is bound only so the boot
     * can REJECT it with a message which names the key that replaced it.
     *
     * @return The removed key's value, if somebody still configures it
     */
    Optional<Duration> asyncTaskTimeout();

    /**
     * How long the shutdown of a workflow module waits for the handlers this adapter still
     * runs. After that the client is closed, even if they still run. Default: 20
     * seconds.
     *
     * @return The grace period
     */
    Optional<Duration> shutdownGrace();

    /**
     * How long the health check waits for the cluster to answer its topology request.
     * <code>PT0S</code> switches the check off.
     *
     * @return The timeout
     */
    Optional<Duration> healthTimeout();

    /**
     * How long the start waits for a cluster which does not answer yet. <code>PT0S</code>
     * switches the waiting off. Default: ten minutes.
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
     * How long a workflow this cluster holds may stay invisible to the query API which the
     * awareness probe searches. The exporter which feeds that API runs behind the engine.
     * Zero switches the waiting off. Default: 10 seconds.
     *
     * @return The visibility window
     */
    Optional<Duration> workflowVisibilityTimeout();

    /**
     * The same window for a workflow the engine no longer holds. In this case only the END
     * of the workflow is still on its way into the read model. Zero switches the waiting
     * off. Default: 3 seconds.
     *
     * @return The visibility window of an ended workflow
     */
    Optional<Duration> endedWorkflowVisibilityTimeout();

    /**
     * Whether the jobs this adapter holds from the activation to the answer are activated
     * with a lease. There is no default. On a release line whose cluster has a lease, the
     * boot stops until this key says which of the two the application wants.
     *
     * @return What the application decided
     */
    Optional<Camunda8AdapterConfiguration.JobLease> jobLease();

    /**
     * Whether a workflow this adapter starts carries the workflow aggregate's id as its
     * business id. The business id is there to be read in Operate. VanillaBP never reads
     * it back. Default: <code>false</code>.
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
     * How this adapter instance runs what it delivers. Either a positive number of
     * platform threads, or the word <code>virtual</code>. Default: four platform
     * threads.
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
     * How long a request to the cluster may take. For an activation request this is also
     * the long-polling window. Default: the client's 10 seconds.
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
     * How long the cluster keeps a published message while it waits for a subscription.
     * In the same window a message id removes duplicates. Default: the client's one
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
   * The <code>vanillabp.adapters.&lt;id&gt;.auth.*</code> keys. See
   * {@link Camunda8AuthConfiguration} for what they mean and for the defaults the Camunda
   * client brings.
   */
  interface AuthKeys {

    /**
     * <code>none</code>, <code>basic</code> or <code>oidc</code>. If it is not set, the
     * method is detected from the keys which are set, and the result is logged.
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
     * The removed key <code>fetch-variables</code> at this level, bound only so the startup
     * can refuse it.
     *
     * @return What somebody wrote under the removed key
     */
    Optional<String> fetchVariables();

    /**
     * How long the cluster keeps a published message, at this level.
     *
     * @return The message time-to-live
     */
    Optional<Duration> messageTimeToLive();

    /**
     * Whether an element built from an element template is left to the runtime which owns
     * it, at this level. It is empty, not <code>false</code>, where nothing is configured.
     * This lets a workflow module switch OFF what the adapter switched on.
     *
     * @return Whether connectors are allowed here
     */
    Optional<Boolean> allowConnectors();

    /**
     * Whether the listeners somebody modelled are served at this level. It is empty, not
     * <code>false</code>, where nothing is configured, for the same reason as
     * {@link #allowConnectors()}.
     *
     * @return Whether the modelled listeners are served here
     */
    Optional<Boolean> allowListeners();

  }

  /**
   * The Camunda 8 keys of one workflow module's adapter section. These are the scoped keys
   * every level has, plus the tenant. Only a workflow module may override the tenant. A
   * tenant id belongs to the deployment, and this adapter deploys once per workflow
   * module.
   */
  interface Camunda8ModuleScopedKeys extends Camunda8ScopedKeys {

    /**
     * The Camunda 8 tenant this workflow module is deployed into. It overrides the name the
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
     * The message sections of the workflow, keyed by message name. They are the most
     * specific level of the keys which are about a MESSAGE and not about a task.
     *
     * @return The message sections
     */
    Map<String, MessageOverlay> messages();

  }

  /**
   * The Camunda 8 adapter's view of one task section. It is the MOST specific level for a
   * key about a job.
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
   * The Camunda 8 adapter's view of one message section. It is the MOST specific level for
   * a key about a published message.
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
