package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vanillabp.camunda8.observability.Camunda8Metrics;
import io.vanillabp.camunda8.observability.MicrometerCamunda8Metrics;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The designed back pressure against a real cluster. A worker of an adapter whose every
 * execution slot is busy does not ask the cluster for work. So a job which nobody could run
 * is left where it is. The client's own activation counter gives the answer, because it
 * counts a job before VanillaBP sees anything of it.
 * <p>
 * The setup is the one with which the starvation was measured, with the question turned
 * around: one blocking handler, a second worker, and the question whether a job is fetched
 * which nobody can run. There is one execution slot here, so the blocking handler occupies
 * all of them.
 * <p>
 * Two settings make the measurement say what it should. The block lasts long enough to
 * outlive an activation request which was already parked at the cluster when the slot
 * filled - such a request is answered whenever a job appears, and no gate on this side
 * reaches it. And <code>request-timeout</code> is two seconds, so that parked request is
 * over before the workflow of the second worker is started. The module configures the same
 * two seconds, and this class says it again where it is read as a premise rather than as a
 * setting somebody may change. What is measured is therefore the rule itself: a worker which
 * has to ask again waits for a slot.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8PollWhenASlotIsFreeIT extends SpringBootTestOnTheSharedCluster {

  @DynamicPropertySource
  static void camunda8Properties(
      final DynamicPropertyRegistry registry) {

    registry.add("vanillabp.adapters.c8.worker-threads", () -> "1");
    registry.add("vanillabp.adapters.c8.request-timeout", () -> "PT2S");
    // the blocking handler holds the only slot for as long as this test reads from the
    // counters, and it holds its job with it. Two minutes, not the twenty seconds which this
    // workflow module configures elsewhere. So a machine which stops this JVM for seconds
    // cannot stretch the block into an expired lock
    registry.add("vanillabp.workflow-modules.test-app.adapters.c8.job-timeout", () -> "PT2M");

  }

  @Autowired
  private WorkerThreadsDockerWorkflowService blockingWorkflowService;

  @Autowired
  private QuickDockerWorkflowService quickWorkflowService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private MicrometerCamunda8Metrics metrics;

  /**
   * How long the only execution slot may stay busy here. The test ends the block itself once
   * it has read the counters. So this is the upper limit and not the length. A machine which
   * leaves this JVM without a turn makes the block longer, not shorter. And a block which
   * ended early would look exactly like a worker which fetched a job it could not run.
   * <p>
   * It is below the two minutes for which this test locks a job. So the handler is never
   * inside a job whose lock ran out.
   */
  private static final long BLOCK_MILLIS = 90_000;

  /**
   * How long the parked activation request of the quick worker is given to run out. It is
   * above the two seconds of <code>request-timeout</code>. That is how long such a request
   * can outlive the moment the slot filled.
   * <p>
   * This is a safety margin, not a budget. A stopped JVM makes the wait longer, and the test
   * reads everything afterwards while the slot is still busy.
   */
  private static final long UNTIL_A_PARKED_REQUEST_RAN_OUT = 3_000;

  /**
   * How long the start of the second workflow is given to reach the cluster before the
   * counter is asked. Four seconds is eight of the half-second windows in which the outbox
   * dispatches here.
   * <p>
   * This is a safety margin as well. The end of this test reads that the job really is at
   * the cluster: the same counter goes up once the slot is free again.
   */
  private static final long UNTIL_THE_START_REACHED_THE_CLUSTER = 4_000;

  @BeforeEach
  public void resetObservations() {

    WorkerThreadsDockerWorkflowService.reset();
    WorkerThreadsDockerWorkflowService.blockFor(BLOCK_MILLIS);

  }

  @AfterEach
  public void resetObservationsForWhoeverComesNext() {

    WorkerThreadsDockerWorkflowService.reset();

  }

  @Test
  @DisplayName("a worker which has to ask again does not, while the only execution slot is busy")
  public void noJobIsFetchedWhileTheOnlySlotIsBusy() throws Exception {

    // in a real application, Actuator applies MeterBinder beans. This application has no
    // Actuator, so the meters of this adapter are bound by hand
    final var registry = new SimpleMeterRegistry();
    metrics.bindTo(registry);

    final var blocked = transactionTemplate
        .execute(status -> blockingWorkflowService.startBlocking().getId());
    assertNotNull(blocked);
    assertTrue(
        WorkerThreadsDockerWorkflowService.BLOCKING_ENTERED.await(30, TimeUnit.SECONDS),
        "the blocking handler was delivered");

    // the activation request which the quick worker had parked at the cluster when the slot
    // filled runs out within request-timeout. Everything it asks afterwards waits for a slot
    Thread.sleep(UNTIL_A_PARKED_REQUEST_RAN_OUT);

    final var quick = transactionTemplate
        .execute(status -> quickWorkflowService.startWorkflow().getId());
    assertNotNull(quick);

    Thread.sleep(UNTIL_THE_START_REACHED_THE_CLUSTER);

    // the counters below say something about back pressure only while the slot really is
    // busy. So the test reads that first and does not assume it
    assertTrue(
        WorkerThreadsDockerWorkflowService.isBlocking(),
        "the blocking handler is still inside its slot");
    assertTrue(
        activated(registry, "blockingTask") >= 1.0,
        "the measurement works: the job the blocking handler is inside was activated");
    assertEquals(
        0.0,
        activated(registry, "quickTask"),
        "but no job is fetched for a worker of an adapter whose every slot is busy");

    // the reading is done. So the slot is given back now, and not at the end of a window
    // somebody guessed
    WorkerThreadsDockerWorkflowService.RELEASE_THE_SLOT.countDown();

    assertTrue(
        WorkerThreadsDockerWorkflowService.QUICK_SERVED.await(30, TimeUnit.SECONDS),
        "and the job is served once the blocking handler gave its slot back");
    assertTrue(
        activated(registry, "quickTask") >= 1.0,
        "which is when it was activated");

  }

  /**
   * What the client counted for the worker of one task definition. The tag carries the job
   * type which the WORKER subscribes to. Where name-clash avoidance prefixes it, that is the
   * scoped one.
   *
   * @param registry Where the adapter's meters were bound
   * @param taskDefinition The task definition the worker serves
   * @return How many jobs the client activated for it
   */
  private static double activated(
      final MeterRegistry registry,
      final String taskDefinition) {

    return registry
        .find(Camunda8Metrics.JOBS_ACTIVATED)
        .counters()
        .stream()
        .filter(counter -> counter
            .getId()
            .getTag(Camunda8Metrics.TAG_JOB_TYPE)
            .endsWith(taskDefinition))
        .mapToDouble(Counter::count)
        .sum();

  }

}
