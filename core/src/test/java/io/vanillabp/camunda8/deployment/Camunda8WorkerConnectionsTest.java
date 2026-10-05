package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.CamundaClientConfiguration;
import io.camunda.client.api.worker.JobWorker;
import io.camunda.client.api.worker.JobWorkerBuilderStep1;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.client.Camunda8Workers;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an application learns when it opens more workers than its Camunda client has HTTP
 * connections, and which workers are in that number.
 * <p>
 * The numbers this is built around were measured on 2026-09-26 and are written down in
 * {@code Camunda8WorkerConnections}: 115 workers against the client's 100 connections took
 * 10412 ms where 92 workers against the same 100 took 184 ms. So 115 and 92 are the two
 * numbers below, and the second one is the counter-check which has to stay quiet.
 * <p>
 * The same pool decides how long the shutdown of those workers takes, and that is the second
 * half of this class. 115 workers on a pool of 100 are two rounds of it, a round costs a
 * request timeout, and the grace this adapter defaults to carries one round and two seconds.
 * So the application which the first half warns about is the same one whose restart ends in
 * the middle of its drain, which is what happened on 2026-09-27.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8WorkerConnectionsTest {

  /**
   * How many connections the Camunda client keeps unless the application says otherwise,
   * the same number in the 8.8, 8.9 and 8.10 clients.
   */
  private static final int THE_CLIENTS_OWN_POOL = 100;

  /** How many workers the Spring Boot test module opens on the 8.10 line. */
  private static final int MORE_WORKERS_THAN_THE_POOL = 115;

  /** How many it opened before the start-event listener of story 653 came along. */
  private static final int FEWER_WORKERS_THAN_THE_POOL = 92;

  /**
   * A startup report which keeps what it was told, which is what a test reads instead of
   * the block the platform renders out of it.
   */
  private static final class WhatWasReported implements StartupReport {

    private record Finding(String severity,
                           StartupTopic topic,
                           String scope,
                           String message) {
    }

    private final List<Finding> findings = new ArrayList<>();

    @Override
    public void notice(
        final StartupTopic topic,
        final String scope,
        final String message) {

      findings.add(new Finding("notice", topic, scope, message));

    }

    @Override
    public void warn(
        final StartupTopic topic,
        final String scope,
        final String message) {

      findings.add(new Finding("warn", topic, scope, message));

    }

    @Override
    public void error(
        final StartupTopic topic,
        final String scope,
        final String message) {

      findings.add(new Finding("error", topic, scope, message));

    }

    @Override
    public void refuse(
        final StartupTopic topic,
        final String scope,
        final String message) {

      findings.add(new Finding("refuse", topic, scope, message));

    }

  }

  /**
   * The factory of the adapter id under test, which is where the workers open on its
   * client are counted. Built with an address nothing listens on: the client is built,
   * which is all a worker count needs, and no test here sends a command.
   */
  private Camunda8ClientFactory clientFactory() {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setRestAddress("http://localhost:65535");
    return new Camunda8ClientFactory("c8", configuration);

  }

  private Camunda8DeploymentService deploymentService() {

    return deploymentService(clientFactory());

  }

  private Camunda8DeploymentService deploymentService(
      final Camunda8ClientFactory clientFactory) {

    return DeploymentServiceUnderTest.of(
        "c8", clientFactory, TestCollaborators
            .of(new Camunda8DeploymentServiceTest.NoOpInvoker()),
        (
            module,
            process,
            task) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration.ofHours(1),
        adapterId -> clientFactory.getConfiguration());

  }

  /**
   * A worker builder which opens the given worker, which is what a test opens instead of
   * subscribing to a cluster.
   */
  private JobWorkerBuilderStep1.JobWorkerBuilderStep3 builderOpening(
      final JobWorker worker) {

    final var builder = mock(JobWorkerBuilderStep1.JobWorkerBuilderStep3.class);
    when(builder.open()).thenReturn(worker);
    return builder;

  }

  /**
   * Opens as many workers on this client as asked for, the way an extension opens one.
   *
   * @return The workers, so a test can close them again
   */
  private List<JobWorker> openWorkers(
      final Camunda8ClientFactory clientFactory,
      final int howMany) {

    final var workers = new ArrayList<JobWorker>();
    for (var i = 0; i < howMany; ++i) {
      workers.add(Camunda8Workers.open(builderOpening(mock(JobWorker.class)), clientFactory));
    }
    return workers;

  }

  /**
   * The configuration of a built client, as the CLIENT resolved it: a pool of the given
   * size, a ten second request timeout and the REST transport the adapter's workers
   * activate over.
   */
  private CamundaClientConfiguration clientWithAPoolOf(
      final int maxHttpConnections) {

    return clientWithAPoolOf(maxHttpConnections, true);

  }

  private CamundaClientConfiguration clientWithAPoolOf(
      final int maxHttpConnections,
      final boolean preferRestOverGrpc) {

    final var clientConfiguration = mock(CamundaClientConfiguration.class);
    when(clientConfiguration.getMaxHttpConnections()).thenReturn(maxHttpConnections);
    when(clientConfiguration.getDefaultRequestTimeout()).thenReturn(Duration.ofSeconds(10));
    when(clientConfiguration.preferRestOverGrpc()).thenReturn(preferRestOverGrpc);
    return clientConfiguration;

  }

  /**
   * The findings which are about the connection pool a running application shares, which is
   * the first of the two sentences this check can produce.
   */
  private static List<String> aboutThePool(
      final WhatWasReported reported) {

    return reported.findings
        .stream()
        .map(WhatWasReported.Finding::message)
        .filter(message -> message.contains("max-http-connections") && !message.contains("shutdown-grace"))
        .toList();

  }

  /**
   * The findings which are about the shutdown of those workers, which is the second one.
   */
  private static List<String> aboutTheShutdown(
      final WhatWasReported reported) {

    return reported.findings
        .stream()
        .map(WhatWasReported.Finding::message)
        .filter(message -> message.contains("shutdown-grace"))
        .toList();

  }

  /**
   * A deployment service whose adapter id has the given shutdown grace configured, so the
   * check reads that value instead of the default.
   */
  private Camunda8DeploymentService deploymentServiceWithAGraceOf(
      final Duration grace) {

    final var clientFactory = clientFactory();
    clientFactory.getConfiguration().setShutdownGrace(grace);
    return deploymentService(clientFactory);

  }

  @Test
  @DisplayName("More workers than connections is said at startup, with both numbers and the way out")
  public void theApplicationIsToldWhenItsWorkersOutgrewThePool() {

    final var service = deploymentService();
    final var reported = new WhatWasReported();
    service.setStartupReport(reported);

    service
        .holdTheWorkersAgainstTheConnectionPool(
            MORE_WORKERS_THAN_THE_POOL,
            clientWithAPoolOf(THE_CLIENTS_OWN_POOL));

    assertEquals(1, aboutThePool(reported).size(), "one finding about the pool: "
        + reported.findings);
    final var finding = reported.findings.get(0);
    assertEquals("warn", finding.severity(), "the application still boots, so it is a warning");
    assertEquals(StartupTopic.CONFIGURATION, finding.topic(), "the fix is a property");
    assertEquals("camunda8 adapter 'c8'", finding.scope(), "the adapter id is the scope, not part of the text");
    final var message = finding.message();
    assertTrue(message.contains("115 job workers"), "how many workers there are: "
        + message);
    assertTrue(message.contains("100 HTTP connections"), "and how many connections they share: "
        + message);
    assertTrue(
        message.contains("vanillabp.adapters.<adapter id>.max-http-connections"),
        "the property which raises the pool: "
            + message);
    assertTrue(message.contains("256"), "and a value to start from: "
        + message);
    assertTrue(message.contains("request-timeout"), "what it costs while nobody raises it: "
        + message);
    assertTrue(message.contains("PT10S"), "and how long that is here: "
        + message);

  }

  @Test
  @DisplayName("An application below the pool hears nothing")
  public void anApplicationWhichFitsIntoItsPoolHearsNothing() {

    final var service = deploymentService();
    final var reported = new WhatWasReported();
    service.setStartupReport(reported);

    service
        .holdTheWorkersAgainstTheConnectionPool(
            FEWER_WORKERS_THAN_THE_POOL,
            clientWithAPoolOf(THE_CLIENTS_OWN_POOL));

    assertTrue(reported.findings.isEmpty(), "92 workers fit into 100 connections: "
        + reported.findings);

  }

  @Test
  @DisplayName("The same workers against a pool which was raised hear nothing either")
  public void theRaisedPoolIsWhatMakesItQuiet() {

    final var service = deploymentService();
    final var reported = new WhatWasReported();
    service.setStartupReport(reported);

    service
        .holdTheWorkersAgainstTheConnectionPool(
            MORE_WORKERS_THAN_THE_POOL,
            clientWithAPoolOf(256));

    assertTrue(reported.findings.isEmpty(), "115 workers fit into 256 connections: "
        + reported.findings);

  }

  @Test
  @DisplayName("A grace which carries one round of the pool is too short for two, and the start says so")
  public void theApplicationIsToldWhenItsGraceCannotDrainItsWorkers() {

    final var service = deploymentService();
    final var reported = new WhatWasReported();
    service.setStartupReport(reported);

    // the default grace, which is what the run of 2026-09-27 had
    service
        .holdTheWorkersAgainstTheConnectionPool(
            MORE_WORKERS_THAN_THE_POOL,
            clientWithAPoolOf(THE_CLIENTS_OWN_POOL));

    final var aboutTheShutdown = aboutTheShutdown(reported);
    assertEquals(1, aboutTheShutdown.size(), "one finding about the shutdown: "
        + reported.findings);
    final var message = aboutTheShutdown.get(0);
    assertTrue(message.contains("115 job workers"), "how many workers have to be drained: "
        + message);
    assertTrue(message.contains("100 HTTP connections"), "against how many connections: "
        + message);
    assertTrue(message.contains("PT20S"), "what the grace is: "
        + message);
    assertTrue(message.contains("2 rounds"), "how many rounds of the pool that is: "
        + message);
    assertTrue(message.contains("PT22S"), "and what the drain therefore needs: "
        + message);
    assertTrue(
        message.contains("vanillabp.adapters.<adapter id>.request-timeout"),
        "the window a queued request waits once it goes out: "
            + message);
    assertTrue(
        message.contains("vanillabp.adapters.<adapter id>.job-timeout"),
        "and what a job created in the window pays: "
            + message);
    assertTrue(
        message.contains("spring.lifecycle.timeout-per-shutdown-phase"),
        "raising the grace means raising the runtime's budget with it: "
            + message);
    assertTrue(
        message.contains("vanillabp.adapters.<adapter id>.max-http-connections"),
        "and the other way out is the pool: "
            + message);

  }

  @Test
  @DisplayName("A grace above the floor of the drain hears nothing")
  public void aGraceWhichCarriesTheDrainIsSilent() {

    final var service = deploymentServiceWithAGraceOf(Duration.ofSeconds(25));
    final var reported = new WhatWasReported();
    service.setStartupReport(reported);

    service
        .holdTheWorkersAgainstTheConnectionPool(
            MORE_WORKERS_THAN_THE_POOL,
            clientWithAPoolOf(THE_CLIENTS_OWN_POOL));

    assertTrue(
        aboutTheShutdown(reported).isEmpty(),
        "PT25S carries the PT22S two rounds of the pool cost: "
            + reported.findings);

  }

  @Test
  @DisplayName("Workers which fit the pool once drain within the default grace")
  public void oneRoundOfThePoolIsWhatTheDefaultGraceWasSizedFor() {

    final var service = deploymentService();
    final var reported = new WhatWasReported();
    service.setStartupReport(reported);

    service
        .holdTheWorkersAgainstTheConnectionPool(
            FEWER_WORKERS_THAN_THE_POOL,
            clientWithAPoolOf(THE_CLIENTS_OWN_POOL));

    assertTrue(reported.findings.isEmpty(), "92 workers are one round of 100 connections: "
        + reported.findings);

  }

  @Test
  @DisplayName("Where the drain outgrew the runtime's budget, the pool is the only way out named first")
  public void aDrainBeyondTheRuntimesBudgetIsNoLongerAboutTheGrace() {

    final var service = deploymentServiceWithAGraceOf(Duration.ofSeconds(29));
    final var reported = new WhatWasReported();
    service.setStartupReport(reported);

    // three rounds of the pool, so the drain needs 32 seconds and no grace under the 30 both
    // Spring Boot and Kubernetes grant a shutdown can carry it
    service
        .holdTheWorkersAgainstTheConnectionPool(250, clientWithAPoolOf(THE_CLIENTS_OWN_POOL));

    final var aboutTheShutdown = aboutTheShutdown(reported);
    assertEquals(1, aboutTheShutdown.size(), "one finding about the shutdown: "
        + reported.findings);
    final var message = aboutTheShutdown.get(0);
    assertTrue(message.contains("3 rounds"), "three rounds of the pool: "
        + message);
    assertTrue(message.contains("PT32S"), "which is what the drain needs: "
        + message);
    assertTrue(
        message.contains("Raising the grace is not the way out here"),
        "and raising the grace that far would get the application killed instead: "
            + message);
    assertTrue(
        message.contains("vanillabp.adapters.<adapter id>.max-http-connections"),
        "so the pool is what is asked for: "
            + message);
    assertTrue(message.contains("512"), "with a value to start from: "
        + message);

  }

  @Test
  @DisplayName("A grace of zero and a grace below the request timeout say nothing here")
  public void theOtherTwoGracesBelongToTheStartupValidation() {

    final var withoutAnyWait = deploymentServiceWithAGraceOf(Duration.ZERO);
    final var reportedForZero = new WhatWasReported();
    withoutAnyWait.setStartupReport(reportedForZero);
    withoutAnyWait
        .holdTheWorkersAgainstTheConnectionPool(
            MORE_WORKERS_THAN_THE_POOL,
            clientWithAPoolOf(THE_CLIENTS_OWN_POOL));

    assertTrue(
        aboutTheShutdown(reportedForZero).isEmpty(),
        "a grace of zero is an operator asking for a shutdown which waits for nothing: "
            + reportedForZero.findings);

    final var belowOneRequest = deploymentServiceWithAGraceOf(Duration.ofSeconds(5));
    final var reportedForFive = new WhatWasReported();
    belowOneRequest.setStartupReport(reportedForFive);
    belowOneRequest
        .holdTheWorkersAgainstTheConnectionPool(
            MORE_WORKERS_THAN_THE_POOL,
            clientWithAPoolOf(THE_CLIENTS_OWN_POOL));

    assertTrue(
        aboutTheShutdown(reportedForFive).isEmpty(),
        "and a grace under one request timeout cannot drain for a reason which has nothing to do with "
            + "the number of workers, which the startup validation of the grace says in its own words: "
            + reportedForFive.findings);

  }

  @Test
  @DisplayName("Every worker open on the client is in the count, whoever opened it")
  public void everyWorkerOpenedOnThisClientIsCounted() {

    final var clientFactory = clientFactory();

    // the workers of one workflow module, the workers of a second one and the worker an
    // extension opened: they reach the count on the same path, because the count happens
    // where a worker is OPENED and not where it was ordered
    final var firstModule = openWorkers(clientFactory, 60);
    final var secondModule = openWorkers(clientFactory, 59);
    final var anExtensions = openWorkers(clientFactory, 1);
    assertEquals(120, clientFactory.countTheOpenWorkers(), "all of them hold a connection");

    // and a worker which is closed gave its connection back
    when(firstModule.get(0).isClosed()).thenReturn(true);
    when(secondModule.get(0).isClosed()).thenReturn(true);
    when(anExtensions.get(0).isClosed()).thenReturn(true);

    assertEquals(117, clientFactory.countTheOpenWorkers(), "a closed worker leaves the count");

  }

  @Test
  @DisplayName("A worker an extension opens after the start is held against the pool as well")
  public void aWorkerAnExtensionOpensCountsAgainstThePoolToo() {

    final var clientFactory = clientFactory();
    final var service = deploymentService(clientFactory);
    final var reported = new WhatWasReported();
    service.setStartupReport(reported);

    // the adapter's own workers, one below the pool of the client it built: the start of
    // the workflow modules said nothing, because nothing was wrong yet
    openWorkers(clientFactory, THE_CLIENTS_OWN_POOL - 1);
    assertTrue(reported.findings.isEmpty(), "99 workers fit into the 100 connections the client keeps: "
        + reported.findings);

    // and now an extension opens one of its own on the same client
    openWorkers(clientFactory, 1);

    assertEquals(1, reported.findings.size(), "which is the worker that takes the pool: "
        + reported.findings);
    final var message = reported.findings.get(0).message();
    assertTrue(message.contains("100 job workers"), "the number is the one over all of them: "
        + message);
    assertTrue(
        message.contains("100 HTTP connections"),
        "and it is held against the pool the client really keeps, which is 100 on every line: "
            + message);

  }

  @Test
  @DisplayName("A client which activates over gRPC is not measured against the HTTP pool")
  public void grpcActivationsAreNotLimitedByThisPool() {

    final var service = deploymentService();
    final var reported = new WhatWasReported();
    service.setStartupReport(reported);

    service
        .holdTheWorkersAgainstTheConnectionPool(
            MORE_WORKERS_THAN_THE_POOL,
            clientWithAPoolOf(THE_CLIENTS_OWN_POOL, false));

    assertTrue(reported.findings.isEmpty(), "the workers do not hold an HTTP connection then: "
        + reported.findings);

  }

  @Test
  @DisplayName("Without a platform integration the finding goes into the log")
  public void anAdapterWithoutTheCollectionPointStillSaysIt(
      final CapturedOutput output) {

    final var service = deploymentService();

    service
        .holdTheWorkersAgainstTheConnectionPool(
            MORE_WORKERS_THAN_THE_POOL,
            clientWithAPoolOf(THE_CLIENTS_OWN_POOL));

    final var logged = output.getOut() + output.getErr();
    assertTrue(logged.contains("115 job workers"), "the message is written where it was found: "
        + logged);
    assertTrue(logged.contains("camunda8 adapter 'c8'"), "and it says which adapter id it is about: "
        + logged);

  }

}
