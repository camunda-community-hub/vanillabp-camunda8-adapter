package io.vanillabp.camunda8.client;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

import lombok.extern.slf4j.Slf4j;

/**
 * The bounded retries this adapter puts around a command whose answer another attempt can
 * change. There are two of them, and they wait out two different things.
 * <p>
 * {@link #send} carries the command a job handler sends BACK to the cluster - the
 * completion, the BPMN error, the failure and the lock renewal of an open asynchronous task
 * - and waits out a cluster which is busy. {@link #sendWhileTheUserTaskIsStillChanging}
 * carries a phase-two command against a Camunda-managed user task and waits out a task the
 * cluster has not finished creating. Both use the same attempt count and the same backoff,
 * so the adapter tells one story about how long it waits. Everything in this comment is
 * about the first one unless it says otherwise.
 * <p>
 * <b>Why it exists.</b> A command the cluster rejects because it is busy arrives as
 * <code>RESOURCE_EXHAUSTED</code> on gRPC and as HTTP 503 on REST, and nothing in the
 * Camunda client repeats either of them: the gRPC retry policy is switched off by default,
 * and even switched on it is a channel setting which does nothing for the REST transport
 * this adapter prefers. Measured against a single node, 19.433 of 20.000 gRPC commands came
 * back rejected. Phase-two commands survive that because the outbox repeats them; the
 * command inside a job handler had nobody to repeat it, so a rejected completion of work
 * which was already committed escaped into the client's fail path and cost the job one of
 * its retries. Under sustained load that walks a job into an incident although every
 * attempt of it succeeded.
 * <p>
 * <b>What bounds it.</b> Two things, and the tighter one wins:
 * <ul>
 * <li><b>the job's lock.</b> Retrying past it hands the job to somebody else, so the
 * remaining lock is the real deadline. It is read from the job's own deadline rather than
 * from the configured timeout, which says how long the lock was granted for and not how
 * much of it is left;</li>
 * <li><b>{@value #MAX_ATTEMPTS} attempts.</b> A handler waiting for a cluster to calm down
 * occupies an execution slot (see
 * {@link Camunda8ExecutionModel}), and a slot which waits
 * delivers nothing. Five attempts spread over less than half a second are a hiccup; more
 * than that is a cluster problem an operator has to see rather than one a worker should
 * sit out.</li>
 * </ul>
 * The backoff is exponential and shaped like the client's own activation backoff
 * ({@code ExponentialBackoffBuilderImpl}): {@value #INITIAL_BACKOFF_MILLIS} ms initially,
 * factor {@value #BACKOFF_FACTOR}, a ceiling of {@value #MAX_BACKOFF_MILLIS} ms and
 * {@value #JITTER_FACTOR} jitter, so the documentation tells one story about backoff
 * instead of two. With five attempts the ceiling is never reached - the longest wait is
 * around 330 ms and the whole sequence stays below half a second, which is the point.
 * <p>
 * <b>What is not retried.</b> The classification is the one the outbox already uses
 * ({@link Camunda8Errors#repeatableJobCommandFailure}), so the adapter has one opinion
 * about what a repetition can change rather than two. A job which is gone and a request
 * the cluster rejects come back on the first attempt. And a shutdown ends the retry at
 * once: while the module is going down, the failure belongs to the shutdown and the job is
 * left to its lock instead of being failed - a retry loop must not hold the
 * drain, and it must not turn into a failure the shutdown would have avoided.
 * <p>
 * <b>A job somebody else holds.</b> One answer ends HERE rather than at the caller: where
 * the cluster says another activation holds the job
 * ({@link Camunda8Errors#jobHeldByAnotherActivation}), the command is dropped with one line
 * and the handler goes on as if it had been accepted. That is what a leased job answers to
 * the run whose lock expired while it worked, and there is nothing a handler could do about
 * it - the newer run answered already, and failing the job would take it away from whoever
 * holds it now.
 * <p>
 * When the bound is reached the original failure is rethrown, so the behaviour after the
 * retries are used up is what it was before this class existed.
 * <p>
 * What may be repeated, what bounds the repetition, and why a shutdown ends it at once is decision
 * 9 in the repository's DECISIONS.md.
 */
@Slf4j
public final class Camunda8CommandRetry {

  private Camunda8CommandRetry() {
  }

  /**
   * How often a repeatable failure of an outcome command is tried at all, the first
   * attempt included.
   */
  public static final int MAX_ATTEMPTS = 5;

