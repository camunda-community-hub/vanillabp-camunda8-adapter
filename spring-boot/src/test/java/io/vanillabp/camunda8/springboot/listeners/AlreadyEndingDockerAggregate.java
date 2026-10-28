package io.vanillabp.camunda8.springboot.listeners;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the test which completes a user task the cluster is already
 * ending. The application writes a note before it completes the task. If the note is still
 * empty afterwards, the transaction was rolled back.
 */
@Entity
@Table(name = "C8_ALREADY_ENDING_AGGREGATE")
@Getter
@Setter
public class AlreadyEndingDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String note;

}
