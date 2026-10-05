package io.vanillabp.camunda8.client;

/**
 * What the start says about the local cluster an adapter without a REST address talks to.
 * This is the 8.8 variant.
 * <p>
 * Camunda's docker compose for 8.8 (camunda/camunda-distributions,
 * <code>docker-compose/versions/camunda-8.8</code>) publishes the REST port of the cluster as
 * <code>8088:8080</code>, while the client's default REST address uses port 8080. A cluster
 * started with that file therefore answers nothing at the default address, and the start
 * says so. From 8.9 on the same file publishes <code>8080:8080</code>.
 */
final class Camunda8LocalCluster {

  private Camunda8LocalCluster() {
  }

  /**
   * One more sentence for the warning about the client's default REST address.
   *
   * @param restAddressKey The full key of the REST address of the adapter id
   * @return The sentence, never <code>null</code> on this line
   */
  static String aboutTheDefaultRestAddress(
      final String restAddressKey) {

    return """
        Camunda's docker compose for 8.8 publishes REST on port 8088, not 8080. If you started \
        your cluster with it, set '%s' to 'http://localhost:8088'."""
        .formatted(restAddressKey);

  }

}
