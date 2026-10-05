package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the secondary-storage integration test.
 * <p>
 * Its ID attribute is deliberately NOT named <code>id</code>: the
 * aggregate-ID process variable is named after it, so a probe deriving that name
 * from anywhere else searches for something the cluster does not have and reports
 * every workflow as unknown.
 */
@Entity
@Table(name = "C8_SECONDARY_STORAGE_AGGREGATE")
@Getter
@Setter
public class SecondaryStorageDockerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long loanRequestId;

  private String processedBy;

}
