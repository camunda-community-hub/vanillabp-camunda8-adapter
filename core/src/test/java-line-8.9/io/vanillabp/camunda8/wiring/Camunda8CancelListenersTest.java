package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.search.enums.JobKind;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What release line <code>8.9</code> can say about an instance which was canceled: nothing. The
 * {@code cancel} execution listener of the process element arrived with 8.10.
 * <p>
 * The sibling of this test in the directory of that line asserts the opposite, which is the
 * whole reason the three live per line.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CancelListenersTest {

  @Test
  @DisplayName("This line reports no cancelation")
  public void thisLineCannotSayIt() {

    assertFalse(Camunda8CancelListeners.theProcessCanReportItsCancellation());

  }

  @Test
  @DisplayName("Writing the listener anyway is refused rather than quietly skipped")
  public void writingItIsRefused() {

    final var model = Camunda8CancelListenersFixture.aProcess();

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> Camunda8TaskWiring
            .attachWorkflowCanceledListener(model, Camunda8CancelListenersFixture.PROCESS));

    assertTrue(
        refused.getMessage().contains("8.10"),
        () -> "the refusal says which line has the construct, but said: "
            + refused.getMessage());
    assertEquals(
        List.of(),
        Camunda8CancelListenersFixture.whatTheProcessCarries(model),
        "and nothing was written into the model");

  }

  @Test
  @DisplayName("The end listener is written the way it always was")
  public void theEndListenerIsUntouched() {

    final var model = Camunda8CancelListenersFixture.aProcess();

    assertTrue(
        Camunda8TaskWiring.attachWorkflowEndedListener(model, Camunda8CancelListenersFixture.PROCESS));

    assertEquals(
        List.of("end:"
            + Camunda8CancelListenersFixture.JOB_TYPE),
        Camunda8CancelListenersFixture.whatTheProcessCarries(model));

  }

  @Test
  @DisplayName("No job of this line reports the cancelation of an instance")
  public void noJobOfThisLineReportsIt() {

    assertFalse(
        Camunda8CancelListeners
            .isCancellationOfTheProcess(
                Camunda8CancelListenersFixture.aJobReporting(JobKind.EXECUTION_LISTENER, "END")));
    assertFalse(
        Camunda8CancelListeners
            .isCancellationOfTheProcess(
                Camunda8CancelListenersFixture
                    .aJobReporting(JobKind.EXECUTION_LISTENER, "UNKNOWN_ENUM_VALUE")));

  }

}
