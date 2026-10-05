package io.vanillabp.camunda8.quarkus.test;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the aggregateChanged tests: both processes park
 * in an asynchronous task, so a changed aggregate can be pushed at the workflow's own
 * scope and into the scope of ONE iteration of a multi-instance subprocess.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = C8PushAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "AggregateChangedProcess"),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "AggregateChangedMultiInstanceProcess"))
public class C8PushWorkflowService {

  @Inject
  ProcessService<C8PushAggregate> processService;

  public C8PushAggregate startWorkflow(
      final C8PushAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  /**
   * Pushes the changed aggregate at the workflow's global scope.
   *
   * @param aggregate The changed aggregate
   */
  public void pushGlobally(
      final C8PushAggregate aggregate) {

    processService.aggregateChanged(aggregate);

  }

  /**
   * Pushes the changed aggregate into the scope the given task runs in.
   *
   * @param aggregate The changed aggregate
   * @param taskId The task whose scope receives the values
   */
  public void pushInto(
      final C8PushAggregate aggregate,
      final String taskId) {

    processService.aggregateChanged(aggregate, taskId);

  }

  @WorkflowTask(taskDefinition = "awaitPush")
  public void awaitPush(
      final C8PushAggregate aggregate,
      @TaskId final String taskId) {

    // parks the workflow: the instance stays in the cluster
    aggregate.setTaskIds(taskId);

  }

  @WorkflowTask(taskDefinition = "awaitPushPerInstance")
  public void awaitPushPerInstance(
      final C8PushAggregate aggregate,
      @TaskId final String taskId) {

    aggregate
        .setTaskIds(
            aggregate.getTaskIds() == null
                ? taskId
                : aggregate.getTaskIds()
                    + ","
                    + taskId);

  }

}
