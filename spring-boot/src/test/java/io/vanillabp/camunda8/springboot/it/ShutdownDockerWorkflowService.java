package io.vanillabp.camunda8.springboot.it;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The two workflows of the shutdown integration test: one handler outlives the
 * grace period and is cut off by the closing client, the other one finishes inside it.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ShutdownDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ShutdownProcess"),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "ShutdownGraceProcess"))
public class ShutdownDockerWorkflowService {

  /**
   * How long the handler which is meant to be cut off holds its execution slot - long
   * enough to outlive any grace period the test configures.
   */
  public static final long BLOCK_MILLIS = 60000;

  /**
   * How long the handler which is meant to finish holds its slot - well below the grace.
   */
  public static final long SHORT_MILLIS = 500;

  /**
   * Counted down when the handler which will be cut off entered.
   */
  public static volatile CountDownLatch BLOCKING_ENTERED = new CountDownLatch(1);

  /**
   * Counted down when the handler which finishes inside the grace entered.
   */
  public static volatile CountDownLatch GRACEFUL_ENTERED = new CountDownLatch(1);

  /**
   * Counted down when that handler returned - the shutdown has to wait for exactly this.
   */
  public static volatile CountDownLatch GRACEFUL_RETURNED = new CountDownLatch(1);

  /**
   * How often each handler ran, so a redelivery cannot be mistaken for the first run.
   */
  public static final AtomicInteger BLOCKING_INVOCATIONS = new AtomicInteger();

  /**
   * Whether the blocking handler already blocked once. A redelivery must return right
   * away: the block is the test's setup, not the handler's business.
   */
  private static final AtomicBoolean ALREADY_BLOCKED = new AtomicBoolean();

  /**
   * Puts the shared observations back to their initial state - called by the test which
   * uses them, so nothing a class left behind reaches the next one.
   */
  public static void reset() {

    BLOCKING_ENTERED = new CountDownLatch(1);
    GRACEFUL_ENTERED = new CountDownLatch(1);
    GRACEFUL_RETURNED = new CountDownLatch(1);
    BLOCKING_INVOCATIONS.set(0);
    ALREADY_BLOCKED.set(false);

  }

  @Autowired
  private ProcessService<ShutdownDockerAggregate> processService;

  public ShutdownDockerAggregate startBlocking() {

    return processService.startWorkflow(new ShutdownDockerAggregate());

  }

  @WorkflowTask(taskDefinition = "shutdownTask")
  public void shutdownTask(
      final ShutdownDockerAggregate aggregate) throws InterruptedException {

    BLOCKING_INVOCATIONS.incrementAndGet();
    aggregate.setResult("blocked");
    if (ALREADY_BLOCKED.compareAndSet(false, true)) {
      BLOCKING_ENTERED.countDown();
      TimeUnit.MILLISECONDS.sleep(BLOCK_MILLIS);
    }

  }

  @WorkflowTask(taskDefinition = "gracefulTask")
  public void gracefulTask(
      final ShutdownDockerAggregate aggregate) throws InterruptedException {

    GRACEFUL_ENTERED.countDown();
    aggregate.setResult("graceful");
    TimeUnit.MILLISECONDS.sleep(SHORT_MILLIS);
    GRACEFUL_RETURNED.countDown();

  }

}
