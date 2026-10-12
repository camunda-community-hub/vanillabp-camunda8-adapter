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
 * The Camunda client does not drain. {@code JobWorker#close()} returns without waiting
 * for the handlers of the jobs the worker already activated. {@code CamundaClient#close()}
 * shuts its executor down with an interrupt, measured at two milliseconds after the call.
 * An interrupted handler throws. The adapter used to answer every failure with
 * {@code newFailCommand(retries - 1)}. So an ordinary restart cost one retry per job in
 * flight. A rolling restart across replicas could drive a job into an incident which has
 * nothing to do with the business logic.
 * <p>
 * This object is the answer, and it does two things. First, it counts what is in flight.
 * Every handler of the module registers the job it is running, and deregisters it
 * afterwards. So the shutdown can wait for the handlers to come back before the client is
 * closed under them ({@link #awaitEveryModuleQuiet} waits for every module of the adapter
 * instance at once). What is still running when the grace period is over is named per job,
 * so an operator knows what was cut off. Second, it carries the state of the module. While
 * the module is shutting down, a failing delivery is not the fault of the application and
 * is not reported as one ({@link #leaveJobToItsLock}). Such a job keeps its lock and its
 * retries. The cluster hands it out again when the lock expires. The delivery record of
 * VanillaBP decides whether the work has to run again.
 * <p>
 * <b>Why the drain waits for the workers as well.</b>
 * {@code JobWorker#isClosed()} is the client's own answer, and it means three things at
 * once. The worker was closed, no activation request of it is in flight, and no activated
 * job is left. This adapter does not wait for it, because a worker long-polls with the
 * request timeout (ten seconds by default) and closing it does not cancel the request in
 * flight, so a worker which runs nothing at all reports {@code false} for as long as that
 * request takes. What that costs was known; what it buys was not, and it turned out to be
 * the difference between an ordinary restart and a job which waits for its lock.
 * <p>
 * Measured against {@code camunda/camunda:8.9.16}, plain client, no VanillaBP: an
 * activation request which waits at the cluster when its client is closed KEEPS waiting
 * there. A job created after that is activated into it, and nobody answers. The worker
 * which is open at that moment sees the job only when the lock expires. With a gap of
 * seven seconds between the two applications, the first job of the new one took 20027 to
 * 21559 ms in twenty runs at a {@code job-timeout} of {@code PT20S}. It took 10 to 29 ms in
 * twenty runs of the same scenario where the shutdown waited for the workers. A gap of
 * twelve seconds, which is above the request timeout, costs nothing either way. So the
 * hole is exactly as long as an activation request of the closed client can outlive it.
 * Waiting until the workers report themselves closed is what closes the hole. Only the
 * REST poll keeps such a request. Over gRPC and over the push path of
 * {@code stream-enabled}, the same scenario delivers in milliseconds without any wait.
 * <p>
 * Jobs which were activated but never handed to a handler are covered by the same wait.
 * {@code isClosed()} counts them as remaining. So the grace is spent on them, and they do
 * not wait for their lock.
 * <p>
 * Why a shutdown waits for the handlers AND for the cluster releasing the workers, and why no
 * worker reports a job as failed while it runs, is decision 6 in the repository's DECISIONS.md.
 */
@Slf4j
// see decision 4 in the repository's DECISIONS.md
@SuppressWarnings("LombokGetterMayBeUsed")
public class Camunda8Drain {

  /**
   * How often the drain checks whether the handlers came back. It is short enough not to
   * add a delay anyone notices to a shutdown which has nothing to wait for.
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
   * Opens the drain of one workflow module. The client factory builds one per module, on
   * first use.
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
   * The workflow module and the thread are here for the watch which looks for a slot that
   * nobody gives back. The module completes what a job timeout is resolved from. The
   * stack trace of an overdue handler is taken from the thread. The entry is removed when
   * the handler returns. So nothing keeps a thread alive for longer than the handler
   * itself.
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
   * Whether the workers of this workflow module are going down. It is set before the
   * workers are closed. So a handler which the shutdown interrupts can tell its own failure
   * apart from the shutdown.
   *
   * @return Whether shutdown began
   */
  public boolean isShuttingDown() {

    return shuttingDown;

  }

  /**
   * Marks the module as shutting down. {@code stopWorkflowProcessing} calls this BEFORE the
   * workers are closed. A job which fails between the flag and the close is already a job
   * the shutdown cut off.
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
   * Deregisters a delivery whose handler returned. It is called in a {@code finally}, so a
   * handler which threw is gone from the drain as well.
   *
   * @param jobKey The job key
   */
  public void jobFinished(
      final long jobKey) {

    inFlight.remove(jobKey);

  }

  /**
   * What this module has in flight. A shutdown waits for it, and a report names it where
   * the shutdown gave up waiting.
   *
   * @return The deliveries whose handler is running right now
   */
  public Collection<InFlightJob> getInFlight() {

    return List.copyOf(inFlight.values());

  }

  /**
   * What the shutdown of one workflow module ended with. It says how long it waited, how
   * many workers it closed, whether the handlers came back, and whether the cluster
   * released the activation requests of those workers.
   * <p>
   * The two booleans are separate on purpose. Whether the workers were CLOSED is an action
   * of this adapter, so it is a count and not a guess. Whether they are RELEASED is the
   * client's answer. That answer decides whether closing the client now leaves a request
   * waiting at the cluster.
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
     * Whether both halves came back. Only then may a shutdown call itself clean.
     *
     * @return Whether the module is quiet: no handler inside the application, no
     *         activation request of this module left at the cluster
     */
    public boolean isQuiet() {

      return handlersReturned && workersReleased;

    }

  }

  /**
   * Waits until this workflow module is quiet, or until the grace period is over. Quiet
   * means that no handler is running any more and every worker reports itself closed.
   * <p>
   * This is the one-module case of {@link #awaitEveryModuleQuiet}. An application with a
   * single workflow module uses it. The backstop of the client factory uses it too, for a
   * module that stopped on its own.
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
   * It exists because the grace period is ONE budget for the whole application. The
   * platform stops the modules one after another. An adapter which spent a grace per module
   * would spend the budget of the application as often as it has modules. So the shutdown
   * closes the workers of each module when that module is stopped, and collects here what
   * it closed. The last module turns the collection into the one wait
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
   * is over. Quiet means that no handler of any of them is running any more, and every one
   * of their workers reports itself closed.
   * <p>
   * This is the wait of one application, not of one module, and that is the whole point.
   * The workers of a module which is still open keep renewing their activation request
   * while another module is drained. So the module stopped next has a request of its own
   * to wait for. Worse, the closed workers of the module being drained wait behind the
   * workers of the others, which still poll. That can cost more than one request timeout.
   * If the workers of every module are closed first, the application waits only once. That
   * one wait is what the shutdown budget of its runtime can be compared with.
   * <p>
   * Measured on 2026-10-01 with {@code Camunda8WhatSeveralModulesPayForAShutdownIT} against
   * {@code camunda/camunda:8.9.21}, one client with a pool of 256, a request timeout of
   * {@code PT10S}, a grace of {@code PT20S} and thirty workers per module: three modules
   * drained one after another took 32743 ms. That is more than the thirty seconds the
   * runtime grants. The last of them gave up after the whole grace, and its workers still
   * held a request. The same three modules, closed first and then waited for together, took
   * 5242 ms, and every module was quiet.
   * <p>
   * Every module gets the same waited time, because there was one wait. Per module the wait
   * reads whether ITS handlers came back and whether ITS workers were released. So each of
   * them still reports its own outcome.
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
   * What one workflow module answered in one round of the wait. The round takes the two
   * answers. So a module is asked once per round, and the outcome reports what it said in
   * the last round of the wait.
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
   * Says what the shutdown of this workflow module ended with. An operator reads this line
   * next to "Closing Camunda 8 client".
   * <p>
   * It can only report what this adapter knows. The number of workers it closed is a count
   * it took itself. Whether the cluster released them is the client's answer, asked after
   * the wait and not during it. So this cannot write a line which claims that the workers
   * were not closed while the client is going down.
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
    // a grace of zero is a decision, not an accident. The operator asked for a shutdown
    // which waits for nothing. So the consequence comes at INFO, and not as a warning on
    // every single restart
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
   * Names what is still running after the grace period is over, once per job. The closing
   * client is about to interrupt those handlers. Their jobs stay locked until the cluster
   * hands them out again.
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
   * Whether a failed delivery is caused by the shutdown and not by the application. The
   * STATE of the adapter decides, not the kind of exception. A handler which the closing
   * client interrupts throws whatever the interrupt made it throw. None of those exceptions
   * can be told apart from a real failure which happened at the same moment.
   * <p>
   * If it is the shutdown, the job is left to its lock on purpose. There is no
   * {@code newFailCommand}, so the cluster delivers the job again after the lock expires,
   * with its retries intact. It is logged at INFO with the job key. A job left behind by a
   * restart is a normal event, but it should not be invisible.
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
