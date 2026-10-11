package io.vanillabp.camunda8.springboot.refusedstart;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring Boot application of the refused-start integration test. It is also the reason
 * why this scenario has a package of its own.
 * <p>
 * Component, entity and repository scanning all start at the package of the
 * {@code @SpringBootApplication}. A scenario in the package of the other integration tests
 * would become part of each of them. All of them would deploy its BPMN and create a table for
 * its aggregate. The workflow of this scenario is never meant to start. This application,
 * together with a configuration file and a resources location of its own, keeps it away from
 * the tests which start theirs.
 */
@SpringBootApplication
public class RefusedStartTestApplication {

}
