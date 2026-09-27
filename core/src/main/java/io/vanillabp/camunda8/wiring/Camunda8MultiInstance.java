package io.vanillabp.camunda8.wiring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.Activity;
import io.camunda.zeebe.model.bpmn.instance.BaseElement;
import io.camunda.zeebe.model.bpmn.instance.CallActivity;
import io.camunda.zeebe.model.bpmn.instance.ExtensionElements;
import io.camunda.zeebe.model.bpmn.instance.FlowElement;
import io.camunda.zeebe.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeCalledElement;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeInput;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeIoMapping;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeLoopCharacteristics;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import lombok.extern.slf4j.Slf4j;

/**
 * What Camunda 8 knows about the iteration a job belongs to, made available to the
 * core.
 *
 * <p>
 * Camunda 7 answers this from the execution hierarchy at runtime. Camunda 8 has no
 * such hierarchy on the client side: a job carries variables and an element ID, and
 * nothing else. Of the three values the SPI asks for, exactly one is there by
 * itself:
 * </p>
 * <ul>
 * <li><strong>the element</strong> is the variable named by
 * <code>inputElement</code>, but a NESTED iteration using the same name shadows the
 * outer one;</li>
 * <li><strong>the index</strong> is <code>loopCounter</code>, counted from 1 rather
 * than from 0, and shadowed the same way;</li>
 * <li><strong>the total</strong> does not exist at all - this engine has no
 * <code>nrOfInstances</code>, and it has no loop cardinality either, so a
 * multi-instance element always iterates over a collection.</li>
 * </ul>
 *
 * <p>
 * So the values are made unambiguous while the BPMN is deployed, which is the stage
 * VanillaBP modifies models anyway: every multi-instance element gets input mappings
 * copying its own index, its own total and its own element into variables named
 * after that element. Those names cannot be shadowed, and a job of a task nested in
 * three iterations sees all three of them. What it costs is three local variables per
 * instance, all of them scalar except the element, which the model was handing over
 * anyway.
 * </p>
 *
 * <p>
 * The chain of multi-instance elements enclosing a task is model knowledge, so it is
 * collected while deploying and kept per process and element - a job carries the ID
 * of its own element only.
 * </p>
 * <p>
 * A called process is part of the same chain. The cluster copies the variables of every
 * scope a call activity sits in into the called instance, so the values are there; what
 * the model does not say is which iterations they belong to, because a process does not
 * know who calls it. That link is read off the call activities of the CALLERS once every
 * process of a workflow module is wired, see {@link Registry#registerCall} and
 * {@link Registry#linkCalledProcesses()}.
 * </p>
 * <p>
 * Why the adapter puts input mappings into the deployed model, why they are idempotent, and why
 * that costs a new process version, is decision 5 in the repository's DECISIONS.md. The rules
 * the chain of a called process follows are decision 30 in the repository's DECISIONS.md.
 */
@Slf4j
public final class Camunda8MultiInstance {

  /** Prefix of every variable this class injects. */
  static final String VARIABLE_PREFIX = "vanillabpMi";

  /**
   * The attribute of {@code zeebe:calledElement} which decides whether the variables of
   * the enclosing scopes reach the called instance. Read off the DOM rather than through
   * {@code isPropagateAllParentVariablesEnabled()}, because the model API answers the
   * DEFAULT where the attribute is absent, and absent is the one case which may be written.
   */
  private static final String PROPAGATE_ALL_PARENT_VARIABLES = "propagateAllParentVariables";

  private Camunda8MultiInstance() {
  }

  /**
   * One multi-instance element and the variables carrying what it knows about the
   * current iteration.
   *
   * @param elementId The BPMN ID of the multi-instance element - the key the SPI
   *          uses, and what the application writes into
   *          <code>@MultiInstanceElement("...")</code>
   * @param indexVariable The variable holding this element's <code>loopCounter</code>
   * @param totalVariable The variable holding the size of this element's collection,
   *          or <code>null</code> if the element has no input collection
   * @param elementVariable The variable holding this element's current element, or
   *          <code>null</code> if the model declares no <code>inputElement</code>
   */
  public record MultiInstanceElement(
                                     String elementId,
                                     String indexVariable,
                                     String totalVariable,
                                     String elementVariable) {
  }

