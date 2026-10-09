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
 * The key <code>fetch-variables</code> was removed in version 2.0. An application which still
 * sets it does not start, and the message names every key which sets it, here one at the
 * adapter level and one at the task level, and says what applies instead.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8RemovedFetchVariablesTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(Aggregate.class)
          .addClass(SampleWorkflowService.class)
          .addClass(TestPhaseTwoOutbox.class)
          .addAsResource("application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("vanillabp.adapters.c8.fetch-variables", "all")
      .overrideConfigKey(
          "vanillabp.workflow-modules.test-app.workflows.TaskProcess.tasks.happyTask.adapters.c8.fetch-variables",
          "all")
      .assertException(throwable -> {
        final var causes = new StringBuilder();
        for (var cause = throwable; cause != null; cause = cause.getCause()) {
          causes
              .append(cause.getMessage())
              .append('\n');
        }
        final var message = causes.toString();
        Assertions.assertTrue(
            message
                .contains("Camunda 8 adapter 'c8' is configured with 'fetch-variables', which does not exist any more"),
            "expected the guiding startup failure but got:\n"
                + message);
        Assertions.assertTrue(message.contains("vanillabp.adapters.c8.fetch-variables"), message);
        Assertions.assertTrue(
            message.contains(
                "vanillabp.workflow-modules.test-app.workflows.TaskProcess.tasks.happyTask.adapters.c8.fetch-variables"),
            message);
        Assertions.assertTrue(message.contains("workflow aggregate"), message);
      });

  @Test
  public void theRemovedKeyEndsTheStart() {
    // never runs, because the start fails as expected
  }

}
