package io.vanillabp.camunda8.quarkus.test;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The workflow service of the timer-started workflow. The cluster starts it, so the
 * aggregate is built here and nowhere else, and the task following the start event has
 * to find it through the aggregate-id variable the start listener wrote.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = C8TimerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TimerStartProcess"))
public class C8TimerWorkflowService {

  /**
   * Builds the workflow aggregate of the workflow the timer started.
   *
   * @param trigger What the cluster fired
   * @return The workflow aggregate of the started workflow
   */
  @WorkflowStartedByBpms
  public C8TimerAggregate aggregateOfTimerStart(
      final BpmsStartTrigger trigger) {

    final var aggregate = new C8TimerAggregate();
    // the name of the workflow is the application's choice, and the cluster keeps it in
    // the process variable named after this aggregate's id attribute
    aggregate.setId("timer-"
        + java.util.UUID.randomUUID());
    aggregate.setStartedBy(trigger.kind().name());
    return aggregate;

  }

  /**
   * The workflow started by the timer also reports its end.
   *
   * @param aggregate The workflow aggregate
   * @param end How the workflow ended
   */
  @WorkflowEnded
  public void workflowEnded(
      final C8TimerAggregate aggregate,
      final WorkflowEnd end) {

    aggregate.setEndedAs(String.valueOf(end.kind()));

  }

  @WorkflowTask(taskDefinition = "recordTimerStart")
  public void recordTimerStart(
      final C8TimerAggregate aggregate) {

    aggregate.setProcessedBy("recordTimerStart");

  }

}
