package io.vanillabp.camunda8.quarkus.deployment;

import java.util.List;
import java.util.Random;
import java.util.ServiceLoader;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBundleBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedPackageBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;

/**
 * What the Camunda 8 adapter needs in a native image. It is registered here, so no
 * application has to find it out from a stack trace.
 * <p>
 * Everything below is on the DEPLOYMENT path, and every application runs that path at
 * every boot. The adapter reads each BPMN file through the Camunda model API, changes it,
 * writes it back to XML and sends it to the cluster. On the way it builds the Camunda
 * client. On the JVM all of that finds what it needs by itself: a resource bundle, a
 * schema, an implementation named in a service file. This works because a class is loaded
 * when it is first used. A native image only holds what somebody named while it was built.
 * So each of these things which nobody names ends the boot at the first workflow module.
 * <p>
 * Only the native build reads these build items. A build in JVM mode ignores them.
 * {@code quarkus/native-image-tests} holds them: it builds an image and runs the binary.
 * A build alone did not show either of the two failures below.
 */
class Camunda8NativeImageProcessor {

  /**
   * Message bundles of the JDK's XML parser. The Camunda model API uses this parser to
   * validate the BPMN. The parser reads the bundles whenever it has something to SAY. The
   * first thing it wanted to say was the schema location. So the very first BPMN file
   * failed with a {@code MissingResourceException} and was not parsed.
   * <p>
   * All five are named, not only the one for the schema. A broken BPMN reaches what the
   * parser says on its error paths. That happens after the boot, so it is in the hands of
   * an application and not of this repository's tests.
   */
  private static final List<String> XML_PARSER_MESSAGES = List
      .of(
          "com.sun.org.apache.xerces.internal.impl.msg.XMLSchemaMessages",
          "com.sun.org.apache.xerces.internal.impl.msg.XMLMessages",
          "com.sun.org.apache.xerces.internal.impl.msg.DatatypeMessages",
          "com.sun.org.apache.xerces.internal.impl.msg.SAXMessages",
          "com.sun.org.apache.xerces.internal.impl.msg.DOMMessages");

  /**
   * The BPMN schema and everything it imports. They are resources in the jar of the
   * Camunda model API. A resource of a dependency is only in a native image if it is
   * named. Without them the parser fails with {@code schema_reference.4} on
   * <code>BPMN20.xsd</code>.
   */
  private static final List<String> BPMN_SCHEMA = List
      .of("BPMN20.xsd", "Semantic.xsd", "BPMNDI.xsd", "DC.xsd", "DI.xsd");

  /**
   * The gRPC extension points the Camunda client reaches through the
   * {@link ServiceLoader}. In a native image the service loader finds nothing unless the
   * providers are registered. The client always builds a gRPC channel while it is built,
   * also for an application which only configured <code>rest-address</code>. See
   * {@code Camunda8ClientFactory} and {@code CamundaClientImpl#buildChannel}. Without the
   * providers the boot ends with
   * <code>Could not find a NameResolverProvider for &lt;the default gRPC
   * address&gt;</code>.
   */
  private static final List<String> GRPC_PROVIDERS = List
      .of(
          "io.grpc.NameResolverProvider",
          "io.grpc.LoadBalancerProvider",
          "io.grpc.ManagedChannelProvider");

  /**
   * Classes of the gRPC stack which must not be initialized while the image is built.
   * <p>
   * Netty's buffer allocators are initialized at run time, because Quarkus' Netty extension
   * says so. gRPC holds one in a static field of a holder class. GraalVM refuses a class
   * which is initialized at build time and whose fields point at objects of a type
   * initialized at run time: <code>An object of type
   * 'io.netty.buffer.PooledByteBufAllocator' was found in the image heap</code>. So
   * everything on the way to those allocators is initialized at run time as well: the two
   * holders, the class which holds them, and the channel builder which would pull it in.
   * The last three names get the same treatment. They are the protobuf runtime and gRPC's
   * retry code, which the client reaches through the channel.
   * <p>
   * Quarkus' own gRPC extension registers the same list, plus what only a gRPC SERVER
   * needs. If this adapter depended on that extension instead, it would pull Vert.x and
   * its gRPC client and server into every application which uses this adapter. That is too
   * much for four lines of registration.
   */
  private static final List<String> GRPC_INITIALIZED_AT_RUN_TIME = List
      .of(
          "io.grpc.netty.Utils$ByteBufAllocatorPreferDirectHolder",
          "io.grpc.netty.Utils$ByteBufAllocatorPreferHeapHolder",
          "io.grpc.netty.Utils",
          "io.grpc.netty.NettyChannelBuilder",
          "io.grpc.internal.RetriableStream",
          "com.google.protobuf.JavaFeaturesProto",
          "com.google.protobuf.UnsafeUtil");

  /**
   * The implementation of the Camunda client. None of it may be initialized while the
   * image is built.
   * <p>
   * A job worker backs off with a {@link Random}. The worker, its builder and the backoff
   * itself each keep a default of the next one in a static field. A random seeded while
   * the image is built carries the same seed into every process started from that image.
   * So GraalVM refuses it: <code>Detected an instance of Random/SplittableRandom class in
   * the image heap</code>. The image builder initializes a class at build time wherever
   * its simulation finds that safe. It found one class after the other safe: the backoff,
   * its builder, the worker, the client builder with its default exception handler. So
   * this names the whole package, instead of collecting the classes one failed build at a
   * time. Nothing of a client's implementation belongs in an image heap anyway. It talks
   * to a cluster the image knows nothing about yet.
   */
  private static final String CAMUNDA_CLIENT_IMPLEMENTATION = "io.camunda.client.impl";

