package io.vanillabp.camunda8.springboot.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The application of {@code Camunda8UnservedUserTaskOfARenamedProcessIT} BEFORE the rename.
 * It starts the workflow and serves no task at all: the one user task of the model has no
 * method, which a user task is allowed.
 */
@Service
@Profile("unserved-rename-before")
@WorkflowService(
    workflowAggregateClass = UnservedRenameDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "UnservedRenameOld"))
public class UnservedRenameBeforeDockerWorkflowService {

  private final ProcessService<UnservedRenameDockerAggregate> processService;

  public UnservedRenameBeforeDockerWorkflowService(
      final ProcessService<UnservedRenameDockerAggregate> processService) {

    this.processService = processService;

  }

  public UnservedRenameDockerAggregate startWorkflow() {

    return processService.startWorkflow(new UnservedRenameDockerAggregate());

  }

}
