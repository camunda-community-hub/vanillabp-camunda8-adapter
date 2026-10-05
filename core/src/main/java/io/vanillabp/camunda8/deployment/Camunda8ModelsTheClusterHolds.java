package io.vanillabp.camunda8.deployment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import lombok.extern.slf4j.Slf4j;

/**
 * Which models the cluster holds for the BPMN process ids one workflow module
 * DECLARES: every version the cluster still keeps of a deployed id, and everything it
 * keeps under an id the application declares without deploying anything - the old id
 * of a renamed process.
 * <p>
 * Every check judging a model asks this picture instead of reading a set of its own,
 * so no check's verdict depends on which application version deployed the model it
 * judges - see decision 21 in the repository's DECISIONS.md. The models of the CURRENT
 * deployment cost nothing (they are read at every boot anyway, see
 * {@link Camunda8DeployedProcesses}); everything else is read from the cluster on
 * first use and kept, because a definition's model never changes.
 * <p>
 * The answer carries "cannot tell" as a value rather than as an absence: a check
 * which cannot see every model that could carry its answer must stay silent, never
 * refuse, and it must not have to invent that case for itself. "Cannot tell" is not
 * kept - a cluster unreachable now says nothing about the next call - while the
 * settled "cannot be searched" of {@code Camunda8QueryApi} answers without a request.
 */
@Slf4j
public class Camunda8ModelsTheClusterHolds {

  /**
   * One model the cluster holds.
   *
   * @param bpmnProcessId The PLAIN BPMN process id the model was declared for
   * @param version The version the cluster assigned
   * @param model The model as the cluster runs it
   */
  public record HeldModel(
                          String bpmnProcessId,
                          String version,
                          BpmnModelInstance model) {
  }

  /**
   * What the picture answers: the models, or that the cluster cannot be asked.
   */
  public sealed interface Answer {

    /**
     * The cluster was asked and answered.
     *
     * @param models The models the cluster holds
     * @param freshlyRead Whether every model was read by THIS call - a caller about
     *          to refuse something re-reads first where this is <code>false</code>,
     *          so a refusal never rests on a picture another node's deployment has
     *          outdated
     */
    record Known(List<HeldModel> models, boolean freshlyRead) implements Answer {
    }

    /**
     * The cluster could not be asked - it cannot be searched, or it did not answer.
     */
    record CannotTell() implements Answer {
    }

  }

  /**
   * Reads the models of every version the cluster holds under one BPMN process id -
   * the deployment service's own machinery ({@code Camunda8ProcessVersions}).
   */
  @FunctionalInterface
  public interface ModelsOfProcess {

    /**
     * Reads every version the cluster holds under one BPMN process id.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The PLAIN BPMN process ID
     * @return The models, empty where the cluster holds nothing under the id, or
     *         <code>null</code> where it could not be asked
     */
    List<HeldModel> read(
        String workflowModuleId,
        String bpmnProcessId);

  }

  private final String adapterId;

  private final Camunda8DeployedProcesses deployedProcesses;

  private final ModelsOfProcess modelsOfProcess;

  /**
   * The settled answers, by {@code <workflow module>|<bpmn process id>}. Only a
   * successful read is kept: a definition's model never changes, while a failed read
   * says nothing about the next one.
   */
  private final Map<String, List<HeldModel>> held = new ConcurrentHashMap<>();

  /**
   * Which reads were already reported as failed, so a cluster outage costs one
   * warning per process id rather than one per call.
   */
  private final Set<String> reportedUnreadable = ConcurrentHashMap.newKeySet();

  /**
   * Opens the picture of one adapter id. It starts out empty and settles per BPMN process id as
   * the checks ask for it.
   *
   * @param adapterId The adapter id whose cluster is asked
   * @param deployedProcesses What this application version deployed, which is read before the
   *          cluster is
   * @param modelsOfProcess How a version of a process is read off the cluster
   */
  public Camunda8ModelsTheClusterHolds(
      final String adapterId,
      final Camunda8DeployedProcesses deployedProcesses,
      final ModelsOfProcess modelsOfProcess) {

    this.adapterId = adapterId;
    this.deployedProcesses = deployedProcesses;
    this.modelsOfProcess = modelsOfProcess;

  }

  /**
   * The models the cluster holds for every BPMN process id the workflow module
   * declares. One id which cannot be read makes the whole answer "cannot tell":
   * a finer answer would claim a completeness it does not have.
   *
   * @param workflowModuleId The workflow module ID
   * @return The models or "cannot tell"
   */
  public Answer heldFor(
      final String workflowModuleId) {

    return heldFor(workflowModuleId, false);

  }

  /**
   * The same answer, read from the cluster again: for a caller about to refuse
   * something on a picture which might be outdated by another node's deployment.
   *
   * @param workflowModuleId The workflow module ID
   * @return The models or "cannot tell"
   */
  public Answer heldForAfterReadingAgain(
      final String workflowModuleId) {

    return heldFor(workflowModuleId, true);

  }

  private Answer heldFor(
      final String workflowModuleId,
      final boolean readAgain) {

    final var models = new ArrayList<HeldModel>();
    var everyModelFreshlyRead = true;
    for (final var bpmnProcessId : declaredIdsOf(workflowModuleId)) {
      final var answer = heldFor(workflowModuleId, bpmnProcessId, readAgain);
      if (answer instanceof Answer.CannotTell cannotTell) {
        return cannotTell;
      }
      final var known = (Answer.Known) answer;
      models.addAll(known.models());
      everyModelFreshlyRead &= known.freshlyRead();
    }
    return new Answer.Known(List.copyOf(models), everyModelFreshlyRead);

  }

  /**
   * The models the cluster holds under ONE BPMN process id the workflow module
   * declares.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The models or "cannot tell"
   */
  public Answer heldFor(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return heldFor(workflowModuleId, bpmnProcessId, false);

  }

  private Answer heldFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final boolean readAgain) {

    final var key = workflowModuleId
        + "|"
        + bpmnProcessId;
    if (!readAgain) {
      final var settled = held.get(key);
      if (settled != null) {
        return new Answer.Known(settled, false);
      }
    }
    final List<HeldModel> read;
    try {
      read = modelsOfProcess.read(workflowModuleId, bpmnProcessId);
    } catch (final RuntimeException e) {
      reportUnreadable(workflowModuleId, bpmnProcessId, key, e);
      return new Answer.CannotTell();
    }
    if (read == null) {
      return new Answer.CannotTell();
    }
    reportedUnreadable.remove(key);
    final var models = List.copyOf(read);
    held.put(key, models);
    return new Answer.Known(models, true);

  }

  /**
   * Every id the module declares: the deployed ones and the ones nothing was
   * deployed under.
   */
  private Collection<String> declaredIdsOf(
      final String workflowModuleId) {

    final var ids = new TreeSet<String>();
    deployedProcesses
        .ofWorkflowModule(workflowModuleId)
        .forEach(deployed -> ids.add(deployed.bpmnProcessId()));
    ids.addAll(deployedProcesses.processesNobodyDeployedOf(workflowModuleId));
    return ids;

  }

  private void reportUnreadable(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String key,
      final RuntimeException e) {

    if (!reportedUnreadable.add(key)) {
      return;
    }
    log.warn(
        "Camunda8[{}]: the models the cluster holds for BPMN process '{}' of workflow module "
            + "'{}' could not be read - every check reading them stays silent until a read "
            + "succeeds",
        adapterId,
        bpmnProcessId,
        workflowModuleId,
        e);

  }

}
