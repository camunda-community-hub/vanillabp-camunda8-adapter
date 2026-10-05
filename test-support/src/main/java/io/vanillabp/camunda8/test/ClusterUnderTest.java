package io.vanillabp.camunda8.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.utility.DockerImageName;

/**
 * The Camunda 8 cluster an integration test runs against, in the flavours the tests of this
 * adapter and of its extensions need.
 * <p>
 * The image is not written into a test. It is filtered into
 * {@code camunda8-cluster.properties} at build time from the Camunda client the active
 * release line pins ({@code camunda8.version}), so activating another line moves the client
 * and the cluster together. That is what makes the supported cluster versions of the README
 * provable instead of claimed: a line's tests meet the oldest cluster its artifacts accept.
 * Override for a single run with {@code mvn verify -Dcamunda8.cluster.image=...}.
 * <p>
 * The image is {@code camunda/camunda}, the orchestration cluster of Camunda 8, and not the
 * older {@code camunda/zeebe}: the latter received no tags beyond 8.9.11 and none at all for
 * 8.10, so a per-line matrix cannot be built on it.
 * <p>
 * Every cluster here brings secondary storage, because the adapter serves no other kind -
 * except {@link #clusterWhichRefusesSearches()}, which exists to prove that. WHERE that
 * storage lives is the second value the same properties file carries, see
 * {@code camunda8.cluster.secondary-storage} of the parent POM, and it belongs to the
 * release line as much as the image does: a line whose cluster can keep the storage in a
 * database of its own process starts one container, an older line starts an Elasticsearch
 * beside it. A test class sees neither, it asks for a cluster and gets one - see decision 22
 * in the repository's DECISIONS.md.
 * <p>
 * How many clusters a run starts is the second thing a test does not decide alone.
 * {@link #cluster()} hands out a container of its own, which a class declares as a
 * {@code @Container} field and Testcontainers stops again when the class is done;
 * {@link #sharedCluster()} hands every class of a module the same one. The second is what a
 * module takes unless a test needs a cluster nobody else may touch.
 * <p>
 * <b>Why this is a published artifact.</b> Four modules carried a copy of this class and of
 * {@link ClusterLog}, and a fifth one grew in the Business Cockpit's Camunda 8 extension.
 * They had drifted: different startup timeouts, different messages, one of them without a
 * log writer at all. A test classpath cannot read another module's test classes, so the only
 * way to have one copy is a module of its own - which is why the classes sit in
 * {@code src/main/java} although nothing but a test ever calls them. An extension of this
 * adapter takes it as a test dependency and meets the same cluster the adapter is tested
 * against, on the same release line.
 */
public final class ClusterUnderTest {

  private static final String RESOURCE = "/camunda8-cluster.properties";

  private static final Properties PROPERTIES = read();

  private static final String IMAGE = property("cluster.image");

  /**
   * What {@code camunda.data.secondary-storage.type} of the cluster is set to. Only the two
   * values the tests know are expected here: {@code rdbms}, which the cluster serves from an
   * embedded H2 inside its own container, and {@code elasticsearch}, which needs a container
   * of its own.
   */
  private static final String SECONDARY_STORAGE = property("cluster.secondary-storage");

  private static final String ELASTICSEARCH = "elasticsearch";

  /**
   * How long a container may take to report itself ready. Five minutes is what the slowest
   * of the modules sharing this needed; it is the point at which a run gives up, not a delay
   * anybody pays on a cluster which starts.
   */
  private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

  private ClusterUnderTest() {
    // static helper
  }

  /**
   * The image every cluster of this build starts from. The build filters it in, so it follows the
   * client the line was compiled against.
   *
   * @return The image of the cluster under test, e.g. {@code camunda/camunda:8.9.19}
   */
  public static DockerImageName image() {

    return DockerImageName.parse(IMAGE);

  }

