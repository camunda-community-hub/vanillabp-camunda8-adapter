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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import io.camunda.client.api.search.enums.ElementInstanceType;
import io.camunda.client.api.search.request.ElementInstanceSearchRequest;
import io.camunda.client.api.search.request.JobSearchRequest;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.client.api.search.response.Job;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.SearchResponsePage;
import io.grpc.Status;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a task-scoped push does when the query API does not report the scope of its task.
 * <p>
 * The read model of a cluster is fed by an exporter. When that exporter stands still, the
 * read model knows nothing about a task created after it stopped, so "the query API does not
 * know the task" is no answer to "is the task still there". The push used to read it as one
 * and dropped the changed aggregate after the visibility window. Now the ENGINE is asked
 * with an <code>UpdateJobTimeout</code>, and only its <code>404</code> lets the push go.
 * <p>
 * The cluster is played by mocks: a job search which knows nothing, the job command whose
 * answer each test chooses, and the command which writes the variables.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8TaskScopedPushWaitsForItsScopeTest {

  private static final String TASK_ID = "2251799813690126";

  private static final long PROCESS_INSTANCE = 4711L;

  private static final long SUBPROCESS = 5000L;

  private static final Duration WINDOW = Duration.ofMillis(300);

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

  private void pushIntoTheScopeOfTheTask(
      final Duration window) {

    PhaseOperations
        .phaseTwo(
            configuredService(window),
            PhaseOperation.AGGREGATE_CHANGED,
            "order-module",
            "OrderApproval",
            null,
            "56",
            Map.of(PhaseTwoCall.ARG_TASK_ID, TASK_ID));

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
