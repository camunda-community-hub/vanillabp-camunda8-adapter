package io.vanillabp.camunda8.client;

import io.camunda.client.CamundaClientBuilder;

/**
 * Hands the client the executor of this adapter instance. This is the 8.8 variant.
 * <p>
 * The 8.8 client knows ONE executor and gives it both jobs: it schedules the polls of
 * every worker on it and it runs every handler invocation on it. That is exactly what
 * {@link Camunda8Executor} separates internally, so the one call is enough here. Since
 * 8.9 the client asks for the two executors separately and {@code jobWorkerExecutor}
 * sets only the scheduling one, which is why this class exists once per release line.
 */
final class Camunda8JobExecutors {

  private Camunda8JobExecutors() {
  }

  static void install(
      final CamundaClientBuilder builder,
      final Camunda8Executor executor) {

    // 'true': the client owns the executor and shuts it down when it is closed
    builder.jobWorkerExecutor(executor, true);

  }

}
