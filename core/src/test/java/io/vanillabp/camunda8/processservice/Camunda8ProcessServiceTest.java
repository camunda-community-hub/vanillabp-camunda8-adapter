package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Unit tests of {@link Camunda8ProcessService} that do not require a cluster: phase one
 * validates only (never contacts Camunda 8). Phase two (which creates the process
 * instance on the cluster) is covered end-to-end by {@code Camunda8DeploymentAndStartIT},
 * and here only for what it reports back to VanillaBP.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ProcessServiceTest {

  /**
   * What a probe is asked about.
   */
  private static final WorkflowScope SCOPE = WorkflowScope
      .of("test-module", "TestProcess");

  /** A minimal aggregate whose ID is configurable (including {@code null}). */
  private record Aggregate(Object id) {
  }

  private static AggregatePersistenceAware<Aggregate> persistence(
      final Object aggregateId) {

    return new AggregatePersistenceAware<>() {
      @Override
      public Class<Aggregate> getAggregateClass() {
        return Aggregate.class;
      }

      @Override
      public Aggregate save(
          final Aggregate aggregate) {
        return aggregate;
      }

      @Override
      public Object getAggregateId(
          final Aggregate aggregate) {
        return aggregateId;
      }
    };

  }

  private static Camunda8ProcessService<Aggregate> configuredService() {

    final var configuration = new Camunda8AdapterConfiguration();
    // a bogus address that is never contacted in phase one
    configuration.setRestAddress("http://localhost:1");
    // no waiting for the exporter in a unit test - the cluster is never contacted
    configuration.setWorkflowVisibilityTimeout(Duration.ZERO);
    return new Camunda8ProcessService<>(
        "c8", new Camunda8ClientFactory("c8", configuration), Duration
            .ofDays(14), (
                aggregateClass,
                check) -> check.run(), null);

  }

  @Test
  @DisplayName("phase one validates a configured adapter without contacting the cluster")
  public void phaseOneValidatesWithoutContactingCluster() {

    final var service = configuredService();

    assertDoesNotThrow(
        () -> PhaseOperations.phaseOne(service, PhaseOperation.START_WORKFLOW, "module",
            "Process", persistence("agg-1"), new Aggregate("agg-1"), Map.of()));

  }

  @Test
  @DisplayName("phase one fails naming the missing property if the adapter is not configured")
  public void phaseOneFailsIfNotConfigured() {

    final var service = new Camunda8ProcessService<Aggregate>(
        "c8", new Camunda8ClientFactory("c8", new Camunda8AdapterConfiguration()), Duration
            .ofDays(14), (
                aggregateClass,
                check) -> check.run(), null);

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> PhaseOperations.phaseOne(service, PhaseOperation.START_WORKFLOW, "module",
            "Process", persistence("agg-1"), new Aggregate("agg-1"), Map.of()));
    assertTrue(exception.getMessage().contains("vanillabp.adapters.c8.rest-address"));

  }

  @Test
  @DisplayName("phase two of a start reports the process instance key the cluster answered")
  public void phaseTwoOfAStartReportsTheProcessInstanceKey() {

    // a cluster which answers every create with the same key. On a real cluster the worker
    // of the start event writes the same key down a moment later, so only a test without
    // one shows that phase two reports it on its own
    final var clientFactory = mock(Camunda8ClientFactory.class, RETURNS_DEEP_STUBS);
    when(clientFactory
        .getClient()
        .newCreateInstanceCommand()
        .bpmnProcessId(anyString())
        .latestVersion()
        .variables(anyMap())
        .send()
        .join()
        .getProcessInstanceKey())
        .thenReturn(2251799813685249L);
    final var service = new Camunda8ProcessService<Aggregate>(
        "c8", clientFactory, Duration.ofDays(14), (
            aggregateClass,
            check) -> check.run(), null);

    final List<String> reported = new ArrayList<>();
    service
        .phaseOperations()
        .get(PhaseOperation.START_WORKFLOW)
        .phaseTwo(
            new PhaseTwoRequest<>(
                "module", "Process", null, "agg-1", Map.of(), reported::add));

    assertEquals(
        List.of("2251799813685249"),
        reported,
        "the key in the form a task delivery names its workflow, and reported once");

  }

  @Test
  @DisplayName("the re-dispatch probe never answers ACTIVE on a failing query - a recovered start must not be skipped")
  public void redispatchProbeIsNeverOptimisticOnFailure() {

    // an unreachable cluster must yield BPMS_UNAVAILABLE (the outbox entry stays
    // pending and is retried) - answering ACTIVE would SKIP a recovered start and
    // thereby lose the workflow
    final var awareness = configuredService().awarenessOfWorkflowForRedispatch(SCOPE, persistence("agg-1"), "agg-1");

    assertTrue(
        awareness == WorkflowAwareness.BPMS_UNAVAILABLE,
        "expected BPMS_UNAVAILABLE but got "
            + awareness);

  }

}
