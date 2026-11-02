package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.processservice.Camunda8ProcessService;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.WorkflowSuspendedException;
import io.vanillabp.spi.service.WorkflowEnd;

/**
 * Answers to the tasks of a suspended instance, against a real cluster.
 * <p>
 * While an instance is suspended, the cluster refuses every command about its jobs and user tasks
 * with <code>409</code>. The adapter tells that from the other refusals with the same code by the
 * state of the instance. An application which answers a task while the workflow is suspended
 * gets a {@link WorkflowSuspendedException} before its transaction commits, so nothing of that
 * transaction is saved. Phase two which meets a suspension asks the outbox to wait. Once the
 * workflow is resumed, the same answers go through.
 * <p>
 * It lives in the test sources of the 8.10 line, because the clusters and the clients of the
 * older lines cannot suspend an instance. The fixture it uses is shared, so its workflow is
 * deployed on every line and simply never suspended there.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8SuspendedWorkflowIT extends SpringBootTestOnTheSharedCluster {

  @Autowired
  private SuspendedDockerWorkflowService workflowService;

  @Autowired
  private SuspendedDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @Autowired
  private ApplicationContext applicationContext;

  @Test
  @DisplayName("Completing a task of a suspended workflow rolls the transaction back, and works after the resume")
  public void aTaskOfASuspendedWorkflow() throws Exception {

    final var aggregateId = startAndWaitFor(SuspendedDockerAggregate::getOpenTaskId, "the asynchronous task");
    final var instance = theInstanceOfTheJob(aggregate(aggregateId).getOpenTaskId());

    suspend(instance);
    final var thrown = assertThrows(
        WorkflowSuspendedException.class,
        () -> transactionTemplate.executeWithoutResult(status -> workflowService
            .completeTask(aggregate(aggregateId), "answered while suspended")));
    assertTrue(
        thrown.getMessage().contains(aggregate(aggregateId).getOpenTaskId()),
        "the message names the task: "
            + thrown.getMessage());
    assertNull(aggregate(aggregateId).getAnswer(), "the transaction of the answer rolled back");

    resume(instance);
    transactionTemplate
        .executeWithoutResult(
            status -> workflowService.completeTask(aggregate(aggregateId), "answered after the resume"));
    awaitUntil(
        () -> aggregate(aggregateId).getUserTaskId() != null,
        60000,
        "the workflow to move on to the user task");
    assertEquals("answered after the resume", aggregate(aggregateId).getAnswer());

  }

  @Test
  @DisplayName("Completing a user task of a suspended workflow rolls the transaction back, and works after the resume")
  public void aUserTaskOfASuspendedWorkflow() throws Exception {

    final var aggregateId = startAndWaitFor(SuspendedDockerAggregate::getOpenTaskId, "the asynchronous task");
    transactionTemplate.executeWithoutResult(status -> workflowService.completeTask(aggregate(aggregateId), null));
    awaitUntil(() -> aggregate(aggregateId).getUserTaskId() != null, 60000, "the user task");
    final var instance = theInstanceOfTheUserTask(aggregate(aggregateId).getUserTaskId());

    suspend(instance);
    // the cluster refuses the empty update of the check with the same 409 as a user task which
    // is still being created. Read that way, the transaction would commit, and every attempt of
    // the completion after the commit would be refused
    assertThrows(
        WorkflowSuspendedException.class,
        () -> transactionTemplate.executeWithoutResult(status -> workflowService
            .completeUserTask(aggregate(aggregateId), "answered while suspended")));
    assertNull(aggregate(aggregateId).getAnswer(), "the transaction of the answer rolled back");

    resume(instance);
    transactionTemplate.executeWithoutResult(status -> workflowService
        .completeUserTask(aggregate(aggregateId), "answered after the resume"));
    awaitUntil(
        () -> aggregate(aggregateId).getEndedAs() != null,
        60000,
        "the workflow to end");
    assertEquals(WorkflowEnd.Kind.COMPLETED.name(), aggregate(aggregateId).getEndedAs());

  }

  @Test
  @DisplayName("Phase two which meets a suspended workflow waits, and goes through after the resume")
  public void phaseTwoWaitsForTheResume() throws Exception {

    final var aggregateId = startAndWaitFor(SuspendedDockerAggregate::getOpenTaskId, "the asynchronous task");
    final var taskId = aggregate(aggregateId).getOpenTaskId();
    final var instance = theInstanceOfTheJob(taskId);

    // the suspension comes after the commit of the answer, so phase two is the first to meet it.
    // The test plays the dispatcher of the outbox and calls the adapter directly
    suspend(instance);
    final var waiting = assertThrows(PhaseTwoRetryLater.class, () -> completeInPhaseTwo(aggregateId, taskId));
    assertTrue(
        waiting.getDeadline().isAfter(Instant.now().plus(waiting.getRetryAfter())),
        "the outbox keeps the entry waiting past the next attempt, so a long suspension does not block it");
    assertTrue(processService().isPhaseTwoFailureRepeatable(waiting), "and the wait is never permanent");

    resume(instance);
    assertDoesNotThrow(() -> completeInPhaseTwo(aggregateId, taskId), "the same call goes through after the resume");
    awaitUntil(
        () -> aggregate(aggregateId).getUserTaskId() != null,
        60000,
        "the workflow to move on to the user task");

  }

  private void completeInPhaseTwo(
      final Long aggregateId,
      final String taskId) {

    PhaseOperations
        .phaseTwo(
            processService(),
            PhaseOperation.COMPLETE_TASK,
            "test-app",
            "SuspendableProcess",
            null,
            aggregateId,
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, taskId));

  }

  @SuppressWarnings("unchecked")
  private Camunda8ProcessService<SuspendedDockerAggregate> processService() {

    return (Camunda8ProcessService<SuspendedDockerAggregate>) applicationContext
        .getBean("Camunda8_ProcessService_c8");

  }

  private Long startAndWaitFor(
      final Function<SuspendedDockerAggregate, String> task,
      final String description) throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow(new SuspendedDockerAggregate()).getId());
    awaitUntil(() -> task.apply(aggregate(aggregateId)) != null, 60000, description);
    return aggregateId;

  }

  private SuspendedDockerAggregate aggregate(
      final Long aggregateId) {

    return transactionTemplate.execute(status -> repository.findById(aggregateId).orElseThrow());

  }

  private CamundaClient client() {

    return clientFactoryRegistry
        .getFactory("c8")
        .getClient();

  }

  private void suspend(
      final long instance) throws Exception {

    client()
        .newSuspendProcessInstanceCommand(instance)
        .send()
        .join();
    // the adapter reads the state from the query API, which runs a moment behind
    awaitTheState(instance, ProcessInstanceState.SUSPENDED);

  }

  private void resume(
      final long instance) throws Exception {

    client()
        .newResumeProcessInstanceCommand(instance)
        .send()
        .join();
    awaitTheState(instance, ProcessInstanceState.ACTIVE);

  }

  private void awaitTheState(
      final long instance,
      final ProcessInstanceState state) throws Exception {

    awaitUntil(
        () -> client()
            .newProcessInstanceGetRequest(instance)
            .send()
            .join()
            .getState() == state,
        30000,
        "the query API to report the instance as "
            + state);

  }

  private long theInstanceOfTheJob(
      final String jobKey) throws Exception {

    final var instance = new AtomicLong(0L);
    awaitUntil(
        () -> {
          final var jobs = client()
              .newJobSearchRequest()
              .filter(filter -> filter.jobKey(Long.parseLong(jobKey)))
              .send()
              .join()
              .items();
          if (jobs.isEmpty()) {
            return Boolean.FALSE;
          }
          instance.set(jobs.getFirst().getProcessInstanceKey());
          return Boolean.TRUE;
        },
        60000,
        "the search to report the job");
    return instance.get();

  }

  private long theInstanceOfTheUserTask(
      final String userTaskKey) throws Exception {

    final var instance = new AtomicLong(0L);
    awaitUntil(
        () -> {
          try {
            instance
                .set(client().newUserTaskGetRequest(Long.parseLong(userTaskKey)).send().join().getProcessInstanceKey());
            return Boolean.TRUE;
          } catch (final RuntimeException e) {
            return Boolean.FALSE;
          }
        },
        60000,
        "the search to report the user task");
    return instance.get();

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
