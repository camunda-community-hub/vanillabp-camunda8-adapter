package io.vanillabp.camunda8;

import java.io.IOException;
import java.util.Properties;

/**
 * The release line this build of the adapter belongs to.
 * <p>
 * The adapter is published once per line, e.g. <code>2.1.0-8.9</code>. Camunda support
 * accepts a client against a cluster of the same minor version and of the next one. A
 * cluster older than the client is not covered. So the line of a build should have the minor
 * version of the cluster. A line therefore brings every VanillaBP fix to users who cannot
 * upgrade their cluster yet. The newest line brings what only the newest cluster can do.
 * <p>
 * The start compares the cluster with the client and says what it thinks of the pair, but it
 * never refuses a cluster. That is decision 90 in the repository's DECISIONS.md.
 * <p>
 * The line reaches the runtime for MESSAGES, not for behaviour. What the adapter does
 * follows what the cluster answers, never what the build believes. That is why nothing here
 * decides anything. A message saying "this cluster cannot do X" is easier to act on when it
 * also names the line the application is running.
 * <p>
 * The Maven build filters the values into the version descriptor of the adapter,
 * <code>META-INF/vanillabp/adapter-camunda8.properties</code>. It takes them from the
 * properties the active line profile selects. Where they cannot be read, the answer is
 * <code>unknown</code>. This must never break an application, not even one with a custom build.
 * <p>
 * Why this adapter is released once per Camunda minor, built from one source tree, is decision 11
 * in the repository's DECISIONS.md.
 */
public final class Camunda8ReleaseLine {

  private static final String DESCRIPTOR = "/META-INF/vanillabp/adapter-camunda8.properties";

  private static final String UNKNOWN = "unknown";

  private static final Properties DESCRIPTOR_PROPERTIES = read();

  private Camunda8ReleaseLine() {
    // static helper
  }

  /**
   * The Camunda 8 minor this build belongs to. A cluster of the same minor version is the
   * one it fits.
   *
   * @return The line, e.g. <code>8.9</code>, or <code>unknown</code> where the descriptor was
   *         not readable
   */
  public static String id() {

    return DESCRIPTOR_PROPERTIES.getProperty("camunda8.line", UNKNOWN);

  }

  /**
   * The exact Camunda client this build was compiled against.
   *
   * @return The client version, e.g. <code>8.9.16</code>, or <code>unknown</code> where the
   *         descriptor was not readable
   */
  public static String clientVersion() {

    return DESCRIPTOR_PROPERTIES.getProperty("camunda8.client", UNKNOWN);

  }

  private static Properties read() {

    final var properties = new Properties();
    try (var descriptor = Camunda8ReleaseLine.class.getResourceAsStream(DESCRIPTOR)) {
      if (descriptor != null) {
        properties.load(descriptor);
      }
    } catch (IOException e) {
      // an unreadable descriptor means unknown, never a failure. The line is used in
      // messages only.
    }
    return properties;

  }

}
