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
 * The pool also decides how long a SHUTDOWN of those workers takes, which is the second
 * half of this class. Closing a worker does not cancel the activation request it has in
 * flight, so a drain cannot end before the cluster answers them, and the requests of the
 * workers which found no connection are queued in the client rather than parked at the
 * cluster: such a request goes out when a connection frees and then waits a whole
 * <code>request-timeout</code> of its own. The floor of a drain therefore grows in rounds
 * of the pool, and a grace period sized for one round gives up in the middle of the second
 * one.
 * <p>
 * The adapter says this and changes nothing. How many connections an application opens
 * against its cluster is a decision about its own resources, and raising that number behind
 * its back would take the decision away. See decision 50 in the repository's DECISIONS.md.
 */
public final class Camunda8WorkerConnections {

  /**
   * What a drain needs beyond the rounds of request timeouts it has to sit out: the poll of
   * {@code Camunda8Drain} looking again, the client answering the last request and every
   * worker reporting itself closed.
   * <p>
   * Two seconds, which is what the one run that ran OUT of grace needed beyond its rounds.
   * See {@link #aDrainFloor} for the numbers.
   */
  static final Duration UNTIL_THE_LAST_WORKER_REPORTS_ITSELF_CLOSED = Duration.ofSeconds(2);

  private Camunda8WorkerConnections() {
  }

  /**
   * How many rounds of the connection pool the workers of one client fill.
   * <p>
   * The first round of workers holds a connection each and has its activation request parked
   * at the cluster. Every further round waits inside the client for a connection to free,
   * which happens one request timeout later, and its request then parks for a request
   * timeout of its own.
   *
   * @param workers How many workers are open
   * @param maxHttpConnections How many HTTP connections the client keeps
   * @return The number of rounds, at least one
   */
  static int roundsOfThePool(
      final int workers,
      final int maxHttpConnections) {

    if ((workers <= 0) || (maxHttpConnections <= 0)) {
      return 1;
    }
    return Math.max(1, ((workers + maxHttpConnections) - 1) / maxHttpConnections);

  }

