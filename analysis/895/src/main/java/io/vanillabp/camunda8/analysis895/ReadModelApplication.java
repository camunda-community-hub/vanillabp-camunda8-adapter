package io.vanillabp.camunda8.analysis895;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The application of analysis 895: one workflow with a service task, a task left open, a
 * user task, a message and a timer, and a driver which keeps starting and advancing such
 * workflows while the cluster's read model is taken away. Run by {@code bin/run.sh}.
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
