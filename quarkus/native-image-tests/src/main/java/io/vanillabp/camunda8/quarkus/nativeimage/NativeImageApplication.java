package io.vanillabp.camunda8.quarkus.nativeimage;

import java.time.Duration;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;

/**
 * The assertion of this module, written as the application's main: what has to work is
 * booting and then running a workflow, and an exit code says whether it did.
 * <p>
 * Building the image is the smaller half. The deployment of a workflow module parses
 * every BPMN file through the Camunda model API, modifies it, serializes it back and
 * sends it to the cluster - a path which reads message bundles, a schema and service
 * providers a native image does not carry unless the adapter's extension names them.
 * That path runs at every boot of every application, so the binary starting is what
 * proves the extension registers enough.
 * <p>
 * Starting a workflow afterwards adds the second half: the start goes through the
 * phase-two outbox, reaches the cluster, and the cluster hands the job back to a handler
 * in this binary, whose transaction writes what it did into the workflow aggregate.
 * Anything on that way either throws or leaves the aggregate unwritten, and the exit code
 * is what the pipeline looks at.
 */
@QuarkusMain
public class NativeImageApplication implements QuarkusApplication {

  /**
   * How long the job may take from the start of the workflow to a handler which committed
   * its work. A REST round trip through a freshly booted cluster is a matter of seconds;
   * the window is wide because a loaded CI runner is slower than a laptop, and it is
   * bounded because a job which never arrives has to end this run rather than hang it.
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

    // the handler's own transaction is what makes its work visible, and it commits after
    // the handler returns - so this reads the aggregate rather than watching the handler
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
