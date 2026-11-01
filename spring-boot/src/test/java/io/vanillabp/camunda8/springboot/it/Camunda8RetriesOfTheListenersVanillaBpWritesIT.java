package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.IncidentErrorType;
import io.camunda.client.api.search.enums.IncidentState;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import io.camunda.client.api.search.response.Incident;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The listeners VanillaBP writes into a model try once, against a real cluster.
 * <p>
 * The adapter writes two execution listeners into every process it claims: one on each start
 * event, which builds the workflow aggregate, and one on the process, which calls the
 * <code>&#64;WorkflowEnded</code> method. Both may write. In VanillaBP a job is retried only
 * where the model asks for it. Nobody modelled these listeners, so nobody asked. A listener
 * without a <code>retries</code> attribute gets the default of the cluster, which is three.
 * So the model carries one retry, which only a lost delivery uses, and a failure leaves none.
 * See decision 83 in the repository's DECISIONS.md.
 * <p>
 * Only a cluster shows how often it hands out a failed job. So this class counts the calls of
 * a method which fails, and reads the incident. Then it does what an operator does. It gives
 * the job a retry and resolves the incident. The method does not fail any more, so the
 * workflow ends. This also leaves nothing behind for the next class.
 * <p>
 * The class is skipped when Docker is unavailable.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
public class Camunda8RetriesOfTheListenersVanillaBpWritesIT extends SpringBootTestOnTheSharedCluster {

  /**
   * The process id the cluster knows. The workflow module of this test runs under
   * <code>name-clash-avoidance: use-prefix</code>.
   */
  private static final String PROCESS_ID = "test-app__OwnListenerRetriesProcess";

  /**
   * Long enough for three attempts with the <code>retry-backoff</code> of this workflow module,
   * twenty seconds each. So a red run says how often the job was handed out, and does not
   * just time out.
   */
  private static final long INCIDENT_WITHIN_MILLIS = 120_000;

  @Autowired
  private OwnListenerRetriesDockerWorkflowService workflowService;

  @Autowired
  private OwnListenerRetriesDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @AfterEach
  void theMethodsDoNotFailForTheNextTest() {

    // the cleanup of the shared cluster cancels what a red test left. On the 8.10 line that
    // runs the cancel listener, which calls the @WorkflowEnded method once more
    OwnListenerRetriesDockerWorkflowService.THE_START_FAILS.set(false);
    OwnListenerRetriesDockerWorkflowService.THE_END_FAILS.set(false);

  }

  @Test
  @DisplayName("A failing @WorkflowEnded method is called once, and the incident follows")
  public void aFailingEndIsCalledOnce() throws Exception {

    OwnListenerRetriesDockerWorkflowService.ENDS_REPORTED.set(0);
    OwnListenerRetriesDockerWorkflowService.THE_END_FAILS.set(true);

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow().getId());

    final var incident = awaitTheIncident();
    assertEquals(
        1,
        OwnListenerRetriesDockerWorkflowService.ENDS_REPORTED.get(),
        "the end listener was handed out once and not again: nobody modelled a retry for it");
    assertEquals(IncidentErrorType.EXECUTION_LISTENER_NO_RETRIES, incident.getErrorType());

    OwnListenerRetriesDockerWorkflowService.THE_END_FAILS.set(false);
    whatAnOperatorDoes(incident);

    awaitUntil(
        () -> "COMPLETED".equals(transactionTemplate
            .execute(status -> repository.findById(aggregateId).orElseThrow().getEndedAs())),
        "the end of the workflow to be reported once the operator gave the job a retry");
    awaitTheEndOf(incident.getProcessInstanceKey());

  }

  @Test
  @DisplayName("Building the aggregate of a start by the cluster is tried once, and the incident follows")
  public void aFailingStartIsTriedOnce() throws Exception {

    OwnListenerRetriesDockerWorkflowService.STARTS_ASKED_FOR.set(0);
    OwnListenerRetriesDockerWorkflowService.THE_START_FAILS.set(true);

    // started without VanillaBP. So the workflow has no name yet, and the start listener asks
    // the application to build its aggregate
    client()
        .newCreateInstanceCommand()
        .bpmnProcessId(PROCESS_ID)
        .latestVersion()
        .send()
        .join();

    final var incident = awaitTheIncident();
    assertEquals(
        1,
        OwnListenerRetriesDockerWorkflowService.STARTS_ASKED_FOR.get(),
        "the start listener was handed out once and not again: nobody modelled a retry for it");
    assertEquals(IncidentErrorType.EXECUTION_LISTENER_NO_RETRIES, incident.getErrorType());
    assertEquals("OwnListenerRetriesStart", incident.getElementId(), "the incident stands at the start event");

    OwnListenerRetriesDockerWorkflowService.THE_START_FAILS.set(false);
    whatAnOperatorDoes(incident);

    awaitTheEndOf(incident.getProcessInstanceKey());
    assertEquals(
        2,
        OwnListenerRetriesDockerWorkflowService.STARTS_ASKED_FOR.get(),
        "the job came back once, because the operator gave it one retry");

  }

  /**
   * Gives the failed job one retry and resolves its incident. That hands the job out again.
   */
  private void whatAnOperatorDoes(
      final Incident incident) {

    client()
        .newUpdateRetriesCommand(incident.getJobKey())
        .retries(1)
        .send()
        .join();
    client()
        .newResolveIncidentCommand(incident.getIncidentKey())
        .send()
        .join();

  }

  private Incident awaitTheIncident() throws InterruptedException {

    final var deadline = System.currentTimeMillis() + INCIDENT_WITHIN_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      final var incidents = client()
          .newIncidentSearchRequest()
          .filter(filter -> filter
              .processDefinitionId(PROCESS_ID)
              .state(IncidentState.ACTIVE))
          .send()
          .join()
          .items();
      if (!incidents.isEmpty()) {
        return incidents.getFirst();
      }
      Thread.sleep(500);
    }
    throw new AssertionError(
        "no incident within %d seconds. The method was called %d times at the end and %d times at the start"
            .formatted(
                INCIDENT_WITHIN_MILLIS / 1000,
                OwnListenerRetriesDockerWorkflowService.ENDS_REPORTED.get(),
                OwnListenerRetriesDockerWorkflowService.STARTS_ASKED_FOR.get()));

  }

  private void awaitTheEndOf(
      final long processInstanceKey) throws InterruptedException {

    awaitUntil(
        () -> {
          try {
            return client()
                .newProcessInstanceGetRequest(processInstanceKey)
                .send()
                .join()
                .getState() == ProcessInstanceState.COMPLETED;
          } catch (final RuntimeException notSearchableYet) {
            return false;
          }
        },
        "the workflow to complete once the operator gave the job a retry");

  }

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 90_000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for "
            + description);
      }
      Thread.sleep(500);
    }

  }

  private CamundaClient client() {

    return clientFactoryRegistry
        .getFactory("c8")
        .getClient();

  }

}