  /**
   * Which multi-instance elements enclose a BPMN element, outermost first. Filled
   * while deploying, read while dispatching a job.
   */
  public static class Registry {

    /**
     * Opens an empty registry. The deployment service of one adapter id fills it while it wires
     * that adapter's models.
     */
    public Registry() {
    }

    private final Map<String, List<MultiInstanceElement>> chains = new ConcurrentHashMap<>();

    /**
     * Where a process is called from, by called process. A process may be called from
     * several places, which is what makes the chain a union.
     */
    private final Map<String, Set<CallSite>> callSites = new ConcurrentHashMap<>();

    /**
     * What a process inherits from the places it is called from, outermost first -
     * computed from {@link #callSites} by {@link #linkCalledProcesses()} rather than on
     * every job.
     */
    private final Map<String, List<MultiInstanceElement>> inheritedChains = new ConcurrentHashMap<>();

    /**
     * One place a process is called from.
     *
     * @param callerBpmnProcessId The calling process, as the CLUSTER knows it
     * @param callActivityId The BPMN ID of the call activity in that process
     */
    private record CallSite(String callerBpmnProcessId, String callActivityId) {
    }

    /**
     * One multi-instance element together with the process declaring it - which is what a
     * message about two elements of one ID has to name.
     */
    private record Level(String bpmnProcessId, MultiInstanceElement element) {
    }

    private static String key(
        final String bpmnProcessId,
        final String elementId) {

      return bpmnProcessId
          + "#"
          + elementId;

    }

    void register(
        final String bpmnProcessId,
        final String elementId,
        final List<MultiInstanceElement> chain) {

      chains.put(key(bpmnProcessId, elementId), List.copyOf(chain));

    }

    /**
     * Remembers that one process calls another one. Recorded for a call activity naming
     * its process statically and calling a process on the same workflow aggregate; the
     * caller decides both, because neither question is answered by this class.
     *
     * @param callerBpmnProcessId The calling process, as the CLUSTER knows it
     * @param callActivityId The BPMN ID of the call activity
     * @param calledBpmnProcessId The called process, as the CLUSTER knows it
     */
    public void registerCall(
        final String callerBpmnProcessId,
        final String callActivityId,
        final String calledBpmnProcessId) {

      callSites
          .computeIfAbsent(calledBpmnProcessId, called -> ConcurrentHashMap.newKeySet())
          .add(new CallSite(callerBpmnProcessId, callActivityId));

    }

    /**
     * Works out what every called process inherits from the places it is called from.
     * Called once the processes of a workflow module are wired, because a call activity
     * of one file may name a process of another one.
     *
     * @throws IllegalStateException Where two call sites of one process carry multi-instance
     *           elements of one ID which do not mean the same thing
     */
    public void linkCalledProcesses() {

      final var linked = new LinkedHashMap<String, List<MultiInstanceElement>>();
      for (final var calledProcess : new TreeSet<>(callSites.keySet())) {
        final var levels = inheritedBy(calledProcess, List.of());
        if (!levels.isEmpty()) {
          linked
              .put(
                  calledProcess,
                  levels
                      .stream()
                      .map(Level::element)
                      .toList());
        }
      }
      inheritedChains.putAll(linked);

    }

