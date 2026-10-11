package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ProcessDefinitionState;
import io.camunda.client.api.search.response.ProcessDefinition;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The old-versions startup check against a REAL cluster. The application deploys version 1
 * of a process. Then it boots again with a model which dropped one of its tasks. Only
 * Camunda 8 can answer two things here: the model of the older version, and the number of
 * workflows which run on it. Both are read by searching the cluster.
 * <p>
 * Every case is a full boot, because the question is what a START reports. The findings are
 * read from the captured output. Spring Boot resets the logging context while it starts, and
 * that removes a log appender which was attached before.
 * <p>
 * The last case is the one an operator ends up in. The operator deletes the older version
 * and boots again. Camunda 8 keeps a deleted definition and marks it <code>DELETED</code>.
 * So without a state in the search, the check would go on reporting it forever.
 */
@ExtendWith(SuppressOutputExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestOnTheSharedCluster.ItsTestsAreOneScenario
public class Camunda8OldProcessVersionsIT extends TestOnTheSharedCluster {

  @Test
  @Order(1)
  @DisplayName("Version 1 is deployed and a workflow is started on it")
  public void deployVersionOneAndStartAWorkflow() throws Exception {

    final var application = boot("v1");
    try {
      final var workflowService = application.getBean(OldProcessVersionsDockerWorkflowService.class);
      final var repository = application.getBean(OldProcessVersionsDockerAggregateRepository.class);
      final var aggregate = application
          .getBean(TransactionTemplate.class)
          .execute(status -> workflowService.startWorkflow());

      // the workflow of version 1 walks through both tasks. For the next boot it matters
      // that the cluster holds the version and that the exporter saw it.
      // This wait is for the engine and for a worker, and a loaded build runner is slower
      // at both. The whole class took 20,0 seconds in the full run of 2026-10-01. So no
      // ordinary run comes near the number below
      final var deadline = System.currentTimeMillis() + 180_000;
      while (repository.findById(aggregate.getId()).orElseThrow().getServedBy() == null) {
        if (System.currentTimeMillis() > deadline) {
          throw new AssertionError("the workflow of version 1 did not run");
        }
        Thread.sleep(200);
      }
    } finally {
      application.close();
    }

  }

  @Test
  @Order(2)
  @DisplayName("The task version 1 still has is read from the cluster and reported")
  public void theDroppedTaskOfVersionOneIsReported(
      final CapturedOutput output) {

    final var before = output.getAll().length();
    boot("v2").close();
    final var reported = output.getAll().substring(before);

    // the method kept for version 1 serves it, so nothing is demanded ...
    assertTrue(
        !reported.contains("definition(s) 'droppedInVersionTwo'"),
        "the task served by the version-1 method is not reported");
    // ... which is only provable if the model of version 1 was read at all. Without the
    // query API the adapter says so instead
    assertTrue(
        !reported.contains("cannot read the models"),
        "the cluster's query API answered, so the models were read");

  }

  @Test
  @Order(3)
  @DisplayName("Fading version 1 out makes the method kept for it dead, and says why")
  public void fadingVersionOneOutReportsTheMethodKeptForIt(
      final CapturedOutput output) throws Exception {

    // the reason is only written if the check LEARNS from the cluster that version 1 is
    // still there. That answer comes from the secondary storage. The exporter lags behind
    // the deployment by an unknown time. A check which runs before it caught up knows the
    // version it just deployed and nothing else. That is why the test waits here
    awaitTheQueryApiKnowingBothVersions();

    final var before = output.getAll().length();
    boot("v2", "--vanillabp.workflow-modules.test-app.adapters.c8.outfaded-versions=<2").close();
    final var reported = output.getAll().substring(before);

    assertTrue(reported.contains("droppedInVersionTwo"), "the method serving the faded-out version is named");
    assertTrue(reported.contains("the method never runs"), "and what that means is said");
    assertTrue(reported.contains("faded out by"), "and why");

  }

  @Test
  @Order(4)
  @DisplayName("A version an operator deleted is not one the check works on any more")
  public void aDeletedVersionIsNoVersionTheClusterHolds(
      final CapturedOutput output) throws Exception {

    // apply the remedy which every report above asks for: delete the version nobody wants
    // to hear about. Camunda 8 does not remove it. It marks it DELETED and keeps answering
    // searches with it. So a check which asks without naming a state would find everything
    // it found before and report it again
    deleteVersionOneFromTheCluster();

    final var before = output.getAll().length();
    boot("v2").close();
    final var reported = output.getAll().substring(before);

    // this is the boot of the second case, which reported nothing while version 1 was
    // there. Now the check works on version 2 alone. The method kept for version 1 has
    // become the dead one
    assertTrue(
        reported.contains("(held: 2)"),
        "the deleted version is gone from the versions the check asks the cluster for");
    assertTrue(
        reported.contains("droppedInVersionTwo"),
        "and the method serving it is named as the method which never runs");

  }

  /**
   * A client of the test's own. The application booted per case is gone between the cases,
   * and this question is asked while none is running.
   */
  private static CamundaClient testClient() {

    return CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(restAddress()))
        .grpcAddress(URI.create(grpcAddress()))
        .build();

  }

  private static void awaitTheQueryApiKnowingBothVersions() throws Exception {

    try (var client = testClient()) {
      // now that the search names the process, this waits for the export pipeline and
      // nothing else. It was never slower than 1,1 seconds in the measurement below,
      // whatever the cluster already held. The minute is the extra time a loaded build
      // runner gets on top of that
      final var deadline = System.currentTimeMillis() + THE_EXPORT_PIPELINE_CATCHES_UP_WITHIN;
      while (versionsKnownToTheCluster(client) < 2) {
        if (System.currentTimeMillis() > deadline) {
          throw new AssertionError(
              "the query API did not learn both versions of 'OldProcessVersionsProcess'");
        }
        Thread.sleep(500);
      }
    }

  }

  /**
   * How long a deployment or a deletion of this class may take to reach the query API.
   * <p>
   * Four minutes stood here before, after two raises, and the class kept failing. What it
   * waited for could not happen at all on a cluster this full. See
   * {@link #THE_TESTS_PROCESS_AS_THE_CLUSTER_KNOWS_IT} for what that was.
   */
  private static final long THE_EXPORT_PIPELINE_CATCHES_UP_WITHIN = 60_000;

  /**
   * The test's process under the id by which the CLUSTER knows it. This module runs with
   * {@code name-clash-avoidance: use-prefix}, so the workflow module stands in front of the
   * plain id of the model.
   * <p>
   * Every search of this class names it. It does not read what the cluster holds and keep
   * whatever ends in the plain id. A search which names nothing answers with one page, oldest
   * definition first, and that page has an upper limit.
   * <p>
   * That used to make this class fail, and only in a full run. Measured on 2026-10-01
   * against {@code camunda/camunda:8.10.0}: the module's shared cluster held 106 process
   * definitions by the time this class started. A search which named nothing answered with
   * 100 of them, and the two versions of this test were not among the 100. This class
   * deploys them last, and the page starts at the oldest. So they sat in the tail which the
   * page never reaches. No deadline can bring an entry onto a page it is not on. That cost
   * four minutes of waiting per run.
   * <p>
   * The export pipeline, which the deadline above is for, was never the slow part. In that
   * measurement it took 262 to 1056 milliseconds at every fill level from 2 definitions to
   * 254. In the full run it took 5 milliseconds, once the search named the process.
   * <p>
   * The adapter's own definition searches name the process the same way. So this test now
   * waits for exactly the answer which the check under test will read.
   */
  private static final String THE_TESTS_PROCESS_AS_THE_CLUSTER_KNOWS_IT = "test-app__OldProcessVersionsProcess";

  /**
   * The versions of the test's process which the cluster answers with.
   */
  private static long versionsKnownToTheCluster(
      final CamundaClient client) {

    return definitionsOfTheTestsProcess(client).count();

  }

  /**
   * Deletes the resource which version 1 was deployed with. Then it waits until the cluster
   * stops answering searches for ACTIVE definitions with it. A deletion reaches the query API
   * the same way as a deployment, so it also lags behind by an unknown time.
   */
  private static void deleteVersionOneFromTheCluster() throws Exception {

    try (var client = testClient()) {
      final var versionOne = definitionsOfTheTestsProcess(client)
          .filter(definition -> definition.getVersion() == 1)
          .findFirst()
          .orElseThrow(() -> new AssertionError("version 1 is not known to the cluster"));
      client
          .newDeleteResourceCommand(versionOne.getProcessDefinitionKey())
          .send()
          .join();

      final var deadline = System.currentTimeMillis() + THE_EXPORT_PIPELINE_CATCHES_UP_WITHIN;
      while (activeDefinitionsOfTheTestsProcess(client) > 1) {
        if (System.currentTimeMillis() > deadline) {
          throw new AssertionError("the query API kept answering with the deleted version 1");
        }
        Thread.sleep(500);
      }
    }

  }

  /**
   * Every version of the test's process which the cluster holds, deleted ones included. The
   * filter limits the answer to the versions of one process. That is two versions here, so
   * there is no second page to ask for.
   */
  private static Stream<ProcessDefinition> definitionsOfTheTestsProcess(
      final CamundaClient client) {

    return client
        .newProcessDefinitionSearchRequest()
        .filter(filter -> filter.processDefinitionId(THE_TESTS_PROCESS_AS_THE_CLUSTER_KNOWS_IT))
        .send()
        .join()
        .items()
        .stream();

  }

  /**
   * What the cluster answers when asked the way the adapter asks: definitions which were not
   * deleted.
   */
  private static long activeDefinitionsOfTheTestsProcess(
      final CamundaClient client) {

    return client
        .newProcessDefinitionSearchRequest()
        .filter(filter -> {
          filter.processDefinitionId(THE_TESTS_PROCESS_AS_THE_CLUSTER_KNOWS_IT);
          filter.state(ProcessDefinitionState.ACTIVE);
        })
        .send()
        .join()
        .items()
        .size();

  }

  private static ConfigurableApplicationContext boot(
      final String version,
      final String... arguments) {

    final var boot = new ArrayList<String>();
    boot.add("--spring.config.name=camunda8-it");
    boot
        .add("--vanillabp.adapters.c8.rest-address="
            + restAddress());
    boot
        .add("--vanillabp.adapters.c8.grpc-address="
            + grpcAddress());
    boot.add("--vanillabp.adapters.c8.workflow-visibility-timeout=PT60S");
    boot
        .add("--vanillabp.workflow-modules.test-app.adapters.c8.resources-location=classpath*:old-process-versions/%s"
            .formatted(version));
    boot.addAll(List.of(arguments));
    return new SpringApplicationBuilder(DockerTestApplication.class).run(boot.toArray(String[]::new));

  }

}
