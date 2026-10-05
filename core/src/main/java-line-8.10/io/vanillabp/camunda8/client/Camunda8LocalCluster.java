package io.vanillabp.camunda8.client;

/**
 * What the start says about the local cluster an adapter without a REST address talks to.
 * This is the 8.10 variant, and there is nothing to add here: Camunda's docker compose for
 * this minor publishes the REST port as <code>8080:8080</code>, which is the client's
 * default. The 8.8 variant explains why that line says more.
 */
final class Camunda8LocalCluster {

  private Camunda8LocalCluster() {
  }

  /**
   * One more sentence for the warning about the client's default REST address.
   *
   * @param restAddressKey The full key of the REST address of the adapter id
   * @return <code>null</code>: nothing to add on this line
   */
  static String aboutTheDefaultRestAddress(
      final String restAddressKey) {

    return null;

  }

}