    /**
     * The levels a process inherits from its call sites, outermost first.
     * <p>
     * Each call site contributes one path: what the CALLER inherits, followed by the
     * multi-instance elements enclosing the call activity in the caller. The paths are
     * then merged into one list, because a job reports an element and not the way its
     * instance was reached. A level of a path which did not run is simply not in the job,
     * and {@link Camunda8MultiInstance#valuesOf} leaves it out.
     *
     * @param bpmnProcessId The process asked about
     * @param path The processes already walked through, so a call graph with a cycle ends
     *          with the levels collected so far
     */
    private List<Level> inheritedBy(
        final String bpmnProcessId,
        final List<String> path) {

      if (path.contains(bpmnProcessId)) {
        // a process calling itself, directly or around a corner: the chain of an element
        // in it would grow with every round, so the walk stops here
        return List.of();
      }
      final var sites = callSites.get(bpmnProcessId);
      if ((sites == null) || sites.isEmpty()) {
        return List.of();
      }
      final var walked = new ArrayList<>(path);
      walked.add(bpmnProcessId);
      final var merged = new LinkedHashMap<String, Level>();
      final var ordered = new ArrayList<>(sites);
      // the union is built in a stable order, so two boots of one application report the
      // levels of a process called from several places the same way round
      ordered
          .sort(
              Comparator
                  .comparing(CallSite::callerBpmnProcessId)
                  .thenComparing(CallSite::callActivityId));
      for (final var site : ordered) {
        final var alongThisPath = new LinkedHashMap<String, Level>();
        inheritedBy(site.callerBpmnProcessId(), walked)
            .forEach(level -> appendLevel(alongThisPath, level));
        chains
            .getOrDefault(key(site.callerBpmnProcessId(), site.callActivityId()), List.of())
            .forEach(element -> appendLevel(alongThisPath, new Level(site.callerBpmnProcessId(), element)));
        alongThisPath.values().forEach(level -> mergeLevel(merged, level, bpmnProcessId));
      }
      return List.copyOf(merged.values());

    }

    /**
     * Adds one level to ONE call path, where a repeated element ID is nesting rather than
     * a choice: both write the same variables, the inner scope overwrites the outer one,
     * and the job therefore carries the inner values. So the later occurrence replaces the
     * earlier one and takes its place at the end. A process calling itself is where this
     * happens by design.
     */
    private static void appendLevel(
        final Map<String, Level> levels,
        final Level level) {

      levels.remove(level.element().elementId());
      levels.put(level.element().elementId(), level);

    }

    /**
     * Adds one level to the union over the call paths, where a repeated element ID is a
     * CHOICE: only one of the paths reached the instance at hand, so one entry serves both
     * as long as both mean the same thing. Where they do not, nobody can say what
     * <code>@MultiInstanceElement</code> of that ID means, and the boot ends here.
     * <p>
     * A level two paths share keeps the place the first path gave it. Where those two paths
     * nest the same two IDs the other way round, one of the two orders is therefore the one
     * reported. Call paths are walked in a fixed order, so the answer is at least the same
     * after a restart.
     */
    private static void mergeLevel(
        final Map<String, Level> levels,
        final Level level,
        final String calledBpmnProcessId) {

      final var alreadyThere = levels.putIfAbsent(level.element().elementId(), level);
      if ((alreadyThere == null) || alreadyThere.element().equals(level.element())) {
        return;
      }
      throw new IllegalStateException(
          """
              Two multi-instance elements named '%s' reach the BPMN process '%s' from the places it \
              is called: the one in '%s' hands over %s, the one in '%s' hands over %s. Both write the \
              variable '%s', so a @MultiInstanceElement("%s") of a task in '%s' cannot say which of \
              the two it means. Rename one of the two elements."""
              .formatted(
                  level.element().elementId(),
                  calledBpmnProcessId,
                  alreadyThere.bpmnProcessId(),
                  shapeOf(alreadyThere.element()),
                  level.bpmnProcessId(),
                  shapeOf(level.element()),
                  level.element().indexVariable(),
                  level.element().elementId(),
                  calledBpmnProcessId));

    }

    /**
     * How a multi-instance element looks to a handler - what tells two elements of one ID
     * apart in a message.
     */
    private static String shapeOf(
        final MultiInstanceElement element) {

      final var parts = new ArrayList<String>();
      parts.add("an index");
      if (element.totalVariable() != null) {
        parts.add("a total");
      }
      if (element.elementVariable() != null) {
        parts.add("an element");
      }
      final var last = parts.remove(parts.size() - 1);
      return parts.isEmpty()
          ? last
          : String.join(", ", parts)
              + " and "
              + last;

    }

