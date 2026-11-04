package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.CompleteJobCommandStep1;
import io.camunda.client.api.command.CompleteUserTaskCommandStep1;
import io.camunda.client.api.command.ProblemException;
import io.camunda.client.api.command.ThrowErrorCommandStep1;
import io.camunda.client.api.command.UpdateTimeoutJobCommandStep1;
import io.camunda.client.api.command.UpdateUserTaskCommandStep1;
import io.camunda.client.api.fetch.ProcessInstanceGetRequest;
import io.camunda.client.api.fetch.UserTaskGetRequest;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import io.camunda.client.api.search.request.JobSearchRequest;
import io.camunda.client.api.search.response.Job;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.UserTask;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.integration.adapter.spi.SuspendedWorkflowReporter;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.extension.spi.runtime.SuspendedWorkflowFound;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.WorkflowSuspendedException;

/**
 * A task of a suspended workflow, on the line whose clusters can suspend.
 * <p>
 * The cluster answers every command about such a task with <code>409</code>, the code it also
 * gives for a user task which is still being created and for a job another activation holds. So
 * the adapter asks the state of the instance after that answer, and only then. Phase one turns a
 * suspended workflow into a {@link WorkflowSuspendedException}, so the caller's transaction rolls
 * back. Phase two turns it into a {@link PhaseTwoRetryLater}, so the outbox entry waits without
 * using its attempts. {@code Camunda8SuspendedWorkflowIT} does the same against a real cluster.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8SuspendedWorkflowTest {

  private static final String TASK_ID = "2251799813685249";

  private static final long PROCESS_INSTANCE = 2251799813685200L;

  private final CamundaClient client = mock(CamundaClient.class);

  private final List<Runnable> preCommitChecks = new ArrayList<>();

  /**
   * What the extensions heard through the core.
   */
  private final List<SuspendedWorkflowFound> heard = new ArrayList<>();

  private SuspendedWorkflowReporter reporter = heard::add;

  private void theExtensionsHeard(
      final SuspendedWorkflowFound.Phase phase,
      final String workflowAggregateId) {

    assertEquals(1, heard.size(), "one report per refusal: %s".formatted(heard));
    final var found = heard.getFirst();
    assertEquals("c8", found.adapterId());
    assertEquals("test-module", found.workflowModuleId());
    assertEquals("TestProcess", found.bpmnProcessId());
    assertEquals(workflowAggregateId, found.workflowAggregateId());
    assertEquals(Long.toString(PROCESS_INSTANCE), found.workflowId(), "the instance the adapter asked about");
    assertEquals(TASK_ID, found.taskId());
    assertEquals(phase, found.phase());
    assertNotNull(found.foundAt());

  }

  private static ProblemException refusedBecauseSuspended(
      final String command) {

    final var details = new ProblemDetail();
    details.setStatus(409);
    details.setTitle("INVALID_STATE");
    return new ProblemException(
        409, ("Command '%s' rejected with code 'INVALID_STATE': Expected to process command for process instance "
            + "with key '%d', but the process instance is suspended.").formatted(command, PROCESS_INSTANCE), details);

  }

  @Test
  @DisplayName("Completing a user task of a suspended workflow rolls the caller's transaction back")
  public void aUserTaskOfASuspendedWorkflowIsRefusedInPhaseOne() {

    final var refusal = refusedBecauseSuspended("UPDATE");
    theClusterRefusesTheUserTaskUpdate(refusal);
    theUserTaskBelongsToTheInstance();
    theInstanceIs(ProcessInstanceState.SUSPENDED);

    phaseOne(PhaseOperation.COMPLETE_USER_TASK);

    final var thrown = assertThrows(WorkflowSuspendedException.class, () -> preCommitChecks.getFirst().run());
    assertTrue(
        thrown.getMessage().contains(TASK_ID) && thrown.getMessage().contains("'c8'") && thrown.getMessage()
            .contains("after the workflow is resumed"),
        "the message names the task, the adapter and what to do: "
            + thrown.getMessage());
    assertSame(refusal, thrown.getCause(), "the answer of the cluster travels along as the cause");
    theExtensionsHeard(SuspendedWorkflowFound.Phase.CALL, null);

  }

  @Test
  @DisplayName("A 409 about a user task of an active workflow lets the transaction commit, as before")
  public void aUserTaskOfAnActiveWorkflowIsStillTheTaskBeingCreated() {

    theClusterRefusesTheUserTaskUpdate(refusedBecauseSuspended("UPDATE"));
    theUserTaskBelongsToTheInstance();
    theInstanceIs(ProcessInstanceState.ACTIVE);

    phaseOne(PhaseOperation.COMPLETE_USER_TASK);

    assertDoesNotThrow(
        () -> preCommitChecks.getFirst().run(),
        "an active workflow refuses a user task which is still being created, and the completion of "
            + "phase two waits that out");
    assertTrue(heard.isEmpty(), "nothing is suspended, so nobody is told");

  }

  @Test
  @DisplayName("Completing a task of a suspended workflow rolls the caller's transaction back")
  public void aTaskOfASuspendedWorkflowIsRefusedInPhaseOne() {

    theClusterRefusesTheJobTimeoutUpdate(refusedBecauseSuspended("UPDATE_TIMEOUT"));
    theJobSearchReportsTheInstance();
    theInstanceIs(ProcessInstanceState.SUSPENDED);

    phaseOne(PhaseOperation.COMPLETE_TASK);

    final var thrown = assertThrows(WorkflowSuspendedException.class, () -> preCommitChecks.getFirst().run());
    assertTrue(
        thrown.getMessage().contains(TASK_ID) && thrown.getMessage().contains("completing"),
        "the message names the task and what was attempted: "
            + thrown.getMessage());
    theExtensionsHeard(SuspendedWorkflowFound.Phase.CALL, null);

  }

  @Test
  @DisplayName("A 409 about a task of an active workflow still comes out unchanged")
  public void aTaskOfAnActiveWorkflowKeepsTheRefusal() {

    final var refusal = refusedBecauseSuspended("UPDATE_TIMEOUT");
    theClusterRefusesTheJobTimeoutUpdate(refusal);
    theJobSearchReportsTheInstance();
    theInstanceIs(ProcessInstanceState.ACTIVE);

    phaseOne(PhaseOperation.CANCEL_TASK);

    final var thrown = assertThrows(ProblemException.class, () -> preCommitChecks.getFirst().run());
    assertSame(refusal, thrown, "nothing is made up about a refusal the adapter cannot explain");
    assertTrue(heard.isEmpty(), "nothing is suspended, so nobody is told");

  }

  @Test
  @DisplayName("A job the search does not report yet is not read as suspended")
  public void aJobTheSearchDoesNotKnowIsNotSuspended() {

    theClusterRefusesTheJobTimeoutUpdate(refusedBecauseSuspended("UPDATE_TIMEOUT"));
    theJobSearchReports(List.of());

    phaseOne(PhaseOperation.COMPLETE_TASK);

    assertThrows(ProblemException.class, () -> preCommitChecks.getFirst().run());
    Mockito.verify(client, Mockito.never()).newProcessInstanceGetRequest(Mockito.anyLong());

  }

  @Test
  @DisplayName("The probe calls a task of a suspended workflow active, so phase one can say why")
  public void theProbeAnswersActiveForASuspendedWorkflow() {

    theClusterRefusesTheJobTimeoutUpdate(refusedBecauseSuspended("UPDATE_TIMEOUT"));
    theJobSearchReportsTheInstance();
    theInstanceIs(ProcessInstanceState.SUSPENDED);

    assertEquals(
        WorkflowAwareness.ACTIVE,
        aService().awarenessOfTask(WorkflowScope.of("test-module", "TestProcess"), "4711", TASK_ID),
        "the cluster holds the task, and BPMS_UNAVAILABLE would send the caller into retries");
    assertTrue(heard.isEmpty(), "the check of phase one reports it, the probe before it does not");

  }

  @Test
  @DisplayName("Phase two of a completed task waits while the workflow is suspended")
  public void aCompletionWaitsInPhaseTwo() {

    final var command = mock(CompleteJobCommandStep1.class, RETURNS_SELF);
    Mockito.when(command.send()).thenThrow(refusedBecauseSuspended("COMPLETE"));
    Mockito.when(client.newCompleteCommand(Mockito.anyLong())).thenReturn(command);
    theJobSearchReportsTheInstance();
    theInstanceIs(ProcessInstanceState.SUSPENDED);

    final var service = aService();
    final var thrown = assertThrows(PhaseTwoRetryLater.class, () -> phaseTwo(service, PhaseOperation.COMPLETE_TASK));
    theWaitLastsAsLongAsTheSuspension(service, thrown);
    theExtensionsHeard(SuspendedWorkflowFound.Phase.WAITING, "4711");

    // the next attempt of the outbox entry is reported again, the extension filters it out
    assertThrows(PhaseTwoRetryLater.class, () -> phaseTwo(service, PhaseOperation.COMPLETE_TASK));
    assertEquals(2, heard.size());

  }

  @Test
  @DisplayName("Phase two of a canceled task waits while the workflow is suspended")
  public void aCancellationWaitsInPhaseTwo() {

    final var command = mock(ThrowErrorCommandStep1.ThrowErrorCommandStep2.class, RETURNS_SELF);
    Mockito.when(command.send()).thenThrow(refusedBecauseSuspended("THROW_ERROR"));
    final var step1 = mock(ThrowErrorCommandStep1.class);
    Mockito.when(step1.errorCode(Mockito.anyString())).thenReturn(command);
    Mockito.when(client.newThrowErrorCommand(Mockito.anyLong())).thenReturn(step1);
    theJobSearchReportsTheInstance();
    theInstanceIs(ProcessInstanceState.SUSPENDED);

    final var service = aService();
    final var thrown = assertThrows(
        PhaseTwoRetryLater.class,
        () -> PhaseOperations
            .phaseTwo(
                service,
                PhaseOperation.CANCEL_TASK,
                "test-module",
                "TestProcess",
                null,
                "4711",
                PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, TASK_ID, PhaseTwoCall.ARG_BPMN_ERROR_CODE, "E")));
    theWaitLastsAsLongAsTheSuspension(service, thrown);
    theExtensionsHeard(SuspendedWorkflowFound.Phase.WAITING, "4711");

  }

  @Test
  @DisplayName("Phase two of a completed user task waits while the workflow is suspended")
  public void aUserTaskCompletionWaitsInPhaseTwo() {

    final var command = mock(CompleteUserTaskCommandStep1.class, RETURNS_SELF);
    Mockito.when(command.send()).thenThrow(refusedBecauseSuspended("COMPLETE"));
    Mockito.when(client.newCompleteUserTaskCommand(Mockito.anyLong())).thenReturn(command);
    theUserTaskBelongsToTheInstance();
    theInstanceIs(ProcessInstanceState.SUSPENDED);

    final var service = aService();
    final var thrown = assertThrows(
        PhaseTwoRetryLater.class,
        () -> phaseTwo(service, PhaseOperation.COMPLETE_USER_TASK));
    theWaitLastsAsLongAsTheSuspension(service, thrown);
    theExtensionsHeard(SuspendedWorkflowFound.Phase.WAITING, "4711");

  }

  @Test
  @DisplayName("Phase two of a task of an active workflow keeps the refusal, and the outbox counts it")
  public void aRefusalOfAnActiveWorkflowIsAnOrdinaryFailure() {

    final var refusal = refusedBecauseSuspended("COMPLETE");
    final var command = mock(CompleteJobCommandStep1.class, RETURNS_SELF);
    Mockito.when(command.send()).thenThrow(refusal);
    Mockito.when(client.newCompleteCommand(Mockito.anyLong())).thenReturn(command);
    theJobSearchReportsTheInstance();
    theInstanceIs(ProcessInstanceState.ACTIVE);

    final var thrown = assertThrows(ProblemException.class, () -> phaseTwo(aService(), PhaseOperation.COMPLETE_TASK));
    assertSame(refusal, thrown);
    assertTrue(heard.isEmpty(), "nothing is suspended, so nobody is told");

  }

  @Test
  @DisplayName("A task which is answered at once costs no question about the workflow")
  public void anAcceptedCheckAsksNothing() {

    final var command = mock(UpdateUserTaskCommandStep1.class, RETURNS_SELF);
    Mockito.when(command.send()).thenReturn(mock(CamundaFuture.class));
    Mockito.when(client.newUpdateUserTaskCommand(Mockito.anyLong())).thenReturn(command);

    phaseOne(PhaseOperation.COMPLETE_USER_TASK);
    preCommitChecks.getFirst().run();

    Mockito.verify(client, Mockito.never()).newProcessInstanceGetRequest(Mockito.anyLong());
    Mockito.verify(client, Mockito.never()).newJobSearchRequest();

  }

  @Test
  @DisplayName("A report which fails leaves the refusal of the caller as it was")
  public void aFailingReportChangesNothing() {

    reporter = found -> {
      throw new IllegalStateException("a reporter which breaks");
    };
    theClusterRefusesTheJobTimeoutUpdate(refusedBecauseSuspended("UPDATE_TIMEOUT"));
    theJobSearchReportsTheInstance();
    theInstanceIs(ProcessInstanceState.SUSPENDED);

    phaseOne(PhaseOperation.COMPLETE_TASK);

    assertThrows(WorkflowSuspendedException.class, () -> preCommitChecks.getFirst().run());

  }

  private void theWaitLastsAsLongAsTheSuspension(
      final Camunda8ProcessService<Object> service,
      final PhaseTwoRetryLater thrown) {

    assertEquals(Camunda8SuspendedWorkflow.WAIT_WHILE_SUSPENDED, thrown.getRetryAfter());
    assertNotNull(thrown.getDeadline(), "a deadline replaces the limit of the outbox for this entry");
    assertTrue(
        thrown.getDeadline().isAfter(Instant.now().plus(Camunda8SuspendedWorkflow.WAIT_WHILE_SUSPENDED)),
        "the deadline lies behind the next attempt, so the wait goes on while the workflow is suspended");
    assertTrue(
        thrown.getMessage().contains("suspended") && thrown.getMessage().contains(TASK_ID),
        "the log of the outbox says why the entry waits: "
            + thrown.getMessage());
    assertTrue(service.isPhaseTwoFailureRepeatable(thrown), "a wait is never a permanent failure");

  }

  private void phaseOne(
      final PhaseOperation operation) {

    PhaseOperations
        .phaseOne(
            aService(),
            operation,
            "test-module",
            "TestProcess",
            null,
            new Object(),
            operation == PhaseOperation.CANCEL_TASK
                ? PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, TASK_ID, PhaseTwoCall.ARG_BPMN_ERROR_CODE, "E")
                : PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, TASK_ID));

  }

  private static void phaseTwo(
      final Camunda8ProcessService<Object> service,
      final PhaseOperation operation) {

    PhaseOperations
        .phaseTwo(
            service,
            operation,
            "test-module",
            "TestProcess",
            null,
            "4711",
            PhaseOperations.args(PhaseTwoCall.ARG_TASK_ID, TASK_ID));

  }

  private Camunda8ProcessService<Object> aService() {

    final var configuration = new Camunda8AdapterConfiguration();
    // nothing ever contacts this address, every request of this test meets the mock above
    configuration.setRestAddress("http://localhost:1");
    configuration.setWorkflowVisibilityTimeout(Duration.ZERO);
    final var clientFactory = new Camunda8ClientFactory("c8", configuration) {

      @Override
      public CamundaClient getClient() {
        return client;
      }

      @Override
      public boolean sharesItsCluster() {
        return false;
      }

    };
    return new Camunda8ProcessService<>(
        "c8", clientFactory, Duration.ofDays(14), (
            aggregateClass,
            check) -> preCommitChecks.add(check), null, found -> reporter.found(found));

  }

  private void theClusterRefusesTheUserTaskUpdate(
      final RuntimeException rejection) {

    final var command = mock(UpdateUserTaskCommandStep1.class, RETURNS_SELF);
    Mockito.when(command.send()).thenThrow(rejection);
    Mockito.when(client.newUpdateUserTaskCommand(Mockito.anyLong())).thenReturn(command);

  }

  private void theClusterRefusesTheJobTimeoutUpdate(
      final RuntimeException rejection) {

    final var command = mock(UpdateTimeoutJobCommandStep1.UpdateTimeoutJobCommandStep2.class, RETURNS_SELF);
    Mockito.when(command.send()).thenThrow(rejection);
    final var step1 = mock(UpdateTimeoutJobCommandStep1.class, RETURNS_SELF);
    Mockito.when(step1.timeout(Mockito.any(Duration.class))).thenReturn(command);
    Mockito.when(client.newUpdateTimeoutCommand(Mockito.anyLong())).thenReturn(step1);

  }

  private void theUserTaskBelongsToTheInstance() {

    final var userTask = mock(UserTask.class);
    Mockito.lenient().when(userTask.getProcessInstanceKey()).thenReturn(PROCESS_INSTANCE);
    final var request = mock(UserTaskGetRequest.class, RETURNS_SELF);
    Mockito.lenient().when(request.send()).thenAnswer(invocation -> future(userTask));
    Mockito.lenient().when(client.newUserTaskGetRequest(Mockito.anyLong())).thenReturn(request);

  }

  private void theJobSearchReportsTheInstance() {

    final var job = mock(Job.class);
    Mockito.lenient().when(job.getProcessInstanceKey()).thenReturn(PROCESS_INSTANCE);
    theJobSearchReports(List.of(job));

  }

  private void theJobSearchReports(
      final List<Job> jobs) {

    @SuppressWarnings("unchecked")
    final SearchResponse<Job> response = mock(SearchResponse.class);
    Mockito.lenient().when(response.items()).thenReturn(jobs);
    final var search = mock(JobSearchRequest.class, RETURNS_SELF);
    Mockito.lenient().when(search.send()).thenAnswer(invocation -> future(response));
    Mockito.lenient().when(client.newJobSearchRequest()).thenReturn(search);

  }

  private void theInstanceIs(
      final ProcessInstanceState state) {

    final var instance = mock(ProcessInstance.class);
    Mockito.lenient().when(instance.getState()).thenReturn(state);
    final var request = mock(ProcessInstanceGetRequest.class, RETURNS_SELF);
    Mockito.lenient().when(request.send()).thenAnswer(invocation -> future(instance));
    Mockito.lenient().when(client.newProcessInstanceGetRequest(Mockito.eq(PROCESS_INSTANCE))).thenReturn(request);

  }

  private static <T> CamundaFuture<T> future(
      final T value) {

    @SuppressWarnings("unchecked")
    final CamundaFuture<T> future = mock(CamundaFuture.class);
    Mockito.lenient().when(future.join()).thenReturn(value);
    return future;

  }

}
