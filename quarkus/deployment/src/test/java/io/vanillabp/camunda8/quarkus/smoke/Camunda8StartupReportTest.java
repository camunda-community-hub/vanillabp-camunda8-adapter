package io.vanillabp.camunda8.quarkus.smoke;

import java.util.List;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Checks that a booting Quarkus application really hands the adapter the place where it
 * collects its findings.
 * <p>
 * What the adapter puts in there is tested next to each check. This tests the wiring, because
 * it fails quietly. A deployment service without the collection point writes its findings into
 * the log, not into the block at the end of the start. Both are one line. So nobody who reads
 * a boot log would see the difference.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8StartupReportTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(Aggregate.class)
          .addClass(SampleWorkflowService.class)
          .addClass(TestPhaseTwoOutbox.class)
          .addAsResource("application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  // the platform contract: the List's element type parameters are literally Object
  @Inject
  List<AdapterDeploymentService<Object, Object>> deploymentServices;

  @Inject
  StartupReport startupReport;

  @Test
  public void theDeploymentServiceReportsIntoTheBlockOfTheStart() {

    Assertions.assertEquals(1, deploymentServices.size());
    final var deploymentService = Assertions
        .assertInstanceOf(Camunda8DeploymentService.class, deploymentServices.getFirst());

    Assertions
        .assertNotNull(
            deploymentService.getStartupReport(),
            "the adapter has to reach the block the platform writes at the end of a start");
    Assertions
        .assertSame(
            startupReport,
            deploymentService.getStartupReport(),
            "and it has to be the one the application published, not one of its own");

  }

}
