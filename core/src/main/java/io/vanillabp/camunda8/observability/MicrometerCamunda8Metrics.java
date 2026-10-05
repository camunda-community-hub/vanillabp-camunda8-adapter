package io.vanillabp.camunda8.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

import io.camunda.client.api.worker.JobWorkerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Publishes what {@link Camunda8Metrics} measures as Micrometer meters. Both platforms
 * apply {@link MeterBinder} beans to their registries themselves, so this one class
 * serves both, exactly like the platform's own meters do.
 * <p>
 * The client ships a Micrometer implementation of {@code JobWorkerMetrics} itself. It is
 * deliberately not used: its meter names and tags are Camunda's
 * ({@code camunda.job.invocations} and friends), and an application should not have to
 * learn two naming schemes to read one dashboard. What is used is the client's HOOK,
 * which is the part that matters - the counters below are incremented by the client, not
 * by this adapter.
 * <p>
 * Before {@link #bindTo(MeterRegistry)} was called there is no registry and nothing is
 * recorded. That is the normal state while beans are being built, and it is why the
 * worker metrics resolve their counters lazily instead of at worker-creation time.
 */
public class MicrometerCamunda8Metrics implements Camunda8Metrics, MeterBinder {

  /**
   * Opens the binder without a registry. Micrometer hands the registry over later, so nothing
   * is counted until it does.
   */
  public MicrometerCamunda8Metrics() {
  }

  private volatile MeterRegistry registry;

  private final Map<String, Counter> counters = new ConcurrentHashMap<>();

  /**
   * The execution slots registered per adapter instance, kept because an adapter builds
   * its client before the registry exists.
   */
  private final Map<String, ExecutionSlots> executionSlots = new LinkedHashMap<>();

  private record ExecutionSlots(
                                IntSupplier configured,
                                IntSupplier inUse,
                                IntSupplier waiting) {
  }

  /**
   * What the handlers of one adapter instance do to their slots, kept for the same reason
   * the slots are.
   */
  private final Map<String, RunningExecutions> runningExecutions = new LinkedHashMap<>();

  private record RunningExecutions(
                                   DoubleSupplier oldestSeconds,
                                   IntSupplier overdue) {
  }

  @Override
  public void bindTo(
      final MeterRegistry meterRegistry) {

    this.registry = meterRegistry;
    // the cached meters belong to the registry they were created in
    counters.clear();
    synchronized (executionSlots) {
      executionSlots.forEach((
          adapterId,
          slots) -> registerSlotGauges(meterRegistry, adapterId, slots));
    }
    synchronized (runningExecutions) {
      runningExecutions.forEach((
          adapterId,
          running) -> registerRunningExecutionGauges(meterRegistry, adapterId, running));
    }

  }

  @Override
  public JobWorkerMetrics workerMetrics(
      final String adapterId,
      final String jobType) {

    final var tags = Tags.of(TAG_ADAPTER, adapterId, TAG_JOB_TYPE, jobType);
    return new JobWorkerMetrics() {

      @Override
      public void jobActivated(
          final int count) {

        count(JOBS_ACTIVATED, "Jobs this worker activated from the cluster", tags, count);

      }

      @Override
      public void jobHandled(
          final int count) {

        count(JOBS_HANDLED, "Jobs this worker handed to a handler", tags, count);

      }

    };

  }

  @Override
  public void registerExecutionSlots(
      final String adapterId,
      final IntSupplier configured,
      final IntSupplier inUse,
      final IntSupplier waiting) {

    final var slots = new ExecutionSlots(configured, inUse, waiting);
    synchronized (executionSlots) {
      executionSlots.put(adapterId, slots);
    }
    final var meterRegistry = registry;
    if (meterRegistry != null) {
      registerSlotGauges(meterRegistry, adapterId, slots);
    }

  }

  @Override
  public void registerRunningExecutions(
      final String adapterId,
      final DoubleSupplier oldestSeconds,
      final IntSupplier overdue) {

    final var running = new RunningExecutions(oldestSeconds, overdue);
    synchronized (runningExecutions) {
      runningExecutions.put(adapterId, running);
    }
    final var meterRegistry = registry;
    if (meterRegistry != null) {
      registerRunningExecutionGauges(meterRegistry, adapterId, running);
    }

  }

  private static void registerRunningExecutionGauges(
      final MeterRegistry meterRegistry,
      final String adapterId,
      final RunningExecutions running) {

    final var tags = Tags.of(TAG_ADAPTER, adapterId);
    if (running.oldestSeconds() != null) {
      Gauge
          .builder(EXECUTION_OLDEST_SECONDS, running.oldestSeconds(), DoubleSupplier::getAsDouble)
          .tags(tags)
          .description("How long the oldest running handler of this adapter has been running")
          .baseUnit("seconds")
          .register(meterRegistry);
    }
    gauge(
        meterRegistry,
        EXECUTION_OVERDUE,
        "How many handlers ran longer than the job timeout of their task and lost the lock of their job",
        tags,
        running.overdue());

  }

  private static void registerSlotGauges(
      final MeterRegistry meterRegistry,
      final String adapterId,
      final ExecutionSlots slots) {

    final var tags = Tags.of(TAG_ADAPTER, adapterId);
    gauge(
        meterRegistry,
        EXECUTION_SLOTS_CONFIGURED,
        "How many handlers this adapter may run at the same time",
        tags,
        slots.configured());
    // absent rather than guessed in the platform-thread mode, where the client owns its
    // pool and does not report what it does with it
    gauge(
        meterRegistry,
        EXECUTION_SLOTS_IN_USE,
        "How many execution slots are busy right now",
        tags,
        slots.inUse());
    gauge(
        meterRegistry,
        JOBS_WAITING,
        "How many activated jobs wait for a free execution slot",
        tags,
        slots.waiting());

  }

  private static void gauge(
      final MeterRegistry meterRegistry,
      final String name,
      final String description,
      final Tags tags,
      final IntSupplier value) {

    if (value == null) {
      return;
    }
    Gauge
        .builder(name, value, IntSupplier::getAsInt)
        .tags(tags)
        .description(description)
        .register(meterRegistry);

  }

  private void count(
      final String name,
      final String description,
      final Tags tags,
      final int count) {

    final var meterRegistry = registry;
    if (meterRegistry == null) {
      return;
    }
    counters
        .computeIfAbsent(
            name
                + "|"
                + tags,
            key -> Counter
                .builder(name)
                .tags(tags)
                .description(description)
                .register(meterRegistry))
        .increment(count);

  }

}
