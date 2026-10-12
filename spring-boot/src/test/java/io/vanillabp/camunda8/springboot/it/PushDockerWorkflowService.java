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
 * The workflow service of the aggregateChanged integration test. Every process parks in an
 * asynchronous task. So the test can push into the workflow's scope, into the scope of ONE
 * instance of a multi-instance activity, and into the scope of a subprocess whose task the
 * read model does not know yet. Two more processes park in a user task: directly in the
 * process, and in each iteration of a multi-instance subprocess. They exist because the id
 * of a user task is no job key.
 */
@Service
@WorkflowService(
    workflowAggregateClass = PushDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "AggregateChangedProcess"),
    secondaryBpmnProcesses = {
        @BpmnProcess(bpmnProcessId = "AggregateChangedMultiInstanceProcess"), @BpmnProcess(
            bpmnProcessId = "AggregateChangedAfterTimerProcess"), @BpmnProcess(
                bpmnProcessId = "AggregateChangedUserTaskProcess"), @BpmnProcess(
                    bpmnProcessId = "AggregateChangedUserTaskMultiInstanceProcess")
    })
public class PushDockerWorkflowService {

  /**
   * The user tasks created so far, comma-separated per aggregate id. They are kept here and
   * not in the aggregate. The two iterations of the multi-instance process are notified at
   * the same time. And a failed notification of a user task is an incident, not a retry.
   */
  public static final Map<Long, String> USER_TASK_IDS = new ConcurrentHashMap<>();

  private final ProcessService<PushDockerAggregate> processService;

  private final PushDockerAggregateRepository repository;

  public PushDockerWorkflowService(
      final ProcessService<PushDockerAggregate> processService,
      final PushDockerAggregateRepository repository) {

    this.processService = processService;
    this.repository = repository;

  }

  public PushDockerAggregate startWorkflow() {

    final var aggregate = new PushDockerAggregate();
    aggregate.setNote("before");
    return processService.startWorkflow(aggregate);

  }

  /**
   * Saves an aggregate without starting a workflow. The test starts the multi-instance
   * process directly at the cluster (the injectable process service starts only the primary
   * process).
   *
   * @return The saved aggregate
   */
  public PushDockerAggregate saveAggregate() {

    final var aggregate = new PushDockerAggregate();
    aggregate.setNote("before");
    return repository.save(aggregate);

  }

  /**
   * Changes the aggregate and pushes it at the workflow's global scope.
   *
   * @param aggregateId The aggregate's id
   * @param note The new note
   */
  public void pushGlobally(
      final Long aggregateId,
      final String note) {

    final var aggregate = repository.findById(aggregateId).orElseThrow();
    aggregate.setNote(note);
    processService.aggregateChanged(aggregate);

  }

  /**
   * Changes the aggregate and pushes it into the scope of ONE task instance.
   *
   * @param aggregateId The aggregate's id
   * @param note The new note
   * @param taskId The task whose scope receives the values
   */
  public void pushInto(
      final Long aggregateId,
      final String note,
      final String taskId) {

    final var aggregate = repository.findById(aggregateId).orElseThrow();
    aggregate.setNote(note);
    processService.aggregateChanged(aggregate, taskId);

  }

  /**
   * Completes the parked task of the PRIMARY process. {@code Camunda8TaskProcessingIT} needs
   * a primary process for this: a stale completion has to raise the same exception there as
   * on a secondary one.
   *
   * @param aggregateId The aggregate's id
   * @param taskId The parked task
   */
  public void completeAwaitPush(
      final Long aggregateId,
      final String taskId) {

    processService.completeTask(repository.findById(aggregateId).orElseThrow(), taskId);

  }

  @WorkflowTask(taskDefinition = "awaitPush")
  public void awaitPush(
      final PushDockerAggregate aggregate,
      @TaskId final String taskId) {

    // parks the workflow. The instance stays in the cluster
    aggregate.setTaskIds(taskId);

  }

  @WorkflowTask(taskDefinition = "awaitPushPerInstance")
  public void awaitPushPerInstance(
      final PushDockerAggregate aggregate,
      @TaskId final String taskId) {

    aggregate
        .setTaskIds(
            aggregate.getTaskIds() == null
                ? taskId
                : aggregate.getTaskIds()
                    + ","
                    + taskId);

  }

  @WorkflowTask(taskDefinition = "awaitPushIntoUserTask")
  public void awaitPushIntoUserTask(
      final PushDockerAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent final TaskEvent.Event event) {

    rememberTheUserTask(aggregate, taskId, event);

  }

  @WorkflowTask(taskDefinition = "awaitPushIntoUserTaskPerInstance")
  public void awaitPushIntoUserTaskPerInstance(
      final PushDockerAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent final TaskEvent.Event event) {

    rememberTheUserTask(aggregate, taskId, event);

  }

  private static void rememberTheUserTask(
      final PushDockerAggregate aggregate,
      final String taskId,
      final TaskEvent.Event event) {

    if (event != TaskEvent.Event.CREATED) {
      return;
    }
    USER_TASK_IDS
        .merge(
            aggregate.getId(),
            taskId,
            (
                known,
                added) -> known
                    + ","
                    + added);

  }

}
