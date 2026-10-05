package io.vanillabp.camunda8.springboot.it;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the signal integration test: the workflow waits at an
 * intermediate signal catch event until a broadcast arrives.
 */
@Service
@WorkflowService(
    workflowAggregateClass = SignalDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "SignalCatchProcess"))
public class SignalDockerWorkflowService {

  @Autowired
  private ProcessService<SignalDockerAggregate> processService;

  public SignalDockerAggregate startWorkflow() {

    return processService.startWorkflow(new SignalDockerAggregate());

  }

  public void broadcast(
      final String signalName) {

    processService.sendSignal(signalName);

  }

  @WorkflowTask(taskDefinition = "recordSignal")
  public void recordSignal(
      final SignalDockerAggregate aggregate) {

    aggregate.setProcessedBy("recordSignal");

  }

}
