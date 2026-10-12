package io.vanillabp.camunda8.client;

import io.camunda.client.api.command.CreateProcessInstanceCommandStep1;

/**
 * The business id an instance carries in Operate. This is the 8.10 variant.
 * <p>
 * Camunda 7 keeps the id of the workflow aggregate in its business key. Camunda 8 got a
 * field of the same kind. This adapter writes the id of the aggregate into it where the
 * application asks for that. The reason is a human one. Operate shows the business id
 * where a reader looks first, and a variable is two clicks further away. VanillaBP itself
 * never reads the value back. The id of the aggregate travels as a process variable at the
 * start, and every other part of this adapter keeps reading that variable. Why, and what an
 * application gives up by switching the key on, is decision 37 in the repository's
 * DECISIONS.md.
 * <p>
 * The field is written at creation and never afterwards. The command which assigns one to a
 * running instance exists on this line. {@link Camunda8InstanceProbe} sends it, but as a
 * QUESTION, because an instance which carries this adapter's id refuses it. An id can be
 * assigned only once, and this cannot be undone. So a workflow which somebody else started
 * keeps whatever business id it has.
 * <p>
 * It is public because the process service which starts a workflow lives in another package
 * of this module. It is not on the list of what an extension of the pipeline is told. So it
 * stays the adapter's own, and it may move with the next change.
 */
public final class Camunda8BusinessId {

  private Camunda8BusinessId() {
  }

  /**
   * Whether an instance of this release line can carry a business id at all.
   *
   * @return <code>true</code>: this line writes the field when the instance is created
   */
  public static boolean supportedByThisLine() {

    return true;

  }

  /**
   * Writes the business id into the create command.
   *
   * @param command The create command
   * @param businessId What the instance is to carry, or <code>null</code> to write nothing
   * @return The command to send
   */
  public static CreateProcessInstanceCommandStep1.CreateProcessInstanceCommandStep3 writeTo(
      final CreateProcessInstanceCommandStep1.CreateProcessInstanceCommandStep3 command,
      final String businessId) {

    return businessId == null
        ? command
        : command.businessId(businessId);

  }

}
