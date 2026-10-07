package io.vanillabp.camunda8.springboot.it;

import static io.vanillabp.camunda8.springboot.it.CompensationDockerWorkflowService.CANCEL_BOOKING;
import static io.vanillabp.camunda8.springboot.it.CompensationDockerWorkflowService.REFUND_PAYMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What Camunda 8 really does with two compensation handlers of one workflow, against a REAL
 * cluster. The model is <code>it/compensation.bpmn</code>: two finished service tasks, a
 * handler on each, and one throw event which compensates both.
 * <p>
 * Camunda 7 answers this with one unit of work: the engine runs the handlers one after the
 * other inside the transaction of the throw event, and a handler which throws makes both run
 * again. This cluster answers the other way round, and this test is what says so:
 * <ul>
 * <li>Both handler jobs are handed out together. Both handlers are inside their method at
 * the same moment, on two threads of the adapter.</li>
 * <li>Each handler is a job of its own, so it is a transaction and a retry of its own. When
 * one handler throws, only that one runs again, after the retry backoff. The handler which
 * succeeded runs once.</li>
 * </ul>
 * The ORDER of the two handlers is not asserted. It was not stable in the measurement: most
 * runs entered the handler of the later activity first, some the other one.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8CompensationIT extends SpringBootTestOnTheSharedCluster {

  private static final Set<String> BOTH_HANDLERS = Set.of(CANCEL_BOOKING, REFUND_PAYMENT);

  @Autowired
  private CompensationDockerWorkflowService workflowService;

  @Autowired
  private CompensationDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 120_000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(250);
    }

  }

  private Long runUntilBothHandlersAreDone(
      final boolean refundFailsOnce) throws InterruptedException {

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow(refundFailsOnce).getId());
    assertNotNull(aggregateId);

    // the task behind the throw event is activated only once both handlers completed
    awaitUntil(
        () -> repository
            .findById(aggregateId)
            .map(CompensationDockerAggregate::getReported)
            .isPresent(),
        "the workflow to have passed the compensation throw event");
    return aggregateId;

  }

  @Test
  @DisplayName("the cluster hands out both handlers together, and they run at the same time on two threads")
  public void bothHandlersRunAtTheSameTime() throws Exception {

    final var aggregateId = runUntilBothHandlersAreDone(false);

    final var observation = CompensationDockerWorkflowService.observationOf(aggregateId);
    assertEquals(BOTH_HANDLERS, Set.copyOf(observation.handlersInTheOrderTheyWereEntered()));
    assertEquals(
        2,
        observation.mostInsideAtOnce(),
        "each handler waited for the other one and met it");
    assertEquals(2, observation.threadsTheHandlersRanOn().stream().distinct().count());

    final var aggregate = repository.findById(aggregateId).orElseThrow();
    assertTrue(aggregate.getBookingCancelled());
    assertTrue(aggregate.getPaymentRefunded());

  }

  @Test
  @DisplayName("a handler which throws runs again on its own, and the handler which succeeded runs once")
  public void aFailingHandlerIsRetriedAlone() throws Exception {

    final var aggregateId = runUntilBothHandlersAreDone(true);

    final var observation = CompensationDockerWorkflowService.observationOf(aggregateId);
    assertEquals(
        1,
        observation.attemptsOf(CANCEL_BOOKING),
        "the other handler's failure is no reason to cancel the booking a second time");
    assertEquals(2, observation.attemptsOf(REFUND_PAYMENT), "the first attempt threw");
    assertEquals(
        2,
        observation.mostInsideAtOnce(),
        "the failing attempt and the other handler ran at the same time");

    // the cancellation was committed by its own job, so the refund's failure did not roll it back
    final var aggregate = repository.findById(aggregateId).orElseThrow();
    assertTrue(aggregate.getBookingCancelled());
    assertTrue(aggregate.getPaymentRefunded());

  }

}