  /**
   * The cluster of a test, ready to be searched, with its log lines written under the name
   * {@code cluster}.
   *
   * @return A container to be used as a Testcontainers {@code @Container} field
   */
  public static GenericContainer<?> cluster() {

    return cluster("cluster");

  }

  /**
   * The one cluster a whole test module shares: started when the first class asks for it
   * and kept running until the JVM of the module's tests ends.
   * <p>
   * It is deliberately NOT a {@code @Container} field of any class. Testcontainers stops
   * what a class declared once that class is done, which is what a cluster per class is
   * made of, and a module of thirty classes then pays thirty startups. Here the container
   * is started once, outside that lifecycle, and Ryuk
   * removes it when the JVM which started it is gone - the same reaper which removes a
   * container left behind by a test that crashed. A build adds a step which force-removes
   * what is left, because a container living for a whole module lives long enough to be
   * worth a belt.
   * <p>
   * What a test pays for the sharing is that the cluster REMEMBERS: the definitions of
   * every class which booted before it are still deployed, and instances of theirs may
   * still be running. Two things make that bearable in this repository. All classes of one
   * module deploy the same files, so the cluster holds one set of definitions rather than
   * one per class, and a class starts by cancelling whatever is still running. A test which
   * needs a cluster nobody has deployed anything into keeps one of its own instead.
   *
   * @return The module's cluster, already started
   */
  public static synchronized GenericContainer<?> sharedCluster() {

    if (shared == null) {
      shared = cluster("shared");
      shared.start();
    }
    return shared;

  }

  /**
   * @see #sharedCluster()
   */
  private static GenericContainer<?> shared;

  /**
   * Where a started cluster answers REST calls. The port is mapped, so it is only known once the
   * container runs.
   *
   * @param cluster A started cluster
   * @return Where its REST API answers, e.g. {@code http://localhost:32770}
   */
  public static String restAddress(
      final GenericContainer<?> cluster) {

    return "http://%s:%d".formatted(cluster.getHost(), cluster.getMappedPort(8080));

  }

  /**
   * Where a started cluster answers gRPC calls. The port is mapped, so it is only known once the
   * container runs.
   *
   * @param cluster A started cluster
   * @return Where its gRPC gateway answers, e.g. {@code http://localhost:32771}
   */
  public static String grpcAddress(
      final GenericContainer<?> cluster) {

    return "http://%s:%d".formatted(cluster.getHost(), cluster.getMappedPort(26500));

  }

  /**
   * The cluster of a test, ready to be searched.
   * <p>
   * The readiness probe turns UP only once the partition leader accepts deployments, which
   * avoids a transient 503 on the first deploy at startup.
   *
   * @param logName What this container is called in {@link ClusterLog} - worth choosing
   *          where a module starts several clusters, because the log of all of them is one
   *          file
   * @return A container to be used as a Testcontainers {@code @Container} field
   */
  public static GenericContainer<?> cluster(
      final String logName) {

    return newCluster(logName)
        // an unprotected API keeps an authentication provider out of a test which is about
        // something else. It unprotects REST and nothing else: gRPC is left WITHOUT an
        // identity here, so an activation over that transport answers with an empty list
        // and a command over it is refused with PERMISSION_DENIED. A test about gRPC takes
        // withAuthentication() instead, which is what Camunda8GrpcTransportIT does - that
        // gap cost somebody an evening on an empty job queue once
        .withEnv("CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI", "true")
        .waitingFor(
            Wait
                .forHttp("/actuator/health/readiness")
                .forPort(9600)
                .forStatusCode(200)
                .withStartupTimeout(STARTUP_TIMEOUT));

  }

