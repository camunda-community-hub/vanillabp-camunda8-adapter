package io.vanillabp.camunda8.client;

import io.camunda.client.api.command.CreateProcessInstanceCommandStep1;

/**
 * The business id an instance carries in Operate. This is the 8.9 variant, and this line can
 * write one at creation.
 * <p>
 * What the id is for is described on the 8.10 variant of this class. What this line does not
 * have is the command which ASSIGNS a business id to an instance which is already running, so
 * the id is written at creation here and nowhere else, and the probe of
 * {@link Camunda8InstanceProbe} sends the modification instead of the assignment.
 * <p>
 * Public because the process service which starts a workflow lives in another package of this
 * module. It is not on the list of what an extension of the pipeline is told, so it stays the
 * adapter's own and may move with the next change.
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
