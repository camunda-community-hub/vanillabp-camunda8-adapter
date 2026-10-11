package io.vanillabp.camunda8.wiring;

import java.time.Duration;

/**
 * Resolves how long the cluster keeps a published message. The platform modules implement
 * it on top of the configuration overlay of the adapter. The most specific value wins,
 * across four levels (message &gt; workflow &gt; workflow-module &gt; adapter):
 *
 * <pre>
 * vanillabp.adapters.&lt;id&gt;.message-time-to-live
 * vanillabp.workflow-modules.&lt;m&gt;.adapters.&lt;id&gt;.message-time-to-live
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.adapters.&lt;id&gt;.message-time-to-live
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.messages.&lt;messageName&gt;.adapters.&lt;id&gt;.message-time-to-live
 * </pre>
 *
 * The most specific level is a MESSAGE and not a task. That is the one thing this
 * resolution does not share with {@code job-timeout}.
 *
 * <h2>Why it is resolvable at all</h2>
 *
 * The number does two jobs which pull in opposite directions. First, it is how long the
 * cluster BUFFERS a message published before its subscription exists. For that it should be
 * large. Second, it is the window in which a message id DEDUPLICATES. For that it should be
 * small. For as long as the message lives, a second publication of the same id is dropped
 * without a word, even a fully legitimate one. Take a catch event whose message may repeat
 * every minute, and one whose message is published long before the workflow reaches it.
 * These are different messages in one application. One number for the whole application
 * would be wrong for one of them.
 *
 * <h2>What it is not</h2>
 *
 * It is not a way to make a repeated correlation work. Two legitimate correlations differ
 * by what they carry: a correlation id which varies, or the activation which VanillaBP puts
 * into the message id where the BPMS names one. A shorter window only makes the collision
 * rarer. And it has a floor nobody configures. The cluster forgets an expired message id
 * on a sweep of its own, not at the moment it expires. Measured against
 * camunda/camunda:8.9.16 on 2026-08-27: a two-second time-to-live still deduplicated five
 * seconds later, and was forgotten after 75.
 */
@FunctionalInterface
public interface Camunda8MessageTimeToLiveResolver {

  /**
   * How long the cluster keeps a published message of this name waiting for a workflow to
   * correlate it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param messageName The BPMN message name, as the application wrote it (without any
   *          prefixing a name-clash-avoidance mode applies)
   * @return The most specific configured time-to-live, or <code>null</code> where no level
   *         configures one. Then the default of the client applies, and VanillaBP sets
   *         nothing on the command
   */
  Duration messageTimeToLiveFor(
      String workflowModuleId,
      String bpmnProcessId,
      String messageName);

}
