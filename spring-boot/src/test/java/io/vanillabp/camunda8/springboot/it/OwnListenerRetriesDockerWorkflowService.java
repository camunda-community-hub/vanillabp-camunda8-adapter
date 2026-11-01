package io.vanillabp.camunda8.springboot.it;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;

/**
 * The workflow service of the test about the retries of the listeners VanillaBP writes into a
 * model. Its process has no task at all. So the start listener and the end listener are the
 * only jobs it creates.
 * <p>
 * Both methods count their calls, and both fail while the test asks them to. That is how the
 * test sees how often the cluster hands out a listener job which failed.
 */
@Service
@WorkflowService(
    workflowAggregateClass = OwnListenerRetriesDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "OwnListenerRetriesProcess"))
public class OwnListenerRetriesDockerWorkflowService {

  /**
   * Whether building the workflow aggregate of a start by the cluster fails.
   */
  public static final AtomicBoolean THE_START_FAILS = new AtomicBoolean();

  /**
   * How often the application was asked to build a workflow aggregate.
   */
  public static final AtomicInteger STARTS_ASKED_FOR = new AtomicInteger();

  /**
   * Whether the <code>&#64;WorkflowEnded</code> method fails.
   */
  public static final AtomicBoolean THE_END_FAILS = new AtomicBoolean();

  /**
   * How often the application was told that a workflow ended.
   */
  public static final AtomicInteger ENDS_REPORTED = new AtomicInteger();

  private final ProcessService<OwnListenerRetriesDockerAggregate> processService;

  public OwnListenerRetriesDockerWorkflowService(
      final ProcessService<OwnListenerRetriesDockerAggregate> processService) {

    this.processService = processService;

  }

  public OwnListenerRetriesDockerAggregate startWorkflow() {

    final var aggregate = new OwnListenerRetriesDockerAggregate();
    aggregate.setId("started-by-the-application-"
        + UUID.randomUUID());
    return processService.startWorkflow(aggregate);

  }

  /**
   * Builds the workflow aggregate of a workflow somebody started without VanillaBP.
   *
   * @param trigger What the cluster fired
   * @return The workflow aggregate of the started workflow
   */
  @WorkflowStartedByBpms
  public OwnListenerRetriesDockerAggregate aggregateOfAStartByTheCluster(
      final BpmsStartTrigger trigger) {

    STARTS_ASKED_FOR.incrementAndGet();
    if (THE_START_FAILS.get()) {
      throw new IllegalStateException("the test lets building the workflow aggregate fail");
    }
    final var aggregate = new OwnListenerRetriesDockerAggregate();
    aggregate.setId("started-by-the-cluster-"
        + UUID.randomUUID());
    return aggregate;

  }

  @WorkflowEnded
  public void workflowEnded(
      final OwnListenerRetriesDockerAggregate aggregate,
      final WorkflowEnd end) {

    ENDS_REPORTED.incrementAndGet();
    if (THE_END_FAILS.get()) {
      throw new IllegalStateException("the test lets the @WorkflowEnded method fail");
    }
    aggregate.setEndedAs(String.valueOf(end.kind()));

  }

}
