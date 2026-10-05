package io.vanillabp.camunda8.quarkus.smoke;

import java.util.logging.Level;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Startup-validation boot test on Quarkus: an adapter WITHOUT a cluster address still
 * boots. The {@code StartupEvent} observer forces the validation, which warns that the
 * client's default address is used, which is the local cluster, and names the key which
 * changes it.
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
                            "Camunda 8 adapter 'c8' has no cluster address, so it connects to the local cluster at 'http://0.0.0.0:8080'") && message
                                .contains(
                                    "Set 'vanillabp.adapters.c8.rest-address' to connect to another cluster") && message
                                        .contains("vanillabp.adapters.c8.mode")),
            "expected the startup warning naming the local cluster but got: "
                + messages);
      });

  @Test
  public void anAdapterWithoutAnAddressBootsAndNamesTheLocalCluster() {
    // the assertion happens on the collected log records after shutdown
  }

}
