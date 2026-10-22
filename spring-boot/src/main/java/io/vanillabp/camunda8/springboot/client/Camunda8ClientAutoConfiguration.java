package io.vanillabp.camunda8.springboot.client;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;

import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.client.Camunda8StartupValidation;
import io.vanillabp.camunda8.client.Camunda8VersionOneKeys;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.camunda8.observability.MicrometerCamunda8Metrics;
import io.vanillabp.camunda8.wiring.Camunda8Connectors;
import io.vanillabp.camunda8.wiring.Camunda8FetchVariables;
import io.vanillabp.camunda8.wiring.Camunda8Listeners;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * Exposes the {@link Camunda8ClientFactoryRegistry}. It is built from the configuration
 * of each adapter at <code>vanillabp.adapters.&lt;id&gt;.*</code>, which the adapter's
 * overlay {@link VanillaBpCamunda8Properties} binds. The registry owns one
 * {@code CamundaClient} per adapter ID. It is a managed bean, so its {@code close()} runs
 * when the application shuts down, and that closes all clients.
 * <p>
 * The set of adapter ids comes from the platform's core properties (the ids of type
 * {@code camunda8}). The overlay map is only used to look up an id the core already knows.
 * The connection configuration of every instance is VALIDATED AT STARTUP
 * ({@link Camunda8StartupValidation}):
 * <ul>
 * <li>A self-managed adapter without an address uses the address of the local cluster of
 * its release line, and the start warns about it.</li>
 * <li>An adapter with an inconsistent configuration ends the boot. The exception is an
 * adapter which is nowhere first priority and whose deployment-failure policy is
 * 'warn'.</li>
 * <li>The clients of completely configured adapters are built eagerly.</li>
 * </ul>
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties({
    VanillaBpConfigurationProperties.class, VanillaBpCamunda8Properties.class
})
public class Camunda8ClientAutoConfiguration {

  /**
   * Built by Spring Boot while it reads the auto-configuration imports.
   */
  public Camunda8ClientAutoConfiguration() {
  }

  /**
   * Builds the one registry of this application, with a client factory per configured adapter id
   * of type <code>camunda8</code>. The ids come from the platform's own properties, and the
   * adapter's overlay is read per id.
   *
   * @param coreProperties The platform's properties, which name the configured adapter ids and
   *          their types
   * @param overlay The adapter's own view of the same tree
   * @param environment Where the keys of version 1 are looked for, which this version does
   *          not read
   * @return The registry, with every completely configured client already built
   */
  @Bean(destroyMethod = "close")
  public Camunda8ClientFactoryRegistry camunda8ClientFactoryRegistry(
      final VanillaBpConfigurationProperties coreProperties,
      final VanillaBpCamunda8Properties overlay,
      final Environment environment) {

    final Map<String, Camunda8AdapterConfiguration> configurations = new HashMap<>();
    coreProperties
        .adapterTypes()
        .entrySet()
        .stream()
        .filter(adapter -> Camunda8DeploymentService.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .forEach(adapterId -> {
          final var configuration = overlay
              .getAdapters()
              .getOrDefault(adapterId, new Camunda8AdapterConfiguration());
          // a removed key is refused before anything else, wherever it is set. The
          // workers no longer read it, and silence would hide that from the application
          Camunda8FetchVariables.rejectTheRemovedKey(adapterId, overlay.fetchVariablesKeys(adapterId));
          Camunda8StartupValidation.validateAtStartup(
              adapterId,
              configuration,
              coreProperties.isFirstPriorityAnywhere(adapterId),
              coreProperties.getDeploymentFailureFor(
                  adapterId) == DeploymentFailurePolicy.WARN,
              coreProperties.resolvedDeliveryRetention(),
              // the warning about the local cluster names the key which moves this
              // application off the port that cluster takes
              "server.port",
              log::warn,
              log::info);
          // a key at a level which does not read it changes nothing. Saying nothing would
          // be worse than a line which says where the key is read
          Camunda8Connectors.reportKeysSetAtTaskLevel(
              adapterId,
              overlay.allowConnectorsKeysAtTaskLevel(adapterId),
              log::warn);
          Camunda8Listeners.reportKeysSetAtTaskLevel(
              adapterId,
              overlay.allowListenersKeysAtTaskLevel(adapterId),
              log::warn);
          configurations.put(adapterId, configuration);
        });

    // a key of version 1 which this version does not read would change nothing in silence
    Camunda8VersionOneKeys.reportKeysOfVersionOne(configurations.keySet(), propertyNames(environment), log::warn);

    return new Camunda8ClientFactoryRegistry(configurations);

  }

  /**
   * The names of every property a configuration file or a property source with a list of
   * names holds. A source which cannot list its names is skipped.
   */
  private static List<String> propertyNames(
      final Environment environment) {

    if (!(environment instanceof ConfigurableEnvironment configurable)) {
      return List.of();
    }
    return configurable
        .getPropertySources()
        .stream()
        .filter(EnumerablePropertySource.class::isInstance)
        .flatMap(source -> Arrays.stream(((EnumerablePropertySource<?>) source).getPropertyNames()))
        .toList();

  }

  /**
   * What this adapter measures on top of the core's meters: the client's own job counters
   * per worker and the execution slots of every adapter instance. Micrometer is optional,
   * so this whole configuration depends on it. An application without Micrometer boots
   * unchanged and reports nothing.
   */
  @Configuration(proxyBeanMethods = false)
  // by NAME, not by class literal. Spring reads the annotation of a nested configuration
  // class by reflection. A class literal of a missing optional dependency would fail
  // there, before the condition is ever checked
  @ConditionalOnClass(
      name = "io.micrometer.core.instrument.MeterRegistry")
  public static class Camunda8MetricsConfiguration {

    /**
     * Built by Spring Boot where Micrometer is on the classpath.
     */
    public Camunda8MetricsConfiguration() {
    }

    /**
     * Publishes the adapter's meter binder, which Spring Boot then applies to its registry.
     *
     * @return The meter binder of this adapter's own numbers
     */
    @Bean
    @ConditionalOnMissingBean
    public MicrometerCamunda8Metrics camunda8Metrics() {

      return new MicrometerCamunda8Metrics();

    }

  }

}
