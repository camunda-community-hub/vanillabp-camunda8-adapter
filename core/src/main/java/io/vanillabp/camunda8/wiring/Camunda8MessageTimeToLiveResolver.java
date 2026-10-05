package io.vanillabp.camunda8.wiring;

import java.time.Duration;

/**
 * Resolves how long the cluster keeps a published message - implemented by the platform
 * modules on top of the adapter's configuration overlay with most-specific-wins semantics
 * across four levels (message &gt; workflow &gt; workflow-module &gt; adapter):
 *
 * <pre>
 * vanillabp.adapters.&lt;id&gt;.message-time-to-live
 * vanillabp.workflow-modules.&lt;m&gt;.adapters.&lt;id&gt;.message-time-to-live
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.adapters.&lt;id&gt;.message-time-to-live
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.messages.&lt;messageName&gt;.adapters.&lt;id&gt;.message-time-to-live
 * </pre>
 *
 * The most specific level is a MESSAGE and not a task, which is the one thing this
 * resolution does not share with {@code job-timeout} next to it.
 *
 * <h2>Why it is resolvable at all</h2>
 *
 * The number does two jobs which pull in opposite directions. It is how long the cluster
 * BUFFERS a message published before its subscription exists, which wants it large; and it
 * is the window a message id DEDUPLICATES in, which wants it small, because for as long as
 * the message lives a second and entirely legitimate publication of the same id is dropped
 * without a word. A catch event whose message may repeat every minute and one whose message
 * is published long before the workflow reaches it are different messages in one
 * application, so one number for the whole application would have to be wrong for one of
 * them.
 *
 * <h2>What it is not</h2>
 *
 * It is not a way to make a repeated correlation work. What tells two legitimate
 * correlations apart is what they carry - a correlation id which varies, or the activation
 * VanillaBP puts into the message id where the BPMS names one. Shortening the window only
 * makes the collision rarer, and it has a floor nobody configures: the cluster forgets an
 * expired message id on a sweep of its own rather than at the moment it expires. Measured
 * against camunda/camunda:8.9.16 on 2026-08-27, a two-second time-to-live was still
 * deduplicating five seconds later and forgotten after 75.
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
   *         configures one - the client's own default applies then, and VanillaBP sets
   *         nothing on the command
   */
  Duration messageTimeToLiveFor(
      String workflowModuleId,
      String bpmnProcessId,
      String messageName);

}
