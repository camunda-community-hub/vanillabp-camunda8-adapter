package io.vanillabp.camunda8.springboot;

import org.springframework.boot.autoconfigure.AutoConfiguration;

import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;

/**
 * Announces the Camunda 8 adapter to the VanillaBP Spring Boot integration. It must be
 * built <i>before</i> the platform validates the configured adapter types, hence
 * {@code before = SpringBootMigrationAdapterAutoConfiguration.class}.
 * <p>
 * This configuration must not declare any other bean definitions since it needs to be
 * constructible very early during bootstrapping of the Spring context.
 */
@AutoConfiguration(before = SpringBootMigrationAdapterAutoConfiguration.class)
public class Camunda8AdapterConfiguration extends AdapterConfigurationBase {

  /**
   * Built by Spring Boot while it reads the auto-configuration imports.
   */
  public Camunda8AdapterConfiguration() {
  }

  /**
   * The adapter type this auto-configuration registers, taken from the deployment service so
   * both halves cannot drift apart.
   */
  public static final String ADAPTER_TYPE = Camunda8DeploymentService.ADAPTER_TYPE;

  @Override
  public String getAdapterType() {

    return ADAPTER_TYPE;

  }

}
