package io.vanillabp.camunda8.springboot;

import org.springframework.boot.autoconfigure.AutoConfiguration;

import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;

/**
 * Announces the Camunda 8 adapter to the VanillaBP Spring Boot integration. The platform
 * validates the configured adapter types, and this class must be built before that. This is
 * why it says {@code before = SpringBootMigrationAdapterAutoConfiguration.class}.
 * <p>
 * Do not declare any other beans here. Spring has to build this class very early while the
 * context starts, and other beans would make that harder.
 */
@AutoConfiguration(before = SpringBootMigrationAdapterAutoConfiguration.class)
public class Camunda8AdapterConfiguration extends AdapterConfigurationBase {

  /**
   * Built by Spring Boot while it reads the auto-configuration imports.
   */
  public Camunda8AdapterConfiguration() {
  }

  /**
   * The adapter type this auto-configuration registers. It is taken from the deployment
   * service, so the two places cannot drift apart.
   */
  public static final String ADAPTER_TYPE = Camunda8DeploymentService.ADAPTER_TYPE;

  @Override
  public String getAdapterType() {

    return ADAPTER_TYPE;

  }

}
