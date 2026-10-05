package io.vanillabp.camunda8.springboot.listeners;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Completing a user task while the cluster is still creating it, with the state FORCED rather
 * than raced for.
 * <p>
 * VanillaBP notifies an application about a Camunda-managed user task from the
 * <code>creating</code> listener it writes itself, and that listener is the FIRST of the
 * element. The model of this test carries a second <code>creating</code> listener whose job
 * type nothing subscribes to, so the task stays in state <code>CREATING</code> for as long as
 * this test wants it to: the application holds a valid task key and the cluster has not
 * finished creating the task.
 * <p>
 * That is the everyday race an application loses on a busy machine, held still. The cluster
 * refuses every command against such a task with HTTP <code>409</code>, the empty update of
 * phase one and the completion of phase two alike, and the adapter has to read both as "the
 * task is there". The unit test of the same defect is
 * {@code Camunda8UserTaskStillCreatingTest}.
 * <p>
 * The notification arrives on a user-task listener job. The REST gateway of the 8.10 alphas
 * dropped the batch those arrive in, which kept this class off the preview line for a while;
 * {@code 8.10.0-rc1} hands the job out, so it runs on every line again.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = ListenerTestApplication.class,
    properties = "spring.config.name=camunda8-listeners-it")
public class Camunda8UserTaskStillCreatingIT extends SpringBootTestOnTheSharedCluster {

  /**
   * The job type of the listener nobody serves, as the model spells it. It is not prefixed
   * with the workflow module id although this module uses prefixes: a listener job type no
   * method of this application names is a name this application does not own.
   */
  private static final String THE_LISTENER_NOBODY_SERVES = "aCreatingListenerNobodyServes";

  @Autowired
  private StillCreatingDockerWorkflowService workflowService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @Autowired
  private StillCreatingDockerAggregateRepository repository;

  @Test
  @DisplayName("A user task is completed although the cluster has not finished creating it")
  public void aUserTaskWhichIsStillBeingCreatedIsCompleted() throws Exception {

    StillCreatingDockerWorkflowService.TASK_IDS.clear();
    StillCreatingDockerWorkflowService.WHAT_CAME_AFTER.clear();

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow().getId());
    assertNotNull(aggregateId);

    // the notification comes from VanillaBP's own 'creating' listener, which is the first
    // one of the element - so the application holds the key while the second listener keeps
    // the task from ever becoming CREATED
    awaitUntil(
        () -> StillCreatingDockerWorkflowService.TASK_IDS.get(aggregateId) != null,
        120000,
        "the application to be told about the user task");
    final var taskId = StillCreatingDockerWorkflowService.TASK_IDS.get(aggregateId);

    // the state is held, not awaited: the listener job of the second listener is open, and
    // nothing in this application is going to answer it
    awaitUntil(
        () -> "CREATING".equals(whatTheSearchSaysAbout(taskId)),
        60000,
        "the cluster to report the user task as CREATING");
    assertEquals(
        "CREATING",
        whatTheSearchSaysAbout(taskId),
        "the search finds the task and names the state this whole test is about");
    assertEquals(
        "CREATING",
        whatAReadSaysAbout(taskId),
        "and a read of the single task answers it just as well, rather than refusing the reader");

    // this is the line which failed before: the pre-commit check of phase one sent the empty
    // update, read the 409 as a task which is gone and threw out of the caller's commit
    assertDoesNotThrow(
        () -> transactionTemplate
            .executeWithoutResult(
                status -> workflowService
                    .completeTheUserTask(repository.findById(aggregateId).orElseThrow(), taskId)),
        "an application may answer the user task it was just told about");

    // and now the cluster is allowed to finish creating the task, which is what phase two
    // has been waiting for
    answerTheListenerNobodyServes();

    awaitUntil(
        () -> StillCreatingDockerWorkflowService.WHAT_CAME_AFTER.get(aggregateId) != null,
        120000,
        "the workflow to move past the user task");

  }

  /**
   * Activates the open listener job of the second <code>creating</code> listener and
   * completes it, which is what ends state <code>CREATING</code>.
   */
  private void answerTheListenerNobodyServes() {

    final var jobs = client()
        .newActivateJobsCommand()
        .jobType(THE_LISTENER_NOBODY_SERVES)
        .maxJobsToActivate(1)
        .timeout(Duration.ofSeconds(30))
        .send()
        .join()
        .getJobs();
    assertFalse(
        jobs.isEmpty(),
        "the listener job has to be open, otherwise the task was never held in CREATING");
    client()
        .newCompleteCommand(jobs.getFirst().getKey())
        .send()
        .join();

  }

  /**
   * What the search says about that user task, or <code>null</code> where it does not know
   * it. The search reads what an exporter wrote, so a task which was just created may not be
   * there yet.
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

  /**
   * What a read of that ONE user task answers, or the rejection it was refused with. Read
   * rather than asserted anywhere else: an extension of this adapter builds its reports from
   * such a read, and whether a task in <code>CREATING</code> can be read at all decides
   * whether it has to wait for the state to pass.
   */
  private String whatAReadSaysAbout(
      final String taskId) {

    try {
      return String
          .valueOf(
              client()
                  .newUserTaskGetRequest(Long.parseLong(taskId))
                  .send()
                  .join()
                  .getState());
    } catch (final RuntimeException e) {
      return "refused: "
          + e.getMessage();
    }

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
