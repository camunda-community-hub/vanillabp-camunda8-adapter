package io.vanillabp.camunda8.springboot.it;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.worker.JobWorker;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.camunda8.test.ClusterUnderTest;

/**
 * A measurement, not a test of this adapter: how often the gateway activates a job for a long
 * poll which has just ended, and what then becomes of the job.
 * <p>
 * The gateway parks an activation request which found no job, and it answers it empty when
 * the request's own timeout runs out. A job which becomes available while the request is
 * parked starts an activation at the broker. If the timeout fires while that activation is
 * on its way, the gateway can no longer send the job. It logs "Failed to send 1 activated
 * jobs" and gives the job back with a FAIL. On a line whose client leases, that FAIL carries
 * no lease token, the broker refuses it ("Failed to reactivate job"), and the job stays
 * locked until the lock of the lost activation runs out.
 * <p>
 * It runs only when asked for, with {@code -Dcamunda8.measure.long-poll-race=true}, because
 * it takes minutes and asserts nothing. It prints its numbers. The knobs are system
 * properties, see the constants below. What it measured, and what the adapter made of it, is
 * decision 88 in the repository's DECISIONS.md.
 */
@EnabledIfSystemProperty(named = "camunda8.measure.long-poll-race", matches = "true")
public class Camunda8LongPollRaceIT {

  /** Whether the activations ask for a lease. Without a lease the FAIL of the gateway works. */
  private static final boolean LEASE = Boolean.parseBoolean(System.getProperty("race.lease", "true"));

  /** The long poll of an activation, which the client sends as its request timeout. */
  private static final Duration REQUEST_TIMEOUT = Duration.parse(System.getProperty("race.request-timeout", "PT10S"));

  /** How much longer than the long poll the client waits for the HTTP answer. */
  private static final Duration REQUEST_TIMEOUT_OFFSET = Duration
      .parse(System.getProperty("race.request-timeout-offset", "PT1S"));

  /** The lock of an activation. A lost activation holds the job for this long. */
  private static final Duration LOCK = Duration.parse(System.getProperty("race.lock", "PT30S"));

  private static final int ROUNDS = Integer.getInteger("race.rounds", 3);

  private static final int OFFSET_FROM = Integer.getInteger("race.offset-from", -60);

  private static final int OFFSET_TO = Integer.getInteger("race.offset-to", 20);

  private static final int OFFSET_STEP = Integer.getInteger("race.offset-step", 2);

  /** Worker mode: the retry backoff of the failed first attempt. */
  private static final Duration BACKOFF = Duration.parse(System.getProperty("race.backoff", "PT10S"));

  /** Worker mode: the poll interval of the workers. */
  private static final Duration POLL_INTERVAL = Duration.parse(System.getProperty("race.poll-interval", "PT0.1S"));

  /** Worker mode: the longest pause of a handler before it fails the job, in ms. */
  private static final int HANDLER_DELAY_MAX = Integer.getInteger("race.handler-delay-max", 250);

  private static final int WORKER_TYPES = Integer.getInteger("race.worker-types", 20);

  private static final int JOBS_PER_TYPE = Integer.getInteger("race.jobs-per-type", 15);

  private static GenericContainer<?> cluster;

  private static CamundaClient client;

