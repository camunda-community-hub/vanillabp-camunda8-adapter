package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.search.enums.ProcessDefinitionState;
import io.camunda.client.api.search.filter.DecisionDefinitionFilter;
import io.camunda.client.api.search.filter.ProcessDefinitionFilter;
import io.camunda.client.api.search.filter.builder.StringProperty;
import io.camunda.client.api.search.request.DecisionDefinitionSearchRequest;
import io.camunda.client.api.search.request.ProcessDefinitionSearchRequest;
import io.camunda.client.api.search.response.DecisionDefinition;
import io.camunda.client.api.search.response.ProcessDefinition;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.SearchResponsePage;
import io.vanillabp.camunda8.processservice.Camunda8SearchPages;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.IdentifierHeldElsewhere;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter asks the cluster about the identifiers a workflow module is about to
 * deploy, and what it makes of the answer.
 * <p>
 * The cluster is played by the two searches, which is what the adapter's side of this can
 * be held against without one: whether it asks for the right thing, whether it keeps its
 * own earlier deployments out of the answer, and what it says about a holder it cannot
 * attribute. The findings are the records the core is handed, so this is the boundary
 * both sides meet at.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8IdentifiersTheClusterHoldsTest {

  private static final String MODULE = "loan-approval";

  private static final String OUR_FILE = "loan-approval.bpmn";

  private final CamundaClient client = mock(CamundaClient.class);

  /**
   * What one process definition search asked the cluster for.
   *
   * @param processDefinitionIds The ids the filter named
   * @param state The definition state the filter named
   * @param tenantId The tenant the filter named, or <code>null</code>
   */
  private record AskedAboutProcesses(
                                     List<String> processDefinitionIds,
                                     ProcessDefinitionState state,
                                     String tenantId) {
  }

  /**
   * What one decision definition search asked the cluster for.
   *
   * @param decisionDefinitionId The id the filter named
   * @param tenantId The tenant the filter named, or <code>null</code>
   */
  private record AskedAboutDecisions(
                                     String decisionDefinitionId,
                                     String tenantId) {
  }

  private final List<AskedAboutProcesses> processSearches = new ArrayList<>();

  private final List<AskedAboutDecisions> decisionSearches = new ArrayList<>();

  /**
   * How often the cluster was asked for a page of process definitions.
   */
  private int pagesAskedFor;

  /**
   * A cluster answering the process definition search with the given definitions, one entry
   * of the list per page, and a cursor on every page so the next one can be asked for.
   * <p>
   * The page lambda itself is deliberately not stubbed: WHICH type the client hands it
   * differs between the release lines this adapter is built from, and naming that type here
   * would make the test compile on one line only. The adapter passes a lambda, which compiles
   * against every line, and whether it asks for a second page is measured by the pages this
   * double is asked for.
   */
  private void theClusterHoldsProcessDefinitions(
      final List<List<ProcessDefinition>> pages) {

    final var search = mock(ProcessDefinitionSearchRequest.class, RETURNS_SELF);
    Mockito
        .lenient()
        .when(search.filter(Mockito.<Consumer<ProcessDefinitionFilter>>any()))
        .thenAnswer(invocation -> {
          processSearches.add(whatTheFilterAsksFor(invocation.getArgument(0)));
          return search;
        });
    Mockito
        .lenient()
        .when(search.send())
        .thenAnswer(invocation -> {
          final var page = Math.min(pagesAskedFor++, pages.size() - 1);
          return future(response(pages.get(page), "page-"
              + (page + 1)));
        });
    Mockito.lenient().when(client.newProcessDefinitionSearchRequest()).thenReturn(search);

  }

  private AskedAboutProcesses whatTheFilterAsksFor(
      final Consumer<ProcessDefinitionFilter> consumer) {

    final var filter = mock(ProcessDefinitionFilter.class, RETURNS_SELF);
    final var ids = new ArrayList<String>();
    final var state = new ProcessDefinitionState[1];
    final var tenantId = new String[1];
    Mockito
        .lenient()
        .when(filter.state(Mockito.any(ProcessDefinitionState.class)))
        .thenAnswer(call -> {
          state[0] = call.getArgument(0);
          return filter;
        });
    Mockito
        .lenient()
        .when(filter.tenantId(Mockito.anyString()))
        .thenAnswer(call -> {
          tenantId[0] = call.getArgument(0);
          return filter;
        });
    Mockito
        .lenient()
        .when(filter.processDefinitionId(Mockito.<Consumer<StringProperty>>any()))
        .thenAnswer(call -> {
          final Consumer<StringProperty> property = call.getArgument(0);
          final var recording = mock(StringProperty.class, RETURNS_SELF);
          Mockito
              .lenient()
              .when(recording.in(Mockito.<String>anyList()))
              .thenAnswer(in -> {
                ids.addAll(in.getArgument(0));
                return recording;
              });
          property.accept(recording);
          return filter;
        });
    consumer.accept(filter);
    return new AskedAboutProcesses(List.copyOf(ids), state[0], tenantId[0]);

  }

  /**
   * A cluster answering every decision definition search with the given definitions.
   */
  private void theClusterHoldsDecisionDefinitions(
      final List<DecisionDefinition> definitions) {

    final var search = mock(DecisionDefinitionSearchRequest.class, RETURNS_SELF);
    Mockito
        .lenient()
        .when(search.filter(Mockito.<Consumer<DecisionDefinitionFilter>>any()))
        .thenAnswer(invocation -> {
          final Consumer<DecisionDefinitionFilter> consumer = invocation.getArgument(0);
          final var filter = mock(DecisionDefinitionFilter.class, RETURNS_SELF);
          final var id = new String[1];
          final var tenantId = new String[1];
          Mockito
              .lenient()
              .when(filter.decisionDefinitionId(Mockito.anyString()))
              .thenAnswer(call -> {
                id[0] = call.getArgument(0);
                return filter;
              });
          Mockito
              .lenient()
              .when(filter.tenantId(Mockito.anyString()))
              .thenAnswer(call -> {
                tenantId[0] = call.getArgument(0);
                return filter;
              });
          consumer.accept(filter);
          decisionSearches.add(new AskedAboutDecisions(id[0], tenantId[0]));
          return search;
        });
    Mockito.lenient().when(search.send()).thenAnswer(invocation -> future(response(definitions, null)));
    Mockito.lenient().when(client.newDecisionDefinitionSearchRequest()).thenReturn(search);

  }

  private Collection<IdentifierHeldElsewhere> askAbout(
      final String tenantId,
      final List<Camunda8IdentifiersTheClusterHolds.DeployedProcess> processes,
      final List<Camunda8IdentifiersTheClusterHolds.DeployedDecision> decisions) {

    return Camunda8IdentifiersTheClusterHolds
        .askTheCluster("c8", MODULE, tenantId, client, processes, decisions);

  }

  private static Camunda8IdentifiersTheClusterHolds.DeployedProcess ourProcess(
      final String bpmnProcessId,
      final int version) {

    return new Camunda8IdentifiersTheClusterHolds.DeployedProcess(bpmnProcessId, bpmnProcessId, OUR_FILE, version);

  }

  @Test
  @DisplayName("The process ids of a whole workflow module are one search, and the deleted definitions stay out")
  public void theProcessIdsOfAModuleAreOneSearch() {

    theClusterHoldsProcessDefinitions(
        List.of(List.of(definition("LoanApproval", OUR_FILE, 2, 7000L))));

    askAbout(null, List.of(ourProcess("LoanApproval", 2), ourProcess("LoanRejection", 2)), List.of());

    assertEquals(1, processSearches.size(), () -> "one search per workflow module, but was "
        + processSearches);
    assertEquals(
        List.of("LoanApproval", "LoanRejection"),
        processSearches
            .get(0)
            .processDefinitionIds(),
        "both ids of the module are batched into the one filter");
    assertEquals(
        ProcessDefinitionState.ACTIVE,
        processSearches
            .get(0)
            .state(),
        "a definition an operator deleted is not a definition this adapter reports about");

  }

  @Test
  @DisplayName("A module living in a tenant asks inside that tenant")
  public void theTenantIsPartOfTheQuestion() {

    theClusterHoldsProcessDefinitions(List.of(List.of()));

    askAbout(MODULE, List.of(ourProcess("LoanApproval", 1)), List.of());

    assertEquals(
        MODULE,
        processSearches
            .get(0)
            .tenantId(),
        "what the cluster holds outside our tenant is not held against us");

  }

  @Test
  @DisplayName("An earlier deployment of our own file is no finding")
  public void ourOwnEarlierVersionsAreNoFinding() {

    theClusterHoldsProcessDefinitions(
        List
            .of(
                List
                    .of(
                        definition("LoanApproval", OUR_FILE, 1, 7001L),
                        definition("LoanApproval", OUR_FILE, 2, 7002L),
                        definition("LoanApproval", OUR_FILE, 3, 7003L))));

    assertEquals(
        List.of(),
        List.copyOf(askAbout(null, List.of(ourProcess("LoanApproval", 3)), List.of())),
        "a deployment of this application is what the resource name says it is, however many "
            + "versions of it the cluster keeps");

  }

  @Test
  @DisplayName("A definition from another file is reported, naming it and saying it is not proven")
  public void aDefinitionFromAnotherFileIsReported() {

    theClusterHoldsProcessDefinitions(
        List.of(List.of(definition("LoanApproval", "another-application.bpmn", 4, 7004L))));

    final var found = List.copyOf(askAbout(null, List.of(ourProcess("LoanApproval", 5)), List.of()));

    assertEquals(1, found.size(), () -> "one finding, but was "
        + found);
    final var finding = found.get(0);
    assertEquals(ScopedIdentifierKind.BPMN_PROCESS_ID, finding.kind(), "what kind of name it is");
    assertEquals(
        "LoanApproval",
        finding.plainIdentifier(),
        "the PLAIN id is handed over - the core composes the form the cluster sees");
    assertTrue(
        finding
            .heldBy()
            .contains("another-application.bpmn"),
        () -> "the file the cluster deployed it from, which is all this cluster says about a holder: "
            + finding.heldBy());
    assertTrue(finding.heldBy().contains("version 4"), () -> "the version it holds: "
        + finding.heldBy());
    assertTrue(finding.heldBy().contains("7004"), () -> "and the key an operator can look it up by: "
        + finding.heldBy());
    assertFalse(
        finding.certainlyForeign(),
        "a cluster records no owner, so this is a hint and the core's message says so");

  }

  @Test
  @DisplayName("A definition the cluster names no resource for is still reported")
  public void aDefinitionWithoutAResourceNameIsReported() {

    theClusterHoldsProcessDefinitions(List.of(List.of(definition("LoanApproval", null, 4, 7005L))));

    final var found = List.copyOf(askAbout(null, List.of(ourProcess("LoanApproval", 5)), List.of()));

    assertEquals(1, found.size(), () -> "a holder which cannot be described is still a holder: "
        + found);
    assertTrue(
        found
            .get(0)
            .heldBy()
            .contains("does not name"),
        () -> "and the message says the cluster named no resource: "
            + found);

  }

  @Test
  @DisplayName("Every decision id is a search of its own, and another DRD is the finding")
  public void everyDecisionIdIsASearchOfItsOwn() {

    theClusterHoldsDecisionDefinitions(
        List
            .of(
                decisionDefinition("Decision_AnotherApplication", 3, 8001L),
                decisionDefinition("Definitions_LoanRating", 2, 8002L)));

    final var found = List
        .copyOf(
            askAbout(
                null,
                List.of(),
                List
                    .of(
                        new Camunda8IdentifiersTheClusterHolds.DeployedDecision(
                            "creditRating", "creditRating", "Definitions_LoanRating", 2),
                        new Camunda8IdentifiersTheClusterHolds.DeployedDecision(
                            "riskClass", "riskClass", "Definitions_LoanRating", 2))));

    assertEquals(
        List.of("creditRating", "riskClass"),
        decisionSearches
            .stream()
            .map(AskedAboutDecisions::decisionDefinitionId)
            .toList(),
        "that filter takes one exact id, so each decision is asked for on its own");
    assertEquals(
        List.of(ScopedIdentifierKind.DMN_DECISION_ID, ScopedIdentifierKind.DMN_DECISION_ID),
        found
            .stream()
            .map(IdentifierHeldElsewhere::kind)
            .toList(),
        "the decision of the foreign DRD is found for both ids, and our own DRD is not a finding");
    assertTrue(
        found
            .get(0)
            .heldBy()
            .contains("Decision_AnotherApplication"),
        () -> "the decision requirements are what names the holder here: "
            + found.get(0).heldBy());

  }

  @Test
  @DisplayName("A search which fails costs the answer and nothing else")
  public void aFailedSearchEndsNothing() {

    final var search = mock(ProcessDefinitionSearchRequest.class, RETURNS_SELF);
    Mockito
        .lenient()
        .when(search.send())
        .thenThrow(new IllegalStateException("the cluster is not answering"));
    Mockito.lenient().when(client.newProcessDefinitionSearchRequest()).thenReturn(search);
    theClusterHoldsDecisionDefinitions(
        List.of(decisionDefinition("Decision_AnotherApplication", 1, 8003L)));

    final var found = assertDoesNotThrow(
        () -> List
            .copyOf(
                askAbout(
                    null,
                    List.of(ourProcess("LoanApproval", 1)),
                    List
                        .of(
                            new Camunda8IdentifiersTheClusterHolds.DeployedDecision(
                                "creditRating", "creditRating", "Definitions_LoanRating", 1)))),
        "a diagnostic must not be the reason an application does not come up");

    assertEquals(
        1,
        found.size(),
        () -> "the question which could be put is still answered, which is the decision id here: "
            + found);

  }

  @Test
  @DisplayName("A module which deploys nothing asks nothing")
  public void aModuleWithoutResourcesAsksNothing() {

    assertEquals(List.of(), List.copyOf(askAbout(null, List.of(), List.of())));
    assertEquals(List.of(), processSearches, "no ids to ask about, so no search");
    assertEquals(List.of(), decisionSearches);

  }

  @Test
  @DisplayName("A module whose definitions fill a page is asked for the next one")
  public void aFullPageIsFollowedByTheNextOne() {

    final var firstPage = IntStream
        .rangeClosed(1, Camunda8SearchPages.PAGE_SIZE)
        .mapToObj(version -> definition("LoanApproval", OUR_FILE, version, 7000L + version))
        .toList();
    theClusterHoldsProcessDefinitions(
        List.of(firstPage, List.of(definition("LoanApproval", "another-application.bpmn", 500, 7500L))));

    final var found = List.copyOf(askAbout(null, List.of(ourProcess("LoanApproval", 501)), List.of()));

    assertEquals(2, pagesAskedFor, "a page which came back full is followed by the next one");
    assertEquals(1, found.size(), () -> "and what that page holds is reported like everything else: "
        + found);

  }

  private static ProcessDefinition definition(
      final String bpmnProcessId,
      final String resourceName,
      final int version,
      final long definitionKey) {

    final var definition = mock(ProcessDefinition.class);
    Mockito.lenient().when(definition.getProcessDefinitionId()).thenReturn(bpmnProcessId);
    Mockito.lenient().when(definition.getResourceName()).thenReturn(resourceName);
    Mockito.lenient().when(definition.getVersion()).thenReturn(version);
    Mockito.lenient().when(definition.getProcessDefinitionKey()).thenReturn(definitionKey);
    return definition;

  }

  private static DecisionDefinition decisionDefinition(
      final String decisionRequirementsId,
      final int version,
      final long decisionKey) {

    final var definition = mock(DecisionDefinition.class);
    Mockito.lenient().when(definition.getDmnDecisionRequirementsId()).thenReturn(decisionRequirementsId);
    Mockito.lenient().when(definition.getVersion()).thenReturn(version);
    Mockito.lenient().when(definition.getDecisionKey()).thenReturn(decisionKey);
    return definition;

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
