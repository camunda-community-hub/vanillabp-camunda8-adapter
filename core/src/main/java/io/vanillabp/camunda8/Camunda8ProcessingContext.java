package io.vanillabp.camunda8;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.camunda.client.api.worker.JobWorker;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.vanillabp.camunda8.wiring.Camunda8Connectors;
import io.vanillabp.camunda8.wiring.Camunda8Listeners;
import io.vanillabp.camunda8.wiring.Camunda8MultiInstance;
import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import lombok.Getter;

/**
 * Adapter-specific processing context accumulated across all BPMN files of a workflow
 * module during the deployment pipeline
 * ({@code readBpmn} &rarr; {@code prepareBpmn} &rarr; {@code wireBpmn} &rarr;
 * {@code deployResources} &rarr; {@code startWorkflowProcessing}).
 * <p>
 * It collects the deployable BPMN resources (keyed by filename, so a file containing
 * several executable processes is deployed only once) together with the discovered
 * executable BPMN process IDs. {@code Camunda8DeploymentService#deployResources} sends all
 * collected resources of the module to Camunda 8 in a single deployment.
 * <p>
 * An extension of the deployment pipeline receives this context in its own
 * {@code wireBpmn}, and the two ids below are what tell it whose call it is looking at.
 * The pipeline runs once per configured Camunda 8 adapter, each run over that adapter's
 * own copy of the workflow module's files, so an extension which does not read
 * {@code getAdapterId()} cannot tell the runs apart - and the model it is handed carries
 * the identifiers of THAT adapter, which name-clash avoidance may have rewritten. Both ids
 * are stated here rather than left to be read back off those identifiers, which stops
 * answering as soon as two adapter ids avoid name clashes differently.
 */
public class Camunda8ProcessingContext {

  /**
   * The id of the Camunda 8 adapter this pipeline run belongs to - the id under
   * {@code vanillabp.adapters.<adapter-id>} the application configured, not the adapter
   * TYPE, of which several instances may be configured at once.
   */
  @Getter
  private final String adapterId;

  /**
   * The workflow module whose BPMN files this run deploys.
   */
  @Getter
  private final String workflowModuleId;

  /**
   * Deployable BPMN resources of the workflow module, keyed by filename. A
   * {@link LinkedHashMap} keeps the deployment order stable and deduplicates files that
   * contain multiple executable processes.
   */
  @Getter
  private final Map<String, BpmnModelInstance> resources = new LinkedHashMap<>();

  /**
   * The decision tables of the workflow module, keyed by filename - deployed with its
   * processes in the same command. Bytes rather than a model: nothing here has to
   * understand a decision, and the one thing which is rewritten, the decision id under
   * prefix scoping, was rewritten while the file was read.
   */
  @Getter
  private final Map<String, byte[]> decisions = new LinkedHashMap<>();

  /**
   * Remembers a decision table for deployment.
   *
   * @param filename The DMN file name - it keeps its extension, which is how the cluster
   *          tells a decision from a process
   * @param dmn The file
   */
  public void addDecision(
      final String filename,
      final byte[] dmn) {

    decisions.putIfAbsent(filename, dmn);

  }

  /**
   * The identifiers the models of this workflow module declare, as the APPLICATION knows
   * them: message names, signal names, BPMN error codes, escalation codes, job types and the
   * ids of the decisions the module brings.
   * <p>
   * Collected while a file is read, because that is the moment those names are still the
   * plain ones - the scoping rewrites them in the model respectively in the DMN file right
   * after. The core is handed them once the module deployed and answers whether two workflow
   * modules of this application end up under one of them.
   */
  @Getter
  private final Set<NameClashAvoidanceSupport.ModelIdentifier> identifiersTheModelsDeclare = new LinkedHashSet<>();

  /**
   * Remembers what one file of this workflow module declares.
   *
   * @param identifiers The plain identifiers read out of that file
   */
  public void recordIdentifiersAModelDeclares(
      final Collection<NameClashAvoidanceSupport.ModelIdentifier> identifiers) {

    identifiersTheModelsDeclare.addAll(identifiers);

  }

  /**
   * Remembers the decisions one DMN file of this workflow module declares. A decision id is
   * scoped by the workflow module alone, exactly like a message name, so it carries no BPMN
   * process: several processes of a module legitimately call the same decision.
   *
   * @param plainDecisionIds The decision ids as the application knows them
   */
  public void recordDecisionIds(
      final Collection<String> plainDecisionIds) {

    plainDecisionIds
        .forEach(
            decisionId -> identifiersTheModelsDeclare
                .add(
                    new NameClashAvoidanceSupport.ModelIdentifier(
                        NameClashAvoidanceSupport.ScopedIdentifierKind.DMN_DECISION_ID, decisionId, null)));

  }

  /**
   * The job-worker tasks of all executable processes of the module, collected
   * during wireBpmn - startWorkflowProcessing opens one worker per distinct task
   * definition.
   */
  @Getter
  private final List<Camunda8TaskWiring.Camunda8TaskToWire> tasksToWire = new LinkedList<>();

