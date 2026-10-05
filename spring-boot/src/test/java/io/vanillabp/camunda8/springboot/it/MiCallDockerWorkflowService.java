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
 * The workflow service of the decomposition test. Three BPMN processes on one workflow
 * aggregate, which is what a call activity is for here: the models are split, the business
 * case is not. Each task writes down what it was told about the iteration it runs in, and
 * every one of those iterations belongs to a process further out.
 */
@Service
@WorkflowService(
    workflowAggregateClass = MiCallDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "MiCallProcess"),
    secondaryBpmnProcesses = {
        @BpmnProcess(bpmnProcessId = "MiCalledProcess"), @BpmnProcess(bpmnProcessId = "MiGrandChildProcess")
    })
public class MiCallDockerWorkflowService {

  private final ProcessService<MiCallDockerAggregate> processService;

  public MiCallDockerWorkflowService(
      final ProcessService<MiCallDockerAggregate> processService) {

    this.processService = processService;

  }

  public MiCallDockerAggregate startWorkflow() {

    return processService.startWorkflow(new MiCallDockerAggregate());

  }

  @WorkflowTask
  public void collectInCalledProcess(
      final MiCallDockerAggregate aggregate,
      @MultiInstanceElement("MIC_PerGroup") final String group,
      @MultiInstanceIndex("MIC_PerGroup") final int groupIndex,
      @MultiInstanceTotal("MIC_PerGroup") final int groupTotal) {

    aggregate
        .setInCalledProcess(
            append(aggregate.getInCalledProcess(), "%s#%d/%d".formatted(group, groupIndex, groupTotal)));

  }

  @WorkflowTask
  public void collectBothChains(
      final MiCallDockerAggregate aggregate,
      @MultiInstanceElement("MIC_PerGroup") final String group,
      @MultiInstanceIndex("MIC_PerGroup") final int groupIndex,
      @MultiInstanceTotal("MIC_PerGroup") final int groupTotal,
      @MultiInstanceElement("MIC_ChildMiTask") final String item,
      @MultiInstanceIndex("MIC_ChildMiTask") final int index,
      @MultiInstanceTotal("MIC_ChildMiTask") final int total,
      @MultiInstanceElement(resolverBean = MiCallChainResolver.class) final String chainOrder) {

    aggregate
        .setBothChains(
            append(
                aggregate.getBothChains(),
                "%s#%d/%d-%s#%d/%d".formatted(group, groupIndex, groupTotal, item, index, total)));
    aggregate.setChainOrder(chainOrder);

  }

  @WorkflowTask
  public void collectTwoLevelsDown(
      final MiCallDockerAggregate aggregate,
      @MultiInstanceElement("MIC_PerGroup") final String group,
      @MultiInstanceIndex("MIC_PerGroup") final int groupIndex,
      @MultiInstanceTotal("MIC_PerGroup") final int groupTotal) {

    aggregate
        .setTwoLevelsDown(
            append(aggregate.getTwoLevelsDown(), "%s#%d/%d".formatted(group, groupIndex, groupTotal)));

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
