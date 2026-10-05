package io.vanillabp.camunda8.springboot.smoke;

import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.camunda8.springboot.client.VanillaBpCamunda8Properties;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Where the Spring binding reads <code>allow-connectors</code>, and what it says about a
 * value written at a level it does not read.
 * <p>
 * Three levels resolve it, the most specific configured one winning in both directions -
 * which includes the case VanillaBP 1 could not express, a workflow module switching OFF
 * what the adapter switched on.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8AllowConnectorsBootTest {

  private static final String DEPLOYMENT_EXCLUDE = "spring.autoconfigure.exclude=io.vanillabp.integration.deployment.DeploymentAutoConfiguration";

  private ConfigurableApplicationContext run(
      final String... properties) {

    final var args = Stream
        .concat(
            Stream.of(DEPLOYMENT_EXCLUDE), Stream.of(properties))
        .map("--%s"::formatted)
        .toArray(String[]::new);
    return new SpringApplicationBuilder(SmokeTestApplication.class)
        .web(WebApplicationType.NONE)
        .run(args);

  }

  @Test
  public void theThreeLevelsResolveMostSpecificFirst() {

    try (var context = run(
        "vanillabp.adapters.c8.rest-address=http://localhost:65535",
        "vanillabp.adapters.c8.allow-connectors=true",
        "vanillabp.workflow-modules.smoke-app.adapters.c8.allow-connectors=false",
        "vanillabp.workflow-modules.smoke-app.workflows.LoanApproval.adapters.c8.allow-connectors=true")) {

      final var overlay = context.getBean(VanillaBpCamunda8Properties.class);

      final var perWorkflow = overlay.allowConnectorsFor("smoke-app", "LoanApproval", "c8");
      Assertions.assertTrue(perWorkflow.allowed());
      Assertions.assertEquals(
          "vanillabp.workflow-modules.smoke-app.workflows.LoanApproval.adapters.c8.allow-connectors",
          perWorkflow.propertyKey(),
          "the report has to name the line the reader can find in their configuration");

      final var perModule = overlay.allowConnectorsFor("smoke-app", "OtherProcess", "c8");
      Assertions.assertFalse(
          perModule.allowed(),
          "a workflow module may switch OFF what the adapter switched on, which version 1 could not");
      Assertions.assertEquals(
          "vanillabp.workflow-modules.smoke-app.adapters.c8.allow-connectors",
          perModule.propertyKey());

      final var perAdapter = overlay.allowConnectorsFor("other-module", "SomeProcess", "c8");
      Assertions.assertTrue(perAdapter.allowed());
      Assertions.assertEquals("vanillabp.adapters.c8.allow-connectors", perAdapter.propertyKey());

    }

  }

  @Test
  public void nothingConfiguredWiresEveryElement() {

    try (var context = run("vanillabp.adapters.c8.rest-address=http://localhost:65535")) {

      final var resolved = context
          .getBean(VanillaBpCamunda8Properties.class)
          .allowConnectorsFor("smoke-app", "LoanApproval", "c8");

      Assertions.assertFalse(resolved.allowed(), "the default is what this adapter has always done");
      Assertions.assertNull(resolved.propertyKey(), "and no key stands behind it");

    }

  }

  @Test
  public void aValueAtTaskLevelIsReportedAndTheBootGoesOn(
      final CapturedOutput output) {

    final var before = output.getAll().length();

    try (var context = run(
        "vanillabp.adapters.c8.rest-address=http://localhost:65535",
        "vanillabp.workflow-modules.smoke-app.workflows.LoanApproval.tasks.assessRisk.adapters.c8.allow-connectors=true")) {

      Assertions.assertTrue(context.isActive(), "a key which changes nothing may not end a boot");
      Assertions.assertFalse(
          context
              .getBean(VanillaBpCamunda8Properties.class)
              .allowConnectorsFor("smoke-app", "LoanApproval", "c8")
              .allowed(),
          "and it changes no answer");

    }

    final var log = output.getAll().substring(before);
    Assertions.assertTrue(
        log.contains("tasks.assessRisk.adapters.c8.allow-connectors"),
        () -> "the key the reader has to find: "
            + log);
    Assertions.assertTrue(
        log.contains("vanillabp.workflow-modules.<m>.adapters.c8.allow-connectors"),
        () -> "and the levels which do resolve it: "
            + log);

  }

}
