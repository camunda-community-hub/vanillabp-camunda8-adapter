package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.TestScoping;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8ConfiguredTenant;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter answers when the core asks whether its own isolation keeps two workflow
 * modules apart. The core puts that question while it looks for two BPMN processes which
 * reach the BPMS under one identifier: under {@code by-adapter} nothing is prefixed, so the
 * core holds two equal strings and only the adapter knows whether the cluster separates the
 * two sides anyway.
 * <p>
 * The scope a Camunda 8 cluster offers is the tenant, so every case below is a pair of
 * tenants. Which tenant a module lands in depends on its mode and on the configured
 * <code>tenant-id</code>, and neither of the two is readable from one property: both are
 * resolved per workflow module, and an unset tenant name under {@code by-adapter} means the
 * workflow module id.
 * <p>
 * No cluster is involved in any of it. The adapter instances built here have no connection
 * configured, so they have no client either, and a question which asked the cluster something
 * would fail instead of answering.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8IsolationSeparatesModulesTest {

  private static final String ONE_MODULE = "loan-approval";

  private static final String ANOTHER_MODULE = "risk-assessment";

  /**
   * An adapter instance whose workflow modules have the given modes and whose
   * <code>tenant-id</code> is the given one (<code>null</code> for unset).
   */
  private Camunda8DeploymentService adapter(
      final Function<String, NameClashAvoidance> modes,
      final String configuredTenantId) {

    return adapter(modes, Map.of(), configuredTenantId);

  }

  /**
   * An adapter instance built the way the platform builds it: the tenant of a workflow module
   * is resolved over the levels the name may stand at, the module's own section first.
   *
   * @param modes The mode per workflow module
   * @param tenantPerWorkflowModule What a module's own section names
   * @param tenantOfTheAdapter What the adapter's section names, or <code>null</code>
   */
  private Camunda8DeploymentService adapter(
      final Function<String, NameClashAvoidance> modes,
      final Map<String, String> tenantPerWorkflowModule,
      final String tenantOfTheAdapter) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setTenantId(tenantOfTheAdapter);
    final var service = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(new Camunda8DeploymentServiceTest.NoOpInvoker()),
        (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofDays(14),
        null, TestScoping.of(modes));
    service
        .setConfiguredTenants(
            workflowModuleId -> Camunda8ConfiguredTenant
                .firstConfigured(
                    "c8",
                    workflowModuleId,
                    tenantPerWorkflowModule.get(workflowModuleId),
                    tenantOfTheAdapter));
    return service;

  }

  private Camunda8DeploymentService adapter(
      final NameClashAvoidance everyModule,
      final String configuredTenantId) {

    return adapter(workflowModuleId -> everyModule, configuredTenantId);

  }

  /**
   * Whether the adapter separates the two modules, asked in both orders: which module
   * deploys first is not the application's choice, so an answer which depended on the order
   * would make the core's refusal depend on it too.
   */
  private boolean separates(
      final Camunda8DeploymentService adapter) {

    final var oneWay = adapter.ownIsolationSeparatesWorkflowModules(ONE_MODULE, ANOTHER_MODULE);
    final var theOtherWay = adapter.ownIsolationSeparatesWorkflowModules(ANOTHER_MODULE, ONE_MODULE);
    assertEquals(
        oneWay,
        theOtherWay,
        "the same two modules, the other order, and a different answer");
    return oneWay;

  }

  @Test
  @DisplayName("The default mode without a tenant name puts the two modules into two tenants")
  public void byAdapterWithoutATenantNameSeparatesTheModules() {

    // 'by-adapter' with nothing configured is what an upgraded version-1 application runs:
    // the tenant is named after the workflow module, so the cluster keeps the two apart and
    // one shared BPMN process id is no clash at all
    assertTrue(separates(adapter(NameClashAvoidance.BY_ADAPTER, null)));

  }

  @Test
  @DisplayName("One tenant-id for the whole adapter puts every module into that one tenant")
  public void oneTenantForTheWholeAdapterSeparatesNothing() {

    // the configuration this question exists for: the name overrides what the module id
    // would have been, both modules reach the same tenant, and nothing tells their
    // processes apart any more
    assertFalse(separates(adapter(NameClashAvoidance.BY_ADAPTER, "one-tenant-for-all")));

  }

  @Test
  @DisplayName("Two modules which reach the cluster without a tenant share the unnamed scope")
  public void modulesWithoutATenantAreNotSeparated() {

    // no tenant is the <default> one, which is a scope like any other: two modules in it
    // are in the SAME scope. Under 'use-prefix' the core composed the identifiers itself
    // and does not ask; the honest answer is still that the cluster separates nothing
    assertFalse(separates(adapter(NameClashAvoidance.USE_PREFIX, null)));
    assertFalse(separates(adapter(NameClashAvoidance.NONE, null)));

    // a tenant name is configured and no module uses one, which the boot rejects
    // elsewhere: the answer stays the one the cluster would give
    assertFalse(separates(adapter(NameClashAvoidance.NONE, "a-tenant-nobody-uses")));

  }

  @Test
  @DisplayName("A tenant named for one workflow module only separates it from the rest")
  public void aTenantOfOneModuleSeparatesIt() {

    // the way out the core's refusal recommends: one of the two modules gets a scope of its
    // own, and every other module of the application stays where it was
    assertTrue(
        separates(
            adapter(
                workflowModuleId -> NameClashAvoidance.BY_ADAPTER,
                Map.of(ONE_MODULE, "loans-tenant"),
                "one-tenant-for-all")),
        "the module's own name wins over the adapter's, so the two tenants differ");

    // and a module which repeats the adapter's name lands in the very same tenant
    assertFalse(
        separates(
            adapter(
                workflowModuleId -> NameClashAvoidance.BY_ADAPTER,
                Map.of(ONE_MODULE, "one-tenant-for-all"),
                "one-tenant-for-all")));

    // a name the mode drops is not a scope: neither module reaches the cluster in a tenant
    assertFalse(
        separates(
            adapter(
                workflowModuleId -> NameClashAvoidance.NONE,
                Map.of(ONE_MODULE, "loans-tenant"),
                null)));

  }

  @Test
  @DisplayName("A module in a tenant is separated from one which reaches the cluster without one")
  public void aTenantedModuleIsSeparatedFromAnUntenantedOne() {

    // the mode is resolvable per workflow module, so this is a configuration an
    // application can really have: one module keeps the default, the other one prefixes
    final var mixed = adapter(
        workflowModuleId -> ONE_MODULE.equals(workflowModuleId)
            ? NameClashAvoidance.BY_ADAPTER
            : NameClashAvoidance.USE_PREFIX,
        null);

    assertTrue(separates(mixed));

    // and the same where the untenanted side scopes nothing at all
    assertTrue(
        separates(
            adapter(
                workflowModuleId -> ONE_MODULE.equals(workflowModuleId)
                    ? NameClashAvoidance.BY_ADAPTER
                    : NameClashAvoidance.NONE,
                "one-tenant-for-all")));

  }

}
