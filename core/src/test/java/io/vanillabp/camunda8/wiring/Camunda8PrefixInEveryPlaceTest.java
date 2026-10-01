package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.Error;
import io.camunda.zeebe.model.bpmn.instance.Escalation;
import io.camunda.zeebe.model.bpmn.instance.Message;
import io.camunda.zeebe.model.bpmn.instance.Signal;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeFormDefinition;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskDefinition;
import io.vanillabp.camunda8.TestScoping;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The one rule, at every place this adapter writes a prefix: a value written as a FEEL
 * expression gets the prefix INSIDE the expression, and every other value gets it in front.
 * <p>
 * Which of those places Camunda 8 evaluates an expression at is deliberately not asked. A list
 * of them would age with every Camunda release, while the rule cannot be wrong: where no
 * expression is evaluated a value starting with {@code =} does not appear, and where one is
 * evaluated later nothing here has to change. So every place is held to the same two forms
 * below.
 * <p>
 * A job type is the place where the prefix is more than the workflow module's, because a task
 * definition is scoped by its BPMN process as well. The frame has to carry that longer prefix,
 * which is what one of the cases reads.
 * <p>
 * The cluster is not asked anything here. What this adapter writes into a model is read back out
 * of the model.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8PrefixInEveryPlaceTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  /**
   * A model carrying one of every place a prefix is written, each of them given the value the
   * caller passes, so one text can be put through all of them.
   */
  private static BpmnModelInstance aModelWriting(
      final String value) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:message id="Message_Approved" name="%1$s" />
          <bpmn:signal id="Signal_Cancelled" name="%1$s" />
          <bpmn:error id="Error_Rejected" errorCode="%1$s" />
          <bpmn:escalation id="Escalation_Overdue" escalationCode="%1$s" />
          <bpmn:process id="%2$s" isExecutable="true">
            <bpmn:serviceTask id="Activity_Approve">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="%1$s" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
            <bpmn:userTask id="Activity_Sign">
              <bpmn:extensionElements>
                <zeebe:userTask />
                <zeebe:formDefinition externalReference="%1$s" />
              </bpmn:extensionElements>
            </bpmn:userTask>
            <bpmn:endEvent id="Event_Done">
              <bpmn:extensionElements>
                <zeebe:executionListeners>
                  <zeebe:executionListener eventType="end" type="%1$s" />
                </zeebe:executionListeners>
              </bpmn:extensionElements>
            </bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(value, PROCESS);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  /**
   * A model whose listener job type is served by this application, which is what makes it a task
   * definition of the workflow module.
   */
  private static final BiPredicate<String, String> everyListenerIsServed = (
      bpmnProcessId,
      jobType) -> true;

  private static void scope(
      final BpmnModelInstance model,
      final NameClashAvoidance mode) {

    Camunda8Scoping.apply(model, MODULE, "c8", TestScoping.of(mode), null, everyListenerIsServed);

  }

  /**
   * What the model says at each of the places, in the order the cases below read them.
   */
  private static List<String> whatTheModelSaysEverywhere(
      final BpmnModelInstance model) {

    return List
        .of(
            model.getModelElementsByType(Message.class).iterator().next().getName(),
            model.getModelElementsByType(Signal.class).iterator().next().getName(),
            model.getModelElementsByType(Error.class).iterator().next().getErrorCode(),
            model.getModelElementsByType(Escalation.class).iterator().next().getEscalationCode(),
            model.getModelElementsByType(ZeebeTaskDefinition.class).iterator().next().getType(),
            model.getModelElementsByType(ZeebeFormDefinition.class).iterator().next().getExternalReference(),
            model.getModelElementsByType(ZeebeExecutionListener.class).iterator().next().getType());

  }

  @Test
  @DisplayName("A static value gets the prefix in front of it, at every place")
  public void aStaticValueIsPrefixed() {

    final var model = aModelWriting("theValue");

    scope(model, NameClashAvoidance.USE_PREFIX);

    assertEquals(
        List
            .of(
                "loan-approval__theValue",
                "loan-approval__theValue",
                "loan-approval__theValue",
                "loan-approval__theValue",
                "loan-approval__LoanApproval__theValue",
                "loan-approval__LoanApproval__theValue",
                "loan-approval__LoanApproval__theValue"),
        whatTheModelSaysEverywhere(model),
        "a name the workflow module scopes carries its prefix, and a job type carries the "
            + "process' as well");

  }

  @Test
  @DisplayName("A value written as an expression gets the prefix inside it, at every place")
  public void anExpressionCarriesThePrefixInside() {

    final var model = aModelWriting("=whichOne");

    scope(model, NameClashAvoidance.USE_PREFIX);

    assertEquals(
        List
            .of(
                "=\"loan-approval__\" + string(whichOne)",
                "=\"loan-approval__\" + string(whichOne)",
                "=\"loan-approval__\" + string(whichOne)",
                "=\"loan-approval__\" + string(whichOne)",
                "=\"loan-approval__LoanApproval__\" + string(whichOne)",
                "=\"loan-approval__LoanApproval__\" + string(whichOne)",
                "=\"loan-approval__LoanApproval__\" + string(whichOne)"),
        whatTheModelSaysEverywhere(model),
        "the prefix of the place goes into the expression, so a job type is framed with the "
            + "longer one");

  }

  @Test
  @DisplayName("Without prefixing nothing is rewritten, whichever form the value has")
  public void withoutPrefixingNothingIsRewritten() {

    final var aStaticValue = aModelWriting("theValue");
    final var anExpression = aModelWriting("=whichOne");

    scope(aStaticValue, NameClashAvoidance.BY_ADAPTER);
    scope(anExpression, NameClashAvoidance.BY_ADAPTER);

    assertTrue(
        whatTheModelSaysEverywhere(aStaticValue).stream().allMatch("theValue"::equals),
        () -> "a tenant keeps the modules apart, so nothing of the model is touched: "
            + whatTheModelSaysEverywhere(aStaticValue));
    assertTrue(
        whatTheModelSaysEverywhere(anExpression).stream().allMatch("=whichOne"::equals),
        () -> "and an expression is not framed either: "
            + whatTheModelSaysEverywhere(anExpression));

  }

  @Test
  @DisplayName("An expression which composes the prefix itself is refused at every place")
  public void anExpressionCarryingThePrefixIsFoundEverywhere() {

    final var found = Camunda8Scoping
        .whatAlreadyCarriesThePrefixInAnExpression(
            aModelWriting("=&quot;loan-approval__&quot; + whichOne"),
            MODULE,
            "c8",
            TestScoping.of(NameClashAvoidance.USE_PREFIX),
            null,
            everyListenerIsServed);

    assertEquals(
        List
            .of(
                "zeebe:executionListener type",
                "zeebe:taskDefinition type",
                "zeebe:formDefinition externalReference",
                "bpmn:message name",
                "bpmn:signal name",
                "bpmn:escalation escalationCode",
                "bpmn:error errorCode"),
        found
            .stream()
            .map(Camunda8Scoping.ExpressionCarryingThePrefix::attribute)
            .toList(),
        () -> "every place the rewrite reaches is guarded, because every one of them would carry "
            + "the prefix twice: "
            + found);
    assertTrue(
        found
            .stream()
            .allMatch(carrying -> carrying.describe().contains("loan-approval__")),
        () -> "and each entry quotes the expression, because the message says what to take out: "
            + found);

  }

  @Test
  @DisplayName("A job type whose expression composes the module's prefix alone is refused too")
  public void halfThePrefixInAJobTypeIsRefusedAsWell() {

    final var found = Camunda8Scoping
        .whatAlreadyCarriesThePrefixInAnExpression(
            aModelWriting("=&quot;loan-approval__&quot; + whichOne"),
            MODULE,
            "c8",
            TestScoping.of(NameClashAvoidance.USE_PREFIX),
            null,
            everyListenerIsServed);

    assertTrue(
        found
            .stream()
            .anyMatch(carrying -> "zeebe:taskDefinition type".equals(carrying.attribute())),
        () -> "a job type carries the module's prefix and the process' one, and an expression "
            + "composing either of them ends up with it twice: "
            + found);

  }

  @Test
  @DisplayName("An expression yielding the plain value is the ordinary case and is framed")
  public void anOrdinaryExpressionIsNotRefused() {

    final var found = Camunda8Scoping
        .whatAlreadyCarriesThePrefixInAnExpression(
            aModelWriting("=whichOne"),
            MODULE,
            "c8",
            TestScoping.of(NameClashAvoidance.USE_PREFIX),
            null,
            everyListenerIsServed);

    assertEquals(List.of(), found, "nothing in it composes a prefix, so there is nothing to refuse");

  }

  @Test
  @DisplayName("The job type of a listener nothing serves is left as the modeller wrote it")
  public void anUnservedListenerJobTypeIsLeftAlone() {

    final var model = aModelWriting("=whichOne");

    Camunda8Scoping.apply(model, MODULE, "c8", TestScoping.of(NameClashAvoidance.USE_PREFIX), null, (
        bpmnProcessId,
        jobType) -> false);

    assertEquals(
        "=whichOne",
        model.getModelElementsByType(ZeebeExecutionListener.class).iterator().next().getType(),
        "that job type names something this application does not own, so renaming it would rename "
            + "somebody else's worker");

  }

  @Test
  @DisplayName("A value holding nothing but '=' gets no frame around nothing, at every place")
  public void anEmptyExpressionIsLeftAloneEverywhere() {

    final var model = aModelWriting("=");

    scope(model, NameClashAvoidance.USE_PREFIX);

    assertTrue(
        whatTheModelSaysEverywhere(model).stream().allMatch("="::equals),
        () -> "the cluster refuses such a model itself, and 'string()' would make its answer "
            + "harder to read rather than easier: "
            + whatTheModelSaysEverywhere(model));

  }

}
