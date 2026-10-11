package io.vanillabp.camunda8.wiring;

/**
 * Resolves whether this application honours the element-template marker of a model. The
 * platform modules implement it on top of the configuration overlay of the adapter. The most
 * specific value wins, across THREE of the four levels (workflow &gt; workflow-module &gt;
 * adapter):
 *
 * <pre>
 * vanillabp.adapters.&lt;id&gt;.allow-connectors
 * vanillabp.workflow-modules.&lt;m&gt;.adapters.&lt;id&gt;.allow-connectors
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.adapters.&lt;id&gt;.allow-connectors
 * </pre>
 *
 * <h2>Why there is no task level</h2>
 *
 * That level is keyed by the task DEFINITION. The task definition of a connector is the
 * type of the connector itself. Every element using that connector shares it. And it carries
 * dots and colons, which a relaxed binder splits on. So the key would not address one
 * element, and it would not survive being written. The decision per element is in the model
 * instead, in {@code zeebe:modelerTemplate}. A value set at task level anyway gets one
 * guiding warning, which names the three levels which work.
 *
 * <h2>Why more specific may switch it OFF</h2>
 *
 * VanillaBP 1 held the flag in primitive booleans. So a more specific level could only turn
 * it ON: a global {@code true} plus a module {@code false} still gave {@code true}. Here the
 * most specific configured value wins in both directions. Every other scope-specific key of
 * this adapter does the same.
 *
 * <h2>Why the signature carries no task definition</h2>
 *
 * {@code Camunda8Scoping#apply} asks this resolver while it walks the task definitions of a
 * FILE, before the process ids are rewritten. It finds the owning process of each of them
 * itself. The other call site is <code>Camunda8DeploymentService</code>. So both call sites
 * can supply the workflow module and the plain process id. Neither of them has a task
 * definition to offer.
 */
@FunctionalInterface
public interface Camunda8AllowConnectorsResolver {

  /**
   * What applies where no level configures anything: VanillaBP wires every element of the
   * model. That is what an application without a connector wants.
   */
  boolean DEFAULT_ALLOW_CONNECTORS = false;

  /**
   * The answer together with the key it came from. The startup report has to name the line
   * a reader can find in their own configuration.
   *
   * @param allowed Whether elements built from an element template are left to the runtime
   *          which owns them
   * @param propertyKey The fully spelled key which decided it, or <code>null</code> where
   *          nothing is configured and the default applies
   */
  record Setting(
                 boolean allowed,
                 String propertyKey) {

    /**
     * The answer of an application which configures nothing.
     */
    public static final Setting NOTHING_CONFIGURED = new Setting(DEFAULT_ALLOW_CONNECTORS, null);

  }

  /**
   * Whether this BPMN process may carry elements another runtime serves, and which property key
   * said so.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The most specific configured setting, never <code>null</code>
   */
  Setting allowConnectorsFor(
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
      final Camunda8AllowConnectorsResolver resolver,
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (resolver == null) {
      return Setting.NOTHING_CONFIGURED;
    }
    final var resolved = resolver.allowConnectorsFor(workflowModuleId, bpmnProcessId);
    return resolved == null
        ? Setting.NOTHING_CONFIGURED
        : resolved;

  }

}
