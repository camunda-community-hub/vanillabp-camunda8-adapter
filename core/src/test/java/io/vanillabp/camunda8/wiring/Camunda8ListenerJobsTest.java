package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.vanillabp.camunda8.client.Camunda8Drain;
import io.vanillabp.integration.adapter.spi.workflowtask.DeliveryOfAnUnknownWorkflowException;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The protocol a listener job follows, asked of the class an EXTENSION calls rather than of
 * one of the adapter's two handlers.
 * <p>
 * The promises of {@link Camunda8ListenerJobs} are the reason it is public, so each of them
 * has a test: the completion, the failure with the retries the caller chose, and the one a
 * listener whose failure leaves no retries depends on - a job cut off by a shutdown is not
 * failed at all, because failing it with no attempt left IS the incident.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8ListenerJobsTest {

  private final JobClient jobClient = mock(JobClient.class, RETURNS_DEEP_STUBS);

  private final Camunda8Drain drain = new Camunda8Drain("c8", "test-module");

  private static ActivatedJob listenerJob() {

    final var job = mock(ActivatedJob.class);
    when(job.getKey()).thenReturn(4711L);
    when(job.getRetries()).thenReturn(3);
    when(job.getType()).thenReturn("theExtensionsListener");
    return job;

  }

  private void run(
      final Camunda8ListenerJobs.Failure failure,
      final Camunda8ListenerJobs.ListenerWork work) {

    Camunda8ListenerJobs
        .completeOrFail(
            "c8",
            jobClient,
            listenerJob(),
            drain,
            "extension listener",
            "theExtensionsListener",
            "TestProcess",
            () -> failure,
            work);

  }

  @Test
  @DisplayName("A listener which succeeded completes its job, and carries what it returned")
  public void aSucceedingListenerCompletesItsJob() {

    run(Camunda8ListenerJobs.Failure.NO_RETRIES_LEFT, () -> Map.of("theOrderWasArchived", Boolean.TRUE));

    final var variables = ArgumentCaptor.forClass(Map.class);
    verify(jobClient.newCompleteCommand(4711L)).variables(variables.capture());
    assertEquals(
        Map.of("theOrderWasArchived", Boolean.TRUE),
        variables.getValue(),
        "the completion carries what the listener returned");

  }

  @Test
  @DisplayName("A listener which carries nothing completes without a variables payload")
  public void aListenerCarryingNothingSendsNoPayload() {

    run(Camunda8ListenerJobs.Failure.NO_RETRIES_LEFT, Map::of);

    verify(jobClient.newCompleteCommand(4711L), never()).variables(any(Map.class));
    verify(jobClient.newCompleteCommand(4711L)).send();

  }

  @Test
  @DisplayName("A listener which threw fails its job with the retries and the backoff of its caller")
  public void aFailingListenerFailsItsJob() {

    run(
        new Camunda8ListenerJobs.Failure(2, Duration.ofSeconds(5)),
        () -> {
          throw new IllegalStateException("the extension could not archive the order");
        });

    final var message = ArgumentCaptor.forClass(String.class);
    verify(jobClient.newFailCommand(4711L).retries(2).retryBackoff(Duration.ofSeconds(5)))
        .errorMessage(message.capture());
    assertTrue(
        message.getValue().contains("could not archive the order"),
        () -> "the incident an operator reads names what went wrong: "
            + message.getValue());

  }

  @Test
  @DisplayName("A job about a workflow this application does not own raises an incident which explains that")
  public void aJobOfAWorkflowWeDoNotOwnExplainsItselfInTheIncident() {

    run(
        Camunda8ListenerJobs.Failure.NO_RETRIES_LEFT,
        () -> {
          throw new DeliveryOfAnUnknownWorkflowException(
              "c8", "test-module", "TestProcess", "theExtensionsListener", "com.example.TheOrder", "42", "2251799813685249");
        });

    final var message = ArgumentCaptor.forClass(String.class);
    verify(jobClient.newFailCommand(4711L).retries(0)).errorMessage(message.capture());
    final var incident = message.getValue();

    // the reader is told what happened and where to look, and is not told that they deleted
    // a workflow aggregate - which nobody can know here
    assertTrue(
        incident.contains("This application was given a task of a workflow it does not own"),
        incident);
    assertTrue(incident.contains("workflow '2251799813685249'"), incident);
    assertTrue(incident.contains("Another application shares this BPMS and owns this workflow"), incident);
    assertFalse(incident.contains("must not be deleted while the workflow is active"), incident);

    // the type is part of the incident text, which is what tells this refusal from a defect
    // of the application at a glance
    assertTrue(incident.startsWith(DeliveryOfAnUnknownWorkflowException.class.getName()), incident);

  }

  @Test
  @DisplayName("A listener cut off by a shutdown keeps its lock instead of raising an incident")
  public void aListenerCutOffByAShutdownIsLeftToItsLock() {

    drain.beginShutdown();

    run(
        Camunda8ListenerJobs.Failure.NO_RETRIES_LEFT,
        () -> {
          throw new IllegalStateException("interrupted by the closing client");
        });

    verify(jobClient, never()).newFailCommand(anyLong());
    verify(jobClient, never()).newCompleteCommand(anyLong());

  }

  @Test
  @DisplayName("A job is deregistered from the drain whatever it ended with")
  public void aFinishedJobLeavesTheDrain() {

    run(
        new Camunda8ListenerJobs.Failure(2, null),
        () -> {
          throw new IllegalStateException("the extension could not archive the order");
        });

    assertTrue(
        drain.getInFlight().isEmpty(),
        "a job whose handler threw is gone from the drain, so a shutdown does not wait for it");

  }

}
