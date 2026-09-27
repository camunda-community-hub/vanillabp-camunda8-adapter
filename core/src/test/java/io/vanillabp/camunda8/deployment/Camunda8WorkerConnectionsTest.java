package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClientConfiguration;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an application learns when it opens more workers than its Camunda client has HTTP
 * connections.
 * <p>
 * The numbers this is built around were measured on 2026-09-26 and are written down in
 * {@code Camunda8WorkerConnections}: 115 workers against the client's 100 connections took
 * 10412 ms where 92 workers against the same 100 took 184 ms. So 115 and 92 are the two
 * numbers below, and the second one is the counter-check which has to stay quiet.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8WorkerConnectionsTest {

  /**
   * How many connections the Camunda client keeps unless the application says otherwise,
   * the same number in the 8.8, 8.9 and 8.10 clients.
   */
  private static final int THE_CLIENTS_OWN_POOL = 100;

  /** How many workers the Spring Boot test module opens on the 8.10 line. */
  private static final int MORE_WORKERS_THAN_THE_POOL = 115;

  /** How many it opened before the start-event listener of story 653 came along. */
  private static final int FEWER_WORKERS_THAN_THE_POOL = 92;

  /**
   * A startup report which keeps what it was told, which is what a test reads instead of
   * the block the platform renders out of it.
   */
  private static final class WhatWasReported implements StartupReport {

    private record Finding(String severity,
                           StartupTopic topic,
                           String scope,
                           String message) {
    }

    private final List<Finding> findings = new ArrayList<>();

    @Override
    public void notice(
        final StartupTopic topic,
        final String scope,
        final String message) {

      findings.add(new Finding("notice", topic, scope, message));

    }

    @Override
    public void warn(
        final StartupTopic topic,
        final String scope,
        final String message) {

      findings.add(new Finding("warn", topic, scope, message));

    }

    @Override
    public void error(
        final StartupTopic topic,
        final String scope,
        final String message) {

      findings.add(new Finding("error", topic, scope, message));

    }

    @Override
    public void refuse(
        final StartupTopic topic,
        final String scope,
        final String message) {

      findings.add(new Finding("refuse", topic, scope, message));

    }

  }

  private Camunda8DeploymentService deploymentService() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:65535");
    return DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(new Camunda8DeploymentServiceTest.NoOpInvoker()),
        (
            module,
            process,
            task) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration.ofHours(1),
        adapterId -> configuration);

  }

  /**
   * The configuration of a built client, as the CLIENT resolved it: a pool of the given
   * size, a ten second request timeout and the REST transport the adapter's workers
   * activate over.
   */
  private CamundaClientConfiguration clientWithAPoolOf(
      final int maxHttpConnections) {

    return clientWithAPoolOf(maxHttpConnections, true);

  }

  private CamundaClientConfiguration clientWithAPoolOf(
      final int maxHttpConnections,
      final boolean preferRestOverGrpc) {

    final var clientConfiguration = mock(CamundaClientConfiguration.class);
    when(clientConfiguration.getMaxHttpConnections()).thenReturn(maxHttpConnections);
    when(clientConfiguration.getDefaultRequestTimeout()).thenReturn(Duration.ofSeconds(10));
    when(clientConfiguration.preferRestOverGrpc()).thenReturn(preferRestOverGrpc);
    return clientConfiguration;

  }

  @Test
  @DisplayName("More workers than connections is said at startup, with both numbers and the way out")
  public void theApplicationIsToldWhenItsWorkersOutgrewThePool() {

    final var service = deploymentService();
    final var reported = new WhatWasReported();
    service.setStartupReport(reported);

    service
        .holdTheWorkersAgainstTheConnectionPool(
            "test-app",
            MORE_WORKERS_THAN_THE_POOL,
            clientWithAPoolOf(THE_CLIENTS_OWN_POOL));

    assertEquals(1, reported.findings.size(), "one finding, and it is the one about the pool: "
        + reported.findings);
    final var finding = reported.findings.get(0);
    assertEquals("warn", finding.severity(), "the application still boots, so it is a warning");
    assertEquals(StartupTopic.CONFIGURATION, finding.topic(), "the fix is a property");
    assertEquals("camunda8 adapter 'c8'", finding.scope(), "the adapter id is the scope, not part of the text");
    final var message = finding.message();
    assertTrue(message.contains("115 job workers"), "how many workers there are: "
        + message);
    assertTrue(message.contains("100 HTTP connections"), "and how many connections they share: "
        + message);
    assertTrue(
        message.contains("vanillabp.adapters.<adapter id>.max-http-connections"),
        "the property which raises the pool: "
            + message);
    assertTrue(message.contains("256"), "and a value to start from: "
        + message);
    assertTrue(message.contains("request-timeout"), "what it costs while nobody raises it: "
        + message);
    assertTrue(message.contains("PT10S"), "and how long that is here: "
        + message);

  }

  @Test
  @DisplayName("An application below the pool hears nothing")
  public void anApplicationWhichFitsIntoItsPoolHearsNothing() {

    final var service = deploymentService();
    final var reported = new WhatWasReported();
    service.setStartupReport(reported);

    service
        .holdTheWorkersAgainstTheConnectionPool(
            "test-app",
            FEWER_WORKERS_THAN_THE_POOL,
            clientWithAPoolOf(THE_CLIENTS_OWN_POOL));

    assertTrue(reported.findings.isEmpty(), "92 workers fit into 100 connections: "
        + reported.findings);

  }

  @Test
  @DisplayName("The same workers against a pool which was raised hear nothing either")
  public void theRaisedPoolIsWhatMakesItQuiet() {

    final var service = deploymentService();
    final var reported = new WhatWasReported();
    service.setStartupReport(reported);

    service
        .holdTheWorkersAgainstTheConnectionPool(
            "test-app",
            MORE_WORKERS_THAN_THE_POOL,
            clientWithAPoolOf(256));

    assertTrue(reported.findings.isEmpty(), "115 workers fit into 256 connections: "
        + reported.findings);

  }

  @Test
  @DisplayName("It is the workers of ALL workflow modules which share the pool")
  public void theWorkersOfEveryWorkflowModuleCountAgainstOnePool() {

    final var service = deploymentService();
    final var reported = new WhatWasReported();
    service.setStartupReport(reported);

    service.holdTheWorkersAgainstTheConnectionPool("first", 60, clientWithAPoolOf(THE_CLIENTS_OWN_POOL));
    assertTrue(reported.findings.isEmpty(), "60 of them fit: "
        + reported.findings);

    service.holdTheWorkersAgainstTheConnectionPool("second", 60, clientWithAPoolOf(THE_CLIENTS_OWN_POOL));

    assertEquals(1, reported.findings.size(), "120 of them do not: "
        + reported.findings);
    assertTrue(
        reported.findings.get(0).message().contains("120 job workers"),
        "and the number is the one over both modules: "
            + reported.findings.get(0).message());

  }

  @Test
  @DisplayName("A client which activates over gRPC is not measured against the HTTP pool")
  public void grpcActivationsAreNotLimitedByThisPool() {

    final var service = deploymentService();
    final var reported = new WhatWasReported();
    service.setStartupReport(reported);

    service
        .holdTheWorkersAgainstTheConnectionPool(
            "test-app",
            MORE_WORKERS_THAN_THE_POOL,
            clientWithAPoolOf(THE_CLIENTS_OWN_POOL, false));

    assertTrue(reported.findings.isEmpty(), "the workers do not hold an HTTP connection then: "
        + reported.findings);

  }

  @Test
  @DisplayName("Without a platform integration the finding goes into the log")
  public void anAdapterWithoutTheCollectionPointStillSaysIt(
      final CapturedOutput output) {

    final var service = deploymentService();

    service
        .holdTheWorkersAgainstTheConnectionPool(
            "test-app",
            MORE_WORKERS_THAN_THE_POOL,
            clientWithAPoolOf(THE_CLIENTS_OWN_POOL));

    final var logged = output.getOut() + output.getErr();
    assertTrue(logged.contains("115 job workers"), "the message is written where it was found: "
        + logged);
    assertTrue(logged.contains("camunda8 adapter 'c8'"), "and it says which adapter id it is about: "
        + logged);

  }

}
