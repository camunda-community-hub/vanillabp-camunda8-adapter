package io.vanillabp.camunda8.client;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.camunda.client.CamundaClientConfiguration;

/**
 * Makes visible what an environment variable changed about a client this adapter had just
 * configured, validated and reported at startup.
 * <p>
 * The Camunda client reads a set of <code>CAMUNDA_*</code> variables (with legacy
 * <code>ZEEBE_*</code> fallbacks) and applies them OVER everything the builder set, and this
 * adapter deliberately keeps that on: it is today the only way to reach a client option
 * VanillaBP does not model. What was missing is the trace. Nothing is logged about it, not
 * even at TRACE, so the addresses, the transport preference, the CA certificate, the TLS
 * authority, the default tenant and the streaming default could all be replaced without a
 * word in VanillaBP's own log.
 * <p>
 * This class closes that: after the client was built, what the adapter asked for is compared
 * against what the client reports, and every value an environment variable changed is named
 * with the variable, the configured value and the effective one. Credentials are not among
 * the compared values, so no message can carry a secret.
 * <p>
 * Why the adapter configures the client rather than each worker, which is what lets an environment
 * variable win here, is decision 7 in the repository's DECISIONS.md.
 */
public final class Camunda8EnvironmentOverrides {

  private Camunda8EnvironmentOverrides() {
  }

  /**
   * One value an environment variable changed.
   *
   * @param variable The environment variable which is set
   * @param propertyKey The VanillaBP property the value would otherwise come from
   * @param configured What the adapter configured (may be <code>null</code>: nothing)
   * @param effective What the built client reports
   */
  public record Override(
                         String variable,
                         String propertyKey,
                         String configured,
                         String effective) {
  }

  /**
   * Compares the adapter's configuration against the built client's.
   *
   * @param adapterId The adapter id (used to build the property keys)
   * @param configuration What the adapter configured
   * @param clientConfiguration What the built client reports
   * @param environment Reads an environment variable (usually {@code System::getenv})
   * @return Every value an environment variable changed, in configuration order
   */
  public static List<Override> detect(
      final String adapterId,
      final Camunda8AdapterConfiguration configuration,
      final CamundaClientConfiguration clientConfiguration,
      final Function<String, String> environment) {

    final var overrides = new LinkedList<Override>();

    compare(
        overrides, environment, "CAMUNDA_REST_ADDRESS", "ZEEBE_REST_ADDRESS",
        Camunda8AdapterConfiguration.propertyKey(adapterId, "rest-address"),
        configuration.getRestAddress(), asString(clientConfiguration.getRestAddress()));
    compare(
        overrides, environment, "CAMUNDA_GRPC_ADDRESS", "ZEEBE_GRPC_ADDRESS",
        Camunda8AdapterConfiguration.propertyKey(adapterId, "grpc-address"),
        configuration.getGrpcAddress(), asString(clientConfiguration.getGrpcAddress()));
    compare(
        overrides, environment, "CAMUNDA_PREFER_REST", "ZEEBE_PREFER_REST",
        Camunda8AdapterConfiguration.propertyKey(adapterId, "prefer-rest-over-grpc"),
        String.valueOf(configuration.isPreferRestOverGrpc()),
        String.valueOf(clientConfiguration.preferRestOverGrpc()));
    compare(
        overrides, environment, "CAMUNDA_DEFAULT_TENANT_ID", "ZEEBE_DEFAULT_TENANT_ID",
        Camunda8AdapterConfiguration.propertyKey(adapterId, "tenant-id"),
        configuration.getTenantId(), clientConfiguration.getDefaultTenantId());
    compare(
        overrides, environment, "CAMUNDA_CLIENT_WORKER_STREAM_ENABLED", "ZEEBE_CLIENT_WORKER_STREAM_ENABLED",
        Camunda8AdapterConfiguration.propertyKey(adapterId, "stream-enabled"),
        asString(configuration.getStreamEnabled()),
        String.valueOf(clientConfiguration.getDefaultJobWorkerStreamEnabled()));
    compare(
        overrides, environment, "CAMUNDA_OVERRIDE_AUTHORITY", "ZEEBE_OVERRIDE_AUTHORITY",
        Camunda8AdapterConfiguration.propertyKey(adapterId, "override-authority"),
        configuration.getOverrideAuthority(), clientConfiguration.getOverrideAuthority());
    compare(
        overrides, environment, "CAMUNDA_KEEP_ALIVE", "ZEEBE_KEEP_ALIVE",
        Camunda8AdapterConfiguration.propertyKey(adapterId, "keep-alive"),
        asString(configuration.getKeepAlive()), asString(clientConfiguration.getKeepAlive()));
    compare(
        overrides, environment, "CAMUNDA_MAX_HTTP_CONNECTIONS", "ZEEBE_MAX_HTTP_CONNECTIONS",
        Camunda8AdapterConfiguration.propertyKey(adapterId, "max-http-connections"),
        asString(configuration.getMaxHttpConnections()),
        String.valueOf(clientConfiguration.getMaxHttpConnections()));
    compare(
        overrides, environment, "CAMUNDA_CA_CERTIFICATE_PATH", "ZEEBE_CA_CERTIFICATE_PATH",
        Camunda8AdapterConfiguration.propertyKey(adapterId, "auth.ca-certificate-path"),
        configuration.getAuth().getCaCertificatePath(), clientConfiguration.getCaCertificatePath());

    return overrides;

  }

