package io.vanillabp.camunda8.springboot.election;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application of the shared-cluster election test. It deploys a single BPMN process to
 * TWO adapter ids of one cluster.
 * <p>
 * It lives in a MAVEN MODULE of its own, and that is the point. A workflow module is a
 * classpath entry with {@code META-INF/workflow-module}. So two scenarios in one Maven
 * module are one workflow module. Then every application is asked for the persistence of
 * the other scenario's aggregates. That ends the startup, before the first task can fail.
 * Here the marker names {@code election-app}, and the tests of the Spring Boot module name
 * {@code test-app}. Neither context registers what it does not configure.
 * <p>
 * The cluster these tests run against comes from the module {@code test-support}, like
 * every other cluster of this repository.
 */
@SpringBootApplication
public class ElectionTestApplication {

}
