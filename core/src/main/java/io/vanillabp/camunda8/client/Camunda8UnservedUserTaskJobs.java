package io.vanillabp.camunda8.client;

import java.util.List;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.JobState;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;

/**
 * How many plain BPMN user tasks of one BPMN process are still waiting for somebody, as far as
 * the cluster's index knows.
 *
 * <h2>Why the search subtracts instead of naming the open states</h2>
 *
 * The cluster serves a plain BPMN user task with a job of
 * {@link Camunda8TaskWiring#TASKDEFINITION_USERTASK_WORKER_V1}, so the question is how many jobs
 * of that type the process still has out. A search by process and job type alone answers every
 * job of that type the process ever had, because the index keeps a job after it is over:
 * measured on 2026-09-28 against <code>camunda/camunda:8.9.21</code> by
 * {@code Camunda8ProbeOfAnOpenUserTaskIT}, a search on the key of a completed job still answered
 * with that job, as <code>TIMED_OUT</code> at once and as <code>COMPLETED</code> five seconds
 * later. A count read that way never goes to zero, which is the one thing an upgrading
 * application wants to watch.
 * <p>
 * So the state belongs in the search, and it goes in as what a job which is over looks like
 * rather than as a list of the states an open one may be in. Two searches are sent and the
 * second is taken off the first: all jobs of that type, and those of them the index holds in a
 * state a job does not leave again. The reason is the client, which grows its enums inside a
 * release line. Line 8.10 brought <code>PRIORITY_UPDATED</code> and
 * <code>TIMEOUT_UPDATED</code>, both of them states of a job which is still there, and
 * 8.10.0-rc1 named a job five seconds after its activation <code>TIMEOUT_UPDATED</code> where
 * 8.9.21 named the same job <code>CREATED</code>. A list of open states written here would have
 * dropped such a job without a word. The subtraction counts a state nobody here knows as open,
 * which says one task too many rather than hiding one somebody still has to finish. It is the
 * same rule as everywhere else in this adapter: a value this build has no literal for lands on
 * the harmless side, see {@code Camunda8UnknownClientEnumsTest}.
 * <p>
 * The three lines agree about the plain case. Measured on 2026-10-01 by
 * {@code Camunda8CountOfOpenUnservedUserTasksIT} against <code>camunda/camunda:8.8.40</code>,
 * <code>8.9.21</code> and <code>8.10.0-rc3</code>, with one job of that type open and one
 * completed in the same process: every line named the open job <code>CREATED</code> and the
 * completed one <code>COMPLETED</code>, and the count settled on one within 1.2 seconds. What
 * they do not agree about is the open job later in its life, which is the whole reason the
 * search names the end states.
 *
 * <h2>What the number is not</h2>
 *
 * An exporter feeds the index, so the index runs behind the engine at both ends. A task which
 * finished a moment ago can still be counted, and one which opened a moment ago can be missing
 * altogether: the same measurement answered "no job of that key" for a job which was activated
 * and open. The two searches are two readings as well, and a job which finishes between them is
 * in the first and in the second. So this is a number to watch rather than one to rely on, and
 * {@link #howManyAreOpen(Count)} is the sentence which says that wherever the number is written.
 * <p>
 * Public because the deployment writes the sentence and an integration test reads the numbers
 * against a real cluster. It is not on the list of what an extension of the pipeline is told, so
 * it stays the adapter's own and may move with the next change.
 */
public final class Camunda8UnservedUserTaskJobs {

  private Camunda8UnservedUserTaskJobs() {
  }

  /**
   * The states a job does not leave again. Everything else counts as open, including a literal
   * this build has never seen.
   */
  private static final List<JobState> A_JOB_WHICH_IS_OVER = List.of(
      JobState.COMPLETED,
      JobState.CANCELED,
      JobState.ERROR_THROWN);

  /**
   * What the index answered about the jobs of the plain user task's type for one process.
   *
   * @param theIndexHolds How many jobs of that type it holds for the process, in whatever state
   * @param theIndexHasSeenFinish How many of those it holds in a state a job does not leave
   *          again
   */
  public record Count(
                      long theIndexHolds,
                      long theIndexHasSeenFinish) {

    /**
     * The number the deployment writes, and the one which falls as those tasks are worked off.
     *
     * @return How many of them are still waiting for somebody. Never below zero: the two
     *         numbers are two readings, and a job which arrived between them is in the second
     *         one only
     */
    public long areOpen() {

      return Math.max(0L, theIndexHolds - theIndexHasSeenFinish);

    }

  }

  /**
   * Asks the index about the jobs of the plain user task's type for one process.
   *
   * @param client The client of the adapter asking
   * @param scopedBpmnProcessId The process definition id as the cluster knows it
   * @return Both numbers. Whatever the search threw is thrown on, because what a cluster which
   *         cannot answer costs is the caller's to decide
   */
  public static Count countFor(
      final CamundaClient client,
      final String scopedBpmnProcessId) {

    // two readings and not one: whoever reads them knows it, because Count never answers
    // less than nothing
    final var allOfThatType = howManyJobs(client, scopedBpmnProcessId, null);
    final var thoseWhichAreOver = howManyJobs(client, scopedBpmnProcessId, A_JOB_WHICH_IS_OVER);
    return new Count(allOfThatType, thoseWhichAreOver);

  }

  /**
   * @param client The client of the adapter asking
   * @param scopedBpmnProcessId The process definition id as the cluster knows it
   * @param states The states to count, or <code>null</code> to count the jobs of that type
   *          whatever state they are in
   * @return How many jobs the index holds for that question
   */
  private static long howManyJobs(
      final CamundaClient client,
      final String scopedBpmnProcessId,
      final List<JobState> states) {

    final var found = client
        .newJobSearchRequest()
        .filter(filter -> {
          filter
              .processDefinitionId(scopedBpmnProcessId)
              .type(Camunda8TaskWiring.TASKDEFINITION_USERTASK_WORKER_V1);
          if (states != null) {
            filter.state(state -> state.in(states));
          }
        })
        // the TOTAL rather than the page which came back, and one item fetched because only
        // the number is wanted
        .page(page -> page.limit(1))
        .send()
        .join();
    final var total = found.page().totalItems();
    return total == null
        ? 0L
        : total.longValue();

  }

  /**
   * What every message about such an element says about the number, so the deployment's
   * refusal and its warning say the same thing about it.
   *
   * @param count What the index answered, or <code>null</code> where the cluster did not answer
   *          at all
   * @return The sentences for the message, ending with a full stop
   */
  public static String howManyAreOpen(
      final Count count) {

    if (count == null) {
      return "The cluster did not answer how many of them are open right now.";
    }
    return """
        Open right now: %d. The cluster's index holds %d job(s) of that type for this process and \
        has seen %d of them end. An exporter feeds that index and it runs behind the engine, so a \
        task which just ended can still be counted and one which just opened can still be \
        missing."""
        .formatted(
            Long.valueOf(count.areOpen()),
            Long.valueOf(count.theIndexHolds()),
            Long.valueOf(count.theIndexHasSeenFinish()));

  }

}