  /**
   * A cluster WITHOUT secondary storage: it refuses every search with HTTP 403, which is
   * what the adapter's requirement is refused by.
   *
   * @return A container to be used as a Testcontainers {@code @Container} field
   */
  public static GenericContainer<?> clusterWhichRefusesSearches() {

    return new GenericContainer<>(image())
        .withLogConsumer(ClusterLog.of("unsearchable"))
        .withExposedPorts(8080, 26500, 9600)
        .withEnv("SPRING_PROFILES_ACTIVE", "broker")
        .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_TYPE", "none")
        .withEnv("CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI", "true")
        .waitingFor(
            Wait
                .forHttp("/actuator/health/readiness")
                .forPort(9600)
                .forStatusCode(200)
                .withStartupTimeout(STARTUP_TIMEOUT));

  }

  /**
   * The user the authenticated clusters are initialized with, and its password. Both are
   * test values and deliberately visible: what matters here is that they REACH the cluster,
   * not that they are secret.
   */
  public static final String USERNAME = "demo";

  /**
   * The password of that user.
   *
   * @see #USERNAME
   */
  public static final String PASSWORD = "demo";

  /**
   * A cluster with its authentication SWITCHED ON - what a self-managed installation
   * normally looks like, and what every other cluster here deliberately is not.
   *
   * @return A container to be used as a Testcontainers {@code @Container} field
   */
  public static GenericContainer<?> withAuthentication() {

    return authenticated(newCluster("authenticated"))
        .withEnv("CAMUNDA_SECURITY_AUTHORIZATIONS_ENABLED", "true");

  }

  /**
   * A cluster which separates its TENANTS, which is what the name-clash avoidance mode
   * {@code by-adapter} puts a workflow module into.
   * <p>
   * Multi-tenancy checks only work on an authenticated cluster - a tenant is something a
   * user is assigned to - so this is the authenticated cluster with the checks switched on.
   * The tenants themselves are not configured here: a test writes them with
   * {@link #createTenant(String, String)} once the cluster answers, because a tenant is
   * per test scenario while the container is per class.
   *
   * @return A container to be used as a Testcontainers {@code @Container} field
   */
  public static GenericContainer<?> clusterWithTenants() {

    return authenticated(newCluster("tenants"))
        .withEnv("CAMUNDA_SECURITY_MULTITENANCY_CHECKSENABLED", "true");

  }

  /**
   * Switches a cluster's authentication on and makes it wait until it really answers to the
   * credentials the tests use.
   * <p>
   * A ready cluster is not yet a cluster which knows this user: the readiness probe answers
   * before the initialization created it, and the first request of the application then gets
   * a 401 it cannot do anything with. So the second condition asks an API which demands
   * authentication, with those credentials.
   */
  private static GenericContainer<?> authenticated(
      final ClusterContainer container) {

    return container
        // no UNPROTECTEDAPI here: every request has to carry credentials
        .withEnv("CAMUNDA_SECURITY_AUTHENTICATION_METHOD", "BASIC")
        .withEnv("CAMUNDA_SECURITY_INITIALIZATION_USERS_0_USERNAME", USERNAME)
        .withEnv("CAMUNDA_SECURITY_INITIALIZATION_USERS_0_PASSWORD", PASSWORD)
        .withEnv("CAMUNDA_SECURITY_INITIALIZATION_USERS_0_NAME", "Demo")
        .withEnv("CAMUNDA_SECURITY_INITIALIZATION_USERS_0_EMAIL", "demo@example.org")
        .withEnv("CAMUNDA_SECURITY_INITIALIZATION_DEFAULTROLES_ADMIN_USERS_0", USERNAME)
        .waitingFor(
            new WaitAllStrategy()
                .withStrategy(
                    Wait
                        .forHttp("/actuator/health/readiness")
                        .forPort(9600)
                        .forStatusCode(200)
                        .withStartupTimeout(STARTUP_TIMEOUT))
                .withStrategy(
                    Wait
                        .forHttp("/v2/topology")
                        .forPort(8080)
                        .withBasicCredentials(USERNAME, PASSWORD)
                        .forStatusCode(200)
                        .withStartupTimeout(STARTUP_TIMEOUT))
                .withStartupTimeout(STARTUP_TIMEOUT));

  }

