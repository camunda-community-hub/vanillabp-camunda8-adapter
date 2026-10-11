package io.vanillabp.camunda8.springboot.listeners;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the test which completes a user task the cluster is still creating.
 * The model has one user task with a second <code>creating</code> listener which nobody
 * answers. Behind it is one service task, which runs only once the completion arrived.
 */
@Service
@WorkflowService(
    workflowAggregateClass = StillCreatingDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "UserTaskStillCreatingProcess"))
public class StillCreatingDockerWorkflowService {

  /**
   * The key of the user task, by aggregate id. The notification writes it. The notification
   * comes from the <code>creating</code> listener which VanillaBP wrote into the model. So the
   * application holds the key while the cluster is still creating the task.
   */
  public static final Map<Long, String> TASK_IDS = new ConcurrentHashMap<>();

  /**
   * The aggregates whose workflow moved past the user task. This shows that the completion
   * reached the cluster.
   */
  public static final Map<Long, String> WHAT_CAME_AFTER = new ConcurrentHashMap<>();

  @Autowired
  private ProcessService<StillCreatingDockerAggregate> processService;

  public StillCreatingDockerAggregate startWorkflow() {

    return processService.startWorkflow(new StillCreatingDockerAggregate());

  }

  public StillCreatingDockerAggregate completeTheUserTask(
      final StillCreatingDockerAggregate aggregate,
      final String taskId) {

    return processService.completeUserTask(aggregate, taskId);

  }

  @WorkflowTask(taskDefinition = "theTaskTheClusterIsStillCreating")
  public void theTaskTheClusterIsStillCreating(
      final StillCreatingDockerAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent final TaskEvent.Event event) {

    if (event == TaskEvent.Event.CREATED) {
      TASK_IDS.put(aggregate.getId(), taskId);
    }

  }

  @WorkflowTask(taskDefinition = "theTaskAfterTheUserTask")
  public void theTaskAfterTheUserTask(
      final StillCreatingDockerAggregate aggregate) {

    WHAT_CAME_AFTER.put(aggregate.getId(), "the user task was completed");

  }

}
