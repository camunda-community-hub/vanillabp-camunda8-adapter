package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.command.SetVariablesCommandStep1;
import io.camunda.client.api.command.SetVariablesCommandStep1.SetVariablesCommandStep2;
import io.camunda.client.api.search.enums.ElementInstanceType;
import io.camunda.client.api.search.filter.ElementInstanceFilter;
import io.camunda.client.api.search.request.ElementInstanceSearchRequest;
import io.camunda.client.api.search.request.JobSearchRequest;
import io.camunda.client.api.search.response.ElementInstance;
import io.camunda.client.api.search.response.Job;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.SearchResponsePage;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Where a changed workflow aggregate is written when the task it belongs to runs inside a
 * scope with MORE sibling element instances than one page holds - the iterations of a
 * multi-instance subprocess over a long list.
 * <p>
 * Camunda 8 reports the children of a scope and never the parent of one, so the scope a
 * task runs in is found by walking down from the process instance. A search answering one
 * page let that walk see the first 100 iterations only: the task of the 101st was a task
 * below no scope at all, the adapter reported the scope as unknown, and the aggregate was
 * not written anywhere. The warning it logged blamed the exporter for being late, which is
 * the one thing it was not.
 * <p>
 * The cluster is played by the searches, one page of children per call. Which page was
 * asked for is not read off the call, because the type the client passes the page lambda
 * differs between the release lines this adapter is built from - what is measured is the
 * element instance the values were written to.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ScopeBeyondOnePageTest {

  private static final long PROCESS_INSTANCE = 4711L;

  private static final long MULTI_INSTANCE_BODY = 5000L;

  /**
   * How many iterations the multi-instance ran. More than one page, so the iteration the
   * task belongs to is on the second one.
   */
  private static final int ITERATIONS = 150;

  /**
   * The subprocess instance of the LAST iteration, which is the scope the task of this test
   * runs in.
   */
  private static final long LAST_ITERATION = 6000L + (ITERATIONS - 1);

  private static final long TASKS_ELEMENT_INSTANCE = 7777L;

  private final CamundaClient client = mock(CamundaClient.class);

  /**
   * The children of each element instance, which is what the walk down the scopes reads.
   */
  private final Map<Long, List<ElementInstance>> childrenByScope = new HashMap<>();

  /**
   * How often each scope was asked for a page of its children.
   */
  private final Map<Long, Integer> pagesAskedPerScope = new HashMap<>();

  /**
   * Which element instance the changed aggregate was written to.
   */
  private final List<Long> writtenTo = new ArrayList<>();

  @BeforeEach
  public void setUp() {

    childrenByScope
        .put(
            Long.valueOf(PROCESS_INSTANCE),
            List
                .of(
                    elementInstance(MULTI_INSTANCE_BODY, ElementInstanceType.MULTI_INSTANCE_BODY)));
    childrenByScope
        .put(
            Long.valueOf(MULTI_INSTANCE_BODY),
            IntStream
                .range(0, ITERATIONS)
                .mapToObj(
                    iteration -> elementInstance(6000L + iteration, ElementInstanceType.SUB_PROCESS))
                .toList());
    childrenByScope
        .put(
            Long.valueOf(LAST_ITERATION),
            List.of(elementInstance(TASKS_ELEMENT_INSTANCE, ElementInstanceType.SERVICE_TASK)));

    theJobOfTheTaskIsKnown();
    theChildrenOfAScopeComeInPages();
    theWritesAreRecorded();

  }

  @Test
  @DisplayName("The aggregate is written into the iteration the task runs in, past the first page as well")
  public void theScopeOfALateIterationIsFound() {

    PhaseOperations
        .phaseTwo(
            configuredService(),
            PhaseOperation.AGGREGATE_CHANGED,
            "order-module",
            "OrderApproval",
            null,
            "42",
            Map.of(PhaseTwoCall.ARG_TASK_ID, String.valueOf(TASKS_ELEMENT_INSTANCE)));

    assertEquals(
        List.of(Long.valueOf(LAST_ITERATION)),
        writtenTo,
        "the values belong to the iteration the task runs in - the workflow's own scope is read by "
            + "every other iteration as well");
    assertEquals(
        2,
        pagesAskedPerScope
            .get(Long.valueOf(MULTI_INSTANCE_BODY))
            .intValue(),
        "150 iterations are two pages of children, and the walk asked for both");
    assertEquals(
        153,
        pagesAskedPerScope
            .values()
            .stream()
            .mapToInt(Integer::intValue)
            .sum(),
        "what the walk costs: one search for the process instance, two pages of the body, and one "
            + "per iteration until the right one - reading every page lifted the ceiling which hid "
            + "that, it did not create it");

  }

  private Camunda8ProcessService<Object> configuredService() {

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing contacts: every request of this test meets a mock
    configuration.setRestAddress("http://localhost:1");
    // the exporter of this cluster is never late, so nothing is waited for
    configuration.setWorkflowVisibilityTimeout(Duration.ZERO);
    final var clientFactory = new Camunda8ClientFactory("c8", configuration) {

      @Override
      public CamundaClient getClient() {
        return client;
      }

    };
    return new Camunda8ProcessService<>("c8", clientFactory, Duration.ofDays(14), (
        aggregateClass,
        check) -> check.run(), null);

  }

  /**
   * A cluster which knows the job behind the task id, which is where the walk down the
   * scopes starts.
   */
  private void theJobOfTheTaskIsKnown() {

    final var job = mock(Job.class);
    Mockito.lenient().when(job.getProcessInstanceKey()).thenReturn(Long.valueOf(PROCESS_INSTANCE));
    Mockito.lenient().when(job.getElementInstanceKey()).thenReturn(Long.valueOf(TASKS_ELEMENT_INSTANCE));
    final var search = mock(JobSearchRequest.class, RETURNS_SELF);
    Mockito.lenient().when(client.newJobSearchRequest()).thenReturn(search);
    Mockito.lenient().when(search.send()).thenAnswer(invocation -> future(response(List.of(job), null)));

  }

  /**
   * A cluster answering with one page of a scope's children per call, in the order the
   * iterations started.
   */
  private void theChildrenOfAScopeComeInPages() {

    final var search = mock(ElementInstanceSearchRequest.class, RETURNS_SELF);
    final var askedAbout = new Long[1];
    Mockito
        .lenient()
        .when(search.filter(Mockito.<Consumer<ElementInstanceFilter>>any()))
        .thenAnswer(invocation -> {
          final Consumer<ElementInstanceFilter> filter = invocation.getArgument(0);
          final var recording = mock(ElementInstanceFilter.class, RETURNS_SELF);
          Mockito.lenient().when(recording.elementInstanceScopeKey(anyLong())).thenAnswer(call -> {
            askedAbout[0] = call.getArgument(0);
            return recording;
          });
          filter.accept(recording);
          return search;
        });
    Mockito.lenient().when(search.send()).thenAnswer(invocation -> {
      final var scope = askedAbout[0];
      final var children = childrenByScope.getOrDefault(scope, List.of());
      final var asked = pagesAskedPerScope.merge(scope, Integer.valueOf(1), (
          before,
          one) -> Integer.valueOf(before.intValue() + 1));
      final var from = (asked.intValue() - 1) * Camunda8SearchPages.PAGE_SIZE;
      final var page = children
          .subList(
              Math.min(from, children.size()),
              Math.min(from + Camunda8SearchPages.PAGE_SIZE, children.size()));
      return future(response(page, "after-child-"
          + asked));
    });
    Mockito.lenient().when(client.newElementInstanceSearchRequest()).thenReturn(search);

  }

  private void theWritesAreRecorded() {

    final var step2 = mock(SetVariablesCommandStep2.class);
    Mockito.lenient().when(step2.local(anyBoolean())).thenReturn(step2);
    Mockito.lenient().when(step2.send()).thenAnswer(invocation -> future(null));
    final var step1 = mock(SetVariablesCommandStep1.class);
    Mockito.lenient().when(step1.variables(Mockito.<Map<String, Object>>any())).thenReturn(step2);
    Mockito.lenient().when(client.newSetVariablesCommand(anyLong())).thenAnswer(invocation -> {
      writtenTo.add(invocation.getArgument(0));
      return step1;
    });

  }

  private static ElementInstance elementInstance(
      final long elementInstanceKey,
      final ElementInstanceType type) {

    final var elementInstance = mock(ElementInstance.class);
    Mockito
        .lenient()
        .when(elementInstance.getElementInstanceKey())
        .thenReturn(Long.valueOf(elementInstanceKey));
    Mockito.lenient().when(elementInstance.getType()).thenReturn(type);
    return elementInstance;

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
