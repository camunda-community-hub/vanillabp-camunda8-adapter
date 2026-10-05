package io.vanillabp.camunda8.client;

import io.camunda.client.CamundaClient;

/**
 * The command which asks the ENGINE whether it holds a process instance. This is the 8.9
 * variant.
 * <p>
 * The engine knows a new instance 16 to 19 ms after the create was sent, while the search the
 * election otherwise uses finds it after 167 to 1324 ms. Every command which asks the engine
 * addresses a key, so where VanillaBP holds one the question can be asked of the engine
 * instead of the index - but only to shorten a YES. An engine forgets an instance the moment
 * it ends, so a key it does not hold says nothing about whether the workflow completed or
 * never existed.
 * <p>
 * A process instance modification is the only one of the two candidates this line has. An
 * instance of this line carries a business id, written when it is created, but the command
 * which ASSIGNS one to an instance already running arrived with 8.10, so a caller of this line
 * never asks for it, see decision 35 in the repository's DECISIONS.md.
 * <p>
 * Public because the process service which asks lives in another package of this module. It
 * is not on the list of what an extension of the pipeline is told, so it stays the adapter's
 * own and may move with the next change.
 */
public final class Camunda8InstanceProbe {

  private Camunda8InstanceProbe() {
  }

  /**
   * Asks the engine about one instance. What the cluster answers is the whole result, so
   * this throws whatever the command threw and the caller reads the code.
   *
   * @param client The client of the adapter asking
   * @param processInstanceKey The instance to ask about
   * @param reservedElementId An element id no model of this adapter carries, which is what
   *          makes the modification a question rather than a change
   * @param businessId Ignored on this line, which cannot assign a business id to an instance
   *          which is already running
   */
  public static void askTheEngine(
      final CamundaClient client,
      final long processInstanceKey,
      final String reservedElementId,
      final String businessId) {

    client
        .newModifyProcessInstanceCommand(processInstanceKey)
        .activateElement(reservedElementId)
        .send()
        .join();

  }

}
