package io.vanillabp.camunda8.client;

import io.camunda.client.CamundaClient;

/**
 * The command which asks the ENGINE whether it holds a process instance. This is the 8.9
 * variant.
 * <p>
 * The engine knows a new instance 16 to 19 ms after the create was sent. The search which
 * the election uses otherwise finds it after 167 to 1324 ms. Every command which asks the
 * engine addresses a key. So where VanillaBP holds a key, the question can go to the engine
 * and not to the index. But this only makes a YES come sooner. An engine forgets an instance
 * the moment it ends. So a key it does not hold says nothing about whether the workflow
 * completed or never existed.
 * <p>
 * Of the two possible commands, this line has only the process instance modification. An
 * instance of this line carries a business id, written when it is created. But the command
 * which ASSIGNS one to a running instance came with 8.10. So a caller of this line never
 * asks for it, see decision 35 in the repository's DECISIONS.md.
 * <p>
 * It is public because the process service which asks lives in another package of this
 * module. It is not on the list of what an extension of the pipeline is told. So it stays the
 * adapter's own, and it may move with the next change.
 */
public final class Camunda8InstanceProbe {

  private Camunda8InstanceProbe() {
  }

  /**
   * Asks the engine about one instance. What the cluster answers is the whole result. So
   * this throws whatever the command threw, and the caller reads the code.
   *
   * @param client The client of the adapter asking
   * @param processInstanceKey The instance to ask about
   * @param reservedElementId An element id which no model of this adapter carries. That
   *          makes the modification a question and not a change
   * @param businessId Ignored on this line, because it cannot assign a business id to an
   *          instance which is already running
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
