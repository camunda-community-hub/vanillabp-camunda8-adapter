package io.vanillabp.camunda8.client;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import io.camunda.client.CredentialsProvider;
import io.camunda.client.impl.NoopCredentialsProvider;

/**
 * The credentials one Camunda 8 adapter instance sends, and what it says when the
 * cluster refuses them.
 * <p>
 * Two things happen here. The first is building the client's own
 * {@link CredentialsProvider} from the adapter's <code>auth.*</code> block, reusing the
 * client's builders rather than handling tokens here: the OIDC provider caches its token
 * on disk and refreshes it lazily, which is work nobody should do twice.
 * <p>
 * The second is a message. Whether a cluster accepts a credential can only be learned by
 * asking it, so nothing about it is knowable at startup - but the client asks
 * {@link CredentialsProvider#shouldRetryRequest(CredentialsProvider.StatusCode)} on every
 * request the cluster refused, on both transports and for commands as well as for job
 * activation. That one method is therefore the place where an adapter learns that its
 * credentials are wrong, or that it sent none where some were wanted, and the provider
 * built here is wrapped to say so once per adapter id.
 * <p>
 * Nothing logged here carries a secret, not even its length: the messages name property
 * keys, the method and the address.
 * <p>
 * Why the runtime message hangs on the credentials provider rather than on the error classification
 * is decision 10 in the repository's DECISIONS.md.
 */
// see decision 4 in the repository's DECISIONS.md
@SuppressWarnings("LombokGetterMayBeUsed")
public final class Camunda8Authentication {

  /**
   * Environment variables from which the CLIENT would build a credentials provider of its
   * own - but only while the application set none. Once this adapter sets one, they stop
   * choosing the method, which is a behaviour change for a deployment that relied on them
   * (see {@link Camunda8EnvironmentOverrides}).
   */
  static final String[] ENVIRONMENT_CREDENTIAL_VARIABLES = {
      "CAMUNDA_CLIENT_ID", "CAMUNDA_CLIENT_SECRET", "ZEEBE_CLIENT_ID", "ZEEBE_CLIENT_SECRET", "CAMUNDA_BASIC_AUTH_USERNAME", "CAMUNDA_BASIC_AUTH_PASSWORD"
  };

  private final String adapterId;

  private final Camunda8AuthConfiguration.Method method;

  private final boolean methodDetected;

  private final String principal;

  private final CredentialsProvider provider;

  private final AtomicBoolean rejectionReported = new AtomicBoolean(false);

  private Camunda8Authentication(
      final String adapterId,
      final Camunda8AuthConfiguration.Method method,
      final boolean methodDetected,
      final String principal,
      final CredentialsProvider provider) {

    this.adapterId = adapterId;
    this.method = method;
    this.methodDetected = methodDetected;
    this.principal = principal;
    this.provider = provider;

  }

  /**
   * Resolves the authentication of one adapter instance and builds its provider.
   *
   * @param adapterId The adapter id
   * @param configuration The adapter's connection configuration
   * @param environment Reads an environment variable (usually {@code System::getenv})
   * @return The resolved authentication
   */
  public static Camunda8Authentication of(
      final String adapterId,
      final Camunda8AdapterConfiguration configuration,
      final Function<String, String> environment) {

    final var auth = configuration.getAuth();
    final var method = auth.resolveMethod(configuration.getMode());
    final var detected = !auth.isMethodExplicit();

    if ((method == Camunda8AuthConfiguration.Method.NONE) && environmentCarriesCredentials(environment)) {
      // the client installs a provider of its own from those variables, but only while
      // the application set none - so this adapter sets none, and says whose credentials
      // are in play
      return new Camunda8Authentication(adapterId, method, detected, null, null);
    }

    return new Camunda8Authentication(
        adapterId, method, detected, auth.principal(configuration), buildProvider(configuration, method));

  }

  /**
   * The provider to hand the client builder, wrapped so a refused request is reported.
   *
   * @param logger Sink for the message (one line, at WARN)
   * @return The provider, or <code>null</code> where the client keeps the one it builds
   *         from the environment
   */
  public CredentialsProvider providerFor(
      final Consumer<String> logger) {

    return provider == null
        ? null
        : new Observing(provider, statusCode -> reportRejection(statusCode, logger));

  }

