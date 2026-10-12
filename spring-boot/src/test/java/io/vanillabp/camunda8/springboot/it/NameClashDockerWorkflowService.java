package io.vanillabp.camunda8.springboot.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the name-clash test. Nothing is started here. The test measures
 * what the DEPLOYMENT reports about a process id which the cluster already held. The one
 * task only exists to make the model an ordinary one.
 * <p>
 * It is a bean only under the profile of its own test. Every test of this module boots the
 * same application, and none of the others deploys the process of this one.
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
