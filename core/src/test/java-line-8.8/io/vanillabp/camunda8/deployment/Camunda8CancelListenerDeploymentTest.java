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
 * What the deployment writes onto the process element of release line <code>8.8</code>, whose
 * cluster has no <code>cancel</code> execution listener: the end listener and nothing else.
 * <p>
 * What the application gives up by running on this line is not left to be found. The boot
 * names every BPMN process whose cancelation would be reported on a newer line, which is what
 * the last test here holds.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CancelListenerDeploymentTest {

  private static final String JOB_TYPE = Camunda8TaskWiring.workflowEndedJobTypeOf("Loans");

  @Test
  @DisplayName("A process whose end is reported gets the end listener and nothing else")
  public void aProcessWhoseEndIsReportedGetsTheEndListener() {

    final var wired = Camunda8CancelDeploymentFixture
        .wire(Camunda8CancelDeploymentFixture.A_SERVED_PROCESS, true, true);

    assertEquals(
        List.of("end:"
            + JOB_TYPE),
        wired.whatTheProcessCarries("Loans"));
    assertEquals(List.of("Loans"), wired.context().getWorkflowEndedProcessesToWire());

  }

  @Test
  @DisplayName("A process nobody wants the end of gets no listener at all")
  public void aProcessWithTasksAloneGetsNothing() {

    final var wired = Camunda8CancelDeploymentFixture
        .wire(Camunda8CancelDeploymentFixture.A_SERVED_PROCESS, false, true);

    assertEquals(
        List.of(),
        wired.whatTheProcessCarries("Loans"),
        "the widening is about the cancel listener, which this line does not have");
    assertTrue(wired.context().getWorkflowEndedProcessesToWire().isEmpty());

  }

  @Test
  @DisplayName("The boot names the process whose cancelation this line cannot report")
  public void theBootNamesTheGap() {

    final var wired = Camunda8CancelDeploymentFixture
        .wire(Camunda8CancelDeploymentFixture.A_SERVED_PROCESS, false, true);

    assertEquals(
        List.of("Loans"),
        Camunda8CancelDeploymentFixture.whatTheBootWouldSay(wired),
        "a workflow terminated through the API leaves the tasks the application believes are "
            + "open in it open forever, and nothing else in the running system says so");

  }

  @Test
  @DisplayName("A process this application serves no task of is not named either")
  public void aProcessNobodyServesIsNotNamed() {

    final var wired = Camunda8CancelDeploymentFixture
        .wire(Camunda8CancelDeploymentFixture.A_SERVED_PROCESS, false, false);

    assertTrue(Camunda8CancelDeploymentFixture.whatTheBootWouldSay(wired).isEmpty());

  }

}
