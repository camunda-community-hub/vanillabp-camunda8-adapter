package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the test which cancels a running instance. What it records is
 * what the application was told: the task it is waiting at, and how the workflow ended.
 */
@Entity
@Table(name = "C8_CANCELED_AGGREGATE")
@Getter
@Setter
public class CanceledDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * The job key of the asynchronous task the instance waits at, written by its handler.
   */
  private String openTaskId;

  /**
   * Set by the <code>&#64;WorkflowEnded</code> method, so the test reads the kind the
   * adapter reported rather than a log line.
   */
  private String endedAs;

}
