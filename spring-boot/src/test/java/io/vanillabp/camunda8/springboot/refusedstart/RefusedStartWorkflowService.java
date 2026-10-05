package io.vanillabp.camunda8.springboot.refusedstart;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the refused-start integration test. Its task method is there
 * because the model has a task, not because anything is expected to run it: the workflow
 * of this scenario never comes into being.
 */
@Service
@WorkflowService(
    workflowAggregateClass = RefusedStartAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "RefusedStartProcess"))
public class RefusedStartWorkflowService {

  private final ProcessService<RefusedStartAggregate> processService;

  public RefusedStartWorkflowService(
      final ProcessService<RefusedStartAggregate> processService) {

    this.processService = processService;

  }

  /**
   * Starts the workflow of an aggregate carrying the given text.
   *
   * @param document What the aggregate hands to the cluster as a process variable
   * @return The persisted aggregate
   */
  public RefusedStartAggregate startWorkflow(
      final String document) {

    final var aggregate = new RefusedStartAggregate();
    aggregate.setDocument(document);
    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask(taskDefinition = "neverRuns")
  public void neverRuns(
      final RefusedStartAggregate aggregate) {

    throw new IllegalStateException(
        "The workflow of the refused-start test was started after all - the cluster took a request "
            + "the test built to be refused!");

  }

}
