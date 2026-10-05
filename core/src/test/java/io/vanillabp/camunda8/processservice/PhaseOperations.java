package io.vanillabp.camunda8.processservice;

import java.util.LinkedHashMap;
import java.util.Map;

import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;

/**
 * Runs one phase of one operation against the adapter, the way VanillaBP's core does:
 * through the handler the adapter contributes for that operation.
 * <p>
 * A unit test has no outbox to dispatch through, so it asks the adapter directly - and
 * this is what asking directly looks like now that an adapter answers a map of handlers
 * instead of a method per operation and phase.
 */
public final class PhaseOperations {

  private PhaseOperations() {
  }

  /**
   * @param <A> The workflow-aggregate type
   * @param adapter The adapter to ask
   * @param operation The operation to run
   * @param workflowModuleId The workflow module the call belongs to
   * @param bpmnProcessId The BPMN process the call belongs to
   * @param aggregatePersistence The aggregate's persistence, or <code>null</code>
   * @param workflowAggregate The workflow aggregate
   * @param args The operation's arguments
   */
  public static <A> void phaseOne(
      final MigratableProcessService<A> adapter,
      final PhaseOperation operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final Map<String, String> args) {

    adapter
        .phaseOperations()
        .get(operation)
        .phaseOne(
            new PhaseOneRequest<>(
                workflowModuleId, bpmnProcessId, aggregatePersistence, workflowAggregate, args));

  }

  /**
   * @param <A> The workflow-aggregate type
   * @param adapter The adapter to ask
   * @param operation The operation to run
   * @param workflowModuleId The workflow module the call belongs to
   * @param bpmnProcessId The BPMN process the call belongs to
   * @param aggregatePersistence The aggregate's persistence, or <code>null</code>
   * @param workflowAggregateId The workflow aggregate's ID
   * @param args The operation's arguments
   */
  public static <A> void phaseTwo(
      final MigratableProcessService<A> adapter,
      final PhaseOperation operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final Map<String, String> args) {

    adapter
        .phaseOperations()
        .get(operation)
        .phaseTwo(
            new PhaseTwoRequest<>(
                workflowModuleId, bpmnProcessId, aggregatePersistence, workflowAggregateId, args));

  }

  /**
   * Builds an argument map from key/value pairs, leaving out the pairs whose value is
   * <code>null</code> - which is how an optional argument such as a correlation id
   * reaches an operation: absent rather than null.
   *
   * @param keysAndValues Key, value, key, value, ...
   * @return The arguments
   */
  public static Map<String, String> args(
      final String... keysAndValues) {

    final var args = new LinkedHashMap<String, String>();
    for (var i = 0; i < keysAndValues.length; i += 2) {
      if (keysAndValues[i + 1] != null) {
        args.put(keysAndValues[i], keysAndValues[i + 1]);
      }
    }
    return args;

  }

}