  /**
   * What the core's wiring validation was handed for each process of the module, by PLAIN
   * BPMN process id: the tasks, the user tasks and the modelled listeners. Kept for the
   * questions which can be asked only once every process of the module is wired, because
   * their answer depends on the processes calling this one.
   */
  @Getter
  private final Map<String, List<BpmnTaskSpec>> taskSpecsByProcess = new LinkedHashMap<>();

  /**
   * Remembers what the core's wiring validation was handed for one process.
   *
   * @param plainBpmnProcessId The BPMN process id as the application knows it
   * @param specs The task specs of that process
   */
  public void recordTaskSpecs(
      final String plainBpmnProcessId,
      final List<BpmnTaskSpec> specs) {

    taskSpecsByProcess.put(plainBpmnProcessId, List.copyOf(specs));

  }

  /**
   * The Camunda-managed user tasks collected during {@code wireBpmn} -
   * one listener-job worker is opened per distinct listener job type.
   */
  @Getter
  private final List<Camunda8TaskWiring.Camunda8UserTaskToWire> userTasksToWire = new LinkedList<>();

  /**
   * The start events the cluster fires on its own, collected while wiring
   * and served by one worker each once workflow processing starts.
   */
  @Getter
  private final List<Camunda8TaskWiring.Camunda8BpmsInitiatedStartToWire> bpmsInitiatedStartsToWire = new LinkedList<>();

  /**
   * The BPMN processes whose end has to be reported to the application,
   * as (scoped process id) - one worker each once workflow processing starts.
   */
  @Getter
  private final List<String> workflowEndedProcessesToWire = new LinkedList<>();

  /**
   * The BPMN processes of this module whose cancelation would be reported on a newer
   * release line, as PLAIN process ids - what the boot says out loud so the gap is read at
   * startup instead of being found in production. Empty on the line which has the
   * construct.
   */
  @Getter
  private final List<String> processesWithoutACancelationReport = new LinkedList<>();

  /**
   * The workers opened by startWorkflowProcessing, closed by
   * stopWorkflowProcessing (reverse order).
   */
  @Getter
  private final List<JobWorker> openWorkers = new LinkedList<>();

  /**
   * The elements of this module which a runtime other than this application serves,
   * collected while the BPMN files are prepared - the list the startup report names one by
   * one, see {@link Camunda8Connectors}.
   */
  @Getter
  private final List<Camunda8Connectors.ElementServedByAnotherRuntime> elementsServedByAnotherRuntime = new LinkedList<>();

  /**
   * Per BPMN process of this module which allows connectors, the property key which said
   * so. Kept per process because the key resolves per workflow as well as per module, and
   * the report has to name the line a reader can find in their own configuration.
   */
  @Getter
  private final Map<String, String> connectorsAllowedBy = new LinkedHashMap<>();

  /**
   * Remembers that connectors are allowed for one BPMN process of this module.
   *
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param propertyKey The key which decided it, or <code>null</code>
   */
  public void recordConnectorsAllowed(
      final String bpmnProcessId,
      final String propertyKey) {

    connectorsAllowedBy.put(bpmnProcessId, propertyKey);

  }

  /**
   * Remembers one element this module leaves to the runtime which owns it.
   *
   * @param element The element
   */
  public void recordElementServedByAnotherRuntime(
      final Camunda8Connectors.ElementServedByAnotherRuntime element) {

    elementsServedByAnotherRuntime.add(element);

  }

  /**
   * Per BPMN process of this module, the elements which name the process they call or the
   * decision they evaluate by a FEEL expression. Under {@code use-prefix} such an expression
   * reaches the cluster with the prefix written inside it, so the cluster holds more text than
   * the developer typed, which is what the deployment says where the cluster refuses a
   * deployment.
   */
  @Getter
  private final Map<String, List<String>> elementsNamingTheirTargetByExpression = new LinkedHashMap<>();

  /**
   * Remembers the elements of one BPMN process whose called process or decision is named by
   * an expression. A process without such an element is not recorded at all.
   *
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param elementIds The call activities respectively business rule tasks
   */
  public void recordElementsNamingTheirTargetByExpression(
      final String bpmnProcessId,
      final List<String> elementIds) {

    if (elementIds.isEmpty()) {
      return;
    }
    elementsNamingTheirTargetByExpression.put(bpmnProcessId, elementIds);

  }

  /**
   * The listeners of this module's models which somebody modelled and this application
   * serves, collected while the BPMN files are prepared - the list the startup report names
   * one by one, see {@link Camunda8Listeners}.
   */
  @Getter
  private final List<Camunda8Listeners.ModelledListener> modelledListeners = new LinkedList<>();

