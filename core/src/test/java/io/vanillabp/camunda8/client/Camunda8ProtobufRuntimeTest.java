package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.google.protobuf.RuntimeVersion.ProtobufRuntimeVersionException;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The startup says what protobuf would say at the first command, and it says what to do
 * about it.
 * <p>
 * The refusal is provoked rather than waited for: protobuf's own exception is handed to the
 * check, which is the exception the generated code raises out of its static initializer.
 * Producing the real thing would mean running a test against a protobuf runtime older than
 * the client of the line, and the build pins one which is new enough on purpose.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ProtobufRuntimeTest {

  @Test
  @DisplayName("the generated protocol code loads on the classpath this line is built with")
  public void theGeneratedProtocolCodeLoads() {

    assertDoesNotThrow(Camunda8ProtobufRuntime::failIfItIsOlderThanTheClientNeeds);

  }

  @Test
  @DisplayName("a refusal by protobuf becomes a sentence naming the fix")
  public void aRefusalByProtobufBecomesASentenceNamingTheFix() {

    final var message = Camunda8ProtobufRuntime.whatProtobufSaysAbout(() -> {
      throw new ExceptionInInitializerError(
          new ProtobufRuntimeVersionException(
              "Detected incompatible Protobuf Gencode/Runtime versions when loading "
                  + "gateway.proto: gencode 4.36.0, runtime 4.35.1."));
    });

    assertNotNull(message, "protobuf refused, so the adapter has something to say");
    assertTrue(
        message.contains("gencode 4.36.0, runtime 4.35.1"),
        "the numbers protobuf named are in the message: "
            + message);
    assertTrue(
        message.contains("protobuf-java"),
        "the message names the artifact to pin: "
            + message);
    assertTrue(
        message.contains("dependencyManagement"),
        "the message names where to pin it: "
            + message);

  }

  @Test
  @DisplayName("anything else which goes wrong while loading is no answer about protobuf")
  public void anythingElseIsNoAnswerAboutProtobuf() {

    assertNull(
        Camunda8ProtobufRuntime.whatProtobufSaysAbout(() -> {
          throw new NoClassDefFoundError("io/camunda/zeebe/gateway/protocol/GatewayOuterClass");
        }),
        "a client without generated protocol code must not keep an application from booting");

  }

}
