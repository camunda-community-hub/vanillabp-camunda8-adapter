package io.vanillabp.camunda8.health;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;

/**
 * What one Camunda 8 adapter instance can say about its cluster: it asks for the
 * topology, which is the cheapest question a Camunda 8 cluster answers and the same one
 * Camunda's own Spring Boot starter asks in its health check.
 * <p>
 * Three answers are possible, and which one is given is decided before the cluster is
 * contacted:
 * <ul>
 * <li>the connection is not configured yet, or the check was switched off with
 * <code>health-timeout: PT0S</code>: UNKNOWN. An application which booted with a guiding
 * warning about missing properties must not be reported as an outage on top of it;</li>
 * <li>the cluster answered: UP, with its gateway version and how many brokers it
 * has;</li>
 * <li>the cluster did not answer within the timeout, or answered with an error: DOWN,
 * with the reason.</li>
 * </ul>
 * Every answer carries the address, because the whole point of the detail is that an
 * operator can act on it without opening the application's configuration first.
 */
public final class Camunda8Health {

  /**
   * The adapter type this check belongs to, which is what the platform reports it under.
   */
  public static final String ADAPTER_TYPE = "camunda8";

  private Camunda8Health() {
  }

  /**
   * Asks the cluster of the given adapter instance whether it is there.
   *
   * @param adapterId The adapter instance
   * @param clientFactory Its client factory
   * @return What was found, never <code>null</code>
   */
  public static AdapterHealth check(
      final String adapterId,
      final Camunda8ClientFactory clientFactory) {

    final var configuration = clientFactory.getConfiguration();
    final var address = configuration.describeAddress();
    final var missing = configuration.missingConnectionProperties();
    if (!missing.isEmpty()) {
      return AdapterHealth
          .unknown(
              adapterId,
              ADAPTER_TYPE,
              "The Camunda 8 connection is not configured yet (missing: %s)".formatted(String.join(", ", missing)),
              AdapterHealth
                  .detailsBuilder()
                  .with("address", address)
                  .with("mode", configuration
                      .getMode()
                      .name()
                      .toLowerCase())
                  .build());
    }

    final var timeout = configuration.resolvedHealthTimeout();
    if (timeout.isZero()) {
      return AdapterHealth
          .unknown(
              adapterId,
              ADAPTER_TYPE,
              "The health check is switched off ('%s: PT0S')"
                  .formatted(Camunda8AdapterConfiguration.propertyKey(adapterId, "health-timeout")),
              AdapterHealth
                  .detailsBuilder()
                  .with("address", address)
                  .build());
    }

    final var request = clientFactory
        .getClient()
        .newTopologyRequest()
        // twice, on purpose: the client's own timeout stops the request, ours stops the
        // waiting. Without the first one a cluster which never answers would leave the
        // request running long after the health endpoint gave up on it
        .requestTimeout(timeout)
        .send();
    try {
      final var topology = request.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return AdapterHealth
          .up(
              adapterId,
              ADAPTER_TYPE,
              "The cluster answered",
              AdapterHealth
                  .detailsBuilder()
                  .with("address", address)
                  .with("tenant", configuration.getTenantId())
                  .with("gatewayVersion", topology.getGatewayVersion())
                  .with("brokers", String.valueOf(topology
                      .getBrokers()
                      .size()))
                  .with("partitions", String.valueOf(topology.getPartitionsCount()))
                  .build());
    } catch (final InterruptedException e) {
      Thread
          .currentThread()
          .interrupt();
      request.cancel(true);
      return down(adapterId, address, "The health check was interrupted", timeout);
    } catch (final Exception e) {
      // includes the TimeoutException of a cluster which is simply too slow to answer -
      // for a health endpoint the two are the same thing said differently
      request.cancel(true);
      return down(adapterId, address, describeFailure(e), timeout);
    }

  }

  private static AdapterHealth down(
      final String adapterId,
      final String address,
      final String reason,
      final Duration timeout) {

    return AdapterHealth
        .down(
            adapterId,
            ADAPTER_TYPE,
            reason,
            AdapterHealth
                .detailsBuilder()
                .with("address", address)
                .with("timeout", timeout.toString())
                .build());

  }

  /**
   * The reason a cluster did not answer, as short as it can be said. A timeout carries no
   * message of its own, so it is named rather than reported as an empty exception.
   *
   * @param failure What the request ended with
   * @return One line for a human
   */
  private static String describeFailure(
      final Exception failure) {

    var cause = (Throwable) failure;
    while ((cause.getCause() != null) && (cause.getCause() != cause)) {
      cause = cause.getCause();
    }
    if (failure instanceof TimeoutException) {
      return "The cluster did not answer the topology request in time";
    }
    final var message = cause.getMessage();
    return (message == null) || message.isBlank()
        ? "The topology request failed: %s".formatted(cause
            .getClass()
            .getSimpleName())
        : "The topology request failed: %s".formatted(message);

  }

}
