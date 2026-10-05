package io.vanillabp.camunda8.quarkus.runtime;

import io.quarkus.runtime.StartupEvent;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;

/**
 * Forces the {@link Camunda8ClientFactoryRegistry} to be built on application startup:
 * configuration is validated AT STARTUP, never first at runtime (a VanillaBP core
 * principle) - without this observer the CDI producer would run lazily on first use
 * and a configuration defect would surface only when the first workflow is started.
 */
@ApplicationScoped
public class Camunda8StartupObserver {

  /**
   * Built by CDI, which needs a no-arg constructor to proxy an application-scoped bean.
   */
  public Camunda8StartupObserver() {
  }

  void onStart(
      @Observes final StartupEvent event,
      final Instance<Camunda8ClientFactoryRegistry> clientFactoryRegistry) {

    // resolving the instance forces the producer (and with it the startup
    // validation and the eager client construction) to run
    clientFactoryRegistry.get();

  }

}
