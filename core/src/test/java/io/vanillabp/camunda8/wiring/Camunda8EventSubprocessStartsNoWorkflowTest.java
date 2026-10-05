package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * A start event inside an event subprocess is no start of a WORKFLOW. It fires while the
 * workflow is already running and already has its aggregate, so the application owes it
 * neither a <code>&#64;WorkflowStartedByBpms</code> method nor a second aggregate.
 * <p>
 * One walk answers both: what the core is told about a process, and which start events
 * the model leaves the adapter with an execution listener on.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8EventSubprocessStartsNoWorkflowTest {

  private static final String PROCESS_ID = "order_approval";

  private static final String PROCESS_START = "Event_nightly";

  private static final String EVENT_SUBPROCESS_START = "Event_customerCalled";

  /**
   * A process the cluster starts every night, with an event subprocess the cluster also
   * fires on its own once the workflow runs.
   */
  private static final String A_PROCESS_WITH_AN_EVENT_SUBPROCESS = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="order_approval" isExecutable="true">
          <bpmn:startEvent id="Event_nightly">
            <bpmn:timerEventDefinition id="Timer_nightly">
              <bpmn:timeCycle xsi:type="bpmn:tFormalExpression">R/P1D</bpmn:timeCycle>
            </bpmn:timerEventDefinition>
          </bpmn:startEvent>
          <bpmn:subProcess id="Activity_takeOver" triggeredByEvent="true">
            <bpmn:startEvent id="Event_customerCalled" isInterrupting="true">
              <bpmn:timerEventDefinition id="Timer_called">
                <bpmn:timeDuration xsi:type="bpmn:tFormalExpression">PT1H</bpmn:timeDuration>
              </bpmn:timerEventDefinition>
            </bpmn:startEvent>
          </bpmn:subProcess>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static BpmnModelInstance theModel() {

    return Bpmn
        .readModelFromStream(
            new ByteArrayInputStream(
                A_PROCESS_WITH_AN_EVENT_SUBPROCESS.getBytes(StandardCharsets.UTF_8)));

  }

  @Test
  @DisplayName("The core hears about the timer of the process and not about the timer of its event subprocess")
  public void onlyTheStartEventOfTheProcessIsReported() {

    final var startEvents = Camunda8TaskWiring
        .bpmsInitiatedStartsOf(theModel(), PROCESS_ID, signalName -> signalName);

    assertEquals(
        List
            .of(
                new Camunda8TaskWiring.Camunda8BpmsInitiatedStartToWire(
                    PROCESS_ID, PROCESS_START, BpmsStartTrigger.Kind.TIMER, null)),
        startEvents,
        () -> "the event subprocess fires inside a running workflow, so it starts none: "
            + startEvents);

  }

  @Test
  @DisplayName("The execution listener building an aggregate is injected into the start event of the process and into no other")
  public void onlyTheStartEventOfTheProcessGetsTheListener() {

    final var model = theModel();

    Camunda8TaskWiring.bpmsInitiatedStartsOf(model, PROCESS_ID, signalName -> signalName);

    final var deployed = Bpmn.convertToString(model);
    assertTrue(
        deployed.contains(Camunda8TaskWiring.listenerJobTypeOf(PROCESS_ID, PROCESS_START)),
        () -> "the workflow the cluster starts every night needs its aggregate built: "
            + deployed);
    assertFalse(
        deployed.contains(Camunda8TaskWiring.listenerJobTypeOf(PROCESS_ID, EVENT_SUBPROCESS_START)),
        () -> "an aggregate built here would be the second one of a workflow which already "
            + "has its own: "
            + deployed);

  }

}
