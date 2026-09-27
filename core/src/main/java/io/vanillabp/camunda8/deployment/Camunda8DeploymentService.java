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
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.camunda.client.CamundaClient;
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
import io.vanillabp.camunda8.wiring.Camunda8FetchVariablesResolver;
import io.vanillabp.camunda8.wiring.Camunda8JobHandler;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.camunda8.wiring.Camunda8Listeners;
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
import lombok.extern.slf4j.Slf4j;

/**
 * Camunda 8 implementation of the {@link AdapterDeploymentService}. One instance is
 * created per configured adapter ID (not per adapter type) because the same BPMS type
 * may be configured multiple times (BPMS migration).
 * <p>
 * The BPMN model type is {@link BpmnModelInstance}, shipped with the Camunda 8 client via
 * {@code io.camunda:zeebe-bpmn-model}. The processing context is
 * {@link Camunda8ProcessingContext}, which collects all deployable resources of a workflow
 * module so they are deployed in a single {@code DeployResourceCommand}.
 * <p>
 * Task wiring ({@code wireBpmn}) validates the BPMN's job-worker tasks
 * (zeebe:taskDefinition) against the registered {@code @WorkflowTask} methods;
 * {@code startWorkflowProcessing} opens one polling job worker per task definition
 * (closed on {@code stopWorkflowProcessing}).
 */
@Slf4j
// see decision 4 in the repository's DECISIONS.md
@SuppressWarnings("LombokSetterMayBeUsed")
public class Camunda8DeploymentService implements AdapterDeploymentService<BpmnModelInstance, Camunda8ProcessingContext> {

  /**
   * The adapter type of the Camunda 8 adapter. Constant across all instances; the
   * adapter ID (see {@link #getAdapterId()}) distinguishes instances.
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
   * The core's task-processing entry point: wiring validation during
   * {@link #wireBpmn} and job dispatch at runtime.
   */
  private final WorkflowTaskWiring workflowTaskWiring;

  /**
   * The runtime half of the split SPI. This service does not only wire: it opens the job
   * workers at {@code startWorkflowProcessing}, and those hand every delivery to the
   * core - so it holds both halves and passes this one on.
   */
  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * What this adapter knows about the multi-instance elements of the processes it
   * deployed. Filled while wiring a model, read while dispatching a job - a job
   * carries the ID of its own element and nothing about the iterations enclosing it.
   */
  private final Camunda8MultiInstance.Registry multiInstanceRegistry = new Camunda8MultiInstance.Registry();

  /**
   * The core's entry point for workflows the cluster starts on its own:
   * the start events of a process are reported here while wiring, and the start
   * execution-listener workers dispatch through it. May be <code>null</code> (tests).
   */
  private final BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker;

  /**
   * The core's entry point for workflows which ended. May be
   * <code>null</code> (tests) - no end listener is attached then.
   */
  private final WorkflowEndedInvoker workflowEndedInvoker;

  /**
   * Whether a worker asks the cluster for the variables this adapter derived or for all
   * of them. Handed in by the platform module after construction rather than
   * through the constructor, whose parameter list is long enough; <code>null</code>
   * (tests) means the default, which is the derived list.
   */
  private Camunda8FetchVariablesResolver fetchVariablesResolver;

  /**
   * Hands over how <code>fetch-variables</code> resolves for this adapter instance.
   *
   * @param fetchVariablesResolver The resolver, or <code>null</code> for the default
   */
  public void setFetchVariablesResolver(
      final Camunda8FetchVariablesResolver fetchVariablesResolver) {

    this.fetchVariablesResolver = fetchVariablesResolver;

  }

  /**
   * Whether this application honours the element-template marker of a model, resolved per
   * workflow module and per workflow. Handed in by the platform module after construction
   * like {@link #fetchVariablesResolver}; <code>null</code> (tests) means the default,
   * which is that VanillaBP wires every element.
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
   * What the configuration says about the listeners somebody modelled. <code>null</code>
   * until a platform module hands one over, which is the default: no listener is served.
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
   * What this adapter instance measures on top of what the core measures.
   * Handed in by the platform module after construction, because it exists once per
   * application while deployment services exist per adapter id;
   * {@link Camunda8Metrics#NONE} for an application without a metrics backend.
   */
  private Camunda8Metrics metrics = Camunda8Metrics.NONE;

  /**
   * Hands over what to measure into, and registers the execution slots of this adapter
   * instance right away - the client is built before this, so there is nothing to wait
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
   * The first three come from the adapter's own executor, which both execution models now
   * build, so the picture of a stalled application is the same whichever one is configured.
   * The last two come from the slot watch, which reads what the workflow modules have in
   * flight. An adapter which booted without a connection has neither; there only the
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
   * What this adapter instance does with a task the core reports as older than
   * <code>vanillabp.delivery.max-task-age</code>. Read from the adapter's own
   * configuration rather than passed through the wiring, because it belongs to the
   * connection like every other adapter-level key; a setup without the resolver (tests)
   * reports only.
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
   * How long this adapter instance's shutdown waits for the handlers it has in flight.
   * Read from the adapter's own configuration rather than passed through the
   * wiring, because it belongs to the connection like every other adapter-level key; a
   * setup without the resolver (tests) uses the default.
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
   * going down - one per workflow module, held by the client factory because an EXTENSION
   * serving jobs of the same module has to take part in the same drain.
   *
   * @param workflowModuleId The workflow module
   * @return The drain of that module, created on first use
   */
  Camunda8Drain drainOf(
      final String workflowModuleId) {

    return clientFactory.drainOf(workflowModuleId);

  }

  /**
   * What removes this adapter's own shutdown hook of a workflow module again, kept per
   * module from the moment its workers were opened. A module holds a hook per party which
   * opened workers of it, so the adapter removes ITS hook rather than the module's.
   */
  private final Map<String, Camunda8ClientFactory.WorkflowModuleShutdownRegistration> shutdownRegistrations = new ConcurrentHashMap<>();

  /**
   * Resolves the per-task job timeout from the adapter's configuration overlay
   * (task &gt; workflow &gt; workflow-module &gt; adapter, most specific wins).
   */
  private final Camunda8JobTimeoutResolver jobTimeoutResolver;

  /**
   * Resolves how long the cluster waits before it hands a FAILED job out again,
   * from the same four levels the job timeout comes from. Unlike the timeout this is not a
   * property of the worker but of each fail command, so nothing has to be aligned between
   * the processes one worker serves. May be <code>null</code> (tests): the default of ten
   * seconds applies then.
   */
  private final Camunda8RetryBackoffResolver retryBackoffResolver;

  /**
   * The window the lock of a job left open by a {@code @TaskId} handler is renewed in
   * (see {@link Camunda8JobHandler}).
   */
  private final Duration asyncTaskLockRenewal;

  /**
   * Resolves an adapter id's connection configuration - platform-supplied, used by
   * {@link #validateDistinctAdapterInstances(List)}. May be <code>null</code>
   * (tests): the check is skipped then.
   */
  private final Function<String, Camunda8AdapterConfiguration> configurations;

  /**
   * The core's name-clash-avoidance model: decides whether a workflow
   * module is isolated by a TENANT ({@code by-adapter}, version 1's behavior), by
   * PREFIXING the identifiers ({@code use-prefix} - no tenant, which is what makes
   * tenant licenses avoidable) or not at all ({@code none}, this adapter's default).
   * May be <code>null</code> (tests): nothing is scoped then.
   */
  private final NameClashAvoidanceSupport scoping;

  /**
   * The tenants already verified against the cluster - asked once per tenant, not once
   * per workflow module (several modules may share a configured tenant).
   */
  private final Set<String> verifiedTenants = ConcurrentHashMap.newKeySet();

  /**
   * The property keys whose tenant was already checked against the mode. A key rather than a
   * flag: the name may come from the adapter's section or from a workflow module's, and the
   * message has to quote the one which is set.
   */
  private final Set<String> tenantKeysCheckedAgainstTheMode = ConcurrentHashMap.newKeySet();

