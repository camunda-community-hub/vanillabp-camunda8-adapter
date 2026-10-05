package io.vanillabp.camunda8.client;

import io.camunda.client.api.command.ActivateJobsCommandStep1;
import io.camunda.client.api.command.CompleteJobCommandStep1;
import io.camunda.client.api.command.FailJobCommandStep1;
import io.camunda.client.api.command.ThrowErrorCommandStep1;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobWorkerBuilderStep1;

/**
 * The lease of a job activation. This is the 8.10 variant, the first line whose client and
 * cluster have one.
 * <p>
 * A worker which activates with a lease gets a token back with the job, and the cluster
 * accepts a completion, a failure and a BPMN error of that job only from whoever carries the
 * current token. An activation which follows a lock that ran out supersedes the token before
 * it, so the run which finished LAST is the one the workflow continues with, and the older
 * run is told that somebody else holds the activation instead of silently overwriting the
 * newer values.
 * <p>
 * The update commands need no token at all, measured on 8.10.0-alpha5: an
 * <code>UpdateJobTimeout</code> of a leased job is accepted from a client which never
 * activated it. The probes of this adapter rest on that.
 * <p>
 * Leasing is a ratchet: once a job has been leased, a worker of the same job type which does
 * not lease never sees that job again, and no command removes a lease. Which is why an
 * application says whether it wants this, see decision 36 in the repository's DECISIONS.md.
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
   * @return <code>true</code>: this is the first line whose client and cluster have a lease
   */
  public static boolean supportedByThisLine() {

    return true;

  }

  /**
   * Opens this worker with a lease, so every job it activates carries a token.
   *
   * @param builder The worker builder
   * @return The same builder
   */
  public static JobWorkerBuilderStep1.JobWorkerBuilderStep3 leaseTheActivations(
      final JobWorkerBuilderStep1.JobWorkerBuilderStep3 builder) {

    return builder.withLease(true);

  }

  /**
   * Asks ONE activation for a lease, which is what a caller outside the workers needs: a
   * tool or a test reaching for a job of a job type this adapter leases has to lease too,
   * or the cluster never hands it that job.
   *
   * @param command The activation command
   * @return The same command
   */
  public static ActivateJobsCommandStep1.ActivateJobsCommandStep3 leaseTheActivation(
      final ActivateJobsCommandStep1.ActivateJobsCommandStep3 command) {

    return command.withLease(true);

  }

  /**
   * The token of an activation, which is what its answer has to carry.
   *
   * @param job The activated job
   * @return The token, or <code>null</code> where this activation was not leased
   */
  public static String tokenOf(
      final ActivatedJob job) {

    return job.getJobLeaseToken();

  }

  /**
   * Carries the token into the completion of a job, which the cluster takes from whoever holds
   * the current one.
   *
   * @param command The completion of a job
   * @param token What {@link #tokenOf(ActivatedJob)} answered for it
   * @return The same command, carrying the token where there is one
   */
  public static CompleteJobCommandStep1 withToken(
      final CompleteJobCommandStep1 command,
      final String token) {

    return token == null
        ? command
        : command.withJobLeaseToken(token);

  }

  /**
   * Carries the token into the failure of a job, which the cluster takes from whoever holds
   * the current one.
   *
   * @param command The failure of a job
   * @param token What {@link #tokenOf(ActivatedJob)} answered for it
   * @return The same command, carrying the token where there is one
   */
  public static FailJobCommandStep1.FailJobCommandStep2 withToken(
      final FailJobCommandStep1.FailJobCommandStep2 command,
      final String token) {

    return token == null
        ? command
        : command.withJobLeaseToken(token);

  }

  /**
   * Carries the token into the BPMN error of a job, which the cluster takes from whoever holds
   * the current one.
   *
   * @param command The BPMN error of a job
   * @param token What {@link #tokenOf(ActivatedJob)} answered for it
   * @return The same command, carrying the token where there is one
   */
  public static ThrowErrorCommandStep1.ThrowErrorCommandStep2 withToken(
      final ThrowErrorCommandStep1.ThrowErrorCommandStep2 command,
      final String token) {

    return token == null
        ? command
        : command.withJobLeaseToken(token);

  }

}
