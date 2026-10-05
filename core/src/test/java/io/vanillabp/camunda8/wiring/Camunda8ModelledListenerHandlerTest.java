package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.response.UserTaskProperties;
import io.camunda.client.api.search.enums.JobKind;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.client.api.worker.JobClient;
import io.vanillabp.camunda8.TestScoping;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.camunda8.client.Camunda8UserTaskProbe;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.TaskEvent;

/**
 * What the job of a listener somebody modelled tells the application's method, and what it
 * sends back to the cluster.
 * <p>
 * What it sends back has three cases and they are pinned one test each, because each of them
 * rests on a different reason: an execution listener on <code>end</code> writes into the
 * process instance like a task, an execution listener on <code>start</code> writes nothing
 * because its values would be local to the element, and a task listener writes nothing because
 * the cluster refuses the payload. The event a method is told is pinned too: a method without a
 * <code>@TaskEvent</code> parameter subscribes to CREATED alone, so CREATED is the only value
 * which reaches such a method at all.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ModelledListenerHandlerTest {

  private final JobClient jobClient = mock(JobClient.class, RETURNS_DEEP_STUBS);

  private final Camunda8Drain drain = new Camunda8Drain("c8", "test-module");

  private static ActivatedJob listenerJob(
      final String jobType) {

    return listenerJob(jobType, JobKind.EXECUTION_LISTENER, ListenerEventType.END);

  }

  private static ActivatedJob listenerJob(
      final String jobType,
      final JobKind kind,
      final ListenerEventType event) {

    final var job = mock(ActivatedJob.class);
    when(job.getKind()).thenReturn(kind);
    when(job.getListenerEventType()).thenReturn(event);
    when(job.getKey()).thenReturn(4711L);
    when(job.getElementInstanceKey()).thenReturn(100L);
    when(job.getRetries()).thenReturn(3);
    when(job.getProcessDefinitionVersion()).thenReturn(7);
    when(job.getBpmnProcessId()).thenReturn("TestProcess");
    when(job.getElementId()).thenReturn("Event_Done");
    when(job.getType()).thenReturn(jobType);
    when(job.getVariablesAsMap()).thenReturn(Map.of("id", "42", "amount", 120));
    return job;

  }

  private TaskInvocationContext deliver(
      final ActivatedJob job,
      final NameClashAvoidance mode,
      final WorkflowTaskOutcome outcome) {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.resolveWorkflowAggregateIdName(anyString(), anyString())).thenReturn("id");
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any())).thenReturn(outcome);
    when(invoker.syncedWorkflowAggregateValues(anyString(), anyString(), anyString(), any()))
        .thenReturn(Map.of("theOrderWasArchived", true));
    Camunda8ModelledListenerHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .workflowTaskInvoker(invoker)
        .scoping(TestScoping.of(mode))
        .drain(drain)
        .build()
        .handle(jobClient, job);
    final var context = ArgumentCaptor.forClass(TaskInvocationContext.class);
    Mockito
        .verify(invoker)
        .invokeWorkflowTask(anyString(), anyString(), context.capture());
    return context.getValue();

  }

  @Test
  @DisplayName("The job type is the task definition, and the method is told CREATED")
  public void theJobTypeIsTheTaskDefinition() {

    final var context = deliver(
        listenerJob("archiveTheOrder"),
        NameClashAvoidance.NONE,
        WorkflowTaskOutcome.completed());

    assertEquals("archiveTheOrder", context.getTaskDefinition(), "what a @WorkflowTask method names");
    assertEquals(
        TaskEvent.Event.CREATED,
        context.getTaskEvent(),
        "the only value a method without a @TaskEvent parameter is called for, and the event itself "
            + "is in the wiring: one method serves one event of one element");
    assertEquals("42", context.getWorkflowAggregateId());
    assertEquals("7", context.getProcessVersion(), "the cluster ships it with the job");
    assertEquals("4711", context.getDeliveryId(), "one listener event is one job");
    assertEquals("100", context.getActivationId(), "two listeners of one element share the activation");
    assertEquals(120, context.getTaskParameter("amount"));

  }

  @Test
  @DisplayName("A failing job with no retry left is failed with none, not with a negative number")
  public void aJobWithoutRetriesRaisesTheIncidentRightAway() {

    final var job = listenerJob("archiveTheOrder");
    // a listener the modeller wrote with retries="0": there is no attempt to spend and no
    // backoff to wait for, so the first failure has to raise the incident
    when(job.getRetries()).thenReturn(0);
    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.resolveWorkflowAggregateIdName(anyString(), anyString())).thenReturn("id");
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("the method of the application threw"));

    Camunda8ModelledListenerHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .workflowTaskInvoker(invoker)
        .scoping(TestScoping.of(NameClashAvoidance.NONE))
        .drain(drain)
        .build()
        .handle(jobClient, job);

    verify(jobClient.newFailCommand(4711L)).retries(0);

  }

  @Test
  @DisplayName("Under use-prefix the method is told the task definition it wrote, not the scoped one")
  public void underPrefixingTheMethodSeesThePlainTaskDefinition() {

    final var context = deliver(
        listenerJob("test-module__TestProcess__archiveTheOrder"),
        NameClashAvoidance.USE_PREFIX,
        WorkflowTaskOutcome.completed());

    assertEquals(
        "archiveTheOrder",
        context.getTaskDefinition(),
        "the core is keyed by the plain names, whatever the cluster knows");

  }

  @Test
  @DisplayName("An execution listener on 'end' completes with the aggregate, like a task does")
  public void anEndExecutionListenerCompletesWithTheAggregate() {

    deliver(
        listenerJob("archiveTheOrder", JobKind.EXECUTION_LISTENER, ListenerEventType.END),
        NameClashAvoidance.NONE,
        WorkflowTaskOutcome.completed());

    final var variables = ArgumentCaptor.forClass(Map.class);
    verify(jobClient.newCompleteCommand(4711L)).variables(variables.capture());
    assertEquals(
        true,
        variables.getValue().get("theOrderWasArchived"),
        "what the method changed reaches the instance, so a gateway behind the element sees it");
    assertEquals(
        "42",
        variables.getValue().get("id"),
        "and the aggregate-ID variable travels with every command, as it does for a task");

  }

  @Test
  @DisplayName("An execution listener on 'start' completes with nothing at all")
  public void aStartExecutionListenerCompletesWithNothing() {

    // measured against cluster and client 8.9.19 on 2026-09-13: the cluster makes the
    // variables of such a completion local to the element, and the copy then swallows every
    // later write of the same name from inside the element, the element's own job included.
    // A reproducer is kept outside this repository, in the VanillaBP workspace under
    // prompts/report-c8-start-listener-variable-scope
    deliver(
        listenerJob("prepareTheWork", JobKind.EXECUTION_LISTENER, ListenerEventType.START),
        NameClashAvoidance.NONE,
        WorkflowTaskOutcome.completed());

    verify(jobClient).newCompleteCommand(4711L);
    verify(jobClient.newCompleteCommand(4711L), never()).variables(any(Map.class));

  }

  @Test
  @DisplayName("A task listener completes with nothing, because the cluster refuses the payload")
  public void aTaskListenerCompletesWithNothing() {

    deliver(
        listenerJob("checkTheForm", JobKind.TASK_LISTENER, ListenerEventType.CREATING),
        NameClashAvoidance.NONE,
        WorkflowTaskOutcome.completed());

    verify(jobClient).newCompleteCommand(4711L);
    verify(jobClient.newCompleteCommand(4711L), never()).variables(any(Map.class));

  }

  @Test
  @DisplayName("A listener method raising a BPMN error fails the job and says why it cannot")
  public void aBpmnErrorFailsTheJob() {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.resolveWorkflowAggregateIdName(anyString(), anyString())).thenReturn("id");
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any()))
        .thenReturn(WorkflowTaskOutcome.bpmnError("SOME_ERROR", null));

    Camunda8ModelledListenerHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .workflowTaskInvoker(invoker)
        .drain(drain)
        .build()
        .handle(jobClient, listenerJob("archiveTheOrder"));

    final var message = ArgumentCaptor.forClass(String.class);
    verify(jobClient.newFailCommand(4711L).retries(2).retryBackoff(any(java.time.Duration.class)))
        .errorMessage(message.capture());
    assertTrue(
        message.getValue().contains("cannot raise a BPMN error"),
        () -> "an incident naming the cause beats one naming a transition the cluster was in: "
            + message.getValue());

  }

  @Test
  @DisplayName("A job without the aggregate's variable says which key would fetch it")
  public void aJobWithoutTheAggregateIdSaysWhatToConfigure() {

    final var job = listenerJob("archiveTheOrder");
    when(job.getVariablesAsMap()).thenReturn(Map.of());
    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.resolveWorkflowAggregateIdName(anyString(), anyString())).thenReturn("id");

    Camunda8ModelledListenerHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .workflowTaskInvoker(invoker)
        .drain(drain)
        .build()
        .handle(jobClient, job);

    verify(invoker, never()).invokeWorkflowTask(anyString(), anyString(), any());
    final var message = ArgumentCaptor.forClass(String.class);
    verify(jobClient.newFailCommand(4711L).retries(2).retryBackoff(any(java.time.Duration.class)))
        .errorMessage(message.capture());
    assertTrue(
        message.getValue().contains("The listener job"),
        () -> "the message names what kind of job it was: "
            + message.getValue());

  }

  @Test
  @DisplayName("The listener job of this adapter's own probe is closed without the application")
  public void ourOwnProbeIsClosedWithoutTheApplication() {

    final var invoker = mock(WorkflowTaskInvoker.class);

    Camunda8ModelledListenerHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .workflowTaskInvoker(invoker)
        .drain(drain)
        .build()
        .handle(jobClient, aProbesOwnListenerJob("watchTheUpdate", Camunda8UserTaskProbe.ACTION, List.of()));

    verify(invoker, never()).invokeWorkflowTask(anyString(), anyString(), any());
    verify(jobClient).newCompleteCommand(4711L);
    verify(jobClient.newCompleteCommand(4711L), never()).variables(any(Map.class));

  }

  @Test
  @DisplayName("Half the mark is not the mark, and the method runs")
  public void halfTheMarkIsNotTheMark() {

    // the action alone is a string anybody may send, and an empty change list alone is not
    // this adapter's doing either - so neither half on its own keeps a method from running
    deliver(
        aProbesOwnListenerJob("watchTheUpdate", "somebody-elses-action", List.of()),
        NameClashAvoidance.NONE,
        WorkflowTaskOutcome.completed());
    deliver(
        aProbesOwnListenerJob("watchTheUpdate", Camunda8UserTaskProbe.ACTION, List.of("dueDate")),
        NameClashAvoidance.NONE,
        WorkflowTaskOutcome.completed());

  }

  /**
   * An <code>updating</code> task-listener job as the cluster hands it over, carrying the
   * action and the changed attributes the mark is read from.
   */
  private static ActivatedJob aProbesOwnListenerJob(
      final String jobType,
      final String action,
      final List<String> changedAttributes) {

    final var job = listenerJob(jobType, JobKind.TASK_LISTENER, ListenerEventType.UPDATING);
    final var userTask = mock(UserTaskProperties.class);
    when(userTask.getAction()).thenReturn(action);
    when(userTask.getChangedAttributes()).thenReturn(changedAttributes);
    when(job.getUserTask()).thenReturn(userTask);
    return job;

  }

}
