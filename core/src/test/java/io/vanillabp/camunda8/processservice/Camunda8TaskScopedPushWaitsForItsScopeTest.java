package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.command.ClientHttpException;
import io.camunda.client.api.command.ClientStatusException;
import io.camunda.client.api.command.SetVariablesCommandStep1;
import io.camunda.client.api.command.SetVariablesCommandStep1.SetVariablesCommandStep2;
import io.camunda.client.api.command.UpdateTimeoutJobCommandStep1;
import io.camunda.client.api.command.UpdateUserTaskCommandStep1;
import io.camunda.client.api.search.enums.ElementInstanceType;
import io.camunda.client.api.search.filter.UserTaskVariableFilter;
import io.camunda.client.api.search.request.ElementInstanceSearchRequest;
import io.camunda.client.api.search.request.JobSearchRequest;
import io.camunda.client.api.search.request.UserTaskSearchRequest;
import io.camunda.client.api.search.request.UserTaskVariableSearchRequest;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.client.api.search.response.Job;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.SearchResponsePage;
import io.camunda.client.api.search.response.Variable;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.grpc.Status;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.deployment.Camunda8DeployedProcesses;
import io.vanillabp.camunda8.wiring.Camunda8MultiInstance;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a task-scoped push does when the query API does not report the scope of its task.
 * <p>
 * The read model of a cluster is fed by an exporter. When that exporter stands still, the
 * read model knows nothing about a task created after it stopped, so "the query API does not
 * know the task" is no answer to "is the task still there". The push used to read it as one
 * and dropped the changed aggregate after the visibility window. Now the ENGINE is asked,
 * and only its <code>404</code> lets the push go. It is asked only about a task the row of the
 * delivery log says the adapter left open, because the question cuts short the lock of a job a
 * handler may hold. Without such a row the push waits for the read model.
 * <p>
 * The same row names the process instance and the element of the task. Where the model this
 * application deployed puts the element directly into its process, the push writes into that
 * process instance without any search.
 * <p>
 * The cluster is played by mocks: a job search which knows nothing, the commands whose
 * answer each test chooses, and the command which writes the variables.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8TaskScopedPushWaitsForItsScopeTest {

  private static final String TASK_ID = "2251799813690126";

  private static final long PROCESS_INSTANCE = 4711L;

  private static final long SUBPROCESS = 5000L;

  private static final Duration WINDOW = Duration.ofMillis(300);

  private static final String MODULE = "order-module";

  private static final String PROCESS = "OrderApproval";

  private static final long ITERATION = 6000L;

  /**
   * The sentence of the failure which says that the engine was not asked, because no row says
   * the task rests.
   */
  private static final String NOT_ASKED_WITHOUT_A_ROW = "The engine is not asked, because no row of the delivery log says that the task rests";

  /**
   * The sentence of the WARN which says that the push was given up because the task is
   * completed. Asserted present where the engine says the job is gone and absent where it
   * says the job is there.
   */
  private static final String GIVEN_UP_BECAUSE_COMPLETED = "is completed - skipping the push of the changed aggregate";

  private final CamundaClient client = mock(CamundaClient.class);

  /**
   * Which element instance the changed aggregate was written to.
   */
  private final List<Long> writtenTo = new ArrayList<>();

  /**
   * How often the engine was asked about the job.
   */
  private final List<Long> engineAskedAbout = new ArrayList<>();

  /**
   * How often the engine was asked about the user task.
   */
  private final List<Long> engineAskedAboutTheUserTask = new ArrayList<>();

  /**
   * The models this application deployed, which the push reads where a row names a version.
   */
  private final Camunda8DeployedProcesses deployed = new Camunda8DeployedProcesses();

  @BeforeEach
  public void setUp() {

    theWritesAreRecorded(null);

  }

  @Test
  @DisplayName("A task the engine holds and the query API does not know is pushed again later")
  public void aTaskTheEngineHoldsIsPushedAgainLater(
      final CapturedOutput output) {

    theQueryApiDoesNotKnowTheJob();
    theEngineAnswersTheJobCommand(null);

    final var retryLater = assertThrows(
        PhaseTwoRetryLater.class,
        () -> pushIntoTheScopeOfTheTask(WINDOW),
        "a task the engine holds is not over, so the entry has to come back");
    assertEquals(
        WINDOW,
        retryLater.getRetryAfter(),
        "the entry comes back after the window the read model may need");
    assertTrue(
        retryLater.getMessage().contains("look at the exporter of the cluster first"),
        "the message says what to look at if this does not stop: "
            + retryLater.getMessage());
    assertEquals(List.of(), writtenTo, "nothing is written while the scope is unknown");
    assertEquals(List.of(Long.valueOf(TASK_ID)), engineAskedAbout, "the engine was asked about the task's job");
    assertFalse(
        output.getAllOfThisTest().contains(GIVEN_UP_BECAUSE_COMPLETED),
        "a task which is still there is not reported as completed");

  }

  @Test
  @DisplayName("A job nobody activated right now is a task the engine holds")
  public void aDormantJobIsPushedAgainLater() {

    theQueryApiDoesNotKnowTheJob();
    theEngineAnswersTheJobCommand(new ClientHttpException("Failed with code 400", 400, "not active"));

    assertThrows(
        PhaseTwoRetryLater.class,
        () -> pushIntoTheScopeOfTheTask(WINDOW),
        "400 is a refusal about a job the cluster has");

  }

  @Test
  @DisplayName("A job another activation holds is a task the engine holds")
  public void aJobAnotherActivationHoldsIsPushedAgainLater() {

    theQueryApiDoesNotKnowTheJob();
    theEngineAnswersTheJobCommand(new ClientStatusException(Status.FAILED_PRECONDITION, null));

    assertThrows(
        PhaseTwoRetryLater.class,
        () -> pushIntoTheScopeOfTheTask(WINDOW),
        "409 respectively FAILED_PRECONDITION is a refusal about a job the cluster has");

  }

  @Test
  @DisplayName("A task the engine no longer holds is given up with a warning")
  public void aTaskTheEngineNoLongerHoldsIsGivenUp(
      final CapturedOutput output) {

    theQueryApiDoesNotKnowTheJob();
    theEngineAnswersTheJobCommand(new ClientHttpException("Failed with code 404", 404, "job not found"));

    pushIntoTheScopeOfTheTask(WINDOW);

    assertEquals(List.of(), writtenTo, "there is no scope left to write to");
    assertTrue(
        output.getAllOfThisTest().contains(GIVEN_UP_BECAUSE_COMPLETED),
        "the push is given up and says why: "
            + output.getAllOfThisTest());

  }

  @Test
  @DisplayName("A cluster which does not answer the question is an outage the outbox repeats")
  public void anOutageIsThrownAsItIs() {

    theQueryApiDoesNotKnowTheJob();
    final var outage = new IllegalStateException("connection reset");
    theEngineAnswersTheJobCommand(outage);

    final var thrown = assertThrows(
        IllegalStateException.class,
        () -> pushIntoTheScopeOfTheTask(WINDOW),
        "an outage is no answer about the task");
    assertEquals(outage, thrown, "the outage reaches the outbox as it is");
    assertNull(
        PhaseTwoRetryLater.retryAfter(thrown),
        "an outage is repeated with the outbox' own backoff, not with the visibility window");

  }

  @Test
  @DisplayName("Without a visibility window the entry is repeated with the outbox' own backoff")
  public void withoutAWindowTheOutboxBackoffApplies() {

    theQueryApiDoesNotKnowTheJob();
    theEngineAnswersTheJobCommand(null);

    final var thrown = assertThrows(
        IllegalStateException.class,
        () -> pushIntoTheScopeOfTheTask(Duration.ZERO),
        "a task the engine holds is not given up, whatever the window");
    assertNull(
        PhaseTwoRetryLater.retryAfter(thrown),
        "a window of zero would bring the entry back at once, so the outbox decides when");

  }

  @Test
  @DisplayName("A scope which ended between the search and the write is given up with a warning")
  public void aScopeWhichEndedMeanwhileIsGivenUp(
      final CapturedOutput output) {

    theQueryApiKnowsTheJobInsideTheSubprocess();
    theWritesAreRecorded(new ClientHttpException("Failed with code 404", 404, "no such element"));

    pushIntoTheScopeOfTheTask(WINDOW);

    assertEquals(List.of(Long.valueOf(SUBPROCESS)), writtenTo, "the write was tried at the scope of the task");
    assertTrue(
        output.getAllOfThisTest().contains("ended before the changed aggregate could be written into it"),
        "the push is given up and says why: "
            + output.getAllOfThisTest());

  }

  @Test
  @DisplayName("A scope the query API knows is written without asking the engine")
  public void aKnownScopeIsWrittenWithoutAskingTheEngine() {

    theQueryApiKnowsTheJobInsideTheSubprocess();
    theEngineAnswersTheJobCommand(null);

    pushIntoTheScopeOfTheTask(WINDOW);

    assertEquals(List.of(Long.valueOf(SUBPROCESS)), writtenTo, "the values belong to the scope the task runs in");
    assertEquals(List.of(), engineAskedAbout, "the everyday case costs no command");

  }

  @Test
  @DisplayName("Without a row saying that the task rests the engine is not asked, and the push waits for the read model")
  public void withoutARowTheEngineIsNotAsked() {

    theQueryApiDoesNotKnowTheJob();
    theQueryApiDoesNotKnowTheUserTask();
    theEngineAnswersTheJobCommand(new ClientHttpException("Failed with code 404", 404, "job not found"));

    final var retryLater = assertThrows(
        PhaseTwoRetryLater.class,
        () -> pushWithTheRow(WINDOW, null),
        "a task nobody says rests may be held by a handler, so the push waits instead of asking");
    assertTrue(
        retryLater.getMessage().contains(NOT_ASKED_WITHOUT_A_ROW),
        "the message says why the engine was not asked: "
            + retryLater.getMessage());
    assertEquals(List.of(), engineAskedAbout, "the lock of a job a handler may hold is left alone");

  }

  @Test
  @DisplayName("A task the application closed already is not asked about either")
  public void aClosedTaskIsNotAskedAbout() {

    theQueryApiDoesNotKnowTheJob();
    theEngineAnswersTheJobCommand(null);

    final var retryLater = assertThrows(
        PhaseTwoRetryLater.class,
        () -> pushWithTheRow(WINDOW, aRow("TASK", Instant.now())),
        "a closed task does not rest any more");
    assertTrue(retryLater.getMessage().contains(NOT_ASKED_WITHOUT_A_ROW), retryLater.getMessage());
    assertEquals(List.of(), engineAskedAbout);

  }

  @Test
  @DisplayName("A user task the query API does not know is asked about as a user task, not as a job")
  public void aUserTaskIsAskedAboutAsAUserTask(
      final CapturedOutput output) {

    theQueryApiDoesNotKnowTheUserTask();
    theEngineAnswersTheUserTaskCommand(null);

    assertThrows(PhaseTwoRetryLater.class, () -> pushWithTheRow(WINDOW, aRow("USER_TASK", null)));
    assertEquals(List.of(Long.valueOf(TASK_ID)), engineAskedAboutTheUserTask);
    assertEquals(List.of(), engineAskedAbout, "a user-task key means nothing to a job command");
    assertFalse(output.getAllOfThisTest().contains(GIVEN_UP_BECAUSE_COMPLETED));

  }

  @Test
  @DisplayName("A user task the engine no longer holds is given up with a warning")
  public void aUserTaskTheEngineNoLongerHoldsIsGivenUp(
      final CapturedOutput output) {

    theQueryApiDoesNotKnowTheUserTask();
    theEngineAnswersTheUserTaskCommand(new ClientHttpException("Failed with code 404", 404, "no such user task"));

    pushWithTheRow(WINDOW, aRow("USER_TASK", null));

    assertEquals(List.of(), writtenTo);
    assertTrue(output.getAllOfThisTest().contains(GIVEN_UP_BECAUSE_COMPLETED), output.getAllOfThisTest());

  }

  @Test
  @DisplayName("A task directly in its process is written into the process instance of its row, without any search")
  public void aTaskDirectlyInItsProcessNeedsNoSearch() {

    theApplicationDeployed(
        Bpmn
            .createExecutableProcess(PROCESS)
            .startEvent()
            .userTask("Approve")
            .endEvent()
            .done());

    pushWithTheRow(WINDOW, aRow("USER_TASK", null));

    assertEquals(
        List.of(Long.valueOf(PROCESS_INSTANCE)),
        writtenTo,
        "the process instance the row names is the scope of a task directly in the process");
    Mockito.verify(client, Mockito.never()).newJobSearchRequest();
    Mockito.verify(client, Mockito.never()).newUserTaskSearchRequest();

  }

  @Test
  @DisplayName("A row of a version this application did not deploy is searched for, because that model may differ")
  public void aRowOfAnotherVersionIsSearchedFor() {

    theApplicationDeployed(
        Bpmn
            .createExecutableProcess(PROCESS)
            .startEvent()
            .userTask("Approve")
            .endEvent()
            .done());
    theQueryApiKnowsTheJobInsideTheSubprocess();
    final var olderRow = aRow("TASK", null);

    pushWithTheRow(
        WINDOW,
        new TaskDelivery(
            olderRow.deliveryKey(), olderRow.adapterId(), MODULE, PROCESS, "56", olderRow
                .workflowId(), "approve", "Approve", TASK_ID, "COMPLETION_PENDING", null, null, olderRow
                    .recordedAt(), null, "TASK", "TASK_DELIVERY", "2"));

    assertEquals(List.of(Long.valueOf(SUBPROCESS)), writtenTo, "the scope the search found");

  }

  @Test
  @DisplayName("A user task in an iteration is written into the iteration the index variable of that iteration lives in")
  public void aUserTaskInAnIterationIsFoundByItsIndexVariable() {

    theApplicationDeployed(
        Bpmn
            .createExecutableProcess(PROCESS)
            .startEvent()
            .subProcess("Positions")
            .multiInstance(multiInstance -> multiInstance.zeebeInputCollectionExpression("[1,2]"))
            .embeddedSubProcess()
            .startEvent()
            .userTask("Approve")
            .endEvent()
            .subProcessDone()
            .endEvent()
            .done());
    final var asked = theUserTaskSeesTheIndexOfItsIteration();

    pushWithTheRow(WINDOW, aRow("USER_TASK", null));

    assertEquals(List.of(Long.valueOf(ITERATION)), writtenTo, "the iteration is the scope of the task");
    assertEquals(
        List.of(Camunda8MultiInstance.indexVariableOf("Positions")),
        asked,
        "the variable the deployment adds to every iteration is the one asked for");
    Mockito.verify(client, Mockito.never()).newElementInstanceSearchRequest();

  }

  private void pushIntoTheScopeOfTheTask(
      final Duration window) {

    pushWithTheRow(window, aRow("TASK", null));

  }

  private void pushWithTheRow(
      final Duration window,
      final TaskDelivery taskRow) {

    configuredService(window)
        .phaseOperations()
        .get(PhaseOperation.AGGREGATE_CHANGED)
        .phaseTwo(
            new PhaseTwoRequest<>(
                MODULE, PROCESS, null, "56", Map.of(PhaseTwoCall.ARG_TASK_ID, TASK_ID), null, null, taskRow));

  }

  /**
   * The row the core hands over for a task this adapter left open.
   *
   * @param taskKind <code>TASK</code> or <code>USER_TASK</code>
   * @param closedAt When the application closed the task, <code>null</code> while it is open
   */
  private static TaskDelivery aRow(
      final String taskKind,
      final Instant closedAt) {

    return new TaskDelivery(
        "delivery", "c8", MODULE, PROCESS, "56", String
            .valueOf(PROCESS_INSTANCE), "approve", "Approve", TASK_ID, "COMPLETION_PENDING", null, null, Instant
                .now(), closedAt, taskKind, "TASK_DELIVERY", "1");

  }

  /**
   * Records that this application deployed version 1 of the process.
   */
  private void theApplicationDeployed(
      final BpmnModelInstance model) {

    deployed.record(new Camunda8DeployedProcesses.DeployedProcess(MODULE, PROCESS, "1", 1, model));

  }

  private Camunda8ProcessService<Object> configuredService(
      final Duration window) {

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing contacts: every request of this test meets a mock
    configuration.setRestAddress("http://localhost:1");
    configuration.setWorkflowVisibilityTimeout(window);
    final var clientFactory = new Camunda8ClientFactory("c8", configuration) {

      @Override
      public CamundaClient getClient() {
        return client;
      }

      @Override
      public Camunda8DeployedProcesses getDeployedProcesses() {
        return deployed;
      }

    };
    return new Camunda8ProcessService<>("c8", clientFactory, Duration.ofDays(14), (
        aggregateClass,
        check) -> check.run(), null);

  }

  /**
   * A read model whose exporter stopped before the task was created.
   */
  private void theQueryApiDoesNotKnowTheJob() {

    final var search = mock(JobSearchRequest.class, RETURNS_SELF);
    Mockito.lenient().when(client.newJobSearchRequest()).thenReturn(search);
    Mockito.lenient().when(search.send()).thenAnswer(invocation -> future(response(List.of())));

  }

  /**
   * A read model which knows the job, and knows it below a subprocess of the workflow.
   */
  private void theQueryApiKnowsTheJobInsideTheSubprocess() {

    final var taskElementInstance = 7777L;
    final var job = mock(Job.class);
    Mockito.lenient().when(job.getProcessInstanceKey()).thenReturn(Long.valueOf(PROCESS_INSTANCE));
    Mockito.lenient().when(job.getElementInstanceKey()).thenReturn(Long.valueOf(taskElementInstance));
    final var jobSearch = mock(JobSearchRequest.class, RETURNS_SELF);
    Mockito.lenient().when(client.newJobSearchRequest()).thenReturn(jobSearch);
    Mockito.lenient().when(jobSearch.send()).thenAnswer(invocation -> future(response(List.of(job))));

    // the first search reads the children of the process instance, the second those of the
    // subprocess
    final var children = new ArrayList<List<ElementInstance>>();
    children.add(List.of(elementInstance(SUBPROCESS, ElementInstanceType.SUB_PROCESS)));
    children.add(List.of(elementInstance(taskElementInstance, ElementInstanceType.SERVICE_TASK)));
    final var elementSearch = mock(ElementInstanceSearchRequest.class, RETURNS_SELF);
    Mockito.lenient().when(client.newElementInstanceSearchRequest()).thenReturn(elementSearch);
    Mockito
        .lenient()
        .when(elementSearch.send())
        .thenAnswer(invocation -> future(response(children.isEmpty()
            ? List.of()
            : children.removeFirst())));

  }

  /**
   * A read model which does not know the user task.
   */
  private void theQueryApiDoesNotKnowTheUserTask() {

    final var search = mock(UserTaskSearchRequest.class, RETURNS_SELF);
    Mockito.lenient().when(client.newUserTaskSearchRequest()).thenReturn(search);
    Mockito.lenient().when(search.send()).thenAnswer(invocation -> future(response(List.of())));

  }

  /**
   * A read model which reports the index variable of the iteration among the variables the user
   * task sees, with the iteration as its scope.
   *
   * @return The names of the variables asked for
   */
  private List<String> theUserTaskSeesTheIndexOfItsIteration() {

    final var asked = new ArrayList<String>();
    final var variable = mock(Variable.class);
    Mockito.lenient().when(variable.getScopeKey()).thenReturn(Long.valueOf(ITERATION));
    final var search = mock(UserTaskVariableSearchRequest.class, RETURNS_SELF);
    final var filter = mock(UserTaskVariableFilter.class, RETURNS_SELF);
    Mockito.lenient().when(filter.name(Mockito.anyString())).thenAnswer(invocation -> {
      asked.add(invocation.getArgument(0));
      return filter;
    });
    Mockito.lenient().when(search.filter(Mockito.<Consumer<UserTaskVariableFilter>>any())).thenAnswer(invocation -> {
      invocation.<Consumer<UserTaskVariableFilter>>getArgument(0).accept(filter);
      return search;
    });
    Mockito.lenient().when(client.newUserTaskVariableSearchRequest(anyLong())).thenReturn(search);
    Mockito.lenient().when(search.send()).thenAnswer(invocation -> future(response(List.of(variable))));
    return asked;

  }

  /**
   * The engine's answer to the empty <code>UpdateUserTask</code>.
   *
   * @param rejection What the command fails with, or <code>null</code> where it is accepted
   */
  private void theEngineAnswersTheUserTaskCommand(
      final RuntimeException rejection) {

    final var command = mock(UpdateUserTaskCommandStep1.class, RETURNS_SELF);
    if (rejection == null) {
      Mockito.lenient().when(command.send()).thenAnswer(invocation -> future(null));
    } else {
      Mockito.lenient().when(command.send()).thenThrow(rejection);
    }
    Mockito.lenient().when(client.newUpdateUserTaskCommand(anyLong())).thenAnswer(invocation -> {
      engineAskedAboutTheUserTask.add(invocation.getArgument(0));
      return command;
    });

  }

  /**
   * The engine's answer to <code>UpdateJobTimeout</code>.
   *
   * @param rejection What the command fails with, or <code>null</code> where it is accepted
   */
  private void theEngineAnswersTheJobCommand(
      final RuntimeException rejection) {

    final var command = mock(
        UpdateTimeoutJobCommandStep1.UpdateTimeoutJobCommandStep2.class,
        RETURNS_SELF);
    if (rejection == null) {
      Mockito.lenient().when(command.send()).thenAnswer(invocation -> future(null));
    } else {
      Mockito.lenient().when(command.send()).thenThrow(rejection);
    }
    final var step1 = mock(UpdateTimeoutJobCommandStep1.class, RETURNS_SELF);
    Mockito.lenient().when(step1.timeout(Mockito.any(Duration.class))).thenReturn(command);
    Mockito.lenient().when(client.newUpdateTimeoutCommand(anyLong())).thenAnswer(invocation -> {
      engineAskedAbout.add(invocation.getArgument(0));
      return step1;
    });

  }

  /**
   * Records where the variables are written to.
   *
   * @param rejection What the write fails with, or <code>null</code> where it is accepted
   */
  private void theWritesAreRecorded(
      final RuntimeException rejection) {

    final var step2 = mock(SetVariablesCommandStep2.class);
    Mockito.lenient().when(step2.local(anyBoolean())).thenReturn(step2);
    if (rejection == null) {
      Mockito.lenient().when(step2.send()).thenAnswer(invocation -> future(null));
    } else {
      Mockito.lenient().when(step2.send()).thenThrow(rejection);
    }
    final var step1 = mock(SetVariablesCommandStep1.class);
    Mockito.lenient().when(step1.variables(Mockito.<Map<String, Object>>any())).thenReturn(step2);
    Mockito.lenient().when(client.newSetVariablesCommand(anyLong())).thenAnswer(invocation -> {
      writtenTo.add(invocation.getArgument(0));
      return step1;
    });
    // stubbing a second time calls the first stub once, with the matcher's value
    writtenTo.clear();

  }

  private static ElementInstance elementInstance(
      final long elementInstanceKey,
      final ElementInstanceType type) {

    final var elementInstance = mock(ElementInstance.class);
    Mockito
        .lenient()
        .when(elementInstance.getElementInstanceKey())
        .thenReturn(Long.valueOf(elementInstanceKey));
    Mockito.lenient().when(elementInstance.getType()).thenReturn(type);
    return elementInstance;

  }

  private static <T> SearchResponse<T> response(
      final List<T> items) {

    @SuppressWarnings("unchecked")
    final SearchResponse<T> response = mock(SearchResponse.class);
    final var page = mock(SearchResponsePage.class);
    Mockito.lenient().when(page.totalItems()).thenReturn(Long.valueOf(items.size()));
    Mockito.lenient().when(response.items()).thenReturn(items);
    Mockito.lenient().when(response.page()).thenReturn(page);
    return response;

  }

  private static <T> CamundaFuture<T> future(
      final T value) {

    @SuppressWarnings("unchecked")
    final CamundaFuture<T> future = mock(CamundaFuture.class);
    Mockito.lenient().when(future.join()).thenReturn(value);
    return future;

  }

}
