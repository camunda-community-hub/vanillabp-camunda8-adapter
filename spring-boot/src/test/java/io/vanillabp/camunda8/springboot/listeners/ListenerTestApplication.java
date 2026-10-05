package io.vanillabp.camunda8.springboot.listeners;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring Boot application of the modelled-listener integration test, and the reason this
 * scenario has a PACKAGE of its own: component, entity and repository scanning all start at
 * the package of the {@code @SpringBootApplication}, so a scenario sitting beside the other
 * integration tests of this module would be deployed by every one of them. This model does not
 * even deploy without {@code allow-listeners}, which is why it gets a configuration file and a
 * resources location of its own as well.
 */
@SpringBootApplication
public class ListenerTestApplication {

}
