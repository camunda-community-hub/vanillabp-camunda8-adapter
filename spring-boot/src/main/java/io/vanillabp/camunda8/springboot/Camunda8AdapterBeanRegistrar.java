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
 * Registers the beans the Camunda 8 adapter needs per adapter id. For EACH configured
 * adapter id of type {@code camunda8} it registers one {@link Camunda8ProcessService} bean
 * and one {@link Camunda8DeploymentService} bean. Several ids of one BPMS type are the setup
 * for a migration, for example an on-prem cluster and a SaaS cluster side by side.
 * <p>
 * Each bean is a single element, never a bean of type {@code List<...>}. The platform
 * collects the element beans with {@code ObjectProvider.stream()}.
 * <p>
 * The set of ids comes from the configuration at runtime. So the beans are registered in
 * code ({@link BeanRegistrar} and
 * {@link AdapterBeanRegistrarSupport#forEachConfiguredAdapterId}). Each instance gets its
 * adapter id as a CONSTRUCTOR parameter. The bean suppliers are lazy: they fetch the
 * {@link Camunda8ClientFactoryRegistry} from the {@code SupplierContext} only when the bean
 * is created.
 */
public class Camunda8AdapterBeanRegistrar implements BeanRegistrar {

  /**
   * Built by Spring. Spring gets this registrar as a class, so it needs a constructor without
   * arguments.
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
                // the tenant a module's operations run in. It is resolved per workflow
                // module, and the tenant name of the adapter is the fallback
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
                // published per adapter id. An extension asks the adapter how long a job
                // stays locked, so it does not have to read the configuration again
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
                // the tenant a workflow module is deployed into. It is resolved per
                // module, and the tenant name of the adapter is the fallback
                deploymentService
                    .setConfiguredTenants(
                        workflowModuleId -> overlay.configuredTenantFor(adapterId, workflowModuleId));
                // which elements of a model this application leaves to the runtime
                // that owns them; it can be set down to the level of the workflow
                deploymentService.setAllowConnectorsResolver((
                    workflowModuleId,
                    bpmnProcessId) -> overlay
                        .allowConnectorsFor(workflowModuleId, bpmnProcessId, adapterId));
                // whether this application serves the listeners in a model; it can be
                // set down to the level of the workflow
                deploymentService.setAllowListenersResolver((
                    workflowModuleId,
                    bpmnProcessId) -> overlay
                        .allowListenersFor(workflowModuleId, bpmnProcessId, adapterId));
                // where this adapter reports what it found while the application
                // starts: the block both platform integrations write at the end of a start
                deploymentService.setStartupReport(
                    supplierContext
                        .beanProvider(StartupReport.class)
                        .getIfAvailable());
                // the client's job counters and this adapter's execution slots, if the
                // application has Micrometer
                deploymentService.setMetrics(
                    supplierContext
                        .beanProvider(Camunda8Metrics.class)
                        .getIfAvailable(() -> Camunda8Metrics.NONE));
                return deploymentService;
              }));

        });

  }


  /**
   * The time window, set at adapter level, in which the job lock of an open asynchronous
   * task is renewed. The default is one hour. The awareness probe grants the same window.
   */
  private static Duration asyncTaskLockRenewalOf(
      final VanillaBpCamunda8Properties overlay,
      final String adapterId) {

    final var adapterKeys = overlay.getAdapters().get(adapterId);
    return adapterKeys != null
        ? adapterKeys.resolvedAsyncTaskLockRenewal()
        // the full name is needed: this package has a Camunda8AdapterConfiguration of its
        // own (the Spring configuration class), and it wins over an import of the same name
        : io.vanillabp.camunda8.client.Camunda8AdapterConfiguration.DEFAULT_ASYNC_TASK_LOCK_RENEWAL;

  }

}
