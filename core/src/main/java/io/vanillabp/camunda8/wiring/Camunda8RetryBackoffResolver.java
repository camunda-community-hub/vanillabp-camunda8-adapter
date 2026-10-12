package io.vanillabp.camunda8.wiring;

import java.time.Duration;

/**
 * Resolves how long the cluster waits before it hands a FAILED job out again. The platform
 * modules implement it on top of the configuration overlay of the adapter. The most
 * specific value wins, across the four levels (task &gt; workflow &gt; workflow-module &gt;
 * adapter):
 *
 * <pre>
 * vanillabp.adapters.&lt;id&gt;.retry-backoff
 * vanillabp.workflow-modules.&lt;m&gt;.adapters.&lt;id&gt;.retry-backoff
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.adapters.&lt;id&gt;.retry-backoff
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.tasks.&lt;taskDefinition&gt;.adapters.&lt;id&gt;.retry-backoff
 * </pre>
 *
 * Unlike <code>job-timeout</code>, this is resolved per COMMAND and not per worker. The
 * backoff travels with the fail command. So two BPMN processes served by one worker may
 * have different ones, and nothing has to be aligned.
 * <p>
 * A BPMN model may say the same thing in the task header
 * {@value Camunda8RetryBackoffHeader#HEADER_NAME}. That is why the answer names its level.
 * See {@link Camunda8RetryBackoffHeader} for how the two are weighed.
 */
@FunctionalInterface
public interface Camunda8RetryBackoffResolver {

  /**
   * How long the cluster waits before the next attempt where no level configures
   * anything: ten seconds.
   * <p>
   * <b>Why ten seconds.</b> Without a backoff, the cluster hands a failed job out again as
   * fast as it can. Some causes need a moment to pass: a locked row, a service which is
   * restarting, a rate limit. A handler failing on such a cause uses up its three retries
   * before the cause has passed. And then the incident says nothing about what really
   * happened. Ten seconds is long enough for such a cause to clear. It is also short enough
   * that a job with three retries is decided within half a minute, not within minutes.
   * Size it by how long the dependency your handlers call needs to come back, not by the
   * length of the handler.
   */
  String DEFAULT_RETRY_BACKOFF_ISO = "PT10S";

  /**
   * {@link #DEFAULT_RETRY_BACKOFF_ISO} as a duration.
   */
  Duration DEFAULT_RETRY_BACKOFF = Duration.parse(DEFAULT_RETRY_BACKOFF_ISO);

  /**
   * A resolved backoff together with the level it was configured at. More exactly, with
   * the one difference which matters. Only the TASK level is told apart. It is the only
   * one the model can meet as an equal. The task header
   * {@value Camunda8RetryBackoffHeader#HEADER_NAME} speaks about exactly one task. So it
   * outranks what a workflow, a workflow module or the adapter says. Only a task-level
   * property has to be weighed against it.
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
   * Asks a resolver which may not be there. In tests the handlers are built without one.
   * And a resolver is free to answer nothing.
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
