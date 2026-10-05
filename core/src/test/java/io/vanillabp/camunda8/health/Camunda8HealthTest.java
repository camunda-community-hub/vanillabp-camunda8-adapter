package io.vanillabp.camunda8.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.command.TopologyRequestStep1;
import io.camunda.client.api.response.Topology;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What one Camunda 8 adapter instance answers when the health endpoint asks
 * it. The rules being pinned here are the ones easy to get wrong: a SaaS adapter which is
 * not configured yet must not read as an outage, and every answer has to name the
 * address, because that is what an operator acts on. That includes the address of the local
 * cluster, which an adapter without an address of its own talks to.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8HealthTest {

  private static final String ADDRESS = "http://localhost:8080";

  private Camunda8ClientFactory factoryWith(
      final Camunda8AdapterConfiguration configuration,
      final CamundaClient client) {

    final var factory = mock(Camunda8ClientFactory.class);
    when(factory.getConfiguration()).thenReturn(configuration);
    if (client != null) {
      when(factory.getClient()).thenReturn(client);
    }
    return factory;

  }

  private Camunda8AdapterConfiguration configured() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress(ADDRESS);
    return configuration;

  }

  @Test
  @DisplayName("Two seconds unless something else is configured")
  public void theDefaultTimeoutIsTwoSeconds() {

    assertEquals(
        Duration.ofSeconds(2),
        new Camunda8AdapterConfiguration().resolvedHealthTimeout(),
        "far above the round trip to a healthy cluster and below what a readiness probe waits");
    assertEquals(
        Duration.ofSeconds(2),
        Camunda8AdapterConfiguration.DEFAULT_HEALTH_TIMEOUT,
        "and the constant says the same as the ISO notation the messages use");

  }

  @Test
  @DisplayName("A negative timeout fails the boot naming the property")
  public void aNegativeTimeoutFailsTheBoot() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setHealthTimeout(Duration.ofSeconds(-1));

    final var e = Assertions
        .assertThrows(
            IllegalStateException.class,
            () -> configuration.validateHealthTimeout("c8"));

    assertTrue(e.getMessage().contains("vanillabp.adapters.c8.health-timeout"), e.getMessage());
    assertTrue(e.getMessage().contains("PT2S"), "names the default: "
        + e.getMessage());

  }

  @Test
  @DisplayName("A SaaS adapter which is not configured yet is UNKNOWN, never DOWN")
  public void anUnconfiguredSaasAdapterIsUnknown() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setMode(Camunda8AdapterConfiguration.Mode.SAAS);

    final var health = Camunda8Health.check("cloud", factoryWith(configuration, null));

    assertEquals(
        AdapterHealth.Status.UNKNOWN,
        health.status(),
        "the application booted degraded on purpose - that is not an outage");
    assertTrue(
        health.description().contains("cluster-id"),
        "and the missing keys are named: "
            + health.description());

  }

  @Test
  @DisplayName("An adapter without an address asks the local cluster, and names its address")
  public void anAdapterWithoutAnAddressAsksTheLocalCluster() throws Exception {

    final var health = Camunda8Health
        .check(
            "c8",
            factoryWith(
                new Camunda8AdapterConfiguration(),
                clientAnswering(
                    null,
                    new ExecutionException(
                        new IllegalStateException("Connection refused")))));

    assertEquals(
        AdapterHealth.Status.DOWN,
        health.status(),
        "the address of the local cluster is a real address, so a cluster missing there is an outage");
    assertEquals(Camunda8AdapterConfiguration.LOCAL_CLUSTER_REST_ADDRESS, health.details().get("address"));

  }

  @Test
  @DisplayName("PT0S switches the check off and says so")
  public void aZeroTimeoutSwitchesTheCheckOff() {

    final var configuration = configured();
    configuration.setHealthTimeout(Duration.ZERO);

    final var health = Camunda8Health.check("c8", factoryWith(configuration, null));

    assertEquals(AdapterHealth.Status.UNKNOWN, health.status());
    assertTrue(
        health.description().contains("vanillabp.adapters.c8.health-timeout"),
        "the description names the property which switched it off: "
            + health.description());
    assertEquals(ADDRESS, health.details().get("address"));

  }

  @Test
  @DisplayName("A cluster which answers is UP, with what it said about itself")
  public void aRespondingClusterIsUp() throws Exception {

    final var topology = mock(Topology.class);
    when(topology.getGatewayVersion()).thenReturn("8.9.16");
    when(topology.getBrokers()).thenReturn(List.of());
    when(topology.getPartitionsCount()).thenReturn(3);

    final var health = Camunda8Health.check("c8", factoryWith(configured(), clientAnswering(topology, null)));

    assertEquals(AdapterHealth.Status.UP, health.status());
    assertEquals(ADDRESS, health.details().get("address"));
    assertEquals("8.9.16", health.details().get("gatewayVersion"));
    assertEquals("3", health.details().get("partitions"));

  }

  @Test
  @DisplayName("A cluster which does not answer is DOWN, naming the address and the timeout")
  public void anUnreachableClusterIsDown() throws Exception {

    final var health = Camunda8Health
        .check(
            "c8",
            factoryWith(configured(), clientAnswering(null, new TimeoutException())));

    assertEquals(AdapterHealth.Status.DOWN, health.status());
    assertEquals(ADDRESS, health.details().get("address"));
    assertEquals("PT2S", health.details().get("timeout"));
    assertTrue(
        health.description().contains("did not answer"),
        "a timeout carries no message of its own, so it is named: "
            + health.description());

  }

  @Test
  @DisplayName("A cluster which answers with an error is DOWN, carrying the reason")
  public void aFailingRequestIsDown() throws Exception {

    final var health = Camunda8Health
        .check(
            "c8",
            factoryWith(
                configured(),
                clientAnswering(
                    null,
                    new ExecutionException(
                        new IllegalStateException("Connection refused")))));

    assertEquals(AdapterHealth.Status.DOWN, health.status());
    assertTrue(
        health.description().contains("Connection refused"),
        "the reason travels to the endpoint: "
            + health.description());

  }

  @Test
  @DisplayName("A SaaS adapter is named by its cluster and region, not by a URL")
  public void aSaasAdapterIsNamedByClusterAndRegion() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setMode(Camunda8AdapterConfiguration.Mode.SAAS);
    configuration.setClusterId("0123abcd-4567-89ef-0123-456789abcdef");
    configuration.setRegion("bru-2");

    // an operator of a SaaS cluster has no address to ping; what they act on is the
    // cluster and the region, and every answer has to carry it
    final var health = Camunda8Health.check("cloud", factoryWith(configuration, null));

    assertEquals(
        "cluster '0123abcd-4567-89ef-0123-456789abcdef' in region 'bru-2'",
        health.details().get("address"));

  }

  @Test
  @DisplayName("A health check interrupted while waiting is DOWN, and the interrupt survives")
  public void anInterruptedCheckIsDown() throws Exception {

    // the endpoint's thread may be interrupted while the request is in flight; swallowing
    // that leaves a thread which cannot be stopped, and answering UP would be a lie
    try {
      final var health = Camunda8Health
          .check("c8", factoryWith(configured(), clientAnswering(null, new InterruptedException())));

      assertEquals(AdapterHealth.Status.DOWN, health.status());
      assertTrue(health.description().contains("interrupted"), health.description());
      assertEquals(ADDRESS, health.details().get("address"));
      assertTrue(Thread.currentThread().isInterrupted(), "the interrupt was swallowed");
    } finally {
      Thread.interrupted();
    }

  }

  @Test
  @DisplayName("A failure without a message is named by its type")
  public void aFailureWithoutAMessageIsNamedByItsType() throws Exception {

    final var health = Camunda8Health
        .check(
            "c8",
            factoryWith(
                configured(),
                clientAnswering(null, new ExecutionException(new IllegalStateException()))));

    assertEquals(AdapterHealth.Status.DOWN, health.status());
    // an exception without a message would leave the endpoint with an empty reason
    assertTrue(health.description().contains("IllegalStateException"), health.description());

  }

  /**
   * A client whose topology request answers with the given topology, or throws the given
   * exception while it is waited for.
   */
  @SuppressWarnings("unchecked")
  private CamundaClient clientAnswering(
      final Topology topology,
      final Exception failure) throws Exception {

    final var future = (CamundaFuture<Topology>) mock(CamundaFuture.class);
    if (failure == null) {
      when(future.get(ArgumentMatchers.anyLong(), ArgumentMatchers.any()))
          .thenReturn(topology);
    } else {
      when(future.get(ArgumentMatchers.anyLong(), ArgumentMatchers.any()))
          .thenThrow(failure);
    }
    final var request = mock(TopologyRequestStep1.class);
    when(request.requestTimeout(ArgumentMatchers.any())).thenReturn(request);
    when(request.send()).thenReturn(future);
    final var client = mock(CamundaClient.class);
    when(client.newTopologyRequest()).thenReturn(request);
    return client;

  }

}
