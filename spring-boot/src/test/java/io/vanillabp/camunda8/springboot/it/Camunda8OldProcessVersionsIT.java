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
 * The old-versions startup check against a REAL cluster: the application deploys version
 * 1 of a process and boots again with a model which dropped one of its tasks. Reading
 * the model of the older version and counting the workflows running on it are the two
 * things only Camunda 8 can answer here, and both are read by searching the cluster.
 * <p>
 * Every case is a full boot, because the question is what a START reports, and the
 * findings are read from the captured output: Spring Boot resets the logging context
 * while it starts, which takes a log appender attached beforehand with it.
 * <p>
 * The last case is the one an operator ends up in: it deletes the older version and
 * boots again. Camunda 8 keeps a deleted definition and marks it <code>DELETED</code>,
 * so without a state in the search the check would go on reporting it forever.
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

      // the workflow of version 1 walks through both tasks - what matters for the
      // next boot is that the cluster holds the version and the exporter saw it
      // this one waits for the engine and for a worker, and a loaded build runner is
      // slower at both. The whole class took 20,0 seconds in the full run of 2026-10-01,
      // so the number below is not a figure anything ordinary comes near
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

    // version 1 is served by the method kept for it, so nothing is demanded ...
    assertTrue(
        !reported.contains("definition(s) 'droppedInVersionTwo'"),
        "the task served by the version-1 method is not reported");
    // ... which is only provable if the model of version 1 was read at all: without
    // the query API the adapter says so instead
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
    // still there, and that answer comes out of the secondary storage. The exporter is
    // behind the deployment by an unknown amount, and a check running before it caught up
    // knows the version it just deployed and nothing else - which is why this waits
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

    // the remedy every report above asks for, applied: the version nobody wants to hear
    // about is deleted. Camunda 8 does not remove it, it marks it DELETED and keeps
    // answering searches with it, so a check asking without naming a state would find
    // everything it found before and report it again
    deleteVersionOneFromTheCluster();

    final var before = output.getAll().length();
    boot("v2").close();
    final var reported = output.getAll().substring(before);

    // this is the boot of the second case, which reported nothing while version 1 was
    // there: what the check works on is now version 2 alone, and the method kept for
    // version 1 has become the dead one
    assertTrue(
        reported.contains("(held: 2)"),
        "the deleted version is gone from the versions the check asks the cluster for");
    assertTrue(
        reported.contains("droppedInVersionTwo"),
        "and the method serving it is named as the method which never runs");

  }

  /**
   * A client of the test's own: the application booted per case is gone between them, and
   * this question is asked while none is running.
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
      // what is waited out here is the export pipeline and nothing else, now that the
      // search names the process: it was never slower than 1,1 seconds in the
      // measurement below, whatever the cluster already held, and the minute is the
      // headroom a loaded build runner gets on top of that
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
   * Four minutes stood here before, after two raises, and the class kept falling: what it
   * waited for could not happen at all on a cluster this full. See
   * {@link #THE_TESTS_PROCESS_AS_THE_CLUSTER_KNOWS_IT} for what that was.
   */
  private static final long THE_EXPORT_PIPELINE_CATCHES_UP_WITHIN = 60_000;

  /**
   * The test's process under the id the CLUSTER knows it by: this module runs with
   * {@code name-clash-avoidance: use-prefix}, so the plain id of the model carries the
   * workflow module in front of it.
   * <p>
   * Every search of this class names it, rather than reading what the cluster holds and
   * keeping whatever ends in the plain id. A search which names nothing answers with one
   * page, oldest definition first, and that page has a ceiling.
   * <p>
   * That is what used to make this class fall, and only in a full run. Measured on
   * 2026-10-01 against {@code camunda/camunda:8.10.0}: the module's shared cluster held
   * 106 process definitions by the time this class started, a search naming nothing
   * answered with 100 of them, and the two versions of this test were not among the 100.
   * This class deploys them last and the page starts at the oldest, so they sat in the
   * tail the page never reaches. No deadline can bring an entry onto a page it is not on,
   * and four minutes of waiting per run is what that cost.
   * <p>
   * The export pipeline, which the deadline above is for, was never the slow part: 262 to
   * 1056 milliseconds at every fill level from 2 definitions to 254 in that measurement,
   * and 5 milliseconds in the full run once the search named the process.
   * <p>
   * The adapter's own definition searches name the process the same way, so this test
   * now waits for exactly the answer the check under test will read.
   */
  private static final String THE_TESTS_PROCESS_AS_THE_CLUSTER_KNOWS_IT = "test-app__OldProcessVersionsProcess";

  /**
   * The versions of the test's process the cluster answers with.
   */
  private static long versionsKnownToTheCluster(
      final CamundaClient client) {

    return definitionsOfTheTestsProcess(client).count();

  }

  /**
   * Deletes the resource version 1 was deployed with and waits until the cluster stops
   * answering searches for ACTIVE definitions with it - a deletion reaches the query API
   * the same way a deployment does, so it is behind by an unknown amount as well.
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
   * Every version of the test's process the cluster holds, deleted ones included. The
   * filter bounds the answer to the versions of one process, which is two here, so there
   * is no second page to ask for.
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
   * What the cluster answers the way the adapter asks: definitions which were not
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
