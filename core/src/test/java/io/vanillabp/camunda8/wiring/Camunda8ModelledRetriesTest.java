package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A failed job keeps retries only where its model writes <code>retries</code> on the
 * <code>zeebe:taskDefinition</code> of the element. Without it, the job is failed with no
 * retries left, so the cluster raises the incident at once. Version 1 did the same from 1.7.1 to
 * 1.10.0. See decision 83 in the repository's DECISIONS.md.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ModelledRetriesTest {

  private final CamundaClient camundaClient = mock(CamundaClient.class, RETURNS_DEEP_STUBS);

  private final JobClient jobClient = mock(JobClient.class, RETURNS_DEEP_STUBS);

  private static BpmnModelInstance model(
      final String taskDefinition) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                          xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
                          xmlns:z="http://camunda.org/schema/zeebe/1.0"
                          id="d" targetNamespace="t">
          <bpmn:process id="TestProcess" isExecutable="true">
            <bpmn:serviceTask id="TheTask">
              <bpmn:extensionElements>%s</bpmn:extensionElements>
            </bpmn:serviceTask>
            <bpmn:task id="NoJob" />
          </bpmn:process>
        </bpmn:definitions>
        """.formatted(taskDefinition);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  @Test
  @DisplayName("An element without the attribute asks for no retries")
  public void withoutTheAttribute() {

    assertEquals(
        Boolean.FALSE,
        Camunda8ModelledRetries.retriesModelledFor(model("<zeebe:taskDefinition type=\"t\" />"), "TheTask"));

  }

  @Test
  @DisplayName("An element with the attribute asks for retries, whatever the value")
  public void withTheAttribute() {

    assertEquals(
        Boolean.TRUE,
        Camunda8ModelledRetries
            .retriesModelledFor(model("<zeebe:taskDefinition type=\"t\" retries=\"3\" />"), "TheTask"));
    assertEquals(
        Boolean.TRUE,
        Camunda8ModelledRetries
            .retriesModelledFor(model("<zeebe:taskDefinition type=\"t\" retries=\"=retryCount\" />"), "TheTask"));

  }

  @Test
  @DisplayName("A task definition written with another prefix for the zeebe namespace is read as well")
  public void anotherPrefix() {

    assertEquals(
        Boolean.TRUE,
        Camunda8ModelledRetries.retriesModelledFor(model("<z:taskDefinition type=\"t\" retries=\"5\" />"),
            "TheTask"));

  }

  @Test
  @DisplayName("No model or no such element means: cannot tell")
  public void cannotTell() {

    assertNull(Camunda8ModelledRetries.retriesModelledFor(null, "TheTask"));
    assertNull(Camunda8ModelledRetries.retriesModelledFor(model("<zeebe:taskDefinition type=\"t\" />"), "Unknown"));
    assertEquals(
        Boolean.FALSE,
        Camunda8ModelledRetries.retriesModelledFor(model("<zeebe:taskDefinition type=\"t\" />"), "NoJob"),
        "an element without a task definition writes no retries");

  }

  private static ActivatedJob job(
      final String elementId) {

    final var job = mock(ActivatedJob.class);
    when(job.getKey()).thenReturn(4711L);
    when(job.getRetries()).thenReturn(3);
    when(job.getBpmnProcessId()).thenReturn("TestProcess");
    when(job.getProcessDefinitionVersion()).thenReturn(7);
    when(job.getElementId()).thenReturn(elementId);
    when(job.getType()).thenReturn("someTask");
    when(job.getVariablesAsMap()).thenReturn(Map.of("id", "42"));
    when(job.getCustomHeaders()).thenReturn(Map.of());
    when(job.getDeadline()).thenReturn(System.currentTimeMillis() + 60_000);
    return job;

  }

  private Camunda8JobHandler failingHandler(
      final Camunda8ModelledRetries.Models models) {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.resolveWorkflowAggregateIdName("test-module", "TestProcess")).thenReturn("id");
    when(invoker.invokeWorkflowTask(anyString(), anyString(), any())).thenThrow(new IllegalStateException("boom"));
    return Camunda8JobHandler
        .builder()
        .adapterId("c8")
        .workflowModuleId("test-module")
        .camundaClient(camundaClient)
        .workflowTaskInvoker(invoker)
        .asyncTaskLockRenewal(Duration.ofHours(1))
        .drain(new Camunda8Drain("c8", "test-module"))
        .modelledRetries(models)
        .build();

  }

  @Test
  @DisplayName("A job whose model writes no retries is failed with none left")
  public void noRetriesInTheModelMeansAnIncident(
      final CapturedOutput output) {

    failingHandler((
        workflowModuleId,
        bpmnProcessId,
        version,
        elementId) -> "TestProcess".equals(bpmnProcessId) && (version == 7) && "NoRetries".equals(elementId)
            ? Boolean.FALSE
            : null)
        .handle(jobClient, job("NoRetries"));

    verify(jobClient.newFailCommand(4711L), times(1)).retries(0);
    final var logged = output.getOut() + output.getErr();
    assertTrue(logged.contains("NoRetries") && logged.contains("'retries'"), "the log says why: "
        + logged);

  }

  @Test
  @DisplayName("A job whose model writes retries keeps one less")
  public void retriesInTheModelCountDown() {

    failingHandler((
        workflowModuleId,
        bpmnProcessId,
        version,
        elementId) -> Boolean.TRUE)
        .handle(jobClient, job("WithRetries"));

    verify(jobClient.newFailCommand(4711L), times(1)).retries(2);

  }

  @Test
  @DisplayName("A model which cannot be read leaves the count to Camunda, and says so once")
  public void anUnreadableModelCountsDown(
      final CapturedOutput output) {

    final var handler = failingHandler((
        workflowModuleId,
        bpmnProcessId,
        version,
        elementId) -> null);
    handler.handle(jobClient, job("Unreadable"));
    handler.handle(jobClient, job("Unreadable"));

    verify(jobClient.newFailCommand(4711L), times(2)).retries(2);
    final var logged = output.getOut() + output.getErr();
    assertEquals(
        1,
        logged.split(Pattern.quote("could not be read, so it is unknown whether element 'Unreadable'"), -1).length - 1,
        "one line per element, not one per job: "
            + logged);

  }

}
