package io.vanillabp.camunda8.springboot.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The same application AFTER the rename. The BPMN process carries the new id. The old id is
 * declared as a secondary process, so these very methods keep serving the workflows which
 * are still on it.
 * <p>
 * Nothing else about the class says that a rename happened. That is the point of the
 * feature: the aggregate is the same, the tasks are the same, and the workflows started
 * under the old id end under it.
 */
@Service
@Profile("rename-after")
@WorkflowService(
    workflowAggregateClass = RenamedDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "RenamedProcessNew"),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "RenamedProcessOld"))
public class RenamedAfterDockerWorkflowService {

  private final ProcessService<RenamedDockerAggregate> processService;

  private final RenamedDockerAggregateRepository repository;

  public RenamedAfterDockerWorkflowService(
      final ProcessService<RenamedDockerAggregate> processService,
      final RenamedDockerAggregateRepository repository) {

    this.processService = processService;
    this.repository = repository;

  }

  public void continueWorkflow(
      final Long orderId) {

    // the OLD name. This generation's model declares only 'RenameContinueV2'. So the name
    // below lives in the model which the cluster still holds under the old id. A workflow
    // started before the rename waits for exactly that name
    processService.correlateMessage(repository.findById(orderId).orElseThrow(), "RenameContinue");

  }

  public void correlateAMessageNoModelDeclares(
      final Long orderId) {

    processService
        .correlateMessage(repository.findById(orderId).orElseThrow(), "DeclaredByNoModelAtAll");

  }

  @WorkflowTask(taskDefinition = "renameStarted")
  public void renameStarted(
      final RenamedDockerAggregate aggregate) {

    aggregate.setStartedBy("after-the-rename");

  }

  @WorkflowTask(taskDefinition = "renameFinished")
  public void renameFinished(
      final RenamedDockerAggregate aggregate) {

    aggregate.setFinishedBy("after-the-rename");

  }

}
