package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

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
 * What a boot does about a user task a job worker serves, which is a user task without a
 * {@code zeebe:userTask} extension element.
 *
 * <p>
 * The shape is the finding. This adapter serves a user task only where the cluster manages it,
 * so nothing here asks whether some worker would fetch the job the cluster hands out.
 * <p>
 * Who claims the process decides the answer. A process a {@code @WorkflowService} class of the
 * application claims is a promise that the application serves it, so the boot ends over such an
 * element. A process nobody claims travels to the cluster because of the file it sits in, and
 * there is nothing to ask of a model somebody else owns, so it is named and the boot goes on.
 * <p>
 * One element carries the shape and is still none of this: a user task with a
 * {@code zeebe:taskDefinition} of its own. A worker of the application serves it under a job
 * type the application chose, so the reader passes over it, and a test below holds that
 * boundary.
 * <p>
 * Two shapes reach both messages and both have to keep them apart. One carries the
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
public class Camunda8JobWorkerUserTasksReportTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  /**
   * The sentence which tells this finding apart from everything else a boot may say.
   */
  private static final String THE_FINDING = "no 'zeebe:userTask' extension element";

  /**
   * The reason both messages give: the shape is not one this adapter takes.
   */
  private static final String THE_REASON = "does not accept that shape";

  /**
   * What is said about the shape a search of the models finds.
   */
  private static final String THE_SHAPE_A_SEARCH_FINDS = "carrying the formKey VanillaBP 1 read their task definition from";

  /**
   * What is said about the shape no such search finds.
   */
  private static final String THE_SHAPE_NO_SEARCH_FINDS = "carrying no formKey at all";

  /**
   * The way out which gets the process running, named by the refusal only.
   */
  private static final String THE_WAY_OUT_THROUGH_A_METHOD = "set 'External form reference'";

  /**
   * The other way out, named by the refusal only.
   */
  private static final String THE_WAY_OUT_THROUGH_THE_MODEL = "take the element out of the model";

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
   * The one element which carries the shape and is still none of this: a worker of the
   * application serves it, under a job type the application chose.
   */
  private static BpmnModelInstance aUserTaskAWorkerOfTheApplicationServes() {

    return model("""
            <bpmn:userTask id="Activity_ApproveTheLoan">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="approveTheLoan" />
              </bpmn:extensionElements>
            </bpmn:userTask>
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
   * A core which answers that no {@code @WorkflowService} class of the application claims the
   * process - the answer the core gives by not knowing a workflow aggregate for it.
   */
  private static class NoWorkflowServiceClaimsIt extends Camunda8DeploymentServiceTest.NoOpInvoker {

    @Override
    public String resolveWorkflowAggregateIdName(
        final String workflowModuleId,
        final String bpmnProcessId) {
      throw new IllegalStateException(
          "no @WorkflowService class of this application claims '%s'".formatted(bpmnProcessId));
    }

  }

  /**
   * Wires one model and hands the wiring back unrun, so the test can read what it said or
   * catch what it refused.
   *
   * @param model The model of the one BPMN file
   * @param claimed Whether a workflow service of the application claims the process
   * @return Preparing and wiring that file
   */
  private static Executable wiringOf(
      final BpmnModelInstance model,
      final boolean claimed) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:65535");
    final var scoping = TestScoping.of(NameClashAvoidance.BY_ADAPTER);
    final var core = claimed
        ? new Camunda8DeploymentServiceTest.NoOpInvoker()
        : new NoWorkflowServiceClaimsIt();
    final var service = DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators.of(core, scoping),
        (
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration.ofHours(1), adapterId -> configuration, scoping);
    return () -> {
      final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
      service.wireBpmn(MODULE, FILE, PROCESS, model, context);
    };

  }

  /**
   * Wires one model of a process nobody claims and hands back what this run wrote.
   */
  private static String whatTheBootSaid(
      final CapturedOutput output,
      final BpmnModelInstance model) {

    final var before = output.getAll().length();
    assertDoesNotThrow(wiringOf(model, false));
    return output.getAll().substring(before);

  }

  /**
   * Wires one model of a process the application claims and hands back why the boot ended.
   */
  private static String whyTheBootEnded(
      final BpmnModelInstance model) {

    return assertThrows(IllegalStateException.class, wiringOf(model, true)).getMessage();

  }

  @Test
  @DisplayName("A claimed process whose user task carries version 1's formKey does not deploy")
  public void theFormKeyShapeEndsTheBootOfAClaimedProcess() {

    final var refused = whyTheBootEnded(aUserTaskCarryingAFormKey());

    assertTrue(
        refused.contains(THE_FINDING),
        () -> "nothing else in the boot sees such an element: "
            + refused);
    assertTrue(
        refused.contains("'"
            + PROCESS
            + "'") && refused.contains(
                "'"
                    + MODULE
                    + "'"),
        () -> "the process and its workflow module are what a developer looks for first: "
            + refused);
    assertTrue(
        refused.contains("'Activity_SignTheContract'"),
        () -> "a developer has to read WHICH element it is about: "
            + refused);
    assertTrue(
        refused.contains(THE_SHAPE_A_SEARCH_FINDS),
        () -> "and that a search of the models for 'formKey' finds this one: "
            + refused);
    assertFalse(
        refused.contains(THE_SHAPE_NO_SEARCH_FINDS),
        () -> "the other shape is not in this model, so nothing is said about it: "
            + refused);
    assertTrue(
        refused.contains(THE_REASON),
        () -> "the reason is the shape of the element, not what the cluster would do with it: "
            + refused);
    assertTrue(
        refused.contains("@WorkflowService"),
        () -> "and the boot ends because the application claims this process: "
            + refused);
    assertTrue(
        refused.contains(THE_WAY_OUT_THROUGH_A_METHOD) && refused.contains(THE_WAY_OUT_THROUGH_THE_MODEL),
        () -> "both ways out are named, so the developer needs no documentation: "
            + refused);
    assertTrue(
        refused.contains("The cluster did not answer how many of them are open right now"),
        () -> "this run has no cluster, and the message says that rather than a number: "
            + refused);

  }

  @Test
  @DisplayName("A claimed process whose user task carries no formKey does not deploy either")
  public void theShapeWithoutAFormKeyEndsTheBootToo() {

    final var refused = whyTheBootEnded(aUserTaskCarryingNothing());

    assertTrue(
        refused.contains(THE_FINDING),
        () -> "this was the silent case: the model deployed, the job appeared, nobody fetched it: "
            + refused);
    assertTrue(
        refused.contains("'Activity_ApproveTheLoan'"),
        () -> "named by its element id like every other finding: "
            + refused);
    assertTrue(
        refused.contains(THE_SHAPE_NO_SEARCH_FINDS),
        () -> "and said to be the shape a search for 'formKey' would have missed: "
            + refused);
    assertFalse(
        refused.contains(THE_SHAPE_A_SEARCH_FINDS),
        () -> "the sentence about version 1's convention is not true for this element: "
            + refused);

  }

  @Test
  @DisplayName("Both shapes of a claimed process are named apart, in the one message which ends the boot")
  public void bothShapesAreNamedApart() {

    final var refused = whyTheBootEnded(model("""
            <bpmn:userTask id="Activity_SignTheContract">
              <bpmn:extensionElements>
                <zeebe:formDefinition formKey="camunda-forms:bpmn:signTheContract" />
              </bpmn:extensionElements>
            </bpmn:userTask>
            <bpmn:userTask id="Activity_ApproveTheLoan" name="approve the loan" />
        """));

    assertTrue(
        refused.contains(THE_SHAPE_A_SEARCH_FINDS) && refused.contains("'Activity_SignTheContract'"),
        () -> "the one a search finds: "
            + refused);
    assertTrue(
        refused.contains(THE_SHAPE_NO_SEARCH_FINDS) && refused.contains("'Activity_ApproveTheLoan'"),
        () -> "and the one it does not: "
            + refused);
    assertTrue(
        refused.contains("2 user task(s) with no 'zeebe:userTask' extension element"),
        () -> "counted together, because the way out is the same for both: "
            + refused);

  }

  @Test
  @DisplayName("A claimed process whose user tasks are all Camunda-managed boots")
  public void aServedUserTaskIsNotWorthAWord(
      final CapturedOutput output) {

    final var before = output.getAll().length();
    assertDoesNotThrow(wiringOf(aCamundaManagedUserTask(), true));
    final var logged = output.getAll().substring(before);

    assertFalse(
        logged.contains(THE_FINDING),
        () -> "VanillaBP wires this one and hears about every task it creates: "
            + logged);

  }

  @Test
  @DisplayName("A process nobody claims keeps its warning, and the boot goes on")
  public void anUnclaimedProcessIsNamedAndBoots(
      final CapturedOutput output) {

    final var logged = whatTheBootSaid(output, aUserTaskCarryingNothing());

    assertTrue(
        logged.contains(THE_FINDING),
        () -> "a reader still has to learn that the element is there: "
            + logged);
    assertTrue(
        logged.contains("'Activity_ApproveTheLoan'") && logged.contains(THE_SHAPE_NO_SEARCH_FINDS),
        () -> "named by its element id and by its shape, as before: "
            + logged);
    assertTrue(
        logged.contains(THE_REASON),
        () -> "with the same reason the refusal gives: "
            + logged);
    assertTrue(
        logged.contains("No @WorkflowService class of this application claims this process"),
        () -> "and told why this one is a warning: "
            + logged);
    assertFalse(
        logged.contains(THE_WAY_OUT_THROUGH_A_METHOD) || logged.contains(THE_WAY_OUT_THROUGH_THE_MODEL),
        () -> "nothing asks the reader to change a model which is none of ours: "
            + logged);

  }

  @Test
  @DisplayName("A claimed process whose user task carries a task definition of its own boots")
  public void anElementTheApplicationServesItselfIsLeftAlone(
      final CapturedOutput output) {

    final var before = output.getAll().length();
    assertDoesNotThrow(wiringOf(aUserTaskAWorkerOfTheApplicationServes(), true));
    final var logged = output.getAll().substring(before);

    assertFalse(
        logged.contains(THE_FINDING),
        () -> "a task definition of its own says a worker of the application serves the element, "
            + "so it is no user task of VanillaBP's and this check passes over it: "
            + logged);

  }

  @Test
  @DisplayName("A process nobody claims keeps its warning for version 1's shape too")
  public void anUnclaimedProcessIsNamedForTheFormKeyShapeToo(
      final CapturedOutput output) {

    final var logged = whatTheBootSaid(output, aUserTaskCarryingAFormKey());

    assertTrue(
        logged.contains(THE_SHAPE_A_SEARCH_FINDS) && logged.contains("'Activity_SignTheContract'"),
        () -> "the shape a search of the models finds is named here as well: "
            + logged);

  }

}
