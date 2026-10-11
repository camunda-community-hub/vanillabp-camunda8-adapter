package io.vanillabp.camunda8.springboot.it;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * Workflow service bound to the BPMN process {@code TestProcess}, which is deployed to the
 * Camunda 8 cluster. With task wiring, the adapter's polling job worker serves the BPMN
 * service task through the {@code @WorkflowTask} method below.
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
   * Aggregate IDs which the handler saw. The integration test reads them.
   */
  public static final List<String> ACTIVATED_AGGREGATE_IDS = new CopyOnWriteArrayList<>();

  @WorkflowTask(taskDefinition = "test-job")
  public void testJob(
      final DockerAggregate aggregate) {

    ACTIVATED_AGGREGATE_IDS.add(String.valueOf(aggregate.getId()));

  }

}