  /**
   * How this adapter instance authenticates, for the startup line - the method, whether
   * it was detected rather than named, and who it authenticates as.
   *
   * @return The description
   */
  public String describe() {

    final var how = methodDetected
        ? "%s (detected)".formatted(method.name().toLowerCase())
        : method.name().toLowerCase();
    if (principal == null) {
      return provider == null && method == Camunda8AuthConfiguration.Method.NONE
          ? "%s, credentials from the environment".formatted(how)
          : how;
    }
    return "%s as '%s'".formatted(how, principal);

  }

  /**
   * The provider behind the wrapper this class puts around it - what the client would
   * have got without the reporting.
   *
   * @param provider What a built client reports
   * @return The Camunda client's own provider
   */
  public static CredentialsProvider unwrap(
      final CredentialsProvider provider) {

    return provider instanceof Observing observing
        ? observing.delegate
        : provider;

  }

  /**
   * Which of the three methods this adapter instance ended up with, the detected one included.
   *
   * @return The method this adapter instance uses
   */
  public Camunda8AuthConfiguration.Method getMethod() {

    return method;

  }

  private void reportRejection(
      final CredentialsProvider.StatusCode statusCode,
      final Consumer<String> logger) {

    if (!rejectionReported.compareAndSet(false, true)) {
      return;
    }
    logger.accept(message(adapterId, method, statusCode.code()));

  }

  /**
   * What the adapter says the first time its cluster refuses a request.
   *
   * @param adapterId The adapter id
   * @param method The method in use
   * @param statusCode The status the cluster answered with (an HTTP status, or a gRPC
   *          code on that transport)
   * @return The message
   */
  static String message(
      final String adapterId,
      final Camunda8AuthConfiguration.Method method,
      final int statusCode) {

    if (method == Camunda8AuthConfiguration.Method.NONE) {
      return """
          Camunda 8 adapter '%s' authenticates with 'none' and the cluster refused a request (%s). A \
          self-managed cluster normally has its authentication switched on, and an adapter without an \
          '%s' block sends no credentials at all. Name the method the cluster wants:
          %s
          The same block takes 'method: oidc' with 'client-id', 'client-secret', \
          'authorization-server-url' and 'audience' where an identity provider issues the tokens."""
          .formatted(
              adapterId,
              describeStatus(statusCode),
              Camunda8AdapterConfiguration.propertyKey(adapterId, "auth"),
              Camunda8AuthConfiguration.exampleYaml(adapterId, Camunda8AuthConfiguration.Method.BASIC));
    }
    return """
        Camunda 8 adapter '%s' authenticates with '%s' and the cluster refused a request (%s). The \
        credentials below '%s' reached the cluster and were not accepted, so the values are wrong, the \
        account is unknown, or it lacks the permission for what was asked. Check them against the \
        cluster - VanillaBP never logs a credential, so this message cannot tell you which of them it \
        was."""
        .formatted(
            adapterId,
            method.name().toLowerCase(),
            describeStatus(statusCode),
            Camunda8AdapterConfiguration.propertyKey(adapterId, "auth"));

  }

  /**
   * gRPC counts its own codes from zero, HTTP starts at 100, so the number itself says
   * which transport answered.
   */
  private static String describeStatus(
      final int statusCode) {

    return switch (statusCode) {
      case 401 -> "HTTP 401 Unauthorized";
      case 403 -> "HTTP 403 Forbidden";
      case 7 -> "gRPC PERMISSION_DENIED";
      case 16 -> "gRPC UNAUTHENTICATED";
      default -> statusCode < 100
          ? "gRPC code %d".formatted(statusCode)
          : "HTTP %d".formatted(statusCode);
    };

  }

  /**
   * Whether the cluster refused the request because of who asked, rather than because of
   * what was asked.
   *
   * @param statusCode What the cluster answered
   * @return Whether this is an authentication or authorization refusal
   */
  static boolean isRefusal(
      final CredentialsProvider.StatusCode statusCode) {

    if (statusCode == null) {
      return false;
    }
    if (statusCode.isUnauthorized()) {
      return true;
    }
    final var code = statusCode.code();
    // 401/403 on HTTP, UNAUTHENTICATED (16) and PERMISSION_DENIED (7) on gRPC - the two
    // numbering schemes cannot collide, HTTP has no status below 100
    return (code == 401) || (code == 403) || (code == 7) || (code == 16);

  }

