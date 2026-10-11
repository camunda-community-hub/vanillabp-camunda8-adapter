package io.vanillabp.camunda8.springboot.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the process-version integration test. Two methods serve one BPMN
 * task. The version of the deployed process definition tells them apart: the first version
 * by its number, the second one (and every one after it) by the
 * <code>zeebe:versionTag</code> which its model carries.
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

  /**
   * Serves the tagged version and every version after it. Exactly 'release-2' is not enough.
   * Every other test class of this module deploys the untagged model as well. All of them share
   * the cluster. So once this test ran, that model becomes a version after the tagged one. A
   * start refuses the version it deploys if a task of it has no method for that version. So with
   * 'release-2' alone, every test class which boots after this one would fail.
   * <p>
   * The range starts at the tag, and the tagged version is deployed while the application runs.
   * So this test also holds that a range which starts at a tag finds a version which came after
   * the start.
   *
   * @param aggregate The workflow aggregate
   */
  @WorkflowTask(taskDefinition = "versionedTask", version = ">=release-2")
  public void taggedVersion(
      final VersionedDockerAggregate aggregate) {

    aggregate.setServedBy("taggedVersion");

  }

}