    /**
     * The multi-instance elements enclosing a BPMN element, outermost first.
     * <p>
     * Where the process is called by another one, the chain begins with what the call
     * sites enclose. A process called from SEVERAL places gets the union of those levels:
     * each call path keeps its own order, a level two paths share appears once, and the
     * runtime drops whatever is not in the job, which is what
     * {@link Camunda8MultiInstance#valuesOf} does for a level whose index variable is
     * missing. So a chain may name more levels than the instance at hand ran in, and a
     * handler still sees exactly the iterations it is inside of.
     * <p>
     * A call activity naming its process by an expression is not part of this, and neither
     * is one calling a process with a workflow aggregate of its own. A task in such a
     * process reports no iteration of its caller, although the cluster copies the values
     * into the instance.
     *
     * @param bpmnProcessId The BPMN process ID as the CLUSTER knows it
     * @param elementId The BPMN element ID the job reports
     * @return The multi-instance elements enclosing that element, outermost first
     */
    public List<MultiInstanceElement> chainOf(
        final String bpmnProcessId,
        final String elementId) {

      final var own = chains.getOrDefault(key(bpmnProcessId, elementId), List.of());
      final var inherited = inheritedChains.getOrDefault(bpmnProcessId, List.of());
      if (inherited.isEmpty()) {
        return own;
      }
      if (own.isEmpty()) {
        return inherited;
      }
      final var ownIds = own
          .stream()
          .map(MultiInstanceElement::elementId)
          .collect(Collectors.toSet());
      final var complete = inherited
          .stream()
          // an ID this process uses itself writes the same variables in a scope further in,
          // so what arrived from the call site is not in the job any more
          .filter(element -> !ownIds.contains(element.elementId()))
          .collect(Collectors.toCollection(ArrayList::new));
      complete.addAll(own);
      return List.copyOf(complete);

    }

  }

  /**
   * Prepares a process for multi-instance: injects the input mappings which make the
   * values of every iteration unambiguous and records which elements are enclosed by
   * which iterations. For a model which is only read see {@link #chainsOf}.
   *
   * @param model The BPMN model, about to be deployed
   * @param bpmnProcessId The process to prepare, as the cluster will know it
   * @param registry Where the chains are recorded
   */
  public static void wire(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final Registry registry) {

    collectChains(model, bpmnProcessId, registry, true);

  }

