package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How long a reader of this cluster may treat "not there" as "not there yet".
 * <p>
 * The number belongs to the cluster and is read off its configuration, by the adapter and by
 * an extension alike. An operator who raises it for a slow exporter raises it once, and a
 * reader which brought a window of its own keeps dropping what the adapter now waits for.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8VisibilityWindowTest {

  @Test
  @DisplayName("A cluster nobody configured a window for answers the default")
  public void anUnconfiguredClusterAnswersTheDefault() {

    assertEquals(
        Camunda8AdapterConfiguration.DEFAULT_WORKFLOW_VISIBILITY_TIMEOUT,
        new Camunda8AdapterConfiguration().workflowVisibilityWindow(),
        "ten seconds, generous for a healthy exporter and short enough to stay in the caller's transaction");

  }

  @Test
  @DisplayName("A configured window is what everybody reading this cluster gets")
  public void aConfiguredWindowIsWhatIsAnswered() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setWorkflowVisibilityTimeout(Duration.ofSeconds(45));

    assertEquals(
        Duration.ofSeconds(45),
        configuration.workflowVisibilityWindow(),
        "raised for a slow exporter once, and the adapter and an extension read the same value");

  }

  @Test
  @DisplayName("A window of zero is answered as it stands, which switches the waiting off")
  public void aZeroWindowSwitchesTheWaitingOff() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setWorkflowVisibilityTimeout(Duration.ZERO);

    assertEquals(
        Duration.ZERO,
        configuration.workflowVisibilityWindow(),
        "zero is a decision and not a missing value, so it is not replaced by the default");

  }

}
