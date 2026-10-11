package io.vanillabp.camunda8.wiring;

import java.time.Duration;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.camunda.client.CamundaClient;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.client.Camunda8InstanceProbe;
import io.vanillabp.camunda8.client.Camunda8UserTaskProbe;
import io.vanillabp.integration.adapter.spi.workflowtask.OpenTaskProbe;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskExistence;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks the cluster whether the other tasks of a workflow are still there, every time it hands
 * this application a job.
 * <p>
 * Zeebe tells a worker nothing about a job it took away. So an application on this BPMS learns
 * nothing on its own. Take an asynchronous task whose element an interrupting boundary event
 * removed: it stays open in the record of VanillaBP forever. The core drives the check.
 * The list, the budget, the transaction and the <code>CANCELED</code> delivery are all its
 * business. This class answers the one question an adapter can answer, for one task at a
 * time.
 * <p>
 * It is called from the three handlers which are a wake-up of a workflow:
 * <ul>
 * <li>the job handler, after the outcome went back to the cluster,</li>
 * <li>the user-task listener handler, after the listener job was completed,</li>
 * <li>the handler of the listeners somebody modelled, inside its listener work.</li>
 * </ul>
 * The user-task one waits for the completion. That completion ends state
 * <code>CREATING</code> of the task the application was just told about. And a task in
 * that state refuses the completion which the application may already have sent.
 * <p>
 * It is not called from the handler which reports the end of a workflow. The end is a
 * stronger statement than a job, and the core already derives the same cancelations from
 * it. It is not called from the handler which starts an instance either. An instance which
 * has just started has nothing else open.
 *
 * <h2>The instance is asked first</h2>
 *
 * Every record the core asks about belongs to the ONE process instance of the wake-up. So the
 * cheapest question comes first: does the ENGINE still hold that instance? It is one command
 * of one to two milliseconds. The engine refuses it and does not carry it out. Which of the
 * two refusals is sent follows the release line and the business id, see
 * {@link Camunda8InstanceProbe} and decision 35 in the repository's DECISIONS.md. A
 * <code>404</code> answers the whole list at once, because an instance the engine has
 * forgotten holds nothing open. The answer is remembered for the rest of that wake-up. So
 * twenty records cost one command.
 * <p>
 * The core makes sure there is only one instance. It reads the open tasks OF ONE WORKFLOW and
 * asks about those. So a record naming another workflow id would mean the core changed its
 * mind. It is answered with {@link TaskExistence#CANNOT_SAY}, not guessed at. Everything below
 * reads the BPMN process of the wake-up. Its model is the one checked for the element the
 * instance probe reserves. That reading only holds while there is only one instance.
 * <p>
 * The <code>404</code> cannot tell an ended workflow from one which never existed. It does
 * not have to here. The question is whether a task is still open, and both answers mean it
 * is not. That reading stays inside this check, for the reason decision 35 gives.
 * <p>
 * And the <code>404</code> does not come early. A cancellation is not the end of an instance.
 * An instance with a Camunda-managed user task waits for the <code>canceling</code> listener
 * job of that task. The engine answers <code>404</code> to a second cancellation within
 * milliseconds of the first. The probe does NOT do the same. Measured on 2026-09-27 inside
 * exactly that window, on all three lines: it answers <code>400</code> for as long as the
 * engine holds the instance, and <code>404</code> only once the listener job was answered.
 * The user-task probe below answers <code>409</code> in the same window.
 * {@code Camunda8ProbeWhileAnInstanceTerminatesIT} holds the window open and reads both.
 *
 * <h2>Then the task, and what kind of task decides how</h2>
 *
 * <b>A job.</b> <code>UpdateJobTimeout</code> for the task the record names. The adapter
 * sends this command for every open asynchronous task anyway. A 404 or a gRPC
 * <code>NOT_FOUND</code> is gone. Everything else is "cannot say", including the 400 which
 * says the cluster holds the job and nobody has it activated right now: that answer means the
 * task is alive.
 * <p>
 * <b>A Camunda-managed user task.</b> Its key is not a job key. Handed to a job command, it
 * answers <code>NOT_FOUND</code> for as long as the task is open. Read as gone, that would
 * cancel a task the cluster is offering to somebody. It holds the other way round as well.
 * {@code Camunda8ProbeOfAnOpenUserTaskIT} measured both on 2026-09-28 against 8.8.39, 8.9.21
 * and 8.10.0-rc1. Each command answered <code>404</code> for a full minute about a key of the
 * other kind. All that time the task was open, and the engine said it holds the instance. So
 * the kind of key a record carries is the whole question. {@link KindOfTask} is there for it.
 * For a user task, the empty update of {@link Camunda8UserTaskProbe} gives the answer. It is
 * sent only where {@code probe-open-user-tasks} asked for it, and only where the instance
 * said it is still there. Without the key, the answer is {@link TaskExistence#CANNOT_SAY}.
 * That costs an application nothing. VanillaBP writes a <code>canceling</code> task listener
 * next to every user task it manages, and the cluster delivers <code>CANCELED</code> straight
 * from there.
 * <p>
 * <b>Anything this adapter cannot tell apart.</b> A BPMN process which this application
 * declares without deploying a model has no model to read. So a record of it may name either
 * kind. And a record which kept no task definition names nothing to look up. So it counts as
 * the ambiguous case wherever its process holds a user task at all. Both are answered with
 * {@link TaskExistence#CANNOT_SAY}, because a wrong "gone" cancels work the cluster is waiting
 * on. A workflow the check says nothing about still hears about a task which went away: at
 * the end of the workflow, and with the next operation which names the task.
 * <p>
 * It is not {@code Camunda8ProcessService#awarenessOfTask}, on purpose. That one first asks
 * which scope a key belongs to. That is a round trip to the query API on a shared cluster,
 * and this check does not need it. The record names the adapter which delivered the task, and
 * the core drops a record of another adapter before anything is sent. And it folds two
 * answers into one. Its <code>UNKNOWN_TO_BPMS</code> means both "the cluster says 404" and
 * "this task is not from my adapter". Reading the second as a cancelation would report a
 * living task as canceled.
 */
@Slf4j
public class Camunda8OpenTaskProbe {

  /**
   * What kind of task a delivery record of this workflow module names. It decides which
   * command can ask about it.
   */
  public enum KindOfTask {
    /**
     * A job: an asynchronous service task, or any other element this adapter serves with a
     * worker. Its id is a job key.
     */
    A_JOB,
    /**
     * A user task the engine manages. Its id is a user-task key, which lives in a namespace
     * of its own.
     */
    A_CAMUNDA_MANAGED_USER_TASK,
    /**
     * Neither can be ruled out, or the element carries an <code>updating</code> listener
     * this application does not serve. Nothing is sent for such a record.
     */
    CANNOT_TELL
  }

  private final String adapterId;

  private final String workflowModuleId;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * The client of this workflow module. It is asked for each time and not held. The workers
   * are opened with the client of the run, and a probe must use the same one.
   */
  private final Supplier<CamundaClient> client;

  /**
   * The configuration of this adapter id, asked the same way and for the same reason. It
   * says whether a user task gets a probe of its own and what the business id of an
   * instance carries.
   */
  private final Supplier<Camunda8AdapterConfiguration> configuration;

  /**
   * What the probe writes as the new deadline of the job. It is the same value every other
   * <code>UpdateJobTimeout</code> of this adapter writes. The command moves the deadline of a
   * job somebody else is holding. With a value of its own, a probe would shorten or extend a
   * lock behind the back of that holder.
   */
  private final Duration asyncTaskLockRenewal;

  /**
   * What a record of that PLAIN BPMN process id and PLAIN task definition names. It is read
   * from the models of this workflow module at deployment.
   */
  private final BiFunction<String, String, KindOfTask> theKindOfTaskARecordNames;

  /**
   * Whether a model of that PLAIN BPMN process id carries the element the instance probe
   * reserved. Where one does, the modification would ACTIVATE that element and would not be
   * refused. That is a change to a running workflow nobody asked for. So the instance is not
   * asked about at all.
   */
  private final Predicate<String> aModelCarriesTheReservedProbeElement;

  /**
   * The check of one workflow module.
   *
   * @param adapterId The adapter whose workers deliver the wake-ups
   * @param workflowModuleId The workflow module
   * @param workflowTaskInvoker The runtime entry point of the core, or <code>null</code> to
   *          ask nothing
   * @param client The client of this workflow module
   * @param configuration The configuration of this adapter id
   * @param asyncTaskLockRenewal What a job probe writes as the new deadline of the job
   * @param theKindOfTaskARecordNames What a record of a process and a task definition names
   * @param aModelCarriesTheReservedProbeElement Whether a model of that process carries the
   *          element the instance probe reserved
   */
  public Camunda8OpenTaskProbe(
      final String adapterId,
      final String workflowModuleId,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final Supplier<CamundaClient> client,
      final Supplier<Camunda8AdapterConfiguration> configuration,
      final Duration asyncTaskLockRenewal,
      final BiFunction<String, String, KindOfTask> theKindOfTaskARecordNames,
      final Predicate<String> aModelCarriesTheReservedProbeElement) {

    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;
    this.workflowTaskInvoker = workflowTaskInvoker;
    this.client = client;
    this.configuration = configuration;
    this.asyncTaskLockRenewal = asyncTaskLockRenewal;
    this.theKindOfTaskARecordNames = theKindOfTaskARecordNames;
    this.aModelCarriesTheReservedProbeElement = aModelCarriesTheReservedProbeElement;

  }

  /**
   * Hands the core the wake-up it just served, so it can look at the other tasks it believes
   * are open in the same workflow.
   * <p>
   * Nothing here may cost the delivery which led to it. The job is answered by the time this
   * runs. A check which threw would turn a finished job into a failed one. So every failure
   * ends here with one line.
   *
   * @param bpmnProcessId The BPMN process of the delivery, as the application knows it
   * @param wakeUp The context of that delivery
   */
  public void reportWhatTheClusterNoLongerHas(
      final String bpmnProcessId,
      final TaskInvocationContext wakeUp) {

    if ((workflowTaskInvoker == null) || (wakeUp == null)) {
      return;
    }
    try {
      // what the engine answered about the instance of this wake-up, for as long as the
      // wake-up is being served. It lives here and not on the class, because it is only true
      // while the cluster is being asked. A workflow which was there a minute ago says
      // nothing about the workflow of the next job.
      final var theInstance = new TheInstanceOfThisWakeUp(
          bpmnProcessId, wakeUp.getWorkflowId(), wakeUp.getWorkflowAggregateId());
      // every record the core asks about belongs to that one instance. So they are all
      // tasks of the BPMN process of this wake-up. What differs per record is the task
      // definition, and the probe below reads it.
      workflowTaskInvoker
          .reportTasksTheBpmsNoLongerHas(workflowModuleId, bpmnProcessId, wakeUp, new OpenTaskProbe() {

            @Override
            public TaskExistence stillExists(
                final String workflowId,
                final String taskId) {

              // the core asks the three-argument method. So only a caller which holds no
              // task definition reaches this one. That is the ambiguous case.
              return stillExists(workflowId, taskId, null);

            }

            @Override
            public TaskExistence stillExists(
                final String workflowId,
                final String taskId,
                final String taskDefinition) {

              return Camunda8OpenTaskProbe.this
                  .stillExists(theInstance, workflowId, taskId, taskDefinition);

            }

          });
    } catch (final RuntimeException e) {
      log
          .debug(
              "Camunda8[{}]: looking at the other open tasks of the workflow of BPMN process '{}' "
                  + "failed - the delivery which led here is done either way, and the next wake-up "
                  + "of that workflow looks again",
              adapterId,
              bpmnProcessId,
              e);
    }

  }

  /**
   * Whether the cluster still has the task of that id.
   *
   * @param theInstance What the engine already said about the instance of this wake-up
   * @param workflowId The process instance key the delivery record kept
   * @param taskId The job key or the user-task key the delivery record kept
   * @param taskDefinition The task definition of the record, or <code>null</code> where it
   *          kept none
   * @return What the cluster said
   */
  TaskExistence stillExists(
      final TheInstanceOfThisWakeUp theInstance,
      final String workflowId,
      final String taskId,
      final String taskDefinition) {

    if (taskId == null) {
      return TaskExistence.CANNOT_SAY;
    }
    if (!theInstance.isTheOneOfThisWakeUp(workflowId)) {
      // the core asks about the open tasks of ONE workflow, so this cannot happen. If it
      // ever does, every answer below reads the wrong process id. Guessing with it could
      // modify a workflow nobody asked about.
      return TaskExistence.CANNOT_SAY;
    }
    final var instance = theInstance.answer();
    if (instance == TaskExistence.GONE) {
      // an instance the engine has forgotten holds nothing open, whatever kind of task
      // the record names. That makes this one command the answer to the whole list, and
      // not the first of many.
      return TaskExistence.GONE;
    }
    final var kind = theKindOfTaskARecordNames.apply(theInstance.bpmnProcessId(), taskDefinition);
    if (kind == KindOfTask.CANNOT_TELL) {
      return TaskExistence.CANNOT_SAY;
    }
    if (kind == KindOfTask.A_CAMUNDA_MANAGED_USER_TASK) {
      return theUserTaskStillExists(instance, taskId);
    }
    return theJobStillExists(taskId);

  }

  /**
   * Whether the cluster still holds the JOB of that key.
   */
  private TaskExistence theJobStillExists(
      final String taskId) {

    final var key = keyOf(taskId);
    if (key == null) {
      return TaskExistence.CANNOT_SAY;
    }
    try {
      client
          .get()
          .newUpdateTimeoutCommand(key)
          .timeout(asyncTaskLockRenewal)
          .send()
          .join();
      return TaskExistence.STILL_THERE;
    } catch (final Exception e) {
      if (Camunda8Errors.jobIsThereButNotActive(e)) {
        // the cluster holds the job and nobody has it activated right now. That is how
        // an asynchronous task whose lock ran out looks.
        return TaskExistence.STILL_THERE;
      }
      if (Camunda8Errors.notFound(e)) {
        return TaskExistence.GONE;
      }
      log
          .debug(
              "Camunda8[{}]: the cluster did not say whether it still has task '{}' - it answered {}",
              adapterId,
              taskId,
              Camunda8Errors.rejection(e));
      return TaskExistence.CANNOT_SAY;
    }

  }

  /**
   * Whether the cluster still holds the USER TASK of that key.
   * <p>
   * Two things have to be true before the command is sent. First, the instance has to be
   * there. A user task of an instance the engine no longer holds was answered above. Second,
   * the application has to have asked for the question. The empty update costs a command per
   * task, and on the way it fires a modelled <code>updating</code> listener.
   * <p>
   * The command does not return until that listener job has been answered. Where this
   * application serves the listener, the answer comes from one of its own execution slots. So
   * this call holds the slot it runs on, while a second one serves the job. With the usual
   * four slots there is room for both. With a single one, the fifteen seconds of the cluster
   * pass and the answer is "cannot say". That harms no task. It only makes the wake-up
   * slow.
   */
  private TaskExistence theUserTaskStillExists(
      final TaskExistence instance,
      final String taskId) {

    if (!configuration.get().isProbeOpenUserTasks()) {
      return TaskExistence.CANNOT_SAY;
    }
    if (instance != TaskExistence.STILL_THERE) {
      // the engine did not say the instance is there. If nobody could ask about the
      // instance, no second question is sent about its task.
      return TaskExistence.CANNOT_SAY;
    }
    final var key = keyOf(taskId);
    if (key == null) {
      return TaskExistence.CANNOT_SAY;
    }
    try {
      Camunda8UserTaskProbe.askTheEngine(client.get(), key);
      return TaskExistence.STILL_THERE;
    } catch (final Exception e) {
      if (Camunda8Errors.notFound(e)) {
        return TaskExistence.GONE;
      }
      if (Camunda8Errors.refusedAboutAUserTaskItHolds(e)) {
        // the cluster has a task standing in UPDATING, and also a task whose listener
        // denied the update
        return TaskExistence.STILL_THERE;
      }
      log
          .debug(
              "Camunda8[{}]: the cluster did not say whether it still has user task '{}' - it "
                  + "answered {}",
              adapterId,
              taskId,
              Camunda8Errors.rejection(e));
      return TaskExistence.CANNOT_SAY;
    }

  }

  /**
   * Whether the ENGINE still holds that process instance.
   */
  private TaskExistence theInstanceStillExists(
      final String bpmnProcessId,
      final String workflowId,
      final String workflowAggregateId) {

    if ((workflowId == null) || workflowId.isBlank()) {
      return TaskExistence.CANNOT_SAY;
    }
    if (aModelCarriesTheReservedProbeElement.test(bpmnProcessId)) {
      return TaskExistence.CANNOT_SAY;
    }
    final var key = keyOf(workflowId);
    if (key == null) {
      return TaskExistence.CANNOT_SAY;
    }
    try {
      // nothing about a shared cluster is asked here, unlike in the probe of the election.
      // The record names the adapter which delivered the task. The core drops a record of
      // another adapter before this is reached. So the instance asked about belongs to this
      // adapter.
      Camunda8InstanceProbe
          .askTheEngine(
              client.get(),
              key,
              Camunda8TaskWiring.RESERVED_PROBE_ELEMENT_ID,
              configuration.get().businessIdOf(workflowAggregateId));
      return TaskExistence.STILL_THERE;
    } catch (final Exception e) {
      if (Camunda8Errors.notFound(e)) {
        return TaskExistence.GONE;
      }
      if (Camunda8Errors.refusedAboutAnInstanceItHolds(e)) {
        return TaskExistence.STILL_THERE;
      }
      log
          .debug(
              "Camunda8[{}]: the engine did not say whether it still holds workflow '{}' - it "
                  + "answered {}",
              adapterId,
              workflowId,
              Camunda8Errors.rejection(e));
      return TaskExistence.CANNOT_SAY;
    }

  }

  /**
   * A key of this cluster as a number, or <code>null</code> where the id is none. A record
   * naming something else was written by another BPMS, and the core should not have asked.
   * Saying so is still cheaper than guessing.
   */
  private static Long keyOf(
      final String id) {

    try {
      return Long.valueOf(id);
    } catch (final NumberFormatException e) {
      return null;
    }

  }

  /**
   * What the engine answered about the ONE process instance a wake-up belongs to.
   * <p>
   * It exists so that a list of twenty records of that workflow costs one instance command
   * and not twenty. It is no cache beyond that. It is created per call and thrown away with
   * it, because the answer is about a moment.
   */
  final class TheInstanceOfThisWakeUp {

    private final String bpmnProcessId;

    private final String workflowId;

    private final String workflowAggregateId;

    private TaskExistence answer;

    TheInstanceOfThisWakeUp(
        final String bpmnProcessId,
        final String workflowId,
        final String workflowAggregateId) {

      this.bpmnProcessId = bpmnProcessId;
      this.workflowId = workflowId;
      this.workflowAggregateId = workflowAggregateId;

    }

    String bpmnProcessId() {

      return bpmnProcessId;

    }

    /**
     * Whether a record naming that workflow id belongs to the instance of this wake-up.
     *
     * @param recordWorkflowId What the delivery record kept
     * @return Whether the two are the same workflow
     */
    boolean isTheOneOfThisWakeUp(
        final String recordWorkflowId) {

      return (workflowId != null) && workflowId.equals(recordWorkflowId);

    }

    /**
     * What the engine says about this workflow, asked once.
     *
     * @return What the engine said
     */
    TaskExistence answer() {

      if (answer == null) {
        answer = theInstanceStillExists(bpmnProcessId, workflowId, workflowAggregateId);
      }
      return answer;

    }

  }

}
