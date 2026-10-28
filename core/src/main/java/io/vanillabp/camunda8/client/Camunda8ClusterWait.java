package io.vanillabp.camunda8.client;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.Topology;
import lombok.extern.slf4j.Slf4j;

/**
 * The one place where a Camunda 8 cluster which is not there YET is treated differently
 * from one which is not there any more. Before the start makes its first round which
 * decides anything, it asks the cluster whether it answers. It waits while the cluster
 * does not.
 * <p>
 * <b>Why it waits and does not repeat.</b> The most common reason why a start cannot reach
 * its cluster is a cluster which boots together with the application. Then every round of
 * the start fails, not only the deployment. That is the tenant check, the deploy command,
 * the question whether the cluster can be searched, and the version queries of the startup
 * check. A retry around one of them would cover a quarter of the cases. So nothing is
 * repeated. The start waits once, and everything behind the wait still fails the start
 * the way it always did. A cluster which breaks down in the middle of a deployment is not
 * booting. It is failing.
 * <p>
 * <b>What ends the wait,</b> whichever comes first:
 * <ul>
 * <li>The cluster answers. This is the normal case, and it costs one request.</li>
 * <li>The wait is used up. The start ends, and names the address, the time waited and the
 * last answer of the cluster.</li>
 * <li>The cluster answers something which a repetition cannot change
 * ({@link Camunda8Errors#permanentFailure}). Credentials the cluster refuses and a request
 * it rejects are not a question of time. So the start ends at once, not at the end of the
 * wait. That is what makes a long wait acceptable.</li>
 * </ul>
 * <b>What is written when it ends.</b> The answer which ends the wait names the version of
 * the cluster. {@link Camunda8ClusterVersionCheck} compares it with the client.
 * <p>
 * <b>What is written while it waits.</b> There is a line before the first attempt which
 * names the wait. Then every few seconds there is a line with the time gone and the last
 * answer of the cluster. Nothing here is summarised, and nothing is remembered as "said
 * already". A start which waits for a cluster must not look like a start which hangs. And
 * the last answer turns a typo in the address into "connection refused" on the very first
 * line, not into a surprise ten minutes later.
 * <p>
 * Why a start waits once rather than repeating each round is decision 17 in the repository's
 * DECISIONS.md.
 */
@Slf4j
public final class Camunda8ClusterWait {

  private Camunda8ClusterWait() {
  }

  /**
   * How long one attempt may take. So it is also how often the wait says where it stands.
   * It is short enough that a cluster which swallows requests still gives a line every few
   * seconds. It is long enough that a cluster which is only slow is not given up within one
   * attempt.
   */
  public static final Duration TIME_PER_ATTEMPT = Duration.ofSeconds(5);

  /**
   * Waits for the cluster of one adapter instance to answer a topology request. The health
   * check asks the same question. It is the cheapest one a Camunda 8 cluster answers, and it
   * needs neither secondary storage nor a tenant.
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

    final var topology = new AtomicReference<Topology>();
    untilTheClusterAnswers(
        adapterId,
        configuration.describeAddress(),
        configuration.resolvedStartupWait(),
        TIME_PER_ATTEMPT,
        () -> topology.set(askForTheTopology(client, TIME_PER_ATTEMPT)));

    // the answer which ended the wait names the version of the cluster. So comparing it
    // with the client costs no request of its own. A start with 'startup-wait: PT0S' asked
    // nothing, and says so instead (see decision 90 in the repository's DECISIONS.md)
    if (topology.get() == null) {
      Camunda8ClusterVersionCheck.logThatNothingWasCompared(adapterId);
    } else {
      Camunda8ClusterVersionCheck.logTheVerdict(adapterId, topology.get().getGatewayVersion());
    }

  }

  /**
   * The waiting itself. It is told what to ask and how long an attempt takes. In this shape
   * a test can drive it without a cluster and without waiting minutes for a result.
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
      // the start then behaves as it did before there was a wait. The first round which
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
   * One attempt. It is a topology request with two limits, as in the health check. The
   * client's own timeout ends the request. The wait on the future ends the waiting for it.
   * Without the second one, a request which the transport never finishes would outlive the
   * deadline this wait promised.
   *
   * @return The topology the cluster answered with
   */
  private static Topology askForTheTopology(
      final CamundaClient client,
      final Duration timePerAttempt) {

    final var request = client
        .newTopologyRequest()
        .requestTimeout(timePerAttempt)
        .send();
    try {
      return request.get(timePerAttempt.toMillis(), TimeUnit.MILLISECONDS);
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
      // wrapped, because a bare TimeoutException has no message. It also does not say
      // that the limit of this adapter ended the attempt
      throw new IllegalStateException("The cluster did not answer the topology request in time", e);
    }

  }

  /**
   * What the cluster answered, short enough for a line which repeats every few seconds. It
   * is the root of the chain of causes, with its type in front of it. That is where
   * "connection refused" and "unknown host" stand.
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
   * How long ago something happened, rounded to milliseconds. Nanoseconds tell an operator
   * nothing, and they do not make a wait of several minutes clearer.
   */
  private static Duration timeSince(
      final long nanos) {

    return Duration
        .ofMillis(Duration
            .ofNanos(System.nanoTime() - nanos)
            .toMillis());

  }

  /**
   * Waits for the rest of an attempt. So a cluster which refuses the connection at once is
   * asked as often as one which swallows the request. And the line which says where the
   * wait stands comes at the same pace either way.
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
