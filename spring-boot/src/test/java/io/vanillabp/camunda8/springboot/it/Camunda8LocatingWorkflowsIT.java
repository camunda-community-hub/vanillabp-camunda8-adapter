package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.camunda.client.CamundaClient;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Locating a running workflow by its aggregate's id, which is what the adapter requires a
 * searchable cluster for: the election probe, the correlation which depends on it, and the
 * viewer.
 * <p>
 * The search is what a filter matching nothing used to break silently. The probe answered
 * "no BPMS knows this workflow" for every workflow, and every operation electing its BPMS
 * by probing failed on real clusters while all tests were green, because no test ran
 * against a cluster the probe could search at all.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8LocatingWorkflowsIT extends SpringBootTestOnTheSharedCluster {

  @DynamicPropertySource
  static void camunda8Properties(
      final DynamicPropertyRegistry registry) {

    // the correlation right after the start waits for two things to happen: the
    // phase-two outbox dispatching the start (poll interval 10s) and the exporter
    // feeding the query API. The production default of 10s covers the export lag of
    // a warm cluster, not a cold container plus a poll interval
    registry.add("vanillabp.adapters.c8.workflow-visibility-timeout", () -> "PT60S");

  }

  @Autowired
  private SecondaryStorageDockerWorkflowService workflowService;

  @Autowired
  private SecondaryStorageDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @Autowired
  private List<MigratableProcessService<?>> processServices;

  @Test
  @DisplayName("The adapter reports that it can locate workflows, which is what a searchable cluster is for")
  public void theAdapterReportsItCanLocateWorkflows() {

    // the answer comes from the probe rather than from a constant, and a cluster which
    // would answer 'no' never got the application this far
    assertTrue(
        processServices
            .getFirst()
            .canLocateWorkflows(),
        "this cluster can be asked which workflows it holds");

  }

  private CamundaClient client() {

    return clientFactoryRegistry
        .getFactory("c8")
        .getClient();

  }

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    // generous: the cluster exports to Elasticsearch before the query API can answer,
    // and that pipeline is the slowest part of the test
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
   * Whether the query API knows the workflow of the aggregate - the very search the
   * adapter's awareness probe runs, with the value encoded the way the cluster stores
   * it (JSON, so a String carries its quotes).
   */
  private boolean queryApiKnows(
      final Long aggregateId) {

    return !client()
        .newProcessInstanceSearchRequest()
        .filter(filter -> filter.variables(Map.of("loanRequestId", "\"%s\"".formatted(aggregateId))))
        .send()
        .join()
        .items()
        .isEmpty();

  }

  private Long startedWorkflow() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow().getLoanRequestId());
    assertNotNull(aggregateId);
    awaitUntil(() -> queryApiKnows(aggregateId), "the query API to know the started workflow");
    return aggregateId;

  }

  @Test
  @DisplayName("a message reaches the workflow although the BPMS was found by probing")
  public void theProbeFindsTheWorkflow() throws Exception {

    final var aggregateId = startedWorkflow();

    // before the fix this threw WorkflowNotFoundException: the probe searched the
    // aggregate-ID variable without its JSON quotes, matched nothing, and reported
    // that no BPMS knows the workflow
    transactionTemplate.executeWithoutResult(status -> workflowService.correlate(aggregateId));

    awaitUntil(
        () -> "messageArrived".equals(
            repository
                .findById(aggregateId)
                .map(SecondaryStorageDockerAggregate::getProcessedBy)
                .orElse(null)),
        "the task behind the message catch event to run");

  }

  @Test
  @DisplayName("correlating RIGHT AFTER the start works - the core waits out the query API's lag")
  public void correlatingRightAfterTheStartWorks() throws Exception {

    // deliberately NO awaitUntil(queryApiKnows(...)) here: this is the everyday
    // sequence "start a workflow, then correlate the message which lets it
    // continue", which must not fail with a WorkflowNotFoundException just because
    // the exporter has not fed the query API yet
    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow().getLoanRequestId());
    assertNotNull(aggregateId);

    transactionTemplate.executeWithoutResult(status -> workflowService.correlate(aggregateId));

    awaitUntil(
        () -> "messageArrived".equals(
            repository
                .findById(aggregateId)
                .map(SecondaryStorageDockerAggregate::getProcessedBy)
                .orElse(null)),
        "the task behind the message catch event to run");

  }

  @Test
  @DisplayName("the viewer RIGHT AFTER the start works - a read waits out the query API's lag too")
  public void theViewerRightAfterTheStartWorks() {

    // again deliberately NO awaitUntil(queryApiKnows(...)): a viewer opened by the
    // application which just started the workflow is as everyday as the correlation
    // above, and unlike it there is no outbox entry behind the call which could ask
    // again later - the read waits for the exporter or the application sees an error
    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow().getLoanRequestId());
    assertNotNull(aggregateId);

    final var history = transactionTemplate.execute(status -> workflowService.historyOf(aggregateId));

    assertNotNull(history, "the history of a workflow started moments ago is served, not refused");
    assertNotNull(history.startTime(), "the workflow is running, so it has a start time");

  }

  @Test
  @DisplayName("an ENDED workflow is still served - it is completed, not unknown")
  public void anEndedWorkflowIsStillServed() throws Exception {

    final var aggregateId = startedWorkflow();

    transactionTemplate.executeWithoutResult(status -> workflowService.correlate(aggregateId));
    awaitUntil(
        () -> "messageArrived".equals(
            repository
                .findById(aggregateId)
                .map(SecondaryStorageDockerAggregate::getProcessedBy)
                .orElse(null)),
        "the task behind the message catch event to run");

    // the instance ends right after that task, and the exporter needs a moment to say so
    awaitUntil(
        () -> {
          final var history = transactionTemplate.execute(status -> workflowService.historyOf(aggregateId));
          return (history != null) && (history.endTime() != null);
        },
        "the query API to report the workflow as ended");

    // the probe must answer COMPLETED here rather than UNKNOWN_TO_BPMS: an ended
    // workflow still has definitions and a history, and 'unknown' is what makes the
    // core raise WorkflowNotFoundException
    final var definitions = transactionTemplate
        .execute(status -> workflowService.definitionsOf(aggregateId));
    assertNotNull(definitions);
    assertFalse(definitions.isEmpty(), "an ended workflow still names the definition it ran on");

  }

  @Test
  @DisplayName("the viewer finds the workflow through the same search")
  public void theViewerFindsTheWorkflow() throws Exception {

    final var aggregateId = startedWorkflow();

    final var definitions = transactionTemplate
        .execute(status -> workflowService.definitionsOf(aggregateId));

    assertNotNull(definitions);
    assertFalse(
        definitions.isEmpty(),
        "the viewer locates the workflow by the same variable filter as the probe");

  }

}
