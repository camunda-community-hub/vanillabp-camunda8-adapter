package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.search.enums.ListenerEventType;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.WorkflowEnd;

/**
 * What the worker of a process' execution listeners reports, and what it refuses to report.
 * <p>
 * One worker answers two listeners now: the <code>end</code> of a completed instance and, on
 * the 8.10 line, the <code>cancel</code> of a terminated one. They carry the same job type, so
 * the event the job reports is the only thing which tells them apart - and an event this build
 * does not know is answered rather than guessed at, because the client's enum grows inside a
 * line. What the cancel job does is asserted in the per-line test of the 8.10 directory, which
 * is the only line whose client names that event.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8WorkflowEndedKindTest {

  @Test
  @DisplayName("An end listener reports a completed workflow, named by its instance key")
  public void anEndReportsACompletedWorkflow() {

    final var happened = Camunda8WorkflowEndedFixture.whatTheHandlerDoesWith(ListenerEventType.END);

    assertEquals(1, happened.reported().size(), "the end of a workflow is reported once");
    assertEquals(WorkflowEnd.Kind.COMPLETED, happened.reported().getFirst().getKind());
    assertEquals(
        Camunda8WorkflowEndedFixture.INSTANCE_KEY,
        happened.reported().getFirst().getWorkflowId(),
        "the key of THIS instance, which is what limits the core's derivation to it");
    assertEquals("agg-1", happened.reported().getFirst().getWorkflowAggregateId());
    assertTrue(happened.jobWasCompleted());

  }

  @Test
  @DisplayName("An event this build does not know completes the job and reports nothing")
  public void anUnknownEventReportsNothing() {

    final var happened = Camunda8WorkflowEndedFixture
        .whatTheHandlerDoesWith(ListenerEventType.UNKNOWN_ENUM_VALUE);

    assertTrue(
        happened.reported().isEmpty(),
        () -> "a job whose event this adapter does not serve tells the application nothing, "
            + "but reported: "
            + happened.reported());
    assertTrue(happened.jobWasCompleted(), "and the job is answered all the same");

  }

  @Test
  @DisplayName("A job reporting no event at all is answered the same way")
  public void aJobWithoutAnEventReportsNothing() {

    final var happened = Camunda8WorkflowEndedFixture.whatTheHandlerDoesWith(null);

    assertTrue(happened.reported().isEmpty());
    assertTrue(happened.jobWasCompleted());

  }

  @Test
  @DisplayName("An instance without the aggregate-ID variable reports nothing either")
  public void anInstanceWithoutTheVariableReportsNothing() {

    final var happened = Camunda8WorkflowEndedFixture
        .whatTheHandlerDoesWith(ListenerEventType.END, Map.of());

    assertTrue(happened.reported().isEmpty());
    assertTrue(happened.jobWasCompleted());

  }

}
