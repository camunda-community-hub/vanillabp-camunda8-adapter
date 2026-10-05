package io.vanillabp.camunda8.client;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import lombok.extern.slf4j.Slf4j;

/**
 * What one workflow module of one adapter instance does with the work it has in flight
 * while the application is going down.
 * <p>
 * The Camunda client does not drain: {@code JobWorker#close()} returns without waiting
 * for the handlers of the jobs the worker already activated, and {@code
 * CamundaClient#close()} shuts its executor down with an interrupt, measured at two
 * milliseconds after the call. An interrupted handler throws, the adapter used to answer
 * every failure with {@code newFailCommand(retries - 1)}, and so an ordinary restart cost
 * one retry per job in flight - a rolling restart across replicas could walk a job into an
 * incident which has nothing to do with the business logic.
 * <p>
 * This object is the answer, and it does two things. It counts what is in flight: every
 * handler of the module registers the job it is running and deregisters it afterwards, so
 * the shutdown can wait for the handlers to come back ({@link #awaitEveryModuleQuiet},
 * which waits for every module of the adapter instance at once) before the
 * client is closed under them, and what is still running when the grace period passed is
 * named per job so an operator knows what was cut off. And it carries the module's state:
 * while it is shutting down, a failing delivery is not the application's fault and is not
 * reported as one ({@link #leaveJobToItsLock}). Such a job keeps its lock, the cluster
 * hands it out again when the lock expires, its retries untouched, and VanillaBP's
 * delivery record decides whether the work has to run again.
 * <p>
 * <b>Why the drain waits for the workers as well.</b>
 * {@code JobWorker#isClosed()} is the client's own answer and it means three things at
 * once: the worker was closed, no activation request of it is in flight, and no activated
 * job is left. This adapter does not wait for it, because a worker long-polls with the
 * request timeout (ten seconds by default) and closing it does not cancel the request in
 * flight, so a worker which runs nothing at all reports {@code false} for as long as that
 * request takes. What that costs was known; what it buys was not, and it turned out to be
 * the difference between an ordinary restart and a job which waits for its lock.
 * <p>
 * Measured against {@code camunda/camunda:8.9.16}, plain client, no VanillaBP: an
 * activation request which is parked at the cluster when its client is closed STAYS
 * parked. A job created afterwards is activated into it, nobody answers, and the worker
 * which is open at that moment sees the job only when the lock expires. With a gap of
 * seven seconds between the two applications the first job of the new one took 20027 to
 * 21559 ms in twenty runs at a {@code job-timeout} of {@code PT20S}, and 10 to 29 ms in
 * twenty runs of the same scenario where the shutdown waited for the workers. A gap of
 * twelve seconds, which is above the request timeout, costs nothing either way. So the
 * hole is exactly as long as an activation request of the closed client can outlive it,
 * and waiting for the workers to report themselves closed is what shuts it. Only the REST
 * poll keeps such a request: over gRPC and over the push path of {@code stream-enabled}
 * the same scenario delivers in milliseconds without any wait.
 * <p>
 * Jobs activated but never handed to a handler are covered by the same wait: they are
 * what {@code isClosed()} counts as remaining, so the grace is spent on them rather than
 * on their lock.
 * <p>
 * Why a shutdown waits for the handlers AND for the cluster releasing the workers, and why no
 * worker reports a job as failed while it runs, is decision 6 in the repository's DECISIONS.md.
 */
@Slf4j
// see decision 4 in the repository's DECISIONS.md
@SuppressWarnings("LombokGetterMayBeUsed")
public class Camunda8Drain {

  /**
   * How often the drain looks whether the handlers came back. Short enough not to add a
   * noticeable delay to a shutdown which has nothing to wait for.
   */
  static final long POLL_MILLIS = 50;

  private final String adapterId;

  private final String workflowModuleId;

  /**
   * The jobs whose handler is running right now, keyed by job key.
   */
  private final Map<Long, InFlightJob> inFlight = new ConcurrentHashMap<>();

  private volatile boolean shuttingDown;

  /**
   * Opens the drain of one workflow module. The client factory builds one per module, on first
   * use.
   *
   * @param adapterId The adapter instance this belongs to
   * @param workflowModuleId The workflow module whose workers are drained
   */
  public Camunda8Drain(
      final String adapterId,
      final String workflowModuleId) {

    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;

  }

