package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ProblemException;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.client.api.worker.JobClient;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartResult;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * The retry at the handler: each of the commands a worker sends BACK to the cluster is
 * repeated where the cluster rejected it because it was busy, and none of them is repeated
 * where a repetition cannot change the answer. What makes the difference is the
 * classification of {@code Camunda8Errors} and nothing the handlers decide themselves.
 * <p>
 * The counter-tests matter as much as the tests: a job which is GONE must stay the benign
 * at-least-once residual it was rather than becoming five commands, and a shutdown during a
 * retry must still end without a fail command.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8OutcomeCommandRetryTest {

  private final CamundaClient camundaClient = mock(CamundaClient.class, RETURNS_DEEP_STUBS);

  private final JobClient jobClient = mock(JobClient.class, RETURNS_DEEP_STUBS);

  private final Camunda8Drain drain = new Camunda8Drain("c8", "test-module");

  /**
   * How the REST transport reports backpressure - the failure the retry exists for.
   */
  private static ProblemException backpressure() {

    final var details = new ProblemDetail();
    details.setStatus(503);
    details.setTitle("RESOURCE_EXHAUSTED");
    return new ProblemException(503, "RESOURCE_EXHAUSTED", details);

  }

  private static ProblemException problem(
      final int status) {

    final var details = new ProblemDetail();
    details.setStatus(status);
    return new ProblemException(status, "reason", details);

  }

  /**
   * Answers a command's {@code send()}: the first <code>rejections</code> calls are
   * rejected, the rest go through. The response is a mock of whatever the client's release
   * line declares, so this test does not name a type which changed between them.
   */
  private static Answer<Object> rejecting(
      final AtomicInteger attempts,
      final int rejections,
      final RuntimeException failure) {

    return invocation -> {
      if (attempts.incrementAndGet() <= rejections) {
        throw failure;
      }
      return mock(invocation.getMethod().getReturnType());
    };

  }

  private ActivatedJob job() {

    final var job = mock(ActivatedJob.class);
    when(job.getKey()).thenReturn(4711L);
    when(job.getRetries()).thenReturn(3);
    when(job.getBpmnProcessId()).thenReturn("TestProcess");
    when(job.getType()).thenReturn("someTask");
    when(job.getVariablesAsMap()).thenReturn(Map.of("id", "42"));
    // a minute of lock left, so the attempt count is what bounds the retry
    when(job.getDeadline()).thenReturn(System.currentTimeMillis() + 60_000);
    return job;

  }

  private Camunda8JobHandler jobHandler(
      final WorkflowTaskInvoker invoker) {

    return Camunda8JobHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .camundaClient(camundaClient)
        .workflowTaskInvoker(invoker)
        .asyncTaskLockRenewal(Duration.ofHours(1))
        .drain(drain)
        .build();

  }

  private WorkflowTaskInvoker invokerReturning(
      final WorkflowTaskOutcome outcome) {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.resolveWorkflowAggregateIdName("test-module", "TestProcess")).thenReturn("id");
    when(invoker.syncedWorkflowAggregateValues(anyString(), anyString(), anyString(), any()))
        .thenReturn(Map.of());
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any())).thenReturn(outcome);
    return invoker;

  }

  // --- the completion -------------------------------------------------------------------

  @Test
  @DisplayName("A completion rejected for backpressure is repeated instead of failing the job")
  public void aRejectedCompletionIsRepeated() {

    final var attempts = new AtomicInteger();
    when(jobClient.newCompleteCommand(4711L).variables(any(Map.class)).send())
        .thenAnswer(rejecting(attempts, 2, backpressure()));

    jobHandler(invokerReturning(WorkflowTaskOutcome.completed())).handle(jobClient, job());

    assertEquals(3, attempts.get(), "two rejections and the completion which went through");
    verify(jobClient, never()).newFailCommand(anyLong());

  }

  @Test
  @DisplayName("A completion the cluster refuses is not repeated")
  public void aRefusedCompletionIsNotRepeated() {

    final var attempts = new AtomicInteger();
    when(jobClient.newCompleteCommand(4711L).variables(any(Map.class)).send())
        .thenAnswer(rejecting(attempts, Integer.MAX_VALUE, problem(400)));

    // and the failure escapes to the client, which fails the job - a retry which gave up
    // changes nothing about that
    Assertions.assertThrows(
        ProblemException.class,
        () -> jobHandler(invokerReturning(WorkflowTaskOutcome.completed())).handle(jobClient, job()));

    assertEquals(1, attempts.get());

  }

  @Test
  @DisplayName("A job which was already completed stays a tolerated no-op, not a retry storm")
  public void aGoneJobIsStillTolerated() {

    final var attempts = new AtomicInteger();
    when(jobClient.newCompleteCommand(4711L).variables(any(Map.class)).send())
        .thenAnswer(rejecting(attempts, Integer.MAX_VALUE, problem(404)));

    // tolerated: the handler returns normally although every attempt was rejected
    jobHandler(invokerReturning(WorkflowTaskOutcome.completed())).handle(jobClient, job());

    assertEquals(1, attempts.get());
    verify(jobClient, never()).newFailCommand(anyLong());

  }

  @Test
  @DisplayName("A shutdown during a retry still leaves the job to its lock")
  public void aShutdownDuringARetryFailsNothing() {

    final var attempts = new AtomicInteger();
    when(jobClient.newCompleteCommand(4711L).variables(any(Map.class)).send())
        .thenAnswer(invocation -> {
          // the module goes down while the handler is on its way back
          drain.beginShutdown();
          attempts.incrementAndGet();
          throw backpressure();
        });

    jobHandler(invokerReturning(WorkflowTaskOutcome.completed())).handle(jobClient, job());

    assertEquals(1, attempts.get(), "the retry gives way to the shutdown");
    verify(jobClient, never()).newFailCommand(anyLong());
    assertTrue(drain.getInFlight().isEmpty());

  }

  // --- the BPMN error -------------------------------------------------------------------

  @Test
  @DisplayName("A BPMN error rejected for backpressure is repeated")
  public void aRejectedBpmnErrorIsRepeated() {

    final var attempts = new AtomicInteger();
    when(
        jobClient
            .newThrowErrorCommand(4711L)
            .errorCode("PAYMENT_FAILED")
            .errorMessage(anyString())
            .variables(any(Map.class))
            .send())
        .thenAnswer(rejecting(attempts, 2, backpressure()));

    jobHandler(invokerReturning(WorkflowTaskOutcome.bpmnError("PAYMENT_FAILED", "PaymentFailed")))
        .handle(jobClient, job());

    assertEquals(3, attempts.get());

  }

  // --- the failure ----------------------------------------------------------------------

  private WorkflowTaskInvoker failingInvoker(
      final RuntimeException failure) {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.resolveWorkflowAggregateIdName("test-module", "TestProcess")).thenReturn("id");
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any())).thenThrow(failure);
    return invoker;

  }

  @Test
  @DisplayName("A fail command rejected for backpressure is repeated")
  public void aRejectedFailureIsRepeated() {

    final var attempts = new AtomicInteger();
    when(
        jobClient
            .newFailCommand(4711L)
            .retries(2)
            .retryBackoff(any(Duration.class))
            .errorMessage(anyString())
            .send())
        .thenAnswer(rejecting(attempts, 2, backpressure()));

    jobHandler(failingInvoker(new IllegalStateException("boom"))).handle(jobClient, job());

    assertEquals(3, attempts.get());

  }

  @Test
  @DisplayName("A failed job carries the default backoff, so it is not handed out again at once")
  public void aFailedJobCarriesTheBackoff() {

    jobHandler(failingInvoker(new IllegalStateException("boom"))).handle(jobClient, job());

    verify(jobClient.newFailCommand(4711L).retries(2), times(1))
        .retryBackoff(Camunda8RetryBackoffResolver.DEFAULT_RETRY_BACKOFF);

  }

  @Test
  @DisplayName("A configured retry-backoff reaches the fail command")
  public void aConfiguredBackoffReachesTheCommand() {

    Camunda8JobHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .camundaClient(camundaClient)
        .workflowTaskInvoker(failingInvoker(new IllegalStateException("boom")))
        .asyncTaskLockRenewal(Duration.ofHours(1))
        .drain(drain)
        .retryBackoffResolver((
            module,
            process,
            task) -> new Camunda8RetryBackoffResolver.Configured(Duration.ofSeconds(42), false))
        .build()
        .handle(jobClient, job());

    verify(jobClient.newFailCommand(4711L).retries(2), times(1)).retryBackoff(Duration.ofSeconds(42));

  }

  @Test
  @DisplayName("The incident names the exception's type as well as its message")
  public void theIncidentNamesTheExceptionType() {

    jobHandler(failingInvoker(new NullPointerException())).handle(jobClient, job());

    verify(jobClient.newFailCommand(4711L).retries(2).retryBackoff(any(Duration.class)), times(1))
        .errorMessage(argThat(message -> "java.lang.NullPointerException".equals(message)));

  }

  // --- the workers which serve no task ---------------------------------------------------

  @Test
  @DisplayName("A start-event listener repeats its completion and backs its failure off")
  public void theStartEventListenerFollowsTheSameRule() {

    final var attempts = new AtomicInteger();
    when(jobClient.newCompleteCommand(4711L).variables(any(Map.class)).send())
        .thenAnswer(rejecting(attempts, 2, backpressure()));
    final var invoker = mock(BpmsInitiatedStartInvoker.class);
    when(invoker.startWorkflowByBpms(anyString(), anyString(), any()))
        .thenReturn(
            new BpmsInitiatedStartResult(
                "42", "id", Map.of("id", "42"), true));

    new Camunda8BpmsInitiatedStartHandler(
        "c8", "test-module", "TestProcess", "Event_Timer", BpmsStartTrigger.Kind.TIMER, null, invoker, drain, null)
        .handle(jobClient, job());

    assertEquals(3, attempts.get());

  }

  @Test
  @DisplayName("A workflow-end listener fails with a backoff, too")
  public void theWorkflowEndListenerBacksItsFailureOff() {

    final var invoker = mock(WorkflowEndedInvoker.class);
    Mockito
        .doThrow(new IllegalStateException("boom"))
        .when(invoker)
        .workflowEnded(anyString(), anyString(), any());

    final var endListenerJob = job();
    // the worker of a process' execution listeners answers two of them now, and the event
    // the job reports is what tells them apart
    when(endListenerJob.getListenerEventType()).thenReturn(ListenerEventType.END);

    new Camunda8WorkflowEndedHandler("c8", "test-module", "TestProcess", "id", invoker, drain, null)
        .handle(jobClient, endListenerJob);

    verify(jobClient.newFailCommand(4711L).retries(2), times(1))
        .retryBackoff(Camunda8RetryBackoffResolver.DEFAULT_RETRY_BACKOFF);

  }

  @Test
  @DisplayName("A user-task listener repeats its incident instead of losing it to backpressure")
  public void theUserTaskListenerRepeatsItsIncident() {

    final var attempts = new AtomicInteger();
    when(jobClient.newFailCommand(4711L).retries(0).errorMessage(anyString()).send())
        .thenAnswer(rejecting(attempts, 2, backpressure()));
    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.workflowTaskHandlerExists(anyString(), anyString(), anyString())).thenReturn(true);
    when(invoker.resolveWorkflowAggregateIdName("test-module", "TestProcess")).thenReturn("id");
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("boom"));

    final var listenerJob = job();
    when(listenerJob.getType())
        .thenReturn(Camunda8TaskWiring.TASKDEFINITION_USERTASK_ZEEBE
            + "someUserTask");
    when(listenerJob.getListenerEventType()).thenReturn(ListenerEventType.CREATING);

    Camunda8UserTaskListenerHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .workflowTaskInvoker(invoker)
        .drain(drain)
        .build()
        .handle(jobClient, listenerJob);

    assertEquals(3, attempts.get());

  }

}
