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
import io.vanillabp.integration.adapter.spi.expressions.ModelExpression;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the core is told about the expressions of a Camunda 8 model: the element, the place
 * inside it and the text FEEL evaluates. The core judges what each of them costs, so what
 * is asserted here is the reading - every place a modeller puts a data read into, and
 * nothing which only names a target.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ModelExpressionsTest {

  /**
   * One model holding the given process body, plus a second process reading expressions
   * of its own: a file may hold several, and one process must never be told about the
   * other's.
   *
   * @param processContent The elements of the process under test
   * @param messages What the file declares next to its processes
   * @return The model
   */
  private static BpmnModelInstance model(
      final String processContent,
      final String messages) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
        %s
          <bpmn:process id="TestProcess" isExecutable="true">
        %s
          </bpmn:process>
          <bpmn:process id="OtherProcess" isExecutable="true">
            <bpmn:exclusiveGateway id="OtherGateway" default="OtherElse" />
            <bpmn:task id="OtherTask" />
            <bpmn:sequenceFlow id="OtherFlow" sourceRef="OtherGateway" targetRef="OtherTask">
              <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">=somebodyElsesData.flag</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            <bpmn:sequenceFlow id="OtherElse" sourceRef="OtherGateway" targetRef="OtherTask" />
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(messages, processContent);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  /**
   * What the adapter reports for the process under test, one entry per line, so an
   * assertion reads the four things the core is told at once.
   *
   * @param processContent The elements of the process under test
   * @param messages What the file declares next to its processes
   * @return Element, place, the expression as the model has it and the body FEEL evaluates
   */
  private static List<String> reported(
      final String processContent,
      final String messages) {

    return Camunda8ModelExpressions
        .of(model(processContent, messages), "TestProcess")
        .stream()
        .map(expression -> "%s | %s | %s | %s"
            .formatted(
                expression.elementId(),
                expression.place(),
                expression.expression(),
                expression.body()))
        .toList();

  }

  private static List<String> reported(
      final String processContent) {

    return reported(processContent, "");

  }

  @Test
  @DisplayName("The condition of a sequence flow is reported with the flow carrying it")
  public void theConditionOfASequenceFlow() {

    assertEquals(
        List
            .of(
                "Flow_express | SEQUENCE_FLOW_CONDITION | =order.shipping.express | order.shipping.express"),
        reported(
            """
                    <bpmn:exclusiveGateway id="Gateway" default="Flow_else" />
                    <bpmn:task id="Ship" />
                    <bpmn:sequenceFlow id="Flow_express" sourceRef="Gateway" targetRef="Ship">
                      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">=order.shipping.express</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="Flow_else" sourceRef="Gateway" targetRef="Ship" />
                """));

  }

  @Test
  @DisplayName("A timer reports what it is told to wait for, and a plain duration is no expression")
  public void theDefinitionOfATimer() {

    assertEquals(
        List.of("Wait | TIMER | =reminderDelay | reminderDelay"),
        reported("""
                <bpmn:intermediateCatchEvent id="Wait">
                  <bpmn:timerEventDefinition>
                    <bpmn:timeDuration xsi:type="bpmn:tFormalExpression">=reminderDelay</bpmn:timeDuration>
                  </bpmn:timerEventDefinition>
                </bpmn:intermediateCatchEvent>
            """));

    // 'PT1H' is what the modeller meant, not something the cluster evaluates
    assertEquals(
        List.of(),
        reported("""
                <bpmn:intermediateCatchEvent id="Park">
                  <bpmn:timerEventDefinition>
                    <bpmn:timeDuration xsi:type="bpmn:tFormalExpression">PT1H</bpmn:timeDuration>
                  </bpmn:timerEventDefinition>
                </bpmn:intermediateCatchEvent>
            """));

  }

  @Test
  @DisplayName("The collection and the completion condition of a multi-instance element are two places")
  public void theTwoPlacesOfAMultiInstanceElement() {

    final var expressions = reported(
        """
                <bpmn:subProcess id="Items">
                  <bpmn:multiInstanceLoopCharacteristics>
                    <bpmn:extensionElements>
                      <zeebe:loopCharacteristics inputCollection="=order.items" inputElement="item" />
                    </bpmn:extensionElements>
                    <bpmn:completionCondition xsi:type="bpmn:tFormalExpression">=count(order.items) &gt; 3</bpmn:completionCondition>
                  </bpmn:multiInstanceLoopCharacteristics>
                  <bpmn:startEvent id="Items_start" />
                </bpmn:subProcess>
            """);

    // the collection sits in an extension element two levels below the activity, the
    // completion condition in a BPMN element one level below it, and both are reported
    // with the activity a modeller searches their model for
    assertTrue(
        expressions.contains("Items | MULTI_INSTANCE_COLLECTION | =order.items | order.items"),
        expressions.toString());
    assertTrue(
        expressions
            .contains("Items | MULTI_INSTANCE_COMPLETION_CONDITION | =count(order.items) > 3 | count(order.items) > 3"),
        expressions.toString());
    assertEquals(2, expressions.size(), expressions.toString());

  }

  @Test
  @DisplayName("The correlation key of a message is reported with the element waiting for it")
  public void theCorrelationKeyOfAMessage() {

    assertEquals(
        List.of("AwaitDelivery | MESSAGE_CORRELATION_KEY | =order.reference | order.reference"),
        reported(
            """
                    <bpmn:intermediateCatchEvent id="AwaitDelivery">
                      <bpmn:messageEventDefinition messageRef="Delivered" />
                    </bpmn:intermediateCatchEvent>
                """,
            """
                  <bpmn:message id="Delivered" name="Delivered">
                    <bpmn:extensionElements>
                      <zeebe:subscription correlationKey="=order.reference" />
                    </bpmn:extensionElements>
                  </bpmn:message>
                """));

  }

  @Test
  @DisplayName("What only names a target is reported nowhere")
  public void whatNamesATargetIsNoDataRead() {

    // a job type, a called process, a called decision and a form all name something the
    // cluster resolves for itself, and no getter on a workflow aggregate replaces them
    assertEquals(
        List.of(),
        reported("""
                <bpmn:serviceTask id="Work">
                  <bpmn:extensionElements>
                    <zeebe:taskDefinition type="=theTaskToRun" />
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:callActivity id="CallChild">
                  <bpmn:extensionElements>
                    <zeebe:calledElement processId="=theChildToCall" />
                  </bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:businessRuleTask id="Decide">
                  <bpmn:extensionElements>
                    <zeebe:calledDecision decisionId="=theDecisionToEvaluate" />
                  </bpmn:extensionElements>
                </bpmn:businessRuleTask>
                <bpmn:userTask id="Approve">
                  <bpmn:extensionElements>
                    <zeebe:userTask />
                    <zeebe:formDefinition externalReference="=theFormToShow" />
                  </bpmn:extensionElements>
                </bpmn:userTask>
            """));

  }

  @Test
  @DisplayName("A process is never told about the expressions of the process next to it")
  public void onlyTheExpressionsOfOneProcess() {

    final var expressions = reported("""
            <bpmn:task id="Nothing" />
        """);

    // 'OtherProcess' of the same file reads '=somebodyElsesData.flag'
    assertEquals(List.of(), expressions);
    assertEquals(
        List.of("OtherFlow | SEQUENCE_FLOW_CONDITION | =somebodyElsesData.flag | somebodyElsesData.flag"),
        Camunda8ModelExpressions
            .of(model("<bpmn:task id=\"Nothing\" />", ""), "OtherProcess")
            .stream()
            .map(expression -> "%s | %s | %s | %s"
                .formatted(
                    expression.elementId(),
                    expression.place(),
                    expression.expression(),
                    expression.body()))
            .toList());

  }

  @Test
  @DisplayName("A process nobody deployed yields nothing")
  public void anUnknownProcessYieldsNothing() {

    assertEquals(
        List.<ModelExpression>of(),
        Camunda8ModelExpressions.of(model("<bpmn:task id=\"Nothing\" />", ""), "NoSuchProcess"));

  }

}