  /**
   * A delivery whose handler is running right now.
   * <p>
   * The workflow module and the thread are here for the watch which looks for a slot
   * nobody gives back. The module completes what a job timeout is resolved from, and the
   * thread is what a stack trace of an overdue handler is taken from. The entry is
   * removed when the handler returns, so nothing holds a thread alive for longer than the
   * handler itself.
   *
   * @param jobKey The job key the cluster handed out
   * @param kind What kind of worker delivered it (for the message)
   * @param name The task definition respectively the job type, as the application knows it
   * @param bpmnProcessId The BPMN process, as the application knows it
   * @param workflowModuleId The workflow module the delivery belongs to
   * @param since When the handler entered
   * @param thread The thread the handler runs on
   */
  public record InFlightJob(
                            long jobKey,
                            String kind,
                            String name,
                            String bpmnProcessId,
                            String workflowModuleId,
                            Instant since,
                            Thread thread) {

    /**
     * How long the handler has been inside its slot.
     *
     * @param now The moment to measure against
     * @return The time since the handler entered, never negative
     */
    public Duration runningFor(
        final Instant now) {

      final var running = Duration.between(since, now);
      return running.isNegative()
          ? Duration.ZERO
          : running;

    }

  }

  /**
   * The workflow module this drain belongs to.
   *
   * @return The workflow module id
   */
  public String getWorkflowModuleId() {

    return workflowModuleId;

  }

  /**
   * Whether the workers of this workflow module are going down. Set before the workers are
   * closed, so a handler which is interrupted by the shutdown can tell the difference
   * between its own failure and the shutdown.
   *
   * @return Whether shutdown began
   */
  public boolean isShuttingDown() {

    return shuttingDown;

  }

  /**
   * Marks the module as shutting down. Called by {@code stopWorkflowProcessing} BEFORE the
   * workers are closed - a job failing between the flag and the close is already a job the
   * shutdown cut off.
   */
  public void beginShutdown() {

    shuttingDown = true;

  }

  /**
   * Registers the delivery a handler just entered.
   *
   * @param jobKey The job key
   * @param kind What kind of worker delivered it
   * @param name The task definition respectively the job type
   * @param bpmnProcessId The BPMN process
   */
  public void jobStarted(
      final long jobKey,
      final String kind,
      final String name,
      final String bpmnProcessId) {

    inFlight
        .put(
            jobKey,
            new InFlightJob(
                jobKey, kind, name, bpmnProcessId, workflowModuleId, Instant.now(), Thread.currentThread()));

  }

  /**
   * Deregisters a delivery whose handler returned - in a {@code finally}, so a handler
   * which threw is gone from the drain as well.
   *
   * @param jobKey The job key
   */
  public void jobFinished(
      final long jobKey) {

    inFlight.remove(jobKey);

  }

  /**
   * What this module has in flight, which is what a shutdown waits for and what a report names
   * where it gave up waiting.
   *
   * @return The deliveries whose handler is running right now
   */
  public Collection<InFlightJob> getInFlight() {

    return List.copyOf(inFlight.values());

  }

  /**
   * What one workflow module's shutdown ended with: how long it waited, how many workers
   * it closed, whether the handlers came back, and whether the cluster released the
   * activation requests of those workers.
   * <p>
   * The two booleans are deliberately separate. Whether the workers were CLOSED is this
   * adapter's own action and is therefore a count rather than a guess; whether they are
   * RELEASED is the client's answer, and it is the one which decides whether closing the
   * client now leaves a request parked at the cluster.
   *
   * @param waitedMillis How long the shutdown waited
   * @param workersClosed How many workers of this module were closed
   * @param handlersReturned Whether every handler came back within the grace period
   * @param workersReleased Whether every worker reports itself closed
   */
  public record DrainOutcome(
                             long waitedMillis,
                             int workersClosed,
                             boolean handlersReturned,
                             boolean workersReleased) {

    /**
     * Whether both halves came back, which is the only outcome a shutdown may call clean.
     *
     * @return Whether the module is quiet: no handler inside the application, no
     *         activation request of this module left at the cluster
     */
    public boolean isQuiet() {

      return handlersReturned && workersReleased;

    }

  }

  /**
   * Waits until this workflow module is quiet, or until the grace period passed: no
   * handler running any more, and every worker reporting itself closed.
   * <p>
   * The one-module case of {@link #awaitEveryModuleQuiet}, which is what an application
   * with a single workflow module has and what the backstop of the client factory does with
   * a module that stopped on its own.
   *
   * @param grace How long the shutdown waits
   * @param workersClosed How many workers of this module were closed before the wait
   * @param workersReleased Whether every one of them reports itself closed
   * @return What the wait ended with
   */
  public DrainOutcome awaitQuiet(
      final Duration grace,
      final int workersClosed,
      final BooleanSupplier workersReleased) {

    return awaitEveryModuleQuiet(
        List.of(new ClosedWorkers(this, workersClosed, workersReleased)),
        grace)
        .get(this);

  }

