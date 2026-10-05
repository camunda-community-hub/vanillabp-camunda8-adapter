package io.vanillabp.camunda8.springboot.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the process which starts on the message 'C8OrderPlaced'. A
 * message passed to <code>startWorkflowByMessage</code> has to start the process of the
 * calling process service, so this process needs a process service, and with it a
 * workflow service, of its own.
 */
@Service
@WorkflowService(
    workflowAggregateClass = MessageStartDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "MessageStartProcess"))
public class MessageStartDockerWorkflowService {

  private final ProcessService<MessageStartDockerAggregate> processService;

  public MessageStartDockerWorkflowService(
      final ProcessService<MessageStartDockerAggregate> processService) {

    this.processService = processService;

  }

  public MessageStartDockerAggregate startByMessage(
      final MessageStartDockerAggregate aggregate,
      final String messageName) {

    return processService.startWorkflowByMessage(aggregate, messageName);

  }

  @WorkflowTask
  public void c8OrderPlaced(
      final MessageStartDockerAggregate aggregate) {

    aggregate.setResults("order-placed");

  }

}
