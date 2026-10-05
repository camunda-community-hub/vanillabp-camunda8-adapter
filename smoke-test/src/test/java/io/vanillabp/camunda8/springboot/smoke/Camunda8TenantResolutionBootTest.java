package io.vanillabp.camunda8.springboot.smoke;

import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.camunda8.springboot.client.VanillaBpCamunda8Properties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Where the Spring binding reads <code>tenant-id</code>. The name is resolved per workflow
 * module, with the adapter's section as the fallback, and the key it was read from travels
 * with it because a message about a tenant has to quote the line the developer wrote.
 * <p>
 * Two workflow modules sharing a BPMN process id depend on this: one name for the whole
 * adapter puts both into one tenant, and a name for one of them is how the application gives
 * that module a scope of its own.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8TenantResolutionBootTest {

  private static final String DEPLOYMENT_EXCLUDE = "spring.autoconfigure.exclude=io.vanillabp.integration.deployment.DeploymentAutoConfiguration";

  private static final String MODULE = "loan-approval";

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
  public void theWorkflowModulesNameWinsOverTheAdapters() {

    try (var context = run(
        "vanillabp.adapters.c8.rest-address=http://localhost:65535",
        "vanillabp.adapters.c8.tenant-id=one-tenant-for-all",
        "vanillabp.workflow-modules.loan-approval.adapters.c8.tenant-id=loans-tenant")) {

      final var overlay = context.getBean(VanillaBpCamunda8Properties.class);

      final var perModule = overlay.configuredTenantFor("c8", MODULE);
      Assertions.assertEquals("loans-tenant", perModule.tenantId());
      Assertions
          .assertEquals(
              "vanillabp.workflow-modules.loan-approval.adapters.c8.tenant-id",
              perModule.propertyKey(),
              "a message about this name has to send the developer to the line which holds it");

      final var perAdapter = overlay.configuredTenantFor("c8", "risk-assessment");
      Assertions
          .assertEquals(
              "one-tenant-for-all",
              perAdapter.tenantId(),
              "another module of the same application falls back to the adapter's name");
      Assertions.assertEquals("vanillabp.adapters.c8.tenant-id", perAdapter.propertyKey());

    }

  }

  @Test
  public void nothingConfiguredAnywhereReadsAsNoName() {

    try (var context = run("vanillabp.adapters.c8.rest-address=http://localhost:65535")) {

      final var overlay = context.getBean(VanillaBpCamunda8Properties.class);

      Assertions
          .assertNull(
              overlay.configuredTenantFor("c8", MODULE),
              "the workflow module id names the tenant then, which the adapter decides");
      Assertions
          .assertNull(
              overlay.configuredTenantFor("c8", null),
              "and a question without a workflow module is answered the same way");

    }

  }

  @Test
  public void aBlankNameIsNotATenant() {

    try (var context = run(
        "vanillabp.adapters.c8.rest-address=http://localhost:65535",
        "vanillabp.adapters.c8.tenant-id=  ")) {

      Assertions
          .assertNull(
              context
                  .getBean(VanillaBpCamunda8Properties.class)
                  .configuredTenantFor("c8", MODULE),
              "a blank name is not a tenant named ' '");

    }

  }

}
