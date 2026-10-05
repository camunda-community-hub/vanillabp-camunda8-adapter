package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the drain of one workflow module does while the application goes down.
 * The two halves are tested separately here - the waiting, and the rule which keeps a
 * failure of the shutdown from being reported as a failure of the application.
 * <p>
 * The shutdown waits for a second thing beside the handlers, the workers reporting
 * themselves closed, and the line the drain writes can only say what this adapter knows.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8DrainTest {

  private final Camunda8Drain drain = new Camunda8Drain("c8", "test-module");

  @Test
  @DisplayName("A drain with nothing in flight and released workers returns immediately")
  public void anEmptyDrainReturnsImmediately() {

    // the same reading as in the test of a grace of zero below: one question to the
    // workers is a drain which returned on its first look, and no clock has to say it
    final var workersAsked = new AtomicInteger();
    assertTrue(drain.awaitQuiet(Duration.ofSeconds(20), 2, () -> {
      workersAsked.incrementAndGet();
      return true;
    }).isQuiet());

    assertEquals(1, workersAsked.get(), "a shutdown with nothing to wait for pays nothing");

  }

  @Test
  @DisplayName("A worker whose activation request is still parked is waited for")
  public void aWorkerWhichIsNotReleasedIsWaitedFor() {

    final var releasedAt = System.nanoTime() + Duration.ofMillis(400).toNanos();

    final var startedAt = System.nanoTime();
    final var outcome = drain.awaitQuiet(
        Duration.ofSeconds(20),
        1,
        () -> System.nanoTime() >= releasedAt);
    final var waited = (System.nanoTime() - startedAt) / 1_000_000;

    assertTrue(outcome.isQuiet(), "the worker reported itself closed before the grace passed");
    assertTrue(
        waited >= 350,
        "and the shutdown waited for it rather than closing the client over a parked activation request (was "
            + waited
            + " ms)");

  }

  @Test
  @DisplayName("The drain waits for a handler which finishes within the grace period")
  public void aHandlerWithinTheGraceIsWaitedFor() throws Exception {

    drain.jobStarted(4711L, "task", "someTask", "TestProcess");
    final var entered = new CountDownLatch(1);
    final var handler = new Thread(() -> {
      try {
        entered.countDown();
        TimeUnit.MILLISECONDS.sleep(300);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        drain.jobFinished(4711L);
      }
    });
    handler.start();
    assertTrue(entered.await(5, TimeUnit.SECONDS), "the handler started");

    assertTrue(
        drain.awaitQuiet(Duration.ofSeconds(20), 1, () -> true).isQuiet(),
        "the handler came back before the grace period passed");
    assertTrue(drain.getInFlight().isEmpty(), "and nothing is left in flight");

    handler.join(TimeUnit.SECONDS.toMillis(5));

  }

  @Test
  @DisplayName("A handler longer than the grace period is given up on and named per job")
  public void aHandlerBeyondTheGraceIsReported() {

    drain.jobStarted(4711L, "task", "someTask", "TestProcess");
    drain.jobStarted(4712L, "user-task listener", "someUserTask", "TestProcess");

    final var startedAt = System.nanoTime();
    assertFalse(
        drain.awaitQuiet(Duration.ofMillis(300), 1, () -> true).handlersReturned(),
        "the handlers did not come back");
    final var waited = (System.nanoTime() - startedAt) / 1_000_000;
    assertTrue(waited >= 300, "the grace period was granted before giving up (was "
        + waited
        + " ms)");

    assertEquals(2, drain.getInFlight().size(), "both are still in flight");

  }

  @Test
  @DisplayName("A grace of zero closes without waiting at all")
  public void aGraceOfZeroDoesNotWait() {

    drain.jobStarted(4711L, "task", "someTask", "TestProcess");

    // waiting means asking the workers again on every poll, so the number of questions
    // is what says whether anything waited. A clock would say it worse: a machine
    // carrying several builds stops this JVM for seconds at a time, and a stopped JVM
    // looks exactly like a drain which waited
    final var workersAsked = new AtomicInteger();
    assertFalse(drain.awaitQuiet(Duration.ZERO, 1, () -> {
      workersAsked.incrementAndGet();
      return true;
    }).isQuiet());
    assertEquals(1, workersAsked.get(), "the workers are asked exactly once");

  }

  @Test
  @DisplayName("Outside a shutdown a failure stays the application's failure")
  public void aFailureOutsideAShutdownIsReported() {

    assertFalse(drain.isShuttingDown());
    assertFalse(
        drain.leaveJobToItsLock(4711L, "task", "someTask", new IllegalStateException("boom")),
        "the caller has to fail the job");

  }

  @Test
  @DisplayName("While shutting down a failure leaves the job to its lock")
  public void aFailureDuringTheShutdownIsTheShutdown(
      final CapturedOutput output) {

    drain.beginShutdown();

    assertTrue(drain.isShuttingDown());
    assertTrue(
        drain.leaveJobToItsLock(4711L, "task", "someTask", new InterruptedException()),
        "the caller has to leave the job alone");
    assertTrue(
        drain.leaveJobToItsLock(4712L, "task", "someTask", null),
        "a failure without an exception is treated the same way");

    final var logged = output.getOut() + output.getErr();
    assertTrue(logged.contains("4711"), "the job key is named, so the operator can find it: "
        + logged);
    assertTrue(logged.contains("someTask"), "and the task with it: "
        + logged);
    assertTrue(logged.contains("retries intact"), "and what happens to it: "
        + logged);

  }

  @Test
  @DisplayName("What is still running when the grace passed is reported once per job")
  public void whatWasCutOffIsNamed(
      final CapturedOutput output) {

    drain.jobStarted(4711L, "task", "someTask", "TestProcess");
    drain.jobStarted(4712L, "task", "anotherTask", "TestProcess");

    drain.reportCutOff(Duration.ofSeconds(20));

    final var logged = output.getOut() + output.getErr();
    assertTrue(logged.contains("4711") && logged.contains("4712"), "every job is named: "
        + logged);
    assertTrue(logged.contains("someTask") && logged.contains("anotherTask"), "with its task: "
        + logged);
    assertTrue(
        logged.contains("vanillabp.adapters.c8.shutdown-grace"),
        "and the property to raise where a handler legitimately runs that long: "
            + logged);
    assertTrue(
        logged.contains("terminationGracePeriodSeconds"),
        "together with what else has to be raised then: "
            + logged);

    assertEquals(2, drain.getInFlight().size(), "reporting does not change what is in flight");
    assertTrue(
        drain
            .getInFlight()
            .stream()
            .allMatch(job -> job.since() != null),
        "every entry knows since when it runs");

  }

  @Test
  @DisplayName("A quiet module says how many workers were closed, and nothing which is not known")
  public void theLineOfAQuietModuleSaysWhatWasDone(
      final CapturedOutput output) {

    final var outcome = drain.awaitQuiet(Duration.ofSeconds(20), 3, () -> true);
    drain.report(Duration.ofSeconds(20), outcome);

    final var logged = output.getOut() + output.getErr();
    assertTrue(logged.contains("3 workers closed"), "the count is this adapter's own action: "
        + logged);
    assertTrue(
        logged.contains("no activation request of theirs left at the cluster"),
        "and the cluster released them, which is what makes closing the client safe: "
            + logged);
    assertFalse(
        logged.contains("workers closed: false"),
        "a line claiming the workers were not closed cannot be written any more: "
            + logged);

  }

  @Test
  @DisplayName("A worker still holding an activation request is a warning naming the consequence")
  public void aWorkerWhichIsNeverReleasedIsWarnedAbout(
      final CapturedOutput output) {

    final var outcome = drain.awaitQuiet(Duration.ofMillis(100), 2, () -> false);
    drain.report(Duration.ofMillis(100), outcome);

    final var logged = output.getOut() + output.getErr();
    assertFalse(
        outcome.isQuiet(),
        "the module is not quiet, so the client must not be closed silently behind it");
    assertTrue(
        logged.contains("still holding an activation request at the cluster"),
        "the operator is told what is left: "
            + logged);
    assertTrue(
        logged.contains("vanillabp.adapters.c8.job-timeout"),
        "and how long the job of the next application waits for it: "
            + logged);
    assertTrue(
        logged.contains("vanillabp.adapters.c8.shutdown-grace"),
        "and which budget to raise: "
            + logged);

  }

  @Test
  @DisplayName("A grace of zero reports the same state without warning about it")
  public void aGraceOfZeroIsADecisionAndNotAWarning() {

    final var events = eventsOf(
        () -> drain.report(Duration.ZERO, drain.awaitQuiet(Duration.ZERO, 1, () -> false)));

    assertEquals(1, events.size(), events::toString);
    assertTrue(
        events.getFirst().getFormattedMessage().contains("still holding an activation request"),
        "the consequence is still said: "
            + events);
    assertEquals(
        ch.qos.logback.classic.Level.INFO,
        events.getFirst().getLevel(),
        "but a shutdown which was configured to wait for nothing is not warned about on every restart");

  }

  /**
   * What the drain logged while the action ran (this module configures no appender, so
   * the events are collected rather than read off the console).
   */
  private List<ch.qos.logback.classic.spi.ILoggingEvent> eventsOf(
      final Runnable action) {

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var drainLog = (ch.qos.logback.classic.Logger) LoggerFactory
        .getLogger(Camunda8Drain.class);
    drainLog.addAppender(logWatcher);
    try {
      action.run();
    } finally {
      drainLog.detachAndStopAllAppenders();
    }
    return List.copyOf(logWatcher.list);

  }

}
