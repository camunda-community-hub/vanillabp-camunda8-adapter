package io.vanillabp.camunda8.deployment;

import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
import io.vanillabp.camunda8.TestScoping;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8AllowListenersResolver;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog;

/**
 * A cluster holding the given versions of a process, each with the model it runs, played by
 * a definition search and an XML request. Every question about a held version goes through
 * the version catalog this hands out, which is the boundary to the migration SPI.
 * <p>
 * The adapter prefixes its identifiers, which is the mode a held model's names have to be
 * stripped under. How often the XML was fetched is part of what the tests measure, so the
 * requests are counted.
 */
final class AClusterHolding {

  /**
   * Which definition keys the XML was asked for, which is one request per fetch.
   */
  private final List<Long> xmlRequests = new ArrayList<>();

  private final CamundaClient client = mock(CamundaClient.class);

  private AClusterHolding() {
  }

  /**
   * @param modelsPerVersion The model of each version the cluster holds, by version number
   * @return The cluster, ready to be asked
   */
  static AClusterHolding theseModels(
      final Map<Integer, String> modelsPerVersion) {

    final var cluster = new AClusterHolding();
    cluster.play(modelsPerVersion);
    return cluster;

  }

  /**
   * @return How often the XML of a version was fetched, by definition key
   */
  List<Long> xmlRequests() {

    return xmlRequests;

  }

  /**
   * Teaches the mocked client which versions this cluster holds and what their models are.
   */
  private void play(
      final Map<Integer, String> modelsPerVersion) {

    final var search = mock(ProcessDefinitionSearchRequest.class, RETURNS_SELF);
    // which version the filter named, 0 for a search asking for every version this cluster
    // holds - a search for a version it does not hold answers nothing, which is what makes
    // the question about a version nobody holds any more answerable at all
    final var askedFor = new int[]{
        0
    };
    Mockito
        .lenient()
        .when(search.filter(Mockito.<Consumer<ProcessDefinitionFilter>>any()))
        .thenAnswer(invocation -> {
          askedFor[0] = 0;
          final Consumer<ProcessDefinitionFilter> filter = invocation.getArgument(0);
          final var recording = mock(ProcessDefinitionFilter.class, RETURNS_SELF);
          Mockito.lenient().when(recording.version(Mockito.anyInt())).thenAnswer(call -> {
            askedFor[0] = call.getArgument(0);
            return recording;
          });
          filter.accept(recording);
          return search;
        });
    Mockito
        .lenient()
        .when(search.send())
        .thenAnswer(invocation -> future(response(modelsPerVersion
            .keySet()
            .stream()
            // the version list is asked for newest first, which is the order the catalog
            // reverses on its way out
            .sorted(Comparator.reverseOrder())
            .filter(version -> (askedFor[0] == 0) || (askedFor[0] == version.intValue()))
            .map(AClusterHolding::definition)
            .toList())));
    Mockito.lenient().when(client.newProcessDefinitionSearchRequest()).thenReturn(search);
    Mockito
        .lenient()
        .when(client.newProcessDefinitionGetXmlRequest(Mockito.anyLong()))
        .thenAnswer(invocation -> {
          final var definitionKey = (Long) invocation.getArgument(0);
          xmlRequests.add(definitionKey);
          final var xml = mock(ProcessDefinitionGetXmlRequest.class, RETURNS_SELF);
          Mockito
              .lenient()
              .when(xml.send())
              .thenAnswer(request -> future(modelsPerVersion.get(Integer.valueOf(definitionKey.intValue() - 1000))));
          return xml;
        });

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing contacts: every request of this test meets the mock above
    configuration.setRestAddress("http://localhost:1");
    final var clientFactory = new Camunda8ClientFactory("c8", configuration) {

      @Override
      public CamundaClient getClient() {
        return client;
      }

    };
    final var scoping = TestScoping.of(NameClashAvoidance.USE_PREFIX);
    final var deploymentService = DeploymentServiceUnderTest.of(
        "c8", clientFactory, TestCollaborators.of(new Camunda8DeploymentServiceTest.NoOpInvoker(), scoping), (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofDays(14),
        adapterId -> configuration, scoping);
    this.deploymentService = deploymentService;

  }

  private static ProcessDefinition definition(
      final Integer version) {

    final var definition = mock(ProcessDefinition.class);
    Mockito.lenient().when(definition.getProcessDefinitionKey()).thenReturn(Long.valueOf(1000 + version.intValue()));
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

  /**
   * The deployment service the catalogs come from.
   */
  private Camunda8DeploymentService deploymentService;

  /**
   * Serves the listeners the held models carry, which is the key an application switched on
   * before it deployed those versions. Without it a listener of a held model is nothing this
   * application ever had a method for, and the catalog leaves it out.
   *
   * @return This cluster
   */
  AClusterHolding servingModelledListeners() {

    deploymentService
        .setAllowListenersResolver((
            workflowModuleId,
            bpmnProcessId) -> new Camunda8AllowListenersResolver.Setting(
                true, "vanillabp.adapters.c8.allow-listeners"));
    return this;

  }

  /**
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The catalog the core asks about the versions this cluster holds
   */
  ProcessVersionCatalog catalogOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return deploymentService.processVersionCatalogOf(workflowModuleId, bpmnProcessId);

  }

}
