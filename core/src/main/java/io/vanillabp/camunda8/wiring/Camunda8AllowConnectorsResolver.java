package io.vanillabp.camunda8.wiring;

/**
 * Resolves whether this application honours the element-template marker of a model -
 * implemented by the platform modules on top of the adapter's configuration overlay with
 * most-specific-wins semantics across THREE of the four levels (workflow &gt;
 * workflow-module &gt; adapter):
 *
 * <pre>
 * vanillabp.adapters.&lt;id&gt;.allow-connectors
 * vanillabp.workflow-modules.&lt;m&gt;.adapters.&lt;id&gt;.allow-connectors
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.adapters.&lt;id&gt;.allow-connectors
 * </pre>
 *
 * <h2>Why there is no task level</h2>
 *
 * That level is keyed by the task DEFINITION, and the task definition of a connector is the
 * connector's own type: every element using that connector shares it, and it carries dots
 * and colons which a relaxed binder splits on. So the key would neither address one element
 * nor survive being written. The per-element decision is in the model instead, in
 * {@code zeebe:modelerTemplate}, and a value set at task level anyway earns one guiding
 * warning naming the three levels which work.
 *
 * <h2>Why more specific may switch it OFF</h2>
 *
 * VanillaBP 1 held the flag in primitive booleans, so a more specific level could only turn
 * it ON: a global {@code true} plus a module {@code false} still yielded {@code true}. Here
 * the most specific configured value wins in both directions, which is what every other
 * scope-specific key of this adapter does.
 *
 * <h2>Why the signature carries no task definition</h2>
 *
 * {@code Camunda8Scoping#apply} asks this resolver while it walks the task definitions of a
 * FILE, before the process ids are rewritten, and it computes the owning process of each of
 * them itself. Both call sites can therefore supply the workflow module and the plain
 * process id, and neither of them has a task definition to offer.
 */
@FunctionalInterface
public interface Camunda8AllowConnectorsResolver {

  /**
   * What applies where no level configures anything: VanillaBP wires every element of the
   * model, which is what an application without a connector wants.
   */
  boolean DEFAULT_ALLOW_CONNECTORS = false;

  /**
   * The answer together with the key it came from, because the startup report has to name
   * the line a reader can find in their own configuration.
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
   * Asks a resolver which may not be there - the deployment service is built without one in
   * tests, and a resolver is free to answer nothing.
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
