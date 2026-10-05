package io.vanillabp.camunda8.springboot.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the old-process-versions test: the task which
 * survives into version 2 is served for every version, the task which was dropped is
 * served for version 1 only - and whether the cluster still holds a version needing it
 * is what the startup check answers.
 */
@Service
@WorkflowService(
    workflowAggregateClass = OldProcessVersionsDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "OldProcessVersionsProcess"))
public class OldProcessVersionsDockerWorkflowService {

  private final ProcessService<OldProcessVersionsDockerAggregate> processService;

  public OldProcessVersionsDockerWorkflowService(
      final ProcessService<OldProcessVersionsDockerAggregate> processService) {

    this.processService = processService;

  }

  public OldProcessVersionsDockerAggregate startWorkflow() {

    return processService.startWorkflow(new OldProcessVersionsDockerAggregate());

  }

  @WorkflowTask(taskDefinition = "keptInBothVersions")
  public void keptInBothVersions(
      final OldProcessVersionsDockerAggregate aggregate) {

    aggregate.setServedBy("kept");

  }

  @WorkflowTask(taskDefinition = "droppedInVersionTwo", version = "1")
  public void droppedInVersionTwo(
      final OldProcessVersionsDockerAggregate aggregate) {

    aggregate.setServedBy("gone");

  }

}
