package io.vanillabp.camunda8.client;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

import lombok.extern.slf4j.Slf4j;

/**
 * The bounded retries which this adapter puts around a command when another attempt can
 * change the answer. There are two of them, and they wait for two different things.
 * <p>
 * {@link #send} carries the command a job handler sends BACK to the cluster. That is the
 * completion, the BPMN error, the failure, and the lock renewal of an open asynchronous
 * task. It waits for a busy cluster. {@link #sendWhileTheUserTaskIsStillChanging} carries
 * a phase-two command against a Camunda-managed user task. It waits for a task which the
 * cluster has not finished creating. Both use the same number of attempts and the same
 * backoff. So the adapter tells one story about how long it waits. Everything in this
 * comment is about the first one, unless it says otherwise.
 * <p>
 * <b>Why it exists.</b> A command the cluster rejects because it is busy arrives as
 * <code>RESOURCE_EXHAUSTED</code> on gRPC and as HTTP 503 on REST. Nothing in the Camunda
 * client repeats either of them. The gRPC retry policy is switched off by default. Even
 * when it is on, it is a channel setting, and it does nothing for the REST transport which
 * this adapter prefers. Measured against a single node, 19.433 of 20.000 gRPC commands came
 * back rejected. Phase-two commands survive that, because the outbox repeats them. The
 * command inside a job handler had nobody to repeat it. So a rejected completion of work
 * which was already committed went into the client's fail path, and the job lost one of
 * its retries. Under lasting load, that drives a job into an incident, although every
 * attempt of it succeeded.
 * <p>
 * <b>What bounds it.</b> Two things, and the tighter one wins:
 * <ul>
 * <li><b>the job's lock.</b> A retry after it ends hands the job to somebody else. So the
 * rest of the lock is the real deadline. It is read from the job's own deadline, not from
 * the configured timeout. The timeout says how long the lock was granted for, not how much
 * of it is left.</li>
 * <li><b>{@value #MAX_ATTEMPTS} attempts.</b> A handler which waits for a cluster to calm
 * down holds an execution slot (see {@link Camunda8ExecutionModel}). A slot which waits
 * delivers nothing. Five attempts spread over less than half a second are a hiccup. More
 * than that is a cluster problem which an operator has to see. A worker should not just
 * sit it out.</li>
 * </ul>
 * The backoff is exponential and has the shape of the client's own activation backoff
 * ({@code ExponentialBackoffBuilderImpl}): {@value #INITIAL_BACKOFF_MILLIS} ms at first,
 * factor {@value #BACKOFF_FACTOR}, a ceiling of {@value #MAX_BACKOFF_MILLIS} ms and
 * {@value #JITTER_FACTOR} jitter. So the documentation tells one story about backoff, not
 * two. With five attempts the ceiling is never reached. There are four waits, of 50, 80, 128
 * and 205 ms before jitter, so the whole sequence stays around half a second, which is the
 * point.
 * <p>
 * <b>What is not retried.</b> The classification is the one the outbox already uses
 * ({@link Camunda8Errors#repeatableJobCommandFailure}). So the adapter has one opinion
 * about what a repetition can change, not two. A job which is gone and a request which the
 * cluster rejects come back on the first attempt. And a shutdown ends the retry at once.
 * While the module is going down, the failure belongs to the shutdown. The job is left to
 * its lock and is not failed. A retry loop must not hold up the drain. And it must not
 * turn into a failure which the shutdown would have avoided.
 * <p>
 * <b>A job somebody else holds.</b> One answer ends HERE and not at the caller. If the
 * cluster says that another activation holds the job
 * ({@link Camunda8Errors#jobHeldByAnotherActivation}), the command is dropped with one
 * line. The handler goes on as if the command had been accepted. A leased job gives this
 * answer to the run whose lock expired while it worked. A handler cannot do anything about
 * it. The newer run answered already, and failing the job would take it away from whoever
 * holds it now.
 * <p>
 * <b>A job which is gone.</b> {@link #sendUnlessTheJobIsGone} is the same retry for a caller
 * whose command may arrive after the cluster moved on. It takes the <code>404</code> as a late
 * answer and tells the caller so, instead of throwing it.
 * <p>
 * When the limit is reached, the original failure is thrown again. So after the retries
 * are used up, the behaviour is what it was before this class existed.
 * <p>
 * What may be repeated, what bounds the repetition, and why a shutdown ends it at once is decision
 * 9 in the repository's DECISIONS.md.
 */
@Slf4j
public final class Camunda8CommandRetry {

  private Camunda8CommandRetry() {
  }

  /**
   * How often an outcome command with a repeatable failure is tried in total, the first
   * attempt included.
   */
  public static final int MAX_ATTEMPTS = 5;

  /**
   * The first backoff in milliseconds. It is the client's own first activation delay.
   */
  public static final long INITIAL_BACKOFF_MILLIS = 50;

