package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The executor of the virtual-thread mode: what the client submits runs on a virtual
 * thread and never more of it at once than the bound allows, while what the client
 * SCHEDULES runs on a platform thread of its own. What both executors share - the bound,
 * the gate in front of the scheduled tasks and the shutdown - is
 * {@link Camunda8ExecutorTest}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8VirtualThreadExecutorTest {

  /**
   * How long a wait for the executor goes on before the test gives up. It guards against
   * something which never got its turn and it measures nothing: what these tests claim is
   * read from which kind of thread ran the work and from what was still inside its slot,
   * so a loaded machine makes a test slower rather than red.
   */
  private static final long UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK = 30000;

  @Test
  @DisplayName("a submitted handler runs on a virtual thread")
  public void submittedWorkRunsOnAVirtualThread() throws Exception {

    final var executor = new Camunda8VirtualThreadExecutor("c8", 4);
    try {
      final var virtual = new AtomicBoolean();
      final var name = new AtomicReference<String>();
      final var ran = new CountDownLatch(1);

      executor.execute(() -> {
        virtual.set(Thread.currentThread().isVirtual());
        name.set(Thread.currentThread().getName());
        ran.countDown();
      });

      assertTrue(
          ran.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
          "the handler ran");
      assertTrue(virtual.get(), "the handler runs on a virtual thread");
      assertTrue(name.get().startsWith("vanillabp-c8-handler-"),
          "the thread is named after the adapter, but was: "
              + name.get());
    } finally {
      executor.shutdownNow();
    }

  }

  @Test
  @DisplayName("the bound holds with more concurrent jobs than the bound")
  public void theBoundHoldsUnderMoreJobsThanSlots() throws Exception {

    final var bound = 3;
    final var jobs = 24;
    final var executor = new Camunda8VirtualThreadExecutor("c8", bound);
    try {
      final var running = new AtomicInteger();
      final var peak = new AtomicInteger();
      final var release = new CountDownLatch(1);
      final var started = new CountDownLatch(bound);
      final var finished = new CountDownLatch(jobs);

      for (int job = 0; job < jobs; job++) {
        executor.execute(() -> {
          peak.accumulateAndGet(running.incrementAndGet(), Math::max);
          started.countDown();
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
          started.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
          "the bound is used");
      assertEquals(0, executor.getFreeSlots(), "every slot is taken while the handlers block");
      release.countDown();
      assertTrue(
          finished.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
          "every job ran");
      assertEquals(bound, peak.get(), "never more handlers at once than the bound allows");
      // the slot is given back AFTER the runnable returned, so the last job's countDown
      // may arrive before its permit is back - the CI runner is slow enough to see it
      assertTrue(
          slotsBackWithin(executor, bound, UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK),
          "the slots are given back");
    } finally {
      executor.shutdownNow();
    }

  }

  /**
   * Waits for every permit of the bound to be back, since the executor releases a slot
   * after the runnable it wrapped returned.
   *
   * @param executor The executor under test
   * @param bound The number of slots it was built with
   * @param millis How long to wait at most
   * @return Whether every slot came back in time
   */
  private static boolean slotsBackWithin(
      final Camunda8VirtualThreadExecutor executor,
      final int bound,
      final long millis) throws InterruptedException {

    final var deadline = System.nanoTime() + (millis * 1_000_000L);
    while (System.nanoTime() < deadline) {
      if (executor.getFreeSlots() == bound) {
        return true;
      }
      Thread.sleep(10);
    }
    return executor.getFreeSlots() == bound;

  }

  @Test
  @DisplayName("a scheduled poll runs on a platform thread while handlers are busy")
  public void schedulingIsNotStarvedByBusyHandlers() throws Exception {

    // one slot is left free on purpose: what a scheduled task does when there is none is
    // the gate of Camunda8ExecutorTest, while this test is about which thread runs it
    final var bound = 3;
    final var executor = new Camunda8VirtualThreadExecutor("c8", bound);
    try {
      final var release = new CountDownLatch(1);
      final var blocking = new CountDownLatch(bound - 1);
      final var handlersInsideTheirSlot = new AtomicInteger();
      for (int job = 0; job < bound - 1; job++) {
        executor.execute(() -> {
          handlersInsideTheirSlot.incrementAndGet();
          blocking.countDown();
          try {
            // the handlers stay inside until this test lets them out, so the poll below
            // can only happen while they are there
            release.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS);
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            handlersInsideTheirSlot.decrementAndGet();
          }
        });
      }
      assertTrue(
          blocking.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
          "the handlers took their slots");

      final var polled = new CountDownLatch(1);
      final var pollThreadIsVirtual = new AtomicBoolean(true);
      final var handlersStillInsideWhenPolled = new AtomicInteger();
      executor.schedule(() -> {
        pollThreadIsVirtual.set(Thread.currentThread().isVirtual());
        handlersStillInsideWhenPolled.set(handlersInsideTheirSlot.get());
        polled.countDown();
      }, 10, TimeUnit.MILLISECONDS);

      // the wait is a generous guard against a poll which never ran; what says that the
      // poll was not starved is the number of handlers which were still inside when it
      // did run. A short wait would carry that claim itself, and on a machine carrying
      // several builds it would report a stopped JVM as a starved poll
      assertTrue(
          polled.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
          "a scheduled poll runs although handlers are inside their slots");
      assertEquals(bound - 1, handlersStillInsideWhenPolled.get(),
          "the poll ran while every handler was still inside its slot");
      assertFalse(pollThreadIsVirtual.get(), "the timing runs on a platform thread");
      release.countDown();
    } finally {
      executor.shutdownNow();
    }

  }

  @Test
  @DisplayName("every way of submitting work runs it on a virtual thread, and every schedule on a platform one")
  public void everyDelegationKeepsItsHalfOfTheSplit() throws Exception {

    final var executor = new Camunda8VirtualThreadExecutor("c8", 4);
    try {
      final Callable<Boolean> virtual = () -> Thread.currentThread().isVirtual();

      assertTrue(executor.submit(virtual).get(5, TimeUnit.SECONDS), "submit(Callable)");
      assertTrue(executor.submit(() -> {
      }, Boolean.TRUE).get(5, TimeUnit.SECONDS), "submit(Runnable, result)");
      assertNull(executor.submit(() -> {
      }).get(5, TimeUnit.SECONDS), "submit(Runnable)");
      assertTrue(
          executor
              .invokeAll(List.of(virtual, virtual))
              .stream()
              .allMatch(future -> {
                try {
                  return future.get();
                } catch (final Exception e) {
                  return false;
                }
              }),
          "invokeAll");
      assertTrue(
          executor.invokeAll(List.of(virtual), 5, TimeUnit.SECONDS).getFirst().get(),
          "invokeAll with a timeout");
      assertTrue(executor.invokeAny(List.of(virtual)), "invokeAny");
      assertTrue(executor.invokeAny(List.of(virtual), 5, TimeUnit.SECONDS), "invokeAny with a timeout");

      // the timing half: a scheduled task never runs on a virtual thread, whichever
      // way the client asks for it
      assertFalse(
          executor
              .schedule(virtual, 1, TimeUnit.MILLISECONDS)
              .get(5, TimeUnit.SECONDS),
          "schedule(Callable)");
      final var periodic = new CountDownLatch(2);
      final var fixedRate = executor.scheduleAtFixedRate(periodic::countDown, 0, 5, TimeUnit.MILLISECONDS);
      assertTrue(
          periodic.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
          "scheduleAtFixedRate");
      fixedRate.cancel(true);
      final var delayed = new CountDownLatch(2);
      final var fixedDelay = executor.scheduleWithFixedDelay(delayed::countDown, 0, 5, TimeUnit.MILLISECONDS);
      assertTrue(
          delayed.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
          "scheduleWithFixedDelay");
      fixedDelay.cancel(true);
    } finally {
      executor.shutdownNow();
    }

  }

  @Test
  @DisplayName("shutdown ends both halves")
  public void shutdownEndsBothHalves() throws Exception {

    final var executor = new Camunda8VirtualThreadExecutor("c8", 2);

    executor.shutdown();

    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "both halves terminate");
    assertTrue(executor.isShutdown());
    assertTrue(executor.isTerminated());

  }

}
