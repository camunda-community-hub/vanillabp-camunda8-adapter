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
 * Startup-validation boot test on Quarkus. A first-priority adapter which is configured
 * INCONSISTENTLY (here <code>mode: saas</code> without any credential) fails the boot. The
 * message names the missing property keys. So the developer learns about the defect at
 * startup, and not when the first workflow is started.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8InconsistentConfigurationTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(Aggregate.class)
          .addClass(SampleWorkflowService.class)
          .addClass(TestPhaseTwoOutbox.class)
          .addAsResource("application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("vanillabp.adapters.c8.mode", "saas")
      .assertException(throwable -> {
        final var causes = new StringBuilder();
        for (var cause = throwable; cause != null; cause = cause.getCause()) {
          causes
              .append(cause.getMessage())
              .append('\n');
        }
        final var message = causes.toString();
        Assertions.assertTrue(
            message.contains("Camunda 8 adapter 'c8' is configured inconsistently"),
            "expected the guiding startup failure but got:\n"
                + message);
        Assertions.assertTrue(message.contains("vanillabp.adapters.c8.cluster-id"));
        Assertions.assertTrue(message.contains("vanillabp.adapters.c8.client-secret"));
        Assertions.assertTrue(message.contains("vanillabp.adapters.c8.deployment-failure"));
      });

  @Test
  public void inconsistentConfigurationFailsTheBoot() {
    // never runs, because the start fails as expected
  }

}
