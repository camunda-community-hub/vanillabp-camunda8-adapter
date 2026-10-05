package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The execution model of a Camunda 8 adapter instance: what
 * <code>worker-threads</code> resolves to, and what an unusable value says.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ExecutionModelTest {

  @Test
  @DisplayName("nothing configured means four platform threads")
  public void defaultIsFourPlatformThreads() {

    final var model = Camunda8ExecutionModel.resolve("c8", null, null);

    assertFalse(model.virtual(), "the default does not use virtual threads");
    assertEquals(4, model.slots());

  }

  @Test
  @DisplayName("a number configures platform threads")
  public void numberConfiguresPlatformThreads() {

    final var model = Camunda8ExecutionModel.resolve("c8", "12", null);

    assertFalse(model.virtual());
    assertEquals(12, model.slots());

  }

  @Test
  @DisplayName("'virtual' takes the bound the platform mode would have used")
  public void virtualDefaultsToThePlatformBound() {

    final var model = Camunda8ExecutionModel.resolve("c8", "virtual", null);

    assertTrue(model.virtual());
    assertEquals(Camunda8ExecutionModel.DEFAULT_SLOTS, model.slots(),
        "switching the mode changes how threads are made, not how much runs at once");

  }

  @Test
  @DisplayName("'virtual' with an own bound")
  public void virtualWithConfiguredBound() {

    final var model = Camunda8ExecutionModel.resolve("c8", "VIRTUAL", 16);

    assertTrue(model.virtual());
    assertEquals(16, model.slots());
    assertTrue(model.describe().contains("16"), "the description names the bound: "
        + model.describe());

  }

  @Test
  @DisplayName("zero threads fails naming the property and why one is not enough either")
  public void zeroThreadsFailsGuiding() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> Camunda8ExecutionModel.resolve("c8", "0", null));

    assertTrue(exception.getMessage().contains("vanillabp.adapters.c8.worker-threads"),
        "message names the property, but was: "
            + exception.getMessage());
    assertTrue(exception.getMessage().contains("connection pool"),
        "message says what to size against, but was: "
            + exception.getMessage());

  }

  @Test
  @DisplayName("a negative number fails the same way")
  public void negativeThreadsFailsGuiding() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> Camunda8ExecutionModel.resolve("c8", "-4", null));

    assertTrue(exception.getMessage().contains("vanillabp.adapters.c8.worker-threads"));

  }

  @Test
  @DisplayName("an unparseable value names both accepted forms")
  public void unparseableValueNamesBothForms() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> Camunda8ExecutionModel.resolve("c8", "many", null));

    assertTrue(exception.getMessage().contains("virtual"),
        "message names the literal, but was: "
            + exception.getMessage());
    assertTrue(exception.getMessage().contains(": 4"),
        "message shows the numeric form, but was: "
            + exception.getMessage());

  }

  @Test
  @DisplayName("a bound without the virtual mode fails rather than being ignored")
  public void boundWithoutVirtualFails() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> Camunda8ExecutionModel.resolve("c8", "4", 8));

    assertTrue(exception.getMessage().contains("vanillabp.adapters.c8.worker-threads-bound"),
        "message names the property, but was: "
            + exception.getMessage());
    assertTrue(exception.getMessage().contains("worker-threads: virtual"),
        "message names the way out, but was: "
            + exception.getMessage());

  }

  @Test
  @DisplayName("a bound below one fails naming the property")
  public void boundBelowOneFails() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> Camunda8ExecutionModel.resolve("c8", "virtual", 0));

    assertTrue(exception.getMessage().contains("vanillabp.adapters.c8.worker-threads-bound"));

  }

  @Test
  @DisplayName("max-jobs-active follows the thread count and is capped at the client's 32")
  public void maxJobsActiveFollowsTheThreadCount() {

    final var configuration = new Camunda8AdapterConfiguration();

    assertEquals(32, configuration.resolvedMaxJobsActive("c8"), "four slots times eight, capped at 32");

    configuration.setWorkerThreads("1");
    assertEquals(8, configuration.resolvedMaxJobsActive("c8"),
        "with one thread the last job of a batch waits for seven handlers, not thirty-one");

    configuration.setWorkerThreads("10");
    assertEquals(32, configuration.resolvedMaxJobsActive("c8"), "the cap holds");

    configuration.setMaxJobsActive(64);
    assertEquals(64, configuration.resolvedMaxJobsActive("c8"), "a configured value wins");

  }

  @Test
  @DisplayName("max-jobs-active below the thread count fails, it would leave threads idle")
  public void maxJobsActiveBelowThreadCountFails() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setWorkerThreads("8");
    configuration.setMaxJobsActive(4);

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> configuration.validateWorkerConfiguration("c8"));

    assertTrue(exception.getMessage().contains("vanillabp.adapters.c8.max-jobs-active"),
        "message names the property, but was: "
            + exception.getMessage());
    assertTrue(exception.getMessage().contains("idle"),
        "message says what goes wrong, but was: "
            + exception.getMessage());

  }

}
