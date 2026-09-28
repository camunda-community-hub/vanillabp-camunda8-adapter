package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a boot says about a call activity which names the process it calls by a FEEL
 * expression, per name-clash-avoidance mode.
 * <p>
 * Under {@code use-prefix} every process of the workflow module is deployed under a prefixed
 * id and a statically named call activity is rewritten with them. An expression is not, so
 * the application has to yield the prefixed id itself, and this is the only moment anything
 * can say so: the model deploys, and the call fails when a workflow reaches the element. Under
 * every other mode there is no prefix and therefore nothing to say.
 * <p>
 * The cluster is an address nothing listens on. Everything asserted here is read from the
 * model and from what the boot wrote.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8CalledProcessByExpressionReportTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  /**
   * The sentence which tells this finding apart from everything else a boot may say.
   */
  private static final String THE_FINDING = "name the process they call by a FEEL expression";

  private static BpmnModelInstance aCallActivityNaming(
      final String calledProcess) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:callActivity id="Activity_CallTheOtherProcess">
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

  /**
   * Deploys one model in the given mode and hands back what this run wrote.
   */
  private static String whatTheBootSaid(
      final CapturedOutput output,
      final NameClashAvoidance mode,
      final BpmnModelInstance model) {

    final var before = output.getAll().length();
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
    return output.getAll().substring(before);

  }

  @Test
  @DisplayName("Prefixing names the element, the prefix it needs and the expression which yields it")
  public void prefixingNamesTheElementAndTheWayOut(
      final CapturedOutput output) {

    final var logged = whatTheBootSaid(output, NameClashAvoidance.USE_PREFIX, aCallActivityNaming("=whichProcess"));

    assertTrue(
        logged.contains(THE_FINDING),
        () -> "the one moment this can be said is the boot: "
            + logged);
    assertTrue(
        logged.contains("'Activity_CallTheOtherProcess'"),
        () -> "a developer has to read WHICH element it is about: "
            + logged);
    assertTrue(
        logged.contains("vanillabp.adapters.c8.name-clash-avoidance"),
        () -> "the message names the key which put the workflow module into this mode: "
            + logged);
    assertTrue(
        logged.contains("loan-approval__TheProcessYouCall"),
        () -> "and it shows the shape of the id the expression has to yield: "
            + logged);
    assertTrue(
        logged.contains("\"loan-approval__\" + whichProcess"),
        () -> "written out as the FEEL a developer can copy: "
            + logged);

  }

  @Test
  @DisplayName("A statically named call activity is rewritten and nothing is said about it")
  public void aStaticNameIsNotWorthAWord(
      final CapturedOutput output) {

    final var logged = whatTheBootSaid(output, NameClashAvoidance.USE_PREFIX, aCallActivityNaming("PaymentHandling"));

    assertFalse(
        logged.contains(THE_FINDING),
        () -> "this adapter puts the prefix there itself, so there is nothing for a developer to do: "
            + logged);

  }

  @Test
  @DisplayName("Without prefixing there is no prefix to compose, so the expression is nobody's business")
  public void withoutPrefixingNothingIsSaid(
      final CapturedOutput output) {

    final var logged = whatTheBootSaid(output, NameClashAvoidance.BY_ADAPTER, aCallActivityNaming("=whichProcess"));

    assertFalse(
        logged.contains(THE_FINDING),
        () -> "the cluster resolves the plain id the expression yields: "
            + logged);

  }

}
