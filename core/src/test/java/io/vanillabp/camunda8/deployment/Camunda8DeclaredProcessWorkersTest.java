package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.Camunda8ProcessingContext;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.TestScoping;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.camunda8.wiring.Camunda8MultiInstance;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which workers a workflow module opens for a BPMN process id it DECLARES without
 * deploying a model under it - the old id of a renamed process.
 * <p>
 * The whole point of those workers is the name a job carries. Under
 * <code>use-prefix</code> a task definition is deployed as
 * <code>&lt;module&gt;__&lt;process&gt;__&lt;task&gt;</code>, so the jobs of the workflows
 * under the old id are named after the OLD id and no worker of the deployed processes asks
 * for them; under every other mode the same job is named like any other and there is
 * nothing to open. Both cases are here, and so is the one an application cannot be helped
 * with: a method wired to a BPMN element id names no task definition, and a job type
 * cannot be composed from a model this application no longer has.
 * <p>
 * The cluster is an address nothing listens on, which is enough: opening a worker
 * contacts nobody, and what is asked here is which job types were subscribed to.
 * {@code Camunda8RenamedProcessIT} is the same question against a real cluster.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda8DeclaredProcessWorkersTest {

  private static final String MODULE = "test-module";

  private static final String OLD_ID = "order_approval";

  private static final String TASK_DEFINITION = "approve";

  @Test
  @DisplayName("Prefixed task definitions get a worker per name the old id's jobs carry")
  public void prefixedTaskDefinitionsGetTheirOwnWorkers(
      final CapturedOutput output) {

    final var service = adapterServing(Map.of(OLD_ID, List.of(TASK_DEFINITION)), NameClashAvoidance.USE_PREFIX);
    final var context = new Camunda8ProcessingContext("c8", MODULE, new Camunda8MultiInstance.Registry());

    try {
      service.startWorkflowProcessing(MODULE, context);

      final var logged = output.getOut() + output.getErr();
      assertTrue(
          logged.contains("test-module__order_approval__approve"),
          () -> "a worker for the job type the tasks of the old id produce: "
              + logged);
      assertTrue(
          logged.contains(Camunda8TaskWiring.TASKDEFINITION_USERTASK_ZEEBE
              + "test-module__order_approval__approve"),
          () -> "and one for the case that task definition belongs to a user task: "
              + logged);
      assertTrue(
          logged.contains("declared BPMN process 'order_approval'"),
          () -> "the report names the id those workers were opened for: "
              + logged);
    } finally {
      service.stopWorkflowProcessing(MODULE, context);
    }

  }

  @Test
  @DisplayName("An unprefixed task definition needs no worker of its own")
  public void anUnprefixedTaskDefinitionNeedsNothing(
      final CapturedOutput output) {

    final var service = adapterServing(Map.of(OLD_ID, List.of(TASK_DEFINITION)), NameClashAvoidance.NONE);
    final var context = new Camunda8ProcessingContext("c8", MODULE, new Camunda8MultiInstance.Registry());
    // the deployed processes of the module already subscribe to that name
    context
        .getTasksToWire()
        .add(new Camunda8TaskWiring.Camunda8TaskToWire("OrderApproval", "Activity_approve", TASK_DEFINITION));

    try {
      service.startWorkflowProcessing(MODULE, context);

      final var logged = output.getOut() + output.getErr();
      assertTrue(
          logged.contains("served by the workers of the deployed processes"),
          () -> "the start says that the old id needs nothing of its own: "
              + logged);
      assertFalse(
          logged.contains(Camunda8TaskWiring.TASKDEFINITION_USERTASK_ZEEBE + TASK_DEFINITION),
          () -> "and no second subscription was opened for it: "
              + logged);
    } finally {
      service.stopWorkflowProcessing(MODULE, context);
    }

  }

  @Test
  @DisplayName("A declared id whose methods name no task definition is a warning naming both ways out")
  public void aDeclaredIdWithoutATaskDefinitionIsReported(
      final CapturedOutput output) {

    final var service = adapterServing(Map.of(OLD_ID, List.of()), NameClashAvoidance.USE_PREFIX);
    final var context = new Camunda8ProcessingContext("c8", MODULE, new Camunda8MultiInstance.Registry());

    try {
      service.startWorkflowProcessing(MODULE, context);

      final var logged = output.getOut() + output.getErr();
      assertTrue(
          logged.contains("no @WorkflowTask method serving that id names a task definition"),
          () -> "what cannot be reached has to be read before the rename is deployed: "
              + logged);
      assertTrue(
          logged.contains("@WorkflowTask(taskDefinition = ...)"),
          () -> "and the way out is part of it: "
              + logged);
      assertTrue(
          logged.contains("keep deploying the old model under its old id"),
          () -> "as is the way which asks nothing of the cluster: "
              + logged);
    } finally {
      service.stopWorkflowProcessing(MODULE, context);
    }

  }

  @Test
  @DisplayName("The multi-instance chains of the models the cluster holds reach the registry")
  public void theChainsOfTheClusterHeldModelsAreRegistered() {

    final var adapter = adapter(Map.of(OLD_ID, List.of(TASK_DEFINITION)), NameClashAvoidance.USE_PREFIX);
    final var service = adapter.service();
    final var context = new Camunda8ProcessingContext("c8", MODULE, new Camunda8MultiInstance.Registry());
    // the cluster still holds the old id's model, and its task sits inside a
    // multi-instance element - the iteration context of its jobs comes from here
    final var scopedOldId = "test-module__order_approval";
    adapter
        .clientFactory()
        .provideModelsTheClusterHolds(
            new Camunda8ModelsTheClusterHolds(
                "c8", adapter.clientFactory().getDeployedProcesses(), (
                    module,
                    bpmnProcessId) -> OLD_ID.equals(bpmnProcessId)
                        ? List.of(new Camunda8ModelsTheClusterHolds.HeldModel(
                            OLD_ID, "1", heldModelWithAMultiInstanceTask(scopedOldId)))
                        : List.of()));

    try {
      service.startWorkflowProcessing(MODULE, context);

      final var chain = service.multiInstanceRegistry().chainOf(scopedOldId, "Approve");
      assertFalse(
          chain.isEmpty(),
          "a job of the old id's multi-instance task has to get its iteration context");
    } finally {
      service.stopWorkflowProcessing(MODULE, context);
    }

  }

  /**
   * A model as the cluster holds it: the process id is the SCOPED one, and one task
   * carries multi-instance loop characteristics.
   */
  private static io.camunda.zeebe.model.bpmn.BpmnModelInstance heldModelWithAMultiInstanceTask(
      final String scopedBpmnProcessId) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:serviceTask id="Approve">
              <bpmn:extensionElements>
                <zeebe:taskDefinition type="test-module__order_approval__approve" />
              </bpmn:extensionElements>
              <bpmn:multiInstanceLoopCharacteristics>
                <bpmn:extensionElements>
                  <zeebe:loopCharacteristics inputCollection="=items" inputElement="item" />
                </bpmn:extensionElements>
              </bpmn:multiInstanceLoopCharacteristics>
            </bpmn:serviceTask>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(scopedBpmnProcessId);
    return io.camunda.zeebe.model.bpmn.Bpmn
        .readModelFromStream(
            new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

  }

  /**
   * The adapter under test together with its client factory, which is where the picture
   * of the cluster-held models lives.
   */
  private record Adapter(
                         Camunda8DeploymentService service,
                         Camunda8ClientFactory clientFactory) {
  }

  /**
   * An adapter whose core declares the given task definitions per BPMN process id nothing
   * was deployed under, and whose workflow modules are scoped by the given mode.
   */
  private static Camunda8DeploymentService adapterServing(
      final Map<String, Collection<String>> declaredWithoutAModel,
      final NameClashAvoidance mode) {

    return adapter(declaredWithoutAModel, mode).service();

  }

  private static Adapter adapter(
      final Map<String, Collection<String>> declaredWithoutAModel,
      final NameClashAvoidance mode) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setShutdownGrace(Duration.ofMillis(200));
    // an address nothing listens on: a worker is opened without contacting anybody
    configuration.setRestAddress("http://localhost:65535");
    final var core = new Camunda8DeploymentServiceTest.NoOpInvoker() {

      @Override
      public Map<String, Collection<String>> taskWiringOfProcessesNobodyDeployed(
          final String workflowModuleId) {

        return MODULE.equals(workflowModuleId)
            ? declaredWithoutAModel
            : Map.of();

      }

    };
    final var scoping = TestScoping.of(mode);
    final var clientFactory = new Camunda8ClientFactory("c8", configuration);
    final var service = DeploymentServiceUnderTest.of(
        "c8", clientFactory, TestCollaborators
            .of(core, scoping),
        (
            module,
            process,
            task) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofHours(1),
        adapterId -> configuration, scoping);
    return new Adapter(service, clientFactory);

  }

}
