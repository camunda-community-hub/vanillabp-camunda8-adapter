package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A client build which fails on an old Apache HttpClient 5 ends with a sentence which names
 * the dependency to pin.
 * <p>
 * The test hands the check the error the client really throws. The real thing needs an
 * application on Spring Boot 4.0 and the 8.9 line, and this build runs a newer Spring Boot.
 * That case was measured by hand with a sample application, see the section "Apache
 * HttpClient 5" of the README.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8HttpClientRuntimeTest {

  /** What the clients of the 8.9 line throw on httpclient5 5.5, copied from the log. */
  private static final String MISSING_METHOD = "'org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder "
      + "org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder.disableContentCompression()'";

  @Test
  @DisplayName("a build which works returns what it built")
  public void aBuildWhichWorksReturnsWhatItBuilt() {

    assertEquals("client", Camunda8HttpClientRuntime.buildOrNameTheMissingPin(() -> "client"));

  }

  @Test
  @DisplayName("a missing method of the HTTP client becomes a sentence naming the fix")
  public void aMissingMethodBecomesASentenceNamingTheFix() {

    final var missingMethod = new NoSuchMethodError(MISSING_METHOD);

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> Camunda8HttpClientRuntime.buildOrNameTheMissingPin(() -> {
          throw missingMethod;
        }));

    final var message = failure.getMessage();
    assertTrue(
        message.contains("disableContentCompression()"),
        "the message names what the client misses: "
            + message);
    assertTrue(
        message.contains("<artifactId>httpclient5</artifactId>") && message
            .contains("<artifactId>httpcore5</artifactId>"),
        "the message names the artifacts to pin: "
            + message);
    assertTrue(
        message.contains("dependencyManagement"),
        "the message names where to pin them: "
            + message);
    assertTrue(
        message.contains("httpclient5 "
            + Camunda8HttpClientRuntime.versionOnTheClasspath("client5")),
        "the message names the version this application runs: "
            + message);
    assertNull(
        failure.getCause(),
        "Spring Boot would print its own report for a NoSuchMethodError among the causes");
    assertSame(
        missingMethod,
        failure.getSuppressed()[0],
        "the original error stays in the log");

  }

  @Test
  @DisplayName("a missing class of the HTTP client is recognised as well")
  public void aMissingClassIsRecognisedAsWell() {

    assertTrue(
        Camunda8HttpClientRuntime
            .whatTheHttpClientSaysAbout(
                new NoClassDefFoundError("org/apache/hc/core5/http2/priority/PriorityValue"))
            .contains("httpclient5"),
        "a class loader writes the package with slashes");

  }

  @Test
  @DisplayName("any other linkage error is left as it is")
  public void anyOtherLinkageErrorIsLeftAsItIs() {

    final var other = new NoSuchMethodError("'void io.grpc.ManagedChannelBuilder.somethingNew()'");

    final var thrown = assertThrows(
        NoSuchMethodError.class,
        () -> Camunda8HttpClientRuntime.buildOrNameTheMissingPin(() -> {
          throw other;
        }));

    assertSame(other, thrown, "an error about another library says nothing about the HTTP client");

  }

  @Test
  @DisplayName("the versions on the classpath are readable")
  public void theVersionsOnTheClasspathAreReadable() {

    assertNotEquals("unknown", Camunda8HttpClientRuntime.versionOnTheClasspath("client5"));
    assertNotEquals("unknown", Camunda8HttpClientRuntime.versionOnTheClasspath("core5"));
    assertEquals("unknown", Camunda8HttpClientRuntime.versionOnTheClasspath("no-such-module"));

  }

}
