package io.vanillabp.camunda8.springboot.smoke;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.DeployedProcess;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two workflow modules bringing the same BPMN process id, judged by the real core against the
 * real adapter.
 * <p>
 * A deployment is per workflow module, so the adapter hands over the processes of the module it
 * is deploying and the core holds what the earlier modules of the boot brought. Whether two
 * equal process ids are a clash is then the adapter's answer, because the mode which applies
 * by default leaves the ids plain and the scope which keeps the modules apart is the cluster's
 * tenant. The configuration decides the outcome: a tenant per workflow module separates them,
 * one <code>tenant-id</code> for the whole adapter does not.
 * <p>
 * No cluster is needed for any of it. The question is put to the core the way the adapter puts
 * it while deploying, and neither side asks the cluster anything to answer it, which is why
 * this is a boot test and not an integration test.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CollidingProcessIdsBootTest {

  private static final String DEPLOYMENT_EXCLUDE = "spring.autoconfigure.exclude=io.vanillabp.integration.deployment.DeploymentAutoConfiguration";

  private static final String ONE_MODULE = "loan-approval";

  private static final String ANOTHER_MODULE = "risk-assessment";

  private static final String SHARED_PROCESS = "Approval";

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

  /**
   * What the adapter hands to the core while it deploys one workflow module.
   */
  private void deploying(
      final NameClashAvoidanceSupport scoping,
      final String workflowModuleId) {

    scoping.validateNoCollidingProcessIds("c8", List.of(new DeployedProcess(workflowModuleId, SHARED_PROCESS)));

  }

  @Test
  public void oneTenantForTheWholeAdapterEndsTheBootOfTheSecondModule() {

    try (var context = run(
        "vanillabp.adapters.c8.rest-address=http://localhost:65535",
        "vanillabp.adapters.c8.tenant-id=one-tenant-for-all")) {

      final var scoping = context.getBean(NameClashAvoidanceSupport.class);

      // the first module gets into the cluster, because nothing it collides with was
      // deployed yet
      Assertions.assertDoesNotThrow(() -> deploying(scoping, ONE_MODULE));

      final var refusal = Assertions
          .assertThrows(IllegalStateException.class, () -> deploying(scoping, ANOTHER_MODULE));

      // what a reader has to be able to act on without opening anything else
      final var message = refusal.getMessage();
      Assertions
          .assertTrue(
              message.contains(ONE_MODULE) && message.contains(ANOTHER_MODULE),
              () -> "both workflow modules, because the reader has to know which two deployments met: "
                  + message);
      Assertions
          .assertTrue(
              message.contains(SHARED_PROCESS),
              () -> "and the process id they share: "
                  + message);
      Assertions
          .assertTrue(
              message.contains("vanillabp.adapters.c8.tenant-id"),
              () -> "and the property which put them into one tenant: "
                  + message);

    }

  }

  @Test
  public void theRefusalDoesNotDependOnWhichModuleDeploysFirst() {

    // the order of the workflow modules is not the application's choice, so the other order
    // has to end the same way
    try (var context = run(
        "vanillabp.adapters.c8.rest-address=http://localhost:65535",
        "vanillabp.adapters.c8.tenant-id=one-tenant-for-all")) {

      final var scoping = context.getBean(NameClashAvoidanceSupport.class);

      Assertions.assertDoesNotThrow(() -> deploying(scoping, ANOTHER_MODULE));

      final var refusal = Assertions
          .assertThrows(IllegalStateException.class, () -> deploying(scoping, ONE_MODULE));

      Assertions
          .assertTrue(
              refusal.getMessage().contains(ONE_MODULE) && refusal.getMessage().contains(ANOTHER_MODULE),
              () -> "both modules again, named in the order they deployed: "
                  + refusal.getMessage());

    }

  }

  @Test
  public void aTenantPerWorkflowModuleLetsBothModulesDeploy() {

    // the default mode with nothing configured, which is what most applications run: the
    // tenant is named after the workflow module, so the two process ids never meet. An
    // application like this boots today and has to keep booting
    try (var context = run("vanillabp.adapters.c8.rest-address=http://localhost:65535")) {

      final var scoping = context.getBean(NameClashAvoidanceSupport.class);

      Assertions.assertDoesNotThrow(() -> deploying(scoping, ONE_MODULE));
      Assertions
          .assertDoesNotThrow(
              () -> deploying(scoping, ANOTHER_MODULE),
              "two tenants keep the two processes apart, so this is no clash");

    }

  }

  @Test
  public void aTenantForOneWorkflowModuleIsTheWayOutTheMessageOffers() {

    // the refusal above tells the developer to give one of the two modules a scope of its
    // own and names that key. This is the boot which follows that advice: everything else
    // stays in the shared tenant, and both modules deploy
    try (var context = run(
        "vanillabp.adapters.c8.rest-address=http://localhost:65535",
        "vanillabp.adapters.c8.tenant-id=one-tenant-for-all",
        "vanillabp.workflow-modules.%s.adapters.c8.tenant-id=loans-tenant".formatted(ONE_MODULE))) {

      final var scoping = context.getBean(NameClashAvoidanceSupport.class);

      Assertions.assertDoesNotThrow(() -> deploying(scoping, ONE_MODULE));
      Assertions
          .assertDoesNotThrow(
              () -> deploying(scoping, ANOTHER_MODULE),
              "the two modules are in two tenants now, so the shared process id is no clash");

    }

  }

  @Test
  public void oneModuleDeployedTwiceIsNoClash() {

    // a BPMN file holding several processes and a workflow module deployed to two adapter
    // ids both report the same pair twice, and neither is two processes under one id
    try (var context = run(
        "vanillabp.adapters.c8.rest-address=http://localhost:65535",
        "vanillabp.adapters.c8.tenant-id=one-tenant-for-all")) {

      final var scoping = context.getBean(NameClashAvoidanceSupport.class);

      Assertions.assertDoesNotThrow(() -> deploying(scoping, ONE_MODULE));
      Assertions.assertDoesNotThrow(() -> deploying(scoping, ONE_MODULE));

    }

  }

}
