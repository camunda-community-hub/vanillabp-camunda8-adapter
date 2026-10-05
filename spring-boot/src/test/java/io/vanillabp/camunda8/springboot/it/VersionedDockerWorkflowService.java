package io.vanillabp.camunda8.springboot.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the process-version integration test: one BPMN
 * task served by two methods, told apart by the version of the deployed process
 * definition - the first version by its number, the second one by the
 * <code>zeebe:versionTag</code> its model carries.
 */
@Service
@WorkflowService(
    workflowAggregateClass = VersionedDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "VersionedProcess"))
public class VersionedDockerWorkflowService {

  private final ProcessService<VersionedDockerAggregate> processService;

  public VersionedDockerWorkflowService(
      final ProcessService<VersionedDockerAggregate> processService) {

    this.processService = processService;

  }

  public VersionedDockerAggregate startWorkflow() {

    return processService.startWorkflow(new VersionedDockerAggregate());

  }

  @WorkflowTask(taskDefinition = "versionedTask", version = "1")
  public void firstVersion(
      final VersionedDockerAggregate aggregate) {

    aggregate.setServedBy("firstVersion");

  }

  @WorkflowTask(taskDefinition = "versionedTask", version = "release-2")
  public void taggedVersion(
      final VersionedDockerAggregate aggregate) {

    aggregate.setServedBy("taggedVersion");

  }

}
