package io.vanillabp.camunda8.springboot.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The application of {@code Camunda8UnservedUserTaskOfARenamedProcessIT} AFTER the rename. It
 * deploys the model under the new id and declares the old one, and like the generation
 * before it, it serves no task.
 */
@Service
@Profile("unserved-rename-after")
@WorkflowService(
    workflowAggregateClass = UnservedRenameDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "UnservedRenameNew"),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "UnservedRenameOld"))
public class UnservedRenameAfterDockerWorkflowService {

  private final ProcessService<UnservedRenameDockerAggregate> processService;

  private final UnservedRenameDockerAggregateRepository repository;

  public UnservedRenameAfterDockerWorkflowService(
      final ProcessService<UnservedRenameDockerAggregate> processService,
      final UnservedRenameDockerAggregateRepository repository) {

    this.processService = processService;
    this.repository = repository;

  }

  public void continueWorkflow(
      final Long orderId) {

    // the name the OLD model declares, which is what the workflow started before the
    // rename waits for
    processService.correlateMessage(repository.findById(orderId).orElseThrow(), "UnservedContinue");

  }

}
