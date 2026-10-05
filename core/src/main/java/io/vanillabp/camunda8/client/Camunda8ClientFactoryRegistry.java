package io.vanillabp.camunda8.client;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Holds the {@link Camunda8ClientFactory} of every configured Camunda 8 adapter
 * instance, keyed by adapter ID. Created ONCE per application by the platform
 * integration (Spring Boot / Quarkus) with the factories built EAGERLY - one per
 * configured adapter id of type {@code camunda8}, each validated at startup (see
 * {@link Camunda8StartupValidation}). Registered as a managed bean so its
 * {@link #close()} is invoked on application shutdown, closing all clients.
 */
public class Camunda8ClientFactoryRegistry implements AutoCloseable {

  private final Map<String, Camunda8ClientFactory> factories;

  /**
   * Builds one factory per configured adapter id, eagerly, so a connection nobody can use is
   * found while the application boots.
   *
   * @param configurationsByAdapterId The connection configuration of every
   *          configured adapter id of type {@code camunda8} (the id set always
   *          comes from the platform's core properties)
   */
  public Camunda8ClientFactoryRegistry(
      final Map<String, Camunda8AdapterConfiguration> configurationsByAdapterId) {

    this.factories = configurationsByAdapterId
        .entrySet()
        .stream()
        .collect(Collectors.toUnmodifiableMap(
            Map.Entry::getKey,
            entry -> new Camunda8ClientFactory(entry.getKey(), entry.getValue())));
    tellEachFactoryWhoSharesItsCluster(configurationsByAdapterId);

  }

  /**
   * Which adapter ids address the SAME cluster.
   * <p>
   * A job key, a user-task key and a process-instance key are unique per cluster, so two
   * ids sharing one are handed each other's keys and their awareness probes have to check
   * which scope a key belongs to before they answer. Nobody but this registry sees all
   * configured ids at once, so this is where the question is answered - once, at startup,
   * rather than per probe.
   *
   * @param configurationsByAdapterId The connection configuration of every configured id
   */
  private void tellEachFactoryWhoSharesItsCluster(
      final Map<String, Camunda8AdapterConfiguration> configurationsByAdapterId) {

    final Map<String, List<String>> idsByCluster = new LinkedHashMap<>();
    configurationsByAdapterId.forEach((
        adapterId,
        configuration) -> idsByCluster
            .computeIfAbsent(
                Camunda8InstanceIdentity.clusterIdentityOf(configuration),
                cluster -> new LinkedList<>())
            .add(adapterId));
    idsByCluster
        .values()
        .stream()
        .filter(adapterIds -> adapterIds.size() > 1)
        .forEach(adapterIds -> adapterIds
            .forEach(adapterId -> factories
                .get(adapterId)
                .sharesItsClusterWith(adapterIds
                    .stream()
                    .filter(other -> !other.equals(adapterId))
                    .toList())));

  }

  /**
   * The factory of one adapter id, which is how everything else in this adapter reaches a
   * client.
   *
   * @param adapterId The adapter ID
   * @return The client factory of the given adapter instance
   * @throws IllegalStateException If no Camunda 8 adapter of that id is configured -
   *           the id set always comes from the platform's core properties, so an
   *           unknown id indicates a wiring bug
   */
  public Camunda8ClientFactory getFactory(
      final String adapterId) {

    final var factory = factories.get(adapterId);
    if (factory == null) {
      throw new IllegalStateException(
          "No Camunda 8 adapter with id '%s' is configured (configured ids of type camunda8: '%s')! "
              .formatted(adapterId, String.join("', '", factories.keySet()))
              + "The adapter-id set always comes from 'vanillabp.adapters.<id>.type' - check the wiring.");
    }
    return factory;

  }

  @Override
  public void close() {

    factories
        .values()
        .forEach(Camunda8ClientFactory::close);

  }

}
