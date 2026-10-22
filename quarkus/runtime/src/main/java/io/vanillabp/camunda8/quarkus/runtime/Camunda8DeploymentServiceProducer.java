package io.vanillabp.camunda8.quarkus.runtime;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.ConfigProvider;

import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.camunda8.observability.Camunda8Metrics;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.runtime.support.AdapterCollaboratorsSupport;
import io.vanillabp.integration.spi.startup.StartupReport;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the {@link Camunda8DeploymentService} instances of the Camunda 8 adapter, ONE per
 * configured adapter id of type {@code camunda8}. They come as one list, because a CDI
 * producer cannot yield one bean per id when the ids are only known at runtime. The
 * deployment pipeline of the VanillaBP Quarkus integration uses them at runtime. Each
 * instance gets its {@code CamundaClient} from the {@link Camunda8ClientFactoryRegistry}.
 * <p>
 * Platform contract: the element type of the list is the SPI interface with BOTH type
 * parameters set to {@code Object}. This holds whatever the adapter's real model class
 * ({@code BpmnModelInstance}) and context class ({@code Camunda8ProcessingContext}) are.
 * The reason: CDI does not match parameterized types with different type arguments
 * reliably in all modes, so the platform looks the beans up with the exact type. The
 * pipeline matches models with {@code getModelType()} and {@code getProcessContextType()},
 * never with the generics. The producer method is {@code @Singleton}, because a CDI
 * client proxy cannot be built for a deployment service.
 */
@ApplicationScoped
public class Camunda8DeploymentServiceProducer {

  /**
   * Built by CDI, which needs a no-arg constructor to proxy an application-scoped bean.
   */
  public Camunda8DeploymentServiceProducer() {
  }

  /**
   * Builds one deployment service per configured adapter id of type <code>camunda8</code>.
   * <p>
   * The platform looks up the list, because a CDI producer cannot yield one bean per id when
   * the application configures the ids at runtime. Its element type has <code>Object</code>
   * for both type parameters on purpose, see the class javadoc.
   *
   * @param properties The platform's properties, which name the configured adapter ids and
   *          their types
   * @param clientFactoryRegistry Where each service gets the client of its adapter id
   * @param workflowTaskRegistry What the application serves, per task
   * @param scoping How identifiers are kept apart where two adapter ids share a cluster
   * @param workflowAggregateSync Which aggregate attributes reach the cluster
   * @param preCommitRegistrar Where phase one hooks its check into the caller's unit of work
   * @param workflowEndedInvoker What is called when a workflow ended, where the application
   *          asked to be told
   * @param bpmsInitiatedStartInvoker What is called when the cluster starts a workflow on its
   *          own
   * @param metrics Where this adapter's own numbers go, absent where the application brings no
   *          Micrometer
   * @param startupReport Where a finding of this adapter goes while the application starts
   * @return One service per configured adapter id of this type
   */
  @Produces
  @Singleton
  @SuppressWarnings({
      "unchecked", "rawtypes"
  })
  public List<AdapterDeploymentService<Object, Object>> camunda8DeploymentServices(
      final MigrationAdapterProperties properties,
      final Camunda8ClientFactoryRegistry clientFactoryRegistry,
      final WorkflowTaskRegistry workflowTaskRegistry,
      final NameClashAvoidanceSupport scoping,
      final WorkflowAggregateSync workflowAggregateSync,
      final PreCommitRegistrar preCommitRegistrar,
      @Any final Instance<WorkflowEndedInvoker> workflowEndedInvoker,
      @Any final Instance<BpmsInitiatedStartInvoker> bpmsInitiatedStartInvoker,
      final Instance<Camunda8Metrics> metrics,
      final Instance<StartupReport> startupReport) {

    final var overlay = ConfigProvider
        .getConfig()
        .unwrap(SmallRyeConfig.class)
        .getConfigMapping(VanillaBpCamunda8Properties.class);

    return (List) properties
        .adapterTypes()
        .entrySet()
        .stream()
        .filter(adapter -> Camunda8DeploymentService.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .sorted()
        .map(adapterId -> {
          final var adapterKeys = overlay.adapters().get(adapterId);
          final var asyncTaskLockRenewal = adapterKeys != null
              ? adapterKeys
                  .asyncTaskLockRenewal()
                  .orElse(Camunda8AdapterConfiguration.DEFAULT_ASYNC_TASK_LOCK_RENEWAL)
              : Camunda8AdapterConfiguration.DEFAULT_ASYNC_TASK_LOCK_RENEWAL;
          final var clientFactory = clientFactoryRegistry.getFactory(adapterId);
          // published per adapter id. An extension asks the adapter how long a job stays
          // locked, so it does not have to read the configuration again
          final Camunda8JobTimeoutResolver jobTimeoutResolver = (
              workflowModuleId,
              bpmnProcessId,
              taskDefinition) -> overlay
                  .jobTimeoutFor(workflowModuleId, bpmnProcessId, taskDefinition, adapterId);
          clientFactory.provideJobTimeoutResolver(jobTimeoutResolver);
          final var deploymentService = new Camunda8DeploymentService(
              adapterId, clientFactory, AdapterCollaboratorsSupport
                  .collaborators(
                      adapterId, workflowTaskRegistry, workflowTaskRegistry, scoping, workflowAggregateSync,
                      preCommitRegistrar, workflowEndedInvoker,
                      bpmsInitiatedStartInvoker), jobTimeoutResolver, asyncTaskLockRenewal, id -> clientFactoryRegistry
                          .getFactory(id)
                          .getConfiguration(), scoping, (
                              workflowModuleId,
                              bpmnProcessId,
                              taskDefinition) -> overlay.configuredRetryBackoffFor(
                                  workflowModuleId, bpmnProcessId, taskDefinition, adapterId));
          // the tenant a workflow module is deployed into. It is resolved per module, and
          // the tenant name of the adapter is the fallback
          deploymentService
              .setConfiguredTenants(
                  workflowModuleId -> overlay.configuredTenantFor(adapterId, workflowModuleId));
          // which elements of a model this application leaves to the runtime that owns
          // them; it can be set down to the level of the workflow
          deploymentService.setAllowConnectorsResolver((
              workflowModuleId,
              bpmnProcessId) -> overlay.allowConnectorsFor(workflowModuleId, bpmnProcessId, adapterId));
          // whether this application serves the listeners in a model; it can be set down
          // to the level of the workflow
          deploymentService.setAllowListenersResolver((
              workflowModuleId,
              bpmnProcessId) -> overlay.allowListenersFor(workflowModuleId, bpmnProcessId, adapterId));
          // whether an incident carries the whole stack trace; it can be set down to the
          // level of the task
          deploymentService.setReportErrorsAsStackTraceResolver((
              workflowModuleId,
              bpmnProcessId,
              taskDefinition) -> overlay
                  .reportErrorsAsStackTraceFor(workflowModuleId, bpmnProcessId, taskDefinition, adapterId));
          // where this adapter reports what it found while the application starts: the
          // block both platform integrations write at the end of a start
          deploymentService.setStartupReport(
              startupReport.isResolvable()
                  ? startupReport.get()
                  : null);
          // the client's job counters and this adapter's execution slots, if the
          // application uses the Micrometer extension
          deploymentService.setMetrics(
              metrics.isResolvable()
                  ? metrics.get()
                  : Camunda8Metrics.NONE);
          return deploymentService;
        })
        .toList();

  }

}
