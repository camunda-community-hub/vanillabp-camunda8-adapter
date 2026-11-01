package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.StartEvent;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The execution listeners VanillaBP writes into a process carry one retry, like the listeners
 * of a user task. Without the attribute the cluster gives each of them three. Nobody modelled
 * these listeners, so nobody asked for a second attempt. The one retry is what a gateway hands
 * back when it lost a delivery. See decision 83 in the repository's DECISIONS.md.
 * <p>
 * Which number a failure leaves is a question for the handlers. The shutdown and command tests
 * of the start and the end listener hold that it is none.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8RetriesOfTheProcessListenersTest {

  private static final String PROCESS = "RetriesProcess";

  private static BpmnModelInstance model() {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="RetriesProcess" isExecutable="true">
            <bpmn:startEvent id="start" />
          </bpmn:process>
        </bpmn:definitions>
        """;
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  private static List<ZeebeExecutionListener> listenersOfTheProcess(
      final BpmnModelInstance model) {

    return List.copyOf(model
        .getModelElementsByType(Process.class)
        .iterator()
        .next()
        .getSingleExtensionElement(ZeebeExecutionListeners.class)
        .getExecutionListeners());

  }

  @Test
  @DisplayName("The listener on a start event carries one retry")
  public void theStartListenerCarriesOneRetry() {

    final var model = model();

    Camunda8TaskWiring.bpmsInitiatedStartsOf(model, PROCESS, UnaryOperator.identity());

    final var listeners = model
        .getModelElementsByType(StartEvent.class)
        .iterator()
        .next()
        .getSingleExtensionElement(ZeebeExecutionListeners.class)
        .getExecutionListeners();
    assertEquals(1, listeners.size());
    assertEquals(
        "1",
        listeners.iterator().next().getRetries(),
        "the aggregate is built here, and that may write");

  }

  @Test
  @DisplayName("The end listener of the process carries one retry")
  public void theEndListenerCarriesOneRetry() {

    final var model = model();

    assertTrue(Camunda8TaskWiring.attachWorkflowEndedListener(model, PROCESS));

    final var listeners = listenersOfTheProcess(model);
    assertEquals(1, listeners.size());
    assertEquals("1", listeners.getFirst().getRetries(), "a @WorkflowEnded method may write");

  }

  @Test
  @DisplayName("The cancel listener of the process carries the retry of the end listener next to it")
  public void theCancelListenerCarriesOneRetry() {

    if (!Camunda8CancelListeners.theProcessCanReportItsCancellation()) {
      // the lines before 8.10 have no cancel listener on the process
      return;
    }
    final var model = model();

    assertTrue(Camunda8TaskWiring.attachWorkflowEndedListener(model, PROCESS));
    assertTrue(Camunda8TaskWiring.attachWorkflowCanceledListener(model, PROCESS));

    final var listeners = listenersOfTheProcess(model);
    assertEquals(2, listeners.size());
    listeners.forEach(listener -> assertEquals(
        "1",
        listener.getRetries(),
        "a @WorkflowEnded method may write, at the end and at a cancelation alike"));

  }

}
