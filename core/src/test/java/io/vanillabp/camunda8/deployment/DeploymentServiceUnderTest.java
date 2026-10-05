package io.vanillabp.camunda8.deployment;

import java.time.Duration;
import java.util.function.Function;

import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * Builds the deployment service for a test which does not need every collaborator.
 * <p>
 * The service itself has one constructor, and it takes everything an application can
 * configure. The shorter forms live here because a public constructor is a promise to every
 * application, and these are only meant for our own tests.
 */
public final class DeploymentServiceUnderTest {

  private DeploymentServiceUnderTest() {
    // static helper
  }

  /**
   * Builds the service without the configuration resolver and without the name-clash
   * avoidance. Two adapter ids of this type are not checked for distinctness then, and
   * nothing is scoped.
   *
   * @param adapterId The configured adapter id this service deploys for
   * @param clientFactory The clients of that adapter id
   * @param collaborators What the platform integration hands every adapter
   * @param jobTimeoutResolver Answers how long a job of a task stays locked
   * @param asyncTaskLockRenewal How far a probe pushes the lock of a job it looks at
   * @return The service
   */
  public static Camunda8DeploymentService of(
      final String adapterId,
      final Camunda8ClientFactory clientFactory,
      final AdapterCollaborators collaborators,
      final Camunda8JobTimeoutResolver jobTimeoutResolver,
      final Duration asyncTaskLockRenewal) {

    return of(adapterId, clientFactory, collaborators, jobTimeoutResolver, asyncTaskLockRenewal, null);

  }

  /**
   * Builds the service without the name-clash avoidance, which is what a test of a single
   * adapter id needs.
   *
   * @param adapterId The configured adapter id this service deploys for
   * @param clientFactory The clients of that adapter id
   * @param collaborators What the platform integration hands every adapter
   * @param jobTimeoutResolver Answers how long a job of a task stays locked
   * @param asyncTaskLockRenewal How far a probe pushes the lock of a job it looks at
   * @param configurations The adapter section per adapter id, or <code>null</code> to leave two
   *          ids of this type unchecked for distinctness
   * @return The service
   */
  public static Camunda8DeploymentService of(
      final String adapterId,
      final Camunda8ClientFactory clientFactory,
      final AdapterCollaborators collaborators,
      final Camunda8JobTimeoutResolver jobTimeoutResolver,
      final Duration asyncTaskLockRenewal,
      final Function<String, Camunda8AdapterConfiguration> configurations) {

    return of(adapterId, clientFactory, collaborators, jobTimeoutResolver, asyncTaskLockRenewal, configurations, null);

  }

  /**
   * Builds the service without the retry-backoff resolver, so every failed job waits the
   * backoff the cluster itself decides on.
   *
   * @param adapterId The configured adapter id this service deploys for
   * @param clientFactory The clients of that adapter id
   * @param collaborators What the platform integration hands every adapter
   * @param jobTimeoutResolver Answers how long a job of a task stays locked
   * @param asyncTaskLockRenewal How far a probe pushes the lock of a job it looks at
   * @param configurations The adapter section per adapter id, or <code>null</code> to leave two
   *          ids of this type unchecked for distinctness
   * @param scoping How identifiers are kept apart where two adapter ids share a cluster, or
   *          <code>null</code> for none
   * @return The service
   */
  public static Camunda8DeploymentService of(
      final String adapterId,
      final Camunda8ClientFactory clientFactory,
      final AdapterCollaborators collaborators,
      final Camunda8JobTimeoutResolver jobTimeoutResolver,
      final Duration asyncTaskLockRenewal,
      final Function<String, Camunda8AdapterConfiguration> configurations,
      final NameClashAvoidanceSupport scoping) {

    return new Camunda8DeploymentService(
        adapterId, clientFactory, collaborators, jobTimeoutResolver, asyncTaskLockRenewal, configurations, scoping, null);

  }

}
