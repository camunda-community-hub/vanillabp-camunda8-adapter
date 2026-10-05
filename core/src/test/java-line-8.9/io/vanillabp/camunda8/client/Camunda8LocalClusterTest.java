package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * On the 8.9 line Camunda's docker compose publishes REST on the client's default port 8080,
 * so the warning about the client's default REST address says nothing about another port.
 * The 8.8 variant of this test asserts the sentence this one excludes.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8LocalClusterTest {

  @Test
  @DisplayName("Without a REST address the warning says nothing about another port")
  public void withoutARestAddressTheWarningSaysNothingAboutAnotherPort() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setJobLease(Camunda8AdapterConfiguration.JobLease.DO_NOT_USE);
    final var warnings = new ArrayList<String>();

    Camunda8StartupValidation.validateAtStartup(
        "c8", configuration, true, false, Duration.ofDays(7), warnings::add, line -> {
        });

    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.getFirst().contains("'http://0.0.0.0:8080'"), warnings.getFirst());
    assertFalse(warnings.getFirst().contains("publishes REST on port 8088"), warnings.getFirst());

  }

}
