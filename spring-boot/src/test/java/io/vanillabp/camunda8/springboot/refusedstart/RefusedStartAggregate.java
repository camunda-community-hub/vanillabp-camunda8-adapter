package io.vanillabp.camunda8.springboot.refusedstart;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the refused-start integration test. Every attribute of an
 * aggregate is shared with the cluster, so {@link #document} travels along as a process
 * variable when the workflow starts - which is how the test hands the cluster a request
 * it is too big to take.
 */
@Entity
@Table(name = "C8_REFUSED_START_AGGREGATE")
@Getter
@Setter
public class RefusedStartAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * What an application would keep in an aggregate of its own: a text it wants the model
   * to read. A megabyte of it is still an application's business, several are more than
   * a request to the cluster may weigh.
   */
  @Lob
  private String document;

}
