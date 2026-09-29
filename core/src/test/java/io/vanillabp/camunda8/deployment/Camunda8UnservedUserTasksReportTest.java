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
 * What a boot says about a plain BPMN user task, which the cluster serves with a job of its
 * own user-task type and this version serves with nothing.
 * <p>
 * Two shapes reach this report and the message has to keep them apart. One carries the
 * {@code formKey} VanillaBP 1 read the task definition from up to its release 1.6.3, and an
 * upgrading application finds those by searching its models for that word. The other carries
 * no {@code formKey} at all, so no such search finds it, while the cluster does exactly the
 * same with it: it hands out a job nobody fetches, and the workflow stands at the element
 * until the job's retries are used up.
 * <p>
 * The cluster is an address nothing listens on, so the count of open tasks is the one thing
 * these runs cannot have. That the message says so instead of a number is asserted as well.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8UnservedUserTasksReportTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  /**
   * The sentence which tells this finding apart from everything else a boot may say.
   */
  private static final String THE_FINDING = "are plain BPMN user tasks";

  /**
   * What is said about the shape a search of the models finds.
   */
  private static final String THE_SHAPE_A_SEARCH_FINDS = "carrying the formKey VanillaBP 1 read their task definition from";

  /**
   * What is said about the shape no such search finds.
   */
  private static final String THE_SHAPE_NO_SEARCH_FINDS = "carrying no formKey at all";

  private static BpmnModelInstance model(
      final String userTaskContent) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
        %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, userTaskContent);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  /**
   * The shape VanillaBP 1 served up to its release 1.6.3.
   */
  private static BpmnModelInstance aUserTaskCarryingAFormKey() {

    return model("""
            <bpmn:userTask id="Activity_SignTheContract">
              <bpmn:extensionElements>
                <zeebe:formDefinition formKey="camunda-forms:bpmn:signTheContract" />
              </bpmn:extensionElements>
            </bpmn:userTask>
        """);

  }

  /**
   * A user task somebody modelled in a modeller and left as it was, which was never a
   * VanillaBP shape.
   */
  private static BpmnModelInstance aUserTaskCarryingNothing() {

    return model("""
            <bpmn:userTask id="Activity_ApproveTheLoan" name="approve the loan" />
        """);

  }

  /**
   * The shape this version serves.
   */
  private static BpmnModelInstance aCamundaManagedUserTask() {

    return model("""
            <bpmn:userTask id="Activity_SignTheContract">
              <bpmn:extensionElements>
                <zeebe:userTask />
                <zeebe:formDefinition externalReference="signTheContract" />
              </bpmn:extensionElements>
            </bpmn:userTask>
        """);

  }

  /**
   * Deploys one model and hands back what this run wrote.
   */
  private static String whatTheBootSaid(
      final CapturedOutput output,
      final BpmnModelInstance model) {

    final var before = output.getAll().length();
    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:65535");
    final var scoping = TestScoping.of(NameClashAvoidance.BY_ADAPTER);
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
  @DisplayName("A user task carrying version 1's formKey is named as the shape a search of the models finds")
  public void theFormKeyShapeIsNamedAsSuch(
      final CapturedOutput output) {

    final var logged = whatTheBootSaid(output, aUserTaskCarryingAFormKey());

    assertTrue(
        logged.contains(THE_FINDING),
        () -> "nothing else in the boot sees such an element: "
            + logged);
    assertTrue(
        logged.contains("'Activity_SignTheContract'"),
        () -> "a developer has to read WHICH element it is about: "
            + logged);
    assertTrue(
        logged.contains(THE_SHAPE_A_SEARCH_FINDS),
        () -> "and that a search of the models for 'formKey' finds this one: "
            + logged);
    assertFalse(
        logged.contains(THE_SHAPE_NO_SEARCH_FINDS),
        () -> "the other shape is not in this model, so nothing is said about it: "
            + logged);
    assertTrue(
        logged.contains("io.camunda.zeebe:userTask"),
        () -> "the job type the cluster serves it with is what an own worker would subscribe to: "
            + logged);
    assertTrue(
        logged.contains("The cluster did not answer how many of them are open right now"),
        () -> "this run has no cluster, and the message says that rather than a number: "
            + logged);

  }

  @Test
  @DisplayName("A plain user task without a formKey is reported too, as the shape no search finds")
  public void theShapeWithoutAFormKeyIsReportedToo(
      final CapturedOutput output) {

    final var logged = whatTheBootSaid(output, aUserTaskCarryingNothing());

    assertTrue(
        logged.contains(THE_FINDING),
        () -> "this was the silent case: the model deploys, the job appears, nobody fetches it: "
            + logged);
    assertTrue(
        logged.contains("'Activity_ApproveTheLoan'"),
        () -> "named by its element id like every other finding: "
            + logged);
    assertTrue(
        logged.contains(THE_SHAPE_NO_SEARCH_FINDS),
        () -> "and said to be the shape a search for 'formKey' would have missed: "
            + logged);
    assertFalse(
        logged.contains(THE_SHAPE_A_SEARCH_FINDS),
        () -> "the sentence about version 1's convention is not true for this element: "
            + logged);

  }

  @Test
  @DisplayName("Both shapes in one process are named apart, in one message")
  public void bothShapesAreNamedApart(
      final CapturedOutput output) {

    final var logged = whatTheBootSaid(output, model("""
            <bpmn:userTask id="Activity_SignTheContract">
              <bpmn:extensionElements>
                <zeebe:formDefinition formKey="camunda-forms:bpmn:signTheContract" />
              </bpmn:extensionElements>
            </bpmn:userTask>
            <bpmn:userTask id="Activity_ApproveTheLoan" name="approve the loan" />
        """));

    assertTrue(
        logged.contains(THE_SHAPE_A_SEARCH_FINDS) && logged.contains("'Activity_SignTheContract'"),
        () -> "the one a search finds: "
            + logged);
    assertTrue(
        logged.contains(THE_SHAPE_NO_SEARCH_FINDS) && logged.contains("'Activity_ApproveTheLoan'"),
        () -> "and the one it does not: "
            + logged);
    assertTrue(
        logged.contains("2 user task(s)"),
        () -> "counted together, because the way out is the same for both: "
            + logged);

  }

  @Test
  @DisplayName("A Camunda-managed user task is served, so the boot says nothing about it")
  public void aServedUserTaskIsNotWorthAWord(
      final CapturedOutput output) {

    final var logged = whatTheBootSaid(output, aCamundaManagedUserTask());

    assertFalse(
        logged.contains(THE_FINDING),
        () -> "VanillaBP wires this one and hears about every task it creates: "
            + logged);

  }

}
