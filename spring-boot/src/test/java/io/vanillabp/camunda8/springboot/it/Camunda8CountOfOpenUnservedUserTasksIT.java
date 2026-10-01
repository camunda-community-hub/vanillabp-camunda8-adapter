package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.camunda8.client.Camunda8UnservedUserTaskJobs;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The number the deployment writes about plain BPMN user tasks, read against a real cluster
 * with one such task open and one of them finished.
 *
 * <h2>Why this class exists</h2>
 *
 * The number used to be the total of one search by process and job type. The index keeps a job
 * after it is over, so that total was every job of that type the process ever had, and an
 * application working through those tasks after an upgrade watched a number which never fell.
 * Measured on 2026-09-28 against {@code camunda/camunda:8.9.21}, a search on the key of a
 * completed job still answered with the job, as {@code TIMED_OUT} at once and {@code COMPLETED}
 * five seconds later.
 * <p>
 * So the search names the states a job does not leave again and takes those off the total, and
 * this class is where that is read from a cluster rather than argued about. Two workflows of one
 * process reach the same plain user task. One of them has its job activated and completed, the
 * other one's job is left alone, and the count has to say one.
 *
 * <h2>What it writes down</h2>
 *
 * Which states the cluster names for an open job and for a finished one, line by line. An open
 * job stands in more than one state over its life, and the three lines do not have to agree
 * about them: 8.9.21 named an open job {@code CREATED} five seconds after its activation, where
 * 8.10.0-rc1 named the same job {@code TIMEOUT_UPDATED}. The record is the reason the search
 * names the end states rather than the open ones, and it is written to the test report and to a
 * file of its own, because the output of a test which passes is suppressed.
 * <p>
 * What this class answered on 2026-10-01: {@code camunda/camunda:8.8.40}, {@code 8.9.21} and
 * {@code 8.10.0-rc3} all named the open job {@code CREATED} and the completed one
 * {@code COMPLETED}, and the count settled on one 674 to 1191 ms after the completion. The
 * numbers move with the machine, the states should not.
 * <p>
 * Nothing of VanillaBP is in the way: the model is deployed with the raw client, no worker of
 * this repository subscribes to the job type the cluster serves the element with, and the class
 * ends the workflow it left running. It is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8CountOfOpenUnservedUserTasksIT extends TestOnTheSharedCluster {

  private static final String PROCESS_ID = "CountOfOpenUnservedUserTasks";

  private static final String ELEMENT_ID = "TheTaskNothingServes";

  /**
   * A plain BPMN user task: no {@code zeebe:userTask} and no {@code zeebe:taskDefinition}, so
   * the cluster serves it with a job of
   * {@link Camunda8TaskWiring#TASKDEFINITION_USERTASK_WORKER_V1} and this version serves it with
   * nothing.
   */
  private static final String MODEL = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
          id="Definitions_CountOfOpenUnservedUserTasks" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="%s" isExecutable="true">
          <bpmn:startEvent id="CountOfOpenStart">
            <bpmn:outgoing>CountOfOpenFlow_1</bpmn:outgoing>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="CountOfOpenFlow_1" sourceRef="CountOfOpenStart" targetRef="%s" />
          <bpmn:userTask id="%s" name="the user task nothing serves">
            <bpmn:incoming>CountOfOpenFlow_1</bpmn:incoming>
            <bpmn:outgoing>CountOfOpenFlow_2</bpmn:outgoing>
          </bpmn:userTask>
          <bpmn:sequenceFlow id="CountOfOpenFlow_2" sourceRef="%s" targetRef="CountOfOpenEnd" />
          <bpmn:endEvent id="CountOfOpenEnd">
            <bpmn:incoming>CountOfOpenFlow_2</bpmn:incoming>
          </bpmn:endEvent>
        </bpmn:process>
      </bpmn:definitions>
      """.formatted(PROCESS_ID, ELEMENT_ID, ELEMENT_ID, ELEMENT_ID);

  /**
   * How long the job of the task which gets finished is leased for. Long enough to complete it
   * and short enough that a run which breaks off does not hold it for minutes.
   */
  private static final Duration THE_JOB_IS_HELD_FOR = Duration.ofSeconds(30);

  /**
   * How long the index is given to catch up with the engine. Generous rather than tight: a
   * loaded machine's exporter is what is being waited for, and story 741 measured over five
   * seconds between a completed job and the state the index settled on.
   */
  private static final Duration THE_INDEX_CATCHES_UP_WITHIN = Duration.ofSeconds(90);

  private static final long BETWEEN_TWO_READINGS_MILLIS = 250;

  @Test
  @DisplayName("One open and one finished job of the same type in the same process, and the count says one")
  public void aFinishedTaskIsNotCountedAsOpen(
      final TestReporter reporter) throws Exception {

    final var measured = new ArrayList<String>();
    measured.add("the cluster: "
        + ClusterUnderTest.image());

    try (final var client = client()) {

      final var deployed = whatTheClusterAnswered(() -> client
          .newDeployResourceCommand()
          .addResourceStringUtf8(MODEL, "count-of-open-unserved-user-tasks.bpmn")
          .send()
          .join());
      measured.add("the deployment of a plain BPMN user task: "
          + deployed);
      if (!"accepted".equals(deployed)) {
        // a line which refuses the shape has no such job to count, and that refusal is the
        // finding rather than a failure of this test
        report(reporter, measured);
        return;
      }

      final var theWorkflowWhoseTaskGetsFinished = start(client);
      final var theWorkflowWhoseTaskStaysOpen = start(client);
      try {

        final var job = awaitTheJobOf(client, theWorkflowWhoseTaskGetsFinished);
        measured.add("the job of the task which gets finished: key "
            + job.getKey());
        Camunda8JobLease
            .withToken(client.newCompleteCommand(job.getKey()), Camunda8JobLease.tokenOf(job))
            .send()
            .join();
        measured.add("that job was completed, and the other task's job was left alone");

        final var count = untilTheIndexSettles(client, measured);

        assertEquals(
            2L,
            count.theIndexHolds(),
            () -> "the index holds both jobs of that type, which is the number the count used to "
                + "say and the reason it never fell: "
                + String.join("; ", measured));
        assertEquals(
            1L,
            count.areOpen(),
            () -> "one of the two tasks is still waiting for somebody, and that is the number the "
                + "message names: "
                + String.join("; ", measured));

      } finally {
        client.newCancelInstanceCommand(theWorkflowWhoseTaskStaysOpen).send().join();
      }

      report(reporter, measured);

    }

  }

  /**
   * Reads the count until the index agrees with the engine, and writes down which states it
   * named on the way.
   *
   * @param client The raw client of this measurement
   * @param measured What is written down
   * @return The count the index settled on, or the last one it gave within the deadline
   */
  private static Camunda8UnservedUserTaskJobs.Count untilTheIndexSettles(
      final CamundaClient client,
      final List<String> measured) throws InterruptedException {

    final var statesSeen = new LinkedHashSet<String>();
    final var startedAt = System.nanoTime();
    final var deadline = System.currentTimeMillis() + THE_INDEX_CATCHES_UP_WITHIN.toMillis();
    var count = Camunda8UnservedUserTaskJobs.countFor(client, PROCESS_ID);
    while (System.currentTimeMillis() < deadline) {
      statesSeen.addAll(whichStatesTheIndexNames(client));
      count = Camunda8UnservedUserTaskJobs.countFor(client, PROCESS_ID);
      if ((count.theIndexHolds() == 2L) && (count.areOpen() == 1L)) {
        break;
      }
      Thread.sleep(BETWEEN_TWO_READINGS_MILLIS);
    }
    statesSeen.addAll(whichStatesTheIndexNames(client));
    measured.add("the states the index named for the two jobs: "
        + String.join(", ", statesSeen));
    measured.add("jobs of that type the index holds: %d, of them finished: %d, open: %d, after %d ms"
        .formatted(
            Long.valueOf(count.theIndexHolds()),
            Long.valueOf(count.theIndexHasSeenFinish()),
            Long.valueOf(count.areOpen()),
            Long.valueOf((System.nanoTime() - startedAt) / 1_000_000)));
    return count;

  }

  /**
   * @param client The raw client of this measurement
   * @return What the index says the jobs of that type are doing, one entry per job
   */
  private static Set<String> whichStatesTheIndexNames(
      final CamundaClient client) {

    return client
        .newJobSearchRequest()
        .filter(filter -> filter
            .processDefinitionId(PROCESS_ID)
            .type(Camunda8TaskWiring.TASKDEFINITION_USERTASK_WORKER_V1))
        .page(page -> page.limit(10))
        .send()
        .join()
        .items()
        .stream()
        .map(job -> "%s is %s".formatted(
            job.getEndTime() == null
                ? "a job the index has not seen end"
                : "a job the index has seen end",
            job.getState()))
        .collect(Collectors.toCollection(LinkedHashSet::new));

  }

  private static long start(
      final CamundaClient client) {

    return client
        .newCreateInstanceCommand()
        .bpmnProcessId(PROCESS_ID)
        .latestVersion()
        .send()
        .join()
        .getProcessInstanceKey();

  }

  /**
   * Takes the job of one workflow's user task, leased for long enough that completing it is not
   * a race against a lock which ran out.
   *
   * @param client The raw client of this measurement
   * @param instanceKey The workflow whose job is wanted
   * @return That job
   */
  private static ActivatedJob awaitTheJobOf(
      final CamundaClient client,
      final long instanceKey) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline) {
      final var jobs = Camunda8JobLease
          .leaseTheActivation(
              client
                  .newActivateJobsCommand()
                  .jobType(Camunda8TaskWiring.TASKDEFINITION_USERTASK_WORKER_V1)
                  .maxJobsToActivate(10)
                  .timeout(THE_JOB_IS_HELD_FOR))
          .send()
          .join()
          .getJobs()
          .stream()
          .filter(job -> job.getProcessInstanceKey() == instanceKey)
          .toList();
      if (!jobs.isEmpty()) {
        return jobs.getFirst();
      }
      Thread.sleep(250);
    }
    return fail(
        "no job of type '"
            + Camunda8TaskWiring.TASKDEFINITION_USERTASK_WORKER_V1
            + "' for workflow "
            + instanceKey
            + " within 120 seconds, so the cluster never reached the element this measurement is "
            + "about");

  }

  /**
   * @param command What is sent
   * @return What came back: the code of the transport for a refusal, and the word this
   *         repository uses for a command the cluster carried out
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

  private CamundaClient client() {

    return CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(restAddress()))
        .grpcAddress(URI.create(grpcAddress()))
        .build();

  }

  /**
   * Puts the measurement where it can be read after a build: into the test report of the runner
   * respectively an IDE, and into a file of its own.
   *
   * @param reporter The JUnit reporter
   * @param measured What the cluster answered, one line per reading
   */
  private static void report(
      final TestReporter reporter,
      final List<String> measured) {

    final var name = "count-of-open-unserved-user-tasks";
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
