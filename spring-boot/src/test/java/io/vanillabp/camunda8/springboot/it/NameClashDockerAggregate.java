package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the name-clash test. It has one of its own because a workflow
 * aggregate belongs to exactly one workflow service, and sharing another test's aggregate
 * would end the boot with that message instead of the report under test.
 */
@Entity
@Table(name = "C8_NAME_CLASH_AGGREGATE")
@Getter
@Setter
public class NameClashDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

}
