package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Both roles reach the client of the 8.8 line, where there is only one place to put them.
 * <p>
 * This is what the line-specific installer is for, so it is asserted per line. The 8.8
 * client takes a single {@code ScheduledExecutorService} and uses it for the polls and for
 * the handlers. The adapter's executor keeps the two apart behind that one reference.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8JobExecutorsTest {

  @Test
  @DisplayName("the one executor the 8.8 client knows is the adapter's")
  public void theClientRunsBothRolesOnTheAdaptersExecutor() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:8080");
    try (final var factory = new Camunda8ClientFactory("c8", configuration)) {

      final var clientConfiguration = factory.getClient().getConfiguration();
      assertSame(factory.getExecutor(), clientConfiguration.jobWorkerExecutor(),
          "the client schedules the polls and runs the handlers on it");
      assertTrue(clientConfiguration.ownsJobWorkerExecutor(),
          "closing the client shuts the executor down");
    }

  }

}
