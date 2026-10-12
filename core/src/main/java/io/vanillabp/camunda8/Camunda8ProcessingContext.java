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
 * The processing context of this adapter. It fills up over all BPMN files of a workflow
 * module while the deployment pipeline runs
 * ({@code readBpmn} &rarr; {@code prepareBpmn} &rarr; {@code wireBpmn} &rarr;
 * {@code deployResources} &rarr; {@code startWorkflowProcessing}).
 * <p>
 * It collects the deployable BPMN resources together with the executable BPMN process IDs
 * found in them. The resources are keyed by filename, so a file with several executable
 * processes is deployed only once. {@code Camunda8DeploymentService#deployResources} sends
 * all collected resources of the module to Camunda 8 in a single deployment.
 * <p>
 * An extension of the deployment pipeline receives this context in its own
 * {@code wireBpmn}. The two ids below tell it whose call it is looking at. The pipeline
 * runs once per configured Camunda 8 adapter. Each run works on its own copy of the files
 * of the workflow module. So an extension which does not read
 * {@code getAdapterId()} cannot tell the runs apart. And the model it gets carries the
 * identifiers of THAT adapter, which name-clash avoidance may have rewritten. Both ids are
 * stated here. Reading them back from those identifiers stops working as soon as two
 * adapter ids avoid name clashes in different ways.
 */
public class Camunda8ProcessingContext {

  /**
   * The id of the Camunda 8 adapter this pipeline run belongs to. It is the id under
   * {@code vanillabp.adapters.<adapter-id>} which the application configured. It is not the
   * adapter TYPE, of which several instances may be configured at once.
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
   * {@link LinkedHashMap} keeps the deployment order stable. It also holds a file with
   * several executable processes only once.
   */
  @Getter
  private final Map<String, BpmnModelInstance> resources = new LinkedHashMap<>();

  /**
   * The decision tables of the workflow module, keyed by filename. They are deployed with
   * the processes of the module in the same command. They are bytes and not a model,
   * because nothing here has to understand a decision. Only the decision id is rewritten
   * under prefix scoping, and that already happened while the file was read.
   */
  @Getter
  private final Map<String, byte[]> decisions = new LinkedHashMap<>();

  /**
   * Remembers a decision table for deployment.
   *
   * @param filename The DMN file name. It keeps its extension, because by it the cluster
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
   * They are collected while a file is read. At that moment the names are still the plain
   * ones. Right after, the scoping rewrites them in the model or in the DMN file. Once the
   * module is deployed, the core gets them. It answers whether two workflow modules of this
   * application end up under one of them.
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
   * scoped by the workflow module alone, exactly like a message name. So it carries no BPMN
   * process. Several processes of a module may well call the same decision.
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
   * during wireBpmn. startWorkflowProcessing opens one worker per distinct task
   * definition.
   */
  @Getter
  private final List<Camunda8TaskWiring.Camunda8TaskToWire> tasksToWire = new LinkedList<>();

  /**
   * What the wiring validation of the core got for each process of the module, by PLAIN
   * BPMN process id: the tasks, the user tasks and the modelled listeners. Some questions
   * can only be asked once every process of the module is wired, because the answer depends
   * on the processes which call this one. These specs are kept for them.
   */
  @Getter
  private final Map<String, List<BpmnTaskSpec>> taskSpecsByProcess = new LinkedHashMap<>();

  /**
   * Remembers what the wiring validation of the core got for one process.
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
   * The Camunda-managed user tasks collected during {@code wireBpmn}.
   * One listener-job worker is opened per distinct listener job type.
   */
  @Getter
  private final List<Camunda8TaskWiring.Camunda8UserTaskToWire> userTasksToWire = new LinkedList<>();

  /**
   * The start events the cluster fires on its own, collected while wiring.
   * Once workflow processing starts, one worker each serves them.
   */
  @Getter
  private final List<Camunda8TaskWiring.Camunda8BpmsInitiatedStartToWire> bpmsInitiatedStartsToWire = new LinkedList<>();

  /**
   * The BPMN processes whose end has to be reported to the application,
   * as scoped process ids. Once workflow processing starts, each gets one worker.
   */
  @Getter
  private final List<String> workflowEndedProcessesToWire = new LinkedList<>();

  /**
   * The BPMN processes of this module whose cancelation would be reported on a newer
   * release line, as PLAIN process ids. The boot names them out loud. So the gap is read at
   * startup instead of being found in production. The list is empty on the line which has
   * the construct.
   */
  @Getter
  private final List<String> processesWithoutACancelationReport = new LinkedList<>();

  /**
   * The workers opened by startWorkflowProcessing. stopWorkflowProcessing closes them in
   * reverse order.
   */
  @Getter
  private final List<JobWorker> openWorkers = new LinkedList<>();

  /**
   * The elements of this module which a runtime other than this application serves,
   * collected while the BPMN files are prepared. The startup report names them one by one,
   * see {@link Camunda8Connectors}.
   */
  @Getter
  private final List<Camunda8Connectors.ElementServedByAnotherRuntime> elementsServedByAnotherRuntime = new LinkedList<>();

  /**
   * Per BPMN process of this module which allows connectors, the property key which said
   * so. It is kept per process, because the key resolves per workflow as well as per module.
   * And the report has to name the line a reader can find in their own configuration.
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
   * reaches the cluster with the prefix written inside it. So the cluster holds more text than
   * the developer typed. Where the cluster refuses a deployment, the message says exactly that.
   */
  @Getter
  private final Map<String, List<String>> elementsNamingTheirTargetByExpression = new LinkedHashMap<>();

  /**
   * Remembers the elements of one BPMN process whose called process or decision is named by
   * an expression. A process without such an element is not recorded at all.
   *
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param elementIds The call activities or business rule tasks
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
   * The listeners in the models of this module which somebody modelled and this application
   * serves, collected while the BPMN files are prepared. The startup report names them one
   * by one, see {@link Camunda8Listeners}.
   */
  @Getter
  private final List<Camunda8Listeners.ModelledListener> modelledListeners = new LinkedList<>();

  /**
   * Per BPMN process of this module which allows listeners, the property key which said so.
   * It is kept per process for the same reason as {@link #connectorsAllowedBy}.
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
   * The listeners of a CLAIMED process whose job type no method of this application names,
   * and which no element template marks as somebody else's. They go to the wiring validation
   * of the core as tasks. So the same rule applies to them as to every task: a method, or the
   * property <code>implemented-externally=true</code>, or the boot ends.
   */
  private final List<Camunda8Listeners.ModelledListener> listenersNothingHereServes = new LinkedList<>();

  /**
   * Remembers a listener of a claimed process which no method of this application names.
   *
   * @param listener The listener
   */
  public void recordListenerNothingHereServes(
      final Camunda8Listeners.ModelledListener listener) {

    listenersNothingHereServes.add(listener);

  }

  /**
   * The listeners of claimed processes which no method of this application names, in the
   * order they were read.
   *
   * @return The listeners
   */
  public List<Camunda8Listeners.ModelledListener> getListenersNothingHereServes() {

    return listenersNothingHereServes;

  }


  /**
   * Per PLAIN BPMN process id of this module, the elements carrying an
   * <code>updating</code> task listener which no method of this application serves.
   * <p>
   * Such an element is never probed. The empty update which asks whether a user task is
   * still open fires that listener. A worker which this application does not run does not
   * answer it. The task then stands in <code>UPDATING</code> for fifteen seconds, and assign
   * and complete are refused. So the check says "cannot say" for those tasks instead, see
   * decision 38 in the repository's DECISIONS.md.
   * <p>
   * Only two ways lead here. In every other case a listener no method serves ends the boot of
   * a claimed process. The two ways are a process no <code>&#64;WorkflowService</code> class
   * claims, and an element carrying a <code>zeebe:modelerTemplate</code>. The template says
   * that another runtime answers the job.
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
   * Which multi-instance elements enclose an element of the models of this adapter,
   * collected while the models are wired.
   * <p>
   * Camunda 8 tells a job its own element id and nothing about the iteration it runs in. So
   * the chain of enclosing multi-instance elements is model knowledge. It has to be read
   * while the model is deployed. The adapter reads it once, for every model of every adapter
   * id. An extension which wants to name the iteration of a task asks the same registry. It
   * does not read the models a second time. It is the registry of the ADAPTER this run
   * belongs to. So it answers for the identifiers of the models of this run.
   * <p>
   * What it holds grows with the pipeline. An element wired after the question was asked is
   * not in it yet. Ask it while serving a job, not while wiring.
   */
  @Getter
  private final Camunda8MultiInstance.Registry multiInstanceRegistry;

  /**
   * Opens the context of one pipeline run. The deployment service then fills it while it
   * walks through the BPMN files of the workflow module.
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
   * The PLAIN BPMN process ids of the executable processes of the module, collected in
   * {@code prepareBpmn}. They are the input of the collision check. Two processes must not
   * end up under the same prefixed identifier, see decision 2 in the repository's
   * DECISIONS.md.
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
   * Adds a deployable BPMN resource. It is idempotent per filename. Several executable
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
   * A workflow module without an executable process is no mistake. So the deployment service
   * asks this and skips the deployment. It does not send an empty one.
   *
   * @return Whether there is nothing to deploy
   */
  public boolean isEmpty() {

    return resources.isEmpty();

  }

}
