package io.vanillabp.camunda8.observability;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

import io.camunda.client.api.worker.JobWorkerMetrics;

/**
 * What this adapter measures on top of what VanillaBP's core measures for every BPMS,
 * expressed without any metrics library. {@link #NONE} is what an application without
 * a metrics backend uses, and what every worker gets until a platform module hands in
 * something else.
 * <p>
 * Two things are worth having per Camunda 8 adapter instance, and both of them say
 * something no generic delivery counter can:
 * <ul>
 * <li>the client's own job counters, which sit BEFORE the execution slots: the
 * difference between activated and handled is the queue in front of the handlers;</li>
 * <li>the execution slots of the adapter: how many there are, how many are
 * busy and how many jobs wait for one. A cluster with work and an application with no
 * free slot is the situation probe P3 of the spike described, and this is where it
 * becomes visible.</li>
 * </ul>
 * The Micrometer implementation is {@link MicrometerCamunda8Metrics}. Micrometer is
 * optional: without it, nothing of it is loaded and the adapter runs unchanged.
 * <p>
 * The names follow the scheme the platform established: they start with
 * {@code vanillabp.}, and the adapter id is a TAG, never part of the name. The tag keys
 * are the platform's as well; they are repeated here as constants because the adapter's
 * core depends on the adapter SPI only, not on the platform's runtime.
 */
public interface Camunda8Metrics {

  /**
   * Measures nothing.
   */
  Camunda8Metrics NONE = new Camunda8Metrics() {
  };

  /**
   * Jobs the client activated, per worker. Together with {@link #JOBS_HANDLED} this is
   * the cheapest way to see the queue in front of the execution slots.
   */
  String JOBS_ACTIVATED = "vanillabp.camunda8.jobs.activated";

  /**
   * Jobs the client handed to a handler, per worker.
   */
  String JOBS_HANDLED = "vanillabp.camunda8.jobs.handled";

  /**
   * How many handlers this adapter instance may run at the same time
   * (<code>worker-threads</code>).
   */
  String EXECUTION_SLOTS_CONFIGURED = "vanillabp.camunda8.execution.slots.configured";

  /**
   * How many of those slots are busy right now. Sitting at the configured number while
   * work waits is the picture of a stalled application.
   */
  String EXECUTION_SLOTS_IN_USE = "vanillabp.camunda8.execution.slots.in.use";

  /**
   * How many activated jobs wait for a free execution slot.
   */
  String JOBS_WAITING = "vanillabp.camunda8.jobs.waiting";

  /**
   * How long the oldest handler of this adapter instance has been running, in seconds. It
   * rises without coming down where a handler never returns.
   */
  String EXECUTION_OLDEST_SECONDS = "vanillabp.camunda8.execution.oldest.seconds";

  /**
   * How many handlers have been running longer than the job timeout of their own task.
   * Each of them has lost the lock of the job it works on, and the slot it holds is a slot
   * no worker can ask for work with.
   */
  String EXECUTION_OVERDUE = "vanillabp.camunda8.execution.overdue";

  /**
   * The tag naming the adapter instance a measurement belongs to. Every meter here carries it,
   * because an application may run more than one Camunda 8 adapter at once.
   */
  String TAG_ADAPTER = "adapter";

  /**
   * The tag naming the job type a measurement belongs to. The job counters carry it, the
   * execution slots do not: the slots are shared by every worker of an adapter instance.
   */
  String TAG_JOB_TYPE = "job.type";

  /**
   * The metrics of one worker, handed to the client through
   * {@code JobWorkerBuilderStep3.metrics(...)}.
   *
   * @param adapterId The adapter instance owning the worker
   * @param jobType The job type the worker subscribes to
   * @return The client's metrics hook
   */
  default JobWorkerMetrics workerMetrics(
      final String adapterId,
      final String jobType) {

    return JobWorkerMetrics.noop();

  }

  /**
   * Registers where the execution slots of one adapter instance are read from. Called
   * once per adapter instance.
   *
   * @param adapterId The adapter instance
   * @param configured How many handlers may run at the same time
   * @param inUse How many of them are running right now
   * @param waiting How many activated jobs wait for a slot
   */
  default void registerExecutionSlots(
      final String adapterId,
      final IntSupplier configured,
      final IntSupplier inUse,
      final IntSupplier waiting) {

  }

  /**
   * Registers what the handlers of one adapter instance are doing to their slots: how old
   * the oldest running one is and how many of them lost the lock of their job. Together
   * with the slots in use this is what an operator alerts on, because all slots in use
   * next to an overdue handler is an adapter which has stopped asking the cluster for
   * work. Called once per adapter instance.
   *
   * @param adapterId The adapter instance
   * @param oldestSeconds How long the oldest running handler has been running
   * @param overdue How many handlers ran longer than the job timeout of their task
   */
  default void registerRunningExecutions(
      final String adapterId,
      final DoubleSupplier oldestSeconds,
      final IntSupplier overdue) {

  }

}
