package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import io.camunda.client.api.command.SetVariablesCommandStep1;
import io.camunda.client.api.command.SetVariablesCommandStep1.SetVariablesCommandStep2;
import io.camunda.client.api.search.request.ProcessInstanceSearchRequest;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.SearchResponsePage;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Where a push into the workflow's own scope writes when the core hands in the process
 * instance key of the start row.
 * <p>
 * The engine answers a command by key, so a push which has that key needs no search and
 * arrives while the exporter of the cluster stands still. The search stays where the key
 * cannot be trusted to belong to this adapter id, and where the engine says the instance is
 * gone.
 * <p>
 * The cluster is played by mocks: a search which knows no workflow, and the command which
 * writes the variables, whose answer each test chooses.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8GlobalPushByTheStartRowTest {

  private static final long STARTED_INSTANCE = 2251799813690126L;

  /**
   * The sentence of the WARN which says that the push was given up because the search found
   * no workflow.
   */
  private static final String NO_ACTIVE_WORKFLOW = "no active workflow found for aggregate '56'";

  private final CamundaClient client = mock(CamundaClient.class);

  /**
   * Which process instance the changed aggregate was written to.
   */
  private final List<Long> writtenTo = new ArrayList<>();

  /**
   * How often the query API was searched, each search noted with the number of writes which
   * happened before it.
   */
  private final List<Object> searches = new ArrayList<>();

  @BeforeEach
  public void setUp() {

    final var search = mock(ProcessInstanceSearchRequest.class, RETURNS_SELF);
    Mockito.lenient().when(client.newProcessInstanceSearchRequest()).thenAnswer(invocation -> {
      searches.add(writtenTo.size());
      return search;
    });
    // a read model whose exporter stopped before the workflow was started
    Mockito.lenient().when(search.send()).thenAnswer(invocation -> future(response()));

  }

  @Test
  @DisplayName("The key of the start row is written to without a search")
  public void theKeyOfTheStartRowIsWrittenToWithoutASearch(
      final CapturedOutput output) {

    theWritesAreRecorded(null);

    push(String.valueOf(STARTED_INSTANCE), false);

    assertEquals(List.of(STARTED_INSTANCE), writtenTo, "the values belong to the instance the start row names");
    assertEquals(
        List.of(1),
        searches,
        "a workflow the engine answers for by key needs no read model: it is written first, and the one search"
            + " afterwards looks for the called instances below it");
    assertTrue(
        output
            .getAllOfThisTest()
            .contains("pushed the changed aggregate '56' into process instance '%s'".formatted(STARTED_INSTANCE)),
        output.getAllOfThisTest());

  }

  @Test
  @DisplayName("An instance the engine no longer holds is searched for, as without a key")
  public void anInstanceTheEngineNoLongerHoldsIsSearchedFor(
      final CapturedOutput output) {

    theWritesAreRecorded(new ClientHttpException("Failed with code 404", 404, "no such element"));

    push(String.valueOf(STARTED_INSTANCE), false);

    assertEquals(List.of(STARTED_INSTANCE), writtenTo, "the key was tried first");
    assertEquals(1, searches.size(), "a 404 falls back to the search");
    assertTrue(output.getAllOfThisTest().contains(NO_ACTIVE_WORKFLOW), output.getAllOfThisTest());

  }

  @Test
  @DisplayName("Any other refusal of the write reaches the outbox as it is")
  public void anyOtherRefusalIsThrown() {

    final var outage = new IllegalStateException("connection reset");
    theWritesAreRecorded(outage);

    final var thrown = assertThrows(IllegalStateException.class, () -> push(String.valueOf(STARTED_INSTANCE), false));

    assertSame(outage, thrown, "the outbox repeats an outage");
    assertEquals(List.of(), searches, "an outage says nothing about the instance, so nothing is searched");

  }

  @Test
  @DisplayName("On a cluster other adapter ids share, the key is not used")
  public void onASharedClusterTheKeyIsNotUsed(
      final CapturedOutput output) {

    theWritesAreRecorded(null);

    push(String.valueOf(STARTED_INSTANCE), true);

    assertEquals(List.of(), writtenTo, "a key says nothing about which adapter id deployed the process");
    assertEquals(1, searches.size(), "the search is what tells the adapter ids apart");
    assertTrue(output.getAllOfThisTest().contains(NO_ACTIVE_WORKFLOW), output.getAllOfThisTest());

  }

  @Test
  @DisplayName("Without a key, or with one which is no key of this cluster, the workflow is searched for")
  public void withoutAUsableKeyTheWorkflowIsSearchedFor() {

    theWritesAreRecorded(null);

    push(null, false);
    push("  ", false);
    push("a-key-of-another-bpms", false);

    assertEquals(List.of(), writtenTo, "the search found nothing, so nothing is written");
    assertEquals(3, searches.size(), "every push searched");

  }

  private void push(
      final String workflowId,
      final boolean sharedCluster) {

    configuredService(sharedCluster)
        .phaseOperations()
        .get(PhaseOperation.AGGREGATE_CHANGED)
        .phaseTwo(
            new PhaseTwoRequest<>(
                "order-module", "OrderApproval", persistence(), "56", Map.of(), null, workflowId));

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

  }

  private static <T> SearchResponse<T> response() {

    @SuppressWarnings("unchecked")
    final SearchResponse<T> response = mock(SearchResponse.class);
    final var page = mock(SearchResponsePage.class);
    Mockito.lenient().when(page.totalItems()).thenReturn(0L);
    Mockito.lenient().when(response.items()).thenReturn(List.of());
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
