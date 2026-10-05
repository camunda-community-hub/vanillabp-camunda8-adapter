package io.vanillabp.camunda8.quarkus.runtime.graal;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Answers the HTTP client's question for the zstd library before the image is built.
 * <p>
 * Apache HttpClient 5 registers a zstd response decoder if <code>zstd-jni</code> is on
 * the classpath, and it decides that by reflection - which the image builder cannot fold
 * away, so both branches stay reachable and the decoder's reference to the absent library
 * ends the build of every application using this adapter:
 * <p>
 * <code>Discovered unresolved type during parsing:
 * com.github.luben.zstd.ZstdDecompressCtx</code>
 * <p>
 * Answered with a constant here, the branch is gone and so is the decoder. The cost is
 * named in the README: a native image built with this adapter accepts gzip and deflate
 * responses, and adding the zstd library to the application does not change that.
 * Camunda's REST API is served over gzip, and the client asks for what it can decode.
 * <p>
 * Read by the image builder only; a JVM never loads this class.
 */
@TargetClass(className = "org.apache.hc.client5.http.impl.ZstdRuntime")
final class ZstdIsNotOnTheClasspath {

  @Substitute
  public static boolean available() {

    return false;

  }

}
