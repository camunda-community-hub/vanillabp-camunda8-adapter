package io.vanillabp.camunda8.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.testcontainers.containers.output.OutputFrame;

/**
 * What the Camunda 8 containers of a test module printed, written into a file below
 * {@code target}.
 * <p>
 * Without it, a red build leaves nothing of the cluster it ran against. The container is
 * removed with the run, and the runner's disk with it. Afterwards a test which timed out
 * waiting for a notification looks the same as a cluster which never handed the job out.
 * When a build fails, it uploads this file next to the test reports. Its name contains
 * {@code application}, because the upload collects files with names of that shape.
 * <p>
 * All containers of one module append to the SAME file. Every line names the container
 * which wrote it and the time it did. The time links a line to the test which was running,
 * and nothing else can. A container from {@code ClusterUnderTest.sharedCluster()} serves
 * every class of its module. So its name says which cluster wrote the line, never which test
 * was waiting for it. The test report of the same run uses the clock of the same machine.
 * <p>
 * The path is relative, so every module writes its own file. A build starts each module in
 * its own directory.
 */
public final class ClusterLog {

  /**
   * Where the containers of the module which uses this write. The path is relative to the
   * module, so a run from the IDE and a run from Maven produce the same file.
   */
  public static final Path FILE = Path
      .of("target", "c8-cluster-application.log")
      .toAbsolutePath();

  /**
   * Gives the containers numbers to tell them apart. Several classes run one after the other
   * in the same fork. Each of them starts a broker with the same image and the same
   * purpose.
   */
  private static final AtomicInteger CONTAINERS = new AtomicInteger();

  private static Writer writer;

  private ClusterLog() {
    // static helper
  }

  /**
   * Opens a log consumer for one container and gives it a number of its own, so two containers
   * of the same kind stay apart in the file.
   *
   * @param kind What the container is, e.g. {@code broker}
   * @return A log consumer for one container, to be handed to
   *         {@code withLogConsumer(...)}
   */
  public static Consumer<OutputFrame> of(
      final String kind) {

    final var container = "%s-%d".formatted(kind, CONTAINERS.incrementAndGet());
    return frame -> append(container, frame);

  }

  private static synchronized void append(
      final String container,
      final OutputFrame frame) {

    final var line = frame.getUtf8StringWithoutLineEnding();
    if (line.isEmpty()) {
      return;
    }
    try {
      if (writer == null) {
        Files.createDirectories(FILE.getParent());
        // truncating: the file belongs to this run. A build which reads it wants the lines
        // of the run which just failed, not of every run since the last 'clean'
        writer = Files
            .newBufferedWriter(
                FILE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
      }
      // the runner's own clock, down to milliseconds. A reader compares a line with the
      // build log and the test report, and this clock writes both
      writer.write("%s %s %s%n".formatted(LocalTime.now().truncatedTo(ChronoUnit.MILLIS), container, line));
      // flushed per line: nothing closes this writer, and the interesting lines are the
      // last ones before a run ends
      writer.flush();
    } catch (final IOException e) {
      throw new UncheckedIOException("Cannot write '%s'".formatted(FILE), e);
    }

  }

}