  /**
   * The shortest time a shutdown of these workers can take, which is what
   * <code>shutdown-grace</code> has to carry.
   * <p>
   * Measured on 2026-09-28 with {@code Camunda8WhatADrainWaitsForIT} against a cluster of the
   * current GA line, a client pool of 30 and a request timeout of {@code PT10S}: 15 workers
   * were released after 6272 ms and 30 workers after 5566 ms, which is one round; 60 workers,
   * two rounds, after 15424 ms; 90 workers, three rounds, after 25541 ms. So a round costs a
   * whole request timeout and where inside the last round a shutdown lands depends on how far
   * that round had got when the workers were closed.
   * <p>
   * The two seconds on top are the one reading which ran OUT: on 2026-09-27 an application
   * with 115 workers on a pool of 100, which is two rounds, gave up after its grace of 20045
   * ms with its workers still holding a request, and the shutdown of the same 115 workers was
   * measured at 22038 ms elsewhere. So the rounds are the floor and the release of the last
   * worker can fall just past them.
   *
   * @param workers How many workers are open
   * @param maxHttpConnections How many HTTP connections the client keeps
   * @param requestTimeout How long one activation request waits at the cluster
   * @return The floor of the drain
   */
  public static Duration aDrainFloor(
      final int workers,
      final int maxHttpConnections,
      final Duration requestTimeout) {

    return requestTimeout
        .multipliedBy(roundsOfThePool(workers, maxHttpConnections))
        .plus(UNTIL_THE_LAST_WORKER_REPORTS_ITSELF_CLOSED);

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

  /**
   * Says whether the shutdown grace of an adapter id can carry the drain of the workers it
   * has open, and what to do about it.
   * <p>
   * This is the half of the pool which nobody was told about. The check of
   * {@link #moreWorkersThanConnections} is about the jobs a running application waits for,
   * and the grace is checked against the request timeout alone
   * ({@code Camunda8AdapterConfiguration#validateShutdownGrace}), which is the floor of a
   * client whose workers fit its pool once. An application above the pool has a higher floor
   * and nothing said so, so a restart ended in the middle of the drain and left a parked
   * request behind - which is what the whole wait exists to avoid.
   * <p>
   * A grace below one request timeout is not reported here. It cannot drain for a reason
   * which has nothing to do with the number of workers, and the startup validation of the
   * grace says so in its own words; two messages about one value would leave the reader
   * choosing between them.
   *
   * @param workers How many workers this adapter id has open
   * @param maxHttpConnections How many HTTP connections its client keeps, as the CLIENT
   *          resolved it
   * @param requestTimeout How long one activation request of that client waits at the cluster
   * @param grace The shutdown grace of this adapter id, as the adapter resolved it, so the
   *          number is the configured one or the default
   * @param platformShutdownBudget How long the runtime around the application grants a
   *          shutdown ({@code Camunda8AdapterConfiguration#PLATFORM_SHUTDOWN_BUDGET}), which
   *          decides whether raising the grace is a way out at all
   * @return The message, or <code>null</code> where the grace carries the drain
   */
  public static String aGraceTooShortForTheDrain(
      final int workers,
      final int maxHttpConnections,
      final Duration requestTimeout,
      final Duration grace,
      final Duration platformShutdownBudget) {

    if (grace.isZero() || grace.isNegative()) {
      return null;
    }
    if (grace.compareTo(requestTimeout) < 0) {
      return null;
    }
    final var floor = aDrainFloor(workers, maxHttpConnections, requestTimeout);
    if (grace.compareTo(floor) >= 0) {
      return null;
    }
    final var rounds = roundsOfThePool(workers, maxHttpConnections);
    final var queued = Math.max(0, workers - maxHttpConnections);
    return """
        This adapter has %d job workers open, its Camunda client keeps at most %d HTTP connections \
        and 'vanillabp.adapters.<adapter id>.shutdown-grace' is %s. A shutdown closes the workers \
        and then waits for the cluster to answer the activation requests they parked, because \
        closing a worker does not cancel the request it has in flight. %d of these workers found no \
        connection, so their request is queued inside the client: it goes out once a connection \
        frees and then waits a whole 'vanillabp.adapters.<adapter id>.request-timeout' of its own, \
        which is %s here. The workers are %d rounds of the pool, so this shutdown cannot end before \
        %s and the grace above gives up in the middle of it. A job created in the window such a \
        shutdown leaves open is activated into a request nobody answers and is served only once \
        'vanillabp.adapters.<adapter id>.job-timeout' expired. %s"""
        .formatted(
            workers,
            maxHttpConnections,
            grace,
            queued,
            requestTimeout,
            rounds,
            floor,
            wayOut(floor, platformShutdownBudget, workers));

  }

  /**
   * What to do about a grace which cannot carry the drain. There are two ways out and which
   * one to put first is decided by the runtime: a grace has to stay under the budget the
   * runtime grants a shutdown, so where the floor has grown past that budget, raising the
   * grace is no longer a way out and only the pool is.
   *
   * @param floor What the drain needs
   * @param platformShutdownBudget What the runtime grants a shutdown
   * @param workers How many workers are open
   * @return The closing sentences of the message
   */
  private static String wayOut(
      final Duration floor,
      final Duration platformShutdownBudget,
      final int workers) {

    if (floor.compareTo(platformShutdownBudget) < 0) {
      return """
          Raise the grace to %s or beyond, and raise the shutdown budget of whatever runs the \
          application with it ('spring.lifecycle.timeout-per-shutdown-phase', Kubernetes' \
          'terminationGracePeriodSeconds'). Or raise \
          'vanillabp.adapters.<adapter id>.max-http-connections' above the number of workers, %d for \
          example, and the workers are one round of the pool again."""
          .formatted(floor, aPoolWhichFits(workers));
    }
    return """
        Raising the grace is not the way out here: %s reaches past the %s both Spring Boot and \
        Kubernetes grant a shutdown by default, so the application would be killed while the drain \
        is still running. Raise \
        'vanillabp.adapters.<adapter id>.max-http-connections' above the number of workers, %d for \
        example. The workers are then one round of the pool, and a drain of one round is what the \
        grace this adapter already has was sized for."""
        .formatted(floor, platformShutdownBudget, aPoolWhichFits(workers));

  }

}
