package io.vanillabp.camunda8.springboot.listeners;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the test which probes user tasks. The model has three user tasks,
 * which differ only in their <code>updating</code> listener. It also has one asynchronous
 * task. The redelivery of that task is the wake-up which runs the check.
 */
@Service
@WorkflowService(
    workflowAggregateClass = UserTaskProbeDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "UserTaskProbeProcess"))
public class UserTaskProbeDockerWorkflowService {

  /**
   * What the application heard about each user task, keyed by "aggregate id/form reference".
   * The test reads this map, not the aggregate. So it still sees a notification which arrives
   * after the aggregate was read.
   */
  public static final Map<String, String> WHAT_HAPPENED = new ConcurrentHashMap<>();

  /**
   * The key of each user task, under the same keys as {@link #WHAT_HAPPENED}. It is kept here
   * and not in the aggregate. The reason is written on {@link UserTaskProbeDockerAggregate}.
   */
  public static final Map<String, String> TASK_IDS = new ConcurrentHashMap<>();

  /**
   * How often the method serving the modelled <code>updating</code> listener ran. It has to
   * stay at zero. The only update anybody sends here is the probe of this adapter. An
   * application method has nothing to do with a probe.
   */
  public static final AtomicInteger UPDATES_THE_APPLICATION_SAW = new AtomicInteger();

  @Autowired
  private ProcessService<UserTaskProbeDockerAggregate> processService;

  public UserTaskProbeDockerAggregate startWorkflow() {

    return processService.startWorkflow(new UserTaskProbeDockerAggregate());

  }

  @WorkflowTask(taskDefinition = "theServedUserTask")
  public void theServedUserTask(
      final UserTaskProbeDockerAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent final TaskEvent.Event event) {

    if (event == TaskEvent.Event.CREATED) {
      TASK_IDS.put(aggregate.getId()
          + "/theServedUserTask", taskId);
    }
    WHAT_HAPPENED.put(aggregate.getId()
        + "/theServedUserTask", event.name());

  }

  @WorkflowTask(taskDefinition = "thePlainUserTask")
  public void thePlainUserTask(
      final UserTaskProbeDockerAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent final TaskEvent.Event event) {

    if (event == TaskEvent.Event.CREATED) {
      TASK_IDS.put(aggregate.getId()
          + "/thePlainUserTask", taskId);
    }
    WHAT_HAPPENED.put(aggregate.getId()
        + "/thePlainUserTask", event.name());

  }

  @WorkflowTask(taskDefinition = "theForeignUserTask")
  public void theForeignUserTask(
      final UserTaskProbeDockerAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent final TaskEvent.Event event) {

    if (event == TaskEvent.Event.CREATED) {
      TASK_IDS.put(aggregate.getId()
          + "/theForeignUserTask", taskId);
    }
    WHAT_HAPPENED.put(aggregate.getId()
        + "/theForeignUserTask", event.name());

  }

  /**
   * The modelled <code>updating</code> listener of the served user task. The probe fires it.
   * The adapter closes its job without calling this method, and the counter shows that.
   */
  @WorkflowTask(taskDefinition = "watchTheServedUserTask")
  public void watchTheServedUserTask(
      final UserTaskProbeDockerAggregate aggregate) {

    UPDATES_THE_APPLICATION_SAW.incrementAndGet();

  }

  /**
   * The asynchronous task which stays open. Its lock runs out while the test waits. The cluster
   * then hands its job to the worker again. That redelivery is the wake-up which looks at the
   * user tasks.
   */
  @WorkflowTask(taskDefinition = "keepTheWorkflowAwake")
  public void keepTheWorkflowAwake(
      final UserTaskProbeDockerAggregate aggregate,
      @TaskId final String taskId) {

    WHAT_HAPPENED.put(aggregate.getId()
        + "/keepTheWorkflowAwake", TaskEvent.Event.CREATED.name());

  }

}