  /**
   * Per BPMN process of this module which allows listeners, the property key which said so.
   * Kept per process for the reason {@link #connectorsAllowedBy} is.
   */
  @Getter
  private final Map<String, String> listenersAllowedBy = new LinkedHashMap<>();

  /**
   * Remembers that the listeners somebody modelled are served for one BPMN process of this
   * module.
   *
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param propertyKey The key which decided it, or <code>null</code>
   */
  public void recordListenersAllowed(
      final String bpmnProcessId,
      final String propertyKey) {

    listenersAllowedBy.put(bpmnProcessId, propertyKey);

  }

  /**
   * Remembers one listener of this module which an application method serves.
   *
   * @param listener The listener
   */
  public void recordModelledListener(
      final Camunda8Listeners.ModelledListener listener) {

    modelledListeners.add(listener);

  }

  /**
   * Per PLAIN BPMN process id of this module, the elements carrying an
   * <code>updating</code> task listener which no method of this application serves.
   * <p>
   * Such an element is never probed. The empty update which asks whether a user task is
   * still open fires that listener, a worker this application does not run does not answer
   * it, and the task then stands in <code>UPDATING</code> for fifteen seconds while assign
   * and complete are refused. The check says "cannot say" for those tasks instead, see
   * decision 38 in the repository's DECISIONS.md.
   * <p>
   * Two ways lead here, because a listener no method serves ends the boot of a claimed process
   * everywhere else: a process no <code>&#64;WorkflowService</code> class claims, and an element
   * carrying a <code>zeebe:modelerTemplate</code>, which says that another runtime answers the
   * job.
   */
  @Getter
  private final Map<String, Set<String>> elementsWithAnUpdatingListenerNobodyServes = new LinkedHashMap<>();

  /**
   * Remembers one element whose <code>updating</code> task listener nothing here serves.
   *
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param elementId The BPMN element carrying the listener
   */
  public void recordUpdatingListenerNobodyServes(
      final String bpmnProcessId,
      final String elementId) {

    elementsWithAnUpdatingListenerNobodyServes
        .computeIfAbsent(bpmnProcessId, process -> new LinkedHashSet<>())
        .add(elementId);

  }

  /**
   * Which multi-instance elements enclose an element of this adapter's models, collected
   * while the models are wired.
   * <p>
   * Camunda 8 tells a job its own element id and nothing about the iteration it runs in, so
   * the chain of enclosing multi-instance elements is model knowledge which has to be read
   * while the model is deployed. The adapter reads it once, for every model of every adapter
   * id, and an extension which wants to name the iteration a task belongs to asks the same
   * registry rather than reading the models a second time. It is the registry of the ADAPTER
   * this run belongs to, so it answers for the identifiers of this run's models.
   * <p>
   * What it holds grows with the pipeline: an element wired after the question was asked is
   * not in it yet. Ask it while serving a job, not while wiring.
   */
  @Getter
  private final Camunda8MultiInstance.Registry multiInstanceRegistry;

  /**
   * Opens the context of one pipeline run, which the deployment service then fills while it
   * walks the BPMN files of the workflow module.
   *
   * @param adapterId The adapter id this pipeline run belongs to
   * @param workflowModuleId The workflow module whose files this run deploys
   * @param multiInstanceRegistry The multi-instance chains of that adapter
   */
  public Camunda8ProcessingContext(
      final String adapterId,
      final String workflowModuleId,
      final Camunda8MultiInstance.Registry multiInstanceRegistry) {

    this.adapterId = adapterId;
    this.workflowModuleId = workflowModuleId;
    this.multiInstanceRegistry = multiInstanceRegistry;

  }

  /**
   * The PLAIN BPMN process ids of the module's executable processes, collected in
   * {@code prepareBpmn} - the input of the collision check (two processes must not
   * end up under the same prefixed identifier, see decision 2 in the repository's
   * DECISIONS.md).
   */
  @Getter
  private final List<String> deployedProcessIds = new LinkedList<>();

  /**
   * Records an executable BPMN process of this workflow module.
   *
   * @param bpmnProcessId The plain BPMN process ID
   */
  public void recordDeployedProcess(
      final String bpmnProcessId) {

    if ((bpmnProcessId != null) && !deployedProcessIds.contains(bpmnProcessId)) {
      deployedProcessIds.add(bpmnProcessId);
    }

  }

  /**
   * Adds a deployable BPMN resource. Idempotent per filename: multiple executable
   * processes of the same file register the same model only once.
   *
   * @param filename The BPMN filename (used as the deployment resource name)
   * @param model The parsed BPMN model
   */
  public void addResource(
      final String filename,
      final BpmnModelInstance model) {

    resources.putIfAbsent(filename, model);

  }

  /**
   * Whether this run collected no BPMN resource at all.
   * <p>
   * A workflow module without an executable process is no mistake, so the deployment service
   * asks this and skips the deployment instead of sending an empty one.
   *
   * @return Whether there is nothing to deploy
   */
  public boolean isEmpty() {

    return resources.isEmpty();

  }

}
