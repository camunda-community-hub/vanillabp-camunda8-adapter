package io.vanillabp.camunda8.quarkus.runtime.graal;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import org.apache.hc.core5.reactor.ssl.TlsDetails;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Takes Conscrypt out of the HTTP client a native image links.
 * <p>
 * The Camunda client talks REST through Apache HttpClient 5, whose connection-manager
 * builder names {@code ConscryptClientTlsStrategy} - a TLS strategy for an optional
 * library which is chosen on Java 8 and never afterwards. Nobody puts Conscrypt on the
 * classpath of a VanillaBP application, and on the JVM that costs nothing, because the
 * class is loaded only if it is used. A native image resolves every type a reachable
 * method names while it is built, so the absent library ends the build of every
 * application using this adapter:
 * <p>
 * <code>Discovered unresolved type during parsing: org.conscrypt.Conscrypt</code>
 * <p>
 * The two methods below are the only ones naming it. Substituted, they do what the
 * original does for an engine which is not a Conscrypt engine - which is every engine
 * these applications will ever see, since the strategy is picked on Java 8 alone.
 * <p>
 * Read by the image builder only; a JVM never loads this class.
 */
@TargetClass(className = "org.apache.hc.client5.http.ssl.ConscryptClientTlsStrategy")
final class HttpClientWithoutConscrypt {

  @Substitute
  void applyParameters(
      final SSLEngine sslEngine,
      final SSLParameters sslParameters,
      final String[] appProtocols) {

    sslParameters.setApplicationProtocols(appProtocols);
    sslEngine.setSSLParameters(sslParameters);

  }

  @Substitute
  TlsDetails createTlsDetails(
      final SSLEngine sslEngine) {

    return null;

  }

}
