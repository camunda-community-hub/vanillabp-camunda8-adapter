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
 * What the Camunda 8 adapter needs in a native image, registered here so that no
 * application has to find it out from a stack trace.
 * <p>
 * Everything below sits on the DEPLOYMENT path, which every application runs at every
 * boot: the adapter reads each BPMN file through the Camunda model API, modifies it,
 * serializes it back and sends it to the cluster, and it builds the Camunda client while
 * doing so. On the JVM all of that finds what it needs by itself - a resource bundle, a
 * schema, an implementation named in a service file - because a class is loaded when it
 * is first used. A native image carries only what somebody named while it was built, so
 * an unnamed one of them ends the boot at the first workflow module.
 * <p>
 * These build items are read by the native build alone; a JVM-mode build ignores them.
 * They are held by {@code quarkus/native-image-tests}, which builds an image and runs
 * the binary - the two failures below were both invisible to a build.
 */
class Camunda8NativeImageProcessor {

  /**
   * Message bundles of the JDK's XML parser, which the Camunda model API validates the
   * BPMN against. The parser reads them whenever it has something to SAY, and the first
   * thing it wanted to say was the schema location, so the very first BPMN file failed
   * with a {@code MissingResourceException} instead of being parsed.
   * <p>
   * All five are named rather than only the schema one: what a parser says on its error
   * paths is reached by a broken BPMN, which is later than boot and therefore in the
   * hands of an application rather than of this repository's tests.
   */
  private static final List<String> XML_PARSER_MESSAGES = List
      .of(
          "com.sun.org.apache.xerces.internal.impl.msg.XMLSchemaMessages",
          "com.sun.org.apache.xerces.internal.impl.msg.XMLMessages",
          "com.sun.org.apache.xerces.internal.impl.msg.DatatypeMessages",
          "com.sun.org.apache.xerces.internal.impl.msg.SAXMessages",
          "com.sun.org.apache.xerces.internal.impl.msg.DOMMessages");

  /**
   * The BPMN schema and everything it imports, resources of the Camunda model API's own
   * jar. A resource of a dependency is not in a native image unless it is named, and
   * without them the parser fails with {@code schema_reference.4} on
   * <code>BPMN20.xsd</code>.
   */
  private static final List<String> BPMN_SCHEMA = List
      .of("BPMN20.xsd", "Semantic.xsd", "BPMNDI.xsd", "DC.xsd", "DI.xsd");

  /**
   * The gRPC extension points the Camunda client reaches through the
   * {@link ServiceLoader}. A native image has no service loader unless the
   * providers are registered, and the client builds a gRPC channel while it is built -
   * unconditionally, also for an application which configured
   * <code>rest-address</code> only, see {@code Camunda8ClientFactory} and
   * {@code CamundaClientImpl#buildChannel}. Without them the boot ends with
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
   * Netty's buffer allocators are initialized at run time (Quarkus' Netty extension says
   * so), and gRPC holds one in a static field of a holder class. A class initialized at
   * build time whose fields point at objects of a run-time-initialized type is what
   * GraalVM refuses: <code>An object of type
   * 'io.netty.buffer.PooledByteBufAllocator' was found in the image heap</code>. So
   * everything on the way to those allocators is initialized at run time as well - the
   * two holders, the class holding them, and the channel builder which would pull it in.
   * The last three names are the same treatment for the protobuf runtime and gRPC's
   * retry machinery, which the client reaches through the channel.
   * <p>
   * The same list, minus what only a gRPC SERVER needs, is what Quarkus' own gRPC
   * extension registers. Depending on that extension instead would drag Vert.x and its
   * gRPC client and server into every application which uses this adapter, for four
   * lines of registration.
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
   * The implementation of the Camunda client, none of which may be initialized while the
   * image is built.
   * <p>
   * A job worker backs off with a {@link Random}, and the worker, its builder
   * and the backoff itself each keep a default of the next one in a static field. A random
   * seeded while the image is built carries the same seed into every process started from
   * that image, so GraalVM refuses it: <code>Detected an instance of
   * Random/SplittableRandom class in the image heap</code>. The image builder initializes
   * a class at build time wherever its simulation finds that safe, and it found one after
   * the other safe - the backoff, its builder, the worker, the client builder holding a
   * default exception handler - so this names the package instead of collecting the
   * classes one failed build at a time. Nothing of a client's implementation belongs in an
   * image heap anyway: it is what talks to a cluster the image knows nothing about yet.
   */
  private static final String CAMUNDA_CLIENT_IMPLEMENTATION = "io.camunda.client.impl";

  /**
   * The package holding the request and response types of the cluster's REST API. Jackson
   * builds them by reflection, and a native image reflects over nothing it was not told
   * about, so the first answer of the cluster - a tenant lookup during the deployment -
   * ended the boot with <code>Cannot construct instance of
   * io.camunda.client.protocol.rest.ProblemDetail ... this appears to be a native
   * image</code>. Every type of the package is registered rather than the handful the
   * deployment happens to read: which of them an operation needs is the client's business,
   * and the next release moves that line.
   */
  private static final String REST_PROTOCOL_PACKAGE = "io.camunda.client.protocol.rest";

  /**
   * The artifact the two names above live in. Its classes are only visible to a build step
   * once they are in the Jandex index, which is what {@link IndexDependencyBuildItem} asks
   * for.
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
   * Initializes the Camunda model API's entry point while the application runs rather
   * than while its image is built.
   * <p>
   * Quarkus initializes classes at build time by default, and this one builds its parser
   * in a static initializer, holding on to the URL the schema was found under. At build
   * time that URL points into the builder container
   * (<code>jar:file:/project/lib/...</code>), which nothing can read afterwards - so
   * registering the schema as a resource is necessary but not sufficient.
   *
   * @return The run-time initialization of the model API's entry point
   */
  @BuildStep
  RuntimeInitializedClassBuildItem theBpmnParserIsBuiltAtRunTime() {

    return new RuntimeInitializedClassBuildItem("io.camunda.zeebe.model.bpmn.Bpmn");

  }

  /**
   * Registers the gRPC providers the Camunda client looks up through the service loader.
   * Taken from the classpath rather than written down class by class: which
   * implementation serves a gRPC extension point is the business of the gRPC release the
   * client brings, and a name held here would be a second place to maintain.
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
   * Indexes the Camunda client, so that the step below can ask for the types of its REST
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