  /**
   * The first backoff in milliseconds - the client's own initial activation delay.
   */
  public static final long INITIAL_BACKOFF_MILLIS = 50;

  /**
   * What each backoff is multiplied by - the client's own factor.
   */
  public static final double BACKOFF_FACTOR = 1.6;

  /**
   * The longest backoff in milliseconds - the client's own ceiling. Deliberately never
   * reached within {@value #MAX_ATTEMPTS} attempts.
   */
  public static final long MAX_BACKOFF_MILLIS = 5_000;

  /**
   * How much a backoff is spread around its nominal value, so two handlers rejected in the
   * same moment do not come back in the same moment - the client's own jitter.
   */
  public static final double JITTER_FACTOR = 0.1;

  /**
   * Sends an outcome command, repeating it while the cluster's answer is worth repeating
   * and the job's lock still allows another attempt.
   *
   * @param adapterId The adapter instance, for the messages
   * @param command What is being sent, named the way the log should name it (e.g.
   *          <code>completion</code>)
   * @param jobKey The job the command belongs to
   * @param taskName The task definition respectively job type, as the application knows it
   * @param lockDeadline When the job's lock expires (epoch milliseconds, i.e.
   *          {@code ActivatedJob#getDeadline()})
   * @param shuttingDown Whether the workflow module is going down
   * @param send The command itself
   * @throws RuntimeException The original failure, once no further attempt is allowed
   */
  public static void send(
      final String adapterId,
      final String command,
      final long jobKey,
      final String taskName,
      final long lockDeadline,
      final BooleanSupplier shuttingDown,
      final Runnable send) {

    var attempt = 1;
    while (true) {
      try {
        send.run();
        if (attempt > 1) {
          log.info(
              "Camunda8[{}]: the {} of job {} ('{}') went through on attempt {}",
              adapterId,
              command,
              jobKey,
              taskName,
              attempt);
        }
        return;
      } catch (final RuntimeException e) {
        if (Camunda8Errors.jobHeldByAnotherActivation(e)) {
          // the lock of this job ran out while the work was running, the cluster handed
          // the job out again, and that activation holds it now. Its run is the one the
          // workflow continues with, so this answer is not sent, not repeated and not a
          // failure of anything: both runs did the same work and the newer one won
          log.info(
              """
                  Camunda8[{}]: the {} of job {} ('{}') was refused because another activation holds \
                  the job - the run converged with a redelivery, and what the cluster keeps is what \
                  that run answered. The lock of this run had expired while its work was still \
                  running, so the work was done twice.""",
              adapterId,
              command,
              jobKey,
              taskName);
          return;
        }
        final var reason = whyToStop(e, attempt, lockDeadline, shuttingDown);
        if (reason != null) {
          if (reason.worthAWarning()) {
            log.warn(
                """
                    Camunda8[{}]: the {} of job {} ('{}') was rejected by the cluster and {}. The job is \
                    reported as failed although its work is done, which costs it one retry - a cluster \
                    answering like this under load needs more capacity or fewer workers pushing at it.""",
                adapterId,
                command,
                jobKey,
                taskName,
                reason.text(),
                e);
          }
          throw e;
        }
        log.debug(
            "Camunda8[{}]: the {} of job {} ('{}') was rejected on attempt {} - retrying in {} ms",
            adapterId,
            command,
            jobKey,
            taskName,
            attempt,
            backoffMillis(attempt),
            e);
        if (!sleep(nextBackoff(attempt))) {
          throw e;
        }
        ++attempt;
      }
    }

  }

