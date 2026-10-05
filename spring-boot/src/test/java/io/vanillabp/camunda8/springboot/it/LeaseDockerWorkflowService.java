package io.vanillabp.camunda8.springboot.it;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * A handler which outlives the lock of its job, so somebody else can take the activation
 * away from it while it is still working.
 * <p>
 * The handler holds until the test says it may finish. That is what makes the test about
 * the lease rather than about timing: the test takes the job over first and lets the
 * handler answer afterwards, so the answer of this run is the one which arrives while
 * another activation holds the job.
 */
@Service
@WorkflowService(
    workflowAggregateClass = LeaseDockerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "LeasedProcess"))
public class LeaseDockerWorkflowService {

  /**
   * How long the handler waits to be released. It is a bound and not a delay: the wait
   * ends the moment the test has taken the activation over.
   */
  private static final long WAIT_TO_BE_RELEASED_SECONDS = 120;

  /**
   * How often the handler ran, counted across the deliveries of the one job this test
   * produces.
   */
  public static final AtomicInteger RUNS = new AtomicInteger();

  /**
   * Counted down when the handler is inside the application, which is what the test waits
   * for before it takes the job over.
   */
  public static volatile CountDownLatch entered = new CountDownLatch(1);

  /**
   * Counted down by the test once it holds the activation, which lets the handler answer.
   */
  public static volatile CountDownLatch mayAnswer = new CountDownLatch(1);

  private final ProcessService<LeaseDockerAggregate> processService;

  public LeaseDockerWorkflowService(
      final ProcessService<LeaseDockerAggregate> processService) {

    this.processService = processService;

  }

  public LeaseDockerAggregate startWorkflow(
      final LeaseDockerAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask(taskDefinition = "slowTask")
  public void slowTask(
      final LeaseDockerAggregate aggregate) throws Exception {

    final var run = RUNS.incrementAndGet();
    entered.countDown();
    mayAnswer.await(WAIT_TO_BE_RELEASED_SECONDS, TimeUnit.SECONDS);
    aggregate.setWrittenBy("run-"
        + run);

  }

  @WorkflowEnded
  public void workflowEnded(
      final LeaseDockerAggregate aggregate,
      final WorkflowEnd end) {

    aggregate.setEndedAs(String.valueOf(end.kind()));

  }

}
