package io.vanillabp.camunda8.client;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The executor of an adapter running <code>worker-threads</code> as a number: as many
 * platform threads as the number says, and the timing on the separate threads
 * {@link Camunda8Executor} keeps for it.
 * <p>
 * The pool IS the bound here - it runs as many handlers as it has threads and queues the
 * rest - so nothing else has to hold the number back, and what waits for a slot waits in the
 * pool's queue rather than on a semaphore.
 * <p>
 * The queue is unbounded on purpose. What may arrive is bounded elsewhere: a worker never
 * holds more than <code>max-jobs-active</code> activated jobs at once, and the gate in front
 * of the scheduled polls keeps a worker from asking for more while every slot is busy.
 */
public class Camunda8PlatformThreadExecutor extends Camunda8Executor {

  private final ThreadPoolExecutor handlers;

  /**
   * Builds the pool which is the bound, with as many threads as the configured number.
   *
   * @param adapterId The adapter id, used to name the threads
   * @param bound How many handlers may run at the same time
   */
  public Camunda8PlatformThreadExecutor(
      final String adapterId,
      final int bound) {

    super(adapterId, bound, handlingHalf(adapterId, bound));
    this.handlers = (ThreadPoolExecutor) handling();

  }

  private static ThreadPoolExecutor handlingHalf(
      final String adapterId,
      final int bound) {

    final var nextThread = new AtomicInteger();
    return new ThreadPoolExecutor(
        bound, bound, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
          final var thread = new Thread(
              runnable, "vanillabp-%s-handler-%d".formatted(adapterId, nextThread.getAndIncrement()));
          // daemon like the virtual threads of the other model and like the timing
          // threads: a shutdown which forgot this executor must not keep the JVM up,
          // and what a handler in flight is owed is the drain's business, not the
          // thread's kind
          thread.setDaemon(true);
          return thread;
        });

  }

  /**
   * A handler which found no free thread waits in the pool's queue.
   */
  @Override
  public int getWaiting() {

    return handlers.getQueue().size();

  }

  /**
   * Nothing to take: a pool of as many threads as there are slots runs no more handlers at
   * once than the bound allows.
   */
  @Override
  protected void enterSlot() {

  }

  @Override
  protected void leaveSlot() {

  }

}
