package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ClientHttpException;
import io.camunda.client.api.command.ClientStatusException;
import io.camunda.client.api.command.ProblemException;
import io.camunda.client.api.command.UpdateTimeoutJobCommandStep1;
import io.grpc.Status;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A task whose job waits in the queue is a task the cluster HOLDS.
 * <p>
 * An asynchronous task keeps its job locked for <code>async-task-lock-renewal</code>, and
 * when that hour passes the cluster puts the job back into the queue until a worker takes it
 * again. A probe arriving in that gap is refused with HTTP <code>400</code> respectively
 * gRPC <code>INVALID_ARGUMENT</code>, which used to fall through to
 * {@link WorkflowAwareness#BPMS_UNAVAILABLE} and sent the caller into retries for a task
 * which was perfectly alive.
 * <p>
 * Measured on 8.8.37, 8.9.19 and 8.10.0-alpha5 with the same answer on all three lines, for
 * a job never activated, a job whose lock ran out and a job with an open incident.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8DormantTaskProbeTest {

  private static final WorkflowScope SCOPE = WorkflowScope.of("test-module", "TestProcess");

  private static final String TASK_ID = "2251799813685249";

  private final CamundaClient client = mock(CamundaClient.class);

  @Test
  @DisplayName("A job the cluster refuses as not active is a task the cluster holds, over REST")
  public void aDormantJobIsReportedAsActiveOverRest() {

    theClusterRefusesTheJobTimeoutUpdate(
        problem(400, "INVALID_ARGUMENT", "Expected to update job timeout, but it is not active"));

    assertEquals(
        WorkflowAwareness.ACTIVE,
        aService().awarenessOfTask(SCOPE, "agg-1", TASK_ID),
        "a job waiting in the queue is a task the BPMS has");

  }

  @Test
  @DisplayName("The same answer over gRPC says the same thing")
  public void aDormantJobIsReportedAsActiveOverGrpc() {

    theClusterRefusesTheJobTimeoutUpdate(
        new ClientStatusException(
            Status.INVALID_ARGUMENT.withDescription("but it is not active"), null));

    assertEquals(
        WorkflowAwareness.ACTIVE,
        aService().awarenessOfTask(SCOPE, "agg-1", TASK_ID),
        "the transport does not change what the cluster said");

  }

  @Test
  @DisplayName("A job the cluster does not hold is still unknown to it")
  public void aJobWhichIsGoneKeepsItsAnswer() {

    theClusterRefusesTheJobTimeoutUpdate(
        new ClientHttpException("Failed with code 404", 404, "job not found"));

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        aService().awarenessOfTask(SCOPE, "agg-1", TASK_ID),
        "the answer of a gone job is untouched by this");

  }

  @Test
  @DisplayName("A cluster which did not answer at all is still an outage")
  public void aClusterWhichDidNotAnswerIsStillAnOutage() {

    theClusterRefusesTheJobTimeoutUpdate(new IllegalStateException("connection reset"));

    assertEquals(
        WorkflowAwareness.BPMS_UNAVAILABLE,
        aService().awarenessOfTask(SCOPE, "agg-1", TASK_ID),
        "only what the cluster SAID is read as an answer about the task");

  }

  /**
   * A process service alone on its cluster, so the probe goes straight to its command
   * instead of asking the query API which scope the key belongs to.
   */
  private Camunda8ProcessService<?> aService() {

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing ever contacts - every request of this test meets the mock above
    configuration.setRestAddress("http://localhost:1");
    configuration.setWorkflowVisibilityTimeout(Duration.ZERO);
    final var clientFactory = new Camunda8ClientFactory("c8", configuration) {

      @Override
      public CamundaClient getClient() {
        return client;
      }

      @Override
      public boolean sharesItsCluster() {
        return false;
      }

    };
    return new Camunda8ProcessService<Object>(
        "c8", clientFactory, Duration.ofDays(14), (
            aggregateClass,
            check) -> check.run(), null);

  }

  private void theClusterRefusesTheJobTimeoutUpdate(
      final RuntimeException rejection) {

    final var command = mock(
        UpdateTimeoutJobCommandStep1.UpdateTimeoutJobCommandStep2.class,
        RETURNS_SELF);
    Mockito.lenient().when(command.send()).thenThrow(rejection);
    final var step1 = mock(UpdateTimeoutJobCommandStep1.class, RETURNS_SELF);
    Mockito.lenient().when(step1.timeout(Mockito.any(Duration.class))).thenReturn(command);
    Mockito.lenient().when(client.newUpdateTimeoutCommand(Mockito.anyLong())).thenReturn(step1);

  }

  /**
   * A cluster's REST answer, as the client hands it on.
   */
  private static ProblemException problem(
      final int status,
      final String title,
      final String reason) {

    final var details = new ProblemDetail();
    details.setStatus(status);
    details.setTitle(title);
    return new ProblemException(status, reason, details);

  }

}
