package io.vanillabp.camunda8.client;

import io.camunda.client.CamundaClient;

/**
 * The command which asks the ENGINE whether it holds a process instance. This is the 8.10
 * variant.
 * <p>
 * The engine knows a new instance 16 to 19 ms after the create was sent, while the search the
 * election otherwise uses finds it after 167 to 1324 ms. Every command which asks the engine
 * addresses a key, so where VanillaBP holds one the question can be asked of the engine
 * instead of the index - but only to shorten a YES. An engine forgets an instance the moment
 * it ends, so a key it does not hold says nothing about whether the workflow completed or
 * never existed.
 * <p>
 * Two commands can carry the question and this line has both. Which of the two is sent
 * follows the business id of the workflow, see decision 35 in the repository's DECISIONS.md.
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
   * @param businessId The id to assign where the business id is ours to write, which is the
   *          value this adapter would have written in the first place, or <code>null</code>
   *          where the modification is the command to send
   */
  public static void askTheEngine(
      final CamundaClient client,
      final long processInstanceKey,
      final String reservedElementId,
      final String businessId) {

    if (businessId != null) {
      // the instance already carries this adapter's business id, so the assignment is
      // refused with 409 and writes nothing at all. Where it is accepted, nothing was
      // there and the value written is the one this adapter writes anyway
      client
          .newAssignProcessInstanceBusinessIdCommand(processInstanceKey)
          .businessId(businessId)
          .send()
          .join();
      return;
    }
    client
        .newModifyProcessInstanceCommand(processInstanceKey)
        .activateElement(reservedElementId)
        .send()
        .join();

  }

}
