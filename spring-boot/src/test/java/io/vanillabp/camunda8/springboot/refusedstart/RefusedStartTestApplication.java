package io.vanillabp.camunda8.springboot.refusedstart;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring Boot application of the refused-start integration test, and the reason this
 * scenario has a package of its own.
 * <p>
 * Component, entity and repository scanning all start at the package of the
 * {@code @SpringBootApplication}. A scenario living in the package of the other
 * integration tests would become part of each of them: its BPMN would be deployed by all
 * of them and its aggregate would become a table in all of them. This application, a
 * configuration file and a resources location of its own keep the workflow which is
 * never meant to start away from the tests which start theirs.
 */
@SpringBootApplication
public class RefusedStartTestApplication {

}
