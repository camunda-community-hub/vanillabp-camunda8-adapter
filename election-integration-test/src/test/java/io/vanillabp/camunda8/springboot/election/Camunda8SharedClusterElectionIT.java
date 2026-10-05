package io.vanillabp.camunda8.springboot.election;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.WorkflowNotFoundException;

/**
 * Two <code>camunda8</code> adapter ids on ONE cluster
 * are told apart by the scope they deployed under, not by the first entry of
 * <code>prioritized-adapters</code>.
 * <p>
 * The scenario is the migration the adapter documents as supported. A workflow is started
 * while only <code>c8-plain</code> exists and waits for a message. The application is then
 * restarted with a second adapter id <code>c8-prefix</code> in FIRST priority, which
 * deploys the same process under a prefixed identifier onto the same cluster. Correlating
 * the message must reach the workflow of the OLD deployment.
 * <p>
 * <b>What fails without the fix.</b> Both adapters find the instance, because the search
 * behind <code>awarenessOfWorkflow</code> filtered by the aggregate-ID variable alone, and
 * the walk stops at the first <code>ACTIVE</code>. The message is then published as
 * <code>election-app__ElectionMessage</code>, which nobody subscribes to, and the cluster
 * buffers it until its time to live passes. Nothing fails, the workflow simply never
 * continues, which is why the assertion here is a task that ran.
 * <p>
 * The tenant is deliberately not part of this test. Two ids differing in
 * <code>use-prefix</code> versus <code>none</code> live in the default tenant and are told
 * apart by the process definition id alone, which needs no authenticated cluster; the
 * tenant is one more comparison in the same scope key and is covered by unit tests.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@Testcontainers(disabledWithoutDocker = true)
public class Camunda8SharedClusterElectionIT {

  /**
   * The election probes search the cluster, so this cluster brings secondary storage.
   * A cluster which answers no search is refused while the adapter deploys, for one
   * adapter id as well as for two, which {@code Camunda8UnsearchableClusterIT} proves.
   */
  @Container
  static final GenericContainer<?> CAMUNDA = ClusterUnderTest.cluster();

  private ConfigurableApplicationContext application;

  private static String restAddress() {

    return "http://"
        + CAMUNDA.getHost()
        + ":"
        + CAMUNDA.getMappedPort(8080);

  }

  private static String grpcAddress() {

    return "http://"
        + CAMUNDA.getHost()
        + ":"
        + CAMUNDA.getMappedPort(26500);

  }

  /**
   * @param withPrefixAdapter Whether the second adapter id is configured, in first
   *          priority - which is what the restart of the migration does
   */
  private ConfigurableApplicationContext boot(
      final boolean withPrefixAdapter) {

    final var arguments = new LinkedList<String>();
    arguments.add("--spring.config.name=camunda8-election-it");
    arguments.add("--spring.main.web-application-type=none");
    arguments.add("--vanillabp.adapters.c8-plain.rest-address="
        + restAddress());
    arguments.add("--vanillabp.adapters.c8-plain.grpc-address="
        + grpcAddress());
    // the correlation follows the start closely, and the exporter of a cold cluster
    // needs longer than the production default to make the workflow findable
    arguments.add("--vanillabp.adapters.c8-plain.workflow-visibility-timeout=PT60S");
    if (withPrefixAdapter) {
      arguments.add("--vanillabp.adapters.c8-prefix.type=camunda8");
      arguments.add("--vanillabp.adapters.c8-prefix.job-lease=use");
      arguments.add("--vanillabp.adapters.c8-prefix.name-clash-avoidance=use-prefix");
      arguments.add("--vanillabp.adapters.c8-prefix.rest-address="
          + restAddress());
      arguments.add("--vanillabp.adapters.c8-prefix.grpc-address="
          + grpcAddress());
      arguments.add("--vanillabp.adapters.c8-prefix.workflow-visibility-timeout=PT60S");
      arguments.add(
          "--vanillabp.workflow-modules.election-app.adapters.c8-prefix.resources-location=classpath:it-election");
      // the new deployment takes over new workflows, which is what makes the old ones
      // the ones the election has to find
      arguments.add("--vanillabp.prioritized-adapters[0]=c8-prefix");
      arguments.add("--vanillabp.prioritized-adapters[1]=c8-plain");
    }
    return new SpringApplicationBuilder(ElectionTestApplication.class)
        .run(arguments.toArray(new String[0]));

  }

  @AfterEach
  public void closeWhatIsLeft() {

    if ((application != null) && application.isActive()) {
      application.close();
    }
    ElectionWorkflowService.reset();

  }

  private <T> T bean(
      final Class<T> type) {

    return application.getBean(type);

  }

  @Test
  @DisplayName("A message reaches the workflow of the adapter which started it, not the one in first priority")
  public void theMessageReachesTheAdapterHoldingTheWorkflow(
      final CapturedOutput output) throws Exception {

    // the application as it was before the migration: one adapter, no prefixes
    ElectionWorkflowService.reset();
    application = boot(false);
    final var aggregateId = bean(TransactionTemplate.class)
        .execute(status -> bean(ElectionWorkflowService.class)
            .startWorkflow()
            .getId());
    assertNotNull(aggregateId, "the workflow was started by 'c8-plain'");
    // Camunda 8 is started after the caller's commit, so returning from startWorkflow says
    // nothing about the cluster yet. Closing here would hand the start to the restart, where
    // the prefixed adapter is already deployed: the instance the election has to find would
    // then be created seconds before the message asks for it.
    awaitInOutput(
        output,
        "Started Camunda 8 workflow 'ElectionProcess' (adapter 'c8-plain'",
        "the workflow to reach the cluster while only 'c8-plain' exists");
    application.close();

    // and after it: the same cluster, a second adapter id deploying the same process
    // under a prefixed identifier, first in the prioritized list
    application = boot(true);
    correlateUntilTheClusterKnowsTheWorkflow(aggregateId);

    assertTrue(
        ElectionWorkflowService.SERVED.await(2, TimeUnit.MINUTES),
        "the message reached the workflow started before the migration - without the scope check it is "
            + "published under the prefixed name into a cluster where nobody subscribes to it");

    // The latch and the line are two events and nothing orders them. The latch counts down
    // in the job worker. The line which names the publishing adapter is written by the
    // thread which published, after the cluster acknowledged the command, and the cluster
    // correlates the message and creates the job in the same step which answers that
    // command. The job can therefore be on its way to the worker while the publishing
    // thread has not reached its log statement yet. On an idle machine the line was 23 to
    // 43 ms ahead of the handler over four runs, and that head start was the whole of the
    // assertion. A pause of the JVM or a busy build server eats it, and that is how this
    // test went red once without anybody touching it: the message had reached the workflow,
    // the line had not reached the buffer. So the test waits for the line it judges by.
    // Delay that line on purpose and the test fails without this wait and passes with it.
    awaitInOutput(
        output,
        "Camunda8[c8-plain]: published message",
        "the adapter holding the workflow to report that IT published the message - the one in first "
            + "priority publishing instead is what the scope check prevents");

    // over the running test, because a sentence which is absent is only absent for the
    // test which says so
    final var logged = output.getAllOfThisTest();
    assertFalse(
        logged.contains("Camunda8[c8-prefix]: published message"),
        "the adapter of the new deployment must not answer for a workflow of the old one: "
            + logged);

  }


  /**
   * Correlates the message, tolerating the read model the election asks.
   * <p>
   * The restart leaves no hint behind which adapter started the workflow, so the walk gets
   * the honest "unknown" of an exporter which has not indexed the instance yet, and the core
   * has nothing to wait for. VanillaBP documents that residual and names retrying the
   * business operation as the answer, which is what happens here: this test asks WHICH
   * adapter answers for the workflow, not how fast the query API catches up.
   *
   * @param aggregateId The aggregate whose workflow waits for the message
   */
  private void correlateUntilTheClusterKnowsTheWorkflow(
      final Long aggregateId) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + Duration
        .ofMinutes(1)
        .toMillis();
    while (true) {
      try {
        final var aggregate = bean(ElectionAggregateRepository.class)
            .findById(aggregateId)
            .orElseThrow();
        bean(TransactionTemplate.class)
            .executeWithoutResult(
                status -> bean(ElectionWorkflowService.class).theMessageArrived(aggregate));
        return;
      } catch (final WorkflowNotFoundException e) {
        if (System.currentTimeMillis() >= deadline) {
          throw e;
        }
        Thread.sleep(500);
      }
    }

  }

  /**
   * Waits for a line the application logs, which is the only place a test outside the
   * application learns that its phase two ran.
   *
   * @param output What the application logged so far
   * @param line The line to wait for
   * @param what What waiting for it means, for the failure message
   */
  private static void awaitInOutput(
      final CapturedOutput output,
      final String line,
      final String what) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + Duration
        .ofMinutes(1)
        .toMillis();
    while (System.currentTimeMillis() < deadline) {
      if ((output.getOut() + output.getErr()).contains(line)) {
        return;
      }
      Thread.sleep(200);
    }
    fail("timed out waiting for "
        + what);

  }

}