  /**
   * The package with the request and response types of the cluster's REST API. Jackson
   * builds them by reflection. A native image only allows reflection on what it was told
   * about. So the first answer of the cluster, a tenant lookup during the deployment, ended
   * the boot with <code>Cannot construct instance of
   * io.camunda.client.protocol.rest.ProblemDetail ... this appears to be a native
   * image</code>. Every type of the package is registered, not only the few the deployment
   * happens to read. Which of them an operation needs is up to the client, and the next
   * release changes that.
   */
  private static final String REST_PROTOCOL_PACKAGE = "io.camunda.client.protocol.rest";

  /**
   * The artifact which holds the two names above. A build step only sees its classes once
   * they are in the Jandex index. {@link IndexDependencyBuildItem} asks for that.
   */
  private static final String CAMUNDA_CLIENT_GROUP_ID = "io.camunda";

  private static final String CAMUNDA_CLIENT_ARTIFACT_ID = "camunda-client-java";

  /**
   * Registers the message bundles and the schema of the BPMN parser.
   *
   * @param bundles Producer of the resource-bundle registrations
   * @param resources Producer of the resource registrations
   */
  @BuildStep
  void whatTheBpmnParserReads(
      final BuildProducer<NativeImageResourceBundleBuildItem> bundles,
      final BuildProducer<NativeImageResourceBuildItem> resources) {

    XML_PARSER_MESSAGES
        .forEach(bundle -> bundles.produce(new NativeImageResourceBundleBuildItem(bundle)));
    BPMN_SCHEMA
        .forEach(schema -> resources.produce(new NativeImageResourceBuildItem(schema)));

  }

  /**
   * Initializes the entry point of the Camunda model API while the application runs, not
   * while its image is built.
   * <p>
   * Quarkus initializes classes at build time by default. This class builds its parser in
   * a static initializer and keeps the URL where it found the schema. At build time that
   * URL points into the builder container (<code>jar:file:/project/lib/...</code>), and
   * nothing can read it afterwards. So registering the schema as a resource is needed, but
   * it is not enough.
   *
   * @return The run-time initialization of the model API's entry point
   */
  @BuildStep
  RuntimeInitializedClassBuildItem theBpmnParserIsBuiltAtRunTime() {

    return new RuntimeInitializedClassBuildItem("io.camunda.zeebe.model.bpmn.Bpmn");

  }

  /**
   * Registers the gRPC providers the Camunda client looks up through the service loader.
   * They are taken from the classpath, not written down class by class. Which
   * implementation serves a gRPC extension point is up to the gRPC release the client
   * brings. A name written down here would be a second place to maintain.
   *
   * @param services Producer of the service-provider registrations
   */
  @BuildStep
  void whatTheCamundaClientLoads(
      final BuildProducer<ServiceProviderBuildItem> services) {

    GRPC_PROVIDERS
        .forEach(provider -> services.produce(ServiceProviderBuildItem.allProvidersFromClassPath(provider)));

  }

  /**
   * Indexes the Camunda client, so the step below can ask for the types of its REST
   * protocol.
   *
   * @return The index request for the Camunda client
   */
  @BuildStep
  IndexDependencyBuildItem theCamundaClientIsIndexed() {

    return new IndexDependencyBuildItem(CAMUNDA_CLIENT_GROUP_ID, CAMUNDA_CLIENT_ARTIFACT_ID);

  }

  /**
   * Registers the types Jackson reads the cluster's answers into.
   *
   * @param index The combined Jandex index, which holds the Camunda client because of the
   *          step above
   * @param reflectiveClasses Producer of the reflection registrations
   */
  @BuildStep
  void whatJacksonBuildsFromTheClustersAnswers(
      final CombinedIndexBuildItem index,
      final BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {

    final var protocolTypes = index
        .getIndex()
        .getKnownClasses()
        .stream()
        .map(ClassInfo::name)
        .map(DotName::toString)
        .filter(className -> className.startsWith(REST_PROTOCOL_PACKAGE
            + "."))
        .sorted()
        .toList();

    reflectiveClasses
        .produce(ReflectiveClassBuildItem
            .builder(protocolTypes.toArray(String[]::new))
            .constructors()
            .methods()
            .fields()
            .reason("Jackson reads the Camunda 8 REST API into these types")
            .build());

  }

  /**
   * Moves the initialization of everything named above out of the image build.
   *
   * @param runTimeInitializedClasses Producer of the per-class registrations
   * @param runTimeInitializedPackages Producer of the per-package registrations
   */
  @BuildStep
  void whatInitializesTooEarly(
      final BuildProducer<RuntimeInitializedClassBuildItem> runTimeInitializedClasses,
      final BuildProducer<RuntimeInitializedPackageBuildItem> runTimeInitializedPackages) {

    GRPC_INITIALIZED_AT_RUN_TIME
        .forEach(className -> runTimeInitializedClasses.produce(new RuntimeInitializedClassBuildItem(className)));
    runTimeInitializedPackages
        .produce(new RuntimeInitializedPackageBuildItem(CAMUNDA_CLIENT_IMPLEMENTATION));

  }

}
