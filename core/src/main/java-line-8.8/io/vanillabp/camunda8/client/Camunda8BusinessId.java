package io.vanillabp.camunda8.client;

import io.camunda.client.api.command.CreateProcessInstanceCommandStep1;

/**
 * The business id an instance carries in Operate. This is the 8.8 variant, and this line has
 * none.
 * <p>
 * Neither the client nor the cluster of this line knows the field: the word
 * <code>businessId</code> appears nowhere in the create command of this client, and the 8.8
 * cluster answers a request carrying it with <code>400</code>, "Request property [businessId]
 * cannot be parsed". So the key is accepted here and nothing is sent, and the boot says so in
 * one line - an application moves between lines with one configuration, and refusing the key
 * would break exactly that.
 * <p>
 * What the id is for is described on the 8.10 variant of this class.
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
   * @return <code>false</code>: neither this client nor an 8.8 cluster knows the field
   */
  public static boolean supportedByThisLine() {

    return false;

  }

  /**
   * Would write the business id into the create command, which this line cannot do.
   *
   * @param command The create command
   * @param businessId What would be written, ignored here
   * @return The same command, unchanged
   */
  public static CreateProcessInstanceCommandStep1.CreateProcessInstanceCommandStep3 writeTo(
      final CreateProcessInstanceCommandStep1.CreateProcessInstanceCommandStep3 command,
      final String businessId) {

    return command;

  }

}
