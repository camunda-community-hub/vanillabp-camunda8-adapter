package io.vanillabp.camunda8.processservice;

import java.time.Duration;
import java.time.Instant;

import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.client.Camunda8Suspension;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import io.vanillabp.spi.process.WorkflowSuspendedException;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds out whether a refused command about a task was refused because the workflow of the task
 * is suspended, and says what follows from that.
 * <p>
 * A suspended instance refuses every command about one of its jobs or user tasks with HTTP
 * <code>409</code>, on gRPC <code>FAILED_PRECONDITION</code>. The cluster gives the same code for
 * other states too: a user task which is still being created, a job which another activation
 * holds. So the code is where the question starts, and the state of the instance answers it.
 * The question is asked ONLY after such a refusal. A call on an active workflow costs nothing
 * extra.
 * <p>
 * Two requests answer it. The first one finds the instance of the task: the job search for a
 * job key, the user-task lookup for a user-task key. The second one asks for the state of that
 * instance. Both go to the query API, which runs behind the engine. A suspension of a moment ago
 * may still read <code>ACTIVE</code>, and a task created a moment ago may not be found yet. Then
 * the answer is "not suspended", and the refusal is read the way it was read before. That window
 * is accepted, see {@code DECISIONS.pending/2070.md}.
 * <p>
 * A cluster of the lines 8.8 and 8.9 cannot suspend. On those lines nothing is asked.
 */
@Slf4j
final class Camunda8SuspendedWorkflow {

  /**
   * How long an outbox entry waits before it asks a suspended workflow again. The resume is
   * nothing the adapter is told about. So this is the longest an answer is late after somebody
   * resumed the workflow, and each wait costs the refused command and the two requests of the
   * question.
   */
  static final Duration WAIT_WHILE_SUSPENDED = Duration.ofSeconds(30);

  private final String adapterId;

  private final Camunda8ClientFactory clientFactory;

  Camunda8SuspendedWorkflow(
      final String adapterId,
      final Camunda8ClientFactory clientFactory) {

    this.adapterId = adapterId;
    this.clientFactory = clientFactory;

  }

  /**
   * Whether a refused command about a JOB was refused because its workflow is suspended.
   *
   * @param refusal What the command threw
   * @param jobKey The job the command was about
   * @return Whether the workflow of the job is suspended
   */
  boolean refusedTheJob(
      final Throwable refusal,
      final long jobKey) {

    if (!Camunda8Suspension.theClusterCanSuspend() || !Camunda8Errors.jobHeldByAnotherActivation(refusal)) {
      return false;
    }
    try {
      final var jobs = clientFactory
          .getClient()
          .newJobSearchRequest()
          .filter(filter -> filter.jobKey(jobKey))
          .send()
          .join()
          .items();
      if (jobs.isEmpty() || (jobs.getFirst().getProcessInstanceKey() == null)) {
        log.debug(
            "Camunda8[{}]: the job search does not report job {} yet - its refusal is not read as a "
                + "suspended workflow",
            adapterId,
            jobKey);
        return false;
      }
      return Camunda8Suspension.isSuspended(clientFactory.getClient(), jobs.getFirst().getProcessInstanceKey());
    } catch (final RuntimeException e) {
      log.debug(
          "Camunda8[{}]: could not find out whether the workflow of job {} is suspended",
          adapterId,
          jobKey,
          e);
      return false;
    }

  }

  /**
   * Whether a refused command about a USER TASK was refused because its workflow is suspended.
   *
   * @param refusal What the command threw
   * @param userTaskKey The user task the command was about
   * @return Whether the workflow of the user task is suspended
   */
  boolean refusedTheUserTask(
      final Throwable refusal,
      final long userTaskKey) {

    if (!Camunda8Suspension.theClusterCanSuspend() || !Camunda8Errors.refusedAboutAUserTaskItHolds(refusal)) {
      return false;
    }
    try {
      final var processInstanceKey = clientFactory
          .getClient()
          .newUserTaskGetRequest(userTaskKey)
          .send()
          .join()
          .getProcessInstanceKey();
      return (processInstanceKey != null) && Camunda8Suspension.isSuspended(clientFactory.getClient(),
          processInstanceKey);
    } catch (final RuntimeException e) {
      // a task which is still being created may not be in the search yet. That is the
      // common reason for a 409 about a user task, and it is no suspension
      log.debug(
          "Camunda8[{}]: could not find out whether the workflow of user task {} is suspended",
          adapterId,
          userTaskKey,
          e);
      return false;
    }

  }

  /**
   * What phase one throws for a task of a suspended workflow. The caller's transaction rolls
   * back, and the task stays open.
   *
   * @param kindOfTask <code>task</code> or <code>user task</code>, for the message
   * @param taskId The task the caller named
   * @param operationDescription What was attempted, for the message
   * @param refusal What the cluster answered the check with
   * @return The exception to throw
   */
  WorkflowSuspendedException refuseTheCall(
      final String kindOfTask,
      final String taskId,
      final String operationDescription,
      final Throwable refusal) {

    log.info(
        "Camunda8[{}]: the pre-commit check found the workflow of {} '{}' suspended - the cluster refused "
            + "it with {}",
        adapterId,
        kindOfTask,
        taskId,
        Camunda8Errors.rejection(refusal));
    return new WorkflowSuspendedException(
        """
            The workflow of %s '%s' is suspended in the Camunda 8 cluster of adapter '%s'. The cluster \
            refuses every answer to its tasks while it is suspended - aborting the transaction %s it! \
            Make the same call again after the workflow is resumed, for example in Operate."""
            .formatted(kindOfTask, taskId, adapterId, operationDescription), refusal);

  }

  /**
   * What phase two throws for a task of a suspended workflow. The outbox entry waits, and the
   * wait uses none of its attempts.
   * <p>
   * The wait lasts as long as the workflow is suspended. A suspension is ended by a person, not
   * by time, so there is no moment after which waiting stops making sense. Each answer
   * therefore names a deadline behind its own window. The outbox blocks the entry only once an
   * answer is late past it, and
   * <code>vanillabp.outbox.wait-for-visibility-at-most</code> does not apply. A workflow which is
   * canceled while it is suspended ends the wait as well: the next attempt finds the task gone.
   * <p>
   * The refused command changed nothing in the cluster. So the next attempt may look like a
   * first one, which is what the outbox expects of this answer.
   *
   * @param kindOfTask <code>task</code> or <code>user task</code>, for the message
   * @param taskId The task of the entry
   * @param operationDescription What waits, for the message
   * @return The failure to throw
   */
  PhaseTwoRetryLater waitInTheOutbox(
      final String kindOfTask,
      final String taskId,
      final String operationDescription) {

    return new PhaseTwoRetryLater(
        """
            Camunda8[%s]: the workflow of %s '%s' is suspended, and the cluster refuses the %s. The \
            outbox entry asks again in %s, for as long as the workflow is suspended, and uses none of \
            its attempts. Resume the workflow to let the %s through, or cancel the workflow to end \
            the wait."""
            .formatted(
                adapterId,
                kindOfTask,
                taskId,
                operationDescription,
                WAIT_WHILE_SUSPENDED,
                operationDescription), WAIT_WHILE_SUSPENDED, Instant
                    .now()
                    .plus(WAIT_WHILE_SUSPENDED.multipliedBy(2)));

  }

}
