package io.vanillabp.camunda8.processservice;

/**
 * Builds the values Camunda 8's search API compares variables against.
 * <p>
 * The cluster stores every variable as JSON and the query API compares against that
 * JSON, verbatim: the client passes a filter value through without encoding it. A
 * variable holding the string <code>4711</code> therefore matches the filter value
 * <code>"4711"</code> - with the quotes - and never the plain <code>4711</code>.
 * Getting this wrong is invisible: the search simply returns nothing, which reads
 * exactly like "no such workflow".
 * <p>
 * VanillaBP writes the workflow aggregate's ID as a STRING (see
 * {@code Camunda8ProcessService#variablesOf}, which sends
 * <code>workflowAggregateId.toString()</code>), no matter which type the aggregate's
 * ID attribute has. The filter has to follow that decision rather than the Java type
 * at hand, which is why this class quotes unconditionally - the two belong together
 * and a test pins them.
 * <p>
 * Public because an extension searching the cluster for a workflow by its aggregate id
 * has to spell the filter value the same way this adapter does. It is one expression, which
 * is the reason it must not be written twice rather than a reason it may be: a divergence
 * returns an empty result, and that reads like "no such workflow".
 */
public final class Camunda8VariableFilters {

  private Camunda8VariableFilters() {
  }

  /**
   * The JSON representation of an aggregate ID as the cluster holds it.
   * <p>
   * A missing ID is refused rather than quoted. <code>null</code> is a perfectly
   * searchable JSON value, so quoting it would send the cluster a filter matching
   * variables which really hold the four letters, and the empty answer to that search is
   * the one this class exists to prevent. The caller has an ID it did not resolve.
   *
   * @param workflowAggregateId The aggregate's ID
   * @return The quoted, escaped JSON string
   * @throws IllegalArgumentException Where no ID was given
   */
  public static String aggregateIdSearchValue(
      final Object workflowAggregateId) {

    if (workflowAggregateId == null) {
      throw new IllegalArgumentException(
          "Cannot build a Camunda 8 variable filter for a workflow aggregate ID which is null. "
              + "The filter would search for the JSON value \"null\" and answer nothing, which "
              + "cannot be told apart from a workflow the cluster does not hold. Resolve the "
              + "aggregate's ID before searching for it.");
    }
    return "\"%s\"".formatted(
        String
            .valueOf(workflowAggregateId)
            .replace("\\", "\\\\")
            .replace("\"", "\\\""));

  }

}
