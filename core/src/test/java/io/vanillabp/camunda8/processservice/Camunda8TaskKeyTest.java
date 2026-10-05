package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a task id which is not a Camunda 8 task key is told, and that saying it does not
 * change how the failure is classified. VanillaBP 1 could hand out hexadecimal task ids
 * and applications stored them, so this is the message an upgrade runs into.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8TaskKeyTest {

  /**
   * The parse site is private, and every command of the process service goes through it.
   * Reaching it through a command would need a client; reaching it by reflection would
   * test the reflection. So the behaviour is asserted where it is observable: the
   * exception a caller gets, produced by the same code path.
   */
  private static IllegalArgumentException parse(
      final String taskId) {

    return assertThrows(
        IllegalArgumentException.class,
        () -> Camunda8ProcessService.taskKeyOf(taskId));

  }

  @Test
  @DisplayName("A hexadecimal task id names the version 1 setting which produced it")
  public void hexadecimalTaskIdNamesTheSetting() {

    final var failure = parse("2251799813685nn".replace("nn", "ff"));

    assertTrue(failure.getMessage().contains("HEXADECIMAL"), failure::getMessage);
    assertTrue(failure.getMessage().contains("task-id-as-hex-string"), failure::getMessage);
    assertTrue(failure.getMessage().contains("converted to decimal"), failure::getMessage);

  }

  @Test
  @DisplayName("A task id which is no number at all does not blame the setting")
  public void anArbitraryTaskIdDoesNotBlameTheSetting() {

    final var failure = parse("not-a-key");

    assertTrue(failure.getMessage().contains("not a Camunda 8 task key"), failure::getMessage);
    assertTrue(failure.getMessage().contains("NOT retried"), failure::getMessage);
    assertTrue(!failure.getMessage().contains("task-id-as-hex-string"), failure::getMessage);

  }

  @Test
  @DisplayName("The failure stays PERMANENT, so the outbox entry is still blocked after one attempt")
  public void theFailureStaysPermanent() {

    assertTrue(
        Camunda8Errors.permanentFailure(parse("beef")),
        "the NumberFormatException travels as the cause, which is what the classification walks");

  }

  @Test
  @DisplayName("A decimal task key parses, which is the whole point")
  public void aDecimalTaskKeyParses() {

    Assertions.assertEquals(2251799813685248L,
        Camunda8ProcessService.taskKeyOf("2251799813685248"));

  }

}
