package io.vanillabp.camunda8.client;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.camunda.client.CamundaClient;
import lombok.extern.slf4j.Slf4j;

/**
 * The one place a Camunda 8 cluster which is not there YET is treated differently from one
 * which is not there any more: before the start makes the first round which decides
 * anything, it asks the cluster whether it answers, and waits while it does not.
 * <p>
 * <b>Why waiting rather than repeating.</b> The commonest reason a start cannot reach its
 * cluster is a cluster booting together with the application, and that lets every round of
 * the start fail, not only the deployment - the tenant check, the deploy command, the
 * question whether the cluster can be searched and the version queries of the startup
 * check. A retry wrapped around one of them would cover a quarter of the cases. So nothing
 * is repeated: it is waited for once, and everything behind the wait keeps failing the
 * start the way it always did. A cluster which breaks down in the middle of a deployment is
 * not booting, it is failing.
 * <p>
 * <b>What ends the wait,</b> whichever comes first:
 * <ul>
 * <li>the cluster answers - the normal case, and it costs one request;</li>
 * <li>the wait is used up - the start ends, naming the address, the time waited and the
 * cluster's last answer;</li>
 * <li>the cluster answers something a repetition cannot change
 * ({@link Camunda8Errors#permanentFailure}) - credentials the cluster refuses and a request
 * it rejects are not a question of time, so the start ends at once instead of at the end of
 * the wait. That is what makes a long wait bearable.</li>
 * </ul>
 * <b>What is written while it waits.</b> A line before the first attempt naming the wait,
 * and one every few seconds carrying the time gone and the cluster's last answer. Nothing
 * here is summarised and nothing is remembered as "said already": a start waiting for a
 * cluster must not look like a start which hangs, and the last answer is what turns a typo
 * in the address into "connection refused" on the very first line instead of a surprise ten
 * minutes later.
 * <p>
 * Why a start waits once rather than repeating each round is decision 17 in the repository's
 * DECISIONS.md.
 */
@Slf4j
public final class Camunda8ClusterWait {

  private Camunda8ClusterWait() {
  }

  /**
   * How long one attempt may take, and therefore how often the wait says where it stands.
   * Short enough that a cluster which swallows requests still produces a line every few
   * seconds, long enough that a cluster which is merely slow is not given up on within one
   * attempt.
   */
  public static final Duration TIME_PER_ATTEMPT = Duration.ofSeconds(5);

  /**
   * Waits for the cluster of one adapter instance to answer a topology request - the same
   * question the health check asks, because it is the cheapest one a Camunda 8 cluster
   * answers and it needs neither secondary storage nor a tenant.
   *
   * @param adapterId The adapter instance
   * @param configuration Its connection configuration, which says how long to wait and
   *          where to
   * @param client Its client
   * @throws IllegalStateException If the cluster did not answer within the wait, or
   *           answered something a repetition cannot change
   */
  public static void untilTheClusterAnswers(
      final String adapterId,
      final Camunda8AdapterConfiguration configuration,
      final CamundaClient client) {

    untilTheClusterAnswers(
        adapterId,
        configuration.describeAddress(),
        configuration.resolvedStartupWait(),
        TIME_PER_ATTEMPT,
        () -> askForTheTopology(client, TIME_PER_ATTEMPT));

  }

