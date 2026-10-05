package io.vanillabp.camunda8.client;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import io.vanillabp.camunda8.client.Camunda8Drain.InFlightJob;
import lombok.extern.slf4j.Slf4j;

/**
 * Looks at the execution slots of one adapter id and says when they are all held by
 * handlers which are not coming back.
 * <p>
 * <b>What it watches for.</b> A handler is application code and may block for as long as it
 * likes. It holds its execution slot while it does, so a handler which never returns costs
 * one slot forever. Lose every slot and no worker of this adapter id asks the cluster for
 * work any more, because a worker only asks while a slot is free. The application is then
 * quiet, the health check is green, and nothing in the log says why. This watch is what
 * says it.
 * <p>
 * <b>The Camunda client cannot help here.</b> The client restarts the polling of a worker
 * on the thread which just finished a job, so a thread which never finishes never restarts
 * anything. Camunda's advice for that is to raise the number of job worker execution
 * threads, and it does not reach this adapter: the adapter hands the client an executor of
 * its own, so the client builds no pool of its own. The knob here is
 * <code>worker-threads</code>, and raising it only means it takes that many stuck handlers
 * instead of one.
 * <p>
 * <b>When a handler counts as overdue.</b> Not after some invented duration, but after the
 * job timeout of its own task. A handler which ran longer than that has lost the lock of
 * the job it is working on, the cluster has handed the job out again, and whatever the
 * handler still does is a second run of work somebody else is already doing. The job
 * timeout is resolved per task over the four configuration levels, so a task which is
 * allowed to run long is measured against its own value.
 * <p>
 * <b>What raises the alarm.</b> One slot held for a long time is an application doing
 * slow work. Every slot held while the oldest of them is overdue is an adapter which has
 * stopped asking for work. Only the second one is reported, once when it starts and once
 * when it ends, so a log is not filled while the state lasts. The two gauges are what an
 * operator alerts on, and they are readable at every moment.
 * <p>
 * Why a hung slot is watched and reported instead of being ended by the adapter is
 * decision 46 in the repository's DECISIONS.md.
 */
@Slf4j
public class Camunda8SlotWatch implements AutoCloseable {

  /**
   * How often the slots are looked at. The alarm is about an application which has been
   * quiet for at least a job timeout, so seeing it a few seconds later costs nothing, and
   * a check of a handful of map entries every ten seconds costs nothing either.
   */
  static final Duration LOOK_AT_THE_SLOTS_EVERY = Duration.ofSeconds(10);

  /**
   * How many stack frames of an overdue handler are reported. The top frames say where
   * the handler is waiting, which is the question being asked; the bottom frames are the
   * client and the executor and are the same every time.
   */
  static final int STACK_FRAMES_REPORTED = 20;

  private final String adapterId;

  private final String slotsPropertyKey;

  private final IntSupplier slotsConfigured;

  private final IntSupplier slotsInUse;

  private final Supplier<Collection<InFlightJob>> runningExecutions;

  private final Function<InFlightJob, Duration> jobTimeoutOf;

  private ScheduledExecutorService watching;

  /**
   * Since when every slot has been held by overdue handlers, or <code>null</code> while
   * that is not the case. It is the flag and the age of the alarm in one.
   */
  private volatile Instant standstillSince;

  /**
   * Opens the watch of one adapter id. Nothing is looked at until {@link #start()}.
   *
   * @param adapterId The adapter instance being watched
   * @param slotsPropertyKey The property key which sizes the slots, named in the message
   * @param slotsConfigured How many handlers this adapter id may run at the same time
   * @param slotsInUse How many of them are running right now
   * @param runningExecutions What every workflow module of this adapter id has in flight
   * @param jobTimeoutOf How long the job of one running handler stays locked
   */
  public Camunda8SlotWatch(
      final String adapterId,
      final String slotsPropertyKey,
      final IntSupplier slotsConfigured,
      final IntSupplier slotsInUse,
      final Supplier<Collection<InFlightJob>> runningExecutions,
      final Function<InFlightJob, Duration> jobTimeoutOf) {

    this.adapterId = adapterId;
    this.slotsPropertyKey = slotsPropertyKey;
    this.slotsConfigured = slotsConfigured;
    this.slotsInUse = slotsInUse;
    this.runningExecutions = runningExecutions;
    this.jobTimeoutOf = jobTimeoutOf;

  }

  /**
   * Starts looking, on a daemon thread of its own. A thread of its own rather than one of
   * the executor's, because this watch has to answer while every thread the adapter runs
   * handlers on is blocked.
   */
  public synchronized void start() {

    if (watching != null) {
      return;
    }
    watching = Executors
        .newSingleThreadScheduledExecutor(runnable -> {
          final var thread = new Thread(runnable, "vanillabp-%s-slot-watch".formatted(adapterId));
          thread.setDaemon(true);
          return thread;
        });
    watching
        .scheduleWithFixedDelay(
            this::lookAtTheSlots,
            LOOK_AT_THE_SLOTS_EVERY.toMillis(),
            LOOK_AT_THE_SLOTS_EVERY.toMillis(),
            TimeUnit.MILLISECONDS);

  }

