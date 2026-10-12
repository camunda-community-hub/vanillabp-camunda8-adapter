package io.vanillabp.camunda8.wiring;

/**
 * Resolves whether this application serves the listeners somebody modelled. The platform
 * modules implement it on top of the configuration overlay of the adapter. The most specific
 * value wins, across THREE of the four levels (workflow &gt; workflow-module &gt;
 * adapter):
 *
 * <pre>
 * vanillabp.adapters.&lt;id&gt;.allow-listeners
 * vanillabp.workflow-modules.&lt;m&gt;.adapters.&lt;id&gt;.allow-listeners
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.adapters.&lt;id&gt;.allow-listeners
 * </pre>
 *
 * The levels are the ones {@link Camunda8AllowConnectorsResolver} is read at. The Camunda 7
 * adapter reads the very same three for the very same key. Two keys about what a model may
 * contain should not reach different levels on different adapters.
 *
 * <h2>Why there is no task level</h2>
 *
 * That level is keyed by a task DEFINITION. But this key decides whether a modelled
 * listener becomes a task at all. So at the moment the key is read, there is no task
 * definition to key a level by. A value set at task level anyway gets one guiding warning,
 * which names the three levels which work.
 *
 * <h2>Why more specific may switch it OFF</h2>
 *
 * The most specific configured value wins in both directions. Every other scope-specific
 * key of this adapter does the same. A workflow module which allows listeners can have one
 * workflow which does not.
 */
@FunctionalInterface
public interface Camunda8AllowListenersResolver {

  /**
   * What applies where no level configures anything: the listeners somebody modelled are
   * not served, and a model carrying one does not boot. VanillaBP 1 served them without
   * saying so. Serving them has a cost, so it is not the default here.
   */
  boolean DEFAULT_ALLOW_LISTENERS = false;

  /**
   * The answer together with the key it came from. The startup report has to name the line
   * a reader can find in their own configuration.
   *
   * @param allowed Whether the listeners of the model are served by
   *          <code>@WorkflowTask</code> methods
   * @param propertyKey The fully spelled key which decided it, or <code>null</code> where
   *          nothing is configured and the default applies
   */
  record Setting(
                 boolean allowed,
                 String propertyKey) {

    /**
     * The answer of an application which configures nothing.
     */
    public static final Setting NOTHING_CONFIGURED = new Setting(DEFAULT_ALLOW_LISTENERS, null);

  }

  /**
   * Whether this BPMN process may carry listeners nobody serves, and which property key said
   * so.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The most specific configured setting, never <code>null</code>
   */
  Setting allowListenersFor(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * Asks a resolver which may not be there. In tests the deployment service is built
   * without one. And a resolver is free to answer nothing.
   *
   * @param resolver The resolver or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The resolved setting, never <code>null</code>
   */
  static Setting resolve(
      final Camunda8AllowListenersResolver resolver,
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (resolver == null) {
      return Setting.NOTHING_CONFIGURED;
    }
    final var resolved = resolver.allowListenersFor(workflowModuleId, bpmnProcessId);
    return resolved == null
        ? Setting.NOTHING_CONFIGURED
        : resolved;

  }

}
