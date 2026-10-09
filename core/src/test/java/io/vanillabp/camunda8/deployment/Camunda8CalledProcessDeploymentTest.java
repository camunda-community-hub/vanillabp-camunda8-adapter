package io.vanillabp.camunda8.deployment;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.StartEvent;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners;
import io.vanillabp.camunda8.Camunda8ProcessingContext;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring.Camunda8BpmsInitiatedStartToWire;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A called process is no workflow of its own, so its model gets neither the start listener
 * nor the end listener, and no worker waits for either. The process which calls it gets
 * both. Which process is a called one is the core's answer, so the doubles here answer the
 * way the core does for a process declared only in <code>secondaryBpmnProcesses</code>.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CalledProcessDeploymentTest {

  private static final String MODULE = "test-module";

  private static final String CALLER_AND_CALLED = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="Ordering" isExecutable="true">
          <bpmn:startEvent id="OrderingStart" />
          <bpmn:callActivity id="CallShipping">
            <bpmn:extensionElements>
              <zeebe:calledElement processId="Shipping" />
            </bpmn:extensionElements>
          </bpmn:callActivity>
          <bpmn:endEvent id="OrderingEnd" />
        </bpmn:process>
        <bpmn:process id="Shipping" isExecutable="true">
          <bpmn:startEvent id="ShippingStart" />
          <bpmn:serviceTask id="Ship">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="ship" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
          <bpmn:endEvent id="ShippingEnd" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  private record Wired(
                       List<BpmnModelInstance> models,
                       Camunda8ProcessingContext context) {

    private Process process(
        final String bpmnProcessId) {

      return models
          .stream()
          .flatMap(model -> model.getModelElementsByType(Process.class).stream())
          .filter(candidate -> bpmnProcessId.equals(candidate.getId()))
          .findFirst()
          .orElseThrow();

    }

    /**
     * The listeners on the start events of the process, as "&lt;event&gt;:&lt;job type&gt;".
     */
    List<String> whatTheStartEventsCarry(
        final String bpmnProcessId) {

      return process(bpmnProcessId)
          .getChildElementsByType(StartEvent.class)
          .stream()
          .map(startEvent -> startEvent.getSingleExtensionElement(ZeebeExecutionListeners.class))
          .filter(java.util.Objects::nonNull)
          .flatMap(listeners -> listeners.getExecutionListeners().stream())
          .map(listener -> "%s:%s".formatted(listener.getEventType(), listener.getType()))
          .toList();

    }

    /**
     * The listeners on the process element, as "&lt;event&gt;:&lt;job type&gt;".
     */
    List<String> whatTheProcessCarries(
        final String bpmnProcessId) {

      final var listeners = process(bpmnProcessId).getSingleExtensionElement(ZeebeExecutionListeners.class);
      if (listeners == null) {
        return List.of();
      }
      return listeners
          .getExecutionListeners()
          .stream()
          .map(listener -> "%s:%s".formatted(listener.getEventType(), listener.getType()))
          .toList();

    }

  }

  private static Wired wire() {

    final var invoker = new Camunda8DeploymentServiceTest.NoOpInvoker() {

      @Override
      public String resolveWorkflowAggregateIdName(
          final String workflowModuleId,
          final String bpmnProcessId) {

        return "orderId";

      }

      @Override
      public boolean workflowTaskHandlerExists(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String taskDefinition) {

        return true;

      }

      @Override
      public Collection<String> taskParameterNames(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String taskDefinitionOrActivityId) {

        return List.of();

      }

    };
    // what the core answers for 'Shipping', which the application declares only in
    // secondaryBpmnProcesses: its start and its end are steps of the workflow of 'Ordering'
    final var workflowEnded = mock(WorkflowEndedInvoker.class);
    when(workflowEnded.workflowEndedHandlerExists(anyString(), anyString())).thenReturn(true);
    when(workflowEnded.workflowEndedHandlerExists(anyString(), eq("Shipping"))).thenReturn(false);
    final var bpmsInitiatedStarts = mock(BpmsInitiatedStartInvoker.class);
    when(bpmsInitiatedStarts.startsAWorkflowOfItsOwn(anyString(), anyString())).thenReturn(true);
    when(bpmsInitiatedStarts.startsAWorkflowOfItsOwn(anyString(), eq("Shipping"))).thenReturn(false);

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:65535");
    final var deploymentService = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(invoker, workflowEnded, bpmsInitiatedStarts),
        (
            m,
            p,
            t) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofHours(1),
        adapterId -> configuration);

    final var models = deploymentService
        .readBpmn(MODULE, "ordering.bpmn", new ByteArrayInputStream(CALLER_AND_CALLED.getBytes(UTF_8)), true);
    Camunda8ProcessingContext context = null;
    for (final var model : models) {
      context = deploymentService.prepareBpmn(MODULE, context, "ordering.bpmn", model.getKey(), model.getValue());
      deploymentService.wireBpmn(MODULE, "ordering.bpmn", model.getKey(), model.getValue(), context);
    }
    return new Wired(models.stream().map(java.util.Map.Entry::getValue).toList(), context);

  }

  @Test
  @DisplayName("The start of a called process gets no listener and no worker, the caller's start gets both")
  public void aCalledProcessGetsNoStartListener() {

    final var wired = wire();

    assertEquals(1, wired.whatTheStartEventsCarry("Ordering").size(),
        wired.whatTheStartEventsCarry("Ordering").toString());
    assertEquals(List.of(), wired.whatTheStartEventsCarry("Shipping"));
    assertEquals(
        List.of("Ordering"),
        wired
            .context()
            .getBpmsInitiatedStartsToWire()
            .stream()
            .map(Camunda8BpmsInitiatedStartToWire::bpmnProcessId)
            .distinct()
            .toList(),
        "a listener nobody answers would hold the called instance for good");

  }

  @Test
  @DisplayName("The end of a called process gets no end listener, the caller's end gets one")
  public void aCalledProcessGetsNoEndListener() {

    final var wired = wire();

    assertTrue(
        wired.whatTheProcessCarries("Ordering").stream().anyMatch(listener -> listener.startsWith("end:")),
        wired.whatTheProcessCarries("Ordering").toString());
    assertTrue(
        wired.whatTheProcessCarries("Shipping").stream().noneMatch(listener -> listener.startsWith("end:")),
        "the called process may keep a cancel listener for its open tasks, never an end listener: "
            + wired.whatTheProcessCarries("Shipping"));

  }

}
