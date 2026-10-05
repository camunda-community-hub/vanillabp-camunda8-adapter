package io.vanillabp.camunda8.client;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * The executor of an adapter running <code>worker-threads: virtual</code>: a virtual thread
 * per handler invocation, and the timing on the platform threads
 * {@link Camunda8Executor} keeps for it.
 * <p>
 * <b>The bound.</b> A virtual thread per submitted task means no limit, and the client's own
 * limit is per worker, so an adapter with fifteen workers would allow fifteen times
 * <code>max-jobs-active</code> transactions at the same time. The submitted task therefore
 * takes a permit of a semaphore before it runs and gives it back afterwards. The permit is
 * taken INSIDE the virtual thread rather than in {@code execute}: the thread calling that is
 * the client's, and blocking it would stall the delivery of every other worker, which is the
 * defect this whole mode exists to avoid. A virtual thread parked on the semaphore costs
 * almost nothing.
 * <p>
 * <b>Streaming adds a second bound.</b> With <code>stream-enabled: true</code> the client
 * wraps whatever executor it was given in its own semaphore of <code>max-jobs-active</code>
 * permits whose acquire waits for the job timeout. That one is per worker and applies before
 * this one, so with streaming on the effective limit is the smaller of the two.
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
   * A handler which found no permit has a virtual thread of its own already and is parked
   * on the semaphore.
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
