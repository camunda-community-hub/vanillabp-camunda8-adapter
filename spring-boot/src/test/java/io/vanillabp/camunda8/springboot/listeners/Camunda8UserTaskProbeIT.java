package io.vanillabp.camunda8.springboot.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.springboot.SpringBootTestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.TaskEvent;

/**
 * Three user tasks of one workflow, one of them completed behind VanillaBP's back, and what
 * the check of the other open tasks makes of the three.
 * <p>
 * A user task the engine manages is completed by whoever holds it - a task list, a form, an
 * operator - and VanillaBP hears nothing when that happens outside its own
 * {@code completeUserTask}. Its record of the task stays open, and
 * {@code probe-open-user-tasks} is what closes it: an empty {@code UpdateUserTask} per user
 * task of an instance which is still running, {@code 404} for the one which is gone.
 * <p>
 * The three tasks differ in one thing only, which is the whole point:
 * <ul>
 * <li>the PLAIN one carries no listener, is completed through the raw client and has to be
 * reported as <code>CANCELED</code> at the next wake-up;</li>
 * <li>the SERVED one carries an <code>updating</code> listener a method of this application
 * serves. It stays open while the plain one is reported, so every wake-up in between probes it
 * and fires that listener, and the adapter recognises the job by its action and its empty change
 * list and completes it without the method running - which is what
 * {@link UserTaskProbeDockerWorkflowService#UPDATES_THE_APPLICATION_SAW} holds at zero. It is
 * completed at the end and reported as gone, which is what says those probes really reached
 * it;</li>
 * <li>the FOREIGN one carries an <code>updating</code> listener of a job type nothing here
 * subscribes to. No probe is sent for it at all, so it is completed through the raw client
 * like the plain one and is still NOT reported, because the adapter cannot say.</li>
 * </ul>
 * The wake-up is the redelivery of the asynchronous task's job, whose lock this class sets to
 * three seconds.
 * <p>
 * Every user task here is delivered through its <code>creating</code> listener job. The REST
 * gateway of the 8.10 alphas dropped the batch those arrive in, and without the CREATED
 * notification there is no delivery record to probe, so this class was kept off the preview
 * line. {@code 8.10.0-rc1} hands the job out and it runs there again.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(
    classes = ListenerTestApplication.class,
    properties = {
        "spring.config.name=camunda8-listeners-it",
        // the question this test is about, off everywhere else
        "vanillabp.adapters.c8.probe-open-user-tasks=true",
        // the job of the asynchronous task goes back to the cluster after three seconds,
        // and that redelivery is the wake-up the check rides in on
        "vanillabp.adapters.c8.async-task-lock-renewal=PT3S"
    })
public class Camunda8UserTaskProbeIT extends SpringBootTestOnTheSharedCluster {

  @Autowired
  private UserTaskProbeDockerWorkflowService workflowService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @Test
  @DisplayName("A user task completed elsewhere is reported, and a task nobody may probe is not")
  public void aUserTaskCompletedElsewhereIsReported() throws Exception {

    UserTaskProbeDockerWorkflowService.UPDATES_THE_APPLICATION_SAW.set(0);
    UserTaskProbeDockerWorkflowService.TASK_IDS.clear();
    UserTaskProbeDockerWorkflowService.WHAT_HAPPENED.clear();
    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow().getId());
    assertNotNull(aggregateId);

    awaitUntil(
        () -> (whatHappenedTo(aggregateId, "theServedUserTask") != null) && (whatHappenedTo(aggregateId,
            "thePlainUserTask") != null) && (whatHappenedTo(aggregateId,
                "theForeignUserTask") != null) && (whatHappenedTo(aggregateId, "keepTheWorkflowAwake") != null),
        120000,
        "all three user tasks and the asynchronous task to be open at once");

    // completed through the raw client, which is what a task list does: VanillaBP's own
    // 'canceling' listener does not fire for a completion, so nothing tells the application
    completeThroughTheCluster(taskIdOf(aggregateId, "thePlainUserTask"));
    completeThroughTheCluster(taskIdOf(aggregateId, "theForeignUserTask"));

    awaitUntil(
        () -> TaskEvent.Event.CANCELED.name().equals(whatHappenedTo(aggregateId, "thePlainUserTask")),
        120000,
        "the next wake-up to report the user task which is gone");

    assertEquals(
        TaskEvent.Event.CREATED.name(),
        whatHappenedTo(aggregateId, "theForeignUserTask"),
        "the task whose updating listener nobody here serves is never probed, so nothing is derived "
            + "about it - a probe there would have left it standing in UPDATING for fifteen seconds");
    assertEquals(
        TaskEvent.Event.CREATED.name(),
        whatHappenedTo(aggregateId, "theServedUserTask"),
        "and the task which is still open answers the probe with 204");
    assertEquals(
        0,
        UserTaskProbeDockerWorkflowService.UPDATES_THE_APPLICATION_SAW.get(),
        "the probe fires the modelled 'updating' listener, and the adapter closes that job by its "
            + "action and its empty change list - no application method runs because somebody asked "
            + "whether a task is still there");

    // the served task is completed LAST, and only now, because up to here it had to be
    // alive: that is what made every wake-up above probe it, fire its 'updating' listener
    // and close that job. A task which is never probed would leave the counter at zero
    // just the same, so this is the half which says the probe really reached it
    completeThroughTheCluster(taskIdOf(aggregateId, "theServedUserTask"));
    awaitUntil(
        () -> TaskEvent.Event.CANCELED.name().equals(whatHappenedTo(aggregateId, "theServedUserTask")),
        120000,
        "the user task whose updating listener this application serves to be reported as gone");
    assertEquals(
        0,
        UserTaskProbeDockerWorkflowService.UPDATES_THE_APPLICATION_SAW.get(),
        "and not one of all those probes ever reached the application's method");

  }

  private static String taskIdOf(
      final Long aggregateId,
      final String formReference) {

    return UserTaskProbeDockerWorkflowService.TASK_IDS.get(aggregateId
        + "/"
        + formReference);

  }

  private static String whatHappenedTo(
      final Long aggregateId,
      final String formReference) {

    return UserTaskProbeDockerWorkflowService.WHAT_HAPPENED.get(aggregateId
        + "/"
        + formReference);

  }

  /**
   * Completes a user task the way somebody outside VanillaBP does, waiting out a task the
   * cluster is busy with.
   * <p>
   * The probe this test switches on is an <code>UpdateUserTask</code>, and a task under
   * update stands in state <code>UPDATING</code> until the update and its listener job are
   * through. Measured on 8.9.19: 60 to 145 milliseconds per probe of the served task, and
   * every command against the task is answered with HTTP <code>409</code> for that long.
   * The probes are densest right after the instance starts, which is where this test sends
   * its completions, and a run of the pull request for story 419 was refused there.
   * <p>
   * The adapter waits the same window out wherever it sends such a command itself, see
   * {@code Camunda8CommandRetry#sendWhileTheUserTaskIsStillChanging}. A task list has no
   * such helper and pays the <code>409</code>, so the test which plays the task list waits
   * here.
   */
  private void completeThroughTheCluster(
      final String userTaskKey) throws InterruptedException {

    assertNotNull(userTaskKey, "the user task has to be known before it can be completed elsewhere");
    final var deadline = System.currentTimeMillis() + 10000;
    while (true) {
      try {
        clientFactoryRegistry
            .getFactory("c8")
            .getClient()
            .newCompleteUserTaskCommand(Long.parseLong(userTaskKey))
            .send()
            .join();
        return;
      } catch (final RuntimeException e) {
        if (!Camunda8Errors.refusedAboutAUserTaskItHolds(e) || (System.currentTimeMillis() > deadline)) {
          throw e;
        }
        Thread.sleep(100);
      }
    }

  }

  private static void awaitUntil(
      final Supplier<Boolean> condition,
      final long milliseconds,
      final String what) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + milliseconds;
    while (System.currentTimeMillis() < deadline) {
      if (Boolean.TRUE.equals(condition.get())) {
        return;
      }
      Thread.sleep(500);
    }
    fail("waited "
        + (milliseconds / 1000)
        + " seconds for "
        + what
        + ", and it never happened");

  }

}
