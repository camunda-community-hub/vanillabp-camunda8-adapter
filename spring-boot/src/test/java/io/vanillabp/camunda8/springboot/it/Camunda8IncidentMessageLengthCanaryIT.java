package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.vanillabp.camunda8.client.Camunda8Errors;
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A CANARY: it watches the cluster, not this adapter.
 * <p>
 * <code>report-errors-as-stack-trace</code> writes a stack trace into an incident, and a stack
 * trace is long. The adapter cuts the text to {@link Camunda8Errors#INCIDENT_MESSAGE_LIMIT}
 * characters itself, at the end of the trace. So the cause at its start is never lost. That only
 * works while the cluster keeps at least that many characters. This test fails a job with a text
 * of exactly that length and reads the incident back. It goes over REST, the transport this
 * adapter prefers. The client hands the text on unchanged over gRPC as well, but this suite's
 * cluster refuses a gRPC command from a client which does not authenticate. A red
 * run here is news about Camunda. The message says what to do with it.
 * <p>
 * It also fails jobs with longer texts and writes what came back into
 * <code>target/incident-message-length.txt</code>. That is the measurement behind the limit. It
 * asserts nothing, because only the lower bound is a promise this adapter relies on.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8IncidentMessageLengthCanaryIT extends TestOnTheSharedCluster {

  private static final String JOB_TYPE = "theIncidentLengthCanarysTask";

  private static final String PROCESS = "IncidentLengthCanary";

  private static final String MODEL = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
          id="Definitions_IncidentLengthCanary" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="%s" isExecutable="true">
          <bpmn:startEvent id="CanaryStart">
            <bpmn:outgoing>CanaryFlow_1</bpmn:outgoing>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="CanaryFlow_1" sourceRef="CanaryStart" targetRef="CanaryTask" />
          <bpmn:serviceTask id="CanaryTask" name="the task of the canary">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="%s" retries="1" />
            </bpmn:extensionElements>
            <bpmn:incoming>CanaryFlow_1</bpmn:incoming>
            <bpmn:outgoing>CanaryFlow_2</bpmn:outgoing>
          </bpmn:serviceTask>
          <bpmn:sequenceFlow id="CanaryFlow_2" sourceRef="CanaryTask" targetRef="CanaryEnd" />
          <bpmn:endEvent id="CanaryEnd">
            <bpmn:incoming>CanaryFlow_2</bpmn:incoming>
          </bpmn:endEvent>
        </bpmn:process>
      </bpmn:definitions>
      """.formatted(PROCESS, JOB_TYPE);

  @Test
  @DisplayName("The cluster keeps an incident text of the length the adapter cuts to")
  public void theClusterKeepsWhatTheAdapterSends() throws Exception {

    final var text = "x".repeat(Camunda8Errors.INCIDENT_MESSAGE_LIMIT - 1)
        + "!";
    try (final var client = client()) {
      deploy(client);
      final var kept = whatTheIncidentKeeps(client, text);
      assertEquals(
          text,
          kept,
          ("The cluster kept %d of the %d characters of an incident text. That is news about "
              + "Camunda, not a defect of this repository. The adapter cuts a stack trace to "
              + "Camunda8Errors.INCIDENT_MESSAGE_LIMIT itself, so the cause at its start stays in the "
              + "incident (see decision 81 in the repository's DECISIONS.md). Measure the new limit "
              + "with the texts this canary writes to target/incident-message-length.txt, and lower "
              + "the constant and decision 81 together.").formatted(kept.length(), text.length()));
    }

  }

  @Test
  @DisplayName("Measures what the cluster keeps of longer texts")
  public void measureWhatTheClusterKeeps() throws Exception {

    final var report = new StringBuilder();
    try (final var client = client()) {
      deploy(client);
      for (final var text : new String[]{
          "x".repeat(10_000), "\u00e4".repeat(10_000), "x".repeat(40_000)
      }) {
        final var kept = whatTheIncidentKeeps(client, text);
        report
            .append("sent ")
            .append(text.length())
            .append(" chars of '")
            .append(text.charAt(0))
            .append("', kept ")
            .append(kept.length())
            .append(" chars, ")
            .append(kept.getBytes(StandardCharsets.UTF_8).length)
            .append(" bytes\n");
      }
    }
    Files
        .writeString(
            Path.of("target", "incident-message-length.txt"),
            "cluster image "
                + ClusterUnderTest.image()
                + "\n"
                + report,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);

  }

  private static CamundaClient client() {

    return CamundaClient
        .newClientBuilder()
        .preferRestOverGrpc(true)
        .restAddress(URI.create(restAddress()))
        .grpcAddress(URI.create(grpcAddress()))
        .build();

  }

  private static void deploy(
      final CamundaClient client) {

    client
        .newDeployResourceCommand()
        .addResourceStringUtf8(MODEL, "incident-length-canary.bpmn")
        .send()
        .join();

  }

  /**
   * Starts an instance, fails its job with the text and no retry left, and reads the text of the
   * incident back.
   */
  private static String whatTheIncidentKeeps(
      final CamundaClient client,
      final String text) throws InterruptedException, IOException {

    final var instance = client
        .newCreateInstanceCommand()
        .bpmnProcessId(PROCESS)
        .latestVersion()
        .send()
        .join()
        .getProcessInstanceKey();
    final var job = awaitTheJob(client, instance);
    Camunda8JobLease
        .withToken(client.newFailCommand(job.getKey()).retries(0).errorMessage(text), Camunda8JobLease.tokenOf(job))
        .send()
        .join();

    final var deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline) {
      final var incidents = client
          .newIncidentSearchRequest()
          .filter(filter -> filter.processInstanceKey(instance))
          .send()
          .join()
          .items();
      if (!incidents.isEmpty()) {
        final var kept = incidents.getFirst().getErrorMessage();
        client.newCancelInstanceCommand(instance).send().join();
        return kept == null
            ? ""
            : kept;
      }
      Thread.sleep(500);
    }
    return fail("no incident of instance "
        + instance
        + " within 120 seconds");

  }

  private static ActivatedJob awaitTheJob(
      final CamundaClient client,
      final long instance) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline) {
      // the cluster never hands a leased job to an activation which does not ask for a lease.
      // This suite configures 'job-lease: use'
      final var jobs = Camunda8JobLease
          .leaseTheActivation(
              client
                  .newActivateJobsCommand()
                  .jobType(JOB_TYPE)
                  .maxJobsToActivate(10)
                  .timeout(Duration.ofMinutes(2)))
          .send()
          .join()
          .getJobs();
      for (final var job : jobs) {
        if (job.getProcessInstanceKey() == instance) {
          return job;
        }
        // a job of an instance a run before left behind is failed out of the way
        Camunda8JobLease
            .withToken(client.newFailCommand(job.getKey()).retries(0), Camunda8JobLease.tokenOf(job))
            .send()
            .join();
      }
      Thread.sleep(500);
    }
    return fail("no job of type '"
        + JOB_TYPE
        + "' for instance "
        + instance
        + " within 120 seconds");

  }

}
