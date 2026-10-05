package io.vanillabp.camunda8.quarkus.deployment.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

/**
 * Camunda 8 adapter build-time properties. Empty for now - adapter-specific
 * configuration (client connection, etc.) is added by later feature stories.
 */
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface Camunda8Properties {

}
