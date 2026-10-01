package io.vanillabp.camunda8.springboot.it;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the test which cancels a running instance. Its one task stays
 * open, so the instance sits at a SERVICE task while it is canceled. That shape was what the
 * 8.10 alphas needed, because an instance holding a Camunda-managed user task could not be
 * canceled on them at all (camunda/camunda#58193). The bug is fixed and the shape is still a
 * service task.
 */
@Service
@WorkflowService(
    workflowAggregateClass = CanceledDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "CancelableProcess"))
public class CanceledDockerWorkflowService {

  /**
   * How the workflow of an aggregate ended, as the application was told, keyed by
   * aggregate id - read by the test next to the aggregate, so a notification which
   * arrives after the aggregate was read is still seen.
   */
  public static final Map<String, String> ENDED_AS = new ConcurrentHashMap<>();

  private final ProcessService<CanceledDockerAggregate> processService;

  public CanceledDockerWorkflowService(
      final ProcessService<CanceledDockerAggregate> processService) {

    this.processService = processService;

  }

  public CanceledDockerAggregate startWorkflow(
      final CanceledDockerAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  /**
   * An asynchronous task: it declares a <code>&#64;TaskId</code>, so the instance waits
   * here until somebody completes it - or until somebody cancels the instance.
   */
  @WorkflowTask(taskDefinition = "awaitCancelation")
  public void awaitCancelation(
      final CanceledDockerAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setOpenTaskId(taskId);

  }

  @WorkflowEnded
  public void workflowEnded(
      final CanceledDockerAggregate aggregate,
      final WorkflowEnd end) {

    aggregate.setEndedAs(String.valueOf(end.kind()));
    ENDED_AS.put(String.valueOf(aggregate.getId()), String.valueOf(end.kind()));

  }

}
