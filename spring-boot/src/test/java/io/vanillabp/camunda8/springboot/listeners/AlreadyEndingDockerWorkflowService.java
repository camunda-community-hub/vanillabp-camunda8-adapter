package io.vanillabp.camunda8.springboot.listeners;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the test which completes a user task the cluster is already ending.
 * The model has one user task with a <code>completing</code> and a <code>canceling</code>
 * listener which nobody answers.
 */
@Service
@WorkflowService(
    workflowAggregateClass = AlreadyEndingDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "UserTaskAlreadyEndingProcess"))
public class AlreadyEndingDockerWorkflowService {

  /**
   * The key of the user task, by aggregate id. The notification writes it.
   */
  public static final Map<Long, String> TASK_IDS = new ConcurrentHashMap<>();

  @Autowired
  private ProcessService<AlreadyEndingDockerAggregate> processService;

  @Autowired
  private AlreadyEndingDockerAggregateRepository repository;

  public AlreadyEndingDockerAggregate startWorkflow() {

    return processService.startWorkflow(new AlreadyEndingDockerAggregate());

  }

  /**
   * Writes the note and completes the task, in the transaction of the caller.
   */
  public AlreadyEndingDockerAggregate completeTheUserTask(
      final AlreadyEndingDockerAggregate aggregate,
      final String taskId,
      final String note) {

    aggregate.setNote(note);
    repository.save(aggregate);
    return processService.completeUserTask(aggregate, taskId);

  }

  @WorkflowTask(taskDefinition = "theTaskWhichIsAlreadyEnding")
  public void theTaskWhichIsAlreadyEnding(
      final AlreadyEndingDockerAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent final TaskEvent.Event event) {

    if (event == TaskEvent.Event.CREATED) {
      TASK_IDS.put(aggregate.getId(), taskId);
    }

  }

}
