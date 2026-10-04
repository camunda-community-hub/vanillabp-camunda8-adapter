package io.vanillabp.camunda8.analysis895;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ProcessInstanceState;

/**
 * Says after a run whether every piece of work ended exactly once. Reads the database of
 * the stopped application and the read model of the cluster, which has caught up by then.
 * <p>
 * Arguments: the H2 file without its suffix, the REST address of the cluster, and
 * optionally the start and the end of the disturbance in epoch milliseconds, which splits
 * the workflows into those started before, during and after it.
 */
public final class Check895 {

  private Check895() {
  }

  /**
   * Runs the check and prints the report.
   *
   * @param args See the type comment
   * @throws Exception Whatever goes wrong - this is a measuring tool
   */
  public static void main(
      final String[] args) throws Exception {

    final var database = args[0];
    final var rest = args[1];
    final var from = args.length > 2 ? Long.parseLong(args[2]) : Long.MAX_VALUE;
    final var to = args.length > 3 ? Long.parseLong(args[3]) : Long.MAX_VALUE;
    try (Connection db = DriverManager.getConnection("jdbc:h2:file:"
        + database, "sa", "sa"); CamundaClient client = CamundaClient
            .newClientBuilder()
            .restAddress(URI.create(rest))
            .preferRestOverGrpc(true)
            .build()) {
      report(db, client, from, to);
    }

  }

  private static String phaseOf(
      final long startedAt,
      final long from,
      final long to) {

    if (startedAt < from) {
      return "before";
    }
    return startedAt < to ? "during" : "after";

  }