  @Override
  public synchronized void close() {

    if (watching == null) {
      return;
    }
    watching.shutdownNow();
    watching = null;

  }

  /**
   * How long the oldest handler of this adapter id has been running, in seconds. Zero
   * while no handler runs.
   *
   * @return The age of the oldest running handler
   */
  public double getOldestRunningSeconds() {

    final var now = Instant.now();
    return runningExecutions
        .get()
        .stream()
        .mapToDouble(job -> job.runningFor(now).toMillis() / 1000d)
        .max()
        .orElse(0d);

  }

  /**
   * How many handlers of this adapter id have been running longer than the job timeout of
   * their own task. Every one of them has lost the lock of the job it is working on.
   *
   * @return The overdue handlers
   */
  public int getOverdueExecutions() {

    final var now = Instant.now();
    return overdueExecutions(now).size();

  }

  private List<InFlightJob> overdueExecutions(
      final Instant now) {

    return runningExecutions
        .get()
        .stream()
        .filter(job -> isOverdue(job, now))
        .sorted((
            left,
            right) -> left.since().compareTo(right.since()))
        .toList();

  }

  private boolean isOverdue(
      final InFlightJob job,
      final Instant now) {

    final var jobTimeout = jobTimeoutOf.apply(job);
    return (jobTimeout != null) && (job.runningFor(now).compareTo(jobTimeout) > 0);

  }

  /**
   * One look at the slots: raises the alarm when every one of them is held and the oldest
   * holder is overdue, and takes it back when that ends.
   */
  void lookAtTheSlots() {

    try {
      final var now = Instant.now();
      final var configured = slotsConfigured.getAsInt();
      final var everySlotIsHeld = (configured > 0) && (slotsInUse.getAsInt() >= configured);
      final var overdue = everySlotIsHeld
          ? overdueExecutions(now)
          : List.<InFlightJob>of();
      if (!overdue.isEmpty()) {
        if (standstillSince == null) {
          standstillSince = now;
          reportStandstill(now, configured, overdue);
        }
        return;
      }
      final var since = standstillSince;
      if (since != null) {
        standstillSince = null;
        reportSlotsAreBackFor(Duration.between(since, now));
      }
    } catch (final RuntimeException e) {
      log.warn("Camunda8[{}]: could not look at the execution slots", adapterId, e);
    }

  }

  private void reportStandstill(
      final Instant now,
      final int configured,
      final List<InFlightJob> overdue) {

    log
        .warn(
            """
                Camunda8[{}]: all {} execution slots are held and {} of the handlers holding them ran \
                longer than the job timeout of their own task. No worker of this adapter id asks the \
                cluster for work while that lasts, and only a handler returning frees a slot again. \
                Each of those jobs lost its lock, so the cluster handed it out a second time. Give the \
                call your handler waits on a time limit of its own, and raise '{}' where handlers \
                legitimately run this long. What holds the slots:
                {}""",
            adapterId,
            configured,
            overdue.size(),
            slotsPropertyKey,
            describe(now, overdue));

  }

  private void reportSlotsAreBackFor(
      final Duration standstill) {

    log
        .info(
            "Camunda8[{}]: an execution slot came free after {}. The workers of this adapter id ask "
                + "the cluster for work again",
            adapterId,
            standstill.truncatedTo(ChronoUnit.SECONDS));

  }

  private String describe(
      final Instant now,
      final List<InFlightJob> overdue) {

    return overdue
        .stream()
        .map(job -> describe(now, job))
        .reduce((
            left,
            right) -> left
                + "\n"
                + right)
        .orElse("");

  }

  private String describe(
      final Instant now,
      final InFlightJob job) {

    return """
        the %s '%s' of BPMN process '%s' (job %d, workflow module '%s') has been running for %s on \
        thread '%s', its job timeout is %s, and it stands at:
        %s"""
        .formatted(
            job.kind(),
            job.name(),
            job.bpmnProcessId(),
            job.jobKey(),
            job.workflowModuleId(),
            job.runningFor(now).truncatedTo(ChronoUnit.SECONDS),
            job.thread() == null
                ? "unknown"
                : job.thread().getName(),
            jobTimeoutOf.apply(job),
            stackOf(job));

  }

  /**
   * Where an overdue handler stands right now. This is the difference between knowing that
   * something is stuck and knowing what is stuck: the top frames name the call the handler
   * waits on.
   */
  private static String stackOf(
      final InFlightJob job) {

    final var thread = job.thread();
    if (thread == null) {
      return "  (the thread of this handler is not known)";
    }
    final var frames = thread.getStackTrace();
    if (frames.length == 0) {
      return "  (thread '%s' is no longer running)".formatted(thread.getName());
    }
    final var reported = new StringBuilder();
    for (var frame = 0; (frame < frames.length) && (frame < STACK_FRAMES_REPORTED); frame++) {
      reported.append("  at ").append(frames[frame]).append('\n');
    }
    if (frames.length > STACK_FRAMES_REPORTED) {
      reported
          .append("  ... ")
          .append(frames.length - STACK_FRAMES_REPORTED)
          .append(" frames below it\n");
    }
    return reported.toString().stripTrailing();

  }

}
