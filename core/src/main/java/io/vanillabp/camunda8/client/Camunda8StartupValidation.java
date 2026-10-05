package io.vanillabp.camunda8.client;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Startup validation of one Camunda 8 adapter instance's connection configuration -
 * configuration is validated AT STARTUP, never first at runtime (a VanillaBP core
 * principle). Three states:
 * <ul>
 *   <li><b>complete</b> - nothing to report;</li>
 *   <li><b>no address</b> (self-managed, and the protocol the client talks has no
 *       address) - the client's default address is used, which is a cluster on this
 *       machine. The start goes on, and a WARN names that address and the key which
 *       changes it. See {@link #reportTheClientDefaultAddress};</li>
 *   <li><b>inconsistent</b> (partially configured, e.g. <code>mode: saas</code>
 *       without <code>cluster-id</code>) - a genuine defect: the boot FAILS with a
 *       message naming the missing keys. Exception: an adapter that is NOWHERE
 *       first in any prioritized-adapters list (globally, per module, per workflow)
 *       may honor its <code>deployment-failure: warn</code> policy and degrade to a
 *       WARN - the migration scenario's old BPMS must not block the boot.</li>
 * </ul>
 * Messages name property KEYS only, never values - credentials
 * (<code>client-secret</code> etc.) are never echoed.
 * <p>
 * One question here is not about configuration at all:
 * {@link Camunda8ProtobufRuntime} asks whether the protobuf runtime of this application is
 * new enough for the client's generated code. It is asked in the same place because it has
 * the same answer, a message at startup instead of a failure in the middle of the first
 * command.
 */
public final class Camunda8StartupValidation {

  private Camunda8StartupValidation() {
  }

  /**
   * Validates the given adapter instance's connection configuration at startup.
   *
   * @param adapterId The adapter ID
   * @param configuration The (bound) connection configuration
   * @param firstPriorityAnywhere Whether the adapter is first priority at any level
   *          (see {@code MigrationAdapterProperties#isFirstPriorityAnywhere})
   * @param deploymentFailureWarn Whether the adapter's deployment-failure policy is
   *          <code>warn</code>
   * @param deliveryRetention How long a delivery record is kept
   *          (<code>vanillabp.delivery.retention</code>) - the bound the renewal window of
   *          open asynchronous tasks has to stay below
   * @param warnLogger Sink for guiding warnings (the application keeps booting)
   * @param infoLogger Sink for a line which is not a warning: a configuration which is
   *          right and does something else here than it does on another release line
   * @throws IllegalStateException If the configuration is inconsistent and the
   *           adapter must not degrade (first priority somewhere or policy
   *           <code>fail</code>)
   */
  public static void validateAtStartup(
      final String adapterId,
      final Camunda8AdapterConfiguration configuration,
      final boolean firstPriorityAnywhere,
      final boolean deploymentFailureWarn,
      final Duration deliveryRetention,
      final Consumer<String> warnLogger,
      final Consumer<String> infoLogger) {

    // before any property: a protobuf runtime older than the client's generated code lets
    // no command of this adapter through, whatever the configuration says, and protobuf
    // itself would say so out of a static initializer at the first command
    Camunda8ProtobufRuntime.failIfItIsOlderThanTheClientNeeds();
    // how the adapter runs its workers is independent of whether it can reach a cluster,
    // and a number which cannot work is a typo rather than a migration scenario - so this
    // fails the boot for every adapter id, degraded or not
    configuration.validateWorkerConfiguration(adapterId);
    // and neither is how it proves who it is: a method whose credentials are incomplete
    // cannot be built at all, so it fails the boot naming the method and the keys
    configuration.validateAuthentication(adapterId);
    // and neither is how an open asynchronous task is kept alive: a window which cannot
    // work outlives the record answering its redelivery, which is silent at runtime
    configuration.validateAsyncTaskLockRenewal(adapterId, deliveryRetention);
    // and neither is how long a restart waits for the handlers in flight: a grace which
    // outlives the shutdown budget around it is never granted, and one nobody notices is
    // the reason a restart burns a retry per job
    configuration.validateShutdownGrace(adapterId, warnLogger);
    configuration.validateHealthTimeout(adapterId);
    // and neither is how long the cluster keeps a message this adapter publishes: zero
    // drops every one of them on arrival, and nothing at runtime says so
    configuration.validateMessageTimeToLive(adapterId);
    // and neither is how long the cluster waits before it hands a failed job out again: a
    // negative duration is a typo, and it decides something nobody watches
    configuration.validateRetryBackoff(adapterId);
    // and neither is the deadline every request of this adapter gets: a value which is too
    // short makes a healthy cluster answer too late, which reads like a network problem
    configuration.validateRequestTimeout(adapterId, warnLogger);
    // and neither is how long the start waits for a cluster which is not answering yet
    configuration.validateStartupWait(adapterId);
    // and what the business id of a started workflow carries is said once here, because
    // everything it decides afterwards happens per started workflow
    configuration.validateAggregateIdAsBusinessId(adapterId, infoLogger);
    // and so is the question this adapter asks about every open user task, for the same
    // reason: what it decides happens per wake-up and nowhere a reader would look
    configuration.validateProbeOpenUserTasks(adapterId, infoLogger);

    final var missing = configuration.missingConnectionProperties();
    if (missing.isEmpty()) {
      reportTheClientDefaultAddress(adapterId, configuration, warnLogger);
      validateJobLease(adapterId, configuration, infoLogger);
      return;
    }

    final var missingKeys = missing
        .stream()
        .map(key -> Camunda8AdapterConfiguration.propertyKey(adapterId, key))
        .collect(Collectors.joining("\n  "));
    if (!firstPriorityAnywhere && deploymentFailureWarn) {
      warnLogger.accept(
          """
              Camunda 8 adapter '%s' is configured inconsistently - these properties are missing:
                %s
              The adapter is nowhere first priority and its deployment-failure policy is 'warn', so the \
              application boots DEGRADED: any use of this adapter will fail until the properties are added."""
              .formatted(adapterId, missingKeys));
      return;
    }

    throw new IllegalStateException(
        """
            Camunda 8 adapter '%s' is configured inconsistently - these properties are missing:
              %s
            Add the missing properties. (An adapter that is nowhere first in a prioritized-adapters list \
            may instead set '%s' to 'warn' to boot degraded - e.g. the old BPMS during a migration.)"""
            .formatted(
                adapterId,
                missingKeys,
                Camunda8AdapterConfiguration.propertyKey(adapterId, "deployment-failure")));

  }

  /**
   * Warns where a self-managed adapter has no address for the protocol its client talks. The
   * client then uses its own default address, which is a cluster on this machine. That is
   * what a developer starting a local cluster wants, so the start goes on. It is never what
   * a production system wants, so the start says it every time. Why the default is the
   * client's, and why this adapter is then asked for <code>job-lease</code> like any other, is
   * decision 67 in the repository's DECISIONS.md.
   *
   * @param adapterId The adapter ID
   * @param configuration The (bound) connection configuration
   * @param warnLogger Sink for the warning
   */
  static void reportTheClientDefaultAddress(
      final String adapterId,
      final Camunda8AdapterConfiguration configuration,
      final Consumer<String> warnLogger) {

    if (!configuration.usesTheClientDefaultAddress()) {
      return;
    }
    final var message = new StringBuilder(
        """
            Camunda 8 adapter '%s' has no cluster address, so it connects to the local cluster at '%s'. \
            This is the default address of the Camunda client. Set '%s' to connect to another cluster.
            For Camunda 8 SaaS, set '%s' to 'saas' and add '%s', '%s', '%s' and '%s'.
            If your cluster asks for credentials, configure them under '%s'. Without it the adapter \
            sends none."""
            .formatted(
                adapterId,
                configuration.describeAddress(),
                Camunda8AdapterConfiguration.propertyKey(adapterId, configuration.addressKeyInUse()),
                Camunda8AdapterConfiguration.propertyKey(adapterId, "mode"),
                Camunda8AdapterConfiguration.propertyKey(adapterId, "cluster-id"),
                Camunda8AdapterConfiguration.propertyKey(adapterId, "region"),
                Camunda8AdapterConfiguration.propertyKey(adapterId, "client-id"),
                Camunda8AdapterConfiguration.propertyKey(adapterId, "client-secret"),
                Camunda8AdapterConfiguration.propertyKey(adapterId, "auth")));
    // where the release line knows that Camunda's own docker compose publishes REST on a
    // port other than the client's default, the warning says so
    final var aboutTheLine = configuration.isPreferRestOverGrpc()
        ? Camunda8LocalCluster
            .aboutTheDefaultRestAddress(Camunda8AdapterConfiguration.propertyKey(adapterId, "rest-address"))
        : null;
    if (aboutTheLine != null) {
      message
          .append('\n')
          .append(aboutTheLine);
    }
    // an address written for the other protocol is most likely meant for this one, and the
    // switch which makes the client use it is easy to miss
    final var otherKey = configuration.isPreferRestOverGrpc()
        ? "grpc-address"
        : "rest-address";
    final var otherAddress = configuration.isPreferRestOverGrpc()
        ? configuration.getGrpcAddress()
        : configuration.getRestAddress();
    if ((otherAddress != null) && !otherAddress.isBlank()) {
      message.append(
          """

              '%s' is set, but the client does not talk that protocol. Set '%s' to '%s' to use \
              that address."""
              .formatted(
                  Camunda8AdapterConfiguration.propertyKey(adapterId, otherKey),
                  Camunda8AdapterConfiguration.propertyKey(adapterId, "prefer-rest-over-grpc"),
                  !configuration.isPreferRestOverGrpc()));
    }
    warnLogger.accept(message.toString());

  }

  /**
   * Whether the jobs of this adapter are leased, which is the one key this adapter has no
   * default for: a lease cannot be taken back per job.
   * <p>
   * It is asked LAST, and only of an adapter whose connection is complete. That includes an
   * adapter which uses the client's default address, because it opens workers like any
   * other. An adapter which boots degraded serves nothing, so it opens no worker and leases
   * nothing.
   */
  private static void validateJobLease(
      final String adapterId,
      final Camunda8AdapterConfiguration configuration,
      final Consumer<String> infoLogger) {

    configuration.validateJobLease(adapterId, infoLogger);

  }

}
