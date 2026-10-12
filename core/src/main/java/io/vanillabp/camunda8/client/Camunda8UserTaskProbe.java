package io.vanillabp.camunda8.client;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.JobKind;
import io.camunda.client.api.search.enums.ListenerEventType;

/**
 * The command which asks the ENGINE whether it still holds a Camunda-managed user task. It
 * also holds the mark which makes the job this command fires recognisable.
 *
 * <h2>Why a command and not a search</h2>
 *
 * An <code>UpdateUserTask</code> which carries nothing but an action changes no attribute
 * of the task and moves nothing on. The partition answers it, not the index which an
 * exporter feeds. Measured on 2026-09-19 against 8.9.19 and 8.10.0-alpha5 with no
 * <code>updating</code> listener modelled: <code>204</code> in 5 to 21 milliseconds for a
 * task which is open, and <code>404</code> for a task which was completed or never existed.
 * The search which used to answer the same question was between 167 and 2068 milliseconds
 * behind the engine. If a worker answers a modelled listener, the same <code>204</code>
 * takes 106 to 111 milliseconds on 8.9.19 and 15 to 78 milliseconds on 8.10.0-alpha5.
 *
 * <h2>What the 404 is about</h2>
 *
 * The key, not the task. A user task which is just open never gives one. Measured on
 * 2026-09-28 by {@code Camunda8ProbeOfAnOpenUserTaskIT} against 8.8.39, 8.9.21 and 8.10.0-rc1:
 * this command answered <code>204</code> 12, 21 and 13 ms after the <code>creating</code>
 * listener job of the task arrived, and also over five further readings. The same task, read
 * from the INDEX, answered <code>404</code> until 1667, 649 and 219 ms after that moment.
 * That is the reason why this is a command and not a search.
 * <p>
 * If it is handed a JOB key, it answers <code>404</code> for as long as that task is open.
 * That was measured in the same run, for a full minute, while the job command on the same
 * key was accepted. So the caller has to know the kind of key. {@code Camunda8OpenTaskProbe}
 * reads it from the model, and sends nothing for a task of the other kind. The core asks
 * the two probes about a task a caller named only for a user task. A version-1 user task
 * which a job worker serves has a job key as its id. The deployment reports such a task, so
 * nobody asks about it.
 *
 * <h2>The mark, and why the change list belongs to it</h2>
 *
 * The empty update FIRES a modelled <code>updating</code> task listener, although it changes
 * nothing at all. That was measured on both lines. A listener job which nobody answers holds
 * the task in state <code>UPDATING</code> for fifteen seconds. While that lasts, assigning or
 * completing the task gets <code>409</code>. So a probe of this adapter has to be
 * recognisable in the job it caused. {@link #isOurOwnProbe(ActivatedJob)} reads that: the
 * action this adapter sends comes back on the job, and the list of changed attributes is
 * empty.
 * <p>
 * Both conditions, not one. Anybody may send the action string. And an empty change list
 * alone does not come from this adapter either. Why the pair is the whole rule is
 * decision 38 in the repository's DECISIONS.md.
 * <p>
 * It is public because three packages of this module use it: the process service which
 * probes a single task, the check which probes the open tasks of a workflow, and the
 * handler which serves modelled listeners. It is not on the list of what an extension of
 * the pipeline is told. So it stays the adapter's own, and it may move with the next change.
 */
public final class Camunda8UserTaskProbe {

  private Camunda8UserTaskProbe() {
  }

  /**
   * The action which every probe of this adapter carries. It reaches the audit log of the
   * task and the <code>updating</code> listener job the probe fires. It is the half of the
   * mark which names VanillaBP.
   */
  public static final String ACTION = "io.vanillabp:probe";

  /**
   * Asks the engine about one user task. What the cluster answers is the whole result. So
   * this throws whatever the command threw, and the caller reads the code.
   *
   * @param client The client of the adapter asking
   * @param userTaskKey The user task to ask about
   */
  public static void askTheEngine(
      final CamundaClient client,
      final long userTaskKey) {

    // an update which carries ONLY an 'action' (audit metadata) is the smallest valid
    // update. No task attribute changes, and nothing moves on
    client
        .newUpdateUserTaskCommand(userTaskKey)
        .action(ACTION)
        .send()
        .join();

  }

