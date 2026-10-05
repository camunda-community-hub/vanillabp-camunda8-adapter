package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A <code>&#64;WorkflowTask</code> method matching no task of any BPMN process of its
 * workflow module ends the boot, naming the method and the fix.
 * <p>
 * The check belongs to the platform's core - this adapter used to call it itself, and
 * Camunda 7 used to forget it. What this test adds is the proof that it still fires for
 * Camunda 8 now that nobody calls it here: the model reaches a REAL cluster, so the
 * deployment really finishes, which is the moment the core is waiting for.
 * <p>
 * Which is also why the assertions name the orphan method rather than just any refusal.
 * The adapter has a second reason to end a boot against a cluster it cannot search, and
 * that one fires BEFORE the deployment; a test which only asked whether the boot failed
 * would pass on a cluster that never saw the model at all.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8OrphanMethodIT extends TestOnTheSharedCluster {

  @Test
  @DisplayName("A method matching no task ends the boot, naming the method and the fix")
  public void anOrphanMethodEndsTheBoot() {

    final var failure = assertThrows(
        RuntimeException.class,
        () -> new SpringApplicationBuilder(DockerTestApplication.class)
            .run(
                "--spring.config.name=camunda8-it",
                "--spring.profiles.active=orphan-method",
                "--vanillabp.adapters.c8.rest-address="
                    + restAddress(),
                "--vanillabp.adapters.c8.grpc-address="
                    + grpcAddress(),
                "--vanillabp.workflow-modules.test-app.adapters.c8.resources-location=classpath*:orphan-method")
            .close());

    final var message = rootMessage(failure);
    assertTrue(
        message.contains("orphanTypo"),
        "this is the orphan method's refusal and not the adapter's requirement of a searchable cluster: "
            + message);
    assertTrue(message.contains("activityNobodyModelled"), message);
    assertTrue(message.contains("fix the annotation"), message);

  }

  private static String rootMessage(
      final Throwable throwable) {

    var cause = throwable;
    while ((cause.getCause() != null) && (cause.getCause() != cause)) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());

  }

}
