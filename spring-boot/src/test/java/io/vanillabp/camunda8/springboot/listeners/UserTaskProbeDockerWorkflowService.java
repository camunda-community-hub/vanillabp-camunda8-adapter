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
 * The workflow service of the test which probes user tasks: three user tasks which differ
 * only in the <code>updating</code> listener they carry, and one asynchronous task whose
 * redelivery is the wake-up the check rides in on.
 */
@Service
@WorkflowService(
    workflowAggregateClass = UserTaskProbeDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "UserTaskProbeProcess"))
public class UserTaskProbeDockerWorkflowService {

  /**
   * What the application heard about each user task, keyed by "aggregate id/form reference" -
   * read by the test rather than the aggregate, so a notification arriving after the
   * aggregate was read is still seen.
   */
  public static final Map<String, String> WHAT_HAPPENED = new ConcurrentHashMap<>();

  /**
   * The key of each user task, under the same keys as {@link #WHAT_HAPPENED}. It is kept
   * here rather than in the aggregate for the reason written on
   * {@link UserTaskProbeDockerAggregate}.
   */
  public static final Map<String, String> TASK_IDS = new ConcurrentHashMap<>();

  /**
   * How often the method serving the modelled <code>updating</code> listener ran. It has to
   * stay at zero: the only update anybody sends here is the probe of this adapter, and a probe
   * is not something an application method has anything to say about.
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
   * The modelled <code>updating</code> listener of the served user task. The probe fires it
   * and the adapter closes its job without coming here, which is what the counter holds.
   */
  @WorkflowTask(taskDefinition = "watchTheServedUserTask")
  public void watchTheServedUserTask(
      final UserTaskProbeDockerAggregate aggregate) {

    UPDATES_THE_APPLICATION_SAW.incrementAndGet();

  }

  /**
   * The asynchronous task which stays open. Its lock runs out while the test waits, the
   * cluster hands its job to the worker again, and that redelivery is the wake-up which looks
   * at the user tasks.
   */
  @WorkflowTask(taskDefinition = "keepTheWorkflowAwake")
  public void keepTheWorkflowAwake(
      final UserTaskProbeDockerAggregate aggregate,
      @TaskId final String taskId) {

    WHAT_HAPPENED.put(aggregate.getId()
        + "/keepTheWorkflowAwake", TaskEvent.Event.CREATED.name());

  }

}
