package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * Which start events the cluster fires on its own in a version it still HOLDS - the
 * answer the core judges the <code>&#64;WorkflowStartedByBpms</code> methods of a
 * declared-only BPMN process id by, the id a renamed process left behind.
 * <p>
 * Nothing wires such an id while an application boots, so those methods used to be judged
 * by nothing while the cluster kept firing the old model's timer every day. What the
 * judgement needs is read out of the models the cluster holds
 * ({@link Camunda8ModelsTheClusterHolds}), which is where every question about a model
 * this application did not deploy is answered from.
 * <p>
 * The cluster is played by a definition search and an XML request, which is what the
 * adapter's side of this can be held against without a cluster: whether it asks for the
 * right thing and reports what the model says.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8StartEventsOfHeldVersionsTest {

  private static final String MODULE = "order-approval";

  private static final String OLD_ID = "order_approval";

  private final CamundaClient client = mock(CamundaClient.class);

  /**
   * A model starting on a daily timer and on a signal, next to the plain start event.
   */
  private static final String STARTS_THE_CLUSTER_FIRES = """
          <bpmn:startEvent id="DailyTimer">
            <bpmn:timerEventDefinition id="Timer_1">
              <bpmn:timeCycle xsi:type="bpmn:tFormalExpression">R/P1D</bpmn:timeCycle>
            </bpmn:timerEventDefinition>
          </bpmn:startEvent>
          <bpmn:startEvent id="ApprovalRequested">
            <bpmn:signalEventDefinition id="Signal_Definition" signalRef="Signal_1" />
          </bpmn:startEvent>
          <bpmn:startEvent id="StartedByTheApplication" />
      """;

  /**
   * A model with nothing but a plain start event.
   */
  private static final String NO_START_THE_CLUSTER_FIRES = """
          <bpmn:startEvent id="StartedByTheApplication" />
      """;

  private static String xmlOf(
      final String processContent) {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:signal id="Signal_1" name="approval-requested" />
          <bpmn:process id="%s" isExecutable="true">
        %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(OLD_ID, processContent);

  }

  @Test
  @DisplayName("Every start event of a held version is read from the model the cluster runs")
  public void theStartEventsOfAHeldVersionAreRead() {

    final var catalog = aClusterHolding(
        Map.of(
            1, xmlOf(NO_START_THE_CLUSTER_FIRES),
            2, xmlOf(STARTS_THE_CLUSTER_FIRES)));

    assertEquals(
        List
            .of(
                BpmsInitiatedStartSpec.of("DailyTimer", BpmsStartTrigger.Kind.TIMER),
                new BpmsInitiatedStartSpec(
                    "ApprovalRequested", BpmsStartTrigger.Kind.SIGNAL, "approval-requested"),
                BpmsInitiatedStartSpec.of("StartedByTheApplication", BpmsStartTrigger.Kind.NONE)),
        List.copyOf(catalog.startEventsOfVersion(MODULE, OLD_ID, "2")),
        "every start event of the old model, the signal by its plain name");

  }

  @Test
  @DisplayName("A held version with one plain start event answers exactly that one")
  public void aVersionWithoutSuchAStartEventAnswersEmpty() {

    final var catalog = aClusterHolding(Map.of(1, xmlOf(NO_START_THE_CLUSTER_FIRES)));

    assertEquals(
        List.of(BpmsInitiatedStartSpec.of("StartedByTheApplication", BpmsStartTrigger.Kind.NONE)),
        List.copyOf(catalog.startEventsOfVersion(MODULE, OLD_ID, "1")),
        "the model was read and says which start events it has, which is not the same as an "
            + "adapter which cannot say");

  }

  @Test
  @DisplayName("A version the cluster no longer holds is one this adapter cannot say anything about")
  public void aVersionTheClusterDoesNotHoldIsNotAnswered() {

    final var catalog = aClusterHolding(Map.of(1, xmlOf(STARTS_THE_CLUSTER_FIRES)));

    assertNull(
        catalog.startEventsOfVersion(MODULE, OLD_ID, "2"),
        "no model was read, so nothing is claimed about what that version starts on");

  }

  @Test
  @DisplayName("Reading a held model leaves it as the cluster runs it")
  public void readingAHeldModelAddsNothingToIt() {

    final var model = Bpmn
        .readModelFromStream(
            new ByteArrayInputStream(
                xmlOf(STARTS_THE_CLUSTER_FIRES).getBytes(StandardCharsets.UTF_8)));

    final var read = Camunda8TaskWiring.bpmsInitiatedStartsOfHeldModel(model, OLD_ID, signalName -> signalName);

    assertEquals(3, read.size(), "every start event of the model is reported");
    assertFalse(
        Bpmn.convertToString(model).contains("executionListeners"),
        "a model the cluster already runs carries the listener of the deployment which brought "
            + "it, and a read on its behalf adds none");
    Camunda8TaskWiring.bpmsInitiatedStartsOf(model, OLD_ID, signalName -> signalName);
    assertTrue(
        Bpmn.convertToString(model).contains("executionListeners"),
        "while the walk which prepares a deployment does add it");

  }

  /**
   * A cluster holding the given versions of the old id, each with the model it runs.
   *
   * @param modelsPerVersion The XML the cluster answers per version
   * @return The catalog the core would ask about that id
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
            .map(Camunda8StartEventsOfHeldVersionsTest::definition)
            .toList())));
    Mockito.lenient().when(client.newProcessDefinitionSearchRequest()).thenReturn(search);
    Mockito
        .lenient()
        .when(client.newProcessDefinitionGetXmlRequest(Mockito.anyLong()))
        .thenAnswer(invocation -> {
          final var xml = mock(ProcessDefinitionGetXmlRequest.class, RETURNS_SELF);
          final var version = Integer.valueOf(((Long) invocation.getArgument(0)).intValue() - 1000);
          Mockito
              .lenient()
              .when(xml.send())
              .thenAnswer(request -> future(modelsPerVersion.get(version)));
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
    return deploymentService.processVersionCatalogOf(MODULE, OLD_ID);

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