  /**
   * What each backoff is multiplied by. It is the client's own factor.
   */
  public static final double BACKOFF_FACTOR = 1.6;

  /**
   * The longest backoff in milliseconds. It is the client's own ceiling. On purpose, it is
   * never reached within {@value #MAX_ATTEMPTS} attempts.
   */
  public static final long MAX_BACKOFF_MILLIS = 5_000;

  /**
   * How much a backoff is spread around its nominal value. So two handlers rejected at the
   * same moment do not come back at the same moment. It is the client's own jitter.
   */
  public static final double JITTER_FACTOR = 0.1;

  /**
   * Sends an outcome command. It repeats the command while the answer of the cluster is
   * worth a repetition and the lock of the job still allows another attempt.
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
          // the lock of this job ran out while the work was running. The cluster handed
          // the job out again, and that activation holds it now. The workflow goes on with
          // that run. So this answer is not sent, not repeated and not a failure of
          // anything. Both runs did the same work, and the newer one won
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
   * Sends an outcome command like {@link #send}. A job which the cluster no longer holds
   * counts as a late answer, not as a failure.
   * <p>
   * The cluster answers a command to a job it does not hold with HTTP <code>404</code>, on
   * gRPC with <code>NOT_FOUND</code> ({@link Camunda8Errors#jobAlreadyGone}). For an outcome
   * command that happens in normal operation. A redelivery of the same job answered it
   * first, or the element or the workflow of the job ended while the handler was working,
   * for example because a boundary event fired or the instance was canceled. Either way the
   * cluster has moved on. Failing the job instead would meet the same <code>404</code>, and
   * the only effect would be a warning about a failure that never happened. So the answer is
   * a debug line, and the caller goes on.
   * <p>
   * Only the <code>404</code> is taken this way. A <code>400</code> to a completion, a
   * failure or a BPMN error is a refusal of the request itself, such as variables the cluster
   * cannot read, and it still escapes. The lock renewal is different: there a
   * <code>400</code> means the job is no longer locked, and its caller says so itself.
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
   * @return <code>true</code> where the cluster took the command, <code>false</code> where
   *         it no longer held the job
   * @throws RuntimeException Every other failure, as {@link #send} throws it
   */
  public static boolean sendUnlessTheJobIsGone(
      final String adapterId,
      final String command,
      final long jobKey,
      final String taskName,
      final long lockDeadline,
      final BooleanSupplier shuttingDown,
      final Runnable send) {

    try {
      send(adapterId, command, jobKey, taskName, lockDeadline, shuttingDown, send);
      return true;
    } catch (final RuntimeException e) {
      if (!Camunda8Errors.jobAlreadyGone(e)) {
        throw e;
      }
      log.debug(
          "Camunda8[{}]: the {} of job {} ('{}') found no job - a redelivery answered it already, "
              + "or its element or workflow ended in the meantime, so there is nothing left to answer",
          adapterId,
          command,
          jobKey,
          taskName,
          e);
      return false;
    }

  }

  /**
   * Sends a command against a Camunda-managed user task. It repeats the command while the
   * cluster refuses it because the task is in a transition of its own.
   * <p>
   * <b>Why it exists.</b> VanillaBP tells an application about a user task from the
   * <code>creating</code> listener of that task. So the task is in state
   * <code>CREATING</code> while the application is told, and until the listener job of that
   * message is answered on the partition. An application which answers its task right away
   * addresses a task the cluster is still creating. The cluster refuses the completion with
   * HTTP <code>409</code> ({@link Camunda8Errors#refusedAboutAUserTaskItHolds}). That answer
   * is repeatable, so the outbox would send the operation again. But it would do so only
   * after <code>attempt-frequency</code>, thirty seconds by default, for a state which
   * passes in a few milliseconds.
   * <p>
   * <b>What bounds it.</b> {@value #MAX_ATTEMPTS} attempts with the backoff of
   * {@link #send}, under half a second in total. This runs on a thread of the phase-two
   * dispatcher. Half a second there is a hiccup, but thirty seconds of waiting is a delay
   * somebody notices. Nothing is lost when the attempts are used up. The failure is thrown
   * again, and the outbox repeats the entry as it does today.
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
   * @param worthAWarning Whether giving up is worth a WARN. A job which is gone and a
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
      // the job is gone, or the cluster refuses the command itself. A repetition would
      // only give the same answer. In both cases the caller knows what to do with it
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
   * The nominal backoff after the given attempt, without jitter. The check whether the
   * lock still allows another attempt uses it.
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
   * The backoff which is really waited. It is the nominal one, spread by
   * {@value #JITTER_FACTOR}. So handlers rejected together do not come back together.
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
   * @return Whether the wait completed. An interrupted handler stops retrying and lets
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
