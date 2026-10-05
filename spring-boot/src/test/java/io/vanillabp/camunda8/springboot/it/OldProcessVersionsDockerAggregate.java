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
 * The aggregate of the old-process-versions integration test.
 */
@Entity
@Table(name = "C8_OLD_PROCESS_VERSIONS_TEST")
@Getter
@Setter
public class OldProcessVersionsDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c8OldProcessVersionsSeq")
  @SequenceGenerator(name = "c8OldProcessVersionsSeq", initialValue = 900000, allocationSize = 1)
  private Long id;

  private String servedBy;

}
