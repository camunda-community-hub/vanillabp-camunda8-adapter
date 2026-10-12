package io.vanillabp.camunda8.client;

import io.camunda.client.CamundaClientBuilder;

/**
 * Hands the client the executor of this adapter instance. This is the 8.9 variant.
 * <p>
 * Since 8.9 the client asks for the two executors separately. Its {@code jobWorkerExecutor}
 * sets only the scheduling one. A client configured through that method alone would run the
 * handlers on its own pool, which has one thread unless something says otherwise.
 * {@link Camunda8Executor} serves both roles, so it is installed twice. Once as the
 * scheduling executor, which the client owns and shuts down. And once as the job-handling
 * executor, which the client must not shut down a second time.
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
