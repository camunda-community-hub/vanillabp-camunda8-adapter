package io.vanillabp.camunda8.quarkus.runtime.graal;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import org.apache.hc.core5.reactor.ssl.TlsDetails;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Takes Conscrypt out of the HTTP client a native image links.
 * <p>
 * The Camunda client talks REST through Apache HttpClient 5. Its connection-manager builder
 * names {@code ConscryptClientTlsStrategy}. That is a TLS strategy for an optional library,
 * and the builder only picks it on Java 8. Nobody puts Conscrypt on the classpath of a
 * VanillaBP application. On the JVM that costs nothing, because the class is only loaded if
 * it is used. But a native image resolves every type a reachable method names while it is
 * built. So the missing library ends the build of every application which uses this
 * adapter:
 * <p>
 * <code>Discovered unresolved type during parsing: org.conscrypt.Conscrypt</code>
 * <p>
 * The two methods below are the only ones which name it. The substitutes do what the
 * original does for an engine which is not a Conscrypt engine. These applications never see
 * any other engine, because the strategy is only picked on Java 8.
 * <p>
 * Only the image builder reads this class. A JVM never loads it.
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
