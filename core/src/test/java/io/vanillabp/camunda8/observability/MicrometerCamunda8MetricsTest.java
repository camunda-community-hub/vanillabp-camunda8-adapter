package io.vanillabp.camunda8.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter adds to the meters the platform publishes for every BPMS.
 * The client increments the job counters itself through the hook it is handed, so what
 * is pinned here is the naming, the tagging and the two states a registry can be in.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MicrometerCamunda8MetricsTest {

  @Test
  @DisplayName("Nothing is recorded before a registry was bound")
  public void recordsAreDroppedWithoutARegistry() {

    final var metrics = new MicrometerCamunda8Metrics();
    final var worker = metrics.workerMetrics("c8", "approve");

    assertDoesNotThrow(
        () -> {
          worker.jobActivated(5);
          worker.jobHandled(5);
        },
        "workers are opened while the application starts, long before the registry binds");

  }

  @Test
  @DisplayName("The client's job counters are tagged by adapter and job type")
  public void jobCountersCarryAdapterAndJobType() {

    final var registry = new SimpleMeterRegistry();
    final var metrics = new MicrometerCamunda8Metrics();
    metrics.bindTo(registry);

    final var approve = metrics.workerMetrics("c8", "approve");
    final var notify = metrics.workerMetrics("c8", "notify");
    approve.jobActivated(7);
    approve.jobHandled(4);
    notify.jobActivated(2);

    assertEquals(
        7.0,
        registry
            .get(Camunda8Metrics.JOBS_ACTIVATED)
            .tag(Camunda8Metrics.TAG_ADAPTER, "c8")
            .tag(Camunda8Metrics.TAG_JOB_TYPE, "approve")
            .counter()
            .count());
    assertEquals(
        4.0,
        registry
            .get(Camunda8Metrics.JOBS_HANDLED)
            .tag(Camunda8Metrics.TAG_JOB_TYPE, "approve")
            .counter()
            .count(),
        "the gap between activated and handled is the queue in front of the execution slots");
    assertEquals(
        2.0,
        registry
            .get(Camunda8Metrics.JOBS_ACTIVATED)
            .tag(Camunda8Metrics.TAG_JOB_TYPE, "notify")
            .counter()
            .count(),
        "one worker's numbers do not land in another's");

  }

  @Test
  @DisplayName("The execution slots are gauges, registered before the registry exists")
  public void executionSlotsAreGauged() {

    final var inUse = new AtomicInteger(2);
    final var waiting = new AtomicInteger(0);
    final var metrics = new MicrometerCamunda8Metrics();

    metrics.registerExecutionSlots("c8", () -> 4, inUse::get, waiting::get);

    final var registry = new SimpleMeterRegistry();
    metrics.bindTo(registry);

    assertEquals(4.0, registry.get(Camunda8Metrics.EXECUTION_SLOTS_CONFIGURED).gauge().value());
    assertEquals(2.0, registry.get(Camunda8Metrics.EXECUTION_SLOTS_IN_USE).gauge().value());
    assertEquals(0.0, registry.get(Camunda8Metrics.JOBS_WAITING).gauge().value());

    inUse.set(4);
    waiting.set(9);
    assertEquals(
        4.0,
        registry.get(Camunda8Metrics.EXECUTION_SLOTS_IN_USE).gauge().value(),
        "every slot busy while jobs wait is the picture of a stalled application");
    assertEquals(9.0, registry.get(Camunda8Metrics.JOBS_WAITING).gauge().value());

  }

  @Test
  @DisplayName("The age of the oldest handler and the overdue ones are gauges too")
  public void whatTheHandlersDoToTheirSlotsIsGauged() {

    final var oldest = new AtomicInteger(3);
    final var overdue = new AtomicInteger(0);
    final var metrics = new MicrometerCamunda8Metrics();

    metrics.registerRunningExecutions("c8", oldest::get, overdue::get);

    final var registry = new SimpleMeterRegistry();
    metrics.bindTo(registry);

    assertEquals(3.0, registry.get(Camunda8Metrics.EXECUTION_OLDEST_SECONDS).gauge().value());
    assertEquals(0.0, registry.get(Camunda8Metrics.EXECUTION_OVERDUE).gauge().value());

    oldest.set(900);
    overdue.set(4);
    assertEquals(
        900.0,
        registry.get(Camunda8Metrics.EXECUTION_OLDEST_SECONDS).gauge().value(),
        "an age which only rises is a slot nobody gives back");
    assertEquals(
        4.0,
        registry.get(Camunda8Metrics.EXECUTION_OVERDUE).gauge().value(),
        "and every one of them lost the lock of its job");

  }

  @Test
  @DisplayName("An adapter which built no client reports the bound and guesses nothing else")
  public void withoutAnExecutorOnlyTheBoundIsReported() {

    final var registry = new SimpleMeterRegistry();
    final var metrics = new MicrometerCamunda8Metrics();
    metrics.bindTo(registry);

    metrics.registerExecutionSlots("c8", () -> 4, null, null);

    assertEquals(4.0, registry.get(Camunda8Metrics.EXECUTION_SLOTS_CONFIGURED).gauge().value());
    assertThrows(
        MeterNotFoundException.class,
        () -> registry.get(Camunda8Metrics.EXECUTION_SLOTS_IN_USE).gauge(),
        "an adapter configured without a connection has no executor to read them from");
    assertThrows(
        MeterNotFoundException.class,
        () -> registry.get(Camunda8Metrics.JOBS_WAITING).gauge());

    metrics.registerRunningExecutions("c8", null, null);

    assertThrows(
        MeterNotFoundException.class,
        () -> registry.get(Camunda8Metrics.EXECUTION_OLDEST_SECONDS).gauge(),
        "without a client there is nothing in flight to age either");
    assertThrows(
        MeterNotFoundException.class,
        () -> registry.get(Camunda8Metrics.EXECUTION_OVERDUE).gauge());

  }

  @Test
  @DisplayName("Without a metrics backend the worker gets the client's no-op hook")
  public void withoutMetricsTheWorkerGetsTheNoOpHook() {

    assertDoesNotThrow(
        () -> {
          final var worker = Camunda8Metrics.NONE.workerMetrics("c8", "approve");
          worker.jobActivated(3);
          worker.jobHandled(3);
          Camunda8Metrics.NONE.registerExecutionSlots("c8", () -> 4, () -> 0, () -> 0);
          Camunda8Metrics.NONE.registerRunningExecutions("c8", () -> 0d, () -> 0);
        });

  }

}
