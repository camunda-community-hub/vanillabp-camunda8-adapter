package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.command.JobChangeset;
import io.camunda.client.api.response.ActivatedJob;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.client.Camunda8InstanceProbe;
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.camunda8.client.Camunda8UserTaskProbe;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What every way of asking about an OPEN user task answers - the case of a task nobody is
 * cancelling, which is the one story 643 never looked at.
 *
 * <h2>Why this class exists</h2>
 *
 * Story 643 held the window of a running cancellation open and read the probes inside it.
 * Its conclusion was that the probe has no hole. Stephan then reported the opposite from
 * production: a user task the Business Cockpit shows as active while a probe answers
 * {@code 404}. His application runs VanillaBP 1, and version 1 asked a different question
 * than version 2 does.
 * <p>
 * So this class asks every question about the same open task and writes down what came back:
 * the empty {@code UpdateUserTask} version 2 sends, the {@code UserTaskGet} version 1 sent,
 * the user-task search, the job command, and the instance probe which says whether the
 * workflow is there at all. Two shapes of task are measured, because version 1 served both
 * and the shape decides which key an application holds.
 *
 * <h2>The two shapes, and why the key decides</h2>
 *
 * A Camunda-managed user task ({@code zeebe:userTask}) has a USER-TASK key. A job-worker user
 * task - a plain BPMN user task, which is what VanillaBP 1 served up to its release 1.6.3 -
 * has a JOB key. The two live in namespaces of their own, so a command of the one kind
 * answers {@code 404} for a key of the other kind however open the task is. Version 2 serves
 * the managed shape only: it ends the boot over the other one where a workflow service claims
 * the process and names it where nobody does, which is why the models here are deployed with
 * the raw client. Version 1 served both, and its {@code UserTaskGet} was sent for either.
 *
 * <h2>What an application knows before the index does</h2>
 *
 * VanillaBP learns of a user task from its {@code creating} task listener, a job the partition
 * hands out before the task is even in state {@code CREATED}. The Business Cockpit's record is
 * written from there. The index an exporter feeds can only show the task afterwards. So the
 * first reading below starts at the moment the {@code creating} job arrived - the moment the
 * cockpit knows - and asks every question from there until each of them answers.
 * <p>
 * Nothing of VanillaBP is in the way: the models are deployed with the raw client and their
 * listeners carry job types no worker of this repository subscribes to. The class ends what it
 * started, which is what leaves the shared cluster the way it found it.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8ProbeOfAnOpenUserTaskIT extends TestOnTheSharedCluster {

  /**
   * The job type of the {@code creating} listener of the managed task. Nothing in this
   * repository subscribes to it, so this test is the party the task waits for and the moment
   * the job arrives is a moment the test knows.
   */
  private static final String CREATING_JOB_TYPE = "theCreatingJobOnlyThisTestAnswers";

  /**
   * The job type a job-worker user task is served with, which is the shape VanillaBP 1
   * served up to release 1.6.3. It is Camunda's own constant and not a VanillaBP one.
   */
  private static final String JOB_WORKER_USER_TASK_JOB_TYPE = "io.camunda.zeebe:userTask";

  private static final String MANAGED_PROCESS_ID = "ProbeOfAnOpenManagedUserTask";

  private static final String LEGACY_PROCESS_ID = "ProbeOfAnOpenJobWorkerUserTask";

  private static final String MANAGED_MODEL = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
          id="Definitions_ProbeOfAnOpenManagedUserTask" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="%s" isExecutable="true">
          <bpmn:startEvent id="OpenManagedStart">
            <bpmn:outgoing>OpenManagedFlow_1</bpmn:outgoing>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="OpenManagedFlow_1" sourceRef="OpenManagedStart" targetRef="OpenManagedTask" />
          <bpmn:userTask id="OpenManagedTask" name="the open user task the engine manages">
            <bpmn:extensionElements>
              <zeebe:userTask />
              <zeebe:taskListeners>
                <zeebe:taskListener eventType="creating" type="%s" />
              </zeebe:taskListeners>
            </bpmn:extensionElements>
            <bpmn:incoming>OpenManagedFlow_1</bpmn:incoming>
            <bpmn:outgoing>OpenManagedFlow_2</bpmn:outgoing>
          </bpmn:userTask>
          <bpmn:sequenceFlow id="OpenManagedFlow_2" sourceRef="OpenManagedTask" targetRef="OpenManagedEnd" />
          <bpmn:endEvent id="OpenManagedEnd">
            <bpmn:incoming>OpenManagedFlow_2</bpmn:incoming>
          </bpmn:endEvent>
        </bpmn:process>
      </bpmn:definitions>
      """.formatted(MANAGED_PROCESS_ID, CREATING_JOB_TYPE);

  /**
   * A plain BPMN user task: no {@code zeebe:userTask}, so the cluster serves it with a job of
   * {@value #JOB_WORKER_USER_TASK_JOB_TYPE} and what an application holds is a job key. That
   * is the shape VanillaBP 1 served up to release 1.6.3, and the shape whose key no
   * user-task command can be asked about.
   */
  private static final String LEGACY_MODEL = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
          id="Definitions_ProbeOfAnOpenJobWorkerUserTask" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="%s" isExecutable="true">
          <bpmn:startEvent id="OpenLegacyStart">
            <bpmn:outgoing>OpenLegacyFlow_1</bpmn:outgoing>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="OpenLegacyFlow_1" sourceRef="OpenLegacyStart" targetRef="OpenLegacyTask" />
          <bpmn:userTask id="OpenLegacyTask" name="the open user task a job worker serves">
            <bpmn:incoming>OpenLegacyFlow_1</bpmn:incoming>
            <bpmn:outgoing>OpenLegacyFlow_2</bpmn:outgoing>
          </bpmn:userTask>
          <bpmn:sequenceFlow id="OpenLegacyFlow_2" sourceRef="OpenLegacyTask" targetRef="OpenLegacyEnd" />
          <bpmn:endEvent id="OpenLegacyEnd">
            <bpmn:incoming>OpenLegacyFlow_2</bpmn:incoming>
          </bpmn:endEvent>
        </bpmn:process>
      </bpmn:definitions>
      """.formatted(LEGACY_PROCESS_ID);

  /**
   * How long a listener job is leased for. Long enough for the readings below and short
   * enough that a run which breaks off does not leave the cluster holding a task for
   * minutes.
   */
  private static final Duration A_LISTENER_JOB_IS_HELD_FOR = Duration.ofMinutes(2);

  /**
   * How long the loop keeps asking the same question before it writes down that the answer
   * never came. Generous: a loaded machine's exporter is what is being measured here.
   */
  private static final Duration EVERY_QUESTION_IS_ASKED_FOR = Duration.ofSeconds(60);

  private static final long BETWEEN_TWO_READINGS_MILLIS = 50;

  /**
   * How long the index is given to hold what the engine has already done, for the one reading
   * which is about the index rather than about the engine.
   */
  private static final Duration THE_INDEX_IS_GIVEN_THIS_LONG = Duration.ofSeconds(5);

  /**
   * How often a question which already answered is repeated, so what is written down is the
   * answer the cluster keeps giving rather than the one it happened to give first.
   */
  private static final int READINGS_OF_A_SETTLED_ANSWER = 5;

  @Test
  @DisplayName("Every way of asking about an open Camunda-managed user task, from the moment an application knows of it")
  public void theOpenManagedUserTaskIsAnsweredByTheEngineAndNotByTheIndex(
      final TestReporter reporter) throws Exception {

    final var measured = new ArrayList<String>();

    try (final var client = client()) {

      client
          .newDeployResourceCommand()
          .addResourceStringUtf8(MANAGED_MODEL, "probe-of-an-open-managed-user-task.bpmn")
          .send()
          .join();
      final var instanceKey = client
          .newCreateInstanceCommand()
          .bpmnProcessId(MANAGED_PROCESS_ID)
          .latestVersion()
          .send()
          .join()
          .getProcessInstanceKey();

      // the moment an application - and with it the cockpit's record - learns of the task:
      // the 'creating' listener job, handed out by the partition before the task is CREATED
      final var creatingJob = awaitTheListenerJob(client, CREATING_JOB_TYPE);
      final var theCockpitKnowsSince = System.nanoTime();
      final var userTaskKey = creatingJob.getUserTask().getUserTaskKey();
      measured.add("the 'creating' listener job named user task "
          + userTaskKey);

      // answered at once, so the task leaves CREATING: a task standing in CREATING is
      // refused rather than answered, which is decision 38 and not what is measured here
      Camunda8JobLease
          .withToken(client.newCompleteCommand(creatingJob.getKey()), Camunda8JobLease.tokenOf(creatingJob))
          .send()
          .join();

      // the four questions, each asked from the moment above until it answers
      measured.add(untilItAnswers(
          "the probe of version 2, an empty UpdateUserTask answered by the partition",
          theCockpitKnowsSince,
          () -> Camunda8UserTaskProbe.askTheEngine(client, userTaskKey),
          answer -> !isGone(answer) && !isRefusedAboutATaskItHolds(answer)));
      measured.add(untilItAnswers(
          "the probe of version 1, a UserTaskGet answered by the index",
          theCockpitKnowsSince,
          () -> client.newUserTaskGetRequest(userTaskKey).send().join(),
          answer -> !isGone(answer)));
      measured.add(untilItAnswers(
          "the user-task search, the other read of the index",
          theCockpitKnowsSince,
          () -> {
            if (userTasksOf(client, instanceKey).isEmpty()) {
              throw new IllegalStateException("HTTP 404 - the search holds no user task of this instance");
            }
          },
          answer -> !isGone(answer)));
      measured.add(untilItAnswers(
          "the fallback of version 1, an UpdateJobTimeout on the user-task key",
          theCockpitKnowsSince,
          () -> client
              .newUpdateTimeoutCommand(userTaskKey)
              .timeout(Duration.ofMinutes(10))
              .send()
              .join(),
          answer -> !isGone(answer)));
      measured.add("the instance probe, asked once the readings were taken: "
          + whatTheClusterAnswered(() -> Camunda8InstanceProbe
              .askTheEngine(client, instanceKey, Camunda8TaskWiring.RESERVED_PROBE_ELEMENT_ID, null)));
      // the mirror of the question the adapter asks after a 404: the job side has to say
      // nothing about a USER-TASK key, or the message of that 404 would name a job key for
      // every user task which really is over
      final var theIndexOnAUserTaskKey = whatTheIndexSaid(client, userTaskKey);
      measured.add("the job search by the user-task key: "
          + theIndexOnAUserTaskKey);
      assertTrue(
          "no job of that key".equals(theIndexOnAUserTaskKey),
          "The index answered about a USER-TASK key with a job. The values a 404 message takes "
              + "from it would then belong to a task of the other kind: "
              + String.join("; ", measured));

      // and the probe of version 2 again, several times, so the 204 above is the answer the
      // cluster keeps giving for a task which stays open
      for (var reading = 1; reading <= READINGS_OF_A_SETTLED_ANSWER; ++reading) {
        measured.add("the probe of version 2, repeated reading "
            + reading
            + ": "
            + whatTheClusterAnswered(() -> Camunda8UserTaskProbe.askTheEngine(client, userTaskKey)));
        Thread.sleep(BETWEEN_TWO_READINGS_MILLIS);
      }

      report(reporter, "probe-of-an-open-managed-user-task", measured);

      // What the assertions hold the cluster to. The probe of version 2 may not say "gone"
      // about a task the engine is holding open, because that 404 is what decisions 35 and
      // 38 read as "the task is over"
      measured
          .stream()
          .filter(reading -> reading.startsWith("the probe of version 2"))
          .forEach(reading -> assertFalse(
              isGone(reading),
              "The probe of version 2 answered 404 for a user task which is open and which "
                  + "nobody is cancelling. That is the hole Stephan reported, and it would make the "
                  + "404 of decisions 35 and 38 mean less than those decisions say: an election "
                  + "would call the task gone and the check of the other open tasks would report it "
                  + "as CANCELED. Correct Camunda8ProcessService, Camunda8OpenTaskProbe, the README "
                  + "and the wiki, and replace decisions 35 and 38 rather than rewording them. What "
                  + "was measured: "
                  + String.join("; ", measured)));

      // and the fallback of version 1 is the mirror image: a job command cannot be asked
      // about a user-task key at all, so its 404 is about the namespace and not about the task
      assertTrue(
          measured
              .stream()
              .filter(reading -> reading.startsWith("the fallback of version 1"))
              .allMatch(Camunda8ProbeOfAnOpenUserTaskIT::neverAnswered),
          "A job command answered about a user-task key, which no line did before. The mirror "
              + "sentence of Camunda8OpenTaskProbe - a user-task key handed to a job command answers "
              + "NOT_FOUND for as long as the task is open - has to be re-read: "
              + String.join("; ", measured));

      completeTheManagedTask(client, userTaskKey, instanceKey);

    }

  }

  @Test
  @DisplayName("A user task a job worker serves is open while every user-task command answers 404 about it")
  public void theOpenJobWorkerUserTaskIsUnknownToEveryUserTaskCommand(
      final TestReporter reporter) throws Exception {

    final var measured = new ArrayList<String>();

    try (final var client = client()) {

      final var deployed = whatTheClusterAnswered(() -> client
          .newDeployResourceCommand()
          .addResourceStringUtf8(LEGACY_MODEL, "probe-of-an-open-job-worker-user-task.bpmn")
          .send()
          .join());
      measured.add("the deployment of a plain BPMN user task: "
          + deployed);
      if (!"accepted".equals(deployed)) {
        // a line which refuses the shape has no such task to measure, and that refusal is
        // the finding rather than a failure of this test
        report(reporter, "probe-of-an-open-job-worker-user-task", measured);
        return;
      }

      final var instanceKey = client
          .newCreateInstanceCommand()
          .bpmnProcessId(LEGACY_PROCESS_ID)
          .latestVersion()
          .send()
          .join()
          .getProcessInstanceKey();

      // what an application of version 1 up to release 1.6.3 held: a JOB key
      final var userTaskJob = awaitTheListenerJob(client, JOB_WORKER_USER_TASK_JOB_TYPE);
      final var jobKey = userTaskJob.getKey();
      measured.add("the job of the user task has key "
          + jobKey);

      // the task is open, and the job command is what proves it
      measured.add("the job command about the job key, which is what version 1 fell back to: "
          + whatTheClusterAnswered(() -> client
              .newUpdateTimeoutCommand(jobKey)
              .timeout(A_LISTENER_JOB_IS_HELD_FOR)
              .send()
              .join()));

      // the question the adapter asks itself once a user-task command answered 404: does the
      // cluster hold a JOB of this key? Two commands could answer it and both are read here,
      // first about the job which is open and further down about the same key once the job is
      // gone. What separates a key the cluster holds from one it does not is the whole point,
      // so a command answering 404 for both is no use whatever else it does
      measured.add("the empty UpdateJob on the job key while the task is open: "
          + whatTheClusterAnswered(() -> client
              .newUpdateJobCommand(jobKey)
              .update(new JobChangeset())
              .send()
              .join()));
      measured.add("the job search by that key while the task is open, read at once: "
          + whatTheIndexSaid(client, jobKey));
      Thread.sleep(THE_INDEX_IS_GIVEN_THIS_LONG.toMillis());
      measured.add("the job search by that key while the task is open, read %d ms later: %s"
          .formatted(
              Long.valueOf(THE_INDEX_IS_GIVEN_THIS_LONG.toMillis()),
              whatTheIndexSaid(client, jobKey)));
      measured.add("the instance probe: "
          + whatTheClusterAnswered(() -> Camunda8InstanceProbe
              .askTheEngine(client, instanceKey, Camunda8TaskWiring.RESERVED_PROBE_ELEMENT_ID, null)));

      // and the three ways of asking as if it were a Camunda-managed task
      measured.add(untilItAnswers(
          "the probe of version 1, a UserTaskGet on the job key",
          System.nanoTime(),
          () -> client.newUserTaskGetRequest(jobKey).send().join(),
          answer -> !isGone(answer)));
      measured.add(untilItAnswers(
          "the probe of version 2, an empty UpdateUserTask on the job key",
          System.nanoTime(),
          () -> Camunda8UserTaskProbe.askTheEngine(client, jobKey),
          answer -> !isGone(answer)));
      measured.add(untilItAnswers(
          "the user-task search for this instance",
          System.nanoTime(),
          () -> {
            if (userTasksOf(client, instanceKey).isEmpty()) {
              throw new IllegalStateException("HTTP 404 - the search holds no user task of this instance");
            }
          },
          answer -> !isGone(answer)));

      assertTrue(
          measured
              .stream()
              .filter(reading -> reading.startsWith("the probe of version 1") || reading
                  .startsWith("the probe of version 2"))
              .allMatch(Camunda8ProbeOfAnOpenUserTaskIT::neverAnswered),
          "A user-task command answered about the job key of a job-worker user task. If a line "
              + "does that, the two key namespaces are no longer separate and the reasoning of "
              + "decision 38 about which command may ask about which kind of task needs re-reading: "
              + String.join("; ", measured));

      Camunda8JobLease
          .withToken(client.newCompleteCommand(jobKey), Camunda8JobLease.tokenOf(userTaskJob))
          .send()
          .join();

      // the same two questions about the same key, now that the job really is gone: this is
      // the reading which says whether either of them tells the two cases apart
      final var emptyUpdateJobOnAGoneKey = whatTheClusterAnswered(() -> client
          .newUpdateJobCommand(jobKey)
          .update(new JobChangeset())
          .send()
          .join());
      measured.add("the empty UpdateJob on the job key once the job is gone: "
          + emptyUpdateJobOnAGoneKey);
      final var jobTimeoutOnAGoneKey = whatTheClusterAnswered(() -> client
          .newUpdateTimeoutCommand(jobKey)
          .timeout(A_LISTENER_JOB_IS_HELD_FOR)
          .send()
          .join());
      measured.add("the UpdateJobTimeout on the job key once the job is gone: "
          + jobTimeoutOnAGoneKey);
      measured.add("the job search by that key once the job is gone, read at once: "
          + whatTheIndexSaid(client, jobKey));
      Thread.sleep(THE_INDEX_IS_GIVEN_THIS_LONG.toMillis());
      final var theIndexOnceTheJobWasOver = whatTheIndexSaid(client, jobKey);
      measured.add("the job search by that key once the job is gone, read %d ms later: %s"
          .formatted(
              Long.valueOf(THE_INDEX_IS_GIVEN_THIS_LONG.toMillis()),
              theIndexOnceTheJobWasOver));

      report(reporter, "probe-of-an-open-job-worker-user-task", measured);

      // Camunda8UserTaskProbe#aJobOfThatKeyIsThere asks the job side after a user-task
      // command answered 404, and the whole value of that question is that its 404 means
      // something else than its other answers
      assertTrue(
          isGone(jobTimeoutOnAGoneKey),
          "A job command answered something other than 404 about a job which is over. The job "
              + "side is then no question at all, and the message of a 404 about a user-task key "
              + "may not name a job key: "
              + String.join("; ", measured));
      assertFalse(
          isGone(
              measured
                  .stream()
                  .filter(reading -> reading.startsWith("the job command about the job key"))
                  .findFirst()
                  .orElseThrow()),
          "A job command answered 404 about the job of a user task which was open. Then no "
              + "question can tell a job key from a key nobody holds: "
              + String.join("; ", measured));

      // and why the two questions are split the way Camunda8ProcessService splits them. The
      // index cannot say whether the cluster holds a job: it answered "no job of that key" while
      // this job was activated and open, and it kept answering with the job once the job was over.
      // So the ENGINE is asked whether there is one, and the index only names the element
      assertTrue(
          theIndexOnceTheJobWasOver.contains(JOB_WORKER_USER_TASK_JOB_TYPE),
          "The index said nothing about a job it had seen, so Camunda8UserTaskProbe"
              + "#theJobTheIndexHoldsFor cannot name the element of a job key at all and the "
              + "message of a 404 loses those values: "
              + String.join("; ", measured));
      assertFalse(
          theIndexOnceTheJobWasOver.contains("no job of that key"),
          "The index dropped a job which is over. If it does that, a search would be a way to ask "
              + "whether the cluster holds one, and this adapter asks the engine instead: "
              + String.join("; ", measured));

    }

  }

  /**
   * Asks the same question every {@value #BETWEEN_TWO_READINGS_MILLIS} milliseconds until the
   * answer is one the caller accepts, and writes down how long that took.
   *
   * @param question What is being asked, for the line which is written down
   * @param since The moment the measurement counts from, which is when an application first
   *          knew of the task
   * @param command The question
   * @param answered Whether an answer is the one the question was waiting for
   * @return The line for the record: the first answer, the accepted one and the delay
   *         between the two
   */
  private static String untilItAnswers(
      final String question,
      final long since,
      final Runnable command,
      final Predicate<String> answered) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + EVERY_QUESTION_IS_ASKED_FOR.toMillis();
    var first = (String) null;
    while (System.currentTimeMillis() < deadline) {
      final var answer = whatTheClusterAnswered(command);
      if (first == null) {
        first = answer;
      }
      if (answered.test(answer)) {
        return "%s: first answer %s, answered %s after %d ms".formatted(
            question,
            first,
            answer,
            Long.valueOf(millisSince(since)));
      }
      Thread.sleep(BETWEEN_TWO_READINGS_MILLIS);
    }
    return "%s: answered %s and nothing else within %d ms".formatted(
        question,
        first,
        Long.valueOf(EVERY_QUESTION_IS_ASKED_FOR.toMillis()));

  }

  /**
   * Whether a written-down reading says the question was never answered, which is the shape
   * {@link #untilItAnswers} writes when it ran out of time.
   *
   * @param reading One line of the record
   * @return Whether it is the shape of a question which never answered
   */
  private static boolean neverAnswered(
      final String reading) {

    return reading.contains("and nothing else within");

  }

  /**
   * What the adapter's own reader of the INDEX says about a key - the values
   * {@code Camunda8ProcessService} puts into its message once a user-task command answered
   * {@code 404} and the engine said it holds a job of that key.
   *
   * @param client The raw client of this measurement
   * @param key The key to ask about
   * @return One phrase for the record
   */
  private static String whatTheIndexSaid(
      final CamundaClient client,
      final long key) {

    final var found = Camunda8UserTaskProbe.theJobTheIndexHoldsFor(client, key);
    return found == null
        ? "no job of that key"
        : "a job of type '%s' at element '%s' of process '%s', state %s"
            .formatted(found.type(), found.elementId(), found.bpmnProcessId(), found.state());

  }

  private static long millisSince(
      final long nanos) {

    return (System.nanoTime() - nanos) / 1_000_000;

  }

  /**
   * Whether the cluster said it does not hold what was asked about, on either transport.
   *
   * @param answer What was written down
   * @return Whether it is a 404
   */
  private static boolean isGone(
      final String answer) {

    return answer.contains("404") || answer.contains("NOT_FOUND");

  }

  /**
   * Whether the cluster refused a command ABOUT a task it holds, which is the {@code 409} of
   * a task standing between two of its states. Not an answer this measurement waits for, and
   * not a 404 either.
   *
   * @param answer What was written down
   * @return Whether it is such a refusal
   */
  private static boolean isRefusedAboutATaskItHolds(
      final String answer) {

    return answer.contains("409") || answer.contains("INVALID_STATE");

  }

  /**
   * What the cluster answered, as one phrase - the code of the transport it arrived on for a
   * refusal, and the word this repository uses for a command the cluster carried out.
   *
   * @param command What is sent
   * @return What came back
   */
  private static String whatTheClusterAnswered(
      final Runnable command) {

    try {
      command.run();
      return "accepted";
    } catch (final RuntimeException e) {
      return Camunda8Errors.rejection(e);
    }

  }

  /**
   * Takes one job of the given type, leased for long enough that the readings are not a race
   * against a lock which ran out.
   */
  private static ActivatedJob awaitTheListenerJob(
      final CamundaClient client,
      final String jobType) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline) {
      final var jobs = Camunda8JobLease
          .leaseTheActivation(
              client
                  .newActivateJobsCommand()
                  .jobType(jobType)
                  .maxJobsToActivate(1)
                  .timeout(A_LISTENER_JOB_IS_HELD_FOR))
          .send()
          .join()
          .getJobs();
      if (!jobs.isEmpty()) {
        return jobs.getFirst();
      }
      Thread.sleep(250);
    }
    return fail(
        "no job of type '"
            + jobType
            + "' within 120 seconds, so the cluster never reached the element this measurement is "
            + "about");

  }

  /**
   * Ends the workflow the first measurement started, so the class which runs next finds the
   * cluster the way this one found it.
   */
  private static void completeTheManagedTask(
      final CamundaClient client,
      final long userTaskKey,
      final long instanceKey) {

    final var completed = whatTheClusterAnswered(() -> client
        .newCompleteUserTaskCommand(userTaskKey)
        .send()
        .join());
    if (!"accepted".equals(completed)) {
      client.newCancelInstanceCommand(instanceKey).send().join();
    }

  }

  private static List<Long> userTasksOf(
      final CamundaClient client,
      final long instanceKey) {

    return client
        .newUserTaskSearchRequest()
        .filter(filter -> filter.processInstanceKey(instanceKey))
        .send()
        .join()
        .items()
        .stream()
        .map(task -> Long.valueOf(task.getUserTaskKey()))
        .toList();

  }

  private CamundaClient client() {

    return CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(restAddress()))
        .grpcAddress(URI.create(grpcAddress()))
        .build();

  }

  /**
   * Puts the measurement where it can be read after a build: into the test report of the
   * runner respectively an IDE, and into a file of its own, because the console output of a
   * test which passes is suppressed.
   *
   * @param reporter The JUnit reporter
   * @param name What the entry and the file are called
   * @param measured What the cluster answered, one line per reading
   */
  private static void report(
      final TestReporter reporter,
      final String name,
      final List<String> measured) {

    final var text = String.join(System.lineSeparator(), measured);
    reporter.publishEntry(name, text);
    try {
      Files.writeString(
          Path.of("target", name
              + ".txt"),
          text + System.lineSeparator());
    } catch (final IOException e) {
      throw new UncheckedIOException("Cannot write down what was measured", e);
    }

  }

}
