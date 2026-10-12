package io.vanillabp.camunda8.quarkus.nativeimage;

import java.time.Duration;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

/**
 * The assertion of this module, written as the application's main. The application has to
 * boot and then run a workflow. An exit code says whether it did.
 * <p>
 * Building the image is the smaller half. The deployment of a workflow module parses every
 * BPMN file through the Camunda model API, changes it, writes it back to XML and sends it
 * to the cluster. On this path it reads message bundles, a schema and service providers.
 * A native image only holds them if the adapter's extension names them. Every application
 * runs this path at every boot. So if the binary starts, the extension registers enough.
 * <p>
 * Starting a workflow afterwards is the second half. The start goes through the phase-two
 * outbox and reaches the cluster. The cluster hands the job back to a handler in this
 * binary. The handler's transaction writes what it did into the workflow aggregate. If
 * anything on that way goes wrong, it either throws or leaves the aggregate unwritten. The
 * pipeline looks at the exit code.
 */
@QuarkusMain
public class NativeImageApplication implements QuarkusApplication {

  /**
   * How long the job may take from the start of the workflow to a handler which committed
   * its work. A REST round trip through a freshly booted cluster takes a few seconds. The
   * window is wide, because a busy CI runner is slower than a laptop. It has a limit,
   * because a job which never arrives has to end this run and not let it hang.
   */
  private static final Duration UNTIL_SERVED = Duration.ofMinutes(2);

  private static final Duration BETWEEN_READS = Duration.ofMillis(250);

  @Inject
  NativeImageWorkflowService workflowService;

  @Inject
  NativeImagePersistence persistence;

  @Override
  public int run(
      final String... args) throws Exception {

    final var aggregateId = QuarkusTransaction
        .requiringNew()
        .call(() -> workflowService
            .startWorkflow()
            .getId());

    // the handler's own transaction makes its work visible, and it commits after the
    // handler returns. So this reads the aggregate and does not watch the handler
    final var deadline = System.nanoTime() + UNTIL_SERVED.toNanos();
    String status = null;
    while (System.nanoTime() < deadline) {
      status = QuarkusTransaction
          .requiringNew()
          .call(() -> persistence
              .loadById(aggregateId)
              .getStatus());
      if (NativeImageWorkflowService.SERVED.equals(status)) {
        System.out.println(
            "VanillaBP ran the workflow of aggregate '%d' on Camunda 8 (status '%s')."
                .formatted(aggregateId, status));
        return 0;
      }
      Thread.sleep(BETWEEN_READS.toMillis());
    }

    System.err.println(
        "The job of workflow aggregate '%d' did not reach its handler within %s - the aggregate reads status '%s'!"
            .formatted(aggregateId, UNTIL_SERVED, status));
    return 1;

  }

}
