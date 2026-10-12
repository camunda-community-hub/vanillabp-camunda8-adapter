package io.vanillabp.camunda8.springboot.election;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A real cluster which refuses to be searched, and what an application configured against it
 * does. It does not come up, and the message says why and what to change.
 * <p>
 * The mocked counterpart pins the message ({@code Camunda8SearchableClusterCheckTest} of the
 * core module). Only a container can prove that a cluster really answers a search with HTTP
 * 403. The refusal is recognised by that code. The code is the whole check, and the cluster
 * owns it. So this is the one cluster in the suites which is still a broker alone.
 * <p>
 * The second cluster of this class is for the migration setup which the way out is meant
 * for. The application's FIRST adapter has a searchable cluster. Its second adapter, the old
 * BPMS, still sits on a cluster which refuses. That one has
 * <code>deployment-failure: warn</code>. So the application boots, and only the old BPMS stays
 * behind, not the whole start.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@Testcontainers(disabledWithoutDocker = true)
public class Camunda8UnsearchableClusterIT {

  @Container
  static final GenericContainer<?> BROKER_ALONE = ClusterUnderTest.clusterWhichRefusesSearches();

  /**
   * The cluster of the adapter which IS first priority in the warn test below. The
   * application has to come up, or that test says nothing. It only comes up if its primary
   * adapter has a cluster which it can search.
   */
  @Container
  static final GenericContainer<?> SEARCHABLE_CLUSTER = ClusterUnderTest.cluster();

  private ConfigurableApplicationContext application;

  private static String restAddress(
      final GenericContainer<?> cluster) {

    return "http://"
        + cluster.getHost()
        + ":"
        + cluster.getMappedPort(8080);

  }

  private static String grpcAddress(
      final GenericContainer<?> cluster) {

    return "http://"
        + cluster.getHost()
        + ":"
        + cluster.getMappedPort(26500);

  }

  @AfterEach
  public void closeWhatIsLeft() {

    if ((application != null) && application.isActive()) {
      application.close();
    }

  }

  @Test
  @DisplayName("ONE adapter id on a cluster which refuses to be searched does not boot")
  public void oneAdapterIdOnAnUnsearchableClusterDoesNotBoot() {

    final var failure = assertThrows(
        Exception.class,
        () -> application = new SpringApplicationBuilder(ElectionTestApplication.class)
            .run(
                "--spring.config.name=camunda8-election-it",
                "--spring.main.web-application-type=none",
                "--vanillabp.adapters.c8-plain.rest-address="
                    + restAddress(BROKER_ALONE),
                "--vanillabp.adapters.c8-plain.grpc-address="
                    + grpcAddress(BROKER_ALONE)));

    final var message = refusalIn(failure);
    assertNotNull(message, "the boot failed with the refusal of the check");
    assertTrue(
        message.contains("'c8-plain'"),
        "which names the adapter whose cluster it is: "
            + message);
    assertTrue(
        message.contains("camunda.data.secondary-storage.type"),
        "and the property which gives the cluster its secondary storage: "
            + message);
    assertTrue(
        message.contains("credentials"),
        "and the second reason, because the cluster answers 403 for either: "
            + message);

  }

  @Test
  @DisplayName("Two adapter ids on such a cluster get the same refusal, not one of their own")
  public void twoAdapterIdsGetTheSameRefusal() {

    final var failure = assertThrows(
        Exception.class,
        () -> application = new SpringApplicationBuilder(ElectionTestApplication.class)
            .run(
                "--spring.config.name=camunda8-election-it",
                "--spring.main.web-application-type=none",
                "--vanillabp.adapters.c8-plain.rest-address="
                    + restAddress(BROKER_ALONE),
                "--vanillabp.adapters.c8-plain.grpc-address="
                    + grpcAddress(BROKER_ALONE),
                "--vanillabp.adapters.c8-prefix.type=camunda8",
                "--vanillabp.adapters.c8-prefix.job-lease=use",
                "--vanillabp.adapters.c8-prefix.name-clash-avoidance=use-prefix",
                "--vanillabp.adapters.c8-prefix.rest-address="
                    + restAddress(BROKER_ALONE),
                "--vanillabp.adapters.c8-prefix.grpc-address="
                    + grpcAddress(BROKER_ALONE),
                "--vanillabp.workflow-modules.election-app.adapters.c8-prefix.resources-location=classpath:it-election",
                "--vanillabp.prioritized-adapters[0]=c8-prefix",
                "--vanillabp.prioritized-adapters[1]=c8-plain"));

    // two ids on one such cluster used to have a refusal of their own, about keys which
    // cannot be mapped to a scope. That check could no longer be reached once one id was no
    // longer enough. A check which cannot be reached, but has the better message, is a trap
    final var message = refusalIn(failure);
    assertNotNull(message, "the boot failed with the refusal of the check");
    assertTrue(
        message.contains("needs a cluster which can be SEARCHED"),
        "the same message a single adapter id gets: "
            + message);

  }

