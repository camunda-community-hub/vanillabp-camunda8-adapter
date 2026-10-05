package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ClientStatusException;
import io.camunda.client.api.command.ProblemException;
import io.grpc.Status;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.camunda8.wiring.Camunda8RetryBackoffResolver;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The retry loop itself: what makes it try again, what makes it stop and what the
 * waits between two attempts look like. The loop is a pure function of the failure, the
 * attempt count and the clock, so its boundaries belong here rather than into a test
 * against a cluster - real backpressure is not something a test can ask a broker for.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CommandRetryTest {

  /**
   * How REST reports backpressure: HTTP 503 with the title the engine sends.
   */
  private static ProblemException backpressure() {

    final var details = new ProblemDetail();
    details.setStatus(503);
    details.setTitle("RESOURCE_EXHAUSTED");
    return new ProblemException(503, "RESOURCE_EXHAUSTED", details);

  }

  /**
   * How gRPC reports the same thing.
   */
  private static ClientStatusException grpcBackpressure() {

    return new ClientStatusException(Status.RESOURCE_EXHAUSTED, null);

  }

  private static ProblemException problem(
      final int status) {

    final var details = new ProblemDetail();
    details.setStatus(status);
    return new ProblemException(status, "reason", details);

  }

  private static long lockOf(
      final Duration remaining) {

    return System.currentTimeMillis() + remaining.toMillis();

  }

  private static void send(
      final long lockDeadline,
      final boolean shuttingDown,
      final Runnable command) {

    Camunda8CommandRetry
        .send("c8", "completion", 4711L, "someTask", lockDeadline, () -> shuttingDown, command);

  }

  @Test
  @DisplayName("A rejected command is repeated until it goes through")
  public void backpressureIsRepeated() {

    final var attempts = new AtomicInteger();

    send(lockOf(Duration.ofMinutes(1)), false, () -> {
      if (attempts.incrementAndGet() < 3) {
        throw backpressure();
      }
    });

    assertEquals(3, attempts.get(), "two rejections and the attempt which went through");

  }

  @Test
  @DisplayName("Backpressure on gRPC is repeated just like backpressure on REST")
  public void grpcBackpressureIsRepeated() {

    final var attempts = new AtomicInteger();

    send(lockOf(Duration.ofMinutes(1)), false, () -> {
      if (attempts.incrementAndGet() < 2) {
        throw grpcBackpressure();
      }
    });

    assertEquals(2, attempts.get());

  }

  @Test
  @DisplayName("A command which timed out is sent again, and the second attempt goes through")
  public void aTimedOutCommandIsSentAgain() {

    final var attempts = new AtomicInteger();

    send(lockOf(Duration.ofMinutes(5)), false, () -> {
      if (attempts.incrementAndGet() < 2) {
        throw new CompletionException(
            new SocketTimeoutException("Read timed out"));
      }
    });

    assertEquals(2, attempts.get(), "a socket which ran out of time says nothing about the job");

  }

  @Test
  @DisplayName("A timeout leaves a job's lock long enough for a second attempt")
  public void aTimeoutLeavesEnoughLockForASecondAttempt() {

    // The arithmetic behind the previous test, with the values an installation which
    // configures neither of them runs on: the lock of a delivered job lasts job-timeout,
    // and a command which ran out of time consumed request-timeout of it before it
    // failed. What is left has to outlast the first backoff, or the retry would be
    // formally responsible for timeouts and practically never run.
    final var lockLeftAfterATimeout = Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT
        .minus(Camunda8AdapterConfiguration.DEFAULT_REQUEST_TIMEOUT);
    assertTrue(
        lockLeftAfterATimeout.toMillis() > Camunda8CommandRetry.backoffMillis(1),
        "five minutes of lock minus ten seconds of request against a backoff of 50 ms");

    final var attempts = new AtomicInteger();
    send(lockOf(lockLeftAfterATimeout), false, () -> {
      if (attempts.incrementAndGet() < 2) {
        throw new CompletionException(
            new SocketTimeoutException("Read timed out"));
      }
    });
    assertEquals(2, attempts.get(), "so the retry really does run");

    // and the other way round: a lock shorter than one request cannot carry a second
    // attempt, which is what the retry says instead of pretending otherwise
    final var tooShort = new AtomicInteger();
    assertThrows(
        RuntimeException.class,
        () -> send(lockOf(Duration.ZERO), false, () -> {
          tooShort.incrementAndGet();
          throw new CompletionException(
              new SocketTimeoutException("Read timed out"));
        }));
    assertEquals(1, tooShort.get(), "one attempt, because there is no lock left to spend");

  }

  @Test
  @DisplayName("A request the cluster refuses is not repeated")
  public void permanentFailuresAreNotRepeated() {

    final var attempts = new AtomicInteger();

    assertThrows(
        ProblemException.class,
        () -> send(lockOf(Duration.ofMinutes(1)), false, () -> {
          attempts.incrementAndGet();
          throw problem(400);
        }));

    assertEquals(1, attempts.get(), "repeating cannot change a 400");

  }

  @Test
  @DisplayName("A job which is gone is not repeated - the benign case stays benign")
  public void aGoneJobIsNotRepeated() {

    final var attempts = new AtomicInteger();

    assertThrows(
        ProblemException.class,
        () -> send(lockOf(Duration.ofMinutes(1)), false, () -> {
          attempts.incrementAndGet();
          throw problem(404);
        }));

    assertEquals(1, attempts.get(), "the at-least-once residual must not become a retry storm");

  }

  @Test
  @DisplayName("A job another activation holds ends here, without a repetition and without a failure")
  public void aJobSomebodyElseHoldsIsDroppedQuietly() {

    final var attempts = new AtomicInteger();

    // the lock of this run expired while its work was running, the cluster handed the job
    // out again and that run answered first. There is nothing to repeat, and failing the
    // job would take it away from whoever holds it now - so the command is dropped
    send(lockOf(Duration.ofMinutes(1)), false, () -> {
      attempts.incrementAndGet();
      throw problem(409);
    });

    assertEquals(1, attempts.get(), "no repetition: no attempt of it is going to be accepted");

    attempts.set(0);
    send(lockOf(Duration.ofMinutes(1)), false, () -> {
      attempts.incrementAndGet();
      throw new ClientStatusException(Status.FAILED_PRECONDITION, null);
    });

    assertEquals(1, attempts.get(), "and the same on the other transport");

  }

  @Test
  @DisplayName("A cluster which keeps rejecting costs five attempts and no more")
  public void theAttemptCountIsTheBound() {

    final var attempts = new AtomicInteger();

    assertThrows(
        ProblemException.class,
        () -> send(lockOf(Duration.ofMinutes(1)), false, () -> {
          attempts.incrementAndGet();
          throw backpressure();
        }));

    assertEquals(Camunda8CommandRetry.MAX_ATTEMPTS, attempts.get());

  }

  @Test
  @DisplayName("The remaining lock stops the retry before the attempt count does")
  public void theLockIsTheTighterBound() {

    final var attempts = new AtomicInteger();

    // 60 ms of lock left: the first backoff of 50 ms still fits, the second one of 80 ms
    // does not - so the loop stops after two attempts rather than after five
    assertThrows(
        ProblemException.class,
        () -> send(lockOf(Duration.ofMillis(60)), false, () -> {
          attempts.incrementAndGet();
          throw backpressure();
        }));

    assertTrue(
        attempts.get() < Camunda8CommandRetry.MAX_ATTEMPTS,
        "expected the lock to stop it early but it ran "
            + attempts.get()
            + " attempts");

  }

  @Test
  @DisplayName("A lock which is already gone stops the retry at the first attempt")
  public void anExpiredLockStopsAtOnce() {

    final var attempts = new AtomicInteger();

    assertThrows(
        ProblemException.class,
        () -> send(lockOf(Duration.ofSeconds(-1)), false, () -> {
          attempts.incrementAndGet();
          throw backpressure();
        }));

    assertEquals(1, attempts.get(), "retrying past the lock hands the job to somebody else");

  }

  @Test
  @DisplayName("A shutdown ends the retry at once, so the job keeps its lock and retries")
  public void aShutdownEndsTheRetry() {

    final var attempts = new AtomicInteger();

    assertThrows(
        ProblemException.class,
        () -> send(lockOf(Duration.ofMinutes(1)), true, () -> {
          attempts.incrementAndGet();
          throw backpressure();
        }));

    assertEquals(1, attempts.get(), "a retry loop must not hold the drain");

  }

  @Test
  @DisplayName("The backoff is the client's own shape, and its ceiling is never reached")
  public void theBackoffFollowsTheClient() {

    assertEquals(50, Camunda8CommandRetry.backoffMillis(1));
    assertEquals(80, Camunda8CommandRetry.backoffMillis(2));
    assertEquals(128, Camunda8CommandRetry.backoffMillis(3));
    assertEquals(205, Camunda8CommandRetry.backoffMillis(4));
    // the last wait of the last allowed retry - well below the client's 5s ceiling, which
    // is deliberate: a handler waiting occupies an execution slot
    assertEquals(328, Camunda8CommandRetry.backoffMillis(Camunda8CommandRetry.MAX_ATTEMPTS));
    assertTrue(
        Camunda8CommandRetry
            .backoffMillis(Camunda8CommandRetry.MAX_ATTEMPTS) < Camunda8CommandRetry.MAX_BACKOFF_MILLIS);
    // and a very late attempt would be capped rather than growing forever
    assertEquals(Camunda8CommandRetry.MAX_BACKOFF_MILLIS, Camunda8CommandRetry.backoffMillis(20));

  }

  @Test
  @DisplayName("The jitter stays within a tenth of the nominal backoff")
  public void theJitterIsBounded() {

    for (var i = 0; i < 100; ++i) {
      final var backoff = Camunda8CommandRetry.nextBackoff(3).toMillis();
      assertTrue(
          (backoff >= 115) && (backoff <= 141),
          "expected 128 ms plus or minus a tenth but got "
              + backoff);
    }

  }

  // --- the backoff a failed job carries ---------------------------------------------------

  @Test
  @DisplayName("Nothing configured means ten seconds before the cluster tries again")
  public void theDefaultBackoffIsTenSeconds() {

    assertEquals(
        Duration.ofSeconds(10),
        new Camunda8AdapterConfiguration().resolvedRetryBackoff(),
        "long enough for a transient dependency to come back, short enough that three "
            + "retries are done deciding within half a minute");
    assertEquals(
        Duration.ofSeconds(10),
        Camunda8RetryBackoffResolver.DEFAULT_RETRY_BACKOFF,
        "and the constant says the same as the ISO notation the messages use");

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRetryBackoff(Duration.ofSeconds(3));
    assertEquals(Duration.ofSeconds(3), configuration.resolvedRetryBackoff(), "a configured value wins");

  }

  @Test
  @DisplayName("A negative backoff fails the boot naming the property and the way out")
  public void aNegativeBackoffFailsTheBoot() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRetryBackoff(Duration.ofSeconds(-1));

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> configuration.validateRetryBackoff("c8"));

    assertTrue(failure.getMessage().contains("vanillabp.adapters.c8.retry-backoff"));
    assertTrue(failure.getMessage().contains("PT10S"), "the message names the default");

  }

  @Test
  @DisplayName("A handler built without a resolver still gets the default")
  public void aMissingResolverIsTheDefault() {

    assertEquals(
        Camunda8RetryBackoffResolver.DEFAULT_RETRY_BACKOFF,
        Camunda8RetryBackoffResolver
            .resolve(null, "m", "P", "t")
            .duration());
    assertEquals(
        Camunda8RetryBackoffResolver.DEFAULT_RETRY_BACKOFF,
        Camunda8RetryBackoffResolver.resolve((
            module,
            process,
            task) -> null, "m", "P", "t")
            .duration(),
        "and a resolver which answers nothing does not become a null backoff");

  }

}