  /**
   * The chains of a model which is only being READ, without touching it: a model a BPMS
   * still holds is not deployed again, and injecting mappings into it would change what the
   * check judges.
   * <p>
   * A model whose element ids cannot be told apart by their variable names is refused while
   * it is DEPLOYED. Here it is not: nobody can change a version the cluster already holds, so
   * the answer is that this adapter cannot read the shape of that version, and the core asks
   * nothing about it.
   *
   * @param model The BPMN model of a version the cluster holds
   * @param bpmnProcessId The process to read, as the cluster knows it
   * @return The chains of that model, or <code>null</code> where it cannot be read
   */
  public static Registry chainsOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    final var registry = new Registry();
    try {
      collectChains(model, bpmnProcessId, registry, false);
    } catch (final RuntimeException e) {
      return null;
    }
    return registry;

  }

  /**
   * Records which elements are enclosed by which iterations, and - for a model about to be
   * deployed - injects the input mappings which make the values of every iteration
   * unambiguous.
   */
  private static void collectChains(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final Registry registry,
      final boolean inject) {

    final var process = model
        .getModelElementsByType(Process.class)
        .stream()
        .filter(candidate -> bpmnProcessId.equals(candidate.getId()))
        .findFirst()
        .orElse(null);
    if (process == null) {
      return;
    }

    final var elements = new LinkedHashMap<String, MultiInstanceElement>();
    final var variableNames = new LinkedHashMap<String, String>();
    for (final var activity : process.getChildElementsByType(Activity.class)) {
      collect(activity, elements, variableNames, bpmnProcessId, inject);
    }
    // no multi-instance in this process - nothing to inject and nothing to remember
    if (elements.isEmpty()) {
      return;
    }

    for (final var flowElement : process.getModelInstance().getModelElementsByType(FlowElement.class)) {
      if (!bpmnProcessId.equals(owningProcessId(flowElement))) {
        continue;
      }
      final var chain = chainOf(flowElement, elements);
      if (!chain.isEmpty()) {
        registry.register(bpmnProcessId, flowElement.getId(), chain);
      }
    }

  }

  /**
   * The call activities of a process which say STATICALLY which process they call.
   * <p>
   * A call activity naming its process by an expression is left out: which process it
   * reaches is decided per instance, and the chain is model knowledge. The same limit
   * applies to the scoped identifiers of decision 2 in the repository's DECISIONS.md and to
   * the workflow viewer, which read this attribute the same way.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The process to read, as the cluster will know it
   * @return The called process per call activity ID, in model order
   */
  public static Map<String, String> calledProcessesOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    final var called = new LinkedHashMap<String, String>();
    for (final var callActivity : model.getModelElementsByType(CallActivity.class)) {
      if (!bpmnProcessId.equals(owningProcessId(callActivity))) {
        continue;
      }
      final var calledProcessId = staticallyCalledProcessId(callActivity);
      if (calledProcessId != null) {
        called.put(callActivity.getId(), calledProcessId);
      }
    }
    return called;

  }

  /**
   * Whether the variables of the scopes a call activity sits in reach the called instance,
   * writing that into the model where the model says nothing about it.
   * <p>
   * Leaving the attribute out means the same thing today, so writing it changes no
   * behaviour on any cluster shipping now; what it does is write down what the chain of a
   * called process relies on, so a later default of the engine cannot take it away quietly.
   * Where the model says <code>false</code> the modeller switched the caller's context off
   * on purpose. That is left alone, because nothing the application modelled itself is
   * overwritten, which is decision 5 in the repository's DECISIONS.md, and the answer is
   * then <code>false</code>: the values never arrive, so there is no iteration to report
   * either.
   *
   * @param model The BPMN model, about to be deployed
   * @param bpmnProcessId The process holding the call activity
   * @param callActivityId The call activity
   * @return Whether the caller's variables reach the called process
   */
  public static boolean theCallersVariablesReachTheCalledProcess(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final String callActivityId) {

    for (final var callActivity : model.getModelElementsByType(CallActivity.class)) {
      if (!callActivityId.equals(callActivity.getId()) || !bpmnProcessId.equals(owningProcessId(callActivity))) {
        continue;
      }
      final var calledElement = callActivity.getSingleExtensionElement(ZeebeCalledElement.class);
      if (calledElement == null) {
        // no process is named at all, so this call activity reaches nothing the cluster
        // would deploy
        return false;
      }
      final var asModelled = calledElement
          .getDomElement()
          .getAttribute(PROPAGATE_ALL_PARENT_VARIABLES);
      if (asModelled == null) {
        calledElement.setPropagateAllParentVariablesEnabled(true);
        return true;
      }
      return !"false".equalsIgnoreCase(asModelled.trim());
    }
    return false;

  }

  /**
   * Reads {@code zeebe:calledElement processId} of a call activity, a static process ID
   * only - an expression is not resolvable while deploying.
   */
  private static String staticallyCalledProcessId(
      final CallActivity callActivity) {

    final var calledElement = callActivity.getSingleExtensionElement(ZeebeCalledElement.class);
    if (calledElement == null) {
      return null;
    }
    final var processId = calledElement.getProcessId();
    if ((processId == null) || processId.isBlank() || processId.startsWith("=")) {
      return null;
    }
    return processId;

  }

  /**
   * Walks an activity and everything below it, remembering every multi-instance element
   * found and, where the model is about to be deployed, injecting its input mappings.
   */
  private static void collect(
      final Activity activity,
      final Map<String, MultiInstanceElement> elements,
      final Map<String, String> variableNames,
      final String bpmnProcessId,
      final boolean inject) {

    if (activity.getLoopCharacteristics() instanceof MultiInstanceLoopCharacteristics loopCharacteristics) {
      final var element = describe(activity, loopCharacteristics, variableNames, bpmnProcessId);
      elements.put(activity.getId(), element);
      if (inject) {
        inject(activity, element, loopCharacteristics);
      }
    }
    activity
        .getChildElementsByType(Activity.class)
        .forEach(child -> collect(child, elements, variableNames, bpmnProcessId, inject));

  }

  /**
   * The variables one multi-instance element uses. Their names are derived from the
   * element's ID, which is what makes them impossible to shadow.
   */
  private static MultiInstanceElement describe(
      final Activity activity,
      final MultiInstanceLoopCharacteristics loopCharacteristics,
      final Map<String, String> variableNames,
      final String bpmnProcessId) {

    final var elementId = activity.getId();
    final var suffix = variableSuffix(elementId);
    final var clashing = variableNames.put(suffix, elementId);
    if ((clashing != null) && !clashing.equals(elementId)) {
      throw new IllegalStateException(
          """
              The multi-instance elements '%s' and '%s' of BPMN process '%s' cannot be told apart by \
              VanillaBP: their IDs differ only in characters which are not valid in a Camunda 8 \
              variable name ('%s' for both). Rename one of them."""
              .formatted(clashing, elementId, bpmnProcessId, suffix));
    }

    final var zeebeLoopCharacteristics = loopCharacteristics
        .getSingleExtensionElement(ZeebeLoopCharacteristics.class);
    final var inputElement = zeebeLoopCharacteristics == null
        ? null
        : zeebeLoopCharacteristics.getInputElement();
    final var inputCollection = zeebeLoopCharacteristics == null
        ? null
        : zeebeLoopCharacteristics.getInputCollection();

    return new MultiInstanceElement(
        elementId, VARIABLE_PREFIX
            + "Index_"
            + suffix, isBlank(inputCollection)
                ? null
                : VARIABLE_PREFIX
                    + "Total_"
                    + suffix, isBlank(inputElement)
                        ? null
                        : VARIABLE_PREFIX
                            + "Element_"
                            + suffix);

  }

  /**
   * Adds the input mappings of one multi-instance element, keeping whatever mappings
   * the model already declares and staying idempotent for a redeployment.
   */
  private static void inject(
      final Activity activity,
      final MultiInstanceElement element,
      final MultiInstanceLoopCharacteristics loopCharacteristics) {

    final var zeebeLoopCharacteristics = loopCharacteristics
        .getSingleExtensionElement(ZeebeLoopCharacteristics.class);

    addInput(activity, element.indexVariable(), "=loopCounter");
    if (element.totalVariable() != null) {
      // the collection is a FEEL expression, not necessarily a variable name, so the
      // size is asked of the expression itself
      addInput(
          activity,
          element.totalVariable(),
          "=count(%s)".formatted(withoutFeelPrefix(zeebeLoopCharacteristics.getInputCollection())));
    }
    if (element.elementVariable() != null) {
      addInput(activity, element.elementVariable(), "="
          + zeebeLoopCharacteristics.getInputElement());
    }

  }

  private static void addInput(
      final Activity activity,
      final String target,
      final String source) {

    if (activity.getExtensionElements() == null) {
      activity
          .setExtensionElements(
              activity
                  .getModelInstance()
                  .newInstance(ExtensionElements.class));
    }
    var ioMapping = activity.getSingleExtensionElement(ZeebeIoMapping.class);
    if (ioMapping == null) {
      ioMapping = activity
          .getExtensionElements()
          .addExtensionElement(ZeebeIoMapping.class);
    }
    final var alreadyThere = ioMapping
        .getInputs()
        .stream()
        .filter(input -> target.equals(input.getTarget()))
        .findFirst()
        .orElse(null);
    if (alreadyThere != null) {
      // the same mapping again is this model coming back for a redeployment, and writing
      // it twice is what has to be avoided. A DIFFERENT one is somebody else's, and
      // letting it stand would hand a handler the values of another iteration while the
      // chain says otherwise - so the boot ends here instead of at the first wrong report
      if (!source.equals(alreadyThere.getSource())) {
        throw new IllegalStateException(
            """
                The BPMN element '%s' already maps something into the variable '%s': it reads '%s' \
                while VanillaBP writes '%s' there to report the iteration of '%s'. VanillaBP does \
                not overwrite what an application modelled itself, and it cannot use the modelled \
                expression either, because a handler would then read the values of another \
                iteration. Map your value into a variable of another name, or remove the input \
                mapping and let VanillaBP write it."""
                .formatted(activity.getId(), target, alreadyThere.getSource(), source, activity.getId()));
      }
      return;
    }
    final var input = activity
        .getModelInstance()
        .newInstance(ZeebeInput.class);
    input.setTarget(target);
    input.setSource(source);
    ioMapping.addChildElement(input);

  }

  /**
   * The multi-instance elements enclosing a BPMN element, outermost first - the order
   * the SPI defines. The element itself is part of the chain when it is
   * multi-instance, which is the usual case: a multi-instance service task.
   */
  private static List<MultiInstanceElement> chainOf(
      final FlowElement flowElement,
      final Map<String, MultiInstanceElement> elements) {

    final var innermostFirst = new LinkedList<MultiInstanceElement>();
    var current = (BaseElement) flowElement;
    while (current != null) {
      final var element = elements.get(current.getId());
      if (element != null) {
        innermostFirst.add(element);
      }
      current = current.getParentElement() instanceof BaseElement parent
          ? parent
          : null;
    }
    final var outermostFirst = new ArrayList<>(innermostFirst);
    Collections.reverse(outermostFirst);
    return outermostFirst;

  }

  /**
   * Builds what the SPI asks for out of the variables a job carries.
   *
   * @param chain The multi-instance elements enclosing the job's element, outermost
   *          first
   * @param variables The job's variables
   * @return The multi-instance contexts, keyed by the ID of the multi-instance
   *         element, outermost first
   */
  public static Map<String, MultiInstanceValue> valuesOf(
      final List<MultiInstanceElement> chain,
      final Map<String, Object> variables) {

    if (chain.isEmpty()) {
      return Map.of();
    }
    final var result = new LinkedHashMap<String, MultiInstanceValue>();
    for (final var element : chain) {
      final var index = intOf(variables.get(element.indexVariable()));
      if (index == null) {
        // two reasons, and a job cannot tell them apart: the workflow runs on a process
        // version deployed before this adapter knew about multi-instance, or the element
        // belongs to a place the process is called from which this workflow did not come
        // through, which the union over the call sites makes an everyday case. The core's
        // message names what was supplied
        log.debug(
            "Camunda8: no variable '{}' in this job, so the multi-instance element '{}' is not "
                + "reported. Either the workflow was started on a process version deployed before "
                + "VanillaBP added the mappings, or the element belongs to another place the "
                + "process is called from.",
            element.indexVariable(),
            element.elementId());
        continue;
      }
      final var total = element.totalVariable() == null
          ? null
          : intOf(variables.get(element.totalVariable()));
      final var currentElement = element.elementVariable() == null
          ? null
          : variables.get(element.elementVariable());
      // Camunda 8 counts iterations from 1, the SPI counts from 0 like Camunda 7 does
      result.put(
          element.elementId(),
          new MultiInstanceValue(
              currentElement, index - 1, total == null
                  ? -1
                  : total));
    }
    return result;

  }

  private static Integer intOf(
      final Object value) {

    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text) {
      try {
        return Integer.valueOf(text);
      } catch (final NumberFormatException e) {
        return null;
      }
    }
    return null;

  }

  /**
   * The element ID, reduced to what a Camunda 8 variable name may consist of.
   */
  static String variableSuffix(
      final String elementId) {

    return elementId.replaceAll("[^A-Za-z0-9_]", "_");

  }

  private static String withoutFeelPrefix(
      final String expression) {

    final var trimmed = expression.trim();
    return trimmed.startsWith("=")
        ? trimmed.substring(1)
        : trimmed;

  }

  private static boolean isBlank(
      final String value) {

    return (value == null) || value.isBlank();

  }

  /**
   * The process a flow element belongs to - a model file may hold several.
   */
  private static String owningProcessId(
      final FlowElement flowElement) {

    var current = flowElement.getParentElement();
    while (current != null) {
      if (current instanceof Process process) {
        return process.getId();
      }
      current = current.getParentElement();
    }
    return null;

  }

}
