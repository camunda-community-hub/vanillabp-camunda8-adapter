package io.vanillabp.camunda8.quarkus.runtime;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8AuthConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.client.Camunda8StartupValidation;
import io.vanillabp.camunda8.deployment.Camunda8DeploymentService;
import io.vanillabp.camunda8.wiring.Camunda8Connectors;
import io.vanillabp.camunda8.wiring.Camunda8FetchVariables;
import io.vanillabp.camunda8.wiring.Camunda8Listeners;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the {@link Camunda8ClientFactoryRegistry} (one lazily built
 * {@code CamundaClient} per Camunda 8 adapter ID). It is built from the configuration of
 * each adapter at <code>vanillabp.adapters.&lt;id&gt;.*</code>, which the adapter's overlay
 * mapping ({@link VanillaBpCamunda8Properties}) binds. Property names are NEVER parsed in
 * code, because that loses information when the values come from environment variables.
 * <p>
 * The set of adapter ids comes from the platform's core properties (the ids of type
 * {@code camunda8}). The overlay map is only used to look up an id the core already knows.
 * The connection configuration of every instance is VALIDATED AT STARTUP. The
 * {@link Camunda8StartupObserver} makes this producer run on {@code StartupEvent}.
 * <ul>
 * <li>A self-managed adapter without an address uses the address of the local cluster of
 * its release line, and the start warns about it.</li>
 * <li>An adapter with an inconsistent configuration ends the boot. The exception is an
 * adapter which is nowhere first priority and whose deployment-failure policy is
 * 'warn'.</li>
 * <li>The clients of completely configured adapters are built eagerly.</li>
 * </ul>
 * When the application shuts down, the {@link #close(Camunda8ClientFactoryRegistry)
 * disposer} closes the registry and with it all clients.
 */
@ApplicationScoped
public class Camunda8ClientProducer {

  /**
   * Built by CDI, which needs a no-arg constructor to proxy an application-scoped bean.
   */
  public Camunda8ClientProducer() {
  }

  private static final Logger log = Logger.getLogger(Camunda8ClientProducer.class);

