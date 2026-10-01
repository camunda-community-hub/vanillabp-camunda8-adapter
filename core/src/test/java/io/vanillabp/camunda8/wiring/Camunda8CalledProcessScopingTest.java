package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledDecision;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledElement;
import io.vanillabp.camunda8.TestScoping;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the prefixing mode does to the process a call activity names and to the decision a
 * business rule task names, per form that name can take.
 * <p>
 * A static name is an identifier of this workflow module and is prefixed like the process
 * respectively the decision it points at. A name given as a FEEL expression is not an
 * identifier at all: the expression takes up the whole value, so the prefix goes INSIDE it and
 * the application has nothing to write. What the cluster does with that frame is held by
 * {@code Camunda8PrefixInsideAnExpressionCanaryIT}; what this adapter writes into the model is
 * held here.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CalledProcessScopingTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static BpmnModelInstance aCallActivityNaming(
      final String calledProcess) {

    return modelOf("""
        <bpmn:callActivity id="Activity_Call">
          <bpmn:extensionElements>
            <zeebe:calledElement processId='%s' propagateAllChildVariablesEnabled="false" />
          </bpmn:extensionElements>
        </bpmn:callActivity>
        """.formatted(calledProcess));

  }

  private static BpmnModelInstance aBusinessRuleTaskNaming(
      final String calledDecision) {

    return modelOf("""
        <bpmn:businessRuleTask id="Activity_Decide">
          <bpmn:extensionElements>
            <zeebe:calledDecision decisionId='%s' resultVariable="theResult" />
          </bpmn:extensionElements>
        </bpmn:businessRuleTask>
        """.formatted(calledDecision));

  }

  private static BpmnModelInstance modelOf(
      final String element) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, element);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  private static List<String> calledProcessIdsOf(
      final BpmnModelInstance model) {

    return model
        .getModelElementsByType(ZeebeCalledElement.class)
        .stream()
        .map(ZeebeCalledElement::getProcessId)
        .toList();

  }

  private static List<String> calledDecisionIdsOf(
      final BpmnModelInstance model) {

    return model
        .getModelElementsByType(ZeebeCalledDecision.class)
        .stream()
        .map(ZeebeCalledDecision::getDecisionId)
        .toList();

  }

  private static void scope(
      final BpmnModelInstance model,
      final NameClashAvoidance mode) {

    Camunda8Scoping.apply(model, MODULE, "c8", TestScoping.of(mode), null, null);

  }

  /**
   * The called process of a model scoped under {@code use-prefix}, which is what most of the
   * cases below read.
   */
  private static String deployedCallOf(
      final String calledProcess) {

    final var model = aCallActivityNaming(calledProcess);
    scope(model, NameClashAvoidance.USE_PREFIX);
    return calledProcessIdsOf(model).getFirst();

  }

  @Test
  @DisplayName("A statically named called process is prefixed the way the process itself is")
  public void aStaticNameIsPrefixed() {

    assertEquals(
        "loan-approval__PaymentHandling",
        deployedCallOf("PaymentHandling"),
        "the called process is deployed under the prefix as well, so the call has to name it that way");

  }

  @Test
  @DisplayName("A called process named by a FEEL expression gets the prefix inside the expression")
  public void anExpressionCarriesThePrefixInside() {

    assertEquals(
        "=\"loan-approval__\" + string(whichProcess)",
        deployedCallOf("=whichProcess"),
        "a prefix in front of an expression names no process, so it goes into the expression and "
            + "the application writes nothing");

  }

  @Test
  @DisplayName("Every shape an expression can take ends up inside the frame unchanged")
  public void everyShapeOfAnExpressionSurvives() {

    assertEquals(
        "=\"loan-approval__\" + string(if useTheOther then \"TheOtherOne\" else \"PaymentHandling\")",
        deployedCallOf("=if useTheOther then \"TheOtherOne\" else \"PaymentHandling\""),
        "an expression returning one of several ids");
    assertEquals(
        "=\"loan-approval__\" + string(get value(theContext, \"which\"))",
        deployedCallOf("=get value(theContext, \"which\")"),
        "an expression reading a context");
    assertEquals(
        "=\"loan-approval__\" + string(\"Payment\" + theRest)",
        deployedCallOf("=\"Payment\" + theRest"),
        "an expression which composes a text itself");
    // a line break in an ATTRIBUTE is written as a character reference, which is what the
    // modeler writes too: XML turns a literal one into a space while it parses
    assertEquals(
        "=\"loan-approval__\" + string(if useTheOther\n  then \"TheOtherOne\"\n  else \"PaymentHandling\")",
        deployedCallOf("=if useTheOther&#10;  then \"TheOtherOne\"&#10;  else \"PaymentHandling\""),
        "an expression written over several lines keeps its line breaks inside the frame");

  }

  @Test
  @DisplayName("The frame survives the XML the deploy command sends")
  public void theFrameSurvivesBeingSerialised() {

    final var model = aCallActivityNaming(
        "=if useTheOther&#10;  then \"TheOtherOne\"&#10;  else \"PaymentHandling\"");
    scope(model, NameClashAvoidance.USE_PREFIX);

    final var asSent = Bpmn.convertToString(model);
    final var readBack = Bpmn
        .readModelFromStream(new ByteArrayInputStream(asSent.getBytes(StandardCharsets.UTF_8)));

    // a line break inside an attribute may come back as a space: XML normalises an attribute
    // value, and the cluster's parser does the same with what this command sends. FEEL reads
    // either, so what has to hold is the expression and not the whitespace in it
    assertEquals(
        "=\"loan-approval__\" + string(if useTheOther then \"TheOtherOne\" else \"PaymentHandling\")",
        calledProcessIdsOf(readBack).getFirst().replaceAll("\\s+", " "),
        "what the cluster receives is this model serialised, so the frame has to survive that");

  }

  @Test
  @DisplayName("A called decision is prefixed the same way, in both forms")
  public void aCalledDecisionIsScopedLikeACalledProcess() {

    final var aStaticName = aBusinessRuleTaskNaming("TheDecision");
    final var anExpression = aBusinessRuleTaskNaming("=whichDecision");

    scope(aStaticName, NameClashAvoidance.USE_PREFIX);
    scope(anExpression, NameClashAvoidance.USE_PREFIX);

    assertEquals(
        List.of("loan-approval__TheDecision"),
        calledDecisionIdsOf(aStaticName),
        "the DMN files of this module are deployed under prefixed ids, so the task has to name one");
    assertEquals(
        List.of("=\"loan-approval__\" + string(whichDecision)"),
        calledDecisionIdsOf(anExpression),
        "and an expression gets the prefix inside it, like the one of a call activity");

  }

  @Test
  @DisplayName("Without prefixing both forms stay as the modeller wrote them")
  public void withoutPrefixingNothingIsRewritten() {

    final var aStaticName = aCallActivityNaming("PaymentHandling");
    final var anExpression = aCallActivityNaming("=whichProcess");
    final var aDecision = aBusinessRuleTaskNaming("=whichDecision");

    scope(aStaticName, NameClashAvoidance.BY_ADAPTER);
    scope(anExpression, NameClashAvoidance.BY_ADAPTER);
    scope(aDecision, NameClashAvoidance.BY_ADAPTER);

    assertEquals(List.of("PaymentHandling"), calledProcessIdsOf(aStaticName));
    assertEquals(List.of("=whichProcess"), calledProcessIdsOf(anExpression));
    assertEquals(List.of("=whichDecision"), calledDecisionIdsOf(aDecision));

  }

  @Test
  @DisplayName("A call activity naming no process at all is left as it is")
  public void aCallActivityWithoutANameIsLeftAlone() {

    final var model = aCallActivityNaming("");

    scope(model, NameClashAvoidance.USE_PREFIX);

    assertNull(
        model
            .getModelElementsByType(ZeebeCalledElement.class)
            .iterator()
            .next()
            .getDomElement()
            .getAttribute("processId"),
        "the cluster rejects such a model on its own, and a composed name would hide what it rejects");

  }

  @Test
  @DisplayName("A call activity holding nothing but '=' gets no frame around nothing")
  public void anEmptyExpressionIsLeftAlone() {

    assertEquals(
        "=",
        deployedCallOf("="),
        "there is no expression to put the prefix into, and 'string()' would make the cluster's "
            + "refusal harder to read rather than easier");

  }

  @Test
  @DisplayName("The elements named by an expression are read for the message a refused deployment owes")
  public void theElementsNamedByAnExpressionAreRead() {

    assertEquals(
        List.of("Activity_Call"),
        Camunda8Scoping
            .elementIdsNamingTheirTargetByExpression(aCallActivityNaming("=whichProcess"), PROCESS),
        "a parse error of the cluster quotes the frame around this expression, so the boot names "
            + "the element it belongs to");
    assertEquals(
        List.of("Activity_Decide"),
        Camunda8Scoping
            .elementIdsNamingTheirTargetByExpression(aBusinessRuleTaskNaming("=whichDecision"), PROCESS),
        "a business rule task is framed like a call activity and is named like one");
    assertEquals(
        List.of(),
        Camunda8Scoping
            .elementIdsNamingTheirTargetByExpression(aCallActivityNaming("PaymentHandling"), PROCESS),
        "a statically named call carries no expression and nothing is quoted of it");
    assertEquals(
        List.of(),
        Camunda8Scoping
            .elementIdsNamingTheirTargetByExpression(aCallActivityNaming("=whichProcess"), "AnotherProcess"),
        "the reading is per BPMN process, so an element of another process is none of it");

  }

  /**
   * What the deployment reads off a model before anything of it was rewritten, in the mode which
   * prefixes.
   */
  private static List<Camunda8Scoping.ExpressionCarryingThePrefix> alreadyCarryingThePrefix(
      final BpmnModelInstance model,
      final NameClashAvoidance mode) {

    return Camunda8Scoping
        .whatAlreadyCarriesThePrefixInAnExpression(model, MODULE, "c8", TestScoping.of(mode), null, null);

  }

  @Test
  @DisplayName("An expression which composes the prefix itself is found before anything is rewritten")
  public void anExpressionCarryingThePrefixIsFound() {

    final var prefix = Camunda8Scoping
        .prefixOf(MODULE, "c8", TestScoping.of(NameClashAvoidance.USE_PREFIX));
    assertEquals("loan-approval__", prefix, "the prefix is read off a scoped id of the core");

    final var found = alreadyCarryingThePrefix(
        aCallActivityNaming("=\"loan-approval__\" + whichProcess"), NameClashAvoidance.USE_PREFIX);

    assertEquals(1, found.size(), "such an expression would carry the prefix twice after the rewrite");
    assertEquals("Activity_Call", found.getFirst().elementId());
    assertEquals(PROCESS, found.getFirst().bpmnProcessId());
    assertEquals("zeebe:calledElement processId", found.getFirst().attribute());
    assertTrue(
        found.getFirst().expression().contains("loan-approval__"),
        "the entry carries the expression, because the message quotes what to change");
    assertEquals(
        List.of(),
        alreadyCarryingThePrefix(aCallActivityNaming("=whichProcess"), NameClashAvoidance.USE_PREFIX),
        "an expression yielding the plain id is the ordinary case and is rewritten");
    assertEquals(
        List.of(),
        alreadyCarryingThePrefix(
            aCallActivityNaming("loan-approval__PaymentHandling"), NameClashAvoidance.USE_PREFIX),
        "a STATIC name is not asked about here: the collision check of the core is what reads those");
    assertEquals(
        List.of(),
        alreadyCarryingThePrefix(
            aCallActivityNaming("=\"loan-approval__\" + whichProcess"), NameClashAvoidance.BY_ADAPTER),
        "and where nothing is prefixed there is no prefix to find in an expression");

  }

}
