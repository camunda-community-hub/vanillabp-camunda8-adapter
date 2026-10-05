package io.vanillabp.camunda8.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The executor one Camunda 8 adapter instance hands its client, whatever the execution
 * model is. It does two things the client's own executors do not.
 * <p>
 * <b>It keeps the two roles apart.</b> Everything the client SCHEDULES - the poll of every
 * worker, and the opening and re-opening of a job stream - runs on a small pool of platform
 * threads which nothing else uses. Everything the client SUBMITS - the handler invocations -
 * runs on the handling half, which the subclass builds and whose width is the number of
 * execution slots. A handler can therefore never delay the timing, and
 * <code>worker-threads</code> counts handlers running at once rather than threads shared
 * between the handlers and the scheduling of every poll. Why that is done on every release
 * line although only the 8.8 client mixes the two is decision 18 in the repository's
 * DECISIONS.md.
 * <p>
 * <b>It asks for work only while there is capacity to run it.</b> A scheduled task runs
 * when an execution slot is free and is looked at again a moment later when none is, so an
 * adapter whose slots are all busy stops activating jobs which would then wait in front of
 * the slots, spending the lock they were handed out with. What arrives at
 * {@link #schedule(Runnable, long, TimeUnit)} is the poll of a worker and the lifecycle of a
 * job stream, on all three lines this adapter builds against; nothing which keeps an already
 * activated job alive is scheduled there, so nothing has to be told apart and let through.
 * The lock renewal of a long-running task is sent by the handler itself, on the thread which
 * is already holding a slot.
 * <p>
 * Two things the gate does not reach. An activation request is a long poll the gateway holds
 * for <code>request-timeout</code> and answers as soon as a job appears, so a request already
 * parked when the last slot filled still brings its batch; what is held back is the asking
 * AGAIN, which is what turns one batch into a queue. And the client tops a worker up directly
 * from the thread on which a handler just finished, which passes no executor - that worker has
 * just freed a slot, so it is the one case where activating more is the point.
 */
public abstract class Camunda8Executor implements ScheduledExecutorService {

  /**
   * How many platform threads do the timing. Two, because the polls of all workers share
   * them and a scheduled task should not wait for another one which is just being handed
   * over; nothing runs on them longer than that hand-over.
   */
  static final int SCHEDULING_THREADS = 2;

  /**
   * How long a scheduled task waits before it looks for a free execution slot again. Short
   * enough that the free slot is used rather than idled away, long enough that an adapter
   * whose slots stay busy does not wake its timing threads all the time. Nothing else pays
   * for it: the worker whose handler just finished is topped up by the client directly,
   * without passing this gate.
   */
  static final long LOOK_FOR_A_SLOT_AGAIN_MILLIS = 100;

  private final ScheduledExecutorService scheduling;

  private final ExecutorService handling;

  private final int bound;

  /**
   * How many handlers are inside their invocation right now. Counted here rather than read
   * from the handling half, because the two halves are built differently per execution
   * model while this number means the same in both.
   */
  private final AtomicInteger handlersRunning = new AtomicInteger();

  /**
   * Builds the shared half every execution model has: the timing threads, the counter of
   * running handlers, and the bound both models are read against.
   *
   * @param adapterId The adapter id, used to name the threads
   * @param bound How many handlers may run at the same time
   * @param handling The half which runs what the client submits
   */
  protected Camunda8Executor(
      final String adapterId,
      final int bound,
      final ExecutorService handling) {

    this.bound = bound;
    this.handling = handling;
    this.scheduling = Executors.newScheduledThreadPool(
        SCHEDULING_THREADS,
        runnable -> {
          final var thread = new Thread(runnable, "vanillabp-%s-scheduling".formatted(adapterId));
          thread.setDaemon(true);
          return thread;
        });

  }

  /**
   * The limit this adapter id runs its handlers under, whichever execution model it uses.
   *
   * @return How many handlers may run at the same time
   */
  public int getBound() {

    return bound;

  }

  /**
   * How many of the {@link #getBound() bound} are free right now - the number a metric or a
   * test asks for.
   *
   * @return The free execution slots
   */
  public int getFreeSlots() {

    return Math.max(0, bound - handlersRunning.get());

  }

  /**
   * How many activated jobs wait for a free slot - the number telling a full application
   * apart from a busy one. Where they wait differs per execution model, which is why the
   * subclass answers it.
   *
   * @return The waiting handlers
   */
  public abstract int getWaiting();

  /**
   * Whether a handler could start right now. This is what the gate in front of the
   * scheduled tasks reads.
   *
   * @return Whether an execution slot is free
   */
  boolean hasAFreeSlot() {

    return handlersRunning.get() < bound;

  }

  /**
   * Takes the execution slot a submitted handler is about to occupy. The handling half of
   * the platform-thread model is as wide as the bound and therefore needs nothing; the
   * virtual-thread model has no width of its own and takes a permit here.
   *
   * @throws InterruptedException If the executor is going down while the handler waits
   */
  protected abstract void enterSlot() throws InterruptedException;

  /**
   * Gives the slot back after the handler returned.
   */
  protected abstract void leaveSlot();

  /**
   * The handling half, for a subclass which has to reach the executor it handed over.
   *
   * @return The half which runs what the client submits
   */
  protected ExecutorService handling() {

    return handling;

  }

  private Runnable counted(
      final Runnable command) {

    return () -> {
      try {
        enterSlot();
      } catch (final InterruptedException e) {
        // the client is shutting down: the job was never started, so its lock runs
        // out and the cluster hands it to the next node
        Thread.currentThread().interrupt();
        return;
      }
      handlersRunning.incrementAndGet();
      try {
        command.run();
      } finally {
        handlersRunning.decrementAndGet();
        leaveSlot();
      }
    };

  }

  private <T> Callable<T> counted(
      final Callable<T> task) {

    return () -> {
      enterSlot();
      handlersRunning.incrementAndGet();
      try {
        return task.call();
      } finally {
        handlersRunning.decrementAndGet();
        leaveSlot();
      }
    };

  }

  @Override
  public void execute(
      final Runnable command) {

    handling.execute(counted(command));

  }

  @Override
  public <T> Future<T> submit(
      final Callable<T> task) {

    return handling.submit(counted(task));

  }

  @Override
  public <T> Future<T> submit(
      final Runnable task,
      final T result) {

    return handling.submit(counted(task), result);

  }

  @Override
  public Future<?> submit(
      final Runnable task) {

    return handling.submit(counted(task));

  }

  @Override
  public <T> List<Future<T>> invokeAll(
      final Collection<? extends Callable<T>> tasks) throws InterruptedException {

    return handling.invokeAll(tasks.stream().map(this::counted).toList());

  }

  @Override
  public <T> List<Future<T>> invokeAll(
      final Collection<? extends Callable<T>> tasks,
      final long timeout,
      final TimeUnit unit) throws InterruptedException {

    return handling.invokeAll(tasks.stream().map(this::counted).toList(), timeout, unit);

  }

  @Override
  public <T> T invokeAny(
      final Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {

    return handling.invokeAny(tasks.stream().map(this::counted).toList());

  }

  @Override
  public <T> T invokeAny(
      final Collection<? extends Callable<T>> tasks,
      final long timeout,
      final TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {

    return handling.invokeAny(tasks.stream().map(this::counted).toList(), timeout, unit);

  }

  @Override
  public ScheduledFuture<?> schedule(
      final Runnable command,
      final long delay,
      final TimeUnit unit) {

    return new WaitsForAFreeSlot<>(Executors.callable(command)).armedIn(delay, unit);

  }

  @Override
  public <V> ScheduledFuture<V> schedule(
      final Callable<V> callable,
      final long delay,
      final TimeUnit unit) {

    return new WaitsForAFreeSlot<>(callable).armedIn(delay, unit);

  }

  /**
   * Not gated, unlike the two {@code schedule} methods: holding one run of a repeating task
   * back would move every run after it, and no client of the supported lines repeats
   * anything on this executor anyway.
   */
  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(
      final Runnable command,
      final long initialDelay,
      final long period,
      final TimeUnit unit) {

    return scheduling.scheduleAtFixedRate(command, initialDelay, period, unit);

  }

  /**
   * Not gated, for the same reason as {@link #scheduleAtFixedRate}.
   */
  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(
      final Runnable command,
      final long initialDelay,
      final long delay,
      final TimeUnit unit) {

    return scheduling.scheduleWithFixedDelay(command, initialDelay, delay, unit);

  }

  @Override
  public void shutdown() {

    scheduling.shutdown();
    handling.shutdown();

  }

  @Override
  public List<Runnable> shutdownNow() {

    final var pending = new ArrayList<>(scheduling.shutdownNow());
    pending.addAll(handling.shutdownNow());
    return pending;

  }

  @Override
  public boolean isShutdown() {

    return scheduling.isShutdown() && handling.isShutdown();

  }

  @Override
  public boolean isTerminated() {

    return scheduling.isTerminated() && handling.isTerminated();

  }

  @Override
  public boolean awaitTermination(
      final long timeout,
      final TimeUnit unit) throws InterruptedException {

    final var deadline = System.nanoTime() + unit.toNanos(timeout);
    final var schedulingTerminated = scheduling.awaitTermination(timeout, unit);
    final var remaining = Math.max(0, deadline - System.nanoTime());
    return handling.awaitTermination(remaining, TimeUnit.NANOSECONDS) && schedulingTerminated;

  }

  /**
   * One task the client scheduled, held back while every execution slot is busy.
   * <p>
   * It is its own future, so a caller which cancels what it scheduled cancels the whole
   * chain and not only the attempt which happens to be pending - the 8.10 client cancels
   * the tasks around a job stream that way. Being a {@link CompletableFuture} it also
   * answers {@code isDone}, {@code isCancelled} and {@code get} about the task rather than
   * about one attempt at it.
   *
   * @param <V> What the task returns
   */
  private final class WaitsForAFreeSlot<V> extends CompletableFuture<V> implements ScheduledFuture<V>, Runnable {

    private final Callable<V> task;

    private final AtomicReference<ScheduledFuture<?>> attempt = new AtomicReference<>();

    private WaitsForAFreeSlot(
        final Callable<V> task) {

      this.task = task;

    }

    private WaitsForAFreeSlot<V> armedIn(
        final long delay,
        final TimeUnit unit) {

      // the first attempt may already have run and armed the next one, which is the
      // one a cancel has to reach - so it is not overwritten here
      attempt.compareAndSet(null, scheduling.schedule(this, delay, unit));
      return this;

    }

    @Override
    public void run() {

      if (isDone()) {
        return;
      }
      if (hasAFreeSlot()) {
        try {
          complete(task.call());
        } catch (final Exception e) {
          completeExceptionally(e);
        }
        return;
      }
      try {
        attempt.set(scheduling.schedule(this, LOOK_FOR_A_SLOT_AGAIN_MILLIS, TimeUnit.MILLISECONDS));
      } catch (final RejectedExecutionException e) {
        // the timing half is going down, so there is nobody left to ask the cluster for
        // work either
        cancel(false);
        return;
      }
      if (isDone()) {
        attempt.get().cancel(false);
      }

    }

    @Override
    public boolean cancel(
        final boolean mayInterruptIfRunning) {

      final var cancelled = super.cancel(mayInterruptIfRunning);
      final var pending = attempt.get();
      if (pending != null) {
        pending.cancel(mayInterruptIfRunning);
      }
      return cancelled;

    }

    @Override
    public long getDelay(
        final TimeUnit unit) {

      final var pending = attempt.get();
      return pending == null
          ? 0
          : pending.getDelay(unit);

    }

    @Override
    public int compareTo(
        final Delayed other) {

      return Long.compare(
          getDelay(TimeUnit.NANOSECONDS),
          other.getDelay(TimeUnit.NANOSECONDS));

    }

  }

}