  private static void report(
      final Connection db,
      final CamundaClient client,
      final long from,
      final long to) throws SQLException {

    final var aggregates = new LinkedHashMap<Long, Map<String, Object>>();
    try (var rs = db
        .createStatement()
        .executeQuery("SELECT ID, STAGE, ENDED_AS, STARTED_AT FROM A895_AGGREGATE ORDER BY ID")) {
      while (rs.next()) {
        final var row = new LinkedHashMap<String, Object>();
        row.put("stage", rs.getString(2));
        row.put("ended", rs.getString(3));
        row.put("phase", phaseOf(rs.getLong(4), from, to));
        aggregates.put(rs.getLong(1), row);
      }
    }
    final var effects = new TreeMap<Long, Map<String, Integer>>();
    final var effectTasks = new TreeMap<String, Integer>();
    try (var rs = db
        .createStatement()
        .executeQuery("SELECT AGGREGATE_ID, EFFECT, TASK_ID FROM A895_EFFECT")) {
      while (rs.next()) {
        effects
            .computeIfAbsent(rs.getLong(1), id -> new TreeMap<>())
            .merge(rs.getString(2), 1, Integer::sum);
        effectTasks.merge(rs.getLong(1)
            + "|"
            + rs.getString(2)
            + "|"
            + rs.getString(3), 1, Integer::sum);
      }
    }
    System.out.printf("workflows started: %d%n", aggregates.size());
    final var problems = new TreeMap<String, List<Long>>();
    final var byPhase = new TreeMap<String, Integer>();
    for (final var entry : aggregates.entrySet()) {
      final var id = entry.getKey();
      final var row = entry.getValue();
      final var phase = (String) row.get("phase");
      byPhase.merge(phase, 1, Integer::sum);
      final var effect = effects.getOrDefault(id, Map.of());
      for (final var expected : List.of("work", "check", "approve-created", "finish", "ended-completed")) {
        final var count = effect.getOrDefault(expected, 0);
        if (count == 0) {
          problems.computeIfAbsent(phase
              + ": no "
              + expected, k -> new ArrayList<>()).add(id);
        } else if (count > 1) {
          problems.computeIfAbsent(phase
              + ": "
              + expected
              + " x"
              + count, k -> new ArrayList<>()).add(id);
        }
      }
      if (!"CORRELATED".equals(row.get("stage"))) {
        problems.computeIfAbsent(phase
            + ": driver stopped at "
            + row.get("stage"), k -> new ArrayList<>()).add(id);
      }
      final var instances = client
          .newProcessInstanceSearchRequest()
          .filter(filter -> filter.variables(Map.of("id", "\""
              + id
              + "\"")))
          .send()
          .join()
          .items()
          .stream()
          .filter(instance -> instance.getProcessDefinitionId().endsWith("ReadModelProcess"))
          .toList();
      if (instances.size() != 1) {
        problems.computeIfAbsent(phase
            + ": instances x"
            + instances.size(), k -> new ArrayList<>()).add(id);
      }
      for (final var instance : instances) {
        if (instance.getState() != ProcessInstanceState.COMPLETED) {
          problems.computeIfAbsent(phase
              + ": instance "
              + instance.getState(), k -> new ArrayList<>()).add(id);
        }
        final var key = instance.getProcessInstanceKey();
        final var variables = client
            .newVariableSearchRequest()
            .filter(filter -> filter.processInstanceKey(key))
            .send()
            .join()
            .items();
        final var taskMarkerInTheSubprocess = variables
            .stream()
            .anyMatch(
                variable -> "taskMarker".equals(variable.getName()) && !key.equals(variable.getScopeKey()) && variable
                    .getValue().contains("t-"
                        + id));
        if (!taskMarkerInTheSubprocess) {
          problems.computeIfAbsent(phase
              + ": task-scoped push never reached the subprocess", k -> new ArrayList<>()).add(id);
        }
      }
    }
    System.out.printf("by phase of their start: %s%n", byPhase);
    final var repeatedWithTheSameTask = effectTasks.entrySet().stream().filter(e -> e.getValue() > 1).toList();
    System.out.printf("effects written twice for the same task id: %d %s%n", repeatedWithTheSameTask.size(),
        repeatedWithTheSameTask.stream().limit(10).toList());
    System.out.println("--- problems (empty = every workflow ended exactly once)");
    problems.forEach((
        problem,
        ids) -> System.out.printf("%s: %d %s%n", problem, ids.size(), ids.stream().limit(15).toList()));
    System.out.println("--- outbox");
    print(db,
        "SELECT OPERATION, STATUS, COUNT(*), MAX(ATTEMPTS) FROM VANILLABP_PHASE_TWO_OUTBOX GROUP BY OPERATION, STATUS ORDER BY 1, 2");
    print(db,
        "SELECT OPERATION, AGGREGATE_ID, STATUS, ATTEMPTS FROM VANILLABP_PHASE_TWO_OUTBOX WHERE STATUS <> 'DONE' ORDER BY 1, 2 LIMIT 40");
    System.out.println("--- failures the application saw in phase one, per operation and exception");
    print(db,
        "SELECT OPERATION, EXCEPTION, COUNT(*), COUNT(DISTINCT AGGREGATE_ID), MAX(TOOK_MILLIS), AVG(TOOK_MILLIS) FROM A895_FAILURE GROUP BY OPERATION, EXCEPTION ORDER BY 1, 2");
    print(db,
        "SELECT OPERATION, LEFT(MESSAGE, 160), COUNT(*) FROM A895_FAILURE GROUP BY OPERATION, LEFT(MESSAGE, 160) ORDER BY 3 DESC LIMIT 12");
    System.out.println("--- phase one that succeeded, duration per operation");
    print(db,
        "SELECT OPERATION, COUNT(*), MAX(TOOK_MILLIS), AVG(TOOK_MILLIS) FROM A895_OPERATION GROUP BY OPERATION ORDER BY 1");
    System.out.println("--- delivery log");
    print(db,
        "SELECT TASK_DEFINITION, OUTCOME, COUNT(*) FROM VANILLABP_TASK_DELIVERY GROUP BY TASK_DEFINITION, OUTCOME ORDER BY 1, 2");
    final var incidents = client.newIncidentSearchRequest().send().join().page().totalItems();
    System.out.printf("--- incidents in the cluster: %d%n", incidents);
    client
        .newIncidentSearchRequest()
        .send()
        .join()
        .items()
        .stream()
        .limit(10)
        .forEach(incident -> System.out.printf("  %s %s %s %s%n", incident.getProcessInstanceKey(),
            incident.getErrorType(), incident.getState(),
            String.valueOf(incident.getErrorMessage()).replace('\n', ' ')));

  }

  private static void print(
      final Connection db,
      final String sql) throws SQLException {

    try (var rs = db.createStatement().executeQuery(sql)) {
      final var columns = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        final var line = new StringBuilder("  ");
        for (var i = 1; i <= columns; i++) {
          line.append(rs.getString(i)).append(i < columns ? " | " : "");
        }
        System.out.println(line);
      }
    }

  }

}