  /**
   * Sends a command against a Camunda-managed user task, repeating it while the cluster
   * refuses it because the task is in a transition of its own.
   * <p>
   * <b>Why it exists.</b> VanillaBP tells an application about a user task from the
   * <code>creating</code> listener of that task, so the task stands in state
   * <code>CREATING</code> while the application is being notified and until the listener
   * job of that notification is answered on the partition. An application which answers its
   * task in the same breath addresses a task the cluster is still creating, and the cluster
   * refuses the completion with HTTP <code>409</code>
   * ({@link Camunda8Errors#refusedAboutAUserTaskItHolds}). That answer is repeatable, so the
   * outbox would send the operation again - after <code>attempt-frequency</code>, thirty
   * seconds by default, for a state which passes in a few milliseconds.
   * <p>
   * <b>What bounds it.</b> {@value #MAX_ATTEMPTS} attempts with the backoff of
   * {@link #send}, under half a second all together. This runs on a thread of the phase-two
   * dispatcher, and half a second of it is a hiccup while thirty seconds of waiting is a
   * delay somebody notices. Nothing is lost when the attempts are used up: the failure is
   * rethrown and the outbox repeats the entry as it does today.
   *
   * @param adapterId The adapter instance, for the messages
   * @param command What is being sent, named the way the log should name it (e.g.
   *          <code>completion</code>)
   * @param taskId The user task the command belongs to
   * @param send The command itself
   * @throws RuntimeException The original failure, once no further attempt is allowed
   */
  public static void sendWhileTheUserTaskIsStillChanging(
      final String adapterId,
      final String command,
      final String taskId,
      final Runnable send) {

    var attempt = 1;
    while (true) {
      try {
        send.run();
        if (attempt > 1) {
          log.info(
              "Camunda8[{}]: the {} of user task '{}' went through on attempt {}",
              adapterId,
              command,
              taskId,
              attempt);
        }
        return;
      } catch (final RuntimeException e) {
        if (!Camunda8Errors.refusedAboutAUserTaskItHolds(e) || (attempt >= MAX_ATTEMPTS)) {
          throw e;
        }
        log.debug(
            "Camunda8[{}]: the {} of user task '{}' was refused on attempt {} because the task is "
                + "in a transition of its own - retrying in {} ms",
            adapterId,
            command,
            taskId,
            attempt,
            backoffMillis(attempt),
            e);
        if (!sleep(nextBackoff(attempt))) {
          throw e;
        }
        ++attempt;
      }
    }

  }

  /**
   * Why no further attempt is made, or <code>null</code> while one is.
   *
   * @param text What the message says after "and"
   * @param worthAWarning Whether giving up is worth a WARN - a job which is gone and a
   *          command the cluster refuses are reported by the caller, not here
   */
  private record Stop(
                      String text,
                      boolean worthAWarning) {
  }

  private static Stop whyToStop(
      final RuntimeException failure,
      final int attempt,
      final long lockDeadline,
      final BooleanSupplier shuttingDown) {

    if (!Camunda8Errors.repeatableJobCommandFailure(failure)) {
      // the job is gone, or the cluster refuses the command itself: repeating it would
      // only produce the same answer, and both cases have a caller which knows what to
      // make of them
      return new Stop("repeating it cannot change the answer", false);
    }
    if (shuttingDown.getAsBoolean()) {
      // The adapter is going down, so this is the shutdown and not the cluster.
      // The caller leaves the job to its lock, which is better than any retry
      return new Stop("the workflow module is shutting down", false);
    }
    if (attempt >= MAX_ATTEMPTS) {
      return new Stop("all %d attempts were used up".formatted(MAX_ATTEMPTS), true);
    }
    final var backoff = backoffMillis(attempt);
    final var remainingLock = lockDeadline - System.currentTimeMillis();
    if (remainingLock <= backoff) {
      return new Stop(
          "its lock runs out in %d ms, which is less than the %d ms until the next attempt"
              .formatted(Math.max(0, remainingLock), backoff), true);
    }
    return null;

  }

  /**
   * The nominal backoff after the given attempt, without jitter - what the decision
   * whether the lock still allows another attempt is made on.
   *
   * @param attempt The attempt which just failed (one-based)
   * @return The backoff in milliseconds
   */
  static long backoffMillis(
      final int attempt) {

    final var nominal = INITIAL_BACKOFF_MILLIS * Math.pow(BACKOFF_FACTOR, attempt - 1d);
    return Math.min(MAX_BACKOFF_MILLIS, Math.round(nominal));

  }

  /**
   * The backoff actually waited: the nominal one spread by {@value #JITTER_FACTOR}, so
   * handlers rejected together do not come back together.
   *
   * @param attempt The attempt which just failed (one-based)
   * @return The backoff to wait
   */
  static Duration nextBackoff(
      final int attempt) {

    final var nominal = backoffMillis(attempt);
    final var spread = nominal * JITTER_FACTOR * ((ThreadLocalRandom.current()
        .nextDouble() * 2) - 1);
    return Duration.ofMillis(Math.max(1, Math.round(nominal + spread)));

  }

  /**
   * @return Whether the wait completed - an interrupted handler stops retrying and lets
   *         its caller report the original failure
   */
  private static boolean sleep(
      final Duration backoff) {

    try {
      Thread.sleep(backoff.toMillis());
      return true;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }

  }

}
