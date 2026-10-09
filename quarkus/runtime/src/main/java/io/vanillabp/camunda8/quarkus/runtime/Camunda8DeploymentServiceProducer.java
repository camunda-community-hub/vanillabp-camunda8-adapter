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
 * Produces the Camunda 8 adapter's {@link Camunda8DeploymentService} instances - ONE
 * per configured adapter id of type {@code camunda8} (the per-adapter-id shape on
 * Quarkus: a CDI producer cannot yield N element beans for N runtime-configured
 * ids), consumed by the VanillaBP Quarkus integration's runtime deployment pipeline.
 * Each instance obtains its {@code CamundaClient} from the
 * {@link Camunda8ClientFactoryRegistry}.
 * <p>
 * Platform contract: the List's element type is the SPI interface with BOTH type
 * parameters literally {@code Object} - regardless of the adapter's actual model
 * ({@code BpmnModelInstance}) and context ({@code Camunda8ProcessingContext})
 * classes: CDI's parameterized-type matching of differing type arguments is not
 * reliable across modes, so the platform looks the beans up with the exact type. The
 * pipeline matches models via {@code getModelType()}/{@code getProcessContextType()},
 * never via the generics. The producer method is {@code @Singleton} (deployment
 * services are not client-proxyable).
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
   * The list is what the platform looks up, because a CDI producer cannot yield one bean per id
   * an application configures at runtime. Its element type carries <code>Object</code> for both
   * type parameters on purpose, see the class javadoc.
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
          // published per adapter id, so an extension asks the adapter how long a job of
          // this adapter stays locked instead of reading the configuration again
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
          // the tenant a workflow module is deployed into, resolved per module with
          // the adapter's own name as the fallback
          deploymentService
              .setConfiguredTenants(
                  workflowModuleId -> overlay.configuredTenantFor(adapterId, workflowModuleId));
          // Which elements of a model this application leaves to the runtime which
          // owns them, resolvable down to the workflow
          deploymentService.setAllowConnectorsResolver((
              workflowModuleId,
              bpmnProcessId) -> overlay.allowConnectorsFor(workflowModuleId, bpmnProcessId, adapterId));
          // Whether the listeners somebody modelled are served by this application,
          // resolvable down to the workflow
          deploymentService.setAllowListenersResolver((
              workflowModuleId,
              bpmnProcessId) -> overlay.allowListenersFor(workflowModuleId, bpmnProcessId, adapterId));
          // Where this adapter says what it found while the application starts:
          // the block both platform integrations write at the end of a start
          deploymentService.setStartupReport(
              startupReport.isResolvable()
                  ? startupReport.get()
                  : null);
          // The client's job counters and this adapter's execution slots,
          // where the application uses the Micrometer extension
          deploymentService.setMetrics(
              metrics.isResolvable()
                  ? metrics.get()
                  : Camunda8Metrics.NONE);
          return deploymentService;
        })
        .toList();

  }

}
