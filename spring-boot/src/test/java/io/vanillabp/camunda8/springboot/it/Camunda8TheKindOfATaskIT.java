package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.camunda.client.CamundaClient;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which kind of task the delivery record says an id is, against a real cluster.
 * <p>
 * A Camunda 8 job key and a Camunda 8 user-task key live in two namespaces. An application
 * may hand a user-task key to {@code completeTask}. The engine's job commands find nothing
 * under that key, however alive the user task is. The answer it used to get listed
 * everything the id could be, instead of naming the mistake. The record has carried the kind
 * since story 824, and the platform asks the adapter for it. This class measures that this
 * adapter answers.
 * <p>
 * Both kinds are delivered as they really arrive. Both are read back through
 * {@link TaskDeliveryLog#recordOfTask}, which is the method the BPMS election asks. So the
 * test reads what a caller which names that id reads. It reads from the table, not from the
 * invocation context. This is about the defect of a value which only the context knows. The
 * log only answers for a task which is left to the application. Those are the only ids an
 * application ever names. Nobody can address a task any more which this adapter completed
 * itself.
 * <p>
 * The class brings a database of its own. The delivery log is not emptied between the test
 * classes of this module. Their workflow aggregates all start their ids at one. So a record
 * of another class would be read here as if it were ours.
 * <p>
 * And it completes both tasks before it is done. This class learned that the hard way. A
 * database of its own separates the database, not the cluster. The next class cancels an
 * open Camunda-managed user task which is left on the shared cluster. The
 * {@code canceling} listener job of that cancellation is then served to the next
 * application, which never held this class's workflow aggregate. Such a delivery is failed
 * with no retries left, on purpose. So the job dies, the cluster raises an incident, and the
 * user task never leaves {@code CANCELING}. The cleanup of every later class then waits its
 * minute out for a job which nobody can activate any more.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = {
        "spring.config.name=camunda8-it",
        // a database which no other class of this module writes into, see above
        "spring.datasource.url=jdbc:h2:mem:c8-the-kind-of-a-task-it;DB_CLOSE_DELAY=-1"
    })
public class Camunda8TheKindOfATaskIT extends SpringBootTestOnTheSharedCluster {

  /**
   * The workflow module under which these models are deployed. It is part of the question
   * which the election asks the log.
   */
  private static final String MODULE = "test-app";

  @Autowired
  private TaskDockerWorkflowService workflowService;

  @Autowired
  private TaskDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @Autowired
  private TaskDeliveryLog deliveryLog;

  /**
   * A task this class parked at the cluster and has to complete before it is done.
   *
   * @param aggregateId The workflow aggregate it was delivered for
   * @param taskId The id the handler was given
   * @param itIsAUserTask Whether the id is a user-task key, which decides both the command
   *          which completes it and the command which asks whether it is gone
   */
  private record ParkedTask(Long aggregateId, String taskId, boolean itIsAUserTask) {
  }

  private final List<ParkedTask> parkedTasks = new ArrayList<>();

  @Test
  @DisplayName("The record says whether an open id is a job or a user task")
  public void theRecordSaysWhichKindAnIdIs() throws Exception {

    // AsyncProcess parks at a @TaskId task. So the job stays open, and its record stays the
    // record of a task which the application still holds
    final var taskAggregateId = seedAndStart("AsyncProcess");
    awaitUntil(
        () -> taskIdOf(taskAggregateId) != null,
        60000,
        "the asynchronous task to report its job key");
    parkedTasks.add(new ParkedTask(taskAggregateId, taskIdOf(taskAggregateId), false));
    assertEquals(
        "TASK",
        kindOf("AsyncProcess", taskAggregateId),
        "a job key is reported as a task, because the engine takes it back through its job commands");

    // UserTaskProcess parks at a Camunda-managed user task. Its creating listener notifies
    // the handler with the USER-TASK key, which is a key of the other namespace
    final var userTaskAggregateId = seedAndStart("UserTaskProcess");
    awaitUntil(
        () -> taskIdOf(userTaskAggregateId) != null,
        60000,
        "the creating listener of the user task to report its user-task key");
    parkedTasks.add(new ParkedTask(userTaskAggregateId, taskIdOf(userTaskAggregateId), true));
    assertEquals(
        "USER_TASK",
        kindOf("UserTaskProcess", userTaskAggregateId),
        "a user-task key is reported as a user task, because the job commands find nothing under it");

  }

