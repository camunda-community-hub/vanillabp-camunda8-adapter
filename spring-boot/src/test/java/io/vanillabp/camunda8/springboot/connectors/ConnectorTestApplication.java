package io.vanillabp.camunda8.springboot.connectors;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring Boot application of the connector integration test, and the reason this
 * scenario has a PACKAGE of its own rather than living beside the other integration tests.
 * <p>
 * Component scanning, entity scanning and repository scanning all start at the package of
 * the {@code @SpringBootApplication}. A scenario which sits in the package of
 * {@code DockerTestApplication} is therefore part of every other integration test of this
 * module: its BPMN file is deployed by all of them, its workflow service is registered for
 * all of them and its aggregate becomes a table in all of them. That is load none of those
 * tests asked for, and this class is what keeps it away from them - together with a
 * configuration file and a resources location of its own, so nothing but this test deploys
 * a process carrying a connector.
 */
@SpringBootApplication
public class ConnectorTestApplication {

}
