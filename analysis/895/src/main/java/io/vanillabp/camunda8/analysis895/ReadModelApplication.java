package io.vanillabp.camunda8.analysis895;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The application of analysis 895. It has one workflow with a service task, a task left
 * open, a user task, a message and a timer. A driver keeps starting such workflows and
 * moving them on while the cluster's read model is taken away. {@code bin/run.sh} runs
 * it.
 */
@SpringBootApplication
@EnableScheduling
public class ReadModelApplication {

  /**
   * Starts the application.
   *
   * @param args Spring Boot arguments
   */
  public static void main(
      final String[] args) {

    SpringApplication.run(ReadModelApplication.class, args);

  }

}
