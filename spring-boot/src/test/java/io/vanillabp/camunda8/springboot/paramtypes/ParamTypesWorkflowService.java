package io.vanillabp.camunda8.springboot.paramtypes;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the parameter-types integration test. One handler per branch of
 * the model, each of them declaring the parameter type whose answer is asserted.
 * <p>
 * Five of the nine branches are meant to run, and four are meant to fail before the
 * handler is entered. The four are written all the same, because a handler which IS
 * entered has to be visible: the test reads {@link #received()} for the five and asserts
 * that the other four left nothing behind.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ParamTypesAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ParamTypesProcess"))
public class ParamTypesWorkflowService {

  private final ProcessService<ParamTypesAggregate> processService;

  /**
   * What each handler was given, by the name of its task definition. The map is written
   * from job-worker threads and read by the test.
   */
  private final Map<String, Object> received = new ConcurrentHashMap<>();

  public ParamTypesWorkflowService(
      final ProcessService<ParamTypesAggregate> processService) {

    this.processService = processService;

  }

  public Map<String, Object> received() {

    return received;

  }

  public ParamTypesAggregate startWorkflow() {

    final var aggregate = new ParamTypesAggregate();
    aggregate.fillWithTheSample();
    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void totalToDouble(
      final ParamTypesAggregate aggregate,
      @TaskParam("total") final Double total) {

    received.put("totalToDouble", total);

  }

  @WorkflowTask
  public void totalToBigDecimal(
      final ParamTypesAggregate aggregate,
      @TaskParam("total") final BigDecimal total) {

    received.put("totalToBigDecimal", total);

  }

  @WorkflowTask
  public void totalToInt(
      final ParamTypesAggregate aggregate,
      @TaskParam("total") final int total) {

    received.put("totalToInt", total);

  }

  @WorkflowTask
  public void rateToDouble(
      final ParamTypesAggregate aggregate,
      @TaskParam("rate") final Double rate) {

    received.put("rateToDouble", rate);

  }

  @WorkflowTask
  public void countToLong(
      final ParamTypesAggregate aggregate,
      @TaskParam("count") final long count) {

    received.put("countToLong", count);

  }

  @WorkflowTask
  public void countToInt(
      final ParamTypesAggregate aggregate,
      @TaskParam("count") final int count) {

    received.put("countToInt", count);

  }

  @WorkflowTask
  public void hugeToLong(
      final ParamTypesAggregate aggregate,
      @TaskParam("huge") final long huge) {

    received.put("hugeToLong", huge);

  }

  @WorkflowTask
  public void hugeToDouble(
      final ParamTypesAggregate aggregate,
      @TaskParam("huge") final Double huge) {

    received.put("hugeToDouble", huge);

  }

  @WorkflowTask
  public void totalTextToInt(
      final ParamTypesAggregate aggregate,
      @TaskParam("totalText") final int totalText) {

    received.put("totalTextToInt", totalText);

  }

}
