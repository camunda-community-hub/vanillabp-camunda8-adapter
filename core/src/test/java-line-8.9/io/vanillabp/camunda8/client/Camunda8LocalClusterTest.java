package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * On the 8.9 line an adapter without a cluster address talks to the ports Camunda's docker
 * compose for 8.9 publishes: REST on 8080, gRPC on 26500, both on <code>localhost</code>.
 * The start's warning names the address and says where it comes from. With REST it also
 * names the key which moves the application off port 8080, because 8080 is the default port of
 * Spring Boot and of Quarkus.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8LocalClusterTest {

  private static List<String> warningsOfTheStart(
      final Camunda8AdapterConfiguration configuration) {

    return warningsOfTheStart(configuration, "server.port");

  }

  private static List<String> warningsOfTheStart(
      final Camunda8AdapterConfiguration configuration,
      final String applicationPortKey) {

    // every line has to know whether the jobs are leased; it is not what this test is about
    configuration.setJobLease(Camunda8AdapterConfiguration.JobLease.DO_NOT_USE);
    final var warnings = new ArrayList<String>();
    Camunda8StartupValidation.validateAtStartup(
        "c8", configuration, true, false, Duration.ofDays(7), applicationPortKey, warnings::add, line -> {
        });
    return warnings;

  }

  @Test
  @DisplayName("Without an address the client talks to the ports of Camunda's 8.9 docker compose")
  public void withoutAnAddressTheClientTalksToThePortsOfTheDockerCompose() {

    try (var factory = new Camunda8ClientFactory("c8", new Camunda8AdapterConfiguration())) {

      final var client = factory.getClient();

      assertEquals("http://localhost:8080", client.getConfiguration().getRestAddress().toString());
      assertEquals("http://localhost:26500", client.getConfiguration().getGrpcAddress().toString());

    }

  }

  @Test
  @DisplayName("Without a REST address the warning names the REST address of Camunda's 8.9 docker compose")
  public void withoutARestAddressTheWarningNamesTheRestAddressOfTheDockerCompose() {

    final var warnings = warningsOfTheStart(new Camunda8AdapterConfiguration());

    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(
        warnings
            .getFirst()
            .contains("Camunda 8 adapter 'c8' has no cluster address, so it connects to the local cluster at "
                + "'http://localhost:8080'. This address matches Camunda's docker compose for 8.9. "
                + "Set 'vanillabp.adapters.c8.rest-address' to connect to another cluster."),
        warnings.getFirst());

  }

  @Test
  @DisplayName("Without a gRPC address the warning names the gRPC address of Camunda's 8.9 docker compose")
  public void withoutAGrpcAddressTheWarningNamesTheGrpcAddressOfTheDockerCompose() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setPreferRestOverGrpc(false);

    final var warnings = warningsOfTheStart(configuration);

    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(
        warnings
            .getFirst()
            .contains("Camunda 8 adapter 'c8' has no cluster address, so it connects to the local cluster at "
                + "'http://localhost:26500'. This address matches Camunda's docker compose for 8.9. "
                + "Set 'vanillabp.adapters.c8.grpc-address' to connect to another cluster."),
        warnings.getFirst());

  }

  private static final String TAKES_PORT_8080 = "Camunda's docker compose for 8.9 takes host port 8080, "
      + "so this application needs another port.";

  @Test
  @DisplayName("With REST the warning names the key which moves a Spring Boot application off port 8080")
  public void withRestTheWarningNamesTheSpringBootPortKey() {

    final var warnings = warningsOfTheStart(new Camunda8AdapterConfiguration(), "server.port");

    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(
        warnings
            .getFirst()
            .contains("Set 'vanillabp.adapters.c8.rest-address' to connect to another cluster.\n"
                + TAKES_PORT_8080
                + " Set 'server.port' to pick one.\n"
                + "For Camunda 8 SaaS"),
        warnings.getFirst());

  }

  @Test
  @DisplayName("With REST the warning names the key which moves a Quarkus application off port 8080")
  public void withRestTheWarningNamesTheQuarkusPortKey() {

    final var warnings = warningsOfTheStart(new Camunda8AdapterConfiguration(), "quarkus.http.port");

    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(
        warnings
            .getFirst()
            .contains(TAKES_PORT_8080
                + " Set 'quarkus.http.port' to pick one."),
        warnings.getFirst());
    assertFalse(warnings.getFirst().contains("server.port"), warnings.getFirst());

  }

  @Test
  @DisplayName("With gRPC the warning says nothing about port 8080")
  public void withGrpcTheWarningSaysNothingAboutPort8080() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setPreferRestOverGrpc(false);

    final var warnings = warningsOfTheStart(configuration, "server.port");

    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(
        warnings
            .getFirst()
            .contains("Set 'vanillabp.adapters.c8.grpc-address' to connect to another cluster.\n"
                + "For Camunda 8 SaaS"),
        warnings.getFirst());
    assertFalse(warnings.getFirst().contains(TAKES_PORT_8080), warnings.getFirst());

  }

}
