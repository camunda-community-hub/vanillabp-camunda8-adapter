package io.vanillabp.camunda8.wiring;

import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.Activity;
import io.camunda.zeebe.model.bpmn.instance.AdHocSubProcess;
import io.camunda.zeebe.model.bpmn.instance.Association;
import io.camunda.zeebe.model.bpmn.instance.BoundaryEvent;
import io.camunda.zeebe.model.bpmn.instance.BusinessRuleTask;
import io.camunda.zeebe.model.bpmn.instance.CatchEvent;
import io.camunda.zeebe.model.bpmn.instance.CompensateEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.ConditionalEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.EndEvent;
import io.camunda.zeebe.model.bpmn.instance.ExtensionElements;
import io.camunda.zeebe.model.bpmn.instance.FlowElement;
import io.camunda.zeebe.model.bpmn.instance.InclusiveGateway;
import io.camunda.zeebe.model.bpmn.instance.IntermediateThrowEvent;
import io.camunda.zeebe.model.bpmn.instance.Message;
import io.camunda.zeebe.model.bpmn.instance.MessageEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import io.camunda.zeebe.model.bpmn.instance.ParallelGateway;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.ReceiveTask;
import io.camunda.zeebe.model.bpmn.instance.ScriptTask;
import io.camunda.zeebe.model.bpmn.instance.SendTask;
import io.camunda.zeebe.model.bpmn.instance.ServiceTask;
import io.camunda.zeebe.model.bpmn.instance.SignalEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.StartEvent;
import io.camunda.zeebe.model.bpmn.instance.SubProcess;
import io.camunda.zeebe.model.bpmn.instance.Task;
import io.camunda.zeebe.model.bpmn.instance.TimerEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.UserTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListenerEventType;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeFormDefinition;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeSubscription;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskDefinition;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListenerEventType;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListeners;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeUserTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeVersionTag;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.CompensationSpec;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * Extracts the job-worker tasks of an executable BPMN process from the Camunda 8
 * model. These are service-like tasks carrying a <code>zeebe:taskDefinition</code>. Its
 * <code>type</code> IS the VanillaBP task definition (job type). It is used during
 * <code>wireBpmn</code> for the wiring validation, and to know which job workers to
 * open per workflow module.
 * <p>
 * Why user-task listeners and message correlation keys are injected into the model rather than
 * expected from the application is decision 5 in the repository's DECISIONS.md.
 */
public final class Camunda8TaskWiring {

  private Camunda8TaskWiring() {
  }

  /**
   * One task to be served by a job worker.
   *
   * @param bpmnProcessId The BPMN process ID
   * @param activityId The BPMN activity ID
   * @param taskDefinition The <code>zeebe:taskDefinition</code> type (= job type)
   */
  public record Camunda8TaskToWire(
                                   String bpmnProcessId,
                                   String activityId,
                                   String taskDefinition) {

    /**
     * The task in the shape in which the checks of the platform read it.
     *
     * @return The element id and the task definition of this task
     */
    public BpmnTaskSpec toSpec() {

      return new BpmnTaskSpec(activityId, taskDefinition);

    }

  }

  /**
   * The job-worker tasks of the given executable process, including tasks inside
   * embedded subprocesses. Tasks without a <code>zeebe:taskDefinition</code> get a
   * <code>null</code> task definition. The wiring validation reports them with a guiding
   * message. A business rule task calling a decision (<code>zeebe:calledDecision</code>)
   * is none of them, because the cluster evaluates it.
   * <p>
   * The task definition is handed over as the model says it, expression or not. A job type
   * written as an expression never reaches this method. The deployment refuses or reports
   * such a model while the file is prepared, see {@link #jobTypesWrittenAsAnExpressionOf}.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The process id as it stands in the model
   * @param connectorsAreAllowed Whether an element built from an element template is left
   *          to the runtime which owns it, see {@link Camunda8Connectors}. Such an element
   *          produces no task spec, so the wiring validation never asks for a
   *          {@code @WorkflowTask} method and no worker is opened for its job type
   * @return The tasks to be served by job workers of this application
   */
  public static List<Camunda8TaskToWire> tasksOf(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final boolean connectorsAreAllowed) {

    final var tasks = new LinkedList<Camunda8TaskToWire>();
    Stream
        .of(ServiceTask.class, SendTask.class, BusinessRuleTask.class, ScriptTask.class)
        .flatMap(type -> model.getModelElementsByType(type).stream())
        .map(Task.class::cast)
        .filter(task -> bpmnProcessId.equals(owningProcessId(task)))
        .forEach(task -> {
          // the cluster serves a business rule task calling a DECISION, not a job worker
          // of the application. The decision was deployed with this process. Asking for a
          // @WorkflowTask method would make DMN unusable here. A business rule task
          // carrying a task definition is an ordinary VanillaBP task.
          if (task.getSingleExtensionElement(
              io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledDecision.class) != null) {
            return;
          }
          // an element built from an element template names a job type which the runtime
          // of somebody else subscribes to. So this application neither validates nor
          // serves it.
          if (connectorsAreAllowed && Camunda8Connectors.isServedByAnotherRuntime(task)) {
            return;
          }
          final var taskDefinition = task.getSingleExtensionElement(ZeebeTaskDefinition.class);
          tasks.add(new Camunda8TaskToWire(
              bpmnProcessId, task.getId(), taskDefinition != null
                  ? taskDefinition.getType()
                  : null));
        });
    return tasks;

  }

  /**
   * One job type of a model which is written as a FEEL expression instead of a name.
   *
   * @param attribute The attribute holding it, as the modeler names it
   * @param elementId The BPMN element it belongs to
   * @param jobType What the modeller wrote, <code>=</code> included
   */
  public record JobTypeWrittenAsAnExpression(
                                             String attribute,
                                             String elementId,
                                             String jobType) {

    /**
     * The entry in one line. This is how the deployment names it.
     *
     * @return Where the expression stands and what it says
     */
    public String describe() {

      return "%s of '%s' (%s)".formatted(attribute, elementId, jobType);

    }

  }

  /**
   * The <code>zeebe:taskDefinition</code> types of the given executable process which are
   * written as a FEEL expression.
   * <p>
   * A job type is the NAME a worker subscribes to. This adapter opens one worker per job type
   * it reads out of the model. It subscribes exactly the string the model says. So an
   * expression leaves the element unserved, whatever the cluster makes of it. Where the
   * cluster evaluates the expression, the job carries the result, while the worker waits for
   * the expression. Where it does not, the job carries the expression, and no
   * <code>&#64;WorkflowTask</code> method can be named after it. So the deployment refuses or
   * reports such a model. It does not let {@link #tasksOf} hand the expression to the core,
   * which would ask for a method nobody can write.
   * <p>
   * It reads the same elements as {@link #tasksOf}, with the same two exceptions. A business
   * rule task calling a decision carries no job type at all. And an element built from an
   * element template names a job type which the runtime of somebody else subscribes to.
   * <p>
   * This is read while the model is still as the modeller wrote it. Under
   * <code>use-prefix</code>, every expression carries the frame of {@link Camunda8Scoping}
   * afterwards. A message quoting that would quote something nobody typed.
   *
   * @param model The BPMN model, as it was read
   * @param bpmnProcessId The process id as it stands in the model
   * @param connectorsAreAllowed Whether an element built from an element template is left to
   *          the runtime which owns it, see {@link Camunda8Connectors}
   * @return One entry per such job type, empty for every other model
   */
  public static List<JobTypeWrittenAsAnExpression> jobTypesWrittenAsAnExpressionOf(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final boolean connectorsAreAllowed) {

    final var found = new LinkedList<JobTypeWrittenAsAnExpression>();
    Stream
        .of(ServiceTask.class, SendTask.class, BusinessRuleTask.class, ScriptTask.class)
        .flatMap(type -> model.getModelElementsByType(type).stream())
        .map(Task.class::cast)
        .filter(task -> bpmnProcessId.equals(owningProcessId(task)))
        .filter(task -> !connectorsAreAllowed || !Camunda8Connectors.isServedByAnotherRuntime(task))
        .filter(task -> task.getSingleExtensionElement(
            io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledDecision.class) == null)
        .forEach(task -> {
          final var taskDefinition = task.getSingleExtensionElement(ZeebeTaskDefinition.class);
          if ((taskDefinition == null) || !Camunda8Scoping.isWrittenAsFeel(taskDefinition.getType())) {
            return;
          }
          found
              .add(new JobTypeWrittenAsAnExpression(
                  "zeebe:taskDefinition type", task.getId(), taskDefinition.getType()));
        });
    return List.copyOf(found);

  }

