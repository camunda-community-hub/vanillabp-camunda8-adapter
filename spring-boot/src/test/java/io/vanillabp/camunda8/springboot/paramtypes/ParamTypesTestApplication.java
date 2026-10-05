package io.vanillabp.camunda8.springboot.paramtypes;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring Boot application of the parameter-types integration test, and the reason
 * this scenario has a package of its own.
 * <p>
 * Component, entity and repository scanning all start at the package of the
 * {@code @SpringBootApplication}. A scenario living in the package of the other
 * integration tests would become part of each of them: its BPMN would be deployed by all
 * of them and its aggregate would become a table in all of them. This application, a
 * configuration file and a resources location of its own keep the model whose branches
 * are meant to fail away from the tests which expect their workflow to run through.
 */
@SpringBootApplication
public class ParamTypesTestApplication {

}
