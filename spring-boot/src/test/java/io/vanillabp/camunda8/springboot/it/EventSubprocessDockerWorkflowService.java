package io.vanillabp.camunda8.springboot.it;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the event-subprocess integration test: the workflow waits at a
 * timer catch event it never reaches the end of, because an event subprocess takes it
 * over.
 * <p>
 * The event subprocess starts on a timer of its own, and that start event is the point of
 * this test. The application serves it with no method at all: it starts no workflow, so
 * the boot must not ask for one, and the aggregate the task below is handed is the one
 * this service created.
 */
@Service
@WorkflowService(
    workflowAggregateClass = EventSubprocessDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "EventSubprocessProcess"))
public class EventSubprocessDockerWorkflowService {

  @Autowired
  private ProcessService<EventSubprocessDockerAggregate> processService;

  public EventSubprocessDockerAggregate startWorkflow() {

    final var aggregate = new EventSubprocessDockerAggregate();
    aggregate.setId(UUID.randomUUID().toString());
    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask(taskDefinition = "recordTakeOver")
  public void recordTakeOver(
      final EventSubprocessDockerAggregate aggregate) {

    aggregate.setProcessedBy("recordTakeOver");

  }

}
