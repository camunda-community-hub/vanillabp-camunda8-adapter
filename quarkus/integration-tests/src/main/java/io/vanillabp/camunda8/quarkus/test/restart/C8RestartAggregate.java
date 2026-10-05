package io.vanillabp.camunda8.quarkus.test.restart;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the restart-delivery test.
 */
@Entity
@Table(name = "C8_RESTART_AGGREGATE")
@Getter
@Setter
public class C8RestartAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String result;

}
