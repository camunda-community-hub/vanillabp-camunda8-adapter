package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.command.ProblemException;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the start does with a cluster which is not there yet: it waits, it says so while it
 * waits, and it stops waiting for the three reasons which are worth stopping for.
 * <p>
 * The waits of these tests are short, otherwise the run would take as long as the default
 * does.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ClusterWaitTest {

  private static final Duration TIME_PER_ATTEMPT = Duration.ofMillis(20);

  private static RuntimeException aTimedOutSocket() {

    return new CompletionException(
        new IllegalStateException("gateway", new SocketTimeoutException("Read timed out")));

  }

  @Test
  @DisplayName("A cluster which answers on the second attempt lets the start continue, having waited")
  public void aClusterAnsweringLateIsWaitedFor(
      final CapturedOutput output) {

    final var attempts = new AtomicInteger();

    Camunda8ClusterWait
        .untilTheClusterAnswers(
            "c8",
            "http://localhost:8080",
            Duration.ofSeconds(10),
            TIME_PER_ATTEMPT,
            () -> {
              if (attempts.incrementAndGet() < 2) {
                throw aTimedOutSocket();
              }
            });

    assertEquals(2, attempts.get(), "the first attempt found nothing, the second did");
    final var logged = output.getOut() + output.getErr();
    assertTrue(
        logged.contains("waiting for cluster http://localhost:8080 for PT10S"),
        "the line before the wait names the address and the deadline: "
            + logged);
    assertTrue(
        logged.contains("still waiting for cluster"),
        "and while it waits it says where it stands: "
            + logged);
    assertTrue(
        logged.contains("Read timed out"),
        "naming the cluster's last answer, which is what makes a wrong address obvious: "
            + logged);
    assertTrue(
        logged.contains("answered after"),
        "and a start which waited says when the waiting ended: "
            + logged);

  }

  @Test
  @DisplayName("A cluster which never answers ends the start, naming address, time waited and last answer")
  public void aClusterNeverAnsweringEndsTheStart() {

    final var wait = Duration.ofMillis(60);

    final var e = assertThrows(
        IllegalStateException.class,
        () -> Camunda8ClusterWait
            .untilTheClusterAnswers(
                "c8",
                "http://localhost:8080",
                wait,
                TIME_PER_ATTEMPT,
                () -> {
                  throw aTimedOutSocket();
                }));

    final var message = e.getMessage();
    assertTrue(message.contains("http://localhost:8080"), message);
    assertTrue(message.contains("vanillabp.adapters.c8.startup-wait"), message);
    assertTrue(message.contains("waited PT"), "names how long it waited: "
        + message);
    assertTrue(message.contains("Read timed out"), "and the cluster's last answer: "
        + message);
    assertTrue(message.contains("PT0S"), "and the way to switch the waiting off: "
        + message);

  }

  @Test
  @DisplayName("An answer a repetition cannot change ends the start at once, without using the wait up")
  public void aPermanentAnswerEndsTheStartAtOnce() {

    final var attempts = new AtomicInteger();
    final var startedAt = System.nanoTime();

    final var e = assertThrows(
        IllegalStateException.class,
        () -> Camunda8ClusterWait
            .untilTheClusterAnswers(
                "c8",
                "http://localhost:8080",
                Duration.ofMinutes(10),
                TIME_PER_ATTEMPT,
                () -> {
                  attempts.incrementAndGet();
                  throw new ProblemException(403, "Forbidden", null);
                }));

    assertEquals(1, attempts.get(), "a refusal is not asked a second time");
    assertTrue(
        Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofSeconds(5)) < 0,
        "and the ten-minute wait is not sat out for it");
    final var message = e.getMessage();
    assertTrue(message.contains("REFUSED"), message);
    assertTrue(message.contains("http://localhost:8080"), message);
    assertTrue(message.contains("credentials"), "and it says where to look: "
        + message);

  }

  @Test
  @DisplayName("PT0S waits for nothing, so the start behaves as it did before there was a wait")
  public void zeroSwitchesTheWaitingOff(
      final CapturedOutput output) {

    final var attempts = new AtomicInteger();

    // an adapter id of its own, because the captured output of a class carries what the
    // tests before this one logged as well
    Camunda8ClusterWait
        .untilTheClusterAnswers(
            "c8-without-a-wait",
            "http://localhost:8080",
            Duration.ZERO,
            TIME_PER_ATTEMPT,
            attempts::incrementAndGet);

    assertEquals(0, attempts.get(), "not even the one request the normal case costs");
    assertFalse(
        (output.getOut() + output.getErr()).contains("c8-without-a-wait"),
        "and nothing is said about a wait which does not happen");

  }

  @Test
  @DisplayName("Nothing configured means ten minutes, and the value is read from the adapter alone")
  public void theDefaultIsTenMinutes() {

    assertEquals(
        Duration.ofMinutes(10),
        new Camunda8AdapterConfiguration().resolvedStartupWait(),
        "long enough for a cluster which boots together with the application");
    assertEquals(
        Duration.ofMinutes(10),
        Camunda8AdapterConfiguration.DEFAULT_STARTUP_WAIT,
        "and the constant says the same as the ISO notation the messages use");

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setStartupWait(Duration.ofSeconds(30));
    assertEquals(Duration.ofSeconds(30), configuration.resolvedStartupWait(), "a configured value wins");

  }

  @Test
  @DisplayName("A negative wait fails the boot naming the property and the way out")
  public void aNegativeWaitFailsTheBoot() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setStartupWait(Duration.ofSeconds(-1));

    final var e = assertThrows(
        IllegalStateException.class,
        () -> configuration.validateStartupWait("c8"));

    final var message = e.getMessage();
    assertTrue(message.contains("vanillabp.adapters.c8.startup-wait"), message);
    assertTrue(message.contains("PT10M"), "names the default: "
        + message);
    assertTrue(message.contains("PT0S"), "and the way to switch the waiting off: "
        + message);

  }

  @Test
  @DisplayName("The address of a message is the one the operator configured")
  public void theAddressIsTheOneAnOperatorWroteDown() {

    final var selfManaged = new Camunda8AdapterConfiguration();
    selfManaged.setRestAddress("http://localhost:8080");
    assertEquals("http://localhost:8080", selfManaged.describeAddress());

    final var grpcOnly = new Camunda8AdapterConfiguration();
    grpcOnly.setPreferRestOverGrpc(false);
    grpcOnly.setGrpcAddress("http://localhost:26500");
    assertEquals(
        "http://localhost:26500",
        grpcOnly.describeAddress(),
        "an adapter preferring gRPC is addressed by its gateway");

    final var nothingWrittenDown = new Camunda8AdapterConfiguration();
    assertEquals(
        Camunda8AdapterConfiguration.LOCAL_CLUSTER_REST_ADDRESS,
        nothingWrittenDown.describeAddress(),
        "an adapter without an address is described by the address of the local cluster");

    final var grpcWithoutAddress = new Camunda8AdapterConfiguration();
    grpcWithoutAddress.setPreferRestOverGrpc(false);
    assertEquals(
        Camunda8AdapterConfiguration.LOCAL_CLUSTER_GRPC_ADDRESS,
        grpcWithoutAddress.describeAddress(),
        "and one preferring gRPC by the gateway of the local cluster");

    final var saas = new Camunda8AdapterConfiguration();
    saas.setMode(Camunda8AdapterConfiguration.Mode.SAAS);
    saas.setClusterId("abc");
    saas.setRegion("bru-2");
    assertEquals("cluster 'abc' in region 'bru-2'", saas.describeAddress());

  }

}