  /**
   * Completes what this class parked and waits until the cluster has forgotten it.
   * <p>
   * It runs after a failed test as well. A cluster which the next class cannot clean is the
   * more expensive of the two failures. Both models end right after their task, so a
   * completion also ends the workflow.
   *
   * @throws InterruptedException Where a wait is interrupted
   */
  @AfterEach
  public void endWhatThisClassStarted() throws InterruptedException {

    parkedTasks
        .forEach(task -> transactionTemplate.executeWithoutResult(status -> {
          final var aggregate = repository.findById(task.aggregateId()).orElseThrow();
          if (task.itIsAUserTask()) {
            workflowService.completeUserTask(aggregate, task.taskId());
          } else {
            workflowService.completeAsyncTask(aggregate, task.taskId());
          }
        }));
    // the completion travels through the phase-two outbox, so it is sent after the caller's
    // transaction committed. A class which ends before that leaves the task open
    for (final var task : parkedTasks) {
      awaitUntil(
          () -> task.itIsAUserTask()
              ? theClusterNoLongerKnowsTheUserTask(task.taskId())
              : theClusterNoLongerKnowsTheJob(task.taskId()),
          60000,
          "the cluster to forget task "
              + task.taskId());
    }
    parkedTasks.clear();

  }

  /**
   * Saves a workflow aggregate and starts one of the parking processes for it.
   * <p>
   * Both processes are secondary ones of their workflow service. So they are started directly
   * at the cluster, with the aggregate-ID variable which VanillaBP's own start writes. These
   * tests run with {@code name-clash-avoidance: use-prefix}, so the cluster knows the process
   * under its prefixed id.
   *
   * @param bpmnProcessId The process as the application knows it
   * @return The id of the saved workflow aggregate
   */
  private Long seedAndStart(
      final String bpmnProcessId) {

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskDockerAggregate())
        .getId());
    clusterClient()
        .newCreateInstanceCommand()
        .bpmnProcessId("test-app__"
            + bpmnProcessId)
        .latestVersion()
        .variable("id", String.valueOf(aggregateId))
        .send()
        .join();
    return aggregateId;

  }

  /**
   * The id the handler of the parking task was given, or <code>null</code> while it has not
   * run yet.
   *
   * @param aggregateId The workflow aggregate
   * @return The id of the open task as the handler received it
   */
  private String taskIdOf(
      final Long aggregateId) {

    return transactionTemplate
        .execute(status -> repository.findById(aggregateId).map(TaskDockerAggregate::getTaskId).orElse(null));

  }

  /**
   * What the delivery record says the open task of this workflow is.
   *
   * @param bpmnProcessId The process which delivered the task
   * @param aggregateId The workflow aggregate it was delivered for
   * @return The kind as the record carries it, which is <code>null</code> where the
   *         delivering adapter named none
   */
  private String kindOf(
      final String bpmnProcessId,
      final Long aggregateId) {

    final var taskId = taskIdOf(aggregateId);
    return transactionTemplate
        .execute(
            status -> deliveryLog
                .recordOfTask(MODULE, bpmnProcessId, String.valueOf(aggregateId), taskId)
                .orElseThrow(() -> new AssertionError("no open record of task "
                    + taskId
                    + " of "
                    + bpmnProcessId))
                .taskKind());

  }

  /**
   * Whether the cluster has forgotten the job. It is asked with an engine COMMAND, not with
   * the search API, which answers from the exporter and lags behind. While the job is there,
   * an UpdateJobTimeout renews a lock which the adapter renews anyway. For a job which is
   * gone, the answer is a 404. {@code Camunda8TaskProcessingIT} asks the same question the
   * same way.
   *
   * @param taskId The job key the handler reported
   * @return Whether the cluster refused the command because the job is gone
   */
  private boolean theClusterNoLongerKnowsTheJob(
      final String taskId) {

    try {
      clusterClient()
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
   * The same question about a user task, asked with the command which this adapter's own
   * awareness probe uses. An update which carries nothing but an audit action changes no
   * attribute. A user task which is over answers it with a 404.
   *
   * @param taskId The user-task key the creating listener reported
   * @return Whether the cluster refused the command because the user task is gone
   */
  private boolean theClusterNoLongerKnowsTheUserTask(
      final String taskId) {

    try {
      clusterClient()
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

  /**
   * @return The client of the application under test, which is the client of the shared
   *         cluster
   */
  private CamundaClient clusterClient() {

    return clientFactoryRegistry
        .getFactory("c8")
        .getClient();

  }

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final long timeoutMillis,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + timeoutMillis;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(200);
    }

  }

}
