package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a BPMN model gets to say about the backoff of its own task, through the task header
 * {@value Camunda8RetryBackoffHeader#HEADER_NAME} version 1 read.
 * <p>
 * Every test here drives a job whose handler throws, because the fail command is where the
 * backoff travels to the cluster - the header is read at delivery and nothing about it is
 * visible before a job actually fails.
 * <p>
 * Each test brings its own element id and its own header value, because the captured
 * output belongs to the whole class: what an assertion counts has to be a string only its
 * own test can have produced.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8RetryBackoffHeaderTest {

  private final CamundaClient camundaClient = mock(CamundaClient.class, RETURNS_DEEP_STUBS);

  private final JobClient jobClient = mock(JobClient.class, RETURNS_DEEP_STUBS);

  private final Camunda8Drain drain = new Camunda8Drain("c8", "test-module");

  private ActivatedJob job(
      final String elementId,
      final Map<String, String> customHeaders) {

    final var job = mock(ActivatedJob.class);
    when(job.getKey()).thenReturn(4711L);
    when(job.getRetries()).thenReturn(3);
    when(job.getBpmnProcessId()).thenReturn("TestProcess");
    when(job.getElementId()).thenReturn(elementId);
    when(job.getType()).thenReturn("someTask");
    when(job.getVariablesAsMap()).thenReturn(Map.of("id", "42"));
    when(job.getCustomHeaders()).thenReturn(customHeaders);
    when(job.getDeadline()).thenReturn(System.currentTimeMillis() + 60_000);
    return job;

  }

  private ActivatedJob job(
      final String elementId,
      final String header) {

    return job(elementId, Map.of(Camunda8RetryBackoffHeader.HEADER_NAME, header));

  }

  private WorkflowTaskInvoker failingInvoker() {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.resolveWorkflowAggregateIdName("test-module", "TestProcess")).thenReturn("id");
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("boom"));
    return invoker;

  }

  /**
   * A handler whose configuration answers <code>configured</code>, found at the task level
   * or above it as <code>perTask</code> says.
   */
  private Camunda8JobHandler jobHandlerConfiguring(
      final Duration configured,
      final boolean perTask) {

    return Camunda8JobHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .camundaClient(camundaClient)
        .workflowTaskInvoker(failingInvoker())
        .asyncTaskLockRenewal(Duration.ofHours(1))
        .drain(drain)
        .retryBackoffResolver((
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> new Camunda8RetryBackoffResolver.Configured(configured, perTask))
        .build();

  }

  /**
   * How often <code>what</code> stands in everything logged so far.
   */
  private static int occurrences(
      final CapturedOutput output,
      final String what) {

    return (output.getOut() + output.getErr()).split(Pattern.quote(what), -1).length - 1;

  }

  @Test
  @DisplayName("The task header of the model beats the configuration above the task level")
  public void theHeaderBeatsEveryLevelAboveTheTask() {

    jobHandlerConfiguring(Duration.ofSeconds(20), false).handle(jobClient, job("HeaderWins", "PT2S"));

    verify(jobClient.newFailCommand(4711L).retries(2), times(1)).retryBackoff(Duration.ofSeconds(2));

  }

  @Test
  @DisplayName("Without a header the configured value applies, exactly as before")
  public void withoutAHeaderTheConfigurationApplies() {

    jobHandlerConfiguring(Duration.ofSeconds(20), false).handle(jobClient, job("NoHeader", Map.of()));

    verify(jobClient.newFailCommand(4711L).retries(2), times(1)).retryBackoff(Duration.ofSeconds(20));

  }

  @Test
  @DisplayName("The task level of the configuration beats the header, and one line says so")
  public void theTaskLevelBeatsTheHeader(
      final CapturedOutput output) {

    jobHandlerConfiguring(Duration.ofSeconds(21), true).handle(jobClient, job("TaskLevelWins", "PT3S"));

    verify(jobClient.newFailCommand(4711L).retries(2), times(1)).retryBackoff(Duration.ofSeconds(21));
    final var logged = output.getOut() + output.getErr();
    assertTrue(logged.contains("TaskLevelWins"), "the element is named: "
        + logged);
    assertTrue(
        logged.contains("vanillabp.workflow-modules.test-module.workflows.TestProcess.tasks.someTask"
            + ".adapters.c8.retry-backoff"),
        "the property which wins is named with its full key: "
            + logged);
    assertTrue(logged.contains("PT3S") && logged.contains("PT21S"), "and both values are named: "
        + logged);

  }

  @Test
  @DisplayName("A task level which says the same as the header stays silent")
  public void anAgreementIsNothingToReport(
      final CapturedOutput output) {

    jobHandlerConfiguring(Duration.ofSeconds(4), true).handle(jobClient, job("AgreeingHeader", "PT4S"));

    verify(jobClient.newFailCommand(4711L).retries(2), times(1)).retryBackoff(Duration.ofSeconds(4));
    assertEquals(
        0,
        occurrences(output, "AgreeingHeader"),
        "nothing disagrees, so there is nothing to say about this element");

  }

  @Test
  @DisplayName("A header which is no duration costs the configured value, not the backoff")
  public void anUnreadableHeaderFallsBackToTheConfiguration(
      final CapturedOutput output) {

    jobHandlerConfiguring(Duration.ofSeconds(22), false).handle(jobClient, job("BrokenHeader", "10 seconds"));

    // version 1 answered a typo with Duration.ZERO, which reads like 'no backoff wanted'
    // and hands the job out again at once
    verify(jobClient.newFailCommand(4711L).retries(2), times(1)).retryBackoff(Duration.ofSeconds(22));
    final var logged = output.getOut() + output.getErr();
    assertTrue(
        logged.contains("BrokenHeader") && logged.contains("TestProcess") && logged.contains("test-module"),
        "the element, its process and its workflow module are named: "
            + logged);
    assertTrue(logged.contains("10 seconds"), "and the value which cannot be read: "
        + logged);
    assertTrue(logged.contains("vanillabp.adapters.c8.retry-backoff"), "and the way out: "
        + logged);

  }

  @Test
  @DisplayName("A broken header is reported once, not once per job")
  public void aBrokenHeaderIsReportedOnce(
      final CapturedOutput output) {

    final var handler = jobHandlerConfiguring(Duration.ofSeconds(23), false);
    handler.handle(jobClient, job("RepeatedBrokenHeader", "9 seconds"));
    handler.handle(jobClient, job("RepeatedBrokenHeader", "9 seconds"));
    handler.handle(jobClient, job("RepeatedBrokenHeader", "9 seconds"));

    assertEquals(
        1,
        occurrences(output, "RepeatedBrokenHeader"),
        "three failing jobs of one element, one line about its header");

  }

  @Test
  @DisplayName("A corrected model is judged again, because the value is part of the memory")
  public void aChangedHeaderIsJudgedAgain(
      final CapturedOutput output) {

    final var handler = jobHandlerConfiguring(Duration.ofSeconds(24), false);
    handler.handle(jobClient, job("CorrectedHeader", "8 seconds"));
    handler.handle(jobClient, job("CorrectedHeader", "seven seconds"));

    final var logged = output.getOut() + output.getErr();
    assertTrue(logged.contains("8 seconds") && logged.contains("seven seconds"), "both values are named: "
        + logged);
    assertFalse(
        logged.contains("PT8S"),
        "and neither of them was read as a duration: "
            + logged);

  }

}
