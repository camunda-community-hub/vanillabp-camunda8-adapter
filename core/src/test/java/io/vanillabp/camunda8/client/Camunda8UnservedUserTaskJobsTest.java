package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.search.enums.JobState;
import io.camunda.client.api.search.filter.JobFilter;
import io.camunda.client.api.search.filter.builder.JobStateProperty;
import io.camunda.client.api.search.request.JobSearchRequest;
import io.camunda.client.api.search.response.Job;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.SearchResponsePage;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How many plain BPMN user tasks of a BPMN process are still open, and why the search asks for
 * it the way it does.
 * <p>
 * The number used to be the total of one search by process and job type. The index keeps a job
 * after it is over, so that total was every job of that type the process ever had and it never
 * reached zero. What this class pins is the two searches which replaced it and the arithmetic
 * between them. What a cluster really answers to them is
 * {@code Camunda8CountOfOpenUnservedUserTasksIT}, which reads one open and one finished job of
 * the same type in the same process.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8UnservedUserTaskJobsTest {

  private static final String PROCESS = "TheLoanApproval";

  /**
   * The filters the searches of one reading were built with, in the order they were sent.
   */
  private final List<JobFilter> filtersSent = new ArrayList<>();

  /**
   * A client whose job search answers the given totals, one per search, in that order.
   *
   * @param totals What the page of each search answers as its total
   * @return The client under the measurement
   */
  private CamundaClient clientWhoseSearchesAnswer(
      final long... totals) {

    final var client = mock(CamundaClient.class);
    final var search = mock(JobSearchRequest.class, RETURNS_SELF);
    when(client.newJobSearchRequest()).thenReturn(search);
    final var answers = new ArrayList<CamundaFuture<SearchResponse<Job>>>();
    for (final var total : totals) {
      answers.add(aSearchAnswering(total));
    }
    when(search.filter(ArgumentMatchers.<Consumer<JobFilter>>any())).thenAnswer(invocation -> {
      // the filter is built by the production code, on a filter this class can read
      // afterwards, so what is asserted below is what was really asked for
      final var filter = mock(JobFilter.class, RETURNS_SELF);
      filtersSent.add(filter);
      invocation.<Consumer<JobFilter>>getArgument(0).accept(filter);
      return search;
    });
    when(search.send()).thenAnswer(invocation -> answers.get(filtersSent.size() - 1));
    return client;

  }

  /**
   * @param total What the page of this search answers as its total
   * @return One search's answer
   */
  private static CamundaFuture<SearchResponse<Job>> aSearchAnswering(
      final long total) {

    final var page = mock(SearchResponsePage.class);
    when(page.totalItems()).thenReturn(Long.valueOf(total));
    @SuppressWarnings("unchecked")
    final SearchResponse<Job> found = mock(SearchResponse.class);
    when(found.page()).thenReturn(page);
    @SuppressWarnings("unchecked")
    final CamundaFuture<SearchResponse<Job>> answer = mock(CamundaFuture.class);
    when(answer.join()).thenReturn(found);
    return answer;

  }

  /**
   * @param which The search, counted from zero
   * @return The job states its filter named
   */
  private List<JobState> statesNamedBy(
      final int which) {

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Consumer<JobStateProperty>> asked = ArgumentCaptor.forClass(Consumer.class);
    verify(filtersSent.get(which)).state(asked.capture());
    final var property = mock(JobStateProperty.class, RETURNS_SELF);
    asked.getValue().accept(property);
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<List<JobState>> states = ArgumentCaptor.forClass(List.class);
    verify(property).in(states.capture());
    return states.getValue();

  }

  @Test
  @DisplayName("A job the index has seen finish is not counted as open any more")
  public void aJobWhichIsOverIsNotOpen() {

    final var count = Camunda8UnservedUserTaskJobs
        .countFor(clientWhoseSearchesAnswer(2L, 1L), PROCESS);

    assertEquals(2L, count.theIndexHolds(), "both jobs of that type are in the index");
    assertEquals(1L, count.theIndexHasSeenFinish(), "and the index has seen one of them finish");
    assertEquals(
        1L,
        count.areOpen(),
        "so one task is left for somebody, which is the number the old count never reached");

  }

  @Test
  @DisplayName("Both searches ask about the same process and the same job type")
  public void bothSearchesAskAboutTheSameJobs() {

    Camunda8UnservedUserTaskJobs.countFor(clientWhoseSearchesAnswer(2L, 1L), PROCESS);

    assertEquals(2, filtersSent.size(), "one search for all of them, one for those which are over");
    filtersSent
        .forEach(filter -> {
          verify(filter).processDefinitionId(PROCESS);
          verify(filter).type(Camunda8TaskWiring.TASKDEFINITION_USERTASK_WORKER_V1);
        });

  }

  @Test
  @DisplayName("Only the states a job does not leave again are named, so a state this build never saw counts as open")
  public void onlyTheStatesOfAJobWhichIsOverAreNamed() {

    Camunda8UnservedUserTaskJobs.countFor(clientWhoseSearchesAnswer(2L, 1L), PROCESS);

    verify(filtersSent.get(0), never()).state(ArgumentMatchers.<Consumer<JobStateProperty>>any());
    assertEquals(
        List.of(JobState.COMPLETED, JobState.CANCELED, JobState.ERROR_THROWN),
        statesNamedBy(1),
        "the states of a job which is over, and nothing else. The client grows this enum inside a "
            + "release line, so a job in a state this build has no literal for is in the first "
            + "search and in none of these three, which counts it as open");

  }

  @Test
  @DisplayName("A job which finishes between the two searches does not make the count negative")
  public void theCountNeverFallsBelowZero() {

    final var count = Camunda8UnservedUserTaskJobs
        .countFor(clientWhoseSearchesAnswer(1L, 2L), PROCESS);

    assertEquals(
        0L,
        count.areOpen(),
        "the two numbers are two readings, and the second may hold a job the first did not");

  }

  @Test
  @DisplayName("The sentence says what the number is and what it is worth")
  public void theSentenceSaysWhatTheNumberIs() {

    final var said = Camunda8UnservedUserTaskJobs
        .howManyAreOpen(new Camunda8UnservedUserTaskJobs.Count(2L, 1L));

    assertTrue(
        said.startsWith("Open right now: 1."),
        () -> "the number a reader watches comes first: "
            + said);
    assertTrue(
        said.contains("holds 2 job(s) of that type for this process and has seen 1 of them end"),
        () -> "and the two numbers behind it, so a reader can check the arithmetic: "
            + said);
    assertTrue(
        said.contains("runs behind the engine"),
        () -> "an exporter feeds that index, which the message admits instead of claiming to be "
            + "exact: "
            + said);

  }

  @Test
  @DisplayName("A cluster which did not answer gets no number invented for it")
  public void aClusterWhichDidNotAnswerGetsNoNumber() {

    final var said = Camunda8UnservedUserTaskJobs.howManyAreOpen(null);

    assertEquals(
        "The cluster did not answer how many of them are open right now.",
        said,
        "a boot which runs before the cluster is up says this instead of a zero");

  }

}
