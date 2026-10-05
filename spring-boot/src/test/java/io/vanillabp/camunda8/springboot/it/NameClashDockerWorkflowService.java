package io.vanillabp.camunda8.springboot.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the name-clash test. Nothing is started here: what the test
 * measures is what the DEPLOYMENT reports about a process id the cluster already held, so
 * the one task exists to make the model an ordinary one.
 * <p>
 * A bean only under the profile of its own test, because every test of this module boots the
 * same application and the process of this one is deployed by none of the others.
 */
@Service
@Profile("name-clash")
@WorkflowService(
    workflowAggregateClass = NameClashDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "NameClashProcess"))
public class NameClashDockerWorkflowService {

  @WorkflowTask(taskDefinition = "approveTheLoan")
  public void approveTheLoan(
      final NameClashDockerAggregate aggregate) {

  }

}
