package io.vanillabp.camunda8.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda8.wiring.Camunda8TaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the deployment writes onto the process element of release line <code>8.10</code>,
 * which is the first line whose cluster takes a <code>cancel</code> execution listener.
 * <p>
 * The two things which decide it are tied together on purpose: the listener holds the
 * instance until its job is answered, so it is written only where this adapter also opens the
 * worker which answers it. A model carrying a listener nobody serves turns a cancelation into
 * a workflow which never goes away and which raises no incident either.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CancelListenerDeploymentTest {

  private static final String JOB_TYPE = Camunda8TaskWiring.workflowEndedJobTypeOf("Loans");

  @Test
  @DisplayName("A process whose end is reported gets both listeners and one worker")
  public void aProcessWhoseEndIsReportedGetsBoth() {

    final var wired = Camunda8CancelDeploymentFixture
        .wire(Camunda8CancelDeploymentFixture.A_SERVED_PROCESS, true, true);

    assertEquals(
        List.of("end:"
            + JOB_TYPE,
            "cancel:"
                + JOB_TYPE),
        wired.whatTheProcessCarries("Loans"));
    assertEquals(
        List.of("Loans"),
        wired.context().getWorkflowEndedProcessesToWire(),
        "one worker answers both listeners, so the process is listed once");

  }

  @Test
  @DisplayName("A process nobody wants the end of still gets the cancel listener")
  public void aProcessWithTasksGetsTheCancelListenerAlone() {

    // the widening this story is about: the core reads the tasks it still believes are
    // open in a canceled instance and reports each of them as canceled, and it needs the
    // notification to do that even where no @WorkflowEnded method exists
    final var wired = Camunda8CancelDeploymentFixture
        .wire(Camunda8CancelDeploymentFixture.A_SERVED_PROCESS, false, true);

    assertEquals(
        List.of("cancel:"
            + JOB_TYPE),
        wired.whatTheProcessCarries("Loans"),
        "the end of a completed instance is still reported to nobody, so no end listener");
    assertEquals(
        List.of("Loans"),
        wired.context().getWorkflowEndedProcessesToWire(),
        "and the worker which answers the cancel job is opened");

  }

  @Test
  @DisplayName("A process this application serves no task of gets neither")
  public void aProcessNobodyServesGetsNeither() {

    final var wired = Camunda8CancelDeploymentFixture
        .wire(Camunda8CancelDeploymentFixture.A_SERVED_PROCESS, false, false);

    assertEquals(List.of(), wired.whatTheProcessCarries("Loans"));
    assertTrue(wired.context().getWorkflowEndedProcessesToWire().isEmpty());

  }

  @Test
  @DisplayName("This line says nothing about a gap it does not have")
  public void thisLineNamesNoGap() {

    final var wired = Camunda8CancelDeploymentFixture
        .wire(Camunda8CancelDeploymentFixture.A_SERVED_PROCESS, true, true);

    assertTrue(
        Camunda8CancelDeploymentFixture.whatTheBootWouldSay(wired).isEmpty(),
        "the boot names the processes whose cancelation is not reported, and here it is");

  }

}
