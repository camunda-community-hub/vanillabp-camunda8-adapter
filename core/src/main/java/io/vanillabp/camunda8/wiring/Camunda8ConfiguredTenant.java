package io.vanillabp.camunda8.wiring;

import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;

/**
 * A Camunda 8 TENANT name the application configured for one workflow module, together with
 * the property key it was read from. The key travels with the name because a message about a
 * tenant this adapter cannot honor has to quote the line the developer wrote, and that line
 * differs per level.
 * <p>
 * The platform modules build one of these per workflow module and hand it to
 * {@link io.vanillabp.camunda8.deployment.Camunda8DeploymentService} and the process service;
 * what the mode then makes of the name is
 * {@link Camunda8Scoping#tenantIdFor(io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport, String, String, String)}.
 *
 * @param tenantId The configured tenant name, never blank
 * @param propertyKey The full property key the name was read from
 */
public record Camunda8ConfiguredTenant(
                                       String tenantId,
                                       String propertyKey) {

  /** The key which names a tenant, below the adapter section it is read from. */
  public static final String TENANT_ID_KEY = "tenant-id";

  /**
   * The tenant name configured for one workflow module of one adapter id, most specific
   * first: the workflow module, then the adapter. A blank value is read as nothing
   * configured, so an empty key does not deploy a workflow module into a tenant named "".
   * <p>
   * Why there is no per-workflow level: the mode is resolvable per workflow, a tenant is not. A
   * tenant id is an attribute of the DEPLOYMENT and this adapter makes one deployment per
   * workflow module, so two workflows of one module cannot reach the cluster in two tenants. A
   * key which looks honored and is ignored is worse than a key nobody may write, so the
   * workflow level carries none.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module ID
   * @param perWorkflowModule What the workflow module's section says, or <code>null</code>
   * @param perAdapter What the adapter's section says, or <code>null</code>
   * @return The name and its key, or <code>null</code> where neither level configured one
   */
  public static Camunda8ConfiguredTenant firstConfigured(
      final String adapterId,
      final String workflowModuleId,
      final String perWorkflowModule,
      final String perAdapter) {

    if ((perWorkflowModule != null) && !perWorkflowModule.isBlank()) {
      return new Camunda8ConfiguredTenant(
          perWorkflowModule, "vanillabp.workflow-modules.%s.adapters.%s.%s".formatted(workflowModuleId, adapterId,
              TENANT_ID_KEY));
    }
    if ((perAdapter != null) && !perAdapter.isBlank()) {
      return new Camunda8ConfiguredTenant(
          perAdapter, Camunda8AdapterConfiguration.propertyKey(adapterId, TENANT_ID_KEY));
    }
    return null;

  }

}
