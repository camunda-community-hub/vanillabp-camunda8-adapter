package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
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
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a boot says about a handler which wants the item of an iteration the model hands
 * none over for.
 * <p>
 * Every refusal is asserted together with the model that must NOT be refused. An element
 * whose handler only wants to know how far the iteration got needs no
 * <code>inputElement</code>, and refusing it would end the boot of an application that
 * does nothing wrong.
 * <p>
 * The cluster is an address nothing listens on. Everything asserted below is read from the
 * model.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8MultiInstanceItemsTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  /**
   * A subprocess walking a collection without naming what one entry of it is called, with
   * one task inside it.
   */
  private static final String SUBPROCESS_WITHOUT_AN_ITEM = """
          <bpmn:subProcess id="Subprocess_applications">
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=applications" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:serviceTask id="Activity_check">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="checkCredit" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
          </bpmn:subProcess>
      """;

  /** The same subprocess naming the variable each round's entry goes into. */
  private static final String SUBPROCESS_WITH_AN_ITEM = """
          <bpmn:subProcess id="Subprocess_applications">
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=applications" inputElement="application" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:serviceTask id="Activity_check">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="checkCredit" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
          </bpmn:subProcess>
      """;

  private static BpmnModelInstance model(
      final String processContent) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
        %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, processContent);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  /**
   * The core of an application whose methods want the item of the given elements, keyed by
   * what the method was wired by. Everything it is not asked about answers nothing, which
   * is what a method declaring no <code>@MultiInstanceElement</code> looks like.
   */
  private static class ACoreWantingTheItemOf extends Camunda8DeploymentServiceTest.NoOpInvoker {

    private final Map<String, List<String>> wantedByWiringName;

    ACoreWantingTheItemOf(
        final Map<String, List<String>> wantedByWiringName) {

      this.wantedByWiringName = wantedByWiringName;

    }

    @Override
    public Collection<String> multiInstanceElementNames(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinitionOrActivityId) {

      return wantedByWiringName.getOrDefault(taskDefinitionOrActivityId, List.of());

    }

  }

  private static void deploy(
      final Map<String, List<String>> wanted,
      final BpmnModelInstance model) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:65535");
    final var scoping = TestScoping.of(NameClashAvoidance.BY_ADAPTER);
    final var service = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(new ACoreWantingTheItemOf(wanted), scoping),
        (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofHours(1),
        adapterId -> configuration, scoping);
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
    service.wireBpmn(MODULE, FILE, PROCESS, model, context);

  }

  @Test
  @DisplayName("A handler wanting the item of an element which names no inputElement ends the boot")
  public void anElementWithoutAnItemEndsTheBoot() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> deploy(
            Map.of("checkCredit", List.of("Subprocess_applications")),
            model(SUBPROCESS_WITHOUT_AN_ITEM)));

    final var message = refused.getMessage();
    assertTrue(
        message.contains("'Subprocess_applications'"),
        () -> "the element a modeller has to find in their own model: "
            + message);
    assertTrue(
        message.contains("'Activity_check'") && message.contains("'checkCredit'"),
        () -> "and the task whose method asked for it: "
            + message);
    assertTrue(
        message.contains("inputElement"),
        () -> "what the model would have to carry: "
            + message);
    assertTrue(
        message.contains("@MultiInstanceIndex"),
        () -> "and the other way out, which is to stop asking for the item: "
            + message);

  }

  @Test
  @DisplayName("The same model naming the variable deploys")
  public void anElementNamingTheVariableDeploys() {

    assertDoesNotThrow(
        () -> deploy(
            Map.of("checkCredit", List.of("Subprocess_applications")),
            model(SUBPROCESS_WITH_AN_ITEM)));

  }

  @Test
  @DisplayName("An element without an item deploys where no handler wants that item")
  public void iteratingWithoutAskingForTheItemDeploys() {

    assertDoesNotThrow(() -> deploy(Map.of(), model(SUBPROCESS_WITHOUT_AN_ITEM)));

  }

  @Test
  @DisplayName("An element which does not enclose the task is no finding")
  public void anElementOfAnotherPlaceIsNoFinding() {

    assertDoesNotThrow(
        () -> deploy(
            Map.of("checkCredit", List.of("Subprocess_ofTheCaller")),
            model(SUBPROCESS_WITHOUT_AN_ITEM)),
        "the chain crosses a call activity, so a task of a called process asks for an "
            + "element of its caller, and that element is read where the caller is deployed");

  }

  @Test
  @DisplayName("A method wired by the element id is found by that id")
  public void aMethodWiredByTheElementIdIsFoundToo() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> deploy(
            Map.of("Activity_check", List.of("Subprocess_applications")),
            model(SUBPROCESS_WITHOUT_AN_ITEM)));

    assertTrue(refused.getMessage().contains("'Subprocess_applications'"), refused::getMessage);

  }

  @Test
  @DisplayName("Every task of one process is named in ONE message")
  public void everyFindingOfAProcessIsReportedAtOnce() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> deploy(
            Map
                .of(
                    "checkCredit",
                    List.of("Subprocess_applications"),
                    "rateCredit",
                    List.of("Subprocess_applications", "Activity_rate")),
            model("""
                    <bpmn:subProcess id="Subprocess_applications">
                      <bpmn:multiInstanceLoopCharacteristics>
                        <bpmn:extensionElements>
                          <zeebe:loopCharacteristics inputCollection="=applications" />
                        </bpmn:extensionElements>
                      </bpmn:multiInstanceLoopCharacteristics>
                      <bpmn:serviceTask id="Activity_check">
                        <bpmn:extensionElements>
                          <zeebe:taskDefinition type="checkCredit" />
                        </bpmn:extensionElements>
                      </bpmn:serviceTask>
                      <bpmn:serviceTask id="Activity_rate">
                        <bpmn:extensionElements>
                          <zeebe:taskDefinition type="rateCredit" />
                        </bpmn:extensionElements>
                        <bpmn:multiInstanceLoopCharacteristics>
                          <bpmn:extensionElements>
                            <zeebe:loopCharacteristics inputCollection="=criteria" />
                          </bpmn:extensionElements>
                        </bpmn:multiInstanceLoopCharacteristics>
                      </bpmn:serviceTask>
                    </bpmn:subProcess>
                """)));

    final var message = refused.getMessage();
    assertTrue(
        message.contains("'Activity_check'") && message.contains("'Activity_rate'"),
        () -> "both tasks, because fixing one model should not need a second restart: "
            + message);
    assertTrue(
        message.contains("'Subprocess_applications', 'Activity_rate'"),
        () -> "and both elements the second task wants an item of: "
            + message);

  }

}
