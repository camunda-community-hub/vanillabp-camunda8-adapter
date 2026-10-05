package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
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
 * What the election probe answers when its search fails: BPMS_UNAVAILABLE, and never a
 * guess about what the cluster holds.
 * <p>
 * The cluster of this adapter can be searched - the deployment refuses one which cannot -
 * so a failed search is an outage, and an outage is an answer nobody has rather than a
 * missing feature. The distinction the probe used to make was between those two, and it
 * is gone because only one of them is left. What stays worth pinning is that a failure
 * carrying the WORDS of a cluster without secondary storage still counts as an outage:
 * the capability was settled by a probe of its own, and nothing re-derives it from the
 * prose of a failure.
 * <p>
 * What a search which ANSWERS and finds nothing means is the other half of the contract,
 * and it belongs against a real cluster rather than against a mock:
 * {@code Camunda8DeploymentAndStartIT#aWorkflowNobodyStartedIsUnknownToBothProbes} holds
 * that both probes answer UNKNOWN_TO_BPMS there, which is an answer of the cluster and not
 * the absence of one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8AwarenessWhenSearchFailsTest {

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
   * A process service whose cluster is asked once whether it can be searched - the way the
   * deployment asks before it deploys a workflow module - and fails every search after
   * that.
   *
   * @param searchFailure What every search after the probe throws
   * @return The service under test, its capability settled
   */
  private static Camunda8ProcessService<Aggregate> serviceOf(
      final RuntimeException searchFailure) {

    final var client = mock(CamundaClient.class);
    final var search = mock(ProcessInstanceSearchRequest.class, RETURNS_SELF);
    when(client.newProcessInstanceSearchRequest()).thenReturn(search);
    @SuppressWarnings("unchecked")
    final SearchResponse<ProcessInstance> nothing = mock(SearchResponse.class);
    when(nothing.items()).thenReturn(List.of());
    @SuppressWarnings("unchecked")
    final CamundaFuture<SearchResponse<ProcessInstance>> answer = mock(CamundaFuture.class);
    when(answer.join()).thenReturn(nothing);
    final var probed = new AtomicBoolean();
    when(search.send()).thenAnswer(invocation -> {
      if (probed.compareAndSet(false, true)) {
        return answer;
      }
      throw searchFailure;
    });

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing ever contacts - every request of this test meets the mock below
    configuration.setRestAddress("http://localhost:1");
    // no waiting for the exporter in a unit test - the cluster is never contacted
    configuration.setWorkflowVisibilityTimeout(Duration.ZERO);
    final var clientFactory = new Camunda8ClientFactory("c8", configuration) {

      @Override
      public CamundaClient getClient() {
        return client;
      }

    };
    final var service = new Camunda8ProcessService<Aggregate>(
        "c8", clientFactory, Duration.ofDays(14), (
            aggregateClass,
            check) -> check.run(), null);
    // what the deployment does before it deploys, and the only search which is allowed
    // to succeed here: from now on the capability is settled and every later failure is
    // read against it
    clientFactory.getQueryApi().answers();
    return service;

  }

  @Test
  @DisplayName("A cluster which can be searched turns a failing probe into BPMS_UNAVAILABLE")
  public void aSearchableClusterFailingASearchIsUnavailable() {

    // the failure even carries the words a cluster without secondary storage uses, and
    // they change nothing: this cluster answered the probe, so a search failing now is
    // an outage
    final var service = serviceOf(
        new IllegalStateException("This endpoint requires a secondary storage, but none is set"));

    assertTrue(service.canLocateWorkflows());
    assertEquals(
        WorkflowAwareness.BPMS_UNAVAILABLE,
        service.awarenessOfWorkflow(SCOPE, persistence(), "agg-1"));

  }

  @Test
  @DisplayName("The re-dispatch probe reports an outage of a searchable cluster as such")
  public void theRedispatchProbeReportsAnOutage() {

    final var service = serviceOf(new IllegalStateException("connection reset"));

    assertEquals(
        WorkflowAwareness.BPMS_UNAVAILABLE,
        service.awarenessOfWorkflowForRedispatch(SCOPE, persistence(), "agg-1"),
        "so the outbox entry is retried instead of starting the workflow a second time");

  }

}