  /**
   * What the index of the cluster holds under a key for which a user-task command answered
   * <code>404</code>. It is the job's own description, for the message which says what that
   * key is.
   *
   * @param type The job type, which is
   *          {@link io.vanillabp.camunda8.wiring.Camunda8TaskWiring#TASKDEFINITION_USERTASK_WORKER_V1}
   *          for a user task a job worker serves
   * @param elementId The BPMN element the job belongs to
   * @param bpmnProcessId The process definition id as the cluster knows it
   * @param state What the index says the job is doing, in the cluster's own words
   */
  public record Camunda8JobOfThatKey(
                                     String type,
                                     String elementId,
                                     String bpmnProcessId,
                                     String state) {
  }

  /**
   * Reads the job of a key out of the index, to NAME it in a message. It does not answer
   * whether the cluster holds such a job, and no caller may read it that way.
   * <p>
   * A search reads the index which an exporter feeds, so it is behind the engine at both
   * ends. Measured on 2026-09-28 against <code>camunda/camunda:8.9.21</code> and
   * <code>camunda/camunda:8.10.0-rc1</code> by {@code Camunda8ProbeOfAnOpenUserTaskIT}, on the
   * job of a user task which a job worker serves. The search answered "no job of that key"
   * while that job was activated and open. It still answered with the job once the job was
   * over, as <code>TIMED_OUT</code> at once and <code>COMPLETED</code> five seconds later. So
   * it says neither "there is one" nor "there is none". Whether the cluster holds a job of a
   * key is a question for the ENGINE. {@code Camunda8ProcessService} asks it with an
   * <code>UpdateJobTimeout</code>: <code>404</code> for a key without a job, and something else
   * for a key with one.
   * <p>
   * The state goes into the message as the cluster wrote it, and it is never read here. The
   * two lines above named the same job differently five seconds after it was activated:
   * 8.9.21 as <code>CREATED</code> and 8.10.0-rc1 as <code>TIMEOUT_UPDATED</code>. This adapter
   * builds no decision on a literal which may be new in a patch release.
   * <p>
   * What this adds is the element, the process and the state. They turn "that is a job key"
   * into a sentence somebody can act on. Where it answers nothing, the message says the rest
   * without them.
   *
   * @param client The client of the adapter asking
   * @param key The key the user-task command was handed
   * @return What the index holds under that key, or <code>null</code> where it holds nothing and
   *         where the search itself could not answer
   */
  public static Camunda8JobOfThatKey theJobTheIndexHoldsFor(
      final CamundaClient client,
      final long key) {

    try {
      final var found = client
          .newJobSearchRequest()
          .filter(filter -> filter.jobKey(Long.valueOf(key)))
          .page(page -> page.limit(1))
          .send()
          .join()
          .items();
      if (found.isEmpty()) {
        return null;
      }
      final var job = found.getFirst();
      return new Camunda8JobOfThatKey(
          job.getType(), job.getElementId(), job.getProcessDefinitionId(), String.valueOf(job.getState()));
    } catch (final RuntimeException e) {
      // a diagnostic never becomes the failure. The message is written without these values
      return null;
    }

  }

  /**
   * Whether a probe of this adapter fired this listener job. No application method has
   * anything to say about such a job.
   *
   * @param job The listener job as it arrived
   * @return Whether the mark is on it
   */
  public static boolean isOurOwnProbe(
      final ActivatedJob job) {

    if ((job.getKind() != JobKind.TASK_LISTENER) || (job.getListenerEventType() != ListenerEventType.UPDATING)) {
      return false;
    }
    final var userTask = job.getUserTask();
    if (userTask == null) {
      return false;
    }
    final var changed = userTask.getChangedAttributes();
    return ACTION.equals(userTask.getAction()) && ((changed == null) || changed.isEmpty());

  }

}