  /**
   * Builds the one registry of this application, with a client factory per configured adapter id
   * of type <code>camunda8</code>. The ids come from the platform's own properties, and the
   * adapter's overlay is read per id.
   *
   * @param properties The platform's properties, which name the configured adapter ids and
   *          their types
   * @return The registry, with every completely configured client already built
   */
  @Produces
  @Singleton
  public Camunda8ClientFactoryRegistry camunda8ClientFactoryRegistry(
      final MigrationAdapterProperties properties) {

    final var overlay = ConfigProvider
        .getConfig()
        .unwrap(SmallRyeConfig.class)
        .getConfigMapping(VanillaBpCamunda8Properties.class);

    final Map<String, Camunda8AdapterConfiguration> configurations = new HashMap<>();
    properties
        .adapterTypes()
        .entrySet()
        .stream()
        .filter(adapter -> Camunda8DeploymentService.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .forEach(adapterId -> {
          final var configuration = toConfiguration(overlay.adapters().get(adapterId));
          // a removed key is refused before anything else, wherever it is set. The
          // workers no longer read it, and silence would hide that from the application
          Camunda8FetchVariables.rejectTheRemovedKey(adapterId, overlay.fetchVariablesKeys(adapterId));
          Camunda8StartupValidation.validateAtStartup(
              adapterId,
              configuration,
              properties.isFirstPriorityAnywhere(adapterId),
              properties.getDeploymentFailureFor(
                  adapterId) == DeploymentFailurePolicy.WARN,
              properties.resolvedDeliveryRetention(),
              // the warning about the local cluster names the key which moves this
              // application off the port that cluster takes
              "quarkus.http.port",
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

    return new Camunda8ClientFactoryRegistry(configurations);

  }

  private static Camunda8AdapterConfiguration toConfiguration(
      final VanillaBpCamunda8Properties.Camunda8AdapterKeys keys) {

    final var configuration = new Camunda8AdapterConfiguration();
    if (keys == null) {
      return configuration;
    }
    keys.mode().ifPresent(configuration::setMode);
    keys.restAddress().ifPresent(configuration::setRestAddress);
    keys.grpcAddress().ifPresent(configuration::setGrpcAddress);
    keys.preferRestOverGrpc().ifPresent(configuration::setPreferRestOverGrpc);
    keys.tenantId().ifPresent(configuration::setTenantId);
    keys.acceptUnscopedIdentifiers().ifPresent(configuration::setAcceptUnscopedIdentifiers);
    keys.allowConnectors().ifPresent(configuration::setAllowConnectors);
    keys.allowListeners().ifPresent(configuration::setAllowListeners);
    keys.clusterId().ifPresent(configuration::setClusterId);
    keys.region().ifPresent(configuration::setRegion);
    keys.clientId().ifPresent(configuration::setClientId);
    keys.clientSecret().ifPresent(configuration::setClientSecret);
    keys.jobTimeout().ifPresent(configuration::setJobTimeout);
    keys.retryBackoff().ifPresent(configuration::setRetryBackoff);
    keys.asyncTaskLockRenewal().ifPresent(configuration::setAsyncTaskLockRenewal);
    // bound only so the removed key can be REJECTED with a guiding message. Otherwise
    // SmallRye would only say "does not map to any root"
    keys.asyncTaskTimeout().ifPresent(configuration::setAsyncTaskTimeout);
    keys.asyncTaskMaxAgeAction().ifPresent(configuration::setAsyncTaskMaxAgeAction);
    keys.shutdownGrace().ifPresent(configuration::setShutdownGrace);
    keys.healthTimeout().ifPresent(configuration::setHealthTimeout);
    keys.startupWait().ifPresent(configuration::setStartupWait);
    keys.workflowVisibilityTimeout().ifPresent(configuration::setWorkflowVisibilityTimeout);
    keys.endedWorkflowVisibilityTimeout().ifPresent(configuration::setEndedWorkflowVisibilityTimeout);
    keys.jobLease().ifPresent(configuration::setJobLease);
    keys.aggregateIdAsBusinessId().ifPresent(configuration::setAggregateIdAsBusinessId);
    keys.probeOpenUserTasks().ifPresent(configuration::setProbeOpenUserTasks);
    keys.workerThreads().ifPresent(configuration::setWorkerThreads);
    keys.workerThreadsBound().ifPresent(configuration::setWorkerThreadsBound);
    keys.maxJobsActive().ifPresent(configuration::setMaxJobsActive);
    keys.pollInterval().ifPresent(configuration::setPollInterval);
    keys.requestTimeout().ifPresent(configuration::setRequestTimeout);
    keys.streamEnabled().ifPresent(configuration::setStreamEnabled);
    keys.streamTimeout().ifPresent(configuration::setStreamTimeout);
    keys.messageTimeToLive().ifPresent(configuration::setMessageTimeToLive);
    keys.maxMessageSize().ifPresent(configuration::setMaxMessageSize);
    keys.keepAlive().ifPresent(configuration::setKeepAlive);
    keys.maxHttpConnections().ifPresent(configuration::setMaxHttpConnections);
    keys.overrideAuthority().ifPresent(configuration::setOverrideAuthority);
    toAuthConfiguration(keys.auth(), configuration.getAuth());
    return configuration;

  }

  private static void toAuthConfiguration(
      final VanillaBpCamunda8Properties.AuthKeys keys,
      final Camunda8AuthConfiguration auth) {

    if (keys == null) {
      return;
    }
    keys.method().ifPresent(auth::setMethod);
    keys.username().ifPresent(auth::setUsername);
    keys.password().ifPresent(auth::setPassword);
    keys.clientId().ifPresent(auth::setClientId);
    keys.clientSecret().ifPresent(auth::setClientSecret);
    keys.authorizationServerUrl().ifPresent(auth::setAuthorizationServerUrl);
    keys.audience().ifPresent(auth::setAudience);
    keys.scope().ifPresent(auth::setScope);
    keys.credentialsCachePath().ifPresent(auth::setCredentialsCachePath);
    keys.connectTimeout().ifPresent(auth::setConnectTimeout);
    keys.readTimeout().ifPresent(auth::setReadTimeout);
    keys.keystorePath().ifPresent(auth::setKeystorePath);
    keys.keystorePassword().ifPresent(auth::setKeystorePassword);
    keys.keystoreKeyPassword().ifPresent(auth::setKeystoreKeyPassword);
    keys.truststorePath().ifPresent(auth::setTruststorePath);
    keys.truststorePassword().ifPresent(auth::setTruststorePassword);
    keys.caCertificatePath().ifPresent(auth::setCaCertificatePath);

  }

  /**
   * Closes every client of the registry when the application goes down.
   *
   * @param registry The registry CDI is disposing of
   */
  public void close(
      @Disposes final Camunda8ClientFactoryRegistry registry) {

    registry.close();

  }

}
