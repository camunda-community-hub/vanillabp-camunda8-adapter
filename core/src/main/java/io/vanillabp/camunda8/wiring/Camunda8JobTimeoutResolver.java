package io.vanillabp.camunda8.wiring;

import java.time.Duration;

/**
 * Resolves the job timeout (how long a worker holds the lock) for a task. The platform
 * modules implement it on top of the configuration overlay of the adapter. The most
 * specific value wins, across the four levels (task &gt; workflow &gt; workflow-module &gt;
 * adapter):
 *
 * <pre>
 * vanillabp.adapters.&lt;id&gt;.job-timeout
 * vanillabp.workflow-modules.&lt;m&gt;.adapters.&lt;id&gt;.job-timeout
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.adapters.&lt;id&gt;.job-timeout
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.tasks.&lt;taskDefinition&gt;.adapters.&lt;id&gt;.job-timeout
 * </pre>
 */
@FunctionalInterface
public interface Camunda8JobTimeoutResolver {

  /**
   * The default job timeout if no level configures one.
   */
  Duration DEFAULT_JOB_TIMEOUT = Duration.ofMinutes(5);

  /**
   * How long a job of this task stays locked for the worker which activated it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskDefinition The task definition (job type)
   * @return The most specific configured job timeout or
   *         {@link #DEFAULT_JOB_TIMEOUT}
   */
  Duration jobTimeoutFor(
      String workflowModuleId,
      String bpmnProcessId,
      String taskDefinition);

}
