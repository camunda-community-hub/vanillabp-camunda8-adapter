package io.vanillabp.camunda8.observability;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

import io.camunda.client.api.worker.JobWorkerMetrics;

/**
 * What this adapter measures on top of what the VanillaBP core measures for every BPMS.
 * It is written without any metrics library. An application without a metrics backend
 * uses {@link #NONE}. Every worker also gets it until a platform module hands in
 * something else.
 * <p>
 * Two things are worth having per Camunda 8 adapter instance. Both of them say something
 * no generic delivery counter can:
 * <ul>
 * <li>the job counters of the client itself, which sit BEFORE the execution slots. The
 * difference between activated and handled is the queue in front of the handlers.</li>
 * <li>the execution slots of the adapter: how many there are, how many are busy and how
 * many jobs wait for one. A cluster with work and an application with no free slot
 * looks like an idle application from the outside. Here it becomes visible.</li>
 * </ul>
 * The Micrometer implementation is {@link MicrometerCamunda8Metrics}. Micrometer is
 * optional. Without it, none of that is loaded and the adapter runs unchanged.
 * <p>
 * The names follow the scheme of the platform. They start with {@code vanillabp.}, and
 * the adapter id is a TAG, never part of the name. The tag keys come from the platform as
 * well. They are repeated here as constants, because the core of the adapter depends on
 * the adapter SPI only, not on the runtime of the platform.
 */
public interface Camunda8Metrics {

  /**
   * Measures nothing.
   */
  Camunda8Metrics NONE = new Camunda8Metrics() {
  };

  /**
   * Jobs the client activated, per worker. Together with {@link #JOBS_HANDLED}, it is
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
   * How many of those slots are busy right now. If it stays at the configured number
   * while work waits, the application is stalled.
   */
  String EXECUTION_SLOTS_IN_USE = "vanillabp.camunda8.execution.slots.in.use";

  /**
   * How many activated jobs wait for a free execution slot.
   */
  String JOBS_WAITING = "vanillabp.camunda8.jobs.waiting";

  /**
   * How long the oldest handler of this adapter instance has been running, in seconds. If
   * a handler never returns, it keeps rising and never comes down.
   */
  String EXECUTION_OLDEST_SECONDS = "vanillabp.camunda8.execution.oldest.seconds";

  /**
   * How many handlers have been running longer than the job timeout of their own task.
   * Each of them has lost the lock of the job it works on. And no worker can use the slot
   * it holds to ask for work.
   */
  String EXECUTION_OVERDUE = "vanillabp.camunda8.execution.overdue";

  /**
   * The tag naming the adapter instance a measurement belongs to. Every meter here carries it,
   * because an application may run more than one Camunda 8 adapter at once.
   */
  String TAG_ADAPTER = "adapter";

  /**
   * The tag naming the job type a measurement belongs to. The job counters carry it, the
   * execution slots do not. Every worker of an adapter instance shares the slots.
   */
  String TAG_JOB_TYPE = "job.type";

  /**
   * The metrics of one worker, handed to the client through
   * {@code JobWorkerBuilderStep3.metrics(...)}.
   *
   * @param adapterId The adapter instance owning the worker
   * @param jobType The job type the worker subscribes to
   * @return The metrics hook of the client
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
   * the oldest running one is and how many of them lost the lock of their job. An operator
   * alerts on this together with the slots in use. If all slots are in use and a handler
   * is overdue, the adapter has stopped asking the cluster for work. Called once per
   * adapter instance.
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
