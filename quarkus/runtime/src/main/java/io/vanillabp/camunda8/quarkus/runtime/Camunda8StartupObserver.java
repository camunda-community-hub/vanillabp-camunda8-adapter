package io.vanillabp.camunda8.quarkus.runtime;

import io.quarkus.runtime.StartupEvent;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;

/**
 * Makes sure the {@link Camunda8ClientFactoryRegistry} is built when the application starts.
 * A VanillaBP core principle: the configuration is validated AT STARTUP, never first at
 * runtime. Without this observer, the CDI producer would run lazily on first use. A
 * defect in the configuration would then only show when the first workflow starts.
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
