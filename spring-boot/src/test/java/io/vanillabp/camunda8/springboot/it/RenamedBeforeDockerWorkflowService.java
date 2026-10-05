package io.vanillabp.camunda8.springboot.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The application of the renamed-process integration test BEFORE the rename: one BPMN
 * process, under the id the workflows of this test are started on.
 * <p>
 * Both halves of the test carry a Spring profile, because the two are two generations of
 * one application and must never boot together: they declare the same workflow aggregate
 * with a different process to start. A workflow service is registered because it is a
 * bean, so a profile which is not active registers nothing - which is also what keeps the
 * other integration tests of this module out of this scenario.
 */
@Service
@Profile("rename-before")
@WorkflowService(
    workflowAggregateClass = RenamedDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "RenamedProcessOld"))
public class RenamedBeforeDockerWorkflowService {

  private final ProcessService<RenamedDockerAggregate> processService;

  public RenamedBeforeDockerWorkflowService(
      final ProcessService<RenamedDockerAggregate> processService) {

    this.processService = processService;

  }

  public RenamedDockerAggregate startWorkflow() {

    return processService.startWorkflow(new RenamedDockerAggregate());

  }

  @WorkflowTask(taskDefinition = "renameStarted")
  public void renameStarted(
      final RenamedDockerAggregate aggregate) {

    aggregate.setStartedBy("before-the-rename");

  }

  @WorkflowTask(taskDefinition = "renameFinished")
  public void renameFinished(
      final RenamedDockerAggregate aggregate) {

    aggregate.setFinishedBy("before-the-rename");

  }

}
