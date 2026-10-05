package io.vanillabp.camunda8.wiring;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners;
import io.vanillabp.camunda8.Camunda8ReleaseLine;

/**
 * How an instance of this release line says that it was canceled. This is the 8.8 variant.
 * <p>
 * It does not say it at all. A {@code cancel} execution listener arrived with 8.10, and the
 * cluster takes it on the PROCESS element, so an instance of this line is terminated without
 * a word and the boot of a workflow module says that out loud rather than leaving it to be
 * found.
 * <p>
 * Both halves live here because both would fail to compile on this line:
 * {@code ZeebeExecutionListenerEventType.cancel} is what would write the listener into the
 * model, and {@code ListenerEventType.CANCEL} is what the job of such a listener would
 * report.
 * <p>
 * Public because an extension of this adapter's pipeline asks the same question about the
 * line it runs on (see decision 28 in the repository's DECISIONS.md).
 */
public final class Camunda8CancelListeners {

  private Camunda8CancelListeners() {
  }

  /**
   * Whether an instance of this line can report its own cancelation.
   *
   * @return <code>false</code>: the <code>cancel</code> execution listener arrived with 8.10
   */
  public static boolean theProcessCanReportItsCancellation() {

    return false;

  }

  /**
   * Never called on this line: the caller asks
   * {@link #theProcessCanReportItsCancellation()} first. It is here so the shared code has
   * one shape on every line, and it throws rather than doing nothing, because a silent no-op
   * would turn a wiring mistake into a cancelation nobody ever hears.
   *
   * @param listeners The execution listeners of the PROCESS element
   * @param jobType The job type the listener's job would carry
   * @param retries What the listener would be modelled with
   */
  public static void addProcessCancelListener(
      final ZeebeExecutionListeners listeners,
      final String jobType,
      final String retries) {

    throw new IllegalStateException(
        ("Camunda8: a cancel execution listener for job type '%s' cannot be written on release line %s! "
            + "The construct arrived with 8.10. Ask Camunda8CancelListeners whether this line has it "
            + "before writing one.")
            .formatted(jobType, Camunda8ReleaseLine.id()));

  }

  /**
   * Reads a listener job which arrived and says whether it is the cancelation of the
   * instance rather than one of the other listener events.
   *
   * @param job The listener job which arrived
   * @return Whether it reports the cancelation of the instance it belongs to, which no job
   *         of this line does
   */
  public static boolean isCancellationOfTheProcess(
      final ActivatedJob job) {

    return false;

  }

}
