package io.vanillabp.camunda8.springboot.connectors;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring Boot application of the connector integration test. It is also the reason why
 * this scenario has a PACKAGE of its own, apart from the other integration tests.
 * <p>
 * Component scanning, entity scanning and repository scanning all start at the package of the
 * {@code @SpringBootApplication}. A scenario in the package of {@code DockerTestApplication}
 * is therefore part of every other integration test of this module. All of them deploy its
 * BPMN file, register its workflow service and create a table for its aggregate. None of
 * those tests asked for that load. This class keeps it away from them, together with a
 * configuration file and a resources location of its own. So only this test deploys a
 * process with a connector.
 */
@SpringBootApplication
public class ConnectorTestApplication {

}
