package io.vanillabp.camunda8.client;

import io.camunda.client.api.worker.JobWorker;
import io.camunda.client.api.worker.JobWorkerBuilderStep1;
import io.vanillabp.camunda8.observability.Camunda8Metrics;

/**
 * How a job worker of one Camunda 8 adapter id is set up, wherever it is opened.
 * <p>
 * Almost everything a worker of this adapter uses is set on the CLIENT while the client is
 * built - <code>max-jobs-active</code>, <code>poll-interval</code>,
 * <code>request-timeout</code>, <code>stream-enabled</code> - so a worker inherits it and an
 * environment variable can still overrule it and be reported for it. Two things cannot be
 * set there, and those are what this class adds: the stream timeout, which the client has no
 * equivalent for, and the job counters, which only exist per worker because they carry the
 * job type.
 * <p>
 * Public because an EXTENSION opens workers on the same cluster. A worker it assembled by
 * hand is missing from what an operator reads: the counters are what an operator looks at to
 * see how much work is queued in front of the execution slots, and a worker which does not
 * report them is not a quieter worker, it is an invisible one. Calling this is how a
 * worker of an extension looks like a worker of the adapter.
 * <p>
 * The same goes for {@link #open}, which is where a worker of this adapter id is counted:
 * the workers share the connection pool of one client, so a worker opened past this class
 * is a connection nobody counts.
 */
public final class Camunda8Workers {

  private Camunda8Workers() {
  }

  /**
   * Applies the options every worker of an adapter id shares.
   * <p>
   * It only sets what the client does not carry, so it may be called on a builder the
   * caller has already configured and the caller keeps configuring the returned builder -
   * which is the same object.
   *
   * @param builder The worker builder, with its job type and its handler already named
   * @param adapterId The adapter id whose worker this is - the counters are reported under
   *          it, so an operator reads which of two configured clusters is busy
   * @param jobType The job type the worker subscribes to, which the counters carry
   * @param configuration The configuration of that adapter id, as the adapter resolved it
   *          ({@code Camunda8ClientFactory#getConfiguration()})
   * @param metrics Where the counters go, or {@link Camunda8Metrics#NONE} where the
   *          application brought no Micrometer
   * @return The same builder
   */
  public static JobWorkerBuilderStep1.JobWorkerBuilderStep3 applyWorkerOptions(
      final JobWorkerBuilderStep1.JobWorkerBuilderStep3 builder,
      final String adapterId,
      final String jobType,
      final Camunda8AdapterConfiguration configuration,
      final Camunda8Metrics metrics) {

    // the client's own counters: it activates and hands over jobs long before
    // the core sees a delivery, so this is where the queue in front of the execution
    // slots becomes visible
    final var withMetrics = builder.metrics(metrics.workerMetrics(adapterId, jobType));
    final var streamTimeout = configuration.getStreamTimeout();
    return streamTimeout == null
        ? withMetrics
        : withMetrics.streamTimeout(streamTimeout);

  }

  /**
   * Opens this worker with a lease on every activation, where the application asked for one
   * and the release line has one.
   * <p>
   * It is NOT part of {@link #applyWorkerOptions} on purpose: a worker which can ever serve
   * an asynchronous task must not lease, because such a task is completed in phase two by a
   * dispatcher holding no token, and only the caller knows what its worker serves. The
   * adapter asks this for the workers which hold their job from the activation to the
   * answer.
   * <p>
   * An EXTENSION which opens listener workers of its own calls it for the same reason it
   * calls {@link #applyWorkerOptions}: two components leasing the same job type with
   * different opinions is the starvation the ratchet describes, and the decision is the
   * adapter's configuration rather than the extension's. Why there is no default, and what
   * a lease costs a rollback, is decision 36 in the repository's DECISIONS.md.
   *
   * @param builder The worker builder
   * @param configuration The configuration of that adapter id, as the adapter resolved it
   *          ({@code Camunda8ClientFactory#getConfiguration()})
   * @return The same builder
   */
  public static JobWorkerBuilderStep1.JobWorkerBuilderStep3 leaseTheActivations(
      final JobWorkerBuilderStep1.JobWorkerBuilderStep3 builder,
      final Camunda8AdapterConfiguration configuration) {

    return configuration.leasesItsJobs()
        ? Camunda8JobLease.leaseTheActivations(builder)
        : builder;

  }

  /**
   * Opens the worker this builder describes and counts it among the workers of its adapter
   * id.
   * <p>
   * Counting happens where a worker is OPENED and not where it is ordered, so every worker
   * on this client is in the number no matter who wanted it. The adapter opens its own
   * workers through here, and an EXTENSION which opens one on the same client calls it for
   * the same reason it calls {@link #applyWorkerOptions}: the workers share one connection
   * pool, and a worker missing from the count is a connection missing from the sum the
   * adapter holds against that pool (see {@link Camunda8WorkerConnections}). Where the
   * extension opens its worker after the start is over, this is also what makes the check
   * run a second time.
   * <p>
   * The factory to pass is the one whose client this worker polls with. An extension which
   * brings a CLIENT OF ITS OWN has a pool of its own as well: its workers are none of this
   * adapter's business and it opens them without coming through here.
   *
   * @param builder The worker builder, ready to open
   * @param clientFactory The factory of the adapter id whose client this worker polls with
   * @return The open worker, which the caller closes when it is done with it
   */
  public static JobWorker open(
      final JobWorkerBuilderStep1.JobWorkerBuilderStep3 builder,
      final Camunda8ClientFactory clientFactory) {

    final var worker = builder.open();
    clientFactory.aWorkerWasOpened(worker);
    return worker;

  }

}
