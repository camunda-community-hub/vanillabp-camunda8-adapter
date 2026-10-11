package io.vanillabp.camunda8.client;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * The executor of an adapter which runs <code>worker-threads: virtual</code>. It uses one
 * virtual thread per handler invocation. The timing runs on the platform threads which
 * {@link Camunda8Executor} keeps for it.
 * <p>
 * <b>The bound.</b> A virtual thread per submitted task means no limit. The client's own
 * limit is per worker. So an adapter with fifteen workers would allow fifteen times
 * <code>max-jobs-active</code> transactions at the same time. So the submitted task takes
 * a permit of a semaphore before it runs, and gives it back afterwards. The permit is
 * taken INSIDE the virtual thread, not in {@code execute}. The thread which calls
 * {@code execute} belongs to the client. Blocking it would stall the delivery of every
 * other worker, and that is the defect this whole mode exists to avoid. A virtual thread
 * which waits on the semaphore costs almost nothing.
 * <p>
 * <b>Streaming adds a second bound.</b> With <code>stream-enabled: true</code> the client
 * wraps whatever executor it was given in its own semaphore of <code>max-jobs-active</code>
 * permits. Taking one of them waits up to the job timeout. That semaphore is per worker and
 * applies before this one. So with streaming on, the effective limit is the smaller of the
 * two.
 * <p>
 * Why virtual threads are a regular mode rather than a caveat is decision 7 in the repository's
 * DECISIONS.md.
 */
public class Camunda8VirtualThreadExecutor extends Camunda8Executor {

  private final Semaphore slots;

  /**
   * Builds the virtual-thread half and the semaphore which bounds it.
   *
   * @param adapterId The adapter id, used to name the threads
   * @param bound How many handlers may run at the same time
   */
  public Camunda8VirtualThreadExecutor(
      final String adapterId,
      final int bound) {

    super(adapterId, bound, handlingHalf(adapterId));
    this.slots = new Semaphore(bound);

  }

  private static ExecutorService handlingHalf(
      final String adapterId) {

    return Executors.newThreadPerTaskExecutor(
        Thread
            .ofVirtual()
            .name("vanillabp-%s-handler-".formatted(adapterId), 0)
            .factory());

  }

  /**
   * A handler which found no permit already has a virtual thread of its own. It waits on
   * the semaphore.
   */
  @Override
  public int getWaiting() {

    return slots.getQueueLength();

  }

  @Override
  protected void enterSlot() throws InterruptedException {

    slots.acquire();

  }

  @Override
  protected void leaveSlot() {

    slots.release();

  }

}
