package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
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
import io.vanillabp.camunda8.wiring.Camunda8AllowConnectorsResolver;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter makes of an ad-hoc subprocess: an element which holds activities
 * without sequence flows between them and runs the ones somebody picked.
 * <p>
 * The element itself is none the wiring collects, so nothing here asks for a
 * <code>&#64;WorkflowTask</code> method for it. The activities inside it are collected like
 * any other task, which is what lets an application serve the element without a line of
 * framework code. What the model does not say out loud is reported instead: the subprocess
 * can put more than one token into the workflow, and the flavour expecting a job worker of
 * its own is served by nothing here.
 * <p>
 * Who claims the process decides what happens about that flavour. A process a
 * {@code @WorkflowService} class of the application claims does not deploy, because the
 * application stands in for it and nothing of it serves the element. A process nobody claims
 * keeps the WARN it always had.
 * <p>
 * The cluster is an address nothing listens on. Everything asserted below is read from the
 * model.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8AdHocSubProcessTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  /**
   * The flavour VanillaBP serves: the model names the activities to run, through a FEEL
   * expression over a variable the workflow aggregate shares.
   */
  private static BpmnModelInstance modelActivatedByTheModel() {

    return model("""
            <bpmn:adHocSubProcess id="AdHoc_AdditionalChecks">
              <bpmn:extensionElements>
                <zeebe:adHoc activeElementsCollection="=checksToRun" />
              </bpmn:extensionElements>
              <bpmn:serviceTask id="Activity_CheckFraud">
                <bpmn:extensionElements>
                  <zeebe:taskDefinition type="checkFraud" />
                </bpmn:extensionElements>
              </bpmn:serviceTask>
              <bpmn:serviceTask id="Activity_CheckIncome">
                <bpmn:extensionElements>
                  <zeebe:taskDefinition type="checkIncome" />
                </bpmn:extensionElements>
              </bpmn:serviceTask>
            </bpmn:adHocSubProcess>
        """);

  }

  /**
   * The flavour expecting a job worker on the element itself, which this adapter does not
   * serve.
   */
  private static BpmnModelInstance modelActivatedByAWorker() {

    return model("""
            <bpmn:adHocSubProcess id="AdHoc_AgentTools">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="chooseTheTools" />
              </bpmn:extensionElements>
              <bpmn:serviceTask id="Activity_LookUpCustomer">
                <bpmn:extensionElements>
                  <zeebe:taskDefinition type="lookUpCustomer" />
                </bpmn:extensionElements>
              </bpmn:serviceTask>
            </bpmn:adHocSubProcess>
        """);

  }

  /**
   * The same element built from an element template, which is what the Camunda AI agent
   * is: a runtime other than this application fetches that job.
   */
  private static BpmnModelInstance modelServedByAnotherRuntime() {

    return model(
        """
                <bpmn:adHocSubProcess id="AdHoc_AgentTools" zeebe:modelerTemplate="io.camunda.agenticai:aiagent:subprocess:2">
                  <bpmn:extensionElements>
                    <zeebe:taskDefinition type="io.camunda.agenticai:aiagent:subprocess:2" />
                  </bpmn:extensionElements>
                  <bpmn:serviceTask id="Activity_LookUpCustomer">
                    <bpmn:extensionElements>
                      <zeebe:taskDefinition type="lookUpCustomer" />
                    </bpmn:extensionElements>
                  </bpmn:serviceTask>
                </bpmn:adHocSubProcess>
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
   * What one wiring run reported: the task specs the core was asked to validate, keyed by
   * activity id, and the elements named as sources of a second token.
   */
  private static class Reported extends Camunda8DeploymentServiceTest.NoOpInvoker {

    private final Map<String, String> taskDefinitions = new LinkedHashMap<>();

    private final List<String> concurrentTokenElements = new ArrayList<>();

    @Override
    public void validateTaskWiring(
        final String workflowModuleId,
        final String bpmnProcessId,
        final Collection<BpmnTaskSpec> tasks) {

      tasks.forEach(task -> taskDefinitions.put(task.activityId(), task.taskDefinition()));

    }

    @Override
    public void reportConcurrentTokenElements(
        final String workflowModuleId,
        final String bpmnProcessId,
        final Collection<String> elementIds) {

      concurrentTokenElements.addAll(elementIds);

    }

  }

  /**
   * The same core answering that no {@code @WorkflowService} class of the application claims
   * the process - the answer the core gives by not knowing a workflow aggregate for it.
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
   * Wires one model and hands back what the core was told plus what the boot wrote.
   */
  private static String wire(
      final CapturedOutput output,
      final Reported core,
      final boolean connectorsAreAllowed,
      final BpmnModelInstance model) {

    // what THIS case logged: the capture spans the whole class
    final var before = output.getAll().length();
    wiringOf(core, connectorsAreAllowed, model).run();
    return output.getAll().substring(before);

  }

  /**
   * Preparing and wiring one model, unrun, so a test can catch what it refused.
   */
  private static Runnable wiringOf(
      final Reported core,
      final boolean connectorsAreAllowed,
      final BpmnModelInstance model) {

    final var service = adapterServedBy(core, connectorsAreAllowed);
    return () -> {
      final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
      service.wireBpmn(MODULE, FILE, PROCESS, model, context);
      service.reportWhatConnectorsCost(MODULE, context);
    };

  }

  private static Camunda8DeploymentService adapterServedBy(
      final Reported core,
      final boolean connectorsAreAllowed) {

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
    service
        .setAllowConnectorsResolver(
            (Camunda8AllowConnectorsResolver) (
                workflowModuleId,
                bpmnProcessId) -> new Camunda8AllowConnectorsResolver.Setting(
                    connectorsAreAllowed, "vanillabp.adapters.c8.allow-connectors"));
    return service;

  }

  /**
   * The sentence which tells the job worker flavour apart from everything else this boot
   * may say. The refusal and the WARN both carry it.
   */
  private static final String THE_UNSERVED_FLAVOUR = "carry a 'zeebe:taskDefinition' of their own";

  @Test
  @DisplayName("The activities inside the element are wired and the element itself is not asked for")
  public void theActivitiesInsideAreOrdinaryTasks(
      final CapturedOutput output) {

    final var core = new Reported();

    final var logged = wire(output, core, false, modelActivatedByTheModel());

    assertEquals(
        Map.of("Activity_CheckFraud", "checkFraud", "Activity_CheckIncome", "checkIncome"),
        core.taskDefinitions,
        () -> "the two inner tasks are validated like any other task: "
            + ((Reported) core).taskDefinitions);
    assertFalse(
        core.taskDefinitions.containsKey("AdHoc_AdditionalChecks"),
        () -> "the element is not a task of the application: "
            + ((Reported) core).taskDefinitions);
    assertFalse(
        logged.contains(THE_UNSERVED_FLAVOUR),
        () -> "nothing is wrong with this model, so nothing is said about it: "
            + logged);

  }

  @Test
  @DisplayName("The element is named as a source of a second token, its activities are not")
  public void theElementIsASourceOfASecondToken(
      final CapturedOutput output) {

    final var core = new Reported();

    wire(output, core, false, modelActivatedByTheModel());

    assertEquals(
        List.of("AdHoc_AdditionalChecks"),
        core.concurrentTokenElements,
        () -> "the subprocess is where the second token comes from: "
            + core.concurrentTokenElements);

  }

  @Test
  @DisplayName("An inner activity without a task definition still reaches the wiring validation")
  public void anInnerActivityWithoutATaskDefinitionIsNotHidden(
      final CapturedOutput output) {

    final var core = new Reported();

    wire(output, core, false, model("""
            <bpmn:adHocSubProcess id="AdHoc_AdditionalChecks">
              <bpmn:extensionElements>
                <zeebe:adHoc activeElementsCollection="=checksToRun" />
              </bpmn:extensionElements>
              <bpmn:serviceTask id="Activity_CheckFraud" />
            </bpmn:adHocSubProcess>
        """));

    // a null task definition is what the core turns into its guiding message about a task
    // no method serves - the element must not become a place where such a task hides
    assertTrue(
        core.taskDefinitions.containsKey("Activity_CheckFraud"),
        () -> "the activity is validated: "
            + ((Reported) core).taskDefinitions);
    assertEquals(
        null,
        core.taskDefinitions.get("Activity_CheckFraud"),
        () -> "and it has nothing to be served by: "
            + ((Reported) core).taskDefinitions);

  }

  @Test
  @DisplayName("An element expecting a job worker of its own ends the boot of a claimed process")
  public void theJobWorkerFlavourEndsTheBootOfAClaimedProcess() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> wiringOf(new Reported(), false, modelActivatedByAWorker()).run()).getMessage();

    assertTrue(
        refused.contains(THE_UNSERVED_FLAVOUR),
        () -> "the boot says which flavour it is about: "
            + refused);
    assertTrue(
        refused.contains("'AdHoc_AgentTools'"),
        () -> "it names the element: "
            + refused);
    assertTrue(
        refused.contains("@WorkflowService"),
        () -> "and that the application stands in for this process, which is why the boot ends: "
            + refused);
    assertTrue(
        refused.contains("zeebe:adHoc activeElementsCollection"),
        () -> "and the way out through the model: "
            + refused);
    assertTrue(
        refused.contains("incident"),
        () -> "and what a workflow reaching it would cost: "
            + refused);

  }

  @Test
  @DisplayName("The refusal names the line which says that a worker of the application serves the element")
  public void theRefusalNamesTheLine() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> wiringOf(new Reported(), false, modelActivatedByAWorker()).run()).getMessage();

    assertTrue(
        refused.contains("tasks.AdHoc_AgentTools.implemented-externally=true"),
        () -> "the third way out, for a worker the application runs itself: "
            + refused);

  }

  @Test
  @DisplayName("An element marked as served elsewhere is handed to the core and ends nothing")
  public void aMarkedElementGoesToTheCore() {

    final var core = new Reported() {

      @Override
      public boolean isImplementedExternally(
          final String adapterId,
          final String workflowModuleId,
          final String bpmnProcessId,
          final BpmnTaskSpec task) {

        return "AdHoc_AgentTools".equals(task.activityId());

      }

    };

    wiringOf(core, false, modelActivatedByAWorker()).run();

    assertTrue(
        ((Reported) core).taskDefinitions.containsKey("AdHoc_AgentTools"),
        () -> "the core holds the rule, a method next to the line included: "
            + ((Reported) core).taskDefinitions);

  }

  @Test
  @DisplayName("The same element in a process nobody claims is not looked at, and the boot goes on")
  public void theJobWorkerFlavourOfAnUnclaimedProcessIsNotLookedAt(
      final CapturedOutput output) {

    final var logged = wire(
        output, new ReportedForAProcessNobodyClaims(), false, modelActivatedByAWorker());

    assertFalse(
        logged.contains(THE_UNSERVED_FLAVOUR),
        () -> "somebody else's model is not judged: "
            + logged);

  }


  @Test
  @DisplayName("An element another runtime serves stays quiet, whether or not connectors are allowed")
  public void theElementTemplateFlavourStaysQuiet(
      final CapturedOutput output) {

    // the AI agent is an element template on exactly this element, and a connector runtime
    // fetches its job. What a boot says about connectors is that feature's business; what
    // must not appear is the message about an element nothing serves
    final var whileConnectorsAreAllowed = wire(
        output, new Reported(), true, modelServedByAnotherRuntime());
    assertFalse(
        whileConnectorsAreAllowed.contains(THE_UNSERVED_FLAVOUR),
        () -> "somebody else's runtime owns the element: "
            + whileConnectorsAreAllowed);

    final var whileTheyAreNot = wire(output, new Reported(), false, modelServedByAnotherRuntime());
    assertFalse(
        whileTheyAreNot.contains(THE_UNSERVED_FLAVOUR),
        () -> "and the marker says so whether or not the key is set: "
            + whileTheyAreNot);

  }

}
