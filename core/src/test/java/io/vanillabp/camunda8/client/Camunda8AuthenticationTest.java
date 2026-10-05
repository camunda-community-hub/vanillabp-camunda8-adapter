package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import io.camunda.client.CredentialsProvider;
import io.camunda.client.impl.NoopCredentialsProvider;
import io.camunda.client.impl.basicauth.BasicAuthCredentialsProvider;
import io.camunda.client.impl.oauth.OAuthCredentialsProvider;
import io.vanillabp.camunda8.client.Camunda8AuthConfiguration.Method;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the adapter sends, and what it says when the cluster refuses it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8AuthenticationTest {

  private static CredentialsProvider.StatusCode status(
      final int code,
      final boolean unauthorized) {

    return new CredentialsProvider.StatusCode() {

      @Override
      public int code() {

        return code;

      }

      @Override
      public boolean isUnauthorized() {

        return unauthorized;

      }

    };

  }

  private static Camunda8AdapterConfiguration selfManaged() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");
    return configuration;

  }

  @Test
  @DisplayName("basic hands the client the client's own basic-auth provider")
  public void basicBuildsTheClientsProvider() {

    final var configuration = selfManaged();
    configuration.getAuth().setUsername("demo");
    configuration.getAuth().setPassword("demo");

    final var authentication = Camunda8Authentication.of("c8", configuration, variable -> null);
    final var provider = authentication.providerFor(message -> {
    });

    assertNotNull(provider);
    assertInstanceOf(
        BasicAuthCredentialsProvider.class,
        Camunda8Authentication.unwrap(provider));
    assertEquals("basic (detected) as 'demo'", authentication.describe());

  }

  @Test
  @DisplayName("oidc hands the client the client's own OAuth provider, which caches the token")
  public void oidcBuildsTheClientsProvider() {

    final var configuration = selfManaged();
    configuration.getAuth().setMethod(Method.OIDC);
    configuration.getAuth().setClientId("app");
    configuration.getAuth().setClientSecret("secret");
    configuration.getAuth().setAuthorizationServerUrl("http://localhost:18080/token");
    configuration.getAuth().setAudience("zeebe-api");

    final var authentication = Camunda8Authentication.of("c8", configuration, variable -> null);
    final var provider = authentication.providerFor(message -> {
    });

    assertInstanceOf(
        OAuthCredentialsProvider.class,
        Camunda8Authentication.unwrap(provider));
    assertEquals("oidc as 'app'", authentication.describe());

  }

  @Test
  @DisplayName("SaaS takes the same path, with Camunda's login endpoint and audience as presets")
  public void saasIsAnOidcAdapterWithPresets() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setMode(Camunda8AdapterConfiguration.Mode.SAAS);
    configuration.setClusterId("cluster");
    configuration.setRegion("bru-2");
    configuration.setClientId("cloud-client");
    configuration.setClientSecret("cloud-secret");

    final var authentication = Camunda8Authentication.of("cloud", configuration, variable -> null);

    assertEquals(Method.OIDC, authentication.getMethod());
    assertEquals("oidc (detected) as 'cloud-client'", authentication.describe());
    assertInstanceOf(
        OAuthCredentialsProvider.class,
        Camunda8Authentication.unwrap(authentication.providerFor(message -> {
        })));

  }

  @Test
  @DisplayName("none sets a provider, so a refused request can be reported at all")
  public void noneStillSetsAProvider() {

    final var authentication = Camunda8Authentication.of("c8", selfManaged(), variable -> null);

    assertEquals(Method.NONE, authentication.getMethod());
    assertInstanceOf(
        NoopCredentialsProvider.class,
        Camunda8Authentication.unwrap(authentication.providerFor(message -> {
        })));

  }

  @Test
  @DisplayName("none plus credentials in the environment leaves the client's own provider alone")
  public void environmentCredentialsKeepTheClientsProvider() {

    final var environment = Map
        .of("CAMUNDA_BASIC_AUTH_USERNAME", "demo", "CAMUNDA_BASIC_AUTH_PASSWORD", "demo");

    final var authentication = Camunda8Authentication.of("c8", selfManaged(), environment::get);

    assertNull(
        authentication.providerFor(message -> {
        }),
        "setting one would switch the environment credentials off, which no upgrade may do");
    assertEquals("none (detected), credentials from the environment", authentication.describe());

  }

  @Test
  @DisplayName("a refused request is reported once per adapter id, naming the method to configure")
  public void refusalIsReportedOnce() {

    final List<String> messages = new CopyOnWriteArrayList<>();
    final var authentication = Camunda8Authentication.of("c8", selfManaged(), variable -> null);
    final var provider = authentication.providerFor(messages::add);

    provider.shouldRetryRequest(status(401, true));
    provider.shouldRetryRequest(status(401, true));
    provider.shouldRetryRequest(status(16, false));

    assertEquals(1, messages.size(), messages.toString());
    final var message = messages.getFirst();
    assertTrue(message.contains("authenticates with 'none'"), message);
    assertTrue(message.contains("HTTP 401 Unauthorized"), message);
    assertTrue(message.contains("vanillabp.adapters.c8.auth"), message);
    assertTrue(message.contains("method: basic"), message);
    assertTrue(message.contains("method: oidc"), message);

  }

  @Test
  @DisplayName("a refused request under a configured method says the credentials were not accepted")
  public void refusalUnderAConfiguredMethod() {

    final var configuration = selfManaged();
    configuration.getAuth().setUsername("demo");
    configuration.getAuth().setPassword("h0rse-battery-staple");
    final List<String> messages = new CopyOnWriteArrayList<>();

    Camunda8Authentication
        .of("c8", configuration, variable -> null)
        .providerFor(messages::add)
        .shouldRetryRequest(status(7, false));

    final var message = messages.getFirst();
    assertTrue(message.contains("authenticates with 'basic'"), message);
    assertTrue(message.contains("gRPC PERMISSION_DENIED"), message);
    assertTrue(message.contains("vanillabp.adapters.c8.auth"), message);
    assertFalse(message.contains("h0rse-battery-staple"), "no message ever carries a credential");

  }

  @Test
  @DisplayName("the status in the message says which transport answered")
  public void theStatusNamesTheTransport() {

    // gRPC counts its own codes from zero and HTTP starts at 100, so the number itself
    // tells an operator where to look - a wrong label sends them to the wrong log
    assertTrue(
        Camunda8Authentication.message("c8", Camunda8AuthConfiguration.Method.NONE, 16)
            .contains("gRPC UNAUTHENTICATED"));
    assertTrue(
        Camunda8Authentication.message("c8", Camunda8AuthConfiguration.Method.NONE, 4)
            .contains("gRPC code 4"));
    assertTrue(
        Camunda8Authentication.message("c8", Camunda8AuthConfiguration.Method.NONE, 503)
            .contains("HTTP 503"));

  }

  @Test
  @DisplayName("every optional OIDC setting reaches the provider the client is handed")
  public void theOptionalOidcSettingsAreApplied(
      @TempDir final Path directory) throws Exception {

    // each of these is a documented property; one silently ignored is a token request
    // which fails at the cluster, hours after a boot which looked fine
    final var keystore = Files.createFile(directory.resolve("keystore.p12"));
    final var truststore = Files.createFile(directory.resolve("truststore.p12"));

    final var configuration = selfManaged();
    final var auth = configuration.getAuth();
    auth.setMethod(Camunda8AuthConfiguration.Method.OIDC);
    auth.setClientId("vanillabp");
    auth.setClientSecret("h0rse-battery-staple");
    auth.setAudience("zeebe-api");
    auth.setAuthorizationServerUrl("http://localhost:18080/auth/realms/camunda/protocol/openid-connect/token");
    auth.setScope("camunda-identity");
    auth.setCredentialsCachePath(directory.resolve("credentials").toString());
    auth.setConnectTimeout(Duration.ofSeconds(3));
    auth.setReadTimeout(Duration.ofSeconds(7));
    auth.setKeystorePath(keystore.toString());
    auth.setKeystorePassword("keystore");
    auth.setKeystoreKeyPassword("key");
    auth.setTruststorePath(truststore.toString());
    auth.setTruststorePassword("truststore");

    final var provider = Camunda8Authentication
        .of("c8", configuration, variable -> null)
        .providerFor(message -> {
        });

    assertNotNull(Camunda8Authentication.unwrap(provider));

  }

  @Test
  @DisplayName("only a refusal is a refusal: a missing resource or a broken cluster is not")
  public void onlyRefusalsAreReported() {

    assertTrue(Camunda8Authentication.isRefusal(status(401, true)));
    assertTrue(Camunda8Authentication.isRefusal(status(403, false)));
    assertTrue(Camunda8Authentication.isRefusal(status(16, false)));
    assertTrue(Camunda8Authentication.isRefusal(status(7, false)));
    assertFalse(Camunda8Authentication.isRefusal(status(404, false)));
    assertFalse(Camunda8Authentication.isRefusal(status(500, false)));
    assertFalse(Camunda8Authentication.isRefusal(null));

  }

  @Test
  @DisplayName("what the client is asked stays what the client answers")
  public void theWrapperDelegates() {

    final var configuration = selfManaged();
    configuration.getAuth().setUsername("demo");
    configuration.getAuth().setPassword("demo");
    final var provider = Camunda8Authentication
        .of("c8", configuration, variable -> null)
        .providerFor(message -> {
        });

    final var headers = new LinkedHashMap<String, String>();
    Assertions.assertDoesNotThrow(() -> provider.applyCredentials(headers::put));

    assertEquals(1, headers.size(), headers.toString());
    assertTrue(headers.containsKey("Authorization"), headers.toString());

  }

}
