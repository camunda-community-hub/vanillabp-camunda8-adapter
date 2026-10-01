package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.search.enums.ElementInstanceState;
import io.camunda.client.api.search.enums.ElementInstanceType;
import io.camunda.client.api.search.enums.IncidentState;
import io.camunda.client.api.search.request.ElementInstanceSearchRequest;
import io.camunda.client.api.search.request.IncidentsByProcessInstanceSearchRequest;
import io.camunda.client.api.search.request.ProcessInstanceSearchRequest;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.client.api.search.response.Incident;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.SearchResponsePage;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.deployment.Camunda8DeployedProcesses;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.WorkflowElementHistory;

/**
 * The history of a workflow which has MORE element instances than one page holds - a
 * multi-instance over a long list, or a loop which ran often.
 * <p>
 * A history is the whole workflow or it is misleading. The elements come back in the order
 * they started, so a search answering one page hands out the BEGINNING of the workflow, and
 * nothing in the answer says that it stopped there: the viewer shows a workflow which looks
 * finished at an element the engine left long ago. The incidents are read the same way,
 * where a message missing from the history reads as an element which ran cleanly.
 * <p>
 * The cluster is played by the searches, one page per call. Which page the adapter asked
 * for is not read off the call, because the type the client passes the page lambda differs
 * between the release lines this adapter is built from - what is measured is how many pages
 * it asked for and what the history is made of.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8HistoryBeyondOnePageTest {

  private static final String BPMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
      id="Definitions_Order" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="OrderApproval" isExecutable="true">
          <bpmn:startEvent id="TheStart" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * How many iterations the multi-instance of this workflow ran, which is what takes one
   * workflow past the 100 of a page.
   */
  private static final int ITERATIONS = 150;

  private final CamundaClient client = mock(CamundaClient.class);

  private Camunda8WorkflowViewer viewer;

  /**
   * The pages of element instances the cluster was asked for.
   */
  private final List<List<ElementInstance>> elementPagesAnswered = new ArrayList<>();

  /**
   * The pages of incidents the cluster was asked for.
   */
  private final List<List<Incident>> incidentPagesAnswered = new ArrayList<>();

  @BeforeEach
  public void setUp() {

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing contacts: every request of this test meets the mock below
    configuration.setRestAddress("http://localhost:1");
    final var clientFactory = spy(new Camunda8ClientFactory("c8", configuration));
    doReturn(client).when(clientFactory).getClient();
    clientFactory
        .getDeployedProcesses()
        .record(
            new Camunda8DeployedProcesses.DeployedProcess(
                "order-module", "OrderApproval", "111", 1, Bpmn
                    .readModelFromStream(new ByteArrayInputStream(BPMN.getBytes(StandardCharsets.UTF_8)))));

    final var instance = mock(ProcessInstance.class);
    when(instance.getProcessInstanceKey()).thenReturn(Long.valueOf(4711L));
    when(instance.getProcessDefinitionKey()).thenReturn(Long.valueOf(111L));
    when(instance.getStartDate()).thenReturn(OffsetDateTime.parse("2026-10-01T10:00:00+02:00"));
    final var instanceSearch = mock(ProcessInstanceSearchRequest.class, RETURNS_SELF);
    when(client.newProcessInstanceSearchRequest()).thenReturn(instanceSearch);
    when(instanceSearch.send()).thenAnswer(invocation -> future(response(List.of(instance), null)));

    viewer = new Camunda8WorkflowViewer("c8", clientFactory, (
        module,
        process) -> process, module -> null);

  }

  @Test
  @DisplayName("Every element instance of a long-running workflow is in its history")
  public void everyElementInstanceIsInTheHistory() {

    theClusterHoldsElementInstances(ITERATIONS);
    theClusterHoldsIncidents(0);

    final var history = viewer.getWorkflowHistory("order-module", "OrderApproval", "id", "42", null);

    final var elementIds = history
        .elementsHistory()
        .stream()
        .map(WorkflowElementHistory::elementId)
        .toList();
    assertEquals(ITERATIONS, elementIds.size(), () -> "every element instance of the workflow, but got "
        + elementIds.size());
    assertTrue(
        elementIds.contains(elementIdOf(ITERATIONS - 1)),
        "the iteration which ran last is where the workflow stands, and it is the one a single page "
            + "leaves out");
    assertEquals(
        2,
        elementPagesAnswered.size(),
        () -> "150 element instances are two pages, and the viewer asked for both, but asked for "
            + elementPagesAnswered.size());

  }

  @Test
  @DisplayName("The error message of an incident past the first page reaches its element")
  public void anIncidentPastTheFirstPageIsReported() {

    theClusterHoldsElementInstances(ITERATIONS);
    theClusterHoldsIncidents(ITERATIONS);

    final var history = viewer.getWorkflowHistory("order-module", "OrderApproval", "id", "42", null);

    final var lastIteration = history
        .elementsHistory()
        .stream()
        .filter(element -> elementIdOf(ITERATIONS - 1).equals(element.elementId()))
        .findFirst()
        .orElseThrow();
    assertEquals(
        "iteration "
            + (ITERATIONS - 1)
            + " failed",
        lastIteration.error(),
        "an element reported without its error message reads like an element which ran cleanly");
    assertEquals(
        2,
        incidentPagesAnswered.size(),
        () -> "the incidents are read page by page as well, but were asked for in "
            + incidentPagesAnswered.size());

  }

  /**
   * A cluster whose workflow has one element instance per iteration, handed out one page at
   * a time in the order they started.
   */
  private void theClusterHoldsElementInstances(
      final int iterations) {

    final var all = IntStream
        .range(0, iterations)
        .mapToObj(Camunda8HistoryBeyondOnePageTest::elementInstance)
        .toList();
    final var search = mock(ElementInstanceSearchRequest.class, RETURNS_SELF);
    when(client.newElementInstanceSearchRequest()).thenReturn(search);
    when(search.send()).thenAnswer(invocation -> {
      final var page = pageOf(all, elementPagesAnswered.size());
      elementPagesAnswered.add(page);
      return future(response(page, "after-element-"
          + elementPagesAnswered.size()));
    });

  }

  /**
   * A cluster whose workflow has one active incident per iteration.
   */
  private void theClusterHoldsIncidents(
      final int iterations) {

    final var all = IntStream
        .range(0, iterations)
        .mapToObj(Camunda8HistoryBeyondOnePageTest::incident)
        .toList();
    final var search = mock(IncidentsByProcessInstanceSearchRequest.class, RETURNS_SELF);
    when(client.newIncidentsByProcessInstanceSearchRequest(anyLong())).thenReturn(search);
    when(search.send()).thenAnswer(invocation -> {
      final var page = pageOf(all, incidentPagesAnswered.size());
      incidentPagesAnswered.add(page);
      return future(response(page, "after-incident-"
          + incidentPagesAnswered.size()));
    });

  }

  private static <T> List<T> pageOf(
      final List<T> all,
      final int pagesAnswered) {

    final var from = pagesAnswered * Camunda8SearchPages.PAGE_SIZE;
    return all.subList(Math.min(from, all.size()), Math.min(from + Camunda8SearchPages.PAGE_SIZE, all.size()));

  }

  private static String elementIdOf(
      final int iteration) {

    return "ApproveItem#"
        + iteration;

  }

  private static ElementInstance elementInstance(
      final int iteration) {

    final var elementInstance = mock(ElementInstance.class);
    Mockito.lenient().when(elementInstance.getElementId()).thenReturn(elementIdOf(iteration));
    Mockito.lenient().when(elementInstance.getType()).thenReturn(ElementInstanceType.SERVICE_TASK);
    Mockito.lenient().when(elementInstance.getState()).thenReturn(ElementInstanceState.ACTIVE);
    Mockito
        .lenient()
        .when(elementInstance.getStartDate())
        .thenReturn(OffsetDateTime.parse("2026-10-01T10:00:00+02:00").plusSeconds(iteration));
    return elementInstance;

  }

  private static Incident incident(
      final int iteration) {

    final var incident = mock(Incident.class);
    Mockito.lenient().when(incident.getState()).thenReturn(IncidentState.ACTIVE);
    Mockito.lenient().when(incident.getElementId()).thenReturn(elementIdOf(iteration));
    Mockito
        .lenient()
        .when(incident.getErrorMessage())
        .thenReturn("iteration "
            + iteration
            + " failed");
    return incident;

  }

  private static <T> SearchResponse<T> response(
      final List<T> items,
      final String endCursor) {

    @SuppressWarnings("unchecked")
    final SearchResponse<T> response = mock(SearchResponse.class);
    final var page = mock(SearchResponsePage.class);
    Mockito.lenient().when(page.totalItems()).thenReturn(Long.valueOf(items.size()));
    Mockito.lenient().when(page.endCursor()).thenReturn(endCursor);
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
