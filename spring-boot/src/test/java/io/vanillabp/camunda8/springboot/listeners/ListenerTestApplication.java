package io.vanillabp.camunda8.springboot.listeners;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring Boot application of the modelled-listener integration test. It is also the reason
 * why this scenario has a PACKAGE of its own. Component, entity and repository scanning all
 * start at the package of the {@code @SpringBootApplication}. So every other integration test
 * of this module would deploy a scenario which sits beside them. This model does not even
 * deploy without {@code allow-listeners}. That is why it also gets a configuration file and a
 * resources location of its own.
 */
@SpringBootApplication
public class ListenerTestApplication {

}
