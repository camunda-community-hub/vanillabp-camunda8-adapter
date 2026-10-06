package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of {@code Camunda8UnservedUserTaskOfARenamedProcessIT}. It carries
 * nothing but its id, because the user task of that test is served by nobody.
 */
@Entity
@Table(name = "C8_UNSERVED_RENAME_AGGREGATE")
@Getter
@Setter
public class UnservedRenameDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long orderId;

}
