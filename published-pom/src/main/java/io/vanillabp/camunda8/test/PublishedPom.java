package io.vanillabp.camunda8.test;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;

/**
 * What the POM of a module promises the application which resolves it, read back out of the
 * file the build wrote.
 * <p>
 * A Camunda 8 adapter is published once per release line, and the POM is what puts a Camunda
 * client on an application's classpath. So the POM is where a line keeps or breaks its
 * promise, and in September 2026 it broke it: every line asked for the client of the current
 * GA line, because the published POM was a copy of the source POM and the client version
 * stood in a property only a profile of ours sets. The flatten plugin's <code>oss</code> mode
 * ended that, and an assertion like this one is what keeps it ended.
 * <p>
 * The promise is the same in every repository which builds a Camunda 8 adapter, and so is the
 * way to check it, which is why this class sits here instead of in a test class each
 * repository copies. A copy drifts, and the whole value of this check is that it still runs
 * in two years. What differs is the artifact being checked and the versions expected of it,
 * and that is what the parameters are for. Each repository keeps its own test methods with
 * its own names and calls these assertions from them.
 * <p>
 * A build whose version does not carry the release line should say which run a failure came
 * from, see {@link #inTheRun(String)}.
 * <p>
 * The POM is read from disk rather than derived, because deriving it would repeat the
 * reasoning the mistake was made in.
 * <p>
 * This module is published per release line, so this class travels with the line an
 * application compiles against. A change here reaches another repository with the next
 * snapshot of this line and not before.
 * <p>
 * It is a module of its own, and not part of 'test-support', because that one starts
 * containers and would put Testcontainers on the test classpath of a module which only wants
 * to read a file. The two are never wanted together: a module asks either for a cluster or
 * for this.
 */
public final class PublishedPom {

  /**
   * The POM which is installed and deployed for a module, written by the flatten plugin into
   * the module's own directory, which is the working directory of its tests.
   */
  private static final Path FILE = Path.of(".flattened-pom.xml");

  private final Element project;

  private String run;

  private PublishedPom(
      final Element project) {

    this.project = project;

  }

  /**
   * Names the run these assertions belong to, and every failure message repeats it.
   * <p>
   * A message names the coordinate of the artifact, and in a build whose version carries the
   * release line that coordinate already says which line was meant. Where the version is
   * plain, as it is in a pull request build, it says nothing, and a reader of a red build has
   * to guess which of several lines produced it. This module cannot fill the gap itself: it
   * knows the line it was built for, not the line the caller is testing.
   *
   * @param run Whatever identifies this run to somebody reading its failure, e.g. the release
   *          line and the client version the build selected
   * @return This, so a test can go on asking
   */
  public PublishedPom inTheRun(
      final String run) {

    this.run = run;
    return this;

  }

  /**
   * Reads the POM published for the module whose test is running.
   *
   * @return The published POM, ready to be asked what it promises
   * @throws AssertionError If the build has not written it. A test which passes because the
   *           file it reads is absent is worse than no test at all.
   */
  public static PublishedPom ofTheModuleUnderTest() {

    if (!Files.isRegularFile(FILE)) {
      throw new AssertionError(
          ("The published POM is missing at '%s'. The flatten plugin writes it in the phase "
              + "'process-resources', so this test cannot run from an IDE or a command which "
              + "skipped that phase, and it says nothing about the POM which would be published.")
              .formatted(FILE.toAbsolutePath()));
    }
    try {
      final var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      return new PublishedPom(
          factory
              .newDocumentBuilder()
              .parse(FILE.toFile())
              .getDocumentElement());
    } catch (Exception e) {
      throw new AssertionError(
          "The published POM at '%s' cannot be read.".formatted(FILE.toAbsolutePath()), e);
    }

  }

