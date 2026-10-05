package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.client.Camunda8AuthConfiguration.Method;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How the authentication of one adapter instance resolves, and what it says while it
 * cannot be built. Every message is asserted by the property keys it has to
 * carry: a message which names the defect without naming the key sends the reader to the
 * documentation, which is what these messages exist to avoid.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8AuthConfigurationTest {

  private static Camunda8AdapterConfiguration selfManaged() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");
    return configuration;

  }

  private static Camunda8AdapterConfiguration saas() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setMode(Camunda8AdapterConfiguration.Mode.SAAS);
    configuration.setClusterId("cluster");
    configuration.setRegion("bru-2");
    configuration.setClientId("client");
    configuration.setClientSecret("secret");
    return configuration;

  }

  @Test
  @DisplayName("an adapter without the block authenticates with none")
  public void absentBlockIsNone() {

    final var configuration = selfManaged();

    assertTrue(configuration.getAuth().isAbsent());
    assertEquals(Method.NONE, configuration.getAuth().resolveMethod(configuration.getMode()));
    assertDoesNotThrow(() -> configuration.validateAuthentication("c8"));

  }

  @Test
  @DisplayName("a user name detects basic, a client id detects oidc, SaaS detects oidc")
  public void methodIsDetectedFromTheKeysWhichAreSet() {

    final var basic = selfManaged();
    basic.getAuth().setUsername("demo");
    basic.getAuth().setPassword("demo");
    assertEquals(Method.BASIC, basic.getAuth().resolveMethod(basic.getMode()));

    final var oidc = selfManaged();
    oidc.getAuth().setClientId("app");
    assertEquals(Method.OIDC, oidc.getAuth().resolveMethod(oidc.getMode()));

    final var cloud = saas();
    assertEquals(Method.OIDC, cloud.getAuth().resolveMethod(cloud.getMode()));

  }

  @Test
  @DisplayName("a named method wins over the detection")
  public void namedMethodWins() {

    final var configuration = selfManaged();
    configuration.getAuth().setMethod(Method.NONE);

    assertEquals(Method.NONE, configuration.getAuth().resolveMethod(configuration.getMode()));
    assertTrue(configuration.getAuth().isMethodExplicit());

  }

  @Test
  @DisplayName("basic and oidc keys at once cannot be detected and name the method key")
  public void twoMethodsAtOnceFail() {

    final var configuration = selfManaged();
    configuration.getAuth().setUsername("demo");
    configuration.getAuth().setClientId("app");

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> configuration.validateAuthentication("c8"));

    assertTrue(exception.getMessage().contains("vanillabp.adapters.c8.auth.method"), exception.getMessage());
    assertTrue(exception.getMessage().contains("both basic and OIDC"), exception.getMessage());

  }

  @Test
  @DisplayName("credentials which would never be sent fail rather than being ignored")
  public void credentialsBelowMethodNoneFail() {

    final var configuration = selfManaged();
    configuration.getAuth().setMethod(Method.NONE);
    configuration.getAuth().setUsername("demo");

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> configuration.validateAuthentication("c8"));

    assertTrue(exception.getMessage().contains("would never be sent"), exception.getMessage());
    assertTrue(exception.getMessage().contains("vanillabp.adapters.c8.auth.method"), exception.getMessage());

  }

  @Test
  @DisplayName("an incomplete basic block names the missing key and the YAML which completes it")
  public void incompleteBasicNamesKeyAndYaml() {

    final var configuration = selfManaged();
    configuration.getAuth().setUsername("demo");

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> configuration.validateAuthentication("c8"));

    final var message = exception.getMessage();
    assertTrue(message.contains("authenticates with 'basic'"), message);
    assertTrue(message.contains("vanillabp.adapters.c8.auth.password"), message);
    assertTrue(message.contains("method: basic"), message);
    assertTrue(message.contains("username: <the cluster's user>"), message);

  }

  @Test
  @DisplayName("an incomplete oidc block names every missing key, the authorization server included")
  public void incompleteOidcNamesEveryMissingKey() {

    final var configuration = selfManaged();
    configuration.getAuth().setClientId("app");

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> configuration.validateAuthentication("c8"));

    final var message = exception.getMessage();
    assertTrue(message.contains("authenticates with 'oidc'"), message);
    assertTrue(message.contains("vanillabp.adapters.c8.auth.client-secret"), message);
    assertTrue(message.contains("vanillabp.adapters.c8.auth.authorization-server-url"), message);
    assertTrue(message.contains("vanillabp.adapters.c8.auth.audience"), message);

  }

  @Test
  @DisplayName("a complete oidc block validates")
  public void completeOidcValidates() {

    final var configuration = selfManaged();
    configuration.getAuth().setClientId("app");
    configuration.getAuth().setClientSecret("secret");
    configuration.getAuth().setAuthorizationServerUrl("http://keycloak/protocol/openid-connect/token");
    configuration.getAuth().setAudience("zeebe-api");

    assertDoesNotThrow(() -> configuration.validateAuthentication("c8"));
    assertEquals("app", configuration.getAuth().principal(configuration));

  }

  @Test
  @DisplayName("SaaS needs no auth block: its connection keys ARE the oidc client")
  public void saasNeedsNoAuthBlock() {

    final var configuration = saas();

    assertDoesNotThrow(() -> configuration.validateAuthentication("cloud"));
    assertTrue(configuration.getAuth().missingProperties(true).isEmpty());
    assertEquals("client", configuration.getAuth().principal(configuration));

  }

  @Test
  @DisplayName("basic against SaaS names the connection keys SaaS accepts instead")
  public void basicAgainstSaasFails() {

    final var configuration = saas();
    configuration.getAuth().setMethod(Method.BASIC);

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> configuration.validateAuthentication("cloud"));

    assertTrue(exception.getMessage().contains("vanillabp.adapters.cloud.client-id"), exception.getMessage());
    assertTrue(exception.getMessage().contains("mode: self-managed"), exception.getMessage());

  }

  @Test
  @DisplayName("a file which is not there is a boot failure naming the key and the path")
  public void unreadableFileFails() {

    final var configuration = selfManaged();
    configuration.getAuth().setCaCertificatePath("/no/such/ca.pem");

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> configuration.validateAuthentication("c8"));

    assertTrue(
        exception.getMessage().contains("vanillabp.adapters.c8.auth.ca-certificate-path"),
        exception.getMessage());
    assertTrue(exception.getMessage().contains("/no/such/ca.pem"), exception.getMessage());

  }

  @Test
  @DisplayName("an adapter carrying only an auth block still hears that it uses the local cluster")
  public void anAuthBlockAloneStillReportsTheClientDefaultAddress() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.getAuth().setUsername("demo");
    final var warnings = new java.util.ArrayList<String>();

    Camunda8StartupValidation.reportTheLocalClusterAddress("c8", configuration, "server.port", warnings::add);

    assertTrue(
        warnings.size() == 1 && warnings.getFirst().contains("vanillabp.adapters.c8.rest-address"),
        "the boot has to report the missing address, not silence: "
            + warnings);

  }

}
