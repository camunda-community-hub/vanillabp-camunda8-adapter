package io.vanillabp.camunda8.wiring;

import java.time.Duration;

/**
 * Resolves how long the cluster waits before it hands a FAILED job out again - implemented
 * by the platform modules on top of the adapter's configuration overlay with
 * most-specific-wins semantics across the four levels (task &gt; workflow &gt;
 * workflow-module &gt; adapter):
 *
 * <pre>
 * vanillabp.adapters.&lt;id&gt;.retry-backoff
 * vanillabp.workflow-modules.&lt;m&gt;.adapters.&lt;id&gt;.retry-backoff
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.adapters.&lt;id&gt;.retry-backoff
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.tasks.&lt;taskDefinition&gt;.adapters.&lt;id&gt;.retry-backoff
 * </pre>
 *
 * Unlike <code>job-timeout</code> this is resolved per COMMAND and not per worker: the
 * backoff travels with the fail command, so two BPMN processes served by one worker may
 * have different ones and nothing has to be aligned.
 * <p>
 * A BPMN model may say the same thing in the task header
 * {@value Camunda8RetryBackoffHeader#HEADER_NAME}, which is why the answer names its
 * level - see {@link Camunda8RetryBackoffHeader} for how the two are weighed.
 */
@FunctionalInterface
public interface Camunda8RetryBackoffResolver {

  /**
   * How long the cluster waits before the next attempt where no level configures
   * anything: ten seconds.
   * <p>
   * <b>Why ten seconds.</b> Without a backoff the cluster hands a failed job out again as
   * fast as it can, so a handler failing on something which needs a moment - a locked row,
   * a service which is restarting, a rate limit - burns its three retries in less time
   * than the cause takes to pass, and the incident says nothing about what really
   * happened. Ten seconds is long enough for such a cause to clear and short enough that a
   * job with three retries is done deciding within half a minute rather than within
   * minutes. Size it against how long the dependency your handlers call needs to come
   * back, not against the length of the handler.
   */
  String DEFAULT_RETRY_BACKOFF_ISO = "PT10S";

  /**
   * {@link #DEFAULT_RETRY_BACKOFF_ISO} as a duration.
   */
  Duration DEFAULT_RETRY_BACKOFF = Duration.parse(DEFAULT_RETRY_BACKOFF_ISO);

  /**
   * A resolved backoff together with the level it was configured at - or rather, with the
   * one distinction that matters. Only the TASK level is told apart, because it is the
   * only one the model can meet as an equal: the task header
   * {@value Camunda8RetryBackoffHeader#HEADER_NAME} speaks about exactly one task, so it
   * outranks what a workflow, a workflow module or the adapter says and has to be decided
   * against a task-level property.
   *
   * @param duration The backoff, never <code>null</code>
   * @param perTask Whether the task level is where the duration comes from
   */
  record Configured(Duration duration, boolean perTask) {

    /**
     * The backoff nobody configured.
     */
    static final Configured DEFAULT = new Configured(DEFAULT_RETRY_BACKOFF, false);

  }

  /**
   * How long the cluster waits before it offers a failed job of this task again, and whether
   * the task level is where that came from.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinition The task definition (job type), or <code>null</code> for the
   *          workers which serve no task
   * @return The most specific configured retry backoff, falling back to
   *         {@link #DEFAULT_RETRY_BACKOFF}
   */
  Configured retryBackoffFor(
      String workflowModuleId,
      String bpmnProcessId,
      String taskDefinition);

  /**
   * Asks a resolver which may not be there - the handlers are built without one in tests,
   * and a resolver is free to answer nothing.
   *
   * @param resolver The resolver or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinition The task definition, or <code>null</code>
   * @return The resolved backoff, never <code>null</code>
   */
  static Configured resolve(
      final Camunda8RetryBackoffResolver resolver,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition) {

    if (resolver == null) {
      return Configured.DEFAULT;
    }
    final var resolved = resolver.retryBackoffFor(workflowModuleId, bpmnProcessId, taskDefinition);
    return resolved == null
        ? Configured.DEFAULT
        : resolved;

  }

}
