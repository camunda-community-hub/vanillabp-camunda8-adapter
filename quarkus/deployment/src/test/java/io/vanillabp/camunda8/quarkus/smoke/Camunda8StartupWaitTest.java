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
 * The counterpart of {@link Camunda8DeploymentPipelineTest}: with a
 * <code>startup-wait</code> configured, a start which cannot reach its cluster ends with
 * the WAIT's message rather than with the deployment's, which is what proves the wait sits
 * in the boot before the first round the adapter makes to the cluster.
 * <p>
 * The wait of this application is two seconds, because the cluster is a closed port and
 * stays one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8StartupWaitTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(Aggregate.class)
          .addClass(SampleWorkflowService.class)
          .addClass(TestPhaseTwoOutbox.class)
          .addAsResource("startup-wait/application.yaml", "application.yaml")
          .addAsResource("test-app/processes/test-process.bpmn", "test-app/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> Assertions.assertTrue(
          hasCauseWithMessagePart(
              throwable,
              "did not reach the cluster http://localhost:1 within 'vanillabp.adapters.c8.startup-wait: PT2S'"),
          "expected the wait to end the boot naming the address and the deadline but got: "
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
  public void theStartWaitsForTheClusterBeforeItDeploys() {
    // the assertion happens on the startup exception (assertException above)
  }

}
