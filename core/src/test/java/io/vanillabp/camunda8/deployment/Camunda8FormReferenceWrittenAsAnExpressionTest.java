package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
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
import io.vanillabp.camunda8.wiring.Camunda8AllowConnectorsResolver;
import io.vanillabp.camunda8.wiring.Camunda8AllowListenersResolver;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a boot does with a model which names the form of a user task by a FEEL expression.
 * <p>
 * The external form reference of a Camunda-managed user task is its task definition. The core
 * finds the {@code @WorkflowTask} method by it, and the job type of the listeners is built from
 * it. Measured on 2026-10-06 against {@code camunda/camunda:8.10.0} with one model and one
 * method before this was refused: without prefixes a method written as
 * {@code taskDefinition = "=whichForm"} was served, and under {@code use-prefix} the boot ended
 * in the core's wiring validation, which said the method matched no task.
 * <p>
 * Who claims the process decides what happens about it, the same way it decides about a job
 * type written as an expression.
 * <p>
 * The cluster is an address nothing listens on. Everything asserted here is read from the model
 * and from what the boot wrote.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8FormReferenceWrittenAsAnExpressionTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  /**
   * A user task whose form is read out of the workflow at runtime.
   */
  private static BpmnModelInstance aUserTaskNamingItsFormByExpression() {

    return model("""
            <bpmn:userTask id="Activity_Approve">
              <bpmn:extensionElements>
                <zeebe:userTask />
                <zeebe:formDefinition externalReference="=whichForm" />
              </bpmn:extensionElements>
            </bpmn:userTask>
        """);

  }

  /**
   * The model written the way it works: a fixed name.
   */
  private static BpmnModelInstance aUserTaskNamingItsForm() {

    return model("""
            <bpmn:userTask id="Activity_Approve">
              <bpmn:extensionElements>
                <zeebe:userTask />
                <zeebe:formDefinition externalReference="approveLoan" />
              </bpmn:extensionElements>
            </bpmn:userTask>
        """);

  }

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
   * What one boot asked the core to validate, keyed by activity id. A boot which ends over the
   * form reference must not have asked anything about it first: the method the core would
   * demand is the message this test exists to replace.
   */
  private static class Reported extends Camunda8DeploymentServiceTest.NoOpInvoker {

    private final Map<String, String> taskDefinitions = new LinkedHashMap<>();

    @Override
    public void validateTaskWiring(
        final String workflowModuleId,
        final String bpmnProcessId,
        final Collection<BpmnTaskSpec> tasks) {

      tasks.forEach(task -> taskDefinitions.put(task.activityId(), task.taskDefinition()));

    }

  }

  /**
   * The same core answering that no {@code @WorkflowService} class of the application claims the
   * process - the answer the core gives by not knowing a workflow aggregate for it.
   */
  private static class ReportedForAProcessNobodyClaims extends Reported {

    @Override
    public String resolveWorkflowAggregateIdName(
        final String workflowModuleId,
        final String bpmnProcessId) {
      throw new IllegalStateException(
          "no @WorkflowService class of this application claims '%s'".formatted(bpmnProcessId));
    }

  }

  /**
   * Boots one model and hands back what this case wrote.
   */
  private static String boot(
      final CapturedOutput output,
      final Reported core,
      final BpmnModelInstance model) {

    final var before = output.getAll().length();
    bootOf(core, NameClashAvoidance.BY_ADAPTER, false, model).run();
    return output.getAll().substring(before);

  }

  /**
   * Preparing and wiring one model, unrun, so a test can catch what it refused.
   */
  private static Runnable bootOf(
      final Reported core,
      final NameClashAvoidance mode,
      final boolean connectorsAreAllowed,
      final BpmnModelInstance model) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:65535");
    final var scoping = TestScoping.of(mode);
    final var service = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(core, scoping),
        (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration.ofHours(1), adapterId -> configuration, scoping);
    service
        .setAllowConnectorsResolver(
            (Camunda8AllowConnectorsResolver) (
                workflowModuleId,
                bpmnProcessId) -> new Camunda8AllowConnectorsResolver.Setting(
                    connectorsAreAllowed, "vanillabp.adapters.c8.allow-connectors"));
    // the listeners are allowed, so a listener job type reaches this boot as something the
    // application asked to be served: the shape of the value is what decides, not the key
    service
        .setAllowListenersResolver(
            (Camunda8AllowListenersResolver) (
                workflowModuleId,
                bpmnProcessId) -> new Camunda8AllowListenersResolver.Setting(
                    true, "vanillabp.adapters.c8.allow-listeners"));
    return () -> {
      final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
      service.wireBpmn(MODULE, FILE, PROCESS, model, context);
    };

  }

  /**
   * The sentence which tells this finding apart from everything else a boot may say. The
   * refusal and the WARN both carry it.
   */
  private static final String THE_FINDING = "name their form by a FEEL expression";

  @Test
  @DisplayName("A form reference written as an expression ends the boot of a claimed process")
  public void anExpressionEndsTheBootOfAClaimedProcess() {

    final var core = new Reported();

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> bootOf(core, NameClashAvoidance.BY_ADAPTER, false, aUserTaskNamingItsFormByExpression())
            .run())
        .getMessage();

    assertTrue(
        refused.contains(THE_FINDING),
        () -> "the boot says what the finding is: "
            + refused);
    assertTrue(
        refused.contains("zeebe:formDefinition externalReference of 'Activity_Approve' (=whichForm)"),
        () -> "it names the attribute, the element and the expression: "
            + refused);
    assertTrue(
        refused.contains("A form reference has to be a fixed name") && refused
            .contains("IS the task definition of the user task"),
        () -> "and why, which is that the reference is the task definition: "
            + refused);
    assertTrue(
        refused.contains("repeats the expression") && refused.contains("'use-prefix' not even then"),
        () -> "and what such a model costs: "
            + refused);
    assertTrue(
        refused.contains("write a fixed name as the external form reference and a @WorkflowTask method"),
        () -> "and the way out: "
            + refused);
    assertTrue(
        refused.contains("@WorkflowService"),
        () -> "and why this process ends the boot: "
            + refused);
    assertEquals(
        Map.of(),
        core.taskDefinitions,
        () -> "the core was never asked for a method named after the expression: "
            + core.taskDefinitions);

  }

  @Test
  @DisplayName("Under use-prefix the refusal quotes the expression the modeller typed, not the frame")
  public void theRefusalQuotesWhatTheModellerTyped() {

    final var core = new Reported();

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> bootOf(core, NameClashAvoidance.USE_PREFIX, false, aUserTaskNamingItsFormByExpression())
            .run())
        .getMessage();

    assertTrue(
        refused.contains(THE_FINDING) && refused.contains("(=whichForm)"),
        () -> "the model is read before the prefix is written into the expression: "
            + refused);
    assertFalse(
        refused.contains(MODULE
            + "__"),
        () -> "so nothing of the frame is quoted back at somebody who never typed it: "
            + refused);
    assertEquals(
        Map.of(),
        core.taskDefinitions,
        () -> "and the core's wiring validation, which used to end this boot naming the method "
            + "instead of the model, is never reached: "
            + core.taskDefinitions);

  }

  @Test
  @DisplayName("The same model in a process nobody claims is not looked at, and the boot goes on")
  public void aProcessNobodyClaimsIsNotLookedAt(
      final CapturedOutput output) {

    final var logged = boot(output, new ReportedForAProcessNobodyClaims(), aUserTaskNamingItsFormByExpression());

    assertFalse(
        logged.contains(THE_FINDING),
        () -> "somebody else's model is not judged: "
            + logged);

  }


  @Test
  @DisplayName("A form reference which is a name says nothing about any of this")
  public void aFormReferenceWhichIsANameIsQuiet(
      final CapturedOutput output) {

    final var core = new Reported();

    final var logged = boot(output, core, aUserTaskNamingItsForm());

    assertEquals(
        Map.of("Activity_Approve", "approveLoan"),
        core.taskDefinitions,
        () -> "the user task reaches the core as it always did: "
            + core.taskDefinitions);
    assertFalse(
        logged.contains(THE_FINDING),
        () -> "and nothing is wrong with this model, so nothing is said about it: "
            + logged);

  }

}
