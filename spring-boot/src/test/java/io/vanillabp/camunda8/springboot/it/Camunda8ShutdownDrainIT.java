package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The shutdown against a real Camunda 8 cluster: a restart must not
 * burn a job's retries.
 * <p>
 * Both halves are measured here with the application shut down while a handler is
 * inside it:
 * <ul>
 * <li>a handler which outlives the grace period is cut off by the closing client. Its job
 * is NOT failed, so the cluster hands it out again once its lock expires - with the three
 * retries it started with. An adapter answering the interrupt with
 * {@code newFailCommand(retries - 1)} would return the job with two instead;</li>
 * <li>a handler shorter than the grace period finishes, and its job is completed before
 * the client goes down, so the cluster has nothing left to hand out.</li>
 * </ul>
 * The assertions are made with a client of the test's own, after the application is gone:
 * what the cluster hands out is the only evidence that counts here.
 * <p>
 * The application is booted by the test rather than by {@code @SpringBootTest}, because
 * SHUTTING IT DOWN is what is under test. Spring's test context is a cache which expects
 * to own that lifecycle, and a context closed inside a test method is a context it tries
 * to restart afterwards.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8ShutdownDrainIT extends TestOnTheSharedCluster {

  /**
   * The retries a Camunda 8 job starts with, as the cluster hands it out.
   */
  private static final int RETRIES_OF_A_FRESH_JOB = 3;

  private ConfigurableApplicationContext application;

  @BeforeEach
  public void bootTheApplication() {

    ShutdownDockerWorkflowService.reset();
    application = new SpringApplicationBuilder(DockerTestApplication.class)
        .run(
            "--spring.config.name=camunda8-it",
            "--spring.main.web-application-type=none",
            "--vanillabp.adapters.c8.rest-address="
                + restAddress(),
            "--vanillabp.adapters.c8.grpc-address="
                + grpcAddress(),
            // two seconds is enough for the graceful handler and far too little for the
            // blocking one, which is what makes both halves observable in one setup
            "--vanillabp.adapters.c8.shutdown-grace=PT2S",
            // and a short lock, so a job left behind comes back within the test instead
            // of in five minutes
            "--vanillabp.workflow-modules.test-app.workflows.ShutdownProcess.adapters.c8.job-timeout=PT8S",
            "--vanillabp.workflow-modules.test-app.workflows.ShutdownGraceProcess.adapters.c8.job-timeout=PT8S");

  }

  @AfterEach
  public void closeWhatIsLeft() {

    if ((application != null) && application.isActive()) {
      application.close();
    }
    ShutdownDockerWorkflowService.reset();

  }

  private <T> T bean(
      final Class<T> type) {

    return application.getBean(type);

  }

  /**
   * The job type the cluster knows a task definition of this application by. The test
   * application isolates its workflow modules by PREFIXING identifiers, so what the
   * cluster hands out is not what the {@code @WorkflowTask} annotation says - and asking
   * the running application beats writing the prefix into the test.
   *
   * @param bpmnProcessId The plain BPMN process id
   * @param taskDefinition The plain task definition
   * @return The job type
   */
  private String jobTypeOf(
      final String bpmnProcessId,
      final String taskDefinition) {

    return bean(NameClashAvoidanceSupport.class)
        .scopedTaskDefinition("test-app", bpmnProcessId, taskDefinition, "c8");

  }

  @Test
  @DisplayName("A handler cut off by the shutdown gets its job back with its retries intact")
  public void aCutOffHandlerCostsNoRetry(
      final CapturedOutput output) throws Exception {

    final var workflowService = bean(ShutdownDockerWorkflowService.class);
    final var transactionTemplate = bean(TransactionTemplate.class);
    final var jobType = jobTypeOf("ShutdownProcess", "shutdownTask");

    assertNotNull(
        transactionTemplate.execute(status -> workflowService.startBlocking().getId()),
        "the workflow was started");

    assertTrue(
        ShutdownDockerWorkflowService.BLOCKING_ENTERED.await(60, TimeUnit.SECONDS),
        "the handler was delivered and is inside the application");

    application.close();

    assertEquals(
        1,
        ShutdownDockerWorkflowService.BLOCKING_INVOCATIONS.get(),
        "the handler ran once - the shutdown is not a redelivery");
    final var logged = output.getOut() + output.getErr();
    assertTrue(
        logged.contains("shutdownTask"),
        "the operator was told what was cut off: "
            + logged);

    // the job is not failed, so nothing shortens its lock: it comes back when the lock
    // expires, which is what the workflow's job-timeout was set to
    try (final var client = testClient()) {
      final var redelivered = awaitJob(client, jobType, Duration.ofSeconds(60));
      assertEquals(
          RETRIES_OF_A_FRESH_JOB,
          redelivered.getRetries(),
          "the restart cost the job nothing - reported as a failure it would be back with "
              + (RETRIES_OF_A_FRESH_JOB - 1));
    }

  }

  @Test
  @DisplayName("A handler shorter than the grace period finishes and its job is completed")
  public void aHandlerWithinTheGraceFinishes() throws Exception {

    final var repository = bean(ShutdownDockerAggregateRepository.class);
    final var transactionTemplate = bean(TransactionTemplate.class);
    final var jobType = jobTypeOf("ShutdownGraceProcess", "gracefulTask");

    final var aggregateId = transactionTemplate
        .execute(status -> repository.save(new ShutdownDockerAggregate()).getId());
    assertNotNull(aggregateId);

    try (final var client = testClient()) {

      client
          .newCreateInstanceCommand()
          .bpmnProcessId("test-app__ShutdownGraceProcess")
          .latestVersion()
          .variable("id", String.valueOf(aggregateId))
          .send()
          .join();

      assertTrue(
          ShutdownDockerWorkflowService.GRACEFUL_ENTERED.await(60, TimeUnit.SECONDS),
          "the handler was delivered and is inside the application");

      application.close();

      assertEquals(
          0,
          ShutdownDockerWorkflowService.GRACEFUL_RETURNED.getCount(),
          "the shutdown waited for the handler instead of interrupting it");
      // the job was completed before the client went down, so the cluster has nothing to
      // hand out any more - not even after the lock would have expired
      assertFalse(
          jobAvailable(client, jobType, Duration.ofSeconds(15)),
          "a completed job is never redelivered");

    }

  }

  /**
   * A client of the test's own - the application's one is gone by the time the assertions
   * are made.
   */
  private CamundaClient testClient() {

    return CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(restAddress()))
        .grpcAddress(URI.create(grpcAddress()))
        .build();

  }

  private List<ActivatedJob> activate(
      final CamundaClient client,
      final String jobType) {

    // this suite configures 'job-lease: use', and a job which was leased is never handed
    // to an activation which does not ask for one. So a test reaching for a job of this
    // application asks the way its workers ask
    return Camunda8JobLease
        .leaseTheActivation(
            client
                .newActivateJobsCommand()
                .jobType(jobType)
                .maxJobsToActivate(1)
                .timeout(Duration.ofSeconds(5))
                .workerName("shutdown-verification"))
        .requestTimeout(Duration.ofSeconds(2))
        .send()
        .join()
        .getJobs();

  }

  private ActivatedJob awaitJob(
      final CamundaClient client,
      final String jobType,
      final Duration timeout) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      final var jobs = activate(client, jobType);
      if (!jobs.isEmpty()) {
        return jobs.getFirst();
      }
      TimeUnit.MILLISECONDS.sleep(500);
    }
    throw new AssertionError("the job of type '%s' was not handed out within %s".formatted(jobType, timeout));

  }

  private boolean jobAvailable(
      final CamundaClient client,
      final String jobType,
      final Duration within) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + within.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (!activate(client, jobType).isEmpty()) {
        return true;
      }
      TimeUnit.MILLISECONDS.sleep(500);
    }
    return false;

  }

}
