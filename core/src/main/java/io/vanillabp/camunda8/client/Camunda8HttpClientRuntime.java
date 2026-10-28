package io.vanillabp.camunda8.client;

import java.io.IOException;
import java.util.Properties;
import java.util.function.Supplier;

import io.vanillabp.camunda8.Camunda8ReleaseLine;

/**
 * Says what to do when the Apache HttpClient 5 of an application is too old for the Camunda
 * client.
 * <p>
 * The Camunda client talks REST through Apache HttpClient 5 and builds that HTTP client
 * while it is built itself. The clients of the 8.9 line call a method which came with
 * httpclient5 5.6. Spring Boot 4.0 manages httpclient5 5.5, and an imported BOM beats
 * every version a dependency brings. So an application on Spring Boot 4.0 and the 8.9
 * line gets a <code>NoSuchMethodError</code> when the adapter builds its client. Nothing
 * in that error says which dependency to pin. Measured in October 2026, see the section
 * "Apache HttpClient 5" of the README.
 * <p>
 * The adapter builds its client at startup, so the failure is a startup failure already.
 * What this class adds is the sentence. It does not compare version numbers. The clients
 * of the 8.8 and 8.10 lines ask for httpclient5 5.6 as well, but they run on 5.5. A check
 * of the numbers would stop applications which work. So only a real failure of the build
 * is turned into a message, see decision 84 in the repository's DECISIONS.md.
 */
public final class Camunda8HttpClientRuntime {

  /**
   * Apache HttpClient 5 and its core are recognised by their package, not by a type.
   * Naming a type would mean declaring the artifact as a dependency of this module. Then
   * the published POM would carry our version of it, which decision 39 in the
   * repository's DECISIONS.md rules out.
   */
  private static final String HTTP_COMPONENTS = "org.apache.hc.";

  /** The same package as a class loader writes it in a <code>NoClassDefFoundError</code>. */
  private static final String HTTP_COMPONENTS_AS_PATH = "org/apache/hc/";

  private Camunda8HttpClientRuntime() {
    // static helper
  }

  /**
   * Builds the client, and turns a failure about Apache HttpClient 5 into a message which
   * names the fix.
   *
   * @param <T> What the build returns
   * @param build Builds the Camunda client
   * @return What the build returned
   * @throws IllegalStateException Where the build failed because a class or method of
   *           Apache HttpClient 5 is missing. The message names the versions on the
   *           classpath and the entries the application has to add
   */
  public static <T> T buildOrNameTheMissingPin(
      final Supplier<T> build) {

    try {
      return build.get();
    } catch (LinkageError e) {
      final var message = whatTheHttpClientSaysAbout(e);
      if (message == null) {
        throw e;
      }
      final var failure = new IllegalStateException(message);
      // not the cause: Spring Boot looks for a NoSuchMethodError in the causes and then
      // prints its own report instead of this message. As a suppressed exception the
      // stack trace is still in the log
      failure.addSuppressed(e);
      throw failure;
    }

  }

  /**
   * Turns a linkage error into a sentence an application can act on.
   *
   * @param error What the build of the client threw
   * @return The message, or {@code null} where the error is not about Apache HttpClient 5
   */
  static String whatTheHttpClientSaysAbout(
      final LinkageError error) {

    final var missing = error.getMessage();
    if (missing == null || !(missing.contains(HTTP_COMPONENTS) || missing.contains(HTTP_COMPONENTS_AS_PATH))) {
      return null;
    }
    return """
        The Camunda client of release line %s needs a newer Apache HttpClient 5 than this \
        application has, so the adapter cannot build it. The client calls something which is \
        missing: %s. This application runs httpclient5 %s and httpcore5 %s.
        The versions are the application's to choose: the platform BOM it imports manages \
        them, and an imported BOM beats every version a dependency brings. Spring Boot 4.0 \
        manages httpclient5 5.5, for example. Add the versions the client asks for to the \
        dependencyManagement of the application, BEFORE the platform BOM is imported:
          <dependency>
            <groupId>org.apache.httpcomponents.client5</groupId>
            <artifactId>httpclient5</artifactId>
            <version>the version the client asks for</version>
          </dependency>
          <dependency>
            <groupId>org.apache.httpcomponents.core5</groupId>
            <artifactId>httpcore5</artifactId>
            <version>the version the client asks for</version>
          </dependency>
          <dependency>
            <groupId>org.apache.httpcomponents.core5</groupId>
            <artifactId>httpcore5-h2</artifactId>
            <version>the same as httpcore5</version>
          </dependency>
        An application with spring-boot-starter-parent as its parent sets the properties \
        'httpclient5.version' and 'httpcore5.version' instead. This command shows the versions \
        the client asks for, as "version managed from":
          mvn dependency:tree -Dverbose -Dincludes=org.apache.httpcomponents.client5,org.apache.httpcomponents.core5
        See the section "Apache HttpClient 5" of the adapter's README.\
        """
        .formatted(
            Camunda8ReleaseLine.id(),
            missing,
            versionOnTheClasspath("client5"),
            versionOnTheClasspath("core5"));

  }

  /**
   * The release of an Apache HttpComponents module on the classpath, or
   * <code>unknown</code> where it cannot be read. Each module ships a
   * <code>version.properties</code> next to its classes. Reading that file needs no type
   * of the module.
   */
  static String versionOnTheClasspath(
      final String module) {

    final var resource = "/org/apache/hc/%s/version.properties".formatted(module);
    try (var in = Camunda8HttpClientRuntime.class.getResourceAsStream(resource)) {
      if (in == null) {
        return "unknown";
      }
      final var properties = new Properties();
      properties.load(in);
      return properties.getProperty("info.release", "unknown");
    } catch (IOException e) {
      return "unknown";
    }

  }

}
