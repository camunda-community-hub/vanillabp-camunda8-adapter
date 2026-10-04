package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import io.camunda.client.api.search.response.Variable;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * End-to-end test of pushing a changed aggregate against a real Camunda 8.
 * <p>
 * Camunda 8 has no business key and no command addressing a workflow by one of its
 * variables, so a search is the only way from an aggregate ID to the process-instance and
 * element-instance keys {@code SetVariables} needs. Which is one of the reasons the
 * adapter requires a cluster it can search rather than serving half of this.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8AggregateChangedIT extends SpringBootTestOnTheSharedCluster {

  @Autowired
  private PushDockerWorkflowService workflowService;

  @Autowired
  private PushDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  private CamundaClient client() {

    return clientFactoryRegistry
        .getFactory("c8")
        .getClient();

  }

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    // generous on purpose: this cluster exports to Elasticsearch before the query
    // API can answer, and that pipeline is the slowest part of the test
    final var deadline = System.currentTimeMillis() + 240_000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(500);
    }

  }

  /**
   * The variables named 'note' of the workflow of the given aggregate, as the query
   * API reports them (with the scope they belong to).
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

  /**
   * The workflow of the given aggregate, as the cluster knows it.
   * <p>
   * The aggregate id alone does not name it. Every class of this module has a database of its
   * own, so their aggregate ids all start at 1, and the cluster they share holds what the
   * classes before this one left. So the search is bound to a process this class owns, and
   * the caller says which of its two it means.
   *
   * @param bpmnProcessId The process as the CLUSTER knows it, prefixed by the workflow module
   * @param aggregateId The aggregate whose workflow is looked for
   * @return The process instance key, or <code>null</code> while the search does not know it
   */
  private Long processInstanceKeyOf(
      final String bpmnProcessId,
      final Long aggregateId) {

    final var found = client()
        .newProcessInstanceSearchRequest()
        // variable values are stored as JSON: a String value is searched WITH its quotes
        .filter(filter -> filter
            .processDefinitionId(bpmnProcessId)
            .variables(Map.of("id", "\"%s\"".formatted(aggregateId)))
            .state(ProcessInstanceState.ACTIVE))
        .send()
        .join()
        .items();
    return found.isEmpty()
        ? null
        : found.getFirst().getProcessInstanceKey();

  }

  /**
   * The process of the test which pushes at the workflow's own scope, as the cluster knows
   * it.
   */
  private static final String THE_PUSH_PROCESS = "test-app__AggregateChangedProcess";

  /**
   * The process of the test which pushes into the scope of one iteration, as the cluster
   * knows it.
   */
  private static final String THE_MULTI_INSTANCE_PUSH_PROCESS = "test-app__AggregateChangedMultiInstanceProcess";

  /**
   * The process of the test which pushes into a subprocess whose task was created while the
   * exporter stood still, as the cluster knows it. A timer of twenty seconds lies between
   * the start and the subprocess, so the exporter can be stopped after the workflow became
   * searchable and before the task exists.
   */
  private static final String THE_PUSH_AFTER_TIMER_PROCESS = "test-app__AggregateChangedAfterTimerProcess";

  /**
   * The element instance of the task itself - the scope a push must NOT write into.
   */
  private Long elementInstanceKeyOfTask(
      final String taskId) {

    return client()
        .newJobSearchRequest()
        .filter(filter -> filter.jobKey(Long.parseLong(taskId)))
        .send()
        .join()
        .items()
        .getFirst()
        .getElementInstanceKey();

  }

  private String taskIdsOf(
      final Long aggregateId) {

    return transactionTemplate
        .execute(status -> repository.findById(aggregateId).map(PushDockerAggregate::getTaskIds).orElse(null));

  }

  @Test
  @DisplayName("a global push updates the workflow's own scope")
  public void aGlobalPushWritesTheWorkflowScope() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow().getId());
    assertNotNull(aggregateId);

    awaitUntil(() -> taskIdsOf(aggregateId) != null, "the workflow to park at its asynchronous task");
    awaitUntil(
        () -> processInstanceKeyOf(THE_PUSH_PROCESS, aggregateId) != null,
        "the query API to know the instance");
    final var processInstanceKey = processInstanceKeyOf(THE_PUSH_PROCESS, aggregateId);

    transactionTemplate
        .executeWithoutResult(status -> workflowService.pushGlobally(aggregateId, "pushed-globally"));

    awaitUntil(
        () -> notesOf(processInstanceKey)
            .stream()
            .anyMatch(variable -> variable.getValue().contains("pushed-globally")),
        "the pushed value to arrive at the cluster");

    final var notes = notesOf(processInstanceKey);
    assertEquals(1, notes.size(), "a global push updates the existing variable instead of adding a scope");
    assertEquals(
        processInstanceKey,
        notes.getFirst().getScopeKey(),
        "the value has to live at the workflow's own scope");

  }

  @Test
  @DisplayName("a task-scoped push lands in the scope the task runs in, not at the workflow's")
  public void aTaskScopedPushReachesTheEnclosingScope() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.saveAggregate().getId());
    assertNotNull(aggregateId);

    // the multi-instance process is started against the cluster: the injectable
    // process service starts the primary process only
    client()
        .newCreateInstanceCommand()
        .bpmnProcessId(THE_MULTI_INSTANCE_PUSH_PROCESS)
        .latestVersion()
        // one call: the client's variable() replaces what a previous call set
        .variables(Map.of("id", String.valueOf(aggregateId), "note", "before"))
        .send()
        .join();

    awaitUntil(
        () -> {
          final var taskIds = taskIdsOf(aggregateId);
          return (taskIds != null) && (taskIds.split(",").length == 2);
        },
        "both iterations of the multi-instance subprocess to park");

    final var taskIds = taskIdsOf(aggregateId).split(",");
    awaitUntil(
        () -> processInstanceKeyOf(THE_MULTI_INSTANCE_PUSH_PROCESS, aggregateId) != null,
        "the query API to know the instance");
    final var processInstanceKey = processInstanceKeyOf(THE_MULTI_INSTANCE_PUSH_PROCESS, aggregateId);

    transactionTemplate
        .executeWithoutResult(status -> workflowService.pushInto(aggregateId, "pushed-locally", taskIds[0]));

    awaitUntil(
        () -> notesOf(processInstanceKey)
            .stream()
            .anyMatch(variable -> variable.getValue().contains("pushed-locally")),
        "the pushed value to arrive at the cluster");

    final var notes = notesOf(processInstanceKey);
    final var local = notes
        .stream()
        .filter(variable -> variable.getValue().contains("pushed-locally"))
        .toList();
    assertEquals(1, local.size(), "exactly ONE scope may see the pushed value");
    assertNotEquals(
        processInstanceKey,
        local.getFirst().getScopeKey(),
        "a task-scoped push may not land at the workflow's scope");
    assertNotEquals(
        elementInstanceKeyOfTask(taskIds[0]),
        local.getFirst().getScopeKey(),
        "and not in the task's own element instance, which disappears with the task");
    assertTrue(
        notes
            .stream()
            .anyMatch(variable -> (variable.getScopeKey().equals(processInstanceKey)) && variable.getValue()
                .contains("before")),
        "the workflow's global value stays as it was - the honest consequence of scoping");

  }

  @Test
  @DisplayName("a task-scoped push waits for a task the read model does not know yet")
  public void aTaskScopedPushWaitsWhileTheExporterStandsStill() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.saveAggregate().getId());
    assertNotNull(aggregateId);
    client()
        .newCreateInstanceCommand()
        .bpmnProcessId(THE_PUSH_AFTER_TIMER_PROCESS)
        .latestVersion()
        .variables(Map.of("id", String.valueOf(aggregateId), "note", "before"))
        .send()
        .join();
    awaitUntil(
        () -> processInstanceKeyOf(THE_PUSH_AFTER_TIMER_PROCESS, aggregateId) != null,
        "the query API to know the instance");
    final var processInstanceKey = processInstanceKeyOf(THE_PUSH_AFTER_TIMER_PROCESS, aggregateId);

    exporting("pause");
    try {
      awaitUntil(() -> taskIdsOf(aggregateId) != null, "the task behind the timer to park");
      final var taskId = taskIdsOf(aggregateId);
      assertTrue(
          client()
              .newJobSearchRequest()
              .filter(filter -> filter.jobKey(Long.parseLong(taskId)))
              .send()
              .join()
              .items()
              .isEmpty(),
          "the read model must not know the task yet, otherwise this test proves nothing");

      transactionTemplate
          .executeWithoutResult(
              status -> workflowService.pushInto(aggregateId, "pushed-while-the-exporter-stands", taskId));

      // longer than the visibility window of ten seconds: the push used to give up after
      // it, and the value then never arrived
      Thread.sleep(25_000);
    } finally {
      exporting("resume");
    }

    awaitUntil(
        () -> notesOf(processInstanceKey)
            .stream()
            .anyMatch(variable -> variable.getValue().contains("pushed-while-the-exporter-stands")),
        "the pushed value to arrive at the cluster once the exporter caught up");
    final var pushed = notesOf(processInstanceKey)
        .stream()
        .filter(variable -> variable.getValue().contains("pushed-while-the-exporter-stands"))
        .toList();
    assertEquals(1, pushed.size(), "exactly ONE scope may see the pushed value");
    assertNotEquals(
        processInstanceKey,
        pushed.getFirst().getScopeKey(),
        "the value belongs to the subprocess the task runs in, not to the workflow's scope");

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
