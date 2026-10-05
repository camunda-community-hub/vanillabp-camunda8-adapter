package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What both execution models promise, asserted against each of them: a bound which holds,
 * and a scheduled task which asks the cluster for work only while a slot is free.
 * <p>
 * The second one is the designed back pressure. Without it a worker whose slots are all
 * busy keeps activating jobs which then wait in front of the slots, spending the lock they
 * were handed out with; with it the poll waits instead, and the job stays at the cluster,
 * where another node can take it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ExecutorTest {

  /**
   * How long a wait for the executor goes on before the test gives up. It guards against
   * something which never got its turn and it measures nothing: what these tests claim is
   * read from the slots and from the handlers waiting for one, so a loaded machine makes a
   * test slower rather than red.
   */
  private static final long UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK = 30000;

  /**
   * The two models, each as a way of building an executor of a given width.
   *
   * @return The models under test, named after the value which configures them
   */
  static Stream<Arguments> executionModels() {

    return Stream.of(
        Arguments.of(
            "worker-threads: <number>",
            (IntFunction<Camunda8Executor>) bound -> new Camunda8PlatformThreadExecutor("c8", bound)),
        Arguments.of(
            "worker-threads: virtual",
            (IntFunction<Camunda8Executor>) bound -> new Camunda8VirtualThreadExecutor("c8", bound)));

  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("executionModels")
  public void neverMoreHandlersAtOnceThanTheBound(
      final String model,
      final IntFunction<Camunda8Executor> executionModel) throws Exception {

    final var bound = 3;
    final var jobs = bound * 8;
    final var executor = executionModel.apply(bound);
    try {
      final var running = new AtomicInteger();
      final var peak = new AtomicInteger();
      final var release = new CountDownLatch(1);
      final var everySlotTaken = new CountDownLatch(bound);
      final var finished = new CountDownLatch(jobs);

      for (int job = 0; job < jobs; job++) {
        executor.execute(() -> {
          peak.accumulateAndGet(running.incrementAndGet(), Math::max);
          everySlotTaken.countDown();
          try {
            // the handlers stay inside until this test lets them out, so what is read
            // about the slots below is read while they really are taken
            release.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS);
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            running.decrementAndGet();
            finished.countDown();
          }
        });
      }

      assertTrue(
          everySlotTaken.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
          "the bound is used");
      assertEquals(0, executor.getFreeSlots(), "no slot is free while the handlers block");
      assertTrue(waitingReaches(executor, jobs - bound), "the jobs which found no slot are counted");
      release.countDown();
      assertTrue(
          finished.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
          "every job ran");
      assertEquals(bound, peak.get(), "never more handlers at once than the bound allows");
    } finally {
      executor.shutdownNow();
    }

  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("executionModels")
  public void aScheduledPollWaitsUntilASlotIsFree(
      final String model,
      final IntFunction<Camunda8Executor> executionModel) throws Exception {

    final var bound = 2;
    final var executor = executionModel.apply(bound);
    try {
      final var release = new CountDownLatch(1);
      takeEverySlot(executor, bound, release);

      final var polled = new CountDownLatch(1);
      executor.schedule(polled::countDown, 1, TimeUnit.MILLISECONDS);

      assertFalse(
          polled.await(Camunda8Executor.LOOK_FOR_A_SLOT_AGAIN_MILLIS * 5, TimeUnit.MILLISECONDS),
          "nothing asks the cluster for work while there is nothing to run it on");
      assertEquals(0, executor.getFreeSlots(), "the handlers are still inside their slots");

      release.countDown();

      assertTrue(
          polled.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
          "and the poll happens as soon as a slot is free");
    } finally {
      executor.shutdownNow();
    }

  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("executionModels")
  public void cancellingAWaitingPollReachesTheAttemptItWaitsIn(
      final String model,
      final IntFunction<Camunda8Executor> executionModel) throws Exception {

    final var bound = 1;
    final var executor = executionModel.apply(bound);
    try {
      final var release = new CountDownLatch(1);
      takeEverySlot(executor, bound, release);

      final var polled = new CountDownLatch(1);
      final var poll = executor.schedule(polled::countDown, 1, TimeUnit.MILLISECONDS);

      // the poll is waiting for a slot now, which is the state a caller cancelling its
      // own scheduled task has to reach: the 8.10 client cancels the tasks around a job
      // stream that way, and it holds the attempt which happens to be pending. The state
      // is read rather than waited out - a pause of two look-again windows only guessed
      // at it, and on a machine carrying several builds a guess is what it stays
      awaitThePollLookingForASlotAgain(poll);
      assertTrue(poll.cancel(false), "the waiting poll is cancelled");
      assertTrue(poll.isCancelled());

      release.countDown();

      assertFalse(
          polled.await(Camunda8Executor.LOOK_FOR_A_SLOT_AGAIN_MILLIS * 5, TimeUnit.MILLISECONDS),
          "a cancelled poll does not run when a slot becomes free either");
    } finally {
      executor.shutdownNow();
    }

  }

  /**
   * Fills every execution slot with a handler which stays inside until the latch is
   * counted down.
   *
   * @param executor The executor under test
   * @param bound How many slots it has
   * @param release What ends the handlers
   */
  private static void takeEverySlot(
      final Camunda8Executor executor,
      final int bound,
      final CountDownLatch release) throws InterruptedException {

    final var everySlotTaken = new CountDownLatch(bound);
    for (int job = 0; job < bound; job++) {
      executor.execute(() -> {
        everySlotTaken.countDown();
        try {
          // the handlers stay inside until the caller lets them out, so every slot is
          // really taken for as long as the test reads something about them
          release.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    }
    assertTrue(
        everySlotTaken.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
        "the handlers took every slot");

  }

  /**
   * Waits until the poll found no slot and armed the next attempt itself.
   * <p>
   * The test arms the first attempt a millisecond out, and every attempt the poll arms
   * carries {@link Camunda8Executor#LOOK_FOR_A_SLOT_AGAIN_MILLIS}. So a pending attempt
   * which is further out than that first millisecond is one the poll armed, and that is
   * the state a cancel has to reach.
   *
   * @param poll The scheduled poll which found every slot busy
   */
  private static void awaitThePollLookingForASlotAgain(
      final ScheduledFuture<?> poll) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK;
    while (poll.getDelay(TimeUnit.MILLISECONDS) <= 1) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the poll never looked for a slot again");
      Thread.sleep(5);
    }

  }

  /**
   * Waits until as many handlers as expected wait for a slot - they are counted where they
   * wait, and a job submitted a moment ago may not have got there yet.
   *
   * @param executor The executor under test
   * @param expected How many are waiting once every submitted job arrived
   * @return Whether the number was reached before the wait gave up
   */
  private static boolean waitingReaches(
      final Camunda8Executor executor,
      final int expected) throws InterruptedException {

    final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK);
    while (System.nanoTime() < deadline) {
      if (executor.getWaiting() == expected) {
        return true;
      }
      Thread.sleep(10);
    }
    return executor.getWaiting() == expected;

  }

}
