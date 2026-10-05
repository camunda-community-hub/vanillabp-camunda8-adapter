package io.vanillabp.camunda8.quarkus.nativeimage;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow of the native-image application: one service task, which is enough to
 * need everything a native image lacks by default - the BPMN is parsed, modified,
 * serialized, deployed, and the job comes back from the cluster into a handler.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = NativeImageAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "NativeProcess"))
public class NativeImageWorkflowService {

  /**
   * What the handler writes into the aggregate, and what the application's main waits for:
   * a job which never arrives must not end in a green run.
   */
  public static final String SERVED = "served";

  @Inject
  ProcessService<NativeImageAggregate> processService;

  public NativeImageAggregate startWorkflow() {

    return processService.startWorkflow(new NativeImageAggregate());

  }

  @WorkflowTask(taskDefinition = "nativeTask")
  public void nativeTask(
      final NativeImageAggregate aggregate) {

    aggregate.setStatus(SERVED);

  }

}
