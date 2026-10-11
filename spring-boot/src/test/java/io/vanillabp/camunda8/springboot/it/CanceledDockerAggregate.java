package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the test which cancels a running instance. It records what the
 * application was told: the task it waits at, and how the workflow ended.
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
   * The job key of the asynchronous task which the instance waits at. Its handler writes it.
   */
  private String openTaskId;

  /**
   * Set by the <code>&#64;WorkflowEnded</code> method. So the test reads the kind which the
   * adapter reported, and not a log line.
   */
  private String endedAs;

}
