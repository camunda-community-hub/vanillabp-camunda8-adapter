package io.vanillabp.camunda8.analysis895;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service. Every handler writes one row into {@code A895_EFFECT} in the
 * transaction VanillaBP opens for it, so a handler which ran twice with effect shows up as
 * two rows of the same effect, and one which never ran as none.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ReadModelAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ReadModelProcess"))
public class ReadModelWorkflow {

  private final ProcessService<ReadModelAggregate> processService;

  private final JdbcTemplate jdbc;

  /**
   * @param processService The process service of the workflow
   * @param jdbc Where the effects are written
   */
  public ReadModelWorkflow(
      final ProcessService<ReadModelAggregate> processService,
      final JdbcTemplate jdbc) {

    this.processService = processService;
    this.jdbc = jdbc;

  }

  /**
   * @return The process service, for the driver
   */
  public ProcessService<ReadModelAggregate> processService() {

    return processService;

  }

  private void effect(
      final ReadModelAggregate aggregate,
      final String effect,
      final String taskId) {

    jdbc
        .update(
            "INSERT INTO A895_EFFECT (AGGREGATE_ID, EFFECT, TASK_ID, AT_MILLIS) VALUES (?, ?, ?, ?)",
            aggregate.getId(),
            effect,
            taskId,
            System.currentTimeMillis());

  }

  /**
   * A service task which completes at once. No task id is asked for, because a method
   * asking for one leaves its task open.
   *
   * @param aggregate The aggregate
   */
  @WorkflowTask(taskDefinition = "work")
  public void work(
      final ReadModelAggregate aggregate) {

    effect(aggregate, "work", null);

  }

  /**
   * A service task which stays open until the driver completes it.
   *
   * @param aggregate The aggregate
   * @param taskId The task
   */
  @WorkflowTask(taskDefinition = "check")
  public void check(
      final ReadModelAggregate aggregate,
      @TaskId final String taskId) {

    effect(aggregate, "check", taskId);
    aggregate.setCheckTaskId(taskId);

  }

  /**
   * The notifications of the user task.
   *
   * @param aggregate The aggregate
   * @param taskId The user task
   * @param event What happened to it
   */
  @WorkflowTask(taskDefinition = "approve")
  public void approve(
      final ReadModelAggregate aggregate,
      @TaskId final String taskId,
      @io.vanillabp.spi.service.TaskEvent final TaskEvent.Event event) {

    effect(aggregate, "approve-"
        + event.name().toLowerCase(), taskId);
    if (event == TaskEvent.Event.CREATED) {
      aggregate.setApproveTaskId(taskId);
    }

  }

  /**
   * The last service task, completed at once like the first one.
   *
   * @param aggregate The aggregate
   */
  @WorkflowTask(taskDefinition = "finish")
  public void finish(
      final ReadModelAggregate aggregate) {

    effect(aggregate, "finish", null);

  }

  /**
   * The end of the workflow.
   *
   * @param aggregate The aggregate
   * @param end How it ended
   */
  @WorkflowEnded
  public void ended(
      final ReadModelAggregate aggregate,
      final WorkflowEnd end) {

    effect(aggregate, "ended-"
        + end.kind().name().toLowerCase(), null);
    aggregate.setEndedAs(end.kind().name());

  }

}
