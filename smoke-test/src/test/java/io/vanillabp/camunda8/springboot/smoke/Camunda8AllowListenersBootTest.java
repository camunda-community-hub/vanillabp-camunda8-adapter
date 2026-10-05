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
 * Where the Spring binding reads <code>allow-listeners</code>, and what it says about a value
 * written at a level it does not read.
 * <p>
 * The same three levels and the same most-specific-wins rule as
 * <code>allow-connectors</code>, deliberately: two keys about what a model may contain,
 * reaching different levels, would be the worse answer. The Camunda 7 adapter reads the very
 * same three for the very same key.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8AllowListenersBootTest {

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
        "vanillabp.adapters.c8.allow-listeners=true",
        "vanillabp.workflow-modules.smoke-app.adapters.c8.allow-listeners=false",
        "vanillabp.workflow-modules.smoke-app.workflows.LoanApproval.adapters.c8.allow-listeners=true")) {

      final var overlay = context.getBean(VanillaBpCamunda8Properties.class);

      final var perWorkflow = overlay.allowListenersFor("smoke-app", "LoanApproval", "c8");
      Assertions.assertTrue(perWorkflow.allowed());
      Assertions.assertEquals(
          "vanillabp.workflow-modules.smoke-app.workflows.LoanApproval.adapters.c8.allow-listeners",
          perWorkflow.propertyKey(),
          "the report has to name the line the reader can find in their configuration");

      final var perModule = overlay.allowListenersFor("smoke-app", "OtherProcess", "c8");
      Assertions.assertFalse(
          perModule.allowed(),
          "a workflow module may switch OFF what the adapter switched on, which version 1 could not");
      Assertions.assertEquals(
          "vanillabp.workflow-modules.smoke-app.adapters.c8.allow-listeners",
          perModule.propertyKey());

      final var perAdapter = overlay.allowListenersFor("other-module", "SomeProcess", "c8");
      Assertions.assertTrue(perAdapter.allowed());
      Assertions.assertEquals("vanillabp.adapters.c8.allow-listeners", perAdapter.propertyKey());

    }

  }

  @Test
  public void nothingConfiguredServesNoModelledListener() {

    try (var context = run("vanillabp.adapters.c8.rest-address=http://localhost:65535")) {

      final var resolved = context
          .getBean(VanillaBpCamunda8Properties.class)
          .allowListenersFor("smoke-app", "LoanApproval", "c8");

      Assertions.assertFalse(resolved.allowed(), "version 1 served them unannounced, which is why the default is off");
      Assertions.assertNull(resolved.propertyKey(), "and no key stands behind it");

    }

  }

  @Test
  public void aValueAtTaskLevelIsReportedAndTheBootGoesOn(
      final CapturedOutput output) {

    final var before = output.getAll().length();

    try (var context = run(
        "vanillabp.adapters.c8.rest-address=http://localhost:65535",
        "vanillabp.workflow-modules.smoke-app.workflows.LoanApproval.tasks.assessRisk.adapters.c8.allow-listeners=true")) {

      Assertions.assertTrue(context.isActive(), "a key which changes nothing may not end a boot");
      Assertions.assertFalse(
          context
              .getBean(VanillaBpCamunda8Properties.class)
              .allowListenersFor("smoke-app", "LoanApproval", "c8")
              .allowed(),
          "and it changes no answer");

    }

    final var log = output.getAll().substring(before);
    Assertions.assertTrue(
        log.contains("tasks.assessRisk.adapters.c8.allow-listeners"),
        () -> "the key the reader has to find: "
            + log);
    Assertions.assertTrue(
        log.contains("vanillabp.workflow-modules.<m>.adapters.c8.allow-listeners"),
        () -> "and the levels which do resolve it: "
            + log);

  }

}
