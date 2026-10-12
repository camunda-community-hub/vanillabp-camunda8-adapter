package io.vanillabp.camunda8.springboot.listeners;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A CANARY: it watches the cluster, not this adapter.
 * <p>
 * A task listener of a Camunda-managed user task is completed without variables. That is not a
 * decision of VanillaBP. The cluster refuses a task-listener completion with a variables
 * payload. It answers INVALID_ARGUMENT and names its issue 23702. This test holds the cluster to
 * that answer. It uses the raw client, so nothing of VanillaBP is in the way. A red run here is
 * news about Camunda, not a defect of this repository. The message says what to do with the
 * news.
 * <p>
 * It runs on the line this build is pinned to, and on every line. It was kept off the 8.10 line
 * while that line was built against an alpha. Its model waits for a {@code creating} listener
 * job, and the REST gateway of those alphas never handed one out. Nobody reads a canary which
 * dies of somebody else's alpha bug. So the next preview line which cannot hand out what this
 * model waits for needs the same exclusion.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8TaskListenerVariablesCanaryIT extends TestOnTheSharedCluster {

  private static final String JOB_TYPE = "theCanarysTaskListener";

  private static final String MODEL = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
          id="Definitions_TaskListenerCanary" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="TaskListenerCanary" isExecutable="true">
          <bpmn:startEvent id="CanaryStart">
            <bpmn:outgoing>CanaryFlow_1</bpmn:outgoing>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="CanaryFlow_1" sourceRef="CanaryStart" targetRef="CanaryTask" />
          <bpmn:userTask id="CanaryTask" name="the user task of the canary">
            <bpmn:extensionElements>
              <zeebe:userTask />
              <zeebe:taskListeners>
                <zeebe:taskListener eventType="creating" type="%s" />
              </zeebe:taskListeners>
            </bpmn:extensionElements>
            <bpmn:incoming>CanaryFlow_1</bpmn:incoming>
            <bpmn:outgoing>CanaryFlow_2</bpmn:outgoing>
          </bpmn:userTask>
          <bpmn:sequenceFlow id="CanaryFlow_2" sourceRef="CanaryTask" targetRef="CanaryEnd" />
          <bpmn:endEvent id="CanaryEnd">
            <bpmn:incoming>CanaryFlow_2</bpmn:incoming>
          </bpmn:endEvent>
        </bpmn:process>
      </bpmn:definitions>
      """.formatted(JOB_TYPE);

  @Test
  @DisplayName("The cluster still refuses a task-listener completion which carries variables")
  public void theClusterStillRefusesVariablesFromATaskListener() throws Exception {

    try (final var client = client()) {

      client
          .newDeployResourceCommand()
          .addResourceStringUtf8(MODEL, "task-listener-canary.bpmn")
          .send()
          .join();
      client
          .newCreateInstanceCommand()
          .bpmnProcessId("TaskListenerCanary")
          .latestVersion()
          .send()
          .join();

      final var job = awaitTheListenerJob(client);

      // both completions below carry the token of this activation. From 8.10.0-rc1 on, the
      // cluster refuses a leased job whose answer has no token, for THAT reason. This canary
      // would then stay green, and the cluster would never be asked about the payload
      final var leaseToken = Camunda8JobLease.tokenOf(job);

      assertThrows(
          Exception.class,
          () -> Camunda8JobLease
              .withToken(client.newCompleteCommand(job.getKey()), leaseToken)
              .variables(Map.of("whatTheListenerWrote", "something"))
              .send()
              .join(),
          "The cluster ACCEPTED variables from a task-listener completion. That is news about "
              + "Camunda, not a defect of this repository: the refusal is why a task listener is "
              + "completed without variables here (see decision 1 in the repository's "
              + "DECISIONS.md), and Camunda named issue 23702 as the place where it would change. "
              + "Read what the cluster does with such variables now, decide what a task-listener "
              + "method may write, and change Camunda8ModelledListenerHandler, decision 1 and this "
              + "canary together.");

      // the same job, completed the way the adapter completes it. This shows that the refusal
      // above was about the payload and nothing else. A job which had expired or was gone would
      // fail here too. Without this, the canary would stay green for the wrong reason
      Camunda8JobLease
          .withToken(client.newCompleteCommand(job.getKey()), leaseToken)
          .send()
          .join();

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
   * The listener job, activated directly and not through a worker. So the completion below runs
   * in the test's own thread, and the test reads its answer.
   *
   * @param client The client of the cluster under test
   * @return The activated job
   */
  private ActivatedJob awaitTheListenerJob(
      final CamundaClient client) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline) {
      // the cluster never hands a leased job to an activation which does not ask for a lease.
      // This suite configures 'job-lease: use'
      final var jobs = Camunda8JobLease
          .leaseTheActivation(
              client
                  .newActivateJobsCommand()
                  .jobType(JOB_TYPE)
                  .maxJobsToActivate(1)
                  .timeout(Duration.ofMinutes(2)))
          .send()
          .join()
          .getJobs();
      if (!jobs.isEmpty()) {
        final var job = jobs.getFirst();
        assertNotNull(job.getKey());
        return job;
      }
      Thread.sleep(1000);
    }
    return fail(
        "no task-listener job of type '"
            + JOB_TYPE
            + "' within 120 seconds, so the cluster answered nothing this canary could read");

  }

}
