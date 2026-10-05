package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.client.api.worker.JobClient;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The two identities this adapter reports about one delivery, and why they are two. The
 * core asks for both and uses them for opposite purposes: the DELIVERY identity has to
 * stay equal while the cluster hands the same job out again, so a repeated delivery is
 * answered from its record; the ACTIVATION identity has to differ between two activations
 * of one element, so the correlations two multi-instance siblings plan do not share an
 * idempotency key.
 * <p>
 * On Camunda 8 the job key would satisfy both today, which is exactly why this is pinned:
 * the element instance key is the honest answer to the second question, and a job created
 * a second time for ONE element must never read as a new element.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ActivationIdentityTest {

  private final CamundaClient camundaClient = mock(CamundaClient.class, RETURNS_DEEP_STUBS);

  private final JobClient jobClient = mock(JobClient.class, RETURNS_DEEP_STUBS);

  private final Camunda8Drain drain = new Camunda8Drain("c8", "test-module");

  /**
   * Delivers one job through the adapter and answers the context the core was handed,
   * which is the boundary this adapter is tested at.
   */
  private TaskInvocationContext deliveredContext(
      final ActivatedJob job) {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.resolveWorkflowAggregateIdName(anyString(), anyString())).thenReturn("id");
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any())).thenReturn(WorkflowTaskOutcome.completed());
    Camunda8JobHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .camundaClient(camundaClient)
        .workflowTaskInvoker(invoker)
        .asyncTaskLockRenewal(Duration.ofHours(1))
        .drain(drain)
        .build()
        .handle(jobClient, job);
    return captured(invoker);

  }

  private TaskInvocationContext deliveredListenerContext(
      final ActivatedJob listenerJob) {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.resolveWorkflowAggregateIdName(anyString(), anyString())).thenReturn("id");
    when(invoker.workflowTaskHandlerExists(anyString(), anyString(), anyString())).thenReturn(true);
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any())).thenReturn(WorkflowTaskOutcome.completed());
    Camunda8UserTaskListenerHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .workflowTaskInvoker(invoker)
        .drain(drain)
        .build()
        .handle(jobClient, listenerJob);
    return captured(invoker);

  }

  private static TaskInvocationContext captured(
      final WorkflowTaskInvoker invoker) {

    final var context = ArgumentCaptor.forClass(TaskInvocationContext.class);
    Mockito
        .verify(invoker)
        .invokeWorkflowTask(anyString(), anyString(), context.capture());
    return context.getValue();

  }

  private ActivatedJob job(
      final long jobKey,
      final long elementInstanceKey) {

    final var job = mock(ActivatedJob.class);
    when(job.getKey()).thenReturn(jobKey);
    when(job.getElementInstanceKey()).thenReturn(elementInstanceKey);
    when(job.getRetries()).thenReturn(3);
    when(job.getBpmnProcessId()).thenReturn("TestProcess");
    when(job.getType()).thenReturn("someTask");
    when(job.getVariablesAsMap()).thenReturn(Map.of("id", "42"));
    return job;

  }

  @Test
  @DisplayName("Two elements of one multi-instance activity are two activations")
  public void twoElementsAreTwoActivations() {

    final var firstElement = deliveredContext(job(4711L, 100L));
    final var secondElement = deliveredContext(job(4712L, 101L));

    assertEquals("100", firstElement.getActivationId());
    assertEquals("101", secondElement.getActivationId());
    assertNotEquals(firstElement.getActivationId(), secondElement.getActivationId());

  }

  @Test
  @DisplayName("A second job for ONE element is a new delivery and the same activation")
  public void asecondJobOfOneElementKeepsTheActivation() {

    // the cluster creates a job per activation, and it can create another one for the
    // same element instance - an incident resolved, a task completed and re-entered by
    // the model. The delivery is new, the element instance is not
    final var firstJob = deliveredContext(job(4711L, 100L));
    final var secondJob = deliveredContext(job(4999L, 100L));

    assertNotEquals(firstJob.getDeliveryId(), secondJob.getDeliveryId());
    assertEquals(firstJob.getActivationId(), secondJob.getActivationId());

  }

  @Test
  @DisplayName("A redelivery of one job repeats both identities")
  public void aRedeliveryRepeatsBoth() {

    final var first = deliveredContext(job(4711L, 100L));
    final var redelivery = deliveredContext(job(4711L, 100L));

    assertEquals(first.getDeliveryId(), redelivery.getDeliveryId());
    assertEquals(first.getActivationId(), redelivery.getActivationId());

  }

  @Test
  @DisplayName("A user-task listener names the element instance of the user task")
  public void aUserTaskListenerNamesItsElementInstance() {

    final var listenerJob = mock(ActivatedJob.class);
    when(listenerJob.getKey()).thenReturn(5011L);
    when(listenerJob.getElementInstanceKey()).thenReturn(200L);
    when(listenerJob.getRetries()).thenReturn(3);
    when(listenerJob.getBpmnProcessId()).thenReturn("TestProcess");
    when(listenerJob.getVariablesAsMap()).thenReturn(Map.of("id", "42"));
    when(listenerJob.getType())
        .thenReturn(Camunda8TaskWiring.TASKDEFINITION_USERTASK_ZEEBE
            + "someUserTask");
    when(listenerJob.getListenerEventType()).thenReturn(ListenerEventType.CREATING);

    final var context = deliveredListenerContext(listenerJob);

    assertEquals("200", context.getActivationId());
    // the listener job is the delivery: creation and cancellation of one user task are
    // two deliveries with two outcomes, and one activation of one element
    assertEquals("5011", context.getDeliveryId());

  }

}
