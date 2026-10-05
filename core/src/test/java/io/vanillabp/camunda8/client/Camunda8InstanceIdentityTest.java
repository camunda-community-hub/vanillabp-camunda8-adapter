package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two <code>camunda8</code> adapter ids only make sense if they address DIFFERENT
 * clusters - or one cluster with different credentials/tenants.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8InstanceIdentityTest {

  private static Camunda8AdapterConfiguration selfManaged(
      final String restAddress) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress(restAddress);
    return configuration;

  }

  private static Camunda8AdapterConfiguration saas(
      final String clusterId,
      final String clientId) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setMode(Camunda8AdapterConfiguration.Mode.SAAS);
    configuration.setClusterId(clusterId);
    configuration.setRegion("bru-2");
    configuration.setClientId(clientId);
    configuration.setClientSecret("secret");
    return configuration;

  }

  private static void validate(
      final Map<String, Camunda8AdapterConfiguration> configurations) {

    Camunda8InstanceIdentity.validateDistinct(List.copyOf(configurations.keySet()), configurations::get);

  }

  @Test
  @DisplayName("Two ids on the same self-managed address are the same cluster")
  public void sameSelfManagedAddressFails() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> validate(Map.of(
            "c8-old", selfManaged("http://localhost:8080"),
            "c8-new", selfManaged("http://localhost:8080"))));

    assertTrue(exception.getMessage().contains("c8-old"), exception::getMessage);
    assertTrue(exception.getMessage().contains("rest-address"), exception::getMessage);

  }

  @Test
  @DisplayName("SaaS: the COMBINATION of cluster id and client id decides")
  public void saasIsDistinguishedByClusterAndClient() {

    // same cluster AND same client: the same instance
    assertThrows(
        IllegalStateException.class,
        () -> validate(Map.of(
            "c8-a", saas("cluster-1", "client-1"),
            "c8-b", saas("cluster-1", "client-1"))));

    // same cluster, DIFFERENT clients: a legitimate setup (separated permissions)
    assertDoesNotThrow(
        () -> validate(Map.of(
            "c8-a", saas("cluster-1", "client-1"),
            "c8-b", saas("cluster-1", "client-2"))));

    // different clusters
    assertDoesNotThrow(
        () -> validate(Map.of(
            "c8-a", saas("cluster-1", "client-1"),
            "c8-b", saas("cluster-2", "client-1"))));

  }

  @Test
  @DisplayName("Distinct addresses and distinct tenants make two ids distinct")
  public void distinctInstancesAreAccepted() {

    assertDoesNotThrow(
        () -> validate(Map.of(
            "c8-old", selfManaged("http://old:8080"),
            "c8-new", selfManaged("http://new:8080"))));

    final var tenantA = selfManaged("http://localhost:8080");
    tenantA.setTenantId("tenant-a");
    final var tenantB = selfManaged("http://localhost:8080");
    tenantB.setTenantId("tenant-b");
    assertDoesNotThrow(() -> validate(Map.of("c8-a", tenantA, "c8-b", tenantB)));

  }

  @Test
  @DisplayName("Two ids on ONE cluster are distinct if their name-clash-avoidance modes differ")
  public void differingNameClashAvoidanceMakesIdsDistinct() {

    final var configurations = Map.of(
        "c8-tenants", selfManaged("http://localhost:8080"),
        "c8-prefixed", selfManaged("http://localhost:8080"));
    final var modes = Map.of(
        "c8-tenants", NameClashAvoidance.BY_ADAPTER,
        "c8-prefixed", NameClashAvoidance.USE_PREFIX);

    // the tenants-to-prefix migration: ONE cluster, two adapter ids differing only in
    // the mode - the identifiers they use are disjoint, so this has to be accepted
    assertDoesNotThrow(
        () -> Camunda8InstanceIdentity.validateDistinct(
            List.copyOf(configurations.keySet()),
            configurations::get,
            modeResolver(modes)));

    // the SAME mode on the same cluster is still the same instance
    final var sameMode = Map.of(
        "c8-tenants", NameClashAvoidance.BY_ADAPTER,
        "c8-prefixed", NameClashAvoidance.BY_ADAPTER);
    final var exception = assertThrows(
        IllegalStateException.class,
        () -> Camunda8InstanceIdentity.validateDistinct(
            List.copyOf(configurations.keySet()),
            configurations::get,
            modeResolver(sameMode)));
    assertTrue(exception.getMessage().contains("name-clash-avoidance"), exception::getMessage);

  }

  @Test
  @DisplayName("A single id is never checked, and an unavailable resolver skips the check")
  public void singleIdAndMissingResolverAreNoOps() {

    assertDoesNotThrow(() -> Camunda8InstanceIdentity.validateDistinct(List.of("c8"), id -> null));
    assertDoesNotThrow(() -> Camunda8InstanceIdentity.validateDistinct(List.of("a", "b"), null));
    assertDoesNotThrow(() -> Camunda8InstanceIdentity.validateDistinct(null, id -> null));

  }

  /**
   * A {@link NameClashAvoidanceSupport} answering only {@code modeFor} - the identity
   * check needs nothing else, and the core's implementation is not available in the
   * Camunda 8 core (it depends on the adapter SPI only).
   */
  private static NameClashAvoidanceSupport modeResolver(
      final Map<String, NameClashAvoidance> modesByAdapterId) {

    return new NameClashAvoidanceSupport() {

      @Override
      public NameClashAvoidance modeFor(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String adapterId) {

        return modesByAdapterId.get(adapterId);

      }

      @Override
      public String scopedProcessId(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String adapterId) {

        throw new UnsupportedOperationException();

      }

      @Override
      public String scopedIdentifier(
          final String workflowModuleId,
          final String identifier,
          final String adapterId) {

        throw new UnsupportedOperationException();

      }

      @Override
      public String scopedTaskDefinition(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String taskDefinition,
          final String adapterId) {

        throw new UnsupportedOperationException();

      }

      @Override
      public String plainProcessId(
          final String workflowModuleId,
          final String scopedBpmnProcessId,
          final String adapterId) {

        throw new UnsupportedOperationException();

      }

      @Override
      public String plainIdentifier(
          final String workflowModuleId,
          final String scopedIdentifier,
          final String adapterId) {

        throw new UnsupportedOperationException();

      }

      @Override
      public String plainTaskDefinition(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String scopedTaskDefinition,
          final String adapterId) {

        throw new UnsupportedOperationException();

      }

      @Override
      public void validateNoneNameClashStrategy(
          final String adapterId,
          final String byAdapterOnlyPropertyKey) {
      }

      @Override
      public void validateNativeIsolationSupported(
          final String adapterId,
          final String workflowModuleId,
          final String bpmsDescription) {

        throw new UnsupportedOperationException();

      }


      @Override
      public void reportIdentifiersTheBpmsAlreadyHolds(
          final String adapterId,
          final String workflowModuleId,
          final Collection<IdentifierHeldElsewhere> found) {

        throw new UnsupportedOperationException();

      }

      @Override
      public void reportIdentifiersTheModelsDeclare(
          final String adapterId,
          final String workflowModuleId,
          final Collection<ModelIdentifier> declared) {

        throw new UnsupportedOperationException();

      }

      @Override
      public void reportIdentifiersOfHeldVersion(
          final String adapterId,
          final String workflowModuleId,
          final String bpmnProcessId,
          final String version,
          final Long activeWorkflows,
          final Collection<ModelIdentifier> declared) {

        throw new UnsupportedOperationException();

      }

      @Override
      public void validateNoCollidingProcessIds(
          final String adapterId,
          final Collection<DeployedProcess> deployedProcesses) {

        throw new UnsupportedOperationException();

      }

    };

  }

}
