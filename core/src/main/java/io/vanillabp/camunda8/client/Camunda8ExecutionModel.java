package io.vanillabp.camunda8.client;

/**
 * How one Camunda 8 adapter instance runs the code it delivers: the resolved value of
 * <code>vanillabp.adapters.&lt;id&gt;.worker-threads</code> together with the number of
 * handlers which may run at the same time.
 * <p>
 * The Camunda client owns ONE executor per client, and this adapter owns one client per
 * adapter id, so the number is not "how parallel may one task be" but "how much of
 * everything this adapter delivers may be in flight at once". Every worker of the adapter
 * shares it: the workers of the task definitions, of the user-task lifecycle listeners, of
 * the start events the cluster fires itself and of the processes whose end is reported -
 * across every workflow module that adapter serves.
 * <p>
 * <b>The number counts handlers, not threads shared with the timing.</b> Whichever model is
 * configured, the adapter hands the client an executor of its own
 * ({@link Camunda8Executor}) which schedules the polls on threads no handler can occupy. So
 * the number below is what it says: how many handler invocations may be inside the
 * application at the same time.
 * <p>
 * <b>Why the default is four platform threads.</b> More than one, because one was the defect
 * this model exists for: nothing passed the number through, and the client's own default of
 * one thread ran the handler invocations AND the poll scheduling of every worker on the 8.8
 * line, so a blocking handler stopped the adapter from asking the cluster for work at all
 * (measured: an unrelated job of another worker waited 8013 ms, and 13 ms with four
 * threads). Small, because every concurrent handler holds a database connection inside
 * VanillaBP's transaction while it runs, and the usual pools are ten (Hikari) to twenty
 * (Agroal) connections wide, so four leaves room for the rest of the application. The number
 * to size against is therefore the connection pool, not the CPU.
 * <p>
 * <b>The virtual mode.</b> <code>worker-threads: virtual</code> runs each handler on a
 * virtual thread of its own ({@link Camunda8VirtualThreadExecutor}) instead of on a pool of
 * platform threads. Virtual threads are unbounded by nature, and the client's own limit is
 * per worker (with N workers the ceiling would be N &times; <code>max-jobs-active</code>
 * concurrent transactions), so that model is bounded by a semaphore sized by
 * <code>worker-threads-bound</code>. Its default is the number the platform mode would use,
 * so switching the mode changes how threads are made, not how much runs at once.
 *
 * <p>
 * Why this adapter picks its own execution model instead of inheriting the client's single thread,
 * and why the default is four, is decision 7 in the repository's DECISIONS.md; why the adapter
 * supplies the executor on every release line is decision 18.
 *
 * @param virtual Whether handlers run on virtual threads
 * @param slots How many handlers may run at the same time (platform threads, or the bound of
 *          the virtual-thread executor)
 */
public record Camunda8ExecutionModel(
                                     boolean virtual,
                                     int slots) {

  /**
   * The literal accepted by <code>worker-threads</code> besides a positive number.
   */
  public static final String VIRTUAL = "virtual";

  /**
   * The number of execution slots an adapter has where nothing is configured - see the
   * class javadoc for the reasoning.
   */
  public static final int DEFAULT_SLOTS = 4;

  /**
   * The default execution model: {@value #DEFAULT_SLOTS} platform threads.
   */
  public static final Camunda8ExecutionModel DEFAULT = new Camunda8ExecutionModel(false, DEFAULT_SLOTS);

  /**
   * Resolves the execution model of one adapter instance.
   *
   * @param adapterId The adapter id (used to build property keys in messages)
   * @param workerThreads The configured <code>worker-threads</code> value, or
   *          <code>null</code>
   * @param workerThreadsBound The configured <code>worker-threads-bound</code> value, or
   *          <code>null</code>
   * @return The resolved model
   * @throws IllegalStateException If a value is not usable, naming the property and the
   *           accepted forms
   */
  public static Camunda8ExecutionModel resolve(
      final String adapterId,
      final String workerThreads,
      final Integer workerThreadsBound) {

    final var configured = (workerThreads == null) || workerThreads.isBlank()
        ? null
        : workerThreads.trim();
    final var isVirtual = VIRTUAL.equalsIgnoreCase(configured);

    if (!isVirtual && (workerThreadsBound != null)) {
      throw new IllegalStateException(
          """
              Camunda 8 adapter '%s' configures '%s' but its '%s' is '%s'. The bound sizes the \
              virtual-thread executor and is ignored by the platform-thread mode, where the number \
              of threads IS the bound. Either set '%s: virtual' or remove the bound."""
              .formatted(
                  adapterId,
                  Camunda8AdapterConfiguration.propertyKey(adapterId, "worker-threads-bound"),
                  Camunda8AdapterConfiguration.propertyKey(adapterId, "worker-threads"),
                  configured == null ? String.valueOf(DEFAULT_SLOTS) : configured,
                  Camunda8AdapterConfiguration.propertyKey(adapterId, "worker-threads")));
    }

    if (isVirtual) {
      final var bound = workerThreadsBound == null
          ? DEFAULT_SLOTS
          : workerThreadsBound;
      if (bound < 1) {
        throw new IllegalStateException(
            """
                Camunda 8 adapter '%s' has '%s: %d'. The bound is the number of handlers which may \
                run at the same time and has to be at least 1. Size it against the database \
                connection pool of the application, since every running handler holds a connection \
                (the default is %d)."""
                .formatted(
                    adapterId,
                    Camunda8AdapterConfiguration.propertyKey(adapterId, "worker-threads-bound"),
                    bound,
                    DEFAULT_SLOTS));
      }
      return new Camunda8ExecutionModel(true, bound);
    }

    if (configured == null) {
      return DEFAULT;
    }

    final int threads;
    try {
      threads = Integer.parseInt(configured);
    } catch (final NumberFormatException e) {
      throw new IllegalStateException(
          """
              Camunda 8 adapter '%s' has '%s: %s', which is neither a number nor the literal \
              'virtual'. Two forms are accepted:
                %s: 4         # this many platform threads run the handlers (the default)
                %s: virtual   # a virtual thread per handler, bounded by 'worker-threads-bound'"""
              .formatted(
                  adapterId,
                  Camunda8AdapterConfiguration.propertyKey(adapterId, "worker-threads"),
                  configured,
                  Camunda8AdapterConfiguration.propertyKey(adapterId, "worker-threads"),
                  Camunda8AdapterConfiguration.propertyKey(adapterId, "worker-threads")));
    }
    if (threads < 1) {
      throw new IllegalStateException(
          """
              Camunda 8 adapter '%s' has '%s: %d'. An adapter without an execution thread delivers \
              nothing, and one thread means that every worker of this adapter waits for the one \
              handler which is running, which is why the default is %d. Size it against the \
              database connection pool of the application, since every running handler holds a \
              connection."""
              .formatted(
                  adapterId,
                  Camunda8AdapterConfiguration.propertyKey(adapterId, "worker-threads"),
                  threads,
                  DEFAULT_SLOTS));
    }
    return new Camunda8ExecutionModel(false, threads);

  }

  /**
   * How the model is named in the startup line and in messages.
   *
   * @return A short description, e.g. <code>4 platform threads</code>
   */
  public String describe() {

    return virtual
        ? "virtual threads, bounded at %d".formatted(slots)
        : "%d platform threads".formatted(slots);

  }

}
