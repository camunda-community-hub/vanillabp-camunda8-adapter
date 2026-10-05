package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ClientHttpException;
import io.camunda.client.api.command.ClientStatusException;
import io.camunda.client.api.command.ModifyProcessInstanceCommandStep1;
import io.camunda.client.api.command.ProblemException;
import io.camunda.client.api.command.UpdateTimeoutJobCommandStep1;
import io.camunda.client.api.command.UpdateUserTaskCommandStep1;
import io.camunda.client.api.response.UpdateTimeoutJobResponse;
import io.camunda.client.api.response.UpdateUserTaskResponse;
import io.grpc.Status;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.wiring.Camunda8OpenTaskProbe.KindOfTask;
import io.vanillabp.integration.adapter.spi.workflowtask.OpenTaskProbe;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskExistence;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The one question this adapter answers for the core's check of the other open tasks of a
 * workflow: does the cluster still have this task.
 * <p>
 * Three answers and not two. Only "gone" produces a cancelation, so an answer which cannot
 * tell a refusal from an outage has to say so - reading a hiccup as "gone" would report every
 * open task of a workflow as canceled whenever the cluster stumbles.
 * <p>
 * Two commands carry the question and the order of them is the point: the ENGINE is asked
 * about the process instance first, because a 404 there answers every record of that workflow
 * at once, and only a task of an instance which is still running is asked about on its own.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8OpenTaskProbeTest {

  private static final String TASK_ID = "2251799813685249";

  private static final String WORKFLOW_ID = "2251799813685240";

  private static final String ANOTHER_WORKFLOW_ID = "2251799813685241";

  private static final String USER_TASK = "the-form-of-a-user-task";

  private static final String SERVICE_TASK = "the-service-task";

  private static final String PROCESS = "TestProcess";

  private final CamundaClient client = mock(CamundaClient.class);

  private final Camunda8AdapterConfiguration configuration = new Camunda8AdapterConfiguration();

  /**
   * The engine holds the instance, which is the everyday answer: every test below which is
   * about a TASK starts from there, so what it reads is the task's own answer.
   */
  private void theEngineHoldsTheInstance() {

    theEngineRefusesTheModificationWith(problem(400, "INVALID_ARGUMENT", "no such element"));

  }

  @Test
  @DisplayName("A job the cluster takes the command for is still there")
  public void aJobTheClusterHasIsStillThere() {

    theEngineHoldsTheInstance();
    theClusterTakesTheJobTimeoutUpdate();

    assertEquals(TaskExistence.STILL_THERE, aServiceTaskRecordReads());

  }

  @Test
  @DisplayName("A job the cluster does not hold is gone, on both transports")
  public void aJobTheClusterDoesNotHoldIsGone() {

    theEngineHoldsTheInstance();

    theClusterRefusesTheJobTimeoutUpdate(notFoundOverRest());
    assertEquals(TaskExistence.GONE, aServiceTaskRecordReads());

    theClusterRefusesTheJobTimeoutUpdate(new ClientStatusException(Status.NOT_FOUND, null));
    assertEquals(TaskExistence.GONE, aServiceTaskRecordReads());

  }

  @Test
  @DisplayName("A job waiting in the queue is still there, not gone")
  public void aJobInTheQueueIsStillThere() {

    // the cluster HAS the job and no worker has it activated right now, which is what an
    // asynchronous task whose lock ran out looks like
    theEngineHoldsTheInstance();
    theClusterRefusesTheJobTimeoutUpdate(problem(400, "INVALID_ARGUMENT", "but it is not active"));

    assertEquals(TaskExistence.STILL_THERE, aServiceTaskRecordReads());

  }

  @Test
  @DisplayName("A cluster which did not answer is 'cannot say' and cancels nothing")
  public void aClusterWhichDidNotAnswerCancelsNothing() {

    theEngineHoldsTheInstance();
    theClusterRefusesTheJobTimeoutUpdate(new IllegalStateException("connection reset"));

    assertEquals(TaskExistence.CANNOT_SAY, aServiceTaskRecordReads());

  }

  @Test
  @DisplayName("A task id which is no key of this cluster is 'cannot say'")
  public void aTaskIdOfAnotherBpmsIsCannotSay() {

    theEngineHoldsTheInstance();
    theClusterTakesTheJobTimeoutUpdate();
    final var handedToTheCore = whatTheCoreWasHanded(aModuleWhere(SERVICE_TASK, KindOfTask.A_JOB));

    assertEquals(TaskExistence.CANNOT_SAY, handedToTheCore.stillExists(WORKFLOW_ID, "not-a-key", SERVICE_TASK));
    assertEquals(TaskExistence.CANNOT_SAY, handedToTheCore.stillExists(WORKFLOW_ID, null, SERVICE_TASK));
    verify(client, never()).newUpdateTimeoutCommand(Mockito.anyLong());

  }

  @Test
  @DisplayName("One user task in a model no longer costs its service tasks their cancelation")
  public void theRefusalIsPerRecordAndNotPerProcess() {

    // the cluster answers NOT_FOUND for the service task because its job is gone, and the
    // user task is not asked about at all while the key is off
    theEngineHoldsTheInstance();
    theClusterRefusesTheJobTimeoutUpdate(notFoundOverRest());
    final var handedToTheCore = whatTheCoreWasHanded(aModuleWithAUserTask());

    // both halves in ONE test, so nobody can pass it by weakening the model
    assertEquals(
        TaskExistence.GONE,
        handedToTheCore.stillExists(WORKFLOW_ID, TASK_ID, SERVICE_TASK),
        "the service task of a process which also holds a user task is derived as canceled");
    assertEquals(
        TaskExistence.CANNOT_SAY,
        handedToTheCore.stillExists(WORKFLOW_ID, TASK_ID, USER_TASK),
        "and the user task of the same process is not");

  }

  @Test
  @DisplayName("A record this adapter cannot place is answered by nothing at all")
  public void aRecordWhichCannotBePlaced() {

    theEngineHoldsTheInstance();
    theClusterRefusesTheJobTimeoutUpdate(notFoundOverRest());

    assertEquals(
        TaskExistence.CANNOT_SAY,
        whatTheCoreWasHanded(aModuleWhere(SERVICE_TASK, KindOfTask.CANNOT_TELL))
            .stillExists(WORKFLOW_ID, TASK_ID, SERVICE_TASK),
        "nothing tells the two kinds of task apart here, so nothing is reported");
    verify(client, never()).newUpdateTimeoutCommand(Mockito.anyLong());
    verify(client, never()).newUpdateUserTaskCommand(Mockito.anyLong());

  }

  @Test
  @DisplayName("An instance the engine has forgotten answers every record of its workflow")
  public void anInstanceWhichIsGoneAnswersTheWholeList() {

    theEngineRefusesTheModificationWith(notFoundOverRest());
    final var handedToTheCore = whatTheCoreWasHanded(aModuleWithAUserTask());

    assertEquals(TaskExistence.GONE, handedToTheCore.stillExists(WORKFLOW_ID, TASK_ID, SERVICE_TASK));
    assertEquals(TaskExistence.GONE, handedToTheCore.stillExists(WORKFLOW_ID, TASK_ID, USER_TASK));
    verify(client, never()).newUpdateTimeoutCommand(Mockito.anyLong());
    verify(client, never()).newUpdateUserTaskCommand(Mockito.anyLong());

  }

  @Test
  @DisplayName("The engine is asked once per wake-up, not once per record")
  public void theEngineIsAskedOncePerWakeUp() {

    theEngineHoldsTheInstance();
    theClusterTakesTheJobTimeoutUpdate();
    final var handedToTheCore = whatTheCoreWasHanded(aModuleWhere(SERVICE_TASK, KindOfTask.A_JOB));

    handedToTheCore.stillExists(WORKFLOW_ID, TASK_ID, SERVICE_TASK);
    handedToTheCore.stillExists(WORKFLOW_ID, TASK_ID, SERVICE_TASK);
    handedToTheCore.stillExists(WORKFLOW_ID, TASK_ID, SERVICE_TASK);

    verify(client, times(1)).newModifyProcessInstanceCommand(Long.parseLong(WORKFLOW_ID));

  }

  @Test
  @DisplayName("A record of another workflow is answered by nothing, because the process id would be wrong")
  public void aRecordOfAnotherWorkflow() {

    // the core reads the open tasks OF ONE WORKFLOW, so this cannot happen. If it ever
    // does, every answer below would be read against the model of the wake-up's process,
    // and the element the instance probe reserves is checked against that model
    theEngineHoldsTheInstance();
    theClusterTakesTheJobTimeoutUpdate();
    final var handedToTheCore = whatTheCoreWasHanded(aModuleWhere(SERVICE_TASK, KindOfTask.A_JOB));

    assertEquals(
        TaskExistence.CANNOT_SAY,
        handedToTheCore.stillExists(ANOTHER_WORKFLOW_ID, TASK_ID, SERVICE_TASK));
    verify(client, never()).newModifyProcessInstanceCommand(Long.parseLong(ANOTHER_WORKFLOW_ID));
    verify(client, never()).newUpdateTimeoutCommand(Mockito.anyLong());

  }

  @Test
  @DisplayName("A model carrying the reserved element is never modified, and its jobs still answer")
  public void aModelCarryingTheReservedElementIsNotAsked() {

    theClusterTakesTheJobTimeoutUpdate();
    final var handedToTheCore = whatTheCoreWasHanded(
        aModuleWhere(SERVICE_TASK, KindOfTask.A_JOB),
        bpmnProcessId -> true);

    assertEquals(TaskExistence.STILL_THERE, handedToTheCore.stillExists(WORKFLOW_ID, TASK_ID, SERVICE_TASK));
    verify(client, never()).newModifyProcessInstanceCommand(Mockito.anyLong());

  }

  @Test
  @DisplayName("A user task is probed only where the key asked for it")
  public void aUserTaskIsProbedOnlyWhereItWasAskedFor() {

    theEngineHoldsTheInstance();
    theClusterTakesTheUserTaskUpdate();

    assertEquals(TaskExistence.CANNOT_SAY, aUserTaskRecordReads());
    verify(client, never()).newUpdateUserTaskCommand(Mockito.anyLong());

    configuration.setProbeOpenUserTasks(true);
    assertEquals(TaskExistence.STILL_THERE, aUserTaskRecordReads());
    verify(client, times(1)).newUpdateUserTaskCommand(Long.parseLong(TASK_ID));

  }

  @Test
  @DisplayName("What the empty update answers about a user task, code by code")
  public void whatTheEmptyUpdateAnswers() {

    configuration.setProbeOpenUserTasks(true);
    theEngineHoldsTheInstance();

    theClusterRefusesTheUserTaskUpdate(notFoundOverRest());
    assertEquals(TaskExistence.GONE, aUserTaskRecordReads(), "404 is a task the cluster no longer has");

    theClusterRefusesTheUserTaskUpdate(new ClientStatusException(Status.NOT_FOUND, null));
    assertEquals(TaskExistence.GONE, aUserTaskRecordReads(), "and so is NOT_FOUND over gRPC");

    theClusterRefusesTheUserTaskUpdate(problem(409, "CONFLICT", "the task is UPDATING"));
    assertEquals(TaskExistence.STILL_THERE, aUserTaskRecordReads(), "409 is a task which is there to refuse about");

    theClusterRefusesTheUserTaskUpdate(problem(400, "INVALID_ARGUMENT", "nobody has measured this"));
    assertEquals(TaskExistence.CANNOT_SAY, aUserTaskRecordReads(), "400 was never measured, so it says nothing");

    theClusterRefusesTheUserTaskUpdate(new IllegalStateException("connection reset"));
    assertEquals(TaskExistence.CANNOT_SAY, aUserTaskRecordReads(), "and neither does an outage");

  }

  @Test
  @DisplayName("A user task of an instance nobody could ask about is not asked about either")
  public void aUserTaskOfAnInstanceNobodyCouldAskAbout() {

    configuration.setProbeOpenUserTasks(true);
    theEngineRefusesTheModificationWith(new IllegalStateException("connection reset"));
    theClusterTakesTheUserTaskUpdate();

    assertEquals(TaskExistence.CANNOT_SAY, aUserTaskRecordReads());
    verify(client, never()).newUpdateUserTaskCommand(Mockito.anyLong());

  }

  @Test
  @DisplayName("The core is handed the wake-up and the probe, and a failure stays here")
  public void theCoreIsHandedTheWakeUp() {

    final var asked = new ArrayList<OpenTaskProbe>();
    final var invoker = anInvokerWhich(asked, false);

    probeOf(invoker, aModuleWithAUserTask(), bpmnProcessId -> false)
        .reportWhatTheClusterNoLongerHas(PROCESS, aWakeUpOfTheWorkflow());

    assertEquals(1, asked.size(), "the core is asked to look at the other open tasks, once");

  }

  @Test
  @DisplayName("A core which throws never fails the job the check rode in on")
  public void aCoreWhichThrowsIsSwallowed() {

    final var invoker = anInvokerWhich(new ArrayList<>(), true);

    org.junit.jupiter.api.Assertions
        .assertDoesNotThrow(
            () -> probeOf(invoker, aModuleWithAUserTask(), bpmnProcessId -> false)
                .reportWhatTheClusterNoLongerHas(PROCESS, aWakeUpOfTheWorkflow()));

  }

  @Test
  @DisplayName("Without a core entry point or a wake-up nothing is asked")
  public void withoutTheHalvesNothingIsAsked() {

    final var asked = new ArrayList<OpenTaskProbe>();
    final var invoker = anInvokerWhich(asked, false);
    final var module = aModuleWithAUserTask();

    probeOf(invoker, module, bpmnProcessId -> false).reportWhatTheClusterNoLongerHas(PROCESS, null);
    probeOf(null, module, bpmnProcessId -> false)
        .reportWhatTheClusterNoLongerHas(PROCESS, aWakeUpOfTheWorkflow());

    assertTrue(asked.isEmpty());

  }

  private TaskExistence aServiceTaskRecordReads() {

    return whatTheCoreWasHanded(aModuleWhere(SERVICE_TASK, KindOfTask.A_JOB))
        .stillExists(WORKFLOW_ID, TASK_ID, SERVICE_TASK);

  }

  private TaskExistence aUserTaskRecordReads() {

    return whatTheCoreWasHanded(aModuleWithAUserTask()).stillExists(WORKFLOW_ID, TASK_ID, USER_TASK);

  }

  /**
   * The probe the core is handed for one wake-up, which is where the memory of what the
   * engine answered about an instance lives.
   */
  private OpenTaskProbe whatTheCoreWasHanded(
      final BiFunction<String, String, KindOfTask> module) {

    return whatTheCoreWasHanded(module, bpmnProcessId -> false);

  }

  private OpenTaskProbe whatTheCoreWasHanded(
      final BiFunction<String, String, KindOfTask> module,
      final java.util.function.Predicate<String> reservedElement) {

    final var asked = new ArrayList<OpenTaskProbe>();
    probeOf(anInvokerWhich(asked, false), module, reservedElement)
        .reportWhatTheClusterNoLongerHas(PROCESS, aWakeUpOfTheWorkflow());
    return asked.getFirst();

  }

  /**
   * The delivery this check rides in on. It names the workflow, which is what says which
   * instance the records the core hands over belong to.
   */
  private static TaskInvocationContext aWakeUpOfTheWorkflow() {

    final var wakeUp = mock(TaskInvocationContext.class);
    Mockito.lenient().when(wakeUp.getWorkflowId()).thenReturn(WORKFLOW_ID);
    Mockito.lenient().when(wakeUp.getWorkflowAggregateId()).thenReturn("4711");
    return wakeUp;

  }

  private static BiFunction<String, String, KindOfTask> aModuleWithAUserTask() {

    return (
        bpmnProcessId,
        taskDefinition) -> USER_TASK.equals(taskDefinition)
            ? KindOfTask.A_CAMUNDA_MANAGED_USER_TASK
            : KindOfTask.A_JOB;

  }

  private static BiFunction<String, String, KindOfTask> aModuleWhere(
      final String taskDefinition,
      final KindOfTask kind) {

    return (
        bpmnProcessId,
        asked) -> taskDefinition.equals(asked)
            ? kind
            : KindOfTask.A_JOB;

  }

  private Camunda8OpenTaskProbe probeOf(
      final WorkflowTaskInvoker invoker,
      final BiFunction<String, String, KindOfTask> theKindOfTaskARecordNames,
      final java.util.function.Predicate<String> aModelCarriesTheReservedProbeElement) {

    return new Camunda8OpenTaskProbe(
        "c8", "test-module", invoker, () -> client, () -> configuration, Duration
            .ofHours(1), theKindOfTaskARecordNames, aModelCarriesTheReservedProbeElement);

  }

  /**
   * A core which records the probe it was handed, and optionally throws while doing it.
   */
  private static WorkflowTaskInvoker anInvokerWhich(
      final List<OpenTaskProbe> asked,
      final boolean throwsUp) {

    final var invoker = mock(WorkflowTaskInvoker.class);
    Mockito
        .lenient()
        .doAnswer(invocation -> {
          if (throwsUp) {
            throw new IllegalStateException("the delivery log did not answer");
          }
          asked.add(invocation.getArgument(3));
          return null;
        })
        .when(invoker)
        .reportTasksTheBpmsNoLongerHas(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(),
            Mockito.any());
    return invoker;

  }

  private static ClientHttpException notFoundOverRest() {

    return new ClientHttpException("Failed with code 404", 404, "not found");

  }

  private void theClusterTakesTheJobTimeoutUpdate() {

    final var command = mock(
        UpdateTimeoutJobCommandStep1.UpdateTimeoutJobCommandStep2.class,
        RETURNS_SELF);
    @SuppressWarnings("unchecked")
    final CamundaFuture<UpdateTimeoutJobResponse> answer = mock(CamundaFuture.class);
    Mockito.lenient().when(answer.join()).thenReturn(null);
    Mockito.lenient().when(command.send()).thenReturn(answer);
    theClusterAnswersTheJobTimeoutUpdateWith(command);

  }

  private void theClusterRefusesTheJobTimeoutUpdate(
      final RuntimeException rejection) {

    final var command = mock(
        UpdateTimeoutJobCommandStep1.UpdateTimeoutJobCommandStep2.class,
        RETURNS_SELF);
    Mockito.lenient().when(command.send()).thenThrow(rejection);
    theClusterAnswersTheJobTimeoutUpdateWith(command);

  }

  private void theClusterAnswersTheJobTimeoutUpdateWith(
      final UpdateTimeoutJobCommandStep1.UpdateTimeoutJobCommandStep2 command) {

    final var step1 = mock(UpdateTimeoutJobCommandStep1.class, RETURNS_SELF);
    Mockito.lenient().when(step1.timeout(Mockito.any(Duration.class))).thenReturn(command);
    Mockito.lenient().when(client.newUpdateTimeoutCommand(Mockito.anyLong())).thenReturn(step1);

  }

  private void theClusterTakesTheUserTaskUpdate() {

    final var command = mock(UpdateUserTaskCommandStep1.class, RETURNS_SELF);
    @SuppressWarnings("unchecked")
    final CamundaFuture<UpdateUserTaskResponse> answer = mock(CamundaFuture.class);
    Mockito.lenient().when(answer.join()).thenReturn(null);
    Mockito.lenient().when(command.send()).thenReturn(answer);
    Mockito.lenient().when(client.newUpdateUserTaskCommand(Mockito.anyLong())).thenReturn(command);

  }

  private void theClusterRefusesTheUserTaskUpdate(
      final RuntimeException rejection) {

    final var command = mock(UpdateUserTaskCommandStep1.class, RETURNS_SELF);
    Mockito.lenient().when(command.send()).thenThrow(rejection);
    Mockito.lenient().when(client.newUpdateUserTaskCommand(Mockito.anyLong())).thenReturn(command);

  }

  /**
   * What the engine answers the instance probe with. Both of its refusals mean the instance
   * is there, which is what makes the command a question.
   */
  private void theEngineRefusesTheModificationWith(
      final RuntimeException rejection) {

    final var step3 = mock(
        ModifyProcessInstanceCommandStep1.ModifyProcessInstanceCommandStep3.class,
        RETURNS_SELF);
    Mockito.lenient().when(step3.send()).thenThrow(rejection);
    theEngineAnswersTheModificationWith(step3);

  }

  private void theEngineAnswersTheModificationWith(
      final ModifyProcessInstanceCommandStep1.ModifyProcessInstanceCommandStep3 step3) {

    final var step1 = mock(ModifyProcessInstanceCommandStep1.class, RETURNS_SELF);
    Mockito.lenient().when(step1.activateElement(Mockito.anyString())).thenReturn(step3);
    Mockito.lenient().when(client.newModifyProcessInstanceCommand(Mockito.anyLong())).thenReturn(step1);

  }

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
