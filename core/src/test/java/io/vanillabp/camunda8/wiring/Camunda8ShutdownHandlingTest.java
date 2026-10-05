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
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.client.api.worker.JobClient;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * The rule all four worker kinds of this adapter share: a delivery which fails
 * while the workflow module is shutting down is not reported to the cluster as a job
 * failure. The job keeps its lock and its retries, so an ordinary restart does not walk a
 * job towards an incident it never earned.
 * <p>
 * The counter-test is in every case the same delivery OUTSIDE a shutdown, which still has
 * to be failed - what distinguishes the two is the adapter's state and never the kind of
 * exception, because a handler interrupted by the closing client throws like any other.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ShutdownHandlingTest {

  private final CamundaClient camundaClient = mock(CamundaClient.class, RETURNS_DEEP_STUBS);

  private final JobClient jobClient = mock(JobClient.class, RETURNS_DEEP_STUBS);

  private final Camunda8Drain drain = new Camunda8Drain("c8", "test-module");

  private ActivatedJob job(
      final String type) {

    final var job = mock(ActivatedJob.class);
    when(job.getKey()).thenReturn(4711L);
    when(job.getRetries()).thenReturn(3);
    when(job.getBpmnProcessId()).thenReturn("TestProcess");
    when(job.getType()).thenReturn(type);
    when(job.getVariablesAsMap()).thenReturn(Map.of("id", "42"));
    // the worker of a process' execution listeners answers two of them now, and the event
    // the job reports is what tells the end of an instance from its cancelation
    Mockito.lenient().when(job.getListenerEventType()).thenReturn(ListenerEventType.END);
    return job;

  }

  // --- the service task ---------------------------------------------------------------

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

  private WorkflowTaskInvoker failingTaskInvoker() {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.resolveWorkflowAggregateIdName("test-module", "TestProcess")).thenReturn("id");
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("interrupted by the shutdown"));
    return invoker;

  }

  @Test
  @DisplayName("A task failing outside a shutdown is failed with one retry less")
  public void aTaskFailingIsReported() {

    jobHandler(failingTaskInvoker()).handle(jobClient, job("someTask"));

    verify(jobClient.newFailCommand(4711L), times(1)).retries(2);

  }

  @Test
  @DisplayName("A task failing while shutting down is left to its lock")
  public void aTaskFailingDuringTheShutdownIsLeftAlone() {

    drain.beginShutdown();

    jobHandler(failingTaskInvoker()).handle(jobClient, job("someTask"));

    verify(jobClient, never()).newFailCommand(anyLong());

  }

  @Test
  @DisplayName("A running task is what the drain waits for, and only while it runs")
  public void aRunningTaskIsInFlight() {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.resolveWorkflowAggregateIdName("test-module", "TestProcess")).thenReturn("id");
    when(invoker.syncedWorkflowAggregateValues(anyString(), anyString(), anyString(), any()))
        .thenReturn(Map.of());
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any()))
        .thenAnswer(invocation -> {
          final var inFlight = drain.getInFlight();
          assertEquals(1, inFlight.size(), "the handler registered its delivery");
          assertEquals(4711L, inFlight.iterator().next().jobKey());
          assertEquals("someTask", inFlight.iterator().next().name());
          return WorkflowTaskOutcome.completed();
        });

    jobHandler(invoker).handle(jobClient, job("someTask"));

    assertTrue(drain.getInFlight().isEmpty(), "and deregistered it when it returned");

  }

  @Test
  @DisplayName("A task which failed on its way back is deregistered as well")
  public void aFailedCompletionDeregisters() {

    drain.beginShutdown();
    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.resolveWorkflowAggregateIdName("test-module", "TestProcess")).thenReturn("id");
    when(invoker.syncedWorkflowAggregateValues(anyString(), anyString(), anyString(), any()))
        .thenReturn(Map.of());
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any()))
        .thenReturn(WorkflowTaskOutcome.completed());
    when(jobClient.newCompleteCommand(4711L)).thenThrow(new IllegalStateException("the client is closing"));

    jobHandler(invoker).handle(jobClient, job("someTask"));

    assertTrue(drain.getInFlight().isEmpty(), "nothing is left behind for the drain to wait for");

  }

  // --- the user-task lifecycle listener -----------------------------------------------

  private Camunda8UserTaskListenerHandler listenerHandler(
      final WorkflowTaskInvoker invoker) {

    return Camunda8UserTaskListenerHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .workflowTaskInvoker(invoker)
        .drain(drain)
        .build();

  }

  private WorkflowTaskInvoker failingListenerInvoker() {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.workflowTaskHandlerExists(anyString(), anyString(), anyString())).thenReturn(true);
    when(invoker.resolveWorkflowAggregateIdName("test-module", "TestProcess")).thenReturn("id");
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("interrupted by the shutdown"));
    return invoker;

  }

  private ActivatedJob listenerJob() {

    final var job = job(Camunda8TaskWiring.TASKDEFINITION_USERTASK_ZEEBE
        + "someUserTask");
    // a listener job always carries its event, and the handler serves the two the
    // deployment added - see Camunda8UnknownClientEnumsTest for what it does with a third
    when(job.getListenerEventType()).thenReturn(ListenerEventType.CREATING);
    return job;

  }

  @Test
  @DisplayName("A listener failing outside a shutdown raises an incident, whatever the model left it")
  public void aListenerFailingIsReported() {

    listenerHandler(failingListenerInvoker()).handle(jobClient, listenerJob());

    // the model gives such a listener one attempt, so that a delivery the gateway lost
    // comes back. A notification which really failed gets none of it: the handler asks for
    // zero retries and the first failure is the incident
    verify(jobClient.newFailCommand(4711L), times(1)).retries(0);

  }

  @Test
  @DisplayName("A listener failing while shutting down raises no incident")
  public void aListenerFailingDuringTheShutdownRaisesNoIncident() {

    drain.beginShutdown();

    listenerHandler(failingListenerInvoker()).handle(jobClient, listenerJob());

    verify(jobClient, never()).newFailCommand(anyLong());
    assertTrue(drain.getInFlight().isEmpty(), "and the drain does not wait for it any more");

  }

  // --- the start event the cluster fires itself ---------------------------------------

  private Camunda8BpmsInitiatedStartHandler startHandler() {

    final var invoker = mock(BpmsInitiatedStartInvoker.class);
    when(invoker.startWorkflowByBpms(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("interrupted by the shutdown"));
    return new Camunda8BpmsInitiatedStartHandler(
        "c8", "test-module", "TestProcess", "Event_Timer", BpmsStartTrigger.Kind.TIMER, null, invoker, drain, null);

  }

  @Test
  @DisplayName("A start listener failing outside a shutdown is failed with one retry less")
  public void aStartListenerFailingIsReported() {

    startHandler().handle(jobClient, job("startEvent"));

    verify(jobClient.newFailCommand(4711L), times(1)).retries(2);

  }

  @Test
  @DisplayName("A start listener failing while shutting down is left to its lock")
  public void aStartListenerFailingDuringTheShutdownIsLeftAlone() {

    drain.beginShutdown();

    startHandler().handle(jobClient, job("startEvent"));

    verify(jobClient, never()).newFailCommand(anyLong());

  }

  // --- the end of a workflow ------------------------------------------------------------

  private Camunda8WorkflowEndedHandler endedHandler() {

    final var invoker = mock(WorkflowEndedInvoker.class);
    Mockito
        .doThrow(new IllegalStateException("interrupted by the shutdown"))
        .when(invoker)
        .workflowEnded(anyString(), anyString(), any());
    return new Camunda8WorkflowEndedHandler("c8", "test-module", "TestProcess", "id", invoker, drain, null);

  }

  @Test
  @DisplayName("A workflow-end listener failing outside a shutdown is failed with one retry less")
  public void anEndListenerFailingIsReported() {

    endedHandler().handle(jobClient, job("workflowEnded"));

    verify(jobClient.newFailCommand(4711L), times(1)).retries(2);

  }

  @Test
  @DisplayName("A workflow-end listener failing while shutting down is left to its lock")
  public void anEndListenerFailingDuringTheShutdownIsLeftAlone() {

    drain.beginShutdown();

    endedHandler().handle(jobClient, job("workflowEnded"));

    verify(jobClient, never()).newFailCommand(anyLong());
    assertTrue(drain.getInFlight().isEmpty());

  }

}
