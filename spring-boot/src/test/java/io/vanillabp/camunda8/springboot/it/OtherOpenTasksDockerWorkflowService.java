package io.vanillabp.camunda8.springboot.it;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the test which lets a boundary event take one of two open tasks
 * away. Both tasks are asynchronous, so the workflow holds both at once. The method of the
 * one which is taken away subscribes to both events. That makes the derived cancelation
 * visible to the application.
 */
@Service
@WorkflowService(
    workflowAggregateClass = OtherOpenTasksDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "OtherOpenTasksProcess"))
public class OtherOpenTasksDockerWorkflowService {

  /**
   * What the application heard about the task which the boundary event took away, keyed by
   * aggregate id. The test reads it next to the aggregate. So a notification which arrives
   * after the aggregate was read is still seen.
   */
  public static final Map<String, String> WHAT_HAPPENED = new ConcurrentHashMap<>();

  private final ProcessService<OtherOpenTasksDockerAggregate> processService;

  public OtherOpenTasksDockerWorkflowService(
      final ProcessService<OtherOpenTasksDockerAggregate> processService) {

    this.processService = processService;

  }

  public OtherOpenTasksDockerAggregate startWorkflow(
      final OtherOpenTasksDockerAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  public OtherOpenTasksDockerAggregate correlate(
      final OtherOpenTasksDockerAggregate aggregate,
      final String messageName) {

    return processService.correlateMessage(aggregate, messageName);

  }

  /**
   * The task which an interrupting boundary event takes away. Zeebe tells no worker about a
   * job it removed. So VanillaBP derives the CANCELED which this method hears, at the next
   * wake-up of the same workflow.
   */
  @WorkflowTask(taskDefinition = "theTaskTakenAway")
  public void theTaskTakenAway(
      final OtherOpenTasksDockerAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent final TaskEvent.Event event) {

    if (event == TaskEvent.Event.CREATED) {
      aggregate.setTakenAwayTaskId(taskId);
      return;
    }
    aggregate.setWhatHappenedToTheTakenTask(event.name());
    WHAT_HAPPENED.put(String.valueOf(aggregate.getId()), event.name());

  }

  /**
   * The task which stays open. Its lock runs out while the test waits, and the cluster hands
   * its job to the worker again. That redelivery is the wake-up which looks at the other
   * task.
   */
  @WorkflowTask(taskDefinition = "theTaskStayingOpen")
  public void theTaskStayingOpen(
      final OtherOpenTasksDockerAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setStayingTaskId(taskId);

  }

}
