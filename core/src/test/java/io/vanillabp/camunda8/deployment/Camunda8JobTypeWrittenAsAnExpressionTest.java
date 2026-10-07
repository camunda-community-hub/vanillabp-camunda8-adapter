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
 * What a boot does with a model which names a job type by a FEEL expression.
 * <p>
 * A job type is the name a worker subscribes to. This adapter opens one worker per job type it
 * reads out of the model and subscribes exactly the string the model says, so an expression
 * there leaves the element unserved: where the cluster evaluates it the job carries the result
 * while the worker waits for the expression, and where it does not, no
 * {@code @WorkflowTask} method can be named after it. Before this was asked, the boot ended
 * over the second half alone and demanded a method whose name nobody can write.
 * <p>
 * Who claims the process decides what happens about it, the same way it decides about a user
 * task a job worker serves and about an ad-hoc subprocess nothing serves.
 * <p>
 * The cluster is an address nothing listens on. Everything asserted here is read from the model
 * and from what the boot wrote.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8JobTypeWrittenAsAnExpressionTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  /**
   * A service task whose job type is read out of the workflow aggregate at runtime.
   */
  private static BpmnModelInstance aServiceTaskNamingItsJobTypeByExpression() {

    return model("""
            <bpmn:serviceTask id="Activity_AssessRisk">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="=whichAssessment" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
        """);

  }

  /**
   * The same element built from an element template, which says that the runtime owning the
   * template serves it - and such a runtime may well compose its job type by expression.
   */
  private static BpmnModelInstance anotherRuntimesTaskNamingItsJobTypeByExpression() {

    return model(
        """
                <bpmn:serviceTask id="Activity_AssessRisk" zeebe:modelerTemplate="io.camunda.connectors.HttpJson.v2">
                  <bpmn:extensionElements>
                    <zeebe:taskDefinition type="=whichAssessment" />
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
            """);

  }

  /**
   * A listener somebody modelled whose job type is an expression.
   */
  private static BpmnModelInstance aListenerNamingItsJobTypeByExpression() {

    return model("""
            <bpmn:serviceTask id="Activity_AssessRisk">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="assessRisk" />
                <zeebe:executionListeners>
                  <zeebe:executionListener eventType="start" type="=whichListener" />
                </zeebe:executionListeners>
              </bpmn:extensionElements>
            </bpmn:serviceTask>
        """);

  }

  /**
   * The model this is all about, written the way it works: a fixed name per job type.
   */
  private static BpmnModelInstance aServiceTaskNamingItsJobType() {

    return model("""
            <bpmn:serviceTask id="Activity_AssessRisk">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="assessRisk" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
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
   * job type must not have asked anything about it first: the method the core would demand is
   * the message this test exists to replace.
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
  private static final String THE_FINDING = "written as a FEEL expression";

  @Test
  @DisplayName("A job type written as an expression ends the boot of a claimed process")
  public void anExpressionEndsTheBootOfAClaimedProcess() {

    final var core = new Reported();

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> bootOf(core, NameClashAvoidance.BY_ADAPTER, false, aServiceTaskNamingItsJobTypeByExpression())
            .run())
        .getMessage();

    assertTrue(
        refused.contains(THE_FINDING),
        () -> "the boot says what the finding is: "
            + refused);
    assertTrue(
        refused.contains("zeebe:taskDefinition type of 'Activity_AssessRisk' (=whichAssessment)"),
        () -> "it names the attribute, the element and the expression: "
            + refused);
    assertTrue(
        refused.contains("A job type is the NAME a worker subscribes to"),
        () -> "and the real error, which is that an expression is not a name: "
            + refused);
    assertTrue(
        refused.contains("carries the RESULT") && refused.contains("no @WorkflowTask method can be named after it"),
        () -> "and both answers the cluster can give, neither of which serves the element: "
            + refused);
    assertTrue(
        refused.contains("stands there") && refused.contains("incident"),
        () -> "and what a workflow reaching it costs: "
            + refused);
    assertTrue(
        refused.contains("a fixed name and a @WorkflowTask method of that name") && refused
            .contains("zeebe:modelerTemplate"),
        () -> "and the two ways out: "
            + refused);
    assertTrue(
        refused.contains("@WorkflowService"),
        () -> "and why this process ends the boot: "
            + refused);
    assertEquals(
        Map.of(),
        core.taskDefinitions,
        () -> "the core was never asked for a method named after the expression, which is the "
            + "message this one replaces: "
            + core.taskDefinitions);

  }

  @Test
  @DisplayName("The refusal quotes the expression the modeller typed, not the frame of use-prefix")
  public void theRefusalQuotesWhatTheModellerTyped() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> bootOf(new Reported(), NameClashAvoidance.USE_PREFIX, false,
            aServiceTaskNamingItsJobTypeByExpression()).run())
        .getMessage();

    assertTrue(
        refused.contains("(=whichAssessment)"),
        () -> "the model is read before the prefix is written into the expression: "
            + refused);
    assertFalse(
        refused.contains(MODULE
            + "__"),
        () -> "so nothing of the frame is quoted back at somebody who never typed it: "
            + refused);

  }

  @Test
  @DisplayName("A modelled listener naming its job type by an expression ends the same boot")
  public void aListenerNamingItsJobTypeByAnExpressionIsTheSameFinding() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> bootOf(new Reported(), NameClashAvoidance.BY_ADAPTER, false,
            aListenerNamingItsJobTypeByExpression()).run())
        .getMessage();

    assertTrue(
        refused.contains(THE_FINDING),
        () -> "a listener's job type is a job type: "
            + refused);
    assertTrue(
        refused.contains("Activity_AssessRisk") && refused.contains("=whichListener"),
        () -> "and the listener is named with its expression: "
            + refused);
    assertFalse(
        refused.contains("no @WorkflowTask method of this application names"),
        () -> "the older message about a listener nothing serves would ask for a method named "
            + "after the expression: "
            + refused);

  }

  @Test
  @DisplayName("The same model in a process nobody claims is not looked at, and the boot goes on")
  public void aProcessNobodyClaimsIsNotLookedAt(
      final CapturedOutput output) {

    final var logged = boot(
        output, new ReportedForAProcessNobodyClaims(), aServiceTaskNamingItsJobTypeByExpression());

    assertFalse(
        logged.contains(THE_FINDING),
        () -> "somebody else's model is not judged: "
            + logged);

  }


  @Test
  @DisplayName("An element another runtime serves keeps its expression and stays quiet")
  public void anotherRuntimesElementStaysQuiet(
      final CapturedOutput output) {

    final var before = output.getAll().length();
    bootOf(new Reported(), NameClashAvoidance.BY_ADAPTER, true,
        anotherRuntimesTaskNamingItsJobTypeByExpression()).run();
    final var logged = output.getAll().substring(before);

    assertFalse(
        logged.contains(THE_FINDING),
        () -> "the job type names a runtime somebody else deployed, which may compose it as it "
            + "likes: "
            + logged);

  }

  @Test
  @DisplayName("A job type which is a name says nothing about any of this")
  public void aJobTypeWhichIsANameIsQuiet(
      final CapturedOutput output) {

    final var core = new Reported();

    final var logged = boot(output, core, aServiceTaskNamingItsJobType());

    assertEquals(
        Map.of("Activity_AssessRisk", "assessRisk"),
        core.taskDefinitions,
        () -> "the task reaches the core as it always did: "
            + core.taskDefinitions);
    assertFalse(
        logged.contains(THE_FINDING),
        () -> "and nothing is wrong with this model, so nothing is said about it: "
            + logged);

  }

}