  /**
   * Asserts that the published POM asks for exactly the given version of one dependency,
   * which for the Camunda client is the client the build was compiled against.
   *
   * @param groupId The group of the dependency, e.g. <code>io.camunda</code>
   * @param artifactId The dependency, e.g. <code>camunda-client-java</code>
   * @param expected The version the build was compiled against
   * @return This, so a test can ask for more than one promise
   * @throws AssertionError If the POM names another version, or none, or does not declare the
   *           dependency at all
   */
  public PublishedPom asksFor(
      final String groupId,
      final String artifactId,
      final String expected) {

    final var declared = versionOf(groupId, artifactId);
    if (expected.equals(declared)) {
      return this;
    }
    throw new AssertionError(
        ("The POM published for %s asks for %s:%s %s, but this build was compiled against %s. An "
            + "application takes that dependency from this POM, so it would run code of one "
            + "version against another one. A version reaches the published POM only when the "
            + "flatten plugin resolves it: check that <flattenMode> in the parent pom.xml is still "
            + "'oss'.")
            .formatted(
                coordinate(),
                groupId,
                artifactId,
                declared == null
                    ? "no version at all"
                    : declared,
                expected));

  }

  /**
   * Asserts that the published POM hands an application nothing of ours to inherit: no parent
   * and no <code>dependencyManagement</code>.
   * <p>
   * Both are read when an application resolves its dependencies, and both would hand it
   * versions this repository picked for its own build, which is the build of the newest line.
   * What one line needs is no business of another line's users.
   *
   * @return This, so a test can ask for more than one promise
   * @throws AssertionError If either is back
   */
  public PublishedPom handsAnApplicationNothingToInherit() {

    final var parent = project.getElementsByTagName("parent").getLength() > 0;
    final var dependencyManagement = project
        .getElementsByTagName("dependencyManagement")
        .getLength() > 0;
    if (!parent && !dependencyManagement) {
      return this;
    }
    throw new AssertionError(
        ("The POM published for %s carries %s. An application reads it while resolving its "
            + "dependencies and would take versions from it which this repository picked for its "
            + "own build. The flatten plugin drops both in its 'oss' mode: check <flattenMode> in "
            + "the parent pom.xml.")
            .formatted(coordinate(), parent && dependencyManagement
                ? "a parent and a dependencyManagement"
                : parent
                    ? "a parent"
                    : "a dependencyManagement"));

  }

  /**
   * Asserts that the published POM names the repository it was built from, with nothing
   * appended to the addresses.
   * <p>
   * Maven hands a child the parent's <code>url</code> and all three <code>scm</code> elements
   * with the child's own name appended, so an artifact ends up advertising an address like
   * <code>https://github.com/someone/some-adapter/some-adapter</code>, which is no page. The
   * four <code>inherit.append.path</code> attributes in the parent POM switch that off. Every
   * artifact then names the repository root, which is one address for all of them and
   * survives a module being renamed or moved.
   *
   * @param repository The repository the artifacts are built from, without a trailing slash,
   *          e.g.
   *          <code>https://github.com/camunda-community-hub/vanillabp-camunda8-adapter</code>.
   *          The scm entries are derived from it the way both Camunda 8 adapter repositories
   *          write them:
   *          <code>scm:git:&lt;repository&gt;.git</code> and
   *          <code>&lt;repository&gt;/tree/main</code>.
   * @return This, so a test can ask for more than one promise
   * @throws AssertionError If any of the four addresses is another one
   */
  public PublishedPom pointsAt(
      final String repository) {

    final var wrong = new StringBuilder();
    appendWhereWrong(wrong, "url", textOfChild(project, "url"), repository);
    final var scm = child(project, "scm");
    appendWhereWrong(
        wrong,
        "scm/connection",
        textOfChild(scm, "connection"),
        "scm:git:"
            + repository
            + ".git");
    appendWhereWrong(
        wrong,
        "scm/developerConnection",
        textOfChild(scm, "developerConnection"),
        "scm:git:"
            + repository
            + ".git");
    appendWhereWrong(wrong, "scm/url", textOfChild(scm, "url"), repository
        + "/tree/main");
    if (wrong.isEmpty()) {
      return this;
    }
    throw new AssertionError(
        ("The POM published for %s names addresses we did not write:%s%nMaven appends the "
            + "artifact's name to the url and to all three scm elements of a child unless the four "
            + "'inherit.append.path' attributes in the parent pom.xml say otherwise, and an address "
            + "with an artifact name in it opens nothing. Every artifact names the repository "
            + "root.")
            .formatted(coordinate(), wrong));

  }