  /**
   * What a workflow module's tenant is CONFIGURED as, resolved by the platform modules over
   * the levels the name may be set at (the workflow module, then the adapter), or
   * <code>null</code> for a module nothing names a tenant for - then the workflow module ID
   * names it (VanillaBP 1's behavior). May be <code>null</code> itself (tests), and then the
   * adapter's own section is the only level.
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

    // which release line this application runs, once per adapter id: the client named
    // here is the LOWEST cluster version these artifacts accept, and a reader comparing
    // it to their cluster sees at a glance whether they are on the right line
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
    // What the cluster's process definitions are versioned as - the version
    // travels with every job, the version TAGS come from here
    this.processVersions = new Camunda8ProcessVersions(
        adapterId, clientFactory::getClient, this::scopedProcessId, this::tenantIdOf);
    // which models the cluster holds for the ids this application declares - the
    // picture every check judging a model asks, so no verdict depends on which
    // application version deployed the model it judges (see decision 21 in the
    // repository's DECISIONS.md). Assembled here because only the deployment
    // service can read its cluster; handed to the factory because the process
    // service's message check reads it
    clientFactory
        .provideModelsTheClusterHolds(
            new Camunda8ModelsTheClusterHolds(
                adapterId, clientFactory.getDeployedProcesses(), this::readModelsTheClusterHolds));
    // and how the version catalog reaches one model out of that picture. Set here rather
    // than while a process is wired, because the questions it serves are asked about ids
    // this application wires nothing for
    processVersions.setHeldModelOfVersion(this::heldModelOfVersion);
    processVersions.setStartEventsOfModel(this::startEventSpecsOf);
    processVersions.setConcurrentTokenElementsOfModel(this::concurrentTokenElementIdsOf);
    processVersions.setIdentifiersOfModel(this::identifiersOfModel);

  }

  /**
   * The identifiers ONE version the cluster still holds declares, as the application knows
   * them - what the core holds against the names another workflow module uses today. A job
   * type is among them and is the one which is live rather than dormant: a worker
   * subscribes to it cluster-wide, so a version workflows still run on owns that name as
   * much as the model deployed now.
   * <p>
   * The names are read off the model the cluster hands back, which carries the scoped forms
   * the deployment wrote into it, and are stripped back to the plain ones: the core
   * composes the scoped forms itself.
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
    // the job types of THIS process, read by the extraction the wiring uses, so a user
    // task's form reference counts as the listener job type it becomes and an element
    // another runtime serves stays out
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
   * The model of ONE version the cluster holds, taken from the picture of what it holds
   * for the ids this application declares (see decision 21 in the repository's
   * DECISIONS.md) - so a question about a held version and a check against one read the
   * same models.
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
   * The start events the cluster fires on its own in a model it holds - the same
   * extraction the wiring runs over the model being deployed, so both directions speak
   * about the same thing. The model is only read: it carries the execution listener of
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
   * holds - the same walk the wiring runs over the model being deployed. The versions
   * which run longest are the ones a walk over this boot's model never reaches: a parallel
   * gateway the newest model dropped keeps forking every workflow started before it.
   */
  private Collection<String> concurrentTokenElementIdsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final BpmnModelInstance model) {

    return Camunda8TaskWiring
        .concurrentTokenElementIdsOf(model, scopedProcessId(workflowModuleId, bpmnProcessId));

  }

  /**
   * Reads the models of every version the cluster holds under one BPMN process id -
   * what the picture of {@link Camunda8ModelsTheClusterHolds} is assembled from. The
   * version this boot deployed is served from the local record instead of being
   * fetched again.
   */
  private List<Camunda8ModelsTheClusterHolds.HeldModel> readModelsTheClusterHolds(
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (!clientFactory.getQueryApi().answers()) {
      // that this cluster cannot be searched was said while the module deployed
      // (see decision 20 in the repository's DECISIONS.md); an adapter degraded to
      // 'warn' keeps booting, and every check reading the picture stays silent
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
   * The versions of this cluster's process definitions: the catalog the core
   * resolves version TAGS through. The version itself travels with every job.
   */
  private final Camunda8ProcessVersions processVersions;

  /**
   * What the cluster holds for a BPMN process this application declares without deploying a
   * model under it - the old id of a renamed process, which the cluster keeps with every
   * version ever deployed under it and with the workflows still running on them.
   * <p>
   * It is the same catalog every deployed process of this adapter is registered with: it
   * searches by the process id as the cluster knows it, so a prefix and a tenant reach the
   * old id like any other.
   */
  @Override
  public io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog processVersionCatalogOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return processVersions;

  }

  /**
   * The BPMN process id as the CLUSTER knows it - the model carries the
   * scoped ids after {@code prepareBpmn}, while the core is keyed by the plain ones.
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
   * The identifier as the application modelled it - the model carries the scoped one
   * where the workflow module prefixes its identifiers.
   */
  private String plainIdentifier(
      final String workflowModuleId,
      final String scopedIdentifier) {

    // the null check is about the IDENTIFIER, not about the support: a name the model does
    // not carry stays absent, and a double of the support need not answer for one
    return scopedIdentifier == null
        ? null
        : NameClashAvoidanceSupport
            .plainIdentifier(scoping, workflowModuleId, scopedIdentifier, adapterId);

  }

  /**
   * The task definition as the core knows it - the model (and therefore the job type
   * a worker subscribes to) carries the scoped one.
   */
  private String plainTaskDefinition(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedTaskDefinition) {

    return NameClashAvoidanceSupport
        .plainTaskDefinition(scoping, workflowModuleId, bpmnProcessId, scopedTaskDefinition, adapterId);

  }

  /**
   * Sets the tenant names the application configured (the platform modules read them from
   * the configuration, per workflow module).
   *
   * @param configuredTenants What a workflow module's tenant is configured as
   */
  public void setConfiguredTenants(
      final Function<String, Camunda8ConfiguredTenant> configuredTenants) {

    this.configuredTenants = configuredTenants;

  }

  /**
   * What the application configured as the tenant of one workflow module, with the key it
   * wrote it under, or <code>null</code>. Without the platform's resolver only the adapter's
   * own section is read, which is what a test built without a platform around it holds.
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
   * Fails the boot if a tenant is configured although no workflow module is deployed into
   * one, i.e. the mode says {@code none} or {@code use-prefix} everywhere. Whether a tenant
   * is what only {@code by-adapter} can use is this adapter's knowledge; the core answers
   * which modes apply. Checked while deploying, before anything reaches the cluster, once per
   * property key which set a name - the adapter's section and a workflow module's are two
   * different lines for the developer to go to.
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
   * The tenant a workflow module is deployed to, respectively its operations are
   * executed in - decided by the name-clash-avoidance mode, with the configured
   * <code>tenant-id</code> naming it under {@code by-adapter}.
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
   * Whether this adapter's own isolation would put the two workflow modules into different
   * scopes of its cluster. The scope a Camunda 8 cluster offers is the TENANT, so the
   * answer is the tenant each of the two modules would REALLY be deployed to, compared.
   * <p>
   * Both tenants are resolved through {@code tenantIdOf}, the same function the deploy
   * command goes through, so this answer cannot drift away from where a module lands.
   * Reading the configured <code>tenant-id</code> instead would be wrong twice over: under
   * {@code by-adapter} an unset name means the workflow module id, so two modules do sit in
   * two tenants, and a name which IS set is used only where the mode of the module asks for
   * a tenant at all. The mode is resolved per workflow module, so two modules of one adapter
   * id may differ in it, and so is the name itself: a workflow module may carry one of its
   * own, which is the way out the core's refusal recommends.
   * <p>
   * A module under {@code use-prefix} or {@code none} reaches the cluster with no tenant of
   * its own, which is the <code>&lt;default&gt;</code> one. That is a scope like any other
   * here: two such modules share it and are NOT separated, while one of them against a
   * tenanted module is separated. On a cluster without multi-tenancy every module lives in
   * that one unnamed scope, because such a cluster rejects a tenant id, so nothing separates
   * anybody there and this says so. Decision 26 in the repository's DECISIONS.md carries why
   * the answer is the resolved tenant.
   * <p>
   * Asked by the core while it checks whether two BPMN processes reach the cluster under one
   * identifier, and the core keeps the answer per pair of modules. So nothing is asked of
   * the cluster here and nothing is remembered: both tenants come out of configuration which
   * does not change while an application boots.
   */
  @Override
  public boolean ownIsolationSeparatesWorkflowModules(
      final String oneWorkflowModuleId,
      final String anotherWorkflowModuleId) {

    return !Objects.equals(tenantIdOf(oneWorkflowModuleId), tenantIdOf(anotherWorkflowModuleId));

  }

  /**
   * Two <code>camunda8</code> adapter ids are only distinct if they address
   * different clusters - or one cluster with different credentials/tenants (see
   * {@link Camunda8InstanceIdentity}).
   */
  @Override
  public void validateDistinctAdapterInstances(
      final List<String> adapterIdsOfThisType) {

    Camunda8InstanceIdentity
        .validateDistinct(adapterIdsOfThisType, configurations, scoping);

  }

  /**
   * Camunda 8 keeps the SPI's default, {@code by-adapter}, which on this BPMS means a
   * TENANT named after the workflow module. That is what VanillaBP 1 deployed when
   * nothing was configured (its {@code use-tenants} was on and the tenant id defaulted
   * to the workflow module id), so an application upgrading from version 1 without
   * touching its configuration keeps addressing the workflows it started back then.
   * <p>
   * <b>What it asks of the cluster.</b> A tenant id other than {@code <default>} needs
   * multi-tenancy enabled, and the tenant has to exist: a cluster started from the
   * stock image answers a deploy command carrying one with "multi-tenancy is
   * disabled". {@link Camunda8TenantCheck} turns that into
   * a boot failure naming the properties leading out, which is the point: the
   * alternative would be a default which quietly deploys every workflow module into
   * the {@code <default>} tenant, and that is not a weaker isolation but none at all -
   * {@code none} by another name, without the warning {@code none} carries.
   * <p>
   * An application on such a cluster says so once, with
   * {@code vanillabp.adapters.<id>.name-clash-avoidance: none} (version 1's
   * {@code use-tenants: false}) or {@code use-prefix}, which needs no cluster support
   * at all. This default stood at {@code none} between 2026-08-11 and 2026-08-22,
   * which was the defect; {@code Camunda8DeploymentServiceTest} holds it now.
   */
  @Override
  public NameClashAvoidance defaultNameClashAvoidance() {

    return NameClashAvoidance.BY_ADAPTER;

  }

  /**
   * Names what Camunda 8 offers instead of {@code none}: prefixing, a tenant per
   * workflow module (which needs a cluster with multi-tenancy enabled) or a cluster
   * per workflow module.
   * <p>
   * Silent if the application accepted unscoped identifiers deliberately
   * ({@code vanillabp.adapters.<id>.accept-unscoped-identifiers}) - the point of the
   * warning is the DECISION, and once it is on record there is nothing left to ask.
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

    // one entry per executable process; the value is always the whole model since
    // Camunda 8 deploys the entire file as one resource (a file may hold several
    // executable processes)
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
    // Rewrite the identifiers the cluster resolves globally BEFORE wiring,
    // so everything downstream (wiring validation, listener injection, workers) sees
    // what the cluster will see. A no-op unless the mode is 'use-prefix'. The core
    // calls prepareBpmn once per executable PROCESS while all processes of a file
    // share ONE model, so scoping has to happen once per FILE - otherwise a
    // multi-process file would collect one prefix per process.
    final var modelAlreadyScoped = context
        .getResources()
        .containsKey(filename);
    if (!modelAlreadyScoped) {
      // the file is read for what it demands of the cluster while it is still the
      // model somebody wrote, before this adapter rewrote a single element of it
      refuseAFileTheClusterWouldReject(workflowModuleId, filename, model);
      // read while the process ids are still the plain ones, and once per FILE rather
      // than once per process: after the rewrite below an element cannot be attributed
      // to the process the configuration is keyed by any more
      recordTheElementsAnotherRuntimeServes(workflowModuleId, model, context);
      // read while the job types of the listeners are still the ones the modeller typed,
      // and before the listeners VanillaBP writes itself are in the model: wireBpmn adds
      // those, so a collection after it could not tell the two apart by anything but their
      // prefix
      refuseAStartListenerTheClusterRefuses(workflowModuleId, filename, model);
      readTheListenersTheModelCarries(workflowModuleId, filename, model, context);
      // the names this file declares which the workflow module scopes, read while they are
      // still the ones the application knows: the rewrite below replaces them in the model,
      // and the core is handed the plain ones
      context.recordIdentifiersAModelDeclares(Camunda8Scoping.moduleWideIdentifiersOf(model));
      context
          .recordIdentifiersAModelDeclares(
              Camunda8Scoping.taskDefinitionsOf(model, workflowModuleId, allowConnectorsResolver));
      Camunda8Scoping
          .apply(
              model, workflowModuleId, adapterId, scoping, allowConnectorsResolver, (
                  processOfTheListener,
                  jobType) -> listenersAllowedFor(workflowModuleId, processOfTheListener)
                      .allowed() && workflowTaskInvoker
                          .workflowTaskHandlerExists(workflowModuleId, processOfTheListener, jobType));
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

    // the decision travels as bytes: the cluster reads it, this adapter only has to make
    // sure the id it is deployed under matches what the business rule task points at
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
    // deploy command: these bytes are the ones the command sends, so nothing read here can name
    // a decision the cluster never got, while the cluster answers with the id IT knows and the
    // plain one would have to be won back by stripping the prefix off it again
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
    // them in mode 'use-prefix'), while the core is keyed by the plain ones - so the
    // model is searched by the SCOPED process id and the invoker is called with the
    // plain one
    final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);
    // extract the job-worker tasks (zeebe:taskDefinition type = VanillaBP task
    // definition) and validate them against the registered @WorkflowTask methods;
    // throwing here honors the deployment-failure policy
    final var connectorsAreAllowed = connectorsAllowedFor(workflowModuleId, bpmnProcessId).allowed();
    final var tasks = Camunda8TaskWiring.tasksOf(model, scopedBpmnProcessId, connectorsAreAllowed);
    // Camunda-managed user tasks: the V1-compatible lifecycle task
    // listeners are ADDED TO THE MODEL here (wireBpmn is the BPMN-modification
    // stage of the pipeline) - the modified model is what deployResources deploys
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
    // a listener somebody modelled is a task like any other one from here on: prepareBpmn
    // read it while the model was still the modeller's, and routing it through the core's
    // validation is what gets both directions for free - a listener nothing serves ends the
    // boot, and a method serving no listener of any wired process is reported
    final var listenersOfThisProcess = context
        .getModelledListeners()
        .stream()
        .filter(listener -> bpmnProcessId.equals(listener.bpmnProcessId()))
        .toList();
    listenersOfThisProcess
        .stream()
        .map(listener -> new BpmnTaskSpec(listener.elementId(), listener.taskDefinition()))
        .forEach(specs::add);
    // the core's validation below knows nothing about element templates, and a Camunda 8
    // sentence in its message would be wrong for every other BPMS. So the adapter says
    // the missing half first, and the developer reads the guidance above the failure
    if (!connectorsAreAllowed) {
      guideTowardsAllowingConnectors(workflowModuleId, bpmnProcessId, scopedBpmnProcessId, model);
    }
    workflowTaskWiring.validateTaskWiring(workflowModuleId, bpmnProcessId, specs);
    // a listener job is completed the moment the method returns, so a method declaring
    // @TaskId would wait for a completion nobody can send. Asked of the core here, where a
    // modeller can still change the model, rather than at the first job
    refuseAsynchronousListenerMethods(workflowModuleId, bpmnProcessId, listenersOfThisProcess);
    // The same extraction serves the models of OLDER versions the cluster
    // still holds, so both directions see a model the same way
    processVersions.setTasksOfModel(this::taskSpecsOf);

    // The cluster can be asked which versions of this process it has, which
    // is what a version specification naming a version TAG needs
    workflowTaskWiring
        .registerProcessVersions(adapterId, workflowModuleId, bpmnProcessId, processVersions);

    // Which elements can put a second token into a running workflow - two
    // tokens are two writers on the workflow aggregate, and the core knows whether
    // that aggregate can survive them
    workflowTaskWiring
        .reportConcurrentTokenElements(
            workflowModuleId,
            bpmnProcessId,
            Camunda8TaskWiring.concurrentTokenElementIdsOf(model, scopedBpmnProcessId));

    // the user tasks version 1 modelled up to its release 1.6.3 are served by
    // nothing here and would be silent - so they are counted and named
    reportLegacyUserTasks(
        workflowModuleId,
        bpmnProcessId,
        scopedBpmnProcessId,
        Camunda8TaskWiring.legacyUserTaskIdsOf(model, scopedBpmnProcessId));

    // an ad-hoc subprocess waiting for a job worker is the other element which would
    // stop a workflow without anything being said about it
    reportUnservedAdHocSubProcesses(
        workflowModuleId,
        bpmnProcessId,
        Camunda8TaskWiring.unservedAdHocSubProcessIdsOf(model, scopedBpmnProcessId));

    // message correlation: inject the correlation-key expression
    // '=<aggregate-ID variable>' into message subscriptions lacking one - the V2
    // convention enabling ProcessService#correlateMessage without manual model
    // tweaks (existing expressions stay untouched, V1 models deploy unchanged).
    // Asking the core outright is safe here: prepareBpmn refused the whole file
    // where a process waiting for a message has no workflow aggregate to name
    Camunda8TaskWiring.wireMessageSubscriptions(
        model,
        scopedBpmnProcessId,
        () -> workflowTaskWiring.resolveWorkflowAggregateIdName(workflowModuleId, bpmnProcessId));
    // multi-instance: the input mappings which make the element, the index
    // and the total of every iteration readable from a job are ADDED TO THE MODEL
    // here, and which iterations enclose which element is remembered for dispatch
    Camunda8MultiInstance
        .wire(model, scopedBpmnProcessId, multiInstanceRegistry);
    // A handler reads the item of an iteration out of the variable the model names in
    // 'inputElement'. An element naming none hands no item over, so the parameter would
    // receive null once a job arrives and nothing would say why. Only this adapter reads
    // the model and only the core scans the handlers, so this is the one place the two
    // halves meet
    refuseHandlersWantingAnItemTheModelHasNot(workflowModuleId, bpmnProcessId, scopedBpmnProcessId, specs);
    context.getTasksToWire().addAll(tasks);
    context.getUserTasksToWire().addAll(userTasks);

    // the start of a workflow: the execution listener deciding what a start means is
    // ADDED TO THE MODEL here as well, on every start event the process itself holds.
    // Only for a process this application serves, though - the listener holds the
    // instance until its job is answered, and for an unclaimed process the core has no
    // workflow service to answer for
    if ((bpmsInitiatedStartInvoker != null) && (aggregateIdNameOf(workflowModuleId, bpmnProcessId) != null)) {
      final var bpmsInitiatedStarts = Camunda8TaskWiring
          .bpmsInitiatedStartsOf(
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
      context.getBpmsInitiatedStartsToWire().addAll(bpmsInitiatedStarts);
    }

    // the end of a workflow is reported only where the application asked for it -
    // a model must not pay for a listener nobody wants. A process this application
    // serves no workflow of is left out even where the end IS wanted, which a workflow
    // module releasing its delivery records on workflow end wants for every process it
    // deploys: the worker answering that listener's job reads the aggregate-ID variable,
    // so a listener without one would stop the workflow at its own end
    // the element id the probe of awarenessOfWorkflow reserved for itself: it asks the
    // engine by sending a modification which names an element no model has, and an id which
    // by accident matches one WOULD BE ACTIVATED instead of refused. So the models are read
    // for it here, where they are deployed, and the probe stays away from such a process
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

    final var theWorkflowCanBeNamed = aggregateIdNameOf(workflowModuleId, bpmnProcessId) != null;
    final var theEndIsReported = (workflowEndedInvoker != null) && workflowEndedInvoker
        .workflowEndedHandlerExists(workflowModuleId, bpmnProcessId) && theWorkflowCanBeNamed;
    // and the cancelation of an instance, which the 8.10 line reports through a listener of
    // its own on the same element and with the same job type. It is worth having for a
    // process this application serves a task of even where nobody declared a
    // @WorkflowEnded method: the core reads the tasks it still believes are open in the
    // instance and reports each of them as canceled, which is the only way an application
    // on this BPMS hears about them at all
    final var theCancelationIsReported = Camunda8CancelListeners
        .theProcessCanReportItsCancellation() && (workflowEndedInvoker != null) && theWorkflowCanBeNamed && (theEndIsReported || servesAnyTaskOf(
            workflowModuleId, bpmnProcessId, specs));
    // the listener holds the instance until its job is answered, so both halves are
    // written only where this adapter opens the worker which answers it. A model carrying
    // a listener nobody serves turns a cancelation into a workflow which never goes away
    // and which raises no incident either
    final var endListenerAttached = theEndIsReported && Camunda8TaskWiring.attachWorkflowEndedListener(model,
        scopedBpmnProcessId);
    final var cancelListenerAttached = theCancelationIsReported && Camunda8TaskWiring
        .attachWorkflowCanceledListener(model, scopedBpmnProcessId);
    if (endListenerAttached || cancelListenerAttached) {
      context.getWorkflowEndedProcessesToWire().add(scopedBpmnProcessId);
    }
    if (!Camunda8CancelListeners
        .theProcessCanReportItsCancellation() && (workflowEndedInvoker != null) && theWorkflowCanBeNamed && (theEndIsReported || servesAnyTaskOf(
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
   * Whether this application serves at least one task of the given BPMN process, which is
   * what makes the cancelation of an instance worth reporting: only such a process can
   * leave a task open which the core would then have to cancel.
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
   * Ends the deployment where a <code>&#64;WorkflowTask</code> method wants the item of a
   * multi-instance element this model never names one for.
   * <p>
   * Judged per task, over the chain of iterations enclosing it which this deployment just
   * recorded. So only elements of THIS process are looked at: a level a caller contributes
   * is linked once the whole workflow module is wired, and it belongs to the model of that
   * caller, where the same question is asked about it.
   * <p>
   * The core is asked by the task definition AND by the element id, which is the pair
   * {@code validateTaskWiring} matches a method against: a method may name either of the
   * two, and a method naming the element id would otherwise be missed.
   * <p>
   * Every finding of the process goes into ONE message, the way the wiring validation
   * reports every unwired task at once - a developer fixing one model should not have to
   * restart to meet the next line of the same defect.
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
      final var wanted = new java.util.LinkedHashSet<String>();
      wanted
          .addAll(workflowTaskWiring
              .multiInstanceElementNames(workflowModuleId, bpmnProcessId, spec.activityId()));
      if (spec.taskDefinition() != null) {
        wanted
            .addAll(workflowTaskWiring
                .multiInstanceElementNames(workflowModuleId, bpmnProcessId, spec.taskDefinition()));
      }
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
   * Ends the deployment of a BPMN file whose executable process waits for a message
   * without saying what to correlate it by, where this application serves no workflow of
   * that process.
   * <p>
   * Camunda 8 accepts no message catch element whose message carries no
   * <code>zeebe:subscription</code>, and it answers with the rejection of the whole FILE,
   * so the processes next to that one would not be deployed either. Where a workflow
   * service claims the process, <code>wireBpmn</code> writes the subscription and
   * correlates by the workflow aggregate's ID, which is decision 5 in the repository's
   * DECISIONS.md. Where none does, there is no aggregate to name, and writing a
   * substitute would change a model this application does not own and hide from the
   * modeller that their process is incomplete. So the boot ends here instead, which is
   * the earlier and clearer half of a failure which happens either way.
   * <p>
   * Asked once per file and before anything of it is rewritten: a message element belongs
   * to the file rather than to one process, so an injection for a process wired earlier
   * would otherwise decide the verdict about a process wired later.
   *
   * @param workflowModuleId The workflow module
   * @param filename The BPMN file, which is what the cluster accepts or rejects
   * @param model The model as it was read
   */
  private void refuseAFileTheClusterWouldReject(
      final String workflowModuleId,
      final String filename,
      final BpmnModelInstance model) {

    for (final var process : model.getModelElementsByType(Process.class)) {
      if (!process.isExecutable()) {
        continue;
      }
      // the model is asked first because it answers for free, while the core has to
      // resolve the ID property of an aggregate to answer at all
      final var elementsWaitingForACorrelationKey = Camunda8TaskWiring
          .messagesWithoutACorrelationKey(model, process.getId());
      if (elementsWaitingForACorrelationKey.isEmpty()) {
        continue;
      }
      // this application serves a workflow of the process, so wireBpmn writes the
      // subscription and what reaches the cluster is complete
      if (aggregateIdNameOf(workflowModuleId, process.getId()) != null) {
        continue;
      }
      throw new IllegalStateException(
          """
              Camunda 8 adapter '%s' does not deploy BPMN file '%s' of workflow module '%s': the \
              cluster would reject the file as a whole, and with it every process the file \
              declares. Its executable process '%s' waits for a message at %s, and Camunda 8 \
              demands a 'zeebe:subscription' with a correlation key on the message of every \
              executable process it is given, whether or not VanillaBP runs that process. \
              VanillaBP writes that subscription for a process one of its @WorkflowService \
              classes claims and correlates by that process' workflow aggregate. No class of \
              this application claims '%s', so there is no aggregate to name. Two ways out: model \
              the correlation key of the message(s) named above (in the modeler: 'Subscription \
              correlation key' on the message), or set isExecutable="false" on process '%s' where \
              nothing is meant to run it."""
              .formatted(
                  adapterId,
                  filename,
                  workflowModuleId,
                  process.getId(),
                  elementsWaitingForACorrelationKey
                      .stream()
                      .map(waiting -> "'%s' (message '%s')"
                          .formatted(waiting.catchElementId(), waiting.messageName()))
                      .collect(Collectors.joining(", ")),
                  process.getId(),
                  process.getId()));
    }

  }

  /**
   * Collects the elements of one BPMN file which a runtime other than this application
   * serves, per executable process, and remembers the key which allowed them.
   * <p>
   * Asked while the file is prepared rather than while a process of it is wired, because
   * the rule reaches further than the wiring does: an ad-hoc subprocess carrying an agent
   * connector, a message throw event and an end event all name a job type, none of them is
   * a task this adapter collects, and all of them are what {@code use-prefix} would
   * otherwise rename.
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
   * This is the half version 1 never had: its boot ended in the core's wiring validation
   * asking for a {@code @WorkflowTask} method for a job type nobody recognises, and the
   * only way from there to {@code allow-connectors} was the documentation. The core's
   * wiring validation, which runs directly after this guidance, still ends the boot, and
   * that is correct: an element nothing serves is a defect until somebody says otherwise.
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
   * One record rather than one per element: a module with eight connectors would produce
   * eight warnings and a reader would skim all of them. It is framed, and nothing else this
   * adapter logs is - a WARN level alone does not survive a boot log where every other line
   * is one line high, and a second framed message would cost this one its effect.
   * <p>
   * There is no key which silences it. {@code accept-unscoped-identifiers} is the precedent
   * for acknowledging a warning away, and it exists because the application can state a
   * fact the adapter cannot check, namely that its identifiers are unique. There is no
   * equivalent fact here: everything below stays true for as long as the connector is in
   * the model, so a key turning it off would only make the loss invisible. See decision 23
   * in the repository's DECISIONS.md.
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
   * A switch nobody needs is worth a sentence rather than a frame: it is on, nothing of
   * this module uses it, and the key to take it off again is what the reader wants.
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
   * The sentence the report carries under {@code use-prefix} and under no other mode, empty
   * elsewhere. Under that mode a passed-over element keeps a job type which is not scoped by
   * anything, which is the very clash the mode exists to avoid, and the report says so
   * rather than leaving the reader to find out.
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
   * Ends the boot over an execution listener the CLUSTER would refuse: a {@code start} listener on
   * a start event.
   * <p>
   * Asked whatever {@code allow-listeners} says, because this is not about who serves the
   * listener. The cluster refuses the whole FILE over it, so every process the file declares is
   * lost, and the message a developer would otherwise read comes from the cluster and names a
   * rule rather than an element. VanillaBP attaches its own listener to a start event on
   * {@code end} for the same reason.
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
   * Asked while the file is prepared rather than while a process of it is wired, for two
   * reasons. The job types are still the ones the modeller typed, so a message can quote
   * them, and the listeners VanillaBP writes itself are not in the model yet - {@code
   * wireBpmn} adds the user-task lifecycle listeners and the start listeners, and a
   * collection running after that would have nothing but a prefix to tell the two apart.
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
      final var listeners = Camunda8Listeners.listenersOf(model, process.getId());
      final var setting = listenersAllowedFor(workflowModuleId, process.getId());
      if (setting.allowed()) {
        // remembered even where the process carries no listener at all, so the report can say
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
      // a job type no method of this application names is served by nothing here, and on this
      // cluster that is not a model the adapter may simply pass over: the cluster creates the job
      // and the workflow stands at it. So it is named, and the boot goes on - a worker somebody
      // else runs may well be the answer, and only the application knows
      final var unserved = listeners
          .stream()
          .filter(listener -> !served.contains(listener))
          .toList();
      sayWhichListenerJobsNothingServes(workflowModuleId, process.getId(), filename, unserved);
      // an element whose 'updating' listener nothing here answers is an element the open
      // task check must not probe: the empty update fires that listener and nobody closes
      // its job. The id is the plain one already - this runs while the file is prepared,
      // before name-clash avoidance rewrites anything
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
   * Whether a {@code @WorkflowTask} method of this application names the listener's job type.
   * <p>
   * This is the line which decides whether a listener is this application's business. A job type
   * is a name in the cluster and anybody may subscribe to it - another application, a connector
   * runtime, a worker somebody runs beside this one - so a model carrying one says nothing about
   * who serves it. A method naming it does.
   * <p>
   * Only the task-definition route counts: {@code @WorkflowTask(id = ...)} names the ELEMENT, and
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
   * Names the listeners of a process whose job type nothing of this application serves, and lets
   * the boot go on.
   * <p>
   * A warning rather than a refusal, like the one about an ad-hoc subprocess waiting for a job
   * worker: the application may well run a worker of its own for that job type, or somebody else
   * may, and nothing a boot can ask tells the two apart from a model nobody serves. What must not
   * happen is silence, because the cluster creates the job either way and the workflow stops at it
   * with no incident and nothing in any log.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param filename The file the model was read from
   * @param listeners The listeners nothing here serves, empty for a model where there are none
   */
  private void sayWhichListenerJobsNothingServes(
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
            listener(s) whose job type no @WorkflowTask method of this application names: {}. The \
            cluster creates a job for every one of them, so a workflow reaching the element stands \
            there until something with that job type takes the job - with no incident and nothing in \
            any log. Either write a @WorkflowTask method named after the job type and set '{}', or \
            make sure a worker of your own subscribes to it.""",
        adapterId,
        bpmnProcessId,
        workflowModuleId,
        filename,
        listeners.size(),
        listeners
            .stream()
            .map(Camunda8Listeners.ModelledListener::describe)
            .collect(Collectors.joining("; ")),
        Camunda8Listeners.propertyKeyOf(adapterId));

  }

  /**
   * The message which ends a boot over a model whose listeners nobody allowed.
   * <p>
   * Here the adapter refuses rather than leaving it to the core's wiring validation, which is
   * what {@link #guideTowardsAllowingConnectors} does: a connector is an element VanillaBP is
   * asked to LEAVE ALONE, so the validation finds a task nothing serves and ends the boot by
   * itself. A listener is the other way round - the key asks VanillaBP to serve something, and
   * without it there is no task spec, nothing for the validation to miss, and the workflow
   * would stop at the listener's job on the cluster with nothing in the log. So the refusal is
   * written here, in the words the report uses.
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
   * This is the defect version 1 left open, from the other side: there two listeners of one
   * element became two entries with the same identity and which of them ran was undefined.
   * Here they would become one task served by one method, called for two events, and nothing
   * it could ask would say which event it is in - {@code TaskEvent.Event} has no value for a
   * listener's event. A job type per event is the fix, and naming the case is better than
   * picking one of them.
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
   * The cluster completes a listener job when the handler returns - the transition the
   * listener sits in waits for nothing else - so a method which wants to keep the task open
   * would wait for a completion no application can send. Version 1 accepted such a method and
   * the workflow went on without it.
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
   * Framed and shaped like {@link #reportWhatConnectorsCost}, and for the same reason: one
   * record rather than one per listener, and no key which silences it. What it says stays true
   * for as long as the listener is in the model, so a key turning it off would only make the
   * loss invisible. See decision 27 in the repository's DECISIONS.md.
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
   * Says that a BPMN process still carries user tasks in the shape VanillaBP 1 modelled
   * them up to its release 1.6.3, and how many of them are open on the cluster right
   * now.
   * <p>
   * A WARN rather than a failed deployment. The model itself is valid, the workflow runs,
   * and an application may deliberately serve such a task with a job worker of its own -
   * what does NOT happen is anything by VanillaBP: no CREATED notification, and
   * <code>completeUserTask</code> cannot complete the task, because version 1 handed out
   * the job's key while the cluster expects a user-task key. Being silent about that was
   * the defect; ending the boot over it would be the other one.
   * <p>
   * Two numbers are reported and only the first one is certain. The elements come from
   * the model this boot deploys and are what has to reach zero; the count of open tasks is
   * a search, and this report runs while the module is wired, which is before the start
   * has waited for its cluster - so a cluster which is not up yet costs the number.
   *
   * @param workflowModuleId The workflow module id
   * @param bpmnProcessId The plain BPMN process id
   * @param scopedBpmnProcessId The process id as the cluster knows it
   * @param elementIds The user tasks found, empty for every model written since 1.7.0
   */
  private void reportLegacyUserTasks(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final List<String> elementIds) {

    if (elementIds.isEmpty()) {
      return;
    }
    final var openTasks = countOpenLegacyUserTasks(scopedBpmnProcessId);
    log.warn(
        """
            Camunda8[{}]: {} user task(s) of BPMN process '{}' (workflow module '{}') are modelled \
            the way VanillaBP 1 modelled them up to its release 1.6.3 - a plain BPMN user task whose \
            'zeebe:formDefinition' names a formKey, served by a job worker on '{}': {}. This version \
            does not serve them, and it says so rather than failing the deployment, because the \
            process itself is fine. What you lose for each of them: no notification when the task is \
            created or canceled, and 'ProcessService#completeUserTask' cannot complete it, because \
            the id such a task hands out is a job key while the cluster expects a user-task key. \
            VanillaBP 1.7.0 replaced this construction, so the way out is the model: make the user \
            task a Camunda-managed one ('zeebe:userTask') and set 'External form reference' \
            (zeebe:formDefinition externalReference) to what the formKey said - VanillaBP then wires \
            its lifecycle listeners itself. {} Finish or cancel the tasks which are still open BEFORE \
            you rely on this application to complete them, because afterwards nothing can.""",
        adapterId,
        elementIds.size(),
        bpmnProcessId,
        workflowModuleId,
        Camunda8TaskWiring.TASKDEFINITION_USERTASK_WORKER_V1,
        String.join("', '", elementIds.stream().map("'%s'"::formatted).toList()),
        openTasks == null
            ? "The cluster did not answer how many of them are open right now."
            : "Open right now: %d.".formatted(openTasks));

  }

  /**
   * The one WARN about an ad-hoc subprocess this adapter cannot serve.
   * <p>
   * The element deploys and the workflow runs up to it. There it stops, because the job
   * of the subprocess is activated and nothing fetches it, and once the retries of that
   * job are used up the cluster raises an incident. Nothing later in the boot detects
   * that, which is why it is said here.
   * <p>
   * It is said and the boot goes on. The model is a defect the developer has to see, not
   * a reason to keep an application down whose other processes are fine, and the same
   * choice was made for the BPMN process nobody serves. See decision 24 in the
   * repository's DECISIONS.md.
   *
   * @param workflowModuleId The workflow module id
   * @param bpmnProcessId The plain BPMN process id
   * @param elementIds The ad-hoc subprocesses waiting for a worker, empty for every other
   *          model
   */
  private void reportUnservedAdHocSubProcesses(
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<String> elementIds) {

    if (elementIds.isEmpty()) {
      return;
    }
    log.warn(
        """
            Camunda8[{}]: {} ad-hoc subprocess(es) of BPMN process '{}' (workflow module '{}') carry \
            a 'zeebe:taskDefinition' of their own: {}. That is the flavour where a job worker decides \
            round by round which of the inner activities to activate, and VanillaBP does not serve \
            it: a @WorkflowTask method has no way to name the elements it wants activated, so this \
            adapter opens no worker for such an element. What it costs: a workflow reaches the \
            subprocess and stops there, and the job the cluster activated ends in an incident once \
            its retries are used up. Two ways out. Let the MODEL say which activities to run: put \
            their element ids into 'zeebe:adHoc activeElementsCollection' and let a task of your \
            application fill the workflow-aggregate attribute that expression reads - the activities \
            inside the element are then ordinary tasks with ordinary @WorkflowTask methods behind \
            them. Or leave the element to a runtime which does serve it, a connector or the Camunda \
            AI agent: such an element carries a 'zeebe:modelerTemplate' as well, and one carrying \
            that attribute is passed over here without a word.""",
        adapterId,
        elementIds.size(),
        bpmnProcessId,
        workflowModuleId,
        String.join(", ", elementIds.stream().map("'%s'"::formatted).toList()));

  }

  /**
   * How many jobs of version 1's user-task type the cluster still holds for one process -
   * the number which goes to zero as those tasks are finished.
   *
   * @param scopedBpmnProcessId The process id as the cluster knows it
   * @return The count, or <code>null</code> where the cluster did not answer
   */
  private Long countOpenLegacyUserTasks(
      final String scopedBpmnProcessId) {

    try {
      // the TOTAL rather than the page which came back, and one item fetched because
      // only the number is wanted
      final var found = clientFactory
          .getClient()
          .newJobSearchRequest()
          .filter(filter -> filter
              .processDefinitionId(scopedBpmnProcessId)
              .type(Camunda8TaskWiring.TASKDEFINITION_USERTASK_WORKER_V1))
          .page(page -> page.limit(1))
          .send()
          .join();
      return found.page().totalItems();
    } catch (final RuntimeException e) {
      // a diagnostic never fails a deployment, and a cluster which cannot answer
      // says so in the message instead
      log.debug(
          "Camunda8[{}]: the cluster did not answer how many version-1 user tasks of '{}' are open",
          adapterId,
          scopedBpmnProcessId,
          e);
      return null;
    }

  }

  /**
   * The tasks of ONE model the cluster holds, as the core validates them - the
   * startup check about older versions reads them through the version catalog. The
   * user tasks are read without any refusal: the check's subject is a model an
   * earlier application deployed, and refusing what is only being read would cost
   * the check its answer over a model nobody can change any more.
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
    Camunda8TaskWiring
        .tasksOf(model, scopedBpmnProcessId, connectorsAllowedFor(workflowModuleId, bpmnProcessId).allowed())
        .stream()
        .map(task -> new BpmnTaskSpec(
            task.activityId(), plainTaskDefinition(workflowModuleId, bpmnProcessId, task.taskDefinition())))
        .forEach(specs::add);
    Camunda8TaskWiring
        .userTasksOfHeldModel(model, scopedBpmnProcessId)
        .stream()
        .map(userTask -> BpmnTaskSpec.userTask(
            userTask.activityId(),
            plainTaskDefinition(workflowModuleId, bpmnProcessId, userTask.externalFormReference())))
        .forEach(specs::add);
    // the listeners of a version the cluster still holds are tasks here as well, so a method
    // serving one of them is not reported as unwired while workflows still run on that version.
    // Only where the key allows them: a held model's listener which nobody asked to serve is
    // nothing this application ever had a method for
    if (listenersAllowedFor(workflowModuleId, bpmnProcessId).allowed()) {
      Camunda8Listeners
          .listenersOf(model, scopedBpmnProcessId)
          .stream()
          .map(listener -> new BpmnTaskSpec(
              listener.elementId(), plainTaskDefinition(workflowModuleId, bpmnProcessId, listener.taskDefinition())))
          // the same gate the deployed model passes: only a listener a method names is a task of
          // this application, and a held model may carry one nobody here ever served
          .filter(
              spec -> (spec.taskDefinition() != null) && workflowTaskInvoker
                  .workflowTaskHandlerExists(workflowModuleId, bpmnProcessId, spec.taskDefinition()))
          .forEach(specs::add);
    }
    return specs;

  }

  /**
   * Reads which process of this workflow module calls which other one, and hands the graph
   * to the multi-instance registry so an element of a called process gets the chain of its
   * call site in front of its own.
   * <p>
   * Two call activities are left out. One whose <code>zeebe:calledElement processId</code>
   * is an expression names its process per instance, which a deployment cannot resolve.
   * One calling a process with a workflow aggregate of its own is not decomposition: such a
   * process runs a business case of its own and is not told the iteration of whoever
   * started it, which is what the core answers with
   * {@code workflowsShareTheWorkflowAggregate}.
   * <p>
   * Where the call activity is kept, the model is also told that the caller's variables
   * travel, unless the model already said they do not. That is
   * {@link Camunda8MultiInstance#theCallersVariablesReachTheCalledProcess}, and a call
   * activity which switched them off stays out of the graph.
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
          // the model may say that the caller's variables stay behind, and then nothing of
          // the caller's iteration arrives - so nothing of it is registered either, rather
          // than promising a chain whose values the cluster will never copy
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

    // a task in a called process runs inside the iterations its CALL ACTIVITY sits in, and
    // only the caller's model says which those are. Every file of this module is wired by
    // now, so this is the first moment the graph over them is complete. Before the cluster
    // is asked anything: a model this refuses is refused without one
    wireTheProcessesThisModuleCalls(workflowModuleId, bpmsProcessingContext);

    // A cluster booting together with the application lets every round of the start fail,
    // so it is waited for here: once per adapter instance, and right before the first
    // round which decides anything - the searchable-cluster check below, which would end
    // the boot of an application whose cluster is merely coming up with it (see
    // io.vanillabp.camunda8.client.Camunda8ClusterWait). An adapter with nothing to
    // deploy makes no such round and therefore waits for nothing
    clientFactory.waitUntilTheClusterAnswers();

    // and now that a cluster HAS answered, whether it answers a SEARCH: this adapter
    // serves no other kind. Per module rather than once per adapter id, because the
    // throw is what the deployment-failure policy of THIS module reads
    Camunda8SearchableClusterCheck
        .requireAClusterWhichCanBeSearched(adapterId, clientFactory.getQueryApi());

    // one DeployResourceCommand per workflow module with all its models
    final var client = clientFactory.getClient();
    DeployResourceCommandStep2 command = null;
    for (final var resource : bpmsProcessingContext.getResources().entrySet()) {
      final DeployResourceCommandStep1 next = command != null ? command : client.newDeployResourceCommand();
      command = next.addProcessModel(resource.getValue(), resource.getKey());
    }
    // the module's decision tables go into the SAME command: a business rule task binding
    // its decision to the deployment finds it, and both are versioned together
    for (final var decision : bpmsProcessingContext.getDecisions().entrySet()) {
      final DeployResourceCommandStep1 next = command != null ? command : client.newDeployResourceCommand();
      command = next.addResourceBytes(decision.getValue(), decision.getKey());
    }

    // Which tenant a workflow module is deployed to is decided by the
    // name-clash-avoidance mode - 'by-adapter' (the default, version 1's behavior)
    // uses the workflow module id, overridable by the adapter's 'tenant-id';
    // 'use-prefix' and 'none' use no tenant at all (the identifiers were prefixed
    // respectively are unique by contract).
    validateTenantConfiguration(workflowModuleId);
    final var tenantId = tenantIdOf(workflowModuleId);
    if (tenantId != null) {
      // asking beforehand turns the cluster's "multi-tenancy is disabled" respectively an
      // unknown tenant into a message naming the property to change (GAPS G2)
      if (verifiedTenants.add(tenantId)) {
        Camunda8TenantCheck
            .requireUsableTenant(adapterId, workflowModuleId, tenantId, client);
      }
      command = command.tenantId(tenantId);
    }
    // two BPMN processes may not reach the cluster under one identifier. The processes of
    // THIS module are what can be handed over, because a deployment is per workflow module;
    // the core holds what the earlier modules of this boot brought and asks this adapter
    // whether its tenants keep two modules apart. Before the command is sent, so a module
    // colliding with an earlier one is refused rather than deployed and then named
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

    // what the cluster made of this deployment, collected while it is read anyway: the
    // question which identifiers the cluster ALREADY held needs the version it assigned and
    // the resource it recorded, and both are in the answer of the deploy command
    final var processesDeployed = new ArrayList<Camunda8IdentifiersTheClusterHolds.DeployedProcess>();
    final var decisionsDeployed = new ArrayList<Camunda8IdentifiersTheClusterHolds.DeployedDecision>();
    try {
      final var deployment = command
          .send()
          .join();
      // remember what was deployed: the viewer API serves definitions and BPMN XML
      // from these models instead of the eventually consistent query API (see
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
            // the cluster reports the id IT knows; the viewer API is keyed by the
            // PLAIN one, like every other core-facing identifier
            final var plainBpmnProcessId = NameClashAvoidanceSupport
                .plainProcessId(scoping, workflowModuleId, process.getBpmnProcessId(), adapterId);
            clientFactory
                .getDeployedProcesses()
                .record(
                    new Camunda8DeployedProcesses.DeployedProcess(
                        workflowModuleId, plainBpmnProcessId, String
                            .valueOf(process.getProcessDefinitionKey()), process.getVersion(), model));
            // The version the cluster just assigned, together with the
            // version tag of the model deployed - no query needed for either
            processVersions.recordDeployedScoped(process.getBpmnProcessId(), process.getVersion());
            processVersions
                .recordDeployed(
                    workflowModuleId,
                    plainBpmnProcessId,
                    process.getVersion(),
                    Camunda8TaskWiring.versionTagOf(model, process.getBpmnProcessId()));
            // The border between the model this boot brought and the older
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
          + "(adapter '{}', deployment key {}, tenant '{}'): {}",
          bpmsProcessingContext.getResources().size(),
          workflowModuleId,
          adapterId,
          deployment.getKey(),
          tenantId != null && !tenantId.isBlank() ? tenantId : "<default>",
          bpmsProcessingContext.getResources().keySet());
    } catch (final RuntimeException e) {
      throw new IllegalStateException(
          "Failed to deploy BPMN resources of workflow module '%s' to Camunda 8 (adapter '%s')!"
              .formatted(workflowModuleId, adapterId), e);
    }

    // which of this module's identifiers the cluster held before this deployment, asked now
    // that it answered which versions and resources it recorded
    reportWhatTheClusterAlreadyHeld(workflowModuleId, processesDeployed, decisionsDeployed);
    // and which of them a second workflow module of this application uses as well, which
    // costs no request at all: the names were read off the files themselves
    reportWhatTheModelsDeclare(workflowModuleId, bpmsProcessingContext);

    // last, and after every file of the module has been read, so the report names every
    // element at once instead of one warning per file
    reportWhatConnectorsCost(workflowModuleId, bpmsProcessingContext);

    // and what the listeners somebody modelled cost, the same way
    reportWhatListenersCost(workflowModuleId, bpmsProcessingContext);

    // and what this release line cannot say about an instance which was canceled
    reportWhatACancelationCannotSay(workflowModuleId, bpmsProcessingContext);

  }

  /**
   * Says that an instance of this workflow module which is CANCELED tells the application
   * nothing, which is what every line before 8.10 does.
   * <p>
   * The gap is named per workflow module and on every boot, because it stays true for as
   * long as the application runs on this line: a workflow terminated through the API leaves
   * the tasks the application believes are open in it open forever, and nothing else in the
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
   * Hands the core the identifiers of this workflow module which the cluster already held,
   * so the warning can name our side, their side and the change which frees the name.
   * <p>
   * Wrapped from the outside as well as inside: a question about a name must not be the
   * reason an application does not come up, and the deployment this runs after has already
   * succeeded.
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
   * Hands the core the identifiers the models of this workflow module declare, which is how
   * two workflow modules of this application ending up under one name get named. The
   * adapter rewrites every one of those names while it scopes a BPMN model or a decision
   * table, so it holds all of them and the question costs no request.
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
   * The options every worker of this adapter shares, applied by {@link Camunda8Workers} so
   * that a worker an EXTENSION opens on the same cluster carries them too.
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
   * Opens this worker with a lease on every activation, where the application asked for
   * one.
   * <p>
   * A leased activation is answered by whoever carries its token, so the run which
   * finished last is the one the workflow continues with. It is a ratchet, per job: no
   * command removes a lease and a worker of the same job type which does not lease never
   * sees a leased job again, which is why the application decides and there is no default,
   * see decision 36 in the repository's DECISIONS.md.
   * <p>
   * This is the answer for a worker which holds its job from the activation to the answer:
   * the listeners, the start events the cluster fires and the end of a workflow. A worker
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
   * The same, for a worker which serves tasks: it leases only where none of the tasks it
   * serves can stay open.
   * <p>
   * An ASYNCHRONOUS task is completed in phase two, hours after the activation and by a
   * dispatcher which holds no token, so a leased job of such a task could never be
   * completed at all. The question is asked per job type and not per task, because a
   * worker subscribes to a job type: one task definition it serves which wants to stay
   * open is enough for the whole worker to activate without a lease.
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
   * events the cluster fires itself and the processes whose end is reported. All three run
   * application code inside a transaction exactly like a task does, so they are resolved the
   * way a task's <code>job-timeout</code> is - at adapter, workflow-module and workflow
   * level, there being no task to key them by - and they default to the same five minutes.
   * <p>
   * A worker subscribes by job type, and one user-task listener job type may belong to
   * several BPMN processes of the module. Where those resolve to different locks the
   * deployment fails guiding, the same way conflicting job timeouts of one task definition
   * do.
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
   * The variable a BPMN process carries the workflow aggregate's ID in - the one variable
   * every worker of this adapter reads. A BPMN file may carry a process no
   * <code>&#64;WorkflowService</code> class claims, and the wiring validation lets such a
   * process pass rather than ending the boot over a model somebody else owns, so this can
   * be asked about a process the core knows no aggregate for.
   * <p>
   * Answering <code>null</code> is what lets each caller decide what to do about it. A
   * worker asks for every variable instead of building a list which is missing exactly the
   * name its handler reads, and the end of such a workflow is not reported at all, because
   * an execution listener whose job nobody activates would stop the workflow at its own
   * end. Where the missing name is not this adapter's to work around it refuses the file
   * instead, which is what a message subscription without a correlation key gets: the
   * cluster demands one of every executable process, and a substitute would rewrite a
   * process this application does not serve.
   *
   * @param workflowModuleId The workflow module
   * @param plainBpmnProcessId The BPMN process id as the core knows it
   * @return The variable's name, or <code>null</code> if the core cannot tell
   */
  private String aggregateIdNameOf(
      final String workflowModuleId,
      final String plainBpmnProcessId) {

    try {
      return workflowTaskWiring.resolveWorkflowAggregateIdName(workflowModuleId, plainBpmnProcessId);
    } catch (final RuntimeException e) {
      log.debug(
          "Camunda8[{}]: the BPMN process '{}' of workflow module '{}' has no known workflow "
              + "aggregate, so nothing which needs its aggregate-ID variable is wired for it",
          adapterId,
          plainBpmnProcessId,
          workflowModuleId,
          e);
      return null;
    }

  }

  /**
   * What one worker asks the cluster for: the union of the aggregate-ID
   * variables, multi-instance contexts and declared <code>&#64;TaskParam</code> names of
   * everything it serves, unless a level of the configuration says <code>all</code>.
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
      final var mode = Camunda8FetchVariablesResolver
          .resolve(fetchVariablesResolver, workflowModuleId, plainBpmnProcessId, element.taskDefinition());
      if (mode == Camunda8FetchVariables.Mode.ALL) {
        // one worker serves a job type, so the two values cannot both apply - and
        // fetching more than derived is never wrong, only more expensive
        return Camunda8FetchVariables.Selection.everything();
      }
      final var aggregateIdName = aggregateIdNameOf(workflowModuleId, plainBpmnProcessId);
      if (aggregateIdName == null) {
        return Camunda8FetchVariables.Selection.everything();
      }
      if (element.elementId() == null) {
        // the workflow-end listener: it reports a process rather than an element, and a
        // @WorkflowEnded method cannot declare a @TaskParam at all (the core rejects one),
        // so the aggregate's id is the complete answer here
        Camunda8FetchVariables.collect(variables, aggregateIdName, List.of());
        continue;
      }
      Camunda8FetchVariables.collect(
          variables,
          aggregateIdName,
          multiInstanceRegistry.chainOf(element.scopedBpmnProcessId(), element.elementId()));
      // and what the handlers of this element read with @TaskParam: the core
      // scanned those names off the methods while wiring, so the list is what the
      // application asks for rather than what the model happens to mention.
      // Asked with BOTH keys a method can be wired by, the way the check for a
      // multi-instance item asks them: a method naming the element id serves this
      // element too, and asking for the job type alone left its variables unfetched -
      // which failed the job rather than passing null, because the worker refuses a
      // @TaskParam it did not fetch
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
   * One BPMN element a worker serves - what the fetch list is derived from.
   *
   * @param scopedBpmnProcessId The BPMN process id as the CLUSTER knows it (the
   *          multi-instance registry is keyed by it)
   * @param elementId The BPMN element id, or <code>null</code> where the worker serves a
   *          whole process rather than an element (the workflow-end listener), which is
   *          also the case where no <code>&#64;TaskParam</code> can occur
   * @param taskDefinition The task definition as the CORE knows it, or <code>null</code>
   *          where there is no task level to configure
   */
  record ServedElement(String scopedBpmnProcessId,
                       String elementId,
                       String taskDefinition) {
  }

  /**
   * Tells the worker what to ask for and says so once per worker, at DEBUG: when
   * somebody reports a variable their handler does not see any more, this line is the
   * first question answered.
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

    // one polling worker per (adapter id, task definition): the job type routes
    // deliveries; tasks of DIFFERENT processes sharing a task definition are
    // served by one worker (job.getBpmnProcessId() routes to the right handlers).
    // The job timeout is resolved most-specific-wins - a task definition used by
    // several tasks with CONFLICTING configured timeouts fails guiding.
    final var timeoutsByDefinition = new LinkedHashMap<String, Duration>();
    // what each worker serves, which is what its fetch list is the union over
    final var servedByJobType = new LinkedHashMap<String, List<ServedElement>>();
    final var client = clientFactory.getClient();
    // what this module has in flight, and later whether it is going down: every handler
    // registers its delivery here, and stopWorkflowProcessing waits for them
    final var drain = clientFactory.freshDrainOf(workflowModuleId);
    // and the client learns that this module has workers open, so a shutdown path which
    // never reaches stopWorkflowProcessing does not close the client under them
    shutdownRegistrations
        .put(
            workflowModuleId,
            clientFactory
                .workflowModuleStarted(
                    workflowModuleId,
                    () -> stopWorkflowProcessing(workflowModuleId, bpmsProcessingContext)));
    // what asks the cluster about the OTHER tasks the core believes are open in a workflow,
    // whenever this module serves a job of it. One per module, handed to the three handlers
    // which are a wake-up: a second copy of the rule would be a second answer to one
    // question. It knows whether this module carries Camunda-managed user tasks, because a
    // user-task key handed to a job command answers NOT_FOUND and would read as gone
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
          // the records carry what the CLUSTER knows (the worker subscribes to it),
          // but the configuration is keyed by the PLAIN names
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
    // user-task lifecycle listeners: one worker per distinct listener
    // job type; listener jobs are consumed like normal jobs
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
        // with 'by-adapter': jobs of a tenant are only delivered to workers
        // subscribing for that tenant
        listenerWorkerBuilder = listenerWorkerBuilder.tenantId(listenerTenantId);
      }
      final var worker = listenerWorkerBuilder.open();
      bpmsProcessingContext.getOpenWorkers().add(worker);
      log.info(
          "Camunda8[{}]: opened user-task listener worker for '{}' of workflow module '{}'",
          adapterId,
          listenerJobType,
          workflowModuleId);
    });

    // the listeners somebody modelled: one worker per distinct job type, the same way the
    // user-task listeners above get theirs. The records carry the PLAIN names, because the
    // configuration is keyed by those, so the job type is scoped back here
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
      bpmsProcessingContext.getOpenWorkers().add(builder.open());
      log.info(
          "Camunda8[{}]: opened listener worker for '{}' of workflow module '{}'",
          adapterId,
          jobType,
          workflowModuleId);
    });

    // start events the cluster fires on its own: one worker per start
    // event, since its job type carries the process and the element
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
              // nothing to derive here: VanillaBP copies every variable such a start
              // carries into the workflow aggregate it builds, so a list would decide
              // which of the application's own values survive
              Camunda8FetchVariables.Selection.everything());
          startWorkerBuilder = leaseTheActivations(startWorkerBuilder);
          final var startTenantId = tenantIdOf(workflowModuleId);
          if (startTenantId != null) {
            startWorkerBuilder = startWorkerBuilder.tenantId(startTenantId);
          }
          bpmsProcessingContext.getOpenWorkers().add(startWorkerBuilder.open());
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
              // asking the core outright is safe here: wireBpmn put only processes
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
          bpmsProcessingContext.getOpenWorkers().add(endWorkerBuilder.open());
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
      final var worker = workerBuilder.open();
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
   * The job types this workflow module already has a worker for - the task definitions and
   * user-task listeners of its deployed processes, plus the ends of the processes whose end
   * is reported. What is in here needs no second worker for a declared BPMN process id: the
   * name is what a worker subscribes to, so wherever the name does not carry the process id,
   * the workers of the deployed processes reach the workflows of the old id as well.
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
   * Opens the workers which reach the workflows of a BPMN process this application DECLARES
   * without deploying a model under it - the old id of a renamed process.
   * <p>
   * A worker subscribes to a job type, and under <code>use-prefix</code> a job type carries
   * the id of the process it was deployed with: the jobs of the workflows under the old id
   * are named after the OLD id, so no worker of the deployed processes asks for them and
   * nobody notices, because an unfetched job is not a failed one. What the workflows need is
   * therefore one more subscription per name they produce, and the names are composed the
   * same way the deployed ones were: the task definitions the application serves for that id,
   * scoped by it.
   * <p>
   * Where a job type is already served nothing is opened, which is every mode but
   * <code>use-prefix</code> and <code>use-prefix</code> with
   * <code>prefix-task-definitions-per-process: false</code>. So an application which does not
   * scope task definitions by their process notices none of this, as it did before.
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
          // correlateMessage has to know that models of this module live in the
          // cluster only - so the declared id is recorded in every mode
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
                // a served task definition is either a service task's or a user task's,
                // and which of the two cannot be told without the model this application
                // no longer has. Both subscriptions are opened therefore, and the one
                // whose kind the task never was stays idle - see decision 19 in the
                // repository's DECISIONS.md
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
   * Registers the multi-instance chains of the models the cluster holds under a
   * declared BPMN process id, so a job of those workflows gets its iteration context -
   * index, total and current element - the way a job of a deployed process does. The
   * chains are read from the models the CLUSTER runs, which carry the input mappings
   * VanillaBP wired into them when they were deployed. Where the cluster cannot be
   * asked, the jobs are still served and a task inside an iteration misses its
   * multi-instance values; the picture reported the failed read once.
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
   * The record of a user-task delivery keeps the USER-TASK key, and a job command answers
   * <code>NOT_FOUND</code> for such a key as long as the task is open - which read as gone
   * would cancel a task the cluster is holding out to somebody. The task definition of a
   * user task is the external form reference its listener job type carries, which is what
   * the records of this adapter keep, so the models of this module say per record which
   * kind of task is being asked about.
   * <p>
   * Three answers are {@link Camunda8OpenTaskProbe.KindOfTask#CANNOT_TELL}, and each of
   * them because nothing here narrows it further. A BPMN process this application declares
   * without deploying a model has no model to read, so a record of it may name either kind.
   * A record which kept no task definition names nothing to look up, so it counts as the
   * ambiguous case wherever its process holds a user task at all. And a user task carrying
   * an <code>updating</code> listener no method of this application serves is a task the
   * probe would leave standing in <code>UPDATING</code>, so it is not asked about either. A
   * wrong "gone" costs more than a check which says nothing.
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
   * The multi-instance chains this adapter registered, which is what the processing context
   * hands to an extension, see
   * {@link Camunda8ProcessingContext#getMultiInstanceRegistry()}.
   */
  Camunda8MultiInstance.Registry multiInstanceRegistry() {

    return multiInstanceRegistry;

  }

  /**
   * One polling worker for the tasks of a declared BPMN process id. It asks for every
   * variable rather than a derived list: deriving one needs the elements of the model, and
   * the model of that id is what this application does not have.
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
    bpmsProcessingContext.getOpenWorkers().add(workerBuilder.open());

  }

  /**
   * One worker for the user-task lifecycle listeners of a declared BPMN process id, opened
   * next to the task worker of the same task definition because nothing outside the model
   * says which of the two kinds that definition belonged to. Whichever of the pair the task
   * never was stays idle, which costs one activation request.
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
    bpmsProcessingContext.getOpenWorkers().add(workerBuilder.open());

  }

  /**
   * The worker which reports the end of a workflow running under a declared BPMN process
   * id, opened only where the application has a <code>&#64;WorkflowEnded</code> method for
   * that id. The job type is composed from the process id alone, so this one is exact
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
    bpmsProcessingContext.getOpenWorkers().add(workerBuilder.open());
    openedJobTypes.add(jobType);

  }

  /**
   * Says what the workflows of a declared BPMN process id are served with, once per start
   * and per id: the job types which were opened for it, or that nothing had to be opened
   * because the deployed processes already reach them.
   * <p>
   * A declared id whose methods name no task definition at all is the one case worth a
   * warning. A <code>&#64;WorkflowTask</code> method wired to a BPMN element id
   * (<code>&#64;WorkflowTask(id = ...)</code>) is matched through the model, and the model
   * of that id is what this application does not have, so its job type cannot be composed
   * and those workflows stand still without an incident.
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

    // From here on, a delivery which fails is the shutdown and not the
    // application - the handlers ask the drain before they report anything to the cluster
    final var drain = drainOf(workflowModuleId);
    drain.beginShutdown();

    // close this module's workers (reverse order); the CamundaClient itself is
    // closed by the Camunda8ClientFactory on application shutdown
    final var workers = bpmsProcessingContext.getOpenWorkers();
    for (var i = workers.size() - 1; i >= 0; --i) {
      workers.get(i).close();
    }

    // and then wait until the module is quiet: for the handlers, because closing a worker
    // does not drain it and the client interrupts every running handler when it goes down
    // right afterwards, and for the workers themselves, because an activation
    // request which is parked at the cluster when the client is closed stays parked and
    // swallows the first job of the next application
    final var grace = shutdownGrace();
    final var closedWorkers = workers.size();
    final var outcome = drain.awaitQuiet(
        grace,
        closedWorkers,
        () -> workers.stream().allMatch(JobWorker::isClosed));
    drain.report(grace, outcome);

    workers.clear();
    final var registration = shutdownRegistrations.remove(workflowModuleId);
    if (registration != null) {
      registration.close();
    }
    log.info("Workflow processing stopped for workflow module '{}' (adapter '{}')",
        workflowModuleId, adapterId);

  }

}
