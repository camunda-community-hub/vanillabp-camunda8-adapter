package io.vanillabp.camunda8.client;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * What makes two configured <code>camunda8</code> adapter ids DIFFERENT clusters.
 * Camunda 8 is remote. So two ids are distinct if they address different systems, or the
 * same system with different credentials:
 * <ul>
 * <li><b>self-managed:</b> a different <code>rest-address</code>/
 * <code>grpc-address</code>.</li>
 * <li><b>SaaS:</b> a different COMBINATION of <code>cluster-id</code> and
 * <code>client-id</code>. Addressing one cluster with two OAuth clients is a valid setup
 * (separate permissions or quotas per workflow module). So the client id counts.</li>
 * <li>In addition, the <code>tenant-id</code> of the ADAPTER section. For the application,
 * the same cluster with different multi-tenancy tenants is different systems. A name which
 * a single workflow module carries is not read here. This question is about the adapter id
 * as a whole, and the name of one module says nothing about the other modules.</li>
 * <li>And a different <code>name-clash-avoidance</code> mode. The identifiers of the SAME
 * workflow module look different in each mode (a tenant, or prefixed identifiers). That is
 * exactly the setup used to MIGRATE from tenants to prefixes: two ids on one cluster, which
 * differ only in that mode, with the new one first in
 * <code>prioritized-adapters</code>.</li>
 * </ul>
 * Two ids whose relevant keys are identical are the same instance. Configuring them as
 * separate adapters is an error.
 * <p>
 * Two ids which ARE distinct but still address one CLUSTER pay for it. Job keys, user-task
 * keys and process-instance keys are unique per cluster, and they carry neither tenant nor
 * prefix. So the election has to look a key up to learn which of the two scopes it belongs
 * to. That is one search per elected task. What counts as one cluster there is
 * {@link #clusterIdentityOf}. It ignores, on purpose, the tenant and the credentials which
 * this identity includes. That the cluster answers such a search at all is a requirement of
 * the adapter, not a property of this setup, see decision 20 in the repository's
 * DECISIONS.md.
 */
public final class Camunda8InstanceIdentity {

  private Camunda8InstanceIdentity() {
  }

  /**
   * The comparable identity of an adapter id's connection configuration.
   *
   * @param configuration The adapter id's connection configuration
   * @return The identity (never <code>null</code>)
   */
  static String identityOf(
      final Camunda8AdapterConfiguration configuration) {

    return identityOf(configuration, null);

  }

  /**
   * The comparable identity of an adapter id's connection configuration, including
   * the name-clash-avoidance mode it uses.
   *
   * @param configuration The adapter id's connection configuration
   * @param nameClashAvoidance The mode configured for the adapter or
   *          <code>null</code> if unknown
   * @return The identity (never <code>null</code>)
   */
  static String identityOf(
      final Camunda8AdapterConfiguration configuration,
      final NameClashAvoidance nameClashAvoidance) {

    final var scoping = nameClashAvoidance == null
        ? ""
        : ", name-clash avoidance '%s'".formatted(nameClashAvoidance.name().toLowerCase().replace('_', '-'));
    return connectionIdentityOf(configuration) + scoping;

  }

  /**
   * What makes two adapter ids the SAME CLUSTER. This is a coarser question than
   * {@link #identityOf}. Neither the tenant nor the credentials matter here, because a job
   * key, a user-task key and a process-instance key are unique per CLUSTER and not per
   * tenant. Two ids with this identity can be handed each other's keys. That is why their
   * awareness probes have to care about the scope.
   *
   * @param configuration The adapter id's connection configuration
   * @return The cluster identity, never <code>null</code>
   */
  static String clusterIdentityOf(
      final Camunda8AdapterConfiguration configuration) {

    if (configuration == null) {
      return "<unconfigured>";
    }
    if (configuration.getMode() == Camunda8AdapterConfiguration.Mode.SAAS) {
      return "SaaS cluster '%s' (region '%s')".formatted(
          configuration.getClusterId(),
          configuration.getRegion());
    }
    return "self-managed cluster (rest-address '%s', grpc-address '%s')".formatted(
        configuration.restAddressInUse(),
        configuration.grpcAddressInUse());

  }

  private static String connectionIdentityOf(
      final Camunda8AdapterConfiguration configuration) {

    if (configuration == null) {
      return "<unconfigured>";
    }
    if (configuration.getMode() == Camunda8AdapterConfiguration.Mode.SAAS) {
      return "SaaS cluster '%s' (region '%s') accessed by client '%s', tenant '%s'".formatted(
          configuration.getClusterId(),
          configuration.getRegion(),
          configuration.getClientId(),
          configuration.getTenantId());
    }
    // who the adapter authenticates as is part of the identity. One self-managed cluster
    // which serves two applications with separate accounts is a valid setup. The SaaS
    // client id already stood for the same setup
    return "self-managed cluster (rest-address '%s', grpc-address '%s'), tenant '%s', authenticated as '%s'"
        .formatted(
            configuration.restAddressInUse(),
            configuration.grpcAddressInUse(),
            configuration.getTenantId(),
            configuration.getAuth().principal(configuration));

  }

  /**
   * Fails the boot if two of the given adapter ids address the same cluster with the same
   * credentials and tenant (see the class comment). The adapter SPI hook
   * {@code AdapterDeploymentService#validateDistinctAdapterInstances} calls it.
   *
   * @param adapterIds The configured adapter ids of type <code>camunda8</code>
   * @param configurationResolver Resolves an adapter id's connection configuration
   */
  public static void validateDistinct(
      final List<String> adapterIds,
      final Function<String, Camunda8AdapterConfiguration> configurationResolver) {

    validateDistinct(adapterIds, configurationResolver, null);

  }

  /**
   * Fails the boot if two of the given adapter ids address the same cluster with the
   * same credentials, tenant AND name-clash-avoidance mode (see the class comment).
   *
   * @param adapterIds The configured adapter ids of type <code>camunda8</code>
   * @param configurationResolver Resolves an adapter id's connection configuration
   * @param scoping The core's name-clash-avoidance support or <code>null</code>
   */
  public static void validateDistinct(
      final List<String> adapterIds,
      final Function<String, Camunda8AdapterConfiguration> configurationResolver,
      final NameClashAvoidanceSupport scoping) {

    if ((adapterIds == null) || (adapterIds.size() < 2) || (configurationResolver == null)) {
      return;
    }

    final var idsByIdentity = new LinkedHashMap<String, List<String>>();
    adapterIds.forEach(
        adapterId -> idsByIdentity
            .computeIfAbsent(
                identityOf(
                    configurationResolver.apply(adapterId),
                    scoping == null
                        ? null
                        : scoping.modeFor(null, null, adapterId)),
                identity -> new LinkedList<>())
            .add(adapterId));

    idsByIdentity.forEach((
        identity,
        idsSharingIt) -> {
      if (idsSharingIt.size() < 2) {
        return;
      }
      throw new IllegalStateException(
          """
              The Camunda 8 adapters '%s' address the SAME cluster with the same credentials \
              (%s)! Two adapter ids of one BPMS type only make sense if they address different \
              systems - the BPMS election would otherwise ask the same cluster twice. Make them \
              distinguishable:
                - self-managed: give each id its own 'vanillabp.adapters.<id>.rest-address' \
              (respectively 'grpc-address'), or - to address one cluster with separated \
              permissions - its own credentials below 'vanillabp.adapters.<id>.auth',
                - SaaS: give each id its own 'vanillabp.adapters.<id>.cluster-id' or - to \
              address one cluster with separated permissions - its own 'client-id',
                - or, when MIGRATING from tenants to prefixed identifiers on ONE cluster: give \
              each id its own 'vanillabp.adapters.<id>.name-clash-avoidance',
              or remove all but one of these adapters."""
              .formatted(String.join("', '", idsSharingIt), identity));
    });

  }

}
