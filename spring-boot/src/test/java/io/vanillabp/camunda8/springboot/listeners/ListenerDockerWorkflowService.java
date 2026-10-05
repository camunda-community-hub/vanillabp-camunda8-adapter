package io.vanillabp.camunda8.springboot.listeners;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the modelled-listener integration test: one method for the task and
 * one per listener, each named after the job type the model carries. Under
 * {@code name-clash-avoidance: use-prefix} the cluster knows those job types prefixed, and that
 * the methods below are reached at all is what proves the prefixing and its way back.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ListenerDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ListenerProcess"))
public class ListenerDockerWorkflowService {

  @Autowired
  private ProcessService<ListenerDockerAggregate> processService;

  public ListenerDockerAggregate startWorkflow() {

    return processService.startWorkflow(new ListenerDockerAggregate());

  }

  @WorkflowTask(taskDefinition = "doTheWork")
  public void doTheWork(
      final ListenerDockerAggregate aggregate) {

    aggregate.setTheWorkWasDone(true);

  }

  /**
   * The <code>start</code> execution listener of the service task. Its completion carries no
   * variables, so this flag reaches the database and the cluster learns of it at the next sync
   * point, which is the completion of the task itself.
   */
  @WorkflowTask(taskDefinition = "prepareTheWork")
  public void prepareTheWork(
      final ListenerDockerAggregate aggregate) {

    aggregate.setTheWorkWasPrepared(true);

  }

  /**
   * The <code>end</code> execution listener of the service task, and the method the gateway
   * behind the task depends on: this flag is false in the cluster until this method sets it, so
   * the process only takes the flow to {@link #confirmTheAudit} when the completion of this
   * listener really carried the shared values.
   */
  @WorkflowTask(taskDefinition = "auditTheWork")
  public void auditTheWork(
      final ListenerDockerAggregate aggregate) {

    aggregate.setTheWorkWasAudited(true);

  }

  /**
   * The task the gateway takes where the process instance saw what the end listener wrote.
   */
  @WorkflowTask(taskDefinition = "confirmTheAudit")
  public void confirmTheAudit(
      final ListenerDockerAggregate aggregate) {

    aggregate.setTheProcessSawTheAudit(true);

  }

  /**
   * The task the gateway takes otherwise, so a lost value fails the test with a flag saying
   * which way the process went rather than with a timeout.
   */
  @WorkflowTask(taskDefinition = "reportTheLoss")
  public void reportTheLoss(
      final ListenerDockerAggregate aggregate) {

    aggregate.setTheProcessMissedTheAudit(true);

  }

  /**
   * The <code>end</code> execution listener of the end event, which is the placement version 1
   * served on Camunda 7 and never on Camunda 8.
   */
  @WorkflowTask(taskDefinition = "archiveTheOrder")
  public void archiveTheOrder(
      final ListenerDockerAggregate aggregate) {

    aggregate.setTheOrderWasArchived(true);

  }

}
