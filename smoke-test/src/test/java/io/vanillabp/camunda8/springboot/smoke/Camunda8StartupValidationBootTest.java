package io.vanillabp.camunda8.springboot.smoke;

import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.camunda.client.impl.basicauth.BasicAuthCredentialsProvider;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8Authentication;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Boot tests of the Camunda 8 startup validation: configuration is
 * validated AT STARTUP, never first at runtime.
 * <ul>
 *   <li>no cluster address → the application BOOTS with a client for the local cluster of
 *       the release line, and a WARN names that address and the key which changes it;</li>
 *   <li>inconsistent connection config of a first-priority adapter → the boot FAILS
 *       naming the missing keys;</li>
 *   <li>inconsistent config of a nowhere-first adapter with policy 'warn' → the
 *       application boots DEGRADED with a warning;</li>
 *   <li>fully configured → no warning, and the configured secret NEVER appears in
 *       the log (messages name keys, not values);</li>
 *   <li>an <code>auth</code> block binds through the Spring overlay and
 *       reaches the client, an incomplete one fails the boot with the YAML which
 *       completes it, and no password reaches a log line.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8StartupValidationBootTest {

  private static final String DEPLOYMENT_EXCLUDE = "spring.autoconfigure.exclude=io.vanillabp.integration.deployment.DeploymentAutoConfiguration";

  private ConfigurableApplicationContext run(
      final String... properties) {

    // command-line arguments (highest precedence) so the scenarios override the
    // application.yaml defaults
    final var args = Stream
        .concat(
            Stream.of(DEPLOYMENT_EXCLUDE), Stream.of(properties))
        .map("--%s"::formatted)
        .toArray(String[]::new);
    return new SpringApplicationBuilder(SmokeTestApplication.class)
        .web(WebApplicationType.NONE)
        .run(args);

  }

  private static final String NO_ADDRESS = "Camunda 8 adapter 'c8' has no cluster address, so it connects to the local cluster at";

  @Test
  public void anAdapterWithoutAnAddressBootsAndUsesTheLocalCluster(
      final CapturedOutput output) {

    final var before = output.getAll().length();

    // application.yaml configures adapter 'c8' WITHOUT any connection property
    try (var context = run()) {
      Assertions.assertTrue(context.isActive());
      final var client = context
          .getBean(Camunda8ClientFactoryRegistry.class)
          .getFactory("c8")
          .getClient();
      Assertions.assertEquals(
          Camunda8AdapterConfiguration.LOCAL_CLUSTER_REST_ADDRESS,
          client.getConfiguration().getRestAddress().toString(),
          "the client talks to the address the warning names");
    }

    final var log = output.getAll().substring(before);
    Assertions.assertTrue(
        log.contains(NO_ADDRESS + " '%s'. This address matches Camunda's docker compose for "
            .formatted(Camunda8AdapterConfiguration.LOCAL_CLUSTER_REST_ADDRESS)),
        "expected the warning naming the local cluster but got: "
            + log);
    Assertions.assertTrue(log.contains("Set 'vanillabp.adapters.c8.rest-address' to connect to another cluster"), log);
    Assertions.assertTrue(log.contains("vanillabp.adapters.c8.mode"));
    Assertions.assertTrue(log.contains("vanillabp.adapters.c8.client-secret"));
    // where the local cluster of the line takes port 8080, the default port of Spring Boot,
    // the warning names the key which moves the application. Which lines that are is held
    // by Camunda8LocalClusterTest of each line
    Assertions.assertEquals(
        Camunda8AdapterConfiguration.LOCAL_CLUSTER_REST_ADDRESS.endsWith(":8080"),
        log.contains("so this application needs another port. Set 'server.port' to pick one."),
        "the warning names the port key of Spring Boot where the line needs it: "
            + log);

  }

  @Test
  public void inconsistentFirstPriorityAdapterFailsTheBoot() {

    // 'c8' is first priority and configured inconsistently: saas without any
    // credential - a genuine defect has to fail the boot naming the missing keys
    final var exception = Assertions.assertThrows(
        Exception.class,
        () -> run("vanillabp.adapters.c8.mode=saas").close());

    final var failure = rootMessage(exception);
    Assertions.assertTrue(
        failure.contains("Camunda 8 adapter 'c8' is configured inconsistently"),
        "expected the guiding failure but got: "
            + failure);
    Assertions.assertTrue(failure.contains("vanillabp.adapters.c8.cluster-id"));
    Assertions.assertTrue(failure.contains("vanillabp.adapters.c8.region"));
    Assertions.assertTrue(failure.contains("vanillabp.adapters.c8.client-id"));
    Assertions.assertTrue(failure.contains("vanillabp.adapters.c8.client-secret"));
    Assertions.assertTrue(failure.contains("vanillabp.adapters.c8.deployment-failure"));

  }

  @Test
  public void inconsistentNowhereFirstAdapterWithWarnPolicyBootsDegraded(
      final CapturedOutput output) {

    final var before = output.getAll().length();

    // 'c8-two' is nowhere first ('c8' leads everywhere) and its policy is 'warn':
    // the inconsistent config degrades to a warning instead of failing the boot
    try (var context = run(
        "vanillabp.prioritized-adapters=c8,c8-two",
        "vanillabp.adapters.c8.rest-address=http://localhost:8080",
        "vanillabp.adapters.c8-two.type=camunda8",
        "vanillabp.adapters.c8-two.deployment-failure=warn",
        "vanillabp.adapters.c8-two.mode=saas",
        "vanillabp.workflow-modules.smoke-app.adapters.c8-two.resources-location=classpath*:test-app/processes-two")) {
      Assertions.assertTrue(context.isActive());
    }

    final var log = output.getAll().substring(before);
    Assertions.assertTrue(
        log.contains("Camunda 8 adapter 'c8-two' is configured inconsistently"),
        "expected the degraded-boot warning but got: "
            + log);
    Assertions.assertTrue(log.contains("boots DEGRADED"));
    Assertions.assertTrue(log.contains("vanillabp.adapters.c8-two.cluster-id"));

  }

  @Test
  public void fullyConfiguredAdapterBootsWithoutWarningAndWithoutEchoingSecrets(
      final CapturedOutput output) {

    final var secret = "super-secret-credential-4711";
    final var before = output.getAll().length();

    try (var context = run(
        "vanillabp.adapters.c8.mode=saas",
        "vanillabp.adapters.c8.cluster-id=my-cluster",
        "vanillabp.adapters.c8.region=bru-2",
        "vanillabp.adapters.c8.client-id=my-client",
        "vanillabp.adapters.c8.client-secret="
            + secret)) {
      Assertions.assertTrue(context.isActive());
    }

    final var log = output.getAll().substring(before);
    Assertions.assertFalse(
        log.contains("has no cluster address, so it connects to the local cluster at"),
        "no warning expected for a fully configured adapter but got: "
            + log);
    Assertions.assertFalse(
        log.contains("configured inconsistently"),
        "no warning expected for a fully configured adapter but got: "
            + log);
    // hard rule: values - especially credentials - are never echoed
    Assertions.assertFalse(
        log.contains(secret),
        "the configured client-secret must never appear in the log");

  }

  @Test
  public void authenticationBlockBindsAndIsReportedWithoutItsPassword(
      final CapturedOutput output) {

    final var password = "super-secret-password-4711";
    final var before = output.getAll().length();

    try (var context = run(
        "vanillabp.adapters.c8.rest-address=http://localhost:8080",
        "vanillabp.adapters.c8.auth.username=demo",
        "vanillabp.adapters.c8.auth.password="
            + password)) {

      Assertions.assertTrue(context.isActive());
      final var provider = context
          .getBean(Camunda8ClientFactoryRegistry.class)
          .getFactory("c8")
          .getClient()
          .getConfiguration()
          .getCredentialsProvider();
      Assertions
          .assertInstanceOf(
              BasicAuthCredentialsProvider.class,
              Camunda8Authentication.unwrap(provider),
              "the auth block of the Spring overlay reaches the client");
    }

    final var log = output.getAll().substring(before);
    Assertions.assertTrue(log.contains("authentication basic (detected) as 'demo'"), log);
    Assertions.assertFalse(log.contains(password), "a password never appears in the log");

  }

  @Test
  public void incompleteAuthenticationFailsTheBootWithTheYamlWhichCompletesIt() {

    final var exception = Assertions.assertThrows(
        Exception.class,
        () -> run(
            "vanillabp.adapters.c8.rest-address=http://localhost:8080",
            "vanillabp.adapters.c8.auth.username=demo").close());

    final var failure = rootMessage(exception);
    Assertions.assertTrue(failure.contains("authenticates with 'basic'"), failure);
    Assertions.assertTrue(failure.contains("vanillabp.adapters.c8.auth.password"), failure);
    Assertions.assertTrue(failure.contains("method: basic"), failure);

  }

  private static String rootMessage(
      final Throwable throwable) {

    var cause = throwable;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());

  }

}
