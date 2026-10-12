package io.vanillabp.camunda8.springboot;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A test of this module which boots an application with {@code @SpringBootTest} and points
 * it at the cluster every class here shares.
 * <p>
 * The context is closed when the class is done ({@code @DirtiesContext}). With a shared
 * cluster this matters even more than with one cluster per class. Spring keeps a test context
 * until the JVM ends, unless it is told otherwise. Such a context keeps its job workers
 * polling. They ask for the same prefixed job types as the class running now, and the cluster
 * hands a job to whoever asks first. The class which then fails is one which nobody touched.
 * The annotation makes sure that only one application of this module talks to the cluster at
 * a time.
 * <p>
 * A class which needs more properties than the two addresses writes a
 * {@code @DynamicPropertySource} method of its own. Spring calls every such method it finds in
 * the class hierarchy. So the method below has a name which no class is likely to repeat. A
 * method of the same name in a subclass would hide it. The application would then boot
 * against no cluster at all.
 */
@DirtiesContext
public abstract class SpringBootTestOnTheSharedCluster extends TestOnTheSharedCluster {

  @DynamicPropertySource
  static void theAddressesOfTheSharedCluster(
      final DynamicPropertyRegistry registry) {

    registry.add("vanillabp.adapters.c8.rest-address", TestOnTheSharedCluster::restAddress);
    registry.add("vanillabp.adapters.c8.grpc-address", TestOnTheSharedCluster::grpcAddress);

  }

}
