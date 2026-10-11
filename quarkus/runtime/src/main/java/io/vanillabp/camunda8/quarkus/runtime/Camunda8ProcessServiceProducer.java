package io.vanillabp.camunda8.quarkus.runtime;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.ConfigProvider;

import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.camunda8.processservice.Camunda8ProcessService;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Provides the {@link Camunda8ProcessService} instances of the Camunda 8 adapter, ONE per
 * configured adapter id of type {@code camunda8}. Several ids of one BPMS type are the setup
 * for a migration, for example an on-prem cluster and a SaaS cluster side by side.
 * <p>
 * A CDI producer cannot yield one bean per id when the ids are only known at runtime. So
 * the adapter produces ONE bean of type <code>List&lt;MigratableProcessService&gt;</code>.
 * Where the platform collects the beans, it flattens list beans together with single
 * beans. Each instance gets its adapter id as a CONSTRUCTOR parameter.
 * <p>
 * The set of adapter ids ALWAYS comes from the platform's core properties
 * ({@code adapterTypes()}). The adapter's configuration overlay is only used to look up an
 * id the core already knows.
 */
@ApplicationScoped
public class Camunda8ProcessServiceProducer {

  /**
   * Built by CDI, which needs a no-arg constructor to proxy an application-scoped bean.
   */
  public Camunda8ProcessServiceProducer() {
  }

  /**
   * Builds one process service per configured adapter id of type <code>camunda8</code>.
   * <p>
   * The platform looks up the list, because a CDI producer cannot yield one bean per id when
   * the application configures the ids at runtime.
   *
   * @param properties The platform's properties, which name the configured adapter ids and
   *          their types
   * @param clientFactoryRegistry Where each service gets the client of its adapter id
   * @param preCommitRegistrar Where phase one hooks its check into the caller's unit of work
   * @param aggregateSync Which aggregate attributes reach the cluster
   * @param scoping How identifiers are kept apart where two adapter ids share a cluster
   * @return One service per configured adapter id of this type
   */
  @Produces
  public List<MigratableProcessService<Object>> camunda8MigratableProcessServices(
      final MigrationAdapterProperties properties,
      final Camunda8ClientFactoryRegistry clientFactoryRegistry,
      final PreCommitRegistrar preCommitRegistrar,
      final WorkflowAggregateSync aggregateSync,
      final NameClashAvoidanceSupport scoping) {

    final var overlay = ConfigProvider
        .getConfig()
        .unwrap(SmallRyeConfig.class)
        .getConfigMapping(VanillaBpCamunda8Properties.class);

    return properties
        .adapterTypes()
        .entrySet()
        .stream()
        .filter(adapter -> Camunda8DeploymentService.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .sorted()
        .<MigratableProcessService<Object>>map(adapterId -> {
          final var adapterKeys = overlay.adapters().get(adapterId);
          final var asyncTaskLockRenewal = adapterKeys != null
              ? adapterKeys
                  .asyncTaskLockRenewal()
                  .orElse(Camunda8AdapterConfiguration.DEFAULT_ASYNC_TASK_LOCK_RENEWAL)
              : Camunda8AdapterConfiguration.DEFAULT_ASYNC_TASK_LOCK_RENEWAL;
          final var processService = new Camunda8ProcessService<>(
              adapterId, clientFactoryRegistry
                  .getFactory(adapterId), asyncTaskLockRenewal, preCommitRegistrar, aggregateSync);
          processService.setScoping(scoping);
          // the tenant a module's operations run in. It is resolved per workflow module,
          // and the tenant name of the adapter is the fallback
          processService
              .setConfiguredTenants(
                  workflowModuleId -> overlay.configuredTenantFor(adapterId, workflowModuleId));
          processService
              .setMessageTimeToLiveResolver((
                  workflowModuleId,
                  bpmnProcessId,
                  messageName) -> overlay
                      .messageTimeToLiveFor(workflowModuleId, bpmnProcessId, messageName, adapterId));
          return processService;
        })
        .toList();

  }

}
