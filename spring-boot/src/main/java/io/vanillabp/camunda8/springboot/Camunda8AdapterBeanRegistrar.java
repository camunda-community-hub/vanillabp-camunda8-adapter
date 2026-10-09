package io.vanillabp.camunda8.springboot;

import java.time.Duration;

import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.env.Environment;

import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.camunda8.observability.Camunda8Metrics;
import io.vanillabp.camunda8.processservice.Camunda8ProcessService;
import io.vanillabp.camunda8.springboot.client.VanillaBpCamunda8Properties;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.adapter.AdapterBeanRegistrarSupport;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.spi.startup.StartupReport;

/**
 * Registers the Camunda 8 adapter's per-adapter-id beans: for EACH configured adapter
 * id of type {@code camunda8} (multiple ids of one BPMS type = the migration scenario,
 * e.g. an on-prem and a SaaS cluster side by side) one
 * {@link Camunda8ProcessService} <i>element</i> bean and one
 * {@link Camunda8DeploymentService} <i>element</i> bean are registered - never beans
 * of type {@code List<...>}: the platform collects element beans via
 * {@code ObjectProvider.stream()}.
 * <p>
 * The id set comes from the runtime configuration, so the beans are registered
 * programmatically ({@link BeanRegistrar} +
 * {@link AdapterBeanRegistrarSupport#forEachConfiguredAdapterId}); the adapter id is a
 * CONSTRUCTOR parameter of each instance. The bean suppliers are lazy: the
 * {@link Camunda8ClientFactoryRegistry} is resolved through the
 * {@code SupplierContext} at bean-creation time.
 */
public class Camunda8AdapterBeanRegistrar implements BeanRegistrar {

  /**
   * Built by Spring, which is handed this registrar as a class and needs a no-arg constructor
   * to build it.
   */
  public Camunda8AdapterBeanRegistrar() {
  }

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(
        environment,
        Camunda8DeploymentService.ADAPTER_TYPE,
        adapterId -> {

          registry.registerBean(
              "Camunda8_ProcessService_%s".formatted(adapterId),
              Camunda8ProcessService.class,
              spec -> spec.supplier(supplierContext -> {
                final var processService = new Camunda8ProcessService<>(
                    adapterId, supplierContext
                        .bean(Camunda8ClientFactoryRegistry.class)
                        .getFactory(adapterId), asyncTaskLockRenewalOf(
                            supplierContext.bean(VanillaBpCamunda8Properties.class),
                            adapterId), supplierContext
                                .bean(PreCommitRegistrar.class), supplierContext
                                    .bean(WorkflowAggregateSync.class));
                processService.setScoping(
                    supplierContext.bean(NameClashAvoidanceSupport.class));
                final var overlay = supplierContext.bean(VanillaBpCamunda8Properties.class);
                // the tenant a module's operations run in, resolved per workflow module
                // with the adapter's own name as the fallback
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
              }));

          registry.registerBean(
              "Camunda8_DeploymentService_%s".formatted(adapterId),
              Camunda8DeploymentService.class,
              spec -> spec.supplier(supplierContext -> {
                final var overlay = supplierContext.bean(VanillaBpCamunda8Properties.class);
                final var asyncTaskLockRenewal = asyncTaskLockRenewalOf(overlay, adapterId);
                final var clientFactory = supplierContext
                    .bean(Camunda8ClientFactoryRegistry.class)
                    .getFactory(adapterId);
                // published per adapter id, so an extension asks the adapter how long a job
                // of this adapter stays locked instead of reading the configuration again
                final Camunda8JobTimeoutResolver jobTimeoutResolver = (
                    workflowModuleId,
                    bpmnProcessId,
                    taskDefinition) -> overlay
                        .jobTimeoutFor(workflowModuleId, bpmnProcessId, taskDefinition, adapterId);
                clientFactory.provideJobTimeoutResolver(jobTimeoutResolver);
                final var deploymentService = new Camunda8DeploymentService(
                    adapterId, clientFactory, AdapterBeanRegistrarSupport
                        .collaborators(supplierContext,
                            adapterId), jobTimeoutResolver, asyncTaskLockRenewal, id -> supplierContext
                                .bean(Camunda8ClientFactoryRegistry.class)
                                .getFactory(id)
                                .getConfiguration(), supplierContext
                                    .bean(
                                        NameClashAvoidanceSupport.class), (
                                            workflowModuleId,
                                            bpmnProcessId,
                                            taskDefinition) -> overlay.configuredRetryBackoffFor(
                                                workflowModuleId, bpmnProcessId, taskDefinition,
                                                adapterId));
                // the tenant a workflow module is deployed into, resolved per module
                // with the adapter's own name as the fallback
                deploymentService
                    .setConfiguredTenants(
                        workflowModuleId -> overlay.configuredTenantFor(adapterId, workflowModuleId));
                // Which elements of a model this application leaves to the runtime
                // which owns them, resolvable down to the workflow
                deploymentService.setAllowConnectorsResolver((
                    workflowModuleId,
                    bpmnProcessId) -> overlay
                        .allowConnectorsFor(workflowModuleId, bpmnProcessId, adapterId));
                // Whether the listeners somebody modelled are served by this
                // application, resolvable down to the workflow
                deploymentService.setAllowListenersResolver((
                    workflowModuleId,
                    bpmnProcessId) -> overlay
                        .allowListenersFor(workflowModuleId, bpmnProcessId, adapterId));
                // Where this adapter says what it found while the application starts:
                // the block both platform integrations write at the end of a start
                deploymentService.setStartupReport(
                    supplierContext
                        .beanProvider(StartupReport.class)
                        .getIfAvailable());
                // The client's job counters and this adapter's execution slots,
                // where the application brings Micrometer
                deploymentService.setMetrics(
                    supplierContext
                        .beanProvider(Camunda8Metrics.class)
                        .getIfAvailable(() -> Camunda8Metrics.NONE));
                return deploymentService;
              }));

        });

  }


  /**
   * The adapter-level window an open asynchronous task's job lock is renewed in
   * (default one hour) - the window the awareness probe grants as well.
   */
  private static Duration asyncTaskLockRenewalOf(
      final VanillaBpCamunda8Properties overlay,
      final String adapterId) {

    final var adapterKeys = overlay.getAdapters().get(adapterId);
    return adapterKeys != null
        ? adapterKeys.resolvedAsyncTaskLockRenewal()
        // spelled out because this package has a Camunda8AdapterConfiguration of its own,
        // the Spring configuration class, and it wins over any import of the same name
        : io.vanillabp.camunda8.client.Camunda8AdapterConfiguration.DEFAULT_ASYNC_TASK_LOCK_RENEWAL;

  }

}
