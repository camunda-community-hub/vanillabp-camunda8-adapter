package io.vanillabp.camunda8.springboot.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * One method serving the task of <code>orphan-process.bpmn</code> and one whose task
 * definition nobody modelled - a typo, or a method left behind after a model change.
 * <p>
 * A bean only under the profile of its own test: the other tests of this module boot the
 * same application, and a workflow service matching nothing would end their starts as
 * well - which is exactly the check this class is here to trigger.
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
