package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which adapter ids address the same Camunda 8 cluster, and why that is a
 * coarser question than whether they are the same INSTANCE.
 * <p>
 * A job key, a user-task key and a process-instance key are unique per cluster and not
 * per tenant, so two ids sharing a cluster are handed each other's keys. Their awareness
 * probes therefore have to ask which scope a key belongs to before they claim it, and
 * that question costs a search per claimed task, which is why it is asked only where two
 * ids really do share a cluster.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8SharedClusterTest {

  private static Camunda8AdapterConfiguration selfManaged(
      final String restAddress,
      final String tenantId) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress(restAddress);
    configuration.setTenantId(tenantId);
    return configuration;

  }

  private static Camunda8ClientFactoryRegistry registryOf(
      final Map<String, Camunda8AdapterConfiguration> configurations) {

    return new Camunda8ClientFactoryRegistry(configurations);

  }

  @Test
  @DisplayName("Two ids with one rest-address share their cluster, whatever their tenants are")
  public void twoIdsOnOneAddressShareTheCluster() {

    final var configurations = new LinkedHashMap<String, Camunda8AdapterConfiguration>();
    configurations.put("c8-prefix", selfManaged("http://localhost:8080", null));
    configurations.put("c8-tenant", selfManaged("http://localhost:8080", "order-processing"));

    final var registry = registryOf(configurations);

    assertTrue(
        registry
            .getFactory("c8-prefix")
            .sharesItsCluster(),
        "a tenant does not make a second cluster - the keys stay global");
    assertEquals(
        List.of("c8-tenant"),
        registry
            .getFactory("c8-prefix")
            .getAdapterIdsSharingTheCluster(),
        "and each id knows WHICH id it shares with, not merely that it shares - a registry "
            + "pairing everything with everything would pass the assertion above");
    assertEquals(
        List.of("c8-prefix"),
        registry
            .getFactory("c8-tenant")
            .getAdapterIdsSharingTheCluster());

  }

  @Test
  @DisplayName("Two ids on different clusters share nothing, and pay nothing")
  public void twoIdsOnTwoClustersShareNothing() {

    final var configurations = new LinkedHashMap<String, Camunda8AdapterConfiguration>();
    configurations.put("old", selfManaged("http://localhost:8080", null));
    configurations.put("new", selfManaged("http://localhost:9090", null));

    final var registry = registryOf(configurations);

    assertFalse(registry.getFactory("old").sharesItsCluster());
    assertFalse(registry.getFactory("new").sharesItsCluster());

  }

  @Test
  @DisplayName("The only configured adapter shares nothing")
  public void oneIdSharesNothing() {

    final var registry = registryOf(Map.of("c8", selfManaged("http://localhost:8080", null)));

    assertFalse(
        registry
            .getFactory("c8")
            .sharesItsCluster(),
        "an application with one Camunda 8 adapter pays no scope probe");

  }

  @Test
  @DisplayName("Two SaaS ids of one cluster share it even with different credentials")
  public void twoSaasIdsOfOneClusterShareIt() {

    final var first = new Camunda8AdapterConfiguration();
    first.setMode(Camunda8AdapterConfiguration.Mode.SAAS);
    first.setClusterId("cluster-1");
    first.setRegion("bru-2");
    first.setClientId("client-a");
    first.setClientSecret("secret-a");
    final var second = new Camunda8AdapterConfiguration();
    second.setMode(Camunda8AdapterConfiguration.Mode.SAAS);
    second.setClusterId("cluster-1");
    second.setRegion("bru-2");
    second.setClientId("client-b");
    second.setClientSecret("secret-b");
    final var configurations = new LinkedHashMap<String, Camunda8AdapterConfiguration>();
    configurations.put("saas-a", first);
    configurations.put("saas-b", second);

    final var registry = registryOf(configurations);

    assertTrue(
        registry
            .getFactory("saas-a")
            .sharesItsCluster(),
        "separate OAuth clients are separate INSTANCES and still one cluster");

  }

}
