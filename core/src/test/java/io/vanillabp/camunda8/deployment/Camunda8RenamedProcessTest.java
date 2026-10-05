package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.search.filter.ProcessDefinitionFilter;
import io.camunda.client.api.search.request.ProcessDefinitionSearchRequest;
import io.camunda.client.api.search.response.ProcessDefinition;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.SearchResponsePage;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter answers about a BPMN process the application declares without deploying a
 * model under it - the old id of a renamed process, which the cluster keeps with every version
 * ever deployed under it and with the workflows still running on them.
 * <p>
 * The core asks once per such id after the workflow module was deployed, because only it knows
 * which ids an application declared. The adapter's whole part in it is to answer with the
 * catalog it would have registered while wiring, and to ask the cluster for the OLD id the way
 * it would have asked for any other: by the id the cluster knows, which carries the workflow
 * module as a prefix under <code>use-prefix</code>, and within the tenant the module is
 * deployed to.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8RenamedProcessTest {

  private static final String MODULE = "order-approval";

  private static final String OLD_ID = "order_approval";

  private final CamundaClient client = mock(CamundaClient.class);

  /**
   * What the filter of a definition search was told to look for.
   */
  private static final class WhatWasAskedFor {

    private String processDefinitionId;

    private String tenantId;

  }

  private final WhatWasAskedFor asked = new WhatWasAskedFor();

  @Test
  @DisplayName("The adapter answers with the catalog of its cluster")
  public void theAdapterAnswersWithItsCatalog() {

    final var deploymentService = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", new Camunda8AdapterConfiguration()), TestCollaborators
            .of(new Camunda8DeploymentServiceTest.NoOpInvoker()),
        (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration.ofDays(14));

    assertNotNull(
        deploymentService.processVersionCatalogOf(MODULE, OLD_ID),
        "the versions of a declared id are what this cluster can be asked about");

  }

  @Test
  @DisplayName("The old id is searched for as the cluster knows it, prefix and tenant included")
  public void theOldIdIsSearchedForScoped() {

    final var versions = aClusterHolding(1, 2, 3);

    final var held = versions
        .deployedVersionsOf(MODULE, OLD_ID)
        .stream()
        .map(DeployedProcessVersion::version)
        .toList();

    assertEquals(List.of("1", "2", "3"), held, "every version the cluster still holds under the old id");
    assertEquals(
        "%s-%s".formatted(MODULE, OLD_ID),
        asked.processDefinitionId,
        "the id a prefixed workflow module deployed its processes under");
    assertEquals("tenant-of-the-module", asked.tenantId, "and the tenant that module is deployed to");

  }

  /**
   * A cluster holding the given versions of the old id, scoped like a workflow module using
   * <code>use-prefix</code> in a tenant of its own.
   */
  private Camunda8ProcessVersions aClusterHolding(
      final int... heldVersions) {

    final var search = mock(ProcessDefinitionSearchRequest.class, RETURNS_SELF);
    Mockito
        .lenient()
        .when(search.filter(Mockito.<Consumer<ProcessDefinitionFilter>>any()))
        .thenAnswer(invocation -> {
          final Consumer<ProcessDefinitionFilter> filter = invocation.getArgument(0);
          filter.accept(recordingFilter());
          return search;
        });
    Mockito
        .lenient()
        .when(search.send())
        .thenAnswer(invocation -> future(response(java.util.Arrays
            .stream(heldVersions)
            .boxed()
            // the adapter asks for the newest version first, so this cluster answers that
            // way round - what the catalog hands on is turned back into oldest first
            .sorted(java.util.Comparator.reverseOrder())
            .map(Camunda8RenamedProcessTest::definition)
            .toList())));
    when(client.newProcessDefinitionSearchRequest()).thenReturn(search);

    return new Camunda8ProcessVersions(
        "c8", () -> client, (
            workflowModuleId,
            bpmnProcessId) -> "%s-%s".formatted(workflowModuleId,
                bpmnProcessId), workflowModuleId -> "tenant-of-the-module");

  }

  private ProcessDefinitionFilter recordingFilter() {

    final var filter = mock(ProcessDefinitionFilter.class, RETURNS_SELF);
    Mockito
        .lenient()
        .when(filter.processDefinitionId(Mockito.anyString()))
        .thenAnswer(invocation -> {
          asked.processDefinitionId = invocation.getArgument(0);
          return filter;
        });
    Mockito
        .lenient()
        .when(filter.tenantId(Mockito.anyString()))
        .thenAnswer(invocation -> {
          asked.tenantId = invocation.getArgument(0);
          return filter;
        });
    return filter;

  }

  private static ProcessDefinition definition(
      final Integer version) {

    final var definition = mock(ProcessDefinition.class);
    Mockito
        .lenient()
        .when(definition.getProcessDefinitionKey())
        .thenReturn(Long.valueOf(1000 + version.intValue()));
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
