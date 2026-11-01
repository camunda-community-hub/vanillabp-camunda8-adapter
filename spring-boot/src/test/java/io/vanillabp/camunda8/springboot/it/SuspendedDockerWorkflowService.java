package io.vanillabp.camunda8.springboot.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the test which suspends a running instance. Both of its tasks wait for
 * the application, so the test decides when each of them is answered.
 */
@Service
@WorkflowService(
    workflowAggregateClass = SuspendedDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "SuspendableProcess"))
public class SuspendedDockerWorkflowService {

  private final ProcessService<SuspendedDockerAggregate> processService;

  public SuspendedDockerWorkflowService(
      final ProcessService<SuspendedDockerAggregate> processService) {

    this.processService = processService;

  }

  public SuspendedDockerAggregate startWorkflow(
      final SuspendedDockerAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  public void completeTask(
      final SuspendedDockerAggregate aggregate,
      final String answer) {

    aggregate.setAnswer(answer);
    processService.completeTask(aggregate, aggregate.getOpenTaskId());

  }

  public void completeUserTask(
      final SuspendedDockerAggregate aggregate,
      final String answer) {

    aggregate.setAnswer(answer);
    processService.completeUserTask(aggregate, aggregate.getUserTaskId());

  }

  @WorkflowTask(taskDefinition = "answeredWhileSuspendable")
  public void asynchronousTask(
      final SuspendedDockerAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setOpenTaskId(taskId);

  }

  @WorkflowTask(taskDefinition = "userTaskAnsweredWhileSuspendable")
  public void userTask(
      final SuspendedDockerAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent final TaskEvent.Event event) {

    if (event == TaskEvent.Event.CREATED) {
      aggregate.setUserTaskId(taskId);
    }

  }

  @WorkflowEnded
  public void workflowEnded(
      final SuspendedDockerAggregate aggregate,
      final WorkflowEnd end) {

    aggregate.setEndedAs(String.valueOf(end.kind()));

  }

}
