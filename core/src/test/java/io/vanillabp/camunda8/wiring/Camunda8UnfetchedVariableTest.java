package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.command.FailJobCommandStep1;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What happens when a delivery is asked for a variable its worker did not fetch. The
 * adapter cannot tell that case apart from a variable which is genuinely absent,
 * and both a <code>null</code> aggregate id and a <code>null</code>
 * <code>&#64;TaskParam</code> would be a loss nothing reports - so both name the fetch
 * list and the property which switches the restriction off.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8UnfetchedVariableTest {

  private final CamundaClient camundaClient = mock(CamundaClient.class, RETURNS_DEEP_STUBS);

  private final JobClient jobClient = mock(JobClient.class, RETURNS_DEEP_STUBS);

  private final WorkflowTaskInvoker invoker = mock(WorkflowTaskInvoker.class);

  private static final Camunda8FetchVariables.Selection DERIVED = Camunda8FetchVariables.Selection
      .of(List.of("id"));

  private ActivatedJob job(
      final Map<String, Object> variables) {

    final var job = mock(ActivatedJob.class);
    when(job.getKey()).thenReturn(4711L);
    when(job.getRetries()).thenReturn(3);
    when(job.getBpmnProcessId()).thenReturn("TestProcess");
    when(job.getType()).thenReturn("approve");
    when(job.getVariablesAsMap()).thenReturn(variables);
    return job;

  }

  private Camunda8JobHandler handler(
      final Camunda8FetchVariables.Selection selection) {

    return Camunda8JobHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .camundaClient(camundaClient)
        .workflowTaskInvoker(invoker)
        .asyncTaskLockRenewal(Duration.ofHours(1))
        .fetchVariables(selection)
        .build();

  }

  private TaskInvocationContext contextOf(
      final Camunda8FetchVariables.Selection selection) {

    when(invoker.resolveWorkflowAggregateIdName("test-module", "TestProcess")).thenReturn("id");
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any())).thenReturn(WorkflowTaskOutcome.completed());
    handler(selection).handle(jobClient, job(Map.of("id", "42")));
    final var context = ArgumentCaptor.forClass(TaskInvocationContext.class);
    verify(invoker).invokeWorkflowTask(anyString(), anyString(), context.capture());
    return context.getValue();

  }

  @Test
  @DisplayName("a @TaskParam the worker did fetch is answered as before")
  public void aFetchedTaskParameterIsAnswered() {

    final var context = contextOf(Camunda8FetchVariables.Selection.of(List.of("id")));

    assertEquals("42", context.getTaskParameter("id"));

  }

  @Test
  @DisplayName("a @TaskParam outside the fetch list fails the delivery naming the escape hatch")
  public void anUnfetchedTaskParameterFailsGuiding() {

    final var context = contextOf(DERIVED);

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> context.getTaskParameter("bigPayload"));

    assertTrue(exception.getMessage().contains("bigPayload"), exception.getMessage());
    assertTrue(exception.getMessage().contains("approve"), "names the task, but was: "
        + exception.getMessage());
    assertTrue(
        exception.getMessage().contains("vanillabp.adapters.c8.fetch-variables"),
        "names the property which switches the restriction off, but was: "
            + exception.getMessage());

  }

  @Test
  @DisplayName("a worker fetching everything answers every @TaskParam, absent ones with null")
  public void everythingKeepsTheOldBehaviour() {

    when(invoker.resolveWorkflowAggregateIdName("test-module", "TestProcess")).thenReturn("id");
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any())).thenReturn(WorkflowTaskOutcome.completed());
    handler(Camunda8FetchVariables.Selection.everything())
        .handle(jobClient, job(Map.of("id", "42", "bigPayload", "x")));
    final var context = ArgumentCaptor.forClass(TaskInvocationContext.class);
    verify(invoker).invokeWorkflowTask(anyString(), anyString(), context.capture());

    assertEquals("x", context.getValue().getTaskParameter("bigPayload"));
    assertNull(
        context.getValue().getTaskParameter("neverThere"),
        "a variable which is genuinely absent stays a null, which is what an optional @TaskParam is");

  }

  @Test
  @DisplayName("a missing aggregate-ID variable names the fetch list as the second possible cause")
  public void theMissingAggregateIdNamesTheFetchList() {

    when(invoker.resolveWorkflowAggregateIdName("test-module", "TestProcess")).thenReturn("id");
    final var failCommand = mock(FailJobCommandStep1.class, RETURNS_DEEP_STUBS);
    when(jobClient.newFailCommand(4711L)).thenReturn(failCommand);

    handler(DERIVED).handle(jobClient, job(Map.of()));

    final var message = ArgumentCaptor.forClass(String.class);
    verify(failCommand.retries(2).retryBackoff(any())).errorMessage(message.capture());
    assertTrue(message.getValue().contains("[id]"), "names what the worker fetches, but was: "
        + message.getValue());
    assertTrue(
        message.getValue().contains("vanillabp.adapters.c8.fetch-variables"),
        "names the property, but was: "
            + message.getValue());

  }

}
