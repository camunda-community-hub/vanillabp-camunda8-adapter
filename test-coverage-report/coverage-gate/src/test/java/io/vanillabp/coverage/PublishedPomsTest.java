package io.vanillabp.coverage;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.PublishedPoms;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the POMs of this repository hand an application: no tool which only compiles our
 * source, and no property name where a version belongs.
 * <p>
 * Lombok and the processors of Spring Boot and Quarkus are read while javac runs. Once the
 * class file exists, they have nothing left to do. An application asked for a workflow
 * engine, not for them. Every jar it did not ask for is one more thing to scan, to ship and to
 * answer a CVE report about. The scope which says that is {@code provided}. It puts the jar on
 * our own compile path and hands it to nobody.
 * <p>
 * The second check is about the versions in those POMs. A version written as
 * {@code ${some.version}} resolves in our own build, where the POM with the value is in the
 * reactor. For a consumer it stops resolving on the day that POM stops defining the property.
 * And a timestamped snapshot cannot keep the parent build it was published with. The consumer
 * reads the newest build of that parent. On 2026-09-27 this turned every Quarkus blueprint of
 * the platform red, while the repository which published the POM stayed green.
 * <p>
 * The knowledge of the checks sits in {@link PublishedPoms} of the platform's module
 * 'test-utils'. Every repository of VanillaBP can make these mistakes, and they all make them
 * in the same way. So the rules live in one place, and each repository calls them. A copy per
 * repository drifts apart, and these checks are only worth something if they still run in two
 * years. This test is the caller. It names the file this repository publishes and the tools of
 * this build.
 * <p>
 * This repository publishes through the flatten plugin. So both checks read the flattened
 * file. An application really resolves that file. In it, a scope which stands only in a
 * dependencyManagement is already written into the declaration. Test dependencies are gone,
 * and the line profile is applied. The source POM shows none of that.
 * <p>
 * That is also why the second check is quiet here, while it had work to do in the other
 * repositories. Decision 39 in this repository's DECISIONS.md publishes a POM with no parent,
 * no properties and no dependencyManagement. The flatten plugin writes a resolved value
 * wherever a module wrote a property name. Only a property which NOBODY defines survives
 * flattening. Maven leaves such a name in the model as plain text. The flattened file carries
 * it, and a consumer gets it as the version. A misspelled name ends that way. So does a name
 * defined in a line profile which the publishing build does not activate. This check finds it
 * before a user does.
 * <p>
 * These checks are gates in the module which already gates this repository as a whole. That
 * module is the last of the reactor. So they read what every other module has published by
 * then.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PublishedPomsTest {

  /**
   * The tools of this build, each as {@code groupId:artifactId}. A tool which this repository
   * starts to use belongs in this list, because nothing else knows that it is a tool. The list
   * names more than the build uses today. So the usual next one is covered as well.
   */
  private static final Set<String> TOOLS_OF_THIS_BUILD = Set
      .of(
          "org.projectlombok:lombok",
          "io.quarkus:quarkus-extension-processor",
          "org.springframework.boot:spring-boot-autoconfigure-processor",
          "org.mapstruct:mapstruct-processor",
          "io.vanillabp:vanillabp-mapstruct-fluent-accessors");

  @Test
  @DisplayName("no POM of this repository hands an application a tool of the build")
  public void noPomHandsAnApplicationAToolOfTheBuild() {

    PublishedPoms
        .ofTheRepositoryUnderTest(PublishedPoms.THE_FLATTENED_POM)
        .handAnApplicationNoToolOfTheBuild(TOOLS_OF_THIS_BUILD);

  }

  @Test
  @DisplayName("no POM of this repository hands an application a property instead of a value")
  public void noPomHandsAnApplicationAPropertyInsteadOfAValue() {

    PublishedPoms
        .ofTheRepositoryUnderTest(PublishedPoms.THE_FLATTENED_POM)
        .handAnApplicationNoPropertyInsteadOfAValue();

  }

}
