package io.vanillabp.camunda8.springboot.smoke;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Discovery test of the per-adapter-id bean convention (the adapter config model): TWO
 * adapter ids of type {@code camunda8} (e.g. an on-prem and a SaaS cluster
 * side by side - the migration scenario) yield one {@code Camunda8ProcessService} and
 * one {@code Camunda8DeploymentService} element bean PER configured id, each with its
 * own client factory. No cluster or Docker involved (deployment lifecycle disabled,
 * clients built lazily).
 */
@ExtendWith(SuppressOutputExtension.class)
@SpringBootTest(
    classes = SmokeTestApplication.class,
    properties = {
        "spring.autoconfigure.exclude=io.vanillabp.integration.deployment.DeploymentAutoConfiguration", "vanillabp.prioritized-adapters=c8,c8-two", "vanillabp.adapters.c8-two.type=camunda8", "vanillabp.adapters.c8-two.job-lease=use", "vanillabp.adapters.c8-two.rest-address=http://localhost:8081", "vanillabp.workflow-modules.smoke-app.adapters.c8-two.resources-location=classpath:test-app/processes/c8-two"
    })
public class Camunda8TwoAdapterIdsDiscoveryTest {

  @Autowired
  private ApplicationContext context;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @Test
  public void perIdBeansAreRegisteredForBothIds() {

    final var processServiceIds = context
        .getBeanProvider(MigratableProcessService.class)
        .stream()
        .map(processService -> ((MigratableProcessService<?>) processService).getAdapterId())
        .collect(Collectors.toSet());
    Assertions.assertEquals(Set.of("c8", "c8-two"), processServiceIds);

    final var deploymentServiceIds = context
        .getBeanProvider(AdapterDeploymentService.class)
        .stream()
        .map(deploymentService -> ((AdapterDeploymentService<?, ?>) deploymentService).getAdapterId())
        .collect(Collectors.toSet());
    Assertions.assertEquals(Set.of("c8", "c8-two"), deploymentServiceIds);

    // each id owns its own client factory fed from its own overlay section
    Assertions.assertNotNull(clientFactoryRegistry.getFactory("c8"));
    Assertions.assertNotNull(clientFactoryRegistry.getFactory("c8-two"));

  }

}
