package io.vanillabp.camunda8.springboot.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * One method which serves the task of <code>orphan-process.bpmn</code>, and one whose task
 * definition nobody modelled. That is a typo, or a method left behind after a model change.
 * <p>
 * It is a bean only under the profile of its own test. The other tests of this module boot
 * the same application. A workflow service which matches nothing would end their starts as
 * well. That is exactly the check which this class is here to trigger.
 */
@Service
@Profile("orphan-method")
@WorkflowService(
    workflowAggregateClass = OrphanMethodDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "OrphanProcess"))
public class OrphanMethodDockerWorkflowService {

  @WorkflowTask
  public void orphanModelled() {

  }

  @WorkflowTask(taskDefinition = "activityNobodyModelled")
  public void orphanTypo() {

  }

}
