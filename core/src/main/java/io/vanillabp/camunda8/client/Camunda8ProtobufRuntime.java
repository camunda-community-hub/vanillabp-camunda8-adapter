package io.vanillabp.camunda8.client;

import io.vanillabp.camunda8.Camunda8ReleaseLine;

/**
 * Says at startup what protobuf would otherwise say at the worst possible moment.
 * <p>
 * The Camunda client ships generated protocol code, and protobuf refuses a runtime older
 * than the code was generated from. The refusal comes out of a static initializer, as an
 * <code>ExceptionInInitializerError</code>, on the first command which touches the protocol
 * and in whatever part of the application happened to send it. Nothing in that error says
 * what to do about it.
 * <p>
 * An application decides this number, not the adapter. The platform BOM an application
 * imports manages <code>protobuf-java</code>, and an imported BOM beats every version a
 * dependency brings, so the pin in this repository's parent POM covers our own build and
 * reaches nobody else. Measured in September 2026: on the 8.10 line both platforms hand an
 * application a runtime OLDER than the client's gencode, on the two older lines a newer one,
 * which protobuf allows. The README's section "Release lines" carries the table.
 * <p>
 * So the adapter asks the question itself, at startup, where a message can name the fix: it
 * loads one generated class and reads what protobuf says about it. That costs a class load
 * the client does a moment later anyway.
 * <p>
 * The question is asked ONCE per JVM and the answer is remembered, because a second attempt
 * to load a class whose initializer failed is answered with a bare
 * <code>NoClassDefFoundError</code> in which protobuf no longer appears.
 */
public final class Camunda8ProtobufRuntime {

  /**
   * The generated protocol code of the Camunda client, which is where protobuf compares its
   * runtime against the version the code was generated from. It travels with the client, in
   * the artifact <code>io.camunda:zeebe-gateway-protocol-impl</code>.
   */
  private static final String GENERATED_PROTOCOL = "io.camunda.zeebe.gateway.protocol.GatewayOuterClass";

  /**
   * Protobuf is recognised by the package of what it throws rather than by its type,
   * because naming the type here would mean declaring <code>protobuf-java</code> as a
   * dependency of this module. That dependency would then stand in the published POM with
   * our own pinned version and push it onto every application, which is the one thing
   * decision 39 in the repository's DECISIONS.md rules out.
   */
  private static final String PROTOBUF = "com.google.protobuf.";

  /** What this classpath is refused for, or {@code null} where nothing is wrong. */
  private static final String REFUSAL = whatProtobufSaysAbout(
      Camunda8ProtobufRuntime::loadTheGeneratedProtocol);

  private Camunda8ProtobufRuntime() {
    // static helper
  }

  /**
   * Fails the boot where the protobuf runtime of this application is older than the code
   * the Camunda client was generated from.
   *
   * @throws IllegalStateException With a message naming the fix, which is one entry in the
   *           application's own dependency management
   */
  public static void failIfItIsOlderThanTheClientNeeds() {

    if (REFUSAL == null) {
      return;
    }
    throw new IllegalStateException(REFUSAL);

  }

  /**
   * Loads the generated protocol code and turns protobuf's refusal into a sentence an
   * application can act on.
   *
   * @param loadTheGeneratedProtocol Loads the class whose initializer asks protobuf
   * @return The message, or {@code null} where protobuf did not refuse anything. Whatever
   *         else goes wrong while loading is no answer about the protobuf runtime, a client
   *         without generated protocol code above all, and it must never keep an application
   *         from booting.
   */
  static String whatProtobufSaysAbout(
      final Runnable loadTheGeneratedProtocol) {

    try {
      loadTheGeneratedProtocol.run();
      return null;
    } catch (Throwable e) {
      final var refusal = protobufsRefusalIn(e);
      return refusal == null
          ? null
          : """
              The protobuf runtime of this application is older than the code the Camunda client \
              of release line %s was generated from, so every command this adapter sends would fail \
              the moment it touches the protocol. Protobuf says: "%s". This application runs \
              protobuf %s.
              The version is the application's to choose: the platform BOM it imports manages \
              protobuf-java, and an imported BOM beats every version a dependency brings, so \
              nothing the adapter declares can raise it. Add the version protobuf names above to \
              the dependencyManagement of the application, BEFORE the platform BOM is imported:
                <dependency>
                  <groupId>com.google.protobuf</groupId>
                  <artifactId>protobuf-java</artifactId>
                  <version>the gencode version named above</version>
                </dependency>
              A runtime newer than the gencode is fine, an older one is not. See the section \
              "Release lines" of the adapter's README for the numbers of every line.\
              """
              .formatted(Camunda8ReleaseLine.id(), refusal.getMessage(), versionOnTheClasspath());
    }

  }

  /**
   * The throwable protobuf raised, from anywhere in the chain, or {@code null} where
   * protobuf raised none of them.
   */
  private static Throwable protobufsRefusalIn(
      final Throwable thrown) {

    for (var cause = thrown; cause != null; cause = cause.getCause()) {
      if (cause
          .getClass()
          .getName()
          .startsWith(PROTOBUF)) {
        return cause;
      }
      if (cause.getCause() == cause) {
        return null;
      }
    }
    return null;

  }

  private static void loadTheGeneratedProtocol() {

    try {
      Class.forName(GENERATED_PROTOCOL);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }

  }

  /**
   * The protobuf runtime this application really runs, read by reflection because naming
   * the class would declare the dependency, or <code>unknown</code> where it cannot be read.
   */
  private static String versionOnTheClasspath() {

    try {
      final var runtimeVersion = Class.forName(PROTOBUF
          + "RuntimeVersion");
      return "%d.%d.%d".formatted(
          runtimeVersion
              .getField("MAJOR")
              .getInt(null),
          runtimeVersion
              .getField("MINOR")
              .getInt(null),
          runtimeVersion
              .getField("PATCH")
              .getInt(null));
    } catch (Throwable e) {
      return "unknown";
    }

  }

}
