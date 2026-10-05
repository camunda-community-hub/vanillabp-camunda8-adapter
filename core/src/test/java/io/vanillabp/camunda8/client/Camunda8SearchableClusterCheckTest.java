package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.ProblemDetail;
import io.camunda.client.api.command.ProblemException;
import io.camunda.client.api.search.request.ProcessInstanceSearchRequest;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.SearchResponse;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the deployment does with a cluster which refuses to be searched: it ends there, and
 * the message is what a developer can act on without opening the wiki.
 * <p>
 * The refusal of {@link Camunda8ClusterWait} is the same shape one step earlier - a
 * cluster which answers nothing at all - so both live next to each other. This one is
 * about the cluster which answers everything except a search.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8SearchableClusterCheckTest {

  /**
   * How many searches reached the cluster, so a check asked twice can be told from a
   * cluster asked twice.
   */
  private final AtomicInteger searches = new AtomicInteger();

  /**
   * @param failure What the cluster's only search throws, or <code>null</code> where it
   *          answers
   */
  private Camunda8QueryApi queryApiOfAClusterWhoseSearchFailsWith(
      final RuntimeException failure) {

    final var client = mock(CamundaClient.class);
    final var search = mock(ProcessInstanceSearchRequest.class, RETURNS_SELF);
    when(client.newProcessInstanceSearchRequest()).thenReturn(search);
    @SuppressWarnings("unchecked")
    final SearchResponse<ProcessInstance> nothing = mock(SearchResponse.class);
    @SuppressWarnings("unchecked")
    final CamundaFuture<SearchResponse<ProcessInstance>> answer = mock(CamundaFuture.class);
    when(answer.join()).thenReturn(nothing);
    when(search.send()).thenAnswer(invocation -> {
      searches.incrementAndGet();
      if (failure != null) {
        throw failure;
      }
      return answer;
    });
    return new Camunda8QueryApi("c8", () -> client);

  }

  private Camunda8QueryApi queryApiOfAClusterWhichAnswers() {

    return queryApiOfAClusterWhoseSearchFailsWith(null);

  }

  /**
   * How a cluster refuses a query-API request: HTTP 403, naming the reason in prose and
   * never in a code.
   */
  private static ProblemException refusal() {

    final var details = new ProblemDetail();
    details.setStatus(403);
    details.setTitle("FORBIDDEN");
    return new ProblemException(403, "Forbidden", details);

  }

  @Test
  @DisplayName("A cluster which answers searches lets the deployment continue")
  public void aSearchableClusterIsWhatTheAdapterWants() {

    final var queryApi = queryApiOfAClusterWhichAnswers();

    assertDoesNotThrow(
        () -> Camunda8SearchableClusterCheck.requireAClusterWhichCanBeSearched("c8", queryApi));

  }

  @Test
  @DisplayName("A cluster refusing to be searched ends the deployment, naming both reasons and both ways out")
  public void aRefusedSearchEndsTheDeployment() {

    final var queryApi = queryApiOfAClusterWhoseSearchFailsWith(refusal());

    final var e = assertThrows(
        IllegalStateException.class,
        () -> Camunda8SearchableClusterCheck.requireAClusterWhichCanBeSearched("c8", queryApi));

    final var message = e.getMessage();
    assertTrue(message.contains("'c8'"), "the adapter id whose cluster it is: "
        + message);
    assertTrue(message.contains("SEARCHED"), message);
    assertTrue(
        message.contains("camunda.data.secondary-storage.type"),
        "the property which gives the cluster its secondary storage: "
            + message);
    assertTrue(
        message.contains("credentials"),
        "and the second reason, because a 403 does not say which of the two it was: "
            + message);
    assertTrue(
        message.contains("permission to read"),
        "what to do about that second reason: "
            + message);
    assertTrue(
        message.contains("vanillabp.adapters.c8.deployment-failure"),
        "and the way an old BPMS of a migration boots degraded anyway: "
            + message);

  }

  @Test
  @DisplayName("The message says what the adapter cannot do without a search")
  public void theMessageSaysWhatIsLost() {

    final var queryApi = queryApiOfAClusterWhoseSearchFailsWith(refusal());

    final var message = assertThrows(
        IllegalStateException.class,
        () -> Camunda8SearchableClusterCheck.requireAClusterWhichCanBeSearched("c8", queryApi))
        .getMessage();

    // whoever reads this line decides from it whether the message is about them
    assertTrue(message.contains("aggregate's id"), message);
    assertTrue(message.contains("elects the"), message);
    assertTrue(message.contains("zeebe:versionTag"), message);
    assertTrue(message.contains("element history"), message);

  }

  @Test
  @DisplayName("A cluster which cannot be reached is not refused")
  public void anUnreachableClusterIsNotRefused() {

    final var queryApi = queryApiOfAClusterWhoseSearchFailsWith(new IllegalStateException("connection refused"));

    assertDoesNotThrow(
        () -> Camunda8SearchableClusterCheck.requireAClusterWhichCanBeSearched("c8", queryApi),
        "otherwise a cluster booting alongside the application would end its boot");

  }

  @Test
  @DisplayName("Every workflow module asks, and the cluster is still searched once")
  public void everyModuleAsksAndTheClusterIsAskedOnce() {

    final var queryApi = queryApiOfAClusterWhoseSearchFailsWith(refusal());

    assertThrows(
        IllegalStateException.class,
        () -> Camunda8SearchableClusterCheck.requireAClusterWhichCanBeSearched("c8", queryApi));
    assertThrows(
        IllegalStateException.class,
        () -> Camunda8SearchableClusterCheck.requireAClusterWhichCanBeSearched("c8", queryApi));

    // asking per module is what lets one module's 'deployment-failure: warn' boot
    // degraded while another one ends the boot, and it costs nothing: the answer is
    // remembered
    assertEquals(1, searches.get());

  }

}
