package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the renamed-process integration test. It outlives the
 * application which started its workflow: the second boot of that test reads it from the
 * same database, which is what a workflow running across an upgrade needs.
 */
@Entity
@Table(name = "C8_RENAMED_AGGREGATE")
@Getter
@Setter
public class RenamedDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long orderId;

  private String startedBy;

  private String finishedBy;

}
