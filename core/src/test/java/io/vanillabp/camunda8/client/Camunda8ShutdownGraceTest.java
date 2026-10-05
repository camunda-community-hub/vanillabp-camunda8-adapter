package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * The grace period an adapter instance grants its handlers while the
 * application goes down - its default, and what the boot says about a value which cannot
 * do what it promises.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ShutdownGraceTest {

  private final List<String> warnings = new ArrayList<>();

  private Camunda8AdapterConfiguration configuration(
      final Duration grace) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setShutdownGrace(grace);
    return configuration;

  }

  @Test
  @DisplayName("Nothing configured means twenty seconds")
  public void theDefaultIsTwentySeconds() {

    assertEquals(
        Duration.ofSeconds(20),
        new Camunda8AdapterConfiguration().resolvedShutdownGrace(),
        "the default sits below the thirty seconds Spring Boot and Kubernetes grant a shutdown");
    assertEquals(
        Duration.ofSeconds(20),
        Camunda8AdapterConfiguration.DEFAULT_SHUTDOWN_GRACE,
        "and the constant says the same as the ISO notation the messages use");
    assertEquals(
        Duration.ofSeconds(5),
        configuration(Duration.ofSeconds(5)).resolvedShutdownGrace(),
        "a configured value wins");

  }

  @Test
  @DisplayName("A negative grace fails the boot naming the property")
  public void aNegativeGraceFailsTheBoot() {

    final var e = assertThrows(
        IllegalStateException.class,
        () -> configuration(Duration.ofSeconds(-1)).validateShutdownGrace("c8", warnings::add));

    final var message = e.getMessage();
    assertTrue(message.contains("vanillabp.adapters.c8.shutdown-grace"), message);
    assertTrue(message.contains("PT20S"), "names the default: "
        + message);
    assertTrue(message.contains("PT0S"), "and the way to switch the drain off: "
        + message);

  }

  @Test
  @DisplayName("A grace reaching into the runtime's shutdown budget is a guiding warning")
  public void aGraceBeyondTheBudgetWarns() {

    configuration(Duration.ofSeconds(45)).validateShutdownGrace("c8", warnings::add);

    assertEquals(1, warnings.size());
    final var warning = warnings.getFirst();
    assertTrue(warning.contains("vanillabp.adapters.c8.shutdown-grace"), warning);
    assertTrue(
        warning.contains("spring.lifecycle.timeout-per-shutdown-phase"),
        "names what has to be raised with it: "
            + warning);
    assertTrue(
        warning.contains("terminationGracePeriodSeconds"),
        "on both runtimes: "
            + warning);

  }

  @Test
  @DisplayName("A grace shorter than the request timeout is a guiding warning of its own")
  public void aGraceBelowTheRequestTimeoutWarns() {

    configuration(Duration.ofSeconds(5)).validateShutdownGrace("c8", warnings::add);

    assertEquals(1, warnings.size(), warnings::toString);
    final var warning = warnings.getFirst();
    assertTrue(
        warning.contains("vanillabp.adapters.c8.request-timeout"),
        "the window an activation request waits at the cluster is named: "
            + warning);
    assertTrue(
        warning.contains("vanillabp.adapters.c8.job-timeout"),
        "and what the next application pays for a request left behind: "
            + warning);

  }

  @Test
  @DisplayName("A grace below the budget, none at all and zero say nothing")
  public void aUsableGraceIsSilent() {

    configuration(Duration.ofSeconds(29)).validateShutdownGrace("c8", warnings::add);
    configuration(Duration.ZERO).validateShutdownGrace("c8", warnings::add);
    new Camunda8AdapterConfiguration().validateShutdownGrace("c8", warnings::add);

    assertTrue(warnings.isEmpty(), warnings.toString());
    assertNull(new Camunda8AdapterConfiguration().getShutdownGrace(), "nothing is bound by default");

  }

  @Test
  @DisplayName("The startup validation of an adapter instance covers the grace")
  public void theStartupValidationCoversTheGrace() {

    final var configuration = configuration(Duration.ofMinutes(2));
    configuration.setRestAddress("http://localhost:8080");
    // a configured adapter has to say whether it leases, on the line which can lease
    configuration.setJobLease(Camunda8AdapterConfiguration.JobLease.DO_NOT_USE);

    Camunda8StartupValidation.validateAtStartup(
        "c8", configuration, true, false, Duration.ofDays(7), warnings::add, line -> {
        });

    assertEquals(1, warnings.size(), "the grace is checked where every other value is checked");
    assertTrue(warnings.getFirst().contains("shutdown-grace"), warnings.toString());

  }

}
