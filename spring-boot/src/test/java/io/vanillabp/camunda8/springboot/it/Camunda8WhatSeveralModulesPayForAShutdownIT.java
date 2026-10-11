package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.worker.JobWorker;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the shutdown of an application with SEVERAL workflow modules costs, measured against
 * the thirty seconds its runtime grants it.
 * <p>
 * The platform stops the workflow modules one after another, on the shutdown thread, and
 * every one of those calls closes the workers of ITS module and then waits for the cluster
 * to release them. The grace period is a property of the adapter, so each of those waits is
 * bounded by the same number. The question nobody had measured is what the APPLICATION pays
 * for that, and it has two halves:
 * <ul>
 * <li>how long an application with two and with three modules really takes, against the
 * thirty seconds {@code spring.lifecycle.timeout-per-shutdown-phase} and Kubernetes'
 * {@code terminationGracePeriodSeconds} grant it;</li>
 * <li>whether a module which is stopped after another still has anything to wait for. The
 * rounds of the connection pool belong to the CLIENT and therefore to every module of it,
 * while one module waits only for its own workers - so it was open whether the second
 * module pays again or walks straight through.</li>
 * </ul>
 * <b>How it is measured.</b> One client, configured the way an application configures one.
 * Its workers are split into groups of thirty. A group is a workflow module, each with a
 * {@link Camunda8Drain} of its own, the same object which the adapter gives a module. The
 * groups are left alone long enough for every activation request to be parked at the
 * cluster. Then the shutdown is replayed exactly as the platform drives it: last module
 * first, one module at a time. Each module closes its own workers and then calls
 * {@link Camunda8Drain#awaitQuiet} with the whole grace. The test reads the wait of each
 * module and the sum over them.
 * <p>
 * The same cases are read a second time, with every module's workers closed BEFORE anything
 * is waited for. That is the other way an adapter can do it. Then the modules cost one wait,
 * not a wait each. Both readings are in the same block, because this measurement was taken to
 * choose between them.
 * <p>
 * The pool is large enough for every case to be one round of it ({@value
 * #MAX_HTTP_CONNECTIONS} connections against at most ninety workers). That is on purpose.
 * {@code Camunda8WhatADrainWaitsForIT} measures what the ROUNDS of the pool cost. Leaving
 * them out here makes the number of MODULES the only thing which varies.
 * <p>
 * {@code whatOneRealApplicationPaysForItsShutdown} is the anchor: a booted application with
 * one real workflow module and every worker which the test suite deploys. So the groups above
 * can be compared with a module which nobody built for the measurement.
 * <p>
 * <b>Why the groups do not start together.</b> What a module pays depends on where in its
 * request cycle it was when its workers were closed. Opening every group in the same
 * millisecond puts every module at the same point of that cycle. A shutdown then meets all
 * of them right after their requests came back and finds nothing parked. Then the per-module
 * waits do not add up. That reading was taken once, before the groups were offset. It is the
 * one case which a real application does not have, because its modules start one after
 * another. So each group opens three seconds after the one before it. The cases which can
 * move with the phase are read twice.
 * <p>
 * The README has the numbers which this produced, together with the machine they were taken
 * on, in the section "Shutting down while work is in flight".
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8WhatSeveralModulesPayForAShutdownIT extends TestOnTheSharedCluster {

  /**
   * The pool which the client of this class gets. It is big enough that ninety workers are
   * one round of it. So only the number of workflow modules varies between the cases.
   */
  private static final int MAX_HTTP_CONNECTIONS = 256;

  /**
   * How long a worker's activation request waits at the cluster. Every number below is read
   * in this unit. It is the client's own default, and the lower bound of a drain is computed
   * from it as well.
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /**
   * How many workers one group opens. A workflow module of the suite below opens about
   * eighty-five. The anchor says whether the number inside a group matters at all. These
   * cases are about how many groups there are.
   */
  private static final int WORKERS_PER_MODULE = 30;

  /**
   * The grace which every wait of this class is given: the adapter's default. An application
   * has this number unless it configured one.
   */
  private static final Duration GRACE = Camunda8AdapterConfiguration.DEFAULT_SHUTDOWN_GRACE;

  /**
   * What the runtime around the application grants its shutdown. So every total below is
   * compared with this number.
   */
  private static final Duration PLATFORM_SHUTDOWN_BUDGET = Camunda8AdapterConfiguration.PLATFORM_SHUTDOWN_BUDGET;

  /**
   * One case: how many workflow modules, which of the two ways of draining them, and how
   * often the case is read.
   *
   * @param modules How many workflow modules
   * @param closeThemAllFirst Whether every module's workers are closed before anything is
   *          waited for
   * @param readings How often the case is read. Two where the reading moves with the phase
   *          in which the close falls, one where the case has no phase to fall into
   */
  private record Case(
                      int modules,
                      boolean closeThemAllFirst,
                      int readings) {

  }

  /**
   * What is read, in this order. The cases with several modules drained one after another are
   * read twice. What one of them costs depends on where in its request cycle each module was
   * when its workers were closed.
   */
  private static final List<Case> CASES = List
      .of(
          new Case(1, false, 1),
          new Case(2, false, 2),
          new Case(3, false, 2),
          new Case(2, true, 1),
          new Case(3, true, 1));

  /**
   * How long the workers are left alone before anything is closed. A whole request timeout
   * plus five seconds. So every request has been parked once, and the close falls into the
   * middle of a round and not onto its edge. On the edge, nobody could tell which round a
   * reading belongs to.
   */
  private static final Duration UNTIL_EVERY_REQUEST_IS_PARKED = REQUEST_TIMEOUT.plusSeconds(5);

  /**
   * How long one module's workers are open before the next module's are opened.
   * <p>
   * An application starts its workflow modules one after another. A module takes seconds to
   * deploy and open its workers. So the request cycles of two modules are offset against each
   * other. Opening every group in the same millisecond would synchronise them instead. Then a
   * shutdown would meet every module at the same point of its cycle. That is the one case
   * where the per-module waits do NOT add up, and the one case which a real application does
   * not have.
   */
  private static final Duration BETWEEN_TWO_MODULES_STARTING = Duration.ofSeconds(3);

  /**
   * Tells the class which runs next that a request of this one can be parked for a whole
   * {@link #REQUEST_TIMEOUT}. That is longer than the five seconds which the suite
   * configures.
   */
  @BeforeAll
  static void aRequestOfThisClassLivesLongerThanTheSuiteExpects() {

    aRequestOfThisClassCanBeParkedFor(REQUEST_TIMEOUT);

  }

  /**
   * What the two ways of draining several workflow modules cost: one wait per module, the
   * way the platform drives it today, and one wait for all of them.
   *
   * @param reporter Where the reading goes
   * @throws Exception Where the cluster or a wait is interrupted
   */
  @Test
  @DisplayName("What an application pays for stopping two and three workflow modules")
  public void whatSeveralWorkflowModulesPayForOneShutdown(
      final TestReporter reporter) throws Exception {

    final var measured = new ArrayList<String>();
    var longestWaitOfAModuleStoppedLater = Duration.ZERO;
    var longestShutdownOfOneWait = Duration.ZERO;

    for (final var testCase : CASES) {
      for (var reading = 1; reading <= testCase.readings(); ++reading) {
        final var waits = howLongTheShutdownTakes(testCase, reading, measured);
        if (testCase.closeThemAllFirst()) {
          longestShutdownOfOneWait = longest(longestShutdownOfOneWait, waits.wholeShutdown());
        } else {
          longestWaitOfAModuleStoppedLater = longest(
              longestWaitOfAModuleStoppedLater,
              waits.longestWaitOfAModuleStoppedLater());
        }
      }
    }

    measured
        .add(
            "conditions: cluster %s, client pool %d, request timeout %s, grace %s, %d workers per module, %s between two modules starting, %d processors"
                .formatted(
                    ClusterUnderTest.image(),
                    Integer.valueOf(MAX_HTTP_CONNECTIONS),
                    REQUEST_TIMEOUT,
                    GRACE,
                    Integer.valueOf(WORKERS_PER_MODULE),
                    BETWEEN_TWO_MODULES_STARTING,
                    Integer.valueOf(Runtime.getRuntime().availableProcessors())));
    report(reporter, "what-several-modules-pay-for-a-shutdown", measured);

    // the first thing which the decision rests on. A module stopped after another one still
    // has an activation request of its own to wait for. So the waits add up and do not
    // overlap
    assertTrue(
        longestWaitOfAModuleStoppedLater.compareTo(REQUEST_TIMEOUT.dividedBy(2)) > 0,
        "A workflow module which is stopped after another one is supposed to have an activation request "
            + "of its own left to wait for: the workers of a module which is still open keep renewing "
            + "their request while another module is drained, and closing a worker does not cancel the "
            + "request it has in flight. The longest such wait of this run was "
            + longestWaitOfAModuleStoppedLater
            + ", which is within half a request timeout, so either the cluster now releases a worker "
            + "whose request is still parked or the client cancels it on close. Both would mean the sum "
            + "of the per-module waits is no longer the number a grace has to be sized against. What was "
            + "measured: "
            + String.join(" | ", measured));

    // and the second. Closing the workers of every module before anything is waited for
    // turns those waits into one wait, whatever the number of modules. That makes the grace
    // a number of the application, not a number per module
    assertTrue(
        longestShutdownOfOneWait.compareTo(REQUEST_TIMEOUT.multipliedBy(2)) < 0,
        "A shutdown which closes the workers of every workflow module and then waits once is supposed to "
            + "cost about one request timeout, however many modules there are: the requests of all of "
            + "them are parked at the same time and come back at the same time. The longest such "
            + "shutdown of this run took "
            + longestShutdownOfOneWait
            + ", which is more than two request timeouts, so waiting once is not one wait after all. "
            + "What was measured: "
            + String.join(" | ", measured));

  }

  /**
   * The longer of two durations.
   *
   * @param one The first
   * @param other The second
   * @return Whichever is longer
   */
  private static Duration longest(
      final Duration one,
      final Duration other) {

    return one.compareTo(other) >= 0
        ? one
        : other;

  }

  /**
   * The anchor: a booted application with one real workflow module, every BPMN process of
   * this suite and every worker they need. It is shut down while nothing is in flight.
   * <p>
   * It says two things which the groups above cannot. First, the wait of a real module is
   * read on the workers which an application really opens. So it shows whether the thirty of
   * a group are too few or too many. Second, the number it produces is the whole
   * {@code close()} of a Spring Boot application. That is what has to fit into the budget of
   * the runtime.
   *
   * @param reporter Where the reading goes
   */
  @Test
  @DisplayName("What a real application with one workflow module pays for its shutdown")
  public void whatOneRealApplicationPaysForItsShutdown(
      final TestReporter reporter) {

    final var application = new SpringApplicationBuilder(DockerTestApplication.class)
        .run(
            "--spring.config.name=camunda8-it",
            "--spring.main.web-application-type=none",
            "--vanillabp.adapters.c8.rest-address="
                + restAddress(),
            "--vanillabp.adapters.c8.grpc-address="
                + grpcAddress(),
            // the default. So the reading is the one which an application gets without
            // saying anything about its shutdown
            "--vanillabp.adapters.c8.shutdown-grace="
                + GRACE);
    final long closedIn;
    try {
      final var startedAt = System.nanoTime();
      application.close();
      closedIn = (System.nanoTime() - startedAt) / 1_000_000;
    } finally {
      if (application.isActive()) {
        application.close();
      }
    }

    report(
        reporter,
        "what-one-real-module-pays-for-a-shutdown",
        List
            .of(
                "one real workflow module, request timeout %s as the suite configures it, grace %s: the whole close() took %d ms"
                    .formatted(
                        Duration.ofSeconds(5),
                        GRACE,
                        Long.valueOf(closedIn)),
                "conditions: cluster %s, client pool 256, %d processors"
                    .formatted(
                        ClusterUnderTest.image(),
                        Integer.valueOf(Runtime.getRuntime().availableProcessors()))));

    // the one thing asserted here: a single module stays well inside the budget of the
    // runtime. That is why the test had to measure several modules
    assertTrue(
        closedIn < PLATFORM_SHUTDOWN_BUDGET.toMillis(),
        "An application with ONE workflow module is supposed to shut down within the "
            + PLATFORM_SHUTDOWN_BUDGET
            + " its runtime grants it. This one took "
            + closedIn
            + " ms, so the per-module wait is already the whole budget and the measurement above is "
            + "about an application which cannot shut down at all");

  }

  /**
   * What one reading ended with.
   *
   * @param wholeShutdown What the whole shutdown of that reading took
   * @param longestWaitOfAModuleStoppedLater The longest wait of a module which was not the
   *          first one stopped. This measurement is about this number. The first module
   *          stopped pays whatever its own request has left. Nobody had measured what the
   *          modules after it pay
   */
  private record Reading(
                         Duration wholeShutdown,
                         Duration longestWaitOfAModuleStoppedLater) {

  }

  /**
   * Opens the groups of one case and lets every activation request park. Then it replays the
   * shutdown over them.
   *
   * @param testCase What to read
   * @param reading Which reading of that case this is, for the line it writes
   * @param measured Where the line of this reading goes
   * @return What this reading ended with
   * @throws Exception Where a wait is interrupted
   */
  private Reading howLongTheShutdownTakes(
      final Case testCase,
      final int reading,
      final List<String> measured) throws Exception {

    final var workersOfModule = new LinkedHashMap<String, List<JobWorker>>();
    final var drainOfModule = new LinkedHashMap<String, Camunda8Drain>();
    try (final var client = client()) {

      // the client has talked to the cluster once before any worker is opened. So the
      // workers do not wait for the connection which an ordinary command uses
      client.newTopologyRequest().send().join();

      for (var module = 1; module <= testCase.modules(); ++module) {
        if (module > 1) {
          // the modules of an application do not start in the same millisecond. Their
          // request cycles are offset by the time which the start of one of them takes
          TimeUnit.MILLISECONDS.sleep(BETWEEN_TWO_MODULES_STARTING.toMillis());
        }
        final var workflowModuleId = "module-%d-of-%d%s-%d".formatted(
            Integer.valueOf(module),
            Integer.valueOf(testCase.modules()),
            testCase.closeThemAllFirst()
                ? "-together"
                : "",
            Integer.valueOf(reading));
        drainOfModule.put(workflowModuleId, new Camunda8Drain("c8", workflowModuleId));
        workersOfModule.put(workflowModuleId, openTheWorkersOf(client, workflowModuleId));
      }
      TimeUnit.MILLISECONDS.sleep(UNTIL_EVERY_REQUEST_IS_PARKED.toMillis());

      final var startedAt = System.nanoTime();
      final var waitOfModule = testCase.closeThemAllFirst()
          ? oneWaitForEveryModule(workersOfModule, drainOfModule)
          : oneWaitPerModule(workersOfModule, drainOfModule);
      final var wholeShutdown = Duration.ofNanos(System.nanoTime() - startedAt);

      measured
          .add(
              "%d workflow module(s), %s, reading %d: the whole shutdown took %d ms (%.2f request timeouts, %.0f%% of the %s the runtime grants it); per module, in the order they were stopped: %s"
                  .formatted(
                      Integer.valueOf(testCase.modules()),
                      testCase.closeThemAllFirst()
                          ? "every module's workers closed first and then one wait"
                          : "one wait per module, as the platform drives it",
                      Integer.valueOf(reading),
                      Long.valueOf(wholeShutdown.toMillis()),
                      Double.valueOf((double) wholeShutdown.toMillis() / REQUEST_TIMEOUT.toMillis()),
                      Double.valueOf((100.0 * wholeShutdown.toMillis()) / PLATFORM_SHUTDOWN_BUDGET.toMillis()),
                      PLATFORM_SHUTDOWN_BUDGET,
                      whatEachModuleWaited(waitOfModule)));
      return new Reading(wholeShutdown, longestWaitAfterTheFirstModule(waitOfModule));

    } finally {
      workersOfModule.values().forEach(workers -> workers.forEach(JobWorker::close));
    }

  }

  /**
   * The longest wait of a module which was not the first one stopped.
   *
   * @param waits What each module waited, in the order they were stopped
   * @return The longest of those waits, or zero where only one module was stopped
   */
  private static Duration longestWaitAfterTheFirstModule(
      final Map<String, Camunda8Drain.DrainOutcome> waits) {

    return waits
        .values()
        .stream()
        .skip(1)
        .map(outcome -> Duration.ofMillis(outcome.waitedMillis()))
        .reduce(Duration.ZERO, Camunda8WhatSeveralModulesPayForAShutdownIT::longest);

  }

  /**
   * The shutdown the platform drives today: one module at a time, in reverse order, each
   * closing its own workers and then waiting the whole grace for them.
   *
   * @param workersOfModule The workers per workflow module
   * @param drainOfModule The drain per workflow module
   * @return What each module waited, in the order the modules were stopped
   */
  private Map<String, Camunda8Drain.DrainOutcome> oneWaitPerModule(
      final Map<String, List<JobWorker>> workersOfModule,
      final Map<String, Camunda8Drain> drainOfModule) {

    final var waits = new LinkedHashMap<String, Camunda8Drain.DrainOutcome>();
    stoppedInReverseOrder(workersOfModule)
        .forEach(workflowModuleId -> {
          final var workers = workersOfModule.get(workflowModuleId);
          final var drain = drainOfModule.get(workflowModuleId);
          drain.beginShutdown();
          workers.forEach(JobWorker::close);
          waits
              .put(
                  workflowModuleId,
                  drain.awaitQuiet(GRACE, workers.size(), () -> workers.stream().allMatch(JobWorker::isClosed)));
        });
    return waits;

  }

  /**
   * The other way: every module's workers are closed first. Then the grace is spent once, on
   * all of them together.
   *
   * @param workersOfModule The workers per workflow module
   * @param drainOfModule The drain per workflow module
   * @return What the one wait ended with, per workflow module
   */
  private Map<String, Camunda8Drain.DrainOutcome> oneWaitForEveryModule(
      final Map<String, List<JobWorker>> workersOfModule,
      final Map<String, Camunda8Drain> drainOfModule) {

    stoppedInReverseOrder(workersOfModule)
        .forEach(workflowModuleId -> {
          drainOfModule.get(workflowModuleId).beginShutdown();
          workersOfModule.get(workflowModuleId).forEach(JobWorker::close);
        });
    final var closed = workersOfModule
        .keySet()
        .stream()
        .map(workflowModuleId -> new Camunda8Drain.ClosedWorkers(
            drainOfModule.get(workflowModuleId), workersOfModule.get(workflowModuleId)
                .size(), () -> workersOfModule.get(workflowModuleId).stream().allMatch(JobWorker::isClosed)))
        .toList();
    final var outcomes = Camunda8Drain.awaitEveryModuleQuiet(closed, GRACE);
    final var waits = new LinkedHashMap<String, Camunda8Drain.DrainOutcome>();
    stoppedInReverseOrder(workersOfModule)
        .forEach(workflowModuleId -> waits
            .put(workflowModuleId, outcomes.get(drainOfModule.get(workflowModuleId))));
    return waits;

  }

  /**
   * The order in which the platform stops the modules: the one started last goes down first.
   *
   * @param workersOfModule The workers per workflow module, in the order they were started
   * @return The workflow module ids in the order they are stopped
   */
  private static List<String> stoppedInReverseOrder(
      final Map<String, List<JobWorker>> workersOfModule) {

    final var stopped = new ArrayList<>(workersOfModule.keySet());
    java.util.Collections.reverse(stopped);
    return stopped;

  }

  /**
   * What each module waited, as one line of the reading.
   *
   * @param waits The outcome per workflow module, in the order they were stopped
   * @return The text
   */
  private static String whatEachModuleWaited(
      final Map<String, Camunda8Drain.DrainOutcome> waits) {

    return waits
        .entrySet()
        .stream()
        .map(wait -> "'%s' %d ms (%s)".formatted(
            wait.getKey(),
            Long.valueOf(wait.getValue().waitedMillis()),
            wait.getValue().isQuiet()
                ? "quiet"
                : "the grace ran out"))
        .reduce((
            one,
            next) -> one
                + ", "
                + next)
        .orElse("nothing");

  }

  /**
   * One group of workers of one workflow module. Nothing ever produces their job types. So no
   * handler of this class runs, and the test reads a worker which waits.
   *
   * @param client The client every module of a case shares, the way the modules of an
   *          adapter instance share one
   * @param workflowModuleId The workflow module the group belongs to
   * @return The open workers
   */
  private List<JobWorker> openTheWorkersOf(
      final CamundaClient client,
      final String workflowModuleId) {

    final var open = new ArrayList<JobWorker>();
    for (var worker = 0; worker < WORKERS_PER_MODULE; ++worker) {
      open
          .add(client
              .newWorker()
              .jobType("whatSeveralModulesPay-%s-%d".formatted(workflowModuleId, Integer.valueOf(worker)))
              .handler((
                  jobClient,
                  job) -> {
              })
              .timeout(Duration.ofMinutes(1))
              .requestTimeout(REQUEST_TIMEOUT)
              .name("what-several-modules-pay-%s-%d".formatted(workflowModuleId, Integer.valueOf(worker)))
              .open());
    }
    return open;

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
   * Puts a reading where it can be read after a build: into the test report of the runner or
   * of an IDE, and into a file of its own. The console output of a test which passes is
   * suppressed.
   *
   * @param reporter The JUnit reporter
   * @param name What the reading is called, which is also the name of its file
   * @param measured What was read, one line per case
   */
  private static void report(
      final TestReporter reporter,
      final String name,
      final List<String> measured) {

    final var text = String.join(System.lineSeparator(), measured);
    reporter.publishEntry(name, text);
    try {
      Files.writeString(
          Path.of("target", name
              + ".txt"),
          text + System.lineSeparator());
    } catch (final IOException e) {
      throw new UncheckedIOException("Cannot write down what was measured", e);
    }

  }

}
