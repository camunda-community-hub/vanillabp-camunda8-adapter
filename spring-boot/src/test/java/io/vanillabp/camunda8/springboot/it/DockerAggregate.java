package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate with a generated ID: the phase-two outbox serializes the ID as a
 * string and the phase-two bean converts it back before the adapter passes it to
 * Camunda 8 as a process variable named after this aggregate's ID property ({@code id}).
 */
@Entity
@Table(name = "CAMUNDA8_IT_AGGREGATE")
@Getter
@Setter
public class DockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String content;

}
