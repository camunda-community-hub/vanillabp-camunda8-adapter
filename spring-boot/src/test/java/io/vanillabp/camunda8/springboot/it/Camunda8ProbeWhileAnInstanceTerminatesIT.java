package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.client.Camunda8InstanceProbe;
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.camunda8.client.Camunda8UserTaskProbe;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the existence probe answers while an instance is still terminating.
 * <p>
 * A cancellation is not the end of an instance. An instance carrying a Camunda-managed user
 * task waits for the {@code canceling} listener job of that task, and the engine answers
 * {@code 404} to a SECOND cancellation of such an instance within milliseconds - measured on
 * 2026-09-25 at 24 ms, while the instance was still alive 130 seconds later. So one command
 * saying "gone" says nothing about the instance being over.
 * <p>
 * The existence probe is a different command, a modification the engine refuses for an
 * instance it holds, and decisions 35 and 38 rest on the sentence that its {@code 404} means
 * gone. Whether the probe has the same hole was never measured, and this class measures it:
 * the probe is sent inside the window in which the {@code canceling} job is unanswered, and
 * again after the job was answered, and both answers are written down.
 * <p>
 * Nothing of VanillaBP is in the way. The model is deployed with the raw client and carries
 * a {@code canceling} listener of a job type no worker of this repository subscribes to, so
 * no application can close the window by accident. The test answers that job itself at the
 * end, which is what leaves the shared cluster the way it found it.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8ProbeWhileAnInstanceTerminatesIT extends TestOnTheSharedCluster {

  /**
   * The job type of the {@code canceling} listener. Nothing in this repository subscribes to
   * it, which is what holds the window open for as long as the measurement needs it.
   */
  private static final String CANCELING_JOB_TYPE = "theCancelingJobNobodyAnswers";

  private static final String PROCESS_ID = "ProbeWhileTerminating";

  private static final String MODEL = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
          id="Definitions_ProbeWhileTerminating" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="%s" isExecutable="true">
          <bpmn:startEvent id="TerminatingStart">
            <bpmn:outgoing>TerminatingFlow_1</bpmn:outgoing>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="TerminatingFlow_1" sourceRef="TerminatingStart" targetRef="TerminatingTask" />
          <bpmn:userTask id="TerminatingTask" name="the user task which holds the instance while it terminates">
            <bpmn:extensionElements>
              <zeebe:userTask />
              <zeebe:taskListeners>
                <zeebe:taskListener eventType="canceling" type="%s" />
              </zeebe:taskListeners>
            </bpmn:extensionElements>
            <bpmn:incoming>TerminatingFlow_1</bpmn:incoming>
            <bpmn:outgoing>TerminatingFlow_2</bpmn:outgoing>
          </bpmn:userTask>
          <bpmn:sequenceFlow id="TerminatingFlow_2" sourceRef="TerminatingTask" targetRef="TerminatingEnd" />
          <bpmn:endEvent id="TerminatingEnd">
            <bpmn:incoming>TerminatingFlow_2</bpmn:incoming>
          </bpmn:endEvent>
        </bpmn:process>
      </bpmn:definitions>
      """.formatted(PROCESS_ID, CANCELING_JOB_TYPE);

  /**
   * How long the measurement holds the {@code canceling} job without answering it. Long
   * enough to take every reading below and to be sure the window is not a race against a
   * lock which ran out.
   */
  private static final Duration THE_WINDOW_IS_HELD_OPEN_FOR = Duration.ofMinutes(2);

  /**
   * How often the probe is repeated inside the window, so the answer read is the answer the
   * engine keeps giving rather than the one it happened to give first.
   */
  private static final int READINGS_INSIDE_THE_WINDOW = 5;

  private static final long BETWEEN_TWO_READINGS_MILLIS = 1_000;

  @Test
  @DisplayName("The existence probe says 'still there' for an instance which is terminating")
  public void theProbeAnswersWhileTheInstanceIsStillTerminating(
      final TestReporter reporter) throws Exception {

    final var measured = new ArrayList<String>();

    try (final var client = client()) {

      client
          .newDeployResourceCommand()
          .addResourceStringUtf8(MODEL, "probe-while-terminating.bpmn")
          .send()
          .join();
      final var instanceKey = client
          .newCreateInstanceCommand()
          .bpmnProcessId(PROCESS_ID)
          .latestVersion()
          .send()
          .join()
          .getProcessInstanceKey();

      awaitTheUserTask(client, instanceKey);

      client
          .newCancelInstanceCommand(instanceKey)
          .send()
          .join();

      // held rather than answered: from here on the instance waits for a job this test is
      // sitting on, so the window below is as wide as the measurement needs and not as
      // wide as a cluster happens to make it
      final var cancelingJob = awaitTheCancelingJob(client);

      // what a SECOND cancellation says, which is the answer story 604 measured and the
      // reason this class exists
      measured
          .add("a second cancellation inside the window: "
              + whatTheClusterAnswered(() -> client.newCancelInstanceCommand(instanceKey).send().join()));

      for (var reading = 1; reading <= READINGS_INSIDE_THE_WINDOW; ++reading) {
        measured
            .add("the existence probe inside the window, reading "
                + reading
                + ": "
                + whatTheClusterAnswered(() -> Camunda8InstanceProbe
                    .askTheEngine(client, instanceKey, Camunda8TaskWiring.RESERVED_PROBE_ELEMENT_ID, null)));
        Thread.sleep(BETWEEN_TWO_READINGS_MILLIS);
      }

      // and the user-task probe of decision 38, in the same window and about the very task
      // the instance is waiting for
      measured
          .add("the user-task probe inside the window: "
              + whatTheClusterAnswered(
                  () -> Camunda8UserTaskProbe.askTheEngine(client, userTaskKeyOf(client, instanceKey))));

      final var answeredAt = System.nanoTime();
      Camunda8JobLease
          .withToken(client.newCompleteCommand(cancelingJob.getKey()), Camunda8JobLease.tokenOf(cancelingJob))
          .send()
          .join();

      final var afterTheWindow = probeUntilItSaysGone(client, instanceKey);
      measured
          .add("the existence probe after the canceling job was answered: "
              + afterTheWindow
              + " (%d ms after the answer)".formatted(Long.valueOf((System.nanoTime() - answeredAt) / 1_000_000)));

      report(reporter, measured);

      // What the assertions hold the cluster to. The probe may not say "gone" about an
      // instance the engine is still holding, because decisions 35 and 38 read that 404 as
      // "the workflow is over" and a terminating instance is not over: it still hands out
      // the listener job its user task waits for
      measured
          .stream()
          .filter(reading -> reading.startsWith("the existence probe inside the window"))
          .forEach(reading -> assertFalse(
              reading.contains("HTTP 404") || reading.contains("NOT_FOUND"),
              "The existence probe answered 404 for an instance the engine was still terminating. "
                  + "That is the hole story 604 found in the cancellation, now in the probe, and it "
                  + "makes the 404 of decisions 35 and 38 mean less than those decisions say: an "
                  + "election would call such a workflow ended while the cluster still hands out its "
                  + "listener job, and the check of the other open tasks would report every task of "
                  + "it as CANCELED. Correct Camunda8ProcessService, Camunda8OpenTaskProbe, the "
                  + "README and the wiki, and replace decisions 35 and 38 rather than rewording "
                  + "them. What was measured: "
                  + String.join("; ", measured)));

      assertTrue(
          afterTheWindow.contains("404") || afterTheWindow.contains("NOT_FOUND"),
          "and once the canceling job is answered the instance really is gone, so the readings above "
              + "were about a window and not about a probe which never says gone: "
              + String.join("; ", measured));

    }

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
   * Sends the existence probe until the engine answers that it does not hold the instance.
   *
   * @param client The client of the test
   * @param instanceKey The instance which is ending
   * @return What the engine answered last
   */
  private static String probeUntilItSaysGone(
      final CamundaClient client,
      final long instanceKey) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 60_000;
    var answer = "nothing was asked";
    while (System.currentTimeMillis() < deadline) {
      answer = whatTheClusterAnswered(() -> Camunda8InstanceProbe
          .askTheEngine(client, instanceKey, Camunda8TaskWiring.RESERVED_PROBE_ELEMENT_ID, null));
      if (answer.contains("404") || answer.contains("NOT_FOUND")) {
        return answer;
      }
      Thread.sleep(250);
    }
    return answer;

  }

  /**
   * Waits until the cluster reports the user task of that instance, which is what makes the
   * cancellation below a cancellation of an instance which really carries one.
   */
  private static void awaitTheUserTask(
      final CamundaClient client,
      final long instanceKey) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline) {
      if (!userTasksOf(client, instanceKey).isEmpty()) {
        return;
      }
      Thread.sleep(500);
    }
    fail("no user task of instance "
        + instanceKey
        + " within 120 seconds, so the cluster never reached the element this measurement is about");

  }

  private static long userTaskKeyOf(
      final CamundaClient client,
      final long instanceKey) {

    final var tasks = userTasksOf(client, instanceKey);
    assertEquals(1, tasks.size(), "the model carries exactly one user task");
    return tasks.getFirst().longValue();

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

  /**
   * Takes the {@code canceling} listener job and keeps it, so the instance waits for this
   * test rather than for a timeout.
   */
  private static ActivatedJob awaitTheCancelingJob(
      final CamundaClient client) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline) {
      final var jobs = Camunda8JobLease
          .leaseTheActivation(
              client
                  .newActivateJobsCommand()
                  .jobType(CANCELING_JOB_TYPE)
                  .maxJobsToActivate(1)
                  .timeout(THE_WINDOW_IS_HELD_OPEN_FOR))
          .send()
          .join()
          .getJobs();
      if (!jobs.isEmpty()) {
        return jobs.getFirst();
      }
      Thread.sleep(250);
    }
    return fail(
        "no 'canceling' listener job within 120 seconds of the cancellation, so there was no window "
            + "to measure the probe in");

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
   * @param measured What the cluster answered, one line per reading
   */
  private static void report(
      final TestReporter reporter,
      final List<String> measured) {

    final var text = String.join(System.lineSeparator(), measured);
    reporter.publishEntry("probe-while-terminating", text);
    try {
      Files.writeString(
          Path.of("target", "probe-while-terminating.txt"),
          text + System.lineSeparator());
    } catch (final IOException e) {
      throw new UncheckedIOException("Cannot write down what was measured", e);
    }

  }

}