  /**
   * One Camunda-managed user task of a model whose external form reference is written as a
   * FEEL expression instead of a name.
   *
   * @param elementId The user task
   * @param formReference What the modeller wrote, <code>=</code> included
   */
  public record FormReferenceWrittenAsAnExpression(
                                                   String elementId,
                                                   String formReference) {

    /**
     * The entry in one line. This is how the deployment names it.
     *
     * @return Where the expression stands and what it says
     */
    public String describe() {

      return "zeebe:formDefinition externalReference of '%s' (%s)".formatted(elementId, formReference);

    }

  }

  /**
   * The Camunda-managed user tasks of the given executable process whose external form
   * reference is written as a FEEL expression.
   * <p>
   * Under the Camunda 8 convention of VanillaBP, the external form reference IS the task
   * definition of a user task. The core finds the <code>&#64;WorkflowTask</code> method by it.
   * The job type of the lifecycle listeners is {@link #TASKDEFINITION_USERTASK_ZEEBE} plus the
   * reference. That job type does not start with <code>=</code>. So the cluster takes it as
   * written, while it evaluates the reference itself. So a method matches only where its task
   * definition repeats the expression. Under <code>use-prefix</code> not even then, because
   * the frame of {@link Camunda8Scoping} is written into the expression. So the deployment
   * refuses or reports such a model, see decision 69 in the repository's DECISIONS.md.
   * <p>
   * A user task served by a job worker is not read here. It has a job type instead, and the
   * deployment already refuses or reports it for that reason. An element template is no way
   * out either. A Camunda-managed user task has no job type another runtime could subscribe
   * to.
   * <p>
   * This is read while the model is still as the modeller wrote it. So a message quotes what
   * was typed, not the frame.
   *
   * @param model The BPMN model, as it was read
   * @param bpmnProcessId The process id as it stands in the model
   * @return One entry per such user task, empty for every other model
   */
  public static List<FormReferenceWrittenAsAnExpression> formReferencesWrittenAsAnExpressionOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    return model
        .getModelElementsByType(UserTask.class)
        .stream()
        .filter(task -> bpmnProcessId.equals(owningProcessId(task)))
        .filter(task -> task.getSingleExtensionElement(ZeebeUserTask.class) != null)
        .map(task -> {
          final var formDefinition = task.getSingleExtensionElement(ZeebeFormDefinition.class);
          final var reference = formDefinition != null
              ? formDefinition.getExternalReference()
              : null;
          return Camunda8Scoping.isWrittenAsFeel(reference)
              ? new FormReferenceWrittenAsAnExpression(task.getId(), reference)
              : null;
        })
        .filter(Objects::nonNull)
        .toList();

  }

  /**
   * The V1-compatible job-type prefix of user-task listeners. The listener type is this
   * prefix plus the external form reference of the user task. It MUST NOT change. It is the
   * name a worker subscribes to. A version-1 application brings workflows whose user tasks
   * carry listener jobs of the old name. With a prefix of our own, nobody would serve those
   * jobs.
   */
  public static final String TASKDEFINITION_USERTASK_ZEEBE = "io.vanillabp.userTask:";

  /**
   * The job-type prefix of the start execution listeners VanillaBP attaches to start
   * events the cluster fires on its own. The job type is this prefix plus the scoped BPMN
   * process id plus the id of the start event. The job type has to be unique across the
   * tenant, so it contains the process id. One worker serves exactly one start event.
   */
  public static final String TASKDEFINITION_BPMS_INITIATED_START = "io.vanillabp.bpmsStart:";

  /**
   * The job-type prefix of the end execution listener VanillaBP attaches to a process whose
   * application wants to be told that a workflow ended. The job type is this prefix plus the
   * scoped BPMN process id.
   */
  public static final String TASKDEFINITION_WORKFLOW_ENDED = "io.vanillabp.workflowEnd:";

  /**
   * The job type of the end execution listener of a process. It is built from the prefix
   * above, so the worker and the model agree on one string.
   *
   * @param scopedBpmnProcessId The BPMN process id the cluster knows
   * @return The job type of the end execution listener of the process
   */
  public static String workflowEndedJobTypeOf(
      final String scopedBpmnProcessId) {

    return TASKDEFINITION_WORKFLOW_ENDED + scopedBpmnProcessId;

  }

  /**
   * The element id this adapter reserved for the probe which asks the engine whether it
   * holds a process instance.
   * <p>
   * The probe is a process instance modification naming an element the model does not have.
   * The cluster refuses it, and the refusal is the answer. So nothing about the workflow
   * changes. An id which by accident matches an element of the model would be ACTIVATED
   * instead. That is a change to a running workflow nobody asked for. So the models are
   * searched for it while they are deployed, and a process which carries it gets no probe,
   * see decision 35 in the repository's DECISIONS.md.
   */
  public static final String RESERVED_PROBE_ELEMENT_ID = "vanillabp-existence-probe";

  /**
   * Whether the given model carries the element id
   * {@link #RESERVED_PROBE_ELEMENT_ID reserved for the probe}.
   * <p>
   * The whole FILE is read, not the one process. A file is the deployment unit, and an id is
   * unique within it. Being one process too careful costs a search. Being one process too
   * few costs a modification of a running workflow.
   *
   * @param model The BPMN model
   * @return Whether anything in it carries the reserved id
   */
  public static boolean carriesTheReservedProbeElement(
      final BpmnModelInstance model) {

    return model.getModelElementById(RESERVED_PROBE_ELEMENT_ID) != null;

  }

  /**
   * The version tag the modeller gave the process (<code>zeebe:versionTag</code>). It is
   * the name a <code>&#64;WorkflowTask(version = "release-2026")</code> refers to.
   *
   * @param model The BPMN model as deployed
   * @param bpmnProcessId The SCOPED BPMN process id
   * @return The version tag or <code>null</code> if the model carries none
   */
  public static String versionTagOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    final var process = model
        .getModelElementsByType(Process.class)
        .stream()
        .filter(candidate -> bpmnProcessId.equals(candidate.getId()))
        .findFirst()
        .orElse(null);
    if (process == null) {
      return null;
    }
    final var versionTag = process
        .getSingleExtensionElement(ZeebeVersionTag.class);
    return versionTag == null
        ? null
        : versionTag.getValue();

  }

  /**
   * Attaches an <code>end</code> execution listener to the PROCESS element. It tells
   * VanillaBP that a workflow ended. It is only called where the application declared a
   * <code>&#64;WorkflowEnded</code> method. A model must not pay for a notification nobody
   * asked for.
   *
   * @param model The BPMN model, already scoped by <code>prepareBpmn</code>
   * @param bpmnProcessId The SCOPED BPMN process id
   * @return Whether a listener was attached (false if the process is not in this
   *         model)
   */
  public static boolean attachWorkflowEndedListener(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    final var jobType = workflowEndedJobTypeOf(bpmnProcessId);
    final var listeners = executionListenersOfTheProcess(model, bpmnProcessId);
    if (listeners == null) {
      return false;
    }
    if (alreadyCarries(listeners, jobType, ZeebeExecutionListenerEventType.end.name())) {
      return true;
    }

    final var listener = listeners.getModelInstance().newInstance(ZeebeExecutionListener.class);
    listener
        .setEventType(ZeebeExecutionListenerEventType.end);
    listener.setType(jobType);
    // LAST listener. Whatever the model itself does at the end of the process runs
    // before the application is told the workflow ended.
    listeners.addChildElement(listener);
    return true;

  }

  /**
   * Attaches a <code>cancel</code> execution listener to the PROCESS element. It tells
   * VanillaBP that an instance was terminated. It carries the SAME job type as the end
   * listener next to it. So one worker answers both, and the handler tells the two apart by
   * the event the job reports.
   * <p>
   * The construct belongs to the 8.10 line. So the caller asks
   * {@link Camunda8CancelListeners#theProcessCanReportItsCancellation()} first. The caller
   * also has to open a worker for the job type. A listener nobody serves holds the instance
   * until its job is answered. That turns a cancelation into a workflow which never goes
   * away, and which raises no incident either.
   *
   * @param model The BPMN model, already scoped by <code>prepareBpmn</code>
   * @param bpmnProcessId The SCOPED BPMN process id
   * @return Whether a listener was attached (false if the process is not in this model)
   */
  public static boolean attachWorkflowCanceledListener(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    final var jobType = workflowEndedJobTypeOf(bpmnProcessId);
    final var listeners = executionListenersOfTheProcess(model, bpmnProcessId);
    if (listeners == null) {
      return false;
    }
    if (alreadyCarries(listeners, jobType, CANCELING_AN_INSTANCE)) {
      return true;
    }

    // the retries of the end listener, which are the default of the model. A failed
    // notification is failed with one attempt less. The last failure raises the
    // incident an operator acts on.
    Camunda8CancelListeners.addProcessCancelListener(listeners, jobType, null);
    return true;

  }

  /**
   * The execution listeners of the given process, created where the model carries none yet.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The SCOPED BPMN process id
   * @return The listeners, or <code>null</code> where this model has no such process
   */
  private static ZeebeExecutionListeners executionListenersOfTheProcess(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    final var process = model
        .getModelElementsByType(Process.class)
        .stream()
        .filter(candidate -> bpmnProcessId.equals(candidate.getId()))
        .findFirst()
        .orElse(null);
    if (process == null) {
      return null;
    }
    if (process.getSingleExtensionElement(ZeebeExecutionListeners.class) != null) {
      return process.getSingleExtensionElement(ZeebeExecutionListeners.class);
    }
    if (process.getExtensionElements() == null) {
      process
          .setExtensionElements(
              process
                  .getModelInstance()
                  .newInstance(ExtensionElements.class));
    }
    return process
        .getExtensionElements()
        .addExtensionElement(ZeebeExecutionListeners.class);

  }

  /**
   * Whether the process already carries that listener. Wiring a model again which was
   * already processed runs into this.
   */
  private static boolean alreadyCarries(
      final ZeebeExecutionListeners listeners,
      final String jobType,
      final String eventType) {

    return listeners
        .getExecutionListeners()
        .stream()
        .anyMatch(listener -> jobType.equals(listener.getType()) && (listener
            .getEventType() != null) && eventType.equals(listener.getEventType().name()));

  }

  /**
   * The event of an execution listener which fires while an INSTANCE is being terminated, as
   * the model spells it. It is written as the word and not as the constant of the client. The
   * constant arrived with the 8.10 client, and this file is compiled on every line.
   */
  private static final String CANCELING_AN_INSTANCE = "cancel";

  /**
   * One start event of a process, to be served by a start execution-listener worker.
   *
   * @param bpmnProcessId The SCOPED BPMN process id (what the cluster knows)
   * @param startEventId The BPMN id of the start event
   * @param kind Which kind of start event it is
   * @param signalName The PLAIN signal name for a signal start event
   */
  public record Camunda8BpmsInitiatedStartToWire(
                                                 String bpmnProcessId,
                                                 String startEventId,
                                                 BpmsStartTrigger.Kind kind,
                                                 String signalName) {

    /**
     * The job type of the execution listener of this start event.
     *
     * @return The job type of the execution listener of this start event
     */
    public String listenerJobType() {

      return listenerJobTypeOf(bpmnProcessId, startEventId);

    }

  }

  /**
   * The job type of the execution listener of a start event. One worker serves one start
   * event, so the element id is part of the job type.
   *
   * @param scopedBpmnProcessId The BPMN process id the cluster knows
   * @param startEventId The BPMN id of the start event
   * @return The job type of the execution listener of the start event
   */
  public static String listenerJobTypeOf(
      final String scopedBpmnProcessId,
      final String startEventId) {

    return "%s%s:%s".formatted(TASKDEFINITION_BPMS_INITIATED_START, scopedBpmnProcessId, startEventId);

  }

  /**
   * EVERY start event of the given executable process. It also attaches an execution
   * listener to each of them. That is how VanillaBP learns about a start. It can then decide
   * what the start means before anything else of the process runs.
   * <p>
   * The plain and the message start event are among them. What a start MEANS is read from
   * the state of the workflow, not from the kind of its start event. A workflow the
   * application started is told apart from one somebody started past VanillaBP by the name
   * the cluster holds for it, whichever event began it. See decision 49 in the repository's
   * DECISIONS.md. Camunda 8 has no conditional events at all. The kind is part of the model
   * here, so an unsupported model fails at the cluster and not silently.
   * <p>
   * Only the start events the process itself holds are read. An event subprocess starts
   * no workflow. {@link #startsTheWorkflow(StartEvent)} says more about that.
   *
   * @param model The BPMN model, already scoped by <code>prepareBpmn</code>
   * @param bpmnProcessId The SCOPED BPMN process id
   * @param signalNameResolver Turns the scoped signal name of the model into the
   *          plain one the application modelled
   * @return The start events to be wired
   */
  public static List<Camunda8BpmsInitiatedStartToWire> bpmsInitiatedStartsOf(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final UnaryOperator<String> signalNameResolver) {

    return bpmsInitiatedStartsOf(model, bpmnProcessId, signalNameResolver, true);

  }

  /**
   * The start events of a called process, read without adding anything. A called process is no
   * workflow of its own. So the core does not have to hear about its start. A listener on it
   * would only hold the instance until a worker answered.
   *
   * @param model The model being deployed
   * @param bpmnProcessId The process id as the CLUSTER knows it
   * @param signalNameResolver Turns the scoped signal name of the model into the
   *          plain one the application modelled
   * @return The start events the model declares. So the core can still refuse one the
   *         cluster fires on its own
   */
  public static List<Camunda8BpmsInitiatedStartToWire> startEventsOfACalledProcess(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final UnaryOperator<String> signalNameResolver) {

    return bpmsInitiatedStartsOf(model, bpmnProcessId, signalNameResolver, false);

  }

  /**
   * The start events the CLUSTER fires on its own in a model it already runs. They are read
   * for checks about versions an earlier application deployed, never for deploying. Nothing
   * is added here. The cluster accepted the model as it stands. It carries the execution
   * listener of the deployment which brought it. So a read on its behalf leaves it exactly
   * as it is.
   *
   * @param model The model as the cluster holds it
   * @param bpmnProcessId The process id as the CLUSTER knows it
   * @param signalNameResolver Turns the scoped signal name of the model into the
   *          plain one the application modelled
   * @return The start events the model declares
   */
  public static List<Camunda8BpmsInitiatedStartToWire> bpmsInitiatedStartsOfHeldModel(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final UnaryOperator<String> signalNameResolver) {

    return bpmsInitiatedStartsOf(model, bpmnProcessId, signalNameResolver, false);

  }

  private static List<Camunda8BpmsInitiatedStartToWire> bpmsInitiatedStartsOf(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final UnaryOperator<String> signalNameResolver,
      final boolean attachTheStartListener) {

    final var startEvents = new LinkedList<Camunda8BpmsInitiatedStartToWire>();
    model
        .getModelElementsByType(StartEvent.class)
        .stream()
        .filter(startEvent -> bpmnProcessId.equals(owningProcessId(startEvent)))
        .filter(Camunda8TaskWiring::startsTheWorkflow)
        .forEach(startEvent -> {
          final var definitions = startEvent.getEventDefinitions();
          final var timer = definitions
              .stream()
              .anyMatch(TimerEventDefinition.class::isInstance);
          final var signal = definitions
              .stream()
              .filter(SignalEventDefinition.class::isInstance)
              .map(SignalEventDefinition.class::cast)
              .findFirst();
          final var conditional = definitions
              .stream()
              .anyMatch(ConditionalEventDefinition.class::isInstance);

          final var message = definitions
              .stream()
              .anyMatch(io.camunda.zeebe.model.bpmn.instance.MessageEventDefinition.class::isInstance);

          final BpmsStartTrigger.Kind kind;
          final String signalName;
          if (timer) {
            kind = BpmsStartTrigger.Kind.TIMER;
            signalName = null;
          } else if (signal.isPresent()) {
            kind = BpmsStartTrigger.Kind.SIGNAL;
            signalName = signal
                .map(definition -> definition.getSignal() == null
                    ? null
                    : definition.getSignal().getName())
                .map(signalNameResolver)
                .orElse(null);
          } else if (conditional) {
            kind = BpmsStartTrigger.Kind.CONDITIONAL;
            signalName = null;
          } else if (message) {
            kind = BpmsStartTrigger.Kind.MESSAGE;
            signalName = null;
          } else {
            kind = BpmsStartTrigger.Kind.NONE;
            signalName = null;
          }

          if (attachTheStartListener) {
            addStartExecutionListener(startEvent, listenerJobTypeOf(bpmnProcessId, startEvent.getId()));
          }
          startEvents
              .add(
                  new Camunda8BpmsInitiatedStartToWire(
                      bpmnProcessId, startEvent.getId(), kind, signalName));
        });
    return startEvents;

  }

  /**
   * The names of the messages which start the given executable process, in the form the
   * application passes them to <code>startWorkflowByMessage</code>. Only the start events
   * the process itself holds count. A message start event of an event subprocess starts no
   * workflow.
   * <p>
   * The answer is empty where one of the names cannot be known before the message is
   * published. That is a message start event without a message or without a name, or a
   * name which is a FEEL expression (it starts with <code>=</code>). A process like that
   * cannot be checked, and the caller does not report it at all.
   *
   * @param model The BPMN model, already scoped by <code>prepareBpmn</code>
   * @param bpmnProcessId The SCOPED BPMN process id
   * @param messageNameResolver Turns the scoped message name of the model into the
   *          plain one the application modelled
   * @return The plain message names, or nothing where one of them is unknown
   */
  public static Optional<List<String>> startMessageNamesOf(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final UnaryOperator<String> messageNameResolver) {

    final var messageStartEvents = model
        .getModelElementsByType(StartEvent.class)
        .stream()
        .filter(startEvent -> bpmnProcessId.equals(owningProcessId(startEvent)))
        .filter(Camunda8TaskWiring::startsTheWorkflow)
        .flatMap(startEvent -> startEvent
            .getEventDefinitions()
            .stream()
            .filter(MessageEventDefinition.class::isInstance)
            .map(MessageEventDefinition.class::cast))
        .toList();

    final var names = new LinkedList<String>();
    for (final var definition : messageStartEvents) {
      final var name = definition.getMessage() == null
          ? null
          : definition
              .getMessage()
              .getName();
      if ((name == null) || name.isBlank() || name
          .strip()
          .startsWith("=")) {
        return Optional.empty();
      }
      names.add(messageNameResolver.apply(name));
    }
    return Optional.of(List.copyOf(names));

  }

  /**
   * Attaches a <code>start</code> execution listener to the start event, unless the
   * model already carries it (wiring a model again which was already processed). Retries
   * stay at the Camunda default. Unlike with the user-task listeners, a failure here means
   * the workflow has no aggregate. That is worth retrying before it becomes an incident.
   * <p>
   * It costs the model one <code>zeebe:executionListeners</code> element per start event.
   * {@code Camunda8StartListenerCostTest} measures that.
   */
  private static void addStartExecutionListener(
      final StartEvent startEvent,
      final String listenerJobType) {

    final ZeebeExecutionListeners listeners;
    if (startEvent
        .getSingleExtensionElement(
            ZeebeExecutionListeners.class) != null) {
      listeners = startEvent
          .getSingleExtensionElement(
              ZeebeExecutionListeners.class);
      final var alreadyWired = listeners
          .getExecutionListeners()
          .stream()
          .anyMatch(listener -> listenerJobType.equals(listener.getType()));
      if (alreadyWired) {
        return;
      }
    } else {
      // a start event carrying no extension elements at all. The container has to
      // be created before a listener can be added to it.
      if (startEvent.getExtensionElements() == null) {
        startEvent
            .setExtensionElements(
                startEvent
                    .getModelInstance()
                    .newInstance(ExtensionElements.class));
      }
      listeners = startEvent
          .getExtensionElements()
          .addExtensionElement(ZeebeExecutionListeners.class);
    }

    final var listener = startEvent
        .getModelInstance()
        .newInstance(ZeebeExecutionListener.class);
    // 'end' on the start event, not 'start'. The cluster rejects start execution
    // listeners on start events (8.8). An end listener still gates the transition.
    // It runs before the flow leaves the start event. So nothing of the process can
    // run before the workflow aggregate exists.
    listener
        .setEventType(ZeebeExecutionListenerEventType.end);
    listener.setType(listenerJobType);
    // the workflow aggregate is built here. So the listener of VanillaBP has to run
    // before any listener the model brings along.
    listeners.insertElementAfter(listener, null);

  }

  /**
   * One user task to be served by listener-job workers.
   *
   * @param bpmnProcessId The BPMN process ID
   * @param activityId The BPMN activity ID
   * @param externalFormReference The <code>zeebe:formDefinition</code> external
   *          reference (= the VanillaBP task definition of the user task)
   */
  public record Camunda8UserTaskToWire(
                                       String bpmnProcessId,
                                       String activityId,
                                       String externalFormReference) {

    /**
     * The user task in the shape in which the checks of the platform read it.
     *
     * @return The element id and the task definition of this user task
     */
    public BpmnTaskSpec toSpec() {

      return BpmnTaskSpec.userTask(activityId, externalFormReference);

    }

    /**
     * The job type of the lifecycle listeners of this user task.
     *
     * @return That job type
     */
    public String listenerJobType() {

      return TASKDEFINITION_USERTASK_ZEEBE + externalFormReference;

    }

  }

  /**
   * The job type with which the cluster serves a job-worker user task. Version 1 used that
   * shape up to its release 1.6.3. Version 2 opens no worker on it, and it does not accept
   * the shape either. {@link #jobWorkerUserTasksOf} exists to find it.
   */
  public static final String TASKDEFINITION_USERTASK_WORKER_V1 = "io.camunda.zeebe:userTask";

  /**
   * The job-worker user tasks of one BPMN process. They are told apart by whether the model
   * still carries the <code>formKey</code> of version 1.
   * <p>
   * The shape is the same for both, and the way out of both is the same. But only one of
   * them can be found by searching a model for <code>formKey</code>. So the report has to
   * keep them apart.
   *
   * @param withVersionOnesFormKey The element ids whose <code>zeebe:formDefinition</code>
   *          names a <code>formKey</code>. That told version 1 the task definition
   * @param withoutAFormKey The element ids of a job-worker user task carrying no
   *          <code>formKey</code> at all. That was never a VanillaBP shape, and it is just as
   *          silent
   */
  public record Camunda8JobWorkerUserTasks(
                                           List<String> withVersionOnesFormKey,
                                           List<String> withoutAFormKey) {

    /**
     * Whether this process gives the report nothing to say.
     *
     * @return Whether the process carries neither shape
     */
    public boolean isEmpty() {

      return withVersionOnesFormKey.isEmpty() && withoutAFormKey.isEmpty();

    }

    /**
     * Both shapes together. The count in the report is about them, because the way out is the
     * same for all of them.
     *
     * @return Every element id found, the shape of version 1 first
     */
    public List<String> all() {

      return java.util.stream.Stream
          .concat(withVersionOnesFormKey.stream(), withoutAFormKey.stream())
          .toList();

    }

  }

  /**
   * The user tasks of the given executable process which are modelled as a JOB-WORKER user
   * task. That is a user task without a <code>zeebe:userTask</code> extension element, and
   * without a <code>zeebe:taskDefinition</code> of the application itself.
   * <p>
   * The question is the shape of the element and nothing else. The cluster manages a user
   * task carrying <code>zeebe:userTask</code>, and that is the one shape this adapter takes.
   * A user task without it is served by a job of {@value #TASKDEFINITION_USERTASK_WORKER_V1}.
   * VanillaBP 1 worked that way up to its release 1.6.3, and this adapter does not take that
   * shape. So the reader does not ask whether something would fetch that job. Nor does it
   * ask whether the element names a <code>formKey</code>. For a process which a workflow
   * service of the application claims, the deployment ends the boot over the answer. It asks
   * nothing about a process nobody claims.
   * <p>
   * The element carrying a <code>zeebe:taskDefinition</code> is left out. It is the one case
   * which is not this shape. A worker of the APPLICATION serves such an element, under a job
   * type the application chose. So it is no longer a user task VanillaBP is meant to serve.
   * Nothing here notifies anybody about it, and nothing completes it. It falls through the
   * rest as well, because {@link #tasksOf} reads service-like tasks only, and
   * {@link #userTasksOf} skips anything without a <code>zeebe:userTask</code>.
   * <p>
   * Two shapes end up here, and the message has to keep them apart. Up to release 1.6.3,
   * VanillaBP 1 served exactly this element. It read its task definition from the
   * <code>zeebe:formDefinition</code> <strong>formKey</strong>. Release 1.7.0 replaced it with
   * a Camunda-managed user task, and kept serving the old one alongside. An application
   * upgrading from that version finds those elements by searching its models for
   * <code>formKey</code>. A user task WITHOUT one does not follow the convention of version 1,
   * and no search for a formKey finds it. But the cluster treats it exactly the same. That is
   * why both are collected and reported apart.
   *
   * @param model The BPMN model of one file
   * @param bpmnProcessId The process id as the CLUSTER will know it
   * @return The element ids per shape, both empty where the model carries none
   */
  public static Camunda8JobWorkerUserTasks jobWorkerUserTasksOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    final var byShape = model
        .getModelElementsByType(UserTask.class)
        .stream()
        .filter(task -> bpmnProcessId.equals(owningProcessId(task)))
        // a Camunda-managed user task is the shape this adapter takes. A user task
        // carrying a zeebe:taskDefinition is an element the application serves itself.
        // Neither of them is a job-worker user task of VanillaBP.
        .filter(task -> task.getSingleExtensionElement(ZeebeUserTask.class) == null)
        .filter(task -> task.getSingleExtensionElement(ZeebeTaskDefinition.class) == null)
        .collect(
            java.util.stream.Collectors
                .partitioningBy(
                    task -> namesAFormKey(task.getSingleExtensionElement(ZeebeFormDefinition.class)),
                    java.util.stream.Collectors.mapping(FlowElement::getId, java.util.stream.Collectors.toList())));
    return new Camunda8JobWorkerUserTasks(
        List.copyOf(byShape.get(Boolean.TRUE)), List.copyOf(byShape.get(Boolean.FALSE)));

  }

  /**
   * Whether a form definition names the formKey of version 1. It told that adapter the task
   * definition of the user task.
   *
   * @param formDefinition The form definition of the element, may be <code>null</code>
   * @return <code>true</code> where a non-blank formKey is set
   */
  private static boolean namesAFormKey(
      final ZeebeFormDefinition formDefinition) {

    if (formDefinition == null) {
      return false;
    }
    final var formKey = formDefinition.getFormKey();
    return (formKey != null) && !formKey.isBlank();

  }

  /**
   * The Camunda-managed user tasks (<code>zeebe:userTask</code>) of the given executable
   * process, READ and nothing else. The model is not touched here.
   * <p>
   * A user task without an external form reference fails with a guiding message. Under the
   * Camunda 8 convention of VanillaBP, the reference IS the task definition. A user task
   * without one is a model this adapter cannot deploy. That is a question about the model,
   * not a change to it. So it is asked here, and the answer is the same however often it is
   * asked.
   * <p>
   * It is public and separate from {@link #userTasksOf}, because an extension which wires
   * its own listeners into the same file needs the same list. Calling the preparing method
   * for it would mean that a second party writes lifecycle listeners into a model the
   * adapter owns. The adapter does run before every extension. So those listeners are
   * already in the model when an extension reads it. Writing them a second time would
   * change nothing, and it would still be the business of somebody else.
   *
   * @param model The BPMN model of one file
   * @param bpmnProcessId The process id as the CLUSTER knows it
   * @param workflowModuleId The workflow module, for the message
   * @param filename The file, for the message
   * @return The Camunda-managed user tasks the process declares
   */
  public static List<Camunda8UserTaskToWire> readUserTasksOf(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final String workflowModuleId,
      final String filename) {

    final var userTasks = new LinkedList<Camunda8UserTaskToWire>();
    model
        .getModelElementsByType(UserTask.class)
        .stream()
        .filter(task -> bpmnProcessId.equals(owningProcessId(task)))
        // only Camunda-managed user tasks (zeebe:userTask, the 8.8 default). Worker-based
        // user tasks (a zeebe:taskDefinition instead) are not wired at all. They are found
        // and reported by jobWorkerUserTasksOf
        .filter(task -> task.getSingleExtensionElement(ZeebeUserTask.class) != null)
        .forEach(task -> {
          final var formDefinition = task.getSingleExtensionElement(ZeebeFormDefinition.class);
          final var externalFormReference = formDefinition != null
              ? formDefinition.getExternalReference()
              : null;
          if ((externalFormReference == null) || externalFormReference.isBlank()) {
            throw new IllegalStateException(
                ("User task '%s' of BPMN process '%s' (file '%s', workflow module '%s') has no "
                    + "external form reference! VanillaBP's Camunda 8 convention: the user task's "
                    + "form is referenced externally and the reference IS the task definition - "
                    + "set 'External form reference' in the modeler (zeebe:formDefinition "
                    + "externalReference).")
                    .formatted(task.getId(), bpmnProcessId, filename, workflowModuleId));
          }
          userTasks.add(new Camunda8UserTaskToWire(bpmnProcessId, task.getId(), externalFormReference));
        });
    return userTasks;

  }

  /**
   * The Camunda-managed user tasks of the given executable process, as
   * {@link #readUserTasksOf} reports them. In addition it writes the V1-compatible lifecycle
   * task listeners into the model. Per user task, a <code>creating</code> listener comes
   * FIRST and a <code>canceling</code> listener comes LAST. Custom listeners which the
   * modeller defined stay in between. Both have <code>retries="1"</code> and the type
   * {@link #TASKDEFINITION_USERTASK_ZEEBE} + external form reference.
   * <p>
   * This is the deployment path, and it CHANGES the model. A second call adds no second set
   * of listeners. A user task which already carries this listener job type is left as it
   * is, see {@code Camunda8UserTasksReadAndPrepareTest}. But a caller which only wants to
   * know what the model declares asks {@link #readUserTasksOf} instead. Being harmless is
   * not the same as being the business of the caller.
   *
   * @param model The BPMN model of one file, modified in place
   * @param bpmnProcessId The process id as the CLUSTER knows it
   * @param workflowModuleId The workflow module, for the message
   * @param filename The file, for the message
   * @return The Camunda-managed user tasks the process declares
   */
  public static List<Camunda8UserTaskToWire> userTasksOf(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final String workflowModuleId,
      final String filename) {

    final var userTasks = readUserTasksOf(model, bpmnProcessId, workflowModuleId, filename);
    userTasks
        .forEach(userTask -> addUserTaskListeners(
            (UserTask) model.getModelElementById(userTask.activityId()),
            userTask.externalFormReference()));
    return userTasks;

  }

  /**
   * The Camunda-managed user tasks of a model the CLUSTER already runs. They are read for
   * checks about versions an earlier application deployed, never for deploying. Nothing is
   * validated and nothing is added here. The cluster accepted the model as it stands. A
   * check may not refuse what is being read on its behalf, a model nobody can change any
   * more. A user task without an external form reference is read by the convention of
   * version 1 instead. Up to release 1.6.3 its formKey WAS the task definition. One which
   * names neither is skipped. VanillaBP serves it under no convention, whatever the
   * application does.
   *
   * @param model The model as the cluster holds it
   * @param bpmnProcessId The process id as the CLUSTER knows it
   * @return The user tasks the model declares
   */
  public static List<Camunda8UserTaskToWire> userTasksOfHeldModel(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    return model
        .getModelElementsByType(UserTask.class)
        .stream()
        .filter(task -> bpmnProcessId.equals(owningProcessId(task)))
        .filter(task -> task.getSingleExtensionElement(ZeebeUserTask.class) != null)
        .map(task -> {
          final var formDefinition = task.getSingleExtensionElement(ZeebeFormDefinition.class);
          if (formDefinition == null) {
            return null;
          }
          final var externalFormReference = formDefinition.getExternalReference();
          if ((externalFormReference != null) && !externalFormReference.isBlank()) {
            return new Camunda8UserTaskToWire(bpmnProcessId, task.getId(), externalFormReference);
          }
          final var formKey = formDefinition.getFormKey();
          if ((formKey != null) && !formKey.isBlank()) {
            return new Camunda8UserTaskToWire(bpmnProcessId, task.getId(), formKey);
          }
          return null;
        })
        .filter(java.util.Objects::nonNull)
        .toList();

  }

  /**
   * The job types of the user-task listeners VanillaBP wrote into a model the CLUSTER already
   * runs, read from the listeners themselves.
   * <p>
   * The job type is read and not put together. The model in the cluster carries it as it was
   * written when that model was deployed: with the scoping of that deployment, and for every
   * user task, served by a method or not. An application which declares an old process id
   * after a rename has no model of its own for that id. So this is the only place which says
   * which listener jobs the workflows of the old id produce.
   *
   * @param model The model as the cluster holds it
   * @param bpmnProcessId The process id as the CLUSTER knows it
   * @return The listener job types, each once, in the order of the model
   */
  public static Set<String> userTaskListenerJobTypesOfHeldModel(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    final var jobTypes = new LinkedHashSet<String>();
    model
        .getModelElementsByType(UserTask.class)
        .stream()
        .filter(task -> bpmnProcessId.equals(owningProcessId(task)))
        .map(task -> task.getSingleExtensionElement(ZeebeTaskListeners.class))
        .filter(Objects::nonNull)
        .flatMap(listeners -> listeners.getTaskListeners().stream())
        .map(ZeebeTaskListener::getType)
        .filter(type -> (type != null) && type.startsWith(TASKDEFINITION_USERTASK_ZEEBE))
        .forEach(jobTypes::add);
    return jobTypes;

  }

  /**
   * What the lifecycle listeners of a user task are modelled with.
   * <p>
   * Version 1 wrote <code>retries="0"</code> here, and that number is not only what a failed
   * notification has left. Sometimes a gateway cannot hand an activated batch to the request
   * it was activated for. Then it fails the job back to the broker WITH THE RETRIES IT HAD.
   * So a job modelled without one has nothing to be failed back with. The lost delivery
   * becomes an incident, and the user task stands in <code>CREATING</code> for good. One
   * retry makes that job activatable again. This adapter documents that recovery for every
   * other job.
   * <p>
   * It does not give a failed notification a second attempt. The handler fails such a job
   * with no retries left, whatever the model says. So the first failure raises the incident
   * an operator acts on, as before. See
   * {@link Camunda8UserTaskListenerHandler} and
   * {@code Camunda8ShutdownHandlingTest#aListenerFailingIsReported}.
   * <p>
   * Why these are two numbers and not one: see decision 41 in the repository's DECISIONS.md.
   */
  private static final String ONE_ATTEMPT_LEFT_FOR_A_DELIVERY_THE_GATEWAY_LOST = "1";

  /**
   * V1 listener order per element: VanillaBP <code>creating</code> FIRST, any custom
   * listeners in between, VanillaBP <code>canceling</code> LAST. Listeners which already
   * carry the VanillaBP prefix are not added twice (wiring a model again which was already
   * processed).
   */
  private static void addUserTaskListeners(
      final UserTask task,
      final String externalFormReference) {

    final var listenerJobType = TASKDEFINITION_USERTASK_ZEEBE + externalFormReference;

    final ZeebeTaskListeners taskListeners;
    final boolean isNew;
    if (task.getSingleExtensionElement(ZeebeTaskListeners.class) != null) {
      taskListeners = task.getSingleExtensionElement(ZeebeTaskListeners.class);
      final var alreadyWired = taskListeners
          .getTaskListeners()
          .stream()
          .anyMatch(listener -> listenerJobType.equals(listener.getType()));
      if (alreadyWired) {
        return;
      }
      isNew = false;
    } else {
      taskListeners = task.getExtensionElements().addExtensionElement(ZeebeTaskListeners.class);
      isNew = true;
    }

    final var createListener = task.getModelInstance().newInstance(ZeebeTaskListener.class);
    createListener.setEventType(ZeebeTaskListenerEventType.creating);
    createListener.setType(listenerJobType);
    createListener.setRetries(ONE_ATTEMPT_LEFT_FOR_A_DELIVERY_THE_GATEWAY_LOST);
    taskListeners.insertElementAfter(createListener, null); // first listener

    final var cancelListener = task.getModelInstance().newInstance(ZeebeTaskListener.class);
    cancelListener.setEventType(ZeebeTaskListenerEventType.canceling);
    cancelListener.setType(listenerJobType);
    cancelListener.setRetries(ONE_ATTEMPT_LEFT_FOR_A_DELIVERY_THE_GATEWAY_LOST);
    if (isNew) {
      taskListeners.insertElementAfter(cancelListener, createListener);
    } else {
      final var previousListeners = new LinkedList<>(taskListeners.getTaskListeners());
      taskListeners.insertElementAfter(
          cancelListener, previousListeners.isEmpty()
              ? createListener
              : previousListeners.getLast());
    }

  }

  /**
   * Wires the correlation keys of the MESSAGE subscriptions of the given executable
   * process. Publishing with <code>correlationKey = workflow-aggregate ID</code> only
   * correlates if the message subscriptions of the deployed model carry a matching
   * <code>zeebe:subscription</code> correlation-key expression. A catch element of this
   * process may point at a message WITHOUT such an expression. For every such message the
   * V2 convention <code>=&lt;aggregate-ID variable&gt;</code> is INJECTED. That is the
   * engine-specific BPMN modification of the pipeline. An existing expression is left
   * untouched. The modeller may correlate by a variable of their own, for example for
   * correlation-id scenarios, and V1 models keep working byte-identically. Message START
   * events need no correlation key and are skipped.
   * <p>
   * The injection is for a process of which the application serves a workflow. It is
   * called for no other. The variable it names belongs to the workflow aggregate. A process
   * no <code>&#64;WorkflowService</code> class claims has no aggregate to name one after.
   * What such a process owes the cluster is asked BEFORE any of the file is rewritten, by
   * {@link #messagesWithoutACorrelationKey}. The deployment refuses the file over it. It
   * does not put a substitute into a model it does not own.
   *
   * @param model The BPMN model (modified in place)
   * @param bpmnProcessId The executable process to wire
   * @param aggregateIdVariableName Supplies the name of the process variable
   *          holding the workflow-aggregate ID. It is asked ONCE, and only if an
   *          injection is really needed. So a process without message catch elements
   *          does not need the ID property of the aggregate to be resolvable
   */
  public static void wireMessageSubscriptions(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final Supplier<String> aggregateIdVariableName) {

    final var messages = new LinkedHashSet<Message>();
    messageCatchElementsOf(model, bpmnProcessId)
        .map(MessageCatchElement::message)
        // the modeller correlates on purpose (for example by a correlation-id variable of
        // their own). Leave it untouched, so V1 models stay byte-identical.
        .filter(message -> message.getSingleExtensionElement(ZeebeSubscription.class) == null)
        .forEach(messages::add);
    if (messages.isEmpty()) {
      return;
    }

    final var correlationKey = "="
        + aggregateIdVariableName.get();
    messages.forEach(message -> {
      final var extensionElements = message.getExtensionElements() != null
          ? message.getExtensionElements()
          : message
              .getModelInstance()
              .newInstance(ExtensionElements.class);
      if (message.getExtensionElements() == null) {
        message.addChildElement(extensionElements);
      }
      final var subscription = extensionElements
          .addExtensionElement(ZeebeSubscription.class);
      subscription.setCorrelationKey(correlationKey);
    });

  }

  /**
   * What the given executable process still owes the cluster: every catch element of it
   * whose message carries no <code>zeebe:subscription</code>, in the order the model
   * declares them.
   * <p>
   * Camunda 8 accepts no such element and rejects the whole FILE over one. So this is the
   * question to ask about a process of which the application serves no workflow. There is
   * no workflow aggregate to name a correlation key after. So the deployment refuses the
   * file and does not invent one. This is asked while the model is still as the modeller
   * wrote it. So the answer does not depend on which process of a file was wired first. A
   * message element belongs to the FILE. Otherwise an injection for one process would fill
   * the gap of another.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The executable process to look at
   * @return One entry per catch element waiting for a correlation key, empty where the
   *         process catches no message or every message it catches carries a key
   */
  public static List<MessageWithoutACorrelationKey> messagesWithoutACorrelationKey(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    return messageCatchElementsOf(model, bpmnProcessId)
        .filter(catchElement -> catchElement
            .message()
            .getSingleExtensionElement(ZeebeSubscription.class) == null)
        .map(catchElement -> new MessageWithoutACorrelationKey(
            catchElement.elementId(), catchElement.message().getName()))
        .toList();

  }

  /**
   * A message a process waits for without saying what to correlate it by.
   *
   * @param catchElementId The intermediate catch event, boundary event or receive task
   *          waiting for it
   * @param messageName The name a publication of that message carries
   */
  public record MessageWithoutACorrelationKey(
                                              String catchElementId,
                                              String messageName) {
  }

  /**
   * A catch element of a process together with the message element it points at. The
   * message belongs to the FILE and not to the process. So two processes of one file can
   * point at the same one.
   *
   * @param elementId The intermediate catch event, boundary event or receive task waiting
   * @param message The message it points at
   */
  record MessageCatchElement(
                             String elementId,
                             Message message) {
  }

  /**
   * Every element of the given executable process which waits for a message: an
   * intermediate catch event, a boundary event or a receive task. A message START event
   * is not one of them. It correlates by name and needs no key.
   *
   * @param model The BPMN model of one file
   * @param bpmnProcessId The process id as it stands in the model
   * @return One entry per catch element, in the order the model declares them
   */
  static Stream<MessageCatchElement> messageCatchElementsOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    final var catchEvents = model
        .getModelElementsByType(CatchEvent.class)
        .stream()
        .filter(event -> bpmnProcessId.equals(owningProcessId(event)))
        .filter(event -> !(event instanceof StartEvent))
        .flatMap(event -> event
            .getEventDefinitions()
            .stream()
            .filter(MessageEventDefinition.class::isInstance)
            .map(MessageEventDefinition.class::cast)
            .map(MessageEventDefinition::getMessage)
            .filter(Objects::nonNull)
            .map(message -> new MessageCatchElement(event.getId(), message)));
    final var receiveTasks = model
        .getModelElementsByType(ReceiveTask.class)
        .stream()
        .filter(task -> bpmnProcessId.equals(owningProcessId(task)))
        .filter(task -> task.getMessage() != null)
        .map(task -> new MessageCatchElement(task.getId(), task.getMessage()));
    return Stream.concat(catchEvents, receiveTasks);

  }

  /**
   * The ad-hoc subprocesses of the given process which expect a job worker of this
   * application and get none.
   * <p>
   * Camunda 8 knows two flavours of the element. In the one VanillaBP serves, the model
   * itself says which activities to run.
   * <code>zeebe:adHoc activeElementsCollection</code> is a FEEL expression over a process
   * variable. So an attribute of the workflow aggregate decides. The activities inside the
   * element are ordinary tasks with ordinary <code>&#64;WorkflowTask</code> methods behind
   * them. In the other flavour, the element carries a <code>zeebe:taskDefinition</code> of
   * its own. A worker decides round by round which activities to activate. It does so by
   * completing the job with a result naming them. Neither the
   * <code>&#64;WorkflowTask</code> contract nor the adapter SPI can express that outcome.
   * So this adapter opens no worker for such an element.
   * <p>
   * An element carrying a <code>zeebe:modelerTemplate</code> is left out, because then the
   * runtime of somebody else owns it. The Camunda AI agent is an element template on exactly
   * this element. A connector runtime subscribing to its job type serves it, without this
   * application being involved. That is the same marker {@link Camunda8Connectors} reads.
   * This method reads it through that class and does not look for the attribute a second
   * time.
   *
   * @param model The BPMN model of one file
   * @param bpmnProcessId The process id as the CLUSTER will know it
   * @return The element ids, empty where the model carries no such element
   */
  public static List<String> unservedAdHocSubProcessIdsOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    return elementsOf(model, bpmnProcessId, AdHocSubProcess.class)
        .filter(subProcess -> subProcess.getSingleExtensionElement(ZeebeTaskDefinition.class) != null)
        .filter(subProcess -> Camunda8Connectors.elementTemplateOf(subProcess) == null)
        .map(FlowElement::getId)
        .toList();

  }

  /**
   * The IDs of the elements of the given process which can put a SECOND token into a
   * running workflow: a boundary event which does not cancel its activity,
   * a parallel or inclusive gateway forking into more than one sequence flow, an
   * activity marked as a PARALLEL multi-instance, an event subprocess whose start
   * event does not interrupt the process, and an ad-hoc subprocess. Two tokens are two
   * branches writing the same workflow aggregate. The core decides what that means. This
   * method only reads the model.
   * <p>
   * The ad-hoc subprocess is reported whichever flavour the model uses, and however few
   * activities it seems to run. Its <code>zeebe:adHoc activeElementsCollection</code> is an
   * expression evaluated when the workflow enters the element. So a list which is one entry
   * long today is two entries long as soon as the data behind it changes. And the job worker
   * flavour lets a worker activate several elements in one result. A warning which appears
   * only after such a change is worse than one which appears always.
   * <p>
   * The activities INSIDE the element are not reported. The second token comes from the
   * subprocess. Naming five inner tasks instead of the one element would make the warning
   * unreadable.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The ID of the process as the model knows it (the SCOPED ID)
   * @return The element IDs, possibly empty
   */
  public static List<String> concurrentTokenElementIdsOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    return Stream
        .of(
            elementsOf(model, bpmnProcessId, BoundaryEvent.class)
                .filter(boundaryEvent -> !boundaryEvent.cancelActivity()),
            elementsOf(model, bpmnProcessId, ParallelGateway.class)
                .filter(gateway -> gateway.getOutgoing().size() > 1),
            elementsOf(model, bpmnProcessId, InclusiveGateway.class)
                .filter(gateway -> gateway.getOutgoing().size() > 1),
            elementsOf(model, bpmnProcessId, Activity.class)
                .filter(Camunda8TaskWiring::isParallelMultiInstance),
            elementsOf(model, bpmnProcessId, SubProcess.class)
                .filter(Camunda8TaskWiring::isNonInterruptingEventSubProcess),
            elementsOf(model, bpmnProcessId, AdHocSubProcess.class))
        .flatMap(elements -> elements)
        .map(FlowElement::getId)
        .distinct()
        .toList();

  }

  /**
   * The compensation throw events of a BPMN process which start MORE THAN ONE handler,
   * each with the handlers it starts.
   * <p>
   * Compensation is a second token, drawn differently. From the throw event on, the workflow
   * holds a token per handler. Every one of those handlers is an ordinary workflow task
   * writing the same workflow aggregate. Camunda 8 hands out both handler jobs together, and
   * the two handlers then run at the same time on two threads. Each handler is a job of its
   * own. So it commits and is retried on its own. When one throws, only that one runs again.
   * {@code Camunda8CompensationIT} measures this against a cluster.
   * <p>
   * A throw event which names ONE activity compensates the handler of that activity and
   * nothing else. So it leaves the workflow with the one token it already had. Such an event
   * is read here, and the caller drops it. The rule that one handler is no finding lives
   * there.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The ID of the process as the CLUSTER will know it (the SCOPED ID)
   * @return The throw events with their handlers, in model order, possibly empty
   */
  public static List<CompensationSpec> compensationOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    final var handlerOfActivity = new java.util.LinkedHashMap<String, Activity>();
    final var compensatedActivities = new java.util.LinkedHashMap<String, Activity>();
    elementsOf(model, bpmnProcessId, BoundaryEvent.class)
        .filter(boundaryEvent -> !boundaryEvent
            .getEventDefinitions()
            .stream()
            .filter(CompensateEventDefinition.class::isInstance)
            .toList()
            .isEmpty())
        .forEach(boundaryEvent -> {
          final var compensated = boundaryEvent.getAttachedTo();
          final var handler = handlerAttachedTo(model, boundaryEvent);
          if ((compensated == null) || (handler == null)) {
            return;
          }
          handlerOfActivity.put(compensated.getId(), handler);
          compensatedActivities.put(compensated.getId(), compensated);
        });
    if (handlerOfActivity.isEmpty()) {
      return List.of();
    }

    final var compensations = new LinkedList<CompensationSpec>();
    Stream
        .of(
            elementsOf(model, bpmnProcessId, IntermediateThrowEvent.class),
            elementsOf(model, bpmnProcessId, EndEvent.class))
        .flatMap(events -> events)
        .forEach(throwEvent -> {
          final var thrown = throwEvent
              .getEventDefinitions()
              .stream()
              .filter(CompensateEventDefinition.class::isInstance)
              .map(CompensateEventDefinition.class::cast)
              .findFirst()
              .orElse(null);
          if (thrown == null) {
            return;
          }
          final var named = thrown.getActivity();
          final List<String> handlers;
          if (named != null) {
            final var handler = handlerOfActivity.get(named.getId());
            handlers = handler == null
                ? List.of()
                : List.of(handler.getId());
          } else {
            // no activity is named. So everything in the scope of the throw event which was
            // compensated is compensated at once.
            final var scope = compensationScopeOf(throwEvent);
            handlers = compensatedActivities
                .entrySet()
                .stream()
                .filter(compensated -> liesWithin(compensated.getValue(), scope))
                .map(compensated -> handlerOfActivity.get(compensated.getKey()).getId())
                .toList();
          }
          compensations.add(new CompensationSpec(throwEvent.getId(), handlers));
        });
    return compensations;

  }

  /**
   * The compensation handler an association points at, starting from the compensation
   * boundary event. Only an activity marked as one which compensates counts. An association
   * may reach a text annotation as well, and that is documentation, not a handler.
   */
  private static Activity handlerAttachedTo(
      final BpmnModelInstance model,
      final BoundaryEvent boundaryEvent) {

    return model
        .getModelElementsByType(Association.class)
        .stream()
        .filter(association -> boundaryEvent.equals(association.getSource()))
        .map(Association::getTarget)
        .filter(Activity.class::isInstance)
        .map(Activity.class::cast)
        .filter(Activity::isForCompensation)
        .findFirst()
        .orElse(null);

  }

  /**
   * The scope which a compensation throw event naming no activity compensates: the
   * subprocess around it, or the process.
   * <p>
   * An EVENT subprocess is walked through and not taken as the scope. Such a subprocess
   * handles what happened in the scope AROUND it. So a throw event inside it undoes the
   * activities of that scope, not the ones of the handler it sits in.
   */
  private static ModelElementInstance compensationScopeOf(
      final FlowElement throwEvent) {

    ModelElementInstance current = throwEvent.getParentElement();
    while (current != null) {
      if ((current instanceof SubProcess subProcess) && !subProcess.triggeredByEvent()) {
        return current;
      }
      if (current instanceof Process) {
        return current;
      }
      current = current.getParentElement();
    }
    return null;

  }

  /**
   * Whether an activity sits inside the given scope, at any depth.
   */
  private static boolean liesWithin(
      final Activity activity,
      final ModelElementInstance scope) {

    if (scope == null) {
      return false;
    }
    ModelElementInstance current = activity;
    while (current != null) {
      if (scope.equals(current)) {
        return true;
      }
      current = current.getParentElement();
    }
    return false;

  }

  private static <T extends FlowElement> Stream<T> elementsOf(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final Class<T> type) {

    return model
        .getModelElementsByType(type)
        .stream()
        .filter(element -> bpmnProcessId.equals(owningProcessId(element)));

  }

  private static boolean isParallelMultiInstance(
      final Activity activity) {

    return (activity
        .getLoopCharacteristics() instanceof MultiInstanceLoopCharacteristics loop) && !loop
            .isSequential();

  }

  private static boolean isNonInterruptingEventSubProcess(
      final SubProcess subProcess) {

    return subProcess.triggeredByEvent() && subProcess
        .getChildElementsByType(StartEvent.class)
        .stream()
        .anyMatch(startEvent -> !startEvent.isInterrupting());

  }

  /**
   * Whether the start event starts the WORKFLOW, which is true of the start events the
   * process itself holds and of no other.
   * <p>
   * A start event of an event subprocess fires while the workflow already runs and
   * already has its aggregate. So it starts no workflow. Counting it as one would refuse
   * every such model at startup. An application has to serve each start of a workflow with
   * a <code>&#64;WorkflowStartedByBpms</code> method. And the execution listener injected
   * into it would ask the application for a second aggregate of a workflow which already
   * owns one.
   *
   * @param startEvent The start event as the BPMN model carries it
   * @return Whether the process itself holds it
   */
  private static boolean startsTheWorkflow(
      final StartEvent startEvent) {

    return startEvent.getParentElement() instanceof Process;

  }

  private static String owningProcessId(
      final FlowElement element) {

    ModelElementInstance current = element;
    while (current != null) {
      if (current instanceof Process process) {
        return process.getId();
      }
      current = current.getParentElement();
    }
    return null;

  }

}
