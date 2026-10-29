package io.vanillabp.camunda8.wiring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.command.FailJobCommandStep1;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.response.FailJobResponse;
import io.camunda.client.api.search.enums.JobKind;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.client.api.worker.JobClient;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * A job which arrives with no retries left is failed with none left, by every worker of this
 * adapter. Counting down from zero would send -1. The cluster takes that on a failure today
 * and raises the incident. But -1 is no count the cluster stands behind: it refuses anything
 * below one when the retries of a job are updated. And the log line would tell the operator
 * about -1 retries.
 * <p>
 * Such a job reaches a worker where the model writes <code>retries="0"</code> on a task or
 * on a listener. An operator cannot bring a job back with none: the cluster refuses to
 * resolve the incident of a job without retries. {@code Camunda8NegativeRetriesCanaryIT}
 * measures both against the cluster.
 * <p>
 * Each case also checks that no backoff is sent. There is no next attempt to delay.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8RetryCountNeverBelowZeroTest {

  private final CamundaClient camundaClient = mock(CamundaClient.class, RETURNS_DEEP_STUBS);

  private final JobClient jobClient = mock(JobClient.class);

  private final Camunda8Drain drain = new Camunda8Drain("c8", "test-module");

  /**
   * The second step of the fail command, which records what it was told. Deep stubs would
   * hand a new mock to every argument. A call which did NOT happen could then not be told
   * from one on another mock.
   */
  private final FailJobCommandStep1.FailJobCommandStep2 failCommand = failCommandOf(jobClient);

  private static FailJobCommandStep1.FailJobCommandStep2 failCommandOf(
      final JobClient client) {

    final var step1 = mock(FailJobCommandStep1.class);
    final var step2 = mock(FailJobCommandStep1.FailJobCommandStep2.class, RETURNS_SELF);
    when(client.newFailCommand(anyLong())).thenReturn(step1);
    when(step1.retries(anyInt())).thenReturn(step2);
    @SuppressWarnings("unchecked")
    final CamundaFuture<FailJobResponse> sent = mock(CamundaFuture.class);
    when(step2.send()).thenReturn(sent);
    return step2;

  }

  private static ActivatedJob jobWithNoRetriesLeft(
      final String type) {

    final var job = mock(ActivatedJob.class);
    when(job.getKey()).thenReturn(4711L);
    when(job.getRetries()).thenReturn(0);
    when(job.getBpmnProcessId()).thenReturn("TestProcess");
    when(job.getProcessDefinitionVersion()).thenReturn(7);
    when(job.getElementId()).thenReturn("TheElement");
    when(job.getType()).thenReturn(type);
    when(job.getVariablesAsMap()).thenReturn(Map.of("id", "42"));
    when(job.getCustomHeaders()).thenReturn(Map.of());
    when(job.getDeadline()).thenReturn(System.currentTimeMillis() + 60_000);
    Mockito.lenient().when(job.getKind()).thenReturn(JobKind.EXECUTION_LISTENER);
    Mockito.lenient().when(job.getListenerEventType()).thenReturn(ListenerEventType.END);
    return job;

  }

  private void verifyFailedWithNoRetriesLeft() {

    verify(jobClient.newFailCommand(4711L)).retries(0);
    verify(failCommand).errorMessage(anyString());
    verify(failCommand, never()).retryBackoff(any(Duration.class));

  }

  private static WorkflowTaskInvoker failingTaskInvoker() {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.resolveWorkflowAggregateIdName(anyString(), anyString())).thenReturn("id");
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("the method of the application threw"));
    return invoker;

  }

  @Test
  @DisplayName("A start listener with no retries left is failed with none")
  public void theStartListener() {

    final var invoker = mock(BpmsInitiatedStartInvoker.class);
    when(invoker.startWorkflowByBpms(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("the aggregate could not be built"));

    new Camunda8BpmsInitiatedStartHandler(
        "c8", "test-module", "TestProcess", "Event_Timer", BpmsStartTrigger.Kind.TIMER, null, invoker, drain, null, null)
        .handle(jobClient, jobWithNoRetriesLeft("startEvent"));

    verifyFailedWithNoRetriesLeft();

  }

  @Test
  @DisplayName("A workflow-end listener with no retries left is failed with none")
  public void theWorkflowEndListener() {

    final var invoker = mock(WorkflowEndedInvoker.class);
    Mockito
        .doThrow(new IllegalStateException("the method of the application threw"))
        .when(invoker)
        .workflowEnded(anyString(), anyString(), any());

    new Camunda8WorkflowEndedHandler("c8", "test-module", "TestProcess", "id", invoker, drain, null, null)
        .handle(jobClient, jobWithNoRetriesLeft("workflowEnded"));

    verifyFailedWithNoRetriesLeft();

  }

  @Test
  @DisplayName("A task whose model writes retries, with none left, is failed with none")
  public void aTaskWithRetriesInTheModel() {

    Camunda8JobHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .camundaClient(camundaClient)
        .workflowTaskInvoker(failingTaskInvoker())
        .asyncTaskLockRenewal(Duration.ofHours(1))
        .drain(drain)
        .modelledRetries((
            workflowModuleId,
            bpmnProcessId,
            version,
            elementId) -> Boolean.TRUE)
        .build()
        .handle(jobClient, jobWithNoRetriesLeft("someTask"));

    verifyFailedWithNoRetriesLeft();

  }

  @Test
  @DisplayName("A task whose model cannot be read, with none left, is failed with none")
  public void aTaskWithoutAModel() {

    Camunda8JobHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .camundaClient(camundaClient)
        .workflowTaskInvoker(failingTaskInvoker())
        .asyncTaskLockRenewal(Duration.ofHours(1))
        .drain(drain)
        .build()
        .handle(jobClient, jobWithNoRetriesLeft("someTask"));

    verifyFailedWithNoRetriesLeft();

  }

  @Test
  @DisplayName("A modelled listener which writes retries, with none left, is failed with none")
  public void aModelledListenerWithRetriesInTheModel() {

    Camunda8ModelledListenerHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .workflowTaskInvoker(failingTaskInvoker())
        .drain(drain)
        .modelledRetries((
            workflowModuleId,
            bpmnProcessId,
            version,
            elementId,
            jobType) -> Boolean.TRUE)
        .build()
        .handle(jobClient, jobWithNoRetriesLeft("archiveTheOrder"));

    verifyFailedWithNoRetriesLeft();

  }

}
