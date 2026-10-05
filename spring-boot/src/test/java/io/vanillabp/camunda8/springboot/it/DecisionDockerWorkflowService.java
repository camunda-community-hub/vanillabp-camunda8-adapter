package io.vanillabp.camunda8.springboot.it;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the decision-table integration test: the workflow runs a
 * business rule task, and the task after it reads what the decision produced.
 */
@Service
@WorkflowService(
    workflowAggregateClass = DecisionDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "DecisionProcess"))
public class DecisionDockerWorkflowService {

  @Autowired
  private ProcessService<DecisionDockerAggregate> processService;

  public DecisionDockerAggregate startWorkflow(
      final boolean approved) {

    final var aggregate = new DecisionDockerAggregate();
    aggregate.setApproved(approved);
    return processService.startWorkflow(aggregate);

  }

  /**
   * Nothing about DMN is visible here: the cluster evaluated the decision this module
   * deployed, wrote the result into the workflow, and the parameter names it.
   */
  @WorkflowTask(taskDefinition = "recordRating")
  public void recordRating(
      final DecisionDockerAggregate aggregate,
      @TaskParam("rating") final String rating) {

    aggregate.setRating(rating);

  }

}
