package io.vanillabp.camunda8.springboot.it;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot application of most integration tests in this package, among them
 * {@link Camunda8DeploymentAndStartIT}: JPA workflow aggregates, which the platform serves
 * with its JDBC phase-two outbox, and the workflow services of this package, bound to the
 * BPMN processes deployed to the Camunda 8 cluster on startup.
 */
@SpringBootApplication
public class DockerTestApplication {

}
