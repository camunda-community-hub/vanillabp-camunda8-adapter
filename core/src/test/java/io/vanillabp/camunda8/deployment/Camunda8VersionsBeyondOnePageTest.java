package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.search.request.ProcessDefinitionSearchRequest;
import io.camunda.client.api.search.response.ProcessDefinition;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.SearchResponsePage;
import io.vanillabp.camunda8.processservice.Camunda8SearchPages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter answers about a process the cluster holds MORE than one page of
 * versions of - a process redeployed with every release of an application.
 * <p>
 * The versions are what the core matches a version tag and the startup check about older
 * versions against, so the answer has to be every version the cluster holds. A search
 * which says nothing about its page is answered with 100 entries, oldest first, which left
 * the application reading the 100 versions nobody runs any more and never seeing the one
 * it had just deployed.
 * <p>
 * The cluster is played by the definition search, handing out one page per call. Which
 * page the adapter asked for cannot be read off the call: the type the client passes the
 * page lambda differs between the release lines this adapter is built from, so naming it
 * here would make the test compile on one line only. What is measured instead is how many
 * pages the adapter asked for and what it made of them.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8VersionsBeyondOnePageTest {

  private static final String MODULE = "order-approval";

  private static final String PROCESS = "OrderApproval";

  /**
   * How many versions the cluster holds. The number a run of this adapter's own test suite
   * reached on 2026-10-01, which is where the ceiling of a page was found.
   */
  private static final int VERSIONS_HELD = 254;

  private final CamundaClient client = mock(CamundaClient.class);

  /**
   * The pages the cluster was asked for, each one as the versions it answered with.
   */
  private final List<List<Integer>> pagesAnswered = new ArrayList<>();

  @Test
  @DisplayName("Every version is read, the one deployed last included, and the answer stays oldest first")
  public void everyVersionIsRead() {

    final var versions = aClusterHolding(VERSIONS_HELD);

    final var held = versions
        .versionsHeldUnder(MODULE, PROCESS);

    assertEquals(VERSIONS_HELD, held.size(), () -> "every version the cluster holds, but got "
        + held.size());
    assertTrue(
        held.contains(String.valueOf(VERSIONS_HELD)),
        "the version deployed last is the one the startup check is about, and it is the one a "
            + "single page leaves out");
    assertEquals("1", held.getFirst(), "the catalog is answered oldest first");
    assertEquals(String.valueOf(VERSIONS_HELD), held.getLast(), "and newest last");
    assertEquals(
        3,
        pagesAnswered.size(),
        () -> "254 versions are three pages of 100, and the adapter asked for all of them, but asked for "
            + pagesAnswered.size());
    assertEquals(
        List.of(Integer.valueOf(VERSIONS_HELD)),
        pagesAnswered
            .get(0)
            .subList(0, 1),
        "the search runs newest first, so the bound of the paging can only cut versions nobody asks "
            + "about any more");

  }

  @Test
  @DisplayName("A version tag is found on a version past the first page")
  public void aTagOfALateVersionIsFound() {

    final var versions = aClusterHolding(VERSIONS_HELD);

    final var found = versions.resolveVersion(MODULE, PROCESS, tagOf(VERSIONS_HELD));

    assertEquals(
        String.valueOf(VERSIONS_HELD),
        found == null
            ? null
            : found.version(),
        "the tag of the version just deployed is what an application names in @WorkflowTask, and it "
            + "lives on no page a single search hands out");

  }

  private static String tagOf(
      final int version) {

    return "release-"
        + version;

  }

  /**
   * A cluster holding the given number of versions of one process, answering a search with
   * one page of {@link Camunda8SearchPages#PAGE_SIZE} versions at a time, newest first -
   * which is the order the adapter asks for.
   */
  private Camunda8ProcessVersions aClusterHolding(
      final int versionsHeld) {

    final var newestFirst = IntStream
        .rangeClosed(1, versionsHeld)
        .boxed()
        .sorted(java.util.Comparator.reverseOrder())
        .toList();
    final var search = mock(ProcessDefinitionSearchRequest.class, RETURNS_SELF);
    Mockito.lenient().when(search.send()).thenAnswer(invocation -> {
      final var from = pagesAnswered.size() * Camunda8SearchPages.PAGE_SIZE;
      final var page = newestFirst
          .subList(
              Math.min(from, newestFirst.size()),
              Math.min(from + Camunda8SearchPages.PAGE_SIZE, newestFirst.size()));
      pagesAnswered.add(page);
      return future(
          response(
              page
                  .stream()
                  .map(Camunda8VersionsBeyondOnePageTest::definition)
                  .toList(),
              "after-version-"
                  + page.getLast()));
    });
    when(client.newProcessDefinitionSearchRequest()).thenReturn(search);

    return new Camunda8ProcessVersions("c8", () -> client, (
        workflowModuleId,
        bpmnProcessId) -> bpmnProcessId, workflowModuleId -> null);

  }

  private static ProcessDefinition definition(
      final Integer version) {

    final var definition = mock(ProcessDefinition.class);
    Mockito
        .lenient()
        .when(definition.getProcessDefinitionKey())
        .thenReturn(Long.valueOf(1000 + version.intValue()));
    Mockito.lenient().when(definition.getVersion()).thenReturn(version);
    Mockito.lenient().when(definition.getVersionTag()).thenReturn(tagOf(version.intValue()));
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
