package io.vanillabp.camunda8.springboot.it;

import java.util.concurrent.CountDownLatch;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of the authentication integration test. Everything it does
 * travels the authenticated connection: the deployment at startup, the command starting
 * the workflow, the activation request of the worker and the completion of its job.
 */
@Service
@WorkflowService(
    workflowAggregateClass = AuthDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "AuthProcess"))
public class AuthDockerWorkflowService {

  /**
   * Counted down by the handler: the worker got its job through the authenticated
   * connection.
   */
  public static final CountDownLatch SERVED = new CountDownLatch(1);

  @Autowired
  private ProcessService<AuthDockerAggregate> processService;

  public AuthDockerAggregate startWorkflow() {

    return processService.startWorkflow(new AuthDockerAggregate());

  }

  @WorkflowTask(taskDefinition = "authTask")
  public void authTask(
      final AuthDockerAggregate aggregate) {

    aggregate.setServed(true);
    SERVED.countDown();

  }

}
