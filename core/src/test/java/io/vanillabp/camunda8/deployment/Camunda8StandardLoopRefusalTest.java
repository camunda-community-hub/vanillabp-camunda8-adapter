package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter says about a standard loop. The cluster runs such an activity once and says
 * nothing, so a model this boot deploys is refused where the application claims its process,
 * and a warning where nobody does. A version the cluster already holds is a warning where
 * workflows still run on it.
 * <p>
 * Every refusal stands next to the model which must NOT be refused: a multi-instance element
 * is the form which does repeat an activity, and the message points to it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8StandardLoopRefusalTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  private static final String A_TASK_WITH_A_STANDARD_LOOP = """
          <bpmn:serviceTask id="Activity_remind">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="remind" />
            </bpmn:extensionElements>
            <bpmn:standardLoopCharacteristics loopMaximum="3">
              <bpmn:loopCondition xsi:type="bpmn:tFormalExpression">=true</bpmn:loopCondition>
            </bpmn:standardLoopCharacteristics>
          </bpmn:serviceTask>
      """;

  private static final String A_TASK_IN_A_SUBPROCESS_WITH_A_STANDARD_LOOP = """
          <bpmn:subProcess id="Subprocess_reminders">
            <bpmn:serviceTask id="Activity_remind">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="remind" />
              </bpmn:extensionElements>
              <bpmn:standardLoopCharacteristics />
            </bpmn:serviceTask>
          </bpmn:subProcess>
      """;

  private static final String A_MULTI_INSTANCE_TASK = """
          <bpmn:serviceTask id="Activity_remind">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="remind" />
            </bpmn:extensionElements>
            <bpmn:multiInstanceLoopCharacteristics isSequential="true">
              <bpmn:extensionElements>
                <zeebe:loopCharacteristics inputCollection="=reminders" inputElement="reminder" />
              </bpmn:extensionElements>
            </bpmn:multiInstanceLoopCharacteristics>
          </bpmn:serviceTask>
      """;

  private static final String A_PLAIN_TASK = """
          <bpmn:serviceTask id="Activity_remind">
            <bpmn:extensionElements>
              <zeebe:taskDefinition type="remind" />
            </bpmn:extensionElements>
          </bpmn:serviceTask>
      """;

  /**
   * One file with the process under test and a second process carrying a standard loop, which
   * is the finding of that other process only.
   */
  private static BpmnModelInstance model(
      final String processContent) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
        %s
          </bpmn:process>
          <bpmn:process id="OtherProcess" isExecutable="true">
            <bpmn:task id="Activity_ofTheOtherProcess">
              <bpmn:standardLoopCharacteristics />
            </bpmn:task>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, processContent);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  private static void deploy(
      final BpmnModelInstance model) {

    deploy(model, new Camunda8DeploymentServiceTest.NoOpInvoker());

  }

  /**
   * A core in which no <code>&#64;WorkflowService</code> class claims the process: it knows no
   * workflow aggregate for it.
   */
  private static class ACoreClaimingNothing extends Camunda8DeploymentServiceTest.NoOpInvoker {

    @Override
    public String resolveWorkflowAggregateIdName(
        final String workflowModuleId,
        final String bpmnProcessId) {

      return null;

    }

  }

  private static void deploy(
      final BpmnModelInstance model,
      final Camunda8DeploymentServiceTest.NoOpInvoker core) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:65535");
    final var scoping = TestScoping.of(NameClashAvoidance.BY_ADAPTER);
    final var service = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(core, scoping),
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
  @DisplayName("A standard loop ends the deployment and the message names the two forms which repeat")
  public void aStandardLoopEndsTheDeployment() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> deploy(model(A_TASK_WITH_A_STANDARD_LOOP)));

    final var message = refused.getMessage();
    assertTrue(
        message.contains("BPMN process 'LoanApproval' of workflow module 'loan-approval'"),
        () -> "the process and the workflow module: "
            + message);
    assertTrue(
        message.contains("the activity 'Activity_remind'"),
        () -> "the element a modeller has to find: "
            + message);
    assertTrue(
        message.contains("runs the activity once"),
        () -> "what the cluster does instead: "
            + message);
    assertTrue(
        message.contains("loop in the sequence flow"),
        () -> "the first form which repeats: "
            + message);
    assertTrue(
        message.contains("@MultiInstanceElement, @MultiInstanceIndex and @MultiInstanceTotal"),
        () -> "and the second one, with what a handler reads there: "
            + message);
    assertFalse(
        message.contains("Activity_ofTheOtherProcess"),
        () -> "the other process of the file is a finding of its own: "
            + message);

  }

  @Test
  @DisplayName("A standard loop in a process nobody claims is a warning, and the boot goes on")
  public void aStandardLoopNobodyClaimsIsAWarning(
      final CapturedOutput output) {

    assertDoesNotThrow(() -> deploy(model(A_TASK_WITH_A_STANDARD_LOOP), new ACoreClaimingNothing()));

    final var logged = output.getAllOfThisTest();
    assertTrue(
        logged.contains("BPMN process 'LoanApproval' of workflow module 'loan-approval' carries a standard loop"),
        () -> "the process and the module: "
            + logged);
    assertTrue(
        logged.contains("No @WorkflowService class of this application claims this process"),
        () -> "and why the boot goes on: "
            + logged);

  }

  @Test
  @DisplayName("A standard loop inside a subprocess is found as well")
  public void aStandardLoopInsideASubprocessIsFound() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> deploy(model(A_TASK_IN_A_SUBPROCESS_WITH_A_STANDARD_LOOP)));

    assertTrue(
        refused.getMessage().contains("the activity 'Activity_remind'"),
        refused::getMessage);

  }

  @Test
  @DisplayName("A multi-instance element deploys")
  public void aMultiInstanceElementDeploys() {

    assertDoesNotThrow(() -> deploy(model(A_MULTI_INSTANCE_TASK)));

  }

  @Test
  @DisplayName("Only the activities of the process asked about are reported")
  public void onlyTheActivitiesOfTheProcessAreReported() {

    assertEquals(
        List.of(),
        Camunda8StandardLoops.elementIdsOf(model(A_MULTI_INSTANCE_TASK), PROCESS));
    assertEquals(
        List.of("Activity_ofTheOtherProcess"),
        Camunda8StandardLoops.elementIdsOf(model(A_MULTI_INSTANCE_TASK), "OtherProcess"));

  }

  @Test
  @DisplayName("A held version with a standard loop is warned about while workflows still run on it")
  public void aHeldVersionWithWorkflowsIsWarnedAbout() {

    final var found = Camunda8StandardLoops
        .heldVersionsToWarnAbout(
            List.of(
                new Camunda8ModelsTheClusterHolds.HeldModel(PROCESS, "1", model(A_TASK_WITH_A_STANDARD_LOOP)),
                new Camunda8ModelsTheClusterHolds.HeldModel(PROCESS, "2", model(A_PLAIN_TASK))),
            PROCESS,
            Map.of("1", 2L, "2", 5L)::get);

    assertEquals(
        List.of(new Camunda8StandardLoops.HeldVersion("1", List.of("Activity_remind"), 2L)),
        found,
        "the version carrying the marker, and not the one without it");
    final var warning = Camunda8StandardLoops.warningAboutAHeldVersion(found.getFirst(), PROCESS, MODULE);
    assertTrue(
        warning.contains("Version '1' of BPMN process 'LoanApproval' (workflow module 'loan-approval') "
            + "carries a standard loop"),
        () -> "the version, the process and the module: "
            + warning);
    assertTrue(
        warning.contains("the activity 'Activity_remind', and 2 workflows still run on it"),
        () -> "the element and how many workflows are on it: "
            + warning);

  }

  @Test
  @DisplayName("A held version no workflow runs on any more is passed over, one the cluster cannot count is not")
  public void aHeldVersionWithoutWorkflowsIsPassedOver() {

    final var heldModels = List.of(
        new Camunda8ModelsTheClusterHolds.HeldModel(PROCESS, "1", model(A_TASK_WITH_A_STANDARD_LOOP)));

    assertEquals(
        List.of(),
        Camunda8StandardLoops.heldVersionsToWarnAbout(heldModels, PROCESS, version -> 0L),
        "a version nothing runs on can do no harm");
    final var uncounted = Camunda8StandardLoops.heldVersionsToWarnAbout(heldModels, PROCESS, version -> null);
    assertEquals(1, uncounted.size(), "a version the cluster cannot count may still have workflows");
    assertTrue(
        Camunda8StandardLoops
            .warningAboutAHeldVersion(uncounted.getFirst(), PROCESS, MODULE)
            .contains("the cluster could not say how many workflows still run on it"));

  }

}
