package io.vanillabp.camunda8.quarkus.deployment.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

/**
 * Camunda 8 adapter build-time properties. There are none. The adapter's own keys (client
 * connection and the rest) are read at run time, through the overlay mapping
 * {@code VanillaBpCamunda8Properties} of the runtime module.
 */
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface Camunda8Properties {

}
