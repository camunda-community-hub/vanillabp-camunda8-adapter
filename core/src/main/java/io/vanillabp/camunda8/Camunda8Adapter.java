package io.vanillabp.camunda8;

/**
 * Constants shared by all modules of the Camunda 8 adapter.
 */
public final class Camunda8Adapter {

  /**
   * The adapter type of this adapter. An application sets it per adapter id with
   * {@code vanillabp.adapters.<id>.type=camunda8}. The adapter announces it to the
   * VanillaBP platform integrations.
   */
  public static final String ADAPTER_TYPE = "camunda8";

  private Camunda8Adapter() {
    // constants holder
  }

}
