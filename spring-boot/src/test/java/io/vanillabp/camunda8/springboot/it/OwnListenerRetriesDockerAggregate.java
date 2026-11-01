package io.vanillabp.camunda8.springboot.it;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the test about the retries of the listeners VanillaBP writes into a
 * model. The id is a string, because one of its workflows is started by the cluster. There the
 * application names the workflow in its <code>&#64;WorkflowStartedByBpms</code> method.
 */
@Entity
@Table(name = "C8_OWN_LISTENER_RETRIES_AGGREGATE")
@Getter
@Setter
public class OwnListenerRetriesDockerAggregate {

  @Id
  private String id;

  /**
   * Set by the <code>&#64;WorkflowEnded</code> method once it does not fail any more.
   */
  private String endedAs;

}
