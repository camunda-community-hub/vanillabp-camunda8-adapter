package io.vanillabp.camunda8.springboot.election;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the shared-cluster election test.
 */
@Entity
@Table(name = "C8_ELECTION_AGGREGATE")
@Getter
@Setter
public class ElectionAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * Written by the task behind the message catch: it proves the message reached the
   * workflow, and therefore the adapter which holds it.
   */
  private String servedBy;

}
