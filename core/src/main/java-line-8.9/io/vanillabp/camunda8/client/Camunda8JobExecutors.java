package io.vanillabp.camunda8.client;

import io.camunda.client.CamundaClientBuilder;

/**
 * Hands the client the executor of this adapter instance. This is the 8.9 variant.
 * <p>
 * Since 8.9 the client asks for the two executors separately, and its
 * {@code jobWorkerExecutor} sets only the scheduling one - a client configured through
 * that method alone would run the handlers on its own pool, which is one thread unless
 * something says otherwise. {@link Camunda8Executor} serves both roles, so it is
 * installed twice: as the scheduling executor, which the client owns and shuts down, and
 * as the job-handling executor, which it must not shut down a second time.
 */
final class Camunda8JobExecutors {

  private Camunda8JobExecutors() {
  }

  static void install(
      final CamundaClientBuilder builder,
      final Camunda8Executor executor) {

    builder.jobWorkerSchedulingExecutor(executor, true);
    builder.jobHandlingExecutor(executor, false);

  }

}
