package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.client.Camunda8Drain.InFlightJob;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an operator is told about execution slots which are held by handlers that are not
 * coming back.
 * <p>
 * The watch is driven by hand here rather than by its own thread, so a test does not wait
 * for a schedule. The handlers are faked as entries of the kind a drain holds, because the
 * watch reads nothing else about them.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8SlotWatchTest {

  private static final Duration JOB_TIMEOUT = Duration.ofMinutes(5);

  private final List<InFlightJob> running = new ArrayList<>();

  private final AtomicInteger slots = new AtomicInteger(2);

  private Camunda8SlotWatch watchOf(
      final Collection<InFlightJob> jobs) {

    running.clear();
    running.addAll(jobs);
    return new Camunda8SlotWatch(
        "c8", "vanillabp.adapters.c8.worker-threads", slots::get, running::size, () -> List
            .copyOf(running), job -> JOB_TIMEOUT);

  }

  private static InFlightJob handlerRunningFor(
      final long jobKey,
      final String name,
      final Duration age) {

    return new InFlightJob(
        jobKey, "task", name, "TestProcess", "test-module", Instant.now().minus(age), Thread.currentThread());

  }

  @Test
  @DisplayName("A slot held longer than the job timeout is counted as overdue")
  public void anOverdueHandlerIsCounted() {

    final var watch = watchOf(List.of(
        handlerRunningFor(4711L, "someTask", Duration.ofMinutes(9)),
        handlerRunningFor(4712L, "anotherTask", Duration.ofSeconds(4))));

    assertEquals(1, watch.getOverdueExecutions(), "only the one past its job timeout counts");
    assertTrue(
        watch.getOldestRunningSeconds() >= 540,
        "and the oldest is reported in seconds, whether it is overdue or not: "
            + watch.getOldestRunningSeconds());

  }

  @Test
  @DisplayName("Nothing running is zero rather than an absent value")
  public void anIdleAdapterReportsZero() {

    final var watch = watchOf(List.of());

    assertEquals(0, watch.getOverdueExecutions());
    assertEquals(0d, watch.getOldestRunningSeconds());

  }

  @Test
  @DisplayName("A free slot next to an overdue handler is not an alarm")
  public void oneOverdueHandlerBesideAFreeSlotIsQuiet(
      final CapturedOutput output) {

    slots.set(4);
    final var watch = watchOf(List.of(handlerRunningFor(4711L, "someTask", Duration.ofMinutes(9))));

    watch.lookAtTheSlots();

    assertFalse(
        (output.getOutOfThisTest() + output.getErrOfThisTest()).contains("execution slots are held"),
        "a worker which can still ask for work is not reported");

  }

  @Test
  @DisplayName("Every slot held by an overdue handler is reported with what holds it")
  public void aStandstillNamesWhatHoldsTheSlots(
      final CapturedOutput output) {

    slots.set(2);
    final var watch = watchOf(List.of(
        handlerRunningFor(4711L, "someTask", Duration.ofMinutes(9)),
        handlerRunningFor(4712L, "anotherTask", Duration.ofMinutes(7))));

    watch.lookAtTheSlots();

    final var logged = output.getOutOfThisTest() + output.getErrOfThisTest();
    assertTrue(
        logged.contains("all 2 execution slots are held and 2 of the handlers holding them ran"),
        "the count of slots and of overdue handlers is in the line: "
            + logged);
    assertTrue(
        logged.contains("No worker of this adapter id asks the cluster for work while that lasts"),
        "and what it means for the application: "
            + logged);
    assertTrue(
        logged.contains("vanillabp.adapters.c8.worker-threads"),
        "together with the property which sizes the slots: "
            + logged);
    assertTrue(
        logged.contains("someTask") && logged.contains("anotherTask"),
        "every slot says what holds it: "
            + logged);
    assertTrue(
        logged.contains("job 4711") && logged.contains("workflow module 'test-module'"),
        "with the job key and the module, so an operator does not have to guess: "
            + logged);
    assertTrue(
        logged.contains("Camunda8SlotWatchTest"),
        "and the stack of the overdue handler, which is what tells WHAT hangs: "
            + logged);

  }

  @Test
  @DisplayName("A standstill is reported once, not on every look")
  public void aStandstillIsReportedOnce(
      final CapturedOutput output) {

    slots.set(1);
    final var watch = watchOf(List.of(handlerRunningFor(4711L, "someTask", Duration.ofMinutes(9))));

    watch.lookAtTheSlots();
    watch.lookAtTheSlots();
    watch.lookAtTheSlots();

    final var logged = output.getOutOfThisTest() + output.getErrOfThisTest();
    assertEquals(
        1,
        logged.split("execution slots are held", -1).length - 1,
        "a state which lasts does not fill the log: "
            + logged);

  }

  @Test
  @DisplayName("A slot coming free is said, so the end of the standstill is in the log too")
  public void theEndOfAStandstillIsSaid(
      final CapturedOutput output) {

    slots.set(1);
    final var watch = watchOf(List.of(handlerRunningFor(4711L, "someTask", Duration.ofMinutes(9))));

    watch.lookAtTheSlots();
    running.clear();
    watch.lookAtTheSlots();

    final var logged = output.getOutOfThisTest() + output.getErrOfThisTest();
    assertTrue(
        logged.contains("an execution slot came free after"),
        "the recovery is a line of its own: "
            + logged);
    assertTrue(
        logged.contains("The workers of this adapter id ask the cluster for work again"),
        "and it says what the application does again: "
            + logged);

  }

  @Test
  @DisplayName("The watch answers while every handler thread is blocked")
  public void theWatchRunsOnAThreadOfItsOwn() throws Exception {

    final var executor = new Camunda8PlatformThreadExecutor("c8", 1);
    final var blocked = new CountDownLatch(1);
    final var entered = new CountDownLatch(1);
    try {
      executor.execute(() -> {
        entered.countDown();
        try {
          blocked.await();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS), "the handler holds the only slot");

      final var watch = new Camunda8SlotWatch(
          "c8", "vanillabp.adapters.c8.worker-threads", executor::getBound, () -> executor.getBound() - executor
              .getFreeSlots(), List::of, job -> JOB_TIMEOUT);
      watch.start();
      try {
        assertEquals(0, watch.getOverdueExecutions(), "the watch answered although the slot is blocked");
      } finally {
        watch.close();
      }
    } finally {
      blocked.countDown();
      executor.shutdownNow();
    }

  }

}
