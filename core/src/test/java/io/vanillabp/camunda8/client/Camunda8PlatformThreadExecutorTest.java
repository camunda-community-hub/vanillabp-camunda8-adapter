package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * The executor of the platform-thread mode, which is what an adapter uses unless
 * <code>worker-threads: virtual</code> says otherwise: handlers on a pool as wide as the
 * configured number, and the timing on threads of its own. What both models promise is
 * {@link Camunda8ExecutorTest}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8PlatformThreadExecutorTest {

  /**
   * How long a wait for the executor goes on before the test gives up. It guards against
   * something which never got its turn and it measures nothing: what these tests claim is
   * read from which thread ran the work and from what was still inside its slot, so a
   * loaded machine makes a test slower rather than red.
   */
  private static final long UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK = 30000;

  @Test
  @DisplayName("a submitted handler runs on a platform thread named after the adapter")
  public void submittedWorkRunsOnANamedPlatformThread() throws Exception {

    final var executor = new Camunda8PlatformThreadExecutor("c8", 4);
    try {
      final var virtual = new AtomicBoolean(true);
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
      assertFalse(virtual.get(), "the handler runs on a platform thread");
      assertTrue(name.get().startsWith("vanillabp-c8-handler-"),
          "the thread is named after the adapter, but was: "
              + name.get());
    } finally {
      executor.shutdownNow();
    }

  }

  @Test
  @DisplayName("a scheduled poll runs on a thread of its own while handlers are busy")
  public void schedulingIsNotStarvedByBusyHandlers() throws Exception {

    // one slot is left free on purpose: what a scheduled task does when there is none is
    // the gate of Camunda8ExecutorTest, while this test is about which thread runs it
    final var bound = 3;
    final var executor = new Camunda8PlatformThreadExecutor("c8", bound);
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
      final var pollThread = new AtomicReference<String>();
      final var handlersStillInsideWhenPolled = new AtomicInteger();
      executor.schedule(() -> {
        pollThread.set(Thread.currentThread().getName());
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
      assertEquals("vanillabp-c8-scheduling", pollThread.get(),
          "the timing runs on a thread no handler can occupy");
      release.countDown();
    } finally {
      executor.shutdownNow();
    }

  }

  @Test
  @DisplayName("shutdown ends both halves")
  public void shutdownEndsBothHalves() throws Exception {

    final var executor = new Camunda8PlatformThreadExecutor("c8", 2);

    executor.shutdown();

    assertTrue(
        executor.awaitTermination(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
        "both halves terminate");
    assertTrue(executor.isShutdown());
    assertTrue(executor.isTerminated());

  }

}
