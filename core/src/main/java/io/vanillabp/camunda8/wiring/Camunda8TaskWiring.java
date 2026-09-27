package io.vanillabp.camunda8.wiring;

import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.Activity;
import io.camunda.zeebe.model.bpmn.instance.AdHocSubProcess;
import io.camunda.zeebe.model.bpmn.instance.BoundaryEvent;
import io.camunda.zeebe.model.bpmn.instance.BusinessRuleTask;
import io.camunda.zeebe.model.bpmn.instance.CatchEvent;
import io.camunda.zeebe.model.bpmn.instance.ConditionalEventDefinition;
import io.camunda.zeebe.model.bpmn.instance.ExtensionElements;
import io.camunda.zeebe.model.bpmn.instance.FlowElement;
import io.camunda.zeebe.model.bpmn.instance.InclusiveGateway;
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
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * Extracts the job-worker tasks of an executable BPMN process from the Camunda 8
 * model: service-like tasks carrying a <code>zeebe:taskDefinition</code> - its
 * <code>type</code> IS the VanillaBP task definition (job type). Used during
 * <code>wireBpmn</code> for the wiring validation and to know which job workers to
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
     * The task in the shape the platform's own checks read it.
     *
     * @return The element id and the task definition of this task
     */
    public BpmnTaskSpec toSpec() {

      return new BpmnTaskSpec(activityId, taskDefinition);

    }

  }

  /**
   * The job-worker tasks of the given executable process (including tasks inside
   * embedded subprocesses). Tasks without a <code>zeebe:taskDefinition</code> get a
   * <code>null</code> task definition - reported by the wiring validation with a
   * guiding message. A business rule task calling a decision
   * (<code>zeebe:calledDecision</code>) is none of them: the cluster evaluates it.
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
          // a business rule task calling a DECISION is served by the cluster, not by a
          // job worker of the application: the decision was deployed with this process,
          // and asking for a @WorkflowTask method would make DMN unusable here. A
          // business rule task carrying a task definition is an ordinary VanillaBP task
          if (task.getSingleExtensionElement(
              io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledDecision.class) != null) {
            return;
          }
          // an element built from an element template names a job type somebody else's
          // runtime subscribes to, so this application neither validates nor serves it
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
   * The V1-compatible job-type prefix of user-task listeners: the listener type is
   * this prefix plus the user task's external form reference. MUST NOT change. It is the
   * name a worker subscribes to, and a version-1 application brings workflows whose user
   * tasks carry listener jobs of the old name: a prefix of our own would leave those jobs
   * to nobody.
   */
  public static final String TASKDEFINITION_USERTASK_ZEEBE = "io.vanillabp.userTask:";

  /**
   * The job-type prefix of the start execution listeners VanillaBP attaches to start
   * events the cluster fires on its own: this prefix plus the scoped BPMN
   * process id plus the start event's id. The job type has to be unique across the
   * tenant, hence the process id - and one worker serves exactly one start event.
   */
  public static final String TASKDEFINITION_BPMS_INITIATED_START = "io.vanillabp.bpmsStart:";

  /**
   * The job-type prefix of the end execution listener VanillaBP attaches to a
   * process whose application wants to be told that a workflow ended:
   * this prefix plus the scoped BPMN process id.
   */
  public static final String TASKDEFINITION_WORKFLOW_ENDED = "io.vanillabp.workflowEnd:";

  /**
   * The job type of a process' end execution listener, built from the prefix above so the
   * worker and the model agree on one string.
   *
   * @param scopedBpmnProcessId The BPMN process id the cluster knows
   * @return The job type of the process' end execution listener
   */
  public static String workflowEndedJobTypeOf(
      final String scopedBpmnProcessId) {

    return TASKDEFINITION_WORKFLOW_ENDED + scopedBpmnProcessId;

  }

  /**
   * The element id this adapter reserved for the probe which asks the engine whether it
   * holds a process instance.
   * <p>
   * The probe is a process instance modification naming an element the model does not have:
   * the cluster refuses it and the refusal is the answer, so nothing about the workflow
   * changes. An id which by accident matches an element of the model would be ACTIVATED
   * instead, which is a change to a running workflow nobody asked for - so the models are
   * read for it while they are deployed and a process which carries it gets no probe, see
   * decision 35 in the repository's DECISIONS.md.
   */
  public static final String RESERVED_PROBE_ELEMENT_ID = "vanillabp-existence-probe";

  /**
   * Whether the given model carries the element id
   * {@link #RESERVED_PROBE_ELEMENT_ID reserved for the probe}.
   * <p>
   * The whole FILE is read rather than the one process: a file is the deployment unit, an
   * id is unique within it, and being one process too careful costs a search where being
   * one too few costs a modification of a running workflow.
   *
   * @param model The BPMN model
   * @return Whether anything in it carries the reserved id
   */
  public static boolean carriesTheReservedProbeElement(
      final BpmnModelInstance model) {

    return model.getModelElementById(RESERVED_PROBE_ELEMENT_ID) != null;

  }

  /**
   * The version tag the modeller gave the process
   * (<code>zeebe:versionTag</code>) - the name a
   * <code>&#64;WorkflowTask(version = "release-2026")</code> refers to.
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
   * Attaches an <code>end</code> execution listener to the PROCESS element, which
   * is what tells VanillaBP that a workflow ended. Only called where the
   * application declared a <code>&#64;WorkflowEnded</code> method: a model must not
   * pay for a notification nobody asked for.
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
    // LAST listener: whatever the model itself does at the end of the process runs
    // before the application is told the workflow ended
    listeners.addChildElement(listener);
    return true;

  }

  /**
   * Attaches a <code>cancel</code> execution listener to the PROCESS element, which is what
   * tells VanillaBP that an instance was terminated. It carries the SAME job type as the
   * end listener next to it, so one worker answers both and the handler tells the two apart
   * by the event the job reports.
   * <p>
   * The construct belongs to the 8.10 line, so the caller asks
   * {@link Camunda8CancelListeners#theProcessCanReportItsCancellation()} first. It is also
   * the caller's business that a worker is opened for the job type: a listener nobody serves
   * holds the instance until its job is answered, which turns a cancelation into a workflow
   * which never goes away and which raises no incident either.
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

    // the retries of the end listener, which are the model's default: a failed
    // notification is failed with one attempt less and the last failure raises the
    // incident an operator acts on
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
   * Whether the process already carries that listener, which is what re-wiring an
   * already-processed model meets.
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
   * the model spells it. Written as the word rather than as the client's constant, because
   * the constant arrived with the 8.10 client and this file is compiled on every line.
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
     * The job type of this start event's execution listener.
     *
     * @return The job type of this start event's execution listener
     */
    public String listenerJobType() {

      return listenerJobTypeOf(bpmnProcessId, startEventId);

    }

  }

  /**
   * The job type of a start event's execution listener. One worker serves one start event, so
   * the element id is part of the job type.
   *
   * @param scopedBpmnProcessId The BPMN process id the cluster knows
   * @param startEventId The BPMN id of the start event
   * @return The job type of the start event's execution listener
   */
  public static String listenerJobTypeOf(
      final String scopedBpmnProcessId,
      final String startEventId) {

    return "%s%s:%s".formatted(TASKDEFINITION_BPMS_INITIATED_START, scopedBpmnProcessId, startEventId);

  }

  /**
   * EVERY start event of the given executable process - and attaches an execution listener
   * to each of them, which is how VanillaBP learns about a start and gets to decide what it
   * means before anything else of the process runs.
   * <p>
   * The plain and the message start event are among them. What a start MEANS is read from
   * the state of the workflow and not from the kind of its start event, so a workflow the
   * application started is told apart from one somebody started past VanillaBP by the name
   * the cluster holds for it, whichever event began it - see
   * {@code DECISIONS.pending/653.md}. Camunda 8 has no conditional events at all; the kind
   * is part of the model here so an unsupported model fails at the cluster, not silently.
   * <p>
   * Only the start events the process itself holds are read. An event subprocess starts
   * no workflow, which {@link #startsTheWorkflow(StartEvent)} says more about.
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
   * The start events the CLUSTER fires on its own in a model it already runs - read for
   * checks about versions an earlier application deployed, never for deploying. Nothing
   * is added here: the cluster accepted the model as it stands and carries the execution
   * listener of the deployment which brought it, so a read on its behalf leaves it
   * exactly as it is.
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
   * Attaches a <code>start</code> execution listener to the start event, unless the
   * model already carries it (re-wiring an already-processed model). Retries stay at
   * the Camunda default: unlike the user-task listeners, a failure here means the
   * workflow has no aggregate, which is worth retrying before it becomes an incident.
   * <p>
   * What it costs the model is one <code>zeebe:executionListeners</code> element per start
   * event, which is what {@code Camunda8StartListenerCostTest} measures.
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
      // a start event carrying no extension elements at all: the container has to
      // be created before a listener can be added to it
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
    // 'end' on the start event, not 'start': the cluster rejects start execution
    // listeners on start events (8.8), while an end listener still gates the
    // transition - it runs before the flow leaves the start event, so nothing of
    // the process can run before the workflow aggregate exists
    listener
        .setEventType(ZeebeExecutionListenerEventType.end);
    listener.setType(listenerJobType);
    // the workflow aggregate is built here: VanillaBP's listener has to run before
    // any listener the model brings along
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
     * The user task in the shape the platform's own checks read it.
     *
     * @return The element id and the task definition of this user task
     */
    public BpmnTaskSpec toSpec() {

      return BpmnTaskSpec.userTask(activityId, externalFormReference);

    }

    /**
     * The job type of this user task's lifecycle listeners.
     *
     * @return That job type
     */
    public String listenerJobType() {

      return TASKDEFINITION_USERTASK_ZEEBE + externalFormReference;

    }

  }

  /**
   * The job type version 1 served its user tasks with up to release 1.6.3, when a user
   * task was a job-worker user task rather than a Camunda-managed one. Version 2 opens
   * no worker on it, which is what {@link #legacyUserTaskIdsOf} exists to report.
   */
  public static final String TASKDEFINITION_USERTASK_WORKER_V1 = "io.camunda.zeebe:userTask";

  /**
   * The user tasks of the given executable process which are still modelled the way
   * VanillaBP 1 modelled them up to its release 1.6.3: a plain BPMN user task, served by
   * a job worker on {@value #TASKDEFINITION_USERTASK_WORKER_V1}, whose task definition
   * was the <code>zeebe:formDefinition</code> <strong>formKey</strong>. Release 1.7.0
   * replaced that with a Camunda-managed user task carrying the lifecycle listeners this
   * version wires, and it kept serving the old shape alongside; this version does not.
   * <p>
   * Such a user task falls through everything else silently, which is why it is looked
   * for on purpose: {@link #tasksOf} reads service-like tasks only, and
   * {@link #userTasksOf} skips anything without a <code>zeebe:userTask</code>. So it is
   * neither wired nor reported, the model deploys, the workflow runs - and VanillaBP
   * never learns that the task was created, while
   * <code>ProcessService#completeUserTask</code> cannot complete it either, because the
   * id such a task hands out is a job key and the cluster expects a user-task key.
   *
   * @param model The BPMN model of one file
   * @param bpmnProcessId The process id as the CLUSTER will know it
   * @return The element ids, empty where the model carries none
   */
  public static List<String> legacyUserTaskIdsOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    return model
        .getModelElementsByType(UserTask.class)
        .stream()
        .filter(task -> bpmnProcessId.equals(owningProcessId(task)))
        // a Camunda-managed user task is what this version serves, and a user task
        // carrying a zeebe:taskDefinition is the application's own job worker rather
        // than version 1's convention - neither is meant here
        .filter(task -> task.getSingleExtensionElement(ZeebeUserTask.class) == null)
        .filter(task -> task.getSingleExtensionElement(ZeebeTaskDefinition.class) == null)
        .filter(task -> namesAFormKey(task.getSingleExtensionElement(ZeebeFormDefinition.class)))
        .map(FlowElement::getId)
        .toList();

  }

  /**
   * Whether a form definition names version 1's formKey, which is what told that adapter
   * the user task's task definition.
   *
   * @param formDefinition The element's form definition, may be <code>null</code>
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
   * process, READ and nothing else: the model is not touched here.
   * <p>
   * A user task without an external form reference fails with a guiding message, because
   * the reference IS the task definition under VanillaBP's Camunda 8 convention and a user
   * task without one is a model this adapter cannot deploy. That is a question about the
   * model rather than a change to it, so it is asked here and the answer is the same
   * however often it is asked.
   * <p>
   * Public and separate from {@link #userTasksOf} because an extension wiring its own
   * listeners into the same file needs the same list. Calling the preparing method for it
   * would mean a second party writing lifecycle listeners into a model the adapter owns. The
   * adapter does run before every extension, so those listeners are already in the model when
   * an extension reads it; writing them a second time would change nothing and still belongs
   * to somebody else.
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
        // only Camunda-managed user tasks (zeebe:userTask, the 8.8 default);
        // worker-based user tasks (a zeebe:taskDefinition instead) are handled
        // like service tasks by tasksOf
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
   * {@link #readUserTasksOf} reports them, AND the V1-compatible lifecycle task listeners
   * written into the model: per user task a <code>creating</code> listener as the FIRST and
   * a <code>canceling</code> listener as the LAST listener (custom modeller-defined
   * listeners stay in between), both with <code>retries="1"</code> and the type
   * {@link #TASKDEFINITION_USERTASK_ZEEBE} + external form reference.
   * <p>
   * This is the deployment path and it CHANGES the model. A second call adds no second set
   * of listeners - a user task which already carries this listener job type is left as it
   * is, see {@code Camunda8UserTasksReadAndPrepareTest} - but a caller which only wants to
   * know what the model declares asks {@link #readUserTasksOf} instead, because being
   * harmless is not the same as being the caller's business.
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
   * The Camunda-managed user tasks of a model the CLUSTER already runs - read for
   * checks about versions an earlier application deployed, never for deploying.
   * Nothing is validated and nothing is added here: the cluster accepted the model
   * as it stands, and a check may not refuse what is being read on its behalf - a
   * model nobody can change any more. A user task without an external form
   * reference is read by version 1's convention instead (its formKey WAS the task
   * definition up to release 1.6.3), and one naming neither is skipped: VanillaBP
   * serves it under no convention, whatever the application does.
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
   * What the lifecycle listeners of a user task are modelled with.
   * <p>
   * Version 1 wrote <code>retries="0"</code> here, and that number is not only what a failed
   * notification has left. A gateway which cannot hand an activated batch to the request it
   * was activated for fails the job back to the broker WITH THE RETRIES IT HAD, so a job
   * modelled without one has nothing to be failed back with: the lost delivery becomes an
   * incident and the user task stands in <code>CREATING</code> for good. One retry is what
   * makes that job activatable again, which is the recovery this adapter documents for every
   * other job.
   * <p>
   * It does not give a failed notification a second attempt. The handler fails such a job
   * with no retries left whatever the model says, so the incident an operator acts on is
   * raised by the first failure as before - see
   * {@link Camunda8UserTaskListenerHandler} and
   * {@code Camunda8ShutdownHandlingTest#aListenerFailingIsReported}.
   * <p>
   * Why these are two numbers and not one: see decision 41 in the repository's DECISIONS.md.
   */
  private static final String ONE_ATTEMPT_LEFT_FOR_A_DELIVERY_THE_GATEWAY_LOST = "1";

  /**
   * V1 listener order per element: VanillaBP <code>creating</code> FIRST, any
   * custom listeners in between, VanillaBP <code>canceling</code> LAST. Listeners
   * already carrying the VanillaBP prefix are not duplicated (re-wiring an
   * already-processed model).
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
   * Wires the correlation keys of the given executable process' MESSAGE
   * subscriptions: publishing with
   * <code>correlationKey = workflow-aggregate ID</code> only correlates if the
   * deployed model's message subscriptions carry a matching
   * <code>zeebe:subscription</code> correlation-key expression. For every message
   * referenced by a catch element of this process WITHOUT such an expression the
   * V2 convention <code>=&lt;aggregate-ID variable&gt;</code> is INJECTED (the
   * engine-specific BPMN modification of the pipeline); an existing expression is
   * left untouched (the modeller may correlate by an own variable, e.g. for
   * correlation-id scenarios - V1 models keep working byte-identically). Message
   * START events need no correlation key and are skipped.
   * <p>
   * The injection is for a process the application serves a workflow of, and it is
   * called for no other: the variable it names is the workflow aggregate's, and a
   * process no <code>&#64;WorkflowService</code> class claims has no aggregate to name
   * one after. What such a process owes the cluster is asked BEFORE any of the file is
   * rewritten, by {@link #messagesWithoutACorrelationKey}, and the deployment refuses
   * the file over it rather than putting a substitute into a model it does not own.
   *
   * @param model The BPMN model (modified in place)
   * @param bpmnProcessId The executable process to wire
   * @param aggregateIdVariableName Supplies the name of the process variable
   *          holding the workflow-aggregate ID - asked ONCE, and only if an
   *          injection is actually necessary, so a process without message catch
   *          elements does not require the aggregate's ID property to be resolvable
   */
  public static void wireMessageSubscriptions(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final Supplier<String> aggregateIdVariableName) {

    final var messages = new LinkedHashSet<Message>();
    messageCatchElementsOf(model, bpmnProcessId)
        .map(MessageCatchElement::message)
        // the modeller correlates deliberately (e.g. by an own correlation-id
        // variable) - leave it untouched, V1 models stay byte-identical
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
   * Camunda 8 accepts no such element and rejects the whole FILE over one, so this is the
   * question to ask about a process the application serves no workflow of: there is no
   * workflow aggregate to name a correlation key after, and the deployment refuses the
   * file rather than inventing one. Asked while the model is still the one the modeller
   * wrote, so the answer does not depend on which process of a file was wired first - a
   * message element belongs to the FILE, and an injection for one process would otherwise
   * fill the gap of another.
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
   * message belongs to the FILE rather than to the process, so two processes of one file
   * can point at the same one.
   */
  private record MessageCatchElement(
                                     String elementId,
                                     Message message) {
  }

  /**
   * Every element of the given executable process which waits for a message: an
   * intermediate catch event, a boundary event or a receive task. A message START event
   * is not one of them, it correlates by name and needs no key.
   */
  private static Stream<MessageCatchElement> messageCatchElementsOf(
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
   * itself says which activities to run:
   * <code>zeebe:adHoc activeElementsCollection</code> is a FEEL expression over a process
   * variable, so an attribute of the workflow aggregate decides and the activities inside
   * the element are ordinary tasks with ordinary <code>&#64;WorkflowTask</code> methods
   * behind them. In the other, the element carries a <code>zeebe:taskDefinition</code> of
   * its own and a worker decides round by round which activities to activate, by
   * completing the job with a result naming them. Neither the
   * <code>&#64;WorkflowTask</code> contract nor the adapter SPI can express that outcome,
   * so this adapter opens no worker for such an element.
   * <p>
   * An element carrying a <code>zeebe:modelerTemplate</code> is left out, because then
   * somebody else's runtime owns it: the Camunda AI agent is an element template on
   * exactly this element, and a connector runtime subscribing to its job type serves it
   * without this application being involved. That is the same marker
   * {@link Camunda8Connectors} reads, and this method reads it through that class rather
   * than looking for the attribute a second time.
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
   * branches writing the same workflow aggregate - what that means is the core's
   * decision, this method only reads the model.
   * <p>
   * The ad-hoc subprocess is reported whichever flavour the model uses and however few
   * activities it looks as if it would run. Its
   * <code>zeebe:adHoc activeElementsCollection</code> is an expression evaluated when the
   * workflow enters the element, so a list which is one entry long today is two entries
   * long as soon as the data behind it changes, and the job worker flavour lets a worker
   * activate several elements in one result. A warning which appears only after such a
   * change is worse than one which appears always.
   * <p>
   * The activities INSIDE the element are not reported. The subprocess is where the
   * second token comes from, and naming five inner tasks instead of the one element
   * would make the warning unreadable.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The process' ID as the model knows it (the SCOPED ID)
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
   * already has its aggregate, so it starts no workflow. Counting it as one would refuse
   * every such model at startup, because an application has to serve each start of a
   * workflow with a <code>&#64;WorkflowStartedByBpms</code> method, and the execution
   * listener injected into it would ask the application for a second aggregate of a
   * workflow which already owns one.
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
