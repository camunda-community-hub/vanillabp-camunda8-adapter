package io.vanillabp.camunda8;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import io.camunda.client.api.search.request.ProcessInstanceSearchRequest;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.worker.JobClient;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.camunda8.processservice.Camunda8ProcessService;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.camunda8.wiring.Camunda8UserTaskListenerHandler;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter does with a value of a Camunda 8 client enum it has never seen.
 * <p>
 * The client adds values to its enums inside a release line, and Camunda does not count that
 * as a breaking change. 8.9 added <code>DRAINING</code> to the process definition states.
 * 8.10 adds <code>SUSPENDED</code> to the instance states and <code>CANCEL</code> to the
 * listener events. A patch does it too: 8.8.39 brought <code>DRAINING</code> to the 8.8
 * line. A client which is older than its cluster reports an unknown value as
 * <code>UNKNOWN_ENUM_VALUE</code>. This adapter is built per line, so an application which
 * runs the 8.8 line against an 8.10 cluster meets exactly that. The compiler does not notice,
 * and neither does a test which only uses the literals of today.
 * <p>
 * So the rule is: write a comparison against one literal so that an unknown value lands on
 * the harmless side. This test pins the two places where the harmless side is not the
 * obvious one. The third form is a <code>switch</code> over a client enum. It lives in
 * {@code Camunda8WorkflowViewer#elementTypeOf} and answers <code>UNKNOWN</code> in its
 * <code>default</code> branch. {@code Camunda8WorkflowViewerTest} holds that.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8UnknownClientEnumsTest {

  // --- the user-task lifecycle listener -----------------------------------------------

  private final JobClient jobClient = mock(JobClient.class, RETURNS_DEEP_STUBS);

  private final Camunda8Drain drain = new Camunda8Drain("c8", "test-module");

  private ActivatedJob listenerJob(
      final ListenerEventType eventType) {

    final var job = mock(ActivatedJob.class);
    when(job.getKey()).thenReturn(4711L);
    when(job.getRetries()).thenReturn(3);
    when(job.getBpmnProcessId()).thenReturn("TestProcess");
    when(job.getType())
        .thenReturn(Camunda8TaskWiring.TASKDEFINITION_USERTASK_ZEEBE
            + "someUserTask");
    when(job.getListenerEventType()).thenReturn(eventType);
    when(job.getVariablesAsMap()).thenReturn(Map.of("id", "42"));
    return job;

  }

  private WorkflowTaskInvoker deliver(
      final ListenerEventType eventType) {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.workflowTaskHandlerExists(anyString(), anyString(), anyString())).thenReturn(true);
    when(invoker.resolveWorkflowAggregateIdName(anyString(), anyString())).thenReturn("id");
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any())).thenReturn(WorkflowTaskOutcome.completed());
    Camunda8UserTaskListenerHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .workflowTaskInvoker(invoker)
        .drain(drain)
        .build()
        .handle(jobClient, listenerJob(eventType));
    return invoker;

  }

  @Test
  @DisplayName("The two events the deployment asked for are notified")
  public void theTwoEventsTheDeploymentAskedForAreNotified() {

    verify(deliver(ListenerEventType.CREATING), times(1)).invokeWorkflowTask(anyString(), anyString(), any());
    verify(deliver(ListenerEventType.CANCELING), times(1)).invokeWorkflowTask(anyString(), anyString(), any());

  }

  @Test
  @DisplayName("An event this adapter never asked for notifies nobody")
  public void anEventNobodyAskedForNotifiesNobody() {

    // The client answers UNKNOWN_ENUM_VALUE for an event its version has no literal for.
    // That happens when the cluster is newer than the client of this build. Reading it as a
    // creation would tell the application about a user task it never got. So the job is
    // completed and the application hears nothing
    final var invoker = deliver(ListenerEventType.UNKNOWN_ENUM_VALUE);

    verify(invoker, never()).invokeWorkflowTask(anyString(), anyString(), any());
    // the job still holds up the lifecycle of the task, so leaving it alone would hang the task
    verify(jobClient.newCompleteCommand(4711L), times(1)).send();

  }

  // --- the election probe --------------------------------------------------------------

  private static final WorkflowScope SCOPE = WorkflowScope.of("test-module", "TestProcess");

  private record Aggregate(String id) {
  }

  private static AggregatePersistenceAware<Aggregate> persistence() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<Aggregate> getAggregateClass() {
        return Aggregate.class;
      }

      @Override
      public Aggregate save(
          final Aggregate aggregate) {
        return aggregate;
      }

      @Override
      public Object getAggregateId(
          final Aggregate aggregate) {
        return aggregate.id();
      }

    };

  }

  /**
   * A process service whose cluster holds one instance of the workflow, in the given
   * state.
   *
   * @param state What the query API reports about that instance
   * @return The service under test
   */
  private static Camunda8ProcessService<Aggregate> serviceHolding(
      final ProcessInstanceState state) {

    final var instance = mock(ProcessInstance.class);
    when(instance.getState()).thenReturn(state);
    when(instance.getTenantId()).thenReturn("<default>");
    when(instance.getProcessDefinitionId()).thenReturn("TestProcess");
    when(instance.getProcessInstanceKey()).thenReturn(4711L);

    final var client = mock(CamundaClient.class);
    final var search = mock(ProcessInstanceSearchRequest.class, RETURNS_SELF);
    when(client.newProcessInstanceSearchRequest()).thenReturn(search);
    @SuppressWarnings("unchecked")
    final SearchResponse<ProcessInstance> found = mock(SearchResponse.class);
    when(found.items()).thenReturn(List.of(instance));
    @SuppressWarnings("unchecked")
    final CamundaFuture<SearchResponse<ProcessInstance>> answer = mock(CamundaFuture.class);
    when(answer.join()).thenReturn(found);
    when(search.send()).thenReturn(answer);

    final var configuration = new Camunda8AdapterConfiguration();
    // nothing ever contacts this address, every request of this test meets the mock above
    configuration.setRestAddress("http://localhost:1");
    configuration.setWorkflowVisibilityTimeout(Duration.ZERO);
    final var clientFactory = new Camunda8ClientFactory("c8", configuration) {

      @Override
      public CamundaClient getClient() {
        return client;
      }

    };
    return new Camunda8ProcessService<Aggregate>(
        "c8", clientFactory, Duration.ofDays(14), (
            aggregateClass,
            check) -> check.run(), null);

  }

  @Test
  @DisplayName("The two states which end a workflow are answered as COMPLETED")
  public void theTwoStatesWhichEndAWorkflowAreCompleted() {

    assertEquals(
        WorkflowAwareness.COMPLETED,
        serviceHolding(ProcessInstanceState.COMPLETED).awarenessOfWorkflow(SCOPE, persistence(), "agg-1"));
    assertEquals(
        WorkflowAwareness.COMPLETED,
        serviceHolding(ProcessInstanceState.TERMINATED).awarenessOfWorkflow(SCOPE, persistence(), "agg-1"));

  }

  @Test
  @DisplayName("A state this build has no literal for is a workflow which still runs")
  public void aStateWithoutALiteralStillRuns() {

    // COMPLETED means "the operation comes too late". Answering it for an unknown state
    // would drop an operation on a workflow which is still alive. 8.10 adds SUSPENDED, and
    // an older client reports it as UNKNOWN_ENUM_VALUE
    assertEquals(
        WorkflowAwareness.ACTIVE,
        serviceHolding(ProcessInstanceState.UNKNOWN_ENUM_VALUE).awarenessOfWorkflow(SCOPE, persistence(), "agg-1"));

  }

}
