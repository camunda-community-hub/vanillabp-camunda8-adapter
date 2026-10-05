package io.vanillabp.camunda8.springboot.it;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The second workflow of the worker-threads integration test: its job is
 * served by another worker of the SAME adapter, so it may only be delayed if the
 * adapter has no free execution slot.
 */
@Service
@WorkflowService(
    workflowAggregateClass = QuickDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "QuickProcess"))
public class QuickDockerWorkflowService {

  @Autowired
  private ProcessService<QuickDockerAggregate> processService;

  public QuickDockerAggregate startWorkflow() {

    return processService.startWorkflow(new QuickDockerAggregate());

  }

  @WorkflowTask(taskDefinition = "quickTask")
  public void quickTask(
      final QuickDockerAggregate aggregate) {

    aggregate.setServedBy("quick");
    WorkerThreadsDockerWorkflowService.QUICK_SERVED_WHILE_BLOCKED
        .compareAndSet(false, WorkerThreadsDockerWorkflowService.isBlocking());
    WorkerThreadsDockerWorkflowService.QUICK_SERVED_ON_VIRTUAL_THREAD
        .compareAndSet(false, Thread.currentThread().isVirtual());
    WorkerThreadsDockerWorkflowService.QUICK_SERVED.countDown();

  }

}
