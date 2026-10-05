package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.TestScoping;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.camunda8.wiring.Camunda8Scoping;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a boot does with a call activity which names the process it calls by a FEEL expression,
 * now that {@code use-prefix} writes the prefix into that expression.
 * <p>
 * Two things are owed. An expression which composes the prefix ITSELF would carry it twice
 * after the rewrite, so the file is refused with the expression to change; an earlier
 * VanillaBP 2 snapshot asked applications to compose it, which is the only way such a model
 * comes about. And where the cluster refuses a deployment over a parse error, the answer
 * quotes the frame this adapter wrote, so the message says what the quote includes.
 * <p>
 * The cluster is an address nothing listens on. Everything asserted here is read from the
 * model and from what the boot wrote.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8PrefixInsideAnExpressionTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  private static BpmnModelInstance aCallActivityNaming(
      final String calledProcess) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:callActivity id="Activity_CallTheOtherProcess">
              <bpmn:extensionElements>
                <zeebe:calledElement processId='%s' propagateAllChildVariablesEnabled="false" />
              </bpmn:extensionElements>
            </bpmn:callActivity>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, calledProcess);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  /**
   * Prepares and wires one model in the given mode, the way the core does it.
   */
  private static void boot(
      final NameClashAvoidance mode,
      final BpmnModelInstance model) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:65535");
    final var scoping = TestScoping.of(mode);
    final var service = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(new Camunda8DeploymentServiceTest.NoOpInvoker(), scoping),
        (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration.ofHours(1), adapterId -> configuration, scoping);
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
    service.wireBpmn(MODULE, FILE, PROCESS, model, context);

  }

  @Test
  @DisplayName("An expression which composes the prefix itself ends the boot, naming what to take out")
  public void anExpressionCarryingThePrefixEndsTheBoot() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> boot(
            NameClashAvoidance.USE_PREFIX,
            aCallActivityNaming("=\"loan-approval__\" + whichProcess")),
        "the rewrite would give such an expression a second prefix, and every call of it would "
            + "fail once a workflow reached the element");
    final var said = refused.getMessage();

    assertTrue(
        said.contains("'Activity_CallTheOtherProcess'"),
        () -> "a developer has to read WHICH element it is about: "
            + said);
    assertTrue(
        said.contains("\"loan-approval__\" + whichProcess"),
        () -> "and the expression to change, as it stands in the model: "
            + said);
    assertTrue(
        said.contains("loan-approval.bpmn"),
        () -> "the file is what they open: "
            + said);
    assertTrue(
        said.contains("vanillabp.adapters.c8.name-clash-avoidance"),
        () -> "the message names the key which put the workflow module into this mode: "
            + said);
    assertTrue(
        said.contains("loan-approval__loan-approval__TheIdentifierYouWrote"),
        () -> "and shows what the cluster would have been given: "
            + said);

  }

  @Test
  @DisplayName("An expression yielding the plain id is the ordinary case and deploys")
  public void anExpressionYieldingThePlainIdIsFine() {

    assertDoesNotThrow(
        () -> boot(NameClashAvoidance.USE_PREFIX, aCallActivityNaming("=whichProcess")),
        "this adapter writes the prefix into it, so there is nothing for the application to do");

  }

  @Test
  @DisplayName("Without prefixing an expression mentioning the module id is nobody's business")
  public void withoutPrefixingNothingIsRefused() {

    assertDoesNotThrow(
        () -> boot(
            NameClashAvoidance.BY_ADAPTER,
            aCallActivityNaming("=\"loan-approval__\" + whichProcess")),
        "there is no prefix in this mode, so the expression yields whatever the cluster holds");

  }

  @Test
  @DisplayName("A refused deployment says what the expressions in the cluster's answer include")
  public void aRefusedDeploymentNamesTheFrame() {

    final var said = Camunda8Scoping
        .whatAQuotedExpressionIncludes(
            "vanillabp.adapters.c8.name-clash-avoidance",
            "loan-approval__",
            Map.of(PROCESS, List.of("Activity_CallTheOtherProcess")));

    assertTrue(
        said.contains("1 element(s)"),
        () -> "how many elements of the module the sentence is about: "
            + said);
    assertTrue(
        said.contains("'Activity_CallTheOtherProcess' of BPMN process 'LoanApproval'"),
        () -> "named one by one, with the process they belong to: "
            + said);
    assertTrue(
        said.contains("=\"loan-approval__\" + string(<your expression>)"),
        () -> "the frame itself, because that is what the cluster quoted: "
            + said);
    assertTrue(
        said.contains("column"),
        () -> "and the column of a parse error is counted in the framed expression: "
            + said);
    assertEquals(
        "",
        Camunda8Scoping
            .whatAQuotedExpressionIncludes(
                "vanillabp.adapters.c8.name-clash-avoidance", "loan-approval__", Map.of()),
        "a module whose calls are all named statically reads nothing about expressions");

  }

}
