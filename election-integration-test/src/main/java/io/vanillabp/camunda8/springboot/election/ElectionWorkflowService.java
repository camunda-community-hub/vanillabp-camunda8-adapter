package io.vanillabp.camunda8.springboot.election;

import java.util.concurrent.CountDownLatch;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of the shared-cluster election test: it waits for a message
 * and records that the message arrived.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ElectionAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ElectionProcess"))
public class ElectionWorkflowService {

  /**
   * Counted down when the task behind the message catch ran, which only happens if the
   * message was correlated into the workflow the FIRST application started.
   */
  public static volatile CountDownLatch SERVED = new CountDownLatch(1);

  public static void reset() {

    SERVED = new CountDownLatch(1);

  }

  @Autowired
  private ProcessService<ElectionAggregate> processService;

  public ElectionAggregate startWorkflow() {

    return processService.startWorkflow(new ElectionAggregate());

  }

  public void theMessageArrived(
      final ElectionAggregate aggregate) {

    processService.correlateMessage(aggregate, "ElectionMessage");

  }

  @WorkflowTask(taskDefinition = "electionTask")
  public void electionTask(
      final ElectionAggregate aggregate) {

    aggregate.setServedBy("electionTask");
    SERVED.countDown();

  }

}
