package io.vanillabp.camunda8.springboot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The message time-to-live names no adapter, so zero means "do not wait" to the Camunda 7
 * family and "drop the message" to this one. This adapter refuses zero only in the workflow
 * modules it serves. The bean registrar hands it exactly that list.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MessageTimeToLiveOfTheServedModulesTest {

  private static MigrationAdapterProperties modules(
      final List<String> adaptersOfTheZeroModule) {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of(
        "c7", AdapterConfigProperties.ofType("camunda7"),
        "c8", AdapterConfigProperties.ofType("camunda8")));
    properties.setWorkflowModules(Map.of(
        "legacy", WorkflowModuleAdapterProperties
            .builder()
            .workflowModuleId("legacy")
            .prioritizedAdapters(adaptersOfTheZeroModule)
            .messageTimeToLive(Duration.ZERO)
            .build(),
        "payments", WorkflowModuleAdapterProperties
            .builder()
            .workflowModuleId("payments")
            .prioritizedAdapters(List.of("c8"))
            .messageTimeToLive(Duration.ofMinutes(10))
            .build()));
    return properties;

  }

  @Test
  @DisplayName("Zero in a module only the Camunda 7 adapter serves does not stop the boot")
  public void zeroInAModuleOfAnotherAdapterBoots() {

    final var properties = modules(List.of("c7"));

    assertDoesNotThrow(() -> Camunda8AdapterConfiguration
        .refuseNonPositiveMessageTimesToLive("c8", properties.messageTimesToLiveWrittenFor("c8")));

  }

  @Test
  @DisplayName("Zero in a module which this adapter serves, also next to another one, ends the boot")
  public void zeroInAServedModuleEndsTheBoot() {

    for (final var adapters : List.of(List.of("c8"), List.of("c7", "c8"))) {
      final var properties = modules(adapters);

      final var message = assertThrows(
          IllegalStateException.class,
          () -> Camunda8AdapterConfiguration
              .refuseNonPositiveMessageTimesToLive("c8", properties.messageTimesToLiveWrittenFor("c8")))
          .getMessage();

      assertTrue(message.contains("vanillabp.workflow-modules.legacy.message-time-to-live: PT0S"), message);
    }

  }

}
