package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.worker.JobWorkerBuilderStep1;
import io.camunda.client.api.worker.JobWorkerMetrics;
import io.vanillabp.camunda8.observability.Camunda8Metrics;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a worker gets from its adapter id, asked of the class an EXTENSION calls.
 * <p>
 * The promise is that a worker an extension opens looks to an operator like a worker of the
 * adapter, so the test asserts the two things a worker cannot inherit from the client: the
 * counters, which carry the adapter id and the job type, and the stream timeout.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8WorkersTest {

  private final JobWorkerBuilderStep1.JobWorkerBuilderStep3 builder = mock(
      JobWorkerBuilderStep1.JobWorkerBuilderStep3.class);

  /**
   * What a worker asked for its counters, so the test can read the two values they are
   * keyed by.
   */
  private final List<String> countersAskedFor = new LinkedList<>();

  private final Camunda8Metrics metrics = new Camunda8Metrics() {

    @Override
    public JobWorkerMetrics workerMetrics(
        final String adapterId,
        final String jobType) {

      countersAskedFor.add("%s|%s".formatted(adapterId, jobType));
      return JobWorkerMetrics.noop();

    }

  };

  @BeforeEach
  public void theBuilderKeepsReturningItself() {

    when(builder.metrics(any())).thenReturn(builder);

  }

  @Test
  @DisplayName("A worker of an extension carries the counters of its adapter id and job type")
  public void aWorkerCarriesTheCountersOfItsAdapterId() {

    Camunda8Workers
        .applyWorkerOptions(
            builder, "c8", "theExtensionsJobType", new Camunda8AdapterConfiguration(), metrics);

    assertEquals(
        List.of("c8|theExtensionsJobType"),
        countersAskedFor,
        "the adapter id an operator reads the counters under, and the job type they are keyed by");

  }

  @Test
  @DisplayName("A worker gets the stream timeout of its adapter, which no client carries")
  public void aWorkerGetsTheStreamTimeoutOfItsAdapter() {

    when(builder.streamTimeout(Duration.ofSeconds(30))).thenReturn(builder);
    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setStreamTimeout(Duration.ofSeconds(30));

    final var result = Camunda8Workers
        .applyWorkerOptions(builder, "c8", "theExtensionsJobType", configuration, Camunda8Metrics.NONE);

    verify(builder).streamTimeout(Duration.ofSeconds(30));
    assertSame(builder, result, "the same builder comes back, so a caller keeps configuring it");

  }

  @Test
  @DisplayName("An adapter without a stream timeout leaves the client's own behaviour alone")
  public void aWorkerWithoutAStreamTimeoutIsNotTouched() {

    Camunda8Workers
        .applyWorkerOptions(
            builder, "c8", "theExtensionsJobType", new Camunda8AdapterConfiguration(), Camunda8Metrics.NONE);

    verify(builder, never()).streamTimeout(any());

  }

}
