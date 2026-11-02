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
@Table(name = "C8_SUSPENDED_AGGREGATE")
@Getter
@Setter
public class SuspendedDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * The job key of the asynchronous task. Its handler writes it.
   */
  private String openTaskId;

  /**
   * The key of the user task. Its handler writes it when the task is created.
   */
  private String userTaskId;

  /**
   * What the application wrote together with its answer. A test reads it to see whether that
   * transaction committed.
   */
  private String answer;

  /**
   * Set by the <code>&#64;WorkflowEnded</code> method.
   */
  private String endedAs;

}
