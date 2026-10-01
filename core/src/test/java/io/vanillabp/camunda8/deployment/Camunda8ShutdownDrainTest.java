package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.camunda.client.api.worker.JobWorker;
import io.vanillabp.camunda8.Camunda8ProcessingContext;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.camunda8.wiring.Camunda8MultiInstance;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The shutdown at the level where it happens: what
 * {@code stopWorkflowProcessing} does with the handlers the closed workers still have
 * inside the application.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ShutdownDrainTest {

  private static final Duration GRACE = Duration.ofSeconds(2);

  /**
   * The grace of the two tests which say that a shutdown did NOT sit it out. A minute, so
   * that sitting it out and returning early are a minute apart and no stalled JVM can be
   * mistaken for either.
   */
  private static final Duration A_GRACE_NOBODY_REACHES = Duration.ofMinutes(1);

  /**
   * How long the handler of the test below stays inside its job. Long enough that a
   * shutdown which does not wait returns while it is still there.
   */
  private static final long A_HANDLER_STAYS_INSIDE_FOR = 1000;

  private Camunda8DeploymentService deploymentService(
      final Duration grace) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setShutdownGrace(grace);
    return DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(new Camunda8DeploymentServiceTest.NoOpInvoker()),
        (
            module,
            process,
            task) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofHours(1),
        adapterId -> configuration);

  }

  /**
   * The same, with a connection complete enough for a client to be built - building one
   * contacts no cluster, and {@code startWorkflowProcessing} asks for it.
   */
  private Camunda8DeploymentService deploymentServiceWithClient() {

    return deploymentServiceWithClient(GRACE);

  }

  /**
   * The same, with the grace the test needs.
   *
   * @param grace What the shutdown of that service is given
   * @return The service
   */
  private Camunda8DeploymentService deploymentServiceWithClient(
      final Duration grace) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setShutdownGrace(grace);
    configuration.setRestAddress("http://localhost:65535");
    return DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(new Camunda8DeploymentServiceTest.NoOpInvoker()),
        (
            module,
            process,
            task) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofHours(1),
        adapterId -> configuration);

  }

  /**
   * A worker which reports itself closed once it was closed, which is what a worker
   * without an activation request in flight does.
   */
  private JobWorker openWorker() {

    final var worker = mock(JobWorker.class);
    final var closed = new AtomicBoolean(false);
    when(worker.isClosed()).thenAnswer(invocation -> closed.get());
    Mockito
        .doAnswer(invocation -> {
          closed.set(true);
          return null;
        })
        .when(worker)
        .close();
    return worker;

  }

  /**
   * A worker whose activation request is parked at the cluster: closing it does not
   * cancel that request, so it keeps reporting itself open for as long as the request
   * takes.
   */
  private JobWorker workerWithAnActivationRequestInFlight() {

    final var worker = mock(JobWorker.class);
    when(worker.isClosed()).thenReturn(false);
    return worker;

  }

  @Test
  @DisplayName("Stopping a module closes its workers and marks it as shutting down")
  public void stoppingMarksTheModuleAndClosesItsWorkers() {

    final var service = deploymentService(GRACE);
    final var context = new Camunda8ProcessingContext("c8", "test-module", new Camunda8MultiInstance.Registry());
    final var worker = openWorker();
    context.getOpenWorkers().add(worker);

    service.stopWorkflowProcessing("test-module", context);

    verify(worker, times(1)).close();
    assertTrue(
        service.drainOf("test-module").isShuttingDown(),
        "a handler failing from here on knows it is the shutdown, not the application");
    assertTrue(context.getOpenWorkers().isEmpty(), "and the workers are gone");

  }

  @Test
  @DisplayName("A handler which comes back within the grace period is waited for, and no longer")
  public void aHandlerWithinTheGraceIsWaitedFor() {

    final var service = deploymentService(A_GRACE_NOBODY_REACHES);
    final var context = new Camunda8ProcessingContext("c8", "test-module", new Camunda8MultiInstance.Registry());
    context.getOpenWorkers().add(openWorker());
    final var drain = service.drainOf("test-module");
    drain.jobStarted(4711L, "task", "someTask", "TestProcess");

    // what happened in which order, which is what says whether the shutdown waited. A
    // clock would say it worse: a machine carrying several builds stops this JVM for
    // seconds at a time, and a stopped JVM looks exactly like a shutdown which waited
    final var whatHappened = new CopyOnWriteArrayList<String>();
    final var handler = new Thread(() -> {
      try {
        TimeUnit.MILLISECONDS.sleep(A_HANDLER_STAYS_INSIDE_FOR);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        whatHappened.add("the handler came back");
        drain.jobFinished(4711L);
      }
    });
    handler.start();

    final var startedAt = System.nanoTime();
    service.stopWorkflowProcessing("test-module", context);
    whatHappened.add("the shutdown returned");
    final var waited = (System.nanoTime() - startedAt) / 1_000_000;

    assertEquals(
        List.of("the handler came back", "the shutdown returned"),
        whatHappened,
        "the shutdown returned after its handler, not next to it");
    // a minute of grace against a handler which stays inside for a second: a shutdown
    // which sat the grace out is a minute away from one which did not, and nothing a
    // loaded machine does to this JVM falls between the two
    assertTrue(waited < A_GRACE_NOBODY_REACHES.dividedBy(2).toMillis(),
        "but not the whole grace period (was "
            + waited
            + " ms)");
    assertTrue(drain.getInFlight().isEmpty());

  }

  @Test
  @DisplayName("A handler outliving the grace period is named and left behind")
  public void aHandlerBeyondTheGraceIsNamed(
      final CapturedOutput output) {

    final var service = deploymentService(Duration.ofMillis(300));
    final var context = new Camunda8ProcessingContext("c8", "test-module", new Camunda8MultiInstance.Registry());
    context.getOpenWorkers().add(openWorker());
    final var drain = service.drainOf("test-module");
    drain.jobStarted(4711L, "task", "someTask", "TestProcess");

    service.stopWorkflowProcessing("test-module", context);

    final var logged = output.getOut() + output.getErr();
    assertTrue(logged.contains("4711"), "the operator learns which job was cut off: "
        + logged);
    assertTrue(logged.contains("someTask"), "and which task it belongs to: "
        + logged);
    assertFalse(drain.getInFlight().isEmpty(), "the handler is still running - it was cut off, not stopped");

  }

  @Test
  @DisplayName("A module which starts processing again is not shutting down any more")
  public void aRestartedModuleIsNotShuttingDown() {

    final var service = deploymentServiceWithClient();
    final var context = new Camunda8ProcessingContext("c8", "test-module", new Camunda8MultiInstance.Registry());

    service.stopWorkflowProcessing("test-module", context);
    assertTrue(service.drainOf("test-module").isShuttingDown());

    // a checkpoint and restore, or a platform restarting its lifecycle beans: the module
    // processes again, and a handler failing now is the application's failure once more
    service.startWorkflowProcessing("test-module", context);

    assertFalse(
        service.drainOf("test-module").isShuttingDown(),
        "the new workers report a failed job again");

  }

  @Test
  @DisplayName("A module with nothing in flight is stopped without waiting")
  public void anIdleModuleIsStoppedImmediately() {

    final var service = deploymentService(A_GRACE_NOBODY_REACHES);
    final var context = new Camunda8ProcessingContext("c8", "test-module", new Camunda8MultiInstance.Registry());
    context.getOpenWorkers().add(openWorker());

    final var startedAt = System.nanoTime();
    service.stopWorkflowProcessing("test-module", context);
    final var waited = (System.nanoTime() - startedAt) / 1_000_000;

    // half a minute against a grace of one: a module with nothing in flight either
    // returns at once or sits the grace out, and nothing a loaded machine does to this
    // JVM falls between the two. A second did not say that, it only said that this JVM
    // had its turn
    assertTrue(waited < A_GRACE_NOBODY_REACHES.dividedBy(2).toMillis(),
        "nothing was waited for (was "
            + waited
            + " ms)");

  }

  @Test
  @DisplayName("A worker whose activation request is parked is waited for, and named if it stays")
  public void aParkedActivationRequestIsWaitedFor(
      final CapturedOutput output) {

    final var service = deploymentService(Duration.ofMillis(400));
    final var context = new Camunda8ProcessingContext("c8", "test-module", new Camunda8MultiInstance.Registry());
    context.getOpenWorkers().add(workerWithAnActivationRequestInFlight());

    final var startedAt = System.nanoTime();
    service.stopWorkflowProcessing("test-module", context);
    final var waited = (System.nanoTime() - startedAt) / 1_000_000;

    assertTrue(
        waited >= 350,
        "the shutdown waited for the cluster to release the worker rather than closing the client over its "
            + "request (was "
            + waited
            + " ms)");
    final var logged = output.getOut() + output.getErr();
    assertTrue(
        logged.contains("still holding an activation request at the cluster"),
        "and what is left is named: "
            + logged);

  }

  /**
   * A worker which reports itself closed only once something else happened: its activation
   * request comes back when the workers of the OTHER workflow module are closed, which is
   * what a cluster does to a client whose connections free up.
   *
   * @param released What has to be true before this worker reports itself closed
   * @return The worker
   */
  private JobWorker workerReleasedOnlyAfter(
      final AtomicBoolean released) {

    final var worker = mock(JobWorker.class);
    when(worker.isClosed()).thenAnswer(invocation -> released.get());
    return worker;

  }

  /**
   * A worker whose close releases the worker above, the way the last module of an
   * application releases what the modules before it were waiting for.
   *
   * @param released What its close sets
   * @return The worker
   */
  private JobWorker workerWhoseCloseReleasesTheOther(
      final AtomicBoolean released) {

    final var worker = mock(JobWorker.class);
    final var closed = new AtomicBoolean(false);
    when(worker.isClosed()).thenAnswer(invocation -> closed.get());
    Mockito
        .doAnswer(invocation -> {
          closed.set(true);
          released.set(true);
          return null;
        })
        .when(worker)
        .close();
    return worker;

  }

  @Test
  @DisplayName("Two workflow modules are one wait, and the grace is not spent once per module")
  public void twoModulesAreOneWait(
      final CapturedOutput output) {

    final var service = deploymentServiceWithClient(A_GRACE_NOBODY_REACHES);
    final var stoppedLast = new Camunda8ProcessingContext("c8", "module-a", new Camunda8MultiInstance.Registry());
    final var stoppedFirst = new Camunda8ProcessingContext("c8", "module-b", new Camunda8MultiInstance.Registry());
    service.startWorkflowProcessing("module-a", stoppedLast);
    service.startWorkflowProcessing("module-b", stoppedFirst);

    // the activation request of the module stopped first comes back when the workers of the
    // other module are closed, which is the whole reason a wait per module adds up: while
    // one module is drained, the workers of the modules which are still open keep their
    // request alive
    final var theOtherModuleIsClosedToo = new AtomicBoolean(false);
    stoppedFirst.getOpenWorkers().add(workerReleasedOnlyAfter(theOtherModuleIsClosedToo));
    stoppedLast.getOpenWorkers().add(workerWhoseCloseReleasesTheOther(theOtherModuleIsClosedToo));

    final var startedAt = System.nanoTime();
    service.stopWorkflowProcessing("module-b", stoppedFirst);
    service.stopWorkflowProcessing("module-a", stoppedLast);
    final var waited = (System.nanoTime() - startedAt) / 1_000_000;

    // a minute of grace against a shutdown which has to wait for nothing once both modules
    // are closed: a shutdown which spent the grace on the module stopped first is a minute
    // away from this one, and nothing a loaded machine does to this JVM falls between the two
    assertTrue(
        waited < A_GRACE_NOBODY_REACHES.dividedBy(2).toMillis(),
        "the module stopped first did not sit out a grace of its own (was "
            + waited
            + " ms)");
    final var logged = output.getOut() + output.getErr();
    assertTrue(
        logged.contains("workflow module 'module-b' drained after"),
        "and the module stopped first is reported as drained, by the one wait: "
            + logged);
    assertTrue(
        logged.contains("workflow module 'module-a' drained after"),
        "together with the module stopped last: "
            + logged);

  }

  @Test
  @DisplayName("Closing the client of a module which never stopped closes its workers first")
  public void aClientClosedUnderOpenWorkersStopsThemFirst(
      final CapturedOutput output) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setShutdownGrace(GRACE);
    configuration.setRestAddress("http://localhost:65535");
    final var clientFactory = new Camunda8ClientFactory("c8", configuration);
    final var service = DeploymentServiceUnderTest.of(
        "c8", clientFactory, TestCollaborators
            .of(new Camunda8DeploymentServiceTest.NoOpInvoker()),
        (
            module,
            process,
            task) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofHours(1),
        adapterId -> configuration);
    final var context = new Camunda8ProcessingContext("c8", "test-module", new Camunda8MultiInstance.Registry());
    service.startWorkflowProcessing("test-module", context);
    // a platform whose shutdown never reaches the adapter: the module is open, and the
    // next thing which happens is the client going down
    final var worker = openWorker();
    context.getOpenWorkers().add(worker);

    clientFactory.close();

    verify(worker, times(1)).close();
    assertTrue(
        service.drainOf("test-module").isShuttingDown(),
        "the module went through its ordinary shutdown");
    assertTrue(clientFactory.getOpenWorkflowModules().isEmpty(), "and is not open any more");
    // the view of this test, not of the class: the test below says the same sentence is
    // absent, and both are only about the shutdown this test just ran
    final var logged = output.getAllOfThisTest();
    assertTrue(
        logged.contains("did not stop workflow processing"),
        "the missing hook is named rather than silently made up for: "
            + logged);

  }

  @Test
  @DisplayName("A module which stopped itself is not stopped again when the client closes")
  public void aModuleWhichStoppedItselfIsNotStoppedTwice(
      final CapturedOutput output) {

    final var configuration = new Camunda8AdapterConfiguration();
    configuration.setShutdownGrace(GRACE);
    configuration.setRestAddress("http://localhost:65535");
    final var clientFactory = new Camunda8ClientFactory("c8", configuration);
    final var service = DeploymentServiceUnderTest.of(
        "c8", clientFactory, TestCollaborators
            .of(new Camunda8DeploymentServiceTest.NoOpInvoker()),
        (
            module,
            process,
            task) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofHours(1),
        adapterId -> configuration);
    final var context = new Camunda8ProcessingContext("c8", "test-module", new Camunda8MultiInstance.Registry());
    service.startWorkflowProcessing("test-module", context);
    service.stopWorkflowProcessing("test-module", context);

    clientFactory.close();

    // the view of this test, not of the class: the test above prints the sentence this
    // one says is absent, so read over the whole class the assertion would hold only as
    // long as that test happens to run later
    final var logged = output.getAllOfThisTest();
    assertFalse(
        logged.contains("did not stop workflow processing"),
        "the ordinary path says nothing about a missing hook: "
            + logged);

  }

}
