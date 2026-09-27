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
import io.vanillabp.integration.adapter.spi.workflowtask.CompensationSpec;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which elements of a model can put a SECOND token into a running workflow -
 * the finding this adapter reports to the core, which turns it into the hint about two
 * writers on one workflow aggregate.
 * <p>
 * Every construct is asserted together with the variant that does NOT produce a second
 * token, since a hint given for a sequential model would be worse than none.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ConcurrentTokensTest {

  private static BpmnModelInstance model(
      final String processContent) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="TestProcess" isExecutable="true">
        %s
          </bpmn:process>
          <bpmn:process id="OtherProcess" isExecutable="true">
            <bpmn:parallelGateway id="OtherFork">
              <bpmn:outgoing>OtherFlow_1</bpmn:outgoing>
              <bpmn:outgoing>OtherFlow_2</bpmn:outgoing>
            </bpmn:parallelGateway>
            <bpmn:task id="OtherTask_1" />
            <bpmn:task id="OtherTask_2" />
            <bpmn:sequenceFlow id="OtherFlow_1" sourceRef="OtherFork" targetRef="OtherTask_1" />
            <bpmn:sequenceFlow id="OtherFlow_2" sourceRef="OtherFork" targetRef="OtherTask_2" />
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(processContent);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  private static List<String> elementsOf(
      final String processContent) {

    return Camunda8TaskWiring.concurrentTokenElementIdsOf(model(processContent), "TestProcess");

  }

  private static List<CompensationSpec> compensationOf(
      final String processContent) {

    return Camunda8TaskWiring.compensationOf(model(processContent), "TestProcess");

  }

  /**
   * Two activities which were compensated, drawn the way a modeller draws them: a
   * compensation boundary event on each, and an association to the handler undoing it.
   */
  private static final String TWO_ACTIVITIES_WITH_A_HANDLER = """
          <bpmn:serviceTask id="Activity_Book" />
          <bpmn:serviceTask id="Activity_Pay" />
          <bpmn:boundaryEvent id="Event_BookWasMade" attachedToRef="Activity_Book">
            <bpmn:compensateEventDefinition id="Compensate_Book" />
          </bpmn:boundaryEvent>
          <bpmn:boundaryEvent id="Event_PaymentWasMade" attachedToRef="Activity_Pay">
            <bpmn:compensateEventDefinition id="Compensate_Pay" />
          </bpmn:boundaryEvent>
          <bpmn:serviceTask id="Activity_CancelBooking" isForCompensation="true" />
          <bpmn:serviceTask id="Activity_RefundPayment" isForCompensation="true" />
          <bpmn:association id="To_CancelBooking" associationDirection="One" sourceRef="Event_BookWasMade" targetRef="Activity_CancelBooking" />
          <bpmn:association id="To_RefundPayment" associationDirection="One" sourceRef="Event_PaymentWasMade" targetRef="Activity_RefundPayment" />
      """;

  @Test
  @DisplayName("A throw event compensating everything names every handler it starts")
  public void aThrowEventCompensatingEverything() {

    final var found = compensationOf("""
            <bpmn:intermediateThrowEvent id="Event_UndoEverything">
              <bpmn:compensateEventDefinition id="Throw_All" />
            </bpmn:intermediateThrowEvent>
        """ + TWO_ACTIVITIES_WITH_A_HANDLER);

    assertEquals(
        List
            .of(
                new CompensationSpec(
                    "Event_UndoEverything", List.of("Activity_CancelBooking", "Activity_RefundPayment"))),
        found);

  }

  @Test
  @DisplayName("A throw event naming ONE activity starts that activity's handler only")
  public void aThrowEventNamingOneActivity() {

    final var found = compensationOf("""
            <bpmn:intermediateThrowEvent id="Event_UndoTheBooking">
              <bpmn:compensateEventDefinition id="Throw_One" activityRef="Activity_Book" />
            </bpmn:intermediateThrowEvent>
        """ + TWO_ACTIVITIES_WITH_A_HANDLER);

    assertEquals(
        List.of(new CompensationSpec("Event_UndoTheBooking", List.of("Activity_CancelBooking"))), found);

  }

  @Test
  @DisplayName("An end event throwing compensation is read like an intermediate one")
  public void anEndEventThrowingCompensation() {

    final var found = compensationOf("""
            <bpmn:endEvent id="Event_EndUndoing">
              <bpmn:compensateEventDefinition id="Throw_End" />
            </bpmn:endEvent>
        """ + TWO_ACTIVITIES_WITH_A_HANDLER);

    assertEquals(
        List
            .of(
                new CompensationSpec(
                    "Event_EndUndoing", List.of("Activity_CancelBooking", "Activity_RefundPayment"))),
        found);

  }

  @Test
  @DisplayName("A throw event inside a subprocess undoes that subprocess only")
  public void aThrowEventInsideASubProcess() {

    final var found = compensationOf(
        """
                <bpmn:subProcess id="SubProcess_Trip">
                  <bpmn:serviceTask id="Activity_Seat" />
                  <bpmn:boundaryEvent id="Event_SeatWasTaken" attachedToRef="Activity_Seat">
                    <bpmn:compensateEventDefinition id="Compensate_Seat" />
                  </bpmn:boundaryEvent>
                  <bpmn:serviceTask id="Activity_CancelSeat" isForCompensation="true" />
                  <bpmn:intermediateThrowEvent id="Event_UndoTheTrip">
                    <bpmn:compensateEventDefinition id="Throw_Trip" />
                  </bpmn:intermediateThrowEvent>
                  <bpmn:association id="To_CancelSeat" associationDirection="One" sourceRef="Event_SeatWasTaken" targetRef="Activity_CancelSeat" />
                </bpmn:subProcess>
            """ + TWO_ACTIVITIES_WITH_A_HANDLER);

    // the two handlers of the process scope are not started by a throw event of the
    // subprocess, so this one starts one handler and is no finding for the caller
    assertEquals(
        List.of(new CompensationSpec("Event_UndoTheTrip", List.of("Activity_CancelSeat"))), found);

  }

  @Test
  @DisplayName("A model without compensation reports nothing")
  public void aModelWithoutCompensation() {

    assertTrue(compensationOf("""
            <bpmn:serviceTask id="Activity_Book" />
            <bpmn:intermediateThrowEvent id="Event_Signal">
              <bpmn:signalEventDefinition id="Signal_1" />
            </bpmn:intermediateThrowEvent>
        """).isEmpty());

  }

  @Test
  @DisplayName("A compensation handler is no concurrent-token element of its own")
  public void aCompensationHandlerIsNoElementOfTheFlatList() {

    final var found = elementsOf("""
            <bpmn:intermediateThrowEvent id="Event_UndoEverything">
              <bpmn:compensateEventDefinition id="Throw_All" />
            </bpmn:intermediateThrowEvent>
        """ + TWO_ACTIVITIES_WITH_A_HANDLER);

    // what compensation means is reported with its shape, so nothing of it leaks into the
    // flat list the other constructs are reported through
    assertTrue(found.isEmpty(), found.toString());

  }

  @Test
  @DisplayName("A boundary event which does not cancel its activity produces a second token")
  public void nonInterruptingBoundaryEvent() {

    final var found = elementsOf("""
            <bpmn:serviceTask id="Activity_Approve" />
            <bpmn:boundaryEvent id="Event_Reminder" cancelActivity="false" attachedToRef="Activity_Approve">
              <bpmn:timerEventDefinition id="Timer_1" />
            </bpmn:boundaryEvent>
            <bpmn:boundaryEvent id="Event_Timeout" attachedToRef="Activity_Approve">
              <bpmn:timerEventDefinition id="Timer_2" />
            </bpmn:boundaryEvent>
        """);

    assertEquals(List.of("Event_Reminder"), found);

  }

  @Test
  @DisplayName("A parallel or inclusive gateway counts when it forks, not when it joins")
  public void forkingGateways() {

    final var found = elementsOf("""
            <bpmn:parallelGateway id="Gateway_Fork">
              <bpmn:outgoing>Flow_1</bpmn:outgoing>
              <bpmn:outgoing>Flow_2</bpmn:outgoing>
            </bpmn:parallelGateway>
            <bpmn:inclusiveGateway id="Gateway_Inclusive">
              <bpmn:outgoing>Flow_3</bpmn:outgoing>
              <bpmn:outgoing>Flow_4</bpmn:outgoing>
            </bpmn:inclusiveGateway>
            <bpmn:parallelGateway id="Gateway_Join">
              <bpmn:incoming>Flow_1</bpmn:incoming>
              <bpmn:incoming>Flow_2</bpmn:incoming>
              <bpmn:outgoing>Flow_5</bpmn:outgoing>
            </bpmn:parallelGateway>
            <bpmn:task id="Task_1" />
            <bpmn:task id="Task_2" />
            <bpmn:task id="Task_3" />
            <bpmn:sequenceFlow id="Flow_1" sourceRef="Gateway_Fork" targetRef="Gateway_Join" />
            <bpmn:sequenceFlow id="Flow_2" sourceRef="Gateway_Fork" targetRef="Gateway_Join" />
            <bpmn:sequenceFlow id="Flow_3" sourceRef="Gateway_Inclusive" targetRef="Task_1" />
            <bpmn:sequenceFlow id="Flow_4" sourceRef="Gateway_Inclusive" targetRef="Task_2" />
            <bpmn:sequenceFlow id="Flow_5" sourceRef="Gateway_Join" targetRef="Task_3" />
        """);

    assertEquals(List.of("Gateway_Fork", "Gateway_Inclusive"), found);

  }

  @Test
  @DisplayName("A multi-instance activity counts when it is parallel, not when it is sequential")
  public void parallelMultiInstance() {

    final var found = elementsOf("""
            <bpmn:serviceTask id="Activity_Parallel">
              <bpmn:multiInstanceLoopCharacteristics isSequential="false" />
            </bpmn:serviceTask>
            <bpmn:serviceTask id="Activity_Sequential">
              <bpmn:multiInstanceLoopCharacteristics isSequential="true" />
            </bpmn:serviceTask>
        """);

    assertEquals(List.of("Activity_Parallel"), found);

  }

  @Test
  @DisplayName("An event subprocess counts when its start event does not interrupt the process")
  public void nonInterruptingEventSubProcess() {

    final var found = elementsOf("""
            <bpmn:subProcess id="SubProcess_Reminder" triggeredByEvent="true">
              <bpmn:startEvent id="Start_Reminder" isInterrupting="false">
                <bpmn:messageEventDefinition id="Message_1" />
              </bpmn:startEvent>
            </bpmn:subProcess>
            <bpmn:subProcess id="SubProcess_Cancel" triggeredByEvent="true">
              <bpmn:startEvent id="Start_Cancel">
                <bpmn:messageEventDefinition id="Message_2" />
              </bpmn:startEvent>
            </bpmn:subProcess>
            <bpmn:subProcess id="SubProcess_Embedded">
              <bpmn:startEvent id="Start_Embedded" />
            </bpmn:subProcess>
        """);

    assertEquals(List.of("SubProcess_Reminder"), found);

  }

  @Test
  @DisplayName("An ad-hoc subprocess is reported, and the activities inside it are not")
  public void adHocSubProcess() {

    final var found = elementsOf("""
            <bpmn:adHocSubProcess id="AdHoc_AdditionalChecks">
              <bpmn:extensionElements>
                <zeebe:adHoc activeElementsCollection="=checksToRun" />
              </bpmn:extensionElements>
              <bpmn:serviceTask id="Activity_CheckFraud">
                <bpmn:extensionElements>
                  <zeebe:taskDefinition type="checkFraud" />
                </bpmn:extensionElements>
              </bpmn:serviceTask>
              <bpmn:serviceTask id="Activity_CheckIncome">
                <bpmn:extensionElements>
                  <zeebe:taskDefinition type="checkIncome" />
                </bpmn:extensionElements>
              </bpmn:serviceTask>
            </bpmn:adHocSubProcess>
        """);

    assertEquals(List.of("AdHoc_AdditionalChecks"), found);

  }

  @Test
  @DisplayName("An ad-hoc subprocess activating exactly one activity is reported all the same")
  public void adHocSubProcessActivatingOneActivity() {

    // the collection is an expression evaluated when the workflow enters the element, so
    // a list of one today is a list of two as soon as the data behind it changes
    final var found = elementsOf("""
            <bpmn:adHocSubProcess id="AdHoc_OneCheck">
              <bpmn:extensionElements>
                <zeebe:adHoc activeElementsCollection="=[&quot;Activity_CheckFraud&quot;]" />
              </bpmn:extensionElements>
              <bpmn:serviceTask id="Activity_CheckFraud">
                <bpmn:extensionElements>
                  <zeebe:taskDefinition type="checkFraud" />
                </bpmn:extensionElements>
              </bpmn:serviceTask>
            </bpmn:adHocSubProcess>
        """);

    assertEquals(List.of("AdHoc_OneCheck"), found);

  }

  @Test
  @DisplayName("An ad-hoc subprocess of the job worker flavour is reported as well")
  public void adHocSubProcessServedByAWorker() {

    // a worker may activate several elements in one result, so this flavour produces
    // concurrent tokens too - whether or not anything serves the job
    final var found = elementsOf("""
            <bpmn:adHocSubProcess id="AdHoc_AgentTools">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="io.camunda.agenticai:aiagent:subprocess:2" />
              </bpmn:extensionElements>
              <bpmn:serviceTask id="Activity_LookUp">
                <bpmn:extensionElements>
                  <zeebe:taskDefinition type="lookUp" />
                </bpmn:extensionElements>
              </bpmn:serviceTask>
            </bpmn:adHocSubProcess>
        """);

    assertEquals(List.of("AdHoc_AgentTools"), found);

  }

  @Test
  @DisplayName("A sequential model reports nothing, and another process' elements never leak in")
  public void aSequentialProcessReportsNothing() {

    final var found = elementsOf("""
            <bpmn:startEvent id="Start" />
            <bpmn:exclusiveGateway id="Gateway_Decision">
              <bpmn:outgoing>Flow_1</bpmn:outgoing>
              <bpmn:outgoing>Flow_2</bpmn:outgoing>
            </bpmn:exclusiveGateway>
            <bpmn:task id="Task_1" />
            <bpmn:task id="Task_2" />
            <bpmn:sequenceFlow id="Flow_1" sourceRef="Gateway_Decision" targetRef="Task_1" />
            <bpmn:sequenceFlow id="Flow_2" sourceRef="Gateway_Decision" targetRef="Task_2" />
        """);

    // the forking parallel gateway of 'OtherProcess' belongs to the other process
    assertTrue(found.isEmpty(), found.toString());

  }

}
