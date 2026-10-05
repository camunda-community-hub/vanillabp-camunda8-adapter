package io.vanillabp.camunda8.client;

import java.util.Optional;

/**
 * The local cluster of the 8.8 line: where an adapter without a cluster address connects to,
 * and what the start says about it.
 * <p>
 * The addresses are the ones Camunda's docker compose for 8.8 publishes
 * (camunda/camunda-distributions, <code>docker-compose/versions/camunda-8.8</code>):
 * <code>"8088:8080"</code> for REST and <code>"26500:26500"</code> for gRPC. So on this line the REST port is 8088, while 8.9 and 8.10 publish 8080. The host is
 * <code>localhost</code>, not the client's own default <code>0.0.0.0</code>, because
 * <code>localhost</code> also works on Windows. See decision 68 in the repository's DECISIONS.md.
 */
final class Camunda8LocalCluster {

  private Camunda8LocalCluster() {
  }

  /**
   * The REST address of the local cluster.
   */
  static final String REST_ADDRESS = "http://localhost:8088";

  /**
   * The gRPC address of the local cluster.
   */
  static final String GRPC_ADDRESS = "http://localhost:26500";

  /**
   * The sentence of the start's warning which says where the address comes from.
   */
  static final String WHERE_THE_ADDRESS_COMES_FROM = "This address matches Camunda's docker compose for 8.8.";

  /**
   * The sentence of the start's warning which says that the application needs a port of its
   * own. There is none on this line: Camunda's docker compose for 8.8 publishes REST on host
   * port 8088, which is the default port of neither Spring Boot nor Quarkus.
   *
   * @param applicationPortKey The key which sets the HTTP port of the application on its
   *          platform
   * @return Nothing
   */
  static Optional<String> whereTheApplicationRuns(
      final String applicationPortKey) {

    return Optional.empty();

  }

}
