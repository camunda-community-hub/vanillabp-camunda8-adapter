package io.vanillabp.camunda8.springboot.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.MultiInstanceTotal;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the multi-instance integration test. Every iteration writes down
 * what the adapter handed it. So the test can assert the element, the index and the total.
 * That includes the outer iteration which a nested task runs in. Camunda 8 shadows that
 * part, and the adapter makes it readable again.
 */
@Service
@WorkflowService(
    workflowAggregateClass = MiDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "MultiInstanceProcess"))
public class MiDockerWorkflowService {

  private final ProcessService<MiDockerAggregate> processService;

  public MiDockerWorkflowService(
      final ProcessService<MiDockerAggregate> processService) {

    this.processService = processService;

  }

  public MiDockerAggregate startWorkflow() {

    return processService.startWorkflow(new MiDockerAggregate());

  }

  @WorkflowTask
  public void collectPerItem(
      final MiDockerAggregate aggregate,
      @MultiInstanceElement("MI_FlatTask") final String item,
      @MultiInstanceIndex("MI_FlatTask") final int index,
      @MultiInstanceTotal("MI_FlatTask") final int total) {

    aggregate
        .setFlat(append(aggregate.getFlat(), "%s#%d/%d".formatted(item, index, total)));

  }

  @WorkflowTask
  public void collectNested(
      final MiDockerAggregate aggregate,
      @MultiInstanceElement("MI_OuterSub") final String group,
      @MultiInstanceIndex("MI_OuterSub") final int groupIndex,
      @MultiInstanceTotal("MI_OuterSub") final int groupTotal,
      @MultiInstanceElement("MI_NestedTask") final String item,
      @MultiInstanceIndex("MI_NestedTask") final int index,
      @MultiInstanceTotal("MI_NestedTask") final int total) {

    aggregate
        .setNested(
            append(
                aggregate.getNested(),
                "%s#%d/%d-%s#%d/%d".formatted(group, groupIndex, groupTotal, item, index, total)));

  }

  private static String append(
      final String current,
      final String value) {

    return current == null
        ? value
        : current
            + ","
            + value;

  }

}
