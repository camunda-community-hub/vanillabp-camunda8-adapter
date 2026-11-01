package io.vanillabp.camunda8.client;

import io.camunda.client.CamundaClient;

/**
 * Whether a process instance is suspended. This is the 8.9 variant. The clusters of this line
 * cannot suspend an instance, and the client of this line knows no such state. So nothing is
 * asked, and the answer is always "no".
 * <p>
 * An application of this line which runs against an 8.10 cluster may still meet a suspended
 * instance. It then behaves as before this class existed: a refusal of the cluster is read the
 * way the 8.10 variant reads it for an instance which is not suspended.
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
   * @return <code>false</code>: the clusters of this line cannot
   */
  public static boolean theClusterCanSuspend() {

    return false;

  }

  /**
   * Never asks the cluster, because a cluster of this line cannot suspend.
   *
   * @param client Not used
   * @param processInstanceKey Not used
   * @return <code>false</code>
   */
  public static boolean isSuspended(
      final CamundaClient client,
      final long processInstanceKey) {

    return false;

  }

}
