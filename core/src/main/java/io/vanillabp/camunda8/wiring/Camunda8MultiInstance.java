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
 * Camunda 7 answers this from the execution hierarchy at runtime. Camunda 8 has no such
 * hierarchy on the client side. A job carries variables and an element ID, and nothing
 * else. Of the three values the SPI asks for, exactly one is there by itself:
 * </p>
 * <ul>
 * <li><strong>the element</strong> is the variable named by
 * <code>inputElement</code>. But a NESTED iteration using the same name shadows the
 * outer one.</li>
 * <li><strong>the index</strong> is <code>loopCounter</code>, counted from 1 and not
 * from 0, and shadowed the same way.</li>
 * <li><strong>the total</strong> does not exist at all. This engine has no
 * <code>nrOfInstances</code>, and it has no loop cardinality either. So a
 * multi-instance element always iterates over a collection.</li>
 * </ul>
 *
 * <p>
 * So the values are made unambiguous while the BPMN is deployed. VanillaBP changes models
 * at that stage anyway. Every multi-instance element gets input mappings which copy its own
 * index, its own total and its own element into variables named after that element. Those
 * names cannot be shadowed. A job of a task nested in three iterations sees all three of
 * them. It costs three local variables per instance. All of them are scalar except the
 * element, which the model was handing over anyway.
 * </p>
 *
 * <p>
 * The chain of multi-instance elements enclosing a task is model knowledge. So it is
 * collected while deploying and kept per process and element. A job carries the ID of its
 * own element only.
 * </p>
 * <p>
 * A called process is part of the same chain. The cluster copies the variables of every
 * scope a call activity sits in into the called instance. So the values are there. But the
 * model does not say which iterations they belong to, because a process does not know who
 * calls it. That link is read from the call activities of the CALLERS, once every process
 * of a workflow module is wired. See {@link Registry#registerCall} and
 * {@link Registry#linkCalledProcesses()}.
 * </p>
 * <p>
 * Where the caller names the process it calls by an EXPRESSION, there is no link to read.
 * The process reached is decided per instance. But the model knowledge on the side of the
 * caller is complete all the same. So the caller hands its chain down in the variable
 * {@link #CHAIN_VARIABLE}. An input mapping of the call activity itself writes it, see
 * {@link #handTheChainDown}. The reading side puts those levels in front of the ones the
 * deployment knew. Afterwards it cannot tell them apart.
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
   * The variable in which a called process gets the iteration chain of its caller, where the
   * caller names the called process by an expression. Which process such a call activity
   * reaches is decided per instance. So no deployment can put the two models together. But
   * the deployment CAN write the chain of the caller into the called instance while it is
   * created.
   */
  public static final String CHAIN_VARIABLE = VARIABLE_PREFIX
      + "Parents";

  /**
   * The keys of one entry of {@link #CHAIN_VARIABLE}: the calling process and its levels.
   * A reader sees these names in Operate. That is where somebody looks when they want to
   * know why an index is missing. Short keys would save about two percent of an entry and
   * lose that.
   */
  private static final String CALLER_KEY = "process";

  /** The levels of one caller, outermost first. */
  private static final String LEVELS_KEY = "levels";

  /** The BPMN id of one multi-instance element, which is the key the SPI uses. */
  private static final String ELEMENT_KEY = "element";

  /** The iteration counter of one level, as the CLUSTER counts it, so from 1. */
  private static final String INDEX_KEY = "index";

  /** The size of the collection of one level, left out where the element has none. */
  private static final String TOTAL_KEY = "total";

  /** The current element of one level, left out where the model names no input element. */
  private static final String ITEM_KEY = "item";

  /**
   * The attribute of {@code zeebe:calledElement} which decides whether the variables of
   * the enclosing scopes reach the called instance. It is read from the DOM and not through
   * {@code isPropagateAllParentVariablesEnabled()}. The model API answers the DEFAULT where
   * the attribute is absent, and absent is the one case in which it may be written.
   */
  private static final String PROPAGATE_ALL_PARENT_VARIABLES = "propagateAllParentVariables";

  private Camunda8MultiInstance() {
  }

  /**
   * One multi-instance element and the variables carrying what it knows about the
   * current iteration.
   *
   * @param elementId The BPMN ID of the multi-instance element. It is the key the SPI
   *          uses, and what the application writes into
   *          <code>@MultiInstanceElement("...")</code>
   * @param indexVariable The variable holding the <code>loopCounter</code> of this element
   * @param totalVariable The variable holding the size of the collection of this element,
   *          or <code>null</code> if the element has no input collection
   * @param elementVariable The variable holding the current element of this element, or
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
     * the models of that adapter.
     */
    public Registry() {
    }

    private final Map<String, List<MultiInstanceElement>> chains = new ConcurrentHashMap<>();

    /**
     * Where a process is called from, by called process. A process may be called from
     * several places. That is what makes the chain a union.
     */
    private final Map<String, Set<CallSite>> callSites = new ConcurrentHashMap<>();

    /**
     * What a process inherits from the places it is called from, outermost first. It is
     * computed from {@link #callSites} by {@link #linkCalledProcesses()}, not on every
     * job.
     */
    private final Map<String, List<Level>> inheritedChains = new ConcurrentHashMap<>();

    /**
     * One place a process is called from.
     *
     * @param callerBpmnProcessId The calling process, as the CLUSTER knows it
     * @param callActivityId The BPMN ID of the call activity in that process
     */
    private record CallSite(String callerBpmnProcessId, String callActivityId) {
    }

    /**
     * One multi-instance element together with the process declaring it. A message about an
     * element of a CALLER has to name both, because then the reader has two models in front
     * of them.
     *
     * @param bpmnProcessId The process declaring the element, as the CLUSTER knows it
     * @param element The element
     */
    public record Level(
                        String bpmnProcessId,
                        MultiInstanceElement element) {
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
     * Remembers that one process calls another one. It is recorded for a call activity which
     * names its process statically and calls a process on the same workflow aggregate. The
     * caller decides both, because this class answers neither question.
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
     * Which callers a process accepts a handed-down chain from, by called process. A call
     * activity which names its process by an expression hands its chain to whatever process
     * it reaches. Is that process a decomposition of the caller, or a business case of its
     * own? That is answered HERE, while the module is deployed. The deployment cannot say
     * which process is reached. But it can say which of its own processes may use the
     * chain.
     */
    private final Map<String, Set<String>> callersNamingTheirProcessByExpression = new ConcurrentHashMap<>();

    /**
     * Remembers that one process of a workflow module may be handed the chain of a caller
     * which names the process it calls by an expression. It is recorded for every process of
     * the module which shares the workflow aggregate of the caller. Which one is reached is
     * decided per instance.
     *
     * @param callerBpmnProcessId The calling process, as the CLUSTER knows it
     * @param calledBpmnProcessId A process the call may reach, as the CLUSTER knows it
     */
    public void registerCallByExpression(
        final String callerBpmnProcessId,
        final String calledBpmnProcessId) {

      callersNamingTheirProcessByExpression
          .computeIfAbsent(calledBpmnProcessId, called -> ConcurrentHashMap.newKeySet())
          .add(callerBpmnProcessId);

    }

    /**
     * Whether a process may use the chain one caller handed it down.
     * <p>
     * An unknown caller is answered <code>false</code>, not guessed. Two ways lead there.
     * The call crossed the boundary of a workflow module, where this adapter never saw the
     * model of the caller. Or the caller works on a workflow aggregate of its own, which is
     * not decomposition. An application writing the variable itself ends up here as well.
     *
     * @param bpmnProcessId The process reading the variable, as the CLUSTER knows it
     * @param callerBpmnProcessId The caller the entry names
     * @return Whether the levels of that entry describe iterations of this process
     */
    boolean acceptsTheChainOf(
        final String bpmnProcessId,
        final String callerBpmnProcessId) {

      return callersNamingTheirProcessByExpression
          .getOrDefault(bpmnProcessId, Set.of())
          .contains(callerBpmnProcessId);

    }

    /**
     * Whether a process may be handed a chain while it runs, by a caller which names the
     * process it calls by an expression.
     * <p>
     * Such a chain arrives in the variable {@link #CHAIN_VARIABLE} of the process instance and
     * not in the model, so {@link #chainOf} cannot see it. An extension may fetch the
     * variables of a task itself, and not take them from a job. It asks this method whether
     * it has to fetch that variable too. <code>true</code> does not mean that a chain is there.
     * It means that one may be there, and only the variable says.
     *
     * @param bpmnProcessId The process, as the CLUSTER knows it
     * @return Whether a process of the same workflow aggregate calls this process by an
     *         expression
     */
    public boolean mayBeHandedAChain(
        final String bpmnProcessId) {

      return callersNamingTheirProcessByExpression.containsKey(bpmnProcessId);

    }

    /**
     * Works out what every called process inherits from the places it is called from.
     * It is called once the processes of a workflow module are wired, because a call
     * activity of one file may name a process of another one.
     *
     * @throws IllegalStateException Where two call sites of one process carry multi-instance
     *           elements of one ID which do not mean the same thing
     */
    public void linkCalledProcesses() {

      final var linked = new LinkedHashMap<String, List<Level>>();
      for (final var calledProcess : new TreeSet<>(callSites.keySet())) {
        final var levels = inheritedBy(calledProcess, List.of());
        if (!levels.isEmpty()) {
          linked.put(calledProcess, levels);
        }
      }
      inheritedChains.putAll(linked);

    }

    /**
     * The levels a process inherits from its call sites, outermost first.
     * <p>
     * Each call site contributes one path: what the CALLER inherits, followed by the
     * multi-instance elements enclosing the call activity in the caller. The paths are
     * then merged into one list. A job reports an element, not the way its instance was
     * reached. A level of a path which did not run is simply not in the job, and
     * {@link Camunda8MultiInstance#valuesOf} leaves it out.
     *
     * @param bpmnProcessId The process asked about
     * @param path The processes already walked through. So a call graph with a cycle ends
     *          with the levels collected so far
     */
    private List<Level> inheritedBy(
        final String bpmnProcessId,
        final List<String> path) {

      if (path.contains(bpmnProcessId)) {
        // a process calling itself, directly or around a corner. The chain of an element
        // in it would grow with every round, so the walk stops here.
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
      // the union is built in a stable order. So two boots of one application report the
      // levels of a process called from several places in the same order.
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
     * Adds one level to ONE call path. On one path, a repeated variable is nesting and not a
     * choice. Both levels write the same variables. The inner scope overwrites the outer one,
     * so the job carries the inner values. So the later occurrence replaces the earlier one
     * and takes its place at the end. The reading side gives the same answer for a
     * handed-down chain. A process calling itself is where this happens by design.
     * <p>
     * It is keyed by the index variable and not by the element ID, because the cluster knows
     * the variable only. Two IDs which differ only in characters a variable name may not
     * hold are one level for the cluster.
     */
    private static void appendLevel(
        final Map<String, Level> levels,
        final Level level) {

      final var variable = level.element().indexVariable();
      final var replaced = levels.remove(variable);
      if ((replaced != null) && !replaced.element().elementId().equals(level.element().elementId())) {
        log
            .debug(
                "Camunda8: the multi-instance elements '{}' of BPMN process '{}' and '{}' of BPMN "
                    + "process '{}' sit on one call path and both write the variable '{}'. The "
                    + "inner one, '{}', is what a job carries, so a @MultiInstanceElement(\"{}\") "
                    + "is not answered there.",
                replaced.element().elementId(),
                replaced.bpmnProcessId(),
                level.element().elementId(),
                level.bpmnProcessId(),
                variable,
                level.element().elementId(),
                replaced.element().elementId());
      }
      levels.put(variable, level);

    }

    /**
     * Adds one level to the union over the call paths. Here a repeated variable is a
     * CHOICE. Only one of the paths reached the instance at hand. So one entry serves both,
     * as long as both mean the same thing. Where they do not, nobody can say what
     * <code>@MultiInstanceElement</code> of that ID means, and the boot ends here.
     * <p>
     * It is keyed by the index variable and not by the element ID, like {@link #appendLevel}.
     * Two different IDs writing one variable are a collision as well. A job of an instance
     * reached from one caller carries that variable. A handler naming the element of the
     * other caller would be told about a round which never ran. The deployment applies the
     * same rule to two elements of ONE process. Here it is applied across the processes
     * calling this one.
     * <p>
     * A level which two paths share keeps the place the first path gave it. Those two paths
     * may nest the same two IDs the other way round. Then only one of the two orders is
     * reported. Call paths are walked in a fixed order, so the answer is at least the same
     * after a restart.
     */
    private static void mergeLevel(
        final Map<String, Level> levels,
        final Level level,
        final String calledBpmnProcessId) {

      final var alreadyThere = levels.putIfAbsent(level.element().indexVariable(), level);
      if ((alreadyThere == null) || alreadyThere.element().equals(level.element())) {
        return;
      }
      final var oneId = alreadyThere.element().elementId().equals(level.element().elementId());
      throw new IllegalStateException(
          """
              Two multi-instance elements %s reach the BPMN process '%s' from the places it is \
              called: the one in '%s' hands over %s, the one in '%s' hands over %s. Both write the \
              variable '%s'%s, so a @MultiInstanceElement of a task in '%s' cannot say which of the \
              two it means. Rename one of the two elements."""
              .formatted(
                  oneId
                      ? "named '%s'".formatted(level.element().elementId())
                      : "named '%s' and '%s'".formatted(alreadyThere.element().elementId(),
                          level.element().elementId()),
                  calledBpmnProcessId,
                  alreadyThere.bpmnProcessId(),
                  shapeOf(alreadyThere.element()),
                  level.bpmnProcessId(),
                  shapeOf(level.element()),
                  level.element().indexVariable(),
                  oneId
                      ? ""
                      : " (their IDs differ only in characters a Camunda 8 variable name cannot hold)",
                  calledBpmnProcessId));

    }

    /**
     * How a multi-instance element looks to a handler. A message tells two elements of one
     * ID apart by it.
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
     * sites enclose. A process called from SEVERAL places gets the union of those levels.
     * Each call path keeps its own order. A level which two paths share appears once. And
     * the runtime drops whatever is not in the job.
     * {@link Camunda8MultiInstance#valuesOf} does that for a level whose index variable is
     * missing. So a chain may name more levels than the instance at hand ran in. A handler
     * still sees exactly the iterations it is inside of.
     * <p>
     * A call activity which names its process by an expression is not part of this. The
     * process it reaches is decided per instance, and no deployment can resolve that. Such a
     * caller writes its chain into the called instance instead.
     * {@link Camunda8MultiInstance#valuesOf(Registry, String, String, Map)} puts those levels
     * in front of what this method answers. A call activity which calls a process with a
     * workflow aggregate of its own is in neither half. That process runs a business case of
     * its own. It reports no iteration of its caller, although the cluster copies the values
     * into its instance.
     *
     * @param bpmnProcessId The BPMN process ID as the CLUSTER knows it
     * @param elementId The BPMN element ID the job reports
     * @return The multi-instance elements enclosing that element, outermost first
     */
    public List<MultiInstanceElement> chainOf(
        final String bpmnProcessId,
        final String elementId) {

      final var own = chains.getOrDefault(key(bpmnProcessId, elementId), List.of());
      final var inherited = inheritedLevelsOf(bpmnProcessId, elementId);
      if (inherited.isEmpty()) {
        return own;
      }
      final var complete = inherited
          .stream()
          .map(Level::element)
          .collect(Collectors.toCollection(ArrayList::new));
      complete.addAll(own);
      return List.copyOf(complete);

    }

    /**
     * The part of {@link #chainOf} which the places a process is called from contribute,
     * outermost first, each level with the process declaring it.
     * <p>
     * Empty until {@link #linkCalledProcesses()} ran. A check which asks before that moment
     * has to know this. It sees the levels of the process itself and nothing else.
     *
     * @param bpmnProcessId The BPMN process ID as the CLUSTER knows it
     * @param elementId The BPMN element ID the job reports
     * @return The inherited levels which are still in a job of that element
     */
    public List<Level> inheritedLevelsOf(
        final String bpmnProcessId,
        final String elementId) {

      final var inherited = inheritedChains.getOrDefault(bpmnProcessId, List.of());
      if (inherited.isEmpty()) {
        return inherited;
      }
      final var own = chains.getOrDefault(key(bpmnProcessId, elementId), List.of());
      if (own.isEmpty()) {
        return inherited;
      }
      final var ownVariables = own
          .stream()
          .map(MultiInstanceElement::indexVariable)
          .collect(Collectors.toSet());
      return inherited
          .stream()
          // a variable this process writes itself is written in a scope further in. So what
          // arrived from the call site is not in the job any more. The variable decides, not
          // the ID. Two IDs which differ only in characters a variable name may not hold
          // write one variable.
          .filter(level -> !ownVariables.contains(level.element().indexVariable()))
          .toList();

    }

  }

  /**
   * Prepares a process for multi-instance. It injects the input mappings which make the
   * values of every iteration unambiguous. And it records which elements are enclosed by
   * which iterations. For a model which is only read, see {@link #chainsOf}.
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
   * The chains of a model which is only being READ, without touching it. A model a BPMS
   * still holds is not deployed again. Injecting mappings into it would change what the
   * check judges.
   * <p>
   * A model whose element ids cannot be told apart by their variable names is refused while
   * it is DEPLOYED. Here it is not. Nobody can change a version the cluster already holds.
   * So the answer is that this adapter cannot read the shape of that version, and the core
   * asks nothing about it.
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
   * Records which elements are enclosed by which iterations. For a model about to be
   * deployed, it also injects the input mappings which make the values of every iteration
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
    // no multi-instance in this process, so nothing to inject and nothing to remember
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
   * A call activity which names its process by an expression is left out. Which process it
   * reaches is decided per instance, and the chain is model knowledge. The same limit
   * applies to the scoped identifiers of decision 2 in the repository's DECISIONS.md and to
   * the workflow viewer. They read this attribute the same way.
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
   * Whether the variables of the scopes a call activity sits in reach the called instance.
   * Where the model says nothing about it, this writes it into the model.
   * <p>
   * Leaving the attribute out means the same thing today. So writing it changes no
   * behaviour on any cluster shipping now. It writes down what the chain of a called
   * process relies on. So a later default of the engine cannot quietly take it away.
   * Where the model says <code>false</code>, the modeller switched the context of the
   * caller off on purpose. That is left alone, because nothing the application modelled
   * itself is overwritten (decision 5 in the repository's DECISIONS.md). The answer is then
   * <code>false</code>. The values never arrive, so there is no iteration to report
   * either.
   *
   * @param model The BPMN model, about to be deployed
   * @param bpmnProcessId The process holding the call activity
   * @param callActivityId The call activity
   * @return Whether the variables of the caller reach the called process
   */
  public static boolean theCallersVariablesReachTheCalledProcess(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final String callActivityId) {

    final var callActivity = callActivityOf(model, bpmnProcessId, callActivityId);
    if (callActivity == null) {
      return false;
    }
    final var calledElement = callActivity.getSingleExtensionElement(ZeebeCalledElement.class);
    if (calledElement == null) {
      // no process is named at all. So this call activity reaches nothing the cluster
      // would deploy.
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

  /**
   * One call activity of one BPMN process, or <code>null</code> where that process has no
   * element of that id. A model file may hold several processes. So the owning process is
   * part of the question.
   */
  private static CallActivity callActivityOf(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final String callActivityId) {

    for (final var callActivity : model.getModelElementsByType(CallActivity.class)) {
      if (callActivityId.equals(callActivity.getId()) && bpmnProcessId.equals(owningProcessId(callActivity))) {
        return callActivity;
      }
    }
    return null;

  }

  /**
   * Writes the iteration chain of one call activity into the instance it calls, as an input
   * mapping into {@link #CHAIN_VARIABLE}.
   * <p>
   * A call activity which names its process by an EXPRESSION needs this. A statically named
   * one is linked model to model and gets nothing of this, not one byte more payload. Here
   * there is no model to link, because the process reached is decided per instance. But the
   * levels themselves are known. And every value they need is in reach where the mapping
   * stands. An enclosing level has its variables from the mappings of decision 5 in the
   * repository's DECISIONS.md. The round of the call activity itself has
   * <code>loopCounter</code>, its input collection and its input element, like any other
   * multi-instance element.
   * <p>
   * It is an input mapping and not a start listener. The cluster evaluates the mapping in the
   * same record which creates the called instance. So there is no window in which the
   * variable is still missing. A listener would cost a job per instance for nothing but
   * bookkeeping.
   * <p>
   * The expression appends to whatever is already there. That is how a chain over several
   * call activities comes about. The cluster copies the variable of the caller into the
   * called instance, and the next mapping appends its own entry. The guard
   * <code>is defined(...)</code> makes the first call work. It is also all the protection
   * there is. A value of another shape makes the whole chain <code>null</code> without an
   * incident. The reading side has to survive that and must not trust the value.
   * <p>
   * Nothing is written at a STATICALLY named call activity, and nothing where there is
   * nothing to hand down. Nothing is written either where the model keeps the variables of
   * the caller out of the called instance with
   * <code>propagateAllParentVariables="false"</code>. There the modeller switched the context
   * of the caller off on purpose. A mapping would travel all the same and undo that.
   *
   * @param model The BPMN model, about to be deployed
   * @param bpmnProcessId The calling process, as the cluster will know it
   * @param callActivityId The call activity naming its process by an expression
   * @param registry Where the chains of this module are recorded, linked already
   * @return The expression written into the model, or <code>null</code> where nothing was
   *         written
   */
  public static String handTheChainDown(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final String callActivityId,
      final Registry registry) {

    final var callActivity = callActivityOf(model, bpmnProcessId, callActivityId);
    if (callActivity == null) {
      // a business rule task naming its decision by an expression is read from the same
      // list, and it calls no process
      return null;
    }
    if (staticallyCalledProcessId(callActivity) != null) {
      // a statically named call activity is linked model to model and is told nothing here.
      // That keeps it at not one byte more payload.
      return null;
    }
    final var chain = registry.chainOf(bpmnProcessId, callActivityId);
    if (chain.isEmpty()) {
      // no iteration of this caller encloses the call activity, so there is nothing to write
      // down. What an outer caller handed to THIS process travels on by itself. The cluster
      // copies that variable into the called instance like every other one.
      return null;
    }
    if (!theCallersVariablesReachTheCalledProcess(model, bpmnProcessId, callActivityId)) {
      return null;
    }
    final var ownLoop = callActivity.getLoopCharacteristics() instanceof MultiInstanceLoopCharacteristics loop
        ? loop.getSingleExtensionElement(ZeebeLoopCharacteristics.class)
        : null;
    final var expression = theChainAsAnExpression(bpmnProcessId, chain, callActivityId, ownLoop);
    addInput(callActivity, CHAIN_VARIABLE, expression);
    return expression;

  }

  /**
   * The expression appending one entry to {@link #CHAIN_VARIABLE}: this caller and its levels,
   * outermost first.
   */
  private static String theChainAsAnExpression(
      final String bpmnProcessId,
      final List<MultiInstanceElement> chain,
      final String callActivityId,
      final ZeebeLoopCharacteristics ownLoop) {

    final var levels = chain
        .stream()
        .map(level -> theLevelAsAnExpression(level, level.elementId().equals(callActivityId), ownLoop))
        .collect(Collectors.joining(", "));
    return "=append(if is defined(%s) then %s else [], {%s: %s, %s: [%s]})"
        .formatted(CHAIN_VARIABLE, CHAIN_VARIABLE, CALLER_KEY, quoted(bpmnProcessId), LEVELS_KEY, levels);

  }

  /**
   * One level of the entry. A level enclosing the call activity reads the variables which
   * the mappings of that element wrote. The round of the call activity ITSELF reads what the
   * cluster offers every multi-instance element. Its own mappings are written on the same
   * element, and one input mapping of an element must not depend on another.
   */
  private static String theLevelAsAnExpression(
      final MultiInstanceElement level,
      final boolean isTheCallActivitysOwnRound,
      final ZeebeLoopCharacteristics ownLoop) {

    final var keys = new ArrayList<String>();
    keys.add(ELEMENT_KEY
        + ": "
        + quoted(level.elementId()));
    keys
        .add(
            INDEX_KEY
                + ": "
                + (isTheCallActivitysOwnRound
                    ? "loopCounter"
                    : level.indexVariable()));
    // a level carries a total exactly where its element has an input collection, and an item
    // exactly where it names an input element. Both are read from the same
    // zeebe:loopCharacteristics this level was described from. So that element is there
    // wherever they are.
    if (level.totalVariable() != null) {
      keys
          .add(
              TOTAL_KEY
                  + ": "
                  + (isTheCallActivitysOwnRound
                      ? "count(%s)".formatted(withoutFeelPrefix(ownLoop.getInputCollection()))
                      : level.totalVariable()));
    }
    if (level.elementVariable() != null) {
      keys
          .add(
              ITEM_KEY
                  + ": "
                  + (isTheCallActivitysOwnRound
                      ? ownLoop.getInputElement()
                      : level.elementVariable()));
    }
    return "{"
        + String.join(", ", keys)
        + "}";

  }

  /**
   * One value as a FEEL string literal. A BPMN process id or element id is unlikely to hold a
   * quote. But an expression broken by one would be refused by the cluster. The message
   * would then show our text, not the one of the application.
   */
  private static String quoted(
      final String value) {

    return "\""
        + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        + "\"";

  }

  /**
   * Reads {@code zeebe:calledElement processId} of a call activity, a static process ID
   * only. An expression cannot be resolved while deploying.
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
   * Walks an activity and everything below it. It remembers every multi-instance element it
   * finds. Where the model is about to be deployed, it also injects its input mappings.
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
   * The variables one multi-instance element uses. Their names are derived from the ID of
   * the element. That makes them impossible to shadow.
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
        elementId, indexVariableOf(elementId), isBlank(inputCollection)
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
   * Adds the input mappings of one multi-instance element. It keeps whatever mappings the
   * model already declares, and it stays idempotent for a redeployment.
   */
  private static void inject(
      final Activity activity,
      final MultiInstanceElement element,
      final MultiInstanceLoopCharacteristics loopCharacteristics) {

    final var zeebeLoopCharacteristics = loopCharacteristics
        .getSingleExtensionElement(ZeebeLoopCharacteristics.class);

    addInput(activity, element.indexVariable(), "=loopCounter");
    if (element.totalVariable() != null) {
      // the collection is a FEEL expression, not necessarily a variable name. So the
      // size is asked of the expression itself.
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
      // the same mapping again means this model came back for a redeployment. Writing it
      // twice has to be avoided. A DIFFERENT one belongs to somebody else. Letting it
      // stand would hand a handler the values of another iteration, while the chain says
      // otherwise. So the boot ends here, and not at the first wrong report.
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
   * The multi-instance elements enclosing a BPMN element, outermost first. That is the
   * order the SPI defines. The element itself is part of the chain when it is
   * multi-instance. That is the usual case: a multi-instance service task.
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
   * Builds what the SPI asks for out of the variables a job carries. It uses BOTH sources of
   * the chain: the levels the deployment knows about, and the ones which a caller naming its
   * process by an expression handed down in {@link #CHAIN_VARIABLE}.
   * <p>
   * The handed-down levels go in FRONT, because they belong to processes further out. A
   * level whose variable the called process writes itself is dropped. Both write the same
   * variable names. The inner scope overwrites the outer one, so the job carries the inner
   * values. {@link Registry#chainOf} follows the same rule for a static call. That is why the
   * two sources cannot be told apart once they are together.
   *
   * @param registry The chains of this module, linked already
   * @param bpmnProcessId The BPMN process id the job reports, as the cluster knows it
   * @param elementId The BPMN element id the job reports
   * @param variables The variables of the job
   * @return The multi-instance contexts, keyed by the ID of the multi-instance element,
   *         outermost first
   */
  public static Map<String, MultiInstanceValue> valuesOf(
      final Registry registry,
      final String bpmnProcessId,
      final String elementId,
      final Map<String, Object> variables) {

    final var own = registry.chainOf(bpmnProcessId, elementId);
    final var handedDown = theChainHandedDown(registry, bpmnProcessId, variables, own);
    if (handedDown.levels().isEmpty()) {
      return valuesOf(own, variables);
    }
    final var complete = new ArrayList<>(handedDown.levels());
    complete.addAll(own);
    // a value the job really carries wins over the copy in the list. So a level of the
    // called process itself is never read from what a caller wrote down.
    final var withWhatWasHandedDown = new LinkedHashMap<>(handedDown.values());
    withWhatWasHandedDown.putAll(variables);
    return valuesOf(complete, withWhatWasHandedDown);

  }

  /**
   * The levels which one or more callers handed down, together with the values under the
   * names those levels refer to.
   *
   * @param levels The multi-instance elements, outermost first
   * @param values What a job would carry if the deployment had known these levels
   */
  private record HandedDownChain(
                                 List<MultiInstanceElement> levels,
                                 Map<String, Object> values) {
  }

  /** What a job without a usable {@link #CHAIN_VARIABLE} hands to the reading side. */
  private static final HandedDownChain NOTHING_HANDED_DOWN = new HandedDownChain(List.of(), Map.of());

  /**
   * Reads {@link #CHAIN_VARIABLE} out of a job.
   * <p>
   * Nothing here trusts the value. The name is not protected. A start variable, an output
   * mapping or the write-back of the workflow aggregate can all set it. And a FEEL expression
   * which reaches into nothing becomes <code>null</code>, which the cluster writes without an
   * incident. So a value which is not a list of entries of the expected shape is read as if
   * the variable were not there. An entry which does not describe a level of this process is
   * left out. None of this reaches a <code>&#64;WorkflowTask</code> as an exception.
   */
  private static HandedDownChain theChainHandedDown(
      final Registry registry,
      final String bpmnProcessId,
      final Map<String, Object> variables,
      final List<MultiInstanceElement> own) {

    final var handedDown = variables.get(CHAIN_VARIABLE);
    if (handedDown == null) {
      return NOTHING_HANDED_DOWN;
    }
    if (!(handedDown instanceof List<?> entries)) {
      somethingWasDiscarded(bpmnProcessId, "it holds a "
          + handedDown.getClass().getSimpleName()
          + " rather than a list of callers");
      return NOTHING_HANDED_DOWN;
    }
    final var ownVariables = own
        .stream()
        .map(MultiInstanceElement::indexVariable)
        .collect(Collectors.toSet());
    // keyed by the index variable, which is the one name two levels cannot share. Ids which
    // differ only in characters a variable name may not hold mean one level. The job carries
    // the later of the two.
    final var levels = new LinkedHashMap<String, MultiInstanceElement>();
    final var values = new LinkedHashMap<String, Object>();
    for (final var entry : entries) {
      if (!(entry instanceof Map<?, ?> caller)) {
        somethingWasDiscarded(bpmnProcessId, "one of its entries does not describe a caller");
        continue;
      }
      if (!(caller.get(CALLER_KEY) instanceof String callerBpmnProcessId) || callerBpmnProcessId.isBlank()) {
        somethingWasDiscarded(bpmnProcessId, "one of its entries names no calling BPMN process");
        continue;
      }
      if (!registry.acceptsTheChainOf(bpmnProcessId, callerBpmnProcessId)) {
        log
            .debug(
                "Camunda8: a job of BPMN process '{}' was handed the iteration chain of '{}', "
                    + "which is not a process of the same workflow module working on the same "
                    + "workflow aggregate, so that entry is left out. A call activity naming its "
                    + "process by an expression hands its chain to whatever process it reaches: "
                    + "across the boundary of a workflow module this adapter never saw the "
                    + "caller's model, and a process with a workflow aggregate of its own runs a "
                    + "business case of its own and is told no iteration of whoever called it.",
                bpmnProcessId,
                callerBpmnProcessId);
        continue;
      }
      if (!(caller.get(LEVELS_KEY) instanceof List<?> reportedLevels)) {
        somethingWasDiscarded(bpmnProcessId, "the entry of caller '%s' lists no levels"
            .formatted(callerBpmnProcessId));
        continue;
      }
      for (final var reportedLevel : reportedLevels) {
        if (!(reportedLevel instanceof Map<?, ?> reported)) {
          somethingWasDiscarded(
              bpmnProcessId,
              "something in the levels of caller '%s' does not describe a level"
                  .formatted(callerBpmnProcessId));
          continue;
        }
        if (!(reported.get(ELEMENT_KEY) instanceof String levelElementId) || levelElementId.isBlank()) {
          somethingWasDiscarded(bpmnProcessId, "a level of caller '%s' names no BPMN element"
              .formatted(callerBpmnProcessId));
          continue;
        }
        final var level = asReported(levelElementId, reported);
        if (ownVariables.contains(level.indexVariable())) {
          // this process iterates an element of that id itself. So the values of the caller
          // are overwritten in a scope further in, and the job carries the inner ones.
          continue;
        }
        // the same id twice along the chain is nesting and not a choice. So the later level
        // replaces the earlier one and takes its place at the end. appendLevel does the same
        // while deploying.
        levels.remove(level.indexVariable());
        levels.put(level.indexVariable(), level);
        values.put(level.indexVariable(), reported.get(INDEX_KEY));
        if (level.totalVariable() != null) {
          values.put(level.totalVariable(), reported.get(TOTAL_KEY));
        }
        if (level.elementVariable() != null) {
          values.put(level.elementVariable(), reported.get(ITEM_KEY));
        }
      }
    }
    return levels.isEmpty()
        ? NOTHING_HANDED_DOWN
        : new HandedDownChain(List.copyOf(levels.values()), values);

  }

  /**
   * One handed-down level, described the way the deployment describes one it knows. The
   * variable names are derived from the element id. That is why the two sources cannot be
   * told apart afterwards.
   * <p>
   * A key which is absent and a key whose value is <code>null</code> are the same thing here.
   * The two cannot be told apart in a job. The entry leaves the total out where the element
   * has no input collection. And an expression which reached into nothing writes the same
   * <code>null</code>. Both mean the value is not reported.
   */
  private static MultiInstanceElement asReported(
      final String elementId,
      final Map<?, ?> reported) {

    final var suffix = variableSuffix(elementId);
    return new MultiInstanceElement(
        elementId, VARIABLE_PREFIX
            + "Index_"
            + suffix, reported.get(TOTAL_KEY) == null
                ? null
                : VARIABLE_PREFIX
                    + "Total_"
                    + suffix, reported.get(ITEM_KEY) == null
                        ? null
                        : VARIABLE_PREFIX
                            + "Element_"
                            + suffix);

  }

  /**
   * Says at DEBUG that something of {@link #CHAIN_VARIABLE} was left out. A WARN per job would
   * repeat itself for every job of an application which uses the name on purpose. And nobody
   * could do anything about a job which already ran.
   */
  private static void somethingWasDiscarded(
      final String bpmnProcessId,
      final String why) {

    log
        .debug(
            "Camunda8: part of the variable '{}' of a job of BPMN process '{}' is read as if it "
                + "were not there, because {}. VanillaBP hands the iteration chain of a call "
                + "activity naming its process by an expression down in that variable, and "
                + "nothing stops an application from writing that name itself. Where it does, no "
                + "iteration of the caller is reported.",
            CHAIN_VARIABLE,
            bpmnProcessId,
            why);

  }

  /**
   * Builds what the SPI asks for out of the variables a job carries.
   *
   * @param chain The multi-instance elements enclosing the element of the job, outermost
   *          first
   * @param variables The variables of the job
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
        // two reasons, and a job cannot tell them apart. The workflow may run on a process
        // version deployed before this adapter knew about multi-instance. Or the element
        // belongs to a place the process is called from, and this workflow did not come
        // through it. The union over the call sites makes that an everyday case. The
        // message of the core names what was supplied.
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
      // Camunda 8 counts iterations from 1. The SPI counts from 0, like Camunda 7 does.
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
   * The variable into which every iteration of a multi-instance element writes its own
   * index. The deployment sets this up. It is a local variable of that iteration. So the
   * scope the cluster reports for it IS the iteration.
   *
   * @param elementId The BPMN ID of the multi-instance element
   * @return The name of the variable
   */
  public static String indexVariableOf(
      final String elementId) {

    return VARIABLE_PREFIX
        + "Index_"
        + variableSuffix(elementId);

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
   * The process a flow element belongs to. A model file may hold several.
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
