package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.fetch.ProcessDefinitionGetXmlRequest;
import io.camunda.client.api.search.filter.ProcessDefinitionFilter;
import io.camunda.client.api.search.request.DecisionDefinitionSearchRequest;
import io.camunda.client.api.search.request.ProcessDefinitionSearchRequest;
import io.camunda.client.api.search.request.ProcessInstanceSearchRequest;
import io.camunda.client.api.search.response.ProcessDefinition;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.SearchResponsePage;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.TestScoping;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter asks the cluster while an application boots, counted.
 * <p>
 * The startup check for old process versions asks the catalog about every version older
 * than the one the boot deployed: how many workflows still run on it, which tasks its model
 * has and which identifiers that model declares. All of them are addressed by the cluster's
 * process definition key, and finding that key used to be a search of its own - so a process
 * with fifty versions behind it paid a hundred searches nobody could see, on top of the one
 * search which had already brought every key back. The two questions about one version's
 * MODEL share the fetch of it, so a version costs one XML request however many of them are
 * put.
 * <p>
 * The deployment asks one more thing, once per workflow module: which of its identifiers the
 * cluster already held. That is one definition search for every BPMN process of the module
 * together, plus one search per DMN decision, and neither number grows with the history of
 * the application.
 * <p>
 * One question the deployment answers reaches no cluster at all: whether two workflow modules
 * are separated is the tenant each of them would be deployed to, and that comes out of
 * configuration.
 * <p>
 * Decision 13 in the repository's DECISIONS.md is what this holds: a request to the
 * cluster while booting is counted, and the count belongs to the versions, never to the
 * workflows.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8StartupQuestionCostTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  /**
   * How many versions the cluster holds - enough that a request per version is a
   * different number from a request per process.
   */
  private static final int VERSIONS = 4;

  private static final String A_MODEL = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" \
      targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="TestProcess" isExecutable="true">
          <bpmn:startEvent id="TheStart" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  private final CamundaClient client = mock(CamundaClient.class);

  private Camunda8ProcessVersions versions;

  /**
   * Every request the cluster answered, by kind.
   */
  private final Map<String, Integer> requests = new TreeMap<>();

  private static ProcessDefinition definition(
      final int version) {

    final var definition = mock(ProcessDefinition.class);
    Mockito
        .lenient()
        .when(definition.getProcessDefinitionKey())
        .thenReturn(Long.valueOf(1000 + version));
    Mockito
        .lenient()
        .when(definition.getVersion())
        .thenReturn(version);
    return definition;

  }

  private static <T> SearchResponse<T> response(
      final List<T> items,
      final long totalItems) {

    @SuppressWarnings("unchecked")
    final SearchResponse<T> response = mock(SearchResponse.class);
    final var page = mock(SearchResponsePage.class);
    Mockito
        .lenient()
        .when(page.totalItems())
        .thenReturn(totalItems);
    Mockito
        .lenient()
        .when(response.items())
        .thenReturn(items);
    Mockito
        .lenient()
        .when(response.page())
        .thenReturn(page);
    return response;

  }

  private static <T> CamundaFuture<T> future(
      final T value) {

    @SuppressWarnings("unchecked")
    final CamundaFuture<T> future = mock(CamundaFuture.class);
    Mockito
        .lenient()
        .when(future.join())
        .thenReturn(value);
    return future;

  }

  @BeforeEach
  public void setUp() {

    final var held = IntStream
        .rangeClosed(1, VERSIONS)
        .mapToObj(Camunda8StartupQuestionCostTest::definition)
        .toList();

    // the cluster answers a definition search with everything it holds for the process,
    // and a search naming ONE version with that version - the second is the case of a
    // version another node deployed after the list was read
    final var askedFor = new int[]{
        0
    };
    final var definitionSearch = mock(ProcessDefinitionSearchRequest.class, RETURNS_SELF);
    Mockito
        .lenient()
        .when(definitionSearch.filter(
            Mockito.<Consumer<ProcessDefinitionFilter>>any()))
        .thenAnswer(invocation -> {
          askedFor[0] = 0;
          final Consumer<ProcessDefinitionFilter> filter = invocation
              .getArgument(0);
          final var recording = mock(
              ProcessDefinitionFilter.class,
              RETURNS_SELF);
          Mockito
              .lenient()
              .when(recording.version(Mockito.anyInt()))
              .thenAnswer(versionCall -> {
                askedFor[0] = versionCall.getArgument(0);
                return recording;
              });
          filter.accept(recording);
          return definitionSearch;
        });
    Mockito
        .lenient()
        .when(definitionSearch.send())
        .thenAnswer(invocation -> future(response(
            askedFor[0] == 0
                ? held
                : List.of(definition(askedFor[0])),
            0L)));
    when(client.newProcessDefinitionSearchRequest()).thenAnswer(invocation -> {
      requests.merge("newProcessDefinitionSearchRequest", 1, Integer::sum);
      return definitionSearch;
    });

    final var instanceSearch = mock(ProcessInstanceSearchRequest.class, RETURNS_SELF);
    Mockito
        .lenient()
        .when(instanceSearch.send())
        .thenAnswer(invocation -> future(response(List.of(), 7L)));
    when(client.newProcessInstanceSearchRequest()).thenAnswer(invocation -> {
      requests.merge("newProcessInstanceSearchRequest", 1, Integer::sum);
      return instanceSearch;
    });

    final var decisionSearch = mock(DecisionDefinitionSearchRequest.class, RETURNS_SELF);
    Mockito
        .lenient()
        .when(decisionSearch.send())
        .thenAnswer(invocation -> future(response(List.of(), 0L)));
    when(client.newDecisionDefinitionSearchRequest()).thenAnswer(invocation -> {
      requests.merge("newDecisionDefinitionSearchRequest", 1, Integer::sum);
      return decisionSearch;
    });

    final var xmlRequest = mock(ProcessDefinitionGetXmlRequest.class, RETURNS_SELF);
    Mockito
        .lenient()
        .when(xmlRequest.send())
        .thenAnswer(invocation -> future(A_MODEL));
    when(client.newProcessDefinitionGetXmlRequest(Mockito.anyLong())).thenAnswer(invocation -> {
      requests.merge("newProcessDefinitionGetXmlRequest", 1, Integer::sum);
      return xmlRequest;
    });

    versions = new Camunda8ProcessVersions(
        "c8", () -> client, (
            workflowModuleId,
            bpmnProcessId) -> bpmnProcessId, workflowModuleId -> null);
    versions.setTasksOfModel((
        workflowModuleId,
        bpmnProcessId,
        version,
        model) -> List.of());
    versions.setIdentifiersOfModel((
        workflowModuleId,
        bpmnProcessId,
        version,
        model) -> List.of());
    requests.clear();

  }

  /**
   * What the startup check does per BPMN process: it asks for the versions once and then
   * asks three questions about every older one - how many workflows run on it, which tasks
   * its model has and which identifiers that model declares.
   */
  private void whatAStartAsks() {

    versions
        .deployedVersionsOf(MODULE, PROCESS)
        .stream()
        .map(DeployedProcessVersion::version)
        .filter(version -> !String.valueOf(VERSIONS).equals(version))
        .forEach(version -> {
          versions.activeInstanceCountOf(MODULE, PROCESS, version);
          versions.identifiersOfVersion(MODULE, PROCESS, version);
          versions.tasksOfVersion(MODULE, PROCESS, version);
        });

  }

  @Test
  @DisplayName("The version search is the only definition search a start needs")
  public void theVersionSearchAnswersEveryLaterQuestion() {

    whatAStartAsks();

    assertEquals(
        1,
        requests.getOrDefault("newProcessDefinitionSearchRequest", 0),
        () -> "one search holds every key the later questions need, but was "
            + requests);
    assertEquals(
        VERSIONS - 1,
        requests.getOrDefault("newProcessInstanceSearchRequest", 0),
        () -> "one count per version older than the deployed one, but was "
            + requests);
    assertEquals(
        VERSIONS - 1,
        requests.getOrDefault("newProcessDefinitionGetXmlRequest", 0),
        () -> "one model per version older than the deployed one, but was "
            + requests);

  }

  @Test
  @DisplayName("A version the search did not hold is looked up once, not once per question")
  public void aVersionDeployedLaterIsLookedUpOnce() {

    // a version another cluster node deployed after the search ran: the cluster has to be
    // asked for it, and asked once
    versions.activeInstanceCountOf(MODULE, PROCESS, "7");
    versions.tasksOfVersion(MODULE, PROCESS, "7");

    assertEquals(
        1,
        requests.getOrDefault("newProcessDefinitionSearchRequest", 0),
        () -> "the second question is answered from what the first one learned, but was "
            + requests);

  }

  @Test
  @DisplayName("Asking what the cluster already holds is one search per module, however many processes it has")
  public void askingWhatTheClusterHoldsDoesNotGrowWithTheProcesses() {

    Camunda8IdentifiersTheClusterHolds
        .askTheCluster(
            "c8",
            MODULE,
            null,
            client,
            IntStream
                .rangeClosed(1, 25)
                .mapToObj(number -> new Camunda8IdentifiersTheClusterHolds.DeployedProcess(
                    "Process"
                        + number, "Process"
                            + number, "process-"
                                + number
                                + ".bpmn", 1))
                .toList(),
            List.of());

    assertEquals(
        1,
        requests.getOrDefault("newProcessDefinitionSearchRequest", 0),
        () -> "the ids of a whole workflow module go into one filter, but was "
            + requests);

  }

  @Test
  @DisplayName("A decision id is a search of its own, because that filter takes one exact id")
  public void everyDecisionIsASearchOfItsOwn() {

    Camunda8IdentifiersTheClusterHolds
        .askTheCluster(
            "c8",
            MODULE,
            null,
            client,
            List.of(),
            IntStream
                .rangeClosed(1, 3)
                .mapToObj(number -> new Camunda8IdentifiersTheClusterHolds.DeployedDecision(
                    "decision"
                        + number, "decision"
                            + number, "Definitions_1", 1))
                .toList());

    assertEquals(
        3,
        requests.getOrDefault("newDecisionDefinitionSearchRequest", 0),
        () -> "three decisions, three searches, and nothing which grows with the cluster, but was "
            + requests);
    assertEquals(
        0,
        requests.getOrDefault("newProcessDefinitionSearchRequest", 0),
        () -> "a module deploying no process asks nothing about process ids, but was "
            + requests);

  }

  @Test
  @DisplayName("Whether two workflow modules are separated is answered without the cluster")
  public void theIsolationQuestionCostsNoRequest() {

    // the core puts this question per pair of workflow modules whose BPMN process ids meet,
    // so an application with many modules puts it many times. The answer is the tenant each
    // module would be deployed to, which is configuration: this adapter instance has no
    // connection and therefore no client at all, so a question which went to the cluster
    // would fail here instead of being counted
    final var deploymentService = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", new Camunda8AdapterConfiguration()), TestCollaborators
            .of(new Camunda8DeploymentServiceTest.NoOpInvoker()),
        (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofDays(14),
        null, TestScoping.of(NameClashAvoidance.BY_ADAPTER));

    IntStream
        .rangeClosed(1, 50)
        .forEach(number -> deploymentService
            .ownIsolationSeparatesWorkflowModules(
                "module-"
                    + number,
                "module-"
                    + (number + 1)));

    assertEquals(
        Map.of(),
        requests,
        () -> "fifty pairs and nothing for the cluster to answer, but was "
            + requests);

  }

  @Test
  @DisplayName("A count reads the total the cluster reports, not the page which came back")
  public void aCountIsTheTotalAndNotThePage() {

    versions.deployedVersionsOf(MODULE, PROCESS);

    // the mocked cluster answers with an EMPTY page and a total of seven, which is what
    // an instance search looks like when only the number is wanted
    assertEquals(
        7L,
        versions.activeInstanceCountOf(MODULE, PROCESS, "1"),
        "the number of workflows is what the cluster counted, not what it transferred");

  }

}
