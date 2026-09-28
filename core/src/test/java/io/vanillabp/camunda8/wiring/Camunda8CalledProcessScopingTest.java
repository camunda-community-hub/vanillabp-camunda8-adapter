package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledElement;
import io.vanillabp.camunda8.TestScoping;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the prefixing mode does to the process a call activity names, per form that name can
 * take.
 * <p>
 * A static name is an identifier of this workflow module and is prefixed like the process it
 * points at. A name given as a FEEL expression is not an identifier at all: the expression
 * takes up the whole value, so a prefix written in front of it makes a string which names no
 * process and is no expression either. Such a name is left as the application wrote it, and
 * the application composes the prefixed id itself - the same thing that happens to the
 * decision id of a business rule task.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CalledProcessScopingTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static BpmnModelInstance aCallActivityNaming(
      final String calledProcess) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:callActivity id="Activity_Call">
              <bpmn:extensionElements>
                <zeebe:calledElement processId="%s" propagateAllChildVariablesEnabled="false" />
              </bpmn:extensionElements>
            </bpmn:callActivity>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, calledProcess);
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

  private static void scope(
      final BpmnModelInstance model,
      final NameClashAvoidance mode) {

    Camunda8Scoping.apply(model, MODULE, "c8", TestScoping.of(mode), null, null);

  }

  @Test
  @DisplayName("A statically named called process is prefixed the way the process itself is")
  public void aStaticNameIsPrefixed() {

    final var model = aCallActivityNaming("PaymentHandling");

    scope(model, NameClashAvoidance.USE_PREFIX);

    assertEquals(
        List.of("loan-approval__PaymentHandling"),
        calledProcessIdsOf(model),
        "the called process is deployed under the prefix as well, so the call has to name it that way");

  }

  @Test
  @DisplayName("A called process named by a FEEL expression keeps the expression the application wrote")
  public void anExpressionIsLeftAlone() {

    final var model = aCallActivityNaming("=whichProcess");

    scope(model, NameClashAvoidance.USE_PREFIX);

    assertEquals(
        List.of("=whichProcess"),
        calledProcessIdsOf(model),
        "a prefix in front of an expression makes a string which names no process and is no "
            + "expression either - so the application composes the prefixed id inside its FEEL");

  }

  @Test
  @DisplayName("Without prefixing both forms stay as the modeller wrote them")
  public void withoutPrefixingNothingIsRewritten() {

    final var aStaticName = aCallActivityNaming("PaymentHandling");
    final var anExpression = aCallActivityNaming("=whichProcess");

    scope(aStaticName, NameClashAvoidance.BY_ADAPTER);
    scope(anExpression, NameClashAvoidance.BY_ADAPTER);

    assertEquals(List.of("PaymentHandling"), calledProcessIdsOf(aStaticName));
    assertEquals(List.of("=whichProcess"), calledProcessIdsOf(anExpression));

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
  @DisplayName("The call activities naming their process by expression are reported per process")
  public void theExpressionsAreReadForTheMessage() {

    assertEquals(
        List.of("Activity_Call"),
        Camunda8Scoping
            .callActivityIdsNamingTheirProcessByExpression(aCallActivityNaming("=whichProcess"), PROCESS),
        "the deployment names them, because nothing else in the boot notices such a call");
    assertEquals(
        List.of(),
        Camunda8Scoping
            .callActivityIdsNamingTheirProcessByExpression(aCallActivityNaming("PaymentHandling"), PROCESS),
        "a statically named call gets its prefix and needs no word");
    assertEquals(
        List.of(),
        Camunda8Scoping
            .callActivityIdsNamingTheirProcessByExpression(aCallActivityNaming("=whichProcess"), "AnotherProcess"),
        "the report is per BPMN process, so a call of another process is none of it");

  }

}
