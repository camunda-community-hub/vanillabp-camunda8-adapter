package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.Camunda8ProcessingContext;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.camunda8.wiring.Camunda8MultiInstance;
import io.vanillabp.camunda8.wiring.Camunda8OpenTaskProbe.KindOfTask;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the probe of a workflow module is told a record names, taken from the models the
 * module deployed.
 * <p>
 * The rule itself lives in {@code Camunda8OpenTaskProbe}; what is asked here is the half
 * the deployment owns, which is where a wrong answer would come from: the task definition
 * of a Camunda-managed user task is the external form reference its listener job type is
 * built from, and reading the wrong name would either refuse every record of a process or
 * none of them.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8OpenTaskProbeWiringTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "OrderApproval";

  private static final String USER_TASK_FORM = "approve-the-order";

  private static final String USER_TASK_ELEMENT = "Activity_approve";

  private static final String SERVICE_TASK = "chargeTheCard";

  private static final String PROCESS_WITHOUT_A_MODEL = "order_approval";

  @Test
  @DisplayName("One user task in a model does not cost the service tasks of it their answer")
  public void theRefusalIsPerRecord() {

    final var kindOfTask = kindsOf(aModuleWithAUserTaskNextToAServiceTask());

    assertEquals(
        KindOfTask.A_CAMUNDA_MANAGED_USER_TASK,
        kindOfTask.apply(PROCESS, USER_TASK_FORM),
        "a record naming the user task cannot be asked about with a job command");
    assertEquals(
        KindOfTask.A_JOB,
        kindOfTask.apply(PROCESS, SERVICE_TASK),
        "and the service task of the same process is asked about as before");

  }

  @Test
  @DisplayName("A record which kept no task definition counts as a user task where the process has one")
  public void aRecordWithoutATaskDefinition() {

    assertEquals(
        KindOfTask.CANNOT_TELL,
        kindsOf(aModuleWithAUserTaskNextToAServiceTask()).apply(PROCESS, null));
    assertEquals(
        KindOfTask.A_JOB,
        kindsOf(new Camunda8ProcessingContext("c8", MODULE, new Camunda8MultiInstance.Registry()))
            .apply(PROCESS, null),
        "and in a process without one it is answered as before");

  }

  @Test
  @DisplayName("A BPMN process nobody deployed a model for keeps the wide answer")
  public void aProcessWithoutAModelKeepsTheWideAnswer() {

    assertEquals(
        KindOfTask.CANNOT_TELL,
        kindsOf(aModuleWithAUserTaskNextToAServiceTask()).apply(PROCESS_WITHOUT_A_MODEL, SERVICE_TASK),
        "without a model nothing says which of its task definitions is a user task");

  }

  @Test
  @DisplayName("A user task whose 'updating' listener nobody serves is not probed at all")
  public void aUserTaskWithAForeignUpdatingListener() {

    final var context = aModuleWithAUserTaskNextToAServiceTask();
    context.recordUpdatingListenerNobodyServes(PROCESS, USER_TASK_ELEMENT);

    assertEquals(
        KindOfTask.CANNOT_TELL,
        kindsOf(context).apply(PROCESS, USER_TASK_FORM),
        "the empty update would fire a listener job nobody here answers, so nothing is sent");
    assertEquals(
        KindOfTask.A_JOB,
        kindsOf(context).apply(PROCESS, SERVICE_TASK),
        "and the service task next to it keeps its answer");

  }

  private static Camunda8ProcessingContext aModuleWithAUserTaskNextToAServiceTask() {

    final var context = new Camunda8ProcessingContext("c8", MODULE, new Camunda8MultiInstance.Registry());
    context
        .getUserTasksToWire()
        .add(new Camunda8TaskWiring.Camunda8UserTaskToWire(PROCESS, USER_TASK_ELEMENT, USER_TASK_FORM));
    context
        .getTasksToWire()
        .add(new Camunda8TaskWiring.Camunda8TaskToWire(PROCESS, "Activity_charge", SERVICE_TASK));
    return context;

  }

  private static java.util.function.BiFunction<String, String, KindOfTask> kindsOf(
      final Camunda8ProcessingContext context) {

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing listens on: nothing here contacts a cluster
    configuration.setRestAddress("http://localhost:65535");
    final var core = new Camunda8DeploymentServiceTest.NoOpInvoker() {

      @Override
      public Map<String, Collection<String>> taskWiringOfProcessesNobodyDeployed(
          final String workflowModuleId) {

        return Map.of(PROCESS_WITHOUT_A_MODEL, List.of(SERVICE_TASK));

      }

    };
    return DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(core),
        (
            module,
            process,
            task) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofHours(1),
        adapterId -> configuration, null)
        .theKindOfTaskARecordNames(MODULE, context);

  }

}
