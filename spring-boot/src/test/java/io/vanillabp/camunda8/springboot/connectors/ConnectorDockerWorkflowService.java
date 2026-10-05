package io.vanillabp.camunda8.springboot.connectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the connector integration test. It serves the task BEHIND the
 * connector element and nothing else: the connector's own job type belongs to a runtime
 * this project does not run, and there is deliberately no method for it.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ConnectorDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ConnectorProcess"))
public class ConnectorDockerWorkflowService {

  @Autowired
  private ProcessService<ConnectorDockerAggregate> processService;

  public ConnectorDockerAggregate startWorkflow() {

    return processService.startWorkflow(new ConnectorDockerAggregate());

  }

  @WorkflowTask(taskDefinition = "afterConnector")
  public void recordThatTheConnectorFinished(
      final ConnectorDockerAggregate aggregate) {

    aggregate.setPastTheConnector(true);

  }

}
