package io.vanillabp.camunda8.springboot.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.UserTask;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.client.Camunda8UserTaskProbe;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Completing a user task while the cluster is already ending it. The test FORCES the two
 * states in which this happens, <code>CANCELING</code> and <code>COMPLETING</code>, and does
 * not race for them.
 * <p>
 * The model has a <code>canceling</code> and a <code>completing</code> listener whose job
 * types nothing subscribes to. So a task whose workflow was canceled stays in
 * <code>CANCELING</code>, and a task somebody else completed stays in <code>COMPLETING</code>,
 * for as long as this test wants.
 * <p>
 * In both states the cluster refuses the empty update of the pre-commit check with HTTP
 * <code>409</code>, the same answer it gives for a task it is still creating. Read alone, that
 * answer says "the task is there", and the caller's transaction committed. The completion of
 * phase two then met a task which was gone, and nobody could tell the caller any more.
 * Version 1 rolled the transaction back in these two states (V1 commit <code>bf242ba</code>),
 * and this adapter does so again. Why is decision 83 in the repository's DECISIONS.md. The
 * state is read from the search, which runs behind the engine. So the test waits until the
 * search reports the state, as an application meets it when the state lasts.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = ListenerTestApplication.class,
    properties = "spring.config.name=camunda8-listeners-it")
public class Camunda8UserTaskAlreadyEndingIT extends SpringBootTestOnTheSharedCluster {

  private static final String THE_CANCELING_LISTENER_NOBODY_SERVES = "aCancelingListenerNobodyServes";

  private static final String THE_COMPLETING_LISTENER_NOBODY_SERVES = "aCompletingListenerNobodyServes";

  @Autowired
  private AlreadyEndingDockerWorkflowService workflowService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @Autowired
  private AlreadyEndingDockerAggregateRepository repository;

  @Test
  @DisplayName("A user task whose workflow is being canceled is not completed, and the transaction rolls back")
  public void aUserTaskInCancelingRollsTheCompletionBack() throws Exception {

    final var aggregateId = startAndAwaitTheTask();
    final var taskId = AlreadyEndingDockerWorkflowService.TASK_IDS.get(aggregateId);

    // cancel the workflow. The task waits in CANCELING for the listener nobody serves
    client()
        .newCancelInstanceCommand(theTask(taskId).getProcessInstanceKey())
        .send()
        .join();
    awaitUntil(
        () -> "CANCELING".equals(whatTheSearchSaysAbout(taskId)),
        60000,
        "the cluster to report the user task as CANCELING");

    assertTheCompletionRollsBack(aggregateId, taskId);

    // let the cancellation finish, so the workflow does not outlive the test
    answer(THE_CANCELING_LISTENER_NOBODY_SERVES);

  }

  @Test
  @DisplayName("A user task somebody else is completing is not completed again, and the transaction rolls back")
  public void aUserTaskInCompletingRollsTheCompletionBack() throws Exception {

    final var aggregateId = startAndAwaitTheTask();
    final var taskId = AlreadyEndingDockerWorkflowService.TASK_IDS.get(aggregateId);

    // somebody else completes the task, a task list for example. The task waits in
    // COMPLETING for the listener nobody serves. The answer of the cluster comes only once
    // the task is completed, so it is not waited for here
    client()
        .newCompleteUserTaskCommand(Long.parseLong(taskId))
        .send();
    awaitUntil(
        () -> "COMPLETING".equals(whatTheSearchSaysAbout(taskId)),
        60000,
        "the cluster to report the user task as COMPLETING");

    assertTheCompletionRollsBack(aggregateId, taskId);

    answer(THE_COMPLETING_LISTENER_NOBODY_SERVES);

  }

  private Long startAndAwaitTheTask() throws InterruptedException {

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow().getId());
    assertNotNull(aggregateId);
    awaitUntil(
        () -> AlreadyEndingDockerWorkflowService.TASK_IDS.get(aggregateId) != null,
        120000,
        "the application to be told about the user task");
    final var taskId = AlreadyEndingDockerWorkflowService.TASK_IDS.get(aggregateId);
    awaitUntil(
        () -> "CREATED".equals(whatTheSearchSaysAbout(taskId)),
        60000,
        "the cluster to report the user task as CREATED");
    return aggregateId;

  }

  private void assertTheCompletionRollsBack(
      final Long aggregateId,
      final String taskId) {

    // the premise. The engine refuses the empty update with the answer it also gives for a
    // task it is still creating. So the answer alone cannot tell the two apart
    final var refusal = assertThrows(
        RuntimeException.class,
        () -> Camunda8UserTaskProbe.askTheEngine(client(), Long.parseLong(taskId)),
        "the engine refuses the empty update of a task it is ending");
    assertTrue(
        Camunda8Errors.refusedAboutAUserTaskItHolds(refusal),
        "and it refuses it as a task it still holds: "
            + refusal);

    final var thrown = assertThrows(
        RuntimeException.class,
        () -> transactionTemplate
            .executeWithoutResult(
                status -> workflowService
                    .completeTheUserTask(repository.findById(aggregateId).orElseThrow(), taskId, "completed")),
        "the application learns that the task cannot be completed any more");
    assertTrue(
        String.valueOf(thrown.getMessage()).contains(taskId),
        "the message names the task: "
            + thrown.getMessage());
    assertNull(
        repository.findById(aggregateId).orElseThrow().getNote(),
        "and what the application changed in the same transaction is rolled back");

  }

  /**
   * Activates the open job of a listener nobody serves and completes it.
   */
  private void answer(
      final String jobType) {

    final var jobs = client()
        .newActivateJobsCommand()
        .jobType(jobType)
        .maxJobsToActivate(1)
        .timeout(Duration.ofSeconds(30))
        .send()
        .join()
        .getJobs();
    assertFalse(jobs.isEmpty(), "the listener job of '"
        + jobType
        + "' has to be open, otherwise the task was never held in its state");
    client()
        .newCompleteCommand(jobs.getFirst().getKey())
        .send()
        .join();

  }

  private UserTask theTask(
      final String taskId) {

    final List<UserTask> found = client()
        .newUserTaskSearchRequest()
        .filter(filter -> filter.userTaskKey(Long.parseLong(taskId)))
        .send()
        .join()
        .items();
    assertEquals(1, found.size(), "the search knows the task");
    return found.getFirst();

  }

  /**
   * What the search says about that user task, or <code>null</code> if it does not know it.
   */
  private String whatTheSearchSaysAbout(
      final String taskId) {

    final List<UserTask> found = client()
        .newUserTaskSearchRequest()
        .filter(filter -> filter.userTaskKey(Long.parseLong(taskId)))
        .send()
        .join()
        .items();
    return found.isEmpty()
        ? null
        : String.valueOf(found.getFirst().getState());

  }

  private CamundaClient client() {

    return clientFactoryRegistry
        .getFactory("c8")
        .getClient();

  }

  private static void awaitUntil(
      final Supplier<Boolean> condition,
      final long milliseconds,
      final String what) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + milliseconds;
    while (System.currentTimeMillis() < deadline) {
      if (Boolean.TRUE.equals(condition.get())) {
        return;
      }
      Thread.sleep(500);
    }
    fail("waited "
        + (milliseconds / 1000)
        + " seconds for "
        + what
        + ", and it never happened");

  }

}
