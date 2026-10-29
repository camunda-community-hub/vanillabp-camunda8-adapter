package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.command.SetVariablesCommandStep1;
import io.camunda.client.api.command.SetVariablesCommandStep1.SetVariablesCommandStep2;
import io.camunda.client.api.search.request.ProcessInstanceSearchRequest;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.SearchResponse;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.workflowstart.WorkflowStartReport;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a push into the workflow's own scope tells VanillaBP after it had to search for the
 * workflow.
 * <p>
 * A workflow which version 1 started has no start row. So the core hands in no key, and the
 * adapter searches by the aggregate's id. The adapter reports what it found, and the core
 * writes the row. Only the instance at the top is reported. A called process carries the
 * aggregate's id as well, and its key would be the wrong one in the row.
 * <p>
 * The cluster is played by mocks. The first search answers the instances a test chooses. The
 * searches after it look for called instances below the one written to, and find none.
 * {@code Camunda8AggregateChangedIT} shows the whole way against a real cluster: the first
 * push searches and reports, and the second one gets the key.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ReportsTheWorkflowItFoundTest {

  private static final long THE_INSTANCE_AT_THE_TOP = 2251799813690126L;

  private static final long A_CALLED_INSTANCE = 2251799813690200L;

  private static final String THE_SENTENCE_OF_THE_REPORT = "by a search and reported it";

  private final CamundaClient client = mock(CamundaClient.class);

  /**
   * Which process instance the changed aggregate was written to.
   */
  private final List<Long> writtenTo = new ArrayList<>();

  /**
   * What the push reported as the workflow it found.
   */
  private final List<String> reported = new ArrayList<>();

  @Test
  @DisplayName("A push without a key reports the instance its search found")
  public void aPushWithoutAKeyReportsWhatItFound(
      final CapturedOutput output) {

    theSearchFinds(instance(THE_INSTANCE_AT_THE_TOP, null));

    push(null, false);

    assertEquals(List.of(THE_INSTANCE_AT_THE_TOP), writtenTo, "the values belong to the instance the search found");
    assertEquals(
        List.of(String.valueOf(THE_INSTANCE_AT_THE_TOP)),
        reported,
        "the core writes the row only from what the adapter reports");
    assertTrue(output.getAllOfThisTest().contains(THE_SENTENCE_OF_THE_REPORT), output.getAllOfThisTest());

  }

  @Test
  @DisplayName("The instance at the top wins over a called one, whatever order the search answers in")
  public void theInstanceAtTheTopWins() {

    theSearchFinds(instance(A_CALLED_INSTANCE, THE_INSTANCE_AT_THE_TOP), instance(THE_INSTANCE_AT_THE_TOP, null));

    push(null, false);

    assertEquals(List.of(THE_INSTANCE_AT_THE_TOP), writtenTo, "the aggregate IS the instance at the top");
    assertEquals(List.of(String.valueOf(THE_INSTANCE_AT_THE_TOP)), reported);

  }

  @Test
  @DisplayName("A called instance is written to, but not reported")
  public void aCalledInstanceIsNotReported(
      final CapturedOutput output) {

    theSearchFinds(instance(A_CALLED_INSTANCE, THE_INSTANCE_AT_THE_TOP));

    push(null, false);

    assertEquals(List.of(A_CALLED_INSTANCE), writtenTo, "the push writes where it always wrote");
    assertEquals(List.of(), reported, "the row names the workflow the aggregate IS, never a part of it");
    assertTrue(
        !output.getAllOfThisTest().contains(THE_SENTENCE_OF_THE_REPORT),
        "the sentence which the first test asserts must not appear here");

  }

  @Test
  @DisplayName("A push which got a key reports nothing, even where it searched")
  public void aPushWithAKeyReportsNothing() {

    theSearchFinds(instance(THE_INSTANCE_AT_THE_TOP, null));

    // on a cluster which other adapter ids share, the key is not used and the push searches
    push(String.valueOf(THE_INSTANCE_AT_THE_TOP), true);

    assertEquals(List.of(THE_INSTANCE_AT_THE_TOP), writtenTo, "the search found the instance");
    assertEquals(List.of(), reported, "a row is there already, the core handed in its key");

  }

  @Test
  @DisplayName("A search which finds nothing reports nothing")
  public void aSearchWhichFindsNothingReportsNothing() {

    theSearchFinds();

    push(null, false);

    assertEquals(List.of(), writtenTo);
    assertEquals(List.of(), reported);

  }

  private void push(
      final String workflowId,
      final boolean sharedCluster) {

    final WorkflowStartReport sink = new WorkflowStartReport() {

      @Override
      public void startedWorkflow(
          final String startedWorkflowId) {
        throw new AssertionError("a push starts nothing");
      }

      @Override
      public void foundWorkflow(
          final String foundWorkflowId) {
        reported.add(foundWorkflowId);
      }

    };
    configuredService(sharedCluster)
        .phaseOperations()
        .get(PhaseOperation.AGGREGATE_CHANGED)
        .phaseTwo(
            new PhaseTwoRequest<>(
                "test-module", "TestProcess", persistence(), "56", Map.of(), sink, workflowId));

  }

  /**
   * The persistence of an aggregate whose ID attribute is 'id', which names the variable the
   * search filters by.
   */
  private static AggregatePersistenceAware<Object> persistence() {

    @SuppressWarnings("unchecked")
    final AggregatePersistenceAware<Object> persistence = mock(AggregatePersistenceAware.class);
    Mockito.lenient().when(persistence.getAggregateIdName()).thenReturn("id");
    return persistence;

  }

  /**
   * An active instance of the process of this test, as the search reports it.
   *
   * @param processInstanceKey The key of the instance
   * @param parentProcessInstanceKey The instance whose call activity started it, or
   *          <code>null</code> for an instance at the top
   */
  private static ProcessInstance instance(
      final long processInstanceKey,
      final Long parentProcessInstanceKey) {

    final var instance = mock(ProcessInstance.class);
    Mockito.lenient().when(instance.getProcessInstanceKey()).thenReturn(processInstanceKey);
    Mockito.lenient().when(instance.getParentProcessInstanceKey()).thenReturn(parentProcessInstanceKey);
    Mockito.lenient().when(instance.getTenantId()).thenReturn("<default>");
    Mockito.lenient().when(instance.getProcessDefinitionId()).thenReturn("TestProcess");
    return instance;

  }

  /**
   * The first search answers the given instances. Every search after it looks for called
   * instances, and finds none.
   */
  private void theSearchFinds(
      final ProcessInstance... instances) {

    final var searches = new AtomicInteger();
    final var search = mock(ProcessInstanceSearchRequest.class, RETURNS_SELF);
    Mockito.lenient().when(client.newProcessInstanceSearchRequest()).thenReturn(search);
    Mockito
        .lenient()
        .when(search.send())
        .thenAnswer(invocation -> future(
            response(searches.getAndIncrement() == 0
                ? List.of(instances)
                : List.of())));

    final var step2 = mock(SetVariablesCommandStep2.class);
    Mockito.lenient().when(step2.local(anyBoolean())).thenReturn(step2);
    Mockito.lenient().when(step2.send()).thenAnswer(invocation -> future(null));
    final var step1 = mock(SetVariablesCommandStep1.class);
    Mockito.lenient().when(step1.variables(Mockito.<Map<String, Object>>any())).thenReturn(step2);
    Mockito.lenient().when(client.newSetVariablesCommand(anyLong())).thenAnswer(invocation -> {
      writtenTo.add(invocation.getArgument(0));
      return step1;
    });

  }

  private Camunda8ProcessService<Object> configuredService(
      final boolean sharedCluster) {

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing contacts: every request of this test meets a mock
    configuration.setRestAddress("http://localhost:1");
    final var clientFactory = new Camunda8ClientFactory("c8", configuration) {

      @Override
      public CamundaClient getClient() {
        return client;
      }

      @Override
      public boolean sharesItsCluster() {
        return sharedCluster;
      }

    };
    return new Camunda8ProcessService<>("c8", clientFactory, Duration.ofDays(14), (
        aggregateClass,
        check) -> check.run(), null);

  }

  private static SearchResponse<ProcessInstance> response(
      final List<ProcessInstance> items) {

    @SuppressWarnings("unchecked")
    final SearchResponse<ProcessInstance> response = mock(SearchResponse.class);
    Mockito.lenient().when(response.items()).thenReturn(items);
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
