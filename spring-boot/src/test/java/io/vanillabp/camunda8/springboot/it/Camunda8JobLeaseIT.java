package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An answer which arrives after somebody else took the activation over, against a real
 * cluster.
 * <p>
 * This is the case the lease exists for. When the lock of a job expires while the business
 * method is still running, the cluster hands the job out again, the method runs a second
 * time, and both runs answer. Without a lease the cluster takes the first answer, which
 * carries the values of the OLDER run. With a lease that answer is refused, and the job
 * stays with the activation holding it.
 * <p>
 * The second activation is the test's own rather than a redelivery, and not because a
 * redelivery is slow. Measured on 2026-09-21 against {@code camunda/camunda:8.10.0-alpha5},
 * one cluster, a lock of 30 seconds and a poll every 100 ms: a job whose lock expired is
 * handed out again about a second later, a listener job like any other. What does not happen
 * is a redelivery to the worker which is still holding the job. That one gets it back in the
 * moment its handler returns, and not before, over 120 seconds of waiting, with nothing in
 * the log to say so. Any other worker, on the same client too, got it after 0.27 to 1.4
 * seconds, so the worker name is not what decides it.
 * <p>
 * The handler of this test is that worker. So the second activation has to come from
 * somewhere else, and the test asks for the job the way a second pod would, lets the handler
 * answer into that, and reads what the adapter made of the refusal.
 * <p>
 * It runs on the 8.10 line and nowhere else, because no earlier client can ask for a lease.
 * The pull-request checks build the GA lines, so what proves this is the nightly matrix.
 * <p>
 * <b>The handler runs more than once here, and the test may not count the runs.</b> The lock of
 * this job has to expire, otherwise the test could not take the activation over. From
 * {@code 8.10.0-rc1} on the client asks for work again while a job of that worker is still in a
 * handler, which is the fix of SUPPORT-34723, so the expired job comes straight back to the
 * worker which is holding it, once per free execution slot. Measured on 2026-09-25 against
 * {@code camunda/camunda:8.10.0-rc1}: the blocked handler was entered four times with the same
 * job, the four being this module's execution slots. Holding the slots down to one does not help
 * and breaks the test instead: the client answers its own requests on the executor this adapter
 * hands it, so the one slot the blocked handler occupies also stops the activation below from
 * ever completing. What the test reads is therefore the refusal and the absence of a failure,
 * which is what it is about, and not how often the cluster offered the job.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@EnabledIf("theLineHasALease")
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8JobLeaseIT extends SpringBootTestOnTheSharedCluster {

  /**
   * The job type of the slow task as the cluster knows it: this module avoids name clashes
   * with a prefix.
   */
  private static final String JOB_TYPE = "test-app__LeasedProcess__slowTask";

  /**
   * Whether the client this build was compiled against can lease an activation. Read
   * before the container of this class is started, so a line without it pays nothing.
   *
   * @return Whether to run this class
   */
  static boolean theLineHasALease() {

    return Camunda8JobLease.supportedByThisLine();

  }

  @Autowired
  private LeaseDockerWorkflowService workflowService;

  @Autowired
  private LeaseDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;


  @Test
  @DisplayName("An answer from a superseded activation is refused, and the job stays with the newer one")
  public void theOlderAnswerIsRefusedAndTheJobStaysWithTheNewerActivation(
      final CapturedOutput output) throws Exception {

    LeaseDockerWorkflowService.RUNS.set(0);
    LeaseDockerWorkflowService.entered = new CountDownLatch(1);
    LeaseDockerWorkflowService.mayAnswer = new CountDownLatch(1);

    final var secondPod = aClientOfItsOwn();

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow(new LeaseDockerAggregate()).getId());

    assertTrue(
        LeaseDockerWorkflowService.entered.await(120, TimeUnit.SECONDS),
        "the handler was delivered and is inside the application");

    // the lock of that job is ten seconds, so it is over by the time this asks, and what
    // comes back is what a second pod would get: the same job with a token of its own
    final var takenOver = awaitTheJobAgain(secondPod);
    assertNotNull(
        Camunda8JobLease.tokenOf(takenOver),
        "the activation which holds the job now carries a token");

    LeaseDockerWorkflowService.mayAnswer.countDown();

    awaitUntil(
        () -> theRefusalOfJob(output, takenOver.getKey()),
        60000,
        "the adapter to be told that its answer to job "
            + takenOver.getKey()
            + " came too late");

    assertTrue(
        LeaseDockerWorkflowService.RUNS.get() >= 1,
        "the handler ran, and how often is the cluster's business once the lock expired");
    // a failure would have counted the job's retries down and could have raised an
    // incident over work which was done and is fine. Read over the whole class this
    // would also speak for every other test of it, so it reads this test alone
    assertFalse(
        logOf(output).contains("failing the job"),
        "the refused answer did not turn into a failure of the job");

    // and the activation which holds the job answers, which is what the workflow goes on with
    Camunda8JobLease
        .withToken(
            secondPod.newCompleteCommand(takenOver.getKey()),
            Camunda8JobLease.tokenOf(takenOver))
        .send()
        .join();

    awaitUntil(
        () -> endedAsOf(aggregateId) != null,
        120000,
        "the workflow to end, which only the accepted answer carries it to");

    secondPod.close();

  }

  /**
   * The client the second activation is sent with, built like an application which is not
   * this one.
   * <p>
   * It may not be the adapter's own, and that is the whole reason this method exists. The
   * adapter hands its client an executor whose handling half is as wide as
   * <code>worker-threads</code>, and the client answers its own requests on it. This test
   * blocks a handler on purpose, and from {@code 8.10.0-rc1} on the cluster hands the expired
   * job straight back to the same worker, once per free slot, so every slot ends up holding a
   * blocked run of it. A request sent with the adapter's client then has nobody left to
   * complete it and dies of its socket timeout, whatever that timeout is: measured on
   * 2026-09-25, 3000 ms with a window of two seconds and 6000 ms with the module's five.
   * A client of its own has an executor of its own and is not affected.
   *
   * @return A client of this test's own, closed by the test
   */
  private CamundaClient aClientOfItsOwn() {

    return CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(restAddress()))
        .grpcAddress(URI.create(grpcAddress()))
        .build();

  }

  /**
   * Asks for the job the way a second pod would, until the cluster hands it out.
   *
   * @param secondPod The client of this test, which is not the adapter's
   * @return The activation which now holds the job
   * @throws Exception Where the wait is interrupted
   */
  private ActivatedJob awaitTheJobAgain(
      final CamundaClient secondPod) throws Exception {

    final var deadline = System.currentTimeMillis() + 120000;
    while (System.currentTimeMillis() < deadline) {
      final List<ActivatedJob> jobs = Camunda8JobLease
          .leaseTheActivation(
              secondPod
                  .newActivateJobsCommand()
                  .jobType(JOB_TYPE)
                  .maxJobsToActivate(1)
                  .timeout(Duration.ofMinutes(5))
                  .workerName("lease-verification"))
          .send()
          .join()
          .getJobs();
      if (!jobs.isEmpty()) {
        return jobs.getFirst();
      }
      TimeUnit.MILLISECONDS.sleep(500);
    }
    throw new AssertionError("the job of type '%s' was not handed out a second time".formatted(JOB_TYPE));

  }

  /**
   * What this test printed, and nothing of what ran before it. An assertion about a
   * sentence which is NOT in the log is only true for the test which makes it.
   */
  private static String logOf(
      final CapturedOutput output) {

    return output.getAllOfThisTest();

  }

  /**
   * Whether the adapter reported the refused answer OF THIS JOB. The sentence names the
   * job, and a wait which only looked for the refusal would be over as soon as any job of
   * this class was refused.
   *
   * @param output What the test printed so far
   * @param jobKey The job the answer belongs to
   * @return Whether that line is in the log
   */
  private static boolean theRefusalOfJob(
      final CapturedOutput output,
      final long jobKey) {

    final var thisJob = "of job "
        + jobKey;
    return logOf(output)
        .lines()
        .anyMatch(line -> line.contains(thisJob) && line.contains("another activation holds the job"));

  }

  private String endedAsOf(
      final Long aggregateId) {

    return transactionTemplate
        .execute(status -> repository.findById(aggregateId).map(LeaseDockerAggregate::getEndedAs).orElse(null));

  }

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final long timeoutMillis,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + timeoutMillis;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(200);
    }

  }

}
