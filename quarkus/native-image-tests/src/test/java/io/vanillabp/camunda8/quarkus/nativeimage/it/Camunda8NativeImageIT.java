package io.vanillabp.camunda8.quarkus.nativeimage.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;

import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Runs the native binary of this module against a real Camunda 8 cluster.
 * <p>
 * The image was built by the Quarkus plugin in the <code>package</code> phase, which is
 * why this is an integration test: at <code>test</code> time there is no binary yet. And
 * a binary which was built is not a binary which runs - the whole reason this module
 * exists is a deployment which parses BPMN, which the image could not do until the
 * adapter's extension registered what the parser reads. So the test starts the binary,
 * gives it a cluster and reads its exit code; the application's own main decides what
 * that code is (see {@code NativeImageApplication}).
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8NativeImageIT {

  /**
   * How long the binary may take to boot, deploy, run its workflow and exit. Its own
   * main waits two minutes for the job, so this is that plus room for the boot.
   */
  private static final Duration UNTIL_EXITED = Duration.ofMinutes(4);

  private static final GenericContainer<?> CAMUNDA = ClusterUnderTest.cluster();

  @Test
  @DisplayName("The native binary boots, deploys its workflow module and runs a workflow")
  public void theNativeBinaryRunsAWorkflow() throws Exception {

    final var binary = Path.of(System.getProperty("native.image.runner"));
    assertTrue(
        Files.isExecutable(binary),
        () -> """
            '%s' is not an executable binary. This test runs what the native build \
            produced, so it needs the profile which builds it: \
            'mvn -Dnative -pl quarkus/native-image-tests verify'."""
            .formatted(binary));

    CAMUNDA.start();
    try {
      final var output = runBinary(binary);
      assertEquals(
          0,
          output.exitCode(),
          () -> "The native binary exited with %d. Its output was:%n%s"
              .formatted(output.exitCode(), output.text()));
      assertTrue(
          output
              .text()
              .contains("status 'served'"),
          () -> "The native binary exited successfully without saying it ran the workflow:%n%s"
              .formatted(output.text()));
    } finally {
      CAMUNDA.stop();
    }

  }

  /**
   * What the binary printed and what it returned. Its output is the only report a
   * failing run leaves behind, so it travels with the exit code rather than into the
   * void.
   *
   * @param exitCode The exit code of the binary
   * @param text Everything it wrote to stdout and stderr
   */
  private record BinaryOutput(int exitCode, String text) {
  }

  private BinaryOutput runBinary(
      final Path binary) throws IOException, InterruptedException {

    final var log = binary
        .resolveSibling("native-image-application.log");
    final var process = new ProcessBuilder(binary.toString())
        // only the test knows the port Testcontainers mapped
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .directory(binary
            .getParent()
            .toFile());
    process
        .environment()
        .put(
            "CAMUNDA_REST_ADDRESS",
            "http://%s:%d".formatted(CAMUNDA.getHost(), CAMUNDA.getMappedPort(8080)));

    final var running = process.start();
    if (!running.waitFor(UNTIL_EXITED.toSeconds(), TimeUnit.SECONDS)) {
      running.destroyForcibly();
      return new BinaryOutput(
          -1, "The binary did not exit within %s. What it logged until then:%n%s"
              .formatted(UNTIL_EXITED, Files.readString(log)));
    }
    return new BinaryOutput(running.exitValue(), Files.readString(log));

  }

}