  /**
   * Creates a tenant on a cluster of {@link #clusterWithTenants()} and assigns the test user
   * to it, and returns only once the cluster answers questions about that tenant.
   *
   * @param restAddress The cluster's REST address, e.g.
   *          {@code http://localhost:32770}
   * @param tenantId The tenant, which for VanillaBP is a workflow module id
   */
  public static void createTenant(
      final String restAddress,
      final String tenantId) {

    send(
        HttpRequest
            .newBuilder(URI.create("%s/v2/tenants".formatted(restAddress)))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers
                    .ofString("{\"tenantId\":\"%s\",\"name\":\"%s\"}".formatted(tenantId, tenantId))));
    awaitTenant(restAddress, tenantId);
    send(
        HttpRequest
            .newBuilder(
                URI.create("%s/v2/tenants/%s/users/%s".formatted(restAddress, tenantId, USERNAME)))
            .PUT(HttpRequest.BodyPublishers.noBody()));
    awaitTenant(restAddress, tenantId);

  }

  /**
   * How long a tenant may take to become readable after it was written.
   */
  private static final Duration TENANT_READABLE_WITHIN = Duration.ofSeconds(60);

  /**
   * Waits until the cluster answers a question about a tenant.
   * <p>
   * Writing a tenant and reading it back are two different stores on a Camunda 8 cluster:
   * the write is accepted by the engine, the read is served by the exporter's read model. A
   * deployment sent in between is rejected for a tenant which exists, which is a failure
   * nothing about the test explains.
   *
   * @param restAddress The cluster's REST address
   * @param tenantId The tenant
   */
  public static void awaitTenant(
      final String restAddress,
      final String tenantId) {

    final var deadline = System.currentTimeMillis() + TENANT_READABLE_WITHIN.toMillis();
    var lastAnswer = "nothing yet";
    while (System.currentTimeMillis() < deadline) {
      final var response = call(
          HttpRequest
              .newBuilder(URI.create("%s/v2/tenants/%s".formatted(restAddress, tenantId)))
              .GET());
      if (response.statusCode() == 200) {
        return;
      }
      lastAnswer = "%d: %s".formatted(Integer.valueOf(response.statusCode()), response.body());
      try {
        Thread.sleep(250);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for the tenant to be readable", e);
      }
    }
    throw new IllegalStateException(
        "The tenant '%s' was not readable within %s, the cluster kept answering %s"
            .formatted(tenantId, TENANT_READABLE_WITHIN, lastAnswer));

  }

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  /**
   * Sends a request which has to succeed.
   */
  private static void send(
      final HttpRequest.Builder request) {

    final var response = call(request);
    if (response.statusCode() >= 300) {
      throw new IllegalStateException(
          "The cluster answered %d to '%s': %s"
              .formatted(
                  Integer.valueOf(response.statusCode()),
                  response.uri(),
                  response.body()));
    }

  }

  /**
   * Sends a request with the credentials of the test user, whatever the cluster answers.
   */
  private static HttpResponse<String> call(
      final HttpRequest.Builder request) {

    final var credentials = Base64
        .getEncoder()
        .encodeToString("%s:%s".formatted(USERNAME, PASSWORD).getBytes(StandardCharsets.UTF_8));
    try {
      return CLIENT
          .send(
              request
                  .header("Authorization", "Basic "
                      + credentials)
                  .build(),
              HttpResponse.BodyHandlers.ofString());
    } catch (final IOException e) {
      throw new UncheckedIOException("Cannot reach the cluster", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while talking to the cluster", e);
    }

  }

  private static ClusterContainer newCluster(
      final String logName) {

    final var container = new ClusterContainer(image())
        .withLogConsumer(ClusterLog.of(logName))
        .withExposedPorts(8080, 26500, 9600)
        .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_TYPE", SECONDARY_STORAGE);
    return SECONDARY_STORAGE.equals(ELASTICSEARCH)
        ? container.exportingToAnElasticsearchOfItsOwn()
        : container
            .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_URL", H2_URL)
            .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_USERNAME", H2_USER)
            .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_PASSWORD", H2_PASSWORD);

  }

  /**
   * The database the cluster keeps its secondary storage in where the release line has one:
   * an H2 in the memory of the cluster's own JVM, whose driver the image ships. It outlives
   * every connection ({@code DB_CLOSE_DELAY=-1}) and dies with the container, so a cluster
   * which is started for one class leaves nothing behind for the next one, and a cluster
   * shared by a module lives exactly as long as what it remembers.
   */
  private static final String H2_URL = "jdbc:h2:mem:camunda;DB_CLOSE_DELAY=-1";

  /**
   * @see #H2_URL
   */
  private static final String H2_USER = "sa";

  /**
   * @see #H2_URL
   */
  private static final String H2_PASSWORD = "sa";

  /**
   * A cluster which takes its Elasticsearch along where it needs one.
   * <p>
   * Testcontainers starts what a container depends on, but it stops only what a test class
   * declared, and an Elasticsearch nobody declared would then outlive its cluster: a module
   * of twenty classes would hold twenty of them by the end of its run, each with its own
   * heap. So the cluster stops the storage it brought, and the test class keeps the one
   * field it asked for.
   */
  private static final class ClusterContainer extends GenericContainer<ClusterContainer> {

    private Network network;

    private GenericContainer<?> elasticsearch;

    private ClusterContainer(
        final DockerImageName image) {

      super(image);

    }

    private ClusterContainer exportingToAnElasticsearchOfItsOwn() {

      network = Network.newNetwork();
      elasticsearch = new GenericContainer<>(
          DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.17.0"))
          .withNetwork(network)
          .withNetworkAliases(ELASTICSEARCH)
          .withEnv("discovery.type", "single-node")
          .withEnv("xpack.security.enabled", "false")
          .withEnv("ES_JAVA_OPTS", "-Xms1g -Xmx1g")
          .withExposedPorts(9200)
          .waitingFor(
              Wait
                  .forHttp("/_cluster/health")
                  .forPort(9200)
                  .forStatusCode(200)
                  .withStartupTimeout(STARTUP_TIMEOUT));
      return withNetwork(network)
          .dependsOn(elasticsearch)
          .withEnv("CAMUNDA_DATA_SECONDARYSTORAGE_ELASTICSEARCH_URL", "http://elasticsearch:9200");

    }

    @Override
    public void stop() {

      try {
        super.stop();
      } finally {
        if (elasticsearch != null) {
          elasticsearch.stop();
          network.close();
        }
      }

    }

  }

  private static Properties read() {

    final var properties = new Properties();
    try (var resource = ClusterUnderTest.class.getResourceAsStream(RESOURCE)) {
      if (resource == null) {
        throw new IllegalStateException(
            ("'%s' is missing from the classpath. It is filtered into the artifact "
                + "'camunda8-adapter-test-support', so build that module once ('mvn install') before "
                + "running an integration test from the IDE.").formatted(RESOURCE));
      }
      properties.load(resource);
    } catch (final IOException e) {
      throw new UncheckedIOException("Cannot read '%s'".formatted(RESOURCE), e);
    }
    return properties;

  }

  private static String property(
      final String name) {

    final var value = PROPERTIES.getProperty(name);
    if ((value == null) || value.isBlank() || value.contains("${")) {
      throw new IllegalStateException(
          ("'%s' of '%s' is '%s' instead of a value. The resources of 'test-support' have to be "
              + "filtered: check its pom.xml and the properties 'camunda8.cluster.image' and "
              + "'camunda8.cluster.secondary-storage' of the parent pom.").formatted(name, RESOURCE, value));
    }
    return value;

  }

}
