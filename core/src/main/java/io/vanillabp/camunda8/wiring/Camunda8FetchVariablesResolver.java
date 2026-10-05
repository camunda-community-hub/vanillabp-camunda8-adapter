package io.vanillabp.camunda8.wiring;

/**
 * Resolves whether a worker asks the cluster for the DERIVED variables or for all of
 * them - implemented by the platform modules on top of the adapter's configuration
 * overlay with most-specific-wins semantics across the four levels (task &gt; workflow
 * &gt; workflow-module &gt; adapter):
 *
 * <pre>
 * vanillabp.adapters.&lt;id&gt;.fetch-variables
 * vanillabp.workflow-modules.&lt;m&gt;.adapters.&lt;id&gt;.fetch-variables
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.adapters.&lt;id&gt;.fetch-variables
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.tasks.&lt;taskDefinition&gt;.adapters.&lt;id&gt;.fetch-variables
 * </pre>
 *
 * The task level is the point of the escape hatch: the case which needs everything is
 * one task reading one process variable, not an installation.
 * <p>
 * A worker serves several tasks, and the two values do not average. Where any of them
 * says <code>all</code>, the worker asks for everything - fetching more than derived is
 * never wrong, only more expensive, so this needs no guiding failure the way two
 * conflicting job timeouts of one worker do.
 */
@FunctionalInterface
public interface Camunda8FetchVariablesResolver {

  /**
   * What applies where no level configures anything: the derived list.
   */
  Camunda8FetchVariables.Mode DEFAULT_FETCH_VARIABLES = Camunda8FetchVariables.Mode.DERIVED;

  /**
   * Which variables the worker of a task asks the cluster for.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinition The task definition (job type), or <code>null</code> for the
   *          workers which serve no task
   * @return The most specific configured mode or {@link #DEFAULT_FETCH_VARIABLES}
   */
  Camunda8FetchVariables.Mode fetchVariablesFor(
      String workflowModuleId,
      String bpmnProcessId,
      String taskDefinition);

  /**
   * Asks a resolver which may not be there - the deployment service is built without one
   * in tests, and a resolver is free to answer nothing.
   *
   * @param resolver The resolver or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinition The task definition, or <code>null</code>
   * @return The resolved mode, never <code>null</code>
   */
  static Camunda8FetchVariables.Mode resolve(
      final Camunda8FetchVariablesResolver resolver,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition) {

    if (resolver == null) {
      return DEFAULT_FETCH_VARIABLES;
    }
    final var resolved = resolver.fetchVariablesFor(workflowModuleId, bpmnProcessId, taskDefinition);
    return resolved == null
        ? DEFAULT_FETCH_VARIABLES
        : resolved;

  }

}
