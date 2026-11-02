package io.vanillabp.camunda8.client;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ProcessInstanceState;

/**
 * Whether a process instance is suspended. This is the 8.10 variant. A cluster of this line can
 * suspend an instance, over REST and in Operate.
 * <p>
 * While an instance is suspended, the cluster refuses every command about one of its jobs or user
 * tasks with HTTP <code>409</code> (on gRPC <code>FAILED_PRECONDITION</code>). That code alone
 * does not say "suspended", because the cluster gives it for other states as well. So the adapter
 * asks this class, and only after such a refusal. The state is a value of the cluster, not its
 * words, so decision 16 in the repository's DECISIONS.md holds.
 * <p>
 * The answer comes from the query API and runs behind the engine. Measured against
 * <code>camunda/camunda:8.10.7</code> on 2026-11-01: the instance showed <code>SUSPENDED</code>
 * 129 to 530 ms after the suspension, and <code>ACTIVE</code> 216 to 438 ms after the resume.
 * <p>
 * It is public because the process service which asks lives in another package of this module.
 * It is not on the list of what an extension of the pipeline is told. So it stays the adapter's
 * own, and it may move with the next change.
 */
public final class Camunda8Suspension {

  private Camunda8Suspension() {
  }

  /**
   * Whether a cluster of this line can suspend a process instance at all.
   *
   * @return <code>true</code>: the clusters of this line can
   */
  public static boolean theClusterCanSuspend() {

    return true;

  }

  /**
   * Asks the query API whether the instance is suspended right now. It throws whatever the
   * request threw, and the caller decides what an unanswered question means.
   *
   * @param client The client of the adapter asking
   * @param processInstanceKey The instance to ask about
   * @return Whether the instance is suspended
   */
  public static boolean isSuspended(
      final CamundaClient client,
      final long processInstanceKey) {

    return client
        .newProcessInstanceGetRequest(processInstanceKey)
        .send()
        .join()
        .getState() == ProcessInstanceState.SUSPENDED;

  }

}