  /**
   * The variables from which the client would pick an authentication method of its own -
   * but only while the application set none. Once the adapter sets a provider, which it
   * always does unless it authenticates with <code>none</code>, these stop
   * choosing the method. The values still apply where they belong to the method the
   * adapter chose, because the client's credential builders read their own environment.
   *
   * @param adapterId The adapter id
   * @param authentication The resolved authentication of the adapter instance
   * @param environment Reads an environment variable (usually {@code System::getenv})
   * @return The message, or <code>null</code> where there is nothing to say
   */
  public static String describeCredentialSelection(
      final String adapterId,
      final Camunda8Authentication authentication,
      final Function<String, String> environment) {

    if (authentication.getMethod() == Camunda8AuthConfiguration.Method.NONE) {
      // nothing was taken away: either the client still builds its provider from the
      // environment, or there is nothing in the environment to build one from
      return null;
    }
    final var set = Arrays
        .stream(Camunda8Authentication.ENVIRONMENT_CREDENTIAL_VARIABLES)
        .filter(variable -> isSet(environment, variable))
        .collect(Collectors.joining(", "));
    if (set.isEmpty()) {
      return null;
    }
    return """
        Camunda 8 adapter '%s' authenticates with '%s' from its configuration, and the environment also \
        carries credentials (%s). The client picks a method from those variables only while the \
        application names none, so they no longer decide how this adapter authenticates - a deployment \
        which relied on them has to move them into '%s'. Where a variable belongs to the chosen method \
        it still overrules the configured value, which is the escape hatch it always was."""
        .formatted(
            adapterId,
            authentication.getMethod().name().toLowerCase(),
            set,
            Camunda8AdapterConfiguration.propertyKey(adapterId, "auth"));

  }

  /**
   * Builds the WARN text for what {@link #detect} found.
   *
   * @param adapterId The adapter id
   * @param overrides What was found (never empty when this is called)
   * @return The message
   */
  public static String describe(
      final String adapterId,
      final List<Override> overrides) {

    final var lines = overrides
        .stream()
        .map(override -> "  %s replaced %s: '%s' -> '%s'".formatted(
            override.variable(),
            override.propertyKey(),
            override.configured() == null ? "" : override.configured(),
            override.effective() == null ? "" : override.effective()))
        .collect(Collectors.joining("\n"));
    return """
        Camunda 8 adapter '%s': the environment changed what this application configured. The client \
        applies CAMUNDA_* variables (and their legacy ZEEBE_* names) over everything VanillaBP set, \
        which stays switched on because it is the only way to reach a client option VanillaBP does \
        not model - but these values are no longer the ones the configuration and the startup \
        messages describe:
        %s
        Remove the variable to go back to the configured value."""
        .formatted(adapterId, lines);

  }

  private static void compare(
      final List<Override> overrides,
      final Function<String, String> environment,
      final String variable,
      final String legacyVariable,
      final String propertyKey,
      final String configured,
      final String effective) {

    final var name = isSet(environment, variable)
        ? variable
        : isSet(environment, legacyVariable) ? legacyVariable : null;
    if (name == null) {
      return;
    }
    if (Objects.equals(emptyAsNull(configured), emptyAsNull(effective))) {
      return;
    }
    overrides.add(new Override(name, propertyKey, configured, effective));

  }

  private static boolean isSet(
      final Function<String, String> environment,
      final String variable) {

    final var value = environment.apply(variable);
    return (value != null) && !value.isBlank();

  }

  private static String emptyAsNull(
      final String value) {

    return ((value == null) || value.isBlank())
        ? null
        : value;

  }

  private static String asString(
      final Object value) {

    return value == null
        ? null
        : value.toString();

  }

}
