package io.vanillabp.camunda8.quarkus.test.restart;

import java.util.concurrent.atomic.AtomicLong;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow of the restart-delivery test: nothing but a task which says
 * when it was reached, so the test can measure how long the first job of a workflow
 * started right after a restart takes to arrive.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = C8RestartAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "RestartProcess"))
public class C8RestartWorkflowService {

  /**
   * When the workflow was started, in milliseconds of the application's clock. The
   * measurement is taken inside the application, because the test talks to it over HTTP
   * and would otherwise measure the round trip too.
   */
  public static final AtomicLong STARTED_AT = new AtomicLong();

  /**
   * When the handler was reached.
   */
  public static final AtomicLong SERVED_AT = new AtomicLong();

  @Inject
  ProcessService<C8RestartAggregate> processService;

  public void startWorkflow() {

    STARTED_AT.set(System.currentTimeMillis());
    SERVED_AT.set(0);
    processService.startWorkflow(new C8RestartAggregate());

  }

  @WorkflowTask(taskDefinition = "restartTask")
  public void restartTask(
      final C8RestartAggregate aggregate) {

    SERVED_AT.compareAndSet(0, System.currentTimeMillis());
    aggregate.setResult("served");

  }

}
