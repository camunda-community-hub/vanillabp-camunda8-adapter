package io.vanillabp.camunda8.client;

/**
 * The local cluster of the 8.9 line: where an adapter without a cluster address connects to,
 * and what the start says about it.
 * <p>
 * The addresses are the ones Camunda's docker compose for 8.9 publishes
 * (camunda/camunda-distributions, <code>docker-compose/versions/camunda-8.9</code>):
 * <code>"8080:8080"</code> for REST and <code>"26500:26500"</code> for gRPC. The host is
 * <code>localhost</code>, not the client's own default <code>0.0.0.0</code>, because
 * <code>localhost</code> also works on Windows. See decision 68 in the repository's DECISIONS.md.
 */
final class Camunda8LocalCluster {

  private Camunda8LocalCluster() {
  }

  /**
   * The REST address of the local cluster.
   */
  static final String REST_ADDRESS = "http://localhost:8080";

  /**
   * The gRPC address of the local cluster.
   */
  static final String GRPC_ADDRESS = "http://localhost:26500";

  /**
   * The sentence of the start's warning which says where the address comes from.
   */
  static final String WHERE_THE_ADDRESS_COMES_FROM = "This address matches Camunda's docker compose for 8.9.";

}
