package io.vanillabp.camunda8.client;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The executor of an adapter which runs <code>worker-threads</code> as a number. It has as
 * many platform threads as the number says. The timing runs on the separate threads which
 * {@link Camunda8Executor} keeps for it.
 * <p>
 * The pool IS the bound here. It runs as many handlers as it has threads, and it queues
 * the rest. So nothing else has to hold the number back. What waits for a slot waits in
 * the queue of the pool, not on a semaphore.
 * <p>
 * The queue has no limit on purpose. What may arrive is limited elsewhere. A worker never
 * holds more than <code>max-jobs-active</code> activated jobs at once. And the gate in
 * front of the scheduled polls keeps a worker from asking for more while every slot is
 * busy.
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
          // daemon, like the virtual threads of the other model and like the timing
          // threads. A shutdown which forgot this executor must not keep the JVM up. What
          // a handler in flight is owed is the business of the drain, not of the kind of
          // thread
          thread.setDaemon(true);
          return thread;
        });

  }

  /**
   * A handler which found no free thread waits in the queue of the pool.
   */
  @Override
  public int getWaiting() {

    return handlers.getQueue().size();

  }

  /**
   * Nothing to take. A pool with as many threads as there are slots runs no more handlers
   * at once than the bound allows.
   */
  @Override
  protected void enterSlot() {

  }

  @Override
  protected void leaveSlot() {

  }

}