  /**
   * One workflow module whose workers are closed and which is now waiting to be released.
   * <p>
   * It exists because the grace period is ONE budget for the whole application: the
   * platform stops the modules one after another, and an adapter which spent a grace per
   * module would spend the application's budget as often as it has modules. So the
   * shutdown closes the workers of each module as that module is stopped and collects what
   * it closed here, and the last module turns the collection into the one wait
   * ({@link #awaitEveryModuleQuiet}).
   *
   * @param drain The drain of that module, which knows what it has in flight
   * @param workersClosed How many workers of it were closed
   * @param workersReleased Whether every one of them reports itself closed
   */
  public record ClosedWorkers(
                              Camunda8Drain drain,
                              int workersClosed,
                              BooleanSupplier workersReleased) {

  }

  /**
   * Waits until EVERY workflow module which was closed is quiet, or until the grace period
   * passed: no handler of any of them running any more, and every one of their workers
   * reporting itself closed.
   * <p>
   * This is the wait of one application rather than of one module, and that is the whole
   * point of it. The workers of a module which is still open keep renewing their activation
   * request while another module is drained, so the module stopped next has a request of its
   * own to sit out, and worse: the closed workers of the module being drained wait behind
   * the still polling workers of the others, which can cost more than one request timeout.
   * Closing every module's workers first and waiting once leaves the application with one
   * wait, which is the number its runtime's shutdown budget can be held against.
   * <p>
   * Measured on 2026-10-01 with {@code Camunda8WhatSeveralModulesPayForAShutdownIT} against
   * {@code camunda/camunda:8.9.21}, one client with a pool of 256, a request timeout of
   * {@code PT10S}, a grace of {@code PT20S} and thirty workers per module: three modules
   * drained one after another took 32743 ms, which is past the thirty seconds the runtime
   * grants, and the last of them gave up after the whole grace with its workers still
   * holding a request. The same three modules closed first and then waited for together took
   * 5242 ms, with every module quiet.
   * <p>
   * Every module gets the same waited time, because there was one wait. What is read per
   * module is whether ITS handlers came back and whether ITS workers were released, so each
   * of them still reports its own outcome.
   *
   * @param closed What the shutdown closed, one entry per workflow module
   * @param grace How long the shutdown waits, all modules together
   * @return What the wait ended with, per module
   */
  public static Map<Camunda8Drain, DrainOutcome> awaitEveryModuleQuiet(
      final Collection<ClosedWorkers> closed,
      final Duration grace) {

    final var startedAt = System.nanoTime();
    final var deadline = startedAt + Math.max(0, grace.toNanos());
    while (true) {
      final var reading = new LinkedHashMap<Camunda8Drain, ModuleReading>();
      var everyModuleQuiet = true;
      for (final var module : closed) {
        final var handlersReturned = module.drain().inFlight.isEmpty();
        final var workersReleased = module.workersReleased().getAsBoolean();
        reading
            .put(
                module.drain(),
                new ModuleReading(module.workersClosed(), handlersReturned, workersReleased));
        everyModuleQuiet = everyModuleQuiet && handlersReturned && workersReleased;
      }
      if (everyModuleQuiet || (System.nanoTime() >= deadline)) {
        return outcomesOf(reading, (System.nanoTime() - startedAt) / 1_000_000);
      }
      try {
        Thread.sleep(POLL_MILLIS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return outcomesOf(reading, (System.nanoTime() - startedAt) / 1_000_000);
      }
    }

  }

  /**
   * What one workflow module answered in one round of the wait. The round is where the two
   * answers are taken, so a module is asked once per round and the outcome reports what it
   * said in the round the wait ended in.
   *
   * @param workersClosed How many workers of it were closed
   * @param handlersReturned Whether its handlers came back
   * @param workersReleased Whether its workers report themselves closed
   */
  private record ModuleReading(
                               int workersClosed,
                               boolean handlersReturned,
                               boolean workersReleased) {

  }

  /**
   * Turns the last round of the wait into what each module ended it with.
   *
   * @param reading What each module answered in that round
   * @param waitedMillis How long the one wait took
   * @return The outcome per module
   */
  private static Map<Camunda8Drain, DrainOutcome> outcomesOf(
      final Map<Camunda8Drain, ModuleReading> reading,
      final long waitedMillis) {

    final var outcomes = new LinkedHashMap<Camunda8Drain, DrainOutcome>();
    reading
        .forEach((
            drain,
            module) -> outcomes
                .put(
                    drain,
                    new DrainOutcome(
                        waitedMillis, module.workersClosed(), module.handlersReturned(), module.workersReleased())));
    return outcomes;

  }

  /**
   * Says what the shutdown of this workflow module ended with, which is the line an
   * operator reads next to "Closing Camunda 8 client".
   * <p>
   * It can only report what this adapter knows: the workers it closed is a count it took
   * itself, and whether the cluster released them is the client's answer, asked after the
   * wait rather than during it. A line claiming the workers were not closed while the
   * client is going down cannot be produced here.
   *
   * @param grace The grace period the shutdown was given
   * @param outcome What {@link #awaitQuiet} ended with
   */
  public void report(
      final Duration grace,
      final DrainOutcome outcome) {

    if (outcome.isQuiet()) {
      log.info(
          "Camunda8[{}]: workflow module '{}' drained after {} ms ({} workers closed, no activation "
              + "request of theirs left at the cluster)",
          adapterId,
          workflowModuleId,
          outcome.waitedMillis(),
          outcome.workersClosed());
      return;
    }
    if (!outcome.handlersReturned()) {
      reportCutOff(grace);
    }
    if (outcome.workersReleased()) {
      return;
    }
    // a grace of zero is a decision, not an accident: the operator asked for a shutdown
    // which waits for nothing and gets the consequence at INFO rather than as a warning
    // on every single restart
    final var message = """
        Camunda8[{}]: workflow module '{}' was stopped after {} ms with at least one of its {} closed \
        workers still holding an activation request at the cluster. Closing the client now leaves that \
        request parked: a job created within '{}' of this shutdown is activated into it, nobody answers \
        it, and the worker of the next application sees it only once '{}' expired. Raise '{}' above the \
        request timeout to close the window, and keep it under the shutdown budget of whatever runs the \
        application.""";
    final var arguments = new Object[]{
        adapterId, workflowModuleId, outcome.waitedMillis(), outcome.workersClosed(), Camunda8AdapterConfiguration
            .propertyKey(adapterId, "request-timeout"), Camunda8AdapterConfiguration.propertyKey(adapterId,
                "job-timeout"), Camunda8AdapterConfiguration.propertyKey(adapterId, "shutdown-grace")
    };
    if (grace.isZero()) {
      log.info(message, arguments);
    } else {
      log.warn(message, arguments);
    }

  }

  /**
   * Names what is still running after the grace period passed, once per job: those handlers
   * are about to be interrupted by the closing client, and their jobs stay locked until the
   * cluster hands them out again.
   *
   * @param grace The grace period which passed
   */
  public void reportCutOff(
      final Duration grace) {

    getInFlight()
        .forEach(job -> log.warn(
            """
                Camunda8[{}]: the {} '{}' of BPMN process '{}' (job {}, workflow module '{}') was still \
                running after the shutdown waited {} for it and is being cut off. The job keeps its \
                retries and is redelivered once its lock expires. Raise '{}' where handlers legitimately \
                run that long - and raise the shutdown budget of the runtime with it \
                ('spring.lifecycle.timeout-per-shutdown-phase', Kubernetes' \
                'terminationGracePeriodSeconds'), which is what this grace stays below.""",
            adapterId,
            job.kind(),
            job.name(),
            job.bpmnProcessId(),
            job.jobKey(),
            workflowModuleId,
            grace,
            Camunda8AdapterConfiguration.propertyKey(adapterId, "shutdown-grace")));

  }

  /**
   * Whether a failed delivery is the shutdown rather than the application - the rule being
   * the adapter's STATE and not the kind of exception: a handler interrupted by the closing
   * client throws whatever the interrupt made it throw, and none of those exceptions is
   * distinguishable from a genuine failure which happened to occur at the same moment.
   * <p>
   * Where it is the shutdown, the job is deliberately left to its lock: no
   * {@code newFailCommand}, so the cluster redelivers it after the lock expires with its
   * retries intact. It is logged at INFO with the job key, because a job left behind by a
   * restart is a normal event but not an invisible one.
   *
   * @param jobKey The job key
   * @param kind What kind of worker delivered it
   * @param name The task definition respectively the job type
   * @param failure What the handler threw
   * @return Whether the caller has to leave the job alone
   */
  public boolean leaveJobToItsLock(
      final long jobKey,
      final String kind,
      final String name,
      final Throwable failure) {

    if (!shuttingDown) {
      return false;
    }
    log.info(
        "Camunda8[{}]: the {} '{}' (job {}, workflow module '{}') did not finish before the adapter shut "
            + "down. The job is left to its lock instead of being failed, so the cluster redelivers it "
            + "with its retries intact and VanillaBP answers the redelivery from its delivery record. "
            + "Reason: {}",
        adapterId,
        kind,
        name,
        jobKey,
        workflowModuleId,
        failure == null
            ? "none given"
            : failure.toString());
    return true;

  }

}
