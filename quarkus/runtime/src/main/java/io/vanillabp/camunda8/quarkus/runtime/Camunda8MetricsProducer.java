package io.vanillabp.camunda8.quarkus.runtime;

import io.vanillabp.camunda8.observability.MicrometerCamunda8Metrics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Publishes what this adapter measures on top of the core's meters: the client's own
 * job counters per worker and the execution slots of every adapter instance. Quarkus
 * applies every {@code MeterBinder} bean to its registry, so producing the binder is
 * all it takes.
 * <p>
 * This class is registered as a bean ONLY if the application uses the Micrometer
 * extension (see {@code Camunda8IntegrationProcessor}) - it references Micrometer types
 * and must not be loaded otherwise.
 */
@ApplicationScoped
public class Camunda8MetricsProducer {

  /**
   * Built by CDI, which needs a no-arg constructor to proxy an application-scoped bean.
   */
  public Camunda8MetricsProducer() {
  }

  /**
   * Publishes the adapter's meter binder, which Quarkus then applies to its registry.
   *
   * @return The binder of this adapter's own numbers
   */
  @Produces
  @Singleton
  public MicrometerCamunda8Metrics camunda8Metrics() {

    return new MicrometerCamunda8Metrics();

  }

}
