package io.vanillabp.camunda8.springboot.removedkey;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.camunda8.springboot.refusedstart.RefusedStartTestApplication;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The key <code>fetch-variables</code> was removed in version 2.0. An application which still
 * sets it does not start, and the message names every key which sets it and says what applies
 * instead. The start ends before the adapter talks to a cluster, so this test needs none.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8RemovedFetchVariablesTest {

  private static final String AT_TASK_LEVEL = "vanillabp.workflow-modules.test-app.workflows.RefusedStartProcess.tasks.someTask.adapters.c8.fetch-variables";

  @Test
  @DisplayName("the removed key fetch-variables ends the start, naming every key which sets it")
  public void theRemovedKeyEndsTheStart() {

    final var failure = assertThrows(
        RuntimeException.class,
        () -> new SpringApplicationBuilder(RefusedStartTestApplication.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.config.name=camunda8-refused-start-it",
                "vanillabp.adapters.c8.fetch-variables=all",
                AT_TASK_LEVEL
                    + "=all")
            .run()
            .close());

    final var causes = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      causes
          .append(cause.getMessage())
          .append('\n');
    }
    final var message = causes.toString();
    assertTrue(
        message.contains("Camunda 8 adapter 'c8' is configured with 'fetch-variables', which does not exist any more"),
        "expected the guiding startup failure but got:\n"
            + message);
    assertTrue(message.contains("vanillabp.adapters.c8.fetch-variables"), message);
    assertTrue(message.contains(AT_TASK_LEVEL), message);
    assertTrue(message.contains("workflow aggregate"), message);

  }

}
