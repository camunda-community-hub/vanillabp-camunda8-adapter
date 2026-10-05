package io.vanillabp.camunda8.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What one workflow module of one adapter id may register with its client, now that an
 * extension opens workers beside the adapter's.
 * <p>
 * Three promises are pinned here, and all three are about a second party being there: a
 * module holds more than one shutdown hook, the hooks run so that what was opened last goes
 * down first, and the adapter removing ITS hook leaves an extension's alone. The fourth test
 * is the backstop of the drain, and the fifth the resolver an extension asks for a job's
 * lock.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ShutdownHooksTest {

  private static Camunda8ClientFactory unconfiguredFactory() {

    // no connection properties: no client is built and nothing is contacted, which is all
    // this test needs - it is about what the factory remembers, not about a cluster
    return new Camunda8ClientFactory("c8", new Camunda8AdapterConfiguration());

  }

  @Test
  @DisplayName("A workflow module holds a shutdown hook per party which opened workers of it")
  public void aModuleHoldsMoreThanOneHook() {

    final var factory = unconfiguredFactory();
    final var stopped = new LinkedList<String>();

    factory.workflowModuleStarted("test-module", () -> stopped.add("adapter"));
    factory.workflowModuleStarted("test-module", () -> stopped.add("extension"));
    factory.close();

    assertEquals(
        List.of("extension", "adapter"),
        stopped,
        "reverse registration order: the workers opened last go down first, so the client is "
            + "closed under none of them");

  }

  @Test
  @DisplayName("A party removing its own hook leaves the other party's registered")
  public void removingOneHookLeavesTheOther() {

    final var factory = unconfiguredFactory();
    final var stopped = new LinkedList<String>();

    final var adapterHook = factory.workflowModuleStarted("test-module", () -> stopped.add("adapter"));
    factory.workflowModuleStarted("test-module", () -> stopped.add("extension"));
    adapterHook.close();

    assertEquals(
        java.util.Set.of("test-module"),
        factory.getOpenWorkflowModules(),
        "the module still has workers open, because the extension never stopped its own");
    factory.close();
    assertEquals(List.of("extension"), stopped, "and only the hook nobody removed still runs");

  }

  @Test
  @DisplayName("A module whose last hook is gone is not open any more")
  public void theLastHookClosesTheModule() {

    final var factory = unconfiguredFactory();
    final var stopped = new LinkedList<String>();

    final var first = factory.workflowModuleStarted("test-module", () -> stopped.add("adapter"));
    final var second = factory.workflowModuleStarted("test-module", () -> stopped.add("extension"));
    first.close();
    second.close();

    assertTrue(factory.getOpenWorkflowModules().isEmpty(), "nothing of that module is open");
    factory.close();
    assertTrue(stopped.isEmpty(), "and the backstop has nothing left to stop");

  }

  @Test
  @DisplayName("A hook which throws does not keep the other hooks of its module from running")
  public void aThrowingHookDoesNotStopTheOthers() {

    final var factory = unconfiguredFactory();
    final var stopped = new LinkedList<String>();

    factory.workflowModuleStarted("test-module", () -> stopped.add("adapter"));
    factory
        .workflowModuleStarted(
            "test-module",
            () -> {
              throw new IllegalStateException("the extension could not close its workers");
            });
    factory.close();

    assertEquals(
        List.of("adapter"),
        stopped,
        "the client is going down either way, so a party which cannot close its workers is named and "
            + "the ones behind it still get their turn");

  }

  @Test
  @DisplayName("Two workflow modules are closed independently of each other")
  public void twoModulesAreClosedIndependently() {

    final var factory = unconfiguredFactory();
    final var stopped = new LinkedList<String>();

    factory.workflowModuleStarted("first-module", () -> stopped.add("first"));
    factory.workflowModuleStarted("second-module", () -> stopped.add("second"));
    factory.close();

    assertEquals(
        java.util.Set.of("first", "second"),
        java.util.Set.copyOf(stopped),
        "every module still holding workers is stopped before the client goes down");

  }

  @Test
  @DisplayName("The drain of a module is the same one for everybody, and a new run gets a new one")
  public void theDrainIsSharedAndRenewedPerRun() {

    final var factory = unconfiguredFactory();

    final var drain = factory.drainOf("test-module");
    assertSame(drain, factory.drainOf("test-module"), "an extension asking gets the adapter's drain");

    drain.beginShutdown();
    final var afterRestart = factory.freshDrainOf("test-module");
    assertNotSame(drain, afterRestart, "a module which starts again gets a drain which is not shut down");
    assertFalse(afterRestart.isShuttingDown(), "otherwise every job of the new run would be left to its lock");
    assertSame(afterRestart, factory.drainOf("test-module"), "and that is the one everybody gets from now on");

  }

  @Test
  @DisplayName("The job timeout of an adapter id is readable, and defaults before a platform provided one")
  public void theJobTimeoutResolverIsPublished() {

    final var factory = unconfiguredFactory();

    assertEquals(
        Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        factory.getJobTimeoutResolver().jobTimeoutFor("test-module", "TestProcess", "doSomething"),
        "an adapter whose platform module never provided a resolver answers the default");

    factory
        .provideJobTimeoutResolver((
            workflowModuleId,
            bpmnProcessId,
            taskDefinition) -> java.time.Duration.ofMinutes(11));

    assertEquals(
        java.time.Duration.ofMinutes(11),
        factory.getJobTimeoutResolver().jobTimeoutFor("test-module", "TestProcess", "doSomething"),
        "and an extension asks the adapter rather than reading the configuration a second time");

  }

}
