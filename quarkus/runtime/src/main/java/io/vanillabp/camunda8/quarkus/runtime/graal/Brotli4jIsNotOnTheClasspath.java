package io.vanillabp.camunda8.quarkus.runtime.graal;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * The same answer as {@link ZstdIsNotOnTheClasspath}, for the other optional decoder of
 * Apache HttpClient 5:
 * <p>
 * <code>Discovered unresolved type during parsing:
 * com.aayushatharva.brotli4j.decoder.DecoderJNI$Wrapper</code>
 * <p>
 * Read by the image builder only; a JVM never loads this class.
 */
@TargetClass(className = "org.apache.hc.client5.http.impl.Brotli4jRuntime")
final class Brotli4jIsNotOnTheClasspath {

  @Substitute
  public static boolean available() {

    return false;

  }

}
