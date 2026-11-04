package io.vanillabp.camunda8.processservice;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.client.Camunda8Suspension;
import io.vanillabp.integration.adapter.spi.SuspendedWorkflowReporter;
import io.vanillabp.integration.extension.spi.runtime.SuspendedWorkflowFound;
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
 * <p>
 * Where the answer is "suspended", the extensions hear it before the call is refused or the
 * outbox entry waits. The core tells them, and nothing they do changes what is thrown here (see
 * {@code DECISIONS.pending/2074.md}).
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

  private final SuspendedWorkflowReporter reporter;

  Camunda8SuspendedWorkflow(
      final String adapterId,
      final Camunda8ClientFactory clientFactory,
      final SuspendedWorkflowReporter reporter) {

    this.adapterId = adapterId;
    this.clientFactory = clientFactory;
    this.reporter = reporter == null
        ? SuspendedWorkflowReporter.NOBODY_LISTENS
        : reporter;

  }

  /**
   * The call whose task met a suspended workflow, as far as the extensions hear about it.
   *
   * @param workflowModuleId The workflow module of the call
   * @param bpmnProcessId The BPMN process of the call, as the application wrote it
   * @param workflowAggregateId The aggregate id of the call, or <code>null</code> where it is not
   *          known
   * @param taskId The task of the call
   */
  record TheCall(
                 String workflowModuleId,
                 String bpmnProcessId,
                 String workflowAggregateId,
                 String taskId) {
  }

  /**
   * Whether a refused command about a JOB was refused because its workflow is suspended.
   *
   * @param refusal What the command threw
   * @param jobKey The job the command was about
   * @return The key of the suspended process instance of the job, or empty where the refusal
   *         had another reason
   */
  OptionalLong suspendedInstanceOfTheJob(
      final Throwable refusal,
      final long jobKey) {

    if (!Camunda8Suspension.theClusterCanSuspend() || !Camunda8Errors.jobHeldByAnotherActivation(refusal)) {
      return OptionalLong.empty();
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
        return OptionalLong.empty();
      }
      final long processInstanceKey = jobs.getFirst().getProcessInstanceKey();
      return Camunda8Suspension.isSuspended(clientFactory.getClient(), processInstanceKey)
          ? OptionalLong.of(processInstanceKey)
          : OptionalLong.empty();
    } catch (final RuntimeException e) {
      log.debug(
          "Camunda8[{}]: could not find out whether the workflow of job {} is suspended",
          adapterId,
          jobKey,
          e);
      return OptionalLong.empty();
    }

  }

  /**
   * Whether a refused command about a USER TASK was refused because its workflow is suspended.
   *
   * @param refusal What the command threw
   * @param userTaskKey The user task the command was about
   * @return The key of the suspended process instance of the user task, or empty where the
   *         refusal had another reason
   */
  OptionalLong suspendedInstanceOfTheUserTask(
      final Throwable refusal,
      final long userTaskKey) {

    if (!Camunda8Suspension.theClusterCanSuspend() || !Camunda8Errors.refusedAboutAUserTaskItHolds(refusal)) {
      return OptionalLong.empty();
    }
    try {
      final var processInstanceKey = clientFactory
          .getClient()
          .newUserTaskGetRequest(userTaskKey)
          .send()
          .join()
          .getProcessInstanceKey();
      return (processInstanceKey != null) && Camunda8Suspension.isSuspended(clientFactory.getClient(),
          processInstanceKey)
              ? OptionalLong.of(processInstanceKey)
              : OptionalLong.empty();
    } catch (final RuntimeException e) {
      // a task which is still being created may not be in the search yet. That is the
      // common reason for a 409 about a user task, and it is no suspension
      log.debug(
          "Camunda8[{}]: could not find out whether the workflow of user task {} is suspended",
          adapterId,
          userTaskKey,
          e);
      return OptionalLong.empty();
    }

  }

  /**
   * What phase one throws for a task of a suspended workflow. The caller's transaction rolls
   * back, and the task stays open. The extensions hear about it first.
   *
   * @param kindOfTask <code>task</code> or <code>user task</code>, for the message
   * @param call The call the caller made
   * @param processInstanceKey The suspended process instance of the task
   * @param operationDescription What was attempted, for the message
   * @param refusal What the cluster answered the check with
   * @return The exception to throw
   */
  WorkflowSuspendedException refuseTheCall(
      final String kindOfTask,
      final TheCall call,
      final long processInstanceKey,
      final String operationDescription,
      final Throwable refusal) {

    final var taskId = call.taskId();
    tellTheExtensions(call, processInstanceKey, SuspendedWorkflowFound.Phase.CALL);

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
   * <p>
   * The extensions hear about it on every attempt. They filter out the repetitions themselves.
   *
   * @param kindOfTask <code>task</code> or <code>user task</code>, for the message
   * @param call The call the entry carries
   * @param processInstanceKey The suspended process instance of the task
   * @param operationDescription What waits, for the message
   * @return The failure to throw
   */
  PhaseTwoRetryLater waitInTheOutbox(
      final String kindOfTask,
      final TheCall call,
      final long processInstanceKey,
      final String operationDescription) {

    final var taskId = call.taskId();
    tellTheExtensions(call, processInstanceKey, SuspendedWorkflowFound.Phase.WAITING);

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

  /**
   * Tells the extensions through the core. Nothing of it may come out: the caller throws what
   * it would have thrown anyway. The core already catches what an extension throws, so this
   * catch is only the net for a value the record refuses.
   */
  private void tellTheExtensions(
      final TheCall call,
      final long processInstanceKey,
      final SuspendedWorkflowFound.Phase phase) {

    try {
      reporter
          .found(new SuspendedWorkflowFound(
              adapterId, call.workflowModuleId(), call.bpmnProcessId(), call.workflowAggregateId(), Long
                  .toString(processInstanceKey), call.taskId(), Instant.now(), phase));
    } catch (final RuntimeException e) {
      log.warn(
          "Camunda8[{}]: could not tell the extensions that the workflow of task '{}' is suspended",
          adapterId,
          call.taskId(),
          e);
    }

  }

}
