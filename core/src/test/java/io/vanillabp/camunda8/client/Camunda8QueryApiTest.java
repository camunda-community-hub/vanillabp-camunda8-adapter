package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Whether a cluster answers query-API requests is asked once and remembered, and nothing
 * later re-derives it from a failure of its own.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8QueryApiTest {

  /**
   * How many searches reached the cluster - the number the "asked once" claim is made of.
   */
  private final AtomicInteger searches = new AtomicInteger();

  /**
   * A cluster whose only search either answers nothing or fails the given way.
   *
   * @param failure What the search throws, or <code>null</code> where it answers
   * @return The query API of an adapter talking to that cluster
   */
  private Camunda8QueryApi queryApiOfAClusterWhich(
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

  /**
   * How a cluster refuses a query-API request: HTTP 403, whose problem detail names the
   * reason in prose and never in a code.
   */
  private static ProblemException refusal() {

    final var details = new ProblemDetail();
    details.setStatus(403);
    details.setTitle("FORBIDDEN");
    return new ProblemException(403, "Forbidden", details);

  }

  @Test
  @DisplayName("A cluster which answers an empty search can be searched, and is asked once")
  public void anEmptyAnswerIsAnAnswer() {

    final var queryApi = queryApiOfAClusterWhich(null);

    assertTrue(queryApi.answers(), "finding nothing is an answer like any other");
    assertTrue(queryApi.answers());
    assertTrue(queryApi.answers());

    assertEquals(1, searches.get(), "the answer is remembered rather than asked again");

  }

  @Test
  @DisplayName("A cluster refusing to be searched is remembered as such, and is asked once")
  public void aRefusalIsRemembered() {

    final var queryApi = queryApiOfAClusterWhich(refusal());

    assertFalse(queryApi.answers());
    assertFalse(queryApi.answers());

    assertEquals(
        1,
        searches.get(),
        "the answer is remembered rather than asked again");

  }

  @Test
  @DisplayName("A cluster which cannot be reached is not declared incapable")
  public void anUnreachableClusterKeepsTheQuestionOpen() {

    final var queryApi = queryApiOfAClusterWhich(new IllegalStateException("connection refused"));

    assertTrue(queryApi.answers(), "an outage says nothing about what the cluster offers");
    assertTrue(queryApi.answers());

    assertEquals(2, searches.get(), "so the next question asks again");

  }

  @Test
  @DisplayName("The wording of a failure decides nothing")
  public void theWordingOfAFailureDecidesNothing() {

    final var queryApi = queryApiOfAClusterWhich(
        new IllegalStateException("This endpoint requires a secondary storage, but none is set"));

    assertTrue(
        queryApi.answers(),
        "a failure carrying the words and no status is an outage, whatever it says");

  }

}
