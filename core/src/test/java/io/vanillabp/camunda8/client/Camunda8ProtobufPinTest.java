package io.vanillabp.camunda8.client;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.w3c.dom.Element;

import io.vanillabp.camunda8.Camunda8ReleaseLine;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The protobuf runtime this build puts on the classpath is at least the gencode version the
 * Camunda client of this release line was generated against.
 * <p>
 * The client ships generated protocol classes, and protobuf refuses a runtime older than the
 * gencode linked against it. That refusal arrives as an
 * <code>ExceptionInInitializerError</code> out of a static initializer, on the first command
 * which touches the protocol, in whatever test happens to send it first. So the number is
 * pinned in the parent POM, and this test is what compares the pin against what the client
 * really asks for, in one sentence naming both numbers and the line to change.
 * <p>
 * What the client asks for is READ rather than remembered: the Camunda client's own POM is
 * copied next to this test by the build, and the version it declares for
 * <code>protobuf-java</code> is the gencode version. What ends up on the classpath is read
 * from the runtime itself, by reflection, because the compiler would otherwise inline the
 * constants of the jar this test was compiled against and the check would compare a number
 * with itself.
 * <p>
 * Only the ACTIVE line is checked, which is the line whose client is on the classpath. A pull
 * request moving any client pin builds every line, so each line's client meets this test.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ProtobufPinTest {

  /** Where the build put the POM of the client this line was compiled against. */
  private static final Path CLIENT_POM = Path.of("target", "camunda-client", "camunda-client-java.pom");

  @Test
  @DisplayName("the pinned protobuf runtime is not older than the client's gencode")
  public void theRuntimeIsNotOlderThanTheClientsGencode() throws Exception {

    final var gencode = protobufTheClientWasGeneratedAgainst();
    final var runtime = protobufOnTheClasspath();

    if ((gencode.major() == runtime.major()) && (gencode.compareTo(runtime) <= 0)) {
      return;
    }
    throw new AssertionError(
        ("The Camunda client %s of release line %s brings protobuf gencode %s, but this build puts "
            + "protobuf runtime %s on the classpath. Protobuf refuses a runtime which is older than "
            + "its gencode, or of another major version, and it does so as an "
            + "ExceptionInInitializerError on the first command which uses the protocol. Set "
            + "<protobuf.version> in the parent pom.xml to %s (the value has to be at least the "
            + "gencode version of every client pinned there, and a newer runtime serves an older "
            + "gencode).")
            .formatted(
                Camunda8ReleaseLine.clientVersion(),
                Camunda8ReleaseLine.id(),
                gencode,
                runtime,
                gencode));

  }

  /**
   * The protobuf version the client of this line declares, read from the client's own POM.
   */
  private Version protobufTheClientWasGeneratedAgainst() throws Exception {

    if (!Files.isRegularFile(CLIENT_POM)) {
      throw new AssertionError(
          "The Camunda client's POM is missing at '%s'. It is copied there by the "
              + "maven-dependency-plugin execution 'the-clients-own-pom' of this module, so this "
              + "test cannot run from an IDE which skipped it.".formatted(CLIENT_POM));
    }
    final var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    final var document = factory
        .newDocumentBuilder()
        .parse(CLIENT_POM.toFile());
    final var dependencies = document.getElementsByTagName("dependency");
    for (var i = 0; i < dependencies.getLength(); i++) {
      final var dependency = (Element) dependencies.item(i);
      if ("com.google.protobuf".equals(textOf(dependency, "groupId")) && "protobuf-java"
          .equals(textOf(dependency, "artifactId"))) {
        return Version.of(textOf(dependency, "version"));
      }
    }
    throw new AssertionError(
        "The POM of Camunda client %s declares no protobuf-java dependency. Either the client "
            .formatted(Camunda8ReleaseLine.clientVersion())
            + "stopped shipping generated protocol code, and then <protobuf.version> in the parent "
            + "pom.xml is dead configuration, or this test reads the wrong file.");

  }

  private static String textOf(
      final Element dependency,
      final String tagName) {

    final var elements = dependency.getElementsByTagName(tagName);
    return elements.getLength() == 0
        ? null
        : elements
            .item(0)
            .getTextContent()
            .trim();

  }

  /**
   * The protobuf runtime which really ends up on the classpath, read by reflection: the
   * compiler inlines a {@code static final int} it can see, so a direct reference would
   * report the version this test was compiled against rather than the one it runs with.
   */
  private Version protobufOnTheClasspath() throws Exception {

    final var runtimeVersion = Class.forName("com.google.protobuf.RuntimeVersion");
    return new Version(
        runtimeVersion.getField("MAJOR").getInt(null), runtimeVersion.getField("MINOR").getInt(null), runtimeVersion
            .getField("PATCH").getInt(null));

  }

  /**
   * A protobuf version, comparable the way protobuf compares gencode against runtime.
   *
   * @param major The major version
   * @param minor The minor version
   * @param patch The patch version
   */
  private record Version(
                         int major,
                         int minor,
                         int patch) implements Comparable<Version> {

    static Version of(
        final String version) {

      final var withoutQualifier = version.split("-", 2)[0];
      final var parts = withoutQualifier.split("\\.");
      return new Version(
          Integer.parseInt(parts[0]), parts.length > 1
              ? Integer.parseInt(parts[1])
              : 0, parts.length > 2
                  ? Integer.parseInt(parts[2])
                  : 0);

    }

    @Override
    public int compareTo(
        final Version other) {

      if (major != other.major) {
        return Integer.compare(major, other.major);
      }
      if (minor != other.minor) {
        return Integer.compare(minor, other.minor);
      }
      return Integer.compare(patch, other.patch);

    }

    @Override
    public String toString() {

      return "%d.%d.%d".formatted(major, minor, patch);

    }

  }

}
