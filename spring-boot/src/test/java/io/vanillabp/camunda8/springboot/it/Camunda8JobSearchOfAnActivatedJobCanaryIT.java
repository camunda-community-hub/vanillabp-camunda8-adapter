package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.JobState;
import io.camunda.client.api.search.response.Job;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A CANARY: it watches the cluster, not this adapter.
 * <p>
 * The wiki tells an operator how to find a workflow which waits for a job nobody fetches: the
 * job search lists such a job with state <code>CREATED</code>, an empty worker and no deadline.
 * That would tell the two apart only if a job a worker HOLDS looked different there. It does
 * not. Measured against <code>camunda/camunda:8.10.7</code> on 2026-11-01: 30 seconds after a
 * worker activated a job with a lock of five minutes, the search still listed it in state
 * <code>CREATED</code>, with an empty worker, no deadline and the creation time as its last
 * update. So the messages of the adapter name time as the sign. This class fails when the
 * search starts to show the activation, on the 8.10 line where it was measured. On every line it
 * writes what it saw into
 * <code>target/activated-job-search.txt</code>.
 * <p>
 * The class is skipped when Docker is unavailable.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8JobSearchOfAnActivatedJobCanaryIT extends TestOnTheSharedCluster {

  private static final String JOB_TYPE = "theActivatedJobCanarysTask";

  private static final String PROCESS = "ActivatedJobCanary";

  private static final String MODEL = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
          id="Definitions_%1$s" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="%1$s" isExecutable="true">
          <bpmn:startEvent id="CanaryStart">
            <bpmn:outgoing>CanaryFlow_1</bpmn:outgoing>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="CanaryFlow_1" sourceRef="CanaryStart" targetRef="CanaryTask" />
          <bpmn:serviceTask id="CanaryTask">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="%2$s" />
            </bpmn:extensionElements>
            <bpmn:incoming>CanaryFlow_1</bpmn:incoming>
          </bpmn:serviceTask>
        </bpmn:process>
      </bpmn:definitions>
      """.formatted(PROCESS, JOB_TYPE);

  private static final String WHAT_TO_DO = "The job search now tells a job a worker holds from one nobody "
      + "fetched. That is news about Camunda, not a defect of this repository. Read "
      + "target/activated-job-search.txt of this run. Then the messages of Camunda8Connectors#A_JOB_NOBODY_FETCHES "
      + "and the wiki section 'A job nobody fetches' can name the fields of the job as the sign, not only time.";

  private final List<String> report = new ArrayList<>();

  @Test
  @DisplayName("Measures what the job search says about a job before and after a worker activated it")
  public void measureAnActivatedJob() throws Exception {

    report.add("cluster image "
        + ClusterUnderTest.image());
    try (final var client = client()) {
      client
          .newDeployResourceCommand()
          .addResourceStringUtf8(MODEL, PROCESS
              + ".bpmn")
          .send()
          .join();
      final var instance = client
          .newCreateInstanceCommand()
          .bpmnProcessId(PROCESS)
          .latestVersion()
          .send()
          .join()
          .getProcessInstanceKey();
      try {
        final var created = awaitTheJob(client, instance);
        report.add("before the activation: "
            + describe(created));

        final var activated = client
            .newActivateJobsCommand()
            .jobType(JOB_TYPE)
            .maxJobsToActivate(1)
            .timeout(Duration.ofMinutes(5))
            .workerName("the-canary")
            .requestTimeout(Duration.ofSeconds(20))
            .send()
            .join()
            .getJobs();
        report.add("activated "
            + activated.size()
            + " job(s), deadline "
            + (activated.isEmpty()
                ? "-"
                : activated.getFirst().getDeadline()));

        // the search runs behind the engine. So it is asked a few times, and every answer is kept
        assertEquals(1, activated.size(), "the worker of the canary activates the job");
        final var activatedAt = System.currentTimeMillis();
        Job seen = null;
        for (final var afterSeconds : List.of(1, 3, 10, 30)) {
          Thread.sleep(Math.max(0, activatedAt + afterSeconds * 1000L - System.currentTimeMillis()));
          seen = awaitTheJob(client, instance);
          report.add("about "
              + afterSeconds
              + " s after the activation: "
              + describe(seen));
        }
        // what an operator reads half a minute after a worker took the job. Only the line it
        // was measured on is asserted. The older lines only write their report
        if (!ClusterUnderTest.image().getVersionPart().startsWith("8.10")) {
          return;
        }
        assertEquals(JobState.CREATED, seen.getState(), WHAT_TO_DO);
        assertTrue((seen.getWorker() == null) || seen.getWorker().isEmpty(), WHAT_TO_DO);
        assertNull(seen.getDeadline(), WHAT_TO_DO);
      } finally {
        client
            .newCancelInstanceCommand(instance)
            .send()
            .join();
      }
    } finally {
      Files.write(Path.of("target", "activated-job-search.txt"), report);
    }

  }

  private static Job awaitTheJob(
      final CamundaClient client,
      final long instance) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 60_000;
    while (System.currentTimeMillis() < deadline) {
      final var jobs = client
          .newJobSearchRequest()
          .filter(filter -> filter.processInstanceKey(instance))
          .send()
          .join()
          .items();
      if (!jobs.isEmpty()) {
        return jobs.getFirst();
      }
      Thread.sleep(500);
    }
    throw new AssertionError("the job search did not list the job of instance "
        + instance
        + " within 60 seconds");

  }

  private static String describe(
      final Job job) {

    return "state=%s worker='%s' deadline=%s retries=%s creationTime=%s lastUpdateTime=%s"
        .formatted(
            job.getState(),
            job.getWorker(),
            job.getDeadline(),
            job.getRetries(),
            job.getCreationTime(),
            job.getLastUpdateTime());

  }

  private static CamundaClient client() {

    return CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(restAddress()))
        .grpcAddress(URI.create(grpcAddress()))
        .build();

  }

}
