package io.vanillabp.camunda8.springboot.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the test about a process called by an expression. Two BPMN
 * processes on one workflow aggregate, and every call between them names the called process
 * by <code>=whichProcess</code>, so the deployment cannot link the two models.
 */
@Service
@WorkflowService(
    workflowAggregateClass = MiFeelDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "MiFeelCallProcess"),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "MiFeelCalledProcess"))
public class MiFeelDockerWorkflowService {

  private final ProcessService<MiFeelDockerAggregate> processService;

  public MiFeelDockerWorkflowService(
      final ProcessService<MiFeelDockerAggregate> processService) {

    this.processService = processService;

  }

  public MiFeelDockerAggregate startWorkflow() {

    final var aggregate = new MiFeelDockerAggregate();
    aggregate.setWhichProcess("MiFeelCalledProcess");
    return processService.startWorkflow(aggregate);

  }

  /**
   * The task of the called process. It names no level of its own: every iteration it runs in
   * belongs to the caller, and the caller handed them over in a variable.
   */
  @WorkflowTask
  public void collectWhatTheCallerHandedDown(
      final MiFeelDockerAggregate aggregate,
      @TaskParam("whichCase") final String whichCase,
      @MultiInstanceElement(resolverBean = MiFeelChainResolver.class) final String chain) {

    aggregate
        .setReported(
            append(aggregate.getReported(), "%s=%s".formatted(whichCase, chain)));

  }

  private static String append(
      final String current,
      final String value) {

    return current == null
        ? value
        : current
            + ","
            + value;

  }

}
