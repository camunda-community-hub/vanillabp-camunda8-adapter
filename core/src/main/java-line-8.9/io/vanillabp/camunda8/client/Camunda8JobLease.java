package io.vanillabp.camunda8.client;

import io.camunda.client.api.command.ActivateJobsCommandStep1;
import io.camunda.client.api.command.CompleteJobCommandStep1;
import io.camunda.client.api.command.FailJobCommandStep1;
import io.camunda.client.api.command.ThrowErrorCommandStep1;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobWorkerBuilderStep1;

/**
 * The lease of a job activation. This is the 8.9 variant, and this line has none: the word
 * <code>lease</code> appears nowhere in <code>io.camunda.client.api</code> of the client
 * this line is compiled against, so every method here does nothing and says so.
 * <p>
 * What a lease buys is described on the 8.10 variant of this class. An application on this
 * line answers a job the way it always did: the cluster takes the completion of whoever
 * sends one, so when a lock expires while the business method still runs, the run which
 * finished FIRST is the one the workflow continues with.
 * <p>
 * The configuration key is accepted here and ignored, and the boot says so in one line. An
 * application which moves between lines carries one configuration, and refusing the key
 * would break exactly that.
 * <p>
 * Public because the workers are opened in one package of this module and the answers are
 * sent from another. It is not on the list of what an extension of the pipeline is told, so
 * it stays the adapter's own and may move with the next change.
 */
public final class Camunda8JobLease {

  private Camunda8JobLease() {
  }

  /**
   * Whether the client of this release line can lease an activation at all.
   *
   * @return <code>false</code>: the client this line is compiled against has no lease
   */
  public static boolean supportedByThisLine() {

    return false;

  }

  /**
   * Would open this worker with a lease, which this line cannot do.
   *
   * @param builder The worker builder
   * @return The same builder, unchanged
   */
  public static JobWorkerBuilderStep1.JobWorkerBuilderStep3 leaseTheActivations(
      final JobWorkerBuilderStep1.JobWorkerBuilderStep3 builder) {

    return builder;

  }

  /**
   * Would ask ONE activation for a lease, which this line cannot do.
   *
   * @param command The activation command
   * @return The same command, unchanged
   */
  public static ActivateJobsCommandStep1.ActivateJobsCommandStep3 leaseTheActivation(
      final ActivateJobsCommandStep1.ActivateJobsCommandStep3 command) {

    return command;

  }

  /**
   * The token of an activation, which on this line is never one.
   *
   * @param job The activated job
   * @return <code>null</code>, always
   */
  public static String tokenOf(
      final ActivatedJob job) {

    return null;

  }

  /**
   * Would carry the token into the completion of a job, which this line cannot do.
   *
   * @param command The completion of a job
   * @param token What {@link #tokenOf(ActivatedJob)} answered for it
   * @return The same command, unchanged
   */
  public static CompleteJobCommandStep1 withToken(
      final CompleteJobCommandStep1 command,
      final String token) {

    return command;

  }

  /**
   * Would carry the token into the failure of a job, which this line cannot do.
   *
   * @param command The failure of a job
   * @param token What {@link #tokenOf(ActivatedJob)} answered for it
   * @return The same command, unchanged
   */
  public static FailJobCommandStep1.FailJobCommandStep2 withToken(
      final FailJobCommandStep1.FailJobCommandStep2 command,
      final String token) {

    return command;

  }

  /**
   * Would carry the token into the BPMN error of a job, which this line cannot do.
   *
   * @param command The BPMN error of a job
   * @param token What {@link #tokenOf(ActivatedJob)} answered for it
   * @return The same command, unchanged
   */
  public static ThrowErrorCommandStep1.ThrowErrorCommandStep2 withToken(
      final ThrowErrorCommandStep1.ThrowErrorCommandStep2 command,
      final String token) {

    return command;

  }

}
