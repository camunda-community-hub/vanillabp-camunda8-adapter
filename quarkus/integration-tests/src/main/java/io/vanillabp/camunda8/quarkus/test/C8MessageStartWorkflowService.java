package io.vanillabp.camunda8.quarkus.test;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the process which starts on the message 'C8OrderPlaced'. A
 * message passed to <code>startWorkflowByMessage</code> has to start the process of the
 * calling process service, so this process needs a process service, and with it a
 * workflow service, of its own.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = C8MessageStartAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "MessageStartProcess"))
public class C8MessageStartWorkflowService {

  @Inject
  ProcessService<C8MessageStartAggregate> processService;

  public C8MessageStartAggregate startWorkflowByMessage(
      final C8MessageStartAggregate aggregate,
      final String messageName) {

    return processService.startWorkflowByMessage(aggregate, messageName);

  }

  @WorkflowTask
  public void c8OrderPlaced(
      final C8MessageStartAggregate aggregate) {

    aggregate.setResults("order-placed");

  }

}