  @BeforeAll
  static void startTheCluster() {

    cluster = ClusterUnderTest.cluster("long-poll-race");
    cluster.start();
    client = CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(ClusterUnderTest.restAddress(cluster)))
        .grpcAddress(URI.create(ClusterUnderTest.grpcAddress(cluster)))
        .defaultRequestTimeout(REQUEST_TIMEOUT)
        .defaultRequestTimeoutOffset(REQUEST_TIMEOUT_OFFSET)
        .defaultJobPollInterval(POLL_INTERVAL)
        // one handler thread per job type of the worker mode, so a pausing handler does not
        // hold up the others and the measured delays are the cluster's
        .numJobWorkerExecutionThreads(Integer.getInteger("race.worker-types", 20))
        .build();
    client
        .newDeployResourceCommand()
        .addProcessModel(
            Bpmn
                .createExecutableProcess("race")
                .startEvent()
                .serviceTask("task", t -> t.zeebeJobTypeExpression("jobType").zeebeJobRetries("3"))
                .endEvent()
                .done(),
            "race.bpmn")
        .send()
        .join();

  }

  @AfterAll
  static void stopTheCluster() {

    if (client != null) {
      client.close();
    }
    if (cluster != null) {
      final var logs = cluster.getLogs();
      System.out.printf(
          "RACE cluster log: 'Failed to send' %d, 'Failed to reactivate' %d, 'lease token' %d%n",
          count(logs, "Failed to send 1 activated jobs"),
          count(logs, "Failed to reactivate"),
          count(logs, "lease token"));
      cluster.stop();
    }

  }

  private static int count(
      final String text,
      final String what) {

    int n = 0;
    int i = 0;
    while ((i = text.indexOf(what, i)) >= 0) {
      n++;
      i += what.length();
    }
    return n;

  }

  /**
   * Hits the end of the long poll on purpose. Each trial has a job type of its own. It sends
   * one activation and creates the job at a chosen offset from the moment the long poll ends.
   * Then it asks a second time, briefly. A job which neither activation got is stuck.
   */
  @Test
  public void boundary() throws Exception {

    final var offsets = new ArrayList<Integer>();
    for (int o = OFFSET_FROM; o <= OFFSET_TO; o += OFFSET_STEP) {
      offsets.add(o);
    }
    final var results = new ConcurrentLinkedQueue<long[]>();
    final var scheduler = Executors.newScheduledThreadPool(4);
    final var trials = Executors.newVirtualThreadPerTaskExecutor();
    try {
      for (int round = 0; round < ROUNDS; round++) {
        final var latch = new CountDownLatch(offsets.size());
        for (int i = 0; i < offsets.size(); i++) {
          final int offset = offsets.get(i);
          final var jobType = "race-%d-%d".formatted(round, i);
          scheduler.schedule(() -> trials.execute(() -> {
            try {
              results.add(trial(jobType, offset, scheduler));
            } catch (final Exception e) {
              System.out.println("RACE trial failed: "
                  + e);
            } finally {
              latch.countDown();
            }
          }), i * 150L, TimeUnit.MILLISECONDS);
        }
        latch.await(5, TimeUnit.MINUTES);
      }
    } finally {
      scheduler.shutdownNow();
      trials.shutdownNow();
    }

    int inPoll = 0;
    int afterPoll = 0;
    int stuck = 0;
    final var stuckAt = new ArrayList<Long>();
    for (final var r : results) {
      switch ((int) r[0]) {
        case 0 -> inPoll++;
        case 1 -> afterPoll++;
        default -> {
          stuck++;
          stuckAt.add(r[1]);
        }
      }
    }
    System.out.printf(
        "RACE boundary lease=%s requestTimeout=%s offset=%s: %d trials, %d in the poll, %d in the next poll, %d stuck; stuck at (ms of job creation minus answer) %s%n",
        LEASE && Camunda8JobLease.supportedByThisLine(), REQUEST_TIMEOUT, REQUEST_TIMEOUT_OFFSET, results.size(),
        inPoll, afterPoll, stuck, stuckAt);
    for (final var r : results) {
      System.out.printf("RACE trial outcome=%d created-minus-answer=%d planned=%d%n", r[0], r[1], r[2]);
    }

  }

  /**
   * One trial of {@link #boundary()}.
   *
   * @return outcome (0 in the poll, 1 in the next poll, 2 stuck), ms between the creation of
   *         the job and the answer of the first poll, planned offset
   */
  private long[] trial(
      final String jobType,
      final int offset,
      final java.util.concurrent.ScheduledExecutorService scheduler) throws Exception {

    final var created = new long[1];
    final var first = activate(jobType, REQUEST_TIMEOUT).send();
    scheduler.schedule(() -> {
      created[0] = System.nanoTime();
      client
          .newCreateInstanceCommand()
          .bpmnProcessId("race")
          .latestVersion()
          .variables(Map.of("jobType", jobType))
          .send();
    }, REQUEST_TIMEOUT.toNanos() + offset * 1_000_000L, TimeUnit.NANOSECONDS);
    final var firstJobs = first.join().getJobs();
    final var answered = System.nanoTime();
    while (created[0] == 0) {
      Thread.sleep(5);
    }
    final long createdMinusAnswer = (created[0] - answered) / 1_000_000L;
    if (!firstJobs.isEmpty()) {
      return new long[]{
          0, createdMinusAnswer, offset
      };
    }
    final var second = activate(jobType, Duration.ofSeconds(3)).send().join().getJobs();
    if (!second.isEmpty()) {
      return new long[]{
          1, createdMinusAnswer, offset
      };
    }
    return new long[]{
        2, createdMinusAnswer, offset
    };

  }

  private io.camunda.client.api.command.FinalCommandStep<io.camunda.client.api.response.ActivateJobsResponse> activate(
      final String jobType,
      final Duration requestTimeout) {

    final var command = client
        .newActivateJobsCommand()
        .jobType(jobType)
        .maxJobsToActivate(1)
        .timeout(LOCK);
    return (LEASE
        ? Camunda8JobLease.leaseTheActivation(command)
        : command).requestTimeout(requestTimeout);

  }

  /**
   * The case the adapter meets: a worker whose handler fails the first attempt with a retry
   * backoff, and the cluster hands the job out again once the backoff is over. A second
   * delivery which comes later than the backoff plus five seconds was held by a lost
   * activation.
   */
  @Test
  public void worker() throws Exception {

    final var delays = new ConcurrentLinkedQueue<long[]>();
    final var workers = new ArrayList<JobWorker>();
    final var done = new CountDownLatch(WORKER_TYPES * JOBS_PER_TYPE);
    final var started = new AtomicInteger();
    try {
      for (int t = 0; t < WORKER_TYPES; t++) {
        final var jobType = "worker-race-%d".formatted(t);
        final var failedAt = new long[2];
        var builder = client
            .newWorker()
            .jobType(jobType)
            .handler((
                jobClient,
                job) -> {
              if (job.getRetries() == 3) {
                final var pause = ThreadLocalRandom.current().nextInt(HANDLER_DELAY_MAX + 1);
                Thread.sleep(pause);
                var fail = jobClient
                    .newFailCommand(job.getKey())
                    .retries(2)
                    .retryBackoff(BACKOFF);
                fail = Camunda8JobLease.withToken(fail, Camunda8JobLease.tokenOf(job));
                fail.send().join();
                failedAt[0] = System.nanoTime();
                failedAt[1] = pause;
              } else {
                final var delay = (System.nanoTime() - failedAt[0]) / 1_000_000L;
                delays.add(new long[]{
                    delay, failedAt[1]
                });
                var complete = jobClient.newCompleteCommand(job.getKey());
                complete = Camunda8JobLease.withToken(complete, Camunda8JobLease.tokenOf(job));
                complete.send().join();
                done.countDown();
                if (started.incrementAndGet() <= WORKER_TYPES * JOBS_PER_TYPE) {
                  startOne(job.getVariable("jobType").toString());
                }
              }
            })
            .timeout(LOCK)
            .requestTimeout(REQUEST_TIMEOUT)
            .pollInterval(POLL_INTERVAL);
        if (LEASE) {
          builder = Camunda8JobLease.leaseTheActivations(builder);
        }
        workers.add(builder.open());
      }
      for (int t = 0; t < WORKER_TYPES; t++) {
        started.incrementAndGet();
        startOne("worker-race-%d".formatted(t));
      }
      done.await(60, TimeUnit.MINUTES);
    } finally {
      workers.forEach(JobWorker::close);
    }

    final var normal = BACKOFF.toMillis() + 5_000;
    final List<long[]> late = delays.stream().filter(d -> d[0] > normal).toList();
    System.out.printf(
        "RACE worker lease=%s requestTimeout=%s backoff=%s pollInterval=%s handlerDelayMax=%d: %d second deliveries, %d late; late (delay ms, handler pause ms) %s%n",
        LEASE && Camunda8JobLease.supportedByThisLine(), REQUEST_TIMEOUT, BACKOFF, POLL_INTERVAL, HANDLER_DELAY_MAX,
        delays.size(), late.size(),
        late.stream().map(d -> d[0]
            + "/"
            + d[1]).toList());

  }

  /**
   * Whether a worker which does not lease gets a job whose lease ran out. This decides
   * whether an application can switch the lease off without leaving jobs behind.
   */
  @Test
  public void ratchet() throws Exception {

    final var jobType = "ratchet";
    startOne(jobType);
    final var leased = client
        .newActivateJobsCommand()
        .jobType(jobType)
        .maxJobsToActivate(1)
        .timeout(Duration.ofSeconds(3));
    final var first = Camunda8JobLease
        .leaseTheActivation(leased)
        .requestTimeout(Duration.ofSeconds(5))
        .send()
        .join()
        .getJobs();
    Thread.sleep(5_000);
    final var withoutLease = client
        .newActivateJobsCommand()
        .jobType(jobType)
        .maxJobsToActivate(1)
        .timeout(Duration.ofSeconds(30))
        .requestTimeout(Duration.ofSeconds(10))
        .send()
        .join()
        .getJobs();
    final var withLease = Camunda8JobLease
        .leaseTheActivation(
            client
                .newActivateJobsCommand()
                .jobType(jobType)
                .maxJobsToActivate(1)
                .timeout(Duration.ofSeconds(30)))
        .requestTimeout(Duration.ofSeconds(5))
        .send()
        .join()
        .getJobs();
    System.out.printf(
        "RACE ratchet: leased activation got %d, after its lock ran out an activation without lease got %d, one with lease got %d%n",
        first.size(), withoutLease.size(), withLease.size());

  }

  private static void startOne(
      final String jobType) {

    client
        .newCreateInstanceCommand()
        .bpmnProcessId("race")
        .latestVersion()
        .variables(Map.of("jobType", jobType))
        .send();

  }

}
