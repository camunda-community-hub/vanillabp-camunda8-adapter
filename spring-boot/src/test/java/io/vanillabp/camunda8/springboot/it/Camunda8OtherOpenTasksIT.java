package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.TaskEvent;

/**
 * Two asynchronous tasks open in one workflow, and an interrupting boundary event takes one
 * of them away.
 * <p>
 * Zeebe tells no worker about a job it removed, so nothing in the application would ever
 * learn about that task. What closes the gap is the next wake-up of the SAME workflow: the
 * core looks at the tasks it still believes are open in it, this adapter asks the cluster
 * about each of them, and the ones the cluster no longer has are reported as
 * <code>CANCELED</code>.
 * <p>
 * The wake-up here is the redelivery of the other task's job. An open asynchronous task keeps
 * its job locked for <code>async-task-lock-renewal</code>, which this class sets to three
 * seconds, so the cluster hands that job to the worker again shortly after the boundary event
 * fired.
 * <p>
 * Nothing in this is about one line. It runs wherever the module runs, which is what keeps it
 * from being read as an 8.10 feature.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = {
        "spring.config.name=camunda8-it",
        // the job of the task which stays open goes back to the cluster after three
        // seconds, and the redelivery is the wake-up this test is about
        "vanillabp.adapters.c8.async-task-lock-renewal=PT3S"
    })
public class Camunda8OtherOpenTasksIT extends SpringBootTestOnTheSharedCluster {

  @Autowired
  private OtherOpenTasksDockerWorkflowService workflowService;

  @Autowired
  private OtherOpenTasksDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Test
  @DisplayName("A task a boundary event took away is reported at the next wake-up of the workflow")
  public void theOtherOpenTaskIsReportedAsCanceled() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow(new OtherOpenTasksDockerAggregate()).getId());
    OtherOpenTasksDockerWorkflowService.WHAT_HAPPENED.remove(String.valueOf(aggregateId));

    awaitUntil(
        () -> (taskOf(aggregateId, true) != null) && (taskOf(aggregateId, false) != null),
        60000,
        "both asynchronous tasks to be open at once");
    final var takenAwayTaskId = taskOf(aggregateId, true);
    assertNotNull(takenAwayTaskId);

    // the boundary event removes the job of the first task, and the cluster says nothing
    // about it to anybody
    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.correlate(aggregate, "TakeTaskAway");
    });

    // the wait ends at the handler's COMMIT, because the aggregate read below is written
    // by that commit. The map the handler fills is already set a moment earlier, while
    // its transaction is still open, so waiting for the map and reading the aggregate
    // right after can read the aggregate before the commit wrote anything into it
    awaitUntil(
        () -> repository
            .findById(aggregateId)
            .map(OtherOpenTasksDockerAggregate::getWhatHappenedToTheTakenTask)
            .orElse(null) != null,
        120000,
        "the next wake-up of the workflow to report the task which is gone");

    assertEquals(
        TaskEvent.Event.CANCELED.name(),
        OtherOpenTasksDockerWorkflowService.WHAT_HAPPENED.get(String.valueOf(aggregateId)),
        "the task the boundary event took away is reported as canceled");
    assertEquals(
        TaskEvent.Event.CANCELED.name(),
        transactionTemplate
            .execute(
                status -> repository
                    .findById(aggregateId)
                    .orElseThrow()
                    .getWhatHappenedToTheTakenTask()),
        "and the handler ran in a transaction of the application, so the aggregate holds it too");

  }

  /**
   * The job key of one of the two tasks, or <code>null</code> while its handler has not run
   * yet.
   *
   * @param aggregateId The workflow aggregate
   * @param theTakenOne Whether the task the boundary event will take away is meant
   */
  private String taskOf(
      final Long aggregateId,
      final boolean theTakenOne) {

    return transactionTemplate
        .execute(
            status -> repository
                .findById(aggregateId)
                .map(
                    aggregate -> theTakenOne
                        ? aggregate.getTakenAwayTaskId()
                        : aggregate.getStayingTaskId())
                .orElse(null));

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
