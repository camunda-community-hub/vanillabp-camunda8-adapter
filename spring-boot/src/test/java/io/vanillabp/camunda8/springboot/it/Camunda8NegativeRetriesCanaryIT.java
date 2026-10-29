package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.vanillabp.camunda8.client.Camunda8JobLease;
import io.vanillabp.camunda8.springboot.TestOnTheSharedCluster;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A CANARY: it watches the cluster, not this adapter.
 * <p>
 * Every worker of this adapter fails a job with one retry less than it arrived with, or with
 * none. It never sends a count below zero, see {@code Camunda8RetryCountNeverBelowZeroTest}.
 * That guard matters because a job CAN arrive with no retries left. A model which writes
 * <code>retries="0"</code> on a task or on a listener hands out such a job on its first
 * delivery. The first test fails when the cluster stops doing that. Then the guard may be
 * dead code, and whoever reads the failure decides.
 * <p>
 * The second test measures what the cluster makes of a count below zero, and of the ways an
 * operator could bring a job back with none. It writes what came back into
 * <code>target/negative-retries.txt</code> and asserts nothing, because the adapter relies on
 * none of these answers. It only needs the guard.
 * <p>
 * The class is skipped when Docker is unavailable
 * ({@code @Testcontainers(disabledWithoutDocker = true)}).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8NegativeRetriesCanaryIT extends TestOnTheSharedCluster {

  private static final String JOB_TYPE = "theNegativeRetriesCanarysTask";

  private static final String LISTENER_TYPE = "theNegativeRetriesCanarysListener";

  private static final String PROCESS = "NegativeRetriesCanary";

  private static final String PROCESS_TASK_WITHOUT_RETRIES = "NegativeRetriesCanaryTaskZero";

  private static final String PROCESS_LISTENER_WITHOUT_RETRIES = "NegativeRetriesCanaryListenerZero";

  private static final String MODEL = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
          id="Definitions_%1$s" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="%1$s" isExecutable="true">
          <bpmn:startEvent id="CanaryStart">
            %4$s
            <bpmn:outgoing>CanaryFlow_1</bpmn:outgoing>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="CanaryFlow_1" sourceRef="CanaryStart" targetRef="CanaryTask" />
          <bpmn:serviceTask id="CanaryTask" name="the task of the canary">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="%2$s" retries="%3$s" />
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
      """;

  /**
   * An <code>end</code> listener on the start event. That is where this adapter puts its own
   * start listener.
   */
  private static final String LISTENER_WITHOUT_RETRIES = """
      <bpmn:extensionElements>
        <zeebe:executionListeners>
          <zeebe:executionListener eventType="end" type="%s" retries="0" />
        </zeebe:executionListeners>
      </bpmn:extensionElements>
      """.formatted(LISTENER_TYPE);

  private static final String WHAT_TO_DO = "That is news about Camunda, not a defect of this repository. "
      + "The workers never fail a job with fewer than zero retries, because a job can arrive with "
      + "none. Read target/negative-retries.txt of this run and decide whether the guard in the handlers still has a case.";

  private final List<String> report = new ArrayList<>();

  @Test
  @DisplayName("A model with retries=\"0\" hands out jobs with no retries left")
  public void aModelWithoutRetriesHandsOutJobsWithNone() throws Exception {

    try (final var client = client()) {
      deploy(client, PROCESS_TASK_WITHOUT_RETRIES, "0", "");
      deploy(client, PROCESS_LISTENER_WITHOUT_RETRIES, "3", LISTENER_WITHOUT_RETRIES);

      final var taskInstance = start(client, PROCESS_TASK_WITHOUT_RETRIES);
      final var taskJob = tryTheJob(client, JOB_TYPE, taskInstance, Duration.ofSeconds(60));
      cancel(client, taskInstance);
      assertNotNull(taskJob, "no job of a task with retries=\"0\" within 60 seconds. "
          + WHAT_TO_DO);
      assertEquals(0, taskJob.getRetries(), "a task with retries=\"0\". "
          + WHAT_TO_DO);

      final var listenerInstance = start(client, PROCESS_LISTENER_WITHOUT_RETRIES);
      final var listenerJob = tryTheJob(client, LISTENER_TYPE, listenerInstance, Duration.ofSeconds(60));
      cancel(client, listenerInstance);
      assertNotNull(listenerJob, "no job of a listener with retries=\"0\" within 60 seconds. "
          + WHAT_TO_DO);
      assertEquals(0, listenerJob.getRetries(), "a listener with retries=\"0\". "
          + WHAT_TO_DO);
    }

  }

  @Test
  @DisplayName("Measures how the cluster answers retry counts of zero and below")
  public void measureCountsBelowOne() throws Exception {

    report.add("cluster image "
        + ClusterUnderTest.image());
    try (final var client = client()) {
      deploy(client, PROCESS, "3", "");
      measureAFailWithMinusOne(client);
      measureWhatAnOperatorCanDo(client);
    } finally {
      Files
          .writeString(
              Path.of("target", "negative-retries.txt"),
              String.join("\n", report)
                  + "\n",
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING);
    }

  }

  private void measureAFailWithMinusOne(
      final CamundaClient client) throws Exception {

    final var instance = start(client, PROCESS);
    final var job = awaitTheJob(client, instance);
    attempt("fail with retries -1", () -> Camunda8JobLease
        .withToken(client.newFailCommand(job.getKey()).retries(-1).errorMessage("minus one"),
            Camunda8JobLease.tokenOf(job))
        .send()
        .join());
    report.add("incidents after it: "
        + incidents(client, instance));
    report.add("job handed out again within 10 seconds: "
        + describe(tryTheJob(client, JOB_TYPE, instance, Duration.ofSeconds(10))));
    final var incidentKey = awaitIncident(client, instance);
    attempt("update retries to 1", () -> client
        .newUpdateRetriesCommand(job.getKey())
        .retries(1)
        .send()
        .join());
    attempt("resolve the incident", () -> client
        .newResolveIncidentCommand(incidentKey)
        .send()
        .join());
    report.add("job handed out after that: "
        + describe(tryTheJob(client, JOB_TYPE, instance, Duration.ofSeconds(20))));
    cancel(client, instance);

  }

  private void measureWhatAnOperatorCanDo(
      final CamundaClient client) throws Exception {

    final var instance = start(client, PROCESS);
    final var job = awaitTheJob(client, instance);
    Camunda8JobLease
        .withToken(client.newFailCommand(job.getKey()).retries(0).errorMessage("zero"),
            Camunda8JobLease.tokenOf(job))
        .send()
        .join();
    final var incidentKey = awaitIncident(client, instance);
    attempt("update retries to 0", () -> client
        .newUpdateRetriesCommand(job.getKey())
        .retries(0)
        .send()
        .join());
    attempt("update retries to -1", () -> client
        .newUpdateRetriesCommand(job.getKey())
        .retries(-1)
        .send()
        .join());
    attempt("resolve the incident without new retries", () -> client
        .newResolveIncidentCommand(incidentKey)
        .send()
        .join());
    report.add("job handed out after that: "
        + describe(tryTheJob(client, JOB_TYPE, instance, Duration.ofSeconds(15))));
    cancel(client, instance);

  }

  private void attempt(
      final String what,
      final Callable<?> action) {

    try {
      action.call();
      report.add(what
          + ": accepted");
    } catch (final Exception e) {
      report.add(what
          + ": "
          + String.valueOf(e.getMessage()).replaceAll("\\s+", " "));
    }

  }

  private static String describe(
      final ActivatedJob job) {

    return job == null
        ? "none"
        : "one with %d retries".formatted(job.getRetries());

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
      final CamundaClient client,
      final String process,
      final String retries,
      final String startEventExtensions) {

    client
        .newDeployResourceCommand()
        .addResourceStringUtf8(MODEL.formatted(process, JOB_TYPE, retries, startEventExtensions), process
            + ".bpmn")
        .send()
        .join();

  }

  private static long start(
      final CamundaClient client,
      final String process) {

    return client
        .newCreateInstanceCommand()
        .bpmnProcessId(process)
        .latestVersion()
        .send()
        .join()
        .getProcessInstanceKey();

  }

  private static void cancel(
      final CamundaClient client,
      final long instance) {

    try {
      client
          .newCancelInstanceCommand(instance)
          .send()
          .join();
    } catch (final RuntimeException e) {
      // the cleanup of the shared cluster ends it otherwise
    }

  }

  private static String incidents(
      final CamundaClient client,
      final long instance) throws InterruptedException {

    // the search answers out of the exporter, a moment behind the engine
    Thread.sleep(2000);
    return client
        .newIncidentSearchRequest()
        .filter(filter -> filter.processInstanceKey(instance))
        .send()
        .join()
        .items()
        .stream()
        .map(incident -> "%s (%s)".formatted(incident.getErrorType(), incident.getState()))
        .toList()
        .toString();

  }

  private static long awaitIncident(
      final CamundaClient client,
      final long instance) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline) {
      final var incidents = client
          .newIncidentSearchRequest()
          .filter(filter -> filter.processInstanceKey(instance))
          .send()
          .join()
          .items();
      if (!incidents.isEmpty()) {
        return incidents.getFirst().getIncidentKey();
      }
      Thread.sleep(500);
    }
    throw new AssertionError("no incident of instance "
        + instance
        + " within 120 seconds");

  }

  private static ActivatedJob awaitTheJob(
      final CamundaClient client,
      final long instance) throws InterruptedException {

    final var job = tryTheJob(client, JOB_TYPE, instance, Duration.ofSeconds(120));
    assertNotNull(job, () -> "no job of type '%s' for instance %d within 120 seconds".formatted(JOB_TYPE, instance));
    return job;

  }

  private static ActivatedJob tryTheJob(
      final CamundaClient client,
      final String jobType,
      final long instance,
      final Duration within) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + within.toMillis();
    while (System.currentTimeMillis() < deadline) {
      // the cluster never hands a leased job to an activation which does not ask for a lease.
      // This suite configures 'job-lease: use'
      final var jobs = Camunda8JobLease
          .leaseTheActivation(
              client
                  .newActivateJobsCommand()
                  .jobType(jobType)
                  .maxJobsToActivate(10)
                  .timeout(Duration.ofMinutes(2)))
          .requestTimeout(Duration.ofSeconds(1))
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
    return null;

  }

}