  /**
   * Asserts that the published POM says nothing about where we deploy.
   * <p>
   * A <code>distributionManagement</code> names the registry the maintainers publish to,
   * which a user of the artifact can neither use nor act on, and on an artifact which later
   * sits on Maven Central it points a reader somewhere else entirely. The source POM keeps
   * it, because that is where the deploy reads it, and the flatten plugin takes it out of
   * what is published.
   *
   * @return This, so a test can ask for more than one promise
   * @throws AssertionError If it is in the published POM
   */
  public PublishedPom saysNothingAboutWhereWeDeploy() {

    if (project.getElementsByTagName("distributionManagement").getLength() == 0) {
      return this;
    }
    throw new AssertionError(
        ("The POM published for %s carries a distributionManagement, which names where WE deploy "
            + "and helps nobody who resolves this artifact. Remove it from the published POM "
            + "rather than from the source, where the deploy needs it: "
            + "<pomElements><distributionManagement>remove</distributionManagement></pomElements> "
            + "in the flatten plugin's configuration.")
            .formatted(coordinate()));

  }

  /**
   * The artifact this POM is published for, named the way a failure message can act on, and
   * with the run appended where the caller named one. See {@link #inTheRun(String)}.
   */
  private String coordinate() {

    final var coordinate = "%s:%s:%s".formatted(
        textOfChild(project, "groupId"),
        textOfChild(project, "artifactId"),
        textOfChild(project, "version"));
    return run == null
        ? coordinate
        : "%s (%s)".formatted(coordinate, run);

  }

  /**
   * The version the published POM declares for one dependency, or {@code null} where it
   * declares the dependency without one.
   */
  private String versionOf(
      final String groupId,
      final String artifactId) {

    final var dependencies = project.getElementsByTagName("dependency");
    for (var i = 0; i < dependencies.getLength(); i++) {
      final var dependency = (Element) dependencies.item(i);
      if (groupId.equals(textOfChild(dependency, "groupId")) && artifactId
          .equals(textOfChild(dependency, "artifactId"))) {
        return textOfChild(dependency, "version");
      }
    }
    throw new AssertionError(
        ("The POM published for %s declares no dependency on %s:%s. Either the module stopped "
            + "using it, and then this check is about something else than it was, or the test "
            + "reads the wrong file.")
            .formatted(coordinate(), groupId, artifactId));

  }

  /**
   * Notes one address which is not the one expected, so a run reports all of them at once
   * rather than the first.
   */
  private static void appendWhereWrong(
      final StringBuilder wrong,
      final String element,
      final String actual,
      final String expected) {

    if (expected.equals(actual)) {
      return;
    }
    wrong
        .append("%n  <%s> is %s, expected '%s'".formatted(element, actual == null
            ? "absent"
            : "'"
                + actual
                + "'",
            expected));

  }

  /**
   * A direct child element of the given one, or {@code null} where it has none of that name.
   * Direct, because a POM repeats names: 'url' stands under the project, under every license
   * and inside 'scm'.
   */
  private static Element child(
      final Element parent,
      final String tagName) {

    if (parent == null) {
      return null;
    }
    final var children = parent.getChildNodes();
    for (var i = 0; i < children.getLength(); i++) {
      if ((children.item(i) instanceof final Element element) && tagName
          .equals(element.getTagName())) {
        return element;
      }
    }
    return null;

  }

  /** The text of a direct child element, or {@code null} where there is none. */
  private static String textOfChild(
      final Element parent,
      final String tagName) {

    final var element = child(parent, tagName);
    return element == null
        ? null
        : element
            .getTextContent()
            .trim();

  }

}
