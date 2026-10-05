package io.vanillabp.camunda8.springboot.it;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of the restart-delivery integration test: nothing but a task
 * which says when it was reached, so the test can measure how long the first job of a
 * workflow started right after a restart takes to arrive.
 */
@Service
@WorkflowService(
    workflowAggregateClass = RestartDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "RestartProcess"))
public class RestartDockerWorkflowService {

  /**
   * Counted down when the handler was reached.
   */
  public static volatile CountDownLatch SERVED = new CountDownLatch(1);

  /**
   * When the handler was reached, in nanoseconds of the JVM's clock - the measurement
   * this test exists for.
   */
  public static final AtomicLong SERVED_AT = new AtomicLong();

  /**
   * Puts the shared observations back to their initial state - called by the test which
   * uses them, so nothing an application left behind reaches the next one.
   */
  public static void reset() {

    SERVED = new CountDownLatch(1);
    SERVED_AT.set(0);

  }

  @Autowired
  private ProcessService<RestartDockerAggregate> processService;

  public RestartDockerAggregate startWorkflow() {

    return processService.startWorkflow(new RestartDockerAggregate());

  }

  @WorkflowTask(taskDefinition = "restartTask")
  public void restartTask(
      final RestartDockerAggregate aggregate) {

    SERVED_AT.compareAndSet(0, System.nanoTime());
    aggregate.setResult("served");
    SERVED.countDown();

  }

}
