package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.search.enums.ProcessDefinitionState;
import io.camunda.client.api.search.filter.ProcessDefinitionFilter;
import io.camunda.client.api.search.request.ProcessDefinitionSearchRequest;
import io.camunda.client.api.search.response.ProcessDefinition;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.SearchResponsePage;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A process definition an operator deleted is not a version this adapter reports.
 * <p>
 * Camunda 8 keeps a deleted definition in its query API and marks it <code>DELETED</code>
 * instead of dropping it, so a search which names no state answers with it like with any
 * other. What the startup check made of that was a report about tasks nobody serves any
 * more, at every start, and deleting the definition - the remedy the report itself
 * suggests - did not end it.
 * <p>
 * The cluster is played by a search which honours the state and the version its filter
 * was given, which is what the adapter's side of this can be held against without a
 * cluster: whether it asks for the right thing.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8DeletedProcessVersionsTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private final CamundaClient client = mock(CamundaClient.class);

  /**
   * One version of the process as the cluster holds it.
   *
   * @param version The version number
   * @param state Whether the definition was deleted
   */
  private record HeldVersion(
                             int version,
                             ProcessDefinitionState state) {
  }

  /**
   * What the filter of a definition search was told to look for - the cluster's side of
   * the two restrictions this test is about.
   */
  private static final class WhatWasAskedFor {

    private ProcessDefinitionState state;

    private Integer version;

    private boolean holds(
        final HeldVersion held) {

      return ((state == null) || (state == held.state())) && ((version == null) || (version.intValue() == held
          .version()));

    }

  }

  /**
   * A cluster holding the given versions, answering a definition search with those of
   * them the filter asked for.
   *
   * @param held What the cluster holds for the process
   * @return The catalog under test, reading from that cluster
   */
  private Camunda8ProcessVersions aClusterHolding(
      final HeldVersion... held) {

    final var asked = new WhatWasAskedFor[]{
        new WhatWasAskedFor()
    };
    final var search = mock(ProcessDefinitionSearchRequest.class, RETURNS_SELF);
    Mockito
        .lenient()
        .when(search.filter(Mockito.<Consumer<ProcessDefinitionFilter>>any()))
        .thenAnswer(invocation -> {
          asked[0] = new WhatWasAskedFor();
          final Consumer<ProcessDefinitionFilter> filter = invocation.getArgument(0);
          filter.accept(recordingFilter(asked[0]));
          return search;
        });
    Mockito
        .lenient()
        .when(search.send())
        .thenAnswer(invocation -> future(response(List
            .of(held)
            .stream()
            .filter(asked[0]::holds)
            .map(Camunda8DeletedProcessVersionsTest::definition)
            .toList())));
    when(client.newProcessDefinitionSearchRequest()).thenReturn(search);

    final var versions = new Camunda8ProcessVersions(
        "c8", () -> client, (
            workflowModuleId,
            bpmnProcessId) -> bpmnProcessId, workflowModuleId -> null);
    // a model this adapter could read would answer with tasks, so an empty answer below
    // is the missing definition and not a catalog which reads no models at all
    versions.setTasksOfModel((
        workflowModuleId,
        bpmnProcessId,
        version,
        model) -> List.of());
    return versions;

  }

  private static ProcessDefinitionFilter recordingFilter(
      final WhatWasAskedFor asked) {

    final var filter = mock(ProcessDefinitionFilter.class, RETURNS_SELF);
    Mockito
        .lenient()
        .when(filter.state(Mockito.any(ProcessDefinitionState.class)))
        .thenAnswer(invocation -> {
          asked.state = invocation.getArgument(0);
          return filter;
        });
    Mockito
        .lenient()
        .when(filter.version(Mockito.anyInt()))
        .thenAnswer(invocation -> {
          asked.version = invocation.getArgument(0);
          return filter;
        });
    return filter;

  }

  private static ProcessDefinition definition(
      final HeldVersion held) {

    final var definition = mock(ProcessDefinition.class);
    Mockito
        .lenient()
        .when(definition.getProcessDefinitionKey())
        .thenReturn(Long.valueOf(1000 + held.version()));
    Mockito
        .lenient()
        .when(definition.getVersion())
        .thenReturn(held.version());
    return definition;

  }

  private static <T> SearchResponse<T> response(
      final List<T> items) {

    @SuppressWarnings("unchecked")
    final SearchResponse<T> response = mock(SearchResponse.class);
    final var page = mock(SearchResponsePage.class);
    Mockito
        .lenient()
        .when(page.totalItems())
        .thenReturn(Long.valueOf(items.size()));
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

  @Test
  @DisplayName("A deleted version is no version the cluster is reported to hold")
  public void aDeletedVersionIsNotReportedAsDeployed() {

    final var versions = aClusterHolding(
        new HeldVersion(1, ProcessDefinitionState.DELETED),
        new HeldVersion(2, ProcessDefinitionState.ACTIVE));

    final var reported = versions
        .deployedVersionsOf(MODULE, PROCESS)
        .stream()
        .map(DeployedProcessVersion::version)
        .toList();

    assertEquals(
        List.of("2"),
        reported,
        "the version the operator deleted is not one this application has to serve");

  }

  @Test
  @DisplayName("A deleted version has no definition key, so nothing is read for it")
  public void aDeletedVersionIsNotFoundBySearchingForIt() {

    // the version was deployed after the list of versions was read, so the cluster is
    // asked for this one - which is the search a deleted definition used to answer
    final var versions = aClusterHolding(new HeldVersion(3, ProcessDefinitionState.DELETED));

    assertNull(
        versions.activeInstanceCountOf(MODULE, PROCESS, "3"),
        "the workflows of a deleted version are not counted");
    assertNull(
        versions.tasksOfVersion(MODULE, PROCESS, "3"),
        "and its model is not read either");

  }

}
