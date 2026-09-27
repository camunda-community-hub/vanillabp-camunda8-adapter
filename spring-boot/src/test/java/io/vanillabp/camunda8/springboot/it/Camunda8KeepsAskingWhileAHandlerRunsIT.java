package io.vanillabp.camunda8.springboot.it;

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

import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A CANARY: it watches the Camunda client, not this adapter.
 * <p>
 * A worker has to keep asking the cluster while a job of its own is inside a handler. The
 * client used to schedule the next poll of a worker only once no job of that worker was in
 * a handler any more, so one long handler stopped the whole worker, and the jobs it was
 * subscribed to waited for their lock instead of for a free slot. Camunda answered it as
 * SUPPORT-34723 and shipped the fix in {@code 8.8.37}, {@code 8.9.18} and
 * {@code 8.10.0-rc1}, which is what the pins of this repository stand on.
 * <p>
 * Nothing here proved that before. {@link Camunda8PollWhenASlotIsFreeIT} drives the
 * opposite direction - a worker of an adapter whose every slot is busy must NOT fetch - and
 * a client which never asks again passes that one. So a client bump could undo the fix and
 * every build would stay green while an application on a real cluster went quiet.
 * <p>
 * <b>What the setup does.</b> Two execution slots, so one blocked handler leaves a slot
 * free and the adapter's own back pressure lets the worker ask. One job of
 * {@code blockingTask} goes into a handler which stays there. The test then waits out
 * {@code request-timeout}, so the activation request which was parked at the cluster when
 * the slot filled is over and has brought nothing back - that empty poll is the premise,
 * because a job which was already on its way would say nothing about the worker asking
 * again. Only then is the second workflow started, and its job is of the SAME type and
 * therefore of the SAME worker, which is where the defect sat.
 * <p>
 * The job timeout is two minutes here, far above the block, so a redelivery of the first
 * job cannot be mistaken for the second one arriving. And the latch the handler counts down
 * is counted only while the first delivery is still inside its slot, so a second job served
 * after the block would leave this test red rather than green.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8KeepsAskingWhileAHandlerRunsIT extends SpringBootTestOnTheSharedCluster {

  @DynamicPropertySource
  static void camunda8PropertiesOfTheAskingTest(
      final DynamicPropertyRegistry registry) {

    // two slots: the blocking handler holds one and leaves one, so nothing of this
    // adapter's own back pressure stops the worker from asking. With a single slot the
    // test would measure the back pressure instead of the client
    registry.add("vanillabp.adapters.c8.worker-threads", () -> "2");
    registry.add("vanillabp.adapters.c8.request-timeout", () -> "PT2S");
    // the blocking handler holds its job for as long as this test runs, and a redelivery
    // of that job would look exactly like the second job arriving
    registry.add("vanillabp.workflow-modules.test-app.adapters.c8.job-timeout", () -> "PT2M");

  }

  @Autowired
  private WorkerThreadsDockerWorkflowService blockingWorkflowService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  /**
   * How long the first handler holds its slot at most. The test lets it out itself once it
   * has its answer, so this is the cap and not the length. Below the two minutes a job is
   * locked for, so the handler is never inside a job whose lock ran out.
   */
  private static final long BLOCK_MILLIS = 90_000;

  /**
   * How long the activation request which was parked when the slot filled is given to run
   * out. Above the two seconds of {@code request-timeout}, so the poll which follows the
   * block really is a poll and not an answer to a request from before it.
   */
  private static final long UNTIL_A_PARKED_REQUEST_RAN_OUT = 3_000;

  /**
   * How long the second job is given to reach a handler. Generous, because what is measured
   * is whether the worker asks again at all and not how fast it does.
   */
  private static final long THE_SECOND_JOB_ARRIVES_WITHIN = 60;

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
  @DisplayName("a worker keeps asking the cluster while a job of its own is in a handler")
  public void theWorkerAsksAgainWhileItsOwnHandlerRuns() throws Exception {

    final var blocked = transactionTemplate
        .execute(status -> blockingWorkflowService.startBlocking().getId());
    assertNotNull(blocked);
    assertTrue(
        WorkerThreadsDockerWorkflowService.BLOCKING_ENTERED.await(60, TimeUnit.SECONDS),
        "the first job reached its handler, which is where the block begins");

    // the request which was parked at the cluster when the slot filled is answered by
    // whatever appears, and nothing has appeared yet. Waiting it out is what makes the
    // second job below an answer to a NEW request
    Thread.sleep(UNTIL_A_PARKED_REQUEST_RAN_OUT);

    final var second = transactionTemplate
        .execute(status -> blockingWorkflowService.startBlocking().getId());
    assertNotNull(second);

    final var servedAgain = WorkerThreadsDockerWorkflowService.BLOCKING_TASK_SERVED_AGAIN_WHILE_BLOCKED
        .await(THE_SECOND_JOB_ARRIVES_WITHIN, TimeUnit.SECONDS);

    // the slot is given back before anything is asserted, so a failing run does not leave
    // a handler sitting inside its cap for the class which comes next
    WorkerThreadsDockerWorkflowService.RELEASE_THE_SLOT.countDown();

    assertTrue(
        servedAgain,
        "The worker of 'blockingTask' stopped asking the cluster while a job of its own was in a "
            + "handler, so the second job of the same type waited "
            + THE_SECOND_JOB_ARRIVES_WITHIN
            + " seconds without being fetched although an execution slot was free the whole time. "
            + "That is news about the Camunda client rather than a defect of this repository: it is "
            + "SUPPORT-34723, fixed in 8.8.37, 8.9.18 and 8.10.0-rc1, and a client pin below one of "
            + "those - or a bump which reintroduced it - brings it back. Read which client this line "
            + "resolved (the 'camunda8.version.line-*' properties of the parent POM), report it to "
            + "Camunda under that ticket, and hold the pin until it is answered. Until then every "
            + "application on this adapter serves one job per worker at a time and the rest waits "
            + "for its lock.");

  }

}
