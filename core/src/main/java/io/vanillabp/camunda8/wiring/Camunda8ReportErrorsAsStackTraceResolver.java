package io.vanillabp.camunda8.wiring;

/**
 * Resolves whether the incident of a failed job carries the whole stack trace of what the
 * application threw, or only its type and message. The platform modules implement it on top
 * of the configuration overlay of the adapter. The most specific value wins, across the four
 * levels (task &gt; workflow &gt; workflow-module &gt; adapter):
 *
 * <pre>
 * vanillabp.adapters.&lt;id&gt;.report-errors-as-stack-trace
 * vanillabp.workflow-modules.&lt;m&gt;.adapters.&lt;id&gt;.report-errors-as-stack-trace
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.adapters.&lt;id&gt;.report-errors-as-stack-trace
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.tasks.&lt;taskDefinition&gt;.adapters.&lt;id&gt;.report-errors-as-stack-trace
 * </pre>
 *
 * Version 1 read the key at the workflow-module level only, with the same name and the same
 * default. A configuration written for version 1 keeps working. The other three levels are
 * the ones every scope-specific key of this adapter has. The value is read per failure and
 * not per worker, like <code>retry-backoff</code>.
 * <p>
 * How the text is built, and how long it may get, is
 * {@link io.vanillabp.camunda8.client.Camunda8Errors#incidentMessage(Throwable, boolean)}.
 */
@FunctionalInterface
public interface Camunda8ReportErrorsAsStackTraceResolver {

  /**
   * The name of the key below <code>adapters.&lt;id&gt;</code>.
   */
  String KEY = "report-errors-as-stack-trace";

  /**
   * What applies where no level configures anything: the incident carries the type and the
   * message of what the application threw, and no stack trace. That was the default of
   * version 1 as well.
   */
  boolean DEFAULT_REPORT_ERRORS_AS_STACK_TRACE = false;

  /**
   * Whether a failure of this task writes the whole stack trace into its incident.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param taskDefinition The task definition, or <code>null</code> for a job which serves
   *          no task, like the start or the end of a workflow
   * @return The most specific configured value, or <code>null</code> where no level
   *         configures one
   */
  Boolean reportErrorsAsStackTraceFor(
      String workflowModuleId,
      String bpmnProcessId,
      String taskDefinition);

  /**
   * Asks a resolver which may not be there. In tests the handlers are built without one.
   * And a resolver is free to answer nothing.
   *
   * @param resolver The resolver or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param taskDefinition The task definition, or <code>null</code>
   * @return Whether the incident carries the stack trace
   */
  static boolean resolve(
      final Camunda8ReportErrorsAsStackTraceResolver resolver,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition) {

    if (resolver == null) {
      return DEFAULT_REPORT_ERRORS_AS_STACK_TRACE;
    }
    final var resolved = resolver.reportErrorsAsStackTraceFor(workflowModuleId, bpmnProcessId, taskDefinition);
    return resolved == null
        ? DEFAULT_REPORT_ERRORS_AS_STACK_TRACE
        : resolved.booleanValue();

  }

}
