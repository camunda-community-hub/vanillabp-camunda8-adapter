package io.vanillabp.camunda8.quarkus.smoke;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Proves the platform's runtime deployment pipeline drives the Camunda 8
 * adapter on Quarkus: a BPMN resource below the configured
 * <code>resources-location</code> is read and parsed
 * ({@code readBpmn}/{@code prepareBpmn}/{@code wireBpmn}) and its deployment is
 * attempted at boot. No cluster is available (the configured REST address points to
 * a closed port), so {@code deployResources} fails - and since the adapter is the
 * first-priority adapter, the boot is aborted with the adapter's guiding message.
 * That failure IS the assertion: without the pipeline the application would boot
 * without ever contacting the (non-existing) cluster. The application configures
 * <code>startup-wait: PT0S</code>, because the closed port of this test stays closed and the
 * start would otherwise spend the default ten minutes finding that out. A real-cluster deployment is
 * covered by the Spring Boot module's Docker-based {@code Camunda8DeploymentAndStartIT}
 * (the deployment logic is shared {@code core} code) - see the README.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8DeploymentPipelineTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(Aggregate.class)
          .addClass(SampleWorkflowService.class)
          .addClass(TestPhaseTwoOutbox.class)
          .addAsResource("pipeline/application.yaml", "application.yaml")
          .addAsResource("test-app/processes/test-process.bpmn", "test-app/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> Assertions.assertTrue(
          hasCauseWithMessagePart(
              throwable,
              "Failed to deploy BPMN resources of workflow module 'test-app' to Camunda 8 (adapter 'c8')"),
          "expected the deployment attempt against the unreachable cluster to abort the boot but got: "
              + throwable));

  private static boolean hasCauseWithMessagePart(
      final Throwable throwable,
      final String messagePart) {

    var current = throwable;
    while (current != null) {
      if ((current.getMessage() != null) && current.getMessage().contains(messagePart)) {
        return true;
      }
      current = current.getCause();
    }
    return false;

  }

  @Test
  public void deploymentPipelineReachesTheAdapter() {
    // the assertion happens on the startup exception (assertException above)
  }

}
