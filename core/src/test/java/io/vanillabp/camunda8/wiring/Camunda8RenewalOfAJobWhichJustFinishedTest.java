package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ClientStatusException;
import io.camunda.client.api.command.ProblemException;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.grpc.Status;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration.AsyncTaskMaxAgeAction;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The lock renewal of an asynchronous task which meets a job the application has just
 * completed.
 * <p>
 * The handler decides that the task stays open, and only then does it renew the lock. An
 * application which completes the task in between closes the job first, and the cluster
 * refuses the renewal. A main build met that race against a real cluster: HTTP 400,
 * "Expected to update the timeout of job with key ..., but it is not active". The test
 * forces the race instead of waiting for it. The cluster's answer comes from a mock, and
 * the handler has to go on as if the renewal had gone through, because there was nothing
 * left to renew.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8RenewalOfAJobWhichJustFinishedTest {

  private final CamundaClient camundaClient = mock(CamundaClient.class, RETURNS_DEEP_STUBS);

  private final JobClient jobClient = mock(JobClient.class, RETURNS_DEEP_STUBS);

  private final WorkflowTaskInvoker invoker = mock(WorkflowTaskInvoker.class);

  private ActivatedJob job() {

    final var job = mock(ActivatedJob.class);
    when(job.getKey()).thenReturn(4711L);
    when(job.getBpmnProcessId()).thenReturn("TestProcess");
    when(job.getType()).thenReturn("asyncTask");
    when(job.getVariablesAsMap()).thenReturn(Map.of("id", "42"));
    // a lock far ahead, so the renewal is not stopped by the lock running out first
    when(job.getDeadline()).thenReturn(System.currentTimeMillis() + 60_000);
    return job;

  }

  private Camunda8JobHandler handler() {

    when(invoker.resolveWorkflowAggregateIdName("test-module", "TestProcess")).thenReturn("id");
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any()))
        .thenReturn(WorkflowTaskOutcome.completionPending());
    return Camunda8JobHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .camundaClient(camundaClient)
        .workflowTaskInvoker(invoker)
        .asyncTaskLockRenewal(Duration.ofHours(1))
        .asyncTaskMaxAgeAction(AsyncTaskMaxAgeAction.REPORT)
        .build();

  }

  private void theClusterAnswersTheRenewalWith(
      final RuntimeException refusal) {

    when(camundaClient
        .newUpdateTimeoutCommand(4711L)
        .timeout(any(Duration.class))
        .send()
        .join())
        .thenThrow(refusal);

  }

  private static ProblemException problem(
      final int status,
      final String title) {

    final var details = new ProblemDetail();
    details.setStatus(status);
    details.setTitle(title);
    return new ProblemException(status, "Failed with code %d".formatted(status), details);

  }

  @Test
  @DisplayName("A job the application completed a moment ago ends the renewal without a failure")
  public void aJobWhichIsNotActiveAnyMoreIsNotRenewed() {

    // what the main build met: the cluster still holds the job, but nobody has it activated
    theClusterAnswersTheRenewalWith(problem(400, "INVALID_ARGUMENT"));

    assertDoesNotThrow(() -> handler().handle(jobClient, job()));

    verify(camundaClient.newUpdateTimeoutCommand(4711L), times(1)).timeout(any(Duration.class));
    verify(jobClient, never()).newFailCommand(anyLong());

  }

  @Test
  @DisplayName("The same refusal over gRPC ends the renewal the same way")
  public void theSameRefusalOverGrpcIsNotAFailure() {

    theClusterAnswersTheRenewalWith(new ClientStatusException(Status.INVALID_ARGUMENT, null));

    assertDoesNotThrow(() -> handler().handle(jobClient, job()));

    verify(jobClient, never()).newFailCommand(anyLong());

  }

  @Test
  @DisplayName("A job the cluster does not hold any more ends the renewal without a failure")
  public void aJobWhichIsGoneIsNotRenewed() {

    // the same race a moment later: the completed job is gone from the cluster
    theClusterAnswersTheRenewalWith(problem(404, "NOT_FOUND"));

    assertDoesNotThrow(() -> handler().handle(jobClient, job()));

    theClusterAnswersTheRenewalWith(new ClientStatusException(Status.NOT_FOUND, null));

    assertDoesNotThrow(() -> handler().handle(jobClient, job()));

    verify(jobClient, never()).newFailCommand(anyLong());

  }

  @Test
  @DisplayName("Any other refusal of the renewal still escapes")
  public void anyOtherRefusalStillEscapes() {

    final var refusal = problem(403, "FORBIDDEN");
    theClusterAnswersTheRenewalWith(refusal);

    final var handler = handler();
    final var job = job();
    final var escaped = assertThrows(RuntimeException.class, () -> handler.handle(jobClient, job));

    assertSame(refusal, escaped, "the refusal reaches the client unchanged, as it did before");

  }

}
