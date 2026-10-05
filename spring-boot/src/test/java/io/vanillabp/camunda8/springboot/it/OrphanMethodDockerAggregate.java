package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the orphan-method test. It has one of its own because a workflow
 * aggregate belongs to exactly one workflow service, and reusing another test's aggregate
 * ends the boot with that message instead of the one under test.
 */
@Entity
@Table(name = "C8_ORPHAN_METHOD_TEST")
@Getter
@Setter
public class OrphanMethodDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c8OrphanMethodSeq")
  @SequenceGenerator(name = "c8OrphanMethodSeq", initialValue = 950000, allocationSize = 1)
  private Long id;

}
