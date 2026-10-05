package io.vanillabp.camunda8.client;

import java.util.Optional;

/**
 * The local cluster of the 8.10 line: where an adapter without a cluster address connects to,
 * and what the start says about it.
 * <p>
 * The addresses are the ones Camunda's docker compose for 8.10 publishes
 * (camunda/camunda-distributions, <code>docker-compose/versions/camunda-8.10</code>):
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
  static final String WHERE_THE_ADDRESS_COMES_FROM = "This address matches Camunda's docker compose for 8.10.";

  /**
   * The sentence of the start's warning which says that the application needs a port of its
   * own. Camunda's docker compose for 8.10 publishes REST on host port 8080, and 8080 is also
   * the default port of Spring Boot and of Quarkus. If the application runs on 8080 and no
   * cluster is up, the adapter talks to the application itself. So the warning names the key
   * which moves the application. That key depends on the platform, so the platform passes it in.
   *
   * @param applicationPortKey The key which sets the HTTP port of the application on its
   *          platform
   * @return The sentence
   */
  static Optional<String> whereTheApplicationRuns(
      final String applicationPortKey) {

    return Optional.of(
        "Camunda's docker compose for 8.10 takes host port 8080, so this application needs another port. Set '%s' to pick one."
            .formatted(applicationPortKey));

  }

}
