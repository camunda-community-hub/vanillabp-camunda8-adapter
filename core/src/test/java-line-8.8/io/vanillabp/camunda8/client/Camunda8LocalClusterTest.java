package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * On the 8.8 line the warning about the client's default REST address also says that
 * Camunda's docker compose for 8.8 publishes REST on port 8088.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8LocalClusterTest {

  private static final String DOCKER_COMPOSE = "Camunda's docker compose for 8.8 publishes REST on port 8088, not 8080.";

  private static List<String> warningsOfTheStart(
      final Camunda8AdapterConfiguration configuration) {

    final var warnings = new ArrayList<String>();
    Camunda8StartupValidation.validateAtStartup(
        "c8", configuration, true, false, Duration.ofDays(7), warnings::add, line -> {
        });
    return warnings;

  }

  @Test
  @DisplayName("Without a REST address the warning names the port of Camunda's 8.8 docker compose")
  public void withoutARestAddressTheWarningNamesTheDockerComposePort() {

    final var warnings = warningsOfTheStart(new Camunda8AdapterConfiguration());

    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.getFirst().contains(DOCKER_COMPOSE), warnings.getFirst());
    assertTrue(
        warnings
            .getFirst()
            .contains("If you started your cluster with it, set 'vanillabp.adapters.c8.rest-address' to "
                + "'http://localhost:8088'."),
        warnings.getFirst());

  }

  @Test
  @DisplayName("An adapter talking gRPC hears nothing about the REST port")
  public void anAdapterTalkingGrpcHearsNothingAboutTheRestPort() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setPreferRestOverGrpc(false);

    final var warnings = warningsOfTheStart(configuration);

    assertEquals(1, warnings.size(), warnings.toString());
    assertFalse(warnings.getFirst().contains(DOCKER_COMPOSE), warnings.getFirst());

  }

}
