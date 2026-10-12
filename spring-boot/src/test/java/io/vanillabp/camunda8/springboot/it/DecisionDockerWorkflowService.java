package io.vanillabp.camunda8.springboot.it;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the decision-table integration test. The workflow runs a
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
   * Nothing about DMN is visible here. The cluster evaluated the decision which this module
   * deployed, and wrote the result into the workflow. The parameter names that result.
   */
  @WorkflowTask(taskDefinition = "recordRating")
  public void recordRating(
      final DecisionDockerAggregate aggregate,
      @TaskParam("rating") final String rating) {

    aggregate.setRating(rating);

  }

}
