package io.vanillabp.camunda8.springboot.smoke;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * A minimal workflow service so a {@link ProcessService} bean is built and the adapter
 * wiring is exercised. No BPMN file is provided, so deployment never runs.
 */
@Service
@WorkflowService(workflowAggregateClass = Aggregate.class)
public class SampleWorkflowService {

  @Autowired
  private ProcessService<Aggregate> processService;

  @WorkflowTask
  public void doSomething() {
    // no-op; only used to trigger process-service wiring
  }

}
