package io.vanillabp.camunda8.quarkus.runtime.graal;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Answers the HTTP client's question for the zstd library before the image is built.
 * <p>
 * Apache HttpClient 5 registers a zstd response decoder if <code>zstd-jni</code> is on
 * the classpath. It finds that out by reflection, and the image builder cannot fold that
 * away. So both branches stay reachable. The decoder refers to the missing library, and
 * that ends the build of every application which uses this adapter:
 * <p>
 * <code>Discovered unresolved type during parsing:
 * com.github.luben.zstd.ZstdDecompressCtx</code>
 * <p>
 * Here the question gets a constant answer. So the branch is gone, and the decoder with it.
 * The README names the cost: a native image built with this adapter accepts gzip and
 * deflate responses, and adding the zstd library to the application does not change that.
 * Camunda's REST API is served over gzip, and the client asks for what it can decode.
 * <p>
 * Only the image builder reads this class. A JVM never loads it.
 */
@TargetClass(className = "org.apache.hc.client5.http.impl.ZstdRuntime")
final class ZstdIsNotOnTheClasspath {

  @Substitute
  public static boolean available() {

    return false;

  }

}
