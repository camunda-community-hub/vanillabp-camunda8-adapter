package io.vanillabp.camunda8.wiring;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which process variables a worker of this adapter asks the cluster for.
 *
 * <p>
 * A Camunda 8 worker which names none gets the COMPLETE variable scope of the process
 * instance with every job. Camunda describes that as "tens or more variables, of arbitrary
 * size" and recommends against it. VanillaBP is in a better position than a plain client
 * user. The workflow aggregate is the source of truth. So a handler is served from the
 * own database of the application, and the job has to carry only what the adapter reads
 * out of it.
 * </p>
 *
 * <p>
 * That is a short and computable list, and every entry of it is here:
 * </p>
 * <ul>
 * <li>the <strong>ID of the workflow aggregate</strong>, in the variable named after the
 * ID attribute of the aggregate
 * ({@code WorkflowTaskWiring#resolveWorkflowAggregateIdName}). Every one of the four
 * worker kinds starts by reading it. The name belongs to the BPMN process, not to the
 * adapter. So a worker serving two processes with different names carries both
 * names.</li>
 * <li>the <strong>multi-instance context</strong> of the element the job belongs to:
 * the index, the total and the element of every iteration enclosing it. They are in the
 * variables which {@link Camunda8MultiInstance} injects while the model is deployed.
 * Which ones those are depends on the element. That is why this list is not a constant.
 * The one part of it which IS a constant is {@link Camunda8MultiInstance#CHAIN_VARIABLE}.
 * A caller which names the process it calls by an expression writes its iteration chain
 * there. No deployment can say which process that call reaches.</li>
 * <li>every variable a {@code @TaskParam} of the served tasks reads
 * ({@code WorkflowTaskInvoker#taskParameterNames}). Those names live on the handler
 * methods. The core reads them from the annotations while the application wires itself.
 * So this part of the list says what the application asks for. It is not guessed from
 * the model. The core is asked with the job type AND with the element id, because a
 * method wired by {@code @WorkflowTask(id = ...)} answers to the element alone. The
 * workflow-end listener is left out. A {@code @WorkflowEnded} method cannot declare a
 * {@code @TaskParam} at all, so the ID of the aggregate is its complete list.</li>
 * </ul>
 *
 * <p>
 * So what stays out is what only the aggregate sync wrote into the instance. That is a copy
 * of the data the handler already holds. On a workflow aggregate with a few large
 * attributes, it is all of what Camunda warns about.
 * </p>
 *
 * <p>
 * <strong>The list belongs to the WORKER, not to the delivery.</strong> A worker
 * subscribes to a job type. It serves every task of the workflow module which uses it,
 * across BPMN processes. So its list is the union over everything it serves. The gateway
 * also compares the list when it decides whether two job streams are equivalent. So it has
 * to be the same on every node and after every restart of one application version. That
 * is why it is sorted, and derived from the deployed models and not from the iteration
 * order of a hash map.
 * </p>
 *
 * <p>
 * <strong>Where the derivation cannot win.</strong> Some start events are fired by the
 * cluster itself. A worker serving them asks for everything, because VanillaBP copies every
 * variable such a start carries into the workflow aggregate
 * ({@code BpmsInitiatedStartContext#getVariables}). There is no list to derive. Apart from
 * that one worker kind, a statically named {@code @TaskParam} is always covered. A name no
 * annotation carries, read through a path the scanner cannot see, is not fetched. Then the
 * delivery fails with a message which says so. It does not quietly hand the method a
 * <code>null</code>. The handler reads such a value from the workflow aggregate instead.
 * </p>
 * <p>
 * There is no setting which asks for more. The key <code>fetch-variables</code> did that in
 * snapshots of version 2.0 and was removed. A handler reads its data from the workflow
 * aggregate. A key which is still set ends the start ({@link #rejectTheRemovedKey}).
 * </p>
 * <p>
 * Why the list is derived per worker, why it is sorted, and why a name outside it fails the
 * delivery instead of arriving as null, is decision 8 in the repository's DECISIONS.md.
 */
public final class Camunda8FetchVariables {

  private Camunda8FetchVariables() {
  }

  /**
   * What one worker asks for: either the complete scope, or the names below.
   *
   * @param all Whether the worker asks for every variable of the process instance
   * @param names The variable names to fetch, sorted. Empty while {@link #all} is
   *          <code>true</code>
   */
  public record Selection(boolean all,
                          List<String> names) {

    /**
     * The selection of a worker which asks for everything.
     *
     * @return A selection asking for the complete variable scope
     */
    public static Selection everything() {

      return new Selection(true, List.of());

    }

    /**
     * The selection of a worker which asks for named variables only.
     *
     * @param names The variable names, in any order
     * @return A selection asking for those names. They are sorted, so it is stable across
     *         restarts
     */
    public static Selection of(
        final Collection<String> names) {

      return new Selection(false, List.copyOf(new TreeSet<>(names)));

    }

    /**
     * Whether this selection brings one variable along. A guiding message about a variable
     * nobody fetched is built on it.
     *
     * @param name A variable name
     * @return Whether a job of this worker carries that variable
     */
    public boolean covers(
        final String name) {

      return all || names.contains(name);

    }

    /**
     * The selection in words, for a reader rather than for the cluster.
     *
     * @return What the startup line and the guiding messages call this selection
     */
    public String describe() {

      return all
          ? "all variables of the process instance"
          : names.toString();

    }

  }

  /**
   * The last part of the removed key, at every level it could be set at.
   */
  public static final String REMOVED_KEY = "fetch-variables";

  /**
   * Ends the start where somebody still sets the removed key <code>fetch-variables</code>.
   * Ignoring it would be silent. A value of <code>all</code> was set for a handler which
   * reads a variable nobody declared. That handler would now fail its delivery long after
   * the start. The message names every key it found and says what applies instead.
   *
   * @param adapterId The adapter id
   * @param keys The full keys which set it, at the adapter level and below. Empty where
   *          nobody does
   * @throws IllegalStateException If any key sets it
   */
  public static void rejectTheRemovedKey(
      final String adapterId,
      final List<String> keys) {

    if ((keys == null) || keys.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        """
            Camunda 8 adapter '%s' is configured with '%s', which does not exist any more:
              %s
            Remove the key. A worker now always asks the cluster for the variables VanillaBP \
            reads: the variable holding the workflow aggregate's ID, the multi-instance \
            variables of the element and every name a @TaskParam of the served tasks declares. \
            A handler which needs more reads it from the workflow aggregate."""
            .formatted(adapterId, REMOVED_KEY, String.join("\n  ", keys)));

  }

  /**
   * What a delivery says when the variable holding the ID of the workflow aggregate is not
   * there. A worker always asks for that variable. So the cause is the process instance: a
   * workflow started past VanillaBP, a model which removed the variable, or a call activity
   * which did not hand it to the called process. The last one is a call activity saying
   * <code>propagateAllParentVariables="false"</code> without an input mapping of the ID. The
   * deployment leaves it as modelled (see decision 77 in the repository's DECISIONS.md). The
   * message still names the fetched list, because a reader checks that first.
   *
   * @param what What kind of job it is, capitalized ("Job", "The user-task listener job")
   * @param jobKey The key of the job
   * @param taskDefinition The task definition, as the core knows it
   * @param bpmnProcessId The BPMN process id, as the core knows it
   * @param aggregateIdName The variable the ID of the aggregate was expected in
   * @param selection What this worker fetches
   * @return The message
   */
  public static String missingAggregateId(
      final String what,
      final long jobKey,
      final String taskDefinition,
      final String bpmnProcessId,
      final String aggregateIdName,
      final Selection selection) {

    return """
        %s '%s' (type '%s') of BPMN process '%s' carries no variable '%s' holding the workflow \
        aggregate's ID! Its worker fetches %s. Either the workflow was not started through \
        VanillaBP (the variable is written on start), or something in the process removed or \
        overwrote that variable. Where '%s' is called by a call activity, check that call \
        activity as well: with propagateAllParentVariables="false" the caller's variables stay \
        behind, the ID among them, so add an input mapping which hands '%s' over."""
        .formatted(
            what,
            jobKey,
            taskDefinition,
            bpmnProcessId,
            aggregateIdName,
            selection.describe(),
            bpmnProcessId,
            aggregateIdName);

  }

  /**
   * What a delivery says when a <code>&#64;TaskParam</code> names a variable this worker
   * did not fetch. The adapter cannot tell that case apart from a variable which is really
   * absent. Handing the method a <code>null</code> would silently lose what the model
   * computed. So the delivery fails. On this BPMS that means retries, and then an incident
   * which names the way out.
   * <p>
   * The worker asks for every name a <code>&#64;TaskParam</code> of the tasks it serves
   * DECLARES. So getting here means the name was not declared on the method. It was put
   * together at runtime, or read past the annotation. The message says so, because a reader
   * checks the annotation first, and the annotation is right there on the method.
   *
   * @param name The variable the method asked for
   * @param taskDefinition The task definition, as the core knows it
   * @param selection What this worker fetches
   * @return The message
   */
  public static String unfetchedTaskParameter(
      final String name,
      final String taskDefinition,
      final Selection selection) {

    return """
        The @WorkflowTask method serving '%s' reads the process variable '%s', but its worker \
        does not fetch that variable: it fetches %s. A worker asks for every name a @TaskParam \
        of its tasks declares, so this name reached the delivery some other way - through a \
        value computed at runtime rather than through @TaskParam("%s"). Either declare it that \
        way, or read the value from the workflow aggregate, which is what VanillaBP is about."""
        .formatted(taskDefinition, name, selection.describe(), name);

  }

  /**
   * The variables one served element contributes: the ID of the workflow aggregate, the
   * multi-instance context of the iterations enclosing that element, and the chain which a
   * caller hands down when it names its process by an expression.
   * <p>
   * That last name is a constant, and every worker serving an element asks for it. No
   * deployment knows which process an expression can reach. So there is no list to derive.
   * The chain is model knowledge of the CALLER, and the called process learns it from that
   * variable alone. Where the variable is absent, the key does not even reach the job. So a
   * worker of a process nobody calls that way pays nothing for it.
   *
   * @param variables Where the names are collected
   * @param aggregateIdName The name of the ID variable of the aggregate, in the BPMN
   *          process of the element
   * @param chain The multi-instance elements enclosing the element, from
   *          {@link Camunda8MultiInstance.Registry#chainOf(String, String)}
   */
  public static void collect(
      final Set<String> variables,
      final String aggregateIdName,
      final List<Camunda8MultiInstance.MultiInstanceElement> chain) {

    if (aggregateIdName != null) {
      variables.add(aggregateIdName);
    }
    variables.add(Camunda8MultiInstance.CHAIN_VARIABLE);
    for (final var element : chain) {
      variables.add(element.indexVariable());
      if (element.totalVariable() != null) {
        variables.add(element.totalVariable());
      }
      if (element.elementVariable() != null) {
        variables.add(element.elementVariable());
      }
    }

  }

}
