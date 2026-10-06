package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import io.camunda.client.api.search.request.ProcessInstanceSearchRequest;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.SearchResponse;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An aggregate may carry a second workflow once its first one ended. This is the probe the
 * core asks before it dispatches a start a second time, and the case where it used to lose
 * that second workflow:
 * <ol>
 * <li>the first workflow of the aggregate runs and ends;</li>
 * <li>a second start of the same aggregate is planned;</li>
 * <li>the first attempt to dispatch it fails before the cluster created anything;</li>
 * <li>the core repeats the dispatch and first asks the adapter whether the workflow exists.</li>
 * </ol>
 * The search has no state filter, so it finds the first workflow. A probe which counts it
 * answers "it is there", the core consumes the entry, and the second workflow never starts.
 * The probe counts only the instances whose <code>startDate</code> is at or after the moment
 * the start was planned.
 * <p>
 * The search is a mock: what is under test is what the adapter does with the instances a
 * search returns. That the search finds an instance at all is held against a real cluster by
 * {@code Camunda8DeploymentAndStartIT}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8SecondWorkflowOfAnAggregateTest {

  private static final WorkflowScope SCOPE = WorkflowScope.of("test-module", "TestProcess");

  private static final String AGGREGATE_ID = "agg-1";

  /**
   * When the first workflow of the aggregate started.
   */
  private static final Instant FIRST_STARTED = Instant.parse("2026-10-01T08:00:00.000Z");

  /**
   * When the second start of the aggregate was planned, a day later.
   */
  private static final Instant SECOND_PLANNED = FIRST_STARTED.plus(Duration.ofDays(1));

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
   * An instance of the workflow as the search reports it. The cluster reports start dates in
   * its own zone, which need not be UTC.
   */
  private static ProcessInstance instance(
      final Instant startedAt,
      final ProcessInstanceState state) {

    final var instance = mock(ProcessInstance.class);
    when(instance.getState()).thenReturn(state);
    when(instance.getTenantId()).thenReturn("<default>");
    when(instance.getProcessDefinitionId()).thenReturn("TestProcess");
    when(instance.getStartDate())
        .thenReturn(startedAt == null
            ? null
            : OffsetDateTime.ofInstant(startedAt, ZoneOffset.ofHours(2)));
    return instance;

  }

  /**
   * A process service whose search for the aggregate returns the given instances.
   */
  private static Camunda8ProcessService<Aggregate> serviceFinding(
      final ProcessInstance... instances) {

    final var client = mock(CamundaClient.class);
    final var search = mock(ProcessInstanceSearchRequest.class, RETURNS_SELF);
    when(client.newProcessInstanceSearchRequest()).thenReturn(search);
    @SuppressWarnings("unchecked")
    final SearchResponse<ProcessInstance> found = mock(SearchResponse.class);
    when(found.items()).thenReturn(Arrays.asList(instances));
    @SuppressWarnings("unchecked")
    final CamundaFuture<SearchResponse<ProcessInstance>> answer = mock(CamundaFuture.class);
    when(answer.join()).thenReturn(found);
    when(search.send()).thenReturn(answer);

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing ever contacts - every request of this test meets the mock above
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
  @DisplayName("A retried second start does not take the ended first workflow for its own")
  public void theFirstWorkflowDoesNotCountForTheSecondStart() {

    // the second start was planned, and its first dispatch failed before the cluster created
    // anything: the search finds the first workflow and nothing else
    final var service = serviceFinding(instance(FIRST_STARTED, ProcessInstanceState.COMPLETED));

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        service.awarenessOfWorkflowForRedispatch(SCOPE, persistence(), AGGREGATE_ID, SECOND_PLANNED),
        "the first workflow started before the second start was planned, so the retry has to start");
    assertEquals(
        WorkflowAwareness.ACTIVE,
        service.awarenessOfWorkflowForRedispatch(SCOPE, persistence(), AGGREGATE_ID),
        "the question without the moment still sees the first workflow, which is the loss this probe avoids");

  }

  @Test
  @DisplayName("A second workflow started for the entry is found, running or ended")
  public void theSecondWorkflowCounts() {

    // the first dispatch of the second start did create the workflow, and only its
    // acknowledgement got lost
    final var second = SECOND_PLANNED.plusMillis(250);

    assertEquals(
        WorkflowAwareness.ACTIVE,
        serviceFinding(
            instance(FIRST_STARTED, ProcessInstanceState.COMPLETED),
            instance(second, ProcessInstanceState.ACTIVE))
            .awarenessOfWorkflowForRedispatch(SCOPE, persistence(), AGGREGATE_ID, SECOND_PLANNED),
        "the workflow this entry started runs, so the retry has to be skipped");
    assertEquals(
        WorkflowAwareness.ACTIVE,
        serviceFinding(
            instance(FIRST_STARTED, ProcessInstanceState.COMPLETED),
            instance(second, ProcessInstanceState.COMPLETED))
            .awarenessOfWorkflowForRedispatch(SCOPE, persistence(), AGGREGATE_ID, SECOND_PLANNED),
        "the workflow this entry started ended already, which still means it must not start again");

  }

  @Test
  @DisplayName("An instance started in the very millisecond the start was planned counts")
  public void theMomentItselfCounts() {

    assertEquals(
        WorkflowAwareness.ACTIVE,
        serviceFinding(instance(SECOND_PLANNED, ProcessInstanceState.ACTIVE))
            .awarenessOfWorkflowForRedispatch(SCOPE, persistence(), AGGREGATE_ID, SECOND_PLANNED),
        "at or after the moment, not only after it");

  }

  @Test
  @DisplayName("An instance without a start date does not count")
  public void anInstanceWithoutAStartDateDoesNotCount() {

    // counting it could skip the start and lose the workflow, while not counting it costs at
    // most a duplicate, which the at-least-once contract permits
    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        serviceFinding(instance(null, ProcessInstanceState.ACTIVE))
            .awarenessOfWorkflowForRedispatch(SCOPE, persistence(), AGGREGATE_ID, SECOND_PLANNED));

  }

  @Test
  @DisplayName("An entry planned before the moment was recorded counts every instance, as before")
  public void anEntryWithoutTheMomentCountsEveryInstance() {

    assertEquals(
        WorkflowAwareness.ACTIVE,
        serviceFinding(instance(FIRST_STARTED, ProcessInstanceState.COMPLETED))
            .awarenessOfWorkflowForRedispatch(SCOPE, persistence(), AGGREGATE_ID, null),
        "without the moment nothing tells the two workflows apart");

  }

  @Test
  @DisplayName("The moment does not widen the scope: an instance of another module stays unknown")
  public void theScopeStillCounts() {

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        serviceFinding(instance(SECOND_PLANNED.plusSeconds(1), ProcessInstanceState.ACTIVE))
            .awarenessOfWorkflowForRedispatch(
                WorkflowScope.of("another-module", "AnotherProcess"),
                persistence(),
                AGGREGATE_ID,
                SECOND_PLANNED),
        "the instances are narrowed to the scope like in every other probe");

  }

}
