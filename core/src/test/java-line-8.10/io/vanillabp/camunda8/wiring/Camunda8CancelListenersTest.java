package io.vanillabp.camunda8.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.camunda.client.api.search.enums.JobKind;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.WorkflowEnd;

/**
 * What release line <code>8.10</code> can say about an instance which was canceled: it has the
 * <code>cancel</code> execution listener of the process element, so it can say it.
 * <p>
 * The siblings of this test in the directories of the older lines assert the opposite, which is
 * the whole reason the three live per line.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda8CancelListenersTest {

  @Test
  @DisplayName("This line reports the cancelation of an instance")
  public void thisLineCanSayIt() {

    assertTrue(Camunda8CancelListeners.theProcessCanReportItsCancellation());

  }

  @Test
  @DisplayName("The listener is written on the process element, with the job type of its end")
  public void theListenerIsWrittenOntoTheProcess() {

    final var model = Camunda8CancelListenersFixture.aProcess();

    assertTrue(
        Camunda8TaskWiring.attachWorkflowCanceledListener(model, Camunda8CancelListenersFixture.PROCESS));

    assertEquals(
        List.of("cancel:"
            + Camunda8CancelListenersFixture.JOB_TYPE),
        Camunda8CancelListenersFixture.whatTheProcessCarries(model),
        "one listener, waiting for the cancelation, carrying the job type one worker answers");

  }

  @Test
  @DisplayName("The end listener and the cancel listener stand next to each other")
  public void bothListenersStandNextToEachOther() {

    final var model = Camunda8CancelListenersFixture.aProcess();

    Camunda8TaskWiring.attachWorkflowEndedListener(model, Camunda8CancelListenersFixture.PROCESS);
    Camunda8TaskWiring.attachWorkflowCanceledListener(model, Camunda8CancelListenersFixture.PROCESS);

    assertEquals(
        List.of("end:"
            + Camunda8CancelListenersFixture.JOB_TYPE,
            "cancel:"
                + Camunda8CancelListenersFixture.JOB_TYPE),
        Camunda8CancelListenersFixture.whatTheProcessCarries(model),
        "the same job type twice, told apart by the event the job reports");

  }

  @Test
  @DisplayName("Wiring a model a second time writes no second listener")
  public void aModelWiredTwiceCarriesOneOfEach() {

    final var model = Camunda8CancelListenersFixture.aProcess();

    Camunda8TaskWiring.attachWorkflowEndedListener(model, Camunda8CancelListenersFixture.PROCESS);
    Camunda8TaskWiring.attachWorkflowCanceledListener(model, Camunda8CancelListenersFixture.PROCESS);
    Camunda8TaskWiring.attachWorkflowEndedListener(model, Camunda8CancelListenersFixture.PROCESS);
    Camunda8TaskWiring.attachWorkflowCanceledListener(model, Camunda8CancelListenersFixture.PROCESS);

    assertEquals(
        2,
        Camunda8CancelListenersFixture.whatTheProcessCarries(model).size(),
        "a model which was wired before is left as it is");

  }

  @Test
  @DisplayName("A cancel listener job reports a canceled workflow, named by its instance key")
  public void aCancelReportsACanceledWorkflow() {

    final var happened = Camunda8WorkflowEndedFixture
        .whatTheHandlerDoesWith(ListenerEventType.CANCEL);

    assertEquals(1, happened.reported().size(), "an instance canceled through the API reports its end");
    assertEquals(
        WorkflowEnd.Kind.CANCELED,
        happened.reported().getFirst().getKind(),
        "and it reports it as the cancelation it is");
    assertEquals(
        Camunda8WorkflowEndedFixture.INSTANCE_KEY,
        happened.reported().getFirst().getWorkflowId(),
        "the key of THIS instance - a called process whose parent was canceled gets a job of "
            + "its own, and the root of the call tree is never what is reported here");
    assertTrue(happened.jobWasCompleted());

  }

  @Test
  @DisplayName("A cancel execution-listener job is recognised, and nothing else is")
  public void theJobOfSuchAListenerIsRecognised() {

    assertTrue(
        Camunda8CancelListeners
            .isCancellationOfTheProcess(
                Camunda8CancelListenersFixture.aJobReporting(JobKind.EXECUTION_LISTENER, "CANCEL")));
    // the end of an instance is the other job of the same worker
    assertFalse(
        Camunda8CancelListeners
            .isCancellationOfTheProcess(
                Camunda8CancelListenersFixture.aJobReporting(JobKind.EXECUTION_LISTENER, "END")));
    // a TASK listener reporting a canceling user task is a different question entirely
    assertFalse(
        Camunda8CancelListeners
            .isCancellationOfTheProcess(
                Camunda8CancelListenersFixture.aJobReporting(JobKind.TASK_LISTENER, "CANCELING")));
    // and an event this build does not know is never read as one it does
    assertFalse(
        Camunda8CancelListeners
            .isCancellationOfTheProcess(
                Camunda8CancelListenersFixture
                    .aJobReporting(JobKind.EXECUTION_LISTENER, "UNKNOWN_ENUM_VALUE")));

  }

}
