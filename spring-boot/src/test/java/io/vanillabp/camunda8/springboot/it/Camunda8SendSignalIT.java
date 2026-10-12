package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * End-to-end test of broadcasting a BPMN signal against a real Camunda 8. Two workflows
 * wait at an intermediate signal catch event, and one broadcast continues both of them. The
 * broadcast happens in phase two, after the local transaction was committed. A rolled-back
 * transaction broadcasts nothing, because its outbox entry is gone with it.
 * <p>
 * The models are deployed to the cluster with prefixed identifiers (the module's
 * name-clash-avoidance mode). So the signal reaches its subscription only if the adapter
 * scopes the plain name which the application passed.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// the order is part of the test. A broadcast reaches EVERY workflow of the module which
// waits at that moment. The broadcast test repeats its signal until both of its instances
// continued. Whatever is still in flight from that loop would continue the instance of the
// rollback test as well. That made it fail in the GitHub build
// ('expected <null> but was <recordSignal>'). So the rollback test runs FIRST, on a cluster
// which nobody signalled yet. It broadcasts itself once it is done, to show that its
// workflow really waited. That broadcast is harmless: it happens after its own assertion,
// and the other test brings workflows of its own.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8SendSignalIT extends SpringBootTestOnTheSharedCluster {

  @Autowired
  private SignalDockerWorkflowService workflowService;

  @Autowired
  private SignalDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  private Long start() {

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow().getId());
    assertNotNull(aggregateId);
    return aggregateId;

  }

  @Test
  @Order(2)
  @DisplayName("one broadcast continues every workflow waiting for the signal")
  public void broadcastContinuesEveryWaitingWorkflow() throws Exception {

    final var first = start();
    final var second = start();

    // A SIGNAL IS NOT BUFFERED. It reaches whoever waits at that very moment. The instances
    // here are created after the commit (phase two), and they need a moment to reach the
    // catch event. So the test broadcasts REPEATEDLY until both continued, and does not
    // guess a sleep. An application which cared would do the same. It is harmless, because a
    // signal has no deduplication anyway
    broadcastUntilEveryWorkflowContinued(first, second);

  }

  /**
   * Broadcasts until every named workflow continued. That shows that each of them really
   * waited at the catch event.
   * <p>
   * The deadline is long, because this module also runs a two-container test. Under that
   * load the cluster needs longer to reach the catch event. And the broadcast has to keep
   * meeting a workflow which already waits.
   *
   * @param aggregateIds The workflows waiting for the signal
   */
  private void broadcastUntilEveryWorkflowContinued(
      final Long... aggregateIds) throws Exception {

    final var deadline = System.currentTimeMillis() + 150_000;
    while (!everyWorkflowContinued(aggregateIds)) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("the workflows waiting for the signal never continued");
      }
      // the application passes the PLAIN signal name. The deployed model carries the
      // prefixed one
      transactionTemplate.executeWithoutResult(status -> workflowService.broadcast("OrderReceived"));
      Thread.sleep(2000);
    }

  }

  private boolean everyWorkflowContinued(
      final Long... aggregateIds) {

    return List
        .of(aggregateIds)
        .stream()
        .allMatch(aggregateId -> "recordSignal".equals(
            repository
                .findById(aggregateId)
                .map(SignalDockerAggregate::getProcessedBy)
                .orElse(null)));

  }

  /**
   * How long the aggregate is watched after a rolled-back broadcast. Three seconds, while a
   * broadcast which committed needs milliseconds. The dispatch begins right after the commit,
   * and a signal reaches a waiting workflow in the same step.
   * <p>
   * This is a safety margin, not a budget which anybody has to beat. A machine which leaves
   * this JVM without a turn only makes the silence longer. And the test asserts afterwards
   * that nothing reached the aggregate at all.
   */
  private static final long UNTIL_A_BROADCAST_WOULD_HAVE_ARRIVED = 3000;

  @Test
  @Order(1)
  @DisplayName("a broadcast in a rolled-back transaction never reaches the cluster")
  public void rollbackBroadcastsNothing() throws Exception {

    final var aggregateId = start();

    try {
      transactionTemplate.executeWithoutResult(status -> {
        workflowService.broadcast("OrderReceived");
        throw new RuntimeException("test rollback");
      });
    } catch (final RuntimeException e) {
      assertEquals("test rollback", e.getMessage());
    }

    // the outbox entry with the broadcast was part of the rolled-back transaction
    Thread.sleep(UNTIL_A_BROADCAST_WOULD_HAVE_ARRIVED);
    assertNull(
        repository
            .findById(aggregateId)
            .map(SignalDockerAggregate::getProcessedBy)
            .orElse(null));

    // and the workflow really was there to be signalled: a committed broadcast reaches it.
    // A pause after the start used to stand in for this. Without it, the silence above
    // could just as well be a workflow which had not reached its catch event yet
    broadcastUntilEveryWorkflowContinued(aggregateId);

  }

}
