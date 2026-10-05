package io.vanillabp.camunda8.quarkus.smoke;

import java.time.Duration;
import java.util.List;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.camunda.client.impl.basicauth.BasicAuthCredentialsProvider;
import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.camunda8.client.Camunda8AuthConfiguration;
import io.vanillabp.camunda8.client.Camunda8Authentication;
import io.vanillabp.camunda8.client.Camunda8ClientFactoryRegistry;
import io.vanillabp.camunda8.client.Camunda8VirtualThreadExecutor;
import io.vanillabp.camunda8.quarkus.runtime.VanillaBpCamunda8Properties;
import io.vanillabp.camunda8.wiring.Camunda8FetchVariables;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.camunda8.wiring.Camunda8RetryBackoffResolver;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Proves the job timeout resolves through all FOUR configuration levels of the
 * Quarkus overlay mapping - task, workflow, workflow-module and
 * adapter level, most specific wins - and that the async-task-lock-renewal is read at
 * adapter level. It is also where the Quarkus overlay is checked key by key: every
 * client, worker and authentication key has to bind AND arrive at the client the adapter
 * id built. No BPMN resources are deployed (the workflow module's resources-location is
 * empty), so the boot succeeds without a cluster.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8JobTimeoutOverlayTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(Aggregate.class)
          .addClass(SampleWorkflowService.class)
          .addClass(TestPhaseTwoOutbox.class)
          .addAsResource("overlay/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  private VanillaBpCamunda8Properties overlay() {

    return ConfigProvider
        .getConfig()
        .unwrap(SmallRyeConfig.class)
        .getConfigMapping(VanillaBpCamunda8Properties.class);

  }

  @Test
  public void jobTimeoutResolvesThroughAllFourLevels() {

    final var overlay = overlay();

    // task level (most specific)
    Assertions.assertEquals(
        Duration.ofSeconds(2),
        overlay.jobTimeoutFor("test-app", "TaskProcess", "happyTask", "c8"));
    // workflow level
    Assertions.assertEquals(
        Duration.ofSeconds(10),
        overlay.jobTimeoutFor("test-app", "TaskProcess", "otherTask", "c8"));
    // workflow-module level
    Assertions.assertEquals(
        Duration.ofSeconds(20),
        overlay.jobTimeoutFor("test-app", "OtherProcess", "someTask", "c8"));
    // adapter level (base)
    Assertions.assertEquals(
        Duration.ofSeconds(30),
        overlay.jobTimeoutFor("unknown-module", "SomeProcess", "someTask", "c8"));

  }

  @Test
  public void messageTimeToLiveResolvesThroughAllFourLevels() {

    final var overlay = overlay();

    // message level (most specific) - a catch event whose message may legitimately repeat
    // every minute, which is what a per-message override is for
    Assertions.assertEquals(
        Duration.ofMinutes(1),
        overlay.messageTimeToLiveFor("test-app", "TaskProcess", "OfferRequested", "c8"));
    // workflow level
    Assertions.assertEquals(
        Duration.ofHours(4),
        overlay.messageTimeToLiveFor("test-app", "TaskProcess", "SomeOtherMessage", "c8"));
    // workflow-module level
    Assertions.assertEquals(
        Duration.ofHours(5),
        overlay.messageTimeToLiveFor("test-app", "OtherProcess", "SomeMessage", "c8"));
    // adapter level (base)
    Assertions.assertEquals(
        Duration.ofHours(6),
        overlay.messageTimeToLiveFor("unknown-module", "SomeProcess", "SomeMessage", "c8"));

  }

  @Test
  public void aMessageOverrideDoesNotReachTasksAndTheOtherWayRound() {

    final var overlay = overlay();

    // the most specific level of the two resolutions is a different map on purpose: an
    // override meant for a message must not apply to a task of the same name, and the
    // task section of 'happyTask' must not answer a message question
    Assertions.assertEquals(
        Duration.ofSeconds(2),
        overlay.jobTimeoutFor("test-app", "TaskProcess", "happyTask", "c8"));
    Assertions.assertEquals(
        Duration.ofHours(4),
        overlay.messageTimeToLiveFor("test-app", "TaskProcess", "happyTask", "c8"),
        "a task name is not a message name - this falls back to the workflow level");

  }

  @Test
  public void retryBackoffResolvesThroughAllFourLevels() {

    final var overlay = overlay();

    // The backoff of a FAILED job is resolved from the same four levels
    Assertions.assertEquals(
        Duration.ofSeconds(2),
        overlay.retryBackoffFor("test-app", "TaskProcess", "happyTask", "c8"));
    Assertions.assertEquals(
        Duration.ofSeconds(10),
        overlay.retryBackoffFor("test-app", "TaskProcess", "otherTask", "c8"));
    Assertions.assertEquals(
        Duration.ofSeconds(20),
        overlay.retryBackoffFor("test-app", "OtherProcess", "someTask", "c8"));
    Assertions.assertEquals(
        Duration.ofSeconds(30),
        overlay.retryBackoffFor("unknown-module", "SomeProcess", "someTask", "c8"));
    // and an adapter id which configures none gets the default of ten seconds
    Assertions.assertEquals(
        Camunda8RetryBackoffResolver.DEFAULT_RETRY_BACKOFF,
        overlay.retryBackoffFor("test-app", "TaskProcess", "happyTask", "unknown-adapter"));

  }

  @Test
  public void theAnswerSaysWhetherTheTaskLevelConfiguredIt() {

    final var overlay = overlay();

    // what a 'retryBackoff' task header of the model is weighed against: only the task
    // level meets it as an equal, everything above it loses to the model
    Assertions.assertTrue(
        overlay.configuredRetryBackoffFor("test-app", "TaskProcess", "happyTask", "c8").perTask(),
        "'happyTask' configures a backoff of its own");
    Assertions.assertFalse(
        overlay.configuredRetryBackoffFor("test-app", "TaskProcess", "otherTask", "c8").perTask(),
        "the workflow level speaks about more than this one task");
    Assertions.assertFalse(
        overlay.configuredRetryBackoffFor("unknown-module", "SomeProcess", "someTask", "c8").perTask(),
        "and so does the adapter level");

  }

  @Test
  public void fetchVariablesResolvesThroughAllFourLevels() {

    final var overlay = overlay();

    // The escape hatch is resolved from the same four levels, and the task
    // level is its point - the case which needs everything is one task
    Assertions.assertEquals(
        Camunda8FetchVariables.Mode.ALL,
        overlay.fetchVariablesFor("test-app", "TaskProcess", "happyTask", "c8"));
    Assertions.assertEquals(
        Camunda8FetchVariables.Mode.DERIVED,
        overlay.fetchVariablesFor("test-app", "TaskProcess", "otherTask", "c8"));
    Assertions.assertEquals(
        Camunda8FetchVariables.Mode.ALL,
        overlay.fetchVariablesFor("test-app", "OtherProcess", "someTask", "c8"));
    Assertions.assertEquals(
        Camunda8FetchVariables.Mode.DERIVED,
        overlay.fetchVariablesFor("unknown-module", "SomeProcess", "someTask", "c8"));
    Assertions.assertEquals(
        Camunda8FetchVariables.Mode.DERIVED,
        overlay.fetchVariablesFor("test-app", "TaskProcess", "happyTask", "unknown-adapter"),
        "an adapter id which configures nothing derives, which is the default");
    Assertions.assertEquals(
        Camunda8FetchVariables.Mode.DERIVED,
        Arc
            .container()
            .instance(Camunda8ClientFactoryRegistry.class)
            .get()
            .getFactory("c8")
            .getConfiguration()
            .resolvedFetchVariables(),
        "the adapter-level value reaches the configuration as well");

  }

  @Test
  public void allowConnectorsResolvesThroughItsThreeLevels() {

    final var overlay = overlay();

    // the workflow says yes where its module said no, which is the case version 1
    // could not express: its primitive booleans could only turn the flag on
    final var perWorkflow = overlay.allowConnectorsFor("test-app", "TaskProcess", "c8");
    Assertions.assertTrue(perWorkflow.allowed());
    Assertions.assertEquals(
        "vanillabp.workflow-modules.test-app.workflows.TaskProcess.adapters.c8.allow-connectors",
        perWorkflow.propertyKey(),
        "the report has to name the line the reader can find in their configuration");

    // the module switches OFF what the adapter switched on
    final var perModule = overlay.allowConnectorsFor("test-app", "OtherProcess", "c8");
    Assertions.assertFalse(perModule.allowed());
    Assertions.assertEquals(
        "vanillabp.workflow-modules.test-app.adapters.c8.allow-connectors",
        perModule.propertyKey());

    // and everything the module says nothing about follows the adapter
    final var perAdapter = overlay.allowConnectorsFor("unknown-module", "SomeProcess", "c8");
    Assertions.assertTrue(perAdapter.allowed());
    Assertions.assertEquals("vanillabp.adapters.c8.allow-connectors", perAdapter.propertyKey());

    final var nothingConfigured = overlay
        .allowConnectorsFor("test-app", "TaskProcess", "unknown-adapter");
    Assertions.assertFalse(nothingConfigured.allowed(), "the default wires every element");
    Assertions.assertNull(nothingConfigured.propertyKey());

  }

  @Test
  public void aValueAtTaskLevelIsFoundAndChangesNoAnswer() {

    final var overlay = overlay();

    Assertions.assertEquals(
        List
            .of(
                "vanillabp.workflow-modules.test-app.workflows.TaskProcess.tasks.happyTask.adapters.c8.allow-connectors"),
        overlay.allowConnectorsKeysAtTaskLevel("c8"),
        "the boot names the key it cannot honour instead of ignoring it silently");
    Assertions.assertTrue(
        overlay.allowConnectorsFor("test-app", "TaskProcess", "c8").allowed(),
        "and the answer still comes from the workflow level");

  }

  @Test
  public void allowListenersResolvesThroughItsThreeLevels() {

    final var overlay = overlay();

    // the workflow serves its listeners where its module does not, which is the same
    // most-specific-wins rule allow-connectors follows - deliberately, because two keys about
    // what a model may contain must not reach different levels
    final var perWorkflow = overlay.allowListenersFor("test-app", "TaskProcess", "c8");
    Assertions.assertTrue(perWorkflow.allowed());
    Assertions.assertEquals(
        "vanillabp.workflow-modules.test-app.workflows.TaskProcess.adapters.c8.allow-listeners",
        perWorkflow.propertyKey(),
        "the report has to name the line the reader can find in their configuration");

    final var perModule = overlay.allowListenersFor("test-app", "OtherProcess", "c8");
    Assertions.assertFalse(perModule.allowed());
    Assertions.assertEquals(
        "vanillabp.workflow-modules.test-app.adapters.c8.allow-listeners",
        perModule.propertyKey());

    final var perAdapter = overlay.allowListenersFor("unknown-module", "SomeProcess", "c8");
    Assertions.assertTrue(perAdapter.allowed());
    Assertions.assertEquals("vanillabp.adapters.c8.allow-listeners", perAdapter.propertyKey());

    final var nothingConfigured = overlay.allowListenersFor("test-app", "TaskProcess", "unknown-adapter");
    Assertions.assertFalse(nothingConfigured.allowed(), "the default serves no modelled listener");
    Assertions.assertNull(nothingConfigured.propertyKey());

  }

  @Test
  public void aListenerValueAtTaskLevelIsFoundAndChangesNoAnswer() {

    final var overlay = overlay();

    Assertions.assertEquals(
        List
            .of(
                "vanillabp.workflow-modules.test-app.workflows.TaskProcess.tasks.happyTask.adapters.c8.allow-listeners"),
        overlay.allowListenersKeysAtTaskLevel("c8"),
        "the boot names the key it cannot honour instead of ignoring it silently");
    Assertions.assertTrue(
        overlay.allowListenersFor("test-app", "TaskProcess", "c8").allowed(),
        "and the answer still comes from the workflow level");

  }

  @Test
  public void theTenantResolvesPerWorkflowModule() {

    final var overlay = overlay();

    // the workflow module's own name wins over the adapter's, which is how an application
    // gives one module a scope of its own without moving every other module with it
    final var perModule = overlay.configuredTenantFor("c8", "test-app");
    Assertions.assertEquals("test-app-tenant", perModule.tenantId());
    Assertions
        .assertEquals(
            "vanillabp.workflow-modules.test-app.adapters.c8.tenant-id",
            perModule.propertyKey(),
            "a message about this name has to send the developer to the line which holds it");

    final var perAdapter = overlay.configuredTenantFor("c8", "unknown-module");
    Assertions.assertEquals("one-tenant-for-all", perAdapter.tenantId());
    Assertions.assertEquals("vanillabp.adapters.c8.tenant-id", perAdapter.propertyKey());

    Assertions
        .assertNull(
            overlay.configuredTenantFor("unknown-adapter", "test-app"),
            "an adapter id nothing configures has no name, and the workflow module id is used");

  }

  @Test
  public void defaultsApplyWithoutAnyConfiguredTimeout() {

    final var overlay = overlay();

    // an adapter id without any configured job-timeout falls back to the default
    Assertions.assertEquals(
        Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        overlay.jobTimeoutFor("test-app", "TaskProcess", "happyTask", "unknown-adapter"));
    // the async-task-lock-renewal is an adapter-level key
    Assertions.assertEquals(
        Duration.ofMinutes(2),
        overlay
            .adapters()
            .get("c8")
            .asyncTaskLockRenewal()
            .orElseThrow());
    // and so is the grace a shutdown grants its handlers
    Assertions.assertEquals(
        Duration.ofSeconds(5),
        overlay
            .adapters()
            .get("c8")
            .shutdownGrace()
            .orElseThrow());
    Assertions.assertEquals(
        Duration.ofSeconds(5),
        Arc
            .container()
            .instance(Camunda8ClientFactoryRegistry.class)
            .get()
            .getFactory("c8")
            .getConfiguration()
            .resolvedShutdownGrace(),
        "the value reaches the configuration the deployment service reads it from");
    // and so is how long the start waits for a cluster which is not answering yet
    Assertions.assertEquals(
        Duration.ofSeconds(30),
        overlay
            .adapters()
            .get("c8")
            .startupWait()
            .orElseThrow());
    Assertions.assertEquals(
        Duration.ofSeconds(30),
        Arc
            .container()
            .instance(Camunda8ClientFactoryRegistry.class)
            .get()
            .getFactory("c8")
            .getConfiguration()
            .resolvedStartupWait(),
        "the value reaches the configuration the wait reads it from");

  }


  @Test
  public void everyWorkerAndClientKeyIsBoundAndReachesTheClient() {

    final var keys = overlay().adapters().get("c8");

    Assertions.assertEquals("virtual", keys.workerThreads().orElseThrow());
    Assertions.assertEquals(6, keys.workerThreadsBound().orElseThrow());
    Assertions.assertEquals(24, keys.maxJobsActive().orElseThrow());
    Assertions.assertEquals(Duration.ofMillis(250), keys.pollInterval().orElseThrow());
    Assertions.assertEquals(Duration.ofSeconds(20), keys.requestTimeout().orElseThrow());
    Assertions.assertTrue(keys.streamEnabled().orElseThrow());
    Assertions.assertEquals(Duration.ofMinutes(30), keys.streamTimeout().orElseThrow());
    Assertions.assertEquals(Duration.ofHours(6), keys.messageTimeToLive().orElseThrow());
    Assertions.assertEquals(8388608, keys.maxMessageSize().orElseThrow());
    Assertions.assertEquals(Duration.ofSeconds(30), keys.keepAlive().orElseThrow());
    Assertions.assertEquals(64, keys.maxHttpConnections().orElseThrow());
    Assertions.assertEquals("gateway.internal", keys.overrideAuthority().orElseThrow());
    Assertions
        .assertEquals(
            Camunda8AuthConfiguration.Method.BASIC,
            keys.auth().method().orElseThrow());
    Assertions.assertEquals("demo", keys.auth().username().orElseThrow());

    // and the values really arrive at the client this adapter id built
    final var factory = Arc
        .container()
        .instance(Camunda8ClientFactoryRegistry.class)
        .get()
        .getFactory("c8");
    final var clientConfiguration = factory.getClient().getConfiguration();
    Assertions.assertEquals(24, clientConfiguration.getDefaultJobWorkerMaxJobsActive());
    Assertions.assertEquals(Duration.ofMillis(250), clientConfiguration.getDefaultJobPollInterval());
    Assertions.assertEquals(Duration.ofSeconds(20), clientConfiguration.getDefaultRequestTimeout());
    Assertions.assertTrue(clientConfiguration.getDefaultJobWorkerStreamEnabled());
    Assertions.assertEquals(Duration.ofHours(6), clientConfiguration.getDefaultMessageTimeToLive());
    Assertions.assertEquals(8388608, clientConfiguration.getMaxMessageSize());
    Assertions.assertEquals(Duration.ofSeconds(30), clientConfiguration.getKeepAlive());
    Assertions.assertEquals(64, clientConfiguration.getMaxHttpConnections());
    Assertions.assertEquals("gateway.internal", clientConfiguration.getOverrideAuthority());
    Assertions
        .assertInstanceOf(
            BasicAuthCredentialsProvider.class,
            Camunda8Authentication
                .unwrap(clientConfiguration.getCredentialsProvider()),
            "the auth block of the overlay reaches the client this adapter id built");
    Assertions
        .assertInstanceOf(
            Camunda8VirtualThreadExecutor.class,
            factory.getExecutor(),
            "the virtual mode is what the overlay configured");
    Assertions.assertEquals(6, factory.getExecutor().getBound(),
        "and the bound of the overlay reaches the executor the client runs its workers on");

  }

}
