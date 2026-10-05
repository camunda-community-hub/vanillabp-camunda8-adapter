package io.vanillabp.camunda8.springboot.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service behind {@code Camunda8AnOperationWithoutTheReadModelIT}: the workflow
 * waits for a message and then parks in an asynchronous task, so the test can correlate the
 * message and afterwards push into the workflow's own scope.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ReadModelDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ReadModelStandsStillProcess"))
public class ReadModelDockerWorkflowService {

  private final ProcessService<ReadModelDockerAggregate> processService;

  private final ReadModelDockerAggregateRepository repository;

  public ReadModelDockerWorkflowService(
      final ProcessService<ReadModelDockerAggregate> processService,
      final ReadModelDockerAggregateRepository repository) {

    this.processService = processService;
    this.repository = repository;

  }

  public ReadModelDockerAggregate startWorkflow() {

    final var aggregate = new ReadModelDockerAggregate();
    aggregate.setNote("before");
    return processService.startWorkflow(aggregate);

  }

  public void correlate(
      final Long aggregateId) {

    processService.correlateMessage(repository.findById(aggregateId).orElseThrow(), "C8ReadModelStandsStill");

  }

  /**
   * Changes the aggregate and pushes it at the workflow's global scope.
   *
   * @param aggregateId The aggregate's id
   * @param note The new note
   */
  public void pushGlobally(
      final Long aggregateId,
      final String note) {

    final var aggregate = repository.findById(aggregateId).orElseThrow();
    aggregate.setNote(note);
    processService.aggregateChanged(aggregate);

  }

  /**
   * Completes the parked task, which ends the workflow.
   *
   * @param aggregateId The aggregate's id
   */
  public void finish(
      final Long aggregateId) {

    final var aggregate = repository.findById(aggregateId).orElseThrow();
    processService.completeTask(aggregate, aggregate.getTaskId());

  }

  @WorkflowTask(taskDefinition = "readModelMessageArrived")
  public void messageArrived(
      final ReadModelDockerAggregate aggregate,
      @TaskId final String taskId) {

    // parks the workflow: the instance stays in the cluster until the test finishes it
    aggregate.setTaskId(taskId);

  }

}
