package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
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
 * What one job worker costs, in connections, threads and heap.
 * <p>
 * The adapter opens one worker per BPMN process and per kind, so the number grows with the
 * product of both: the Spring Boot test module deploys 35 processes and opens 85 workers on
 * the GA lines and 115 on 8.10, where a cancel listener per process comes on top. Whether
 * that cut holds is a question about a price nobody had measured, and this is the price.
 * <p>
 * <b>How it is measured.</b> Workers are opened in batches against the shared cluster, with
 * the raw client and job types nothing ever produces, so nothing but the worker itself is in
 * the reading. Three readings are taken - before any worker, after the first batch and after
 * the second - and what is reported is the SLOPE between two readings rather than a total:
 * the fixed cost of a client (its executor, its connection pool, the objects it keeps) is in
 * every reading alike and would otherwise be counted as a worker.
 * <p>
 * Each reading is taken once every worker has settled, which is one {@code request-timeout}
 * after the batch was opened plus a margin: a worker costs a connection because it keeps an
 * activation request open, and a worker whose first request has not gone out yet costs
 * nothing yet.
 * <ul>
 * <li><b>Connections</b> are the established TCP sockets of this network namespace whose
 * remote port is the cluster's REST port, read from {@code /proc/net/tcp} and
 * {@code /proc/net/tcp6}. That port belongs to this one container, and no second application
 * talks to it while a class of this module runs.</li>
 * <li><b>Threads</b> are what the JVM reports, which is the honest way round: the client
 * runs every worker on the executor it was handed, so the expected slope is zero and a
 * number above zero would be the news.</li>
 * <li><b>Heap</b> is what is used after a full collection, which is an indication and not an
 * accounting - a JVM under a test runner has other things in it.</li>
 * </ul>
 * The numbers this produced are in the README, section "Connecting to a Camunda 8 cluster",
 * with the machine they were taken on.
 * <p>
 * It asserts almost nothing, on purpose. A measurement which fails a build on a machine
 * being slower than the one it was written on stops being read. What it does hold is the one
 * property the sizing rests on: a worker costs a CONNECTION, so the workers of an
 * application have to fit into its pool.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8WhatAWorkerCostsIT extends TestOnTheSharedCluster {

  /**
   * How many workers each batch opens. Fifty is well above the noise of a single connection
   * and well below the pool below, so neither reading is taken at a limit.
   */
  private static final int WORKERS_PER_BATCH = 50;

  /**
   * The pool this measurement gives its client. Far above the two batches, because a worker
   * which has to wait for a connection is a worker which does not hold one, and the slope
   * would then measure the pool rather than the worker.
   */
  private static final int MAX_HTTP_CONNECTIONS = 300;

  /**
   * How long a worker's activation request waits at the cluster. The client's own default,
   * written down because the settling time below is derived from it.
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /**
   * How long a batch is given to settle before it is read: one {@code request-timeout} for
   * the first round of requests to be answered plus half of one for the next to be out.
   */
  private static final Duration UNTIL_A_BATCH_HOLDS_ITS_CONNECTIONS = Duration.ofSeconds(15);

  @Test
  @DisplayName("What one job worker costs in connections, threads and heap")
  public void whatOneWorkerCosts(
      final TestReporter reporter) throws Exception {

    final var workers = new ArrayList<JobWorker>();
    final var measured = new ArrayList<String>();

    try (final var client = client()) {

      // the client has talked to the cluster once before the baseline is taken, so what it
      // keeps open for an ordinary command is part of the baseline and not of the slope
      client.newTopologyRequest().send().join();

      final var withoutWorkers = readingNow(client, "no worker at all");
      measured.add(withoutWorkers.toString());

      openABatch(client, workers, 1);
      final var afterTheFirstBatch = readingNow(client, WORKERS_PER_BATCH
          + " workers");
      measured.add(afterTheFirstBatch.toString());

      openABatch(client, workers, 2);
      final var afterTheSecondBatch = readingNow(client, (2 * WORKERS_PER_BATCH)
          + " workers");
      measured.add(afterTheSecondBatch.toString());

      measured.add(slope("the first batch", withoutWorkers, afterTheFirstBatch));
      measured.add(slope("the second batch", afterTheFirstBatch, afterTheSecondBatch));
      measured
          .add("conditions: cluster %s, client pool %d, request timeout %s, %d processors, heap max %d MiB"
              .formatted(
                  ClusterUnderTest.image(),
                  Integer.valueOf(MAX_HTTP_CONNECTIONS),
                  REQUEST_TIMEOUT,
                  Integer.valueOf(Runtime.getRuntime().availableProcessors()),
                  Long.valueOf(Runtime.getRuntime().maxMemory() / (1024 * 1024))));

      report(reporter, measured);

      // the one property the sizing rests on, and the only thing this class fails a build
      // over: a worker holds a connection of the client's pool for as long as it is open
      final var connectionsPerWorker = (double) (afterTheSecondBatch.connections() - withoutWorkers
          .connections()) / (2 * WORKERS_PER_BATCH);
      assertTrue(
          connectionsPerWorker > 0.5,
          "A worker is supposed to hold one connection of the client's pool while it waits, which is "
              + "why an application with more workers than connections serves the surplus of them a "
              + "'request-timeout' late and why this adapter warns about that at startup. This run "
              + "measured "
              + connectionsPerWorker
              + " connections per worker, so either the client stopped keeping a request open per "
              + "worker - which would be news about Camunda and would make the startup warning wrong - "
              + "or this reading did not see the sockets. What was measured: "
              + String.join(" | ", measured));

    } finally {
      workers.forEach(JobWorker::close);
    }

  }

  /**
   * One set of readings, taken at a moment when nothing is moving any more.
   *
   * @param what How many workers were open
   * @param connections Established sockets to the cluster's REST port
   * @param threads What the JVM has
   * @param heapBytes What is used after a full collection
   */
  private record Reading(String what, int connections, int threads, long heapBytes) {

    @Override
    public String toString() {

      return "%s: %d connection(s), %d thread(s), %d KiB heap in use"
          .formatted(what, Integer.valueOf(connections), Integer.valueOf(threads), Long.valueOf(heapBytes / 1024));

    }

  }

  /**
   * What the step from one reading to the next cost per worker.
   *
   * @param which Which batch is being reported
   * @param before The reading before the batch
   * @param after The reading after it
   * @return One line, per worker
   */
  private static String slope(
      final String which,
      final Reading before,
      final Reading after) {

    return "%s, per worker: %.2f connection(s), %.2f thread(s), %.1f KiB heap"
        .formatted(
            which,
            Double.valueOf((double) (after.connections() - before.connections()) / WORKERS_PER_BATCH),
            Double.valueOf((double) (after.threads() - before.threads()) / WORKERS_PER_BATCH),
            Double.valueOf((double) (after.heapBytes() - before.heapBytes()) / WORKERS_PER_BATCH / 1024));

  }

  private void openABatch(
      final CamundaClient client,
      final List<JobWorker> workers,
      final int batch) throws InterruptedException {

    for (var i = 0; i < WORKERS_PER_BATCH; ++i) {
      workers
          .add(
              client
                  .newWorker()
                  .jobType("whatAWorkerCosts-%d-%d".formatted(Integer.valueOf(batch), Integer.valueOf(i)))
                  // nothing ever produces these job types, so no handler of this class ever
                  // runs and what is measured is a worker which waits
                  .handler((
                      jobClient,
                      job) -> {
                  })
                  .timeout(Duration.ofMinutes(1))
                  .requestTimeout(REQUEST_TIMEOUT)
                  .name("what-a-worker-costs-%d-%d".formatted(Integer.valueOf(batch), Integer.valueOf(i)))
                  .open());
    }
    TimeUnit.MILLISECONDS.sleep(UNTIL_A_BATCH_HOLDS_ITS_CONNECTIONS.toMillis());

  }

  private Reading readingNow(
      final CamundaClient client,
      final String what) throws InterruptedException {

    // twice, because the first collection makes objects reachable for the second one, and
    // a heap figure taken between the two is neither of the two
    System.gc();
    TimeUnit.MILLISECONDS.sleep(200);
    System.gc();
    TimeUnit.MILLISECONDS.sleep(200);
    return new Reading(
        what, establishedConnectionsToTheCluster(), ManagementFactory.getThreadMXBean()
            .getThreadCount(), ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());

  }

  /**
   * The established TCP sockets of this network namespace whose remote port is the one the
   * cluster answers REST on.
   * <p>
   * Read from {@code /proc}, because neither the client nor the JVM publishes the size of
   * that pool. The port is the one Testcontainers mapped for this cluster, so nothing but
   * this JVM is on the other end of it.
   *
   * @return How many sockets there are
   */
  private static int establishedConnectionsToTheCluster() {

    final var port = URI.create(restAddress()).getPort();
    return countIn("/proc/net/tcp", port) + countIn("/proc/net/tcp6", port);

  }

  /**
   * Counts the ESTABLISHED lines of one of the kernel's socket tables whose remote port is
   * the one asked for. The columns are {@code sl local_address rem_address st ...}, the
   * addresses are {@code hex:hex} and the state {@code 01} is ESTABLISHED.
   */
  private static int countIn(
      final String table,
      final int port) {

    final var remotePort = "%04X".formatted(Integer.valueOf(port));
    try {
      return (int) Files
          .readAllLines(Path.of(table))
          .stream()
          .map(String::trim)
          .map(line -> line.split("\\s+"))
          .filter(columns -> columns.length > 3)
          .filter(columns -> columns[2].endsWith(":"
              + remotePort))
          .filter(columns -> "01".equals(columns[3]))
          .count();
    } catch (final IOException e) {
      throw new UncheckedIOException("Cannot read the socket table '"
          + table
          + "', so the connections a worker holds cannot be counted", e);
    }

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
   * @param measured What was read, one line per reading
   */
  private static void report(
      final TestReporter reporter,
      final List<String> measured) {

    final var text = String.join(System.lineSeparator(), measured);
    reporter.publishEntry("what-a-worker-costs", text);
    try {
      Files.writeString(
          Path.of("target", "what-a-worker-costs.txt"),
          text + System.lineSeparator());
    } catch (final IOException e) {
      throw new UncheckedIOException("Cannot write down what was measured", e);
    }

  }

}
