package io.vanillabp.camunda8.springboot.it;

import java.util.Set;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the ad-hoc subprocess integration test.
 *
 * <p>
 * Every activity inside the element is an ordinary task with an ordinary method behind it,
 * and there is deliberately no method for the subprocess itself: the element is not a task
 * of the application, and which of the three activities run is decided by what
 * {@code startWorkflow} was handed.
 * </p>
 */
@Service
@WorkflowService(
    workflowAggregateClass = AdHocDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "AdHocProcess"))
public class AdHocDockerWorkflowService {

  private final ProcessService<AdHocDockerAggregate> processService;

  public AdHocDockerWorkflowService(
      final ProcessService<AdHocDockerAggregate> processService) {

    this.processService = processService;

  }

  public AdHocDockerAggregate startWorkflow(
      final Set<String> checksToRun) {

    final var aggregate = new AdHocDockerAggregate();
    aggregate.setChecksToRun(checksToRun);
    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void checkFraud(
      final AdHocDockerAggregate aggregate) {

    aggregate.setFraudChecked(Boolean.TRUE);

  }

  @WorkflowTask
  public void checkIncome(
      final AdHocDockerAggregate aggregate) {

    aggregate.setIncomeChecked(Boolean.TRUE);

  }

  @WorkflowTask
  public void checkCollateral(
      final AdHocDockerAggregate aggregate) {

    aggregate.setCollateralChecked(Boolean.TRUE);

  }

  @WorkflowTask
  public void summarizeChecks(
      final AdHocDockerAggregate aggregate) {

    aggregate.setSummarized(Boolean.TRUE);

  }

}
