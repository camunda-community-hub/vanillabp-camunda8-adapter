package io.vanillabp.camunda8.springboot.it;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot application for {@link Camunda8DeploymentAndStartIT}: a JPA workflow
 * aggregate, which the platform serves with its JDBC phase-two outbox, and a workflow
 * service bound to the BPMN process deployed to the Camunda 8 cluster on startup.
 */
@SpringBootApplication
public class DockerTestApplication {

}
