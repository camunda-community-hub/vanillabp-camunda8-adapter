package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.command.ClientHttpException;
import io.camunda.client.api.command.UpdateTimeoutJobCommandStep1;
import io.camunda.client.api.command.UpdateUserTaskCommandStep1;
import io.camunda.client.api.search.enums.JobState;
import io.camunda.client.api.search.request.JobSearchRequest;
import io.camunda.client.api.search.response.Job;
import io.camunda.client.api.search.response.SearchResponse;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.TaskNotFoundException;

/**
 * What the adapter says when a user-task command answers {@code 404} for a key which is not a
 * user-task key.
 * <p>
 * The command asks about a user-task key, so its {@code 404} means "no user task of that key".
 * For a real user-task key that is the same as "the task is over". For a job key it is not: a
 * user task served by a job worker, the shape VanillaBP modelled up to its release 1.6.3, has no
 * user-task record in the cluster at all, so every user-task command answers {@code 404} about
 * it however open the task is. An application meets such a key while it upgrades, because the
 * task ids of version 1 are data it brings along.
 * <p>
 * So the job side is asked once, on the {@code 404} and nowhere else, and the sentence says what
 * came back. Two questions make it up and they are not interchangeable: the ENGINE says whether it
 * holds a job of that key, with the {@code UpdateJobTimeout} this adapter sends as its existence
 * check anyway, and the index only names the element, the process and the state. Measured against a
 * cluster, the index answered "no job" about a job which was open and kept answering about one which
 * was over, so it can carry neither half of the question.
 * <p>
 * What the caller gets is unchanged, which is why every case below asserts the outcome next to the
 * words.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8A404AboutAJobKeyTest {

  private static final WorkflowScope SCOPE = WorkflowScope.of("loan-approval", "LoanApproval");

  private static final String TASK_ID = "2251799813685322";

  private final CamundaClient client = mock(CamundaClient.class);

  private Camunda8ProcessService<Object> service() {

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing ever contacts - every request of this test meets the mock above
    configuration.setRestAddress("http://localhost:1");
    configuration.setWorkflowVisibilityTimeout(Duration.ZERO);
    final var clientFactory = new Camunda8ClientFactory("c8", configuration) {

      @Override
      public CamundaClient getClient() {
        return client;
      }

      @Override
      public boolean sharesItsCluster() {
        return false;
      }

    };
    return new Camunda8ProcessService<>(
        "c8", clientFactory, Duration.ofDays(14), (
            aggregateClass,
            check) -> check.run(), null);

  }

  @Test
  @DisplayName("The probe names the job key, the element and the way out of the old model")
  public void theProbeNamesTheJobKeyAndTheWayOut() {

    theUserTaskCommandAnswers404();
    theJobSideHolds(Camunda8TaskWiring.TASKDEFINITION_USERTASK_WORKER_V1, JobState.CREATED);

    final var lines = whatWasLoggedWhile(
        () -> assertEquals(
            WorkflowAwareness.UNKNOWN_TO_BPMS,
            service().awarenessOfUserTask(SCOPE, "agg-1", TASK_ID),
            "what the probe answers is unchanged - the sentence beside it is what this is about"));

    final var line = oneLineNaming(lines, TASK_ID);
    assertTrue(
        line.contains("is a JOB key, not a user-task key"),
        () -> "the 404 was about the key, and that is the whole finding: "
            + line);
    assertTrue(
        line.contains(Camunda8TaskWiring.TASKDEFINITION_USERTASK_WORKER_V1) && line
            .contains("Activity_SignTheContract") && line.contains("LoanApproval"),
        () -> "the job type says which kind of task it is, and the element says where to look: "
            + line);
    assertTrue(
        line.contains("CREATED"),
        () -> "whether the task is still open is what the job's state says, so the state is in it: "
            + line);
    assertTrue(
        line.contains("does NOT say the task is over"),
        () -> "the sentence has to take back what a 404 reads like: "
            + line);
    assertTrue(
        line.contains("zeebe:userTask") && line.contains("External form reference"),
        () -> "and it names the way out, which is the model: "
            + line);

  }

  @Test
  @DisplayName("A job of another type is named as one, and points at completeTask")
  public void aServiceTaskKeyIsNamedAsOne() {

    theUserTaskCommandAnswers404();
    theJobSideHolds("approveTheLoan", JobState.CREATED);

    final var lines = whatWasLoggedWhile(
        () -> assertEquals(
            WorkflowAwareness.UNKNOWN_TO_BPMS,
            service().awarenessOfUserTask(SCOPE, "agg-1", TASK_ID),
            "the answer is the same for every kind of key"));

    final var line = oneLineNaming(lines, TASK_ID);
    assertTrue(
        line.contains("is a JOB key, not a user-task key") && line.contains("approveTheLoan"),
        () -> "somebody asked a user-task question about an ordinary task: "
            + line);
    assertTrue(
        line.contains("'ProcessService#completeTask'"),
        () -> "so the message names the call which does fit: "
            + line);
    assertFalse(
        line.contains("release 1.6.3"),
        () -> "and it says nothing about version 1's user tasks, which this is not: "
            + line);

  }

  @Test
  @DisplayName("Where the cluster holds no job either, the task really is gone and the message says that")
  public void withoutAJobTheTaskIsGone() {

    theUserTaskCommandAnswers404();
    theJobSideHoldsNothing();

    final var lines = whatWasLoggedWhile(
        () -> assertEquals(
            WorkflowAwareness.UNKNOWN_TO_BPMS,
            service().awarenessOfUserTask(SCOPE, "agg-1", TASK_ID),
            "the everyday case, and the one the 404 was always read as"));

    final var line = oneLineNaming(lines, TASK_ID);
    assertTrue(
        line.contains("is gone (completed or canceled meanwhile)"),
        () -> "this is the sentence which was right all along: "
            + line);
    assertFalse(
        line.contains("is a JOB key"),
        () -> "and nothing is claimed about a job the cluster does not have: "
            + line);

  }

  @Test
  @DisplayName("Where the index has not written the job yet, the message says the rest without the element")
  public void anIndexWhichLagsCostsTheElementAndNothingElse() {

    theUserTaskCommandAnswers404();
    theEngineSaysItHoldsAJob();
    theJobSearchAnswers(List.of());

    final var lines = whatWasLoggedWhile(
        () -> assertEquals(
            WorkflowAwareness.UNKNOWN_TO_BPMS,
            service().awarenessOfUserTask(SCOPE, "agg-1", TASK_ID),
            "the index decides nothing here, so the answer cannot depend on it either"));

    final var line = oneLineNaming(lines, TASK_ID);
    assertTrue(
        line.contains("is a JOB key, not a user-task key"),
        () -> "the engine said it holds a job, and that is what the finding rests on: "
            + line);
    assertTrue(
        line.contains("not in the cluster's index yet"),
        () -> "and the message says why it cannot name the element: "
            + line);
    assertFalse(
        line.contains("is gone (completed or canceled meanwhile)"),
        () -> "what it must not fall back to is the sentence which was wrong: "
            + line);

  }

  @Test
  @DisplayName("The pre-commit check of completeUserTask says the same, and still aborts the transaction")
  public void thePreCommitCheckSaysTheSame() {

    theUserTaskCommandAnswers404();
    theJobSideHolds(Camunda8TaskWiring.TASKDEFINITION_USERTASK_WORKER_V1, JobState.CREATED);

    final var refused = assertThrows(
        TaskNotFoundException.class,
        () -> PhaseOperations
            .phaseOne(
                service(), PhaseOperation.COMPLETE_USER_TASK, "loan-approval", "LoanApproval", null,
                new Object(), PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, TASK_ID)),
        "the exception the SPI documents for this outcome is unchanged");

    assertTrue(
        refused.getMessage().contains("is a JOB key, not a user-task key"),
        () -> "the caller reads why its id cannot be answered: "
            + refused.getMessage());
    assertTrue(
        refused.getMessage().contains("Aborting the transaction completing it!"),
        () -> "and what the check did about it: "
            + refused.getMessage());

  }

  /**
   * A cluster which refuses every user-task command with the {@code 404} this is about.
   */
  private void theUserTaskCommandAnswers404() {

    final var command = mock(UpdateUserTaskCommandStep1.class, RETURNS_SELF);
    Mockito
        .lenient()
        .when(command.send())
        .thenThrow(
            new ClientHttpException(
                "Failed with code 404", 404, "Expected to update user task with key '"
                    + TASK_ID
                    + "', but no such user task was found"));
    Mockito.lenient().when(client.newUpdateUserTaskCommand(Mockito.anyLong())).thenReturn(command);

  }

  /**
   * A cluster which HOLDS a job of the key asked about, answered the way it answers for a job
   * nobody has activated: the engine refuses the timeout update with 400, which is what says the
   * job is there. The index names it.
   */
  private void theJobSideHolds(
      final String type,
      final JobState state) {

    theEngineSaysItHoldsAJob();
    final var job = mock(Job.class);
    Mockito.lenient().when(job.getType()).thenReturn(type);
    Mockito.lenient().when(job.getElementId()).thenReturn("Activity_SignTheContract");
    Mockito.lenient().when(job.getProcessDefinitionId()).thenReturn("LoanApproval");
    Mockito.lenient().when(job.getState()).thenReturn(state);
    theJobSearchAnswers(List.of(job));

  }

  /**
   * A cluster which holds no job of that key either, which is the everyday case of a task which
   * really is over.
   */
  private void theJobSideHoldsNothing() {

    theEngineRefusesTheTimeoutUpdate(
        new ClientHttpException(
            "Failed with code 404", 404, "Expected to update job with key '"
                + TASK_ID
                + "', but no such job was found"));
    theJobSearchAnswers(List.of());

  }

  private void theEngineSaysItHoldsAJob() {

    theEngineRefusesTheTimeoutUpdate(
        new ClientHttpException("Failed with code 400", 400, "Expected to update job with key '"
            + TASK_ID
            + "', but no such job was found"));

  }

  private void theEngineRefusesTheTimeoutUpdate(
      final RuntimeException rejection) {

    final var command = mock(
        UpdateTimeoutJobCommandStep1.UpdateTimeoutJobCommandStep2.class,
        RETURNS_SELF);
    Mockito.lenient().when(command.send()).thenThrow(rejection);
    final var step1 = mock(UpdateTimeoutJobCommandStep1.class, RETURNS_SELF);
    Mockito.lenient().when(step1.timeout(Mockito.any(Duration.class))).thenReturn(command);
    Mockito.lenient().when(client.newUpdateTimeoutCommand(Mockito.anyLong())).thenReturn(step1);

  }

  private void theJobSearchAnswers(
      final List<Job> jobs) {

    final var search = mock(JobSearchRequest.class, RETURNS_SELF);
    @SuppressWarnings("unchecked")
    final SearchResponse<Job> found = mock(SearchResponse.class);
    Mockito.lenient().when(found.items()).thenReturn(jobs);
    // the future is built BEFORE the stubbing which returns it - mocking inside a when(...)
    // leaves Mockito with an unfinished stubbing
    final var answer = future(found);
    Mockito.lenient().when(search.send()).thenReturn(answer);
    Mockito.lenient().when(client.newJobSearchRequest()).thenReturn(search);

  }

  /**
   * The one line naming the key, which is the message under test.
   */
  private static String oneLineNaming(
      final List<String> lines,
      final String taskId) {

    return lines
        .stream()
        .filter(line -> line.contains(taskId))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError(
                "no line of the adapter named the task at all, and saw: "
                    + lines));

  }

  private static List<String> whatWasLoggedWhile(
      final Runnable probe) {

    final var logWatcher = new ListAppender<ILoggingEvent>();
    logWatcher.start();
    final var adapterLog = (Logger) LoggerFactory.getLogger(Camunda8ProcessService.class);
    adapterLog.addAppender(logWatcher);
    try {
      probe.run();
    } finally {
      adapterLog.detachAppender(logWatcher);
    }
    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel() == Level.INFO)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();

  }

  private static <T> CamundaFuture<T> future(
      final T value) {

    @SuppressWarnings("unchecked")
    final CamundaFuture<T> future = mock(CamundaFuture.class);
    Mockito.lenient().when(future.join()).thenReturn(value);
    return future;

  }

}
