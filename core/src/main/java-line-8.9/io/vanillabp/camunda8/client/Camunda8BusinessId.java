package io.vanillabp.camunda8.client;

import io.camunda.client.api.command.CreateProcessInstanceCommandStep1;

/**
 * The business id an instance carries in Operate. This is the 8.9 variant, and this line can
 * write one at creation.
 * <p>
 * The 8.10 variant of this class describes what the id is for. This line does not have the
 * command which ASSIGNS a business id to an instance which is already running. So here the
 * id is written at creation and nowhere else. And the probe of {@link Camunda8InstanceProbe}
 * sends the modification and not the assignment.
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
