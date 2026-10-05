package io.vanillabp.camunda8.springboot.smoke;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * That a booting Spring Boot application really hands the adapter the collection point its
 * findings go into.
 * <p>
 * What the adapter puts in there is tested where each check is. What is tested here is the
 * wiring, because its failure is quiet: a deployment service without the collection point
 * writes its findings into the log instead of the block at the end of the start, and both
 * are one line, so nobody reading a boot log would see the difference.
 */
@ExtendWith(SuppressOutputExtension.class)
@SpringBootTest(
    classes = SmokeTestApplication.class,
    properties = "spring.autoconfigure.exclude=io.vanillabp.integration.deployment.DeploymentAutoConfiguration")
public class Camunda8StartupReportBootTest {

  @Autowired
  private ApplicationContext context;

  @Test
  public void theDeploymentServiceReportsIntoTheBlockOfTheStart() {

    final var deploymentService = (Camunda8DeploymentService) context
        .getBean(AdapterDeploymentService.class);

    Assertions
        .assertNotNull(
            deploymentService.getStartupReport(),
            "the adapter has to reach the block the platform writes at the end of a start");
    Assertions
        .assertSame(
            context.getBean(StartupReport.class),
            deploymentService.getStartupReport(),
            "and it has to be the one the application published, not one of its own");

  }

}
