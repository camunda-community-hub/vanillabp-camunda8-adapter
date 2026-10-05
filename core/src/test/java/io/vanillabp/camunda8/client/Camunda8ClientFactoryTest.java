package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import io.camunda.client.impl.basicauth.BasicAuthCredentialsProvider;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Unit tests of {@link Camunda8ClientFactory} / {@link Camunda8AdapterConfiguration}: a
 * self-managed adapter without an address gets a client for the client's default address,
 * and a missing SaaS property is reported on first use, naming the exact property. Building
 * the self-managed client does not contact any cluster.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ClientFactoryTest {

  @Test
  @DisplayName("self-managed without an address builds a client for the client's default address")
  public void selfManagedWithoutAnAddressUsesTheClientDefault() {

    try (var factory = new Camunda8ClientFactory("c8", new Camunda8AdapterConfiguration())) {

      factory.validateConfigured();
      final var client = factory.getClient();
      assertEquals(
          Camunda8AdapterConfiguration.CLIENT_DEFAULT_REST_ADDRESS,
          client.getConfiguration().getRestAddress().toString(),
          "the adapter leaves the address to the client");
      assertEquals(
          "http://0.0.0.0:8080",
          Camunda8AdapterConfiguration.CLIENT_DEFAULT_REST_ADDRESS,
          "the wiki and the startup message name this address; a client which changes it has to change them too");
      assertEquals(
          "http://0.0.0.0:26500",
          Camunda8AdapterConfiguration.CLIENT_DEFAULT_GRPC_ADDRESS,
          "the same for gRPC");
    }

  }

  @Test
  @DisplayName("saas without required properties fails naming the exact property")
  public void saasMissingPropertiesNamesProperty() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setMode(Camunda8AdapterConfiguration.Mode.SAAS);
    final var factory = new Camunda8ClientFactory("cloud", configuration);

    final var exception = assertThrows(IllegalStateException.class, factory::validateConfigured);
    assertTrue(exception.getMessage().contains("vanillabp.adapters.cloud.cluster-id"),
        "message should name the missing property, but was: "
            + exception.getMessage());

  }

  @Test
  @DisplayName("a configured self-managed adapter validates and builds a client without contacting a cluster")
  public void selfManagedConfiguredBuildsClient() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");
    configuration.setGrpcAddress("http://localhost:26500");
    final var factory = new Camunda8ClientFactory("c8", configuration);

    assertDoesNotThrow(factory::validateConfigured);

    final var client = factory.getClient();
    assertNotNull(client);
    // the client is cached (single instance per adapter)
    assertSame(client, factory.getClient());

    factory.close();

  }


  @Test
  @DisplayName("the execution model reaches the client: four threads by default")
  public void executionModelReachesTheClient() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");
    try (final var factory = new Camunda8ClientFactory("c8", configuration)) {

      final var executor = assertInstanceOf(Camunda8PlatformThreadExecutor.class, factory.getExecutor(),
          "the adapter supplies the executor in the platform mode too");
      assertEquals(4, executor.getBound(),
          "four execution slots, not the client's own default of one");
      assertEquals(32, factory.getClient().getConfiguration().getDefaultJobWorkerMaxJobsActive(),
          "eight per slot, capped at the client's 32");
    }

  }

  @Test
  @DisplayName("'virtual' hands the client the bounded virtual-thread executor")
  public void virtualModeHandsTheClientItsExecutor() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");
    configuration.setWorkerThreads("virtual");
    configuration.setWorkerThreadsBound(6);
    try (final var factory = new Camunda8ClientFactory("c8", configuration)) {

      final var executor = assertInstanceOf(Camunda8VirtualThreadExecutor.class, factory.getExecutor(),
          "the adapter supplies the executor itself");
      assertEquals(6, executor.getBound());
    }

  }

  @Test
  @DisplayName("every worker and client property reaches the built client")
  public void everyPropertyReachesTheClient() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");
    configuration.setWorkerThreads("3");
    configuration.setMaxJobsActive(9);
    configuration.setPollInterval(Duration.ofMillis(250));
    configuration.setRequestTimeout(Duration.ofSeconds(20));
    configuration.setStreamEnabled(true);
    configuration.setMessageTimeToLive(Duration.ofHours(6));
    configuration.setMaxMessageSize(8 * 1024 * 1024);
    configuration.setKeepAlive(Duration.ofSeconds(30));
    configuration.setMaxHttpConnections(64);
    configuration.setOverrideAuthority("gateway.internal");
    try (final var factory = new Camunda8ClientFactory("c8", configuration)) {

      final var clientConfiguration = factory.getClient().getConfiguration();
      assertEquals(3, factory.getExecutor().getBound());
      assertEquals(9, clientConfiguration.getDefaultJobWorkerMaxJobsActive());
      assertEquals(Duration.ofMillis(250), clientConfiguration.getDefaultJobPollInterval());
      assertEquals(Duration.ofSeconds(20), clientConfiguration.getDefaultRequestTimeout());
      assertTrue(clientConfiguration.getDefaultJobWorkerStreamEnabled());
      assertEquals(Duration.ofHours(6), clientConfiguration.getDefaultMessageTimeToLive());
      assertEquals(8 * 1024 * 1024, clientConfiguration.getMaxMessageSize());
      assertEquals(Duration.ofSeconds(30), clientConfiguration.getKeepAlive());
      assertEquals(64, clientConfiguration.getMaxHttpConnections());
      assertEquals("gateway.internal", clientConfiguration.getOverrideAuthority());
    }

  }

  @Test
  @DisplayName("the startup line names the four numbers which are the sizing decision")
  public void startupLineNamesTheSizingDecision(
      final CapturedOutput output) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");
    configuration.setJobTimeout(Duration.ofMinutes(2));
    try (final var factory = new Camunda8ClientFactory("sizing", configuration)) {

      assertNotNull(factory.getClient());
      final var logged = output.getOut() + output.getErr();
      assertTrue(logged.contains("4 platform threads"), logged);
      assertTrue(logged.contains("max-jobs-active 32"), logged);
      assertTrue(logged.contains("PT2M"), logged);
    }

  }

  @Test
  @DisplayName("the credentials the adapter configured reach the client")
  public void credentialsReachTheClient() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");
    configuration.getAuth().setUsername("demo");
    configuration.getAuth().setPassword("demo");
    try (final var factory = new Camunda8ClientFactory("c8", configuration)) {

      final var provider = factory.getClient().getConfiguration().getCredentialsProvider();
      assertInstanceOf(Camunda8Authentication.Observing.class, provider,
          "the adapter wraps the provider to report a refused request");
      assertInstanceOf(
          BasicAuthCredentialsProvider.class,
          Camunda8Authentication.unwrap(provider));
    }

  }

  @Test
  @DisplayName("the CA certificate of the cluster connection reaches the client")
  public void caCertificateReachesTheClient(
      @TempDir final Path directory) throws Exception {

    final var certificate = Files
        .writeString(directory.resolve("ca.pem"), "-----BEGIN CERTIFICATE-----");
    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");
    configuration.getAuth().setCaCertificatePath(certificate.toString());
    try (final var factory = new Camunda8ClientFactory("c8", configuration)) {

      assertEquals(
          certificate.toString(),
          factory.getClient().getConfiguration().getCaCertificatePath());
      assertEquals(
          Camunda8AuthConfiguration.Method.NONE,
          factory.getAuthentication().getMethod(),
          "a certificate is a question of the transport, not of a credential");
    }

  }

  @Test
  @DisplayName("the startup line names how the adapter authenticates and whether that was detected")
  public void startupLineNamesTheAuthentication(
      final CapturedOutput output) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");
    configuration.getAuth().setUsername("demo");
    configuration.getAuth().setPassword("demo");
    try (final var factory = new Camunda8ClientFactory("c8", configuration)) {

      assertNotNull(factory.getClient());
      final var logged = output.getOut() + output.getErr();
      assertTrue(logged.contains("authentication basic (detected) as 'demo'"), logged);
      assertFalse(logged.contains("demo:"), "a password never reaches a log line");
    }

  }

  @Test
  @DisplayName("an incomplete authentication block fails while the factory is built")
  public void incompleteAuthenticationFailsAtStartup() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");
    configuration.getAuth().setUsername("demo");

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> new Camunda8ClientFactory("c8", configuration));

    assertTrue(exception.getMessage().contains("vanillabp.adapters.c8.auth.password"), exception.getMessage());

  }

  @Test
  @DisplayName("an unusable worker-threads value fails while the factory is built")
  public void unusableWorkerThreadsFailsAtStartup() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");
    configuration.setWorkerThreads("none");

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> new Camunda8ClientFactory("c8", configuration));

    assertTrue(exception.getMessage().contains("vanillabp.adapters.c8.worker-threads"),
        exception.getMessage());

  }

}
