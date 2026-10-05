package io.vanillabp.camunda8.springboot.it;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * Workflow service bound to the BPMN process {@code TestProcess} deployed to the Camunda 8
 * cluster. Since task wiring the BPMN service task is served by the
 * {@code @WorkflowTask} method below through the adapter's polling job worker.
 */
@Service
@WorkflowService(
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TestProcess"),
    workflowAggregateClass = DockerAggregate.class)
public class DockerWorkflowService {

  private final ProcessService<DockerAggregate> processService;

  public DockerWorkflowService(
      final ProcessService<DockerAggregate> processService) {

    this.processService = processService;

  }

  public DockerAggregate startWorkflow(
      final DockerAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  /**
   * Aggregate IDs seen by the handler - inspected by the integration test.
   */
  public static final List<String> ACTIVATED_AGGREGATE_IDS = new CopyOnWriteArrayList<>();

  @WorkflowTask(taskDefinition = "test-job")
  public void testJob(
      final DockerAggregate aggregate) {

    ACTIVATED_AGGREGATE_IDS.add(String.valueOf(aggregate.getId()));

  }

}
