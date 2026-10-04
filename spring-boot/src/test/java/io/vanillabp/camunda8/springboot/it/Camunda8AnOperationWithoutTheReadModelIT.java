package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.Variable;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An operation on a workflow while the exporter of the cluster stands still, on a node which
 * has no hint about that workflow in its election cache.
 * <p>
 * Without the hint, "the search does not know this workflow" used to read as "nobody knows
 * it": phase one refused the operation, and phase two consumed an entry it had planned
 * before as stale. A restarted node, a second node and an expired hint all look like that.
 * What such a node still has is the start row VanillaBP wrote when the workflow started. It
 * names this adapter and the process instance key, so the engine answers by key and the read
 * model is not needed at all. That is what this class shows: a {@code correlateMessage} and
 * then a global {@code aggregateChanged} arrive while the exporter stands still, each sent
 * after this node forgot its hint.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8AnOperationWithoutTheReadModelIT extends SpringBootTestOnTheSharedCluster {

  /**
   * The workflow module of the workflow, which is also the prefix of its process in the
   * cluster.
   */
  private static final String THE_WORKFLOW_MODULE = "test-app";

  /**
   * The process of the workflow, as VanillaBP knows it. No other class of this module uses
   * it, see {@link ReadModelDockerAggregate}.
   */
  private static final String THE_PROCESS = "ReadModelStandsStillProcess";

  /**
   * How long the election waits for a workflow it has a reason to expect, with the default
   * 'workflow-visibility-timeout'. Phase one runs in the caller's transaction, so an engine
   * which answers by key has to answer well within it.
   */
  private static final Duration THE_VISIBILITY_WINDOW = Duration.ofSeconds(10);

  @Autowired
  private ReadModelDockerWorkflowService workflowService;

  @Autowired
  private ReadModelDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @Autowired
  private TaskDeliveryLog deliveryLog;

  @Autowired
  private List<WorkflowAdapterCache> electionCaches;

  @Test
  @DisplayName("correlateMessage and aggregateChanged arrive while the exporter stands still and no hint is cached")
  public void operationsReachAWorkflowTheReadModelDoesNotKnow(
      final CapturedOutput output) throws Exception {

    final Long aggregateId;
    final TaskDelivery started;
    exporting("pause");
    try {
      aggregateId = transactionTemplate
          .execute(status -> workflowService.startWorkflow().getId());
      assertNotNull(aggregateId);
      started = awaitTheStartRowOf(aggregateId);
      assertTrue(
          searchFindsNoWorkflowOf(Long.parseLong(started.workflowId())),
          "the read model must not know the workflow, otherwise this test proves nothing");

      forgetTheHintAbout(aggregateId);
      final var phaseOneOfTheCorrelation = timed(
          () -> transactionTemplate
              .executeWithoutResult(status -> workflowService.correlate(aggregateId)));
      assertTrue(
          phaseOneOfTheCorrelation.compareTo(THE_VISIBILITY_WINDOW) < 0,
          "phase one of the correlation took %s, so it waited for the read model instead of asking the engine"
              .formatted(phaseOneOfTheCorrelation));
      // the message moves the workflow on to its next task, which the engine hands out
      // without any read model
      awaitUntil(() -> taskIdOf(aggregateId) != null, "the correlated message to move the workflow on");

      forgetTheHintAbout(aggregateId);
      final var phaseOneOfThePush = timed(
          () -> transactionTemplate
              .executeWithoutResult(status -> workflowService.pushGlobally(aggregateId, "pushed-unseen")));
      assertTrue(
          phaseOneOfThePush.compareTo(THE_VISIBILITY_WINDOW) < 0,
          "phase one of the push took %s, so it waited for the read model instead of asking the engine"
              .formatted(phaseOneOfThePush));
      awaitUntil(
          () -> output
              .getAllOfThisTest()
              .contains(
                  "pushed the changed aggregate '%s' into process instance '%s'"
                      .formatted(aggregateId, started.workflowId())),
          "the push to be written into the instance the start row names");
    } finally {
      exporting("resume");
    }

    final var processInstanceKey = Long.parseLong(started.workflowId());
    awaitUntil(
        () -> notesOf(processInstanceKey)
            .stream()
            .anyMatch(variable -> variable.getValue().contains("pushed-unseen")),
        "the read model to report the pushed value once the exporter caught up");
    final var notes = notesOf(processInstanceKey);
    assertEquals(1, notes.size(), "a global push updates the existing variable instead of adding a scope");
    assertEquals(
        processInstanceKey,
        notes.getFirst().getScopeKey(),
        "the value has to live at the workflow's own scope");

    // the parked workflow is ended, so the cluster this module shares holds nothing running
    // of this class
    transactionTemplate.executeWithoutResult(status -> workflowService.finish(aggregateId));

  }

  /**
   * Waits for the row VanillaBP writes when it started a workflow, which is what a node
   * without a cached hint reads instead. It names the adapter and the process instance key.
   */
  private TaskDelivery awaitTheStartRowOf(
      final Long aggregateId) throws InterruptedException {

    awaitUntil(
        () -> startRowOf(aggregateId) != null,
        "the start of the workflow of aggregate '%s' to be recorded".formatted(aggregateId));
    final var started = startRowOf(aggregateId);
    assertEquals("c8", started.adapterId(), "the start row has to name this adapter");
    return started;

  }

  private TaskDelivery startRowOf(
      final Long aggregateId) {

    return deliveryLog
        .workflowStartOf(THE_WORKFLOW_MODULE, THE_PROCESS, aggregateId.toString())
        .filter(row -> row.workflowId() != null)
        .orElse(null);

  }

  /**
   * Makes this node forget what it remembered about the workflow, which is the state of a
   * node that just started or never saw the start. The hint is read back first, so a key
   * which does not match what the election wrote cannot let this test pass by accident.
   */
  private void forgetTheHintAbout(
      final Long aggregateId) {

    assertTrue(
        electionCaches
            .stream()
            .anyMatch(cache -> cache.get(THE_WORKFLOW_MODULE, THE_PROCESS, aggregateId.toString()).isPresent()),
        "this node has to hold a hint, otherwise forgetting it proves nothing");
    electionCaches.forEach(cache -> cache.invalidate(THE_WORKFLOW_MODULE, THE_PROCESS, aggregateId.toString()));

  }

  private boolean searchFindsNoWorkflowOf(
      final long processInstanceKey) {

    return client()
        .newProcessInstanceSearchRequest()
        .filter(filter -> filter.processInstanceKey(processInstanceKey))
        .send()
        .join()
        .items()
        .isEmpty();

  }

  private String taskIdOf(
      final Long aggregateId) {

    return transactionTemplate
        .execute(status -> repository.findById(aggregateId).map(ReadModelDockerAggregate::getTaskId).orElse(null));

  }

  /**
   * The variables named 'note' of a workflow, as the query API reports them.
   */
  private List<Variable> notesOf(
      final Long processInstanceKey) {

    return client()
        .newVariableSearchRequest()
        .filter(filter -> filter
            .processInstanceKey(processInstanceKey)
            .name("note"))
        .send()
        .join()
        .items();

  }

  private CamundaClient client() {

    return clientFactoryRegistry
        .getFactory("c8")
        .getClient();

  }

  private static Duration timed(
      final Runnable action) {

    final var started = System.nanoTime();
    action.run();
    return Duration.ofNanos(System.nanoTime() - started);

  }

  private static void awaitUntil(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    // generous on purpose: the outbox dispatches every ten seconds, and a cold cluster
    // needs a while for its first jobs
    final var deadline = System.currentTimeMillis() + 120_000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(500);
    }

  }

  /**
   * Stops or restarts the exporter of the shared cluster through the management API of its
   * broker. The engine keeps running either way, only the read model stops following it.
   *
   * @param action <code>pause</code> or <code>resume</code>
   */
  private static void exporting(
      final String action) throws Exception {

    final var cluster = ClusterUnderTest.sharedCluster();
    final var request = HttpRequest
        .newBuilder(
            URI
                .create(
                    "http://%s:%d/actuator/exporting/%s"
                        .formatted(cluster.getHost(), cluster.getMappedPort(9600), action)))
        .POST(HttpRequest.BodyPublishers.noBody())
        .build();
    try (var http = HttpClient.newHttpClient()) {
      final var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertTrue(
          response.statusCode() < 300,
          "the broker has to accept '%s' of its exporter: %d %s"
              .formatted(action, response.statusCode(), response.body()));
    }

  }

}
