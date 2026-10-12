package io.vanillabp.camunda8.client;

import io.camunda.client.CamundaClientBuilder;

/**
 * Hands the client the executor of this adapter instance. This is the 8.8 variant.
 * <p>
 * The 8.8 client knows ONE executor and gives it both jobs. It schedules the polls of
 * every worker on it, and it runs every handler invocation on it. {@link Camunda8Executor}
 * keeps exactly these two apart inside, so the one call is enough here. Since 8.9 the
 * client asks for the two executors separately, and {@code jobWorkerExecutor} sets only the
 * scheduling one. That is why this class exists once per release line.
 */
final class Camunda8JobExecutors {

  private Camunda8JobExecutors() {
  }

  static void install(
      final CamundaClientBuilder builder,
      final Camunda8Executor executor) {

    // 'true' means that the client owns the executor and shuts it down when it is closed
    builder.jobWorkerExecutor(executor, true);

  }

}
