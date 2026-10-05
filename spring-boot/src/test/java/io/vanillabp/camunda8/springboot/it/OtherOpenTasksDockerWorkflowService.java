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
 * away. Both tasks are asynchronous, so the workflow holds both at once, and the method of
 * the one which is taken away subscribes to both events - which is what makes the derived
 * cancelation visible to the application.
 */
@Service
@WorkflowService(
    workflowAggregateClass = OtherOpenTasksDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "OtherOpenTasksProcess"))
public class OtherOpenTasksDockerWorkflowService {

  /**
   * What the application heard about the task the boundary event took away, keyed by
   * aggregate id - read by the test next to the aggregate, so a notification arriving after
   * the aggregate was read is still seen.
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
   * The task an interrupting boundary event takes away. Zeebe tells no worker about a job it
   * removed, so the CANCELED this method hears is derived by VanillaBP at the next wake-up
   * of the same workflow.
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
   * The task which stays open. Its lock runs out while the test waits, the cluster hands
   * its job to the worker again, and that redelivery is the wake-up which looks at the
   * other task.
   */
  @WorkflowTask(taskDefinition = "theTaskStayingOpen")
  public void theTaskStayingOpen(
      final OtherOpenTasksDockerAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setStayingTaskId(taskId);

  }

}
