package io.vanillabp.camunda8.springboot.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the timer-start integration test. The cluster starts this
 * workflow, so the aggregate is built here and nowhere else, and the task following the
 * start event has to find it through the aggregate-ID variable the start listener wrote.
 */
@Service
@WorkflowService(
    workflowAggregateClass = TimerStartDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TimerStartProcess"))
public class TimerStartDockerWorkflowService {

  /**
   * Builds the workflow aggregate of the workflow the timer started.
   *
   * @param trigger What the cluster fired
   * @return The workflow aggregate of the started workflow
   */
  @WorkflowStartedByBpms
  public TimerStartDockerAggregate aggregateOfTimerStart(
      final BpmsStartTrigger trigger) {

    final var aggregate = new TimerStartDockerAggregate();
    // the name of the workflow is the application's choice, and the cluster keeps it in
    // the process variable named after this aggregate's id attribute
    aggregate.setId("timer-"
        + java.util.UUID.randomUUID());
    aggregate.setStartedBy(trigger.kind().name());
    return aggregate;

  }

  /**
   * The workflow reports its end as well.
   */
  @WorkflowEnded
  public void workflowEnded(
      final TimerStartDockerAggregate aggregate,
      final WorkflowEnd end) {

    aggregate.setEndedAs(String.valueOf(end.kind()));

  }

  @WorkflowTask(taskDefinition = "recordTimerStart")
  public void recordTimerStart(
      final TimerStartDockerAggregate aggregate) {

    aggregate.setProcessedBy("recordTimerStart");

  }

}
