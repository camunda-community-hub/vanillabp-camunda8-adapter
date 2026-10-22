package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A key of VanillaBP 1 which this version does not read gets one WARN which names what to write
 * instead. A key this version reads gets none, and neither does a key of another adapter.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8VersionOneKeysTest {

  private static List<String> warningsFor(
      final String... propertyNames) {

    final var warnings = new ArrayList<String>();
    Camunda8VersionOneKeys.reportKeysOfVersionOne(Set.of("camunda8"), List.of(propertyNames), warnings::add);
    return warnings;

  }

  @Test
  @DisplayName("task-timeout names job-timeout at the same level")
  public void taskTimeoutNamesJobTimeout() {

    final var warnings = warningsFor(
        "vanillabp.workflow-modules.Demo.workflows.Loan.tasks.check.adapters.camunda8.task-timeout");

    assertEquals(1, warnings.size(), warnings::toString);
    assertTrue(
        warnings
            .getFirst()
            .contains(
                "Write 'vanillabp.workflow-modules.Demo.workflows.Loan.tasks.check.adapters.camunda8.job-timeout'"),
        warnings.getFirst());

  }

  @Test
  @DisplayName("A worker key below the adapter level names the adapter level, and its new name")
  public void aWorkerKeyNamesTheAdapterLevel() {

    final var warnings = warningsFor(
        "vanillabp.workflow-modules.Demo.adapters.camunda8.poll-request-timeout",
        "vanillabp.workflow-modules.Demo.workflows.Loan.adapters.camunda8.stream-enabled",
        "vanillabp.workflow-modules.Demo.adapters.camunda8.pollInterval",
        "vanillabp.workflow-modules.Demo.adapters.camunda8.stream-timeout");

    assertEquals(4, warnings.size(), warnings::toString);
    assertTrue(warnings.get(0).contains("'vanillabp.adapters.camunda8.request-timeout'"), warnings.get(0));
    assertTrue(warnings.get(1).contains("'vanillabp.adapters.camunda8.stream-enabled'"), warnings.get(1));
    assertTrue(warnings.get(2).contains("'vanillabp.adapters.camunda8.poll-interval'"), warnings.get(2));
    assertTrue(warnings.get(3).contains("'vanillabp.adapters.camunda8.stream-timeout'"), warnings.get(3));

  }

  @Test
  @DisplayName("use-tenants, task-id-as-hex-string and the old places of allow-connectors are named")
  public void theRemovedAndMovedKeysAreNamed() {

    final var warnings = warningsFor(
        "vanillabp.workflow-modules.Demo.adapters.camunda8.use-tenants",
        "vanillabp.workflow-modules.Demo.adapters.camunda8.task-id-as-hex-string",
        "vanillabp.allow-connectors",
        "vanillabp.workflow-modules.Demo.allow-connectors",
        "vanillabp.workflow-modules.Demo.workflows.Loan.allow-connectors");

    assertEquals(5, warnings.size(), warnings::toString);
    assertTrue(warnings.get(0).contains("name-clash-avoidance: none"), warnings.get(0));
    assertTrue(warnings.get(1).contains("always decimal"), warnings.get(1));
    assertTrue(warnings.get(2).contains("'vanillabp.adapters.<id>.allow-connectors'"), warnings.get(2));
    assertTrue(
        warnings.get(3).contains("'vanillabp.workflow-modules.Demo.adapters.<id>.allow-connectors'"),
        warnings.get(3));
    assertTrue(
        warnings.get(4).contains("'vanillabp.workflow-modules.Demo.workflows.Loan.adapters.<id>.allow-connectors'"),
        warnings.get(4));

  }

  @Test
  @DisplayName("Keys this version reads, and keys of another adapter, get no warning")
  public void keysThisVersionReadsAreLeftAlone() {

    final var warnings = warningsFor(
        "vanillabp.adapters.camunda8.poll-interval",
        "vanillabp.adapters.camunda8.stream-timeout",
        "vanillabp.adapters.camunda8.allow-connectors",
        "vanillabp.workflow-modules.Demo.adapters.camunda8.allow-connectors",
        "vanillabp.workflow-modules.Demo.adapters.camunda8.retry-backoff",
        "vanillabp.workflow-modules.Demo.adapters.camunda8.tenant-id",
        "vanillabp.workflow-modules.Demo.adapters.camunda8.report-errors-as-stack-trace",
        "vanillabp.workflow-modules.Demo.adapters.camunda7.task-timeout",
        "camunda.client.worker.defaults.timeout",
        "VANILLABP_WORKFLOWMODULES_DEMO_ADAPTERS_CAMUNDA8_TASKTIMEOUT");

    assertEquals(List.of(), warnings);

  }

  @Test
  @DisplayName("A key found in two property sources is reported once")
  public void aKeyIsReportedOnce() {

    final var key = "vanillabp.workflow-modules.Demo.adapters.camunda8.task-timeout";

    assertEquals(1, warningsFor(key, key).size());

  }

}
