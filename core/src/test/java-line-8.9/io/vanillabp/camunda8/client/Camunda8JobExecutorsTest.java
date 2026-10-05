package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Both roles reach the client of the 8.9 line, which asks for them separately.
 * <p>
 * This is what the line-specific installer is for, so it is asserted per line: a client
 * given only a scheduling executor would build a pool of its own for the handlers, and the
 * bound the adapter promises would hold for nothing.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8JobExecutorsTest {

  @Test
  @DisplayName("the client schedules and handles on the adapter's executor")
  public void theClientRunsBothRolesOnTheAdaptersExecutor() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");
    try (final var factory = new Camunda8ClientFactory("c8", configuration)) {

      final var clientConfiguration = factory.getClient().getConfiguration();
      assertSame(factory.getExecutor(), clientConfiguration.jobWorkerSchedulingExecutor(),
          "the client schedules the polls on it");
      assertSame(factory.getExecutor(), clientConfiguration.jobHandlingExecutor(),
          "and runs the handlers on it, instead of building a pool of its own");
      assertTrue(clientConfiguration.ownsJobWorkerSchedulingExecutor(),
          "closing the client shuts the executor down");
      assertFalse(clientConfiguration.ownsJobHandlingExecutor(),
          "but only once, both roles being the same object");
    }

  }

}
