package io.vanillabp.camunda8.springboot.paramtypes;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring Boot application of the parameter-types integration test. It is also the reason
 * why this scenario has a package of its own.
 * <p>
 * Component, entity and repository scanning all start at the package of the
 * {@code @SpringBootApplication}. A scenario in the package of the other integration tests
 * would become part of each of them. All of them would deploy its BPMN and create a table for
 * its aggregate. Some branches of this model are meant to fail. This application, together
 * with a configuration file and a resources location of its own, keeps that model away from
 * the tests which expect their workflow to run through.
 */
@SpringBootApplication
public class ParamTypesTestApplication {

}
