package io.vanillabp.camunda8.processservice;

/**
 * Builds the values which the search API of Camunda 8 compares variables against.
 * <p>
 * The cluster stores every variable as JSON, and the query API compares against that
 * JSON exactly as it is. The client passes a filter value through without encoding it.
 * So a variable which holds the string <code>4711</code> matches the filter value
 * <code>"4711"</code> - with the quotes - and never the plain <code>4711</code>.
 * Nobody sees this mistake. The search simply returns nothing, and that looks exactly
 * like "no such workflow".
 * <p>
 * VanillaBP writes the ID of the workflow aggregate as a STRING (see
 * {@code Camunda8ProcessService#variablesOf}, which sends
 * <code>workflowAggregateId.toString()</code>), whatever type the ID attribute of the
 * aggregate has. The filter has to follow that decision, not the Java type at hand. That
 * is why this class always quotes. The two belong together, and a test pins them.
 * <p>
 * It is public, because an extension which searches the cluster for a workflow by its
 * aggregate id has to spell the filter value the same way as this adapter. It is one
 * expression, and so it must not be written twice. If two copies differ, the search returns
 * an empty result, and that looks like "no such workflow".
 */
public final class Camunda8VariableFilters {

  private Camunda8VariableFilters() {
  }

  /**
   * The JSON representation of an aggregate ID as the cluster holds it.
   * <p>
   * A missing ID is refused, not quoted. <code>null</code> is a JSON value which can be
   * searched for. Quoting it would send the cluster a filter which matches variables that
   * really hold those four letters. The empty answer to that search is exactly what this
   * class is there to prevent. The caller has an ID which it did not resolve.
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
