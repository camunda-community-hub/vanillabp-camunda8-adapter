package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * What the start listener costs the deployed model, measured rather than guessed.
 * <p>
 * Since story 653 the listener sits on EVERY start event of a process, the plain and the
 * message one included, because what a start means is read from the state of the workflow
 * and not from the kind of its start event. On Camunda 8 that listener is written INTO the
 * model at deployment, so every start event of every model this application deploys grows
 * by it. These tests hold the number, so a change shows up here instead of in somebody's
 * deployment.
 * <p>
 * The reasoning is `DECISIONS.pending/653.md`.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8StartListenerCostTest {

  private static final String PROCESS_ID = "order_approval";

  /**
   * A process with the four shapes a start event has on Camunda 8: plain, message, timer
   * and signal. Only the first two were free of the listener before story 653.
   */
  private static final String A_PROCESS_WITH_FOUR_START_EVENTS = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="order_approval" isExecutable="true">
          <bpmn:startEvent id="Event_plain" />
          <bpmn:startEvent id="Event_ordered">
            <bpmn:messageEventDefinition id="Message_ordered" messageRef="Message_1" />
          </bpmn:startEvent>
          <bpmn:startEvent id="Event_nightly">
            <bpmn:timerEventDefinition id="Timer_nightly">
              <bpmn:timeCycle xsi:type="bpmn:tFormalExpression">R/P1D</bpmn:timeCycle>
            </bpmn:timerEventDefinition>
          </bpmn:startEvent>
          <bpmn:startEvent id="Event_branchClosed">
            <bpmn:signalEventDefinition id="Signal_branchClosed" signalRef="Signal_1" />
          </bpmn:startEvent>
        </bpmn:process>
        <bpmn:message id="Message_1" name="OrderReceived" />
        <bpmn:signal id="Signal_1" name="BranchClosed" />
      </bpmn:definitions>
      """;

  private static BpmnModelInstance theModel() {

    return Bpmn
        .readModelFromStream(
            new ByteArrayInputStream(
                A_PROCESS_WITH_FOUR_START_EVENTS.getBytes(StandardCharsets.UTF_8)));

  }

  @Test
  @DisplayName("Every start event of the process is reported, whichever event definition it carries")
  public void everyStartEventIsReported() {

    final var startEvents = Camunda8TaskWiring
        .bpmsInitiatedStartsOf(theModel(), PROCESS_ID, signalName -> signalName);

    assertEquals(
        List
            .of(
                new Camunda8TaskWiring.Camunda8BpmsInitiatedStartToWire(
                    PROCESS_ID, "Event_plain", BpmsStartTrigger.Kind.NONE, null),
                new Camunda8TaskWiring.Camunda8BpmsInitiatedStartToWire(
                    PROCESS_ID, "Event_ordered", BpmsStartTrigger.Kind.MESSAGE, null),
                new Camunda8TaskWiring.Camunda8BpmsInitiatedStartToWire(
                    PROCESS_ID, "Event_nightly", BpmsStartTrigger.Kind.TIMER, null),
                new Camunda8TaskWiring.Camunda8BpmsInitiatedStartToWire(
                    PROCESS_ID, "Event_branchClosed", BpmsStartTrigger.Kind.SIGNAL, "BranchClosed")),
        startEvents,
        () -> "a start is read from the state of the workflow, so every start event is reported: "
            + startEvents);

  }

  @Test
  @DisplayName("Every start event carries exactly one execution listener after the wiring")
  public void everyStartEventCarriesOneListener() {

    final var model = theModel();

    Camunda8TaskWiring.bpmsInitiatedStartsOf(model, PROCESS_ID, signalName -> signalName);

    final var deployed = Bpmn.convertToString(model);
    List
        .of("Event_plain", "Event_ordered", "Event_nightly", "Event_branchClosed")
        .forEach(startEventId -> assertEquals(
            1,
            occurrencesOf(deployed, Camunda8TaskWiring.listenerJobTypeOf(PROCESS_ID, startEventId)),
            () -> "start event '"
                + startEventId
                + "' carries its listener once: "
                + deployed));
    assertEquals(
        4,
        occurrencesOf(deployed, "<zeebe:executionListener "),
        () -> "one listener per start event and not one more: "
            + deployed);

  }

  @Test
  @DisplayName("A start event grows by some 300 bytes, and a second wiring adds nothing")
  public void whatOneStartEventAddsToTheModel() {

    final var plain = Bpmn.convertToString(theModel()).length();

    final var wired = theModel();
    Camunda8TaskWiring.bpmsInitiatedStartsOf(wired, PROCESS_ID, signalName -> signalName);
    final var afterWiring = Bpmn.convertToString(wired).length();

    final var perStartEvent = (afterWiring - plain) / 4;
    // measured: 294 characters per start event with this process id and these element
    // ids. Most of it is the extensionElements wrapper Camunda's model API writes around
    // one listener; the job type carries the process id and the element id, so longer
    // ids cost a little more. The bound is what this test holds, and it is four orders of
    // magnitude below the 4 MB a Camunda 8 deployment may carry
    assertTrue(
        perStartEvent < 400,
        () -> "one start event costs "
            + perStartEvent
            + " characters of the deployed model");
    assertTrue(
        perStartEvent > 100,
        () -> "the listener has to be in there at all, but one start event only grew by "
            + perStartEvent
            + " characters");

    // re-wiring a model which already carries the listener adds nothing, which is what
    // keeps a redeployment of the same resources from growing it every time
    Camunda8TaskWiring.bpmsInitiatedStartsOf(wired, PROCESS_ID, signalName -> signalName);
    assertEquals(
        afterWiring,
        Bpmn.convertToString(wired).length(),
        "wiring the same model twice writes the listener once");

  }

  private static int occurrencesOf(
      final String text,
      final String what) {

    var found = 0;
    var at = text.indexOf(what);
    while (at >= 0) {
      found++;
      at = text.indexOf(what, at + what.length());
    }
    return found;

  }

}
