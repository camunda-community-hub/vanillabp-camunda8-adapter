package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the process-version integration test.
 */
@Entity
@Table(name = "C8_VERSIONED_AGGREGATE")
@Getter
@Setter
public class VersionedDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * Which <code>&#64;WorkflowTask</code> method served the task - the version of the
   * deployed process definition decides it.
   */
  private String servedBy;

}
