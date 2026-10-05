package io.vanillabp.camunda8;

/**
 * Constants shared by all modules of the Camunda 8 adapter.
 */
public final class Camunda8Adapter {

  /**
   * The adapter type of this adapter. Configured per adapter id via
   * {@code vanillabp.adapters.<id>.type=camunda8} and announced to the
   * VanillaBP platform integrations.
   */
  public static final String ADAPTER_TYPE = "camunda8";

  private Camunda8Adapter() {
    // constants holder
  }

}
