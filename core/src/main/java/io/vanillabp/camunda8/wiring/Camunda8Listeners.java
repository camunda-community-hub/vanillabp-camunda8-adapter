package io.vanillabp.camunda8.wiring;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.BaseElement;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListenerEventType;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListenerEventType;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskListeners;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;

/**
 * The listeners somebody MODELLED, and how this adapter tells them apart from the listeners
 * VanillaBP writes into the same model.
 *
 * <h2>What a modelled listener is</h2>
 *
 * A {@code zeebe:taskListener} of a Camunda-managed user task or a
 * {@code zeebe:executionListener} of any element. Both name a JOB TYPE, both produce a job
 * when the cluster reaches them, and a job type nothing subscribes to stops the workflow
 * right there without anything being logged. That silence is what this class exists for.
 *
 * <h2>How the adapter's own listeners are kept out</h2>
 *
 * Every listener VanillaBP writes carries a job type starting with
 * {@value #VANILLABP_JOB_TYPE_PREFIX}: the user-task lifecycle listeners, the start
 * listeners of the start events the cluster fires on its own, and the end listener of a
 * process whose end is reported. The Business Cockpit extension writes its own under
 * {@code io.vanillabp.businesscockpit:}, which that prefix covers as well. Beyond the
 * prefix the moment helps: a listener of an extension is not in the model yet while the
 * collection below walks it. The moment an extension reaches a model is VanillaBP's
 * promise, not this adapter's, and the wiki page
 * <a href="https://github.com/vanillabp/adapter-platform-integration/wiki/Extensions">Extensions</a>
 * says what is promised and what is not.
 * <p>
 * A third-party extension choosing a prefix of its own is not known here, and nothing can
 * ask for one. That is why the startup report names every listener this adapter treats as
 * the modeller's: a job type a reader does not recognise is the one line worth a second
 * look.
 */
public final class Camunda8Listeners {

  private Camunda8Listeners() {
  }

  /**
   * The property switching the whole rule on, without its adapter id.
   */
  public static final String ALLOW_LISTENERS_KEY = "allow-listeners";

  /**
   * The job-type prefix of every listener VanillaBP and its extensions write. A listener
   * carrying it belongs to the framework and is none of the application's business.
   */
  public static final String VANILLABP_JOB_TYPE_PREFIX = "io.vanillabp.";

  /**
   * Which kind of listener a modeller wrote, in the words the Camunda Modeler uses.
   */
  public enum Kind {
    /**
     * {@code zeebe:taskListener} of a Camunda-managed user task.
     */
    TASK_LISTENER("task listener"),
    /**
     * {@code zeebe:executionListener} of any element.
     */
    EXECUTION_LISTENER("execution listener");

    private final String described;

    Kind(
        final String described) {

      this.described = described;

    }

    /**
     * The kind in words, so a message reads like a sentence rather than like an enum.
     *
     * @return What a message calls this kind
     */
    public String described() {

      return described;

    }

  }

  /**
   * One listener of a model which this application is to serve with a
   * {@code @WorkflowTask} method.
   *
   * @param bpmnProcessId The BPMN process the element belongs to, as the application wrote
   *          it
   * @param elementId The BPMN element the listener sits on
   * @param kind Task listener or execution listener
   * @param event The listener's event, as the model spells it
   * @param taskDefinition The listener's job type, which is the task definition a
   *          {@code @WorkflowTask} method names
   */
  public record ModelledListener(
                                 String bpmnProcessId,
                                 String elementId,
                                 Kind kind,
                                 String event,
                                 String taskDefinition) {

    /**
     * The listener in one line, which is how the startup report and the guiding messages name
     * it.
     *
     * @return The listener as one line of a message
     */
    public String describe() {

      return "process '%s', element '%s', %s on '%s', job type '%s'"
          .formatted(bpmnProcessId, elementId, kind.described(), event, taskDefinition);

    }

  }

  /**
   * Whether this is a task listener on <code>updating</code> - the one event which fires on
   * the empty update this adapter probes a user task with.
   * <p>
   * The probe changes no attribute at all and the listener fires anyway, measured on 8.9 and
   * on 8.10. So an element carrying such a listener is only probed where a worker of this
   * application answers its job; anywhere else the probe would hold the task in
   * <code>UPDATING</code> for fifteen seconds. See decision 38 in the repository's
   * DECISIONS.md.
   * <p>
   * BOTH spellings count. The enum of the model API carries the old <code>update</code> next
   * to today's <code>updating</code>, on every line this adapter builds against, and a model
   * written with the old one produces the same job. Reading only the new spelling would send
   * the probe at exactly the element this question exists to keep it away from.
   *
   * @param listener The listener as the model carries it
   * @return Whether it is a task listener on <code>updating</code>
   */
  public static boolean isAnUpdatingTaskListener(
      final ModelledListener listener) {

    if (listener.kind() != Kind.TASK_LISTENER) {
      return false;
    }
    return ZeebeTaskListenerEventType.updating.name().equals(listener.event()) || ZeebeTaskListenerEventType.update
        .name().equals(listener.event());

  }