  private static boolean environmentCarriesCredentials(
      final Function<String, String> environment) {

    return (isSet(environment, "CAMUNDA_CLIENT_ID") || isSet(environment, "ZEEBE_CLIENT_ID")) && (isSet(environment,
        "CAMUNDA_CLIENT_SECRET") || isSet(environment, "ZEEBE_CLIENT_SECRET")) || (isSet(environment,
            "CAMUNDA_BASIC_AUTH_USERNAME") && isSet(environment, "CAMUNDA_BASIC_AUTH_PASSWORD"));

  }

  private static boolean isSet(
      final Function<String, String> environment,
      final String variable) {

    final var value = environment.apply(variable);
    return (value != null) && !value.isBlank();

  }

  private static CredentialsProvider buildProvider(
      final Camunda8AdapterConfiguration configuration,
      final Camunda8AuthConfiguration.Method method) {

    final var auth = configuration.getAuth();
    return switch (method) {
      case NONE -> new NoopCredentialsProvider();
      case BASIC -> CredentialsProvider
          .newBasicAuthCredentialsProviderBuilder()
          .username(auth.getUsername())
          .password(auth.getPassword())
          .build();
      case OIDC -> buildOidcProvider(configuration);
    };

  }

  private static CredentialsProvider buildOidcProvider(
      final Camunda8AdapterConfiguration configuration) {

    final var auth = configuration.getAuth();
    final var saas = configuration.getMode() == Camunda8AdapterConfiguration.Mode.SAAS;
    // SaaS is an OIDC client with two presets, so it takes the same path: the cluster's
    // audience and Camunda's login endpoint, both overridable for a setup which needs it
    final var builder = CredentialsProvider
        .newCredentialsProviderBuilder()
        .clientId(
            firstOf(auth.getClientId(), configuration.getClientId()))
        .clientSecret(
            firstOf(auth.getClientSecret(), configuration.getClientSecret()))
        .audience(
            firstOf(auth.getAudience(), saas
                ? Camunda8AuthConfiguration.SAAS_AUDIENCE
                : null))
        .authorizationServerUrl(
            firstOf(auth.getAuthorizationServerUrl(), saas
                ? Camunda8AuthConfiguration.SAAS_AUTHORIZATION_SERVER_URL
                : null));
    if (hasText(auth.getScope())) {
      builder.scope(auth.getScope());
    }
    if (hasText(auth.getCredentialsCachePath())) {
      builder.credentialsCachePath(auth.getCredentialsCachePath());
    }
    if (auth.getConnectTimeout() != null) {
      builder.connectTimeout(auth.getConnectTimeout());
    }
    if (auth.getReadTimeout() != null) {
      builder.readTimeout(auth.getReadTimeout());
    }
    if (hasText(auth.getKeystorePath())) {
      builder.keystorePath(Paths.get(auth.getKeystorePath()));
      builder.keystorePassword(auth.getKeystorePassword());
      builder.keystoreKeyPassword(auth.getKeystoreKeyPassword());
    }
    if (hasText(auth.getTruststorePath())) {
      builder.truststorePath(Paths.get(auth.getTruststorePath()));
      builder.truststorePassword(auth.getTruststorePassword());
    }
    return builder.build();

  }

  private static String firstOf(
      final String preferred,
      final String fallback) {

    return hasText(preferred)
        ? preferred
        : fallback;

  }

  private static boolean hasText(
      final String value) {

    return (value != null) && !value.isBlank();

  }

  /**
   * Passes everything to the provider it wraps and watches the one method the client
   * calls whenever a request came back refused.
   */
  static final class Observing implements CredentialsProvider {

    private final CredentialsProvider delegate;

    private final Consumer<StatusCode> onRefusal;

    Observing(
        final CredentialsProvider delegate,
        final Consumer<StatusCode> onRefusal) {

      this.delegate = delegate;
      this.onRefusal = onRefusal;

    }

    @Override
    public void applyCredentials(
        final CredentialsApplier applier) throws IOException {

      delegate.applyCredentials(applier);

    }

    @Override
    public boolean shouldRetryRequest(
        final StatusCode statusCode) {

      if (isRefusal(statusCode)) {
        onRefusal.accept(statusCode);
      }
      return delegate.shouldRetryRequest(statusCode);

    }

  }

}
