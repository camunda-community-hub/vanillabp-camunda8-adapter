package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A self-managed adapter without a cluster address uses the address of the local cluster. The
 * start goes on and warns, and the warning names that address and the key which changes it.
 * Which address that is depends on the release line, and <code>Camunda8LocalClusterTest</code>
 * of each line holds it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8MissingClusterAddressTest {

  private static final String NO_ADDRESS = "Camunda 8 adapter 'c8' has no cluster address, so it connects to the local cluster at";

  private static List<String> warningsOfTheStart(
      final Camunda8AdapterConfiguration configuration) {

    // every line has to know whether the jobs are leased; it is not what this test is about
    configuration.setJobLease(Camunda8AdapterConfiguration.JobLease.DO_NOT_USE);
    final var warnings = new ArrayList<String>();
    Camunda8StartupValidation.validateAtStartup(
        "c8", configuration, true, false, Duration.ofDays(7), warnings::add, line -> {
        });
    return warnings;

  }

  @Test
  @DisplayName("Without any address the start goes on and names the local REST address")
  public void withoutAnyAddressTheStartNamesTheLocalRestAddress() {

    final var warnings = warningsOfTheStart(new Camunda8AdapterConfiguration());

    assertEquals(1, warnings.size(), warnings.toString());
    final var warning = warnings.getFirst();
    assertTrue(
        warning.contains(NO_ADDRESS + " '%s'".formatted(Camunda8AdapterConfiguration.LOCAL_CLUSTER_REST_ADDRESS)),
        warning);
    assertTrue(warning.contains("This address matches Camunda's docker compose for "), warning);
    assertTrue(warning.contains("Set 'vanillabp.adapters.c8.rest-address' to connect to another cluster"), warning);
    assertTrue(warning.contains("vanillabp.adapters.c8.mode"), "and the way to SaaS: "
        + warning);
    assertTrue(warning.contains("vanillabp.adapters.c8.client-secret"), warning);
    assertTrue(warning.contains("vanillabp.adapters.c8.auth"), "and where credentials go: "
        + warning);
    assertFalse(warning.contains("will fail"), "the start no longer promises a failure: "
        + warning);

  }

  @Test
  @DisplayName("An adapter talking gRPC without an address names the local gateway")
  public void anAdapterTalkingGrpcNamesTheLocalGateway() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setPreferRestOverGrpc(false);

    final var warnings = warningsOfTheStart(configuration);

    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(
        warnings.getFirst()
            .contains(NO_ADDRESS + " '%s'".formatted(Camunda8AdapterConfiguration.LOCAL_CLUSTER_GRPC_ADDRESS)),
        warnings.getFirst());
    assertTrue(warnings.getFirst().contains("Set 'vanillabp.adapters.c8.grpc-address'"), warnings.getFirst());

  }

  @Test
  @DisplayName("An address written for the other protocol is pointed out with the switch which uses it")
  public void anAddressForTheOtherProtocolIsPointedOut() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setGrpcAddress("http://gateway:26500");

    final var warnings = warningsOfTheStart(configuration);

    assertEquals(1, warnings.size(), warnings.toString());
    final var warning = warnings.getFirst();
    assertTrue(
        warning.contains(NO_ADDRESS + " '%s'".formatted(Camunda8AdapterConfiguration.LOCAL_CLUSTER_REST_ADDRESS)),
        warning);
    assertTrue(
        warning.contains("'vanillabp.adapters.c8.grpc-address' is set, but the client does not talk that protocol"),
        warning);
    assertTrue(warning.contains("Set 'vanillabp.adapters.c8.prefer-rest-over-grpc' to 'false'"), warning);
    assertFalse(warning.contains("http://gateway:26500"), "a message names keys, not values: "
        + warning);

  }

  @Test
  @DisplayName("An adapter with an address hears nothing about the local cluster")
  public void anAdapterWithAnAddressHearsNothing() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");

    final var warnings = warningsOfTheStart(configuration);

    assertTrue(warnings.stream().noneMatch(warning -> warning.contains(NO_ADDRESS)), warnings.toString());

  }

  @Test
  @DisplayName("A SaaS adapter never falls back to the local cluster")
  public void aSaasAdapterNeverFallsBackToTheLocalCluster() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setMode(Camunda8AdapterConfiguration.Mode.SAAS);
    final var warnings = new ArrayList<String>();

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> Camunda8StartupValidation.validateAtStartup(
            "c8", configuration, true, false, Duration.ofDays(7), warnings::add, line -> {
            }));

    assertTrue(failure.getMessage().contains("vanillabp.adapters.c8.cluster-id"), failure.getMessage());
    assertTrue(warnings.stream().noneMatch(warning -> warning.contains(NO_ADDRESS)), warnings.toString());

  }

}
