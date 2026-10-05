package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the test about a lock which expires while the handler runs.
 */
@Entity
@Table(name = "C8_LEASE_AGGREGATE")
@Getter
@Setter
public class LeaseDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * Which run of the handler wrote here last, so the test can say whose values the
   * workflow continued with.
   */
  private String writtenBy;

  /**
   * Set by the <code>&#64;WorkflowEnded</code> method, which only runs once a completion
   * was accepted.
   */
  private String endedAs;

}
