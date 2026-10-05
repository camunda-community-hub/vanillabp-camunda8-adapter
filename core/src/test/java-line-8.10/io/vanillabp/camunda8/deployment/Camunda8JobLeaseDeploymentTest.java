package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.worker.JobWorkerBuilderStep1;
import io.vanillabp.camunda8.TestCollaborators;
import io.vanillabp.camunda8.client.Camunda8AdapterConfiguration;
import io.vanillabp.camunda8.client.Camunda8ClientFactory;
import io.vanillabp.camunda8.wiring.Camunda8JobTimeoutResolver;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which workers of a workflow module lease their activations, on the line which can.
 * <p>
 * Two rules and one switch. A worker which holds its job from the activation to the answer
 * leases, and a worker which serves tasks leases only where none of the task definitions of
 * its job type can stay open - a task completed in phase two is completed hours later by a
 * dispatcher holding no token, so a leased job of such a task could never be completed at
 * all. The switch is the application's, because a lease cannot be taken back per job.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8JobLeaseDeploymentTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String SYNCHRONOUS_TASK = "doSomething";

  private static final String ASYNCHRONOUS_TASK = "waitForSomebody";

  @Test
  @DisplayName("A worker which holds its job to the answer leases")
  public void aListenerWorkerLeases() {

    final var builder = aWorkerBuilder();

    adapterWhich(Camunda8AdapterConfiguration.JobLease.USE).leaseTheActivations(builder);

    verify(builder).withLease(true);

  }

  @Test
  @DisplayName("A task worker leases where none of its task definitions stays open")
  public void aSynchronousTaskWorkerLeases() {

    final var builder = aWorkerBuilder();

    adapterWhich(Camunda8AdapterConfiguration.JobLease.USE)
        .leaseUnlessATaskStaysOpen(builder, MODULE, List.of(served(SYNCHRONOUS_TASK)));

    verify(builder).withLease(true);

  }

  @Test
  @DisplayName("One task of a job type which stays open switches the lease off for that worker")
  public void oneAsynchronousTaskIsEnoughToStopIt() {

    final var builder = aWorkerBuilder();

    adapterWhich(Camunda8AdapterConfiguration.JobLease.USE)
        .leaseUnlessATaskStaysOpen(
            builder, MODULE, List.of(served(SYNCHRONOUS_TASK), served(ASYNCHRONOUS_TASK)));

    verify(builder, never()).withLease(true);
    assertTrue(
        mockingDetails(builder).getInvocations().isEmpty(),
        "the worker is opened exactly as it would be without the key");

  }

  @Test
  @DisplayName("A job type nothing is known about does not lease either")
  public void anUnknownJobTypeDoesNotLease() {

    final var builder = aWorkerBuilder();

    adapterWhich(Camunda8AdapterConfiguration.JobLease.USE)
        .leaseUnlessATaskStaysOpen(builder, MODULE, null);

    verify(builder, never()).withLease(true);

  }

  @Test
  @DisplayName("An application which said no leases nothing at all")
  public void nothingLeasesWhereTheApplicationSaidNo() {

    final var adapter = adapterWhich(Camunda8AdapterConfiguration.JobLease.DO_NOT_USE);

    final var listener = aWorkerBuilder();
    adapter.leaseTheActivations(listener);
    final var task = aWorkerBuilder();
    adapter.leaseUnlessATaskStaysOpen(task, MODULE, List.of(served(SYNCHRONOUS_TASK)));

    assertTrue(mockingDetails(listener).getInvocations().isEmpty());
    assertTrue(mockingDetails(task).getInvocations().isEmpty());

  }

  private static JobWorkerBuilderStep1.JobWorkerBuilderStep3 aWorkerBuilder() {

    return mock(JobWorkerBuilderStep1.JobWorkerBuilderStep3.class, RETURNS_SELF);

  }

  private static Camunda8DeploymentService.ServedElement served(
      final String taskDefinition) {

    return new Camunda8DeploymentService.ServedElement(PROCESS, "Activity_1", taskDefinition);

  }

  /**
   * An adapter whose core says that {@link #ASYNCHRONOUS_TASK} is the one task which can
   * stay open, and whose application decided the given way about the lease.
   */
  private static Camunda8DeploymentService adapterWhich(
      final Camunda8AdapterConfiguration.JobLease jobLease) {

    final var configuration = new Camunda8AdapterConfiguration();
    // an address nothing listens on: nothing here contacts a cluster
    configuration.setRestAddress("http://localhost:65535");
    configuration.setJobLease(jobLease);
    final var core = new Camunda8DeploymentServiceTest.NoOpInvoker() {

      @Override
      public boolean workflowTaskCompletesAsynchronously(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String taskDefinition) {

        return ASYNCHRONOUS_TASK.equals(taskDefinition);

      }

    };
    return DeploymentServiceUnderTest.of(
        "c8", new Camunda8ClientFactory("c8", configuration), TestCollaborators
            .of(core),
        (
            module,
            process,
            task) -> Camunda8JobTimeoutResolver.DEFAULT_JOB_TIMEOUT,
        Duration
            .ofHours(1),
        adapterId -> configuration, null);

  }

}
