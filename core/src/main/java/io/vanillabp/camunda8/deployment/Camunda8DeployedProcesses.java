package io.vanillabp.camunda8.deployment;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;

/**
 * What THIS application version deployed to Camunda 8, per adapter id.
 * {@link Camunda8DeploymentService#deployResources} fills it at every boot.
 * <p>
 * <b>Why the adapter keeps this:</b> Camunda 8 is remote. Its process definitions and
 * their XML can only be read through the query API, which is eventually consistent.
 * The deployment pipeline of VanillaBP reads the BPMN of every workflow module at EVERY
 * boot anyway. So the adapter serves the definitions and BPMN XML of the viewer API from
 * these freshly read models. There is no round trip to the cluster and no lag.
 * <p>
 * <b>The boundary:</b> only definitions deployed by the RUNNING application version
 * are held here. A workflow may still run on a definition which a PREVIOUS application
 * version deployed, like a long-running workflow which outlived a redeployment. It is
 * served from the cluster instead ({@code ProcessDefinitionGetXmlRequest}). This is one
 * of the reasons why the adapter needs a cluster it can search - see decision 20 in the
 * repository's DECISIONS.md.
 * <p>
 * Why the probes compare against what THIS adapter id deployed rather than trusting a cluster key
 * is decision 3 in the repository's DECISIONS.md.
 */
public class Camunda8DeployedProcesses {

  /**
   * Opens an empty record. The client factory of an adapter id owns one, and the deployment
   * of every workflow module fills it at each boot.
   */
  public Camunda8DeployedProcesses() {
  }

  /**
   * A process deployed by this application version.
   *
   * @param workflowModuleId The workflow module the process belongs to
   * @param bpmnProcessId The BPMN process id
   * @param processDefinitionKey The Camunda 8 process definition key (the
   *        adapter-native definition id)
   * @param version The version assigned by the cluster
   * @param model The BPMN model AS DEPLOYED, with the changes of the VanillaBP
   *        wiring. This is what the cluster runs
   */
  public record DeployedProcess(
                                String workflowModuleId,
                                String bpmnProcessId,
                                String processDefinitionKey,
                                int version,
                                BpmnModelInstance model) {
  }

  /**
   * By process definition key. {@code getBpmnXml} looks a process up here.
   */
  private final Map<String, DeployedProcess> byDefinitionKey = new ConcurrentHashMap<>();

  /**
   * By {@code <workflow module>|<bpmn process id>}. This answers "which version
   * would run next".
   */
  private final Map<String, DeployedProcess> byProcess = new ConcurrentHashMap<>();

  /**
   * The PLAIN BPMN process ids which a workflow module DECLARES, while this
   * application version deployed nothing under them. An example is the old id of a
   * renamed process, whose models live only in the cluster. They are recorded when
   * the module starts processing. Only then is it settled which ids were declared
   * but not deployed.
   */
  private final Map<String, Set<String>> declaredWithoutDeployment = new ConcurrentHashMap<>();

  /**
   * Notes a process the deployment just sent to the cluster, under both keys the viewer reads
   * it back by.
   *
   * @param deployedProcess What the cluster answered the deployment with
   */
  public void record(
      final DeployedProcess deployedProcess) {

    byDefinitionKey.put(deployedProcess.processDefinitionKey(), deployedProcess);
    byProcess.put(
        key(deployedProcess.workflowModuleId(), deployedProcess.bpmnProcessId()),
        deployedProcess);

  }

  /**
   * Every process of a workflow module which THIS application version deployed. The
   * message-name check of {@code correlateMessage} reads these models. For a workflow
   * module whose processes a previous application version deployed, the collection is
   * empty, and the check then stays silent.
   *
   * @param workflowModuleId The workflow module
   * @return The processes deployed by this application version
   */
  public Collection<DeployedProcess> ofWorkflowModule(
      final String workflowModuleId) {

    return byProcess
        .values()
        .stream()
        .filter(deployed -> deployed
            .workflowModuleId()
            .equals(workflowModuleId))
        .toList();

  }

  /**
   * Records a BPMN process id the workflow module declares without this application
   * version deploying anything under it.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id nothing was deployed under
   */
  public void recordDeclaredWithoutDeployment(
      final String workflowModuleId,
      final String bpmnProcessId) {

    declaredWithoutDeployment
        .computeIfAbsent(workflowModuleId, module -> ConcurrentHashMap.newKeySet())
        .add(bpmnProcessId);

  }

  /**
   * Whether the workflow module declares at least one BPMN process id nothing was
   * deployed under. The message check of {@code correlateMessage} reads this as "the
   * declared names are unknown, not absent". The models which could hold the answer
   * live only in the cluster.
   *
   * @param workflowModuleId The workflow module
   * @return Whether such an id exists
   */
  public boolean declaresProcessesNobodyDeployed(
      final String workflowModuleId) {

    return !processesNobodyDeployedOf(workflowModuleId).isEmpty();

  }

  /**
   * The PLAIN BPMN process ids the workflow module declares without this application
   * version deploying anything under them.
   *
   * @param workflowModuleId The workflow module
   * @return The ids, empty where every declared id was deployed
   */
  public Collection<String> processesNobodyDeployedOf(
      final String workflowModuleId) {

    return Set.copyOf(declaredWithoutDeployment.getOrDefault(workflowModuleId, Set.of()));

  }

  /**
   * The model behind one definition key, which is how the viewer serves BPMN XML without asking
   * the cluster.
   *
   * @param processDefinitionKey The Camunda 8 process definition key
   * @return The deployed process or <code>null</code> if not deployed by this
   *         application version
   */
  public DeployedProcess byDefinitionKey(
      final String processDefinitionKey) {

    return byDefinitionKey.get(processDefinitionKey);

  }

  /**
   * Which version of a BPMN process the running application version deployed, which is the
   * version a workflow started now would run on.
   *
   * @param workflowModuleId The workflow module id
   * @param bpmnProcessId The BPMN process id
   * @return The version deployed by this application version or <code>null</code>
   */
  public DeployedProcess deployedVersionOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return byProcess.get(key(workflowModuleId, bpmnProcessId));

  }

  /**
   * The BPMN processes whose model carries the element id this adapter reserved for the
   * probe which asks the engine whether it holds an instance. Empty in every installation
   * which did not happen to pick that id.
   */
  private final Set<String> carryingTheReservedProbeElement = ConcurrentHashMap.newKeySet();

  /**
   * Records that a model carries the element id which the probe of
   * {@code Camunda8ProcessService#awarenessOfWorkflow} reserved for itself.
   * <p>
   * The probe asks the engine by sending a modification. It names an element no model
   * has, so the cluster refuses it, and the refusal IS the answer. If the id matched an
   * element of the model by accident, that element would be ACTIVATED instead. That
   * changes a running workflow, and nobody asked for it. So the models are searched for
   * the id while they are deployed, and a process which carries it gets no probe.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id
   */
  public void recordTheReservedProbeElement(
      final String workflowModuleId,
      final String bpmnProcessId) {

    carryingTheReservedProbeElement.add(key(workflowModuleId, bpmnProcessId));

  }

  /**
   * Whether the model of that process carries the reserved element id, which is what
   * keeps the probe away from it.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id
   * @return Whether the probe must not be sent for a workflow of that process
   */
  public boolean carriesTheReservedProbeElement(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return carryingTheReservedProbeElement.contains(key(workflowModuleId, bpmnProcessId));

  }

  private static String key(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return workflowModuleId
        + "|"
        + bpmnProcessId;

  }

}
