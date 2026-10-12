package io.vanillabp.camunda8.wiring;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.search.enums.JobKind;
import io.camunda.client.api.search.enums.ListenerEventType;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListener;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListenerEventType;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeExecutionListeners;

/**
 * How an instance of this release line says that it was canceled. This is the 8.10 variant.
 * <p>
 * 8.10 is the first line with a {@code cancel} execution listener. The cluster takes it on
 * the PROCESS element and nowhere else. Such a listener fires when an instance is terminated
 * through the API. It fires after every child element has terminated and before the
 * instance reaches its final state. So it reports that the instance is gone. On 8.8 and 8.9
 * nothing reports that at all, and the variants of this class for those lines say so.
 * <p>
 * Both halves live here, because both would fail to compile on an older line.
 * {@code ZeebeExecutionListenerEventType.cancel} writes the listener into the model.
 * {@code ListenerEventType.CANCEL} is what the job of such a listener reports.
 * <p>
 * The class is public, because an extension of the pipeline of this adapter writes the
 * same listener, with retries of its own. A second copy of the rule which decides what a
 * line can do would sooner or later be wrong in one of the two places (see decision 28 in
 * the repository's DECISIONS.md).
 */
public final class Camunda8CancelListeners {

  private Camunda8CancelListeners() {
  }

  /**
   * Whether an instance of this line can report its own cancelation.
   *
   * @return <code>true</code>: this line takes a <code>cancel</code> execution listener on the
   *         process element
   */
  public static boolean theProcessCanReportItsCancellation() {

    return true;

  }

  /**
   * Writes the {@code cancel} execution listener of a process into the model.
   *
   * @param listeners The execution listeners of the PROCESS element
   * @param jobType The job type the job of the listener carries
   * @param retries What the listener is modelled with, or <code>null</code> for the
   *          default of the cluster. The end listener next to it uses that default too
   */
  public static void addProcessCancelListener(
      final ZeebeExecutionListeners listeners,
      final String jobType,
      final String retries) {

    final var cancelListener = listeners
        .getModelInstance()
        .newInstance(ZeebeExecutionListener.class);
    cancelListener.setEventType(ZeebeExecutionListenerEventType.cancel);
    cancelListener.setType(jobType);
    if (retries != null) {
      cancelListener.setRetries(retries);
    }
    listeners.addChildElement(cancelListener);

  }

  /**
   * Reads a listener job which arrived and says whether it is the cancelation of the
   * instance rather than one of the other listener events.
   *
   * @param job The listener job which arrived
   * @return Whether it reports the cancelation of the instance it belongs to
   */
  public static boolean isCancellationOfTheProcess(
      final ActivatedJob job) {

    return (job.getKind() == JobKind.EXECUTION_LISTENER) && (job
        .getListenerEventType() == ListenerEventType.CANCEL);

  }

}
