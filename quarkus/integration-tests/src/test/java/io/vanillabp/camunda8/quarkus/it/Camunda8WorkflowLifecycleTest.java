package io.vanillabp.camunda8.quarkus.it;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;

import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The Camunda 8 adapter's documented features, run end to end on a BOOTED Quarkus
 * application against a real cluster.
 * <p>
 * This duplicates what the Spring Boot integration tests prove, and the duplication is the
 * point. A correct platform-neutral core of the adapter says nothing about whether the glue
 * code of a platform ever calls it. That is exactly why coverage is measured per platform. The
 * core lines which Quarkus never reaches name the features which Quarkus never runs. The
 * features are: deploying a workflow module, starting a workflow through the two-phase
 * outbox, having a task delivered, completing and cancelling one, notifying about user tasks,
 * correlating a message, broadcasting a signal, pushing a changed aggregate, matching process
 * versions and reading a workflow through the viewer.
 * <p>
 * Everything is observed through the application's own <code>introspect/...</code> endpoints,
 * because a prod-mode test runs the application in a forked JVM. The JaCoCo agent is passed on
 * into that JVM. Otherwise the run would prove the features but count as nothing.
 * <p>
 * One cluster carries all of it. A prod-mode test boots its application once per test class.
 * On Camunda 8 that boot needs a cluster, because the adapter serves no cluster which it
 * cannot search. So this is ONE class with many tests on purpose, not a class per feature. The
 * Spring Boot module pays for one cluster per class. This one pays for one in total.
 * <p>
 * Three things of the Spring Boot suite are NOT repeated here, on purpose:
 * <ul>
 * <li>the startup check for old process versions needs several boots against one cluster,
 * each with a different model. A prod-mode test boots its application once per test class.
 * The Camunda 7 adapter wrote down the same reason;</li>
 * <li>authentication against a protected cluster and the shutdown drain each need a cluster
 * or a lifecycle of their own. That means a second container and a second boot;</li>
 * <li>the release line the build runs on answers {@code cancelUserTask}. So asserting it here
 * would need a test source per line. That belongs in the core's
 * {@code Camunda8ReleaseLineTest}.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8WorkflowLifecycleTest {

  private static final Logger LOG = LoggerFactory.getLogger(Camunda8WorkflowLifecycleTest.class);

  private static final String MODULE = "c8-e2e";

  /**
   * What the cluster needs to answer a command. Generous on purpose. The other integration
   * tests of this repository use 180 s, because shorter deadlines failed in a full build.
   */
  private static final long TIMEOUT_MS = 180_000;

  /**
   * How long a wait may go on before it says out loud what it is still missing.
   * <p>
   * A runner once printed nothing for three minutes. Afterwards nobody could read why a
   * user-task notification got lost. The deadline's message says what the state was at the
   * END. Nothing says whether it had been that way from the first second, or whether it moved
   * once and then stopped. A line every fifteen seconds turns the wait into a record. It costs
   * nothing in a green run, where no wait ever gets that long.
   */
  private static final long PROGRESS_INTERVAL_MS = 15_000;

  /**
   * What the query API needs on top. An exporter fills it. So everything read through it
   * arrives after the cluster already acted.
   */
  private static final long QUERY_TIMEOUT_MS = 240_000;

  // --- the cluster under test ---

  static final GenericContainer<?> CAMUNDA = ClusterUnderTest.cluster("lifecycle-cluster");

  /*
   * The cluster is started HERE, not by the Testcontainers extension. The application's
   * configuration needs the mapped ports. A prod-mode test reads its runtime properties while
   * the field below is initialized, and that happens before any extension callback runs. Ryuk
   * removes what is left when this JVM exits.
   */
  static {
    CAMUNDA.start();
  }

  private static final int HTTP_PORT = OneFreePortPerJvm.getPort();

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      // the lines below are the deployment, not the directory. Classes travel by package.
      // Every resource needs a line of its own. So a BPMN file added to src/main/resources
      // reaches this application only once it is listed here
      .withApplicationRoot(jar -> jar
          .addPackage("io.vanillabp.camunda8.quarkus.test")
          .addAsResource("application.yaml")
          .addAsResource("c8-e2e/processes/task-matrix.bpmn")
          .addAsResource("c8-e2e/processes/multi-instance.bpmn")
          .addAsResource("c8-e2e/processes/signal-catch.bpmn")
          .addAsResource("c8-e2e/processes/timer-start.bpmn")
          .addAsResource("c8-e2e/processes/versioned-process.bpmn")
          .addAsResource("c8-e2e/processes/aggregate-changed.bpmn")
          .addAsResource("c8-e2e/processes/connector-process.bpmn")
          .addAsResource("c8-e2e/processes/multi-instance-call.bpmn")
          .addAsResource("c8-e2e/processes/multi-instance-called.bpmn")
          // the test deploys it WHILE the application runs. So it must travel with the
          // application, but must not sit in the workflow module's resources location
          .addAsResource("c8-e2e/versioned/versioned-process-v2.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // JVM args needed to track coverage. This module's POM sets the
      // systemPropertyVariables which feed 'jacoco.agent'
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      .setRun(true)
      .setRuntimeProperties(Map
          .of(
              "quarkus.http.port",
              Integer.toString(HTTP_PORT),
              // only the test knows the ports Testcontainers mapped
              "vanillabp.adapters.c8.rest-address",
              "http://%s:%d".formatted(CAMUNDA.getHost(), CAMUNDA.getMappedPort(8080)),
              "vanillabp.adapters.c8.grpc-address",
              "http://%s:%d".formatted(CAMUNDA.getHost(), CAMUNDA.getMappedPort(26500)),
              // the application runs in a forked JVM. So a failure inside it can be read
              // afterwards only in its own log
              "quarkus.log.file.enable",
              "true",
              "quarkus.log.file.path",
              Path
                  .of("target", "c8-e2e-application.log")
                  .toAbsolutePath()
                  .toString()));

  // --- talking to the application ---

  private static RequestSpecification api() {

    return RestAssured
        .given()
        .baseUri("http://localhost")
        .port(HTTP_PORT);

  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> post(
      final String path) {

    return api()
        .post(path)
        .then()
        .statusCode(200)
        .extract()
        .as(Map.class);

  }

  private static void postWithoutResponse(
      final String path) {

    api()
        .post(path)
        .then()
        .statusCode(204);

  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(
      final String path) {

    return api()
        .get(path)
        .then()
        .statusCode(200)
        .extract()
        .as(Map.class);

  }

  private static List<String> strings(
      final String path) {

    return api()
        .get(path)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("$", String.class);

  }

  private static String text(
      final String path) {

    return api()
        .get(path)
        .then()
        .statusCode(200)
        .extract()
        .asString();

  }

  private static void await(
      final Supplier<Boolean> condition,
      final long timeoutMillis,
      final String description) throws InterruptedException {

    await(condition, timeoutMillis, description, () -> null);

  }

  /**
   * Waits for a condition. If it never becomes true, it says what it saw while waiting.
   * <p>
   * A timeout in a runner is hard to read if its message only holds its own description. The
   * run is gone, and "the notification never came" leaves every explanation open. So a caller
   * whose wait can fail on a machine nobody watches hands over a second supplier. That
   * supplier is asked once, at the deadline.
   *
   * @param condition What is waited for
   * @param timeoutMillis How long to wait
   * @param description What is waited for, as the message says it
   * @param whatWasSeen The state at the deadline, or <code>null</code> to report none
   */
  private static void await(
      final Supplier<Boolean> condition,
      final long timeoutMillis,
      final String description,
      final Supplier<String> whatWasSeen) throws InterruptedException {

    final var startedAt = System.currentTimeMillis();
    final var deadline = startedAt + timeoutMillis;
    var nextProgressAt = startedAt + PROGRESS_INTERVAL_MS;
    while (!Boolean.TRUE.equals(condition.get())) {
      final var now = System.currentTimeMillis();
      if (now > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description
            + " after "
            + secondsSince(startedAt)
            + whatWasSeenOrWhyNot(whatWasSeen));
      }
      if (now >= nextProgressAt) {
        LOG.info("still waiting for: {} after {}{}",
            description,
            secondsSince(startedAt),
            whatWasSeenOrWhyNot(whatWasSeen));
        nextProgressAt = now + PROGRESS_INTERVAL_MS;
      }
      Thread.sleep(250);
    }

  }

  /**
   * @param startedAt When the wait began
   * @return How long it has been waiting, in seconds
   */
  private static String secondsSince(
      final long startedAt) {

    return (System.currentTimeMillis() - startedAt) / 1000
        + " s";

  }

  private static void await(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    await(condition, TIMEOUT_MS, description);

  }

  /**
   * What the timeout appends to its message, or nothing.
   * <p>
   * Reading the state can run into the same trouble as the wait. A diagnosis which fails must
   * not replace the timeout it was written to explain. An assertion is caught along with the
   * exceptions, because a REST client raises one for an unexpected status.
   *
   * @param whatWasSeen The state at the deadline
   * @return The sentence to append
   */
  private static String whatWasSeenOrWhyNot(
      final Supplier<String> whatWasSeen) {

    try {
      final var seen = whatWasSeen.get();
      return seen == null
          ? ""
          : ", having seen "
              + seen;
    } catch (final Exception | AssertionError e) {
      return ", and what it saw could not be read either: "
          + e;
    }

  }

  /**
   * Five seconds is more than the outbox needs to dispatch (poll interval 0.5 s). It is also
   * more than the cluster needs to hand out a job. So "nothing happened" is a fact, not a
   * race.
   */
  private static void awaitNothingElseHappens() throws InterruptedException {

    Thread.sleep(5000);

  }

  // --- the workflows the tests drive ---

  private static String startPrimaryWorkflow() {

    return post("introspect/workflows")
        .get("id")
        .toString();

  }

  private static Map<String, Object> startProcess(
      final String bpmnProcessId) {

    return post("introspect/processes/"
        + bpmnProcessId);

  }

  private static String aggregateIdOf(
      final Map<String, Object> started) {

    return started
        .get("id")
        .toString();

  }

  private static String instanceKeyOf(
      final Map<String, Object> started) {

    return started
        .get("processInstanceKey")
        .toString();

  }

  private static String resultsOf(
      final String aggregateId) {

    final var value = object("introspect/aggregates/"
        + aggregateId)
        .get("results");
    return value == null
        ? null
        : value.toString();

  }

  private static String taskIdOf(
      final String aggregateId) {

    final var value = object("introspect/aggregates/"
        + aggregateId)
        .get("taskId");
    return value == null
        ? null
        : value.toString();

  }

  /**
   * The events the handlers reported into the aggregate. A repeated event counts only once,
   * at its first place.
   * <p>
   * Camunda 8 delivers at least once. The core drops a repetition with the same job key, but
   * nothing drops one with a new job key. So an assertion about WHICH events reached the
   * aggregate must not fail because of how often one of them did. The inbound-idempotency
   * tests measure how often.
   *
   * @param aggregateId The aggregate the events were reported into
   * @return The reported events in the order they arrived
   */
  private static List<String> reportedEventsWithoutRepetitions(
      final String aggregateId) {

    final var results = resultsOf(aggregateId);
    return results == null
        ? List.of()
        : Arrays
            .stream(results.split("\\|"))
            .distinct()
            .toList();

  }

  /**
   * Everything the CREATED notification of a user task depends on, in one line: what the
   * handler wrote into the aggregate, how often it ran at all, and which user tasks and jobs
   * the cluster itself holds for the workflow.
   * <p>
   * The jobs show where a missing notification was lost. The notification IS a listener job.
   * If the cluster still reports the job as <code>CREATED</code>, it was there to be fetched,
   * and no worker fetched it. If the listener job of a workflow is gone, it was served, and
   * the notification was lost on this side. A job reported as <code>FAILED</code> was failed
   * by a command. The retries and the error message printed with it say who sent that
   * command.
   * <p>
   * The incidents are the third place where a notification can end. A listener job which is
   * failed with no retry left raises one. The user task then stays in <code>CREATING</code>
   * until an operator resolves it.
   *
   * @param aggregateId The aggregate the notification is awaited for
   * @param processInstanceKey The workflow the user task belongs to
   * @return What a timeout should report
   */
  private static String userTaskNotificationState(
      final String aggregateId,
      final String processInstanceKey) {

    return ("results '%s', task id '%s' and %d invocation(s) of 'approveUser', while the cluster reports "
        + "the user tasks %s, the jobs %s and the incidents %s for process instance %s")
        .formatted(
            resultsOf(aggregateId),
            taskIdOf(aggregateId),
            invocations("approveUser", aggregateId),
            clusterUserTasksOf(processInstanceKey),
            clusterJobsOf(processInstanceKey),
            clusterIncidentsOf(processInstanceKey),
            processInstanceKey);

  }

  /**
   * The user tasks the cluster holds, or why they could not be read. It has a guard of its
   * own, not only the caller's guard. So a cluster which cannot answer still leaves the
   * application's half of the message readable.
   *
   * @param processInstanceKey The workflow to ask about
   * @return The user-task keys, or what went wrong instead
   */
  private static String clusterUserTasksOf(
      final String processInstanceKey) {

    try {
      return strings("introspect/cluster/user-tasks/"
          + processInstanceKey).toString();
    } catch (final Exception | AssertionError e) {
      return "<unreadable: %s>".formatted(e);
    }

  }

  /**
   * The jobs the cluster holds for a workflow, or why they could not be read. Guarded
   * like the user tasks, for the same reason.
   *
   * @param processInstanceKey The workflow to ask about
   * @return One entry per job, or what went wrong instead
   */
  private static String clusterJobsOf(
      final String processInstanceKey) {

    try {
      return strings("introspect/cluster/jobs/"
          + processInstanceKey).toString();
    } catch (final Exception | AssertionError e) {
      return "<unreadable: %s>".formatted(e);
    }

  }

  /**
   * What the cluster is waiting for an operator about, or why it could not be read.
   * Guarded like the jobs, for the same reason.
   *
   * @param processInstanceKey The workflow to ask about
   * @return One entry per incident, or what went wrong instead
   */
  private static String clusterIncidentsOf(
      final String processInstanceKey) {

    try {
      return strings("introspect/cluster/incidents/"
          + processInstanceKey).toString();
    } catch (final Exception | AssertionError e) {
      return "<unreadable: %s>".formatted(e);
    }

  }

  private static int invocations(
      final String taskDefinition,
      final String aggregateId) {

    return Integer.parseInt(text("introspect/invocations/%s/%s".formatted(taskDefinition, aggregateId)));

  }

  private static String instanceState(
      final String processInstanceKey) {

    return text("introspect/cluster/instance-state/"
        + processInstanceKey);

  }

  private static String awaitParkedTask(
      final String aggregateId) throws InterruptedException {

    await(() -> taskIdOf(aggregateId) != null, "the handler to park the task of aggregate "
        + aggregateId);
    return taskIdOf(aggregateId);

  }

  /**
   * Waits until the query API knows the workflow. Every probe of workflow awareness reads it
   * from there. The exporter which fills it runs behind the cluster.
   *
   * @param aggregateId The aggregate
   * @return The instance key the query API reports
   */
  private static String awaitKnownToTheQueryApi(
      final String aggregateId) throws InterruptedException {

    await(
        () -> !text("introspect/cluster/instance-key/"
            + aggregateId).isEmpty(),
        QUERY_TIMEOUT_MS,
        "the query API to know the workflow of aggregate "
            + aggregateId);
    return text("introspect/cluster/instance-key/"
        + aggregateId);

  }

  // --- deployment ---

  @Test
  @DisplayName("The boot deploys every model of the resources location under the module's prefix")
  public void theBootDeploysTheWorkflowModule() throws Exception {

    assertEquals(MODULE, text("introspect/workflow-module"));

    // what the cluster holds is read through the query API. An exporter fills it
    await(
        () -> strings("introspect/cluster/definitions").size() >= 6,
        QUERY_TIMEOUT_MS,
        "the query API to know the deployed process definitions");
    final var definitions = strings("introspect/cluster/definitions");
    assertTrue(
        definitions
            .stream()
            .anyMatch(definition -> definition.startsWith("%s__TaskProcess|".formatted(MODULE))),
        "the primary process is deployed under the workflow module's prefix, but got: "
            + definitions);
    assertTrue(
        definitions
            .stream()
            .allMatch(definition -> definition.startsWith("%s__".formatted(MODULE))),
        "with name-clash-avoidance 'use-prefix' no identifier may reach the cluster unscoped: "
            + definitions);
    // every BPMN below the resources location is deployed, also the ones nobody starts
    for (final var bpmnProcessId : List
        .of("SignalCatchProcess", "MultiInstanceProcess", "TimerStartProcess", "AggregateChangedProcess")) {
      assertTrue(
          definitions
              .stream()
              .anyMatch(definition -> definition.startsWith("%s__%s|".formatted(MODULE, bpmnProcessId))),
          bpmnProcessId
              + " is missing from "
              + definitions);
    }

  }

  @Test
  @DisplayName("The job timeout and the retry backoff resolve through all four configuration levels")
  public void theConfigurationResolvesThroughAllFourLevels() {

    // task level (most specific)
    final var task = object("introspect/config/%s/TaskProcess/happyTask".formatted(MODULE));
    assertEquals("PT2S", task.get("jobTimeout"));
    assertEquals("PT2S", task.get("retryBackoff"));
    // workflow level
    final var workflow = object("introspect/config/%s/TaskProcess/errorTask".formatted(MODULE));
    assertEquals("PT10S", workflow.get("jobTimeout"));
    assertEquals("PT10S", workflow.get("retryBackoff"));
    // workflow-module level
    final var module = object("introspect/config/%s/RetryProcess/retryTask".formatted(MODULE));
    assertEquals("PT20S", module.get("jobTimeout"));
    assertEquals("PT20S", module.get("retryBackoff"));
    // adapter level (the base everything falls back to)
    final var adapter = object("introspect/config/unknown-module/SomeProcess/someTask");
    assertEquals("PT30S", adapter.get("jobTimeout"));
    assertEquals("PT30S", adapter.get("retryBackoff"));

  }

  // --- starting a workflow ---

  @Test
  @DisplayName("startWorkflow runs the workflow, and a TaskException routes through the error boundary")
  public void startWorkflowRunsThroughTheErrorBoundary() throws Exception {

    final var aggregateId = startPrimaryWorkflow();

    await(
        () -> {
          final var results = resultsOf(aggregateId);
          return (results != null) && results.contains("handled");
        },
        "TaskProcess to converge through the error boundary");

    // the change of the throwing handler is committed, although it threw. A TaskException
    // is a BPMN error, not a rollback
    assertEquals("happy|error-raised|handled", resultsOf(aggregateId));

  }

  @Test
  @DisplayName("A rolled-back start creates nothing - the workflow is created by the phase-two outbox")
  public void aRolledBackStartCreatesNothing() throws Exception {

    final var aggregateId = post("introspect/workflows/rollback")
        .get("id")
        .toString();

    awaitNothingElseHappens();
    assertEquals(
        Boolean.FALSE,
        object("introspect/aggregates/"
            + aggregateId).get("exists"),
        "the aggregate was rolled back");
    assertEquals(
        0,
        invocations("happyTask", aggregateId),
        "the outbox record was written in the same transaction, so no workflow may have run");

  }

  @Test
  @DisplayName("A connector element is left to its own runtime, unprefixed and unserved")
  public void aConnectorElementIsLeftToItsOwnRuntime() throws Exception {

    final var aggregateId = aggregateIdOf(startProcess("ConnectorProcess"));

    // no connector runtime runs here, so the job waits. That is the point. It waits under
    // the job type which the modeller wrote. Yet this workflow module runs with name-clash
    // avoidance 'use-prefix', and every job type of its own carries the module and the
    // process
    await(
        () -> !text("introspect/cluster/element-waiting-for-job-type/io.camunda:http-json:1").isEmpty(),
        "the cluster to hold a job of the connector's unprefixed job type");
    assertEquals(
        "Activity_Connector",
        text("introspect/cluster/element-waiting-for-job-type/io.camunda:http-json:1"),
        "the waiting job belongs to the connector element");

    // and the task behind the element was never reached. So nothing of this application
    // served the job
    Thread.sleep(5000);
    assertEquals(
        0,
        invocations("afterConnector", aggregateId),
        "no worker of this application may serve a job type belonging to a connector runtime");

  }

  // --- delivering a task ---

  @Test
  @DisplayName("A technical exception rolls the job back, decrements the retries and honours the backoff")
  public void aTechnicalExceptionRollsBackAndIsRetriedAfterTheBackoff() throws Exception {

    final var aggregateId = aggregateIdOf(startProcess("FailProcess"));

    await(() -> invocations("alwaysFails", aggregateId) >= 1, "the failing job to be delivered");
    // FailProcess is configured with 'retry-backoff: PT5S'. Without it, the cluster hands
    // out the job again within milliseconds. That used to use up all three retries before
    // the cause of the failure had any chance to pass
    Thread.sleep(3000);
    assertEquals(
        1,
        invocations("alwaysFails", aggregateId),
        "the job must not be redelivered while the backoff is still running");

    await(() -> invocations("alwaysFails", aggregateId) >= 2, "the failing job to be redelivered");
    final var gap = Long.parseLong(text("introspect/delivery-gap/alwaysFails/"
        + aggregateId));
    assertTrue(gap >= 4000, "expected at least the configured five seconds between two deliveries but saw "
        + gap);

    assertNull(
        resultsOf(aggregateId),
        "the handler's mutation was rolled back with the job's local transaction");

  }

  @Test
  @DisplayName("The task header of the model decides the backoff where nothing configures the task")
  public void theModelledBackoffReachesTheCluster() throws Exception {

    final var aggregateId = aggregateIdOf(startProcess("ModelledBackoffProcess"));
    // an application which comes from version 1 has models with their own backoff. Keeping
    // that value must not cost it a new process version

    await(() -> invocations("modelledBackoffFails", aggregateId) >= 1, "the failing job to be delivered");
    await(() -> invocations("modelledBackoffFails", aggregateId) >= 2, "the failing job to be handed out again");

    // the element models PT12S, while its workflow configures PT1S. So the time between two
    // deliveries shows which of the two the cluster was given. The long value is the
    // modelled one, on purpose. A busy cluster can stretch a gap but never shorten it. So
    // this assertion cannot fail on a slow machine
    final var gap = Long.parseLong(text("introspect/delivery-gap/modelledBackoffFails/"
        + aggregateId));
    assertTrue(gap >= 8000, "expected the twelve seconds the model asks for between two deliveries but saw "
        + gap
        + " ms, which is the second the configuration asks for");

  }

  @Test
  @DisplayName("A second delivery of the same task converges - at-least-once, proven")
  public void aRedeliveredTaskConverges() throws Exception {

    final var aggregateId = aggregateIdOf(startProcess("RetryProcess"));

    await(
        () -> {
          final var results = resultsOf(aggregateId);
          return (results != null) && results.contains("retried");
        },
        "RetryProcess to converge after a redelivery");

    assertTrue(invocations("retryTask", aggregateId) >= 2, "expected a second delivery but saw "
        + invocations("retryTask", aggregateId));
    // exactly one 'retried'. The second delivery converged idempotently, and the change of
    // the first delivery was rolled back
    assertEquals("retried", resultsOf(aggregateId));

  }

  @Test
  @DisplayName("A @TaskId handler parks the job, its lock is renewed, and completeTask ends the workflow")
  public void asyncTaskStaysDormantAndIsCompletedAfterTheCommit() throws Exception {

    final var started = startProcess("AsyncProcess");
    final var aggregateId = aggregateIdOf(started);
    final var taskId = awaitParkedTask(aggregateId);
    assertEquals("async-open", resultsOf(aggregateId));

    // the task's job timeout is PT2S. Without the lock renewal of a dormant job, the
    // cluster would hand out the job again within this time
    Thread.sleep(8000);
    assertEquals(1, invocations("asyncTask", aggregateId), "the dormant job must not be re-invoked");

    assertEquals(Map.of(), post("introspect/tasks/%s/complete/%s".formatted(taskId, aggregateId)));

    await(
        () -> "COMPLETED".equals(instanceState(instanceKeyOf(started))),
        QUERY_TIMEOUT_MS,
        "AsyncProcess to end after the commit");

  }

  @Test
  @DisplayName("completeTask inside a rolled-back transaction leaves the job where it was")
  public void completeTaskInARolledBackTransactionChangesNothing() throws Exception {

    final var started = startProcess("AsyncProcess");
    final var aggregateId = aggregateIdOf(started);
    final var taskId = awaitParkedTask(aggregateId);

    post("introspect/tasks/%s/complete-and-rollback/%s".formatted(taskId, aggregateId));

    awaitNothingElseHappens();
    assertEquals(
        1,
        invocations("asyncTask", aggregateId),
        "a rolled-back completion may not advance the workflow");
    assertEquals("async-open", resultsOf(aggregateId), "and it may not commit the handler's changes either");

  }

  @Test
  @DisplayName("A completion arriving after the job is gone raises the guiding TaskNotFoundException")
  public void aStaleCompletionRaisesTheGuidingException() throws Exception {

    final var aggregateId = aggregateIdOf(startProcess("AsyncProcess"));
    final var taskId = awaitParkedTask(aggregateId);

    // the job is completed OUTSIDE VanillaBP. A concurrent completion looks like this
    postWithoutResponse("introspect/cluster/jobs/%s/complete".formatted(taskId));

    final var failed = post("introspect/tasks/%s/complete/%s".formatted(taskId, aggregateId));
    assertEquals(
        "TaskNotFoundException",
        failed.get("rootException"),
        "expected the documented exception but got: "
            + failed);

  }

  @Test
  @DisplayName("cancelTask throws the BPMN error and the boundary path runs")
  public void cancelTaskRoutesTheBpmnError() throws Exception {

    final var aggregateId = aggregateIdOf(startProcess("AsyncCancelProcess"));
    final var taskId = awaitParkedTask(aggregateId);

    post("introspect/tasks/%s/cancel/%s/PAYMENT_FAILED".formatted(taskId, aggregateId));

    await(
        () -> {
          final var results = resultsOf(aggregateId);
          return (results != null) && results.contains("cancel-handled");
        },
        "AsyncCancelProcess to end through the error boundary");
    assertEquals("await-cancel|cancel-handled", resultsOf(aggregateId));

  }

  @Test
  @DisplayName("Multi-instance: element, index and total are bound, the outer iteration included")
  public void multiInstanceBindsElementIndexAndTotal() throws Exception {

    final var aggregateId = aggregateIdOf(startProcess("MultiInstanceProcess"));

    await(
        () -> "a#0/2,b#1/2".equals(object("introspect/aggregates/"
            + aggregateId).get("flat")),
        "the flat multi-instance task to report every iteration");

    await(
        () -> {
          final var nested = object("introspect/aggregates/"
              + aggregateId).get("nested");
          return (nested != null) && (nested.toString().split(",").length == 6);
        },
        "the nested multi-instance task to report all six iterations");

    // Camunda 8 hides the OUTER iteration from a nested task. The adapter makes the group
    // readable again in which the iteration ran
    assertEquals(
        "g1#0/2-x#0/3,g1#0/2-y#1/3,g1#0/2-z#2/3,g2#1/2-x#0/3,g2#1/2-y#1/3,g2#1/2-z#2/3",
        object("introspect/aggregates/"
            + aggregateId).get("nested"));

  }

  @Test
  @DisplayName("A task in a called process runs in the iteration of its caller, two levels down as well")
  public void multiInstanceCrossesTheCallActivity() throws Exception {

    final var aggregateId = aggregateIdOf(startProcess("MiCallProcess"));

    await(
        () -> {
          final var reported = object("introspect/aggregates/"
              + aggregateId).get("twoLevelsDown");
          return (reported != null) && (reported.toString().split(",").length == 2);
        },
        "both groups to have run through the called process and the one it calls");

    final var aggregate = object("introspect/aggregates/"
        + aggregateId);

    assertEquals(
        "g1#0/2,g2#1/2",
        aggregate.get("inCalledProcess"),
        "the subprocess around the call activity is in the caller, and the task is not");

    assertEquals(
        "g1#0/2-x#0/2,g1#0/2-y#1/2,g2#1/2-x#0/2,g2#1/2-y#1/2",
        aggregate.get("bothChains"),
        "a task which is multi-instance in the called process reports its own iteration and "
            + "the caller's");

    assertEquals(
        "MIC_PerGroup>MIC_ChildMiTask",
        aggregate.get("chainOrder"),
        "outermost first, and the outermost one belongs to another BPMN process");

    assertEquals(
        "g1#0/2,g2#1/2",
        aggregate.get("twoLevelsDown"),
        "two call activities away from the subprocess, the iteration is still reported");

  }

  // --- user tasks ---
  @Test
  @DisplayName("A user task notifies on creation and completeUserTask resumes the workflow")
  public void userTaskNotificationAndCompletion() throws Exception {

    final var started = startProcess("UserTaskProcess");
    final var aggregateId = aggregateIdOf(started);
    final var instanceKey = instanceKeyOf(started);

    // contains, not equals, as in the Spring Boot suite. A repeated notification appends a
    // second event. An exact comparison could then never become true again. That would turn
    // an accepted repetition into a timeout whose message is about something else entirely
    await(
        () -> {
          final var results = resultsOf(aggregateId);
          return (results != null) && results.contains("usertask-created");
        },
        TIMEOUT_MS,
        "the user task's CREATED notification",
        () -> userTaskNotificationState(aggregateId, instanceKey));
    final var taskId = taskIdOf(aggregateId);
    assertNotNull(taskId, "the notification carries the cluster's user-task key");

    post("introspect/user-tasks/%s/complete/%s".formatted(taskId, aggregateId));

    await(
        () -> "COMPLETED".equals(instanceState(instanceKey)),
        QUERY_TIMEOUT_MS,
        "UserTaskProcess to end after the commit");
    assertEquals(
        List.of("usertask-created"),
        reportedEventsWithoutRepetitions(aggregateId),
        "completing a user task must not be reported as an event of its own");

  }

  @Test
  @DisplayName("Canceling the workflow delivers CANCELED through the canceling task listener")
  public void userTaskCanceledWhenTheWorkflowIsCanceled() throws Exception {

    final var started = startProcess("UserTaskProcess");
    final var aggregateId = aggregateIdOf(started);
    final var instanceKey = instanceKeyOf(started);

    await(
        () -> {
          final var results = resultsOf(aggregateId);
          return (results != null) && results.contains("usertask-created");
        },
        TIMEOUT_MS,
        "the user task's CREATED notification",
        () -> userTaskNotificationState(aggregateId, instanceKey));

    postWithoutResponse("introspect/cluster/instances/%s/cancel".formatted(instanceKey));

    await(
        () -> {
          final var results = resultsOf(aggregateId);
          return (results != null) && results.contains("usertask-canceled");
        },
        "the canceling listener to deliver CANCELED");

  }

  @Test
  @DisplayName("A user task WITHOUT a handler notifies nobody and still shows up at the cluster")
  public void aUserTaskWithoutAHandlerStillWorks() throws Exception {

    final var started = startProcess("SilentUserTaskProcess");
    final var aggregateId = aggregateIdOf(started);

    await(
        () -> !strings("introspect/cluster/user-tasks/"
            + instanceKeyOf(started)).isEmpty(),
        QUERY_TIMEOUT_MS,
        "the user task nobody is notified about to show up");
    assertNull(resultsOf(aggregateId), "a task without a handler notifies nobody");

  }

  // --- messages ---

  @Test
  @DisplayName("correlateMessage resumes the waiting workflow, a rolled-back correlation does not")
  public void correlateMessageResumesTheWaitingWorkflow() throws Exception {

    final var aggregateId = aggregateIdOf(startProcess("MessageProcess"));
    // correlating checks workflow awareness, and that asks the query API
    awaitKnownToTheQueryApi(aggregateId);

    post("introspect/messages/C8PaymentReceived/correlate-and-rollback/"
        + aggregateId);
    awaitNothingElseHappens();
    assertEquals(
        0,
        invocations("c8MessageArrived", aggregateId),
        "a correlation in a rolled-back transaction never reaches the cluster");

    post("introspect/messages/C8PaymentReceived/correlate/"
        + aggregateId);

    await(
        () -> "message-arrived".equals(resultsOf(aggregateId)),
        "MessageProcess to continue after the correlation");

  }

  @Test
  @DisplayName("A correlation id which matches no subscription correlates nothing")
  public void aMismatchingCorrelationIdCorrelatesNothing() throws Exception {

    final var aggregateId = aggregateIdOf(startProcess("MessageProcess"));
    awaitKnownToTheQueryApi(aggregateId);

    // the subscription is injected as '=id'. So this one matches no workflow
    post("introspect/messages/C8PaymentReceived/correlate/%s/no-such-correlation".formatted(aggregateId));

    awaitNothingElseHappens();
    assertEquals(0, invocations("c8MessageArrived", aggregateId), "the workflow has to keep waiting");

    post("introspect/messages/C8PaymentReceived/correlate/%s/%s".formatted(aggregateId, aggregateId));
    await(() -> "message-arrived".equals(resultsOf(aggregateId)), "the matching correlation to resume the workflow");

  }

  @Test
  @DisplayName("startWorkflowByMessage starts the workflow through its message start event")
  public void startWorkflowByMessageStartsTheWorkflow() throws Exception {

    final var aggregateId = post("introspect/messages/C8OrderPlaced/start")
        .get("id")
        .toString();

    await(
        () -> "order-placed".equals(text("introspect/message-start/aggregates/%s/results".formatted(aggregateId))),
        "MessageStartProcess to run through");

  }

  @Test
  @DisplayName("startWorkflowByMessage refuses a message which starts another process")
  public void startWorkflowByMessageRefusesTheMessageOfAnotherProcess() {

    // 'C8OrderPlaced' starts MessageStartProcess. Publishing it names no process. So the
    // cluster would start that process for the aggregate of TaskProcess. The core refuses
    // it before phase one. It uses the message names which this adapter read from the model
    final var refused = post("introspect/messages/C8OrderPlaced/start-task-process");

    assertEquals("IllegalArgumentException", refused.get("rootException"), () -> "but got: "
        + refused);
    assertTrue(
        String
            .valueOf(refused.get("rootMessage"))
            .contains("Message 'C8OrderPlaced' does not start BPMN process 'TaskProcess'"),
        () -> "the message names the message and the process of the caller: "
            + refused);

  }

  @Test
  @DisplayName("Correlating a workflow nobody started raises the guiding WorkflowNotFoundException")
  public void correlatingAnUnknownWorkflowRaisesTheGuidingException() {

    final var aggregateId = post("introspect/aggregates")
        .get("id")
        .toString();

    final var failed = post("introspect/messages/C8PaymentReceived/correlate/"
        + aggregateId);
    assertEquals("WorkflowNotFoundException", failed.get("rootException"), "but got: "
        + failed);

  }

  // --- signals ---

  @Test
  @DisplayName("A broadcast signal continues the waiting workflow")
  public void sendSignalContinuesTheWaitingWorkflow() throws Exception {

    final var aggregateId = aggregateIdOf(startProcess("SignalCatchProcess"));
    awaitKnownToTheQueryApi(aggregateId);

    postWithoutResponse("introspect/signals/OrderReceived");

    await(() -> "signal-received".equals(resultsOf(aggregateId)), "the broadcast to continue the waiting workflow");

  }

  // --- pushing a changed aggregate ---

  @Test
  @DisplayName("A global push updates the workflow's own scope")
  public void aGlobalPushWritesTheWorkflowScope() throws Exception {

    final var aggregateId = post("introspect/push/workflows")
        .get("id")
        .toString();

    await(
        () -> object("introspect/push-aggregates/"
            + aggregateId).get("taskIds") != null,
        "the workflow to park at its asynchronous task");
    final var processInstanceKey = awaitKnownToTheQueryApi(aggregateId);

    postWithoutResponse("introspect/push/%s/global/pushed-globally".formatted(aggregateId));

    await(
        () -> notesOf(processInstanceKey)
            .stream()
            .anyMatch(note -> note.contains("pushed-globally")),
        QUERY_TIMEOUT_MS,
        "the pushed value to arrive at the cluster");

    final var notes = notesOf(processInstanceKey);
    assertEquals(1, notes.size(), "a global push updates the existing variable instead of adding a scope");
    assertTrue(
        notes
            .getFirst()
            .startsWith(processInstanceKey
                + "|"),
        "the value has to live at the workflow's own scope but sits at: "
            + notes);

  }

  @Test
  @DisplayName("A task-scoped push lands in the scope the task runs in, not at the workflow's")
  public void aTaskScopedPushReachesTheEnclosingScope() throws Exception {

    final var started = startPushProcess("AggregateChangedMultiInstanceProcess");
    final var aggregateId = aggregateIdOf(started);
    final var processInstanceKey = instanceKeyOf(started);

    await(
        () -> {
          final var taskIds = object("introspect/push-aggregates/"
              + aggregateId).get("taskIds");
          return (taskIds != null) && (taskIds.toString().split(",").length == 2);
        },
        "both iterations of the multi-instance subprocess to park");
    final var taskId = object("introspect/push-aggregates/"
        + aggregateId)
        .get("taskIds")
        .toString()
        .split(",")[0];
    // the job has to be known to the query API before its scope can be looked up
    await(
        () -> !text("introspect/cluster/element-instance-of-job/"
            + taskId).isEmpty(),
        QUERY_TIMEOUT_MS,
        "the query API to know the parked job");
    final var elementInstanceKey = text("introspect/cluster/element-instance-of-job/"
        + taskId);

    postWithoutResponse("introspect/push/%s/task/%s/pushed-locally".formatted(aggregateId, taskId));

    await(
        () -> notesOf(processInstanceKey)
            .stream()
            .anyMatch(note -> note.contains("pushed-locally")),
        QUERY_TIMEOUT_MS,
        "the pushed value to arrive at the cluster");

    final var pushed = notesOf(processInstanceKey)
        .stream()
        .filter(note -> note.contains("pushed-locally"))
        .toList();
    assertEquals(1, pushed.size(), "exactly one scope may have received the push: "
        + pushed);
    assertFalse(
        pushed
            .getFirst()
            .startsWith(processInstanceKey
                + "|"),
        "a task-scoped push may not land at the workflow's own scope: "
            + pushed);
    assertFalse(
        pushed
            .getFirst()
            .startsWith(elementInstanceKey
                + "|"),
        "and not at the activity's own scope either - the scope meant is the one the task RUNS in: "
            + pushed);

  }

  private static Map<String, Object> startPushProcess(
      final String bpmnProcessId) {

    return post("introspect/push/processes/"
        + bpmnProcessId);

  }

  private static List<String> notesOf(
      final String processInstanceKey) {

    return strings("introspect/cluster/variables/%s/note".formatted(processInstanceKey));

  }

  // --- process versions ---

  @Test
  @DisplayName("The version of the deployed process definition decides which method serves the task")
  public void theDeployedVersionDecidesWhichMethodServesTheTask() throws Exception {

    // the application deployed version 1 while booting. A version made of numbers is
    // compared to what the job carries. So nothing is asked of the cluster
    final var first = aggregateIdOf(startProcess("VersionedProcess"));
    await(() -> "firstVersion".equals(resultsOf(first)), "the method naming version 1 to serve the task");

    postWithoutResponse("introspect/cluster/deploy-version-two");
    // an exporter fills the query API, so the tag arrives there a moment later. And a job
    // of a version which no method serves would use up its retries
    await(
        () -> Boolean.parseBoolean(text("introspect/cluster/tagged-version-known")),
        QUERY_TIMEOUT_MS,
        "the query API to know the tagged version");

    final var second = aggregateIdOf(startProcess("VersionedProcess"));
    await(
        () -> "taggedVersion".equals(resultsOf(second)),
        "the method naming the version tag of version 2 to serve the task - the version this "
            + "application never deployed itself");

  }

  // --- a workflow the cluster starts on its own ---

  @Test
  @DisplayName("A timer start event creates the aggregate, the task finds it and the end is reported")
  public void theClusterStartsAWorkflowOnItsOwn() throws Exception {

    // the timer fires one second after the deployment
    await(() -> !strings("introspect/timer-aggregates").isEmpty(), "the timer to fire and the aggregate to be created");

    await(
        () -> strings("introspect/timer-aggregates")
            .stream()
            .anyMatch(reported -> reported.contains("|recordTimerStart|") && reported.contains("|COMPLETED")),
        "the task following the timer start event to run and the end to be reported, but got: "
            + strings("introspect/timer-aggregates"));

  }

  // --- what a completed job pushes ---

  @Test
  @DisplayName("A gateway right after a @WorkflowTask sees the values that task produced")
  public void aGatewayAfterATaskSeesTheValuesItProduced() throws Exception {

    final var aggregateId = aggregateIdOf(startProcess("SyncProcess"));

    await(
        () -> {
          final var results = resultsOf(aggregateId);
          return (results != null) && (results.contains("sync-approved") || results.contains("sync-rejected"));
        },
        "SyncProcess to pass the FEEL gateway");

    // the gateway's condition '=approved = true' branched on the value which the
    // @WorkflowTask method produced. Without the push it would have taken the default
    // (rejected) flow
    assertEquals("sync-task|sync-approved", resultsOf(aggregateId));

    final var observed = object("introspect/observed-variables");
    assertEquals("true", observed.get("approved"));
    assertEquals("sync-task", observed.get("results"));
    assertEquals(
        "null",
        observed.get("secret"),
        "a @NoSyncWithBPMS attribute must never appear in the cluster's variables");

  }

  @Test
  @DisplayName("A @TaskParam is delivered although its variable appears in no model and nothing is configured")
  public void declaredTaskParametersAreFetched() throws Exception {

    final var payloadSize = 32768;
    final var aggregateId = post("introspect/processes/FetchProcess?payloadSize="
        + payloadSize)
        .get("id")
        .toString();

    // the FIRST task configures nothing, and the declared name reaches its worker
    await(() -> invocations("fetchAllTask", aggregateId) >= 1, "the first task to be delivered");
    // the SECOND task configures nothing either, and its worker asks for 'bigPayload' too.
    // The core read the name from the method while wiring
    await(() -> invocations("fetchDerivedTask", aggregateId) >= 1, "the task fetching the derived list");
    await(() -> "fetch-all|fetch-derived".equals(resultsOf(aggregateId)), "both tasks to have committed");

    final var observed = object("introspect/observed-variables");
    assertEquals(String.valueOf(payloadSize), observed.get("bigPayloadLength"));
    assertEquals(
        String.valueOf(payloadSize),
        observed.get("derivedPayloadLength"),
        "the variable stands in no model and no property was set - the annotation alone brought it");

  }

  // --- the viewer ---

  @Test
  @DisplayName("The viewer serves the deployed model, its history and a guiding error for an unknown one")
  public void theViewerServesTheDeployedModelAndItsHistory() throws Exception {

    final var aggregateId = startPrimaryWorkflow();
    await(() -> resultsOf(aggregateId) != null, "TaskProcess to run");
    awaitKnownToTheQueryApi(aggregateId);

    final var definitions = definitionsOf(aggregateId);
    assertEquals(1, definitions.size(), "expected the deployed definition but got: "
        + definitions);
    final var definition = definitions.getFirst();
    assertTrue(definition.startsWith("c8#"), "process definition ids are namespaced per adapter id: "
        + definition);
    // the viewer reports the BPMN process id which the APPLICATION knows, not the prefixed
    // one the cluster holds. Name-clash avoidance is the adapter's business
    assertTrue(definition.contains("|TaskProcess|"), definition);
    assertTrue(definition.endsWith("|null"), "the primary definition is used by no element: "
        + definition);

    final var definitionId = definition.substring(0, definition.indexOf('|'));
    final var xml = api()
        .queryParam("id", definitionId)
        .get("introspect/viewer/xml")
        .then()
        .statusCode(200)
        .extract()
        .asString();
    assertTrue(xml.contains("TaskProcess"), "the BPMN XML has to contain the process but got: "
        + xml);
    assertTrue(xml.contains("happyTask"), "the XML is the model AS DEPLOYED, VanillaBP's wiring included");

    // the history is read by a search, and the exporter fills it. So the elements arrive a
    // moment after the workflow
    await(
        () -> {
          final var history = object("introspect/viewer/history/"
              + aggregateId);
          return history.get("elements") != null;
        },
        QUERY_TIMEOUT_MS,
        "the query API to serve the element history");
    final var history = object("introspect/viewer/history/"
        + aggregateId);
    assertNotNull(history.get("processDefinitionId"));
    assertEquals(Boolean.TRUE, history.get("started"));

    @SuppressWarnings("unchecked")
    final Map<String, Object> failed = api()
        .queryParam("id", "c8#123456789")
        .get("introspect/viewer/unknown-xml")
        .then()
        .statusCode(200)
        .extract()
        .as(Map.class);
    assertEquals("ProcessDefinitionNotFoundException", failed.get("rootException"), "but got: "
        + failed);

  }

  @SuppressWarnings("unchecked")
  private static List<String> definitionsOf(
      final String aggregateId) {

    return (List<String>) object("introspect/viewer/definitions/"
        + aggregateId)
        .get("definitions");

  }

}
