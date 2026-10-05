package io.vanillabp.camunda8.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What travels to the cluster on behalf of a workflow - the Camunda 8 twin of the
 * Process-Engine-API adapter's {@code PeaSharedValuesTest}.
 * <p>
 * {@code WorkflowAggregateSync} promises two things which nothing held here: the
 * workflow-aggregate's ID is never one of the shared values, and the technical variable
 * carrying it is written ALWAYS, no matter what the sync model says. Camunda 8 has no
 * business key, so that variable is the only way back to the workflow: an aggregate
 * annotated {@code @NoSyncWithBPMS} would otherwise be started and never found again -
 * not by an awareness probe, not by a correlation, not by the viewer. The integration
 * tests start aggregates which share everything, so the interesting half was never
 * exercised.
 * <p>
 * The third case pins the sharing default: {@link AggregateSyncMode#FULL} for every
 * adapter, so a model reads the same attributes wherever it runs.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8SharedValuesTest {

  private static final String AGGREGATE_ID_NAME = "loanRequestId";

  /** A minimal aggregate: what it holds is the sync model's business, not this test's. */
  private record Aggregate(String id) {
  }

  /** What the aggregate's persistence answers - the ID, its name, nothing else. */
  private static AggregatePersistenceAware<Aggregate> persistence(
      final Aggregate aggregate) {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<Aggregate> getAggregateClass() {

        return Aggregate.class;

      }

      @Override
      public String getAggregateIdName() {

        return AGGREGATE_ID_NAME;

      }

      @Override
      public Aggregate loadById(
          final Object aggregateId) {

        return aggregate;

      }

    };

  }

  /** A sync model answering the given values, recording which default it was asked with. */
  private static class RecordingSync implements WorkflowAggregateSync {

    private final Map<String, Object> values;

    private AggregateSyncMode askedWith;

    private RecordingSync(
        final Map<String, Object> values) {

      this.values = values;

    }

    @Override
    public Map<String, Object> syncedValues(
        final Object workflowAggregate,
        final AggregateSyncMode adapterDefault) {

      askedWith = adapterDefault;
      return values;

    }

    @Override
    public void validateSyncModel(
        final Class<?> workflowAggregateClass) {

    }

  }

  /**
   * A service which never contacts the cluster: what is asserted here is the map built
   * before any command is sent.
   */
  private static Camunda8ProcessService<Aggregate> serviceSharing(
      final WorkflowAggregateSync aggregateSync) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:1");
    // no waiting for the exporter in a unit test - the cluster is never contacted
    configuration.setWorkflowVisibilityTimeout(Duration.ZERO);
    return new Camunda8ProcessService<>(
        "c8", new Camunda8ClientFactory("c8", configuration), Duration
            .ofDays(14), (
                aggregateClass,
                check) -> check.run(), aggregateSync);

  }

  @Test
  @DisplayName("An aggregate sharing nothing still reaches the cluster with its ID variable")
  public void theIdVariableIsWrittenWhateverIsShared() {

    final var sharesNothing = new RecordingSync(Map.of());

    final var variables = serviceSharing(sharesNothing)
        .variablesOf(persistence(new Aggregate("loan-4711")), "loan-4711");

    assertEquals(
        Map.of(AGGREGATE_ID_NAME, "loan-4711"),
        variables,
        "the technical ID variable is written although the aggregate shares nothing");

  }

  @Test
  @DisplayName("The shared values travel next to the ID variable, which is none of them")
  public void theIdVariableIsNotOneOfTheSharedValues() {

    final var sharesTwo = new RecordingSync(Map.of("amount", 4711, "approved", Boolean.FALSE));

    final var variables = serviceSharing(sharesTwo)
        .variablesOf(persistence(new Aggregate("loan-4712")), "loan-4712");

    assertEquals(3, variables.size(), "the shared values plus the ID variable");
    assertEquals(4711, variables.get("amount"));
    assertEquals(Boolean.FALSE, variables.get("approved"));
    assertEquals("loan-4712", variables.get(AGGREGATE_ID_NAME));

  }

  @Test
  @DisplayName("This adapter asks for everything - FULL is the default of every adapter")
  public void theAdapterAsksWithFull() {

    final var recording = new RecordingSync(Map.of());

    serviceSharing(recording).variablesOf(persistence(new Aggregate("loan-4713")), "loan-4713");

    assertNotNull(recording.askedWith, "the sync model has to be asked at all");
    assertEquals(AggregateSyncMode.FULL, recording.askedWith);
    assertEquals(AggregateSyncMode.FULL, Camunda8ProcessService.SYNC_MODE);

  }

}
