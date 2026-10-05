package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the environment changed about a client this adapter had just configured. The
 * client applies <code>CAMUNDA_*</code> variables over everything the builder set and
 * says nothing about it; this is what makes it visible.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8EnvironmentOverridesTest {

  private static Camunda8AdapterConfiguration configured() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://configured:8080");
    return configuration;

  }

  /**
   * A client whose values are what the environment would have made of them - built
   * without the environment, so the test does not have to set variables on the JVM.
   */
  private static CamundaClient clientReporting(
      final String restAddress,
      final String tenantId) {

    final var builder = CamundaClient
        .newClientBuilder()
        .applyEnvironmentVariableOverrides(false)
        .restAddress(URI.create(restAddress));
    if (tenantId != null) {
      builder.defaultTenantId(tenantId);
    }
    return builder.build();

  }

  @Test
  @DisplayName("a variable which changed the address is named with both values")
  public void changedAddressIsReported() {

    final var configuration = configured();
    try (final var client = clientReporting("http://from-the-environment:8080", null)) {

      final var overrides = Camunda8EnvironmentOverrides.detect(
          "c8", configuration, client.getConfiguration(),
          Map.of("CAMUNDA_REST_ADDRESS", "http://from-the-environment:8080")::get);

      assertEquals(1, overrides.size(), "exactly the changed value is reported");
      final var override = overrides.getFirst();
      assertEquals("CAMUNDA_REST_ADDRESS", override.variable());
      assertEquals("vanillabp.adapters.c8.rest-address", override.propertyKey());
      assertEquals("http://configured:8080", override.configured());

      final var message = Camunda8EnvironmentOverrides.describe("c8", overrides);
      assertTrue(message.contains("CAMUNDA_REST_ADDRESS"), message);
      assertTrue(message.contains("vanillabp.adapters.c8.rest-address"), message);
      assertTrue(message.contains("Remove the variable"), message);
    }

  }

  @Test
  @DisplayName("the legacy ZEEBE_ name is recognised too")
  public void legacyVariableIsRecognised() {

    final var configuration = configured();
    try (final var client = clientReporting("http://from-the-environment:8080", null)) {

      final var overrides = Camunda8EnvironmentOverrides.detect(
          "c8", configuration, client.getConfiguration(),
          Map.of("ZEEBE_REST_ADDRESS", "http://from-the-environment:8080")::get);

      assertEquals(1, overrides.size());
      assertEquals("ZEEBE_REST_ADDRESS", overrides.getFirst().variable());
    }

  }

  @Test
  @DisplayName("a variable which changed nothing is not reported")
  public void unchangedValueIsSilent() {

    final var configuration = configured();
    try (final var client = clientReporting("http://configured:8080", null)) {

      final var overrides = Camunda8EnvironmentOverrides.detect(
          "c8", configuration, client.getConfiguration(),
          Map.of("CAMUNDA_REST_ADDRESS", "http://configured:8080")::get);

      assertTrue(overrides.isEmpty(), "nothing was overruled, so nothing is reported");
    }

  }

  @Test
  @DisplayName("without any variable nothing is reported at all")
  public void withoutVariablesNothingIsReported() {

    final var configuration = configured();
    configuration.setTenantId("configured-tenant");
    configuration.setKeepAlive(Duration.ofSeconds(30));
    try (final var client = clientReporting("http://somewhere-else:8080", "another-tenant")) {

      final var overrides = Camunda8EnvironmentOverrides.detect(
          "c8", configuration, client.getConfiguration(), name -> null);

      assertTrue(overrides.isEmpty(),
          "a difference without a variable set is the adapter's own doing, not the environment's");
    }

  }

  @Test
  @DisplayName("a changed tenant is reported and no secret is ever compared")
  public void changedTenantIsReported() {

    final var configuration = configured();
    configuration.setTenantId("configured-tenant");
    configuration.setClientSecret("do-not-log-me");
    try (final var client = clientReporting("http://configured:8080", "environment-tenant")) {

      final var overrides = Camunda8EnvironmentOverrides.detect(
          "c8", configuration, client.getConfiguration(),
          Map.of("CAMUNDA_DEFAULT_TENANT_ID", "environment-tenant")::get);

      assertEquals(1, overrides.size());
      assertEquals("vanillabp.adapters.c8.tenant-id", overrides.getFirst().propertyKey());
      final var message = Camunda8EnvironmentOverrides.describe("c8", overrides);
      assertFalse(message.contains("do-not-log-me"), "credentials are never part of a message");
    }

  }

  @Test
  @DisplayName("credentials in the environment no longer choose the method once the adapter names one")
  public void credentialsInTheEnvironmentAreReported() {

    final var configuration = configured();
    configuration.getAuth().setUsername("demo");
    configuration.getAuth().setPassword("demo");
    final var environment = Map.of("CAMUNDA_CLIENT_ID", "an-oidc-client", "CAMUNDA_CLIENT_SECRET", "a-secret");

    final var message = Camunda8EnvironmentOverrides.describeCredentialSelection(
        "c8",
        Camunda8Authentication.of("c8", configuration, environment::get),
        environment::get);

    assertTrue(message.contains("CAMUNDA_CLIENT_ID"), message);
    assertTrue(message.contains("CAMUNDA_CLIENT_SECRET"), message);
    assertTrue(message.contains("vanillabp.adapters.c8.auth"), message);
    assertFalse(message.contains("a-secret"), "no message ever carries a credential");

  }

  @Test
  @DisplayName("an adapter authenticating with none takes nothing away, so nothing is said")
  public void nothingIsSaidWhileTheEnvironmentStillDecides() {

    final var environment = Map.of("CAMUNDA_BASIC_AUTH_USERNAME", "demo", "CAMUNDA_BASIC_AUTH_PASSWORD", "demo");

    assertEquals(
        null,
        Camunda8EnvironmentOverrides.describeCredentialSelection(
            "c8",
            Camunda8Authentication.of("c8", configured(), environment::get),
            environment::get));

  }

}
