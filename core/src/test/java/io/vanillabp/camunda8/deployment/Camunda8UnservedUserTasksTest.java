package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

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
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.camunda8.wiring.Camunda8UnservedUserTasks;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a boot says about a Camunda-managed user task which no {@code @WorkflowTask} method
 * serves.
 * <p>
 * Such a task is no defect. The cluster creates it, a task list shows it, and whoever finishes it
 * moves the workflow on. The application only misses the notification, so the boot says that once
 * per claimed process, on INFO, and goes on. A process nobody claims was never meant to be served
 * by a method of this application, so nothing is said about it.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8UnservedUserTasksTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  /**
   * The sentence which tells this report apart from everything else a boot may say.
   */
  private static final String THE_REPORT = "which no @WorkflowTask method serves";

  private static BpmnModelInstance model(
      final String content) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
        %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, content);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  private static String managedUserTask(
      final String elementId,
      final String name,
      final String formReference) {

    return """
            <bpmn:userTask id="%s"%s>
              <bpmn:extensionElements>
                <zeebe:userTask />
                <zeebe:formDefinition externalReference="%s" />
              </bpmn:extensionElements>
            </bpmn:userTask>
        """
        .formatted(
            elementId,
            name == null
                ? ""
                : " name=\"%s\"".formatted(name),
            formReference);

  }

  /**
   * A core which knows the methods named and nothing else.
   */
  private static class ACoreServing extends Camunda8DeploymentServiceTest.NoOpInvoker {

    private final Set<String> served;

    private final boolean claimed;

    ACoreServing(
        final boolean claimed,
        final String... served) {
      this.claimed = claimed;
      this.served = Set.of(served);
    }

    @Override
    public boolean workflowTaskHandlerExists(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinitionOrActivityId) {
      return served.contains(taskDefinitionOrActivityId);
    }

    @Override
    public String resolveWorkflowAggregateIdName(
        final String workflowModuleId,
        final String bpmnProcessId) {
      if (!claimed) {
        throw new IllegalStateException(
            "no @WorkflowService class of this application claims '%s'".formatted(bpmnProcessId));
      }
      return super.resolveWorkflowAggregateIdName(workflowModuleId, bpmnProcessId);
    }

  }

  /**
   * Prepares and wires one model and hands back what the boot wrote meanwhile.
   */
  private static String whatTheBootSaid(
      final CapturedOutput output,
      final BpmnModelInstance model,
      final ACoreServing core) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:65535");
    final var scoping = TestScoping.of(NameClashAvoidance.BY_ADAPTER);
    final var service = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators.of(core, scoping),
        (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration.ofHours(1), adapterId -> configuration, scoping);
    final var before = output.getAll().length();
    assertDoesNotThrow(() -> {
      final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
      service.wireBpmn(MODULE, FILE, PROCESS, model, context);
    });
    return output.getAll().substring(before);

  }

  @Test
  @DisplayName("A claimed process names its user task nothing serves once, on INFO, and boots")
  public void aClaimedProcessNamesTheTaskOnce(
      final CapturedOutput output) {

    final var logged = whatTheBootSaid(
        output,
        model(managedUserTask("Activity_SignTheContract", "sign the contract",
            "signTheContract") + managedUserTask("Activity_ApproveTheLoan", null, "approveTheLoan")),
        new ACoreServing(true));

    assertEquals(
        1,
        Pattern.compile(Pattern.quote(THE_REPORT)).matcher(logged).results().count(),
        () -> "one line per process, not one per task: "
            + logged);
    final var reportLine = logged
        .lines()
        .filter(line -> line.contains(THE_REPORT))
        .findFirst()
        .orElseThrow();
    assertTrue(
        reportLine.contains("INFO"),
        () -> "nothing is wrong with the model, so it is no warning: "
            + reportLine);
    assertTrue(
        logged.contains("BPMN process '%s' of workflow module '%s' has 2 user task(s)".formatted(PROCESS, MODULE)),
        () -> "the process, its module and how many tasks: "
            + logged);
    assertTrue(
        logged.contains("user task 'Activity_SignTheContract' (named 'sign the contract' in the model)"),
        () -> "the element id and the name a modeller searches for: "
            + logged);
    assertTrue(
        logged.contains(
            "@WorkflowTask(taskDefinition = \"approveTheLoan\") or @WorkflowTask(id = \"Activity_ApproveTheLoan\")"),
        () -> "both ways to write the method: "
            + logged);
    assertTrue(
        logged.contains("ProcessService#completeUserTask"),
        () -> "and that the task can be completed anyway: "
            + logged);

  }

  @Test
  @DisplayName("A user task served by its form reference or by its element id is not named")
  public void aServedUserTaskIsNotNamed(
      final CapturedOutput output) {

    final var logged = whatTheBootSaid(
        output,
        model(managedUserTask("Activity_SignTheContract", null,
            "signTheContract") + managedUserTask("Activity_ApproveTheLoan", null, "approveTheLoan")),
        new ACoreServing(true, "signTheContract", "Activity_ApproveTheLoan"));

    assertFalse(
        logged.contains(THE_REPORT),
        () -> "a method names one key or the other, and both count: "
            + logged);

  }

  @Test
  @DisplayName("Only the user task nothing serves is named, not the one next to it")
  public void onlyTheUnservedTaskIsNamed(
      final CapturedOutput output) {

    final var logged = whatTheBootSaid(
        output,
        model(managedUserTask("Activity_SignTheContract", null,
            "signTheContract") + managedUserTask("Activity_ApproveTheLoan", null, "approveTheLoan")),
        new ACoreServing(true, "signTheContract"));

    assertTrue(
        logged.contains("has 1 user task(s)") && logged.contains("'Activity_ApproveTheLoan'"),
        () -> "the unserved one is named: "
            + logged);
    assertFalse(
        logged.contains("'Activity_SignTheContract'"),
        () -> "the served one is not: "
            + logged);

  }

  @Test
  @DisplayName("A process nobody claims says nothing about its user tasks")
  public void anUnclaimedProcessSaysNothing(
      final CapturedOutput output) {

    final var logged = whatTheBootSaid(
        output,
        model(managedUserTask("Activity_SignTheContract", null, "signTheContract")),
        new ACoreServing(false));

    assertFalse(
        logged.contains(THE_REPORT),
        () -> "no method of this application was meant to serve it: "
            + logged);

  }

  @Test
  @DisplayName("A form reference written as an expression is left to its own finding")
  public void aReferenceWrittenAsAnExpressionIsLeftOut() {

    // read straight from the model: a claimed process with such a reference never gets this far,
    // because the deployment refuses it while the file is prepared
    final var model = model(managedUserTask("Activity_SignTheContract", null, "=whichForm"));
    final var userTasks = Camunda8TaskWiring.readUserTasksOf(model, PROCESS, MODULE, FILE);

    final var unserved = Camunda8UnservedUserTasks.of(model, userTasks, reference -> reference, key -> false);

    assertTrue(
        unserved.isEmpty(),
        () -> "the expression has a finding of its own, and a second one would only confuse: "
            + unserved);

  }

}
