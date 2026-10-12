package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.vanillabp.camunda8.Camunda8ReleaseLine;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.camunda8.processservice.Camunda8ProcessService;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.camunda8.springboot.client.VanillaBpCamunda8Properties;
import io.vanillabp.camunda8.test.ClusterLog;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.delivery.TaskDeliveryLogReader;
import io.vanillabp.integration.test.utils.delivery.TaskDeliveryLogReader.Delivery;
import io.vanillabp.spi.process.TaskNotFoundException;

/**
 * Task processing against a REAL Camunda 8 broker (Testcontainers):
 * <ul>
 * <li>happy path through the adapter's polling job workers incl. redelivery
 * convergence (the first invocation blocks beyond the task's job timeout - the
 * redelivered job converges idempotently, the duplicate completion is
 * tolerated);</li>
 * <li>{@code TaskException} - BPMN error with error-boundary routing, the
 * throwing handler's aggregate changes committed;</li>
 * <li>technical exception - job failed with decremented retries, local
 * transaction rolled back;</li>
 * <li>{@code @TaskId} - the handler returns without completing the job, and the job's lock
 * is renewed (async-task-lock-renewal). So the handler is NOT invoked again while the test
 * watches, although the task's job timeout is 2s;</li>
 * <li>the job timeout resolves through all four configuration levels from the
 * real application configuration;</li>
 * <li>a {@code retryBackoff} task header in the model decides the backoff of its own
 * element, without a new process version and without configuration;</li>
 * <li>the start of a workflow leaves its process instance key in the delivery log, together
 * with the version of the process definition. It uses the form in which a delivery of its
 * tasks names the key. This holds for a direct start and for a start by message;</li>
 * <li>a message passed to {@code startWorkflowByMessage} which starts another process is
 * refused.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8TaskProcessingIT extends SpringBootTestOnTheSharedCluster {

  private static final Logger LOG = LoggerFactory.getLogger(Camunda8TaskProcessingIT.class);

  /**
   * How long a wait may go on before it says out loud what it is still missing.
   * <p>
   * A minute in which a runner printed nothing made the lost user-task notification
   * impossible to read afterwards. The deadline's message says what the state was at the
   * END. Nothing says whether it had been that way from the first second, or whether it
   * moved once and then stopped. A line every quarter minute turns the wait into a record.
   * It costs nothing in a green run, where no wait ever gets that far.
   */
  private static final long PROGRESS_INTERVAL_MS = 15_000;

  /**
   * What a probe is asked about.
   */
  private static final WorkflowScope SCOPE = WorkflowScope
      .of("test-module", "TestProcess");

  @Autowired
  private TaskDockerWorkflowService workflowService;

  @Autowired
  private TaskDockerAggregateRepository repository;

  @Autowired
  private MessageStartDockerWorkflowService messageStartWorkflowService;

  @Autowired
  private MessageStartDockerAggregateRepository messageStartRepository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private VanillaBpCamunda8Properties overlay;

  @Autowired
  private DataSource dataSource;

  /**
   * Borrowed from the aggregateChanged fixture, whose PRIMARY process parks in a
   * {@code @TaskId} task - the one shape this class's own workflow service does not have.
   */
  @Autowired
  private PushDockerWorkflowService pushWorkflowService;

  @Autowired
  private PushDockerAggregateRepository pushRepository;

  private Long start(
      final String bpmnProcessId) {

    return transactionTemplate.execute(status -> {
      final var aggregate = new TaskDockerAggregate();
      if ("TaskProcess".equals(bpmnProcessId)) {
        return workflowService.startWorkflow(aggregate).getId();
      }
      throw new IllegalArgumentException(bpmnProcessId);
    });

  }

  @Test
  @DisplayName("completeTask of a task this cluster never had fails at once, not after the visibility window")
  public void completeUnknownTaskFailsWithoutWaiting() {

    // the workflow was started through this adapter, so the election has a hint for it,
    // and the adapter reports a visibility window of ten seconds. That window is for a
    // WORKFLOW which the query API has not caught up with. A job key is asked of the engine
    // itself (UpdateJobTimeout), and the engine answers exactly. So this must not wait for
    // the window (decision 27 of the platform's DECISIONS.md)
    final var aggregateId = start("TaskProcess");

    final var startedAt = System.nanoTime();
    final var exception = assertThrows(
        TaskNotFoundException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          workflowService.completeAsyncTask(aggregate, "2251799813685247");
        }));
    final var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

    assertTrue(
        exception.getMessage().contains("2251799813685247"),
        "expected the unknown task to be named but got: "
            + exception.getMessage());
    assertTrue(
        elapsed.toSeconds() < 5,
        "an unknown task must fail without waiting out the visibility window, but took "
            + elapsed);

  }

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final long timeoutMillis,
      final String description) throws InterruptedException {

    awaitUntil(condition, timeoutMillis, description, () -> null);

  }

  /**
   * Waits for a condition and, if it never becomes true, says what it saw while waiting.
   * <p>
   * A message which holds nothing but its own description makes a timeout in a runner
   * impossible to read. The run is gone, and "the notification never came" leaves every
   * explanation open. So the caller of a wait which can go red on a machine nobody watches
   * hands over a second supplier. The wait asks it once, at the deadline.
   *
   * @param condition What is waited for
   * @param timeoutMillis How long to wait
   * @param description What is waited for, as the message says it
   * @param whatWasSeen The state at the deadline, or <code>null</code> to report none
   */
  private void awaitUntil(
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
      Thread.sleep(200);
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

  /**
   * What the timeout appends to its message, or nothing.
   * <p>
   * Reading the state can run into the same trouble as the wait. A diagnosis which fails
   * must not replace the timeout it was written to explain. An assertion error is caught
   * together with the exceptions, because reading the state may go through code which
   * asserts instead of throwing.
   *
   * @param whatWasSeen The state at the deadline
   * @return The sentence to append
   */
  private String whatWasSeenOrWhyNot(
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

  private int invocations(
      final String taskDefinition,
      final Long aggregateId) {

    final var counter = TaskDockerWorkflowService.INVOCATIONS
        .get(taskDefinition
            + ":"
            + aggregateId);
    return counter != null
        ? counter.get()
        : 0;

  }

  /**
   * Waits for the row which the start of a workflow leaves behind. Then it checks two things.
   * Its workflow id is the process instance key which a delivered task of the same workflow
   * names. And its process version is the version which that task names.
   *
   * @param aggregateId The workflow aggregate this test started
   * @param bpmnProcessId The process the workflow was started of
   */
  private void assertTheStartLeftTheKeyOfItsWorkflow(
      final Object aggregateId,
      final String bpmnProcessId) throws InterruptedException {

    // the row is written after the transaction of the start committed. So the test waits
    // for it and does not read it right away
    final var started = new AtomicReference<List<Delivery>>(List.of());
    awaitUntil(
        () -> {
          started.set(workflowStartsOf(aggregateId, bpmnProcessId));
          return !started.get().isEmpty();
        },
        60000,
        "the start of %s to be written down".formatted(bpmnProcessId),
        () -> deliveryLog().workflowStarts().toString());
    assertEquals(1, started.get().size(), "one row per workflow of one aggregate");
    final var workflowId = started.get().getFirst().workflowId();

    // a task of that workflow names its workflow through the invocation context. Both end
    // up in the same column, so the two have to be the same text for the same instance
    final var delivered = new AtomicReference<Delivery>();
    awaitUntil(
        () -> {
          deliveryLog()
              .deliveries()
              .stream()
              .filter(delivery -> String.valueOf(aggregateId).equals(delivery.aggregateId()))
              .filter(delivery -> workflowId.equals(delivery.workflowId()))
              .findFirst()
              .ifPresent(delivered::set);
          return delivered.get() != null;
        },
        60000,
        "a task of the started workflow to be delivered under process instance key '%s'".formatted(workflowId),
        () -> deliveryLog().deliveries().toString());

    // the same holds for the version of the process definition. The start and the task
    // write it into the same column, and a reader compares the two
    final var startedOn = workflowStartsOf(aggregateId, bpmnProcessId)
        .getFirst()
        .processVersion();
    assertNotNull(startedOn, "the start names the version of the process definition it started on");
    assertEquals(
        delivered
            .get()
            .processVersion(),
        startedOn,
        "the start names the version in the form a task of the same workflow names it");

  }

  /**
   * The delivery log of this application. The reader belongs to the platform, so this
   * class names neither the table nor its columns.
   *
   * @return The reader
   */
  private TaskDeliveryLogReader deliveryLog() {

    return TaskDeliveryLogReader.of(dataSource);

  }

  /**
   * The rows the start of one workflow left behind. Read by aggregate AND by process:
   * the classes of this module share one in-memory database, and their aggregate ids
   * overlap.
   *
   * @param aggregateId The workflow aggregate this test started
   * @param bpmnProcessId The process the workflow was started of
   * @return Those rows, empty while the log holds none
   */
  private List<Delivery> workflowStartsOf(
      final Object aggregateId,
      final String bpmnProcessId) {

    return deliveryLog()
        .workflowStartsOfAggregate(String.valueOf(aggregateId))
        .stream()
        .filter(row -> row.bpmnProcessId().endsWith(bpmnProcessId))
        .toList();

  }

  private String results(
      final Long aggregateId) {

    return repository.findById(aggregateId).orElseThrow().getResults();

  }

  /**
   * The events which the handlers reported into the aggregate. A repetition of the same
   * event is collapsed into its first occurrence.
   * <p>
   * Camunda 8 delivers at least once. The core drops a repetition which carries the same
   * job key. Nothing drops one which carries a new job key. So an assertion about WHICH
   * events reached the aggregate must not turn red over how often one of them did. The
   * inbound-idempotency test measures how often.
   *
   * @param aggregateId The aggregate the events were reported into
   * @return The reported events in the order they arrived
   */
  private List<String> reportedEventsWithoutRepetitions(
      final Long aggregateId) {

    final var results = results(aggregateId);
    return results == null
        ? List.of()
        : Arrays
            .stream(results.split("\\|"))
            .distinct()
            .toList();

  }

  /**
   * Everything the CREATED notification of a user task depends on, in one line: what the
   * handler wrote into the aggregate, how often it ran at all, which workflow it was about,
   * and what the cluster holds for that workflow. The workflow is the one this test
   * started, since a test starts exactly one.
   * <p>
   * The cluster's half was missing when this wait ran out of its minute on a runner. The
   * application's half says that the notification never came. It says so in three cases:
   * the listener job was never created, or it was created and never handed to a worker, or
   * it was handed over and failed. Those are three different defects.
   * <p>
   * The cluster is asked three questions, not one. The job says what became of the
   * delivery. The user task says whether the cluster ever got past <code>CREATING</code>.
   * The incident says whether the instance now waits for an operator. A red run which
   * answers all three can be read without a second run.
   * <p>
   * The cluster's own log has the details of what it made of the listener job. Search
   * there for the process instance key.
   *
   * @param aggregateId The aggregate the notification is awaited for
   * @return What a timeout should report
   */
  private String userTaskNotificationState(
      final Long aggregateId) {

    final var aggregate = repository.findById(aggregateId).orElseThrow();
    return ("results '%s', task id '%s' and %d invocation(s) of 'approveUser' for process instance %d. "
        + "The cluster holds these jobs for it: %s. Its user tasks: %s. Its incidents: %s. "
        + "Its own log is in '%s'")
        .formatted(
            aggregate.getResults(),
            aggregate.getTaskId(),
            invocations("approveUser", aggregateId),
            lastStartedInstanceKey,
            jobsTheClusterHoldsForTheStartedInstance(),
            userTasksTheClusterHoldsForTheStartedInstance(),
            incidentsTheClusterHoldsForTheStartedInstance(),
            ClusterLog.FILE);

  }

  /**
   * The jobs which the cluster holds for the workflow this test started, as one sentence.
   * <p>
   * A listener job which is there and waiting says that the cluster did its part. No job at
   * all says that the user task was never created, or that its listener never became a
   * job. The answer comes from the search API, which is eventually consistent. So "no job
   * at all" is a strong hint, not a proof. Without this line, a timeout has no hint at all.
   * <p>
   * <b>Why the retries, the worker and the error message are in here.</b> A job in state
   * <code>FAILED</code> was failed by a command which somebody sent. On 2026-09-18 a red run
   * showed one without saying who sent it. Only three senders exist, and each of them
   * leaves something different on the job:
   * <ul>
   * <li>this adapter, in {@code Camunda8ListenerJobs.completeOrFail}: a warning which names
   * the job stands in the log right before it. The error message is one line of the shape
   * <code>type: message</code>;</li>
   * <li>the Camunda client, for anything thrown out of a job handler before that method
   * takes over: the error message is a whole stack trace. The retries no longer tell the
   * two apart. A user-task listener is written into the model with one retry, and both
   * senders leave zero;</li>
   * <li>the Camunda client again, when the worker had no execution slot for the job: the
   * retries are unchanged, and the error message says that the worker had no capacity and
   * returned the job. That needs <code>stream-enabled</code>, which no test here switches
   * on.</li>
   * </ul>
   *
   * @return What the cluster holds, or why it could not be asked
   */
  private String jobsTheClusterHoldsForTheStartedInstance() {

    try {
      final var jobs = workflowServiceClient()
          .newJobSearchRequest()
          .filter(filter -> filter.processInstanceKey(lastStartedInstanceKey))
          .send()
          .join()
          .items();
      if (jobs.isEmpty()) {
        return "no job at all";
      }
      return jobs
          .stream()
          .map(job -> ("a %s job '%s' at '%s' for %s in state %s with %s retries left, "
              + "last held by worker '%s', denied %s, error message '%s'")
              .formatted(
                  job.getKind(),
                  job.getType(),
                  job.getElementId(),
                  job.getListenerEventType(),
                  job.getState(),
                  job.getRetries(),
                  job.getWorker(),
                  job.isDenied(),
                  inOneLine(job.getErrorMessage())))
          .collect(java.util.stream.Collectors.joining(", "));
    } catch (final RuntimeException e) {
      return "no answer: "
          + e;
    }

  }

  /**
   * The user tasks which the cluster holds for the workflow this test started, with the
   * state of each one.
   * <p>
   * A task in <code>CREATING</code> is the other half of a failed <code>creating</code>
   * listener job. The cluster holds the transition open and waits for an answer which nobody
   * is going to send. Without this line, a reader cannot tell that case from a task which
   * the cluster never began to create.
   *
   * @return What the cluster holds, or why it could not be asked
   */
  private String userTasksTheClusterHoldsForTheStartedInstance() {

    try {
      final var userTasks = workflowServiceClient()
          .newUserTaskSearchRequest()
          .filter(filter -> filter.processInstanceKey(lastStartedInstanceKey))
          .send()
          .join()
          .items();
      if (userTasks.isEmpty()) {
        return "none";
      }
      return userTasks
          .stream()
          .map(userTask -> "'%s' (key %s) in state %s"
              .formatted(userTask.getElementId(), userTask.getUserTaskKey(), userTask.getState()))
          .collect(java.util.stream.Collectors.joining(", "));
    } catch (final RuntimeException e) {
      return "no answer: "
          + e;
    }

  }

  /**
   * What the cluster is waiting for an operator about, for the workflow this test started.
   * <p>
   * A listener job which failed with no retry left IS an incident. So this line says whether
   * the delivery ended there. It also names the job which the incident belongs to. That is
   * the link back to the job list above.
   *
   * @return What the cluster holds, or why it could not be asked
   */
  private String incidentsTheClusterHoldsForTheStartedInstance() {

    try {
      final var incidents = workflowServiceClient()
          .newIncidentSearchRequest()
          .filter(filter -> filter.processInstanceKey(lastStartedInstanceKey))
          .send()
          .join()
          .items();
      if (incidents.isEmpty()) {
        return "none";
      }
      return incidents
          .stream()
          .map(incident -> "%s at '%s' (job %s) in state %s: '%s'"
              .formatted(
                  incident.getErrorType(),
                  incident.getElementId(),
                  incident.getJobKey(),
                  incident.getState(),
                  inOneLine(incident.getErrorMessage())))
          .collect(java.util.stream.Collectors.joining(", "));
    } catch (final RuntimeException e) {
      return "no answer: "
          + e;
    }

  }

  /**
   * How many characters of an error message a diagnosis carries. A stack trace is the error
   * message of a job which the Camunda client failed. Printing all of it would bury the rest
   * of the line. The first few hundred characters hold the exception and the frames which
   * name the class. That is what tells the senders apart.
   */
  private static final int ERROR_MESSAGE_EXCERPT = 400;

  /**
   * An error message of the cluster as one line a reader can scan: the line breaks of a
   * stack trace collapsed into spaces, and only the beginning of a long one.
   *
   * @param message What the cluster reported, possibly <code>null</code>
   * @return One line, never <code>null</code>
   */
  private static String inOneLine(
      final String message) {

    if ((message == null) || message.isBlank()) {
      return "none";
    }
    final var oneLine = message.replaceAll("\\s+", " ").trim();
    return oneLine.length() <= ERROR_MESSAGE_EXCERPT
        ? oneLine
        : oneLine.substring(0, ERROR_MESSAGE_EXCERPT)
            + "... (cut after "
            + ERROR_MESSAGE_EXCERPT
            + " characters)";

  }

  @Test
  @DisplayName("The job timeout resolves through all four configuration levels from real config")
  public void jobTimeoutResolvesThroughAllFourLevels() {

    // task level (most specific)
    assertEquals(
        Duration.ofSeconds(2),
        overlay.jobTimeoutFor("test-app", "TaskProcess", "happyTask", "c8"));
    // workflow level
    assertEquals(
        Duration.ofSeconds(10),
        overlay.jobTimeoutFor("test-app", "TaskProcess", "errorTask", "c8"));
    // workflow-module level
    assertEquals(
        Duration.ofSeconds(20),
        overlay.jobTimeoutFor("test-app", "FailProcess", "alwaysFails", "c8"));
    // adapter level (base)
    assertEquals(
        Duration.ofSeconds(30),
        overlay.jobTimeoutFor("unknown-module", "SomeProcess", "someTask", "c8"));

  }

  @Test
  @DisplayName("The message time-to-live resolves through all four levels, message first")
  public void messageTimeToLiveResolvesThroughAllFourLevels() {

    // message level (most specific). A catch event whose message may repeat every minute,
    // for good reason. A per-message override is for exactly this
    assertEquals(
        Duration.ofMinutes(1),
        overlay.messageTimeToLiveFor("test-app", "TaskProcess", "OfferRequested", "c8"));
    // workflow level
    assertEquals(
        Duration.ofHours(4),
        overlay.messageTimeToLiveFor("test-app", "TaskProcess", "SomeOtherMessage", "c8"));
    // workflow-module level
    assertEquals(
        Duration.ofHours(5),
        overlay.messageTimeToLiveFor("test-app", "FailProcess", "SomeMessage", "c8"));
    // adapter level (base)
    assertEquals(
        Duration.ofHours(6),
        overlay.messageTimeToLiveFor("unknown-module", "SomeProcess", "SomeMessage", "c8"));
    // a task name is not a message name. The two most specific levels are different maps
    assertEquals(
        Duration.ofHours(4),
        overlay.messageTimeToLiveFor("test-app", "TaskProcess", "happyTask", "c8"));

  }

  @Test
  @DisplayName("The retry backoff resolves through all four configuration levels from real config")
  public void retryBackoffResolvesThroughAllFourLevels() {

    // task level (most specific)
    assertEquals(
        Duration.ofSeconds(2),
        overlay.retryBackoffFor("test-app", "TaskProcess", "happyTask", "c8"));
    // workflow level
    assertEquals(
        Duration.ofSeconds(10),
        overlay.retryBackoffFor("test-app", "TaskProcess", "errorTask", "c8"));
    // workflow level of the process the backoff is measured on below
    assertEquals(
        Duration.ofSeconds(5),
        overlay.retryBackoffFor("test-app", "FailProcess", "alwaysFails", "c8"));
    // workflow-module level
    assertEquals(
        Duration.ofSeconds(20),
        overlay.retryBackoffFor("test-app", "OtherProcess", "someTask", "c8"));
    // adapter level (base)
    assertEquals(
        Duration.ofSeconds(30),
        overlay.retryBackoffFor("unknown-module", "SomeProcess", "someTask", "c8"));
    // and the answer names the one level which a 'retryBackoff' task header meets as an
    // equal
    assertTrue(
        overlay.configuredRetryBackoffFor("test-app", "TaskProcess", "happyTask", "c8").perTask(),
        "'happyTask' configures a backoff of its own");
    assertFalse(
        overlay.configuredRetryBackoffFor("test-app", "TaskProcess", "errorTask", "c8").perTask(),
        "the workflow level speaks about more than this one task");

  }

  @Test
  @DisplayName("A @TaskParam is delivered although its variable appears in no model and nothing is configured")
  public void aDeclaredTaskParameterIsFetched() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    // a variable which the BPMN model does not mention anywhere. No input mapping declares
    // it, and no script writes it. The workflow was started with it, and that is all the
    // cluster knows about it
    final var bigPayload = "x".repeat(32768);
    lastStartedInstanceKey = workflowServiceClient()
        .newCreateInstanceCommand()
        .bpmnProcessId("test-app__FetchProcess")
        .latestVersion()
        .variables(Map.of("id", String.valueOf(aggregateId), "bigPayload", bigPayload))
        .send()
        .join()
        .getProcessInstanceKey();

    // the FIRST task is served by a worker of its own, and the declared name reaches it
    awaitUntil(
        () -> invocations("fetchAllTask", aggregateId) >= 1,
        60000,
        "the first task to be delivered");
    assertEquals(
        bigPayload.length(),
        TaskDockerWorkflowService.OBSERVED_VARIABLES.get("bigPayloadLength"),
        "the declared @TaskParam is fetched for the first task as well");

    // the SECOND task configures nothing either, and its worker asks the cluster for
    // 'bigPayload'. The core read the name off the method while wiring. So the derived list
    // covers what the application reads, not what the model happens to declare. The core
    // module has its own tests for how SHORT that list is. Here the point is that the value
    // arrives
    awaitUntil(
        () -> invocations("fetchDerivedTask", aggregateId) >= 1,
        60000,
        "the task fetching the derived list to be delivered");
    assertEquals(
        bigPayload.length(),
        TaskDockerWorkflowService.OBSERVED_VARIABLES.get("derivedPayloadLength"),
        "the variable stands in no model and no property was set - the annotation alone brought it");
    // a method wired to the ELEMENT id serves the THIRD task. Its job type is a name which
    // no method of the workflow service carries. So the core knows this handler by the
    // element alone. The worker still has to ask the cluster for 'bigPayload'
    awaitUntil(
        () -> invocations("fetchByElementId", aggregateId) >= 1,
        60000,
        "the task wired by its element id to be delivered");
    assertEquals(
        bigPayload.length(),
        TaskDockerWorkflowService.OBSERVED_VARIABLES.get("byElementIdPayloadLength"),
        "a @WorkflowTask(id = ...) declares its @TaskParam like any other, so its worker fetches "
            + "the variable - asking the core by the task definition alone would leave this handler out");
    awaitUntil(
        () -> "fetch-all|fetch-derived|fetch-by-element-id".equals(results(aggregateId)),
        60000,
        "all three tasks to have committed");

  }

  @Test
  @DisplayName("A failed job is not handed out again at once - the fail command carries the backoff")
  public void aFailedJobIsHandedOutAgainOnlyAfterTheBackoff() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    startSecondaryProcess("FailProcess", aggregateId);

    awaitUntil(
        () -> invocations("alwaysFails", aggregateId) >= 1,
        60000,
        "the failing job to be delivered");

    // FailProcess is configured with 'retry-backoff: PT5S'. Without it the cluster hands the
    // job out again within milliseconds. That used to use up all three retries before the
    // cause of the failure had any chance to pass
    Thread.sleep(3000);
    assertEquals(
        1,
        invocations("alwaysFails", aggregateId),
        "the job must not be redelivered while the backoff is still running");

    awaitUntil(
        () -> invocations("alwaysFails", aggregateId) >= 2,
        60000,
        "the failing job to be redelivered after its backoff");

    final var times = TaskDockerWorkflowService.INVOCATION_TIMES
        .get("alwaysFails:"
            + aggregateId);
    final var gap = times.get(1) - times.get(0);
    assertTrue(
        gap >= 4000,
        "expected at least the configured five seconds between two deliveries but saw "
            + gap
            + " ms");

  }

  @Test
  @DisplayName("The task header of the model decides the backoff where nothing configures the task")
  public void theModelledBackoffReachesTheCluster() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    // an application which comes from version 1 has a model which brings its own backoff.
    // Keeping that value must not cost it a new process version
    startSecondaryProcess("ModelledBackoffProcess", aggregateId);

    awaitUntil(
        () -> invocations("modelledBackoffFails", aggregateId) >= 1,
        60000,
        "the failing job to be delivered");

    awaitUntil(
        () -> invocations("modelledBackoffFails", aggregateId) >= 2,
        60000,
        "the failing job to be handed out again");

    // the element models PT12S, while its workflow configures PT1S. So the distance between
    // two deliveries says which of the two the cluster was given. The modelled value is the
    // long one on purpose. A busy cluster can stretch a gap but never shorten it, so this
    // assertion cannot go red on a slow machine
    final var times = TaskDockerWorkflowService.INVOCATION_TIMES
        .get("modelledBackoffFails:"
            + aggregateId);
    final var gap = times.get(1) - times.get(0);
    assertTrue(
        gap >= 8000,
        "expected the twelve seconds the model asks for between two deliveries but saw "
            + gap
            + " ms, which is the second the configuration asks for");

  }

  @Test
  @DisplayName("Happy path with TaskException error-boundary routing")
  public void happyPathAndBpmnErrorRoutesBoundary() throws Exception {

    final var aggregateId = start("TaskProcess");

    awaitUntil(
        () -> {
          final var results = results(aggregateId);
          return (results != null) && results.contains("handled");
        },
        60000,
        "TaskProcess to converge through the error boundary");

    // the throwing handler's mutation committed, the boundary path ran
    assertEquals("happy|error-raised|handled", results(aggregateId));

  }

  @Test
  @DisplayName("The start of a workflow leaves the process instance key behind, as a delivery names it")
  public void theStartLeavesTheProcessInstanceKeyBehind() throws Exception {

    final var aggregateId = start("TaskProcess");

    assertTheStartLeftTheKeyOfItsWorkflow(aggregateId, "TaskProcess");

  }

  @Test
  @DisplayName("A second delivery of the same task converges (at-least-once redelivery)")
  public void redeliveryConverges() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    startSecondaryProcess("RetryProcess", aggregateId);

    // the first delivery fails (job failed, local TX rolled back). Camunda 8 delivers it
    // again. The second delivery converges, and the process ends
    awaitUntil(
        () -> {
          final var results = results(aggregateId);
          return (results != null) && results.contains("retried");
        },
        60000,
        "RetryProcess to converge after a redelivery");

    assertTrue(
        invocations("retryTask", aggregateId) >= 2,
        "expected a second delivery but saw "
            + invocations("retryTask", aggregateId));
    // exactly one 'retried' in the results. The second delivery converged idempotently,
    // and the change of the first delivery was rolled back
    assertEquals("retried", results(aggregateId));

  }

  @Test
  @DisplayName("A technical exception fails the job with decremented retries and rolls back the aggregate")
  public void technicalExceptionFailsJobAndRollsBack() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new TaskDockerAggregate();
      final var saved = repository.save(aggregate);
      // FailProcess is started directly via the adapter's client (the injectable
      // ProcessService starts only the primary process)
      return saved.getId();
    });
    // start FailProcess through a fresh aggregate + direct client call
    startSecondaryProcess("FailProcess", aggregateId);

    // Camunda 8 retries the failing job (3 attempts by default)
    awaitUntil(
        () -> invocations("alwaysFails", aggregateId) >= 2,
        60000,
        "the failing job to be retried");

    // the handler's change never became visible (local rollback per attempt)
    assertNull(results(aggregateId));

  }

  @Test
  @DisplayName("@TaskId dormancy: the open job is not re-invoked although its job timeout is 2s")
  public void asyncTaskStaysDormant() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    startSecondaryProcess("AsyncProcess", aggregateId);

    // the COMMIT of the handler writes the job key which the handler reports. So the wait
    // has to end at the commit as well. The first statement of the handler raises the
    // invocation counter. So the counter is true a few milliseconds earlier, while the
    // aggregate in the database still carries no task id at all
    awaitUntil(
        () -> repository.findById(aggregateId).map(TaskDockerAggregate::getTaskId).orElse(null) != null,
        60000,
        "the async task to commit its job key");

    // the task's job timeout is PT2S. Without the lock renewal of a dormant task, the job
    // would be delivered again within this time
    Thread.sleep(8000);
    assertEquals(
        1,
        invocations("asyncTask", aggregateId),
        "the dormant async job must not be re-invoked");
    assertEquals("async-open", results(aggregateId));

  }

  @Test
  @DisplayName("completeTask completes the dormant job through the outbox after the commit")
  public void completeTaskEndsDormantProcess() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    startSecondaryProcess("AsyncProcess", aggregateId);

    awaitUntil(
        () -> repository.findById(aggregateId).map(TaskDockerAggregate::getTaskId).orElse(null) != null,
        60000,
        "the async task to report its job key");

    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      aggregate.appendResult("completing");
      workflowService.completeAsyncTask(aggregate, aggregate.getTaskId());
    });

    // phase two completes the job through the outbox after the commit. The test asks the
    // cluster about that job DIRECTLY, with a command and not with the eventually
    // consistent search API
    final var taskId = repository.findById(aggregateId).orElseThrow().getTaskId();
    awaitUntil(
        () -> theClusterNoLongerKnowsTheJob(taskId),
        60000,
        "the dormant job to be completed through the outbox");
    assertTrue(results(aggregateId).startsWith("async-open|completing"));
    // the dormant job was NOT re-invoked by the completion flow
    assertEquals(1, invocations("asyncTask", aggregateId));

  }

  @Test
  @DisplayName("cancelTask throws the BPMN error and the boundary path runs")
  public void cancelTaskRoutesErrorBoundary() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    startSecondaryProcess("AsyncCancelProcess", aggregateId);

    awaitUntil(
        () -> repository.findById(aggregateId).map(TaskDockerAggregate::getTaskId).orElse(null) != null,
        60000,
        "the await-cancel task to report its job key");

    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.cancelAsyncTask(aggregate, aggregate.getTaskId(), "PAYMENT_FAILED");
    });

    awaitUntil(
        () -> {
          final var results = results(aggregateId);
          return (results != null) && results.contains("cancel-handled");
        },
        60000,
        "the BPMN error to route through the boundary");
    assertEquals("await-cancel|cancel-handled", results(aggregateId));

  }

  @Test
  @DisplayName("A stale completion aborts the transaction with the documented TaskNotFoundException")
  public void aStaleCompletionRaisesTheGuidingException() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    startSecondaryProcess("AsyncProcess", aggregateId);

    awaitUntil(
        () -> repository.findById(aggregateId).map(TaskDockerAggregate::getTaskId).orElse(null) != null,
        60000,
        "the async task to report its job key");
    final var taskId = repository.findById(aggregateId).orElseThrow().getTaskId();

    // the job is completed OUTSIDE VanillaBP (simulating a concurrent completion)
    workflowServiceClient()
        .newCompleteCommand(Long.parseLong(taskId))
        .send()
        .join();

    // the delivery record still says that this adapter holds an open task. So the call
    // reaches the adapter and not a probe, and the pre-commit check meets the cluster's
    // 404. The transaction is aborted, and the caller gets the type which the SPI documents
    // for a task no BPMS knows any more
    Assertions.assertThrows(
        TaskNotFoundException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          workflowService.completeAsyncTask(aggregate, taskId);
        }));

  }

  /**
   * The same stale completion, on a PRIMARY process.
   * <p>
   * The platform elects the adapter from the delivery record before it probes any BPMS. It
   * looks for that record under every BPMN process id which the workflow service serves.
   * Every parking process of this class's own workflow service is a secondary one. So the
   * test above only tests the longer of those two lookups. This one tests the short one,
   * with the fixture whose PRIMARY process parks in a {@code @TaskId} task.
   */
  @Test
  @DisplayName("A stale completion of a primary process's task raises the same exception")
  public void aStaleCompletionOnAPrimaryProcessRaisesTheGuidingException() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> pushWorkflowService.startWorkflow().getId());

    awaitUntil(
        () -> pushRepository.findById(aggregateId).map(PushDockerAggregate::getTaskIds).orElse(null) != null,
        60000,
        "the parking task of the primary process to report its job key");
    final var taskId = pushRepository.findById(aggregateId).orElseThrow().getTaskIds();

    workflowServiceClient()
        .newCompleteCommand(Long.parseLong(taskId))
        .send()
        .join();

    Assertions.assertThrows(
        TaskNotFoundException.class,
        () -> transactionTemplate
            .executeWithoutResult(status -> pushWorkflowService.completeAwaitPush(aggregateId, taskId)));

  }

  @Test
  @DisplayName("User task: CREATED via listener job, completeUserTask ends the process")
  public void userTaskCreatedAndCompleted() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    startSecondaryProcess("UserTaskProcess", aggregateId);

    // the creating listener job notified the optional handler, with the USER-TASK key as
    // @TaskId
    awaitUntil(
        () -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          return (aggregate.getTaskId() != null) && aggregate.getResults().contains("usertask-created");
        },
        60000,
        "the creating listener to notify the handler",
        () -> userTaskNotificationState(aggregateId));
    final var taskId = repository.findById(aggregateId).orElseThrow().getTaskId();

    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      aggregate.appendResult("approving");
      workflowService.completeUserTask(aggregate, taskId);
    });

    // the test asks the cluster about that user task DIRECTLY, with a command and not with
    // the eventually consistent search API
    awaitUntil(
        () -> theClusterNoLongerKnowsTheUserTask(taskId),
        60000,
        "the user task to be completed through the outbox");
    // completing a user task is not an event of its own. The aggregate holds the creation
    // which the listener reported, and the word which this test wrote before completing
    assertEquals(
        List.of("usertask-created", "approving"),
        reportedEventsWithoutRepetitions(aggregateId),
        "completing a user task must not be reported as an event of its own");

  }

  @Test
  @DisplayName("Canceling the instance delivers CANCELED through the canceling listener")
  public void userTaskCanceledOnInstanceCancellation() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    startSecondaryProcess("UserTaskProcess", aggregateId);

    awaitUntil(
        () -> repository.findById(aggregateId).map(TaskDockerAggregate::getTaskId).orElse(null) != null,
        60000,
        "the creating listener to notify the handler",
        () -> userTaskNotificationState(aggregateId));

    // cancel the whole instance - the canceling task listener fires as a job
    // (the instance key was captured at start: the search API needs secondary
    // storage which the test broker does not run)
    workflowServiceClient()
        .newCancelInstanceCommand(lastStartedInstanceKey)
        .send()
        .join();

    awaitUntil(
        () -> {
          final var results = results(aggregateId);
          return (results != null) && results.contains("usertask-canceled");
        },
        60000,
        "the canceling listener to deliver CANCELED");

  }

  @Test
  @DisplayName("cancelUserTask is unsupported on Camunda 8.8 - the guiding error explains it")
  public void cancelUserTaskUnsupportedGuiding() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    startSecondaryProcess("UserTaskProcess", aggregateId);

    awaitUntil(
        () -> repository.findById(aggregateId).map(TaskDockerAggregate::getTaskId).orElse(null) != null,
        60000,
        "the creating listener to notify the handler",
        () -> userTaskNotificationState(aggregateId));
    final var taskId = repository.findById(aggregateId).orElseThrow().getTaskId();

    final var exception = Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          workflowService.cancelUserTask(aggregate, taskId, "SOME_ERROR");
        }));
    // the message names the release line which the application runs, not a fixed version.
    // A reader has to change the line to get the operation
    assertTrue(
        exception.getMessage().contains("release line "
            + Camunda8ReleaseLine.id()) && exception.getMessage().contains("8.10"),
        "expected the guiding explanation naming the release line and where the operation arrives, but got: "
            + exception.getMessage());

    // the user task is completed before the test ends. The cleanup cancels an open
    // Camunda-managed user task left on the shared cluster. The canceling listener job of
    // that cancellation is one more thing which can reach the wrong application
    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.completeUserTask(aggregate, taskId);
    });
    awaitUntil(
        () -> theClusterNoLongerKnowsTheUserTask(taskId),
        60000,
        "the user task to be completed through the outbox");

  }

  @Test
  @DisplayName("User-task edge cases: silent task, awareness, gone-task tolerance")
  public void userTaskEdgeCases() throws Exception {

    // a user task WITHOUT a handler. The creating listener job is completed without a
    // notification, and the process keeps waiting at the user task
    final var silentAggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    startSecondaryProcess("SilentUserTaskProcess", silentAggregateId);
    final var silentInstanceKey = lastStartedInstanceKey;

    @SuppressWarnings("unchecked")
    final var c8ProcessService = (Camunda8ProcessService<TaskDockerAggregate>) applicationContext
        .getBean("Camunda8_ProcessService_c8");

    // gone user task: awareness UNKNOWN, and phase two is accepted as a no-op with a warning
    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        c8ProcessService.awarenessOfUserTask(SCOPE, silentAggregateId, "1"));
    Assertions.assertDoesNotThrow(
        () -> PhaseOperations.phaseTwo(c8ProcessService, PhaseOperation.COMPLETE_USER_TASK,
            "test-app", "SilentUserTaskProcess", null, silentAggregateId,
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "1")));
    // phase two of a gone SERVICE task is accepted the same way
    Assertions.assertDoesNotThrow(
        () -> PhaseOperations.phaseTwo(c8ProcessService, PhaseOperation.COMPLETE_TASK,
            "test-app", "SilentUserTaskProcess", null, silentAggregateId,
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "1")));
    Assertions.assertDoesNotThrow(
        () -> PhaseOperations.phaseTwo(c8ProcessService, PhaseOperation.CANCEL_TASK,
            "test-app", "SilentUserTaskProcess", null, silentAggregateId,
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, "1",
                PhaseTwoCall.ARG_BPMN_ERROR_CODE, "ERR")));

    // and the silent task itself. The creating listener job was completed without a
    // notification, so the process is parked at the user task. Nothing inside the
    // application can say that, because no handler of this test ever ran for it. So the
    // test asks the cluster. The pause which this test used to end with only hoped for it.
    // The test waits for the state, not for the mere existence of the task. A task whose
    // creating listener job was never delivered stands in CREATING, and the search answers
    // with it just the same. So a test which only asked whether the search names the task
    // passed in exactly the case it was written for
    awaitUntil(
        () -> userTaskStatesOf(silentInstanceKey).equals(List.of(UserTaskState.CREATED)),
        60000,
        "the user task without a handler to be created at the cluster",
        () -> "the cluster reports "
            + userTaskStatesOf(silentInstanceKey));
    assertEquals(
        List.of(),
        incidentsOf(silentInstanceKey),
        "a user task nobody listens to leaves the instance healthy");

  }

  /**
   * The BPMN process whose user-task listener job this test answers itself. It lies outside the
   * resources location of the test application. So no workflow module deploys it, and no worker
   * subscribes to its listener job type.
   */
  private static final String LOST_DELIVERY_PROCESS = "LostDeliveryProcess";

  @Test
  @DisplayName("A listener delivery the gateway lost comes back instead of raising an incident")
  public void aLostListenerDeliveryComesBack() throws Exception {

    final BpmnModelInstance model;
    try (final var file = getClass().getResourceAsStream("/lost-delivery/lost-delivery.bpmn")) {
      model = Bpmn.readModelFromStream(file);
    }
    // the adapter writes the lifecycle listeners into the file. So the model deployed here
    // is the one a deployment really produces, not one written to pass this test
    final var userTasks = Camunda8TaskWiring
        .userTasksOf(model, LOST_DELIVERY_PROCESS, "test-app", "lost-delivery.bpmn");
    final var listenerJobType = userTasks.getFirst().listenerJobType();

    workflowServiceClient()
        .newDeployResourceCommand()
        .addProcessModel(model, "lost-delivery.bpmn")
        .send()
        .join();
    final var instanceKey = workflowServiceClient()
        .newCreateInstanceCommand()
        .bpmnProcessId(LOST_DELIVERY_PROCESS)
        .latestVersion()
        .send()
        .join()
        .getProcessInstanceKey();

    try {
      final var lost = activateOneJob(listenerJobType, Duration.ofSeconds(30));
      assertEquals(1, lost.getRetries(), "the deployed listener carries one attempt");

      // what a gateway does with a batch which it could not hand to the request it activated
      // the batch for. It fails the job back with the retries the job had, and asks for no
      // backoff. With no retries left, the job would die here and the user task would stand
      // in CREATING
      Camunda8JobLease
          .withToken(
              workflowServiceClient()
                  .newFailCommand(lost.getKey())
                  .retries(lost.getRetries()),
              Camunda8JobLease.tokenOf(lost))
          .errorMessage("Failed to send activated jobs to client")
          .send()
          .join();

      final var offeredAgain = activateOneJob(listenerJobType, Duration.ofSeconds(30));
      assertEquals(lost.getKey(), offeredAgain.getKey(), "the same listener job was offered again");

      // and it still holds the task back. Answering it ends the state CREATING
      Camunda8JobLease
          .withToken(
              workflowServiceClient().newCompleteCommand(offeredAgain.getKey()),
              Camunda8JobLease.tokenOf(offeredAgain))
          .send()
          .join();
      awaitUntil(
          () -> userTaskStatesOf(instanceKey).equals(List.of(UserTaskState.CREATED)),
          60000,
          "the user task of the recovered listener job to be created at the cluster",
          () -> "the cluster reports "
              + userTaskStatesOf(instanceKey));
      assertEquals(
          List.of(),
          incidentsOf(instanceKey),
          "a delivery which was lost on the way leaves the instance healthy");
    } finally {
      workflowServiceClient()
          .newCancelInstanceCommand(instanceKey)
          .send()
          .join();
    }

  }

  /**
   * What the cluster says about the user tasks of an instance.
   * <p>
   * The state is the answer, not the mere presence of a task. A user task whose
   * {@code creating} listener job was never handed to a worker stands in
   * {@link UserTaskState#CREATING}. The search names it there just as it names a task which
   * has arrived.
   *
   * @param processInstanceKey The instance
   * @return The state of each user task of that instance
   */
  private List<UserTaskState> userTaskStatesOf(
      final Long processInstanceKey) {

    return workflowServiceClient()
        .newUserTaskSearchRequest()
        .filter(filter -> filter.processInstanceKey(processInstanceKey))
        .send()
        .join()
        .items()
        .stream()
        .map(userTask -> userTask.getState())
        .toList();

  }

  /**
   * What went wrong with an instance, as the cluster reports it. With an empty list, a test
   * says that a job was consumed and not left in an incident.
   *
   * @param processInstanceKey The instance
   * @return One message per incident
   */
  private List<String> incidentsOf(
      final Long processInstanceKey) {

    return workflowServiceClient()
        .newIncidentSearchRequest()
        .filter(filter -> filter.processInstanceKey(processInstanceKey))
        .send()
        .join()
        .items()
        .stream()
        .map(incident -> incident.getErrorMessage())
        .toList();

  }

  @Test
  @DisplayName("correlateMessage resumes the instance via the INJECTED zeebe:subscription (no manual model tweaks)")
  public void correlateMessageResumesInstanceViaInjectedSubscription() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    startSecondaryProcess("MessageProcess", aggregateId);

    awaitTheQueryApiKnowingTheStartedInstance(aggregateId);
    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      aggregate.appendResult("correlating");
      workflowService.correlate(aggregate, "C8PaymentReceived");
    });

    awaitUntil(
        () -> {
          final var results = results(aggregateId);
          return (results != null) && results.contains("message-arrived");
        },
        60000,
        "the correlated message to resume the instance");
    assertEquals("correlating|message-arrived", results(aggregateId));

  }

  /**
   * How long the handler is watched after it ran once, before a second delivery counts as
   * one which never came. Three seconds, while a job created at the cluster needs
   * milliseconds to reach a worker whose activation request is already parked there.
   * <p>
   * It is a safety margin, not a budget which anybody has to beat. The test asserts
   * afterwards that a count did not grow. So a machine which leaves this JVM without a turn
   * only makes the silence longer.
   */
  private static final long UNTIL_A_SECOND_DELIVERY_WOULD_HAVE_ARRIVED = 3000;

  @Test
  @DisplayName("The messageId deduplicates: a redelivered phase-two correlation does not double-fire")
  public void duplicateCorrelationDispatchIsDeduplicated() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    startSecondaryProcess("MessageProcess", aggregateId);
    // the instance has to wait at the catch event before anything is published to it. The
    // query API knowing it says so, because the exporter is behind the partition, never
    // ahead of it
    awaitTheQueryApiKnowingTheStartedInstance(aggregateId);

    @SuppressWarnings("unchecked")
    final var c8ProcessService = (Camunda8ProcessService<TaskDockerAggregate>) applicationContext
        .getBean("Camunda8_ProcessService_c8");

    // simulate an at-least-once redelivery of the SAME phase-two dispatch (same
    // correlation id -> same messageId). The engine rejects the second publish, and the
    // adapter accepts that as the documented no-op
    PhaseOperations.phaseTwo(c8ProcessService, PhaseOperation.CORRELATE_MESSAGE,
        "test-app", "MessageProcess", null, aggregateId,
        PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME, "C8PaymentReceived",
            PhaseTwoCall.ARG_CORRELATION_ID, "pay-1"));
    PhaseOperations.phaseTwo(c8ProcessService, PhaseOperation.CORRELATE_MESSAGE,
        "test-app", "MessageProcess", null, aggregateId,
        PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME, "C8PaymentReceived",
            PhaseTwoCall.ARG_CORRELATION_ID, "pay-1"));

    // the correlation id 'pay-1' matches no subscription of this instance. So neither
    // publication may ever reach the handler. The count at the end of this test proves it,
    // not this line. The matching correlation below adds exactly one invocation. A
    // publication which had fired after all would show up there as a second one
    assertEquals(
        0,
        invocations("c8MessageArrived", aggregateId),
        "a message published under a correlation id nobody waits for reaches no handler");
    PhaseOperations.phaseTwo(c8ProcessService, PhaseOperation.CORRELATE_MESSAGE,
        "test-app", "MessageProcess", null, aggregateId,
        PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME, "C8PaymentReceived",
            PhaseTwoCall.ARG_CORRELATION_ID, String.valueOf(aggregateId)));
    awaitUntil(
        () -> invocations("c8MessageArrived", aggregateId) >= 1,
        60000,
        "the matching correlation to resume the instance");
    Thread.sleep(UNTIL_A_SECOND_DELIVERY_WOULD_HAVE_ARRIVED);
    assertEquals(
        1,
        invocations("c8MessageArrived", aggregateId),
        "the redelivered publication never fired, so the handler ran once");

  }

  /**
   * How long a test waits for the cluster to forget an expired message id. See the
   * measurement where it is used. The cluster removes expired ids on its own interval, so
   * this is not the time-to-live plus a margin.
   */
  private static final long SWEEP_TOLERANCE_MILLIS = 75_000;

  /**
   * Collects what the adapter logged while a test ran. The cluster's refusal of a
   * duplicated message id becomes visible only there. The publication itself is accepted
   * as a no-op, so nothing else about it can be observed.
   */
  private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> watchTheAdapter() {

    final var watcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    watcher.start();
    ((ch.qos.logback.classic.Logger) LoggerFactory
        .getLogger(Camunda8ProcessService.class)).addAppender(watcher);
    return watcher;

  }

  private long refusals(
      final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> watcher) {

    return watcher.list
        .stream()
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .filter(message -> message.contains("because a message of the same id was published before"))
        .count();

  }

  @Test
  @DisplayName("Two activations of one element publish two messages, one activation publishes one")
  public void theActivationTellsSiblingsApartInTheClustersOwnNet() throws Exception {

    // The cluster deduplicates by the message id which this adapter derives. For a
    // multi-instance call activity, everything else about the three correlations is equal,
    // because a called process is a secondary workflow of the SAME aggregate. Without the
    // activation, the cluster keeps one of them and drops the rest. VanillaBP cannot see
    // that and cannot fix it from its own side
    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    startSecondaryProcess("MessageProcess", aggregateId);

    @SuppressWarnings("unchecked")
    final var c8ProcessService = (Camunda8ProcessService<TaskDockerAggregate>) applicationContext
        .getBean("Camunda8_ProcessService_c8");

    final var watcher = watchTheAdapter();
    try {
      // two siblings: same message name, same correlation id, different activations
      PhaseOperations.phaseTwo(c8ProcessService, PhaseOperation.CORRELATE_MESSAGE,
          "test-app", "MessageProcess", null, aggregateId,
          PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME, "C8PaymentReceived",
              PhaseTwoCall.ARG_CORRELATION_ID, "partner-42",
              PhaseTwoCall.ARG_ACTIVATION_ID, "element-1"));
      PhaseOperations.phaseTwo(c8ProcessService, PhaseOperation.CORRELATE_MESSAGE,
          "test-app", "MessageProcess", null, aggregateId,
          PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME, "C8PaymentReceived",
              PhaseTwoCall.ARG_CORRELATION_ID, "partner-42",
              PhaseTwoCall.ARG_ACTIVATION_ID, "element-2"));
      assertEquals(
          0,
          refusals(watcher),
          "two activations are two messages for the cluster: "
              + watcher.list);

      // and the guarantee which must not get lost: the same activation twice is still ONE
      // message. That is an at-least-once redelivery of one entry
      PhaseOperations.phaseTwo(c8ProcessService, PhaseOperation.CORRELATE_MESSAGE,
          "test-app", "MessageProcess", null, aggregateId,
          PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME, "C8PaymentReceived",
              PhaseTwoCall.ARG_CORRELATION_ID, "partner-42",
              PhaseTwoCall.ARG_ACTIVATION_ID, "element-1"));
      assertEquals(
          1,
          refusals(watcher),
          "the repetition of one activation is refused by the cluster, as it has to be: "
              + watcher.list);
    } finally {
      ((ch.qos.logback.classic.Logger) LoggerFactory
          .getLogger(Camunda8ProcessService.class)).detachAppender(watcher);
    }

  }

  @Test
  @DisplayName("Past the message time-to-live the cluster accepts the same message id again")
  public void theTimeToLiveDecidesHowLongTheClustersNetLasts() throws Exception {

    // The other half of what the number does. It is the window in which a message id
    // deduplicates. This application shortens it to two seconds for THIS message
    // ('vanillabp.workflow-modules.test-app.workflows.MessageProcess.messages.C8PaymentReceived').
    // A repetition inside the window is refused. The same one after it is a new message
    assertEquals(
        Duration.ofSeconds(2),
        overlay.messageTimeToLiveFor("test-app", "MessageProcess", "C8PaymentReceived", "c8"),
        "the per-message override is what this test rests on");

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    startSecondaryProcess("MessageProcess", aggregateId);

    @SuppressWarnings("unchecked")
    final var c8ProcessService = (Camunda8ProcessService<TaskDockerAggregate>) applicationContext
        .getBean("Camunda8_ProcessService_c8");

    final var watcher = watchTheAdapter();
    try {
      PhaseOperations.phaseTwo(c8ProcessService, PhaseOperation.CORRELATE_MESSAGE,
          "test-app", "MessageProcess", null, aggregateId,
          PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME, "C8PaymentReceived",
              PhaseTwoCall.ARG_CORRELATION_ID, "round-1"));
      PhaseOperations.phaseTwo(c8ProcessService, PhaseOperation.CORRELATE_MESSAGE,
          "test-app", "MessageProcess", null, aggregateId,
          PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME, "C8PaymentReceived",
              PhaseTwoCall.ARG_CORRELATION_ID, "round-1"));
      assertEquals(1, refusals(watcher), "inside the window the second one is refused: "
          + watcher.list);

      // Wait the window out. The number is not "the TTL plus a margin". The cluster removes
      // expired message ids on an interval of its own, not at the moment they expire. So a
      // two-second TTL is NOT forgotten two seconds later. Measured on camunda/camunda:8.9.16
      // on 2026-08-27: still refused 5 s after the TTL, accepted 75 s after it. Because of
      // this lower limit, the wiki tells nobody to shorten this number to get a short
      // deduplication window. It does not work
      Thread.sleep(SWEEP_TOLERANCE_MILLIS);
      PhaseOperations.phaseTwo(c8ProcessService, PhaseOperation.CORRELATE_MESSAGE,
          "test-app", "MessageProcess", null, aggregateId,
          PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME, "C8PaymentReceived",
              PhaseTwoCall.ARG_CORRELATION_ID, "round-1"));
      assertEquals(
          1,
          refusals(watcher),
          "past the window the very same message id is accepted again: "
              + watcher.list);
    } finally {
      ((ch.qos.logback.classic.Logger) LoggerFactory
          .getLogger(Camunda8ProcessService.class)).detachAppender(watcher);
    }

  }

  @Test
  @DisplayName("startWorkflowByMessage starts the instance via the message start event")
  public void startWorkflowByMessageStartsInstance() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new MessageStartDockerAggregate();
      aggregate.setId("message-start-%s".formatted(java.util.UUID.randomUUID()));
      final var saved = messageStartRepository.save(aggregate);
      messageStartWorkflowService.startByMessage(saved, "C8OrderPlaced");
      return saved.getId();
    });

    awaitUntil(
        () -> "order-placed".equals(messageStartRepository
            .findById(aggregateId)
            .orElseThrow()
            .getResults()),
        60000,
        "the message start event to start the instance");

    // the answer to a published message names the message, not the instance it started.
    // The key reaches the log anyway, through the worker of the message start event. It has
    // to be the key which a task of that instance names
    assertTheStartLeftTheKeyOfItsWorkflow(aggregateId, "MessageStartProcess");

  }

  @Test
  @DisplayName("startWorkflowByMessage refuses a message which starts another process")
  public void startWorkflowByMessageRefusesTheMessageOfAnotherProcess() {

    // 'C8OrderPlaced' starts MessageStartProcess. Publishing it names no process. So the
    // cluster would start that process for the aggregate of TaskProcess, and the start
    // would be written down under the wrong process. The core refuses it before phase one.
    // It uses the message names which this adapter read out of the model
    final var refused = assertThrows(
        RuntimeException.class,
        () -> transactionTemplate.execute(status -> {
          final var saved = repository.save(new TaskDockerAggregate());
          workflowService.startByMessage(saved, "C8OrderPlaced");
          return saved.getId();
        }));

    final var said = messagesOf(refused);
    assertTrue(
        said.contains("Message 'C8OrderPlaced' does not start BPMN process 'TaskProcess'"),
        () -> "the message names the message and the process of the caller: "
            + said);

  }

  private static String messagesOf(
      final Throwable thrown) {

    final var messages = new StringBuilder();
    for (var cause = thrown; cause != null; cause = cause.getCause() == cause
        ? null
        : cause.getCause()) {
      messages
          .append(cause.getMessage())
          .append('\n');
    }
    return messages.toString();

  }

  @Autowired
  private ApplicationContext applicationContext;

  private long lastStartedInstanceKey;

  @Test
  @DisplayName("A gateway right after a @WorkflowTask sees the values that task produced")
  public void gatewayAfterTaskSeesTheNewValues() throws Exception {

    TaskDockerWorkflowService.OBSERVED_VARIABLES.clear();
    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    // the instance is started with the aggregate-ID variable ONLY. The completion of
    // 'syncTask' has to push everything which the gateway evaluates
    startSecondaryProcess("SyncProcess", aggregateId);

    awaitUntil(
        () -> {
          final var results = results(aggregateId);
          return (results != null) && (results.contains("sync-approved") || results.contains("sync-rejected"));
        },
        60000,
        "SyncProcess to pass the FEEL gateway");

    // the exclusive gateway's FEEL condition '=approved = true' branched on the value which
    // the @WorkflowTask method produced. Without the push it would have taken the default
    // (rejected) flow
    assertEquals("sync-task|sync-approved", results(aggregateId));

    // what the cluster delivered to the task after the gateway proves what was pushed: the
    // shared attributes, but never a @NoSyncWithBPMS one
    assertEquals("true", TaskDockerWorkflowService.OBSERVED_VARIABLES.get("approved"));
    assertEquals("sync-task", TaskDockerWorkflowService.OBSERVED_VARIABLES.get("results"));
    assertEquals(
        "null",
        TaskDockerWorkflowService.OBSERVED_VARIABLES.get("secret"),
        "a @NoSyncWithBPMS attribute must never appear in the cluster's variables");

  }

  private void startSecondaryProcess(
      final String bpmnProcessId,
      final Long aggregateId) {

    // secondary processes are started directly at the cluster, with the aggregate-ID
    // variable. That is exactly what VanillaBP's start writes. The name-clash avoidance
    // mode of these tests is 'use-prefix', so the CLUSTER knows the process under its
    // prefixed id
    lastStartedInstanceKey = workflowServiceClient()
        .newCreateInstanceCommand()
        .bpmnProcessId("test-app__"
            + bpmnProcessId)
        .latestVersion()
        .variable("id", String.valueOf(aggregateId))
        .send()
        .join()
        .getProcessInstanceKey();

  }

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  /**
   * Waits until the query API answers with the instance which the test just started at the
   * cluster.
   * <p>
   * A correlation which takes the platform's path first asks this adapter whether it knows
   * the workflow. The adapter answers that from a search for the aggregate-ID variable. The
   * platform waits the visibility window out for the workflows it started itself. An
   * instance created directly at the cluster is not one of those. So here the fixture waits
   * for the export, and this test does not measure it.
   */
  private void awaitTheQueryApiKnowingTheStartedInstance(
      final Long aggregateId) throws InterruptedException {

    final Long startedInstanceKey = lastStartedInstanceKey;
    awaitUntil(
        () -> workflowServiceClient()
            .newProcessInstanceSearchRequest()
            // variable values are stored as JSON, so a String value is searched WITH its quotes
            .filter(filter -> filter.variables(Map.of("id", "\"%s\"".formatted(aggregateId))))
            .send()
            .join()
            .items()
            .stream()
            .anyMatch(instance -> startedInstanceKey.equals(instance.getProcessInstanceKey())),
        30000,
        "the query API to know the instance started for aggregate "
            + aggregateId);

  }

  /**
   * Whether the cluster has forgotten the job, asked with an engine COMMAND.
   * <p>
   * A completion which travelled through the outbox is over when the job is gone. A test
   * which wants to know that has two ways to ask. The search API answers from the exporter,
   * so these tests avoid it. The partition answers an UpdateJobTimeout exactly, and the
   * command moves nothing forward. While the job is there, it renews the lock which the
   * adapter renews anyway. Once the job is gone, the cluster refuses it with a 404. This
   * waits for that refusal.
   *
   * @param taskId The job key the handler reported
   * @return Whether the cluster refused the command because the job is gone
   */
  private boolean theClusterNoLongerKnowsTheJob(
      final String taskId) {

    try {
      workflowServiceClient()
          .newUpdateTimeoutCommand(Long.parseLong(taskId))
          .timeout(Duration.ofMinutes(2))
          .send()
          .join();
      return false;
    } catch (final RuntimeException e) {
      if (Camunda8Errors.jobAlreadyGone(e)) {
        return true;
      }
      throw e;
    }

  }

  /**
   * The same question about a user task, asked with the command which the adapter's own
   * awareness probe uses. An update which carries nothing but an audit action changes no
   * attribute. A user task which is over answers it with a 404.
   *
   * @param taskId The user-task key the creating listener reported
   * @return Whether the cluster refused the command because the user task is gone
   */
  private boolean theClusterNoLongerKnowsTheUserTask(
      final String taskId) {

    try {
      workflowServiceClient()
          .newUpdateUserTaskCommand(Long.parseLong(taskId))
          .action("io.vanillabp:it-probe")
          .send()
          .join();
      return false;
    } catch (final RuntimeException e) {
      if (Camunda8Errors.jobAlreadyGone(e)) {
        return true;
      }
      throw e;
    }

  }

  @Test
  @DisplayName("A task whose job waits in the queue is a task the cluster still holds")
  public void aJobInTheQueueIsNotAnOutage() throws Exception {

    // An asynchronous task keeps its job locked for async-task-lock-renewal. When that
    // window passes, the cluster puts the job back into the queue until a worker takes it
    // again. A probe which arrives in that gap is refused. It used to be refused in a way
    // which the adapter read as an outage, for a task which is perfectly alive.
    //
    // The test makes the gap and does not wait for it. It uses a model for which this
    // application has no method. So nobody activates the job again while the test looks at
    // it.
    final var jobType = "dormant-"
        + System.nanoTime();
    final var model = Bpmn
        .createExecutableProcess("DormantJobProcess")
        .startEvent()
        .serviceTask("Wait", task -> task.zeebeJobType(jobType))
        .endEvent()
        .done();
    workflowServiceClient()
        .newDeployResourceCommand()
        .addProcessModel(model, "dormant-job-process.bpmn")
        .send()
        .join();
    workflowServiceClient()
        .newCreateInstanceCommand()
        .bpmnProcessId("DormantJobProcess")
        .latestVersion()
        .send()
        .join();

    @SuppressWarnings("unchecked")
    final var c8ProcessService = (Camunda8ProcessService<TaskDockerAggregate>) applicationContext
        .getBean("Camunda8_ProcessService_c8");

    // the job as the cluster hands it out, with a lock of one second
    final var jobKey = activateOne(jobType, Duration.ofSeconds(1));

    // and now the case which an application meets. The lock runs out, and the job goes back
    // into the queue. Nobody serves this job type, so nothing takes it from there
    Thread.sleep(3000);

    assertEquals(
        WorkflowAwareness.ACTIVE,
        c8ProcessService.awarenessOfTask(SCOPE, "irrelevant", String.valueOf(jobKey)),
        "a task whose lock ran out is still a task the cluster holds");

    // this gives the assertion above its meaning: the job really was in the queue while it
    // was probed. The probe is refused in that state, so it moved nothing. And no worker of
    // this application serves the type
    assertEquals(
        jobKey,
        activateOne(jobType, Duration.ofSeconds(30)),
        "the same job was waiting in the queue, so the probe met the refused answer");

  }

  /**
   * Activates one job of the given type and returns its key. It waits until the cluster
   * offers one at all.
   */
  private long activateOne(
      final String jobType,
      final Duration lock) throws Exception {

    return activateOneJob(jobType, lock).getKey();

  }

  /**
   * Activates one job of the given type and returns the job itself. It waits until the
   * cluster offers one at all. A caller which needs more of the job than its key reads it
   * from here: what it carries, and how many attempts it has left.
   *
   * @param jobType The job type to ask for
   * @param lock How long the activation locks the job
   * @return The job the cluster handed out
   */
  private ActivatedJob activateOneJob(
      final String jobType,
      final Duration lock) throws Exception {

    final var activated = new AtomicReference<ActivatedJob>();
    awaitUntil(
        () -> {
          // 'job-lease: use' is configured here. A leased job is never handed to an
          // activation which does not ask for a lease
          final var jobs = Camunda8JobLease
              .leaseTheActivation(
                  workflowServiceClient()
                      .newActivateJobsCommand()
                      .jobType(jobType)
                      .maxJobsToActivate(1)
                      .timeout(lock))
              .send()
              .join()
              .getJobs();
          if (jobs.isEmpty()) {
            return Boolean.FALSE;
          }
          activated.set(jobs.getFirst());
          return Boolean.TRUE;
        },
        60000,
        "the cluster to offer the job of type '"
            + jobType
            + "'");
    return activated.get();

  }

  private CamundaClient workflowServiceClient() {

    return clientFactoryRegistry
        .getFactory("c8")
        .getClient();

  }

}
