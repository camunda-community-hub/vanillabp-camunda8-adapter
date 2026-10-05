package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the workflow whose job must not wait behind a blocking
 * handler of another worker.
 */
@Entity
@Table(name = "C8_QUICK_AGGREGATE")
@Getter
@Setter
public class QuickDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String servedBy;

}
