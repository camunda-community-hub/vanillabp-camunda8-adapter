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
import io.camunda.client.api.search.enums.JobState;
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An answer which arrives after somebody else took the activation over, against a real
 * cluster.
 * <p>
 * The lease exists for this case. The lock of a job expires while the business method is
 * still running. The cluster hands the job out again, the method runs a second time, and
 * both runs answer. Without a lease the cluster takes the first answer, which carries the
 * values of the OLDER run. With a lease that answer is refused, and the job stays with the
 * activation which holds it.
 * <p>
 * The second activation is the test's own, not a redelivery. The reason is not that a
 * redelivery is slow. Measured on 2026-09-21 against {@code camunda/camunda:8.10.0-alpha5},
 * one cluster, a lock of 30 seconds and a poll every 100 ms: a job whose lock expired is
 * handed out again about a second later, a listener job like any other. But there is no
 * redelivery to the worker which still holds the job. That worker gets it back in the moment
 * its handler returns, and not before. This held over 120 seconds of waiting, with nothing
 * in the log to say so. Any other worker, also one on the same client, got it after 0.27 to
 * 1.4 seconds. So the worker name does not decide it.
 * <p>
 * The handler of this test is that worker. So the second activation has to come from
 * somewhere else. The test asks for the job the way a second pod would. It lets the handler
 * answer after that, and it reads what the adapter made of the refusal.
 * <p>
 * It runs on the 8.10 line and nowhere else, because no earlier client can ask for a lease.
 * The pull-request checks build the GA lines, so what proves this is the nightly matrix.
 * <p>
 * <b>The handler runs more than once here, and the test may not count the runs.</b> The lock of
 * this job has to expire, otherwise the test could not take the activation over. From
 * {@code 8.10.0-rc1} on, the client asks for work again while a job of that worker is still in a
 * handler. That is the fix of SUPPORT-34723. So the expired job comes straight back to the
 * worker which holds it, once per free execution slot. Measured on 2026-09-25 against
 * {@code camunda/camunda:8.10.0-rc1}: the blocked handler was entered four times with the same
 * job. Four is the number of this module's execution slots. Holding the slots down to one does
 * not help. It breaks the test instead. The client answers its own requests on the executor
 * which this adapter hands it. So the one slot which the blocked handler occupies also stops the
 * activation below from ever completing. So the test reads the refusal and the absence of a
 * failure, which is what it is about. It does not read how often the cluster offered the job.
 * <p>
 * <b>The second activation is short, and the wait for it counts on that.</b> An activation
 * request is a long poll. The poll may end in the same moment as the job's lock runs out. Then
 * the cluster can activate the job for a request which has already been answered. The gateway
 * cannot deliver the job, and it gives the job back with a FAIL command. But that command
 * carries no lease token, so the cluster refuses it ("a matching lease token must be provided
 * because the job is currently leased"). The job then stays locked for nobody until that
 * activation runs out. Measured on 2026-10-06 against {@code camunda/camunda:8.10.0}: 1 run in
 * 23 met it. This class used to fail in that run, after 129 seconds. The activation was asked
 * for with a lock of five minutes, so the job was out of reach for longer than the test waits.
 * So the lock of the second activation is {@link #SECOND_ACTIVATION_LOCK}. That is long enough
 * for what the test does with the job. It is short enough that a lost activation costs one
 * lock and not the test. This is the cluster's defect, not the adapter's. The adapter meets it
 * as well, and its own job timeout is the cost. The README of this repository says so.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@EnabledIf("theLineHasALease")
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8JobLeaseIT extends SpringBootTestOnTheSharedCluster {

  /**
   * The job type of the slow task as the cluster knows it. This module avoids name clashes
   * with a prefix.
   */
  private static final String JOB_TYPE = "test-app__LeasedProcess__slowTask";

  /**
   * The lock of the test's own activation. What the test does while it holds the job takes
   * well under a second. An activation which the cluster loses blocks the job for exactly
   * this long, see the class comment.
   */
  private static final Duration SECOND_ACTIVATION_LOCK = Duration.ofSeconds(30);

  /**
   * How long the test asks for the job. The adapter's own worker can take the expired job
   * back up to three times before all four execution slots of this module hold a blocked run.
   * Each takeback costs the ten-second lock of the task. One activation which the cluster
   * loses costs {@link #SECOND_ACTIVATION_LOCK} on top. Two minutes cover both with time to
   * spare.
   */
  private static final Duration TIME_TO_TAKE_THE_JOB_OVER = Duration.ofMinutes(2);

  /**
   * Whether the client which this build was compiled against can lease an activation. It is
   * read before the container of this class starts, so a line without a lease pays nothing.
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

    // the lock of that job is ten seconds, so it runs out while the test asks. What comes
    // back is what a second pod would get: the same job with a token of its own
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
    // a failure would have counted the job's retries down. It could have raised an incident
    // over work which was done and is fine. Read over the whole class, the check would also
    // speak for every other test of it. So it reads the output of this test alone
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
   * The client which sends the second activation. It is built like an application which is
   * not this one.
   * <p>
   * It may not be the adapter's own client. That is the whole reason this method exists. The
   * adapter hands its client an executor whose handling half is as wide as
   * <code>worker-threads</code>, and the client answers its own requests on it. This test
   * blocks a handler on purpose. From {@code 8.10.0-rc1} on, the cluster hands the expired job
   * straight back to the same worker, once per free slot. So every slot ends up holding a
   * blocked run of it. A request sent with the adapter's client then has nobody left to
   * complete it. It dies of its socket timeout, whatever that timeout is. Measured on
   * 2026-09-25: 3000 ms with a window of two seconds, and 6000 ms with the module's five. A
   * client of its own has an executor of its own and is not affected.
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

    final var deadline = System.currentTimeMillis() + TIME_TO_TAKE_THE_JOB_OVER.toMillis();
    while (System.currentTimeMillis() < deadline) {
      final List<ActivatedJob> jobs = Camunda8JobLease
          .leaseTheActivation(
              secondPod
                  .newActivateJobsCommand()
                  .jobType(JOB_TYPE)
                  .maxJobsToActivate(1)
                  .timeout(SECOND_ACTIVATION_LOCK)
                  .workerName("lease-verification"))
          .send()
          .join()
          .getJobs();
      if (!jobs.isEmpty()) {
        return jobs.getFirst();
      }
      TimeUnit.MILLISECONDS.sleep(500);
    }
    throw new AssertionError(
        "the job of type '%s' was not handed out a second time within %s; the cluster's search says: %s"
            .formatted(JOB_TYPE, TIME_TO_TAKE_THE_JOB_OVER, whatTheSearchSaysAbout(secondPod)));

  }

  /**
   * What the cluster's search knows about the jobs of the slow task, for a failure message.
   * The search lags behind the engine. So it says where the job was a moment ago: which
   * worker had it and until when.
   *
   * @param secondPod The client of this test
   * @return One entry per job of that type which is not over yet
   */
  private static String whatTheSearchSaysAbout(
      final CamundaClient secondPod) {

    try {
      return secondPod
          .newJobSearchRequest()
          .filter(filter -> filter.type(JOB_TYPE))
          .send()
          .join()
          .items()
          .stream()
          .filter(job -> (job.getState() != JobState.COMPLETED) && (job.getState() != JobState.CANCELED))
          .map(job -> "job %d %s, worker '%s', lock until %s"
              .formatted(job.getJobKey(), job.getState(), job.getWorker(), job.getDeadline()))
          .toList()
          .toString();
    } catch (final RuntimeException e) {
      return "nothing, the search failed with %s".formatted(e);
    }

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
   * job. A wait which only looked for the refusal would end as soon as any job of this class
   * was refused.
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
