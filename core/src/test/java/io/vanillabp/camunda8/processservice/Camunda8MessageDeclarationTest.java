package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.deployment.Camunda8DeployedProcesses;
import io.vanillabp.camunda8.deployment.Camunda8ModelsTheClusterHolds;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Phase one of a message correlation asks the deployed models whether they
 * declare the message. A name no model knows would be published into the void - the
 * cluster buffers it until its time-to-live passes, so nothing correlates and nothing
 * fails.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8MessageDeclarationTest {

  private record Aggregate(Object id) {
  }

  private static AggregatePersistenceAware<Aggregate> persistence() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<Aggregate> getAggregateClass() {
        return Aggregate.class;
      }

      @Override
      public Aggregate save(
          final Aggregate aggregate) {
        return aggregate;
      }

      @Override
      public Object getAggregateId(
          final Aggregate aggregate) {
        return aggregate.id();
      }

    };

  }

  private static BpmnModelInstance modelWaitingFor(
      final String messageName) {

    return Bpmn
        .createExecutableProcess("Process")
        .startEvent()
        .intermediateCatchEvent("wait")
        .message(message -> message.name(messageName))
        .endEvent()
        .done();

  }

  private static Camunda8ClientFactory clientFactory() {

    final var configuration = new Camunda8AdapterConfiguration();
    // never contacted: phase one asks the models, not the cluster
    configuration.setRestAddress("http://localhost:1");
    // no waiting for the exporter in a unit test - the cluster is never contacted
    configuration.setWorkflowVisibilityTimeout(Duration.ZERO);
    return new Camunda8ClientFactory("c8", configuration);

  }

  private static Camunda8ProcessService<Aggregate> serviceOf(
      final Camunda8ClientFactory clientFactory) {

    return new Camunda8ProcessService<>(
        "c8", clientFactory, Duration.ofDays(14), (
            aggregateClass,
            check) -> check.run(), null);

  }

  private static void deploy(
      final Camunda8ClientFactory clientFactory,
      final String workflowModuleId,
      final BpmnModelInstance model) {

    clientFactory
        .getDeployedProcesses()
        .record(
            new Camunda8DeployedProcesses.DeployedProcess(
                workflowModuleId, "Process", "2251799813685249", 1, model));

  }

  @Test
  @DisplayName("A message the model declares passes phase one")
  public void aDeclaredMessagePasses() {

    final var clientFactory = clientFactory();
    deploy(clientFactory, "module", modelWaitingFor("PaymentReceived"));

    assertDoesNotThrow(
        () -> PhaseOperations.phaseOne(serviceOf(clientFactory),
            PhaseOperation.CORRELATE_MESSAGE, "module", "Process", persistence(),
            new Aggregate("agg-1"), PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME,
                "PaymentReceived", PhaseTwoCall.ARG_CORRELATION_ID, null)));

  }

  @Test
  @DisplayName("A message no model declares fails where the application called, naming the declared ones")
  public void anUndeclaredMessageFailsInPhaseOne() {

    final var clientFactory = clientFactory();
    deploy(clientFactory, "module", modelWaitingFor("PaymentReceived"));

    final var failure = assertThrows(
        IllegalArgumentException.class,
        () -> PhaseOperations.phaseOne(serviceOf(clientFactory),
            PhaseOperation.CORRELATE_MESSAGE, "module", "Process", persistence(),
            new Aggregate("agg-1"), PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME,
                "PaymentRecieved", PhaseTwoCall.ARG_CORRELATION_ID, null)));

    assertTrue(failure.getMessage().contains("PaymentRecieved"), failure.getMessage());
    // the remedy: what IS declared
    assertTrue(failure.getMessage().contains("PaymentReceived"), failure.getMessage());

  }

  @Test
  @DisplayName("Without a process deployed by this application version the check stays silent")
  public void withoutDeployedProcessesTheCheckIsSilent() {

    final var clientFactory = clientFactory();
    // a workflow still running on a definition of a PREVIOUS application version: the
    // declared names are unknown here rather than absent, so correlating must not fail
    deploy(clientFactory, "another-module", modelWaitingFor("PaymentReceived"));

    assertDoesNotThrow(
        () -> PhaseOperations.phaseOne(serviceOf(clientFactory),
            PhaseOperation.CORRELATE_MESSAGE, "module", "Process", persistence(),
            new Aggregate("agg-1"), PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME,
                "AnyMessage", PhaseTwoCall.ARG_CORRELATION_ID, null)));

  }

  @Test
  @DisplayName("A module declaring an id nothing was deployed under refuses nothing, whatever the name")
  public void aModuleWithADeclaredOnlyIdRefusesNothing() {

    final var clientFactory = clientFactory();
    deploy(clientFactory, "module", modelWaitingFor("PaymentReceived"));
    // the old id of a renamed process: the message a waiting workflow needs may be
    // declared only by a model the cluster holds, so the declared names are unknown
    // rather than absent
    clientFactory
        .getDeployedProcesses()
        .recordDeclaredWithoutDeployment("module", "OldProcess");

    assertDoesNotThrow(
        () -> PhaseOperations.phaseOne(serviceOf(clientFactory),
            PhaseOperation.CORRELATE_MESSAGE, "module", "Process", persistence(),
            new Aggregate("agg-1"), PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME,
                "DeclaredNowhereAmongTheDeployedModels", PhaseTwoCall.ARG_CORRELATION_ID, null)));

  }

  /**
   * A picture answering from the given models, next to the deployed ones - what the
   * deployment service assembles from the cluster at runtime.
   */
  private static void pictureAnswering(
      final Camunda8ClientFactory clientFactory,
      final java.util.function.BiFunction<String, String, java.util.List<Camunda8ModelsTheClusterHolds.HeldModel>> modelsOfProcess) {

    clientFactory
        .provideModelsTheClusterHolds(
            new Camunda8ModelsTheClusterHolds(
                "c8", clientFactory.getDeployedProcesses(), modelsOfProcess::apply));

  }

  @Test
  @DisplayName("A message only a model of the RENAMED process declares passes: the cluster's models count")
  public void aMessageOnlyTheClusterHeldModelDeclaresPasses() {

    final var clientFactory = clientFactory();
    deploy(clientFactory, "module", modelWaitingFor("PaymentReceived"));
    clientFactory
        .getDeployedProcesses()
        .recordDeclaredWithoutDeployment("module", "OldProcess");
    // the model the cluster still holds under the old id declares the message the
    // waiting workflow needs - the current deployment does not
    pictureAnswering(clientFactory, (
        module,
        bpmnProcessId) -> "OldProcess".equals(bpmnProcessId)
            ? java.util.List.of(new Camunda8ModelsTheClusterHolds.HeldModel(
                "OldProcess", "1", modelWaitingFor("RenameContinue")))
            : java.util.List.of());

    assertDoesNotThrow(
        () -> PhaseOperations.phaseOne(serviceOf(clientFactory),
            PhaseOperation.CORRELATE_MESSAGE, "module", "Process", persistence(),
            new Aggregate("agg-1"), PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME,
                "RenameContinue", PhaseTwoCall.ARG_CORRELATION_ID, null)));

  }

  @Test
  @DisplayName("A name NO model declares still fails, naming what the cluster holds")
  public void aNameNoModelDeclaresFailsNamingWhatTheClusterHolds() {

    final var clientFactory = clientFactory();
    deploy(clientFactory, "module", modelWaitingFor("PaymentReceived"));
    clientFactory
        .getDeployedProcesses()
        .recordDeclaredWithoutDeployment("module", "OldProcess");
    pictureAnswering(clientFactory, (
        module,
        bpmnProcessId) -> "OldProcess".equals(bpmnProcessId)
            ? java.util.List.of(new Camunda8ModelsTheClusterHolds.HeldModel(
                "OldProcess", "1", modelWaitingFor("RenameContinue")))
            : java.util.List.of(new Camunda8ModelsTheClusterHolds.HeldModel(
                "Process", "1", modelWaitingFor("PaymentReceived"))));

    final var failure = assertThrows(
        IllegalArgumentException.class,
        () -> PhaseOperations.phaseOne(serviceOf(clientFactory),
            PhaseOperation.CORRELATE_MESSAGE, "module", "Process", persistence(),
            new Aggregate("agg-1"), PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME,
                "DeclaredNowhereAtAll", PhaseTwoCall.ARG_CORRELATION_ID, null)));

    assertTrue(failure.getMessage().contains("DeclaredNowhereAtAll"), failure.getMessage());
    // the remedy names the messages of the models the CLUSTER holds, the renamed
    // process' old model included
    assertTrue(failure.getMessage().contains("RenameContinue"), failure.getMessage());
    assertTrue(failure.getMessage().contains("PaymentReceived"), failure.getMessage());

  }

  @Test
  @DisplayName("Where the cluster cannot be asked, the check stays silent instead of refusing")
  public void whereTheClusterCannotBeAskedTheCheckStaysSilent() {

    final var clientFactory = clientFactory();
    deploy(clientFactory, "module", modelWaitingFor("PaymentReceived"));
    clientFactory
        .getDeployedProcesses()
        .recordDeclaredWithoutDeployment("module", "OldProcess");
    pictureAnswering(clientFactory, (
        module,
        bpmnProcessId) -> null);

    assertDoesNotThrow(
        () -> PhaseOperations.phaseOne(serviceOf(clientFactory),
            PhaseOperation.CORRELATE_MESSAGE, "module", "Process", persistence(),
            new Aggregate("agg-1"), PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME,
                "AnyMessage", PhaseTwoCall.ARG_CORRELATION_ID, null)));

  }

  @Test
  @DisplayName("A refusal reads the cluster again first, so another node's deployment is seen")
  public void aRefusalRestsOnAFreshRead() {

    final var clientFactory = clientFactory();
    deploy(clientFactory, "module", modelWaitingFor("PaymentReceived"));
    clientFactory
        .getDeployedProcesses()
        .recordDeclaredWithoutDeployment("module", "OldProcess");
    final var whatTheClusterHolds = new java.util.concurrent.atomic.AtomicReference<>(
        java.util.List.of(new Camunda8ModelsTheClusterHolds.HeldModel(
            "OldProcess", "1", modelWaitingFor("RenameContinue"))));
    pictureAnswering(clientFactory, (
        module,
        bpmnProcessId) -> "OldProcess".equals(bpmnProcessId)
            ? whatTheClusterHolds.get()
            : java.util.List.of());
    final var service = serviceOf(clientFactory);

    // settles the picture with the models of today
    assertDoesNotThrow(
        () -> PhaseOperations.phaseOne(service,
            PhaseOperation.CORRELATE_MESSAGE, "module", "Process", persistence(),
            new Aggregate("agg-1"), PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME,
                "RenameContinue", PhaseTwoCall.ARG_CORRELATION_ID, null)));

    // another node deploys a version declaring a new message - the kept picture
    // does not carry it, so refusing from the kept picture would be wrong
    whatTheClusterHolds
        .set(
            java.util.List.of(
                new Camunda8ModelsTheClusterHolds.HeldModel(
                    "OldProcess", "1", modelWaitingFor("RenameContinue")),
                new Camunda8ModelsTheClusterHolds.HeldModel(
                    "OldProcess", "2", modelWaitingFor("AddedElsewhere"))));

    assertDoesNotThrow(
        () -> PhaseOperations.phaseOne(service,
            PhaseOperation.CORRELATE_MESSAGE, "module", "Process", persistence(),
            new Aggregate("agg-1"), PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME,
                "AddedElsewhere", PhaseTwoCall.ARG_CORRELATION_ID, null)));

  }

  @Test
  @DisplayName("A declared-only id of ANOTHER module does not silence the check")
  public void aDeclaredOnlyIdOfAnotherModuleChangesNothing() {

    final var clientFactory = clientFactory();
    deploy(clientFactory, "module", modelWaitingFor("PaymentReceived"));
    clientFactory
        .getDeployedProcesses()
        .recordDeclaredWithoutDeployment("another-module", "OldProcess");

    assertThrows(
        IllegalArgumentException.class,
        () -> PhaseOperations.phaseOne(serviceOf(clientFactory),
            PhaseOperation.CORRELATE_MESSAGE, "module", "Process", persistence(),
            new Aggregate("agg-1"), PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME,
                "PaymentRecieved", PhaseTwoCall.ARG_CORRELATION_ID, null)));

  }

  @Test
  @DisplayName("Messages of every deployed process of the module count, not only the calling one")
  public void everyProcessOfTheModuleCounts() {

    final var clientFactory = clientFactory();
    deploy(clientFactory, "module", modelWaitingFor("PaymentReceived"));
    clientFactory
        .getDeployedProcesses()
        .record(
            new Camunda8DeployedProcesses.DeployedProcess(
                "module", "CalledProcess", "2251799813685250", 1, modelWaitingFor("DocumentsArrived")));

    // the message waits in a called process - same aggregate, other BPMN process
    assertDoesNotThrow(
        () -> PhaseOperations.phaseOne(serviceOf(clientFactory),
            PhaseOperation.CORRELATE_MESSAGE, "module", "Process", persistence(),
            new Aggregate("agg-1"), PhaseOperations.args(PhaseTwoCall.ARG_MESSAGE_NAME,
                "DocumentsArrived", PhaseTwoCall.ARG_CORRELATION_ID, null)));

  }

}
