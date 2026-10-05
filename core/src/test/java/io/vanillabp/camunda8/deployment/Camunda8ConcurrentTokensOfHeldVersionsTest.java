package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.fetch.ProcessDefinitionGetXmlRequest;
import io.camunda.client.api.search.filter.ProcessDefinitionFilter;
import io.camunda.client.api.search.request.ProcessDefinitionSearchRequest;
import io.camunda.client.api.search.response.ProcessDefinition;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.SearchResponsePage;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which elements of a version the cluster still HOLDS can put a second token into a
 * running workflow - two branches writing one workflow aggregate, which loses an update
 * where the aggregate carries no version attribute.
 * <p>
 * The versions which run longest are the ones a walk over this boot's model never reaches:
 * a parallel gateway the newest model dropped keeps forking every workflow started before
 * it. Reading the old model is this adapter's part, deciding what it means is the core's,
 * which is the same split the model of the current deployment goes through.
 * <p>
 * The cluster is played by a definition search and an XML request, the same way
 * {@link Camunda8StartEventsOfHeldVersionsTest} plays it for the other question about a
 * held version.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ConcurrentTokensOfHeldVersionsTest {

  private static final String MODULE = "order-approval";

  private static final String PROCESS = "OrderApproval";

  private final CamundaClient client = mock(CamundaClient.class);

  /**
   * The model of the version whose gateway forks, with the non-interrupting boundary
   * event of a second branch next to it.
   */
  private static final String FORKS = """
          <bpmn:parallelGateway id="Gateway_Fork">
            <bpmn:outgoing>Flow_1</bpmn:outgoing>
            <bpmn:outgoing>Flow_2</bpmn:outgoing>
          </bpmn:parallelGateway>
          <bpmn:serviceTask id="Activity_Approve">
            <bpmn:incoming>Flow_1</bpmn:incoming>
          </bpmn:serviceTask>
          <bpmn:serviceTask id="Activity_Notify">
            <bpmn:incoming>Flow_2</bpmn:incoming>
          </bpmn:serviceTask>
          <bpmn:boundaryEvent id="Event_Reminder" cancelActivity="false" attachedToRef="Activity_Approve">
            <bpmn:timerEventDefinition id="Timer_1" />
          </bpmn:boundaryEvent>
          <bpmn:sequenceFlow id="Flow_1" sourceRef="Gateway_Fork" targetRef="Activity_Approve" />
          <bpmn:sequenceFlow id="Flow_2" sourceRef="Gateway_Fork" targetRef="Activity_Notify" />
      """;

  /**
   * The model which replaced it, one token from start to end.
   */
  private static final String RUNS_SEQUENTIALLY = """
          <bpmn:serviceTask id="Activity_Approve" />
      """;

  private static String xmlOf(
      final String processContent) {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
        %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, processContent);

  }

  @Test
  @DisplayName("A version the newest model dropped the gateway from still reports its elements")
  public void theElementsOfAnOlderVersionAreRead() {

    final var catalog = aClusterHolding(
        Map.of(1, xmlOf(FORKS), 2, xmlOf(RUNS_SEQUENTIALLY)));

    assertEquals(
        List.of("Event_Reminder", "Gateway_Fork"),
        List.copyOf(catalog.concurrentTokenElementsOfVersion(MODULE, PROCESS, "1")),
        "the workflows still running on version 1 fork exactly as they did when it was deployed");
    assertEquals(
        List.of(),
        List.copyOf(catalog.concurrentTokenElementsOfVersion(MODULE, PROCESS, "2")),
        "while the model which replaced it runs on one token, which is an answer and not a "
            + "missing one");

  }

  @Test
  @DisplayName("A version the cluster no longer holds is one this adapter cannot say anything about")
  public void aVersionTheClusterDoesNotHoldIsNotAnswered() {

    final var catalog = aClusterHolding(Map.of(1, xmlOf(FORKS)));

    assertNull(
        catalog.concurrentTokenElementsOfVersion(MODULE, PROCESS, "2"),
        "no model was read, so nothing is claimed about what its workflows fork into");

  }

  /**
   * A cluster holding the given versions of the process, each with the model it runs.
   *
   * @param modelsPerVersion The XML the cluster answers per version
   * @return The catalog the core asks about a version workflows still run on
   */
  private ProcessVersionCatalog aClusterHolding(
      final Map<Integer, String> modelsPerVersion) {

    final var search = mock(ProcessDefinitionSearchRequest.class, RETURNS_SELF);
    Mockito
        .lenient()
        .when(search.filter(Mockito.<Consumer<ProcessDefinitionFilter>>any()))
        .thenAnswer(invocation -> {
          final Consumer<ProcessDefinitionFilter> filter = invocation.getArgument(0);
          filter.accept(mock(ProcessDefinitionFilter.class, RETURNS_SELF));
          return search;
        });
    Mockito
        .lenient()
        .when(search.send())
        .thenAnswer(invocation -> future(response(modelsPerVersion
            .keySet()
            .stream()
            .sorted()
            .map(Camunda8ConcurrentTokensOfHeldVersionsTest::definition)
            .toList())));
    Mockito.lenient().when(client.newProcessDefinitionSearchRequest()).thenReturn(search);
    Mockito
        .lenient()
        .when(client.newProcessDefinitionGetXmlRequest(Mockito.anyLong()))
        .thenAnswer(invocation -> {
          final var xml = mock(ProcessDefinitionGetXmlRequest.class, RETURNS_SELF);
          final var version = Integer.valueOf(((Long) invocation.getArgument(0)).intValue() - 1000);
          Mockito.lenient().when(xml.send()).thenAnswer(request -> future(modelsPerVersion.get(version)));
          return xml;
        });

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing ever contacts - every request of this test meets the mock above
    configuration.setRestAddress("http://localhost:1");
    final var clientFactory = new Camunda8ClientFactory("c8", configuration) {

      @Override
      public CamundaClient getClient() {
        return client;
      }

    };
    final var deploymentService = DeploymentServiceUnderTest.of(
        "c8", clientFactory, TestCollaborators
            .of(new Camunda8DeploymentServiceTest.NoOpInvoker()),
        (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration.ofDays(14));
    return deploymentService.processVersionCatalogOf(MODULE, PROCESS);

  }

  private static ProcessDefinition definition(
      final int version) {

    final var definition = mock(ProcessDefinition.class);
    Mockito
        .lenient()
        .when(definition.getProcessDefinitionKey())
        .thenReturn(Long.valueOf(1000 + version));
    Mockito.lenient().when(definition.getVersion()).thenReturn(version);
    return definition;

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
