package io.vanillabp.camunda8.quarkus.it;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.GenericContainer;

import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The Quarkus half. On this platform too, the workers of a workflow module are closed before
 * the client. And a workflow started right after a restart gets its first job in
 * milliseconds, not after a job timeout.
 * <p>
 * Spring Boot's {@code Camunda8RestartDeliveryIT} measures the same thing, and the
 * duplication is the point. The order which the two lifecycles produce comes from the
 * platform's glue code, not from the adapter's neutral core. Spring stops its
 * {@code SmartLifecycle} beans before it destroys them. Quarkus fires its
 * {@code ShutdownEvent} before it disposes the client's producer. Only a real run shows that
 * either of them reaches the adapter.
 * <p>
 * The test method itself stops and starts the application, not the extension. Only that makes
 * a restart visible in a prod-mode test at all. Between the two runs, the log of the forked
 * application says which came first: the drain or the closing client.
 * <p>
 * This test brings a cluster of its own. The lifecycle test avoids that on purpose for every
 * other feature. But this scenario owns the application's lifecycle. So it cannot share the
 * application which every other Quarkus test uses. Testcontainers removes the containers when
 * the JVM of the test run exits.
 * <p>
 * <b>Which ending of the drain counts, and why this half has the easier one.</b> Both halves
 * wait for the cluster to answer the activation requests which their closed workers parked.
 * The Spring Boot class ran out of time on that wait twice on 2026-10-01. It opens 119
 * workers, because its module deploys thirty-five processes into one application. Its
 * shutdown ends only once the cluster has answered the request of every one of them. This
 * application deploys one process into a cluster nobody else uses. So its shutdown waits for
 * only a few requests, and nobody has seen it end in any other way than quiet. Both halves
 * still accept either ending, for the reason given at {@link #THE_CLUSTER_STILL_OWED_AN_ANSWER}:
 * this adapter does not promise how fast the cluster answers. Everything the adapter does
 * promise is still asserted here, and a failure names the ending it read.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8RestartDeliveryTest {

  /**
   * The lock of the job under test, as {@code c8-restart/application.yaml} configures it. A job
   * can get lost in an activation request which outlived its client. Such a job comes back
   * exactly this late.
   */
  private static final Duration JOB_TIMEOUT = Duration.ofSeconds(20);

  /**
   * How long the application stays down. It is below the client's {@code request-timeout} of
   * ten seconds. That is how long an activation request of the stopped application can
   * outlive it.
   */
  private static final Duration GAP = Duration.ofSeconds(5);

  /**
   * What the first job may take before the test calls it a delivery which waited for the
   * lock.
   * <p>
   * Fifteen seconds against a lock of twenty. A delivery which waited for the lock cannot be
   * faster than the lock. One which did not wait takes milliseconds. So nothing a busy machine
   * does to this JVM falls between the two. Eight seconds did fall between them. A machine
   * which runs several builds can leave a JVM without CPU time for that long. The number then
   * said what the machine did, not what the shutdown did.
   */
  private static final Duration DELIVERED_WITHOUT_WAITING_FOR_THE_LOCK = Duration.ofSeconds(15);

  private static final Path LOG_FILE = Path
      .of("target", "c8-restart-application.log")
      .toAbsolutePath();

  /**
   * The cluster of this test. It brings secondary storage, because the adapter serves no
   * cluster which it cannot search. The application under test would refuse to deploy into a
   * broker alone, whatever the test is actually about.
   */
  static final GenericContainer<?> CAMUNDA = ClusterUnderTest.cluster("restart-cluster");

  /*
   * Started here and not by the Testcontainers extension. The application's runtime
   * properties need the mapped ports, and they are read while the field below is initialized.
   * The cluster outlives BOTH runs of the application which this test makes.
   */
  static {
    CAMUNDA.start();
  }

  private static final int HTTP_PORT = OneFreePortPerJvm.getPort();

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addPackage("io.vanillabp.camunda8.quarkus.test.restart")
          .addAsResource("c8-restart/application.yaml", "application.yaml")
          .addAsResource("c8-restart/processes/restart.bpmn", "c8-restart/processes/restart.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      .setRun(true)
      .setRuntimeProperties(Map
          .of(
              "quarkus.http.port",
              Integer.toString(HTTP_PORT),
              "vanillabp.adapters.c8.rest-address",
              "http://%s:%d".formatted(CAMUNDA.getHost(), CAMUNDA.getMappedPort(8080)),
              "vanillabp.adapters.c8.grpc-address",
              "http://%s:%d".formatted(CAMUNDA.getHost(), CAMUNDA.getMappedPort(26500)),
              // the application runs in a forked JVM. The order of its shutdown can be read
              // in its log
              "quarkus.log.file.enable",
              "true",
              "quarkus.log.file.path",
              LOG_FILE.toString()));

  private static void startWorkflow() {

    RestAssured
        .given()
        .baseUri("http://localhost")
        .port(HTTP_PORT)
        .post("/restart/start")
        .then()
        .statusCode(204);

  }

  /**
   * @return How long the first job of the last started workflow took, or -1 while it has
   *         not been delivered yet
   */
  @SuppressWarnings("unchecked")
  private static long deliveredAfterMillis() {

    final var delivery = RestAssured
        .given()
        .baseUri("http://localhost")
        .port(HTTP_PORT)
        .get("/restart/delivery")
        .then()
        .statusCode(200)
        .extract()
        .as(Map.class);
    return ((Number) ((Map<String, Object>) delivery).get("millis")).longValue();

  }

  private static long awaitDelivery(
      final Duration atMost) throws InterruptedException {

    final var deadline = System.nanoTime() + atMost.toNanos();
    while (System.nanoTime() < deadline) {
      final var millis = deliveredAfterMillis();
      if (millis >= 0) {
        return millis;
      }
      TimeUnit.MILLISECONDS.sleep(100);
    }
    return -1;

  }

  private static String applicationLog() {

    try {
      return Files.readString(LOG_FILE);
    } catch (final IOException e) {
      throw new UncheckedIOException("Cannot read the log of the application under test", e);
    }

  }

  /**
   * Puts the measurement where it can be read after a build. It goes into the test report of
   * the runner or the IDE, and into a file of its own. The console output of a test which
   * passes is suppressed.
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
  @DisplayName("The workers are closed before the client, and the workflow after the restart is served right away")
  public void aWorkflowStartedAfterARestartIsServedRightAway(
      final TestReporter reporter) throws Exception {

    // the first run works one workflow. So its workers have an activation request parked at
    // the cluster by the time the application stops
    startWorkflow();
    assertTrue(
        awaitDelivery(Duration.ofMinutes(1)) >= 0,
        "the first run served its own workflow");

    prodModeTest.stop();

    final var shutdownLog = applicationLog();
    // named here and reported further down. The test accepts both endings, and only the
    // report says which one a passing run took
    final var theDrainOfTheFirstRun = whichEndingTheDrainTook(shutdownLog);
    final var drainedAt = whereTheDrainReported(shutdownLog);
    final var clientClosedAt = shutdownLog.indexOf("Closing Camunda 8 client");
    assertTrue(drainedAt >= 0, "the drain said what it did with this module, and this is what it said instead: "
        + whatTheShutdownSaid(shutdownLog));
    assertTrue(clientClosedAt >= 0, "and the client was closed: "
        + whatTheShutdownSaid(shutdownLog));
    assertTrue(
        drainedAt < clientClosedAt,
        "the workers of the module were closed and drained BEFORE its client went down. Its drain "
            + theDrainOfTheFirstRun
            + ": "
            + whatTheShutdownSaid(shutdownLog));
    assertFalse(
        shutdownLog.contains(A_HANDLER_WAS_CUT_OFF),
        "and no handler was still running when the client was closed. The drain "
            + theDrainOfTheFirstRun
            + ": "
            + whatTheShutdownSaid(shutdownLog));
    assertFalse(
        shutdownLog.contains("did not stop workflow processing"),
        "the Quarkus shutdown event reaches the adapter, so the backstop of the client factory stays silent: "
            + whatTheShutdownSaid(shutdownLog));

    TimeUnit.MILLISECONDS.sleep(GAP.toMillis());

    // and the second run. Its worker is open before the workflow exists
    prodModeTest.start();
    startWorkflow();
    final var deliveredAfterMillis = awaitDelivery(JOB_TIMEOUT.multipliedBy(2));

    // written down, not printed. The output of a test which passes is suppressed, and this
    // number is the whole point of the test
    report(
        reporter,
        "the first job of a workflow started %s after a restart was delivered after %d ms (job timeout %s, and the drain of the first run %s)"
            .formatted(GAP, deliveredAfterMillis, JOB_TIMEOUT, theDrainOfTheFirstRun));

    assertTrue(deliveredAfterMillis >= 0, "the job reached the handler at all");
    assertTrue(
        deliveredAfterMillis < DELIVERED_WITHOUT_WAITING_FOR_THE_LOCK.toMillis(),
        "the first job of the restarted application was delivered in seconds rather than in a job timeout (was "
            + deliveredAfterMillis
            + " ms, the lock is "
            + JOB_TIMEOUT
            + ")");

  }

  /**
   * The sentences of the application's log which this test is about.
   * <p>
   * The log of a whole run has a few thousand lines. A failing assertion which dumps all of it
   * hides the one line it is about.
   *
   * @param logged What the application wrote
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
   * What the drain writes when the module went quiet within the grace period. No handler is
   * left in the application, and no activation request of its closed workers is left at the
   * cluster. This is the normal ending, and the only one this half has ever written.
   */
  private static final String THE_MODULE_WENT_QUIET = "no activation request of theirs left at the cluster";

  /**
   * The warning of the drain when the grace period ran out while the cluster still owed it an
   * answer. The answer is for a request which one of the closed workers had parked.
   * <p>
   * This test accepts that ending too. So it stays the twin of the Spring Boot half. The
   * javadoc of {@code Camunda8RestartDeliveryIT#SHUTDOWN_GRACE} has the measurement. The wait
   * ends when the cluster answers the parked requests. So demanding the quiet ending would be a
   * claim about the speed of the machine the test runs on. This gives up nothing about the
   * adapter. The drain still runs before the client is closed, and it still says what it did.
   * The delivery in the name of this test is asserted either way. But the two endings are not
   * equally good. This one is the drain's own warning. So the assertions above name which one
   * they saw.
   */
  private static final String THE_CLUSTER_STILL_OWED_AN_ANSWER = "still holding an activation request at the cluster";

  /**
   * The warning of the drain, once per job, about a handler which was still running when the
   * grace period passed. No run of this test may contain it. The one workflow of this test is
   * served long before the application stops. So a handler which is cut off is a defect of the
   * adapter, not a slow cluster.
   */
  private static final String A_HANDLER_WAS_CUT_OFF = "is being cut off";

  /**
   * @param logged What the application wrote
   * @return Where the drain reported about its module, whichever of its two endings it
   *         wrote, or -1 if it reported nothing
   */
  private static int whereTheDrainReported(
      final String logged) {

    final var wentQuietAt = logged.indexOf(THE_MODULE_WENT_QUIET);
    return wentQuietAt >= 0
        ? wentQuietAt
        : logged.indexOf(THE_CLUSTER_STILL_OWED_AN_ANSWER);

  }

  /**
   * Names the ending the drain took. So a failing assertion says which of the two it read,
   * and a later reader does not mix them up.
   *
   * @param logged What the application wrote
   * @return The ending, as a half sentence about the drain
   */
  private static String whichEndingTheDrainTook(
      final String logged) {

    if (logged.contains(THE_MODULE_WENT_QUIET)) {
      return "ended quiet, which is the normal case";
    }
    if (logged.contains(THE_CLUSTER_STILL_OWED_AN_ANSWER)) {
      return "ran out of its grace with a request still parked at the cluster, which is its own warning and "
          + "accepted here";
    }
    return "wrote neither of its two endings";

  }

  /**
   * How a line about the shutdown is recognized: by the words the drain reports with, and by
   * the line the client factory writes when the client goes down.
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