  /**
   * The adapter-level key which switches the rule on, spelled out for a message.
   *
   * @param adapterId The adapter ID
   * @return {@code vanillabp.adapters.<id>.allow-listeners}
   */
  public static String propertyKeyOf(
      final String adapterId) {

    return Camunda8AdapterConfiguration.propertyKey(adapterId, ALLOW_LISTENERS_KEY);

  }

  /**
   * The three levels the property is read at, as a copy-pasteable block for a message.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module id, or <code>&lt;m&gt;</code> where the
   *          message speaks about no particular module
   * @param bpmnProcessId The BPMN process id, or <code>&lt;w&gt;</code> where the message
   *          speaks about no particular process
   * @return Three indented lines, the least specific level first
   */
  public static String levelsOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return """
        vanillabp.adapters.%s.%s
        vanillabp.workflow-modules.%s.adapters.%s.%s
        vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.%s"""
        .formatted(
            adapterId,
            ALLOW_LISTENERS_KEY,
            workflowModuleId,
            adapterId,
            ALLOW_LISTENERS_KEY,
            workflowModuleId,
            bpmnProcessId,
            adapterId,
            ALLOW_LISTENERS_KEY);

  }

  /**
   * The line which opens and closes the startup report. Plain ASCII, for the reason
   * {@link Camunda8Connectors#FRAME_LINE} gives, and the same line: the two reports are
   * read in the same boot log and a second width would look like a second kind of message.
   */
  public static final String FRAME_LINE = Camunda8Connectors.FRAME_LINE;

  /**
   * What an application gives up while its modelled listeners are served, in the words of
   * the startup report. Kept here because the same sentences belong into the refusal a boot
   * WITHOUT the property writes, and a reader must meet them in both places unchanged.
   * <p>
   * Word for word what the Camunda 7 adapter says, because the sentences are about
   * VanillaBP rather than about a cluster, and a developer moving a module between the two
   * must not have to work out whether two wordings mean the same thing.
   */
  public static final String WHAT_IT_COSTS = """
      A listener is where a BPMS lets an application in at a moment the BPMS owns, and every \
      BPMS draws that moment differently. So the model stops being portable: another BPMS has \
      no listener at this element, and a migration of the model stops at the method serving it. \
      The Process-Engine-API has no listener concept at all, which is gap 16 and 17 of that \
      adapter's GAPS.md. The event is part of a listener's identity here, so one @WorkflowTask \
      method serves one event of one element - and @TaskEvent tells such a method nothing, \
      because TaskEvent.Event has no value for a listener's event.""";

  /**
   * The sentences only Camunda 8 can say, and the reason the report carries more than
   * {@link #WHAT_IT_COSTS}: what a listener may write into the process instance depends on the
   * kind of listener and on its event. Measured against cluster and client 8.9.19 in September
   * 2026; {@link Camunda8ModelledListenerHandler} is where the three cases are implemented.
   */
  public static final String WHAT_CAMUNDA8_ADDS = """
      What a listener method may write into the process instance depends on the listener. An \
      execution listener on 'end' completes the way a task completes: the shared values of your \
      workflow aggregate reach the instance, so a gateway behind the element decides on what the \
      method wrote. An execution listener on 'start' writes nothing there, because the cluster \
      would keep those values local to the element and the element's own task would then lose \
      its writes into that copy - model a task of the process where something has to be written. \
      A task listener writes nothing either: the cluster refuses a completion carrying variables \
      and names its issue 23702 while doing so. What your method changed is kept by your \
      application in both cases and reaches the cluster at the next real sync point of that \
      workflow.""";

  /**
   * The sentence about the one ambiguity this design leaves, said wherever listeners are
   * named: an element may carry two listeners, and the element id names the element.
   */
  public static final String WHICH_METHOD_SERVES_WHICH = """
      A method serves a listener by naming its TASK DEFINITION and in no other way. \
      @WorkflowTask(id = ...) names the ELEMENT, and one element may carry a task and a listener \
      at once, so the element id cannot say which of them a method means. A listener no method \
      names is not served here: whatever else resolves it keeps resolving it.""";

  /**
   * Reports keys the application set at TASK level, where this one does not resolve, and
   * lets the boot go on: the value changes no answer, and ending a boot over a key which
   * simply does nothing would be worse than saying so.
   *
   * @param adapterId The adapter ID
   * @param keysAtTaskLevel The keys found, fully spelled out; nothing is reported for an
   *          empty list
   * @param warnLogger Where the guidance goes
   */
  public static void reportKeysSetAtTaskLevel(
      final String adapterId,
      final List<String> keysAtTaskLevel,
      final Consumer<String> warnLogger) {

    if (keysAtTaskLevel.isEmpty()) {
      return;
    }
    warnLogger.accept(
        """
            Camunda 8 adapter '%s' has '%s' set at TASK level: %s. That level does not resolve this \
            key and the value changes nothing. A task level is keyed by a task DEFINITION, and \
            whether a modelled listener becomes a task at all is what this key decides - so the \
            task definition such a level would need does not exist yet at the moment the key is \
            read. The key is read at these three levels, the most specific configured one winning:
            %s"""
            .formatted(
                adapterId,
                ALLOW_LISTENERS_KEY,
                String.join(", ", keysAtTaskLevel),
                levelsOf(adapterId, "<m>", "<w>")));

  }

  /**
   * Every listener of one process which somebody modelled, in document order.
   * <p>
   * Read off the model rather than off a list of element types, so a listener reaches this
   * wherever the cluster lets one sit. A listener of VanillaBP or of one of its extensions
   * is not among them, see the class comment.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The process id as it stands in the model at this point
   * @return The listeners, in document order
   */
  public static List<ModelledListener> listenersOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    final var found = new LinkedList<ModelledListener>();
    for (final var listeners : model.getModelElementsByType(ZeebeTaskListeners.class)) {
      final var element = Camunda8Connectors.owningElementOf(listeners);
      if (!belongsTo(element, bpmnProcessId)) {
        continue;
      }
      for (final var listener : listeners.getTaskListeners()) {
        if (isVanillaBpsOwn(listener.getType())) {
          continue;
        }
        found.add(new ModelledListener(
            bpmnProcessId, element.getId(), Kind.TASK_LISTENER, String
                .valueOf(listener.getEventType()), listener.getType()));
      }
    }
    for (final var listeners : model.getModelElementsByType(ZeebeExecutionListeners.class)) {
      final var element = Camunda8Connectors.owningElementOf(listeners);
      if (!belongsTo(element, bpmnProcessId)) {
        continue;
      }
      for (final var listener : listeners.getExecutionListeners()) {
        if (isVanillaBpsOwn(listener.getType())) {
          continue;
        }
        found.add(new ModelledListener(
            bpmnProcessId, element.getId(), Kind.EXECUTION_LISTENER, String
                .valueOf(listener.getEventType()), listener.getType()));
      }
    }
    return found;

  }

  /**
   * The execution listeners of one process which the CLUSTER refuses: a {@code start} listener
   * on a start event. Verified against 8.8, which is why VanillaBP attaches its own listener to
   * a start event on {@code end} instead.
   * <p>
   * Worth its own answer because the cluster refuses the FILE, not the element, so every process
   * the file declares is lost and the message comes from the cluster rather than from anything a
   * modeller can read.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The process id as it stands in the model at this point
   * @return The listeners, in document order
   */
  public static List<ModelledListener> listenersTheClusterRefuses(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    final var found = new LinkedList<ModelledListener>();
    for (final var listeners : model.getModelElementsByType(ZeebeExecutionListeners.class)) {
      final var element = Camunda8Connectors.owningElementOf(listeners);
      if (!belongsTo(element, bpmnProcessId) || !(element instanceof io.camunda.zeebe.model.bpmn.instance.StartEvent)) {
        continue;
      }
      for (final var listener : listeners.getExecutionListeners()) {
        if (!ZeebeExecutionListenerEventType.start.equals(listener.getEventType())) {
          continue;
        }
        found.add(new ModelledListener(
            bpmnProcessId, element.getId(), Kind.EXECUTION_LISTENER, String
                .valueOf(listener.getEventType()), listener.getType()));
      }
    }
    return found;

  }

  /**
   * Whether a job type belongs to VanillaBP or to one of its extensions.
   *
   * @param jobType The listener's job type, may be <code>null</code> for a listener a
   *          modeller left unfinished
   * @return Whether the framework owns it
   */
  public static boolean isVanillaBpsOwn(
      final String jobType) {

    return (jobType != null) && jobType.startsWith(VANILLABP_JOB_TYPE_PREFIX);

  }

  /**
   * The listeners of one element which share a job type, which is the one case a served
   * listener cannot be wired in: one method would serve two events and nothing could tell
   * it which one it is being called for.
   *
   * @param listeners The listeners of one process
   * @return Per element and job type the listeners sharing it, only where there is more
   *         than one
   */
  public static List<List<ModelledListener>> listenersSharingAJobType(
      final List<ModelledListener> listeners) {

    final var byElementAndJobType = new LinkedHashMap<String, List<ModelledListener>>();
    listeners
        .forEach(listener -> byElementAndJobType
            .computeIfAbsent(
                listener.elementId()
                    + "|"
                    + listener.taskDefinition(),
                key -> new LinkedList<>())
            .add(listener));
    return byElementAndJobType
        .values()
        .stream()
        .filter(sharing -> sharing.size() > 1)
        .collect(Collectors.toList());

  }

  /**
   * Whether an element belongs to the given process.
   *
   * @param element The element carrying the listeners, may be <code>null</code>
   * @param bpmnProcessId The process id
   * @return Whether the element is part of that process
   */
  private static boolean belongsTo(
      final BaseElement element,
      final String bpmnProcessId) {

    if (element == null) {
      return false;
    }
    org.camunda.bpm.model.xml.instance.ModelElementInstance current = element;
    while (current != null) {
      if ((current instanceof Process process) && bpmnProcessId.equals(process.getId())) {
        return true;
      }
      current = current.getParentElement();
    }
    return false;

  }

}
