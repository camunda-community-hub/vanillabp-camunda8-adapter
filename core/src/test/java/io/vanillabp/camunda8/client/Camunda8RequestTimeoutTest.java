package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The deadline every request of an adapter instance gets, and what the boot says about a
 * value which cannot carry one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8RequestTimeoutTest {

  private final List<String> warnings = new ArrayList<>();

  private Camunda8AdapterConfiguration configuration(
      final Duration requestTimeout) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRequestTimeout(requestTimeout);
    return configuration;

  }

  @Test
  @DisplayName("Nothing configured means the client's own ten seconds, and nothing is said about it")
  public void theDefaultIsTheClients() {

    assertEquals(Duration.ofSeconds(10), new Camunda8AdapterConfiguration().resolvedRequestTimeout());

    new Camunda8AdapterConfiguration().validateRequestTimeout("c8", warnings::add);
    configuration(Duration.ofSeconds(30)).validateRequestTimeout("c8", warnings::add);

    assertTrue(warnings.isEmpty(), warnings.toString());

  }

  @Test
  @DisplayName("A timeout which is not positive fails the boot naming the property")
  public void aTimeoutWithoutTimeFailsTheBoot() {

    for (final var impossible : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
      final var e = assertThrows(
          IllegalStateException.class,
          () -> configuration(impossible).validateRequestTimeout("c8", warnings::add));
      final var message = e.getMessage();
      assertTrue(message.contains("vanillabp.adapters.c8.request-timeout"), message);
      assertTrue(message.contains("PT10S"), "names the default: "
          + message);
    }

  }

  @Test
  @DisplayName("A timeout below a second is a guiding warning, not a boot failure")
  public void aTimeoutTooShortForACommandIsReported() {

    configuration(Duration.ofMillis(200)).validateRequestTimeout("c8", warnings::add);

    assertEquals(1, warnings.size(), warnings.toString());
    final var warning = warnings.get(0);
    assertTrue(warning.contains("vanillabp.adapters.c8.request-timeout"), warning);
    assertTrue(
        warning.contains("vanillabp.adapters.c8.poll-interval"),
        "names what a worker does instead of waiting at the cluster: "
            + warning);
    assertTrue(warning.contains("PT10S"), "and the default to go back to: "
        + warning);

  }

}
