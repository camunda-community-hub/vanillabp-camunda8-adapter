package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.mockito.ArgumentCaptor;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.command.FailJobCommandStep1;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration.AsyncTaskMaxAgeAction;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the job handler does with a task the core reports as
 * still open, and with one it reports as older than
 * <code>vanillabp.delivery.max-task-age</code> allows.
 * <ul>
 * <li>an open task has its lock renewed for the configured window - every redelivery
 * renews it again, which is what keeps the job alive without any timer of VanillaBP's
 * own;</li>
 * <li>an overdue task is renewed as well while the action is <code>report</code>: the
 * core already said so, and a cluster is not the place to decide that a task waiting for
 * a person is a defect;</li>
 * <li>with <code>incident</code> the renewal stops and the job is failed without retries
 * left, so the cluster raises an incident naming the workflow aggregate and the age.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8AsyncTaskAgeTest {

  private static final Duration RENEWAL = Duration.ofHours(1);

  private final CamundaClient camundaClient = mock(CamundaClient.class, RETURNS_DEEP_STUBS);

  private final JobClient jobClient = mock(JobClient.class, RETURNS_DEEP_STUBS);

  private final WorkflowTaskInvoker invoker = mock(WorkflowTaskInvoker.class);

  private ActivatedJob job() {

    final var job = mock(ActivatedJob.class);
    when(job.getKey()).thenReturn(4711L);
    when(job.getBpmnProcessId()).thenReturn("TestProcess");
    when(job.getType()).thenReturn("asyncTask");
    when(job.getVariablesAsMap()).thenReturn(Map.of("id", "42"));
    return job;

  }

  private Camunda8JobHandler handler(
      final AsyncTaskMaxAgeAction action) {

    return Camunda8JobHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .camundaClient(camundaClient)
        .workflowTaskInvoker(invoker)
        .asyncTaskLockRenewal(RENEWAL)
        .asyncTaskMaxAgeAction(action)
        .build();

  }

  private void given(
      final WorkflowTaskOutcome outcome) {

    when(invoker.resolveWorkflowAggregateIdName("test-module", "TestProcess")).thenReturn("id");
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any())).thenReturn(outcome);

  }

  @Test
  @DisplayName("An open task has its lock renewed for the configured window")
  public void anOpenTaskIsRenewed() {

    given(WorkflowTaskOutcome.completionPending());

    handler(AsyncTaskMaxAgeAction.REPORT).handle(jobClient, job());

    verify(camundaClient.newUpdateTimeoutCommand(4711L), times(1)).timeout(RENEWAL);
    verify(jobClient, never()).newFailCommand(anyLong());

  }

  @Test
  @DisplayName("An overdue task is renewed as well while the action is report")
  public void anOverdueTaskIsRenewedWhileReporting() {

    given(WorkflowTaskOutcome.completionPending(Duration.ofDays(31), true));

    handler(AsyncTaskMaxAgeAction.REPORT).handle(jobClient, job());

    verify(camundaClient.newUpdateTimeoutCommand(4711L), times(1)).timeout(RENEWAL);
    verify(jobClient, never()).newFailCommand(anyLong());

  }

  @Test
  @DisplayName("With incident the renewal stops and the job is failed without retries left")
  public void anOverdueTaskBecomesAnIncident() {

    given(WorkflowTaskOutcome.completionPending(Duration.ofDays(31), true));
    final var failCommand = mock(FailJobCommandStep1.class, RETURNS_DEEP_STUBS);
    when(jobClient.newFailCommand(4711L)).thenReturn(failCommand);

    handler(AsyncTaskMaxAgeAction.INCIDENT).handle(jobClient, job());

    verify(failCommand, times(1)).retries(0);
    final var message = ArgumentCaptor.forClass(String.class);
    verify(failCommand.retries(0)).errorMessage(message.capture());
    assertTrue(message.getValue().contains("asyncTask"), "names the task");
    assertTrue(message.getValue().contains("42"), "names the workflow aggregate");
    assertTrue(message.getValue().contains("TestProcess"), "names the BPMN process");
    assertTrue(message.getValue().contains("test-module"), "names the workflow module");
    assertTrue(message.getValue().contains("PT744H"), "names the age");
    assertTrue(
        message.getValue().contains("vanillabp.adapters.c8.async-task-max-age-action"),
        "names the property which asked for the incident");

    // the whole point: an incident replaces the renewal, it does not accompany it
    verify(camundaClient.newUpdateTimeoutCommand(4711L), never()).timeout(any());

  }

  @Test
  @DisplayName("A task nobody reported as overdue never becomes an incident")
  public void aYoungTaskIsNeverAnIncident() {

    given(WorkflowTaskOutcome.completionPending(Duration.ofDays(1), false));

    handler(AsyncTaskMaxAgeAction.INCIDENT).handle(jobClient, job());

    verify(camundaClient.newUpdateTimeoutCommand(4711L), times(1)).timeout(RENEWAL);
    verify(jobClient, never()).newFailCommand(anyLong());
    assertEquals(
        AsyncTaskMaxAgeAction.INCIDENT,
        AsyncTaskMaxAgeAction.valueOf("INCIDENT"),
        "the action is configured by name");

  }

}
