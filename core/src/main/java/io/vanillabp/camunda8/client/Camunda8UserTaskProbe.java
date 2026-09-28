package io.vanillabp.camunda8.client;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.JobKind;
import io.camunda.client.api.search.enums.ListenerEventType;

/**
 * The command which asks the ENGINE whether it still holds a Camunda-managed user task, and
 * the mark which makes the job it fires recognisable.
 *
 * <h2>Why a command rather than a search</h2>
 *
 * An <code>UpdateUserTask</code> carrying nothing but an action changes no attribute of the
 * task and advances nothing. It is answered by the partition rather than by the index an
 * exporter feeds: measured on 2026-09-19 against 8.9.19 and 8.10.0-alpha5 with no
 * <code>updating</code> listener modelled, <code>204</code> in 5 to 21 milliseconds for a task
 * which is open and <code>404</code> for a task which was completed or never existed, while
 * the search the same question used to take was between 167 and 2068 milliseconds behind the
 * engine. A modelled listener a worker answers makes the same <code>204</code> take 106 to 111
 * milliseconds on 8.9.19 and 15 to 78 milliseconds on 8.10.0-alpha5.
 *
 * <h2>What the 404 is about</h2>
 *
 * The key, and not the task. A user task which is simply open never produces one: measured on
 * 2026-09-28 by {@code Camunda8ProbeOfAnOpenUserTaskIT} against 8.8.39, 8.9.21 and 8.10.0-rc1,
 * this command answered <code>204</code> 12, 21 respectively 13 ms after the
 * <code>creating</code> listener job of the task arrived, and over five further readings. The
 * same task read from the INDEX answered <code>404</code> until 1667, 649 respectively 219 ms
 * after that moment, which is the reason this is a command and not a search.
 * <p>
 * Handed a JOB key it answers <code>404</code> for as long as that task is open - measured in
 * the same run, for a full minute, while the job command on the same key was accepted. So the
 * caller owes the kind of key: {@code Camunda8OpenTaskProbe} reads it from the model and sends
 * nothing for a task of the other kind, and the two probes which ask about a task a caller
 * named are asked about a user task by the core. A version-1 user task served by a job worker
 * is the shape whose id is a job key, and the deployment reports one rather than letting it be
 * asked about.
 *
 * <h2>The mark, and why the change list belongs to it</h2>
 *
 * The empty update FIRES a modelled <code>updating</code> task listener, measured on both
 * lines, although it changes nothing at all. A listener job which nobody answers holds the
 * task in state <code>UPDATING</code> for fifteen seconds, and assigning or completing it
 * answers <code>409</code> for as long as that lasts. So a probe of this adapter has to be
 * recognisable in the job it caused, and {@link #isOurOwnProbe(ActivatedJob)} is where that
 * is read: the action this adapter sends comes back on the job, and the list of changed
 * attributes is empty.
 * <p>
 * Both conditions and not one. The action is a string anybody may send, and an empty change
 * list on its own is not this adapter's doing either. Why the pair is the whole rule is
 * decision 38 in the repository's DECISIONS.md.
 * <p>
 * Public because the process service which probes a single task, the check which probes the
 * open tasks of a workflow and the handler which serves modelled listeners live in three
 * packages of this module. It is not on the list of what an extension of the pipeline is
 * told, so it stays the adapter's own and may move with the next change.
 */
public final class Camunda8UserTaskProbe {

  private Camunda8UserTaskProbe() {
  }

  /**
   * The action every probe of this adapter carries. It reaches the audit log of the task and
   * the <code>updating</code> listener job the probe fires, and it is the half of the mark
   * which names VanillaBP.
   */
  public static final String ACTION = "io.vanillabp:probe";

  /**
   * Asks the engine about one user task. What the cluster answers is the whole result, so
   * this throws whatever the command threw and the caller reads the code.
   *
   * @param client The client of the adapter asking
   * @param userTaskKey The user task to ask about
   */
  public static void askTheEngine(
      final CamundaClient client,
      final long userTaskKey) {

    // an update carrying ONLY an 'action' (an audit metadatum) is the minimal valid
    // update - no task attribute changes, nothing advances
    client
        .newUpdateUserTaskCommand(userTaskKey)
        .action(ACTION)
        .send()
        .join();

  }

  /**
   * Whether this listener job was fired by a probe of this adapter, which is a job no
   * application method has anything to say about.
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
