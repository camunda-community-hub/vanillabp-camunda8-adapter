package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the multi-instance integration test. The
 * iterations run one after another, so appending to one column is safe here - a
 * parallel multi-instance would need a row per iteration.
 */
@Entity
@Table(name = "C8_MI_AGGREGATE")
@Getter
@Setter
public class MiDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** What the iterations of the flat multi-instance task were handed. */
  @Column(length = 2000)
  private String flat;

  /** What the iterations of the task inside the multi-instance subprocess were handed. */
  @Column(length = 2000)
  private String nested;

}
