package io.vanillabp.camunda8.client;

import java.time.Duration;

/**
 * What the workers of one adapter id cost the connection pool of its client.
 * <p>
 * The adapter opens one worker per process and per kind, and every one of them holds a REST
 * activation request open while it waits for work. The client keeps a fixed number of HTTP
 * connections for all of that, 100 unless the application says otherwise, the same number in
 * the <code>8.8</code>, <code>8.9</code> and <code>8.10</code> clients. An application with
 * as many workers as the pool has connections leaves nothing for the requests which answer a
 * job, and one with more workers than that does not get them all served: the surplus workers
 * take turns, and whatever one of them waits for arrives a whole <code>request-timeout</code>
 * later.
 * <p>
 * Measured on 2026-09-26 with {@code Camunda8RestartDeliveryIT} against
 * {@code camunda/camunda:8.10.0-rc1}: 115 workers against the client's 100 connections took
 * 10412 ms, the same 115 workers against 256 connections took 215 ms, and 92 workers against
 * the client's 100 took 184 ms. So it is the number of workers against the size of the pool
 * which decides, and not the version of the client.
 * <p>
 * The adapter says this and changes nothing. How many connections an application opens
 * against its cluster is a decision about its own resources, and raising that number behind
 * its back would take the decision away. See decision &lt;pending: 685&gt; in the
 * repository's DECISIONS.md.
 */
public final class Camunda8WorkerConnections {

  private Camunda8WorkerConnections() {
  }

  /**
   * The pool the message suggests: one connection per worker, and as many again for the
   * requests which answer the jobs, rounded up to a number somebody would type.
   *
   * @param workers How many workers are open
   * @return The suggested value of <code>max-http-connections</code>
   */
  static int aPoolWhichFits(
      final int workers) {

    final var wanted = Math.max(2 * workers, 128);
    return ((wanted + 63) / 64) * 64;

  }

  /**
   * Says whether the workers of an adapter id outgrew the connection pool of its client, and
   * what to do about it.
   *
   * @param workers How many workers this adapter id has open
   * @param maxHttpConnections How many HTTP connections its client keeps, as the CLIENT
   *          resolved it, so the number is the configured one or the client's own default
   * @param requestTimeout How long a request of that client waits, which is how late a job
   *          arrives when its worker found no connection
   * @return The message, or <code>null</code> where the pool is big enough
   */
  public static String moreWorkersThanConnections(
      final int workers,
      final int maxHttpConnections,
      final Duration requestTimeout) {

    if (workers < maxHttpConnections) {
      return null;
    }
    return """
        This adapter has %d job workers open and its Camunda client keeps at most %d HTTP \
        connections. Every worker holds one of them while it waits for work, so the workers \
        take the whole pool, and the requests which answer a job wait for a connection as \
        well. What waits arrives up to one request-timeout later, which is %s here, and \
        nothing is written to the log while it happens. Set \
        'vanillabp.adapters.<adapter id>.max-http-connections' above the number of workers, \
        %d for example: one connection per worker, and as many again for everything else \
        this adapter sends."""
        .formatted(
            workers,
            maxHttpConnections,
            requestTimeout,
            aPoolWhichFits(workers));

  }

}
