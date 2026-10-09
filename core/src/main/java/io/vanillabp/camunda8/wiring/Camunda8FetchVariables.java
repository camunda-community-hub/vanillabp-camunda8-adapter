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
 * instance with every job, which Camunda describes as "tens or more variables, of
 * arbitrary size" and recommends against. VanillaBP is in a better position than a plain
 * client user: the workflow aggregate is the source of truth, so a handler is served from
 * the application's own database and the job itself has to carry only what the adapter
 * reads out of it.
 * </p>
 *
 * <p>
 * That is a short and computable list, and every entry of it is here:
 * </p>
 * <ul>
 * <li>the <strong>workflow aggregate's ID</strong>, in the variable named after the
 * aggregate's ID attribute
 * ({@code WorkflowTaskWiring#resolveWorkflowAggregateIdName}). Every one of the four
 * worker kinds starts by reading it, and the name is a property of the BPMN process
 * rather than of the adapter, so a worker serving two processes which disagree carries
 * both names;</li>
 * <li>the <strong>multi-instance context</strong> of the element the job belongs to:
 * the index, the total and the element of every iteration enclosing it,
 * in the variables {@link Camunda8MultiInstance} injects while the model is deployed.
 * Which ones those are depends on the element, which is why this list is not a
 * constant. The one part of it which IS a constant is
 * {@link Camunda8MultiInstance#CHAIN_VARIABLE}: a caller naming the process it calls by an
 * expression writes its iteration chain there, and no deployment can say which process that
 * call reaches;</li>
 * <li>every variable a {@code @TaskParam} of the served tasks reads
 * ({@code WorkflowTaskInvoker#taskParameterNames}). Those names live on the
 * handler methods, and the core reads them off the annotations while the application
 * wires itself - so this part of the list says what the application asks for instead of
 * guessing it from the model. The core is asked with the job type AND with the element
 * id, because a method wired by {@code @WorkflowTask(id = ...)} answers to the element
 * alone. The workflow-end listener is left out of it: a
 * {@code @WorkflowEnded} method cannot declare a {@code @TaskParam} at all, so the
 * aggregate's ID is its complete list.</li>
 * </ul>
 *
 * <p>
 * What stays out is therefore what only the aggregate sync wrote into the instance: a copy
 * of the data the handler already holds, which on a workflow aggregate with a few large
 * attributes is the whole of what Camunda warns about.
 * </p>
 *
 * <p>
 * <strong>The list belongs to the WORKER, not to the delivery.</strong> A worker
 * subscribes to a job type and serves every task of the workflow module using it, across
 * BPMN processes, so its list is the union over everything it serves. The list is also
 * part of what the gateway compares when it decides whether two job streams are
 * equivalent, so it has to be the same on every node and after every restart
 * of one application version: it is therefore sorted, and derived from the deployed
 * models rather than from the iteration order of a hash map.
 * </p>
 *
 * <p>
 * <strong>Where the derivation cannot win.</strong> A worker serving the start events the
 * cluster fires itself asks for everything, because VanillaBP copies every variable such a
 * start carries into the workflow aggregate
 * ({@code BpmsInitiatedStartContext#getVariables}) - there is no list to derive. Apart
 * from that one worker kind, a statically named {@code @TaskParam} is covered by
 * construction. A name no annotation carries, read through a path the scanner cannot see,
 * is not fetched: the delivery fails with a message which says so rather than quietly
 * handing the method a <code>null</code>. The handler reads such a value from the workflow
 * aggregate instead.
 * </p>
 * <p>
 * There is no setting which asks for more. The key <code>fetch-variables</code> did that in
 * snapshots of version 2.0 and was removed: a handler reads its data from the workflow
 * aggregate.
 * A key still set ends the start ({@link #rejectTheRemovedKey}).
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
   * @param names The variable names to fetch, sorted; empty while {@link #all} is
   *          <code>true</code>
   */
  public record Selection(boolean all,
                          List<String> names) {

    /**
     * The selection of a worker which asks for everything, which is what mode <code>all</code>
     * ends up as.
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
     * @return A selection asking for those names, sorted so it is stable across
     *         restarts
     */
    public static Selection of(
        final Collection<String> names) {

      return new Selection(false, List.copyOf(new TreeSet<>(names)));

    }

    /**
     * Whether this selection brings one variable along, which is what a guiding message about a
     * variable nobody fetched is built on.
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
   * Ignoring it would be silent: a value of <code>all</code> was set for a handler which
   * reads a variable nobody declared, and that handler would now fail its delivery long
   * after the start. The message names every key it found and says what applies instead.
   *
   * @param adapterId The adapter id
   * @param keys The full keys which set it, at the adapter level and below; empty where
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
   * What a delivery says when the variable holding the workflow aggregate's ID is not
   * there. A worker always asks for that variable, so the cause is the process instance: a
   * workflow started past VanillaBP, a model which removed the variable, or a call activity
   * which did not hand it to the called process. The last one is a call activity saying
   * <code>propagateAllParentVariables="false"</code> without an input mapping of the ID, which
   * the deployment leaves as modelled (see decision 77 in the repository's DECISIONS.md). The
   * message still names the fetched list, because that is the first thing a reader checks.
   *
   * @param what What kind of job it is, capitalized ("Job", "The user-task listener job")
   * @param jobKey The job's key
   * @param taskDefinition The task definition, as the core knows it
   * @param bpmnProcessId The BPMN process id, as the core knows it
   * @param aggregateIdName The variable the aggregate's ID was expected in
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
   * did not fetch. The adapter cannot tell that case apart from a variable which is
   * genuinely absent, and handing the method a <code>null</code> would be a silent loss
   * of what the model computed - so the delivery fails, which on this BPMS means retries
   * and then an incident naming the way out.
   * <p>
   * The worker asks for every name a <code>&#64;TaskParam</code> of the tasks it serves
   * DECLARES, so getting here means the name was not declared on the
   * method: it was assembled at runtime, or read past the annotation. The message says
   * so, because the first thing a reader will check is the annotation, and it is right
   * there.
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
   * The variables one served element contributes: the workflow aggregate's ID, the
   * multi-instance context of the iterations enclosing that element, and the chain a caller
   * naming its process by an expression hands down.
   * <p>
   * That last name is a constant, and every worker serving an element asks for it. No
   * deployment knows who can be reached by an expression, so there is no list to derive: the
   * chain is model knowledge of the CALLER, and the called process learns it from that
   * variable alone. Where the variable is absent the key does not even reach the job, so a
   * worker of a process nobody calls that way pays nothing for it.
   *
   * @param variables Where the names are collected
   * @param aggregateIdName The name of the aggregate's ID variable of the element's
   *          BPMN process
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
