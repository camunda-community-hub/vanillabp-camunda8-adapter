package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A workflow started right after a restart gets its
 * first job in milliseconds rather than in a job timeout.
 * <p>
 * Two application contexts run in one JVM against one cluster, which is what a restart
 * with changed configuration looks like. The first one works one workflow, so its workers
 * have an activation request parked at the cluster, and is then closed. The second one
 * starts a workflow, and the time until its handler is reached is the number this test
 * exists for. Both talk to the same pair of containers: the Testcontainers extension
 * starts them before the class and stops them after it, so the cluster and its
 * Elasticsearch outlive both applications.
 * <p>
 * <b>What it guards.</b> An activation request which is parked at the cluster when its
 * client is closed stays parked, and a job created afterwards is activated into it and
 * answered by nobody. Measured against {@code camunda/camunda:8.9.16} with a plain client
 * and no VanillaBP: 20,2 to 21,0 seconds at a {@code job-timeout} of {@code PT20S} where
 * the second application starts seven seconds after the first one was closed, and 15 to
 * 25 milliseconds where it starts twelve seconds afterwards, which is beyond the client's
 * {@code request-timeout} of ten seconds. The adapter closes that window by waiting for
 * its workers to report themselves closed before the client goes down, which is what this
 * test measures end to end.
 * <p>
 * The applications are booted by the test rather than by {@code @SpringBootTest}, for the
 * same reason as in {@link Camunda8ShutdownDrainIT}: shutting one down is part of the
 * scenario, and Spring's test context is a cache which expects to own that lifecycle.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8RestartDeliveryIT extends TestOnTheSharedCluster {

  /**
   * The lock of the job under test. A job swallowed by a parked activation request comes
   * back exactly this late, so it is also the number the assertion is measured against.
   */
  private static final Duration JOB_TIMEOUT = Duration.ofSeconds(20);

  /**
   * How long an activation request of the closed application can outlive it. The module
   * lowers {@code request-timeout} for its other classes to make them faster, and this class
   * is the one which needs the client's own value: the window has to be wide enough for
   * {@link #GAP} to sit inside it, or the test would pass on a drain which does nothing.
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /**
   * How long the shutdown of this class waits for its workers to be released, which is what
   * the drain assertion below reads.
   * <p>
   * It is written down here because the default does not fit this class. A drain cannot end
   * before the cluster answers the activation requests its workers have parked, so its floor
   * is one {@link #REQUEST_TIMEOUT}, and this is the one class in the module which doubles
   * that timeout without touching the grace: with the default of {@code PT20S} the drain of
   * this class gets twice its own floor, while every other class gets four times theirs.
   * <p>
   * What that cost, measured on 2026-09-27. In the nightly-style run of line 8.10 on the
   * runner of attempt 1 of run 36305111194, the first application of this test was stopped
   * after 20045 ms with its 115 workers not yet released - the grace, exactly - while the
   * SECOND application of the same run drained in 10073 ms. Re-running the same commit was
   * green. Locally, on an idle machine, the whole {@code close()} takes 12252 to 12522 ms on
   * {@code camunda/camunda:8.10.0-rc1} and 11973 to 12053 ms on {@code camunda/camunda:8.9.21},
   * so the line is not what decides it; the load of the machine is. With the client's own
   * pool of 100 connections instead of the 256 this module sets, the test fails at the
   * DELIVERY assertion (10284 ms) rather than here, which is the finding of story 685 and a
   * different one from this.
   * <p>
   * Twenty-five seconds and not more: from thirty on, the adapter warns that the grace
   * reaches into the shutdown budget Spring Boot and Kubernetes default to, and that warning
   * would be right.
   * <p>
   * <b>What raising it to twenty-five bought, measured on 2026-10-01.</b> Nothing. The same
   * shutdown ran out of the same grace twice that day: the publish run of {@code 443a32d}
   * (36871927557) stopped after 25049 ms, the run of pull request 231 (36889710683) after
   * 25012 ms. Both were the FIRST application of this test, with 119 closed workers of which
   * at least one had not been released and no handler left inside the application. The
   * second application of each of those runs drained in 10123 ms. So this number is not what
   * decides it, and the next number would not be either.
   * <p>
   * What the wait hangs on was measured locally the same day, by reading every closed worker
   * while the drain ran. All 119 of them sit on the activation request they had in flight
   * when they were closed: no job in their hands, no poll scheduled, nothing but a request
   * the cluster has not answered yet. They come back within a second of each other, one
   * {@link #REQUEST_TIMEOUT} after the shutdown began, which is where the 10 to 12 seconds of
   * an idle machine come from. Squeezing the cluster into four tenths of a core reproduces
   * the red runs on demand: the gateway answers so few of those requests that 105 of the 119
   * are still open when the grace runs out. The floor of this wait is therefore the cluster
   * answering 119 parked requests, and no grace this test may configure covers a cluster
   * slow enough.
   */
  private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(25);

  /**
   * How long the second application waits before it starts. It has to stay below
   * {@link #REQUEST_TIMEOUT}, because that is how long an activation request of the closed
   * application can outlive it. The blueprint which found this took 7,4 seconds.
   */
  private static final Duration GAP = Duration.ofSeconds(5);

  /**
   * Tells the class after this one that a request of this one can be parked for the client's
   * whole window rather than the short one the module configures.
   */
  @BeforeAll
  static void aRequestOfThisClassIsParkedForTheClientsOwnWindow() {

    aRequestOfThisClassCanBeParkedFor(REQUEST_TIMEOUT);

  }

  /**
   * What the first job may take before the test calls it a delivery which waited for the
   * lock. Far above the milliseconds a healthy delivery needs and far below the lock.
   */
  private static final Duration DELIVERED_IN_SECONDS = Duration.ofSeconds(8);

  private ConfigurableApplicationContext application;

  private ConfigurableApplicationContext boot() {

    return new SpringApplicationBuilder(DockerTestApplication.class)
        .run(
            "--spring.config.name=camunda8-it",
            "--spring.main.web-application-type=none",
            "--vanillabp.adapters.c8.rest-address="
                + restAddress(),
            "--vanillabp.adapters.c8.grpc-address="
                + grpcAddress(),
            "--vanillabp.adapters.c8.request-timeout="
                + REQUEST_TIMEOUT,
            "--vanillabp.adapters.c8.shutdown-grace="
                + SHUTDOWN_GRACE,
            "--vanillabp.workflow-modules.test-app.workflows.RestartProcess.adapters.c8.job-timeout="
                + JOB_TIMEOUT);

  }

  @AfterEach
  public void closeWhatIsLeft() {

    if ((application != null) && application.isActive()) {
      application.close();
    }
    RestartDockerWorkflowService.reset();

  }

  private <T> T bean(
      final Class<T> type) {

    return application.getBean(type);

  }

  private void startWorkflow() {

    bean(TransactionTemplate.class)
        .executeWithoutResult(status -> bean(RestartDockerWorkflowService.class).startWorkflow());

  }

  /**
   * Puts the measurement where it can be read after a build: into the test report of the
   * runner respectively an IDE, and into a file of its own, because the console output of
   * a test which passes is suppressed.
   *
   * @param reporter The JUnit reporter
   * @param measurement What was measured
   */
  private static void report(
      final TestReporter reporter,
      final String measurement) {

    reporter.publishEntry("restart-delivery", measurement);
    try {
      Files.writeString(
          Path.of("target", "restart-delivery.txt"),
          measurement + System.lineSeparator());
    } catch (final IOException e) {
      throw new UncheckedIOException("Cannot write down what was measured", e);
    }

  }

  @Test
  @DisplayName("A workflow started right after a restart gets its first job in milliseconds")
  public void aWorkflowStartedAfterARestartIsServedRightAway(
      final CapturedOutput output,
      final TestReporter reporter) throws Exception {

    // the first application, which works one workflow so its workers have an activation
    // request parked at the cluster by the time it is closed
    RestartDockerWorkflowService.reset();
    application = boot();
    startWorkflow();
    assertTrue(
        RestartDockerWorkflowService.SERVED.await(1, TimeUnit.MINUTES),
        "the first application served its own workflow");

    final var shutdownStartedAt = System.nanoTime();
    application.close();
    final var shutdownMillis = (System.nanoTime() - shutdownStartedAt) / 1_000_000;

    TimeUnit.MILLISECONDS.sleep(GAP.toMillis());

    // and the second one, whose worker is open before the workflow exists
    RestartDockerWorkflowService.reset();
    application = boot();
    final var startedAt = System.nanoTime();
    startWorkflow();
    final var served = RestartDockerWorkflowService.SERVED.await(
        JOB_TIMEOUT.multipliedBy(2).toSeconds(),
        TimeUnit.SECONDS);
    final var deliveredAfterMillis = (RestartDockerWorkflowService.SERVED_AT.get() - startedAt) / 1_000_000;

    // written down rather than printed: the output of a test which passes is suppressed,
    // and this number is the whole point of the test
    report(
        reporter,
        "the first job of a workflow started %s after a restart was delivered after %d ms (job timeout %s, shutdown grace %s, the shutdown of the first application took %d ms)"
            .formatted(GAP, deliveredAfterMillis, JOB_TIMEOUT, SHUTDOWN_GRACE, shutdownMillis));

    assertTrue(served, "the job reached the handler at all");
    assertTrue(
        deliveredAfterMillis < DELIVERED_IN_SECONDS.toMillis(),
        "the first job of the restarted application was delivered in seconds rather than in a job timeout (was "
            + deliveredAfterMillis
            + " ms, the lock is "
            + JOB_TIMEOUT
            + ")");

    // and the shutdown said what it did: the workers were closed and the cluster released
    // them, which is the reason the number above is what it is
    final var logged = output.getOut() + output.getErr();
    final var drainedAt = logged.indexOf("no activation request of theirs left at the cluster");
    final var clientClosedAt = logged.indexOf("Closing Camunda 8 client");
    assertTrue(drainedAt >= 0, "the drain reported a module which is quiet, and this is what it said instead: "
        + whatTheShutdownSaid(logged));
    assertTrue(clientClosedAt >= 0, "and the client was closed: "
        + whatTheShutdownSaid(logged));
    assertTrue(
        drainedAt < clientClosedAt,
        "the workers of the module were closed and released BEFORE its client went down");

    // read over the running test, because a sentence which is absent is only absent for
    // the test which says so. Over the whole class it would speak for every other test of
    // it as well, and nothing orders them
    final var ofThisTest = output.getAllOfThisTest();
    assertFalse(
        ofThisTest.contains("still holding an activation request at the cluster"),
        "and nothing was left parked when the client was closed: "
            + whatTheShutdownSaid(ofThisTest));
    assertFalse(
        ofThisTest.contains("did not stop workflow processing"),
        "the ordinary Spring Boot shutdown reaches the adapter, so the backstop of the client factory stays "
            + "silent: "
            + whatTheShutdownSaid(ofThisTest));

  }

  /**
   * The sentences of the log which the two shutdowns of this test are about.
   * <p>
   * The whole captured log is a few thousand lines of two application starts, and a
   * failing assertion which dumps all of it buries the one line it is about. Both red runs
   * of 2026-10-01 read that way: the drain had written down what it did, and the reader had
   * to search a log of 6000 lines to find it.
   *
   * @param logged What was printed
   * @return The lines about the shutdown, or a note that there were none
   */
  private static String whatTheShutdownSaid(
      final String logged) {

    final var aboutTheShutdown = logged
        .lines()
        .filter(
            line -> WHAT_A_SHUTDOWN_WRITES
                .stream()
                .anyMatch(line::contains))
        .collect(Collectors.joining(System.lineSeparator()));
    return aboutTheShutdown.isEmpty()
        ? "nothing at all about its shutdown"
        : System.lineSeparator() + aboutTheShutdown;

  }

  /**
   * How a line about the shutdown is recognized: the words the drain reports with, and the
   * one the client factory writes when the client goes down.
   */
  private static final List<String> WHAT_A_SHUTDOWN_WRITES = List
      .of(
          "drained after",
          "was stopped after",
          "was still running after the shutdown waited",
          "did not stop workflow processing",
          "Workflow processing stopped",
          "Closing Camunda 8 client");

}
