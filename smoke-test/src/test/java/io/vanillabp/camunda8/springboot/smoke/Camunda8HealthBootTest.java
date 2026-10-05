package io.vanillabp.camunda8.springboot.smoke;

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;

import io.vanillabp.integration.health.VanillaBpHealthIndicator;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the health endpoint of a Spring Boot application says about a Camunda 8
 * cluster which is configured but not there. No Docker and no cluster: the address points
 * at a port nothing listens on, which is exactly the situation the endpoint exists for.
 * <p>
 * The counterpart - an adapter which is not configured at all - is asserted by
 * {@link Camunda8AdapterDiscoveryTest}, whose application has no address configured.
 */
@ExtendWith(SuppressOutputExtension.class)
@SpringBootTest(
    classes = SmokeTestApplication.class,
    properties = {
        "spring.autoconfigure.exclude=io.vanillabp.integration.deployment.DeploymentAutoConfiguration", "vanillabp.adapters.c8.grpc-address=http://localhost:1", "vanillabp.adapters.c8.prefer-rest-over-grpc=false", "vanillabp.adapters.c8.health-timeout=PT1S"
    })
public class Camunda8HealthBootTest {

  @Autowired
  private VanillaBpHealthIndicator healthIndicator;

  @Test
  @DisplayName("A cluster nobody answers for is DOWN, naming the adapter and the address")
  @SuppressWarnings("unchecked")
  public void anUnreachableClusterIsDown() {

    final var health = healthIndicator.health();

    Assertions.assertEquals(Status.DOWN, health.getStatus());

    final var detail = (Map<String, Object>) health
        .getDetails()
        .get("c8");
    Assertions.assertNotNull(detail, "the detail is named after the adapter id: "
        + health.getDetails());
    Assertions.assertEquals("camunda8", detail.get("type"));
    Assertions.assertEquals(
        "http://localhost:1",
        detail.get("address"),
        "an operator has to see which cluster is meant without reading the configuration");
    Assertions.assertEquals("PT1S", detail.get("timeout"));

  }

}
