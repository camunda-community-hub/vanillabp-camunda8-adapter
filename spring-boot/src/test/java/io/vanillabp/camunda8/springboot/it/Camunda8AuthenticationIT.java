package io.vanillabp.camunda8.springboot.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8AuthConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.test.ClusterUnderTest;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The adapter against a cluster with its authentication SWITCHED ON.
 * <p>
 * This is the test whose absence let the gap live: every other cluster here runs with
 * {@code CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI=true}, so an adapter which never
 * sent a credential passed all of them while it could not reach a single real
 * installation. Here nothing is unprotected, and everything the adapter does travels the
 * authenticated connection: the deployment at startup, the command which starts a
 * workflow, the activation request of the worker and the completion of its job.
 * <p>
 * The second half is the message. An adapter authenticating with {@code none} cannot be
 * warned at startup - whether a cluster wants credentials is only learnable by asking it
 * - so a client of that kind is built against the same running cluster and the guiding
 * message it produces is asserted.
 * <p>
 * The cluster is this class's own, while the other tests of this module share one. A shared
 * cluster with authentication switched on for everybody would be a different module under
 * test: every other class would have to carry credentials, and the one thing this class is
 * about would be what all of them do.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = DockerTestApplication.class,
    properties = "spring.config.name=camunda8-it")
// closed when the class is done: this context has a cluster of its own, Spring would keep
// the context until the JVM exits, and a context outliving its cluster keeps its job
// workers polling an address nobody answers
@DirtiesContext
public class Camunda8AuthenticationIT {

  @Container
  static final GenericContainer<?> CAMUNDA = ClusterUnderTest.withAuthentication();

  private static String restAddress() {

    return "http://"
        + CAMUNDA.getHost()
        + ":"
        + CAMUNDA.getMappedPort(8080);

  }

  @DynamicPropertySource
  static void camunda8Properties(
      final DynamicPropertyRegistry registry) {

    registry.add("vanillabp.adapters.c8.rest-address", Camunda8AuthenticationIT::restAddress);
    registry.add("vanillabp.adapters.c8.auth.username", () -> ClusterUnderTest.USERNAME);
    registry.add("vanillabp.adapters.c8.auth.password", () -> ClusterUnderTest.PASSWORD);
    // a cold container plus the outbox poll interval need more than the production
    // default of the exporter lag
    registry.add("vanillabp.adapters.c8.workflow-visibility-timeout", () -> "PT60S");

  }

  @Autowired
  private AuthDockerWorkflowService workflowService;

  @Autowired
  private AuthDockerAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private Camunda8ClientFactoryRegistry clientFactoryRegistry;

  @Test
  @DisplayName("basic credentials carry the whole round trip: deploy, start, activate, complete")
  public void credentialsCarryTheWholeRoundTrip() throws Exception {

    // the deployment already happened at startup, through the authenticated connection -
    // without credentials this context could not have booted at all
    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow().getId());
    assertNotNull(aggregateId);

    assertTrue(
        AuthDockerWorkflowService.SERVED.await(60, TimeUnit.SECONDS),
        "the worker never got its job from the authenticated cluster");

    final var deadline = System.currentTimeMillis() + 30_000;
    while (!repository.findById(aggregateId).map(AuthDockerAggregate::isServed).orElse(false)) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("the handler's change to the aggregate was never committed");
      }
      Thread.sleep(200);
    }

    // and a command of the application's own, on the same connection
    final var found = clientFactoryRegistry
        .getFactory("c8")
        .getClient()
        .newProcessInstanceSearchRequest()
        .send()
        .join();
    assertNotNull(found.items());

  }

  @Test
  @DisplayName("an adapter authenticating with none says why the cluster refuses it")
  public void authenticatingWithNoneIsReportedAtRuntime(
      final CapturedOutput output) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress(restAddress());
    configuration.getAuth().setMethod(Camunda8AuthConfiguration.Method.NONE);

    try (final var factory = new Camunda8ClientFactory("unauthenticated", configuration)) {

      assertEquals(
          Camunda8AuthConfiguration.Method.NONE,
          factory.getAuthentication().getMethod());
      // the cluster answers 401, which is where the adapter learns what a startup check
      // never could
      assertThrows(
          RuntimeException.class,
          () -> factory
              .getClient()
              .newProcessInstanceSearchRequest()
              .send()
              .join());
    }

    final var logged = output.getOut() + output.getErr();
    assertTrue(
        logged.contains("Camunda 8 adapter 'unauthenticated' authenticates with 'none'"),
        logged);
    assertTrue(logged.contains("vanillabp.adapters.unauthenticated.auth"), logged);
    assertTrue(logged.contains("method: basic"), logged);

  }

}