  /**
   * The waiting itself, told what to ask and how long an attempt takes - the shape a test
   * can drive without a cluster and without waiting minutes for a result.
   *
   * @param adapterId The adapter instance
   * @param address Where this adapter talks to, for the messages
   * @param startupWait How long to wait at most, <code>PT0S</code> not waiting at all
   * @param timePerAttempt How long one attempt takes at most, which is also the pause
   *          between two of them
   * @param askTheCluster One attempt, throwing what the cluster answered
   * @throws IllegalStateException If the cluster did not answer within the wait, or
   *           answered something a repetition cannot change
   */
  static void untilTheClusterAnswers(
      final String adapterId,
      final String address,
      final Duration startupWait,
      final Duration timePerAttempt,
      final Runnable askTheCluster) {

    if (startupWait.isZero() || startupWait.isNegative()) {
      // the start then behaves as it did before there was a wait: the first round which
      // cannot reach the cluster ends it
      return;
    }

    log.info("Camunda8[{}]: waiting for cluster {} for {} ...", adapterId, address, startupWait);
    final var startedAt = System.nanoTime();
    final var deadline = startedAt + startupWait.toNanos();
    while (true) {
      final var attemptStartedAt = System.nanoTime();
      try {
        askTheCluster.run();
        log.info(
            "Camunda8[{}]: the cluster {} answered after {}",
            adapterId,
            address,
            timeSince(startedAt));
        return;
      } catch (final RuntimeException e) {
        if (Camunda8Errors.permanentFailure(e)) {
          throw new IllegalStateException(
              """
                  Camunda 8 adapter '%s' cannot use the cluster %s: the cluster REFUSED the request, and \
                  no amount of waiting ('%s: %s') changes an answer like that. The cluster said: %s. \
                  Check the address, the credentials and the tenant of this adapter."""
                  .formatted(
                      adapterId,
                      address,
                      Camunda8AdapterConfiguration.propertyKey(adapterId, "startup-wait"),
                      startupWait,
                      whatTheClusterSaid(e)), e);
        }
        if (System.nanoTime() >= deadline) {
          throw new IllegalStateException(
              """
                  Camunda 8 adapter '%s' did not reach the cluster %s within '%s: %s' (waited %s). The \
                  last answer was: %s. Start the cluster, correct the address, raise the wait, or set \
                  '%s' to 'PT0S' to have the start fail on the first round it cannot make."""
                  .formatted(
                      adapterId,
                      address,
                      Camunda8AdapterConfiguration.propertyKey(adapterId, "startup-wait"),
                      startupWait,
                      timeSince(startedAt),
                      whatTheClusterSaid(e),
                      Camunda8AdapterConfiguration.propertyKey(adapterId, "startup-wait")), e);
        }
        log.info(
            "Camunda8[{}]: still waiting for cluster {} - {} of {} gone, last answer: {}",
            adapterId,
            address,
            timeSince(startedAt),
            startupWait,
            whatTheClusterSaid(e));
        if (!sleepRestOfTheAttempt(attemptStartedAt, timePerAttempt)) {
          throw new IllegalStateException(
              "Camunda 8 adapter '%s' was interrupted while it waited for the cluster %s!"
                  .formatted(adapterId, address), e);
        }
      }
    }

  }

  /**
   * One attempt: a topology request bounded twice, the way the health check bounds it - the
   * client's own timeout ends the request, the wait on the future ends the waiting for it.
   * Without the second one a request the transport never finishes would outlive the deadline
   * this wait promised.
   */
  private static void askForTheTopology(
      final CamundaClient client,
      final Duration timePerAttempt) {

    final var request = client
        .newTopologyRequest()
        .requestTimeout(timePerAttempt)
        .send();
    try {
      request.get(timePerAttempt.toMillis(), TimeUnit.MILLISECONDS);
    } catch (final InterruptedException e) {
      Thread
          .currentThread()
          .interrupt();
      request.cancel(true);
      throw new IllegalStateException("Interrupted while asking the cluster for its topology", e);
    } catch (final ExecutionException e) {
      request.cancel(true);
      throw e.getCause() instanceof RuntimeException answer
          ? answer
          : new IllegalStateException(e.getCause());
    } catch (final TimeoutException e) {
      request.cancel(true);
      // wrapped, because a bare TimeoutException carries neither a message nor the fact
      // that it was this adapter's own bound which ended the attempt
      throw new IllegalStateException("The cluster did not answer the topology request in time", e);
    }

  }

  /**
   * What the cluster answered, short enough for a line which repeats every few seconds: the
   * root of the chain of causes, which is where "connection refused" and "unknown host"
   * stand, with its type in front of it.
   */
  private static String whatTheClusterSaid(
      final Throwable failure) {

    var cause = failure;
    while ((cause.getCause() != null) && (cause.getCause() != cause)) {
      cause = cause.getCause();
    }
    return Camunda8Errors.incidentMessage(cause);

  }

  /**
   * How long ago something happened, rounded to milliseconds - nanoseconds in a line an
   * operator reads say nothing, and a wait measured in minutes is not made clearer by them.
   */
  private static Duration timeSince(
      final long nanos) {

    return Duration
        .ofMillis(Duration
            .ofNanos(System.nanoTime() - nanos)
            .toMillis());

  }

  /**
   * Waits out what is left of an attempt, so a cluster refusing the connection at once is
   * asked as often as one which swallows the request - and the line saying where the wait
   * stands comes at the same pace either way.
   *
   * @return Whether the pause completed, <code>false</code> saying the thread was
   *         interrupted
   */
  private static boolean sleepRestOfTheAttempt(
      final long attemptStartedAt,
      final Duration timePerAttempt) {

    final var rest = timePerAttempt.toNanos() - (System.nanoTime() - attemptStartedAt);
    if (rest <= 0) {
      return true;
    }
    try {
      Thread.sleep(Duration.ofNanos(rest).toMillis());
      return true;
    } catch (final InterruptedException e) {
      Thread
          .currentThread()
          .interrupt();
      return false;
    }

  }

}
