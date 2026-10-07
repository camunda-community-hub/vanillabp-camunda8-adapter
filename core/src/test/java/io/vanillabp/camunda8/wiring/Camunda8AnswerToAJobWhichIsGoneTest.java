package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ClientStatusException;
import io.camunda.client.api.command.ProblemException;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.client.api.worker.JobClient;
import io.grpc.Status;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration.AsyncTaskMaxAgeAction;
import io.vanillabp.camunda8.client.Camunda8CommandRetry;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartResult;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * An answer to a job the cluster no longer holds.
 * <p>
 * A handler works while the cluster moves on. A redelivery of the same job may answer it
 * first, or a boundary event or a cancelation may end its element or its workflow. The answer
 * of this handler then meets a job which is gone, and the cluster says so with HTTP 404, on
 * gRPC with <code>NOT_FOUND</code>. That is a late answer and not a failure. Before, four
 * places read it as one: the listener completions answered it with a fail command, which met
 * the same 404 and escaped, and the fail commands and the BPMN error of the task handler let
 * it escape directly.
 * <p>
 * Each place gets its test here. The cluster's answers come from mocks, so the race is forced
 * rather than waited for. The counter-test matters as much: a 400 to a completion is a refusal
 * of the request itself and still ends in a fail command.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8AnswerToAJobWhichIsGoneTest {

  private final CamundaClient camundaClient = mock(CamundaClient.class, RETURNS_DEEP_STUBS);

  private final JobClient jobClient = mock(JobClient.class, RETURNS_DEEP_STUBS);

  private final Camunda8Drain drain = new Camunda8Drain("c8", "test-module");

  private static ProblemException problem(
      final int status) {

    final var details = new ProblemDetail();
    details.setStatus(status);
    return new ProblemException(status, "Failed with code %d".formatted(status), details);

  }

  private static ProblemException gone() {

    return problem(404);

  }

  private static ActivatedJob job() {

    final var job = mock(ActivatedJob.class);
    when(job.getKey()).thenReturn(4711L);
    when(job.getRetries()).thenReturn(3);
    when(job.getBpmnProcessId()).thenReturn("TestProcess");
    when(job.getType()).thenReturn("someJob");
    when(job.getVariablesAsMap()).thenReturn(Map.of("id", "42"));
    // a minute of lock left, so nothing but the answer of the cluster ends a command
    when(job.getDeadline()).thenReturn(System.currentTimeMillis() + 60_000);
    Mockito.lenient().when(job.getListenerEventType()).thenReturn(ListenerEventType.END);
    return job;

  }

  private void theJobIsGoneWhenItIsCompleted() {

    when(jobClient.newCompleteCommand(4711L)).thenThrow(gone());

  }

  private void theJobIsGoneWhenItIsFailed() {

    when(jobClient.newFailCommand(4711L)).thenThrow(gone());

  }

  // --- the shared send -----------------------------------------------------------------

  private static boolean sendAndSee(
      final RuntimeException answer) {

    return Camunda8CommandRetry
        .sendUnlessTheJobIsGone(
            "c8",
            "completion",
            4711L,
            "someJob",
            System.currentTimeMillis() + 60_000,
            () -> false,
            () -> {
              throw answer;
            });

  }

  @Test
  @DisplayName("A command to a job which is gone reports that, over both transports")
  public void aJobWhichIsGoneIsReportedAndNotThrown() {

    assertFalse(sendAndSee(gone()), "a 404 over REST is a job which is gone");
    assertFalse(
        sendAndSee(new ClientStatusException(Status.NOT_FOUND, null)),
        "and so is NOT_FOUND over gRPC");
    assertTrue(
        Camunda8CommandRetry.sendUnlessTheJobIsGone("c8", "completion", 4711L, "someJob", 0, () -> false, () -> {
        }),
        "a command the cluster took is reported as taken");

  }

  @Test
  @DisplayName("A 400 to a command which is not a lock renewal still escapes")
  public void aRefusalOfTheRequestItselfStillEscapes() {

    final var refusal = problem(400);

    final var escaped = assertThrows(RuntimeException.class, () -> sendAndSee(refusal));

    assertSame(refusal, escaped, "a request the cluster cannot read is not a job which is gone");

  }

  // --- a listener job, as the user-task listeners and an extension answer it -----------

  private boolean runTheListener(
      final Camunda8ListenerJobs.ListenerWork work) {

    final var followUpRan = new AtomicBoolean();
    Camunda8ListenerJobs
        .completeOrFail(
            "c8",
            jobClient,
            job(),
            drain,
            "test listener",
            "someJob",
            "TestProcess",
            () -> Camunda8ListenerJobs.Failure.NO_RETRIES_LEFT,
            work,
            () -> followUpRan.set(true));
    return followUpRan.get();

  }

  @Test
  @DisplayName("A listener whose job is gone at the completion is not failed")
  public void aListenerCompletionWhichFindsNoJobIsNotAFailure() {

    theJobIsGoneWhenItIsCompleted();

    final var followUpRan = runTheListener(Map::of);

    verify(jobClient, never()).newFailCommand(anyLong());
    assertFalse(followUpRan, "the follow-up belongs to whoever answered the job, or to nobody");
    assertTrue(drain.getInFlight().isEmpty(), "and the drain does not wait for it any more");

  }

  @Test
  @DisplayName("A listener which failed and whose job is gone by then ends without an exception")
  public void aListenerFailureWhichFindsNoJobDoesNotEscape() {

    theJobIsGoneWhenItIsFailed();

    assertDoesNotThrow(() -> runTheListener(() -> {
      throw new IllegalStateException("the listener failed");
    }));

    verify(jobClient).newFailCommand(4711L);

  }

  @Test
  @DisplayName("A listener completion refused with 400 still fails the job")
  public void aListenerCompletionRefusedForItsRequestStillFailsTheJob() {

    when(jobClient.newCompleteCommand(4711L)).thenThrow(problem(400));

    runTheListener(Map::of);

    verify(jobClient).newFailCommand(4711L);

  }

  // --- the end of a workflow -----------------------------------------------------------

  private Camunda8WorkflowEndedHandler endedHandler(
      final boolean notificationFails) {

    final var invoker = mock(WorkflowEndedInvoker.class);
    if (notificationFails) {
      Mockito
          .doThrow(new IllegalStateException("the notification failed"))
          .when(invoker)
          .workflowEnded(anyString(), anyString(), any());
    }
    return new Camunda8WorkflowEndedHandler("c8", "test-module", "TestProcess", "id", invoker, drain, null);

  }

  @Test
  @DisplayName("A workflow-end listener whose job is gone at the completion is not failed")
  public void anEndListenerCompletionWhichFindsNoJobIsNotAFailure() {

    theJobIsGoneWhenItIsCompleted();

    assertDoesNotThrow(() -> endedHandler(false).handle(jobClient, job()));

    verify(jobClient, never()).newFailCommand(anyLong());

  }

  @Test
  @DisplayName("A workflow-end listener which failed and whose job is gone by then ends without an exception")
  public void anEndListenerFailureWhichFindsNoJobDoesNotEscape() {

    theJobIsGoneWhenItIsFailed();

    assertDoesNotThrow(() -> endedHandler(true).handle(jobClient, job()));

    verify(jobClient).newFailCommand(4711L);

  }

  // --- the start event the cluster fires itself ----------------------------------------

  private Camunda8BpmsInitiatedStartHandler startHandler(
      final boolean buildFails) {

    final var invoker = mock(BpmsInitiatedStartInvoker.class);
    if (buildFails) {
      when(invoker.startWorkflowByBpms(anyString(), anyString(), any()))
          .thenThrow(new IllegalStateException("the aggregate could not be built"));
    } else {
      when(invoker.startWorkflowByBpms(anyString(), anyString(), any()))
          .thenReturn(new BpmsInitiatedStartResult("42", "id", Map.of("id", "42"), true));
    }
    return new Camunda8BpmsInitiatedStartHandler(
        "c8", "test-module", "TestProcess", "Event_Timer", BpmsStartTrigger.Kind.TIMER, null, invoker, drain, null);

  }

  @Test
  @DisplayName("A start-event listener whose job is gone at the completion is not failed")
  public void aStartListenerCompletionWhichFindsNoJobIsNotAFailure() {

    theJobIsGoneWhenItIsCompleted();

    assertDoesNotThrow(() -> startHandler(false).handle(jobClient, job()));

    verify(jobClient, never()).newFailCommand(anyLong());

  }

  @Test
  @DisplayName("A start-event listener which failed and whose job is gone by then ends without an exception")
  public void aStartListenerFailureWhichFindsNoJobDoesNotEscape() {

    theJobIsGoneWhenItIsFailed();

    assertDoesNotThrow(() -> startHandler(true).handle(jobClient, job()));

    verify(jobClient).newFailCommand(4711L);

  }

  // --- a service task ------------------------------------------------------------------

  private Camunda8JobHandler taskHandler(
      final WorkflowTaskInvoker invoker) {

    return Camunda8JobHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .camundaClient(camundaClient)
        .workflowTaskInvoker(invoker)
        .asyncTaskLockRenewal(Duration.ofHours(1))
        .asyncTaskMaxAgeAction(AsyncTaskMaxAgeAction.INCIDENT)
        .drain(drain)
        .build();

  }

  private static WorkflowTaskInvoker invokerReturning(
      final WorkflowTaskOutcome outcome) {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.resolveWorkflowAggregateIdName("test-module", "TestProcess")).thenReturn("id");
    when(invoker.syncedWorkflowAggregateValues(anyString(), anyString(), anyString(), any()))
        .thenReturn(Map.of());
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any())).thenReturn(outcome);
    return invoker;

  }

  @Test
  @DisplayName("A task which failed and whose job is gone by then ends without an exception")
  public void aTaskFailureWhichFindsNoJobDoesNotEscape() {

    theJobIsGoneWhenItIsFailed();
    final var invoker = invokerReturning(null);
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("the business method failed"));

    assertDoesNotThrow(() -> taskHandler(invoker).handle(jobClient, job()));

    verify(jobClient).newFailCommand(4711L);

  }

  @Test
  @DisplayName("A BPMN error which finds the job gone ends without an exception")
  public void aBpmnErrorWhichFindsNoJobDoesNotEscape() {

    when(jobClient.newThrowErrorCommand(4711L)).thenThrow(gone());

    assertDoesNotThrow(
        () -> taskHandler(invokerReturning(WorkflowTaskOutcome.bpmnError("PAYMENT_FAILED", "PaymentFailed")))
            .handle(jobClient, job()));

    verify(jobClient).newThrowErrorCommand(4711L);
    verify(jobClient, never()).newFailCommand(anyLong());

  }

  @Test
  @DisplayName("An overdue task whose job was completed a moment ago ends without an exception")
  public void anOverdueFailureWhichFindsNoJobDoesNotEscape() {

    theJobIsGoneWhenItIsFailed();

    assertDoesNotThrow(
        () -> taskHandler(invokerReturning(WorkflowTaskOutcome.completionPending(Duration.ofDays(31), true)))
            .handle(jobClient, job()));

    verify(jobClient).newFailCommand(4711L);
    // the overdue path ends the renewal, gone job or not
    verify(camundaClient, never()).newUpdateTimeoutCommand(anyLong());
    verify(jobClient, never()).newCompleteCommand(anyLong());

  }

}
