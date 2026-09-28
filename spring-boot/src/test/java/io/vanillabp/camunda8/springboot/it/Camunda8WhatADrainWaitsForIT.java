package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.worker.JobWorker;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How long a shutdown has to wait for the workers of one client to be released, against the
 * number of workers and the size of the connection pool.
 * <p>
 * The drain of this adapter waits until every closed worker reports itself closed, because an
 * activation request which is still parked at the cluster when the client goes down keeps the
 * first job created afterwards until its lock expires. Closing a worker does not cancel the
 * request it has in flight, so the wait cannot be shorter than one {@code request-timeout}.
 * That much was known. What was not known is what happens when the workers outnumber the
 * connections: the surplus workers have their request queued in the client and not yet at the
 * cluster, the queued request goes out when a connection frees, and it then parks for a whole
 * request timeout of its own. So the floor grows in rounds of the pool, and a grace which
 * carries one round gives up halfway through the second.
 * <p>
 * <b>How it is measured.</b> A raw client with a small pool, job types nothing ever produces,
 * and one case per number of workers: half a pool, a full pool, two pools and three pools. The
 * workers are given long enough for every round to have gone out once, then all of them are
 * closed in one go and the clock runs until the last one reports {@code isClosed()}. That is
 * exactly what {@code Camunda8Drain#awaitQuiet} waits for, without an application around it.
 * <p>
 * The pool is small on purpose. What decides is the RATIO of workers to connections, and a
 * pool of 100 would need three hundred workers for the third round and a quarter of an hour
 * of request timeouts to measure them.
 * <p>
 * Each case is read once. The reading is a duration in the seconds, the rounds are ten seconds
 * apart, and a second run of the same case moves it by the phase the close happened to fall
 * into rather than by anything a repetition would average out.
 * <p>
 * The numbers this produced are in the README, section "How many workers an application opens,
 * and what one costs", with the machine they were taken on.
 * <p>
 * It asserts one thing, which is the thing the startup check rests on: a client whose workers
 * fill several rounds of its pool needs more than one request timeout to be released. A
 * measurement which fails a build because a machine was slow stops being read, so the rest is
 * reported and asserted nowhere.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8WhatADrainWaitsForIT extends TestOnTheSharedCluster {

  /**
   * The pool every case of this class gives its client. Small, so three rounds of it are
   * ninety workers rather than three hundred, and still far above the one or two connections
   * the client uses for an ordinary command.
   */
  private static final int MAX_HTTP_CONNECTIONS = 30;

  /**
   * How long a worker's activation request waits at the cluster. The client's own default,
   * and the unit every number below is read in.
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /**
   * How many workers each case opens, as a multiple of the pool: half of it, all of it, twice
   * and three times. Half a pool is the case where nothing queues, and the three above it are
   * the rounds.
   */
  private static final double[] CASES = {
      0.5, 1.0, 2.0, 3.0
  };

  @Test
  @DisplayName("How long the workers of a client take to be released, per round of its pool")
  public void whatADrainWaitsFor(
      final TestReporter reporter) throws Exception {

    final var measured = new ArrayList<String>();
    final var releasedAfter = new ArrayList<Duration>();

    for (final var multipleOfThePool : CASES) {
      final var workers = (int) Math.round(multipleOfThePool * MAX_HTTP_CONNECTIONS);
      final var rounds = roundsOfThePool(workers);
      releasedAfter.add(howLongTheWorkersTakeToBeReleased(workers, rounds, measured));
    }

    measured
        .add("conditions: cluster %s, client pool %d, request timeout %s, %d processors"
            .formatted(
                ClusterUnderTest.image(),
                Integer.valueOf(MAX_HTTP_CONNECTIONS),
                REQUEST_TIMEOUT,
                Integer.valueOf(Runtime.getRuntime().availableProcessors())));
    report(reporter, measured);

    // the property the startup check rests on: workers which fill more than one round of the
    // pool cannot be released within one request timeout, so a grace sized for one round is
    // too short for them
    final var threeRounds = releasedAfter.getLast();
    assertTrue(
        threeRounds.compareTo(REQUEST_TIMEOUT) > 0,
        "Workers which fill three rounds of the connection pool are supposed to need more than one "
            + "request timeout to be released: the requests of the second and the third round are queued "
            + "in the client, they go out when a connection frees, and closing a worker does not cancel "
            + "them. That is why this adapter holds the shutdown grace against the number of workers and "
            + "not against the request timeout alone. This run released them after "
            + threeRounds
            + ", which is within the "
            + REQUEST_TIMEOUT
            + " one round costs, so either the client stopped queueing such a request or it now cancels "
            + "it on close - both of which would make the startup check wrong. What was measured: "
            + String.join(" | ", measured));

  }

  /**
   * How many rounds of the pool the workers of a client fill, which is the model the startup
   * check uses: the first round has a connection, every further one waits for one to free.
   *
   * @param workers How many workers are open
   * @return The number of rounds, at least one
   */
  private static int roundsOfThePool(
      final int workers) {

    return Math.max(1, ((workers + MAX_HTTP_CONNECTIONS) - 1) / MAX_HTTP_CONNECTIONS);

  }

  /**
   * Opens one case, lets it settle, closes every worker at once and times how long the last
   * one takes to report itself closed.
   *
   * @param workers How many workers to open
   * @param rounds How many rounds of the pool those workers fill
   * @param measured Where the line of this case goes
   * @return How long the workers took to be released
   */
  private Duration howLongTheWorkersTakeToBeReleased(
      final int workers,
      final int rounds,
      final List<String> measured) throws Exception {

    final var open = new ArrayList<JobWorker>();
    try (final var client = client()) {

      // the client has talked to the cluster once before any worker is opened, so the
      // connection an ordinary command uses is not one the workers are waiting for
      client.newTopologyRequest().send().join();

      for (var i = 0; i < workers; ++i) {
        open.add(aWorkerNothingEverFeeds(client, i));
      }
      // every round of requests has to have gone out at least once, or the case measures
      // workers which never had a connection rather than workers which have one
      TimeUnit.MILLISECONDS
          .sleep((rounds * REQUEST_TIMEOUT.toMillis()) + Duration.ofSeconds(5).toMillis());

      final var closedAt = System.nanoTime();
      open.forEach(JobWorker::close);
      while (!open.stream().allMatch(JobWorker::isClosed)) {
        TimeUnit.MILLISECONDS.sleep(50);
      }
      final var released = Duration.ofNanos(System.nanoTime() - closedAt);

      measured
          .add("%d workers on a pool of %d (%d round(s) of it): released after %d ms, which is %.2f request timeouts"
              .formatted(
                  Integer.valueOf(workers),
                  Integer.valueOf(MAX_HTTP_CONNECTIONS),
                  Integer.valueOf(rounds),
                  Long.valueOf(released.toMillis()),
                  Double.valueOf((double) released.toMillis() / REQUEST_TIMEOUT.toMillis())));
      return released;

    } finally {
      open.forEach(JobWorker::close);
    }

  }

  private JobWorker aWorkerNothingEverFeeds(
      final CamundaClient client,
      final int number) {

    return client
        .newWorker()
        .jobType("whatADrainWaitsFor-%d".formatted(Integer.valueOf(number)))
        // nothing ever produces these job types, so no handler of this class ever runs and
        // what is timed is a worker which waits
        .handler((
            jobClient,
            job) -> {
        })
        .timeout(Duration.ofMinutes(1))
        .requestTimeout(REQUEST_TIMEOUT)
        .name("what-a-drain-waits-for-%d".formatted(Integer.valueOf(number)))
        .open();

  }

  private CamundaClient client() {

    return CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(restAddress()))
        .grpcAddress(URI.create(grpcAddress()))
        .maxHttpConnections(MAX_HTTP_CONNECTIONS)
        .defaultRequestTimeout(REQUEST_TIMEOUT)
        .build();

  }

  /**
   * Puts the measurement where it can be read after a build: into the test report of the
   * runner respectively an IDE, and into a file of its own, because the console output of a
   * test which passes is suppressed.
   *
   * @param reporter The JUnit reporter
   * @param measured What was read, one line per case
   */
  private static void report(
      final TestReporter reporter,
      final List<String> measured) {

    final var text = String.join(System.lineSeparator(), measured);
    reporter.publishEntry("what-a-drain-waits-for", text);
    try {
      Files.writeString(
          Path.of("target", "what-a-drain-waits-for.txt"),
          text + System.lineSeparator());
    } catch (final IOException e) {
      throw new UncheckedIOException("Cannot write down what was measured", e);
    }

  }

}
