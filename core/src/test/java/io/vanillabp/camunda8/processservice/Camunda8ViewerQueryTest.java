package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
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
import io.camunda.client.api.command.ProblemException;
import io.camunda.client.api.fetch.ProcessDefinitionGetXmlRequest;
import io.camunda.client.api.search.enums.ElementInstanceState;
import io.camunda.client.api.search.enums.ElementInstanceType;
import io.camunda.client.api.search.enums.IncidentState;
import io.camunda.client.api.search.filter.ProcessInstanceFilter;
import io.camunda.client.api.search.request.ElementInstanceSearchRequest;
import io.camunda.client.api.search.request.IncidentsByProcessInstanceSearchRequest;
import io.camunda.client.api.search.request.ProcessInstanceSearchRequest;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.client.api.search.response.Incident;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.deployment.Camunda8DeployedProcesses;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.WorkflowElementType;

/**
 * The viewer/history API's query paths of the Camunda 8 adapter, against a mocked cluster.
 * The point here is the mapping: which instance answers, how element instances and
 * incidents become a {@code WorkflowHistory}, and how a call activity's secondary history
 * context is resolved and validated.
 * <p>
 * A mock rather than a container because three of these ask what a FAILING search does
 * ({@link #anOutageWhileReadingElementInstancesYieldsNoElementHistory},
 * {@link #anOutageWhileReadingIncidentsYieldsNoErrors} and
 * {@link #anOutageIsWarnedAboutOnce}), and an outage is not something a healthy Docker
 * cluster does on request.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ViewerQueryTest {

  private static final String PARENT_BPMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" \
      xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="Definitions_Parent" \
      targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="ParentProcess" isExecutable="true">
          <bpmn:startEvent id="TheStart" />
          <bpmn:callActivity id="TheCallActivity">
            <bpmn:extensionElements>
              <zeebe:calledElement processId="SubProcess" propagateAllChildVariables="false" />
            </bpmn:extensionElements>
          </bpmn:callActivity>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static final String SUB_BPMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_Sub" \
      targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="SubProcess" isExecutable="true">
          <bpmn:startEvent id="TheSubStart" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  private final CamundaClient client = mock(CamundaClient.class);

  private Camunda8ClientFactory clientFactory;

  private Camunda8WorkflowViewer viewer;

  /**
   * The process instances the mocked cluster knows - the search returns the first
   * one matching the filter the production code applies (the filters themselves
   * are consumers, so the test steers the ANSWER, not the filter).
   */
  private List<ProcessInstance> foundInstances = List.of();

  private List<ElementInstance> foundElementInstances = List.of();

  /**
   * Answers for CONSECUTIVE instance searches (the production code searches the
   * primary instance first, then the called one) - the mocked cluster cannot
   * distinguish the filters, so the test scripts the sequence. Falls back to
   * {@link #foundInstances} once exhausted.
   */
  private final Deque<List<ProcessInstance>> scriptedInstanceAnswers = new ArrayDeque<>();

  @BeforeEach
  public void setUp() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:1");
    clientFactory = spy(new Camunda8ClientFactory("c8", configuration));
    doReturn(client)
        .when(clientFactory)
        .getClient();
    clientFactory
        .getDeployedProcesses()
        .record(
            new Camunda8DeployedProcesses.DeployedProcess(
                "test-module", "ParentProcess", "111", 3, Bpmn
                    .readModelFromStream(new ByteArrayInputStream(PARENT_BPMN.getBytes(StandardCharsets.UTF_8)))));
    clientFactory
        .getDeployedProcesses()
        .record(
            new Camunda8DeployedProcesses.DeployedProcess(
                "test-module", "SubProcess", "222", 1, Bpmn
                    .readModelFromStream(new ByteArrayInputStream(SUB_BPMN.getBytes(StandardCharsets.UTF_8)))));

    final var instanceSearch = mock(ProcessInstanceSearchRequest.class, RETURNS_SELF);
    when(client.newProcessInstanceSearchRequest()).thenReturn(instanceSearch);
    when(instanceSearch.send()).thenAnswer(invocation -> future(response(
        scriptedInstanceAnswers.isEmpty()
            ? foundInstances
            : scriptedInstanceAnswers.poll())));

    final var elementSearch = mock(ElementInstanceSearchRequest.class, RETURNS_SELF);
    when(client.newElementInstanceSearchRequest()).thenReturn(elementSearch);
    when(elementSearch.send()).thenAnswer(invocation -> future(response(foundElementInstances)));

    viewer = new Camunda8WorkflowViewer("c8", clientFactory, (
        module,
        process) -> process, module -> null);

  }

  private static <T> SearchResponse<T> response(
      final List<T> items) {

    @SuppressWarnings("unchecked")
    final SearchResponse<T> response = mock(SearchResponse.class);
    when(response.items()).thenReturn(items);
    return response;

  }

  /**
   * A cluster which stopped answering. The adapter's cluster can be searched - the
   * deployment refuses one which cannot - so this is what a viewer query fails with, and
   * what the viewer reports as no history rather than as an error.
   *
   * @return The failure a search of an unreachable cluster ends with
   */
  private static RuntimeException clusterDoesNotAnswer() {

    return new ProblemException(503, "Service Unavailable", null);

  }

  private static <T> CamundaFuture<T> future(
      final T value) {

    @SuppressWarnings("unchecked")
    final CamundaFuture<T> future = mock(CamundaFuture.class);
    when(future.join()).thenReturn(value);
    return future;

  }

  private static ProcessInstance instance(
      final long instanceKey,
      final String processDefinitionKey,
      final Long parentInstanceKey) {

    final var instance = mock(ProcessInstance.class);
    when(instance.getProcessInstanceKey()).thenReturn(instanceKey);
    when(instance.getProcessDefinitionKey()).thenReturn(Long.valueOf(processDefinitionKey));
    when(instance.getParentProcessInstanceKey()).thenReturn(parentInstanceKey);
    return instance;

  }

  private static ElementInstance elementInstance(
      final String elementId,
      final ElementInstanceType type,
      final ElementInstanceState state) {

    final var elementInstance = mock(ElementInstance.class);
    when(elementInstance.getElementId()).thenReturn(elementId);
    when(elementInstance.getType()).thenReturn(type);
    when(elementInstance.getState()).thenReturn(state);
    when(elementInstance.getStartDate()).thenReturn(OffsetDateTime.parse("2026-08-06T10:00:00+02:00"));
    return elementInstance;

  }

  private void withoutIncidents() {

    final var incidentSearch = mock(IncidentsByProcessInstanceSearchRequest.class, RETURNS_SELF);
    when(client.newIncidentsByProcessInstanceSearchRequest(anyLong())).thenReturn(incidentSearch);
    when(incidentSearch.send()).thenAnswer(invocation -> future(response(List.<Incident>of())));

  }

  @Test
  @DisplayName("The definition a RUNNING workflow uses is reported, incl. its call activity's definition")
  public void runningInstanceAnswersItsDefinition() {

    foundInstances = List.of(instance(4711L, "111", null));

    final var definitions = viewer.getProcessDefinitions("test-module", "ParentProcess", "id", "42", null);

    assertEquals(2, definitions.size());
    assertEquals("111", definitions.get(0).id());
    assertEquals("3", definitions.get(0).version());
    assertEquals("222", definitions.get(1).id());
    assertEquals(List.of("TheCallActivity"), definitions.get(1).usedByElements());

  }

  @Test
  @DisplayName("Element instances and incidents become the workflow history, call activities their context")
  public void elementInstancesBecomeTheHistory() {

    final var primaryInstance = instance(4711L, "111", null);
    when(primaryInstance.getStartDate()).thenReturn(OffsetDateTime.parse("2026-08-06T10:00:00+02:00"));
    foundInstances = List.of(primaryInstance);

    final var startEvent = elementInstance("TheStart", ElementInstanceType.START_EVENT, ElementInstanceState.COMPLETED);
    when(startEvent.getEndDate()).thenReturn(OffsetDateTime.parse("2026-08-06T10:00:01+02:00"));
    final var callActivity = elementInstance(
        "TheCallActivity", ElementInstanceType.CALL_ACTIVITY, ElementInstanceState.ACTIVE);
    when(callActivity.getElementInstanceKey()).thenReturn(99L);
    foundElementInstances = List.of(startEvent, callActivity);

    final var incident = mock(Incident.class);
    when(incident.getState()).thenReturn(IncidentState.ACTIVE);
    when(incident.getElementId()).thenReturn("TheCallActivity");
    when(incident.getErrorMessage()).thenReturn("something went wrong");
    final var incidentSearch = mock(IncidentsByProcessInstanceSearchRequest.class, RETURNS_SELF);
    when(client.newIncidentsByProcessInstanceSearchRequest(anyLong())).thenReturn(incidentSearch);
    when(incidentSearch.send()).thenAnswer(invocation -> future(response(List.of(incident))));

    final var history = viewer.getWorkflowHistory("test-module", "ParentProcess", "id", "42", null);

    assertEquals("111", history.processDefinitionId());
    assertNotNull(history.startTime());
    assertEquals(2, history.elementsHistory().size());
    assertEquals(
        WorkflowElementType.START_EVENT,
        history
            .elementsHistory()
            .get(0)
            .elementType());
    final var callActivityHistory = history
        .elementsHistory()
        .get(1);
    assertEquals(WorkflowElementType.CALL_ACTIVITY, callActivityHistory.elementType());
    assertEquals("something went wrong", callActivityHistory.error());
    // the called instance is looked up by the call activity's element instance key
    assertEquals("4711", callActivityHistory.secondaryWorkflowHistoryContext());

  }

  @Test
  @DisplayName("A TERMINATED element instance is reported as canceled")
  public void terminatedElementsAreCanceled() {

    foundInstances = List.of(instance(4711L, "111", null));
    foundElementInstances = List.of(
        elementInstance("TheCallActivity", ElementInstanceType.SERVICE_TASK, ElementInstanceState.TERMINATED));
    withoutIncidents();

    final var history = viewer.getWorkflowHistory("test-module", "ParentProcess", "id", "42", null);

    assertTrue(
        history
            .elementsHistory()
            .getFirst()
            .isCanceled());

  }

  @Test
  @DisplayName("A history context is accepted only if its instance belongs to the workflow")
  public void historyContextIsValidatedAgainstTheWorkflow() {

    // the search answers the SAME instance for every filter - a called instance
    // whose parent chain reaches the primary instance
    foundInstances = List.of(instance(4711L, "222", null));

    final var definitions = viewer.getProcessDefinitions("test-module", "ParentProcess", "id", "42", "4711");

    assertEquals(1, definitions.size(), () -> "the called instance's definition is reported but got: "
        + definitions);
    assertEquals("222", definitions
        .getFirst()
        .id());

    // a called instance whose parent chain does NOT reach the workflow's primary
    // instance is rejected (scripted: first search = primary, second = the called
    // instance the context names)
    final var primaryInstance = instance(4711L, "111", null);
    final var foreignInstance = instance(9999L, "222", null);
    scriptedInstanceAnswers.add(List.of(primaryInstance));
    scriptedInstanceAnswers.add(List.of(foreignInstance));
    assertEquals(
        List.of(),
        viewer.getProcessDefinitions("test-module", "ParentProcess", "id", "42", "9999"));
    scriptedInstanceAnswers.add(List.of(primaryInstance));
    scriptedInstanceAnswers.add(List.of(foreignInstance));
    assertNull(viewer.getWorkflowHistory("test-module", "ParentProcess", "id", "42", "9999"));
    // a non-numeric context can never be a Camunda 8 process instance key
    assertEquals(
        List.of(),
        viewer.getProcessDefinitions("test-module", "ParentProcess", "id", "42", "not-a-key"));

  }

  @Test
  @DisplayName("A definition of a previous application version is fetched from the cluster")
  public void definitionsOfPreviousApplicationVersionsComeFromTheCluster() throws Exception {

    // the running instance uses a definition this application version did not deploy
    foundInstances = List.of(instance(4711L, "999", null));
    final var xmlRequest = mock(ProcessDefinitionGetXmlRequest.class, RETURNS_SELF);
    when(client.newProcessDefinitionGetXmlRequest(anyLong())).thenReturn(xmlRequest);
    when(xmlRequest.send()).thenAnswer(invocation -> future(PARENT_BPMN));

    final var definitions = viewer.getProcessDefinitions("test-module", "ParentProcess", "id", "42", null);

    assertEquals(2, definitions.size(), () -> "the cluster's model is parsed for its call activities but got: "
        + definitions);
    assertEquals("999", definitions
        .getFirst()
        .id());

    try (var xml = viewer.getBpmnXml("999")) {
      assertEquals(PARENT_BPMN, new String(xml.readAllBytes(), StandardCharsets.UTF_8));
    }

  }

  @Test
  @DisplayName("A cluster answering no XML for a definition is reported as unknown")
  public void missingClusterXmlIsReportedAsUnknown() {

    final var xmlRequest = mock(ProcessDefinitionGetXmlRequest.class, RETURNS_SELF);
    when(client.newProcessDefinitionGetXmlRequest(anyLong())).thenReturn(xmlRequest);
    when(xmlRequest.send()).thenAnswer(invocation -> future((String) null));

    assertNull(viewer.getBpmnXml("999"));

  }

  @Test
  @DisplayName("A cluster which stops answering: the history reports the instance without elements")
  public void anOutageWhileReadingElementInstancesYieldsNoElementHistory() {

    foundInstances = List.of(instance(4711L, "111", null));
    final var elementSearch = mock(ElementInstanceSearchRequest.class, RETURNS_SELF);
    when(client.newElementInstanceSearchRequest()).thenReturn(elementSearch);
    when(elementSearch.send()).thenThrow(clusterDoesNotAnswer());

    final var history = viewer.getWorkflowHistory("test-module", "ParentProcess", "id", "42", null);

    assertEquals("111", history.processDefinitionId());
    assertNull(history.elementsHistory());

  }

  @Test
  @DisplayName("A cluster which stops answering for incidents: the history has no error messages")
  public void anOutageWhileReadingIncidentsYieldsNoErrors() {

    foundInstances = List.of(instance(4711L, "111", null));
    foundElementInstances = List.of(
        elementInstance("TheStart", ElementInstanceType.START_EVENT, ElementInstanceState.COMPLETED));
    when(client.newIncidentsByProcessInstanceSearchRequest(anyLong()))
        .thenThrow(clusterDoesNotAnswer());

    final var history = viewer.getWorkflowHistory("test-module", "ParentProcess", "id", "42", null);

    assertNull(
        history
            .elementsHistory()
            .getFirst()
            .error());

  }

  @Test
  @DisplayName("The instance search filters by the aggregate ID as the cluster stores it")
  public void theInstanceSearchFiltersByTheAggregateIdVariable() {

    // proves the filter callback is executed (it is a consumer of the filter API)
    final var filter = mock(ProcessInstanceFilter.class, RETURNS_SELF);
    final var instanceSearch = mock(ProcessInstanceSearchRequest.class, RETURNS_SELF);
    when(client.newProcessInstanceSearchRequest()).thenReturn(instanceSearch);
    when(instanceSearch.filter(any(Consumer.class)))
        .thenAnswer(invocation -> {
          invocation.<Consumer<ProcessInstanceFilter>>getArgument(
              0)
              .accept(filter);
          return instanceSearch;
        });
    when(instanceSearch.send()).thenAnswer(invocation -> future(response(List.<ProcessInstance>of())));

    assertNull(viewer.getWorkflowHistory("test-module", "ParentProcess", "aggregateId", "42", null).elementsHistory());

    // The cluster compares a variable against its stored JSON, so the ID
    // travels quoted. This assertion used to pin the plain value and thereby pinned
    // the defect - the viewer found no workflow at all on a cluster with secondary
    // storage
    Mockito
        .verify(filter)
        .variables(Map.of("aggregateId", "\"42\""));

  }


  @Test
  @DisplayName("A nested called instance is accepted via its parent chain")
  public void nestedCalledInstancesAreAcceptedViaTheParentChain() {

    // primary 4711 -> called 8888 (parent 5555) -> 5555 (parent 4711)
    final var primaryInstance = instance(4711L, "111", null);
    final var nestedInstance = instance(8888L, "222", 5555L);
    final var intermediateInstance = instance(5555L, "111", 4711L);
    scriptedInstanceAnswers.add(List.of(primaryInstance));
    scriptedInstanceAnswers.add(List.of(nestedInstance));
    scriptedInstanceAnswers.add(List.of(intermediateInstance));
    scriptedInstanceAnswers.add(List.of(primaryInstance));

    final var definitions = viewer.getProcessDefinitions("test-module", "ParentProcess", "id", "8888", "8888");

    assertEquals(1, definitions.size(), () -> "the nested instance's definition is reported but got: "
        + definitions);
    assertEquals("222", definitions
        .getFirst()
        .id());

  }

  @Test
  @DisplayName("An outage is warned about once, and every read after that only logs at debug level")
  public void anOutageIsWarnedAboutOnce(
      final CapturedOutput output) {

    final var instanceSearch = mock(ProcessInstanceSearchRequest.class, RETURNS_SELF);
    when(client.newProcessInstanceSearchRequest()).thenReturn(instanceSearch);
    when(instanceSearch.send()).thenThrow(clusterDoesNotAnswer());

    // all three calls fall back to what this application version deployed, and the
    // reader is told about it once rather than per read
    assertNotNull(viewer.getWorkflowHistory("test-module", "ParentProcess", "id", "42", null));
    assertNotNull(viewer.getWorkflowHistory("test-module", "ParentProcess", "id", "42", null));
    assertEquals(2, viewer.getProcessDefinitions("test-module", "ParentProcess", "id", "42", null).size());

    final var logged = output.getOut() + output.getErr();
    assertEquals(
        1,
        logged.split("could not query process instances, so", -1).length - 1,
        () -> "one warning, not one per read: "
            + logged);
    // and it says outage rather than missing capability, because a failed query cannot
    // tell those apart and the deployment already settled which cluster this is
    assertTrue(
        logged.contains("Retried on the next call"),
        () -> "the warning is about an outage: "
            + logged);

  }

}