  @Test
  @DisplayName("An old BPMS which may warn boots degraded on such a cluster instead of blocking the start")
  public void anAdapterWhichMayWarnBootsDegraded(
      final CapturedOutput output) {

    application = new SpringApplicationBuilder(ElectionTestApplication.class)
        .run(
            "--spring.config.name=camunda8-election-it",
            "--spring.main.web-application-type=none",
            // the new BPMS of the migration, on a cluster which can be searched
            "--vanillabp.adapters.c8-prefix.type=camunda8",
            "--vanillabp.adapters.c8-prefix.job-lease=use",
            "--vanillabp.adapters.c8-prefix.name-clash-avoidance=use-prefix",
            "--vanillabp.adapters.c8-prefix.rest-address="
                + restAddress(SEARCHABLE_CLUSTER),
            "--vanillabp.adapters.c8-prefix.grpc-address="
                + grpcAddress(SEARCHABLE_CLUSTER),
            "--vanillabp.workflow-modules.election-app.adapters.c8-prefix.resources-location=classpath:it-election",
            // and the old one, on the cluster nobody can search any more
            "--vanillabp.adapters.c8-plain.rest-address="
                + restAddress(BROKER_ALONE),
            "--vanillabp.adapters.c8-plain.grpc-address="
                + grpcAddress(BROKER_ALONE),
            "--vanillabp.adapters.c8-plain.deployment-failure=warn",
            // the second half of the way out. The core asks for it in a message of its own.
            // An adapter which deployed nothing cannot answer the election either. So routing
            // by list order has to be accepted on purpose, not by accident
            "--vanillabp.workflow-modules.election-app.election.guessing-adapters=ACCEPTED",
            "--vanillabp.prioritized-adapters[0]=c8-prefix",
            "--vanillabp.prioritized-adapters[1]=c8-plain");

    assertTrue(application.isActive(), "the application is up, which is the whole point of the policy");
    final var logged = output.getOut() + output.getErr();
    assertTrue(
        logged.contains("needs a cluster which can be SEARCHED"),
        () -> "and it was told why the old adapter is not serving anything: "
            + logged);
    assertTrue(
        logged.contains("deployment-failure") && logged.contains("'warn'"),
        () -> "naming the policy which let it boot: "
            + logged);
    assertTrue(
        logged.contains("cannot ask their BPMS whether it holds a workflow"),
        () -> "and the core says what the degraded adapter costs the election, which is why "
            + "'canLocateWorkflows' still reads the probe: "
            + logged);

  }

  @Test
  @DisplayName("Without accepting the routing by list order the degraded adapter still ends the boot")
  public void aDegradedAdapterAloneIsNotEnoughForAMigrationSetup() {

    final var failure = assertThrows(
        Exception.class,
        () -> application = new SpringApplicationBuilder(ElectionTestApplication.class)
            .run(
                "--spring.config.name=camunda8-election-it",
                "--spring.main.web-application-type=none",
                "--vanillabp.adapters.c8-prefix.type=camunda8",
                "--vanillabp.adapters.c8-prefix.job-lease=use",
                "--vanillabp.adapters.c8-prefix.name-clash-avoidance=use-prefix",
                "--vanillabp.adapters.c8-prefix.rest-address="
                    + restAddress(SEARCHABLE_CLUSTER),
                "--vanillabp.adapters.c8-prefix.grpc-address="
                    + grpcAddress(SEARCHABLE_CLUSTER),
                "--vanillabp.workflow-modules.election-app.adapters.c8-prefix.resources-location=classpath:it-election",
                "--vanillabp.adapters.c8-plain.rest-address="
                    + restAddress(BROKER_ALONE),
                "--vanillabp.adapters.c8-plain.grpc-address="
                    + grpcAddress(BROKER_ALONE),
                "--vanillabp.adapters.c8-plain.deployment-failure=warn",
                "--vanillabp.prioritized-adapters[0]=c8-prefix",
                "--vanillabp.prioritized-adapters[1]=c8-plain"));

    // the deployment warned and did not fail. The core then refused the SETUP. A migration
    // must not be built, unquestioned, on an adapter which reports that it cannot locate a
    // workflow. Now that this requirement exists, 'canLocateWorkflows' reads the probe and
    // does not answer true from a constant
    final var message = messageContaining(failure, "cannot ask their BPMS whether it holds a workflow");
    assertTrue(
        message.contains("cannot ask their BPMS whether it holds a workflow"),
        () -> "the core's refusal, not the adapter's: "
            + message);
    assertTrue(
        message.contains("guessing-adapters"),
        () -> "naming the way out this test leaves unconfigured: "
            + message);

  }

  /**
   * @param failure What the boot threw
   * @return The message of the cause which carries the refusal, or the outermost message
   */
  private static String refusalIn(
      final Throwable failure) {

    return messageContaining(failure, "needs a cluster which can be SEARCHED");

  }

  /**
   * @param failure What the boot threw
   * @param phrase What the message looked for says
   * @return The message of the cause carrying that phrase, or the outermost message
   */
  private static String messageContaining(
      final Throwable failure,
      final String phrase) {

    var current = failure;
    while (current != null) {
      final var message = current.getMessage();
      if ((message != null) && message.contains(phrase)) {
        return message;
      }
      current = current.getCause();
    }
    return String.valueOf(failure.getMessage());

  }

}
