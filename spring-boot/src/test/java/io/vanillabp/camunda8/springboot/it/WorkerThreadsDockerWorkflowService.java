package io.vanillabp.camunda8.springboot.it;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The two workflows of the worker-threads integration test: one handler
 * blocks its execution slot for a while, the other one has to be served meanwhile by
 * another slot of the SAME adapter.
 */
@Service
@WorkflowService(
    workflowAggregateClass = WorkerThreadsDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "BlockingProcess"))
public class WorkerThreadsDockerWorkflowService {

  /**
   * How long the blocking handler holds its execution slot at most. A test which is done
   * reading ends the block earlier through {@link #RELEASE_THE_SLOT}.
   */
  public static final long BLOCK_MILLIS = 4000;

  /**
   * How long the blocking handler may hold its execution slot in the test which is
   * running - {@link #BLOCK_MILLIS} unless a test asked for a wider cap, which the poll
   * test does because it gives the slot back itself.
   */
  private static final AtomicLong BLOCK_FOR = new AtomicLong(BLOCK_MILLIS);

  /**
   * Lets a test cap the block somewhere else than at {@link #BLOCK_MILLIS}. Put back by
   * {@link #reset()}, which every test using these observations calls.
   *
   * @param millis How long the next blocking handler stays inside at most
   */
  public static void blockFor(
      final long millis) {

    BLOCK_FOR.set(millis);

  }

  /**
   * What lets the blocking handler out before {@link #BLOCK_FOR} runs out. A test which
   * reads something while the slot is busy counts this down once it is done, so the slot
   * stays taken for as long as that test needs rather than for a number written down
   * before the test ran.
   */
  public static volatile CountDownLatch RELEASE_THE_SLOT = new CountDownLatch(1);

  /**
   * Counted down when the blocking handler entered.
   */
  public static volatile CountDownLatch BLOCKING_ENTERED = new CountDownLatch(1);

  /**
   * Counted down when the handler of the other worker ran.
   */
  public static volatile CountDownLatch QUICK_SERVED = new CountDownLatch(1);

  /**
   * Counted down when the blocking task was delivered a SECOND time while the first
   * delivery is still inside its slot.
   * <p>
   * That is the one property of the Camunda client this adapter relies on and cannot see
   * from the outside: a worker has to keep asking the cluster while a job of its own is in
   * a handler. See {@code Camunda8KeepsAskingWhileAHandlerRunsIT}.
   */
  public static volatile CountDownLatch BLOCKING_TASK_SERVED_AGAIN_WHILE_BLOCKED = new CountDownLatch(1);

  /**
   * Whether the blocking handler was still inside when the other one ran - a quick
   * handler served only AFTER the block would prove nothing.
   */
  public static final AtomicBoolean QUICK_SERVED_WHILE_BLOCKED = new AtomicBoolean();

  /**
   * Whether the quick handler ran on a virtual thread (asserted by the virtual-mode
   * variant of the test).
   */
  public static final AtomicBoolean QUICK_SERVED_ON_VIRTUAL_THREAD = new AtomicBoolean();

  private static final AtomicBoolean BLOCKING = new AtomicBoolean();

  private static final AtomicBoolean ALREADY_BLOCKED = new AtomicBoolean();

  /**
   * Puts the shared observations back to their initial state - called by the test
   * which uses them, so nothing a class left behind reaches the next one.
   */
  public static void reset() {

    // a handler which is still blocked waits on the latch it entered with, so that one is
    // counted down before it is replaced. Otherwise a test which failed early would leave
    // its handler inside until the cap ran out, and the class after it would pay for that
    RELEASE_THE_SLOT.countDown();
    RELEASE_THE_SLOT = new CountDownLatch(1);
    BLOCKING_ENTERED = new CountDownLatch(1);
    QUICK_SERVED = new CountDownLatch(1);
    BLOCKING_TASK_SERVED_AGAIN_WHILE_BLOCKED = new CountDownLatch(1);
    QUICK_SERVED_WHILE_BLOCKED.set(false);
    QUICK_SERVED_ON_VIRTUAL_THREAD.set(false);
    BLOCKING.set(false);
    ALREADY_BLOCKED.set(false);
    BLOCK_FOR.set(BLOCK_MILLIS);

  }

  @Autowired
  private ProcessService<WorkerThreadsDockerAggregate> processService;

  public WorkerThreadsDockerAggregate startBlocking() {

    return processService.startWorkflow(new WorkerThreadsDockerAggregate());

  }

  @WorkflowTask(taskDefinition = "blockingTask")
  public void blockingTask(
      final WorkerThreadsDockerAggregate aggregate) throws InterruptedException {

    aggregate.setServedBy("blocking");
    // a redelivery must not block a second time - the block is the test's setup, not
    // the handler's business
    if (ALREADY_BLOCKED.compareAndSet(false, true)) {
      BLOCKING.set(true);
      BLOCKING_ENTERED.countDown();
      try {
        // the block lasts until a test lets the handler out, and BLOCK_FOR is only the
        // cap: a test which is done reading gives the slot back at once, and one which
        // is not keeps it however long it needs
        RELEASE_THE_SLOT.await(BLOCK_FOR.get(), TimeUnit.MILLISECONDS);
      } finally {
        BLOCKING.set(false);
      }
    } else if (BLOCKING.get()) {
      // a second job of THIS worker, delivered while the first one still holds its slot.
      // Only a client which kept asking while a handler of that worker was running can
      // have brought it here
      BLOCKING_TASK_SERVED_AGAIN_WHILE_BLOCKED.countDown();
    }

  }

  /**
   * Whether the blocking handler is inside its block right now - read by the other
   * workflow service, which is what proves the two ran at the same time.
   *
   * @return Whether a slot is blocked at this moment
   */
  public static boolean isBlocking() {

    return BLOCKING.get();

  }

}
