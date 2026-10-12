package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the decision-table integration test. The input of the decision
 * travels as a shared value ({@code approved}). The result of the decision comes back into
 * {@code rating}.
 */
@Entity
@Table(name = "C8_DECISION_AGGREGATE")
@Getter
@Setter
public class DecisionDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private boolean approved;

  private String rating;

}
