package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of {@code Camunda8AnOperationWithoutTheReadModelIT}. Every attribute
 * is shared with Camunda 8, so the cluster holds {@link #note} as a process variable, which
 * is what the test reads back to see that a push arrived.
 * <p>
 * Its process is used by no other test class. The ids of every class start at 1 and the
 * cluster is shared, so a finished workflow of the same process and the same aggregate id
 * would be found by the search of a later class and taken for its own.
 */
@Entity
@Table(name = "C8_READ_MODEL_AGGREGATE")
@Getter
@Setter
public class ReadModelDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String note;

  /**
   * The task the workflow parks at once the message arrived.
   */
  private String taskId;

}
