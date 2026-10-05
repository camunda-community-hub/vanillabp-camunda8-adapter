package io.vanillabp.camunda8.quarkus.smoke;

import java.util.logging.Level;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Startup-validation boot test on Quarkus: an adapter WITHOUT a cluster address still
 * boots. The {@code StartupEvent} observer forces the validation, which warns that the
 * address of the local cluster of the release line is used, and names the key which changes
 * it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8StartupValidationTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(Aggregate.class)
          .addClass(SampleWorkflowService.class)
          .addClass(TestPhaseTwoOutbox.class)
          .addAsResource("application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .setLogRecordPredicate(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
      .assertLogRecords(records -> {
        final var messages = records
            .stream()
            .map(record -> record.getMessage() == null
                ? ""
                : String.format(record.getMessage(), record.getParameters()))
            .toList();
        Assertions.assertTrue(
            messages
                .stream()
                .anyMatch(
                    message -> message
                        .contains(
                            "Camunda 8 adapter 'c8' has no cluster address, so it connects to the local cluster at '%s'. This address matches Camunda's docker compose for "
                                .formatted(Camunda8AdapterConfiguration.LOCAL_CLUSTER_REST_ADDRESS)) && message
                                    .contains(
                                        "Set 'vanillabp.adapters.c8.rest-address' to connect to another cluster") && message
                                            .contains("vanillabp.adapters.c8.mode")),
            "expected the startup warning naming the local cluster but got: "
                + messages);
        // where the local cluster of the line takes port 8080, the default port of Quarkus,
        // the warning names the key which moves the application. Which lines that are is
        // held by Camunda8LocalClusterTest of each line
        Assertions.assertEquals(
            Camunda8AdapterConfiguration.LOCAL_CLUSTER_REST_ADDRESS.endsWith(":8080"),
            messages
                .stream()
                .anyMatch(message -> message.contains(
                    "so this application needs another port. Set 'quarkus.http.port' to pick one.")),
            "the warning names the port key of Quarkus where the line needs it: "
                + messages);
      });

  @Test
  public void anAdapterWithoutAnAddressBootsAndNamesTheLocalCluster() {
    // the assertion happens on the collected log records after shutdown
  }

}
